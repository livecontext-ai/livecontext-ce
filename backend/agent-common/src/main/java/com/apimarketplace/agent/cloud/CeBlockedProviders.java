package com.apimarketplace.agent.cloud;

import java.util.Set;

/**
 * Provider names a CE (self-hosted, {@code auth.mode=embedded}) install must NOT
 * expose to its users.
 *
 * <p>Multi-provider aggregation is the hosted product's value proposition: a CE
 * user who could configure their own aggregator ({@code openrouter}) on top would
 * bypass it. {@code cohere} is dropped as well (low recognition, curated catalog).
 *
 * <p>This is a CE-only boundary. Cloud (the default, non-embedded profile) is
 * unaffected and keeps every provider, {@code openrouter} included, notably as a
 * relay fallback for cloud-linked CE installs (see {@link CloudRelaySupport}).
 *
 * <p>Callers gate on {@code auth.mode == "embedded"} before applying this set, so
 * the same shared-agent-lib / agent-common code runs unchanged in cloud.
 */
public final class CeBlockedProviders {

    private static final Set<String> BLOCKED = Set.of("openrouter", "cohere");

    private CeBlockedProviders() {
    }

    /** True iff {@code providerName} must be hidden/disabled on a CE install. */
    public static boolean isBlocked(String providerName) {
        return providerName != null
                && BLOCKED.contains(providerName.trim().toLowerCase());
    }

    /** The immutable set of CE-blocked provider names (lowercase). */
    public static Set<String> names() {
        return BLOCKED;
    }

    /** True iff CE mode ({@code auth.mode=embedded}) blocks {@code providerName}. */
    public static boolean isBlockedInMode(String authMode, String providerName) {
        return "embedded".equalsIgnoreCase(authMode == null ? "" : authMode.trim())
                && isBlocked(providerName);
    }
}
