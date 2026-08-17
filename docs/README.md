# MongoDB to Kingbase 数据同步服务 - 文档中心

## 文档目录

### 核心文档
- [README.md](../README.md) - 项目介绍和快速开始
- [CHANGELOG.md](CHANGELOG.md) - 版本更新日志

### 部署文档
- [DEPLOY.md](../deploy/DEPLOY.md) - 银河麒麟V11部署指南

### 功能文档
- [bedside-analysis.md](bedside-analysis.md) - Bedside表结构分析指南

## 功能概览

### 1. 数据同步
- 支持MongoDB到Kingbase的数据同步
- 支持全量同步和增量同步
- 自动创建目标表结构

### 2. 同步标识字段
- 每个表自动添加`MongoToKingDate`字段
- 记录每次同步的时间戳
- 支持自定义字段名

### 3. 增量同步
- 基于`editTime`字段进行增量同步
- 自动检测数据变更
- 支持upsert模式

### 4. 定时任务
- 可配置cron表达式
- 支持手动触发
- 支持后台运行

### 5. REST API
- 健康检查接口
- 同步状态查询
- 手动触发同步
- 表结构分析

## 快速开始

### 1. 环境要求
- Java 8+
- Maven 3.6+
- MongoDB 4.4+
- Kingbase V009R002C014

### 2. 编译打包
```bash
mvn clean package -DskipTests
```

### 3. 配置数据库
编辑`src/main/resources/application.yml`：

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

# 同步配置
sync:
  tables:
    - bedside
  sync-mode: incremental
  edit-time-field: editTime
  sync-field-name: MongoToKingDate
```

### 4. 启动服务
```bash
java -jar target/mongo-to-kingbase-1.0.0.jar
```

### 5. 测试同步
```bash
# 分析表结构
curl http://localhost:8080/api/sync/analyze/bedside

# 触发同步
curl -X POST http://localhost:8080/api/sync/start/bedside

# 查看状态
curl http://localhost:8080/api/sync/status
```

## API接口列表

| 接口 | 方法 | 说明 |
|------|------|------|
| /api/sync/health | GET | 健康检查 |
| /api/sync/collections | GET | 获取MongoDB所有集合 |
| /api/sync/analyze/{collectionName} | GET | 分析集合结构 |
| /api/sync/status | GET | 获取同步状态 |
| /api/sync/start | POST | 触发全量同步 |
| /api/sync/start/{tableName} | POST | 触发单个表同步 |

## 配置说明

### MongoDB配置
```yaml
mongo:
  host: 10.35.4.12          # MongoDB服务器地址
  port: 32121               # MongoDB端口
  database: SmartCare       # 数据库名称
  username: admin           # 用户名
  password: "Dxm99*"        # 密码
  auth-database: admin      # 认证数据库
  connection-timeout: 30000 # 连接超时（毫秒）
  socket-timeout: 60000     # Socket超时（毫秒）
```

### Kingbase配置
```yaml
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase  # JDBC连接URL（PostgreSQL协议）
  username: system                                   # 用户名
  password: "chc3Xz3PS4N^x"                         # 密码
  driver-class-name: org.postgresql.Driver           # 驱动类名
  max-pool-size: 10                                  # 最大连接池大小
  min-idle: 5                                        # 最小空闲连接数
```

### 同步配置
```yaml
sync:
  cron-expression: "0 0 2 * * ?"                    # 定时任务cron表达式
  tables:                                            # 需要同步的表列表
    - bedside
  batch-size: 1000                                   # 批量大小
  enable: true                                       # 是否启用定时同步
  drop-table-before-sync: false                      # 同步前是否删除目标表
  create-table-if-not-exists: true                   # 如果目标表不存在是否创建
  sync-mode: incremental                             # 同步模式（full/incremental）
  timestamp-field: ""                                # 增量同步时间字段
  edit-time-field: editTime                          # MongoDB中的编辑时间字段
  sync-field-name: MongoToKingDate                   # Kingbase中的同步时间字段名
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
| null | TEXT |

## 同步流程

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

## 故障排查

### 1. 连接失败
- 检查数据库服务是否启动
- 检查防火墙配置
- 检查用户名密码是否正确

### 2. 同步失败
- 查看日志文件：`tail -f logs/sync-service.log`
- 检查同步状态：`curl http://localhost:8080/api/sync/status`
- 检查MongoDB中的`editTime`字段是否存在

### 3. 性能问题
- 调整批量大小：`sync.batch-size`
- 使用增量同步模式：`sync.sync-mode: incremental`
- 增加JVM内存：`-Xmx2048m`

## 技术支持

如有问题，请：
1. 查看本文档
2. 检查日志文件
3. 联系开发团队
