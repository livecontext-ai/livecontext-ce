package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for {@code AgentAsyncCompletionService.recordAsyncObservability}.
 *
 * <h2>Why this test exists</h2>
 * <p>The async agent execution path (introduced by commit {@code 8f184ef81} for classify /
 * guardrail / agent when {@code scaling.agent.queue.enabled=true}) previously skipped the
 * observability recording call that {@link com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode}
 * makes on the inline path. Flipping the scaling default to {@code redis} in commit
 * {@code d0a24209d} meant every agent run stopped writing rows to the {@code agent_executions}
 * table - the frontend "Agent Performance" metrics dashboard went blank.</p>
 *
 * <p>This test exercises the new async-side recorder in isolation, verifying the builder
 * mirrors {@code AgentNode.buildMinimalObservabilityRequest} + the inline classify/guardrail
 * enrichment. Reflection is used because {@code recordAsyncObservability} is intentionally
 * private (its collaborators are already covered - this just wires the fields).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - recordAsyncObservability")
class AgentAsyncCompletionServiceObservabilityTest {

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private AgentClient agentClient;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            new NodeSearchService(),
            runRepository);
        // Field-injected in production via @Autowired(required=false); set directly here.
        var field = AgentAsyncCompletionService.class.getDeclaredField("agentClient");
        field.setAccessible(true);
        field.set(service, agentClient);
    }

    private void invokeRecordAsyncObservability(
            WorkflowExecution execution, PendingAgent pending,
            AgentResultMessage result, StepExecutionResult stepResult) throws Exception {
        Method m = AgentAsyncCompletionService.class.getDeclaredMethod(
            "recordAsyncObservability",
            WorkflowExecution.class, PendingAgent.class,
            AgentResultMessage.class, StepExecutionResult.class);
        m.setAccessible(true);
        m.invoke(service, execution, pending, result, stepResult);
    }

    private WorkflowExecution executionWith(Agent planAgent, String workflowId) {
        WorkflowExecution execution = mock(WorkflowExecution.class);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        lenient().when(execution.getPlan()).thenReturn(plan);
        lenient().when(plan.getId()).thenReturn(workflowId);
        if (planAgent != null) {
            lenient().when(plan.findAgent(planAgent.getNormalizedKey()))
                .thenReturn(Optional.of(planAgent));
        }
        return execution;
    }

    private Agent classifyPlanAgent() {
        return new Agent(
            null, "classify", "categorize_message", null, true,
            "openai", "gpt-4", "system", "prompt", 0.5, 2048, 10, 5,
            List.of(), null, Map.of(),
            List.of(Map.of("label", "a")), null,
            List.of(), null, null);
    }

    private Agent regularPlanAgent() {
        return new Agent(
            "agent_id", "agent", "writer", "00000000-0000-0000-0000-000000000001", true,
            "openai", "gpt-4", "sys", "prompt", 0.7, 4096, 10, 5,
            List.of(), null, Map.of(),
            List.of(), null,
            List.of(), null, null);
    }

    private PendingAgent pending(String nodeId, String agentType) {
        return pending(nodeId, agentType, null, null);
    }

    private PendingAgent pending(String nodeId, String agentType,
                                 String resolvedSystemPrompt, String resolvedUserPrompt) {
        return new PendingAgent(
            "corr-1",
            "00000000-0000-0000-0000-000000000099",
            nodeId,
            nodeId.substring(nodeId.indexOf(':') + 1),
            "trigger:default",
            2,
            0,
            "0",
            agentType,
            "tenant-1",
            null,
            null,
            null,
            null,
            null,
            null,
            resolvedSystemPrompt,
            resolvedUserPrompt,
            Instant.now().minusMillis(100));
    }

    @Test
    @DisplayName("classify: records row with workflow context + token fields from flat camelCase result")
    void classifyRecordsRow() throws Exception {
        Agent planAgent = classifyPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000010");
        PendingAgent p = pending("agent:categorize_message", "classify");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("tokensUsed", 120);
        resultMap.put("promptTokens", 80);
        resultMap.put("completionTokens", 40);
        resultMap.put("durationMs", 500L);
        resultMap.put("systemPrompt", "you are a classifier");
        resultMap.put("userPrompt", "## Content\nhello\n## Categories\n- a");
        // Production shape: ClassifyService maps AgentLoopResult.conversationHistory()
        // (which strips SYSTEM/USER via LoopExecutionState.markExecutionStart) so only
        // the assistant turn lands here. SYSTEM + USER are carried in the dedicated
        // systemPrompt / userPrompt flat fields.
        resultMap.put("conversationMessages", List.of(
            Map.of("role", "ASSISTANT", "content", "category=a")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "classify", Instant.now());

        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 500L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getTenantId()).isEqualTo("tenant-1");
        assertThat(req.getAgentType()).isEqualTo("classify");
        assertThat(req.getNodeId()).isEqualTo("agent:categorize_message");
        assertThat(req.getStatus()).isEqualTo("COMPLETED");
        assertThat(req.getErrorMessage()).isNull();
        assertThat(req.getRunId()).isEqualTo("00000000-0000-0000-0000-000000000099");
        assertThat(req.getEpoch()).isEqualTo(2);
        assertThat(req.getItemIndex()).isEqualTo(0);
        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-4");
        assertThat(req.getTemperature()).isEqualTo(0.5);
        assertThat(req.getMaxTokensConfig()).isEqualTo(2048);
        assertThat(req.getMaxIterationsConfig()).isEqualTo(10);
        assertThat(req.getMemoryEnabled()).isTrue();
        assertThat(req.getDurationMs()).isEqualTo(500L);
        assertThat(req.getTotalTokens()).isEqualTo(120L);
        assertThat(req.getPromptTokens()).isEqualTo(80L);
        assertThat(req.getCompletionTokens()).isEqualTo(40L);
        assertThat(req.getIterationCount()).isEqualTo(1);
        assertThat(req.getSystemPrompt()).isEqualTo("you are a classifier");
        assertThat(req.getMessages()).hasSize(3);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(0).getContent()).isEqualTo("you are a classifier");
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(1).getContent()).isEqualTo("## Content\nhello\n## Categories\n- a");
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(req.getMessages().get(2).getIterationNumber()).isEqualTo(1);
        assertThat(req.getWorkflowId()).isNotNull(); // parsed from plan id
        assertThat(req.getWorkflowRunId()).isNotNull(); // parsed from runId
    }

    @Test
    @DisplayName("classify: prepends SYSTEM + USER from worker DTO when conversationMessages only has ASSISTANT (regression for missing user message in async metric view)")
    void classifyPrependsSystemAndUserFromWorkerDto() throws Exception {
        // Pre-fix: AgentAsyncCompletionService read systemPrompt + conversationMessages
        // from the rawResult Map but ignored userPrompt entirely. Since the agent loop
        // strips SYSTEM/USER from conversationHistory (LoopExecutionState
        // .markExecutionStart sets executionStartIndex past them), the worker DTO's
        // conversationMessages was just [ASSISTANT] in production. Result: the metric
        // view rendered only the system prompt + assistant reply, with no visible
        // input - making it impossible to tell what was classified.
        // Post-fix: SYSTEM + USER are prepended from the worker DTO's flat
        // systemPrompt + userPrompt fields, then the assistant turn is appended.
        Agent planAgent = classifyPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000024");
        PendingAgent p = pending("agent:categorize_message", "classify");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("tokensUsed", 90);
        resultMap.put("durationMs", 200L);
        resultMap.put("systemPrompt",
            "You are a classification assistant. Your ONLY task is to categorize content.");
        resultMap.put("userPrompt",
            "## Classification Instruction\nClassify the email\n\n## Available Categories\n- Promotions: marketing\n- Updates: notifications\n");
        resultMap.put("conversationMessages", List.of(
            Map.of("role", "ASSISTANT",
                "content", "{\"selected_category\":\"Promotions\",\"confidence\":0.85}")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "classify", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 200L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getMessages()).hasSize(3);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(0).getContent())
            .isEqualTo("You are a classification assistant. Your ONLY task is to categorize content.");
        // The USER message - this is the field the bug was hiding.
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(1).getContent())
            .contains("Classification Instruction")
            .contains("Promotions");
        assertThat(req.getMessages().get(1).getIterationNumber()).isEqualTo(1);
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(req.getMessages().get(2).getContent()).contains("Promotions");
    }

    @Test
    @DisplayName("guardrail: prepends SYSTEM + USER from worker DTO (regression - same bug as classify)")
    void guardrailPrependsSystemAndUserFromWorkerDto() throws Exception {
        // Same regression as classifyPrependsSystemAndUserFromWorkerDto, scoped to the
        // guardrail branch since it shares the same case-arm in recordAsyncObservability.
        Agent planAgent = new Agent(
            null, "guardrail", "check_safety", null, true,
            "openai", "gpt-4", null, "prompt", 0.7, 1024, 10, 5,
            List.of(), null, Map.of(),
            List.of(), null,
            List.of(Map.of("id", "rule-1", "description", "no pii")), null, null);
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000025");
        PendingAgent p = pending("agent:check_safety", "guardrail");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("passed", true);
        resultMap.put("tokensUsed", 60);
        resultMap.put("durationMs", 150L);
        resultMap.put("systemPrompt", "You are a content safety guard.");
        resultMap.put("userPrompt",
            "## Content\nuser-supplied text\n## Rules\n- no pii: no personal info\n");
        resultMap.put("conversationMessages", List.of(
            Map.of("role", "ASSISTANT", "content", "{\"passed\":true,\"violations\":[]}")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "guardrail", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 150L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getMessages()).hasSize(3);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(1).getContent())
            .contains("user-supplied text")
            .contains("no pii");
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("classify: worker echoes SYSTEM/USER → deduped (defensive, not current prod shape)")
    void classifyDedupesEchoedSystemUserFromHistory() throws Exception {
        // Defensive: if a future worker change starts echoing SYSTEM/USER turns into
        // conversationMessages, we must not render them twice. The async path skips
        // SYSTEM/USER roles in the appended history when the flat prompt fields are
        // present (mirrors enrichAgentShape's dedupe at lines 1737-1739).
        Agent planAgent = classifyPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000026");
        PendingAgent p = pending("agent:categorize_message", "classify");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("tokensUsed", 50);
        resultMap.put("durationMs", 100L);
        resultMap.put("systemPrompt", "you are a classifier");
        resultMap.put("userPrompt", "classify hello");
        resultMap.put("conversationMessages", List.of(
            Map.of("role", "SYSTEM", "content", "you are a classifier"),  // echoed
            Map.of("role", "USER", "content", "classify hello"),           // echoed
            Map.of("role", "ASSISTANT", "content", "{\"selected_category\":\"a\"}")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "classify", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 100L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        // Exactly 3 messages - no duplicate SYSTEM / USER from echoed history.
        assertThat(req.getMessages()).hasSize(3);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("guardrail: records row with passed=false + error message")
    void guardrailRecordsFailure() throws Exception {
        Agent planAgent = new Agent(
            null, "guardrail", "check_safety", null, true,
            "openai", "gpt-4", null, "prompt", 0.7, 1024, 10, 5,
            List.of(), null, Map.of(),
            List.of(), null,
            List.of(Map.of("id", "rule-1", "description", "no pii")), null, null);
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000011");
        PendingAgent p = pending("agent:check_safety", "guardrail");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("passed", false);
        resultMap.put("tokensUsed", 50);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, false, "blocked by rule", "guardrail", Instant.now());

        StepExecutionResult stepResult = StepExecutionResult.failureWithOutput(
            p.nodeId(), "blocked by rule", resultMap, 300L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getStatus()).isEqualTo("FAILED");
        assertThat(req.getErrorMessage()).isEqualTo("blocked by rule");
        assertThat(req.getAgentType()).isEqualTo("guardrail");
        assertThat(req.getTotalTokens()).isEqualTo(50L);
        assertThat(req.getDurationMs()).isEqualTo(300L);
        assertThat(req.getIterationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("agent: full shape - totalUsage + metrics + toolCalls + messages + iterations")
    void agentRecordsFullShape() throws Exception {
        Agent planAgent = regularPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000012");
        // Snapshot the resolved SYSTEM + USER prompts at enqueue - matches what
        // AgentNode.executeAgentAsyncQueue stores on PendingAgent in production.
        // Worker history echoes its own SYSTEM/USER turns; the new enrichAgentShape
        // contract dedupes those (skip echoed SYSTEM/USER) and trusts the snapshot.
        PendingAgent p = pending("agent:writer", "agent",
            "you are an agent (resolved with vars)",
            "do something (resolved)");

        Map<String, Object> usage = new HashMap<>();
        usage.put("promptTokens", 200);
        usage.put("completionTokens", 100);
        // Worker-reported totalTokens should take precedence (UsageInfo.getTotal()
        // prefers the persisted total over prompt+completion).
        usage.put("totalTokens", 310);
        usage.put("cacheReadInputTokens", 10);
        usage.put("cacheCreationInputTokens", 5);

        // metrics carries budget scope + loop detection + per-iteration tool counts
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("budgetScope", "agent");
        metrics.put("loopDetected", true);
        metrics.put("loopType", "repeat");
        metrics.put("loopToolName", "web_search");
        metrics.put("toolCallsPerIteration", List.of(1, 1, 0));

        // Two tool results - the Jackson-serialized shape nests `toolCall`
        Map<String, Object> toolCall1 = new HashMap<>();
        toolCall1.put("id", "call_1");
        toolCall1.put("toolName", "web_search");
        toolCall1.put("arguments", Map.of("query", "hello"));
        toolCall1.put("index", 0);
        Map<String, Object> toolResult1 = new HashMap<>();
        toolResult1.put("toolCall", toolCall1);
        toolResult1.put("success", true);
        toolResult1.put("content", "[result]");
        toolResult1.put("durationMs", 120);

        Map<String, Object> toolCall2 = new HashMap<>();
        toolCall2.put("id", "call_2");
        toolCall2.put("toolName", "web_search"); // same tool → uniqueToolCount=1
        toolCall2.put("arguments", Map.of("query", "world"));
        toolCall2.put("index", 0);
        Map<String, Object> toolResult2 = new HashMap<>();
        toolResult2.put("toolCall", toolCall2);
        toolResult2.put("success", false);
        toolResult2.put("content", "oops");
        toolResult2.put("error", "timeout");
        toolResult2.put("durationMs", 50);

        // Conversation history - matches Jackson-serialized Message record
        List<Map<String, Object>> history = List.of(
            Map.of("role", "SYSTEM", "content", "you are an agent"),
            Map.of("role", "USER", "content", "do something"),
            Map.of("role", "ASSISTANT", "content", "ok, searching"),
            Map.of("role", "TOOL", "content", "[result]", "toolCallId", "call_1", "toolName", "web_search"),
            Map.of("role", "ASSISTANT", "content", "done")
        );

        // usagePerIteration with parallel durations + finish reasons
        Map<String, Object> u0 = new HashMap<>();
        u0.put("promptTokens", 100);
        u0.put("completionTokens", 40);
        Map<String, Object> u1 = new HashMap<>();
        u1.put("promptTokens", 60);
        u1.put("completionTokens", 30);
        Map<String, Object> u2 = new HashMap<>();
        u2.put("promptTokens", 40);
        u2.put("completionTokens", 30);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("totalUsage", usage);
        resultMap.put("iterations", 3);
        resultMap.put("stopReason", "LOOP_COMPLETED");
        resultMap.put("toolResults", List.of(toolResult1, toolResult2));
        resultMap.put("metrics", metrics);
        resultMap.put("conversationHistory", history);
        resultMap.put("usagePerIteration", List.of(u0, u1, u2));
        resultMap.put("iterationDurations", List.of(400L, 300L, 200L));
        resultMap.put("finishReasonsPerIteration", List.of("tool_use", "tool_use", "end_turn"));
        resultMap.put("durationMs", 1500L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());

        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 1500L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        // Core fields
        assertThat(req.getAgentType()).isEqualTo("agent");
        assertThat(req.getStatus()).isEqualTo("COMPLETED");
        assertThat(req.getIterationCount()).isEqualTo(3);
        assertThat(req.getStopReason()).isEqualTo("LOOP_COMPLETED");
        assertThat(req.getTotalToolCalls()).isEqualTo(2);
        assertThat(req.getUniqueToolCount()).isEqualTo(1); // both calls are web_search
        assertThat(req.getDurationMs()).isEqualTo(1500L);
        assertThat(req.getAgentEntityId()).isNotNull();

        // Token precedence - worker-reported totalTokens wins over prompt+completion
        assertThat(req.getPromptTokens()).isEqualTo(200L);
        assertThat(req.getCompletionTokens()).isEqualTo(100L);
        assertThat(req.getTotalTokens()).isEqualTo(310L); // NOT 300
        assertThat(req.getCacheReadTokens()).isEqualTo(10L);
        assertThat(req.getCacheCreationTokens()).isEqualTo(5L);

        // Metrics-derived fields
        assertThat(req.getBudgetScope()).isEqualTo("agent");
        assertThat(req.isLoopDetected()).isTrue();
        assertThat(req.getLoopType()).isEqualTo("repeat");
        assertThat(req.getLoopToolName()).isEqualTo("web_search");

        // systemPrompt - comes from the PendingAgent snapshot (resolved, with the
        // modular prefix and variables substituted), not from the raw plan template.
        assertThat(req.getSystemPrompt()).isEqualTo("you are an agent (resolved with vars)");
        assertThat(req.getMemoryEnabled()).isTrue();

        // Messages - SYSTEM + USER come from the snapshot, then worker's
        // ASSISTANT/TOOL/ASSISTANT (worker's own SYSTEM/USER are deduped).
        // Iteration numbering increments on each ASSISTANT role.
        assertThat(req.getMessages()).hasSize(5);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(0).getContent()).isEqualTo("you are an agent (resolved with vars)");
        assertThat(req.getMessages().get(0).getIterationNumber()).isNull();
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(1).getContent()).isEqualTo("do something (resolved)");
        assertThat(req.getMessages().get(1).getIterationNumber()).isEqualTo(0);
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(req.getMessages().get(2).getIterationNumber()).isEqualTo(1);
        assertThat(req.getMessages().get(3).getRole()).isEqualTo("TOOL");
        assertThat(req.getMessages().get(3).getIterationNumber()).isEqualTo(1);
        assertThat(req.getMessages().get(3).getToolCallId()).isEqualTo("call_1");
        assertThat(req.getMessages().get(3).getToolName()).isEqualTo("web_search");
        assertThat(req.getMessages().get(4).getRole()).isEqualTo("ASSISTANT");
        assertThat(req.getMessages().get(4).getIterationNumber()).isEqualTo(2);

        // ToolCalls - per-call detail
        assertThat(req.getToolCalls()).hasSize(2);
        assertThat(req.getToolCalls().get(0).getToolCallId()).isEqualTo("call_1");
        assertThat(req.getToolCalls().get(0).getToolName()).isEqualTo("web_search");
        assertThat(req.getToolCalls().get(0).getArguments()).containsEntry("query", "hello");
        assertThat(req.getToolCalls().get(0).getParallelIndex()).isEqualTo(0);
        assertThat(req.getToolCalls().get(0).isSuccess()).isTrue();
        assertThat(req.getToolCalls().get(0).getResult()).isEqualTo("[result]");
        assertThat(req.getToolCalls().get(0).getDurationMs()).isEqualTo(120L);
        assertThat(req.getToolCalls().get(1).isSuccess()).isFalse();
        assertThat(req.getToolCalls().get(1).getErrorMessage()).isEqualTo("timeout");
        assertThat(req.getToolCalls().get(1).getResult()).isEqualTo("oops");

        // Iterations list - tokens + duration + finishReason + toolCallCount
        assertThat(req.getIterations()).hasSize(3);
        assertThat(req.getIterations().get(0).getIterationNumber()).isEqualTo(0);
        assertThat(req.getIterations().get(0).getPromptTokens()).isEqualTo(100L);
        assertThat(req.getIterations().get(0).getCompletionTokens()).isEqualTo(40L);
        assertThat(req.getIterations().get(0).getDurationMs()).isEqualTo(400L);
        assertThat(req.getIterations().get(0).getFinishReason()).isEqualTo("tool_use");
        assertThat(req.getIterations().get(0).getToolCallCount()).isEqualTo(1);
        assertThat(req.getIterations().get(2).getIterationNumber()).isEqualTo(2);
        assertThat(req.getIterations().get(2).getFinishReason()).isEqualTo("end_turn");
        assertThat(req.getIterations().get(2).getToolCallCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("agent: totalTokens absent → falls back to prompt+completion sum")
    void agentFallsBackToSumWhenTotalTokensAbsent() throws Exception {
        Agent planAgent = regularPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000016");
        PendingAgent p = pending("agent:writer", "agent");

        Map<String, Object> usage = new HashMap<>();
        usage.put("promptTokens", 150);
        usage.put("completionTokens", 75);
        // no totalTokens - must compute 225

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("totalUsage", usage);
        resultMap.put("iterations", 1);
        resultMap.put("durationMs", 600L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 600L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getPromptTokens()).isEqualTo(150L);
        assertThat(req.getCompletionTokens()).isEqualTo(75L);
        assertThat(req.getTotalTokens()).isEqualTo(225L);
    }

    @Test
    @DisplayName("agent: PendingAgent snapshot drives SYSTEM + USER prepend (regression for missing prompts in async metric view)")
    void agentPrependsSystemAndUserFromSnapshot() throws Exception {
        // Pre-fix: enrichAgentShape used agentConfig.systemPrompt() (raw template,
        // no variable substitution, no modular prefix) and never built a USER message.
        // Result: the Agent Performance metric view rendered only the assistant reply
        // for async-executed agents - no system prompt, no user message - diverging
        // from chat / sync / sub-agent paths.
        // Post-fix: the resolved values are snapshotted on PendingAgent at enqueue
        // and prepended as SYSTEM + USER messages before any worker history.
        Agent planAgent = regularPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000017");
        PendingAgent p = pending("agent:writer", "agent",
            "RESOLVED: you are an agent for tenant tenant-1",
            "RESOLVED: classify message #42");

        // Worker only echoes the assistant reply - no SYSTEM/USER turns in history.
        // This is the production shape: the agent loop's "current execution messages"
        // strips the system + user prompts, so the metric view depended on the snapshot.
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("iterations", 1);
        resultMap.put("conversationHistory", List.of(
            Map.of("role", "ASSISTANT", "content", "Done.")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 100L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        // The header column shows the snapshot, not the raw plan template ("sys").
        assertThat(req.getSystemPrompt()).isEqualTo("RESOLVED: you are an agent for tenant tenant-1");

        // Conversation tab: SYSTEM + USER + ASSISTANT in that order.
        assertThat(req.getMessages()).hasSize(3);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(0).getContent())
            .isEqualTo("RESOLVED: you are an agent for tenant tenant-1");
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("USER");
        assertThat(req.getMessages().get(1).getContent()).isEqualTo("RESOLVED: classify message #42");
        assertThat(req.getMessages().get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(req.getMessages().get(2).getContent()).isEqualTo("Done.");
    }

    @Test
    @DisplayName("agent: snapshot absent → falls back to raw plan systemPrompt and skips USER (legacy PendingAgent compat)")
    void agentFallsBackToPlanWhenSnapshotMissing() throws Exception {
        // Crash-recovered PendingAgent rows persisted before the snapshot field was
        // added carry null resolvedSystemPrompt / resolvedUserPrompt. The async
        // recorder must not blow up and should fall back to the raw plan template
        // (matches pre-fix behavior for legacy rows). USER is omitted - better than
        // fabricating a placeholder.
        Agent planAgent = regularPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000018");
        PendingAgent p = pending("agent:writer", "agent"); // both snapshots null

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("iterations", 1);
        resultMap.put("conversationHistory", List.of(
            Map.of("role", "ASSISTANT", "content", "Hello.")
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 100L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getSystemPrompt()).isEqualTo("sys"); // plan template fallback
        // Messages: SYSTEM (from plan fallback) + ASSISTANT. No USER row fabricated.
        assertThat(req.getMessages()).hasSize(2);
        assertThat(req.getMessages().get(0).getRole()).isEqualTo("SYSTEM");
        assertThat(req.getMessages().get(0).getContent()).isEqualTo("sys");
        assertThat(req.getMessages().get(1).getRole()).isEqualTo("ASSISTANT");
    }

    @Test
    @DisplayName("null agentClient is a no-op (test-fixture safety)")
    void nullClientIsNoOp() throws Exception {
        var field = AgentAsyncCompletionService.class.getDeclaredField("agentClient");
        field.setAccessible(true);
        field.set(service, null);

        Agent planAgent = classifyPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000013");
        PendingAgent p = pending("agent:categorize_message", "classify");
        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), new HashMap<>(), true, null, "classify", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), new HashMap<>(), 0L);

        // Should not throw - no client to call.
        invokeRecordAsyncObservability(exec, p, msg, stepResult);
        verify(agentClient, never()).recordObservability(any());
    }

    @Test
    @DisplayName("recordObservability throwing does not propagate (best-effort contract)")
    void swallowsClientFailure() throws Exception {
        doThrow(new RuntimeException("network down")).when(agentClient).recordObservability(any());

        Agent planAgent = classifyPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000014");
        PendingAgent p = pending("agent:categorize_message", "classify");
        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), new HashMap<>(), true, null, "classify", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), new HashMap<>(), 0L);

        // Must not throw - observability is fire-and-forget at this layer.
        invokeRecordAsyncObservability(exec, p, msg, stepResult);
        verify(agentClient).recordObservability(any());
    }

    @Test
    @DisplayName("agent: linkage failure during observability enrichment does not propagate")
    void swallowsLinkageFailureDuringAgentEnrichment() {
        Agent planAgent = regularPlanAgent();
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000027");
        PendingAgent p = pending("agent:writer", "agent",
            "resolved system prompt",
            "resolved user prompt");

        Object brokenContent = new Object() {
            @Override
            public String toString() {
                throw new NoClassDefFoundError(
                    "com/apimarketplace/agent/client/dto/AgentObservabilityRequest$MessageData");
            }
        };

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("iterations", 1);
        resultMap.put("conversationHistory", List.of(
            Map.of("role", "ASSISTANT", "content", brokenContent)
        ));

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 100L);

        assertDoesNotThrow(() -> invokeRecordAsyncObservability(exec, p, msg, stepResult));
        verify(agentClient, never()).recordObservability(any());
    }

    @Test
    @DisplayName("provider/model: runtime worker values override the static plan when present")
    void runtimeProviderModelOverrideStaticPlan() throws Exception {
        // Regression for prod NULL provider/model: when the plan stores no provider
        // but the worker resolves one at runtime (e.g. routing/defaulting layer),
        // the persisted row must record the runtime values, not the plan's nulls.
        Agent planAgentWithoutProvider = new Agent(
            "agent_id", "agent", "smart_assistant", "00000000-0000-0000-0000-000000000001", true,
            null, null, "sys", "prompt", 0.7, 4096, 10, 5,
            List.of(), null, Map.of(),
            List.of(), null,
            List.of(), null, null);
        WorkflowExecution exec = executionWith(planAgentWithoutProvider, "00000000-0000-0000-0000-000000000020");
        PendingAgent p = pending("agent:smart_assistant", "agent");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("provider", "google");
        resultMap.put("model", "gemini-3-flash-preview");
        resultMap.put("iterations", 1);
        resultMap.put("durationMs", 500L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 500L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getProvider()).isEqualTo("google");
        assertThat(req.getModel()).isEqualTo("gemini-3-flash-preview");
    }

    @Test
    @DisplayName("provider/model: classify runtime override also wins over plan")
    void classifyRuntimeProviderModelOverrideStaticPlan() throws Exception {
        // The runtime fallback applies to all three async types (agent/classify/guardrail)
        // since the resolution happens before the type-switch in recordAsyncObservability.
        // This guards the classify shape which has its own DTO (ClassifyResponseDto) but
        // exposes the same provider/model field names at the top level.
        Agent classifyWithoutProvider = new Agent(
            null, "classify", "categorize_message", null, true,
            null, null, "system", "prompt", 0.5, 2048, 10, 5,
            List.of(), null, Map.of(),
            List.of(Map.of("label", "a")), null,
            List.of(), null, null);
        WorkflowExecution exec = executionWith(classifyWithoutProvider, "00000000-0000-0000-0000-000000000023");
        PendingAgent p = pending("agent:categorize_message", "classify");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("provider", "openai");
        resultMap.put("model", "gpt-4o-mini");
        resultMap.put("tokensUsed", 50);
        resultMap.put("durationMs", 200L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "classify", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 200L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("provider/model: falls back to plan when worker omits them")
    void planProviderModelUsedWhenRuntimeAbsent() throws Exception {
        Agent planAgent = regularPlanAgent(); // openai / gpt-4
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000021");
        PendingAgent p = pending("agent:writer", "agent");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        // no provider/model in worker result
        resultMap.put("iterations", 1);
        resultMap.put("durationMs", 500L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 500L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("provider/model: blank runtime values fall through to plan")
    void blankRuntimeProviderFallsThroughToPlan() throws Exception {
        Agent planAgent = regularPlanAgent(); // openai / gpt-4
        WorkflowExecution exec = executionWith(planAgent, "00000000-0000-0000-0000-000000000022");
        PendingAgent p = pending("agent:writer", "agent");

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", true);
        resultMap.put("provider", "");
        resultMap.put("model", "   ");
        resultMap.put("iterations", 1);
        resultMap.put("durationMs", 500L);

        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), resultMap, true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), resultMap, 500L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        AgentObservabilityRequest req = captor.getValue();

        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("plan without matching agent: skipped (no row written)")
    void skipsWhenAgentNotInPlan() throws Exception {
        WorkflowExecution exec = executionWith(null, "00000000-0000-0000-0000-000000000015");
        // No plan agent at all: findAgent returns Optional.empty()
        when(exec.getPlan().findAgent(any())).thenReturn(Optional.empty());
        PendingAgent p = pending("agent:ghost", "agent");
        AgentResultMessage msg = new AgentResultMessage(
            "corr-1", p.runId(), p.nodeId(), new HashMap<>(), true, null, "agent", Instant.now());
        StepExecutionResult stepResult = StepExecutionResult.success(p.nodeId(), new HashMap<>(), 0L);

        invokeRecordAsyncObservability(exec, p, msg, stepResult);
        verify(agentClient, never()).recordObservability(any());
    }
}
