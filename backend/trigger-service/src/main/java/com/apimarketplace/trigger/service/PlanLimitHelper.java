package com.apimarketplace.trigger.service;

import com.apimarketplace.common.web.PlanLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Shared plan-based limit helper for trigger resources (webhooks, schedules).
 * Extracts duplicated plan limit maps from StandaloneWebhookController and ScheduleController.
 * Limits are disabled entirely in CE mode (plan-limits.enabled=false), in which case
 * the helper returns {@link PlanLimits#UNLIMITED}.
 */
@Component
public class PlanLimitHelper {

    private static final Map<String, Integer> PLAN_LIMITS_MAP = Map.ofEntries(
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

    private static final int DEFAULT_MAX = 10;

    private final boolean limitsEnabled;

    public PlanLimitHelper(@Value("${plan-limits.enabled:true}") boolean limitsEnabled) {
        this.limitsEnabled = limitsEnabled;
    }

    public int getMaxEndpoints(String userPlan) {
        if (!limitsEnabled) return PlanLimits.UNLIMITED;
        if (userPlan == null || userPlan.isBlank()) return DEFAULT_MAX;
        String normalized = userPlan.trim().toUpperCase();
        if ("ENTERPRISE".equals(normalized)) normalized = "ENTERPRISE_BASIC";
        return PLAN_LIMITS_MAP.getOrDefault(normalized, DEFAULT_MAX);
    }

    public void checkLimit(String userPlan, long currentCount) {
        if (!limitsEnabled) return;
        int max = getMaxEndpoints(userPlan);
        if (currentCount >= max) {
            throw new IllegalStateException("Resource limit reached: " + currentCount + "/" + max);
        }
    }
}
