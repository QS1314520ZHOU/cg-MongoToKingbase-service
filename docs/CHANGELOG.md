# 更新日志

## v1.1.1 (2026-08-17)

### 修复

- **构建错误**：移除kingbase8系统依赖，改用PostgreSQL驱动
- **数据库连接**：Kingbase兼容PostgreSQL协议，使用PostgreSQL JDBC驱动连接
- **配置更新**：更新JDBC URL格式为`jdbc:postgresql://host:port/db`

### 变更

- Kingbase JDBC驱动改为PostgreSQL驱动（版本42.6.0）
- 默认驱动类名改为`org.postgresql.Driver`
- JDBC连接URL格式改为`jdbc:postgresql://host:port/db`

## v1.1.0 (2026-08-17)

### 新增功能

#### 1. 同步标识字段
- 每个同步的表自动添加`MongoToKingDate`字段
- 记录每次同步的时间戳
- 支持自定义字段名

#### 2. 增量同步支持
- 基于MongoDB的`editTime`字段进行增量同步
- 当MongoDB中的数据`editTime`晚于上次同步时间时，自动更新对应记录
- 支持upsert模式：存在则更新，不存在则插入

### 配置项新增

| 参数 | 说明 | 默认值 |
|------|------|--------|
| sync.edit-time-field | MongoDB中的编辑时间字段 | editTime |
| sync.sync-field-name | Kingbase中的同步时间字段名 | MongoToKingDate |

### 配置示例

```yaml
sync:
  # 同步模式：incremental（增量同步）
  sync-mode: incremental
  # MongoDB中的编辑时间字段
  edit-time-field: editTime
  # Kingbase中的同步时间字段名
  sync-field-name: MongoToKingDate
```

### 表结构变化

每个同步的表都会包含以下额外字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | SERIAL | Kingbase自增主键 |
| mongo_id | VARCHAR(24) | MongoDB的ObjectId，唯一索引 |
| MongoToKingDate | TIMESTAMP | 同步时间戳，每次同步时更新 |

### 同步流程

1. **首次同步**：执行全量同步，将MongoDB中所有数据写入Kingbase
2. **后续同步**：
   - 检查MongoDB中`editTime`字段晚于上次同步时间的记录
   - 对这些记录执行upsert操作（存在则更新，不存在则插入）
   - 更新`MongoToKingDate`字段为当前同步时间

### API接口

#### 分析集合结构
```bash
GET /api/sync/analyze/{collectionName}
```

返回示例：
```json
{
  "collectionName": "bedside",
  "documentCount": 12345,
  "schema": {
    "fields": {
      "fieldName": "fieldType"
    }
  },
  "kingbaseTableName": "bedside",
  "kingbaseTableExists": false
}
```

#### 触发同步
```bash
# 同步所有表
POST /api/sync/start

# 同步单个表
POST /api/sync/start/{tableName}
```

#### 查看同步状态
```bash
GET /api/sync/status
```

### 使用方法

1. 编译打包：
```bash
mvn clean package -DskipTests
```

2. 启动服务：
```bash
java -jar target/mongo-to-kingbase-1.0.0.jar
```

3. 分析表结构：
```bash
curl http://localhost:8080/api/sync/analyze/bedside
```

4. 手动触发同步：
```bash
curl -X POST http://localhost:8080/api/sync/start/bedside
```

5. 查看同步状态：
```bash
curl http://localhost:8080/api/sync/status
```

### 注意事项

1. MongoDB中的文档需要包含`editTime`字段才能使用增量同步
2. 首次同步会执行全量同步，后续同步会根据`editTime`进行增量更新
3. `MongoToKingDate`字段会在每次同步时自动更新
4. 如果需要修改同步时间字段名，可以在配置文件中修改`sync.sync-field-name`

### 故障排查

1. 查看日志文件：
```bash
tail -f logs/sync-service.log
```

2. 检查同步状态：
```bash
curl http://localhost:8080/api/sync/status
```

3. 检查数据库连接：
```bash
# 测试MongoDB连接
mongosh --host 10.35.4.12 --port 32121 --username admin --password "Dxm99*" --authenticationDatabase admin SmartCare

# 测试Kingbase连接
psql -h 10.35.4.12 -p 54321 -U system -d kingbase
```

---

## v1.0.0 (2026-08-17)

### 初始版本

#### 功能
- MongoDB到Kingbase数据同步
- 定时同步（可配置cron表达式）
- 全量同步模式
- 自动创建目标表
- 批量数据插入
- REST API接口

#### 支持的数据类型
- String -> TEXT
- Integer -> INTEGER
- Long -> BIGINT
- Double -> NUMERIC
- Boolean -> BOOLEAN
- Date -> TIMESTAMP
- ObjectId -> VARCHAR(24)
- Array -> JSONB
- Document -> JSONB
