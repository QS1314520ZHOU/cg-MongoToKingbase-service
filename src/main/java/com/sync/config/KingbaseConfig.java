package com.sync.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class KingbaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(KingbaseConfig.class);

    @Value("${kingbase.url}")
    private String url;

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
            logger.info("Connecting to Kingbase at {}", url);

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(driverClassName);
            dataSource.setUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            return dataSource;
        } catch (Exception e) {
            logger.error("Failed to connect to Kingbase: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Bean
    public JdbcTemplate kingbaseJdbcTemplate(DataSource kingbaseDataSource) {
        return new JdbcTemplate(kingbaseDataSource);
    }
}
