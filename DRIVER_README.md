# 数据库驱动说明

## 驱动变更说明

### 原始设计
- 使用Kingbase官方JDBC驱动（kingbase8-8.6.0.jar）
- 需要手动下载并放置到lib目录
- 驱动类名：`com.kingbase8.Driver`
- JDBC URL格式：`jdbc:kingbase8://host:port/db`

### 当前方案
- **使用PostgreSQL JDBC驱动**（版本42.6.0）
- 通过Maven自动下载，无需手动配置
- 驱动类名：`org.postgresql.Driver`
- JDBC URL格式：`jdbc:postgresql://host:port/db`

### 为什么可以使用PostgreSQL驱动？

Kingbase V009R002C014兼容PostgreSQL协议，因此可以直接使用PostgreSQL JDBC驱动连接Kingbase数据库。

## 配置说明

### application.yml配置

```yaml
kingbase:
  # 使用PostgreSQL协议连接Kingbase
  url: jdbc:postgresql://10.35.4.12:54321/kingbase
  username: system
  password: "chc3Xz3PS4N^x"
  driver-class-name: org.postgresql.Driver
```

### 连接参数

| 参数 | 说明 | 示例值 |
|------|------|--------|
| url | JDBC连接URL | jdbc:postgresql://10.35.4.12:54321/kingbase |
| username | 数据库用户名 | system |
| password | 数据库密码 | chc3Xz3PS4N^x |
| driver-class-name | JDBC驱动类名 | org.postgresql.Driver |

## 构建说明

### 编译打包

```bash
# 直接编译打包（PostgreSQL驱动会自动下载）
mvn clean package -DskipTests
```

### Maven依赖

```xml
<!-- PostgreSQL Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>42.6.0</version>
</dependency>
```

## 验证连接

### 使用psql测试

```bash
# 安装PostgreSQL客户端（如果没有）
sudo yum install postgresql -y

# 测试连接
PGPASSWORD="chc3Xz3PS4N^x" psql -h 10.35.4.12 -p 54321 -U system -d kingbase
```

### 使用Java代码测试

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestConnection {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://10.35.4.12:54321/kingbase";
        String user = "system";
        String password = "chc3Xz3PS4N^x";

        Connection conn = DriverManager.getConnection(url, user, password);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT version()");

        if (rs.next()) {
            System.out.println("Kingbase版本: " + rs.getString(1));
        }

        rs.close();
        stmt.close();
        conn.close();
    }
}
```

## 常见问题

### 1. 连接失败

**错误信息**：`Connection refused`

**解决方案**：
- 检查Kingbase服务是否启动
- 检查防火墙是否开放54321端口
- 验证用户名密码是否正确

### 2. 认证失败

**错误信息**：`FATAL: password authentication failed`

**解决方案**：
- 检查用户名密码是否正确
- 确认authDatabase配置

### 3. 数据库不存在

**错误信息**：`FATAL: database "kingbase" does not exist`

**解决方案**：
- 确认数据库名称是否正确
- 检查Kingbase中的数据库列表

## 性能优化

### 连接池配置

```yaml
kingbase:
  max-pool-size: 20    # 最大连接数
  min-idle: 10         # 最小空闲连接数
```

### 批量操作优化

```yaml
sync:
  batch-size: 2000     # 增大批量大小
```

## 参考资料

- [PostgreSQL JDBC驱动文档](https://jdbc.postgresql.org/documentation/head/)
- [Kingbase数据库文档](https://www.kingbase.com.cn/)
