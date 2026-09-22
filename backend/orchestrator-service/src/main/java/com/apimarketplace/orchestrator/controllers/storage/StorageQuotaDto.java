package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.common.storage.domain.QuotaStatus;

/**
 * DTO for storage quota information returned to frontend.
 *
 * <p>The {@code unlimited} flag is set by the controller when the deployment
 * edition exposes unlimited storage (CE). When true the frontend hides the
 * progress bar and percentage, but the per-category breakdown and total
 * {@code usedBytes} remain tracked.
 */
public record StorageQuotaDto(
    String tenantId,
    long usedBytes,
    long maxBytes,
    long softLimitBytes,
    long hardLimitBytes,
    long availableBytes,
    double usagePercentage,
    QuotaStatus status,
    boolean unlimited,
    /**
     * What the whole ACCOUNT stores, across every workspace it owns, or null when this row is
     * not attributed to one (and enforcement is then per-workspace).
     *
     * <p>{@code maxBytes} is the pool's ceiling, not this workspace's private grant: the plan
     * grants its allowance once and every workspace draws from it, so a workspace holding 3 MB
     * can still be refused because its siblings filled the pot. Without this figure the page
     * could only show "3 MB of 100 GB" and imply room the account may not have.
     */
    Long accountUsedBytes
) {

    /**
     * Backward-compatible 8-arg constructor that defaults {@code unlimited} to
     * {@code false} (cloud semantics). Existing test callsites keep working
     * unchanged; only edition-aware producers opt into the 9-arg form.
     */
    public StorageQuotaDto(String tenantId, long usedBytes, long maxBytes, long softLimitBytes,
                           long hardLimitBytes, long availableBytes, double usagePercentage,
                           QuotaStatus status) {
        this(tenantId, usedBytes, maxBytes, softLimitBytes, hardLimitBytes, availableBytes,
             usagePercentage, status, false, null);
    }

    /** Edition-aware form without an account total (tenant scope has no pool). */
    public StorageQuotaDto(String tenantId, long usedBytes, long maxBytes, long softLimitBytes,
                           long hardLimitBytes, long availableBytes, double usagePercentage,
                           QuotaStatus status, boolean unlimited) {
        this(tenantId, usedBytes, maxBytes, softLimitBytes, hardLimitBytes, availableBytes,
             usagePercentage, status, unlimited, null);
    }

    /**
     * Creates a StorageQuotaDto with human-readable formatted values.
     */
    public String getUsedFormatted() {
        return formatBytes(usedBytes);
    }

    public String getMaxFormatted() {
        return formatBytes(maxBytes);
    }

    public String getAvailableFormatted() {
        return formatBytes(availableBytes);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
