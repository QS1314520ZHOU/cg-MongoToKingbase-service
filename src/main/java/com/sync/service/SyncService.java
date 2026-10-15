package com.sync.service;

import com.sync.config.SyncConfig;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // 全局同步互斥锁：启动Runner的首次同步跑在main线程，定时同步跑在scheduling线程，
    // 两者可能并发调用syncAll，必须互斥（否则两个流程同时搬运同一批数据、互相覆盖sync_status水位线）
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    /**
     * 同步所有配置的表（带全局互斥，防止并发同步）
     * 采用轮询方式：每轮各表同步一批数据，然后等待间隔时间
     */
    public Map<String, Object> syncAll() {
        if (!syncRunning.compareAndSet(false, true)) {
            logger.warn("syncAll is already running (startup initial sync or scheduled sync), skip this invocation");
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("status", "SKIPPED");
            skipped.put("reason", "another sync is already running");
            return skipped;
        }
        try {
            return doSyncAll();
        } finally {
            syncRunning.set(false);
        }
    }

    private Map<String, Object> doSyncAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> tables;

        // 判断是否同步所有集合
        if (syncConfig.isSyncAllCollections()) {
            logger.info("Sync all collections mode enabled, discovering collections from MongoDB...");
            tables = mongoReaderService.getAllCollections();

            // 排除符合排除规则的集合
            String excludePattern = syncConfig.getExcludeCollectionsPattern();
            if (excludePattern != null && !excludePattern.isEmpty()) {
                tables.removeIf(table -> table.matches(excludePattern));
            }

            logger.info("Discovered {} collections: {}", tables.size(), tables);
        } else {
            tables = syncConfig.getTables();
            logger.info("Using configured tables: {}", tables.size());
        }

        int batchSize = syncConfig.getBatchSize();
        logger.info("Starting sync for {} tables: {}", tables.size(), tables);

        // 1. 分析所有表的状态
        List<TableSyncContext> tableContexts = new ArrayList<>();
        for (String table : tables) {
            try {
                TableSyncContext ctx = prepareTableSync(table);
                tableContexts.add(ctx);
                result.put(table, new LinkedHashMap<>()); // 占位，最后填充结果
            } catch (Exception e) {
                logger.error("Failed to prepare table {}: {}", table, e.getMessage(), e);
                result.put(table, "ERROR: " + e.getMessage());
            }
        }

        // 2. 检查是否有需要全量同步的表
        boolean hasFullSyncTables = tableContexts.stream().anyMatch(ctx -> ctx.useFullSync);

        if (hasFullSyncTables) {
            // 3. 轮询同步：每轮各表同步一批，然后等待
            logger.info("Using round-robin sync mode with batch size: {}, step interval: {}ms",
                batchSize, syncConfig.getStepInterval());

            int round = 0;
            while (true) {
                round++;
                boolean allCompleted = true;
                int totalSyncedThisRound = 0;

                for (TableSyncContext ctx : tableContexts) {
                    if (ctx.completed) {
                        continue;
                    }

                    try {
                        int readCount = syncTableBatch(ctx, batchSize);
                        totalSyncedThisRound += readCount;

                        // 如果读取的文档数小于批次大小，说明MongoDB中没有更多数据了
                        if (readCount < batchSize) {
                            ctx.completed = true;
                            logger.info("Table {} completed sync (read {} < batchSize {})", ctx.tableName, readCount, batchSize);
                        } else {
                            allCompleted = false;
                        }
                    } catch (Exception e) {
                        logger.error("Failed to sync table {}: {}", ctx.tableName, e.getMessage(), e);
                        ctx.completed = true; // 出错也标记为完成，避免无限重试
                    }
                }

                logger.info("Round {} completed: synced {} documents", round, totalSyncedThisRound);

                // 所有表都同步完成
                if (allCompleted) {
                    logger.info("All tables completed sync");
                    break;
                }

                // 等待间隔时间
                if (syncConfig.getStepInterval() > 0) {
                    logger.info("Waiting {}ms before next round...", syncConfig.getStepInterval());
                    try {
                        Thread.sleep(syncConfig.getStepInterval());
                    } catch (InterruptedException e) {
                        logger.warn("Step interval interrupted: {}", e.getMessage());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } else {
            // 4. 所有表都是增量同步，直接同步
            logger.info("All tables using incremental sync, no round-robin needed");
            for (TableSyncContext ctx : tableContexts) {
                try {
                    Map<String, Object> tableResult = syncTableIncremental(ctx);
                    result.put(ctx.tableName, tableResult);
                } catch (Exception e) {
                    logger.error("Failed to sync table {}: {}", ctx.tableName, e.getMessage(), e);
                    result.put(ctx.tableName, "ERROR: " + e.getMessage());
                }
            }
        }

        // 5. 收集所有表的同步结果
        for (TableSyncContext ctx : tableContexts) {
            Map<String, Object> tableResult = new LinkedHashMap<>();
            tableResult.put("status", ctx.completed ? "SUCCESS" : "PARTIAL");
            tableResult.put("totalInserted", ctx.totalInserted.get());
            tableResult.put("totalSkipped", ctx.totalSkipped.get());
            tableResult.put("useFullSync", ctx.useFullSync);
            tableResult.put("isNewTable", ctx.isNewTable);
            result.put(ctx.tableName, tableResult);
        }

        logger.info("Sync completed. Results: {}", result);
        return result;
    }

    /**
     * 表同步上下文
     */
    private static class TableSyncContext {
        String tableName;
        String kingbaseTable;
        Set<String> mongoFields;
        List<String> columns;
        boolean useFullSync;
        boolean isNewTable;
        /**
         * 源集合是否真实存在 editTime 字段（按集合实际字段探测，而非看全局配置）。
         * false 时增量同步必须回退到哈希比对，否则 editTime 游标查询永远返回 0 条 → 数据冻结。
         */
        boolean hasEditTimeField;
        boolean completed = false;
        int skip = 0;
        Date lastSyncTime;
        Object lastMongoId; // 增量游标：上批最后一条文档的原始_id（tie-breaker，防止同一时间戳漏读/重复读）
        AtomicLong totalInserted = new AtomicLong(0);
        AtomicLong totalSkipped = new AtomicLong(0);
    }

    /**
     * 准备表同步上下文
     */
    private TableSyncContext prepareTableSync(String collectionName) throws Exception {
        TableSyncContext ctx = new TableSyncContext();
        ctx.tableName = collectionName;
        ctx.kingbaseTable = collectionName.toLowerCase().replaceAll("[^a-zA-Z0-9_]", "_");

        // 获取MongoDB集合信息
        long docCount = mongoReaderService.getDocumentCount(collectionName);
        Map<String, Object> schema = mongoReaderService.getCollectionSchema(collectionName);
        ctx.mongoFields = mongoReaderService.getCollectionFields(collectionName);

        if (docCount == 0) {
            logger.info("Collection {} is empty, skipping", collectionName);
            ctx.completed = true;
            return ctx;
        }

        // 处理Kingbase表
        boolean tableExists = kingbaseWriterService.tableExists(ctx.kingbaseTable);
        boolean tableIsEmpty = tableExists ? kingbaseWriterService.isTableEmpty(ctx.kingbaseTable) : true;
        ctx.isNewTable = !tableExists;

        // 创建表（如果不存在）
        if (!tableExists) {
            Map<String, String> columnTypes = new LinkedHashMap<>();
            columnTypes.put("mongo_id", "String");
            for (String field : ctx.mongoFields) {
                String type = ((Map<String, String>) schema.get("fields")).getOrDefault(field, "String");
                columnTypes.put(field, type);
            }
            columnTypes.put(syncConfig.getSyncFieldName(), "Date");
            columnTypes.put(dataHashService.getHashFieldName(), "String");
            kingbaseWriterService.createTable(ctx.kingbaseTable, columnTypes);
            logger.info("Table {} created (new table)", ctx.kingbaseTable);
        } else {
            ensureSyncFieldExists(ctx.kingbaseTable, syncConfig.getSyncFieldName());
            ensureHashFieldExists(ctx.kingbaseTable);
            logger.info("Table {} already exists, isEmpty: {}", ctx.kingbaseTable, tableIsEmpty);
        }

        // 准备列列表
        ctx.columns = new ArrayList<>();
        ctx.columns.add("mongo_id");
        ctx.columns.addAll(ctx.mongoFields);
        ctx.columns.add(syncConfig.getSyncFieldName());
        ctx.columns.add(dataHashService.getHashFieldName());

        // 获取上次同步时间
        ctx.lastSyncTime = lastSyncTimes.get(collectionName);

        // 探测源集合是否可作为增量水位线（关键：按集合实际字段 + 实际值类型判断，
        // 而不是只看全局配置或字段名是否存在）
        //
        // 【教训】editTime 若存的是字符串（如 "2026-10-07T11:12:50.217830874"），
        // 查询 {"editTime": {"$gt": <java.util.Date>}} 会因为 BSON 类型排序中
        // String < Date 而恒返回 0 条 —— 该表被永久冻结，且全过程没有任何报错。
        // 实测 fallDangerFormExtra / selfCareFormExtra / ydwzl_form_extra 三张表中招。
        String editTimeFieldCfg = syncConfig.getEditTimeField();
        ctx.hasEditTimeField = editTimeFieldCfg != null && !editTimeFieldCfg.isEmpty()
            && ctx.mongoFields != null
            && ctx.mongoFields.stream().anyMatch(f -> f.equalsIgnoreCase(editTimeFieldCfg))
            && mongoReaderService.isUsableWatermarkField(collectionName, editTimeFieldCfg);

        // 判断同步模式
        ctx.useFullSync = !tableExists || tableIsEmpty || ctx.lastSyncTime == null;

        logger.info("Table {}: exists={}, isEmpty={}, lastSyncTime={}, useFullSync={}, hasEditTimeField={}",
            collectionName, tableExists, tableIsEmpty, ctx.lastSyncTime, ctx.useFullSync, ctx.hasEditTimeField);

        return ctx;
    }

    /**
     * 同步一批数据（用于轮询模式）
     */
    private int syncTableBatch(TableSyncContext ctx, int batchSize) {
        if (ctx.completed) {
            return 0;
        }

        String editTimeField = syncConfig.getEditTimeField();
        // 增量模式：必须「源集合真实存在 editTime 字段」才可用 editTime 游标
        boolean incremental = !ctx.useFullSync && ctx.hasEditTimeField
            && editTimeField != null && !editTimeField.isEmpty();
        // 哈希比对模式：表已存在但源集合无 editTime 字段时的增量替代方案
        // （editTime 游标查询在无该字段的集合上永远返回 0 条，必须回退到哈希比对）
        boolean hashMode = !ctx.useFullSync && !incremental;

        List<Document> documents;
        if (incremental) {
            // 增量同步：读取editTime大于上次同步时间的数据（按editTime+_id排序，游标分页）
            documents = mongoReaderService.readCollectionByEditTime(
                ctx.tableName, batchSize, editTimeField, ctx.lastSyncTime, ctx.lastMongoId);
        } else {
            // 全量同步 / 哈希比对：按 skip 分页读取一批数据
            documents = mongoReaderService.readCollection(ctx.tableName, batchSize, ctx.skip);
        }

        int readCount = documents.size();

        if (readCount == 0) {
            ctx.completed = true;
            // 同步完成后，更新同步状态
            // 水位线持久化：增量模式用当前游标水位线（本批最大editTime），
            // 全量模式用集合最大editTime，避免用墙钟时间导致重启后跳过未同步数据
            Date maxEditTime = null;
            if (editTimeField != null && !editTimeField.isEmpty()) {
                maxEditTime = mongoReaderService.getMaxEditTime(ctx.tableName, editTimeField);
                if (maxEditTime != null) {
                    maxEditTimes.put(ctx.tableName, maxEditTime);
                }
            }
            Date watermark = incremental ? ctx.lastSyncTime : maxEditTime;
            if (watermark != null) {
                lastSyncTimes.put(ctx.tableName, watermark);
            }
            syncStatusService.saveSyncStatus(ctx.tableName,
                watermark != null ? watermark : new Date(),
                maxEditTime, ctx.totalInserted.get());
            if (hashMode) {
                logger.info("Table {}: hash-compare finished, totalInserted={} (new/updated), totalSkipped={}",
                    ctx.tableName, ctx.totalInserted.get(), ctx.totalSkipped.get());
            }
            logger.info("Saved sync status for {}: totalInserted={}, watermark={}",
                ctx.tableName, ctx.totalInserted.get(), watermark);
            return 0;
        }

        // 哈希比对模式：先剔除内容未变化的文档，只写有变化的（否则每轮全表重写）
        if (hashMode) {
            documents = filterChangedByHash(ctx, documents);
            if (documents.isEmpty()) {
                // 本批全部未变化：推进分页游标继续下一批
                ctx.skip += batchSize;
                logger.info("Table {} hash batch: read {} documents, 0 changed, skipped {}",
                    ctx.tableName, readCount, readCount);
                return readCount;
            }
        }

        // 处理文档，同时收集所有实际字段
        List<Document> processedDocs = new ArrayList<>();
        Set<String> actualFields = new LinkedHashSet<>();
        for (Document doc : documents) {
            Document processed = dataHashService.addHashToDocument(doc);
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
            processed.put("mongo_id", mongoId);
            processed.put(syncConfig.getSyncFieldName(), new Date());
            processedDocs.add(processed);

            // 收集文档中的所有字段名（转小写）
            for (String key : processed.keySet()) {
                actualFields.add(key.toLowerCase());
            }
        }

        // 合并预定义字段和实际字段，确保不遗漏任何字段（忽略大小写去重）
        List<String> allColumns = new ArrayList<>(ctx.columns);
        Set<String> allColumnsLower = new LinkedHashSet<>();
        for (String col : allColumns) {
            allColumnsLower.add(col.toLowerCase());
        }
        for (String field : actualFields) {
            if (!allColumnsLower.contains(field.toLowerCase())) {
                allColumns.add(field);
                allColumnsLower.add(field.toLowerCase());
                logger.debug("Added dynamic column from document: {}", field);
            }
        }

        // 写入数据
        int insertedCount;  // 实际插入/更新的行数
        if (ctx.useFullSync) {
            insertedCount = kingbaseWriterService.batchInsertIgnoreDuplicate(
                ctx.kingbaseTable, processedDocs, allColumns);
        } else {
            // 增量 / 哈希比对模式都用 upsert（哈希模式需支持更新已有行）
            insertedCount = kingbaseWriterService.batchUpsert(
                ctx.kingbaseTable, processedDocs, allColumns, syncConfig.getSyncFieldName());
        }

        ctx.totalInserted.addAndGet(insertedCount);

        // 关键修复：增量模式推进水位线到本批最大editTime（结果已排序，即最后一条），
        // 否则每轮都会重复读取同一批数据，且循环退出条件永不满足导致死循环
        if (incremental) {
            Object[] outLastId = new Object[1];
            Date batchMax = MongoReaderService.extractBatchMaxEditTime(
                documents, editTimeField, outLastId);
            if (batchMax != null) {
                ctx.lastSyncTime = batchMax;
                ctx.lastMongoId = outLastId[0];
            } else {
                // editTime字段缺失或非Date类型，无法推进水位线，标记完成避免死循环
                logger.warn("Table {}: batch last document has no valid editTime field '{}', stop incremental sync",
                    ctx.tableName, editTimeField);
                ctx.completed = true;
            }
        } else {
            // 全量 / 哈希比对模式：推进 skip 分页游标
            ctx.skip += batchSize;
        }

        logger.info("Table {} batch: read {} documents, inserted {} (new), total: {}, watermark: {}",
            ctx.tableName, readCount, insertedCount, ctx.totalInserted.get(), ctx.lastSyncTime);

        // 每批同步完成后都保存同步状态（水位线用游标值，而非墙钟时间）
        Date maxEditTime = null;
        if (editTimeField != null && !editTimeField.isEmpty()) {
            maxEditTime = mongoReaderService.getMaxEditTime(ctx.tableName, editTimeField);
            if (maxEditTime != null) {
                maxEditTimes.put(ctx.tableName, maxEditTime);
            }
        }
        if (ctx.lastSyncTime != null) {
            lastSyncTimes.put(ctx.tableName, ctx.lastSyncTime);
        }
        syncStatusService.saveSyncStatus(ctx.tableName,
            ctx.lastSyncTime != null ? ctx.lastSyncTime : new Date(),
            maxEditTime, ctx.totalInserted.get());
        logger.debug("Saved sync status for {}: totalInserted={}, watermark={}",
            ctx.tableName, ctx.totalInserted.get(), ctx.lastSyncTime);

        // 返回读取的文档数（而非插入数），用于判断是否还有更多数据
        return readCount;
    }

    /**
     * 哈希比对过滤：剔除与 Kingbase 中 _data_hash 完全相同的文档，只返回内容有变化的。
     * 适用于源集合没有 editTime 字段、无法用时间戳增量判断的集合。
     * 采用一次 IN 查询批量取哈希（避免逐行查询的 N+1 问题）；无法取到哈希时视为已变化（安全降级）。
     */
    private List<Document> filterChangedByHash(TableSyncContext ctx, List<Document> documents) {
        List<String> ids = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            ids.add(extractMongoId(doc));
        }
        Map<String, String> existingHashes =
            kingbaseWriterService.getHashByMongoIds(ctx.kingbaseTable, ids);

        List<Document> changed = new ArrayList<>();
        int unchanged = 0;
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String existing = existingHashes.get(ids.get(i));
            String current = dataHashService.calculateHash(doc);
            if (existing != null && current != null && existing.equals(current)) {
                unchanged++;
            } else {
                changed.add(doc);
            }
        }
        ctx.totalSkipped.addAndGet(unchanged);
        return changed;
    }

    /**
     * 提取文档的 mongo_id（兼容 ObjectId 与 String 两种 _id 类型，缺失时生成随机 UUID）
     */
    private String extractMongoId(Document doc) {
        Object idValue = doc.get("_id");
        if (idValue instanceof org.bson.types.ObjectId) {
            return ((org.bson.types.ObjectId) idValue).toString();
        } else if (idValue != null) {
            return idValue.toString();
        }
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 同步单个表（增量模式）
     */
    private Map<String, Object> syncTableIncremental(TableSyncContext ctx) {
        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        logger.info("Starting incremental sync for collection: {}", ctx.tableName);

        try {
            if (ctx.completed) {
                result.put("status", "SKIPPED");
                result.put("reason", "Empty collection");
                return result;
            }

            int batchSize = syncConfig.getBatchSize();
            String editTimeField = syncConfig.getEditTimeField();

            // 增量模式：源集合真实存在 editTime 字段才可用时间戳游标；
            // 否则回退到哈希比对（无该字段的集合，时间戳查询永远返回 0 条 → 数据冻结）
            boolean incremental = ctx.hasEditTimeField
                && editTimeField != null && !editTimeField.isEmpty();
            boolean hashMode = !incremental;

            // 局部游标：每批推进，避免同一批数据被无限重复读取
            Date watermark = ctx.lastSyncTime;
            Object lastMongoId = ctx.lastMongoId;
            int skip = ctx.skip;

            while (true) {
                List<Document> documents;
                if (incremental) {
                    documents = mongoReaderService.readCollectionByEditTime(
                        ctx.tableName, batchSize, editTimeField, watermark, lastMongoId);
                } else {
                    // 哈希比对模式：按 skip 分页全量扫描
                    documents = mongoReaderService.readCollection(ctx.tableName, batchSize, skip);
                }

                int readCount = documents.size();

                if (readCount == 0) {
                    break;
                }

                // 哈希比对模式：先剔除内容未变化的文档，只写有变化的（否则每轮全表重写）
                if (hashMode) {
                    documents = filterChangedByHash(ctx, documents);
                    if (documents.isEmpty()) {
                        skip += batchSize;
                        if (readCount < batchSize) {
                            break;
                        }
                        continue;
                    }
                }

                // 处理文档，同时收集所有实际字段
                List<Document> processedDocs = new ArrayList<>();
                Set<String> actualFields = new LinkedHashSet<>();
                for (Document doc : documents) {
                    Document processed = dataHashService.addHashToDocument(doc);
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
                    processed.put("mongo_id", mongoId);
                    processed.put(syncConfig.getSyncFieldName(), new Date());
                    processedDocs.add(processed);

                    // 收集文档中的所有字段名（转小写）
                    for (String key : processed.keySet()) {
                        actualFields.add(key.toLowerCase());
                    }
                }

                // 合并预定义字段和实际字段（忽略大小写去重）
                List<String> allColumns = new ArrayList<>(ctx.columns);
                Set<String> allColumnsLower = new LinkedHashSet<>();
                for (String col : allColumns) {
                    allColumnsLower.add(col.toLowerCase());
                }
                for (String field : actualFields) {
                    if (!allColumnsLower.contains(field.toLowerCase())) {
                        allColumns.add(field);
                        allColumnsLower.add(field.toLowerCase());
                        logger.debug("Added dynamic column from document: {}", field);
                    }
                }

                int affected = kingbaseWriterService.batchUpsert(
                    ctx.kingbaseTable, processedDocs, allColumns, syncConfig.getSyncFieldName());

                ctx.totalInserted.addAndGet(affected);

                if (incremental) {
                    // 推进水位线游标到本批最大editTime（结果已按editTime+_id排序，即最后一条）
                    Object[] outLastId = new Object[1];
                    Date batchMax = MongoReaderService.extractBatchMaxEditTime(
                        documents, editTimeField, outLastId);
                    if (batchMax == null) {
                        // editTime字段缺失或非Date类型，无法推进水位线，终止避免死循环
                        logger.warn("Table {}: batch last document has no valid editTime field '{}', stop incremental sync",
                            ctx.tableName, editTimeField);
                        break;
                    }
                    watermark = batchMax;
                    lastMongoId = outLastId[0];
                } else {
                    // 哈希比对模式：推进 skip 分页游标
                    skip += batchSize;
                }

                if (readCount < batchSize) {
                    break;
                }
            }

            // 回写游标到表上下文，供后续批次/状态持久化使用
            ctx.lastSyncTime = watermark;
            ctx.lastMongoId = lastMongoId;
            ctx.skip = skip;

            // 获取MongoDB中最大的editTime（仅源集合含该字段时有意义）
            Date maxEditTime = ctx.hasEditTimeField
                ? mongoReaderService.getMaxEditTime(ctx.tableName, editTimeField) : null;
            if (maxEditTime != null) {
                maxEditTimes.put(ctx.tableName, maxEditTime);
            }

            // 更新同步状态（水位线用游标值，而非墙钟时间）
            if (watermark != null) {
                lastSyncTimes.put(ctx.tableName, watermark);
            }
            syncStatusService.saveSyncStatus(ctx.tableName,
                watermark != null ? watermark : new Date(),
                maxEditTime, ctx.totalInserted.get());

            long duration = System.currentTimeMillis() - startTime;
            result.put("status", "SUCCESS");
            result.put("totalInserted", ctx.totalInserted.get());
            result.put("durationMs", duration);

            logger.info("Incremental sync completed for {}: inserted={}, duration={}ms",
                ctx.tableName, ctx.totalInserted.get(), duration);

        } catch (Exception e) {
            logger.error("Failed to incremental sync {}: {}", ctx.tableName, e.getMessage(), e);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 同步单个表（带全局互斥，与syncAll共用同一把锁）
     */
    public Map<String, Object> syncTable(String collectionName) {
        if (!syncRunning.compareAndSet(false, true)) {
            logger.warn("syncAll is already running, skip manual table sync for {}", collectionName);
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("status", "SKIPPED");
            skipped.put("reason", "another sync is already running");
            return skipped;
        }
        try {
            return doSyncTable(collectionName);
        } finally {
            syncRunning.set(false);
        }
    }

    private Map<String, Object> doSyncTable(String collectionName) {
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
            boolean tableExists = kingbaseWriterService.tableExists(kingbaseTable);
            boolean tableIsEmpty = tableExists ? kingbaseWriterService.isTableEmpty(kingbaseTable) : true;

            // 3. 创建表（如果不存在）
            if (!tableExists) {
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
                logger.info("Table {} created (new table)", kingbaseTable);
            } else {
                // 表已存在，检查是否需要添加同步时间字段和哈希字段
                ensureSyncFieldExists(kingbaseTable, syncConfig.getSyncFieldName());
                ensureHashFieldExists(kingbaseTable);
                logger.info("Table {} already exists, isEmpty: {}", kingbaseTable, tableIsEmpty);
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

            // 判断同步模式：新表或空表全量同步，已存在且有数据的表根据配置执行增量同步
            boolean useFullSync = !tableExists || tableIsEmpty || lastSyncTime == null;

            logger.info("Table exists: {}, isEmpty: {}, lastSyncTime: {}, useFullSync: {}",
                tableExists, tableIsEmpty, lastSyncTime, useFullSync);

            // 5. 判断同步模式
            if (!useFullSync) {
                // 增量同步模式（表已存在且有同步记录）
                logger.info("Using incremental sync mode");

                // 关键：判断的是「源集合是否真实存在 editTime 字段」，而不是「全局配置是否填了该字段名」。
                // 配置里 edit-time-field 恒为 editTime，若按配置判断，无该字段的集合会永远走时间戳分支
                // → 查询恒返回 0 条 → 哈希比对分支永远不可达（修复前该分支即为死代码）。
                // 另外还必须校验字段值确实是 BSON 日期：若存的是字符串，
                // {"editTime":{"$gt":Date}} 因 String < Date 恒返回 0 条，同样会冻死该表。
                String editTimeFieldCfg = syncConfig.getEditTimeField();
                boolean collectionHasEditTime = editTimeFieldCfg != null && !editTimeFieldCfg.isEmpty()
                    && mongoFields.stream().anyMatch(f -> f.equalsIgnoreCase(editTimeFieldCfg))
                    && mongoReaderService.isUsableWatermarkField(collectionName, editTimeFieldCfg);
                if (collectionHasEditTime) {
                    // 有editTime字段，使用时间戳增量同步
                    logger.info("Using editTime field for incremental sync: {}", editTimeFieldCfg);

                    // 局部水位线游标：每批推进，避免同一批数据被无限重复读取
                    Date watermark = lastSyncTime;
                    Object lastMongoId = null;

                    while (true) {
                        List<Document> documents = mongoReaderService.readCollectionByEditTime(
                            collectionName, batchSize, syncConfig.getEditTimeField(), watermark, lastMongoId);

                        if (documents.isEmpty()) {
                            break;
                        }

                        // 处理文档，添加哈希值
                        List<Document> processedDocs = new ArrayList<>();
                        for (Document doc : documents) {
                            Document processed = dataHashService.addHashToDocument(doc);
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
                            processed.put("mongo_id", mongoId);
                            processed.put(syncConfig.getSyncFieldName(), new Date());
                            processedDocs.add(processed);
                        }

                        // 使用upsert方式同步
                        int affected = kingbaseWriterService.batchUpsert(
                            kingbaseTable, processedDocs, columns, syncConfig.getSyncFieldName());

                        totalInserted.addAndGet(affected);

                        // 推进水位线游标到本批最大editTime（结果已排序，即最后一条）
                        Object[] outLastId = new Object[1];
                        Date batchMax = MongoReaderService.extractBatchMaxEditTime(
                            documents, syncConfig.getEditTimeField(), outLastId);
                        if (batchMax == null) {
                            logger.warn("Table {}: batch last document has no valid editTime field '{}', stop incremental sync",
                                collectionName, syncConfig.getEditTimeField());
                            break;
                        }
                        watermark = batchMax;
                        lastMongoId = outLastId[0];

                        if (documents.size() < batchSize) {
                            break;
                        }

                        logger.info("Incremental progress: {} documents processed, watermark: {}",
                            totalInserted.get(), watermark);
                    }

                    // 回写水位线，供最后的状态持久化使用
                    lastSyncTime = watermark;
                } else {
                    // 源集合没有editTime字段，使用哈希校验（内容比对）
                    logger.info("No editTime field in collection {}, using hash-based incremental sync", collectionName);

                    while (true) {
                        List<Document> documents = mongoReaderService.readCollection(collectionName, batchSize, skip);

                        if (documents.isEmpty()) {
                            break;
                        }

                        // 批量取本批文档在 Kingbase 中已存的哈希（一次 IN 查询，避免逐行 N+1）
                        List<String> batchIds = new ArrayList<>(documents.size());
                        for (Document doc : documents) {
                            batchIds.add(extractMongoId(doc));
                        }
                        Map<String, String> existingHashes =
                            kingbaseWriterService.getHashByMongoIds(kingbaseTable, batchIds);

                        // 处理文档，添加哈希值
                        List<Document> processedDocs = new ArrayList<>();
                        for (int i = 0; i < documents.size(); i++) {
                            Document doc = documents.get(i);
                            String mongoId = batchIds.get(i);

                            // 获取Kingbase中对应的哈希值
                            String existingHash = existingHashes.get(mongoId);

                            // 计算MongoDB文档的哈希
                            String newHash = dataHashService.calculateHash(doc);

                            // 只有哈希不同时才更新
                            if (existingHash == null || newHash == null || !existingHash.equals(newHash)) {
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
            // 水位线策略：增量模式用推进后的游标水位线（lastSyncTime已被增量分支更新），
            // 全量模式用集合最大editTime；避免用墙钟时间导致重启后跳过未同步数据
            Date watermark = useFullSync ? maxEditTime : lastSyncTime;
            if (watermark != null) {
                lastSyncTimes.put(collectionName, watermark);
            }

            // 持久化同步状态到数据库
            syncStatusService.saveSyncStatus(collectionName,
                watermark != null ? watermark : new Date(),
                maxEditTime, totalInserted.get() + totalUpdated.get());

            long duration = System.currentTimeMillis() - startTime;
            result.put("status", "SUCCESS");
            result.put("totalInserted", totalInserted.get());
            result.put("totalUpdated", totalUpdated.get());
            result.put("totalSkipped", totalSkipped.get());
            result.put("durationMs", duration);
            result.put("kingbaseTable", kingbaseTable);
            result.put("lastSyncTime", watermark);
            result.put("isNewTable", !tableExists);
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
     * 同步符合模式的表（用于配置表定时任务）
     * @param pattern 正则表达式模式
     */
    public Map<String, Object> syncTablesByPattern(String pattern) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 筛选符合模式的表
        List<String> configTables = new ArrayList<>();
        for (String table : syncConfig.getTables()) {
            if (table.matches(pattern)) {
                configTables.add(table);
            }
        }

        if (configTables.isEmpty()) {
            logger.info("No tables matched pattern: {}", pattern);
            result.put("status", "NO_TABLES_MATCHED");
            return result;
        }

        logger.info("Syncing config tables with pattern '{}': {}", pattern, configTables);

        // 只同步筛选出的表
        List<String> originalTables = syncConfig.getTables();
        syncConfig.setTables(configTables);

        try {
            result = syncAll();
        } finally {
            // 恢复原始表列表
            syncConfig.setTables(originalTables);
        }

        return result;
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
