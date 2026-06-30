package com.apimarketplace.agent.bridge;

/**
 * Mirror of {@code com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessDecision}
 * as observed by shared-agent-lib over HTTP.
 *
 * <p>Lives here (not auth-client) so the cloud agent-service can use the same
 * guard without depending on the auth schema - the field set is intentionally
 * duplicated to decouple the two modules.
 */
public record BridgeAccessDecision(
        boolean allowed,
        String reason,
        String bridgeProvider,
        Integer remainingRequestsToday
) {

    public static final String REASON_DISABLED = "bridge_disabled";
    public static final String REASON_NOT_ADMIN = "admin_only_requires_admin_role";
    public static final String REASON_NOT_ALLOWLISTED = "not_in_allowlist";
    public static final String REASON_QUOTA_EXHAUSTED = "daily_quota_exhausted";
    public static final String REASON_UNKNOWN_BRIDGE = "unknown_bridge";

    /** Marker used when the guard itself is unavailable (null client, network error). */
    public static final String REASON_GUARD_UNAVAILABLE = "guard_unavailable";

    public static BridgeAccessDecision allow(String bridge, Integer remaining) {
        return new BridgeAccessDecision(true, "ok", bridge, remaining);
    }

    public static BridgeAccessDecision deny(String bridge, String reason) {
        return new BridgeAccessDecision(false, reason, bridge, null);
    }
}
