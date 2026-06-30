package com.apimarketplace.orchestrator.controllers.storage;

/**
 * DTO for storage usage history data point.
 */
public record StorageHistoryDto(
        String snapshotDate,
        String category,
        long usedBytes,
        int itemCount
) {}
