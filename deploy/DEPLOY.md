# 部署指南 - 银河麒麟V11

## 前提条件

### 1. 系统要求
- 银河麒麟 V11（或CentOS 7+）
- Java 8 或更高版本
- 至少 1GB 可用内存
- 磁盘空间：至少 500MB

### 2. 网络要求
- 能够访问MongoDB服务器 (10.35.4.12:32121)
- 能够访问Kingbase服务器 (10.35.4.12:54321)

## 部署步骤

### 步骤1：安装Java环境

```bash
# 检查是否已安装Java
java -version

# 如果未安装，使用yum安装
sudo yum install java-1.8.0-openjdk java-1.8.0-openjdk-devel -y

# 验证安装
java -version
javac -version
```

### 步骤2：创建部署目录

```bash
# 创建部署目录
sudo mkdir -p /opt/mongo-to-kingbase
sudo mkdir -p /opt/mongo-to-kingbase/logs

# 设置权限
sudo chown -R root:root /opt/mongo-to-kingbase
sudo chmod -R 755 /opt/mongo-to-kingbase
```

### 步骤3：上传文件

将以下文件上传到服务器：

1. **JAR包**：`mongo-to-kingbase-1.0.0.jar`
2. **配置文件**：`application.yml`（可选，如需修改配置）

上传方式：
```bash
# 使用scp
scp target/mongo-to-kingbase-1.0.0.jar root@server:/opt/mongo-to-kingbase/
scp src/main/resources/application.yml root@server:/opt/mongo-to-kingbase/

# 或使用rsync
rsync -avz target/mongo-to-kingbase-1.0.0.jar root@server:/opt/mongo-to-kingbase/
```

### 步骤4：配置Kingbase连接

**注意**：本项目使用PostgreSQL JDBC驱动连接Kingbase（Kingbase兼容PostgreSQL协议），无需单独下载Kingbase JDBC驱动。

配置文件 `application.yml` 中的Kingbase连接配置：
```yaml
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase
  username: system
  password: "chc3Xz3PS4N^x"
  driver-class-name: org.postgresql.Driver
```

### 步骤5：修改配置文件（如需要）

```bash
# 编辑配置文件
vi /opt/mongo-to-kingbase/application.yml
```

主要配置项：
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
  cron-expression: "0 0 2 * * ?"
```

### 步骤6：配置systemd服务

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

### 步骤7：验证部署

```bash
# 查看服务状态
sudo systemctl status mongo-to-kingbase

# 查看日志
sudo journalctl -u mongo-to-kingbase -f

# 测试API
curl http://localhost:8080/api/sync/health

# 测试连接
curl http://localhost:8080/api/sync/collections
```

## 使用管理脚本

项目提供了便捷的管理脚本 `deploy/start.sh`：

```bash
# 赋予执行权限
chmod +x deploy/start.sh

# 启动服务
./deploy/start.sh start

# 停止服务
./deploy/start.sh stop

# 重启服务
./deploy/start.sh restart

# 查看状态
./deploy/start.sh status

# 查看日志
./deploy/start.sh logs
```

## 数据库连接测试

使用测试脚本验证数据库连接：

```bash
# 赋予执行权限
chmod +x deploy/test-connection.sh

# 运行测试
./deploy/test-connection.sh
```

## 定时任务配置

默认配置为每天凌晨2点执行同步。如需修改：

### 方式1：修改配置文件

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

### 方式2：使用crontab（推荐）

如果希望使用系统级定时任务，可以禁用应用内定时任务，使用crontab：

```yaml
# 禁用应用内定时任务
sync:
  enable: false
```

然后添加crontab：
```bash
# 编辑crontab
crontab -e

# 添加定时任务（每天凌晨2点执行）
0 2 * * * /usr/bin/java -jar /opt/mongo-to-kingbase/mongo-to-kingbase-1.0.0.jar --sync.now=true
```

## 手动同步

### 通过API触发

```bash
# 同步所有表
curl -X POST http://localhost:8080/api/sync/start

# 同步单个表
curl -X POST http://localhost:8080/api/sync/start/bedside
```

### 通过命令行触发

```bash
# 使用spring-boot-starter的actuator端点
curl -X POST http://localhost:8080/actuator/sync/trigger
```

## 监控和日志

### 查看实时日志

```bash
# 使用journalctl
sudo journalctl -u mongo-to-kingbase -f

# 查看日志文件
tail -f /opt/mongo-to-kingbase/logs/nohup.log

# 查看最近100行日志
tail -100 /opt/mongo-to-kingbase/logs/nohup.log
```

### 监控同步状态

```bash
# 查看同步状态
curl http://localhost:8080/api/sync/status

# 查看特定表分析
curl http://localhost:8080/api/sync/analyze/bedside
```

### 日志轮转配置

创建日志轮转配置：

```bash
sudo vi /etc/logrotate.d/mongo-to-kingbase
```

添加内容：
```
/opt/mongo-to-kingbase/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 644 root root
}
```

## 故障排查

### 问题1：服务启动失败

```bash
# 查看详细日志
sudo journalctl -u mongo-to-kingbase -n 100

# 检查Java版本
java -version

# 检查端口占用
netstat -tlnp | grep 8080
```

### 问题2：数据库连接失败

```bash
# 测试MongoDB连接
telnet 10.35.4.12 32121

# 测试Kingbase连接
telnet 10.35.4.12 54321

# 检查防火墙
sudo firewall-cmd --list-all
```

### 问题3：同步性能问题

调整配置：
```yaml
sync:
  batch-size: 500  # 减小批量大小
  sync-mode: incremental  # 使用增量同步
```

### 问题4：内存不足

调整JVM参数：
```bash
# 修改启动脚本中的JAVA_OPTS
JAVA_OPTS="-Xms1024m -Xmx2048m -Dfile.encoding=UTF-8"
```

## 卸载服务

```bash
# 停止服务
sudo systemctl stop mongo-to-kingbase

# 禁用开机启动
sudo systemctl disable mongo-to-kingbase

# 删除服务文件
sudo rm /etc/systemd/system/mongo-to-kingbase.service

# 重新加载systemd
sudo systemctl daemon-reload

# 删除部署目录
sudo rm -rf /opt/mongo-to-kingbase
```

## 安全建议

1. **密码管理**：不要在配置文件中明文存储密码，建议使用环境变量或配置中心
2. **网络安全**：确保服务器能够访问MongoDB和Kingbase，必要时配置防火墙规则
3. **日志安全**：定期清理日志文件，避免磁盘空间耗尽
4. **权限控制**：使用最小权限原则运行服务

## 技术支持

如遇到问题，请：
1. 查看日志文件
2. 检查网络连接
3. 验证数据库配置
4. 联系开发团队
