# Bedside 表结构分析指南

## 概述

本文档说明如何分析MongoDB中的bedside表结构，并将其转换为Kingbase数据库表。

## 功能特性

### 1. 自动同步标识字段
- MongoDB和Kingbase表都会添加`MongoToKingDate`字段
- 记录每次同步的时间戳

### 2. 增量同步支持
- 基于MongoDB的`editTime`字段进行增量同步
- 当MongoDB中的数据`editTime`晚于上次同步时间时，自动更新对应记录
- 支持upsert模式：存在则更新，不存在则插入

## 分析步骤

### 1. 启动服务

```bash
# 编译打包
mvn clean package -DskipTests

# 启动服务
java -jar target/mongo-to-kingbase-1.0.0.jar
```

### 2. 使用API分析表结构

```bash
# 分析bedside表结构
curl http://localhost:8080/api/sync/analyze/bedside

# 获取所有集合
curl http://localhost:8080/api/sync/collections
```

### 3. 分析结果说明

API返回的分析结果包含以下信息：

```json
{
  "collectionName": "bedside",
  "documentCount": 12345,
  "schema": {
    "fields": {
      "fieldName": "fieldType",
      ...
    },
    "fieldCount": 20,
    "sampleSize": 100
  },
  "fields": ["field1", "field2", ...],
  "kingbaseTableName": "bedside",
  "kingbaseTableExists": false
}
```

### 4. 类型映射规则

MongoDB类型到Kingbase类型的映射：

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

### 5. 嵌套文档处理

MongoDB中的嵌套文档会被转换为JSONB类型存储在Kingbase中。

例如：
```json
{
  "name": "张三",
  "address": {
    "city": "北京",
    "district": "朝阳区"
  }
}
```

会存储为：
- `name`: TEXT
- `address`: JSONB (存储完整的JSON对象)

### 6. 数组处理

MongoDB中的数组会被转换为JSONB类型。

例如：
```json
{
  "tags": ["tag1", "tag2", "tag3"]
}
```

会存储为：
- `tags`: JSONB (存储JSON数组)

## 同步标识字段

### 字段说明

每个同步的表都会包含以下字段：

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | SERIAL | Kingbase自增主键 |
| mongo_id | VARCHAR(24) | MongoDB的ObjectId，唯一索引 |
| MongoToKingDate | TIMESTAMP | 同步时间戳，每次同步时更新 |

### 示例表结构

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    -- 业务字段
    patient_name TEXT,
    bed_number INTEGER,
    -- ... 其他字段
    -- 同步标识字段
    MongoToKingDate TIMESTAMP,
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);
```

## 增量同步机制

### 工作原理

1. **首次同步**：执行全量同步，将MongoDB中所有数据写入Kingbase
2. **后续同步**：
   - 检查MongoDB中`editTime`字段晚于上次同步时间的记录
   - 对这些记录执行upsert操作（存在则更新，不存在则插入）
   - 更新`MongoToKingDate`字段为当前同步时间

### 配置说明

在`application.yml`中配置：

```yaml
sync:
  # 同步模式：incremental（增量同步）
  sync-mode: incremental
  # MongoDB中的编辑时间字段
  edit-time-field: editTime
  # Kingbase中的同步时间字段名
  sync-field-name: MongoToKingDate
```

### 同步流程

```
1. 检查lastSyncTime（上次同步时间）
   |
   v
2. 如果是首次同步（lastSyncTime为null）
   |
   v
3. 执行全量同步，读取MongoDB所有数据
   |
   v
4. 写入Kingbase，设置MongoToKingDate = 当前时间
   |
   v
5. 记录lastSyncTime = 当前时间
   |
   v
6. 下次同步时，查询editTime > lastSyncTime的记录
   |
   v
7. 执行upsert操作，更新或插入记录
   |
   v
8. 更新MongoToKingDate字段
```

## 手动分析方法

如果API分析不准确，可以使用以下方法手动分析：

### 方法1：使用MongoDB Compass

1. 下载并安装MongoDB Compass
2. 连接到MongoDB (10.35.4.12:32121)
3. 选择SmartCare数据库
4. 选择bedside集合
5. 查看Schema标签页

### 方法2：使用mongosh

```bash
# 连接到MongoDB
mongosh --host 10.35.4.12 --port 32121 --username admin --password "Dxm99*" --authenticationDatabase admin SmartCare

# 查看集合结构
db.bedside.findOne()

# 查看字段类型
db.bedside.aggregate([
  { $limit: 100 },
  { $project: { 
      _id: 0,
      fields: { $objectToArray: "$$ROOT" }
    }
  },
  { $unwind: "$fields" },
  { $group: {
      _id: "$fields.k",
      types: { $addToSet: { $type: "$fields.v" } }
    }
  }
])

# 查看editTime字段的最大值
db.bedside.find().sort({editTime: -1}).limit(1)
```

## 生成Kingbase建表语句

根据分析结果，可以手动生成建表语句：

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    -- 根据实际字段添加
    field_name TEXT,
    numeric_field NUMERIC,
    boolean_field BOOLEAN,
    date_field TIMESTAMP,
    json_field JSONB,
    -- 同步标识字段
    MongoToKingDate TIMESTAMP,
    -- 添加索引
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);

-- 创建索引
CREATE INDEX idx_bedside_mongo_id ON bedside(mongo_id);
CREATE INDEX idx_bedside_mongotokingdate ON bedside(MongoToKingDate);
```

## 注意事项

1. **主键处理**：MongoDB的`_id`字段会转换为`mongo_id`字段，并创建唯一索引
2. **同步字段**：`MongoToKingDate`字段会在每次同步时自动更新
3. **增量同步**：确保MongoDB中的文档包含`editTime`字段
4. **嵌套文档**：复杂的嵌套文档建议使用JSONB类型
5. **数组字段**：数组字段使用JSONB类型存储
6. **中文字段名**：字段名中的特殊字符会被替换为下划线
7. **大文本字段**：超长文本考虑使用TEXT类型而非VARCHAR

## 测试同步

分析完成后，可以测试同步：

```bash
# 同步bedside表
curl -X POST http://localhost:8080/api/sync/start/bedside

# 查看同步状态
curl http://localhost:8080/api/sync/status

# 查看Kingbase中的数据
psql -h 10.35.4.12 -p 54321 -U system -d kingbase -c "SELECT * FROM bedside LIMIT 10;"
```

## 故障排查

### 问题1：字段类型不匹配

如果某些字段类型推断不准确，可以：
1. 修改`MongoReaderService`中的类型映射逻辑
2. 或者在Kingbase中手动调整表结构

### 问题2：数据转换错误

检查日志文件：
```bash
tail -f logs/sync-service.log
```

### 问题3：性能问题

调整批量大小：
```yaml
sync:
  batch-size: 500  # 减小批量大小
```

### 问题4：增量同步不工作

检查：
1. MongoDB中是否存在`editTime`字段
2. 配置文件中的`edit-time-field`是否正确
3. 查看日志中的同步时间记录
