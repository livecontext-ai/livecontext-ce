package com.apimarketplace.orchestrator.trigger.queue;

import java.util.Map;

/**
 * Maps subscription plan codes to integer priorities for execution queue ordering.
 * Higher priority = dequeued first.
 *
 * Uses the same normalization pattern as auth-service's PlanCode.normalize():
 * uppercase + "ENTERPRISE" shorthand → "ENTERPRISE_BASIC".
 */
public final class PlanPriorityMapper {

    private static final Map<String, Integer> PLAN_PRIORITIES = Map.ofEntries(
        Map.entry("FREE", 0),
        Map.entry("STARTER", 10),
        Map.entry("PRO", 20),
        Map.entry("TEAM", 30),
        Map.entry("PAYG", 30),
        Map.entry("ENTERPRISE_BASIC", 40),
        Map.entry("ENTERPRISE_STANDARD", 50),
        Map.entry("ENTERPRISE_PREMIUM", 60),
        Map.entry("ENTERPRISE_ULTIMATE", 70)
    );

    private static final int DEFAULT_PRIORITY = 0;

    /**
     * Max concurrent RUNS per tenant/plan. Higher plan = more parallel workflow runs.
     * This limits how many workflow runs a tenant can have in RUNNING status simultaneously.
     */
    private static final Map<String, Integer> PLAN_MAX_CONCURRENT_RUNS = Map.ofEntries(
        Map.entry("FREE", 3),
        Map.entry("STARTER", 10),
        Map.entry("PRO", 50),
        Map.entry("TEAM", 100),
        Map.entry("PAYG", 100),
        Map.entry("ENTERPRISE_BASIC", 100),
        Map.entry("ENTERPRISE_STANDARD", 100),
        Map.entry("ENTERPRISE_PREMIUM", 100),
        Map.entry("ENTERPRISE_ULTIMATE", 100)
    );

    private static final int DEFAULT_MAX_CONCURRENT_RUNS = 3;

    /**
     * Max trigger endpoints (webhooks, chat, form, schedule) per tenant/plan.
     * Same limits as concurrent runs - each trigger type gets this allowance.
     */
    private static final Map<String, Integer> PLAN_MAX_TRIGGER_ENDPOINTS = Map.ofEntries(
        Map.entry("FREE", 3),
        Map.entry("STARTER", 10),
        Map.entry("PRO", 50),
        Map.entry("TEAM", 100),
        Map.entry("PAYG", 100),
        Map.entry("ENTERPRISE_BASIC", 100),
        Map.entry("ENTERPRISE_STANDARD", 100),
        Map.entry("ENTERPRISE_PREMIUM", 100),
        Map.entry("ENTERPRISE_ULTIMATE", 100)
    );

    private static final int DEFAULT_MAX_TRIGGER_ENDPOINTS = 10;

    private PlanPriorityMapper() {
        // Utility class
    }

    /**
     * Maps a plan code to its execution priority.
     *
     * @param planCode the subscription plan code (case-insensitive, nullable)
     * @return priority value (higher = higher priority). Defaults to 0 (FREE) for null/unknown plans.
     */
    public static int getPriority(String planCode) {
        String normalized = normalize(planCode);
        if (normalized == null) {
            return DEFAULT_PRIORITY;
        }
        return PLAN_PRIORITIES.getOrDefault(normalized, DEFAULT_PRIORITY);
    }

    /**
     * Converts the plan priority scale used by the orchestrator (0-70, higher is better)
     * to the Redis queue tier scale (0-7, lower is better).
     */
    public static int toRedisPriorityTier(int planPriority) {
        int normalizedBucket = Math.max(0, Math.min(7, planPriority / 10));
        return 7 - normalizedBucket;
    }

    /**
     * Returns the max concurrent runs allowed for a plan.
     * Controls how many workflow runs a tenant can have in RUNNING status simultaneously.
     *
     * @param planCode the subscription plan code (case-insensitive, nullable)
     * @return max concurrent runs. Defaults to 1 for null/unknown plans.
     */
    public static int getMaxConcurrentRuns(String planCode) {
        String normalized = normalize(planCode);
        if (normalized == null) {
            return DEFAULT_MAX_CONCURRENT_RUNS;
        }
        return PLAN_MAX_CONCURRENT_RUNS.getOrDefault(normalized, DEFAULT_MAX_CONCURRENT_RUNS);
    }

    /**
     * Returns the max trigger endpoints (webhooks, chat, form, schedule) allowed per type for a plan.
     *
     * @param planCode the subscription plan code (case-insensitive, nullable)
     * @return max endpoints per type. Defaults to 10 for null/unknown plans.
     */
    public static int getMaxTriggerEndpoints(String planCode) {
        String normalized = normalize(planCode);
        if (normalized == null) {
            return DEFAULT_MAX_TRIGGER_ENDPOINTS;
        }
        return PLAN_MAX_TRIGGER_ENDPOINTS.getOrDefault(normalized, DEFAULT_MAX_TRIGGER_ENDPOINTS);
    }

    /**
     * Normalizes a plan code: uppercase + "ENTERPRISE" shorthand → "ENTERPRISE_BASIC".
     * Mirrors auth-service PlanCode.normalize() pattern.
     */
    static String normalize(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            return null;
        }
        String normalized = planCode.trim().toUpperCase();
        if ("ENTERPRISE".equals(normalized)) {
            normalized = "ENTERPRISE_BASIC";
        }
        return normalized;
    }
}
