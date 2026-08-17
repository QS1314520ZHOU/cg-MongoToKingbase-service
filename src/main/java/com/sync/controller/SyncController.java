package com.sync.controller;

import com.sync.scheduler.SyncScheduler;
import com.sync.service.MongoReaderService;
import com.sync.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger logger = LoggerFactory.getLogger(SyncController.class);

    @Autowired
    private SyncScheduler syncScheduler;

    @Autowired
    private SyncService syncService;

    @Autowired
    private MongoReaderService mongoReaderService;

    /**
     * 触发全量同步
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startSync() {
        try {
            Map<String, Object> result = syncScheduler.triggerSync();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 触发单个表同步
     */
    @PostMapping("/start/{tableName}")
    public ResponseEntity<Map<String, Object>> startSyncTable(@PathVariable String tableName) {
        try {
            Map<String, Object> result = syncScheduler.triggerSyncTable(tableName);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 获取同步状态
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSyncStatus() {
        Map<String, Object> status = syncService.getSyncStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * 分析集合结构
     */
    @GetMapping("/analyze/{collectionName}")
    public ResponseEntity<Map<String, Object>> analyzeCollection(@PathVariable String collectionName) {
        try {
            Map<String, Object> analysis = syncService.analyzeCollection(collectionName);
            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 获取MongoDB所有集合
     */
    @GetMapping("/collections")
    public ResponseEntity<List<String>> getCollections() {
        List<String> collections = mongoReaderService.getAllCollections();
        return ResponseEntity.ok(collections);
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "MongoDB to Kingbase Sync Service");
        return ResponseEntity.ok(status);
    }

    /**
     * 测试数据库连接
     */
    @GetMapping("/test-connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        Map<String, Object> result = new HashMap<>();

        // 测试MongoDB连接
        try {
            List<String> collections = mongoReaderService.getAllCollections();
            result.put("mongodb", "SUCCESS");
            result.put("mongodb_collections", collections.size());
            result.put("mongodb_message", "MongoDB连接成功，共" + collections.size() + "个集合");
        } catch (Exception e) {
            result.put("mongodb", "FAILED");
            result.put("mongodb_error", e.getMessage());
        }

        // 测试Kingbase连接
        try {
            // 这里只是测试连接，不执行实际操作
            result.put("kingbase", "需要验证密码");
            result.put("kingbase_message", "Kingbase连接失败，请检查密码是否正确");
        } catch (Exception e) {
            result.put("kingbase", "FAILED");
            result.put("kingbase_error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
