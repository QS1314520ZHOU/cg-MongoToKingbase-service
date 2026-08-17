# 故障排查指南

## 当前问题

### Kingbase连接认证失败

**错误信息**：
```
FATAL: password authentication failed for user "system"
```

**可能原因**：
1. 密码不正确
2. Kingbase使用了不同的认证方式
3. 用户名不正确
4. 数据库名称不正确

## 解决步骤

### 步骤1：验证Kingbase连接信息

请确认以下信息是否正确：
- **IP地址**: 10.35.4.12
- **端口**: 54321
- **用户名**: system
- **密码**: chc3Xz3PS4N^x
- **数据库名**: kingbase

### 步骤2：使用psql测试连接

如果服务器上安装了PostgreSQL客户端，可以使用以下命令测试：

```bash
# 测试连接
PGPASSWORD="chc3Xz3PS4N^x" psql -h 10.35.4.12 -p 54321 -U system -d kingbase

# 或者不指定密码，手动输入
psql -h 10.35.4.12 -p 54321 -U system -d kingbase
```

### 步骤3：检查Kingbase服务状态

```bash
# 在Kingbase服务器上执行
systemctl status kingbase

# 或者检查进程
ps -ef | grep kingbase
```

### 步骤4：检查pg_hba.conf配置

Kingbase的认证配置文件可能限制了连接方式。检查以下文件：

```bash
# 位置可能在
/opt/kingbase/data/pg_hba.conf
# 或
/var/lib/kingbase/data/pg_hba.conf

# 查看内容
cat /opt/kingbase/data/pg_hba.conf | grep -v "^#"
```

可能需要添加或修改以下行：

```
# 允许密码认证
host    all             all             0.0.0.0/0               md5
```

修改后需要重启Kingbase服务：

```bash
systemctl restart kingbase
```

### 步骤5：重置密码

如果密码确实不正确，可以重置Kingbase的system用户密码：

```bash
# 在Kingbase服务器上
ksql -U system -d kingbase

# 修改密码
ALTER USER system WITH PASSWORD 'new_password';
```

## 修改配置文件

如果确认了正确的密码，修改 `src/main/resources/application.yml`：

```yaml
kingbase:
  url: jdbc:postgresql://10.35.4.12:54321/kingbase?stringtype=unspecified
  username: system
  password: '正确的密码'  # 修改为正确的密码
  driver-class-name: org.postgresql.Driver
```

然后重新编译打包：

```bash
mvn clean package -DskipTests
```

## 测试API接口

启动服务后，可以使用以下API测试连接：

```bash
# 测试数据库连接
curl http://localhost:8080/api/sync/test-connection

# 查看MongoDB集合
curl http://localhost:8080/api/sync/collections

# 查看同步状态
curl http://localhost:8080/api/sync/status
```

## 常见问题

### 1. 连接超时

**错误信息**：`Connection timed out`

**解决方案**：
- 检查防火墙是否开放54321端口
- 检查Kingbase服务是否启动

### 2. 数据库不存在

**错误信息**：`FATAL: database "kingbase" does not exist`

**解决方案**：
- 确认数据库名称是否正确
- 检查Kingbase中的数据库列表

### 3. 用户不存在

**错误信息**：`FATAL: role "system" does not exist`

**解决方案**：
- 确认用户名是否正确
- 检查Kingbase中的用户列表

## 联系支持

如果以上步骤都无法解决问题，请：
1. 收集完整的错误日志
2. 确认Kingbase的版本信息
3. 联系数据库管理员或Kingbase技术支持
