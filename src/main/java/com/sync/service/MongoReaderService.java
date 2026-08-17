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

            schema.put("fields", fieldTypes);
            schema.put("fieldCount", allFields.size());
            schema.put("sampleSize", Math.min(100, getDocumentCount(collectionName)));

            logger.info("Schema for {}: {} fields", collectionName, allFields.size());
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
     * 根据editTime字段读取需要更新的数据
     */
    public List<Document> readCollectionByEditTime(String collectionName, int batchSize,
                                                    String editTimeField, Date lastSyncTime) {
        List<Document> documents = new ArrayList<>();
        try {
            MongoCollection<Document> collection = mongoDatabase.getCollection(collectionName);

            // 查询editTime晚于上次同步时间的文档
            Document query = new Document(editTimeField, new Document("$gt", lastSyncTime));
            MongoCursor<Document> cursor = collection.find(query)
                .limit(batchSize)
                .iterator();

            while (cursor.hasNext()) {
                documents.add(cursor.next());
            }
            logger.debug("Read {} documents with {} > {} from {}",
                documents.size(), editTimeField, lastSyncTime, collectionName);
        } catch (Exception e) {
            logger.error("Failed to read by editTime from {}: {}", collectionName, e.getMessage(), e);
        }
        return documents;
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
     * 获取集合的所有字段名
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
