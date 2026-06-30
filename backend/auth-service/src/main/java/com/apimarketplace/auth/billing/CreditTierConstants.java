package com.apimarketplace.auth.billing;

/**
 * Single source of truth for credit tier definitions.
 * Mirrors frontend CREDIT_TIERS / CREDIT_COSTS / TEAM_CREDIT_COSTS arrays.
 *
 * <p>Slider position (tierIndex) maps to a credit amount and a dollar cost.
 * The dollar cost becomes the Stripe quantity for the per-unit credit pack price.
 *
 * <p><b>Pricing curve (revised 2026-05-27)</b>: degressive credit packs with a
 * $0.70 / 1k floor on Pro and a $0.80 / 1k floor on Team. Team has explicit
 * per-tier costs rather than an unbounded multiplier, so large workflow-heavy
 * workspaces are not punished indefinitely.
 */
public final class CreditTierConstants {

    public static final int[] CREDIT_TIERS = {
        5_000, 10_000, 25_000, 50_000, 100_000,
        250_000, 500_000, 1_000_000, 5_000_000, 10_000_000
    };

    public static final int[] CREDIT_COSTS = {
        0, 10, 22, 42, 80,
        185, 365, 720, 3_500, 7_000
    };

    public static final int[] TEAM_CREDIT_COSTS = {
        0, 15, 30, 55, 100,
        230, 430, 825, 4_000, 8_000
    };

    /** Starter plan max tier index (100K credits). */
    public static final int STARTER_MAX_TIER_INDEX = 4;

    private CreditTierConstants() {}

    public static int getCreditCost(int tierIndex) {
        validateTierIndex(tierIndex);
        return CREDIT_COSTS[tierIndex];
    }

    public static int getCreditCost(int tierIndex, String planCode) {
        validateTierIndex(tierIndex);
        return isTeamPlan(planCode) ? TEAM_CREDIT_COSTS[tierIndex] : CREDIT_COSTS[tierIndex];
    }

    public static int getCreditAmount(int tierIndex) {
        validateTierIndex(tierIndex);
        return CREDIT_TIERS[tierIndex];
    }

    public static void validateTierForPlan(int tierIndex, String planCode) {
        validateTierIndex(tierIndex);
        if ("STARTER".equalsIgnoreCase(planCode) && tierIndex > STARTER_MAX_TIER_INDEX) {
            throw new IllegalArgumentException(
                "Starter plan supports up to tier " + STARTER_MAX_TIER_INDEX +
                " (" + CREDIT_TIERS[STARTER_MAX_TIER_INDEX] + " credits). Requested tier: " + tierIndex
            );
        }
    }

    /** Reverse lookup: find the tier index for a given credit cost (Stripe quantity). */
    public static int resolveTierIndex(int creditQuantity) {
        return resolveTierIndex(creditQuantity, null);
    }

    /** Reverse lookup with plan-aware Team pricing. */
    public static int resolveTierIndex(int creditQuantity, String planCode) {
        int[] primaryCosts = isTeamPlan(planCode) ? TEAM_CREDIT_COSTS : CREDIT_COSTS;
        Integer exact = findTierIndex(primaryCosts, creditQuantity);
        if (exact != null) {
            return exact;
        }

        return 0;
    }

    private static Integer findTierIndex(int[] costs, int creditQuantity) {
        for (int i = 0; i < costs.length; i++) {
            if (costs[i] == creditQuantity) {
                return i;
            }
        }
        return null;
    }

    private static boolean isTeamPlan(String planCode) {
        return "TEAM".equalsIgnoreCase(planCode);
    }

    private static void validateTierIndex(int tierIndex) {
        if (tierIndex < 0 || tierIndex >= CREDIT_TIERS.length) {
            throw new IllegalArgumentException(
                "Invalid tier index: " + tierIndex + ". Must be 0-" + (CREDIT_TIERS.length - 1)
            );
        }
    }
}
