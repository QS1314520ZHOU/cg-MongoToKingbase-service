# 项目概览

## 项目简介

MongoDB to Kingbase 数据同步服务是一个基于Spring Boot的应用程序，用于将MongoDB数据自动同步到Kingbase数据库。

## 核心功能

### 1. 自动启动同步
- 程序启动后自动读取配置文件
- 自动分析所有配置表的结构
- 自动执行首次全量同步
- 自动启动定时任务

### 2. 定时同步
- 支持cron表达式配置定时任务
- 默认每天凌晨2点执行
- 可自定义执行频率

### 3. 增量同步
- 基于`editTime`字段进行增量同步
- 自动检测数据变更
- 支持upsert模式（存在则更新，不存在则插入）

### 4. 同步标识字段
- 自动添加`MongoToKingDate`字段
- 记录每次同步的时间戳
- 支持自定义字段名

## 工作流程

```
┌─────────────────────────────────────────────────────────────┐
│                      启动JAR包                               │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              读取配置文件中的表列表                           │
│              (application.yml -> sync.tables)                │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              自动分析每个表的结构                             │
│              - 字段名、字段类型                              │
│              - 文档数量                                     │
│              - 嵌套结构                                     │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              在Kingbase中创建表（如果不存在）                 │
│              - 自动映射数据类型                              │
│              - 添加mongo_id唯一索引                         │
│              - 添加MongoToKingDate字段                      │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              执行首次全量同步                                 │
│              - 批量读取MongoDB数据                           │
│              - 批量写入Kingbase                              │
│              - 记录同步时间                                  │
└─────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│              启动定时任务                                     │
│              - 按cron表达式执行                              │
│              - 增量同步：检查editTime > 上次同步时间           │
│              - 更新MongoToKingDate字段                       │
└─────────────────────────────────────────────────────────────┘
```

## 项目结构

```
cg-MongoToKingbase-service/
├── pom.xml                                    # Maven配置
├── build.sh                                   # 构建脚本
├── README.md                                  # 项目说明
├── .gitignore                                 # Git忽略文件
│
├── src/
│   └── main/
│       ├── java/
│       │   └── com/sync/
│       │       ├── MongoToKingbaseApplication.java  # 主应用类
│       │       ├── config/
│       │       │   ├── MongoConfig.java             # MongoDB配置
│       │       │   ├── KingbaseConfig.java          # Kingbase配置
│       │       │   ├── SyncConfig.java              # 同步配置
│       │       │   └── ApplicationStartupRunner.java # 启动自动执行
│       │       ├── service/
│       │       │   ├── MongoReaderService.java      # MongoDB读取服务
│       │       │   ├── KingbaseWriterService.java   # Kingbase写入服务
│       │       │   └── SyncService.java             # 同步协调服务
│       │       ├── scheduler/
│       │       │   └── SyncScheduler.java           # 定时任务调度器
│       │       └── controller/
│       │           └── SyncController.java          # REST API控制器
│       └── resources/
│           └── application.yml                       # 配置文件
│
├── lib/                                         # Kingbase JDBC驱动
├── deploy/                                      # 部署文件
│   ├── DEPLOY.md                               # 部署指南
│   ├── start.sh                                # 启动脚本
│   ├── stop.sh                                 # 停止脚本
│   ├── test-connection.sh                      # 连接测试脚本
│   └── mongo-to-kingbase.service               # systemd服务文件
│
└── docs/                                        # 文档目录
    ├── README.md                               # 文档中心
    ├── CHANGELOG.md                            # 更新日志
    ├── bedside-analysis.md                     # Bedside表分析指南
    ├── STARTUP_LOG.md                          # 启动日志示例
    └── PROJECT_OVERVIEW.md                     # 项目概览
```

## 配置说明

### 数据库配置

```yaml
# MongoDB配置
mongo:
  host: 10.35.4.12
  port: 32121
  database: SmartCare
  username: admin
  password: "Dxm99*"

# Kingbase配置（使用PostgreSQL协议连接）
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase
  username: system
  password: "chc3Xz3PS4N^x"
```

### 同步配置

```yaml
sync:
  # 需要同步的表列表
  tables:
    - bedside
  # 同步模式：full（全量）或 incremental（增量）
  sync-mode: incremental
  # MongoDB中的编辑时间字段
  edit-time-field: editTime
  # Kingbase中的同步时间字段名
  sync-field-name: MongoToKingDate
  # 定时任务cron表达式（每天凌晨2点执行）
  cron-expression: "0 0 2 * * ?"
  # 批量大小
  batch-size: 1000
```

## API接口

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/sync/health | GET | 健康检查 |
| /api/sync/collections | GET | 获取MongoDB所有集合 |
| /api/sync/analyze/{collectionName} | GET | 分析集合结构 |
| /api/sync/status | GET | 获取同步状态 |
| /api/sync/start | POST | 触发全量同步 |
| /api/sync/start/{tableName} | POST | 触发单个表同步 |

## 数据类型映射

| MongoDB类型 | Kingbase类型 |
|------------|--------------|
| String | TEXT |
| Integer | INTEGER |
| Long | BIGINT |
| Double | NUMERIC |
| Boolean | BOOLEAN |
| Date | TIMESTAMP |
| ObjectId | VARCHAR(24) |
| Array | JSONB |
| Document | JSONB |
| null | TEXT |

## 表结构示例

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    -- 业务字段
    patient_name TEXT,
    bed_number INTEGER,
    admission_date TIMESTAMP,
    -- 同步标识字段
    MongoToKingDate TIMESTAMP,
    -- 唯一索引
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);

-- 创建索引
CREATE INDEX idx_bedside_mongo_id ON bedside(mongo_id);
CREATE INDEX idx_bedside_mongotokingdate ON bedside(MongoToKingDate);
```

## 同步流程详解

### 全量同步
1. 读取MongoDB集合所有数据
2. 在Kingbase创建表（如果不存在）
3. 批量插入数据
4. 设置`MongoToKingDate`字段为当前时间

### 增量同步
1. 检查`lastSyncTime`（上次同步时间）
2. 查询MongoDB中`editTime > lastSyncTime`的记录
3. 对这些记录执行upsert操作
4. 更新`MongoToKingDate`字段
5. 更新`lastSyncTime`

## 常见问题

### 1. 连接失败
- 检查数据库服务是否启动
- 检查防火墙配置
- 检查用户名密码是否正确

### 2. 同步失败
- 查看日志文件
- 检查MongoDB中的`editTime`字段是否存在
- 检查Kingbase表结构是否正确

### 3. 性能问题
- 调整批量大小
- 使用增量同步模式
- 增加JVM内存

## 技术支持

如有问题，请：
1. 查看本文档
2. 检查日志文件
3. 联系开发团队
