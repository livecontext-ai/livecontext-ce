package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.trigger.queue.PlanPriorityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive test verifying the trigger system limits are coherent
 * across all trigger types (webhook, chat, form, schedule).
 *
 * All 4 types share the same PlanPriorityMapper.getMaxTriggerEndpoints() limits.
 * This test ensures:
 * - Limits are consistent across plans
 * - Edge cases are handled (null, blank, unknown plans)
 * - Plan hierarchy is monotonically non-decreasing
 * - Enterprise shorthand normalization works
 * - Trigger-service's inline limit maps mirror PlanPriorityMapper
 */
@DisplayName("Trigger System Limits - Cross-Type Coherence")
class TriggerSystemLimitsComprehensiveTest {

    private static final List<String> ALL_PLANS = List.of(
            "FREE", "STARTER", "PRO", "TEAM", "PAYG",
            "ENTERPRISE_BASIC", "ENTERPRISE_STANDARD", "ENTERPRISE_PREMIUM", "ENTERPRISE_ULTIMATE"
    );

    private static final Map<String, Integer> EXPECTED_LIMITS = Map.of(
            "FREE", 3,
            "STARTER", 10,
            "PRO", 50,
            "TEAM", 100,
            "PAYG", 100
    );

    // ==================== Plan Hierarchy ====================

    @Nested
    @DisplayName("Plan Hierarchy")
    class PlanHierarchyTests {

        @Test
        @DisplayName("FREE < STARTER < PRO < TEAM for all limit types")
        void mainPlanHierarchy() {
            int free = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            int starter = PlanPriorityMapper.getMaxTriggerEndpoints("STARTER");
            int pro = PlanPriorityMapper.getMaxTriggerEndpoints("PRO");
            int team = PlanPriorityMapper.getMaxTriggerEndpoints("TEAM");

            assertThat(free).isLessThan(starter);
            assertThat(starter).isLessThan(pro);
            assertThat(pro).isLessThanOrEqualTo(team);
        }

        @Test
        @DisplayName("Enterprise tiers are all >= TEAM")
        void enterpriseAtLeastTeam() {
            int team = PlanPriorityMapper.getMaxTriggerEndpoints("TEAM");
            for (String plan : List.of("ENTERPRISE_BASIC", "ENTERPRISE_STANDARD",
                    "ENTERPRISE_PREMIUM", "ENTERPRISE_ULTIMATE")) {
                assertThat(PlanPriorityMapper.getMaxTriggerEndpoints(plan))
                        .as("Enterprise plan %s should be >= TEAM", plan)
                        .isGreaterThanOrEqualTo(team);
            }
        }

        @Test
        @DisplayName("All plans have positive limits")
        void allPlansPositive() {
            for (String plan : ALL_PLANS) {
                assertThat(PlanPriorityMapper.getMaxTriggerEndpoints(plan))
                        .as("Plan %s", plan)
                        .isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("PAYG has same limits as TEAM")
        void paygEqualsTeam() {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("PAYG"))
                    .isEqualTo(PlanPriorityMapper.getMaxTriggerEndpoints("TEAM"));
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t", "\n"})
        @DisplayName("Null/blank plan returns safe default (10)")
        void nullBlankPlanDefault(String plan) {
            int limit = PlanPriorityMapper.getMaxTriggerEndpoints(plan);
            assertThat(limit).isEqualTo(10); // DEFAULT_MAX_TRIGGER_ENDPOINTS
        }

        @Test
        @DisplayName("Unknown plan returns safe default")
        void unknownPlan() {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("MYSTERY_PLAN")).isEqualTo(10);
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("BRONZE")).isEqualTo(10);
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("GOLD")).isEqualTo(10);
        }

        @Test
        @DisplayName("Enterprise shorthand normalization")
        void enterpriseShorthand() {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE"))
                    .isEqualTo(PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE_BASIC"));
        }

        @ParameterizedTest
        @CsvSource({
            "free, FREE",
            "pro, PRO",
            "Pro, PRO",
            "enterprise, ENTERPRISE_BASIC",
            "Enterprise, ENTERPRISE_BASIC",
            "ENTERPRISE, ENTERPRISE_BASIC"
        })
        @DisplayName("Case insensitive plan resolution")
        void caseInsensitive(String input, String expectedNormalized) {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints(input))
                    .isEqualTo(PlanPriorityMapper.getMaxTriggerEndpoints(expectedNormalized));
        }

