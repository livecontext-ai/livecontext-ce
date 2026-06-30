package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for the package-private static helpers that shape user-facing failure
 * text and bound the reviewer reject-loop. Both are documented as "for unit testing" yet had
 * no coverage; a regression here silently re-introduces a double "agent execution failed"
 * prefix or lets a malicious per-task {@code max_review_attempts} create a near-infinite loop.
 */
@DisplayName("AgentTaskService - pure helpers")
class AgentTaskServicePureFunctionTest {

    @Nested
    @DisplayName("stripTautologicalPrefix")
    class StripTautologicalPrefix {

        @Test
        @DisplayName("null / blank → a specific default reason")
        void nullOrBlankDefaults() {
            String expected = "agent execution failed without a specific reason";
            assertThat(AgentTaskService.stripTautologicalPrefix(null)).isEqualTo(expected);
            assertThat(AgentTaskService.stripTautologicalPrefix("   ")).isEqualTo(expected);
        }

        @Test
        @DisplayName("a bare tautological phrase collapses to the default reason")
        void bareTautologyCollapses() {
            assertThat(AgentTaskService.stripTautologicalPrefix("Agent execution failed"))
                    .isEqualTo("agent execution failed without a specific reason");
        }

        @Test
        @DisplayName("a single prefix is normalised (case + single prefix)")
        void singlePrefixNormalised() {
            assertThat(AgentTaskService.stripTautologicalPrefix("Agent execution failed: disk full"))
                    .isEqualTo("agent execution failed: disk full");
        }

        @Test
        @DisplayName("nested duplicate prefixes are stripped down to the real reason")
        void nestedPrefixesStripped() {
            assertThat(AgentTaskService.stripTautologicalPrefix(
                    "Agent execution failed: Agent execution failed: rate limited"))
                    .isEqualTo("agent execution failed: rate limited");
        }

        @Test
        @DisplayName("a plain reason is prefixed exactly once")
        void plainReasonPrefixedOnce() {
            assertThat(AgentTaskService.stripTautologicalPrefix("timeout after 600s"))
                    .isEqualTo("agent execution failed: timeout after 600s");
        }
    }

    @Nested
    @DisplayName("effectiveMaxReviewAttempts")
    class EffectiveMaxReviewAttempts {

        private AgentTaskEntity taskWithCap(Integer cap) {
            AgentTaskEntity t = new AgentTaskEntity();
            t.setMaxReviewAttempts(cap);
            return t;
        }

        @Test
        @DisplayName("null task → the default cap")
        void nullTaskDefaults() {
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(null))
                    .isEqualTo(AgentTaskService.MAX_REVIEW_ATTEMPTS);
        }

        @Test
        @DisplayName("null override → the default cap")
        void nullOverrideDefaults() {
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(taskWithCap(null)))
                    .isEqualTo(AgentTaskService.MAX_REVIEW_ATTEMPTS);
        }

        @Test
        @DisplayName("an in-range override is honoured")
        void inRangeOverrideHonoured() {
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(taskWithCap(10))).isEqualTo(10);
        }

        @Test
        @DisplayName("an excessive override is clamped to the ceiling (anti runaway-loop)")
        void excessiveOverrideClampedToCeiling() {
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(taskWithCap(10_000)))
                    .isEqualTo(AgentTaskService.MAX_REVIEW_ATTEMPTS_CEILING);
        }

        @Test
        @DisplayName("zero / negative override is clamped up to 1")
        void nonPositiveOverrideClampedToOne() {
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(taskWithCap(0))).isEqualTo(1);
            assertThat(AgentTaskService.effectiveMaxReviewAttempts(taskWithCap(-5))).isEqualTo(1);
        }
    }
}
