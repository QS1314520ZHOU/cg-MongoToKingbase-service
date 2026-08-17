# MongoDB to Kingbase 数据同步服务

## 功能介绍

这是一个将MongoDB数据同步到Kingbase数据库的服务，支持：
- **自动启动同步**：启动后自动分析表结构并执行首次同步
- 定时同步（可配置cron表达式）
- 全量同步和增量同步
- 自动创建目标表
- 批量数据插入
- REST API接口
- **同步标识字段**：自动添加`MongoToKingDate`字段记录同步时间
- **增量更新**：基于`editTime`字段自动更新变更的数据

## 工作流程

```
启动JAR包
    ↓
自动读取配置文件中的表列表
    ↓
自动分析每个表的结构
    ↓
执行首次全量同步
    ↓
定时任务按cron表达式自动同步
    ↓
增量同步：检查editTime > 上次同步时间的数据
    ↓
更新MongoToKingDate字段
```

## 环境要求

- Java 8+
- Maven 3.6+
- 银河麒麟 V11（或Linux系统）

## 快速开始

### 1. 编译打包

```bash
# 下载Kingbase JDBC驱动放到lib目录
# 编译打包
mvn clean package -DskipTests
```

### 2. 配置数据库连接

编辑 `src/main/resources/application.yml` 文件：

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

# 需要同步的表
sync:
  tables:
    - bedside
  # 同步模式：incremental（增量同步）
  sync-mode: incremental
  # MongoDB中的编辑时间字段
  edit-time-field: editTime
  # Kingbase中的同步时间字段名
  sync-field-name: MongoToKingDate
  # 定时任务cron表达式（每天凌晨2点执行）
  cron-expression: "0 0 2 * * ?"
```

### 3. 运行服务

```bash
# 方式1：直接运行JAR（启动后自动执行同步）
java -jar target/mongo-to-kingbase-1.0.0.jar

# 方式2：后台运行（推荐用于生产环境）
nohup java -jar target/mongo-to-kingbase-1.0.0.jar > nohup.log 2>&1 &

# 方式3：使用systemd服务（推荐）
sudo cp deploy/mongo-to-kingbase.service /etc/systemd/system/
sudo systemctl enable mongo-to-kingbase
sudo systemctl start mongo-to-kingbase
```

### 4. 启动后自动执行流程

程序启动后会自动执行以下流程：

```
1. 连接MongoDB和Kingbase数据库
2. 读取配置文件中需要同步的表列表
3. 自动分析每个表的结构（字段、类型、数据量）
4. 在Kingbase中创建表（如果不存在）
5. 执行首次全量同步
6. 启动定时任务，按cron表达式自动同步
```

启动日志示例：
```
========================================
MongoDB to Kingbase Sync Service 启动
========================================
需要同步的表: [bedside]
同步模式: incremental
定时任务表达式: 0 0 2 * * ?

---------- 开始分析表结构 ----------
分析表: bedside
  - MongoDB文档数: 12345
  - 字段数量: 20
  - Kingbase表是否存在: false
---------- 表结构分析完成 ----------

---------- 开始首次同步 ----------
首次同步完成，耗时: 5234ms
  - bedside: 状态=SUCCESS, 插入/更新=12345条
