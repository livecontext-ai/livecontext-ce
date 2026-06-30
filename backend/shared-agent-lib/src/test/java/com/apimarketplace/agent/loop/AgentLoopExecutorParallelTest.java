package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for parallel tool execution in AgentLoopExecutor.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor Parallel Tool Execution")
class AgentLoopExecutorParallelTest {

    @Mock private ToolExecutionService toolExecutionService;

    private ExecutorService toolExecutor;
    private AgentLoopExecutor parallelExecutor;
    private AgentLoopExecutor sequentialExecutor;
    private LoopExecutionState state;

    private static final long TOOL_TIMEOUT_MS = 5000L;

    @BeforeEach
    void setUp() {
        toolExecutor = Executors.newFixedThreadPool(4);
        parallelExecutor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP, toolExecutor, TOOL_TIMEOUT_MS, true
        );
        sequentialExecutor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP, toolExecutor, TOOL_TIMEOUT_MS, false
        );
        state = new LoopExecutionState("run-1", 10, 0);
    }

    private ToolCall makeToolCall(String name, int index) {
        return ToolCall.builder()
            .id("call_" + name + "_" + index)
            .toolName(name)
            .arguments(Map.of("action", "execute"))
            .index(index)
            .build();
    }

    private ToolDefinition makeToolDef(String name) {
        return ToolDefinition.builder()
            .id(name)
            .name(name)
            .description("Test tool " + name)
            .build();
    }

    @Nested
    @DisplayName("Parallel execution with multiple tools")
    class ParallelTests {

        @Test
        @DisplayName("should execute 3 tool calls concurrently and return results in original order")
        void shouldExecuteInParallelAndPreserveOrder() throws Exception {
            ToolCall call1 = makeToolCall("agent", 0);
            ToolCall call2 = makeToolCall("catalog", 1);
            ToolCall call3 = makeToolCall("table", 2);
            List<ToolCall> toolCalls = List.of(call1, call2, call3);

            ToolDefinition def1 = makeToolDef("agent");
            ToolDefinition def2 = makeToolDef("catalog");
            ToolDefinition def3 = makeToolDef("table");
            List<ToolDefinition> tools = List.of(def1, def2, def3);

            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);
            CountDownLatch allStarted = new CountDownLatch(3);

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    allStarted.countDown();
                    // Wait for all to start before completing, proving concurrency
                    allStarted.await(2, TimeUnit.SECONDS);
                    Thread.sleep(50);
                    concurrentCount.decrementAndGet();
                    ToolCall tc = inv.getArgument(0);
                    return ToolResult.success(tc, "result_" + tc.toolName());
                });

            AgentLoopContext context = buildContext();
            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, context, state);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).toolCall().toolName()).isEqualTo("agent");
            assertThat(results.get(1).toolCall().toolName()).isEqualTo("catalog");
            assertThat(results.get(2).toolCall().toolName()).isEqualTo("table");
            assertThat(results.get(0).content()).isEqualTo("result_agent");
            assertThat(results.get(1).content()).isEqualTo("result_catalog");
            assertThat(results.get(2).content()).isEqualTo("result_table");

            verify(toolExecutionService, times(3)).executeTool(any(), any(), any(), any());

            // Verify concurrency happened (max concurrent >= 2 proves parallelism)
            assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("should handle one tool failure without affecting others")
        void shouldHandlePartialFailure() {
            ToolCall call1 = makeToolCall("agent", 0);
            ToolCall call2 = makeToolCall("catalog", 1);
            List<ToolCall> toolCalls = List.of(call1, call2);
            List<ToolDefinition> tools = List.of(makeToolDef("agent"), makeToolDef("catalog"));

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ToolCall tc = inv.getArgument(0);
                    if (tc.toolName().equals("agent")) {
                        throw new RuntimeException("Agent failed");
                    }
                    return ToolResult.success(tc, "catalog_ok");
                });

            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, buildContext(), state);

            assertThat(results).hasSize(2);
            // First tool failed
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).error()).contains("Agent failed");
            // Second tool succeeded
            assertThat(results.get(1).success()).isTrue();
            assertThat(results.get(1).content()).isEqualTo("catalog_ok");
        }
    }

    @Nested
    @DisplayName("Sequential fallback")
    class SequentialFallbackTests {

        @Test
        @DisplayName("should fall back to sequential when parallelToolExecution is false")
        void shouldUseSequentialWhenFlagDisabled() {
            ToolCall call1 = makeToolCall("agent", 0);
            ToolCall call2 = makeToolCall("catalog", 1);
            List<ToolCall> toolCalls = List.of(call1, call2);
            List<ToolDefinition> tools = List.of(makeToolDef("agent"), makeToolDef("catalog"));

            AtomicInteger maxConcurrent = new AtomicInteger(0);
            AtomicInteger concurrentCount = new AtomicInteger(0);

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    Thread.sleep(50);
                    concurrentCount.decrementAndGet();
                    ToolCall tc = inv.getArgument(0);
                    return ToolResult.success(tc, "result_" + tc.toolName());
                });

            List<ToolResult> results = invokeExecuteToolCalls(sequentialExecutor, toolCalls, tools, buildContext(), state);

            assertThat(results).hasSize(2);
            // Sequential = max concurrent should be exactly 1
            assertThat(maxConcurrent.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Single tool call")
    class SingleToolCallTests {

        @Test
        @DisplayName("should stay sequential for single tool call even with parallel enabled")
        void shouldNotParallelizeForSingleCall() {
            ToolCall call1 = makeToolCall("agent", 0);
            List<ToolCall> toolCalls = List.of(call1);
            List<ToolDefinition> tools = List.of(makeToolDef("agent"));

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenReturn(ToolResult.success(call1, "result"));

            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, buildContext(), state);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isTrue();
        }

        @Test
        @DisplayName("binds credential orgId on the tool executor thread")
        void bindsCredentialOrgIdOnToolExecutorThread() {
            ToolCall call1 = makeToolCall("agent", 0);
            List<ToolCall> toolCalls = List.of(call1);
            List<ToolDefinition> tools = List.of(makeToolDef("agent"));

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> ToolResult.success(inv.getArgument(0), TenantResolver.currentRequestOrganizationId()));

            AgentLoopContext context = AgentLoopContext.builder()
                .tenantId("tenant-1")
                .userPrompt("test prompt")
                .credentials(Map.of("__orgId__", "org-loop-executor"))
                .build();

            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, context, state);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).content()).isEqualTo("org-loop-executor");
            assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
        }
    }

    @Nested
    @DisplayName("Workflow tool exclusion from parallelism")
    class WorkflowExclusionTests {

        @Test
        @DisplayName("should execute workflow tools sequentially even in parallel mode")
        void shouldKeepWorkflowToolsSequential() {
            ToolCall workflowCall = makeToolCall("workflow", 0);
            ToolCall agentCall = makeToolCall("agent", 1);
            ToolCall catalogCall = makeToolCall("catalog", 2);
            List<ToolCall> toolCalls = List.of(workflowCall, agentCall, catalogCall);
            List<ToolDefinition> tools = List.of(makeToolDef("workflow"), makeToolDef("agent"), makeToolDef("catalog"));

            AtomicInteger concurrentCount = new AtomicInteger(0);
            AtomicInteger maxConcurrent = new AtomicInteger(0);

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    Thread.sleep(50);
                    concurrentCount.decrementAndGet();
                    ToolCall tc = inv.getArgument(0);
                    return ToolResult.success(tc, "result_" + tc.toolName());
                });

            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, buildContext(), state);

            assertThat(results).hasSize(3);
            // All results present in correct order
            assertThat(results.get(0).toolCall().toolName()).isEqualTo("workflow");
            assertThat(results.get(1).toolCall().toolName()).isEqualTo("agent");
            assertThat(results.get(2).toolCall().toolName()).isEqualTo("catalog");
            // Workflow ran sequentially (before parallel batch), so max concurrent should be <= 2
            // (agent + catalog in parallel, but not workflow)
            assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("should execute all workflow-only calls sequentially")
        void shouldRunAllWorkflowCallsSequentially() {
            ToolCall wf1 = makeToolCall("workflow", 0);
            ToolCall wf2 = makeToolCall("workflow", 1);
            List<ToolCall> toolCalls = List.of(wf1, wf2);
            List<ToolDefinition> tools = List.of(makeToolDef("workflow"));

            AtomicInteger maxConcurrent = new AtomicInteger(0);
            AtomicInteger concurrentCount = new AtomicInteger(0);

            when(toolExecutionService.executeTool(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    Thread.sleep(50);
                    concurrentCount.decrementAndGet();
                    ToolCall tc = inv.getArgument(0);
                    return ToolResult.success(tc, "result_" + tc.toolName());
                });

            List<ToolResult> results = invokeExecuteToolCalls(parallelExecutor, toolCalls, tools, buildContext(), state);

            assertThat(results).hasSize(2);
            // Both sequential - max concurrent must be 1
            assertThat(maxConcurrent.get()).isEqualTo(1);
        }
    }

    // --- Helpers ---

    private AgentLoopContext buildContext() {
        return AgentLoopContext.builder()
            .tenantId("tenant-1")
            .userPrompt("test prompt")
            .build();
    }

    /**
     * Uses reflection to invoke the private executeToolCalls method for testing.
     * Passes null callback (no STOP signal in this test suite - see
     * {@link AgentLoopExecutorMidIterationStopTest} for cancel-aware coverage).
     */
    private List<ToolResult> invokeExecuteToolCalls(
            AgentLoopExecutor executor,
            List<ToolCall> toolCalls,
            List<ToolDefinition> tools,
            AgentLoopContext context,
            LoopExecutionState state) {
        try {
            var method = AgentLoopExecutor.class.getDeclaredMethod(
                "executeToolCalls", List.class, List.class, AgentLoopContext.class, LoopExecutionState.class,
                com.apimarketplace.agent.streaming.StreamingCallback.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<ToolResult> results = (List<ToolResult>) method.invoke(
                executor, toolCalls, tools, context, state, null);
            return results;
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke executeToolCalls", e);
        }
    }
}