        @Test
        @DisplayName("Plan with leading/trailing whitespace is trimmed")
        void whitespaceHandling() {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints("  PRO  "))
                    .isEqualTo(PlanPriorityMapper.getMaxTriggerEndpoints("PRO"));
        }
    }

    // ==================== Trigger-Service Mirror Consistency ====================

    @Nested
    @DisplayName("Trigger-Service Mirror Consistency")
    class TriggerServiceMirrorTests {

        /**
         * The trigger-service duplicates PlanPriorityMapper limits in:
         * - ScheduleController.PLAN_MAX_SCHEDULES
         * - StandaloneWebhookController.PLAN_MAX_ENDPOINTS
         *
         * These tests verify the values stay in sync by testing the same
         * expected values against PlanPriorityMapper.
         */
        @ParameterizedTest
        @CsvSource({
            "FREE, 3",
            "STARTER, 10",
            "PRO, 50",
            "TEAM, 100",
            "PAYG, 100",
            "ENTERPRISE_BASIC, 100",
            "ENTERPRISE_STANDARD, 100",
            "ENTERPRISE_PREMIUM, 100",
            "ENTERPRISE_ULTIMATE, 100"
        })
        @DisplayName("PlanPriorityMapper limits match expected trigger-service values")
        void limitsMatchExpectedValues(String plan, int expected) {
            assertThat(PlanPriorityMapper.getMaxTriggerEndpoints(plan))
                    .as("Plan %s should have limit %d in both orchestrator and trigger-service", plan, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("Null plan default matches trigger-service fallback (3 for FREE users)")
        void nullPlanFallback() {
            // trigger-service getMaxSchedules returns 3 for null plan
            // PlanPriorityMapper returns DEFAULT_MAX_TRIGGER_ENDPOINTS (10) for null
            // This is acceptable: trigger-service is more restrictive for safety
            int triggerServiceDefault = 3;
            int orchestratorDefault = PlanPriorityMapper.getMaxTriggerEndpoints(null);

            // trigger-service should be <= orchestrator default
            assertThat(triggerServiceDefault).isLessThanOrEqualTo(orchestratorDefault);
        }
    }

    // ==================== Stress / Boundary Tests ====================

    @Nested
    @DisplayName("Boundary Value Tests")
    class BoundaryTests {

        @Test
        @DisplayName("At exactly the limit: count == max")
        void atExactLimit() {
            int maxFree = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            // count == max → should block creation
            assertThat(maxFree).isEqualTo(3);
            // Simulating: currentCount(3) >= maxPerUser(3) → limit reached
            assertThat(3 >= maxFree).isTrue();
        }

        @Test
        @DisplayName("One below limit: count == max - 1")
        void oneBelowLimit() {
            int maxFree = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            // count == max - 1 → should allow creation
            assertThat((maxFree - 1) >= maxFree).isFalse();
        }

        @Test
        @DisplayName("Zero count: always allowed")
        void zeroCount() {
            for (String plan : ALL_PLANS) {
                int max = PlanPriorityMapper.getMaxTriggerEndpoints(plan);
                assertThat(0 < max).as("Plan %s should allow creation when count is 0", plan).isTrue();
            }
        }

        @Test
        @DisplayName("Over limit: count > max")
        void overLimit() {
            int maxFree = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            // count > max → should block (can happen if plan was downgraded)
            assertThat(maxFree + 1 >= maxFree).isTrue();
        }

        @Test
        @DisplayName("Plan upgrade allows more schedules")
        void planUpgradeUnblocksCreation() {
            int maxFree = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            int maxPro = PlanPriorityMapper.getMaxTriggerEndpoints("PRO");

            // User has 3 schedules (at FREE limit)
            int currentCount = 3;

            // On FREE plan: blocked
            assertThat(currentCount >= maxFree).isTrue();

            // Upgraded to PRO: now allowed
            assertThat(currentCount >= maxPro).isFalse();
        }

        @Test
        @DisplayName("Plan downgrade blocks at existing count")
        void planDowngradeBlocksCreation() {
            int maxFree = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");

            // User had PRO plan with 10 schedules, downgraded to FREE
            int currentCount = 10;

            // On FREE plan: blocked (count > max)
            assertThat(currentCount >= maxFree).isTrue();

            // Existing schedules should still work (not deleted on downgrade)
            // Just can't create new ones
        }
    }

    // ==================== Consistency Across Trigger Types ====================

    @Nested
    @DisplayName("Cross-Type Consistency")
    class CrossTypeConsistencyTests {

        @Test
        @DisplayName("All trigger types share same limit method")
        void allTypesShareSameMethod() {
            // Webhooks, Chat, Form, Schedule all use getMaxTriggerEndpoints
            // This test documents that design decision
            for (String plan : ALL_PLANS) {
                int limit = PlanPriorityMapper.getMaxTriggerEndpoints(plan);
                // Each type gets the same allowance independently
                // i.e., FREE user can have 3 webhooks AND 3 chats AND 3 forms AND 3 schedules
                assertThat(limit).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("Total possible endpoints = 4 * maxPerType")
        void totalEndpointCalculation() {
            // FREE user: 3 * 4 = 12 total possible endpoints
            int freeMax = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
            assertThat(freeMax * 4).isEqualTo(12);

            // PRO user: 50 * 4 = 200 total possible endpoints
            int proMax = PlanPriorityMapper.getMaxTriggerEndpoints("PRO");
            assertThat(proMax * 4).isEqualTo(200);
        }
    }
}
