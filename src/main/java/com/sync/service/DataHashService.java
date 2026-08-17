package com.sync.service;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据哈希服务
 * 用于检测数据是否发生变化（适用于没有editTime字段的表）
 */
@Service
public class DataHashService {

    private static final Logger logger = LoggerFactory.getLogger(DataHashService.class);
    private static final String HASH_FIELD = "_data_hash";

    /**
     * 计算文档的哈希值
     * 排除_id和MongoToKingDate字段，只计算业务数据
     */
    public String calculateHash(Document doc) {
        try {
            // 按字段名排序，确保相同的文档产生相同的哈希
            TreeMap<String, Object> sortedMap = new TreeMap<>();
            for (Map.Entry<String, Object> entry : doc.entrySet()) {
                String key = entry.getKey();
                // 排除_id和MongoToKingDate字段
                if (!"_id".equals(key) && !"MongoToKingDate".equals(key) && !HASH_FIELD.equals(key)) {
                    sortedMap.put(key, entry.getValue());
                }
            }

            // 转换为JSON字符串
            String jsonString = sortedMap.toString();

            // 计算MD5哈希
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(jsonString.getBytes(StandardCharsets.UTF_8));

            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("Failed to calculate hash: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 计算文档哈希并添加到文档中
     */
    public Document addHashToDocument(Document doc) {
        Document result = new Document(doc);
        String hash = calculateHash(doc);
        if (hash != null) {
            result.put(HASH_FIELD, hash);
        }
        return result;
    }

    /**
     * 比较两个文档的哈希值
     */
    public boolean isDataChanged(Document mongoDoc, String kingbaseHash) {
        String mongoHash = calculateHash(mongoDoc);
        if (mongoHash == null || kingbaseHash == null) {
            return true; // 无法比较，认为已变化
        }
        return !mongoHash.equals(kingbaseHash);
    }

    /**
     * 从Kingbase结果中提取哈希值
     */
    public String extractHash(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Object hash = row.get(HASH_FIELD);
        return hash != null ? hash.toString() : null;
    }

    /**
     * 获取哈希字段名
     */
    public String getHashFieldName() {
        return HASH_FIELD;
    }
}
