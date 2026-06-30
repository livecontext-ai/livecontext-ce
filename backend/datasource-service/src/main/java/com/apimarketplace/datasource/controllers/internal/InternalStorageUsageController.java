package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.datasource.services.DataSourceStorageUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoint for storage usage reporting.
 * Called by orchestrator's StorageReconciliationService via DataSourceClient.
 */
@RestController
@RequestMapping("/api/internal/datasource/storage")
public class InternalStorageUsageController {

    private final DataSourceStorageUsageService storageUsageService;

    public InternalStorageUsageController(DataSourceStorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDto> getStorageUsage(
            @RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(storageUsageService.getStorageUsage(tenantId));
    }
}
