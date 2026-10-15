package com.sync.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class KingbaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(KingbaseConfig.class);

    /** 匹配 jdbc:xxx://host:port/db?params，用于替换其中的库名 */
    private static final Pattern URL_PATTERN =
        Pattern.compile("^(jdbc:[a-zA-Z0-9]+://[^/]+)/([^/?]+)(\\?.*)?$");

    @Value("${kingbase.url}")
    private String url;

    /**
     * 目标库名称（可选）。配置后覆盖 kingbase.url 中的库名，实现"同步到指定库"。
     * 留空则完全沿用 kingbase.url（向后兼容）。
     */
    @Value("${kingbase.database:}")
    private String database;

    @Value("${kingbase.username}")
    private String username;

    @Value("${kingbase.password}")
    private String password;

    @Value("${kingbase.driver-class-name:org.postgresql.Driver}")
    private String driverClassName;

    @Value("${kingbase.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${kingbase.min-idle:5}")
    private int minIdle;

    @Bean
    public DataSource kingbaseDataSource() {
        try {
            String finalUrl = resolveUrl();
            logger.info("Connecting to Kingbase at {}", finalUrl);

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(driverClassName);
            dataSource.setUrl(finalUrl);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            return dataSource;
        } catch (Exception e) {
            logger.error("Failed to connect to Kingbase: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 解析最终JDBC URL：若配置了 kingbase.database，则覆盖 URL 中的目标库名。
     * 未配置 database 时原样返回 url，保持向后兼容。
     */
    private String resolveUrl() {
        if (database == null || database.trim().isEmpty()) {
            return url;
        }
        String db = database.trim();
        Matcher matcher = URL_PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            logger.warn("无法从 kingbase.url 解析出目标库名，忽略 kingbase.database 配置: {}", url);
            return url;
        }
        String oldDb = matcher.group(2);
        String resolved = matcher.group(1) + "/" + db
            + (matcher.group(3) != null ? matcher.group(3) : "");
        if (!oldDb.equals(db)) {
            logger.info("目标库已由配置覆盖: {} -> {}", oldDb, db);
        }
        return resolved;
    }

    @Bean
    public JdbcTemplate kingbaseJdbcTemplate(DataSource kingbaseDataSource) {
        return new JdbcTemplate(kingbaseDataSource);
    }
}
