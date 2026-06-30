package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.AgentStorageUsageService;
import com.apimarketplace.common.storage.StorageUsageDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal API endpoint for storage usage reporting.
 * Called by orchestrator's StorageReconciliationService via AgentClient.
 */
@RestController
@RequestMapping("/api/internal/agents/storage")
public class InternalStorageUsageController {

    private final AgentStorageUsageService storageUsageService;

    public InternalStorageUsageController(AgentStorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping("/usage")
    public ResponseEntity<Map<String, StorageUsageDto>> getStorageUsage(
            @RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(storageUsageService.getStorageUsage(tenantId));
    }
}
