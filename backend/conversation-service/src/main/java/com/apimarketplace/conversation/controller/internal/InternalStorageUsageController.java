package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.conversation.service.ConversationStorageUsageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API endpoint for storage usage reporting.
 * Called by orchestrator's StorageReconciliationService.
 */
@RestController
@RequestMapping("/api/internal/conversation/storage")
public class InternalStorageUsageController {

    private final ConversationStorageUsageService storageUsageService;

    public InternalStorageUsageController(ConversationStorageUsageService storageUsageService) {
        this.storageUsageService = storageUsageService;
    }

    @GetMapping("/usage")
    public ResponseEntity<StorageUsageDto> getStorageUsage(
            @RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(storageUsageService.getStorageUsage(tenantId));
    }
}
