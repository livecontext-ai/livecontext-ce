package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentState;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentLoopExecutor task turn stop")
class AgentLoopExecutorTaskTurnStopTest {

    @Test
    @DisplayName("Stops after a successful task decision tool when executing an agent task")
    void stopsAfterTaskDecisionToolInTaskContext() {
        ToolCall taskCompleteCall = ToolCall.builder()
                .id("call-task-complete")
                .toolName("agent")
                .arguments(Map.of("action", "task_complete"))
                .build();
        LLMProvider provider = providerReturning(taskCompleteCall);
        ToolExecutionService toolExecutionService = successfulToolWithMetadata(
                Map.of("stopAgentLoop", true));
        AgentLoopExecutor executor = newExecutor(toolExecutionService);
        LoopExecutionState state = new LoopExecutionState("run-task", 3, 0);
        AgentLoopContext context = AgentLoopContext.builder()
                .tenantId("tenant-1")
                .credentials(Map.of("__taskId__", "11111111-1111-1111-1111-111111111111"))
                .build();

        AgentLoopExecutor.IterationResult result = executor.processIteration(
                provider,
                "model",
                context,
                List.of(ToolDefinition.builder().name("agent").build()),
                state,
                "system",
                null);

        assertThat(result.shouldContinue()).isFalse();
        assertThat(result.isComplete()).isTrue();
        assertThat(state.getCurrentState()).isEqualTo(AgentState.COMPLETED);
        assertThat(state.getStopReason()).isEqualTo(AgentStopReason.COMPLETED);
        assertThat(state.getIterations()).isEqualTo(1);
        assertThat(state.getAllToolResults()).hasSize(1);
    }

    @Test
    @DisplayName("Does not stop on task decision metadata outside a task execution")
    void ignoresTaskDecisionMetadataOutsideTaskContext() {
        ToolCall taskCompleteCall = ToolCall.builder()
                .id("call-task-complete")
                .toolName("agent")
                .arguments(Map.of("action", "task_complete"))
                .build();
        LLMProvider provider = providerReturning(taskCompleteCall);
        ToolExecutionService toolExecutionService = successfulToolWithMetadata(
                Map.of("stopAgentLoop", true));
        AgentLoopExecutor executor = newExecutor(toolExecutionService);
        LoopExecutionState state = new LoopExecutionState("run-chat", 3, 0);
        AgentLoopContext context = AgentLoopContext.builder()
                .tenantId("tenant-1")
                .credentials(Map.of())
                .build();

        AgentLoopExecutor.IterationResult result = executor.processIteration(
                provider,
                "model",
                context,
                List.of(ToolDefinition.builder().name("agent").build()),
                state,
                "system",
                null);

        assertThat(result.shouldContinue()).isTrue();
        assertThat(state.getCurrentState()).isEqualTo(AgentState.EXECUTING_TOOLS);
        assertThat(state.getStopReason()).isEqualTo(AgentStopReason.COMPLETED);
    }

    private static AgentLoopExecutor newExecutor(ToolExecutionService toolExecutionService) {
        return new AgentLoopExecutor(
                toolExecutionService,
                AgentLogger.NOOP,
                Executors.newSingleThreadExecutor(),
                5000L,
                false);
    }

    private static LLMProvider providerReturning(ToolCall toolCall) {
        return new LLMProvider() {
            @Override
            public String getProviderName() {
                return "test";
            }

            @Override
            public String getDefaultModel() {
                return "model";
            }

            @Override
            public List<String> getSupportedModels() {
                return List.of("model");
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public boolean supportsStreaming() {
                return false;
            }

            @Override
            public boolean supportsToolCalling() {
                return true;
            }

            @Override
            public CompletionResponse complete(CompletionRequest request) {
                return CompletionResponse.builder()
                        .content("")
                        .finishReason("tool_calls")
                        .toolCalls(List.of(toolCall))
                        .usage(UsageInfo.builder()
                                .promptTokens(1)
                                .completionTokens(1)
                                .totalTokens(2)
                                .build())
                        .build();
            }

            @Override
            public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
                throw new UnsupportedOperationException("streaming is not used in this test");
            }

            @Override
            public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
                return Flux.empty();
            }
        };
    }

    private static ToolExecutionService successfulToolWithMetadata(Map<String, Object> metadata) {
        return new ToolExecutionService() {
            @Override
            public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                          String tenantId, Map<String, Object> credentials) {
                return ToolResult.builder()
                        .toolCall(toolCall)
                        .success(true)
                        .content("{\"status\":\"in_review\"}")
                        .durationMs(1L)
                        .metadata(metadata)
                        .build();
            }

            @Override
            public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                return true;
            }
        };
    }
}
