package com.sync.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class KingbaseWriterService {

    private static final Logger logger = LoggerFactory.getLogger(KingbaseWriterService.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * PostgreSQL JDBC 驱动绑定参数个数上限（按 2 字节发送）。
     * 服务端是 65535，驱动侧更严，取小值。
     */
    private static final int MAX_BIND_PARAMS = 32767;

    /** 已确认用不了 ON CONFLICT（缺 UNIQUE(mongo_id)）的表，之后都走逐行回退 */
    private final Set<String> noFastUpsertTables = Collections.synchronizedSet(new HashSet<String>());

    /**
     * 检查表是否存在
     */
    public boolean tableExists(String tableName) {
        try {
            Connection connection = jdbcTemplate.getDataSource().getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet rs = metaData.getTables(null, null, tableName.toLowerCase(), new String[]{"TABLE"});
            boolean exists = rs.next();
            rs.close();
            connection.close();
            return exists;
        } catch (SQLException e) {
            logger.error("Failed to check table existence: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 检查表是否有数据
     */
    public boolean isTableEmpty(String tableName) {
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName;
            Long count = jdbcTemplate.queryForObject(sql, Long.class);
            return count == null || count == 0;
        } catch (Exception e) {
            logger.error("Failed to check if table is empty: {}", e.getMessage(), e);
            return true; // 出错时视为空表
        }
    }

    /**
     * 确保表中存在所有需要的列
     * 如果列不存在，则添加列
     */
    public void ensureColumnsExist(String tableName, List<String> columns) {
        try {
            // 获取现有列
            Map<String, String> existingColumns = getTableColumns(tableName);

            for (String col : columns) {
                String sanitizedRaw = col.replaceAll("[^a-zA-Z0-9_]", "_");
                String sanitizedCol = sanitizedRaw.toLowerCase();
                // 保留字列建表时被加了双引号，PG 会保留原始大小写（如 "Order"），
                // 只查小写会误判为"列不存在"→重复 ADD COLUMN，故两种写法都要查
                if (!existingColumns.containsKey(sanitizedCol) && !existingColumns.containsKey(sanitizedRaw)) {
                    // 列不存在，添加列
                    String colType = inferColumnType(sanitizedCol);

                    String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + escapeReservedKeyword(sanitizedCol) + " " + colType;
                    logger.info("Adding missing column {} to {} to table {}", sanitizedCol, colType, tableName);
                    jdbcTemplate.execute(sql);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to ensure columns exist: {}", e.getMessage());
        }
    }

    /**
     * 根据列名推断 Kingbase 列类型（仅用于动态新增列）。
     *
     * 【教训 / 必须遵守】旧实现用 {@code contains("count")} 判断 INTEGER，
     * 结果 {@code accountId} 含子串 "count"（ac-count-id）、{@code cancelAccountName} 同理，
     * 被建成 INTEGER，写入 ObjectId / 中文姓名时报
     * {@code invalid input syntax for type integer: "6a1f9f01..."}。
     * 实测导致 nurserecords / nurserecords_copy1 两表 6 个列写不进数据。
     *
     * 【原则】TEXT 是唯一安全兜底（任何值都能装），所以：
     *   ① 判定用 endsWith 而不是 contains，避免子串误命中；
     *   ② id / name / code / no 这类「标识·名称·编码」后缀一律 TEXT，绝不猜成数字；
     *   ③ 布尔判定放在标识判定之前，否则 {@code valid} 会因为 endsWith("id") 被误判成 TEXT。
     */
    private String inferColumnType(String col) {
        String compact = col == null ? "" : col.toLowerCase().replace("_", "");
        if (compact.isEmpty()) {
            return "TEXT";
        }
        // 1. 布尔（必须先判，"valid" 尾部的 "id" 会撞上第 2 条）
        if (compact.endsWith("valid") || compact.endsWith("flag")
                || compact.endsWith("enabled") || compact.endsWith("deleted")
                || compact.endsWith("boolean") || compact.endsWith("bool")) {
            return "BOOLEAN";
        }
        // 2. 标识 / 名称 / 编码 → 严格 TEXT
        if (compact.endsWith("id") || compact.endsWith("name") || compact.endsWith("code")
                || compact.endsWith("no") || compact.endsWith("key") || compact.endsWith("uuid")
                || compact.endsWith("url") || compact.endsWith("path")) {
            return "TEXT";
        }
        // 3. 时间
        if (compact.endsWith("time") || compact.endsWith("date")) {
            return "TIMESTAMP";
        }
        // 4. 计数 / 序号（用 endsWith，"accountId" 不会命中）
        if (compact.endsWith("count") || compact.endsWith("times") || compact.endsWith("num")) {
            return "INTEGER";
        }
        // 5. 其余一律 TEXT 兜底：宁愿存成字符串，也不要建出一个写不进去的数字列
        return "TEXT";
    }

    /**
     * 列名去重：sanitize后相同的列只保留第一个（防重复列导致SQL语法错误）。
     * 不同Mongo字段名sanitize后可能坍缩同名（如嵌套路径/特殊字符），必须去重。
     */
    private List<String> dedupeColumns(List<String> columns) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String col : columns) {
            String sanitized = col.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            if (seen.add(sanitized)) {
                result.add(col);
            } else {
                logger.warn("Duplicated column after sanitize, keeping first occurrence only: {}", col);
            }
        }
        return result;
    }

    /**
     * 创建表
     */
    public void createTable(String tableName, Map<String, String> columns) {
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
            sql.append("    id SERIAL PRIMARY KEY,\n");

            Set<String> seenCols = new LinkedHashSet<>();
            List<String> columnDefs = new ArrayList<>();
            for (Map.Entry<String, String> entry : columns.entrySet()) {
                String colName = entry.getKey().replaceAll("[^a-zA-Z0-9_]", "_");
                // 处理SQL保留关键字
                colName = escapeReservedKeyword(colName);
                // 去重：sanitize后同名列只建一次（否则 CREATE TABLE 报 column specified more than once）
                if (!seenCols.add(colName.toLowerCase())) {
                    logger.warn("createTable {}: duplicated column {} after sanitize, skipped", tableName, colName);
                    continue;
                }
                String colType = mapMongoTypeToKingbase(entry.getValue());
                columnDefs.add("    " + colName + " " + colType);
            }

            // 添加mongo_id唯一约束（使用表名作为前缀，确保约束名唯一）
            String constraintName = "uk_" + tableName.toLowerCase() + "_mongo_id";
            columnDefs.add("    CONSTRAINT " + constraintName + " UNIQUE (mongo_id)");

            sql.append(String.join(",\n", columnDefs));
            sql.append("\n)");

            logger.info("Creating table: {}", tableName);
            logger.debug("SQL: {}", sql.toString());

            jdbcTemplate.execute(sql.toString());
            logger.info("Table {} created successfully", tableName);
        } catch (Exception e) {
            logger.error("Failed to create table {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 转义SQL保留关键字
     */
    private String escapeReservedKeyword(String columnName) {
        // SQL保留关键字列表
        java.util.Set<String> reservedKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
            "desc", "select", "insert", "update", "delete", "from", "where",
            "order", "group", "by", "having", "limit", "offset", "as",
            "and", "or", "not", "in", "between", "like", "is", "null",
            "true", "false", "case", "when", "then", "else", "end",
            "join", "left", "right", "inner", "outer", "on", "using",
            "union", "all", "distinct", "exists", "any", "some",
            "create", "alter", "drop", "table", "index", "view",
            "primary", "key", "foreign", "references", "constraint",
            "check", "default", "auto_increment", "unique",
            "grant", "revoke", "commit", "rollback", "transaction",
            "begin", "savepoint", "release",
            "user", "role", "password", "grant", "revoke",
            "database", "schema", "tablespace",
            "varchar", "char", "text", "integer", "int", "bigint",
            "smallint", "decimal", "numeric", "float", "double",
            "date", "time", "timestamp", "interval",
            "boolean", "bit", "bytea", "json", "jsonb",
            "array", "enum", "set", "blob", "clob",
            "current_date", "current_time", "current_timestamp",
            "now", "extract", "date_part", "date_trunc",
            "coalesce", "nullif", "greatest", "least",
            "count", "sum", "avg", "min", "max",
            "abs", "ceil", "floor", "round", "trunc",
            "length", "upper", "lower", "trim", "ltrim", "rtrim",
            "substring", "replace", "position", "strpos",
            "concat", "concat_ws", "repeat", "reverse", "left", "right",
            "initcap", "md5", "sha256", "sha512",
            "to_char", "to_date", "to_number", "to_timestamp",
            "age", "justify_days", "justify_hours", "justify_interval",
            "make_date", "make_time", "make_timestamp",
            "clock_timestamp", "statement_timestamp", "timeofday",
            "transaction_timestamp", "localtime", "localtimestamp",
            "isfinite", "isunknown",
            "overlay", "bit_length", "octet_length",
            "convert", "transcode",
            "normalize", "is_normalized",
            "similar", "regexp_match", "regexp_matches",
            "regexp_replace", "regexp_split_to_array", "regexp_split_to_table",
            "string_agg", "array_agg", "json_agg", "jsonb_agg",
            "json_object_agg", "jsonb_object_agg",
            "xmlagg", "xml_is_well_formed", "xml_is_well_formed_content",
            "xpath", "xpath_exists", "xmltable", "xmlcolumn",
            "xmlcomment", "xmlconcat", "xmlelement", "xmlexists",
            "xmlforest", "xmlparse", "xmlpi", "xmlroot",
            "xmlserialize", "xmlvalidate",
            "cube", "rollup", "grouping",
            "lateral", "with", "recursive",
            "fetch", "first", "next", "row", "rows",
            "only", "for", "no", "key", "share", "update",
            "skip", "locked",
            "returning", "conflict", "nothing", "do",
            "window", "over", "partition", "range", "current",
            "preceding", "following", "unbounded", "exclude",
            "ties", "dense", "rank", "percent", "cont",
            "grouping", "sets", "cube", "rollup",
            "filter", "within", "ties", "both", "leading", "trailing"
        ));

        if (reservedKeywords.contains(columnName.toLowerCase())) {
            return "\"" + columnName + "\"";
        }
        return columnName;
    }

    /**
     * 列名规范化：sanitize（仅保留字母/数字/下划线）+ 保留字加双引号。
     * 【强制】所有拼接列名的 SQL 路径（INSERT / UPDATE / UPSERT / DDL）必须统一走这里。
     * 否则保留字列（如 order / desc / group）在建表时被加引号、写数据时却没加，
     * 会表现为"表建得出来、数据却写不进去"的 syntax error，且只在增量/更新路径暴露。
     */
    private String normalizeColumnName(String columnName) {
        return escapeReservedKeyword(columnName.replaceAll("[^a-zA-Z0-9_]", "_"));
    }

    /**
     * 删除表
     */
    public void dropTable(String tableName) {
        try {
            String sql = "DROP TABLE IF EXISTS " + tableName;
            logger.info("Dropping table: {}", tableName);
            jdbcTemplate.execute(sql);
            logger.info("Table {} dropped successfully", tableName);
        } catch (Exception e) {
            logger.error("Failed to drop table {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 批量插入数据
     */
    public int batchInsert(String tableName, List<Document> documents, List<String> columns) {
        if (documents.isEmpty()) {
            return 0;
        }

        try {
            columns = dedupeColumns(columns);
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(tableName).append(" (");

            List<String> sanitizedColumns = new ArrayList<>();
            for (String col : columns) {
                sanitizedColumns.add(normalizeColumnName(col));
            }
            sql.append(String.join(", ", sanitizedColumns));
            sql.append(") VALUES ");

            List<Object> params = new ArrayList<>();
            List<String> valuePlaceholders = new ArrayList<>();

            for (Document doc : documents) {
                List<String> singleRowPlaceholders = new ArrayList<>();
                for (String col : columns) {
                    singleRowPlaceholders.add("?");
                    params.add(extractValue(doc, col));
                }
                valuePlaceholders.add("(" + String.join(", ", singleRowPlaceholders) + ")");
            }

            sql.append(String.join(", ", valuePlaceholders));

            jdbcTemplate.update(sql.toString(), params.toArray());
            logger.debug("Inserted {} rows into {}", documents.size(), tableName);
            return documents.size();
        } catch (Exception e) {
            logger.error("Failed to batch insert into {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 批量更新数据（根据mongo_id）
     */
    public int batchUpdateByMongoId(String tableName, List<Document> documents,
                                     List<String> columns, String syncFieldName) {
        if (documents.isEmpty()) {
            return 0;
        }

        int totalUpdated = 0;
        try {
            columns = dedupeColumns(columns);
            // 准备列名（排除mongo_id）
            List<String> sanitizedColumns = new ArrayList<>();
            for (String col : columns) {
                if (!"mongo_id".equals(col)) {
                    sanitizedColumns.add(normalizeColumnName(col));
                }
            }

            for (Document doc : documents) {
                StringBuilder sql = new StringBuilder();
                sql.append("UPDATE ").append(tableName).append(" SET ");

                List<Object> params = new ArrayList<>();
                List<String> setClauses = new ArrayList<>();

                for (String col : sanitizedColumns) {
                    setClauses.add(col + " = ?");
                    params.add(extractValue(doc, col));
                }

                // 添加同步时间字段
                setClauses.add(syncFieldName + " = ?");
                params.add(new java.sql.Timestamp(System.currentTimeMillis()));

                sql.append(String.join(", ", setClauses));
                sql.append(" WHERE mongo_id = ?");

                // 处理不同类型的_id字段（ObjectId或String）
                Object idValue = doc.get("_id");
                String mongoId;
                if (idValue instanceof org.bson.types.ObjectId) {
                    mongoId = ((org.bson.types.ObjectId) idValue).toString();
                } else if (idValue != null) {
                    mongoId = idValue.toString();
                } else {
                    mongoId = java.util.UUID.randomUUID().toString();
                }
                params.add(mongoId);

                int updated = jdbcTemplate.update(sql.toString(), params.toArray());
                totalUpdated += updated;
            }

            logger.debug("Updated {} rows in {}", totalUpdated, tableName);
        } catch (Exception e) {
            logger.error("Failed to batch update in {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
        return totalUpdated;
    }

    /**
     * 批量Upsert数据（存在则更新，不存在则插入）
     *
     * 【性能】旧实现是「逐行 SELECT COUNT(*) + UPDATE/INSERT」，N 行 = 2N 次网络往返，
     * 生产实测 2000 行要 ~60 秒。现改为多行「INSERT ... ON CONFLICT (mongo_id) DO UPDATE」，
     * 每块只 1 次往返，实测 2000 行 ~0.2 秒。
     *
     * 【重要】PostgreSQL JDBC 驱动把绑定参数个数按 2 字节发送，上限 32767
     * （不是服务端的 65535）。超过会抛
     * "Tried to send an out-of-range integer as a 2-byte value"，
     * 因此必须按列数动态切块，绝不能整批塞进一条语句。
     */
    public int batchUpsert(String tableName, List<Document> documents,
                            List<String> columns, String syncFieldName) {
        if (documents.isEmpty()) {
            return 0;
        }
        try {
            columns = dedupeColumns(columns);
            // 确保所有列都存在
            ensureColumnsExist(tableName, columns);

            List<String> quotedColumns = new ArrayList<>(columns.size());
            for (String col : columns) {
                quotedColumns.add(normalizeColumnName(col));
            }
            String columnList = String.join(", ", quotedColumns);
            List<String> setClauses = new ArrayList<>(quotedColumns.size());
            for (String qc : quotedColumns) {
                setClauses.add(qc + " = EXCLUDED." + qc);
            }
            String updateClause = String.join(", ", setClauses);

            int chunkSize = upsertChunkSize(quotedColumns.size());
            int totalAffected = 0;

            for (int start = 0; start < documents.size(); start += chunkSize) {
                int end = Math.min(documents.size(), start + chunkSize);
                List<Document> slice = documents.subList(start, end);

                if (noFastUpsertTables.contains(tableName)) {
                    // 该表已确认用不了 ON CONFLICT，本表以后都走逐行方式
                    totalAffected += perRowUpsert(tableName, slice, columns, syncFieldName);
                    continue;
                }

                try {
                    totalAffected += executeMultiRowUpsert(tableName, slice, columns, columnList, updateClause);
                } catch (Exception e) {
                    if (isMissingUniqueConstraint(e)) {
                        noFastUpsertTables.add(tableName);
                        logger.warn("Table {} has no UNIQUE(mongo_id) usable by ON CONFLICT, "
                                + "falling back to per-row upsert for this table", tableName);
                    } else {
                        logger.error("Multi-row upsert failed on {} ({} rows), falling back to per-row: {}",
                                tableName, slice.size(), e.getMessage());
                    }
                    totalAffected += perRowUpsert(tableName, slice, columns, syncFieldName);
                }
            }

            logger.debug("Upserted {} rows in {} (chunkSize={})", totalAffected, tableName, chunkSize);
            return totalAffected;
        } catch (Exception e) {
            logger.error("Failed to batch upsert in {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 多行 INSERT ... ON CONFLICT (mongo_id) DO UPDATE，一次网络往返写完整个 chunk。
     */
    private int executeMultiRowUpsert(String tableName, List<Document> docs, List<String> columns,
                                       String columnList, String updateClause) {
        StringBuilder sql = new StringBuilder(256 + docs.size() * columns.size() * 3);
        sql.append("INSERT INTO ").append(tableName).append(" (").append(columnList).append(") VALUES ");

        List<Object> params = new ArrayList<>(docs.size() * columns.size());
        for (int r = 0; r < docs.size(); r++) {
            if (r > 0) {
                sql.append(", ");
            }
            sql.append("(");
            Document doc = docs.get(r);
            for (int k = 0; k < columns.size(); k++) {
                if (k > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(columnValue(doc, columns.get(k)));
            }
            sql.append(")");
        }

        sql.append(" ON CONFLICT (mongo_id) DO UPDATE SET ").append(updateClause);
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    /**
     * 逐行 upsert（回退路径）：仅用于没有 UNIQUE(mongo_id)、用不了 ON CONFLICT 的表。
     * 单行失败只记 ERROR 并继续，不让一行脏数据冻结整张表。
     */
    private int perRowUpsert(String tableName, List<Document> documents,
                              List<String> columns, String syncFieldName) {
        int totalAffected = 0;
        for (Document doc : documents) {
            String mongoId = String.valueOf(columnValue(doc, "mongo_id"));
            try {
                Integer count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM " + tableName + " WHERE mongo_id = ?", Integer.class, mongoId);

                if (count != null && count > 0) {
                    List<String> setClauses = new ArrayList<>();
                    List<Object> params = new ArrayList<>();
                    boolean hasSyncField = false;
                    for (String col : columns) {
                        if ("mongo_id".equals(col)) {
                            continue;
                        }
                        if (col.equalsIgnoreCase(syncFieldName)) {
                            hasSyncField = true;
                        }
                        setClauses.add(normalizeColumnName(col) + " = ?");
                        params.add(extractValue(doc, col));
                    }
                    if (!hasSyncField) {
                        setClauses.add(normalizeColumnName(syncFieldName) + " = ?");
                        params.add(new java.sql.Timestamp(System.currentTimeMillis()));
                    }
                    params.add(mongoId);
                    totalAffected += jdbcTemplate.update(
                            "UPDATE " + tableName + " SET " + String.join(", ", setClauses)
                                    + " WHERE mongo_id = ?", params.toArray());
                } else {
                    List<String> quotedColumns = new ArrayList<>(columns.size());
                    List<Object> params = new ArrayList<>(columns.size());
                    for (String col : columns) {
                        quotedColumns.add(normalizeColumnName(col));
                        params.add(columnValue(doc, col));
                    }
                    totalAffected += jdbcTemplate.update(
                            "INSERT INTO " + tableName + " (" + String.join(", ", quotedColumns)
                                    + ") VALUES (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")",
                            params.toArray());
                }
            } catch (Exception e) {
                logger.error("Failed to upsert document {} into {}: {}", mongoId, tableName, e.getMessage());
            }
        }
        return totalAffected;
    }

    /**
     * 取列值。mongo_id 为空时用 _id 兜底——否则 ON CONFLICT 永不命中，
     * 每轮同步都会写出重复行。
     */
    private Object columnValue(Document doc, String column) {
        Object value = extractValue(doc, column);
        if (value == null && "mongo_id".equalsIgnoreCase(column)) {
            Object idValue = doc.get("_id");
            if (idValue instanceof org.bson.types.ObjectId) {
                return ((org.bson.types.ObjectId) idValue).toString();
            } else if (idValue != null) {
                return idValue.toString();
            }
            return java.util.UUID.randomUUID().toString();
        }
        return value;
    }

    /** 每块最大行数：保证 rows * cols 不超过 JDBC 驱动 32767 的绑定参数上限 */
    private int upsertChunkSize(int columnCount) {
        int byParams = MAX_BIND_PARAMS / Math.max(1, columnCount);
        return Math.max(1, Math.min(1000, byParams));
    }

    private boolean isMissingUniqueConstraint(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("no unique or exclusion constraint matching")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 获取表的列信息
     */
    public Map<String, String> getTableColumns(String tableName) {
        Map<String, String> columns = new LinkedHashMap<>();
        try {
            Connection connection = jdbcTemplate.getDataSource().getConnection();
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet rs = metaData.getColumns(null, null, tableName.toLowerCase(), null);

            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                String typeName = rs.getString("TYPE_NAME");
                columns.put(columnName, typeName);
            }

            rs.close();
            connection.close();
        } catch (SQLException e) {
            logger.error("Failed to get table columns: {}", e.getMessage(), e);
        }
        return columns;
    }

    /**
     * 提取文档中的值
     */
    private Object extractValue(Document doc, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        Object current = doc;

        for (String part : parts) {
            if (current instanceof Document) {
                Document docObj = (Document) current;
                // 先尝试直接获取
                current = docObj.get(part);
                // 如果找不到，尝试大小写不敏感匹配
                if (current == null) {
                    for (String key : docObj.keySet()) {
                        if (key.equalsIgnoreCase(part)) {
                            current = docObj.get(key);
                            break;
                        }
                    }
                }
            } else {
                return null;
            }
        }

        if (current == null) {
            return null;
        }

        if (current instanceof Date) {
            return new java.sql.Timestamp(((Date) current).getTime());
        } else if (current instanceof org.bson.types.ObjectId) {
            return current.toString();
        } else if (current instanceof org.bson.types.Decimal128) {
            return ((org.bson.types.Decimal128) current).bigDecimalValue();
        } else if (current instanceof org.bson.types.Binary) {
            // GridFS二进制数据转换为byte[]，映射到Kingbase的bytea类型
            return ((org.bson.types.Binary) current).getData();
        } else if (current instanceof List) {
            return convertListToJson((List<?>) current);
        } else if (current instanceof Document) {
            return convertDocumentToJson((Document) current);
        }

        return current;
    }

    /**
     * 将Document转换为JSON字符串
     */
    private String convertDocumentToJson(Document doc) {
        try {
            // 移除_id字段，避免重复
            Map<String, Object> map = new LinkedHashMap<>(doc);
            map.remove("_id");
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            logger.warn("Failed to convert Document to JSON: {}", e.getMessage());
            return doc.toJson();
        }
    }

    /**
     * 将List转换为JSON字符串
     */
    private String convertListToJson(List<?> list) {
        try {
            // 递归处理列表中的Document
            List<Object> processedList = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Document) {
                    // 将Document转换为Map
                    Map<String, Object> map = new LinkedHashMap<>((Document) item);
                    map.remove("_id");
                    processedList.add(map);
                } else {
                    processedList.add(item);
                }
            }
            return objectMapper.writeValueAsString(processedList);
        } catch (Exception e) {
            logger.warn("Failed to convert List to JSON: {}", e.getMessage());
            // 降级处理
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Object item = list.get(i);
                if (item instanceof Document) {
                    sb.append(convertDocumentToJson((Document) item));
                } else {
                    try {
                        sb.append(objectMapper.writeValueAsString(item));
                    } catch (Exception ex) {
                        sb.append("\"").append(item.toString().replace("\"", "\\\"")).append("\"");
                    }
                }
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /**
     * 映射MongoDB类型到Kingbase类型
     */
    private String mapMongoTypeToKingbase(String mongoType) {
        switch (mongoType.toLowerCase()) {
            case "string":
                return "TEXT";
            case "integer":
                return "INTEGER";
            case "long":
                return "BIGINT";
            case "double":
            case "decimal128":
                return "NUMERIC";
            case "boolean":
                return "BOOLEAN";
            case "date":
                return "TIMESTAMP";
            case "objectid":
                return "VARCHAR(24)";
            case "array":
            case "array<document>":
                return "JSONB";
            case "null":
                return "TEXT";
            default:
                return "TEXT";
        }
    }

    /**
     * 获取JdbcTemplate实例
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * 根据mongo_id获取哈希值（单条，保留兼容）
     */
    public String getHashByMongoId(String tableName, String mongoId) {
        try {
            String sql = "SELECT _data_hash FROM " + tableName + " WHERE mongo_id = ?";
            return jdbcTemplate.queryForObject(sql, String.class, mongoId);
        } catch (Exception e) {
            // 记录不存在或查询失败
            return null;
        }
    }

    /**
     * 批量根据mongo_id获取哈希值（一次 IN 查询，避免逐行查询的 N+1 性能问题）。
     * 表不存在 / 无 _data_hash 列 / 查询异常时返回空 Map，
     * 调用方据此会把全部文档判定为「已变化」并执行 upsert，属安全降级。
     */
    public Map<String, String> getHashByMongoIds(String tableName, List<String> mongoIds) {
        Map<String, String> result = new HashMap<>();
        if (mongoIds == null || mongoIds.isEmpty()) {
            return result;
        }
        try {
            StringBuilder sql = new StringBuilder("SELECT mongo_id, _data_hash FROM ")
                .append(tableName).append(" WHERE mongo_id IN (");
            for (int i = 0; i < mongoIds.size(); i++) {
                if (i > 0) {
                    sql.append(",");
                }
                sql.append("?");
            }
            sql.append(")");

            jdbcTemplate.query(sql.toString(), rs -> {
                result.put(rs.getString("mongo_id"), rs.getString("_data_hash"));
            }, mongoIds.toArray());
        } catch (Exception e) {
            logger.warn("Failed to batch-load hashes from {}: {}", tableName, e.getMessage());
        }
        return result;
    }

    /**
     * 批量插入数据（忽略重复的mongo_id）
     *
     * 多行 INSERT ... ON CONFLICT (mongo_id) DO NOTHING：每块 1 次往返，
     * 而不是旧实现的逐行一次。注意 ON CONFLICT DO NOTHING 不带冲突目标时
     * 不需要唯一索引也合法，所以这里无需判断约束。
     */
    public int batchInsertIgnoreDuplicate(String tableName, List<Document> documents, List<String> columns) {
        if (documents.isEmpty()) {
            return 0;
        }

        try {
            columns = dedupeColumns(columns);
            // 确保所有列都存在
            ensureColumnsExist(tableName, columns);

            List<String> quotedColumns = new ArrayList<>(columns.size());
            for (String col : columns) {
                quotedColumns.add(normalizeColumnName(col));
            }
            String columnList = String.join(", ", quotedColumns);
            int chunkSize = upsertChunkSize(quotedColumns.size());

            int insertedCount = 0;
            for (int start = 0; start < documents.size(); start += chunkSize) {
                int end = Math.min(documents.size(), start + chunkSize);
                List<Document> slice = documents.subList(start, end);
                try {
                    insertedCount += executeMultiRowInsertIgnore(tableName, slice, columns, columnList);
                } catch (Exception e) {
                    logger.error("Multi-row insert failed on {} ({} rows), falling back to per-row: {}",
                            tableName, slice.size(), e.getMessage());
                    insertedCount += perRowInsertIgnore(tableName, slice, columns, columnList);
                }
            }

            logger.debug("Inserted {} rows into {} (chunkSize={})", insertedCount, tableName, chunkSize);
            return insertedCount;
        } catch (Exception e) {
            logger.error("Failed to batch insert into {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
    }

    private int executeMultiRowInsertIgnore(String tableName, List<Document> docs,
                                             List<String> columns, String columnList) {
        StringBuilder sql = new StringBuilder(256 + docs.size() * columns.size() * 3);
        sql.append("INSERT INTO ").append(tableName).append(" (").append(columnList).append(") VALUES ");

        List<Object> params = new ArrayList<>(docs.size() * columns.size());
        for (int r = 0; r < docs.size(); r++) {
            if (r > 0) {
                sql.append(", ");
            }
            sql.append("(");
            Document doc = docs.get(r);
            for (int k = 0; k < columns.size(); k++) {
                if (k > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                params.add(columnValue(doc, columns.get(k)));
            }
            sql.append(")");
        }

        sql.append(" ON CONFLICT (mongo_id) DO NOTHING");
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    private int perRowInsertIgnore(String tableName, List<Document> docs,
                                    List<String> columns, String columnList) {
        int inserted = 0;
        for (Document doc : docs) {
            try {
                List<Object> params = new ArrayList<>(columns.size());
                for (String col : columns) {
                    params.add(columnValue(doc, col));
                }
                inserted += jdbcTemplate.update(
                        "INSERT INTO " + tableName + " (" + columnList + ") VALUES ("
                                + String.join(", ", Collections.nCopies(columns.size(), "?"))
                                + ") ON CONFLICT (mongo_id) DO NOTHING",
                        params.toArray());
            } catch (Exception e) {
                Object idValue = doc.get("_id");
                logger.warn("Failed to insert document {}: {}",
                        idValue != null ? idValue.toString() : "unknown", e.getMessage());
            }
        }
        return inserted;
    }

    /**
     * 获取Kingbase中的表列表
     */
    public List<String> getAllTables() {
        List<String> tables = new ArrayList<>();
        try {
            String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'";
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : result) {
                tables.add((String) row.get("table_name"));
            }
        } catch (Exception e) {
            logger.error("Failed to get tables: {}", e.getMessage(), e);
        }
        return tables;
    }

    /**
     * 获取表的行数
     */
    public long getRowCount(String tableName) {
        try {
            String sql = "SELECT COUNT(*) FROM " + tableName;
            return jdbcTemplate.queryForObject(sql, Long.class);
        } catch (Exception e) {
            logger.error("Failed to get row count for {}: {}", tableName, e.getMessage(), e);
            return 0;
        }
    }
}
