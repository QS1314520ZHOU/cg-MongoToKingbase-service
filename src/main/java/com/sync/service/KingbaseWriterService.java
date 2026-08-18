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
     * 创建表
     */
    public void createTable(String tableName, Map<String, String> columns) {
        try {
            StringBuilder sql = new StringBuilder();
            sql.append("CREATE TABLE IF NOT EXISTS ").append(tableName).append(" (\n");
            sql.append("    id SERIAL PRIMARY KEY,\n");

            List<String> columnDefs = new ArrayList<>();
            for (Map.Entry<String, String> entry : columns.entrySet()) {
                String colName = entry.getKey().replaceAll("[^a-zA-Z0-9_]", "_");
                // 处理SQL保留关键字
                colName = escapeReservedKeyword(colName);
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
            StringBuilder sql = new StringBuilder();
            sql.append("INSERT INTO ").append(tableName).append(" (");

            List<String> sanitizedColumns = new ArrayList<>();
            for (String col : columns) {
                String sanitized = col.replaceAll("[^a-zA-Z0-9_]", "_");
                sanitizedColumns.add(escapeReservedKeyword(sanitized));
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
            // 准备列名（排除mongo_id）
            List<String> sanitizedColumns = new ArrayList<>();
            for (String col : columns) {
                if (!"mongo_id".equals(col)) {
                    sanitizedColumns.add(col.replaceAll("[^a-zA-Z0-9_]", "_"));
                }
            }

            for (Document doc : documents) {
                StringBuilder sql = new StringBuilder();
                sql.append("UPDATE ").append(tableName).append(" SET ");

                List<Object> params = new ArrayList<>();
                List<String> setClauses = new ArrayList<>();

                for (String col : sanitizedColumns) {
                    setClauses.add(col + " = ?");
                    params.add(extractValue(doc, col.replace("_", ".")));
                }

                // 添加同步时间字段
                setClauses.add(syncFieldName + " = ?");
                params.add(new java.sql.Timestamp(System.currentTimeMillis()));

                sql.append(String.join(", ", setClauses));
                sql.append(" WHERE mongo_id = ?");

                params.add(doc.getObjectId("_id").toString());

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
     */
    public int batchUpsert(String tableName, List<Document> documents,
                            List<String> columns, String syncFieldName) {
        if (documents.isEmpty()) {
            return 0;
        }

        int totalAffected = 0;
        try {
            for (Document doc : documents) {
                String mongoId = doc.getObjectId("_id").toString();

                // 检查是否已存在
                String checkSql = "SELECT COUNT(*) FROM " + tableName + " WHERE mongo_id = ?";
                Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, mongoId);

                if (count != null && count > 0) {
                    // 存在则更新
                    List<String> sanitizedColumns = new ArrayList<>();
                    for (String col : columns) {
                        if (!"mongo_id".equals(col)) {
                            sanitizedColumns.add(col.replaceAll("[^a-zA-Z0-9_]", "_"));
                        }
                    }

                    StringBuilder updateSql = new StringBuilder();
                    updateSql.append("UPDATE ").append(tableName).append(" SET ");

                    List<Object> params = new ArrayList<>();
                    List<String> setClauses = new ArrayList<>();

                    for (String col : sanitizedColumns) {
                        setClauses.add(col + " = ?");
                        params.add(extractValue(doc, col.replace("_", ".")));
                    }

                    // 添加同步时间字段
                    setClauses.add(syncFieldName + " = ?");
                    params.add(new java.sql.Timestamp(System.currentTimeMillis()));

                    updateSql.append(String.join(", ", setClauses));
                    updateSql.append(" WHERE mongo_id = ?");

                    params.add(mongoId);

                    totalAffected += jdbcTemplate.update(updateSql.toString(), params.toArray());
                } else {
                    // 不存在则插入
                    List<String> sanitizedColumns = new ArrayList<>();
                    for (String col : columns) {
                        sanitizedColumns.add(col.replaceAll("[^a-zA-Z0-9_]", "_"));
                    }

                    StringBuilder insertSql = new StringBuilder();
                    insertSql.append("INSERT INTO ").append(tableName).append(" (");
                    insertSql.append(String.join(", ", sanitizedColumns));
                    insertSql.append(") VALUES ");

                    List<Object> params = new ArrayList<>();
                    List<String> placeholders = new ArrayList<>();

                    for (String col : columns) {
                        placeholders.add("?");
                        params.add(extractValue(doc, col));
                    }

                    insertSql.append("(").append(String.join(", ", placeholders)).append(")");

                    totalAffected += jdbcTemplate.update(insertSql.toString(), params.toArray());
                }
            }

            logger.debug("Upserted {} rows in {}", totalAffected, tableName);
        } catch (Exception e) {
            logger.error("Failed to batch upsert in {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
        return totalAffected;
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
                current = ((Document) current).get(part);
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
     * 根据mongo_id获取哈希值
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
     * 批量插入数据（忽略重复的mongo_id）
     */
    public int batchInsertIgnoreDuplicate(String tableName, List<Document> documents, List<String> columns) {
        if (documents.isEmpty()) {
            return 0;
        }

        int insertedCount = 0;
        try {
            for (Document doc : documents) {
                try {
                    StringBuilder sql = new StringBuilder();
                    sql.append("INSERT INTO ").append(tableName).append(" (");

                    List<String> sanitizedColumns = new ArrayList<>();
                    for (String col : columns) {
                        String sanitized = col.replaceAll("[^a-zA-Z0-9_]", "_");
                        sanitizedColumns.add(escapeReservedKeyword(sanitized));
                    }
                    sql.append(String.join(", ", sanitizedColumns));
                    sql.append(") VALUES ");

                    List<Object> params = new ArrayList<>();
                    List<String> placeholders = new ArrayList<>();

                    for (String col : columns) {
                        placeholders.add("?");
                        params.add(extractValue(doc, col));
                    }

                    sql.append("(").append(String.join(", ", placeholders)).append(")");
                    sql.append(" ON CONFLICT (mongo_id) DO NOTHING");

                    int affected = jdbcTemplate.update(sql.toString(), params.toArray());
                    insertedCount += affected;
                } catch (Exception e) {
                    logger.warn("Failed to insert document {}: {}", doc.getObjectId("_id"), e.getMessage());
                }
            }

            logger.debug("Inserted {} rows into {}", insertedCount, tableName);
        } catch (Exception e) {
            logger.error("Failed to batch insert into {}: {}", tableName, e.getMessage(), e);
            throw e;
        }
        return insertedCount;
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
