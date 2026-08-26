package com.sync.scheduler;

import com.sync.config.SyncConfig;
import com.sync.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SyncScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SyncScheduler.class);

    @Autowired
    private SyncService syncService;

    @Autowired
    private SyncConfig syncConfig;

    // 同步锁，确保同一时间只有一个同步任务在执行
    private final AtomicBoolean syncLock = new AtomicBoolean(false);

    // 配置表同步锁，确保同一时间只有一个配置表同步任务在执行
    private final AtomicBoolean configSyncLock = new AtomicBoolean(false);

    /**
     * 定时同步任务（普通表）
     * 使用配置文件中的cron表达式
     */
    @Scheduled(cron = "${sync.cron-expression:0 0 2 * * ?}")
    public void scheduledSync() {
        if (!syncConfig.isEnable()) {
            logger.info("同步功能已禁用，跳过定时同步");
            return;
        }

        // 检查是否有同步任务正在执行
        if (!syncLock.compareAndSet(false, true)) {
            logger.info("上一次同步任务仍在执行中，跳过本次定时同步");
            return;
        }

        logger.info("");
        logger.info("========== 定时同步开始 ==========");
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> result = syncService.syncAll();
            long duration = System.currentTimeMillis() - startTime;

            logger.info("定时同步完成，耗时: {}ms", duration);

            // 输出同步结果摘要
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                String tableName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map) {
                    Map<String, Object> tableResult = (Map<String, Object>) value;
                    String status = (String) tableResult.get("status");
                    Long inserted = (Long) tableResult.get("totalInserted");

                    logger.info("  - {}: 状态={}, 数量={}条",
                        tableName, status, inserted);
                }
            }

            logger.info("==================================");
        } catch (Exception e) {
            logger.error("定时同步失败: {}", e.getMessage(), e);
        } finally {
            // 释放同步锁
            syncLock.set(false);
        }
    }

    /**
     * 定时同步任务（配置表）
     * 对表名包含'config'的表使用单独的cron表达式（默认7天一次）
     */
    @Scheduled(cron = "${sync.config-table-cron-expression:0 0 3 1/7 * ?}")
    public void scheduledConfigTableSync() {
        if (!syncConfig.isEnable()) {
            logger.info("同步功能已禁用，跳过配置表定时同步");
            return;
        }

        // 检查是否有同步任务正在执行
        if (!syncLock.get()) {
            // 主同步未执行，可以执行配置表同步
            if (!configSyncLock.compareAndSet(false, true)) {
                logger.info("上一次配置表同步任务仍在执行中，跳过本次定时同步");
                return;
            }
        } else {
            // 主同步正在执行，跳过配置表同步
            logger.info("主同步任务正在执行中，跳过配置表定时同步");
            return;
        }

        logger.info("");
        logger.info("========== 配置表定时同步开始 ==========");
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> result = syncService.syncTablesByPattern(syncConfig.getConfigTablePattern());
            long duration = System.currentTimeMillis() - startTime;

            logger.info("配置表定时同步完成，耗时: {}ms", duration);

            // 输出同步结果摘要
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                String tableName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map) {
                    Map<String, Object> tableResult = (Map<String, Object>) value;
                    String status = (String) tableResult.get("status");
                    Long inserted = (Long) tableResult.get("totalInserted");

                    logger.info("  - {}: 状态={}, 数量={}条",
                        tableName, status, inserted);
                }
            }

            logger.info("===========================================");
        } catch (Exception e) {
            logger.error("配置表定时同步失败: {}", e.getMessage(), e);
        } finally {
            // 释放配置表同步锁
            configSyncLock.set(false);
        }
    }

    /**
     * 手动触发同步
     */
    public Map<String, Object> triggerSync() {
        // 检查是否有同步任务正在执行
        if (!syncLock.compareAndSet(false, true)) {
            logger.info("上一次同步任务仍在执行中，请稍后再试");
            throw new RuntimeException("同步任务正在执行中，请稍后再试");
        }

        logger.info("手动触发同步");
        long startTime = System.currentTimeMillis();

        try {
            Map<String, Object> result = syncService.syncAll();
            long duration = System.currentTimeMillis() - startTime;
            logger.info("手动同步完成，耗时: {}ms", duration);
            return result;
        } catch (Exception e) {
            logger.error("手动同步失败: {}", e.getMessage(), e);
            throw e;
        } finally {
            // 释放同步锁
            syncLock.set(false);
        }
    }

    /**
     * 手动触发单个表同步
     */
    public Map<String, Object> triggerSyncTable(String tableName) {
        // 检查是否有同步任务正在执行
        if (!syncLock.compareAndSet(false, true)) {
            logger.info("上一次同步任务仍在执行中，请稍后再试");
            throw new RuntimeException("同步任务正在执行中，请稍后再试");
        }

        logger.info("手动触发表同步: {}", tableName);
        try {
            return syncService.syncTable(tableName);
        } finally {
            // 释放同步锁
            syncLock.set(false);
        }
    }
}
