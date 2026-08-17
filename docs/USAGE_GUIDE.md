# 使用指南

## 快速开始

### 1. 编译打包

```bash
# 进入项目目录
cd cg-MongoToKingbase-service

# 编译打包
mvn clean package -DskipTests
```

### 2. 配置数据库

编辑 `src/main/resources/application.yml`：

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

### 3. 启动服务

```bash
# 直接运行
java -jar target/mongo-to-kingbase-1.0.0.jar

# 后台运行
nohup java -jar target/mongo-to-kingbase-1.0.0.jar > nohup.log 2>&1 &
```

### 4. 验证服务

```bash
# 健康检查
curl http://localhost:8080/api/sync/health

# 查看同步状态
curl http://localhost:8080/api/sync/status

# 查看MongoDB集合
curl http://localhost:8080/api/sync/collections
```

## 功能详解

### 1. 自动同步

程序启动后会自动执行：
1. 连接MongoDB和Kingbase数据库
2. 读取配置文件中需要同步的表列表
3. 自动分析每个表的结构
4. 在Kingbase中创建表（如果不存在）
5. 执行首次全量同步
6. 启动定时任务，按cron表达式自动同步

### 2. 定时同步

默认配置为每天凌晨2点执行同步。可自定义cron表达式：

```yaml
sync:
  # 每小时执行一次
  cron-expression: "0 0 * * * ?"

  # 每天凌晨3点执行
  cron-expression: "0 0 3 * * ?"

  # 每周一凌晨2点执行
  cron-expression: "0 0 2 ? * MON"

  # 每5分钟执行一次
  cron-expression: "0 */5 * * * ?"
```

### 3. 增量同步

基于`editTime`字段进行增量同步：

```yaml
sync:
  sync-mode: incremental
  edit-time-field: editTime
```

工作流程：
1. 首次同步：全量同步所有数据
2. 后续同步：查询MongoDB中`editTime > 上次同步时间`的记录
3. 执行upsert操作（存在则更新，不存在则插入）
4. 更新`MongoToKingDate`字段

### 4. 同步标识字段

每个同步的表都会自动添加`MongoToKingDate`字段：

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    -- 业务字段...
    MongoToKingDate TIMESTAMP,  -- 同步时间字段
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);
```

## API接口使用

### 1. 健康检查

```bash
curl http://localhost:8080/api/sync/health
```

响应示例：
```json
{
  "status": "UP",
  "service": "MongoDB to Kingbase Sync Service"
}
```

### 2. 获取MongoDB集合

```bash
curl http://localhost:8080/api/sync/collections
```

响应示例：
```json
["bedside", "patient", "doctor", "nurse"]
```

### 3. 分析集合结构

```bash
curl http://localhost:8080/api/sync/analyze/bedside
```

响应示例：
```json
{
  "collectionName": "bedside",
  "documentCount": 12345,
  "schema": {
    "fields": {
      "patientName": "String",
      "bedNumber": "Integer",
      "admissionDate": "Date"
    },
    "fieldCount": 20,
    "sampleSize": 100
  },
  "kingbaseTableName": "bedside",
  "kingbaseTableExists": true
}
```

### 4. 获取同步状态

```bash
curl http://localhost:8080/api/sync/status
```

响应示例：
```json
{
  "configuredTables": ["bedside"],
  "lastSyncTimes": {
    "bedside": "2026-08-17T02:00:00.000+0000"
  },
  "syncMode": "incremental",
  "batchSize": 1000,
  "cronExpression": "0 0 2 * * ?"
}
```

### 5. 触发全量同步

```bash
curl -X POST http://localhost:8080/api/sync/start
```

### 6. 触发单个表同步

```bash
curl -X POST http://localhost:8080/api/sync/start/bedside
```

## 部署到银河麒麟V11

### 1. 安装Java环境

```bash
# 检查Java版本
java -version

# 如果没有安装
sudo yum install java-1.8.0-openjdk java-1.8.0-openjdk-devel -y
```

### 2. 上传JAR包

```bash
# 创建部署目录
sudo mkdir -p /opt/mongo-to-kingbase
sudo mkdir -p /opt/mongo-to-kingbase/logs

# 上传JAR包
scp target/mongo-to-kingbase-1.0.0.jar root@server:/opt/mongo-to-kingbase/
```

### 3. 配置systemd服务

```bash
# 复制服务文件
sudo cp deploy/mongo-to-kingbase.service /etc/systemd/system/

# 重新加载systemd
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start mongo-to-kingbase

# 设置开机启动
sudo systemctl enable mongo-to-kingbase
```

### 4. 验证部署

```bash
# 查看服务状态
sudo systemctl status mongo-to-kingbase

# 查看日志
sudo journalctl -u mongo-to-kingbase -f
```

## 故障排查

### 1. 连接MongoDB失败

```bash
# 测试连接
telnet 10.35.4.12 32121

# 检查防火墙
sudo firewall-cmd --list-all
```

### 2. 连接Kingbase失败

```bash
# 测试连接
telnet 10.35.4.12 54321

# 检查JDBC驱动
ls -la /opt/mongo-to-kingbase/lib/
```

### 3. 同步失败

```bash
# 查看日志
tail -f /opt/mongo-to-kingbase/logs/nohup.log

# 检查同步状态
curl http://localhost:8080/api/sync/status

# 手动触发同步
curl -X POST http://localhost:8080/api/sync/start/bedside
```

### 4. 性能问题

调整配置：
```yaml
sync:
  batch-size: 500  # 减小批量大小
  sync-mode: incremental  # 使用增量同步
```

## 常用命令

```bash
# 启动服务
sudo systemctl start mongo-to-kingbase

# 停止服务
sudo systemctl stop mongo-to-kingbase

# 重启服务
sudo systemctl restart mongo-to-kingbase

# 查看状态
sudo systemctl status mongo-to-kingbase

# 查看日志
sudo journalctl -u mongo-to-kingbase -f

# 查看最近100行日志
sudo journalctl -u mongo-to-kingbase -n 100
```

## 注意事项

1. MongoDB中的文档需要包含`editTime`字段才能使用增量同步
2. 首次同步会执行全量同步，后续同步会根据`editTime`进行增量更新
3. `MongoToKingDate`字段会自动更新
4. 查看日志文件排查问题

## 技术支持

如有问题，请：
1. 查看本文档
2. 检查日志文件
3. 联系开发团队
