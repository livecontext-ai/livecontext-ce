package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentState enum - execution lifecycle states.
 */
@DisplayName("AgentState")
class AgentStateTest {

    @Test
    @DisplayName("should have 8 states")
    void shouldHaveAllStates() {
        assertThat(AgentState.values()).hasSize(8);
    }

    @ParameterizedTest
    @EnumSource(AgentState.class)
    @DisplayName("all states should have a non-null description")
    void allStatesShouldHaveDescription(AgentState state) {
        assertThat(state.getDescription()).isNotNull().isNotBlank();
    }

    @Nested
    @DisplayName("isTerminal()")
    class IsTerminalTests {

        @Test
        @DisplayName("COMPLETED should be terminal")
        void completedShouldBeTerminal() {
            assertThat(AgentState.COMPLETED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("FAILED should be terminal")
        void failedShouldBeTerminal() {
            assertThat(AgentState.FAILED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("CANCELLED should be terminal")
        void cancelledShouldBeTerminal() {
            assertThat(AgentState.CANCELLED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("INITIALIZING should not be terminal")
        void initializingShouldNotBeTerminal() {
            assertThat(AgentState.INITIALIZING.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("CALLING_LLM should not be terminal")
        void callingLlmShouldNotBeTerminal() {
            assertThat(AgentState.CALLING_LLM.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("EXECUTING_TOOLS should not be terminal")
        void executingToolsShouldNotBeTerminal() {
            assertThat(AgentState.EXECUTING_TOOLS.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("DISCOVERING_TOOLS should not be terminal")
        void discoveringToolsShouldNotBeTerminal() {
            assertThat(AgentState.DISCOVERING_TOOLS.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("PROCESSING_RESULTS should not be terminal")
        void processingResultsShouldNotBeTerminal() {
            assertThat(AgentState.PROCESSING_RESULTS.isTerminal()).isFalse();
        }
    }

    @Nested
    @DisplayName("allowsToolExecution()")
    class AllowsToolExecutionTests {

        @Test
        @DisplayName("EXECUTING_TOOLS should allow tool execution")
        void executingToolsShouldAllow() {
            assertThat(AgentState.EXECUTING_TOOLS.allowsToolExecution()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = AgentState.class, names = {"INITIALIZING", "DISCOVERING_TOOLS",
                "CALLING_LLM", "PROCESSING_RESULTS", "COMPLETED", "FAILED", "CANCELLED"})
        @DisplayName("non-EXECUTING_TOOLS states should not allow tool execution")
        void otherStatesShouldNotAllow(AgentState state) {
            assertThat(state.allowsToolExecution()).isFalse();
        }
    }

    @Nested
    @DisplayName("allowsLLMCall()")
    class AllowsLLMCallTests {

        @Test
        @DisplayName("CALLING_LLM should allow LLM calls")
        void callingLlmShouldAllow() {
            assertThat(AgentState.CALLING_LLM.allowsLLMCall()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = AgentState.class, names = {"INITIALIZING", "DISCOVERING_TOOLS",
                "EXECUTING_TOOLS", "PROCESSING_RESULTS", "COMPLETED", "FAILED", "CANCELLED"})
        @DisplayName("non-CALLING_LLM states should not allow LLM calls")
        void otherStatesShouldNotAllow(AgentState state) {
            assertThat(state.allowsLLMCall()).isFalse();
        }
    }
}
