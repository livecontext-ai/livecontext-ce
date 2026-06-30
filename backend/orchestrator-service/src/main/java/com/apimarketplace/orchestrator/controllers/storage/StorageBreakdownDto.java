package com.apimarketplace.orchestrator.controllers.storage;

import java.time.Instant;

/**
 * DTO for per-category storage breakdown returned to frontend.
 */
public record StorageBreakdownDto(
    String category,
    long usedBytes,
    int itemCount,
    Instant calculatedAt
) {}
