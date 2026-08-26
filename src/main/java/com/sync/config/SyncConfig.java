package com.sync.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "sync")
public class SyncConfig {

    private static final Logger logger = LoggerFactory.getLogger(SyncConfig.class);

    private String cronExpression = "0 0 2 * * ?";
    private List<String> tables = new ArrayList<>();
    private int batchSize = 1000;
    private boolean enable = true;
    private boolean createTableIfNotExists = true;
    private String syncMode = "incremental";
    private String timestampField = "";
    private String editTimeField = "editTime";
    private String syncFieldName = "MongoToKingDate";

    private long stepInterval = 180000; // 默认3分钟

    // 配置表同步cron表达式（默认7天一次）
    private String configTableCronExpression = "0 0 3 1/7 * ?";

    // 配置表名称匹配模式（正则表达式，不区分大小写，默认包含config）
    private String configTablePattern = "(?i).*config.*";

    // 是否同步MongoDB中的所有表（忽略tables配置）
    private boolean syncAllCollections = false;

    // 排除的集合名称（正则表达式，不区分大小写）
    private String excludeCollectionsPattern = "(?i)^(system\\..*|tmp.*)$";

    public String getCronExpression() {
        return cronExpression;
    }

    public List<String> getTables() {
        return tables;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public boolean isEnable() {
        return enable;
    }

    public boolean isCreateTableIfNotExists() {
        return createTableIfNotExists;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public String getTimestampField() {
        return timestampField;
    }

    public String getEditTimeField() {
        return editTimeField;
    }

    public String getSyncFieldName() {
        return syncFieldName;
    }

    // Setter方法（@ConfigurationProperties需要）
    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public void setTables(List<String> tables) {
        this.tables = tables;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public void setCreateTableIfNotExists(boolean createTableIfNotExists) {
        this.createTableIfNotExists = createTableIfNotExists;
    }

    public void setSyncMode(String syncMode) {
        this.syncMode = syncMode;
    }

    public void setTimestampField(String timestampField) {
        this.timestampField = timestampField;
    }

    public void setEditTimeField(String editTimeField) {
        this.editTimeField = editTimeField;
    }

    public void setSyncFieldName(String syncFieldName) {
        this.syncFieldName = syncFieldName;
    }

    public long getStepInterval() {
        return stepInterval;
    }

    public void setStepInterval(long stepInterval) {
        this.stepInterval = stepInterval;
    }

    public String getConfigTableCronExpression() {
        return configTableCronExpression;
    }

    public void setConfigTableCronExpression(String configTableCronExpression) {
        this.configTableCronExpression = configTableCronExpression;
    }

    public String getConfigTablePattern() {
        return configTablePattern;
    }

    public void setConfigTablePattern(String configTablePattern) {
        this.configTablePattern = configTablePattern;
    }

    public boolean isSyncAllCollections() {
        return syncAllCollections;
    }

    public void setSyncAllCollections(boolean syncAllCollections) {
        this.syncAllCollections = syncAllCollections;
    }

    public String getExcludeCollectionsPattern() {
        return excludeCollectionsPattern;
    }

    public void setExcludeCollectionsPattern(String excludeCollectionsPattern) {
        this.excludeCollectionsPattern = excludeCollectionsPattern;
    }
}
