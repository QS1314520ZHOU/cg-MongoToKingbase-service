package com.sync.service;

import com.sync.config.SyncConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SyncService {

    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    @Autowired
    private MongoReaderService mongoReaderService;

    @Autowired
    private KingbaseWriterService kingbaseWriterService;

    @Autowired
    private SyncConfig syncConfig;

    @Autowired
    private SyncStatusService syncStatusService;

    @Autowired
    private DataHashService dataHashService;

    // 记录最后同步时间（静态变量，供SyncStatusService访问）
    public static final Map<String, Date> lastSyncTimes = new HashMap<>();

    // 记录MongoDB中每个集合的最大editTime（静态变量，供SyncStatusService访问）
    public static final Map<String, Date> maxEditTimes = new HashMap<>();

    /**
     * 同步所有配置的表
     */
    public Map<String, Object> syncAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> tables = syncConfig.getTables();

        logger.info("Starting sync for {} tables: {}", tables.size(), tables);

        for (String table : tables) {
            try {
                Map<String, Object> tableResult = syncTable(table);
                result.put(table, tableResult);
            } catch (Exception e) {
                logger.error("Failed to sync table {}: {}", table, e.getMessage(), e);
                result.put(table, "ERROR: " + e.getMessage());
            }
        }

        logger.info("Sync completed. Results: {}", result);
        return result;
    }

    /**
     * 同步单个表
     */
    public Map<String, Object> syncTable(String collectionName) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        logger.info("Starting sync for collection: {}", collectionName);

        try {
            // 1. 获取MongoDB集合信息
            long docCount = mongoReaderService.getDocumentCount(collectionName);
            Map<String, Object> schema = mongoReaderService.getCollectionSchema(collectionName);
            Set<String> mongoFields = mongoReaderService.getCollectionFields(collectionName);

            result.put("mongoDocCount", docCount);
            result.put("mongoFieldCount", mongoFields.size());

            if (docCount == 0) {
                logger.info("Collection {} is empty, skipping", collectionName);
                result.put("status", "SKIPPED");
                result.put("reason", "Empty collection");
                return result;
            }

            // 2. 处理Kingbase表
            String kingbaseTable = collectionName.toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");

            if (syncConfig.isDropTableBeforeSync() && kingbaseWriterService.tableExists(kingbaseTable)) {
                kingbaseWriterService.dropTable(kingbaseTable);
            }

            // 3. 创建表（如果不存在）
            if (!kingbaseWriterService.tableExists(kingbaseTable)) {
                if (syncConfig.isCreateTableIfNotExists()) {
                    Map<String, String> columnTypes = new LinkedHashMap<>();
                    columnTypes.put("mongo_id", "String");
                    for (String field : mongoFields) {
                        String type = ((Map<String, String>) schema.get("fields")).getOrDefault(field, "String");
                        columnTypes.put(field, type);
                    }
                    // 添加同步时间字段和哈希字段
                    columnTypes.put(syncConfig.getSyncFieldName(), "Date");
                    columnTypes.put(dataHashService.getHashFieldName(), "String");
                    kingbaseWriterService.createTable(kingbaseTable, columnTypes);
                } else {
                    logger.warn("Table {} does not exist in Kingbase and createTableIfNotExists is false", kingbaseTable);
                    result.put("status", "SKIPPED");
                    result.put("reason", "Table does not exist");
                    return result;
                }
            } else {
                // 表已存在，检查是否需要添加同步时间字段和哈希字段
                ensureSyncFieldExists(kingbaseTable, syncConfig.getSyncFieldName());
                ensureHashFieldExists(kingbaseTable);
            }

            // 4. 读取并写入数据
            AtomicLong totalInserted = new AtomicLong(0);
            AtomicLong totalUpdated = new AtomicLong(0);
            AtomicLong totalSkipped = new AtomicLong(0);
            int batchSize = syncConfig.getBatchSize();
            int skip = 0;

            // 准备列列表（包含同步时间字段和哈希字段）
            List<String> columns = new ArrayList<>();
            columns.add("mongo_id");
            columns.addAll(mongoFields);
            columns.add(syncConfig.getSyncFieldName());
            columns.add(dataHashService.getHashFieldName());

            // 获取上次同步时间
            Date lastSyncTime = lastSyncTimes.get(collectionName);
            boolean isFirstSync = (lastSyncTime == null);

            logger.info("Sync mode: {}, lastSyncTime: {}, isFirstSync: {}",
                syncConfig.getSyncMode(), lastSyncTime, isFirstSync);

            // 5. 判断同步模式
            if ("incremental".equals(syncConfig.getSyncMode()) && !isFirstSync) {
                // 增量同步模式
                logger.info("Using incremental sync mode");

                if (syncConfig.getEditTimeField() != null && !syncConfig.getEditTimeField().isEmpty()) {
                    // 有editTime字段，使用时间戳增量同步
                    logger.info("Using editTime field for incremental sync: {}", syncConfig.getEditTimeField());

                    while (true) {
                        List<Document> documents = mongoReaderService.readCollectionByEditTime(
                            collectionName, batchSize, syncConfig.getEditTimeField(), lastSyncTime);

                        if (documents.isEmpty()) {
                            break;
                        }

                        // 处理文档，添加哈希值
                        List<Document> processedDocs = new ArrayList<>();
                        for (Document doc : documents) {
                            Document processed = dataHashService.addHashToDocument(doc);
                            processed.put("mongo_id", doc.getObjectId("_id").toString());
                            processed.put(syncConfig.getSyncFieldName(), new Date());
                            processedDocs.add(processed);
                        }

                        // 使用upsert方式同步
                        int affected = kingbaseWriterService.batchUpsert(
                            kingbaseTable, processedDocs, columns, syncConfig.getSyncFieldName());

                        totalInserted.addAndGet(affected);
                        skip += batchSize;

                        if (documents.size() < batchSize) {
                            break;
                        }

                        logger.info("Incremental progress: {} documents processed", totalInserted.get());
                    }
                } else {
                    // 没有editTime字段，使用哈希校验
                    logger.info("No editTime field, using hash-based incremental sync");

                    while (true) {
                        List<Document> documents = mongoReaderService.readCollection(collectionName, batchSize, skip);

                        if (documents.isEmpty()) {
                            break;
                        }

                        // 处理文档，添加哈希值
                        List<Document> processedDocs = new ArrayList<>();
                        for (Document doc : documents) {
                            // 获取Kingbase中对应的哈希值
                            String mongoId = doc.getObjectId("_id").toString();
                            String existingHash = kingbaseWriterService.getHashByMongoId(kingbaseTable, mongoId);

                            // 计算MongoDB文档的哈希
                            String newHash = dataHashService.calculateHash(doc);

                            // 只有哈希不同时才更新
                            if (existingHash == null || !existingHash.equals(newHash)) {
                                Document processed = dataHashService.addHashToDocument(doc);
                                processed.put("mongo_id", mongoId);
                                processed.put(syncConfig.getSyncFieldName(), new Date());
                                processedDocs.add(processed);
                            } else {
                                totalSkipped.incrementAndGet();
                            }
                        }

                        // 使用upsert方式同步
                        if (!processedDocs.isEmpty()) {
                            int affected = kingbaseWriterService.batchUpsert(
                                kingbaseTable, processedDocs, columns, syncConfig.getSyncFieldName());
                            totalInserted.addAndGet(affected);
                        }

                        skip += batchSize;

                        if (documents.size() < batchSize) {
                            break;
                        }

                        logger.info("Hash-based progress: {} updated, {} skipped",
                            totalInserted.get(), totalSkipped.get());
                    }
                }
            } else {
                // 全量同步模式（首次同步或配置为全量模式）
                logger.info("Using full sync mode, batch size: {}, step interval: {}ms",
                    batchSize, syncConfig.getStepInterval());

                int stepCount = 0;
                while (true) {
                    stepCount++;
                    List<Document> documents = mongoReaderService.readCollection(collectionName, batchSize, skip);

                    if (documents.isEmpty()) {
                        break;
                    }

                    // 处理文档，添加mongo_id、同步时间和哈希值
                    List<Document> processedDocs = new ArrayList<>();
                    for (Document doc : documents) {
                        Document processed = dataHashService.addHashToDocument(doc);
                        processed.put("mongo_id", doc.getObjectId("_id").toString());
                        processed.put(syncConfig.getSyncFieldName(), new Date());
                        processedDocs.add(processed);
                    }

                    // 批量插入（全量同步时使用insert，存在则跳过）
                    int inserted = kingbaseWriterService.batchInsertIgnoreDuplicate(
                        kingbaseTable, processedDocs, columns);
                    totalInserted.addAndGet(inserted);

                    skip += batchSize;

                    logger.info("Step {} completed: inserted {} documents, total: {} / {}",
                        stepCount, inserted, totalInserted.get(), docCount);

                    // 如果已经同步完所有数据，退出循环
                    if (documents.size() < batchSize) {
                        logger.info("All documents synced, exiting loop");
                        break;
                    }

                    // 分步同步：等待指定间隔后再同步下一批
                    if (syncConfig.getStepInterval() > 0) {
                        logger.info("Waiting {}ms before next step...", syncConfig.getStepInterval());
                        try {
                            Thread.sleep(syncConfig.getStepInterval());
                        } catch (InterruptedException e) {
                            logger.warn("Step interval interrupted: {}", e.getMessage());
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            // 6. 获取MongoDB中最大的editTime
            Date maxEditTime = null;
            if (syncConfig.getEditTimeField() != null && !syncConfig.getEditTimeField().isEmpty()) {
                maxEditTime = mongoReaderService.getMaxEditTime(collectionName, syncConfig.getEditTimeField());
                maxEditTimes.put(collectionName, maxEditTime);
                logger.info("Max editTime for {}: {}", collectionName, maxEditTime);
            }

            // 7. 更新最后同步时间并持久化
            Date currentSyncTime = new Date();
            lastSyncTimes.put(collectionName, currentSyncTime);

            // 持久化同步状态到数据库
            syncStatusService.saveSyncStatus(collectionName, currentSyncTime, maxEditTime,
                totalInserted.get() + totalUpdated.get());

            long duration = System.currentTimeMillis() - startTime;
            result.put("status", "SUCCESS");
            result.put("totalInserted", totalInserted.get());
            result.put("totalUpdated", totalUpdated.get());
            result.put("totalSkipped", totalSkipped.get());
            result.put("durationMs", duration);
            result.put("kingbaseTable", kingbaseTable);
            result.put("lastSyncTime", currentSyncTime);
            result.put("isFirstSync", isFirstSync);
            if (maxEditTime != null) {
                result.put("maxEditTime", maxEditTime);
            }

            logger.info("Sync completed for {}: inserted={}, updated={}, skipped={}, duration={}ms",
                collectionName, totalInserted.get(), totalUpdated.get(), totalSkipped.get(), duration);

        } catch (Exception e) {
            logger.error("Failed to sync {}: {}", collectionName, e.getMessage(), e);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 确保同步时间字段存在
     */
    private void ensureSyncFieldExists(String tableName, String syncFieldName) {
        try {
            Map<String, String> existingColumns = kingbaseWriterService.getTableColumns(tableName);
            if (!existingColumns.containsKey(syncFieldName.toLowerCase())) {
                logger.info("Adding sync field {} to table {}", syncFieldName, tableName);
                String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + syncFieldName + " TIMESTAMP";
                kingbaseWriterService.getJdbcTemplate().execute(sql);
            }
        } catch (Exception e) {
            logger.warn("Failed to ensure sync field exists: {}", e.getMessage());
        }
    }

    /**
     * 确保哈希字段存在
     */
    private void ensureHashFieldExists(String tableName) {
        try {
            Map<String, String> existingColumns = kingbaseWriterService.getTableColumns(tableName);
            String hashField = dataHashService.getHashFieldName();
            if (!existingColumns.containsKey(hashField.toLowerCase())) {
                logger.info("Adding hash field {} to table {}", hashField, tableName);
                String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + hashField + " VARCHAR(32)";
                kingbaseWriterService.getJdbcTemplate().execute(sql);
            }
        } catch (Exception e) {
            logger.warn("Failed to ensure hash field exists: {}", e.getMessage());
        }
    }

    /**
     * 获取同步状态
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configuredTables", syncConfig.getTables());
        status.put("lastSyncTimes", lastSyncTimes);
        status.put("syncMode", syncConfig.getSyncMode());
        status.put("batchSize", syncConfig.getBatchSize());
        status.put("cronExpression", syncConfig.getCronExpression());
        return status;
    }

    /**
     * 分析集合结构（不进行同步）
     */
    public Map<String, Object> analyzeCollection(String collectionName) {
        Map<String, Object> analysis = new LinkedHashMap<>();

        try {
            // MongoDB信息
            long docCount = mongoReaderService.getDocumentCount(collectionName);
            Map<String, Object> schema = mongoReaderService.getCollectionSchema(collectionName);
            Set<String> fields = mongoReaderService.getCollectionFields(collectionName);

            analysis.put("collectionName", collectionName);
            analysis.put("documentCount", docCount);
            analysis.put("schema", schema);
            analysis.put("fields", new ArrayList<>(fields));

            // Kingbase表信息
            String kingbaseTable = collectionName.toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");
            analysis.put("kingbaseTableName", kingbaseTable);
            analysis.put("kingbaseTableExists", kingbaseWriterService.tableExists(kingbaseTable));

            if (kingbaseWriterService.tableExists(kingbaseTable)) {
                Map<String, String> existingColumns = kingbaseWriterService.getTableColumns(kingbaseTable);
                analysis.put("kingbaseColumns", existingColumns);
                analysis.put("kingbaseRowCount", kingbaseWriterService.getRowCount(kingbaseTable));
            }

            logger.info("Analysis for {}: {} documents, {} fields", collectionName, docCount, fields.size());
        } catch (Exception e) {
            logger.error("Failed to analyze {}: {}", collectionName, e.getMessage(), e);
            analysis.put("error", e.getMessage());
        }

        return analysis;
    }
}
