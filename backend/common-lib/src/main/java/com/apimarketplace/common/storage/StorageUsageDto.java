package com.apimarketplace.common.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Storage usage data for a single category.
 * Used by internal storage endpoints to report per-category usage to orchestrator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageUsageDto(long usedBytes, int itemCount) {

    public static StorageUsageDto zero() {
        return new StorageUsageDto(0, 0);
    }
}
