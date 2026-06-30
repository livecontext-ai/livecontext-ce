package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.domain.CeLinkHeartbeat;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /api/ce-link/mine}. Rendered in the cloud
 * /settings/cloud-account page (doc §6). Heartbeat-derived fields are null when
 * the install has never sent a heartbeat (e.g. just registered, CE still booting).
 */
public record CeLinkSummary(
        UUID installId,
        String label,
        String status,
        String scopes,
        Instant createdAt,
        Instant lastSeenAt,
        String lastSeenCeVersion
) {
    /**
     * Build a summary from the cold registry row + the (nullable) hot heartbeat
     * row. Use this overload from {@code CeLinkService.mine()} after the
     * heartbeat join. The single-arg overload below is reserved for callsites
     * where heartbeat data is unavailable (tests, future internal endpoints).
     */
    public static CeLinkSummary of(CeLink entity, CeLinkHeartbeat heartbeat) {
        return new CeLinkSummary(
                entity.getInstallId(),
                entity.getLabel(),
                entity.getStatus().name(),
                entity.getScopes(),
                entity.getCreatedAt(),
                heartbeat == null ? null : heartbeat.getLastSeenAt(),
                heartbeat == null ? null : heartbeat.getLastSeenCeVersion()
        );
    }

    /** No-heartbeat shortcut. */
    public static CeLinkSummary of(CeLink entity) {
        return of(entity, null);
    }
}
