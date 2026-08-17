# 启动日志示例

## 正常启动日志

```
2026-08-17 10:00:00 [main] INFO  com.sync.MongoToKingbaseApplication - Starting MongoToKingbaseApplication
2026-08-17 10:00:01 [main] INFO  com.sync.MongoToKingbaseApplication - No active profile set, falling back to default profiles: default
2026-08-17 10:00:05 [main] INFO  com.sync.config.MongoConfig - Connecting to MongoDB at 10.35.4.12:32121/SmartCare
2026-08-17 10:00:06 [main] INFO  com.sync.config.KingbaseConfig - Connecting to Kingbase at jdbc:postgresql://10.35.4.12:54321/kingbase
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - ========================================
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - MongoDB to Kingbase Sync Service 启动
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - ========================================
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - 需要同步的表: [bedside]
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - 同步模式: incremental
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - 定时任务表达式: 0 0 2 * * ?
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner -
2026-08-17 10:00:10 [main] INFO  c.s.c.ApplicationStartupRunner - ---------- 开始分析表结构 ----------
2026-08-17 10:00:11 [main] INFO  c.s.c.ApplicationStartupRunner - 分析表: bedside
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner -   - MongoDB文档数: 12345
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner -   - 字段数量: 20
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner -   - Kingbase表是否存在: false
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner - ---------- 表结构分析完成 ----------
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner -
2026-08-17 10:00:12 [main] INFO  c.s.c.ApplicationStartupRunner - ---------- 开始首次同步 ----------
2026-08-17 10:00:12 [main] INFO  c.s.s.SyncService - Starting sync for collection: bedside
2026-08-17 10:00:12 [main] INFO  c.s.s.SyncService - Using full sync mode
2026-08-17 10:00:13 [main] INFO  c.s.s.SyncService - Progress: 1000 / 12345 documents
2026-08-17 10:00:14 [main] INFO  c.s.s.SyncService - Progress: 2000 / 12345 documents
2026-08-17 10:00:15 [main] INFO  c.s.s.SyncService - Progress: 3000 / 12345 documents
...
2026-08-17 10:00:25 [main] INFO  c.s.s.SyncService - Progress: 12000 / 12345 documents
2026-08-17 10:00:25 [main] INFO  c.s.s.SyncService - Progress: 12345 / 12345 documents
2026-08-17 10:00:25 [main] INFO  c.s.s.SyncService - Sync completed for bedside: 12345 documents in 13000ms
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner - 首次同步完成，耗时: 13000ms
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner -   - bedside: 状态=SUCCESS, 插入/更新=12345条
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner -
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner - ========================================
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner - 启动完成，定时任务已启用
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner - 下次同步时间将按照cron表达式执行: 0 0 2 * * ?
2026-08-17 10:00:25 [main] INFO  c.s.c.ApplicationStartupRunner - ========================================
2026-08-17 10:00:26 [main] INFO  com.sync.MongoToKingbaseApplication - Started MongoToKingbaseApplication in 26.543 seconds
```

## 定时同步日志

```
2026-08-18 02:00:00 [scheduling-1] INFO  c.s.s.SyncScheduler - ==========================================
2026-08-18 02:00:00 [scheduling-1] INFO  c.s.s.SyncScheduler - 定时同步开始
2026-08-18 02:00:00 [scheduling-1] INFO  c.s.s.SyncScheduler - ==========================================
2026-08-18 02:00:00 [scheduling-1] INFO  c.s.s.SyncService - Starting sync for collection: bedside
2026-08-18 02:00:00 [scheduling-1] INFO  c.s.s.SyncService - Using incremental sync mode based on editTime field: editTime
2026-08-18 02:00:01 [scheduling-1] INFO  c.s.s.SyncService - Read 5 documents with editTime > Mon Aug 17 02:00:00 CST 2026
2026-08-18 02:00:01 [scheduling-1] INFO  c.s.s.SyncService - Sync completed for bedside: 5 documents in 1000ms
2026-08-18 02:00:01 [scheduling-1] INFO  c.s.s.SyncScheduler - 定时同步完成，耗时: 1000ms
2026-08-18 02:00:01 [scheduling-1] INFO  c.s.s.SyncScheduler -   - bedside: 状态=SUCCESS, 数量=5条
2026-08-18 02:00:01 [scheduling-1] INFO  c.s.s.SyncScheduler - ==========================================
```

## 错误日志

```
2026-08-17 10:00:05 [main] ERROR c.s.c.MongoConfig - Failed to connect to MongoDB: Connection refused
2026-08-17 10:00:05 [main] ERROR c.s.MongoToKingbaseApplication - Application run failed
org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'mongoClient'
    at org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory.createBean(AbstractAutowireCapableBeanFactory.java:520)
    ...
Caused by: com.mongodb.MongoSocketOpenException: Connection refused
    at com.mongodb.connection.SocketStream.open(SocketStream.java:63)
    ...
```

## 常见日志信息

### 同步状态
- `SUCCESS` - 同步成功
- `SKIPPED` - 跳过同步（表为空或不存在）
- `ERROR` - 同步失败

### 同步模式
- `full` - 全量同步
- `incremental` - 增量同步

### 关键字段
- `mongoDocCount` - MongoDB文档数量
- `totalInserted` - 插入/更新的记录数
- `durationMs` - 同步耗时（毫秒）
- `lastSyncTime` - 上次同步时间
- `maxEditTime` - MongoDB中最大的editTime
