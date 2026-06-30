package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RunStatus")
class RunStatusTest {

    @Nested
    @DisplayName("getValue()")
    class GetValueTests {

        @Test
        @DisplayName("Each status should have correct value string")
        void eachStatusShouldHaveCorrectValue() {
            assertEquals("pending", RunStatus.PENDING.getValue());
            assertEquals("running", RunStatus.RUNNING.getValue());
            assertEquals("completed", RunStatus.COMPLETED.getValue());
            assertEquals("failed", RunStatus.FAILED.getValue());
            assertEquals("partial_success", RunStatus.PARTIAL_SUCCESS.getValue());
            assertEquals("cancelled", RunStatus.CANCELLED.getValue());
            assertEquals("timeout", RunStatus.TIMEOUT.getValue());
            assertEquals("paused", RunStatus.PAUSED.getValue());
            assertEquals("waiting_trigger", RunStatus.WAITING_TRIGGER.getValue());
        }
    }

    @Nested
    @DisplayName("toWireValue()")
    class ToWireValueTests {

        @Test
        @DisplayName("toWireValue should return same as getValue")
        void toWireValueShouldMatchGetValue() {
            for (RunStatus status : RunStatus.values()) {
                assertEquals(status.getValue(), status.toWireValue());
            }
        }
    }

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @ParameterizedTest
        @EnumSource(value = RunStatus.class, names = {"COMPLETED", "SKIPPED", "FAILED", "PARTIAL_SUCCESS", "CANCELLED", "TIMEOUT"})
        @DisplayName("Terminal statuses should return true")
        void terminalStatusesShouldReturnTrue(RunStatus status) {
            assertTrue(status.isTerminal());
        }

        @ParameterizedTest
        @EnumSource(value = RunStatus.class, names = {"PENDING", "RUNNING", "PAUSED", "WAITING_TRIGGER", "AWAITING_SIGNAL"})
        @DisplayName("Non-terminal statuses should return false")
        void nonTerminalStatusesShouldReturnFalse(RunStatus status) {
            assertFalse(status.isTerminal());
        }
    }

    @Nested
    @DisplayName("isPaused()")
    class IsPausedTests {

        @Test
        @DisplayName("PAUSED should return true")
        void pausedShouldReturnTrue() {
            assertTrue(RunStatus.PAUSED.isPaused());
        }

        @Test
        @DisplayName("RUNNING should return false")
        void runningShouldReturnFalse() {
            assertFalse(RunStatus.RUNNING.isPaused());
        }
    }

    @Nested
    @DisplayName("canResume()")
    class CanResumeTests {

        @Test
        @DisplayName("PAUSED should be resumable")
        void pausedShouldBeResumable() {
            assertTrue(RunStatus.PAUSED.canResume());
        }

        @Test
        @DisplayName("RUNNING should not be resumable")
        void runningShouldNotBeResumable() {
            assertFalse(RunStatus.RUNNING.canResume());
        }

        @Test
        @DisplayName("COMPLETED should not be resumable")
        void completedShouldNotBeResumable() {
            assertFalse(RunStatus.COMPLETED.canResume());
        }
    }

    @Nested
    @DisplayName("isWaitingForTrigger()")
    class IsWaitingForTriggerTests {

        @Test
        @DisplayName("WAITING_TRIGGER should return true")
        void waitingTriggerShouldReturnTrue() {
            assertTrue(RunStatus.WAITING_TRIGGER.isWaitingForTrigger());
        }

        @Test
        @DisplayName("RUNNING should return false")
        void runningShouldReturnFalse() {
            assertFalse(RunStatus.RUNNING.isWaitingForTrigger());
        }
    }

    @Nested
    @DisplayName("isSuccess()")
    class IsSuccessTests {

        @Test
        @DisplayName("COMPLETED should be success")
        void completedShouldBeSuccess() {
            assertTrue(RunStatus.COMPLETED.isSuccess());
        }

        @Test
        @DisplayName("FAILED should not be success")
        void failedShouldNotBeSuccess() {
            assertFalse(RunStatus.FAILED.isSuccess());
        }
    }

    @Nested
    @DisplayName("isFailure()")
    class IsFailureTests {

        @Test
        @DisplayName("FAILED should be failure")
        void failedShouldBeFailure() {
            assertTrue(RunStatus.FAILED.isFailure());
        }

        @Test
        @DisplayName("CANCELLED should be failure")
        void cancelledShouldBeFailure() {
            assertTrue(RunStatus.CANCELLED.isFailure());
        }

        @Test
        @DisplayName("TIMEOUT should be failure")
        void timeoutShouldBeFailure() {
            assertTrue(RunStatus.TIMEOUT.isFailure());
        }

        @Test
        @DisplayName("COMPLETED should not be failure")
        void completedShouldNotBeFailure() {
            assertFalse(RunStatus.COMPLETED.isFailure());
        }
    }

    @Nested
    @DisplayName("fromString()")
    class FromStringTests {

        @Test
        @DisplayName("Should parse valid values")
        void shouldParseValidValues() {
            assertEquals(RunStatus.COMPLETED, RunStatus.fromString("completed"));
            assertEquals(RunStatus.FAILED, RunStatus.fromString("failed"));
            assertEquals(RunStatus.RUNNING, RunStatus.fromString("running"));
            assertEquals(RunStatus.PENDING, RunStatus.fromString("pending"));
            assertEquals(RunStatus.PAUSED, RunStatus.fromString("paused"));
            assertEquals(RunStatus.CANCELLED, RunStatus.fromString("cancelled"));
            assertEquals(RunStatus.TIMEOUT, RunStatus.fromString("timeout"));
            assertEquals(RunStatus.WAITING_TRIGGER, RunStatus.fromString("waiting_trigger"));
            assertEquals(RunStatus.PARTIAL_SUCCESS, RunStatus.fromString("partial_success"));
        }

        @Test
        @DisplayName("Should parse legacy aliases")
        void shouldParseLegacyAliases() {
            assertEquals(RunStatus.PAUSED, RunStatus.fromString("pausing"));
            assertEquals(RunStatus.RUNNING, RunStatus.fromString("resuming"));
            assertEquals(RunStatus.PENDING, RunStatus.fromString("ready"));
            assertEquals(RunStatus.SKIPPED, RunStatus.fromString("skipped"));
        }

        @Test
        @DisplayName("Should return PENDING for null")
        void shouldReturnPendingForNull() {
            assertEquals(RunStatus.PENDING, RunStatus.fromString(null));
        }

        @Test
        @DisplayName("Should return PENDING for blank string")
        void shouldReturnPendingForBlank() {
            assertEquals(RunStatus.PENDING, RunStatus.fromString(""));
            assertEquals(RunStatus.PENDING, RunStatus.fromString("   "));
        }

        @Test
        @DisplayName("Should return PENDING for unknown value")
        void shouldReturnPendingForUnknown() {
            assertEquals(RunStatus.PENDING, RunStatus.fromString("unknown_status"));
        }
    }
}
