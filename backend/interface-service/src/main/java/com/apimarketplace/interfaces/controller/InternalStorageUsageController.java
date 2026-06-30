package com.apimarketplace.interfaces.controller;

import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.interfaces.service.InterfaceStorageUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoint for storage usage reporting.
 * Called by orchestrator's StorageReconciliationService via InterfaceClient.
 */
@RestController
@RequestMapping("/api/internal/interfaces/storage")
public class InternalStorageUsageController {

    private final InterfaceStorageUsageService storageUsageService;

    public InternalStorageUsageController(InterfaceStorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDto> getStorageUsage(
            @RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(storageUsageService.getStorageUsage(tenantId));
    }
}
