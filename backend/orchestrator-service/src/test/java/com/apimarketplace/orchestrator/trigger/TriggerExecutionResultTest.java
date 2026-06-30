package com.apimarketplace.orchestrator.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TriggerExecutionResult record.
 */
@DisplayName("TriggerExecutionResult")
class TriggerExecutionResultTest {

    @Nested
    @DisplayName("success factory method")
    class SuccessTests {

        @Test
        @DisplayName("Should create a success result with default message")
        void shouldCreateSuccessResult() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, Set.of("step1"), 1
            );

            assertThat(result.success()).isTrue();
            assertThat(result.runId()).isEqualTo("run-1");
            assertThat(result.triggerId()).isEqualTo("trigger:webhook");
            assertThat(result.triggerType()).isEqualTo(TriggerType.WEBHOOK);
            assertThat(result.readySteps()).containsExactly("step1");
            assertThat(result.epoch()).isEqualTo(1);
            assertThat(result.message()).isEqualTo("Trigger executed successfully");
        }

        @Test
        @DisplayName("Should handle null readySteps by using empty set")
        void shouldHandleNullReadySteps() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:manual", TriggerType.MANUAL, null, 2
            );

            assertThat(result.readySteps()).isEmpty();
        }

        @Test
        @DisplayName("Should create success result with custom message")
        void shouldCreateSuccessWithCustomMessage() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:chat", TriggerType.CHAT, "Custom message", Set.of(), 3
            );

            assertThat(result.message()).isEqualTo("Custom message");
            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("failure factory method")
    class FailureTests {

        @Test
        @DisplayName("Should create a failure result")
        void shouldCreateFailureResult() {
            TriggerExecutionResult result = TriggerExecutionResult.failure(
                "run-2", "trigger:schedule", TriggerType.SCHEDULE, "Run not found"
            );

            assertThat(result.success()).isFalse();
            assertThat(result.runId()).isEqualTo("run-2");
            assertThat(result.triggerId()).isEqualTo("trigger:schedule");
            assertThat(result.message()).isEqualTo("Run not found");
            assertThat(result.readySteps()).isEmpty();
            assertThat(result.epoch()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("isPausedWaitingForUser")
    class IsPausedWaitingForUserTests {

        @Test
        @DisplayName("Should return true when successful with ready steps")
        void shouldReturnTrueWhenSuccessfulWithReadySteps() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:manual", TriggerType.MANUAL, Set.of("step1", "step2"), 1
            );

            assertThat(result.isPausedWaitingForUser()).isTrue();
        }

        @Test
        @DisplayName("Should return false when successful but no ready steps")
        void shouldReturnFalseWhenNoReadySteps() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:manual", TriggerType.MANUAL, Set.of(), 1
            );

            assertThat(result.isPausedWaitingForUser()).isFalse();
        }

        @Test
        @DisplayName("Should return false when failed")
        void shouldReturnFalseWhenFailed() {
            TriggerExecutionResult result = TriggerExecutionResult.failure(
                "run-1", "trigger:manual", TriggerType.MANUAL, "Error"
            );

            assertThat(result.isPausedWaitingForUser()).isFalse();
        }
    }

    @Nested
    @DisplayName("isCycleCompleted")
    class IsCycleCompletedTests {

        @Test
        @DisplayName("Should return true when successful with no ready steps")
        void shouldReturnTrueWhenAutoModeCompleted() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, Set.of(), 1
            );

            assertThat(result.isCycleCompleted()).isTrue();
        }

        @Test
        @DisplayName("Should return false when successful but has ready steps")
        void shouldReturnFalseWhenHasReadySteps() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, Set.of("step1"), 1
            );

            assertThat(result.isCycleCompleted()).isFalse();
        }

        @Test
        @DisplayName("Should return false when failed")
        void shouldReturnFalseWhenFailed() {
            TriggerExecutionResult result = TriggerExecutionResult.failure(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, "Error"
            );

            assertThat(result.isCycleCompleted()).isFalse();
        }

        @Test
        @DisplayName("Should return true when readySteps is null (success with null)")
        void shouldReturnTrueWhenReadyStepsNull() {
            TriggerExecutionResult result = TriggerExecutionResult.success(
                "run-1", "trigger:webhook", TriggerType.WEBHOOK, (Set<String>) null, 1
            );

            assertThat(result.isCycleCompleted()).isTrue();
        }
    }
}
