package com.sync.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MongoConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoConfig.class);

    @Value("${mongo.host}")
    private String host;

    @Value("${mongo.port}")
    private int port;

    @Value("${mongo.database}")
    private String database;

    @Value("${mongo.username}")
    private String username;

    @Value("${mongo.password}")
    private String password;

    @Value("${mongo.auth-database:admin}")
    private String authDatabase;

    @Value("${mongo.connection-timeout:30000}")
    private int connectionTimeout;

    @Value("${mongo.socket-timeout:60000}")
    private int socketTimeout;

    @Bean
    public MongoClient mongoClient() {
        try {
            String connectionString = String.format(
                "mongodb://%s:%s@%s:%d/%s?authSource=%s",
                username, password, host, port, database, authDatabase
            );

            logger.info("Connecting to MongoDB at {}:{}/{}", host, port, database);

            CodecRegistry pojoCodecRegistry = CodecRegistries.fromProviders(
                PojoCodecProvider.builder().automatic(true).build()
            );
            CodecRegistry codecRegistry = CodecRegistries.fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                pojoCodecRegistry
            );

            MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .codecRegistry(codecRegistry)
                .applyToSocketSettings(builder ->
                    builder.connectTimeout(connectionTimeout, TimeUnit.MILLISECONDS)
                        .readTimeout(socketTimeout, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(builder ->
                    builder.serverSelectionTimeout(30, TimeUnit.SECONDS))
                .build();

            return MongoClients.create(settings);
        } catch (Exception e) {
            logger.error("Failed to connect to MongoDB: {}", e.getMessage(), e);
            throw e;
        }
    }

    @Bean
    public MongoDatabase mongoDatabase(MongoClient mongoClient) {
        return mongoClient.getDatabase(database);
    }
}
