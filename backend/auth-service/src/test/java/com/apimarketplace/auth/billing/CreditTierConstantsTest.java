package com.apimarketplace.auth.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CreditTierConstantsTest {

    @Test
    void getCreditCost_validTiers() {
        assertEquals(0, CreditTierConstants.getCreditCost(0));
        assertEquals(80, CreditTierConstants.getCreditCost(4));
        assertEquals(7_000, CreditTierConstants.getCreditCost(9));
    }

    @Test
    void getCreditCost_teamUsesExplicitPremiumCurve() {
        assertEquals(0, CreditTierConstants.getCreditCost(0, "TEAM"));
        assertEquals(100, CreditTierConstants.getCreditCost(4, "TEAM"));
        assertEquals(8_000, CreditTierConstants.getCreditCost(9, "TEAM"));
    }

    @Test
    void getCreditCost_invalidIndex() {
        assertThrows(IllegalArgumentException.class, () -> CreditTierConstants.getCreditCost(-1));
        assertThrows(IllegalArgumentException.class, () -> CreditTierConstants.getCreditCost(10));
    }

    @Test
    void getCreditAmount_validTiers() {
        assertEquals(5_000, CreditTierConstants.getCreditAmount(0));
        assertEquals(100_000, CreditTierConstants.getCreditAmount(4));
        assertEquals(10_000_000, CreditTierConstants.getCreditAmount(9));
    }

    @Test
    void validateTierForPlan_starterMaxTier() {
        assertDoesNotThrow(() -> CreditTierConstants.validateTierForPlan(0, "STARTER"));
        assertDoesNotThrow(() -> CreditTierConstants.validateTierForPlan(4, "STARTER"));
        assertThrows(IllegalArgumentException.class, () -> CreditTierConstants.validateTierForPlan(5, "STARTER"));
    }

    @Test
    void validateTierForPlan_proNoLimit() {
        assertDoesNotThrow(() -> CreditTierConstants.validateTierForPlan(9, "PRO"));
    }

    @Test
    void resolveTierIndex_knownCosts() {
        assertEquals(0, CreditTierConstants.resolveTierIndex(0));
        assertEquals(3, CreditTierConstants.resolveTierIndex(42));
        assertEquals(4, CreditTierConstants.resolveTierIndex(80));
        assertEquals(9, CreditTierConstants.resolveTierIndex(7_000));
    }

    @Test
    void resolveTierIndex_teamKnownCosts() {
        assertEquals(3, CreditTierConstants.resolveTierIndex(55, "TEAM"));
        assertEquals(4, CreditTierConstants.resolveTierIndex(100, "TEAM"));
        assertEquals(9, CreditTierConstants.resolveTierIndex(8_000, "TEAM"));
    }

    @Test
    void resolveTierIndex_unknownCost() {
        assertEquals(0, CreditTierConstants.resolveTierIndex(999));
    }

    @Test
    void resolveTierIndex_unknownNonTableCosts() {
        assertEquals(0, CreditTierConstants.resolveTierIndex(65));
        assertEquals(0, CreditTierConstants.resolveTierIndex(5_000));
        assertEquals(0, CreditTierConstants.resolveTierIndex(65, "TEAM"));
        assertEquals(0, CreditTierConstants.resolveTierIndex(5_000, "TEAM"));
    }

    @Test
    void arraysHaveSameLength() {
        assertEquals(CreditTierConstants.CREDIT_TIERS.length, CreditTierConstants.CREDIT_COSTS.length);
        assertEquals(CreditTierConstants.CREDIT_TIERS.length, CreditTierConstants.TEAM_CREDIT_COSTS.length);
    }

    @Test
    void creditTierArrays_lockInExactContents() {
        assertArrayEquals(
            new int[]{5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000},
            CreditTierConstants.CREDIT_TIERS,
            "CREDIT_TIERS contract changed; update frontend mirror too");
        assertArrayEquals(
            new int[]{0, 10, 22, 42, 80, 185, 365, 720, 3_500, 7_000},
            CreditTierConstants.CREDIT_COSTS,
            "CREDIT_COSTS contract changed; update frontend mirror too");
        assertArrayEquals(
            new int[]{0, 15, 30, 55, 100, 230, 430, 825, 4_000, 8_000},
            CreditTierConstants.TEAM_CREDIT_COSTS,
            "TEAM_CREDIT_COSTS contract changed; update frontend mirror too");
    }

    @Test
    void creditsPerDollar_monotonicNonDecreasingFromTier1() {
        assertMonotonicCreditsPerDollar(CreditTierConstants.CREDIT_COSTS);
        assertMonotonicCreditsPerDollar(CreditTierConstants.TEAM_CREDIT_COSTS);
    }

    @Test
    void creditsPerDollar_respectsPricingFloors() {
        assertCreditsPerDollarAtMost(CreditTierConstants.CREDIT_COSTS, 1_428.58d);
        assertCreditsPerDollarAtMost(CreditTierConstants.TEAM_CREDIT_COSTS, 1_250.0d);
    }

    private static void assertMonotonicCreditsPerDollar(int[] costs) {
        double prevRatio = 0d;
        for (int i = 1; i < CreditTierConstants.CREDIT_TIERS.length; i++) {
            int credits = CreditTierConstants.CREDIT_TIERS[i];
            int cost = costs[i];
            assertTrue(cost > 0, "tier " + i + " must have non-zero cost");
            double ratio = (double) credits / cost;
            assertTrue(ratio >= prevRatio,
                    "c/$ regression at tier " + i + " (got " + ratio + ", previous was " + prevRatio + ")");
            prevRatio = ratio;
        }
    }

    private static void assertCreditsPerDollarAtMost(int[] costs, double maxRatio) {
        for (int i = 1; i < CreditTierConstants.CREDIT_TIERS.length; i++) {
            double ratio = (double) CreditTierConstants.CREDIT_TIERS[i] / costs[i];
            assertTrue(ratio <= maxRatio,
                    "tier " + i + " breaches the pricing floor (got c/$ = " + ratio + ")");
        }
    }
}
