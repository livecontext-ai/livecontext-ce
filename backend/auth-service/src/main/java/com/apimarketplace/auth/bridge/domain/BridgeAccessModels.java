package com.apimarketplace.auth.bridge.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.List;

/**
 * Domain models for CLI bridge access control (V118).
 *
 * <p>Governs who can dispatch LLM calls through the local CLI bridge
 * (Claude Code, Codex, Gemini CLI, Mistral Vibe). Bridges run on a SHARED
 * OS-level session (admin's Claude Pro / ChatGPT Plus account) so without
 * gating any user can exhaust the subscription's rate limits and break it
 * for everyone. Opt-in by default: every bridge ships as {@code disabled}.
 */
public final class BridgeAccessModels {

    private BridgeAccessModels() {}

    /** Four access regimes, persisted as {@code auth.bridge_access_policy.access_mode}. */
    public enum AccessMode {
        /** No one (even admin) can dispatch through this bridge. */
        DISABLED,
        /** Only users with the ADMIN role. */
        ADMIN_ONLY,
        /** Users explicitly listed in {@link BridgeAccessAllowlistEntry}. */
        ALLOWLIST,
        /** Every user of the CE instance (pair with per-user quota). */
        ALL_USERS;

        public String dbValue() {
            return name().toLowerCase();
        }

        @JsonValue
        public String jsonValue() {
            return dbValue();
        }

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static AccessMode fromJson(String raw) {
            return fromDb(raw);
        }

        public static AccessMode fromDb(String raw) {
            if (raw == null) return DISABLED;
            for (AccessMode m : values()) {
                if (m.dbValue().equals(raw.toLowerCase())) return m;
            }
            return DISABLED;
        }
    }

    /** Per-bridge policy row. */
    public record BridgeAccessPolicy(
            Long id,
            String bridgeProvider,
            AccessMode accessMode,
            Integer maxRequestsPerUserPerDay,
            Instant updatedAt,
            String updatedBy
    ) {}

    public record BridgeAccessAllowlistEntry(
            Long policyId,
            String userId,
            Instant grantedAt,
            String grantedBy
    ) {}

    /** Request body for admin PUT of a policy. */
    public record UpdatePolicyRequest(
            AccessMode accessMode,
            Integer maxRequestsPerUserPerDay
    ) {}

    /** Aggregate returned to the admin UI: policy + allowlist + usage stats. */
    public record BridgeAccessView(
            BridgeAccessPolicy policy,
            List<BridgeAccessAllowlistEntry> allowlist,
            List<UsageStat> recentUsage
    ) {}

    public record UsageStat(
            String userId,
            int requestsToday,
            Instant lastRequestAt
    ) {}

    /**
     * Result of an internal access check from shared-agent-lib. The typed
     * decision avoids string comparisons at the call site.
     */
    public record AccessDecision(
            boolean allowed,
            String reason,
            String bridgeProvider,
            Integer remainingRequestsToday
    ) {
        public static AccessDecision allow(String bridge, Integer remaining) {
            return new AccessDecision(true, "ok", bridge, remaining);
        }

        public static AccessDecision deny(String bridge, String reason) {
            return new AccessDecision(false, reason, bridge, null);
        }

        public static final String REASON_DISABLED = "bridge_disabled";
        public static final String REASON_NOT_ADMIN = "admin_only_requires_admin_role";
        public static final String REASON_NOT_ALLOWLISTED = "not_in_allowlist";
        public static final String REASON_QUOTA_EXHAUSTED = "daily_quota_exhausted";
        public static final String REASON_UNKNOWN_BRIDGE = "unknown_bridge";
    }
}
