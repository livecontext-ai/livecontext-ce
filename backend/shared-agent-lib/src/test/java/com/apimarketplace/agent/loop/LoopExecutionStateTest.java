package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentState;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for LoopExecutionState - mutable state holder for agent loop execution.
 */
@DisplayName("LoopExecutionState")
class LoopExecutionStateTest {

    private LoopExecutionState state;

    @BeforeEach
    void setUp() {
        state = new LoopExecutionState("run-1", 10, 0);
    }

    @Nested
    @DisplayName("Constructor and initial state")
    class InitialStateTests {

        @Test
        @DisplayName("should initialize with correct values")
        void shouldInitializeCorrectly() {
            assertThat(state.getRunId()).isEqualTo("run-1");
            assertThat(state.getMaxIterations()).isEqualTo(10);
            assertThat(state.getIterations()).isEqualTo(0);
            assertThat(state.getCurrentState()).isEqualTo(AgentState.INITIALIZING);
            assertThat(state.getStopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("should initialize empty collections")
        void shouldInitializeEmptyCollections() {
            assertThat(state.getMessages()).isEmpty();
            assertThat(state.getAllToolResults()).isEmpty();
            assertThat(state.getIterationDurations()).isEmpty();
            assertThat(state.getToolCallsPerIteration()).isEmpty();
            assertThat(state.getMetrics()).isEmpty();
        }

        @Test
        @DisplayName("should initialize token counters to zero")
        void shouldInitializeTokenCountersToZero() {
            assertThat(state.getTotalPromptTokens()).isEqualTo(0);
            assertThat(state.getTotalCompletionTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("should initialize loop detector")
        void shouldInitializeComponents() {
            assertThat(state.getLoopDetector()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Iteration control")
    class IterationControlTests {

        @Test
        @DisplayName("incrementIterations should increase count")
        void shouldIncrementIterations() {
            state.incrementIterations();
            assertThat(state.getIterations()).isEqualTo(1);

            state.incrementIterations();
            assertThat(state.getIterations()).isEqualTo(2);
        }

        @Test
        @DisplayName("hasMoreIterations should return true when below max")
        void shouldHaveMoreIterations() {
            assertThat(state.hasMoreIterations()).isTrue();
        }

        @Test
        @DisplayName("hasMoreIterations should return false when at max")
        void shouldNotHaveMoreIterationsAtMax() {
            for (int i = 0; i < 10; i++) {
                state.incrementIterations();
            }
            assertThat(state.hasMoreIterations()).isFalse();
        }

        @Test
        @DisplayName("isLastIteration should return true at max")
        void shouldBeLastIteration() {
            for (int i = 0; i < 10; i++) {
                state.incrementIterations();
            }
            assertThat(state.isLastIteration()).isTrue();
        }

        @Test
        @DisplayName("isLastIteration should return false before max")
        void shouldNotBeLastIterationBefore() {
            state.incrementIterations();
            assertThat(state.isLastIteration()).isFalse();
        }
    }

    @Nested
    @DisplayName("Tool results tracking")
    class ToolResultsTests {

        @Test
        @DisplayName("addToolResults should accumulate results")
        void shouldAddToolResults() {
            ToolResult r1 = ToolResult.builder()
                    .toolCall(null).success(true).content("result1").build();
            ToolResult r2 = ToolResult.builder()
                    .toolCall(null).success(false).content(null).error("error1").build();

            state.addToolResults(List.of(r1, r2));

            assertThat(state.getAllToolResults()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Metrics recording")
    class MetricsTests {

        @Test
        @DisplayName("recordIterationDuration should track durations")
        void shouldRecordIterationDuration() {
            state.recordIterationDuration(100L);
            state.recordIterationDuration(200L);

            assertThat(state.getIterationDurations()).containsExactly(100L, 200L);
        }

        @Test
        @DisplayName("recordToolCallCount should track counts")
        void shouldRecordToolCallCount() {
            state.recordToolCallCount(3);
            state.recordToolCallCount(5);

            assertThat(state.getToolCallsPerIteration()).containsExactly(3, 5);
        }
    }

    @Nested
    @DisplayName("Token tracking")
    class TokenTrackingTests {

        @Test
        @DisplayName("trackUsage should accumulate tokens")
        void shouldTrackUsage() {
            UsageInfo usage1 = UsageInfo.builder().promptTokens(100).completionTokens(50).build();
            UsageInfo usage2 = UsageInfo.builder().promptTokens(200).completionTokens(75).build();

            state.trackUsage(usage1);
            state.trackUsage(usage2);

            assertThat(state.getTotalPromptTokens()).isEqualTo(300);
            assertThat(state.getTotalCompletionTokens()).isEqualTo(125);
        }

        @Test
        @DisplayName("trackUsage should handle null usage")
        void shouldHandleNullUsage() {
            state.trackUsage(null);

            assertThat(state.getTotalPromptTokens()).isEqualTo(0);
            assertThat(state.getTotalCompletionTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("trackUsage should handle null token values")
        void shouldHandleNullTokenValues() {
            UsageInfo usage = UsageInfo.builder().build();

            state.trackUsage(usage);

            assertThat(state.getTotalPromptTokens()).isEqualTo(0);
            assertThat(state.getTotalCompletionTokens()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Content tracking")
    class ContentTests {

        @Test
        @DisplayName("appendContent should accumulate content")
        void shouldAppendContent() {
            state.appendContent("Hello ");
            state.appendContent("World");

            assertThat(state.getFullContent().toString()).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("appendContent should handle null")
        void shouldHandleNullContent() {
            state.appendContent("Hello");
            state.appendContent(null);

            assertThat(state.getFullContent().toString()).isEqualTo("Hello");
        }
    }

    @Nested
    @DisplayName("buildUsageInfo()")
    class BuildUsageInfoTests {

        @Test
        @DisplayName("should build UsageInfo from accumulated tokens")
        void shouldBuildUsageInfo() {
            UsageInfo usage = UsageInfo.builder().promptTokens(100).completionTokens(50).build();
            state.trackUsage(usage);

            UsageInfo result = state.buildUsageInfo();

            assertThat(result.promptTokens()).isEqualTo(100);
            assertThat(result.completionTokens()).isEqualTo(50);
            assertThat(result.totalTokens()).isEqualTo(150);
        }
    }

    @Nested
    @DisplayName("buildFinalMetrics()")
    class BuildFinalMetricsTests {

        @Test
        @DisplayName("should populate metrics map")
        void shouldPopulateMetrics() {
            state.incrementIterations();
            state.incrementIterations();

            ToolResult success = ToolResult.builder()
                    .toolCall(null).success(true).content("ok").build();
            ToolResult failure = ToolResult.builder()
                    .toolCall(null).success(false).content(null).error("err").build();
            state.addToolResults(List.of(success, failure));

            state.recordIterationDuration(100L);
            state.recordIterationDuration(200L);

            state.recordToolCallCount(1);
            state.recordToolCallCount(1);

            UsageInfo usage = UsageInfo.builder().promptTokens(500).completionTokens(200).build();
            state.trackUsage(usage);

            state.buildFinalMetrics();

            Map<String, Object> metrics = state.getMetrics();
            assertThat(metrics.get("totalIterations")).isEqualTo(2);
            assertThat(metrics.get("totalToolCalls")).isEqualTo(2);
            assertThat(metrics.get("successfulToolCalls")).isEqualTo(1L);
            assertThat(metrics.get("failedToolCalls")).isEqualTo(1L);
            assertThat(metrics.get("totalPromptTokens")).isEqualTo(500);
            assertThat(metrics.get("totalCompletionTokens")).isEqualTo(200);
            assertThat(metrics).containsKey("avgIterationDurationMs");
            assertThat(metrics).containsKey("toolCallsPerIteration");
        }
    }

    @Nested
    @DisplayName("markLoopDetected()")
    class MarkLoopDetectedTests {

        @Test
        @DisplayName("should record identical loop detection")
        void shouldRecordIdenticalLoop() {
            state.markLoopDetected(true, "search");

            Map<String, Object> metrics = state.getMetrics();
            assertThat(metrics.get("loopDetected")).isEqualTo(true);
            assertThat(metrics.get("loopType")).isEqualTo("identical");
            assertThat(metrics.get("loopToolName")).isEqualTo("search");
            assertThat(metrics.get("finalResponseGiven")).isEqualTo(true);
        }

        @Test
        @DisplayName("should record consecutive loop detection")
        void shouldRecordConsecutiveLoop() {
            state.markLoopDetected(false, null);

            Map<String, Object> metrics = state.getMetrics();
            assertThat(metrics.get("loopType")).isEqualTo("consecutive");
            assertThat(metrics).doesNotContainKey("loopToolName");
        }
    }

    @Nested
    @DisplayName("getDuration()")
    class GetDurationTests {

        @Test
        @DisplayName("should return positive duration since creation")
        void shouldReturnPositiveDuration() {
            assertThat(state.getDuration()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("State mutation")
    class StateMutationTests {

        @Test
        @DisplayName("should allow setting current state")
        void shouldSetCurrentState() {
            state.setCurrentState(AgentState.EXECUTING_TOOLS);
            assertThat(state.getCurrentState()).isEqualTo(AgentState.EXECUTING_TOOLS);
        }

        @Test
        @DisplayName("should allow setting stop reason")
        void shouldSetStopReason() {
            state.setStopReason(AgentStopReason.MAX_ITERATIONS);
            assertThat(state.getStopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        }
    }

    @Nested
    @DisplayName("Execution message filtering (getCurrentExecutionMessages)")
    class ExecutionMessageFilteringTests {

        @Test
        @DisplayName("should return empty when markExecutionStart called on empty messages")
        void shouldReturnEmptyWhenNoMessagesAfterMark() {
            state.markExecutionStart();
            assertThat(state.getCurrentExecutionMessages()).isEmpty();
        }

        @Test
        @DisplayName("should exclude history messages and only return execution messages")
        void shouldExcludeHistoryMessages() {
            // Simulate: system prompt + 3 history messages + user prompt (= pre-execution)
            state.getMessages().add(Message.system("You are a helpful assistant"));
            state.getMessages().add(Message.builder().role(Message.Role.USER).content("old question").build());
            state.getMessages().add(Message.builder().role(Message.Role.ASSISTANT).content("old answer").build());
            state.getMessages().add(Message.builder().role(Message.Role.USER).content("old follow-up").build());
            state.getMessages().add(Message.user("current prompt"));

            // Mark: everything above is pre-execution context
            state.markExecutionStart();

            // Simulate execution: LLM responds + tool call + tool result
            state.getMessages().add(Message.builder().role(Message.Role.ASSISTANT).content("I'll search for that").build());
            state.getMessages().add(Message.builder().role(Message.Role.TOOL).content("search result").build());
            state.getMessages().add(Message.builder().role(Message.Role.ASSISTANT).content("Here is the answer").build());

            // Only the 3 execution messages should be returned
            var executionMessages = state.getCurrentExecutionMessages();
            assertThat(executionMessages).hasSize(3);
            assertThat(executionMessages.get(0).content()).isEqualTo("I'll search for that");
            assertThat(executionMessages.get(1).content()).isEqualTo("search result");
            assertThat(executionMessages.get(2).content()).isEqualTo("Here is the answer");
        }

        @Test
        @DisplayName("should return all messages when no history was loaded")
        void shouldReturnAllWhenNoHistory() {
            // No history - just system prompt + user prompt
            state.getMessages().add(Message.system("system"));
            state.getMessages().add(Message.user("prompt"));

            state.markExecutionStart();

            // Execution messages
            state.getMessages().add(Message.builder().role(Message.Role.ASSISTANT).content("response").build());

            var executionMessages = state.getCurrentExecutionMessages();
            assertThat(executionMessages).hasSize(1);
            assertThat(executionMessages.get(0).content()).isEqualTo("response");
        }

        @Test
        @DisplayName("returned list should be independent copy")
        void shouldReturnIndependentCopy() {
            state.getMessages().add(Message.system("system"));
            state.markExecutionStart();
            state.getMessages().add(Message.builder().role(Message.Role.ASSISTANT).content("response").build());

            var copy = state.getCurrentExecutionMessages();
            copy.clear(); // mutate the copy

            // Original should be unaffected
            assertThat(state.getMessages()).hasSize(2);
            assertThat(state.getCurrentExecutionMessages()).hasSize(1);
        }
    }
}
