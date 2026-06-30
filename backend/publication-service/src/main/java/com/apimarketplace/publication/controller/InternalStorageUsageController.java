package com.apimarketplace.publication.controller;

import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.publication.service.PublicationStorageUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoint for storage usage reporting.
 * Called by orchestrator's StorageReconciliationService via PublicationClient.
 */
@RestController
@RequestMapping("/api/internal/publications/storage")
public class InternalStorageUsageController {

    private final PublicationStorageUsageService storageUsageService;

    public InternalStorageUsageController(PublicationStorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDto> getStorageUsage(
            @RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(storageUsageService.getStorageUsage(tenantId));
    }
}
