package com.sync;

import com.sync.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

@SpringBootTest
public class SyncServiceTest {

    @Autowired
    private SyncService syncService;

    @Test
    public void testAnalyzeCollection() {
        Map<String, Object> analysis = syncService.analyzeCollection("bedside");
        System.out.println("Analysis result: " + analysis);
    }

    @Test
    public void testSyncStatus() {
        Map<String, Object> status = syncService.getSyncStatus();
        System.out.println("Sync status: " + status);
    }
}
