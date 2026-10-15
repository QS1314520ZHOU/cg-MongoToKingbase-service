package com.sync.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MongoReaderService {

    private static final Logger logger = LoggerFactory.getLogger(MongoReaderService.class);

    @Autowired
    private MongoDatabase mongoDatabase;

    // 已确保创建过索引的集合缓存，避免每批重复调用 createIndex
    private final Set<String> indexEnsured = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 校验「水位线字段」类型时的抽样条数：抽到任意一条非 Date 就判定不可用 */
    private static final int WATERMARK_TYPE_SAMPLE = 200;

    /** 「水位线字段不可用」的否定结论缓存（key = collection#field） */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> watermarkUnusableCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 获取所有集合名称
     */
    public List<String> getAllCollections() {
        List<String> collections = new ArrayList<>();
        try {
            MongoCursor<String> cursor = mongoDatabase.listCollectionNames().iterator();
            while (cursor.hasNext()) {
                collections.add(cursor.next());
            }
            logger.info("Found {} collections in MongoDB", collections.size());
        } catch (Exception e) {
            logger.error("Failed to get collections: {}", e.getMessage(), e);
        }
        return collections;
    }

    /**
     * 获取集合的文档数量
     */
    public long getDocumentCount(String collectionName) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            return collection.countDocuments();
        } catch (Exception e) {
            logger.error("Failed to get document count for {}: {}", collectionName, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 获取集合的字段结构
     */
    public Map<String, Object> getCollectionSchema(String collectionName) {
        Map<String, Object> schema = new LinkedHashMap<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

            // 采样文档来推断结构
            MongoCursor<Document> cursor = collection.find().limit(100).iterator();
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            Set<String> allFields = new LinkedHashSet<>();

            while (cursor.hasNext()) {
                Document doc = cursor.next();
                extractFieldTypes(doc, "", fieldTypes, allFields);
            }

            // 将字段名统一转换为小写，避免大小写重复
            Map<String, String> normalizedFieldTypes = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : fieldTypes.entrySet()) {
                normalizedFieldTypes.put(entry.getKey().toLowerCase(), entry.getValue());
            }

            schema.put("fields", normalizedFieldTypes);
            schema.put("fieldCount", normalizedFieldTypes.size());
            schema.put("sampleSize", Math.min(100, getDocumentCount(collectionName)));

            logger.info("Schema for {}: {} fields", collectionName, normalizedFieldTypes.size());
        } catch (Exception e) {
            logger.error("Failed to get schema for {}: {}", collectionName, e.getMessage(), e);
        }
        return schema;
    }

    /**
     * 递归提取字段类型
     */
    private void extractFieldTypes(Document doc, String prefix, Map<String, String> fieldTypes, Set<String> allFields) {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String fieldName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            allFields.add(fieldName);

            if (value == null) {
                fieldTypes.putIfAbsent(fieldName, "null");
            } else if (value instanceof Document) {
                // 嵌套Document不再递归展开子字段：整体作为object类型（建表为TEXT列，写入时序列化JSON）
                fieldTypes.putIfAbsent(fieldName, "object");
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (!list.isEmpty()) {
                    Object firstElement = list.get(0);
                    if (firstElement instanceof Document) {
                        // 嵌套Document数组不再递归展开：整体作为array<document>类型（TEXT列，写入时序列化JSON）
                        fieldTypes.put(fieldName, "array<document>");
                    } else {
                        fieldTypes.put(fieldName, "array<" + getJavaType(firstElement) + ">");
                    }
                } else {
                    fieldTypes.put(fieldName, "array");
                }
            } else {
                fieldTypes.putIfAbsent(fieldName, getJavaType(value));
            }
        }
    }

    /**
     * 获取Java类型
     */
    private String getJavaType(Object value) {
        if (value instanceof String) {
            return "String";
        } else if (value instanceof Integer) {
            return "Integer";
        } else if (value instanceof Long) {
            return "Long";
        } else if (value instanceof Double) {
            return "Double";
        } else if (value instanceof Boolean) {
            return "Boolean";
        } else if (value instanceof Date) {
            return "Date";
        } else if (value instanceof org.bson.types.ObjectId) {
            return "ObjectId";
        } else if (value instanceof org.bson.types.Decimal128) {
            return "Decimal128";
        } else if (value instanceof org.bson.types.Binary) {
            return "bytea";  // GridFS二进制数据映射到bytea类型
        } else {
            return value.getClass().getSimpleName();
        }
    }

    /**
     * 读取集合数据（分批）
     */
    public List<Document> readCollection(String collectionName, int batchSize, int skip) {
        List<Document> documents = new ArrayList<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            MongoCursor<Document> cursor = collection.find()
                .skip(skip)
                .limit(batchSize)
                .iterator();

            while (cursor.hasNext()) {
                documents.add(cursor.next());
            }
            logger.debug("Read {} documents from {} (skip: {}, limit: {})",
                documents.size(), collectionName, skip, batchSize);
        } catch (Exception e) {
            logger.error("Failed to read from {}: {}", collectionName, e.getMessage(), e);
        }
        return documents;
    }

    /**
     * 读取增量数据
     */
    public List<Document> readCollectionIncremental(String collectionName, int batchSize,
                                                     String timestampField, Date lastSyncTime) {
        List<Document> documents = new ArrayList<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

            Document query = new Document(timestampField, new Document("$gt", lastSyncTime));
            MongoCursor<Document> cursor = collection.find(query)
                .limit(batchSize)
                .iterator();

            while (cursor.hasNext()) {
                documents.add(cursor.next());
            }
            logger.debug("Read {} incremental documents from {} (since {})",
                documents.size(), collectionName, lastSyncTime);
        } catch (Exception e) {
            logger.error("Failed to read incremental from {}: {}", collectionName, e.getMessage(), e);
        }
        return documents;
    }

    /**
     * 根据editTime字段读取需要更新的数据（增量）
     * 按 (editTime asc, _id asc) 排序，配合 lastMongoId 做游标分页，
     * 确保同一批数据不会被重复读取，也不会因同一时间戳多条而漏读。
     *
     * @param lastSyncTime 上次同步的水位线时间（可null，null表示从头开始）
     * @param lastMongoId  上批最后一条文档的原始_id（可null），用于同一editTime的tie-breaker
     */
    public List<Document> readCollectionByEditTime(String collectionName, int batchSize,
                                                    String editTimeField, Date lastSyncTime,
                                                    Object lastMongoId) {
        List<Document> documents = new ArrayList<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

            // 确保 (editTime, _id) 复合索引存在，避免每批全表扫描排序（首轮追平积压时尤其重要）
            ensureEditTimeIndex(collectionName, editTimeField);

            Document query;
            if (lastSyncTime == null) {
                // 没有水位线，从头读
                query = new Document();
            } else if (lastMongoId == null) {
                query = new Document(editTimeField, new Document("$gt", lastSyncTime));
            } else {
                // 游标分页：(editTime > t) OR (editTime == t AND _id > lastId)
                query = new Document("$or", java.util.Arrays.asList(
                    new Document(editTimeField, new Document("$gt", lastSyncTime)),
                    new Document(editTimeField, new Document("$eq", lastSyncTime))
                        .append("_id", new Document("$gt", lastMongoId))
                ));
            }

            Document sortDoc = new Document(editTimeField, 1).append("_id", 1);
            MongoCursor<Document> cursor = collection.find(query)
                .sort(sortDoc)
                .limit(batchSize)
                .iterator();

            while (cursor.hasNext()) {
                documents.add(cursor.next());
            }
            logger.debug("Read {} documents with {} > {} from {} (lastId: {})",
                documents.size(), editTimeField, lastSyncTime, collectionName, lastMongoId);
        } catch (Exception e) {
            logger.error("Failed to read by editTime from {}: {}", collectionName, e.getMessage(), e);
        }
        return documents;
    }

    /**
     * 提取一批文档中的最大editTime及其对应_id
     * （结果集已按 editTime asc, _id asc 排序，故最大值即为最后一条）
     *
     * @param outLastId 长度>=1的数组，用于带回最后一条文档的原始_id；可为null
     * @return 本批最大editTime；若最后一条的editTime字段缺失或非Date类型则返回null
     */
    public static Date extractBatchMaxEditTime(List<Document> documents, String editTimeField,
                                               Object[] outLastId) {
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        Document lastDoc = documents.get(documents.size() - 1);
        Object value = lastDoc.get(editTimeField);
        if (value instanceof Date) {
            if (outLastId != null && outLastId.length > 0) {
                outLastId[0] = lastDoc.get("_id");
            }
            return (Date) value;
        }
        return null;
    }

    /**
     * 获取集合中最大的editTime值
     */
    public Date getMaxEditTime(String collectionName, String editTimeField) {
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

            Document sortDoc = new Document(editTimeField, -1);
            Document first = collection.find()
                .sort(sortDoc)
                .limit(1)
                .first();

            if (first != null && first.containsKey(editTimeField)) {
                Object value = first.get(editTimeField);
                if (value instanceof Date) {
                    return (Date) value;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get max editTime from {}: {}", collectionName, e.getMessage(), e);
        }
        return null;
    }

    /**
     * 幂等地为集合创建 (editTime, _id) 复合索引，加速增量同步的排序与游标分页查询。
     * 同一集合仅创建一次（由 indexEnsured 缓存）；索引已存在时 createIndex 为幂等操作，不会重建。
     * 任何异常都被吞掉，索引创建失败绝不影响同步主流程。
     */
    public void ensureEditTimeIndex(String collectionName, String editTimeField) {
        if (editTimeField == null || editTimeField.isEmpty()) {
            return;
        }
        String cacheKey = collectionName + "#" + editTimeField;
        if (indexEnsured.contains(cacheKey)) {
            return;
        }
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            Document indexKeys = new Document(editTimeField, 1).append("_id", 1);
            // 后台索引构建，避免锁表影响在线业务（后台构建在 MongoDB 4.2+ 已弃用前台参数，
            // 但旧版本仍可按 background:true 处理；即便不支持也只是忽略该选项）
            collection.createIndex(indexKeys,
                new com.mongodb.client.model.IndexOptions()
                    .name("idx_sync_editTime_id")
                    .background(true));
            logger.info("Ensured index idx_sync_editTime_id on {}({}, _id)", collectionName, editTimeField);
        } catch (Exception e) {
            logger.warn("Failed to create index on {} (sync continues): {}", collectionName, e.getMessage());
        } finally {
            indexEnsured.add(cacheKey);
        }
    }

    /**
     * 判断某个字段能否作为「增量水位线」使用。
     *
     * 不仅要字段存在，还要它的**值是 BSON 日期**。
     *
     * 【为什么必须查类型 / 血的教训】
     * 若 editTime 存的是字符串（例："2026-10-07T11:12:50.217830874"），
     * 增量查询 {@code {"editTime": {"$gt": <java.util.Date>}}} 会因为
     * MongoDB 的 BSON 类型排序是
     *   MinKey < Null < Number < String < Object < Array < BinData < ObjectId < Boolean < Date
     * 即 **String 恒小于 Date**，导致该查询**永远返回 0 条**。
     * 表现为：这张表从此再也同步不到任何数据，而且**全程没有任何报错**，
     * 只有对比行数才能发现。
     * 实测 2026-10-08：fallDangerFormExtra / selfCareFormExtra / ydwzl_form_extra 三张表中招。
     *
     * @return true 才能启用增量模式；任何异常/不确定都返回 false，
     *         退化为「全表读 + 哈希比对」，速度慢一些但结果永远正确。
     */
    public boolean isUsableWatermarkField(String collectionName, String field) {
        if (field == null || field.isEmpty()) {
            return false;
        }
        String cacheKey = collectionName + "#" + field;
        // 只缓存「不可用」的否定结论：
        //   ① 每 5 分钟一轮，否定的表没必要反复抽样复查；
        //   ② 更不会每轮都重复打同一条 WARN 把日志刷爆（3 张表 × 288 轮/天 ≈ 864 行）；
        //   ③ 缓存否定方向永远是安全的 —— 退化为哈希比对只是慢一点，结果一定正确。
        // 肯定结论不缓存，保留「源端类型某天变回 Date 就能自动切回增量模式」的能力。
        if (Boolean.TRUE.equals(watermarkUnusableCache.get(cacheKey))) {
            return false;
        }
        boolean usable = probeWatermarkField(collectionName, field);
        if (!usable) {
            watermarkUnusableCache.put(cacheKey, Boolean.TRUE);
        }
        return usable;
    }

    private boolean probeWatermarkField(String collectionName, String field) {
        if (field == null || field.isEmpty()) {
            return false;
        }
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            MongoCursor<Document> cursor = collection
                    .find(new Document(field, new Document("$exists", true)))
                    .projection(new Document(field, 1))
                    .limit(WATERMARK_TYPE_SAMPLE)
                    .iterator();
            int checked = 0;
            try {
                while (cursor.hasNext()) {
                    Object value = cursor.next().get(field);
                    checked++;
                    // null 视为「无值」跳过；只要出现非 Date 的值就判定不能做水位线
                    if (value != null && !(value instanceof java.util.Date)) {
                        logger.warn("Collection {}: field '{}' is {} (NOT a BSON date), sample={}. "
                                + "Incremental watermark is unusable (String < Date in BSON order "
                                + "would make the $gt query always return 0) -> fallback to full-read + hash comparison. "
                                + "(this warning is logged only once per startup)",
                                collectionName, field, value.getClass().getSimpleName(), value);
                        return false;
                    }
                }
            } finally {
                cursor.close();
            }
            return checked > 0;
        } catch (Exception e) {
            logger.warn("Failed to verify watermark field '{}' on {}: {} -> fallback to hash comparison",
                    field, collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * 获取集合的所有字段名（统一转换为小写，避免大小写重复）
     */
    public Set<String> getCollectionFields(String collectionName) {
        Set<String> fields = new LinkedHashSet<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);
            MongoCursor<Document> cursor = collection.find().limit(100).iterator();

            while (cursor.hasNext()) {
                Document doc = cursor.next();
                extractAllFields(doc, "", fields);
            }

            // 将字段名统一转换为小写，避免大小写重复
            Set<String> normalizedFields = new LinkedHashSet<>();
            for (String field : fields) {
                normalizedFields.add(field.toLowerCase());
            }
            return normalizedFields;
        } catch (Exception e) {
            logger.error("Failed to get fields for {}: {}", collectionName, e.getMessage(), e);
        }
        return fields;
    }

    /**
     * 提取顶层字段（不递归展开嵌套Document）。
     * 嵌套值由写入侧（KingbaseWriterService.extractValue）序列化为JSON存入TEXT列。
     * 原因：不同文档的嵌套路径（数组下标/子键不同）展开后经列名sanitize会坍缩成
     * 同一列名（如 toothscore_toothmap____8 specified more than once），导致建表失败、整表漏同步。
     */
    private void extractAllFields(Document doc, String prefix, Set<String> fields) {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String fieldName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            fields.add(fieldName);
            // 不再递归：嵌套Document整体保留，写入时转JSON
        }
    }
}
