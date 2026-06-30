package com.apimarketplace.orchestrator.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for RedisCacheKeys utility class.
 * Validates key generation patterns and consistency.
 */
@DisplayName("RedisCacheKeys")
class RedisCacheKeysTest {

    @Nested
    @DisplayName("executionGraph")
    class ExecutionGraphTests {

        @Test
        @DisplayName("Should generate correct execution graph key")
        void shouldGenerateExecutionGraphKey() {
            String key = RedisCacheKeys.executionGraph("plan-123", 42);
            assertThat(key).isEqualTo("orchestrator:execution-graph:plan-123:42");
        }

        @Test
        @DisplayName("Should handle negative hash by using absolute value in caller")
        void shouldHandleHash() {
            String key = RedisCacheKeys.executionGraph("plan-456", 0);
            assertThat(key).isEqualTo("orchestrator:execution-graph:plan-456:0");
        }

        @Test
        @DisplayName("Should generate correct execution graph pattern")
        void shouldGenerateExecutionGraphPattern() {
            String pattern = RedisCacheKeys.executionGraphPattern("plan-123");
            assertThat(pattern).isEqualTo("orchestrator:execution-graph:plan-123:*");
        }

        @Test
        @DisplayName("Pattern with wildcard planId should match all plans")
        void shouldGenerateWildcardPattern() {
            String pattern = RedisCacheKeys.executionGraphPattern("*");
            assertThat(pattern).isEqualTo("orchestrator:execution-graph:*:*");
        }
    }

    @Nested
    @DisplayName("snapshot")
    class SnapshotTests {

        @Test
        @DisplayName("Should generate correct snapshot key")
        void shouldGenerateSnapshotKey() {
            String key = RedisCacheKeys.snapshot("run-abc-123");
            assertThat(key).isEqualTo("orchestrator:snapshot:run-abc-123");
        }
    }

    @Nested
    @DisplayName("pausedWorkflow")
    class PausedWorkflowTests {

        @Test
        @DisplayName("Should generate correct paused workflow key")
        void shouldGeneratePausedWorkflowKey() {
            String key = RedisCacheKeys.pausedWorkflow("run-abc");
            assertThat(key).isEqualTo("orchestrator:paused:run-abc");
        }
    }

    @Nested
    @DisplayName("evaluatedCores")
    class EvaluatedCoresTests {

        @Test
        @DisplayName("Should generate correct evaluated cores key")
        void shouldGenerateEvaluatedCoresKey() {
            String key = RedisCacheKeys.evaluatedCores("run-xyz");
            assertThat(key).isEqualTo("orchestrator:evaluated-cores:run-xyz");
        }
    }

    @Nested
    @DisplayName("stateManager")
    class StateManagerTests {

        @Test
        @DisplayName("Should generate correct state manager key")
        void shouldGenerateStateManagerKey() {
            String key = RedisCacheKeys.stateManager("run-state-1");
            assertThat(key).isEqualTo("orchestrator:state-manager:run-state-1");
        }
    }

    @Nested
    @DisplayName("lockKeys")
    class LockKeyTests {

        @Test
        @DisplayName("Should generate correct workflow run lock key")
        void shouldGenerateWorkflowRunLockKey() {
            String key = RedisCacheKeys.lockWorkflowRun("run-lock-1");
            assertThat(key).isEqualTo("orchestrator:lock:workflow-run:run-lock-1");
        }

        @Test
        @DisplayName("Should generate correct state transition lock key")
        void shouldGenerateStateTransitionLockKey() {
            String key = RedisCacheKeys.lockStateTransition("run-st-1");
            assertThat(key).isEqualTo("orchestrator:lock:state-transition:run-st-1");
        }
    }

    @Nested
    @DisplayName("workflowBuilder")
    class WorkflowBuilderTests {

        @Test
        @DisplayName("Should generate correct session key")
        void shouldGenerateSessionKey() {
            String key = RedisCacheKeys.workflowBuilderSession("wb_abc123");
            assertThat(key).isEqualTo("orchestrator:wb-session:wb_abc123");
        }

        @Test
        @DisplayName("Should generate correct tenant index key")
        void shouldGenerateTenantIndexKey() {
            String key = RedisCacheKeys.workflowBuilderTenantIndex("tenant-1");
            assertThat(key).isEqualTo("orchestrator:wb-tenant:tenant-1");
        }

        @Test
        @DisplayName("Should generate correct conversation index key")
        void shouldGenerateConversationIndexKey() {
            String key = RedisCacheKeys.workflowBuilderConversationIndex("tenant-1", "conv-99");
            assertThat(key).isEqualTo("orchestrator:wb-conv:tenant-1:conv-99");
        }

        @Test
        @DisplayName("Should generate correct session pattern")
        void shouldGenerateSessionPattern() {
            String pattern = RedisCacheKeys.workflowBuilderSessionPattern();
            assertThat(pattern).isEqualTo("orchestrator:wb-session:*");
        }
    }

    @Nested
    @DisplayName("cleanup patterns")
    class CleanupTests {

        @Test
        @DisplayName("Should generate correct pattern for all keys of a run")
        void shouldGenerateAllKeysForRunPattern() {
            String pattern = RedisCacheKeys.allKeysForRun("run-cleanup-1");
            assertThat(pattern).isEqualTo("orchestrator:*:run-cleanup-1");
        }

        @Test
        @DisplayName("Should generate correct pattern for all keys")
        void shouldGenerateAllKeysPattern() {
            String pattern = RedisCacheKeys.allKeys();
            assertThat(pattern).isEqualTo("orchestrator:*");
        }
    }

    @Nested
    @DisplayName("prefix consistency")
    class PrefixConsistencyTests {

        @ParameterizedTest(name = "Key for {0} should start with orchestrator:")
        @CsvSource({
            "executionGraph",
            "snapshot",
            "paused",
            "evaluatedCores",
            "stateManager",
            "lock",
            "wbSession",
            "wbTenant",
            "wbConv"
        })
        @DisplayName("All keys should use orchestrator: prefix")
        void allKeysShouldUseOrchestratorPrefix(String keyType) {
            String key = switch (keyType) {
                case "executionGraph" -> RedisCacheKeys.executionGraph("id", 0);
                case "snapshot" -> RedisCacheKeys.snapshot("id");
                case "paused" -> RedisCacheKeys.pausedWorkflow("id");
                case "evaluatedCores" -> RedisCacheKeys.evaluatedCores("id");
                case "stateManager" -> RedisCacheKeys.stateManager("id");
                case "lock" -> RedisCacheKeys.lockWorkflowRun("id");
                case "wbSession" -> RedisCacheKeys.workflowBuilderSession("id");
                case "wbTenant" -> RedisCacheKeys.workflowBuilderTenantIndex("id");
                case "wbConv" -> RedisCacheKeys.workflowBuilderConversationIndex("t", "c");
                default -> throw new IllegalArgumentException("Unknown key type: " + keyType);
            };
            assertThat(key).startsWith("orchestrator:");
        }
    }
}
