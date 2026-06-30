package com.apimarketplace.conversation.service.ai;

/**
 * Thrown by {@link BridgeAccessEnforcer} when a user is denied access to a CLI
 * bridge provider (disabled by admin, not in allowlist, or daily quota exhausted).
 *
 * <p>Carries the typed {@link #reason} so the upstream HTTP layer can map to
 * 403 vs 429. Matches the {@code AccessDecision.reason} values produced by
 * auth-service's {@code BridgeAccessService}:
 * {@code bridge_disabled}, {@code admin_only_requires_admin_role},
 * {@code not_in_allowlist}, {@code daily_quota_exhausted},
 * {@code unknown_bridge}, {@code guard_unavailable}.
 */
public class BridgeAccessDeniedException extends RuntimeException {

    public static final String REASON_QUOTA_EXHAUSTED = "daily_quota_exhausted";

    private final String providerName;
    private final String reason;
    private final Integer remainingRequestsToday;

    public BridgeAccessDeniedException(String providerName, String reason, Integer remainingRequestsToday) {
        super("Bridge access denied for " + providerName + ": " + reason);
        this.providerName = providerName;
        this.reason = reason;
        this.remainingRequestsToday = remainingRequestsToday;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getReason() {
        return reason;
    }

    public Integer getRemainingRequestsToday() {
        return remainingRequestsToday;
    }

    public boolean isQuotaExhausted() {
        return REASON_QUOTA_EXHAUSTED.equals(reason);
    }
}
