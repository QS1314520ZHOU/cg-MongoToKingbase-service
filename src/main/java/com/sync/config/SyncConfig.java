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
    private boolean dropTableBeforeSync = false;
    private boolean createTableIfNotExists = true;
    private String syncMode = "full";
    private String timestampField = "";
    private String editTimeField = "editTime";
    private String syncFieldName = "MongoToKingDate";

    private long stepInterval = 180000; // 默认3分钟

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

    public boolean isDropTableBeforeSync() {
        return dropTableBeforeSync;
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

    public void setDropTableBeforeSync(boolean dropTableBeforeSync) {
        this.dropTableBeforeSync = dropTableBeforeSync;
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
}
