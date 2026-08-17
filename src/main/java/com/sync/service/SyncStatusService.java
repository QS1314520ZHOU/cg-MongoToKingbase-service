package com.sync.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 同步状态持久化服务
 * 将同步状态保存到Kingbase数据库，重启后不会丢失
 */
@Service
public class SyncStatusService {

    private static final Logger logger = LoggerFactory.getLogger(SyncStatusService.class);
    private static final String STATUS_TABLE = "sync_status";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 初始化：创建sync_status表
     */
    @PostConstruct
    public void init() {
        createStatusTable();
        loadSyncStatus();
    }

    /**
     * 创建sync_status表
     */
    private void createStatusTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS " + STATUS_TABLE + " (" +
                "id SERIAL PRIMARY KEY, " +
                "table_name VARCHAR(100) NOT NULL UNIQUE, " +
                "last_sync_time TIMESTAMP, " +
                "max_edit_time TIMESTAMP, " +
                "total_synced BIGINT DEFAULT 0, " +
                "created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";
            jdbcTemplate.execute(sql);
            logger.info("Sync status table created/verified");
        } catch (Exception e) {
            logger.error("Failed to create sync_status table: {}", e.getMessage(), e);
        }
    }

    /**
     * 从数据库加载同步状态
     */
    private void loadSyncStatus() {
        try {
            String sql = "SELECT table_name, last_sync_time, max_edit_time FROM " + STATUS_TABLE;
            jdbcTemplate.query(sql, (rs, rowNum) -> {
                String tableName = rs.getString("table_name");
                Date lastSyncTime = rs.getTimestamp("last_sync_time");
                Date maxEditTime = rs.getTimestamp("max_edit_time");

                // 存储到内存中
                SyncService.lastSyncTimes.put(tableName, lastSyncTime);
                SyncService.maxEditTimes.put(tableName, maxEditTime);

                logger.info("Loaded sync status for {}: lastSyncTime={}, maxEditTime={}",
                    tableName, lastSyncTime, maxEditTime);
                return null;
            });
        } catch (Exception e) {
            logger.error("Failed to load sync status: {}", e.getMessage(), e);
        }
    }

    /**
     * 保存同步状态
     */
    public void saveSyncStatus(String tableName, Date lastSyncTime, Date maxEditTime, long totalSynced) {
        try {
            String sql = "INSERT INTO " + STATUS_TABLE + " (table_name, last_sync_time, max_edit_time, total_synced, updated_time) " +
                "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (table_name) DO UPDATE SET " +
                "last_sync_time = EXCLUDED.last_sync_time, " +
                "max_edit_time = EXCLUDED.max_edit_time, " +
                "total_synced = EXCLUDED.total_synced, " +
                "updated_time = CURRENT_TIMESTAMP";

            jdbcTemplate.update(sql, tableName, lastSyncTime, maxEditTime, totalSynced);
            logger.debug("Saved sync status for {}: lastSyncTime={}, maxEditTime={}, totalSynced={}",
                tableName, lastSyncTime, maxEditTime, totalSynced);
        } catch (Exception e) {
            logger.error("Failed to save sync status for {}: {}", tableName, e.getMessage(), e);
        }
    }

    /**
     * 获取上次同步时间
     */
    public Date getLastSyncTime(String tableName) {
        try {
            String sql = "SELECT last_sync_time FROM " + STATUS_TABLE + " WHERE table_name = ?";
            return jdbcTemplate.queryForObject(sql, Date.class, tableName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取最大editTime
     */
    public Date getMaxEditTime(String tableName) {
        try {
            String sql = "SELECT max_edit_time FROM " + STATUS_TABLE + " WHERE table_name = ?";
            return jdbcTemplate.queryForObject(sql, Date.class, tableName);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 检查是否是首次同步
     */
    public boolean isFirstSync(String tableName) {
        return getLastSyncTime(tableName) == null;
    }

    /**
     * 清除指定表的同步状态
     * 当drop-table-before-sync为true时，需要重置同步状态
     */
    public void clearSyncStatus(String tableName) {
        try {
            String sql = "DELETE FROM " + STATUS_TABLE + " WHERE table_name = ?";
            int affected = jdbcTemplate.update(sql, tableName);
            logger.info("Cleared sync status for {}: {} rows deleted", tableName, affected);
        } catch (Exception e) {
            logger.error("Failed to clear sync status for {}: {}", tableName, e.getMessage(), e);
        }
    }
}
