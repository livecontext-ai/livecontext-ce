package com.apimarketplace.common.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lightweight read-only user descriptor - name + avatar - used by
 * cross-resource list views (e.g. the {@code /app/activity} feed) to render
 * "by {actor}" attribution without a per-row N+1 user lookup.
 *
 * <p>Returned in bulk by
 * {@code POST /api/internal/auth/users/resolve-batch} →
 * {@code Map<userId, UserSummaryDto>}. Consumers (orchestrator's
 * {@code RecentActivityAggregatorService}) call the batch endpoint once
 * per cache-miss path, then enrich the row DTOs in-place.
 *
 * <p><b>Note on {@code avatarUrl}:</b> v3.3.1 ships with {@code avatarUrl}
 * always {@code null} - the {@code auth.user_onboarding} table doesn't carry
 * an avatar column today (Keycloak holds it, fetching per user defeats the
 * batch optimization). The field is present for forward-compat: when
 * {@code user_onboarding.avatar_url} backfill lands, the batch endpoint
 * starts populating it without any DTO surface change downstream.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSummaryDto(
        String userId,
        String displayName,
        String avatarUrl
) {
    public static UserSummaryDto displayNameOnly(String userId, String displayName) {
        return new UserSummaryDto(userId, displayName, null);
    }
}