========================================
启动完成，定时任务已启用
下次同步时间将按照cron表达式执行: 0 0 2 * * ?
========================================
```

## API接口

服务启动后，提供以下REST API接口：

### 1. 触发全量同步
```
POST /api/sync/start
```

### 2. 触发单个表同步
```
POST /api/sync/start/{tableName}
```

### 3. 获取同步状态
```
GET /api/sync/status
```

### 4. 分析集合结构
```
GET /api/sync/analyze/{collectionName}
```

### 5. 获取所有集合
```
GET /api/sync/collections
```

### 6. 健康检查
```
GET /api/sync/health
```

## 配置说明

### MongoDB配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| mongo.host | MongoDB服务器地址 | - |
| mongo.port | MongoDB端口 | - |
| mongo.database | 数据库名称 | - |
| mongo.username | 用户名 | - |
| mongo.password | 密码 | - |
| mongo.auth-database | 认证数据库 | admin |

### Kingbase配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| kingbase.url | JDBC连接URL（PostgreSQL协议） | jdbc:postgresql://host:port/db |
| kingbase.username | 用户名 | - |
| kingbase.password | 密码 | - |
| kingbase.driver-class-name | 驱动类名 | org.postgresql.Driver |

### 同步配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| sync.cron-expression | 定时任务cron表达式 | 0 0 2 * * ? |
| sync.tables | 需要同步的表列表 | - |
| sync.batch-size | 批量插入大小 | 1000 |
| sync.enable | 是否启用定时同步 | true |
| sync.drop-table-before-sync | 同步前是否删除目标表 | false |
| sync.create-table-if-not-exists | 如果目标表不存在是否创建 | true |
| sync.sync-mode | 同步模式（full/incremental） | incremental |
| sync.timestamp-field | 增量同步时间字段 | - |
| sync.edit-time-field | MongoDB中的编辑时间字段 | editTime |
| sync.sync-field-name | Kingbase中的同步时间字段名 | MongoToKingDate |

## 部署到银河麒麟V11

### 1. 安装Java环境

```bash
# 检查Java版本
java -version

# 如果没有安装，使用yum安装
sudo yum install java-1.8.0-openjdk java-1.8.0-openjdk-devel
```

### 2. 上传JAR包

```bash
# 创建部署目录
sudo mkdir -p /opt/mongo-to-kingbase
sudo mkdir -p /opt/mongo-to-kingbase/logs

# 上传JAR包
scp target/mongo-to-kingbase-1.0.0.jar user@server:/opt/mongo-to-kingbase/
```

### 3. 配置systemd服务

创建服务文件 `/etc/systemd/system/mongo-to-kingbase.service`：

```ini
[Unit]
Description=MongoDB to Kingbase Sync Service
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/mongo-to-kingbase
ExecStart=/usr/bin/java -jar /opt/mongo-to-kingbase/mongo-to-kingbase-1.0.0.jar
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

### 4. 启动服务

```bash
# 重新加载systemd
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start mongo-to-kingbase

# 设置开机启动
sudo systemctl enable mongo-to-kingbase

# 查看状态
sudo systemctl status mongo-to-kingbase

# 查看日志
sudo journalctl -u mongo-to-kingbase -f
```

## 同步标识字段

### 功能说明

每个同步的表都会自动添加`MongoToKingDate`字段，用于记录数据的同步时间。

### 字段结构

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | SERIAL | Kingbase自增主键 |
| mongo_id | VARCHAR(24) | MongoDB的ObjectId，唯一索引 |
| MongoToKingDate | TIMESTAMP | 同步时间戳，每次同步时更新 |

### 增量同步机制

1. **首次同步**：执行全量同步，将MongoDB中所有数据写入Kingbase
2. **后续同步**：
   - 检查MongoDB中`editTime`字段晚于上次同步时间的记录
   - 对这些记录执行upsert操作（存在则更新，不存在则插入）
   - 更新`MongoToKingDate`字段为当前同步时间

### 配置示例

```yaml
sync:
  # 使用增量同步模式
  sync-mode: incremental
  # MongoDB中的编辑时间字段
  edit-time-field: editTime
  # Kingbase中的同步时间字段名
  sync-field-name: MongoToKingDate
```

## 常见问题

### 1. 连接MongoDB失败

检查：
- MongoDB服务是否启动
- 防火墙是否开放端口
- 用户名密码是否正确
- authDatabase是否正确

### 2. 连接Kingbase失败

检查：
- Kingbase服务是否启动
- JDBC驱动是否正确
- 用户名密码是否正确

### 3. 中文乱码

在JVM启动参数中添加：
```bash
java -Dfile.encoding=UTF-8 -jar mongo-to-kingbase-1.0.0.jar
```

## 技术支持

如有问题，请联系开发团队。
