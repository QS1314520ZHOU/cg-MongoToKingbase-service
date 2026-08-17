# 增量同步逻辑说明

## 改进后的同步策略

### 1. 持久化同步状态

**问题**：重启后`lastSyncTime`丢失，导致全量同步

**解决方案**：在Kingbase中创建`sync_status`表，持久化同步状态

```sql
CREATE TABLE IF NOT EXISTS sync_status (
    id SERIAL PRIMARY KEY,
    table_name VARCHAR(100) NOT NULL UNIQUE,
    last_sync_time TIMESTAMP,
    max_edit_time TIMESTAMP,
    total_synced BIGINT DEFAULT 0,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**效果**：
- 程序启动时从数据库读取`lastSyncTime`
- 重启后不会丢失同步状态
- 只同步新增/修改的数据

### 2. 双重检测机制

#### 机制一：时间戳检测（有editTime字段）

```java
// 查询条件：editTime > lastSyncTime
db.bedside.find({editTime: {$gt: lastSyncTime}})
```

**适用场景**：
- MongoDB文档包含`editTime`字段
- 每次更新时会修改`editTime`

#### 机制二：哈希校验（无editTime字段）

```java
// 1. 计算MongoDB文档的MD5哈希
String mongoHash = calculateHash(mongoDoc);

// 2. 获取Kingbase中对应记录的哈希
String kingbaseHash = getHashByMongoId(mongoId);

// 3. 比较哈希值
if (!mongoHash.equals(kingbaseHash)) {
    // 数据有变化，执行更新
}
```

**适用场景**：
- MongoDB文档没有`editTime`字段
- 需要检测数据内容是否变化

### 3. 哈希计算规则

```java
// 排除的字段
- _id (MongoDB主键)
- MongoToKingDate (同步时间)
- _data_hash (哈希字段本身)

// 计算方法
1. 按字段名排序
2. 转换为JSON字符串
3. 计算MD5哈希
```

## 同步流程

### 首次同步

```
1. 检查sync_status表
   ↓
2. lastSyncTime = null (首次同步)
   ↓
3. 执行全量同步
   ↓
4. 保存同步状态到数据库
   ↓
5. 更新lastSyncTime
```

### 后续同步（有editTime字段）

```
1. 从数据库读取lastSyncTime
   ↓
2. 查询MongoDB: editTime > lastSyncTime
   ↓
3. 对比数据，执行upsert
   ↓
4. 更新sync_status表
```

### 后续同步（无editTime字段）

```
1. 从数据库读取lastSyncTime
   ↓
2. 读取MongoDB数据
   ↓
3. 计算每条数据的哈希值
   ↓
4. 获取Kingbase中对应的哈希值
   ↓
5. 对比哈希值
   ├─ 相同 → 跳过
   └─ 不同 → 更新
   ↓
6. 更新sync_status表
```

## 表结构

### Kingbase表结构

```sql
CREATE TABLE bedside (
    id SERIAL PRIMARY KEY,
    mongo_id VARCHAR(24) NOT NULL,
    -- 业务字段...
    MongoToKingDate TIMESTAMP,      -- 同步时间
    _data_hash VARCHAR(32),          -- 数据哈希
    CONSTRAINT uk_mongo_id UNIQUE (mongo_id)
);
```

### sync_status表

```sql
CREATE TABLE sync_status (
    id SERIAL PRIMARY KEY,
    table_name VARCHAR(100) NOT NULL UNIQUE,
    last_sync_time TIMESTAMP,
    max_edit_time TIMESTAMP,
    total_synced BIGINT DEFAULT 0,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 配置说明

```yaml
sync:
  # 同步模式
  sync-mode: incremental

  # 编辑时间字段（可选）
  # 如果MongoDB文档有此字段，使用时间戳检测
  # 如果没有，使用哈希校验
  edit-time-field: editTime

  # 同步时间字段名
  sync-field-name: MongoToKingDate
```

## 场景示例

### 场景1：有editTime字段

```
MongoDB文档:
{
    _id: ObjectId("..."),
    name: "张三",
    editTime: ISODate("2026-08-17T10:00:00Z")
}

同步流程:
1. lastSyncTime = 2026-08-16 02:00:00
2. 查询: editTime > 2026-08-16 02:00:00
3. 找到这条文档
4. 执行upsert
```

### 场景2：无editTime字段

```
MongoDB文档:
{
    _id: ObjectId("..."),
    name: "张三",
    age: 25
}

同步流程:
1. 计算哈希: md5("{age=25, name=张三}") = "abc123"
2. 获取Kingbase哈希: "def456"
3. 对比: abc123 != def456
4. 执行更新
```

### 场景3：数据未变化

```
MongoDB文档:
{
    _id: ObjectId("..."),
    name: "张三",
    age: 25
}

同步流程:
1. 计算哈希: md5("{age=25, name=张三}") = "abc123"
2. 获取Kingbase哈希: "abc123"
3. 对比: abc123 == abc123
4. 跳过，不更新
```

## 性能优化

### 1. 哈希索引

```sql
-- 在mongo_id上创建索引
CREATE INDEX idx_bedside_mongo_id ON bedside(mongo_id);

-- 在哈希字段上创建索引（可选）
CREATE INDEX idx_bedside_hash ON bedside(_data_hash);
```

### 2. 批量处理

```yaml
sync:
  batch-size: 1000  # 增大批量大小
```

### 3. 增量同步 vs 全量同步

| 特性 | 增量同步 | 全量同步 |
|------|----------|----------|
| 首次同步 | 全量 | 全量 |
| 后续同步 | 增量 | 全量 |
| 性能 | 高 | 低 |
| 数据一致性 | 高 | 高 |
| 适用场景 | 大数据量 | 小数据量 |

## 注意事项

1. **首次同步总是全量的** - 因为没有lastSyncTime
2. **哈希校验有性能开销** - 需要逐条对比
3. **哈希字段不参与业务** - 只用于同步校验
4. **sync_status表是全局的** - 所有表共享
5. **重启后不会全量同步** - 从数据库读取lastSyncTime

## 故障排查

### 问题1：重启后全量同步

**原因**：sync_status表不存在或数据丢失

**解决**：
```sql
-- 检查sync_status表
SELECT * FROM sync_status;

-- 手动插入同步状态
INSERT INTO sync_status (table_name, last_sync_time)
VALUES ('bedside', '2026-08-17 02:00:00');
```

### 问题2：数据不一致

**原因**：哈希校验未生效

**解决**：
```sql
-- 检查哈希字段
SELECT mongo_id, _data_hash FROM bedside LIMIT 10;

-- 重新同步（全量）
curl -X POST http://localhost:8080/api/sync/start/bedside
```

### 问题3：同步时间不准确

**原因**：lastSyncTime未正确更新

**解决**：
```sql
-- 检查同步状态
SELECT * FROM sync_status WHERE table_name = 'bedside';

-- 手动更新
UPDATE sync_status
SET last_sync_time = '2026-08-17 14:00:00'
WHERE table_name = 'bedside';
```
