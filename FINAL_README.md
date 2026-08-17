# MongoDB to Kingbase 数据同步服务 - 完成版

## 项目简介

这是一个将MongoDB数据自动同步到Kingbase数据库的服务，支持定时同步、增量同步和自动创建表结构。

## 功能特性

### 1. 自动启动同步
- 程序启动后自动读取配置文件
- 自动分析MongoDB表结构
- 自动创建Kingbase表
- 自动执行首次全量同步
- 自动启动定时任务

### 2. 增量同步
- 基于`editTime`字段进行增量同步
- 自动检测数据变更
- 支持upsert模式（存在则更新，不存在则插入）

### 3. 同步标识字段
- 自动添加`MongoToKingDate`字段
- 记录每次同步的时间戳

### 4. 定时任务
- 支持cron表达式配置
- 默认每天凌晨2点执行

## 快速开始

### 1. 环境要求
- Java 8+
- Maven 3.6+
- MongoDB 4.4+
- Kingbase V009R002C014

### 2. 编译打包
```bash
cd cg-MongoToKingbase-service
mvn clean package -DskipTests
```

### 3. 配置数据库
编辑 `src/main/resources/application.yml`：

```yaml
# MongoDB配置
mongo:
  host: 10.35.4.12
  port: 32121
  database: SmartCare
  username: admin
  password: "Dxm99*"

# Kingbase配置
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase?stringtype=unspecified
  username: system
  password: 'MjJuX6tKd#ac2'
  driver-class-name: org.postgresql.Driver

# 同步配置
sync:
  tables:
    - bedside
  sync-mode: incremental
  edit-time-field: editTime
  sync-field-name: MongoToKingDate
  cron-expression: "0 0 2 * * ?"
```

### 4. 启动服务
```bash
# 直接运行
java -jar target/mongo-to-kingbase-1.0.0.jar

# 后台运行
nohup java -jar target/mongo-to-kingbase-1.0.0.jar > nohup.log 2>&1 &
```

## 启动日志示例

```
========================================
MongoDB to Kingbase Sync Service 启动
========================================
需要同步的表: [bedside]
同步模式: incremental
定时任务表达式: 0 0 2 * * ?

---------- 开始分析表结构 ----------
分析表: bedside
  - MongoDB文档数: 78
  - 字段数量: 12
  - Kingbase表是否存在: true
---------- 表结构分析完成 ----------

---------- 开始首次同步 ----------
首次同步完成，耗时: 1383ms
  - bedside: 状态=SUCCESS, 插入/更新=78条
========================================
启动完成，定时任务已启用
下次同步时间将按照cron表达式执行: 0 0 2 * * ?
========================================
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
| /api/sync/test-connection | GET | 测试数据库连接 |

## 配置说明

### 数据库配置

```yaml
# MongoDB
mongo:
  host: 10.35.4.12
  port: 32121
  database: SmartCare
  username: admin
  password: "Dxm99*"

# Kingbase
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase?stringtype=unspecified
  username: system
  password: 'MjJuX6tKd#ac2'
  driver-class-name: org.postgresql.Driver
```

### 同步配置

```yaml
sync:
  tables:                    # 需要同步的表列表
    - bedside
  sync-mode: incremental     # 同步模式：full/incremental
  edit-time-field: editTime  # MongoDB中的编辑时间字段
  sync-field-name: MongoToKingDate  # Kingbase中的同步时间字段
  cron-expression: "0 0 2 * * ?"  # 定时任务cron表达式
  batch-size: 1000           # 批量大小
```

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

## 表结构示例

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    _id VARCHAR(24),
    pid VARCHAR(100),
    code VARCHAR(100),
    time TIMESTAMP,
    strVal TEXT,
    valid BOOLEAN,
    editTime TIMESTAMP,
    _class VARCHAR(100),
    editUser VARCHAR(100),
    history JSONB,
    fVal NUMERIC,
    remark TEXT,
    MongoToKingDate TIMESTAMP,
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);
```

## 部署到银河麒麟V11

### 1. 安装Java
```bash
sudo yum install java-1.8.0-openjdk java-1.8.0-openjdk-devel -y
```

### 2. 上传JAR包
```bash
sudo mkdir -p /opt/mongo-to-kingbase
scp target/mongo-to-kingbase-1.0.0.jar root@server:/opt/mongo-to-kingbase/
```

### 3. 配置systemd服务
```bash
sudo cp deploy/mongo-to-kingbase.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl start mongo-to-kingbase
sudo systemctl enable mongo-to-kingbase
```

### 4. 验证部署
```bash
# 查看状态
sudo systemctl status mongo-to-kingbase

# 查看日志
sudo journalctl -u mongo-to-kingbase -f

# 测试API
curl http://localhost:8080/api/sync/health
```

## 常见问题

### 1. Kingbase连接失败
- 检查密码是否正确
- 检查防火墙是否开放54321端口
- 检查Kingbase服务是否启动

### 2. JSON字段格式错误
- 确保Kingbase表使用JSONB类型存储嵌套文档
- 检查MongoDB中的数据格式

### 3. 同步性能问题
- 调整`batch-size`参数
- 使用增量同步模式

## 项目结构

```
cg-MongoToKingbase-service/
├── pom.xml                                    # Maven配置
├── src/main/java/com/sync/
│   ├── MongoToKingbaseApplication.java       # 主应用类
│   ├── config/
│   │   ├── MongoConfig.java                  # MongoDB配置
│   │   ├── KingbaseConfig.java               # Kingbase配置
│   │   ├── SyncConfig.java                   # 同步配置
│   │   └── ApplicationStartupRunner.java     # 启动自动执行
│   ├── service/
│   │   ├── MongoReaderService.java           # MongoDB读取服务
│   │   ├── KingbaseWriterService.java        # Kingbase写入服务
│   │   └── SyncService.java                  # 同步协调服务
│   ├── scheduler/
│   │   └── SyncScheduler.java                # 定时任务调度器
│   └── controller/
│       └── SyncController.java               # REST API控制器
├── src/main/resources/
│   └── application.yml                        # 配置文件
├── target/
│   └── mongo-to-kingbase-1.0.0.jar           # JAR包（26MB）
└── deploy/                                     # 部署文件
```

## 技术支持

如有问题，请查看日志文件或联系开发团队。
