package com.apimarketplace.publication.config;

import com.apimarketplace.common.web.PlanLimits;

import java.util.Map;

/**
 * Plan-based limits for shared links (global quota across all types).
 * Limits can be disabled entirely in CE mode via {@link #setEnabled(boolean)}.
 */
public final class SharedLinkPlanLimits {

    private static final int DEFAULT_MAX = 10;

    private static final Map<String, Integer> LIMITS = Map.ofEntries(
            Map.entry("FREE", 5),
            Map.entry("STARTER", 20),
            Map.entry("PRO", 50),
            Map.entry("TEAM", 100),
            Map.entry("PAYG", 100),
            Map.entry("ENTERPRISE_BASIC", 200),
            Map.entry("ENTERPRISE_STANDARD", 200),
            Map.entry("ENTERPRISE_PREMIUM", 200),
            Map.entry("ENTERPRISE_ULTIMATE", 200)
    );

    private static volatile boolean enabled = true;

    private SharedLinkPlanLimits() {}

    static void setEnabled(boolean enabled) {
        SharedLinkPlanLimits.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getMaxSharedLinks(String planCode) {
        if (!enabled) return PlanLimits.UNLIMITED;
        if (planCode == null || planCode.isBlank()) {
            return DEFAULT_MAX;
        }
        String normalized = planCode.trim().toUpperCase();
        if (normalized.equals("ENTERPRISE")) {
            normalized = "ENTERPRISE_BASIC";
        }
        return LIMITS.getOrDefault(normalized, DEFAULT_MAX);
    }
}
