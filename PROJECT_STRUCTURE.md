# 项目结构

```
cg-MongoToKingbase-service/
├── pom.xml                                    # Maven配置文件
├── build.sh                                   # 构建脚本
├── README.md                                  # 项目说明
├── .gitignore                                 # Git忽略文件
│
├── src/
│   └── main/
│       ├── java/
│       │   └── com/
│       │       └── sync/
│       │           ├── MongoToKingbaseApplication.java  # 主应用类
│       │           │
│       │           ├── config/
│       │           │   ├── MongoConfig.java             # MongoDB配置
│       │           │   ├── KingbaseConfig.java          # Kingbase配置
│       │           │   └── SyncConfig.java              # 同步配置
│       │           │
│       │           ├── service/
│       │           │   ├── MongoReaderService.java      # MongoDB读取服务
│       │           │   ├── KingbaseWriterService.java   # Kingbase写入服务
│       │           │   └── SyncService.java             # 同步协调服务
│       │           │
│       │           ├── scheduler/
│       │           │   └── SyncScheduler.java           # 定时任务调度器
│       │           │
│       │           └── controller/
│       │               └── SyncController.java          # REST API控制器
│       │
│       └── resources/
│           └── application.yml                           # 配置文件
│
├── lib/                                         # 备用驱动目录（可选）
│

├── deploy/                                      # 部署相关文件
│   ├── DEPLOY.md                               # 部署指南
│   ├── start.sh                                # 启动脚本
│   ├── stop.sh                                 # 停止脚本
│   ├── test-connection.sh                      # 连接测试脚本
│   └── mongo-to-kingbase.service               # systemd服务文件
│
└── docs/                                        # 文档目录
    ├── README.md                               # 文档中心
    ├── CHANGELOG.md                            # 更新日志
    └── bedside-analysis.md                     # Bedside表分析指南
```

## 文件说明

### 核心文件

#### MongoToKingbaseApplication.java
- Spring Boot主应用类
- 启用定时任务功能

#### MongoConfig.java
- MongoDB连接配置
- 支持连接池和超时设置

#### KingbaseConfig.java
- Kingbase数据库连接配置
- 支持JDBC连接池

#### SyncConfig.java
- 同步参数配置
- 支持定时任务cron表达式
- 支持同步模式（全量/增量）
- 支持同步标识字段配置

#### MongoReaderService.java
- MongoDB数据读取服务
- 支持全量读取和增量读取
- 支持获取集合结构
- 支持获取最大editTime

#### KingbaseWriterService.java
- Kingbase数据写入服务
- 支持批量插入和更新
- 支持upsert操作
- 支持表结构管理

#### SyncService.java
- 同步协调服务
- 支持全量同步和增量同步
- 管理同步状态和时间

#### SyncScheduler.java
- 定时任务调度器
- 支持手动触发同步

#### SyncController.java
- REST API控制器
- 提供同步管理接口

### 配置文件

#### application.yml
- 数据库连接配置
- 同步参数配置
- 日志配置

### 部署文件

#### DEPLOY.md
- 详细的部署指南
- 包含银河麒麟V11部署步骤

#### start.sh / stop.sh
- 服务启动/停止脚本
- 支持后台运行

#### test-connection.sh
- 数据库连接测试脚本
- 验证MongoDB和Kingbase连接

#### mongo-to-kingbase.service
- systemd服务文件
- 支持开机自启

### 文档文件

#### README.md
- 项目介绍和快速开始
- API接口说明
- 配置说明

#### CHANGELOG.md
- 版本更新日志
- 功能变更记录

#### bedside-analysis.md
- Bedside表结构分析指南
- 同步流程说明

## 依赖说明

### Maven依赖

```xml
<!-- Spring Boot -->
spring-boot-starter-web
spring-boot-starter-data-mongodb
spring-boot-starter-jdbc

<!-- MongoDB Driver -->
mongodb-driver-sync

<!-- PostgreSQL Driver (用于连接Kingbase) -->
postgresql 42.6.0

<!-- 工具库 -->
snakeyaml
jackson-databind
commons-lang3
```

### 系统依赖

- Java 8+
- Maven 3.6+
- MongoDB 4.4+
- Kingbase V009R002C014

## 构建说明

### 本地构建
```bash
mvn clean package -DskipTests
```

### 部署构建
```bash
chmod +x build.sh
./build.sh
```

## 运行说明

### 开发环境
```bash
java -jar target/mongo-to-kingbase-1.0.0.jar
```

### 生产环境
```bash
# 使用systemd服务
sudo systemctl start mongo-to-kingbase

# 或使用启动脚本
./deploy/start.sh start
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

## 配置说明

### 数据库配置
- MongoDB: 10.35.4.12:32121
- Kingbase: 10.35.4.12:54321

### 同步配置
- 同步模式: incremental（增量同步）
- 编辑时间字段: editTime
- 同步时间字段: MongoToKingDate
- 批量大小: 1000

## 注意事项

1. 确保MongoDB中的文档包含`editTime`字段
2. 首次同步会执行全量同步
3. 后续同步会根据`editTime`进行增量更新
4. `MongoToKingDate`字段会自动更新
5. 查看日志文件排查问题
