package com.apimarketplace.common.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PlanTier")
class PlanTierTest {

    @Nested
    @DisplayName("meets")
    class Meets {

        @Test
        @DisplayName("A plan above the requirement passes, a plan below it does not")
        void ordersTheTiers() {
            assertTrue(PlanTier.meets("TEAM", "PRO"));
            assertTrue(PlanTier.meets("PRO", "PRO"));
            assertFalse(PlanTier.meets("STARTER", "PRO"));
            assertFalse(PlanTier.meets("FREE", "PRO"));
        }

        @Test
        @DisplayName("No requirement lets every plan through, including no subscription at all")
        void noRequirementAllowsEveryone() {
            assertTrue(PlanTier.meets("FREE", null));
            assertTrue(PlanTier.meets("FREE", ""));
            assertTrue(PlanTier.meets("FREE", "FREE"));
            assertTrue(PlanTier.meets(PlanTier.NO_SUBSCRIPTION, "FREE"));
        }

        @Test
        @DisplayName("No subscription ranks as FREE, so a paid requirement blocks it")
        void noSubscriptionIsFree() {
            assertFalse(PlanTier.meets(PlanTier.NO_SUBSCRIPTION, "STARTER"));
            assertFalse(PlanTier.meets(null, "STARTER"));
            assertFalse(PlanTier.meets("  ", "STARTER"));
        }

        @Test
        @DisplayName("CE clears every requirement - a self-hosted install has no plan to upgrade to")
        void ceIsUnrestricted() {
            assertTrue(PlanTier.meets("CE", "TEAM"));
            assertTrue(PlanTier.meets("CE", "ENTERPRISE"));
        }

        @Test
        @DisplayName("An unknown USER plan fails OPEN rather than locking a paying customer out")
        void unknownUserPlanFailsOpen() {
            assertTrue(PlanTier.meets("SCALE_2027", "TEAM"));
        }

        @Test
        @DisplayName("An unknown REQUIRED plan is treated as no requirement, so a typo opens rather than seals")
        void unknownRequirementFailsOpen() {
            assertTrue(PlanTier.meets("FREE", "PROO"));
            assertTrue(PlanTier.meets("FREE", "gold"));
        }

        @Test
        @DisplayName("Case and surrounding whitespace do not change the verdict")
        void normalisesInput() {
            assertTrue(PlanTier.meets(" pro ", "pro"));
            assertFalse(PlanTier.meets(" free ", " PRO "));
        }

        @Test
        @DisplayName("PAYG ranks with STARTER: paying per call is not a tier upgrade")
        void paygDoesNotUnlockHigherTiers() {
            assertTrue(PlanTier.meets("PAYG", "STARTER"));
            assertFalse(PlanTier.meets("PAYG", "PRO"));
        }

        @Test
        @DisplayName("A credit pack is a top-up on top of a plan, not a plan: it unlocks nothing")
        void creditPackRanksAsFree() {
            assertFalse(PlanTier.meets("CREDIT_PACK", "STARTER"));
        }

        @Test
        @DisplayName("Any ENTERPRISE SKU clears every tier, including one this file has not been taught")
        void enterpriseSkusRankTogether() {
            assertTrue(PlanTier.meets("ENTERPRISE_BASIC", "TEAM"));
            assertTrue(PlanTier.meets("ENTERPRISE_ULTIMATE", "ENTERPRISE"));
            assertTrue(PlanTier.meets("ENTERPRISE_GALACTIC", "ENTERPRISE"));
        }
    }

    @Nested
    @DisplayName("normalizeRequirement")
    class NormalizeRequirement {

        @Test
        @DisplayName("Returns null for everything that is not a real requirement")
        void nullWhenNothingIsRequired() {
            assertNull(PlanTier.normalizeRequirement(null));
            assertNull(PlanTier.normalizeRequirement(""));
            assertNull(PlanTier.normalizeRequirement("FREE"));
            assertNull(PlanTier.normalizeRequirement("free"));
            assertNull(PlanTier.normalizeRequirement("not-a-plan"));
        }

        @Test
        @DisplayName("Returns the upper-cased code for a real requirement")
        void upperCasesARealRequirement() {
            assertEquals("PRO", PlanTier.normalizeRequirement(" pro "));
            assertEquals("ENTERPRISE", PlanTier.normalizeRequirement("enterprise"));
        }
    }

    @Nested
    @DisplayName("selectable codes")
    class Selectable {

        @Test
        @DisplayName("Offers one spelling per tier, cheapest first, FREE included as the way to clear a gate")
        void listsOneCodePerTier() {
            assertEquals(java.util.List.of("FREE", "STARTER", "PRO", "TEAM", "ENTERPRISE"),
                    PlanTier.selectableCodes());
        }

        @Test
        @DisplayName("Accepts only the codes it offers, whatever the casing")
        void validatesAgainstItsOwnList() {
            assertTrue(PlanTier.isSelectable("pro"));
            assertTrue(PlanTier.isSelectable("FREE"));
            // A real plan code that is not offered as a REQUIREMENT: an admin picks
            // ENTERPRISE, not one of its four SKUs.
            assertFalse(PlanTier.isSelectable("ENTERPRISE_BASIC"));
            assertFalse(PlanTier.isSelectable("PAYG"));
            assertFalse(PlanTier.isSelectable(null));
        }
    }

    @Nested
    @DisplayName("isPaid (strict, fails closed)")
    class IsPaid {

        @Test
        @DisplayName("Every known plan ranked above FREE is paid")
        void knownPaidPlans() {
            assertTrue(PlanTier.isPaid("STARTER"));
            assertTrue(PlanTier.isPaid("PAYG"));
            assertTrue(PlanTier.isPaid("PRO"));
            assertTrue(PlanTier.isPaid("TEAM"));
            assertTrue(PlanTier.isPaid("ENTERPRISE_BASIC"));
            assertTrue(PlanTier.isPaid(" pro "));
        }

        @Test
        @DisplayName("An ENTERPRISE SKU not listed yet is still paid (the prefix is the product)")
        void unlistedEnterpriseSkuIsPaid() {
            assertTrue(PlanTier.isPaid("ENTERPRISE_GOLD"));
        }

        @Test
        @DisplayName("FREE and CREDIT_PACK are not paid: a top-up is not a plan")
        void freeAndCreditPackAreNotPaid() {
            assertFalse(PlanTier.isPaid("FREE"));
            assertFalse(PlanTier.isPaid("CREDIT_PACK"));
        }

        @Test
        @DisplayName("CE, no subscription, null, blank and unknown codes are NOT paid, unlike userRank which fails open")
        void unknownsFailClosed() {
            assertFalse(PlanTier.isPaid(PlanTier.CE));
            assertFalse(PlanTier.isPaid(PlanTier.NO_SUBSCRIPTION));
            assertFalse(PlanTier.isPaid(null));
            assertFalse(PlanTier.isPaid(""));
            assertFalse(PlanTier.isPaid("   "));
            assertFalse(PlanTier.isPaid("PLATINUM"));
            // The contrast that makes this predicate necessary: the gating rank lets it through.
            assertTrue(PlanTier.meets("PLATINUM", "PRO"));
        }
    }
}
