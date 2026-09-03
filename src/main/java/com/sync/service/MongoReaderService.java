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
                extractFieldTypes((Document) value, fieldName, fieldTypes, allFields);
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                if (!list.isEmpty()) {
                    Object firstElement = list.get(0);
                    if (firstElement instanceof Document) {
                        fieldTypes.put(fieldName, "array<document>");
                        extractFieldTypes((Document) firstElement, fieldName + "[]", fieldTypes, allFields);
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
     * 递归提取所有字段
     */
    private void extractAllFields(Document doc, String prefix, Set<String> fields) {
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            String fieldName = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            fields.add(fieldName);

            if (entry.getValue() instanceof Document) {
                extractAllFields((Document) entry.getValue(), fieldName, fields);
            }
        }
    }
}
