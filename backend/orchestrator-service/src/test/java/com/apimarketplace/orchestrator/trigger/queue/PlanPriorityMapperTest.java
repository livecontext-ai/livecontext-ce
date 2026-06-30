package com.apimarketplace.orchestrator.trigger.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanPriorityMapperTest {

    @ParameterizedTest
    @CsvSource({
        "FREE, 0",
        "STARTER, 10",
        "PRO, 20",
        "TEAM, 30",
        "PAYG, 30",
        "ENTERPRISE_BASIC, 40",
        "ENTERPRISE_STANDARD, 50",
        "ENTERPRISE_PREMIUM, 60",
        "ENTERPRISE_ULTIMATE, 70"
    })
    void allPlanCodesMappedCorrectly(String planCode, int expectedPriority) {
        assertEquals(expectedPriority, PlanPriorityMapper.getPriority(planCode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void nullOrBlankReturnsFree(String planCode) {
        assertEquals(0, PlanPriorityMapper.getPriority(planCode));
    }

    @Test
    void unknownPlanReturnsDefaultPriority() {
        assertEquals(0, PlanPriorityMapper.getPriority("UNKNOWN_PLAN"));
    }

    @Test
    void enterpriseShorthandMapsToEnterpriseBasic() {
        assertEquals(40, PlanPriorityMapper.getPriority("ENTERPRISE"));
    }

    @Test
    void caseInsensitive() {
        assertEquals(20, PlanPriorityMapper.getPriority("pro"));
        assertEquals(20, PlanPriorityMapper.getPriority("Pro"));
        assertEquals(70, PlanPriorityMapper.getPriority("enterprise_ultimate"));
    }

    @Test
    void enterpriseShorthandCaseInsensitive() {
        assertEquals(40, PlanPriorityMapper.getPriority("enterprise"));
        assertEquals(40, PlanPriorityMapper.getPriority("Enterprise"));
    }

    @Test
    void higherPlanHasHigherPriority() {
        assertTrue(PlanPriorityMapper.getPriority("ENTERPRISE_ULTIMATE") >
                   PlanPriorityMapper.getPriority("FREE"));
        assertTrue(PlanPriorityMapper.getPriority("PRO") >
                   PlanPriorityMapper.getPriority("STARTER"));
        assertTrue(PlanPriorityMapper.getPriority("ENTERPRISE_PREMIUM") >
                   PlanPriorityMapper.getPriority("ENTERPRISE_STANDARD"));
    }

    @ParameterizedTest
    @CsvSource({
        "0, 7",
        "10, 6",
        "20, 5",
        "30, 4",
        "40, 3",
        "50, 2",
        "60, 1",
        "70, 0",
        "700, 0",
        "-10, 7"
    })
    void redisTierInvertsPlanPriorityBecauseLowerRedisTierIsHigherPriority(int planPriority, int expectedTier) {
        assertEquals(expectedTier, PlanPriorityMapper.toRedisPriorityTier(planPriority));
    }

    // --- getMaxConcurrentRuns tests ---

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
    void allPlanMaxRunsMappedCorrectly(String planCode, int expectedMax) {
        assertEquals(expectedMax, PlanPriorityMapper.getMaxConcurrentRuns(planCode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void nullOrBlankMaxRunsReturnsDefault(String planCode) {
        assertEquals(3, PlanPriorityMapper.getMaxConcurrentRuns(planCode));
    }

    @Test
    void unknownPlanMaxRunsReturnsDefault() {
        assertEquals(3, PlanPriorityMapper.getMaxConcurrentRuns("UNKNOWN_PLAN"));
    }

    @Test
    void enterpriseShorthandMaxRuns() {
        assertEquals(100, PlanPriorityMapper.getMaxConcurrentRuns("ENTERPRISE"));
    }

    @Test
    void maxRunsCaseInsensitive() {
        assertEquals(50, PlanPriorityMapper.getMaxConcurrentRuns("pro"));
        assertEquals(100, PlanPriorityMapper.getMaxConcurrentRuns("team"));
    }

    @Test
    void higherPlanHasMoreRuns() {
        assertTrue(PlanPriorityMapper.getMaxConcurrentRuns("PRO") >
                   PlanPriorityMapper.getMaxConcurrentRuns("STARTER"));
        assertTrue(PlanPriorityMapper.getMaxConcurrentRuns("TEAM") >
                   PlanPriorityMapper.getMaxConcurrentRuns("PRO"));
    }

    // --- getMaxTriggerEndpoints tests ---

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
    void allPlanMaxEndpointsMappedCorrectly(String planCode, int expectedMax) {
        assertEquals(expectedMax, PlanPriorityMapper.getMaxTriggerEndpoints(planCode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void nullOrBlankMaxEndpointsReturnsDefault(String planCode) {
        assertEquals(10, PlanPriorityMapper.getMaxTriggerEndpoints(planCode));
    }

    @Test
    void unknownPlanMaxEndpointsReturnsDefault() {
        assertEquals(10, PlanPriorityMapper.getMaxTriggerEndpoints("UNKNOWN_PLAN"));
    }

    @Test
    void enterpriseShorthandMaxEndpoints() {
        assertEquals(100, PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE"));
    }

    @Test
    void maxEndpointsCaseInsensitive() {
        assertEquals(50, PlanPriorityMapper.getMaxTriggerEndpoints("pro"));
        assertEquals(100, PlanPriorityMapper.getMaxTriggerEndpoints("team"));
        assertEquals(3, PlanPriorityMapper.getMaxTriggerEndpoints("free"));
    }

    @Test
    void higherPlanHasMoreEndpoints() {
        assertTrue(PlanPriorityMapper.getMaxTriggerEndpoints("PRO") >
                   PlanPriorityMapper.getMaxTriggerEndpoints("STARTER"));
        assertTrue(PlanPriorityMapper.getMaxTriggerEndpoints("TEAM") >
                   PlanPriorityMapper.getMaxTriggerEndpoints("PRO"));
        assertTrue(PlanPriorityMapper.getMaxTriggerEndpoints("STARTER") >
                   PlanPriorityMapper.getMaxTriggerEndpoints("FREE"));
    }

    @Test
    void freePlanHasLowestEndpointLimit() {
        int freeLimit = PlanPriorityMapper.getMaxTriggerEndpoints("FREE");
        assertEquals(3, freeLimit);
        assertTrue(freeLimit < PlanPriorityMapper.getMaxTriggerEndpoints("STARTER"));
    }

    @Test
    void allEnterprisePlansHaveSameEndpointLimit() {
        int basic = PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE_BASIC");
        int standard = PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE_STANDARD");
        int premium = PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE_PREMIUM");
        int ultimate = PlanPriorityMapper.getMaxTriggerEndpoints("ENTERPRISE_ULTIMATE");
        assertEquals(basic, standard);
        assertEquals(standard, premium);
        assertEquals(premium, ultimate);
        assertEquals(100, basic);
    }

    // --- Cross-method consistency tests ---

    @Test
    void allPlanCodesAreConsistentAcrossMethods() {
        // Every plan that has a priority should also have endpoint limits
        for (String plan : List.of("FREE", "STARTER", "PRO", "TEAM", "PAYG",
                "ENTERPRISE_BASIC", "ENTERPRISE_STANDARD", "ENTERPRISE_PREMIUM", "ENTERPRISE_ULTIMATE")) {
            assertTrue(PlanPriorityMapper.getMaxTriggerEndpoints(plan) > 0,
                    "Plan " + plan + " should have positive endpoint limit");
            assertTrue(PlanPriorityMapper.getMaxConcurrentRuns(plan) > 0,
                    "Plan " + plan + " should have positive run limit");
        }
    }
}
