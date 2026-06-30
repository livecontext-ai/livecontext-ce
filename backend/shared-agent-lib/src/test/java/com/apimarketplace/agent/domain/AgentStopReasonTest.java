package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentStopReason enum.
 */
@DisplayName("AgentStopReason")
class AgentStopReasonTest {

    @Test
    @DisplayName("should have 10 stop reasons")
    void shouldHaveAllReasons() {
        assertThat(AgentStopReason.values()).hasSize(10);
    }

    @ParameterizedTest
    @EnumSource(AgentStopReason.class)
    @DisplayName("all reasons should have non-null description")
    void allReasonsShouldHaveDescription(AgentStopReason reason) {
        assertThat(reason.getDescription()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("COMPLETED should be in the SUCCESS terminal category")
    void completedDescription() {
        assertThat(AgentStopReason.COMPLETED.terminal())
            .isEqualTo(AgentStopReason.TerminalCategory.SUCCESS);
        assertThat(AgentStopReason.COMPLETED.isSuccessLike()).isTrue();
    }

    @Test
    @DisplayName("MAX_ITERATIONS should mention iteration limit")
    void maxIterationsDescription() {
        assertThat(AgentStopReason.MAX_ITERATIONS.getDescription()).containsIgnoringCase("iteration limit");
        assertThat(AgentStopReason.MAX_ITERATIONS.isPartial()).isTrue();
    }

    @Test
    @DisplayName("ERROR should be in the FAILURE terminal category")
    void errorDescription() {
        assertThat(AgentStopReason.ERROR.terminal())
            .isEqualTo(AgentStopReason.TerminalCategory.FAILURE);
        assertThat(AgentStopReason.ERROR.isFailure()).isTrue();
    }

    @Test
    @DisplayName("TIMEOUT should mention timeout in the description")
    void timeoutDescription() {
        assertThat(AgentStopReason.TIMEOUT.getDescription()).containsIgnoringCase("timeout");
        assertThat(AgentStopReason.TIMEOUT.isPartial()).isTrue();
    }

    @Test
    @DisplayName("INACTIVITY_TIMEOUT is a failure and is distinct from the (partial) total TIMEOUT")
    void inactivityTimeoutVsTotalTimeout() {
        // A frozen/stalled agent that the inactivity watchdog had to kill is a FAILURE,
        // unlike TIMEOUT which is a PARTIAL outcome (the agent was working the whole time).
        assertThat(AgentStopReason.INACTIVITY_TIMEOUT.isFailure()).isTrue();
        assertThat(AgentStopReason.INACTIVITY_TIMEOUT.terminal())
            .isEqualTo(AgentStopReason.TerminalCategory.FAILURE);
        assertThat(AgentStopReason.TIMEOUT.isPartial()).isTrue();
        assertThat(AgentStopReason.INACTIVITY_TIMEOUT.terminal())
            .isNotEqualTo(AgentStopReason.TIMEOUT.terminal());
        assertThat(AgentStopReason.INACTIVITY_TIMEOUT.getDescription())
            .containsIgnoringCase("no activity");
        assertThat(AgentStopReason.valueOfOrError("INACTIVITY_TIMEOUT"))
            .isEqualTo(AgentStopReason.INACTIVITY_TIMEOUT);
    }

    @Test
    @DisplayName("NO_TOOLS should mention tool discovery and be a failure")
    void noToolsDescription() {
        assertThat(AgentStopReason.NO_TOOLS.getDescription()).containsIgnoringCase("tool");
        assertThat(AgentStopReason.NO_TOOLS.isFailure()).isTrue();
    }

    @Test
    @DisplayName("BUDGET_EXHAUSTED is a partial outcome (output may be usable)")
    void budgetExhaustedIsPartial() {
        assertThat(AgentStopReason.BUDGET_EXHAUSTED.isPartial()).isTrue();
    }

    @Test
    @DisplayName("STOPPED_BY_USER is a partial outcome distinct from system CANCELLED")
    void stoppedByUserVsCancelled() {
        assertThat(AgentStopReason.STOPPED_BY_USER.isPartial()).isTrue();
        assertThat(AgentStopReason.CANCELLED.isFailure()).isTrue();
    }

    @Test
    @DisplayName("valueOfOrError returns ERROR for unknown input instead of throwing")
    void valueOfOrErrorLenient() {
        assertThat(AgentStopReason.valueOfOrError("UNKNOWN_REASON")).isEqualTo(AgentStopReason.ERROR);
        assertThat(AgentStopReason.valueOfOrError(null)).isEqualTo(AgentStopReason.ERROR);
        assertThat(AgentStopReason.valueOfOrError("MAX_ITERATIONS")).isEqualTo(AgentStopReason.MAX_ITERATIONS);
    }
}
