# 部署指南

## 银河麒麟V11部署步骤

### 1. 环境准备

```bash
# 检查Java版本
java -version

# 如果没有安装Java
sudo yum install java-1.8.0-openjdk java-1.8.0-openjdk-devel -y

# 检查Java版本
java -version
javac -version
```

### 2. 创建部署目录

```bash
# 创建部署目录
sudo mkdir -p /opt/mongo-to-kingbase
sudo mkdir -p /opt/mongo-to-kingbase/logs

# 设置权限
sudo chown -R root:root /opt/mongo-to-kingbase
sudo chmod -R 755 /opt/mongo-to-kingbase
```

### 3. 上传文件

```bash
# 上传JAR包
scp target/mongo-to-kingbase-1.0.0.jar root@server:/opt/mongo-to-kingbase/

# 上传配置文件（可选）
scp src/main/resources/application.yml root@server:/opt/mongo-to-kingbase/
```

**注意**：JDBC驱动已包含在JAR包中（PostgreSQL驱动），无需单独上传。

### 4. 配置systemd服务

```bash
# 复制服务文件
sudo cp deploy/mongo-to-kingbase.service /etc/systemd/system/

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

### 5. 验证部署

```bash
# 查看服务状态
sudo systemctl status mongo-to-kingbase

# 测试API
curl http://localhost:8080/api/sync/health

# 查看同步状态
curl http://localhost:8080/api/sync/status
```

## 管理命令

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

## 故障排查

### 1. 服务启动失败

```bash
# 查看详细日志
sudo journalctl -u mongo-to-kingbase -n 100

# 检查Java版本
java -version

# 检查端口占用
netstat -tlnp | grep 8080
```

### 2. 数据库连接失败

```bash
# 测试MongoDB连接
telnet 10.35.4.12 32121

# 测试Kingbase连接
telnet 10.35.4.12 54321

# 检查防火墙
sudo firewall-cmd --list-all
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
