package com.sync.config;

import com.sync.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 应用启动后自动执行的Runner
 * 负责自动分析表结构并启动定时同步
 */
@Component
@Order(1)
public class ApplicationStartupRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationStartupRunner.class);

    @Autowired
    private SyncService syncService;

    @Autowired
    private SyncConfig syncConfig;

    @Override
    public void run(ApplicationArguments args) {
        logger.info("========================================");
        logger.info("MongoDB to Kingbase Sync Service 启动");
        logger.info("========================================");

        // 1. 检查是否启用同步
        if (!syncConfig.isEnable()) {
            logger.info("同步功能已禁用，跳过自动同步");
            return;
        }

        // 2. 获取需要同步的表列表
        List<String> tables = syncConfig.getTables();
        if (tables == null || tables.isEmpty()) {
            logger.warn("未配置需要同步的表，请检查配置文件");
            return;
        }

        logger.info("需要同步的表: {}", tables);
        logger.info("同步模式: {}", syncConfig.getSyncMode());
        logger.info("定时任务表达式: {}", syncConfig.getCronExpression());

        // 3. 自动分析所有表结构
        logger.info("");
        logger.info("---------- 开始分析表结构 ----------");
        analyzeAllTables(tables);

        // 4. 执行首次同步
        logger.info("");
        logger.info("---------- 开始首次同步 ----------");
        performInitialSync(tables);

        logger.info("");
        logger.info("========================================");
        logger.info("启动完成，定时任务已启用");
        logger.info("下次同步时间将按照cron表达式执行: {}", syncConfig.getCronExpression());
        logger.info("========================================");
    }

    /**
     * 分析所有表结构
     */
    private void analyzeAllTables(List<String> tables) {
        for (String table : tables) {
            try {
                logger.info("分析表: {}", table);
                Map<String, Object> analysis = syncService.analyzeCollection(table);

                // 输出分析结果
                Long docCount = (Long) analysis.get("documentCount");
                Integer fieldCount = analysis.get("fields") != null ?
                    ((List<?>) analysis.get("fields")).size() : 0;
                Boolean exists = (Boolean) analysis.get("kingbaseTableExists");

                logger.info("  - MongoDB文档数: {}", docCount);
                logger.info("  - 字段数量: {}", fieldCount);
                logger.info("  - Kingbase表是否存在: {}", exists);

                if (analysis.containsKey("error")) {
                    logger.warn("  - 分析警告: {}", analysis.get("error"));
                }
            } catch (Exception e) {
                logger.error("分析表 {} 失败: {}", table, e.getMessage());
            }
        }
        logger.info("---------- 表结构分析完成 ----------");
    }

    /**
     * 执行首次同步
     */
    private void performInitialSync(List<String> tables) {
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> result = syncService.syncAll();
            long duration = System.currentTimeMillis() - startTime;

            logger.info("首次同步完成，耗时: {}ms", duration);

            // 输出同步结果
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                String tableName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map) {
                    Map<String, Object> tableResult = (Map<String, Object>) value;
                    String status = (String) tableResult.get("status");
                    Long inserted = (Long) tableResult.get("totalInserted");

                    logger.info("  - {}: 状态={}, 插入/更新={}条",
                        tableName, status, inserted);
                } else {
                    logger.info("  - {}: {}", tableName, value);
                }
            }
        } catch (Exception e) {
            logger.error("首次同步失败: {}", e.getMessage(), e);
        }
    }
}
