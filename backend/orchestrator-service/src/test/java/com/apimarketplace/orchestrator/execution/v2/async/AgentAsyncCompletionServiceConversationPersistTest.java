package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager;
import com.apimarketplace.orchestrator.services.agent.AgentExecutionResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression tests for the async-mode conversation persistence hook on the delivery
 * side. Bug context: when {@code scaling.agent.queue.enabled=true}, agent executions
 * went through {@code executeAgentAsyncQueue} which yields before reaching the
 * synchronous {@code conversationManager.saveAssistantResponse} call in
 * {@code AgentNode.executeAgent}. The agent's conversation row therefore stayed empty
 * no matter how many times the workflow fired the agent (observed in prod for the
 * "Smart Assistant" agent - 10 successful executions, 0 messages persisted).
 *
 * <p>The fix snapshots {@code conversationId}/{@code streamId}/{@code executionId}/
 * {@code model} on {@link PendingAgent} at enqueue time and replays them at delivery
 * via {@code persistConversationOnDelivery}. These tests fail on the pre-fix code
 * (no call) and pass on the post-fix code (one call per delivered agent result).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - conversation persistence on delivery")
class AgentAsyncCompletionServiceConversationPersistTest {

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private AgentConversationManager conversationManager;

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
        var field = AgentAsyncCompletionService.class.getDeclaredField("conversationManager");
        field.setAccessible(true);
        field.set(service, conversationManager);
    }

    private void invoke(PendingAgent pending, AgentResultMessage result) throws Exception {
        Method m = AgentAsyncCompletionService.class.getDeclaredMethod(
            "persistConversationOnDelivery", PendingAgent.class, AgentResultMessage.class);
        m.setAccessible(true);
        m.invoke(service, pending, result);
    }

    private PendingAgent pendingWithConv(String conversationId, String streamId, String executionId) {
        return new PendingAgent(
            "corr-1", "run-1", "agent:smart_assistant", "smart_assistant",
            "trigger:user_question", 1, 0, "0", "agent", "tenant-1",
            null, null,
            conversationId, streamId, executionId, "gemini-3-flash-preview",
            null, null,
            Instant.now());
    }

    private Map<String, Object> agentResultPayload(String content) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("content", content);
        raw.put("provider", "google");
        raw.put("model", "gemini-3-flash-preview");
        return raw;
    }

    @Test
    @DisplayName("Regression - saves the assistant response and completes the stream when conversation is bound")
    void savesAssistantAndCompletesStream() throws Exception {
        PendingAgent pending = pendingWithConv("conv-7730cebb", "stream-abc", "exec-99");
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            agentResultPayload("La capitale de la France est Paris."),
            true, null, "agent", Instant.now());

        invoke(pending, result);

        ArgumentCaptor<AgentExecutionResult> savedCaptor = ArgumentCaptor.forClass(AgentExecutionResult.class);
        verify(conversationManager).saveAssistantResponse(
            eq("conv-7730cebb"), eq("tenant-1"), savedCaptor.capture(), eq("exec-99"));
        assertThat(savedCaptor.getValue().getContent()).isEqualTo("La capitale de la France est Paris.");
        assertThat(savedCaptor.getValue().isSuccess()).isTrue();

        // completeStream is invoked with the same StreamSession (conversationId, streamId)
        // so the panel emits stream_completed and stops the typing indicator.
        verify(conversationManager).completeStream(
            any(AgentConversationManager.StreamSession.class), any(AgentExecutionResult.class));
    }

    @Test
    @DisplayName("Regression - on failure result, completeStream still fires (with error path)")
    void completesStreamOnFailureToo() throws Exception {
        PendingAgent pending = pendingWithConv("conv-x", "stream-y", "exec-z");
        AgentResultMessage failure = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            new HashMap<>(), false, "LLM provider unreachable", "agent", Instant.now());

        invoke(pending, failure);

        ArgumentCaptor<AgentExecutionResult> savedCaptor = ArgumentCaptor.forClass(AgentExecutionResult.class);
        verify(conversationManager).saveAssistantResponse(
            eq("conv-x"), eq("tenant-1"), savedCaptor.capture(), eq("exec-z"));
        assertThat(savedCaptor.getValue().isSuccess()).isFalse();
        assertThat(savedCaptor.getValue().getError()).isEqualTo("LLM provider unreachable");

        verify(conversationManager).completeStream(
            any(AgentConversationManager.StreamSession.class), any(AgentExecutionResult.class));
    }

    @Test
    @DisplayName("Skips persistence entirely when no conversation was bound (classify/guardrail path)")
    void skipsWhenNoConversationBound() throws Exception {
        PendingAgent pending = new PendingAgent(
            "corr-1", "run-1", "agent:classify", "classify", "trigger:cron", 0, 0, "0", "classify",
            "tenant-1", null, null,
            null, null, null, null, // no conversation
            null, null,
            Instant.now());
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:classify",
            Map.of("selectedCategory", "tech"), true, null, "classify", Instant.now());

        invoke(pending, result);

        verifyNoInteractions(conversationManager);
    }

    @Test
    @DisplayName("Skips completeStream when streamId is null but still saves the message")
    void savesEvenWithoutStream() throws Exception {
        PendingAgent pending = pendingWithConv("conv-x", null, "exec-z"); // no streamId
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            agentResultPayload("ok"), true, null, "agent", Instant.now());

        invoke(pending, result);

        verify(conversationManager).saveAssistantResponse(
            eq("conv-x"), eq("tenant-1"), any(AgentExecutionResult.class), eq("exec-z"));
        verify(conversationManager, never()).completeStream(any(), any());
    }

    @Test
    @DisplayName("Maps tool results from raw worker payload - toolCall + content + duration land on AgentExecutionResult")
    void mapsToolResultsFromRawPayload() throws Exception {
        PendingAgent pending = pendingWithConv("conv-x", "stream-y", "exec-z");

        Map<String, Object> tcRaw = new HashMap<>();
        tcRaw.put("id", "tool-call-1");
        tcRaw.put("toolName", "web_search");
        tcRaw.put("arguments", Map.of("query", "Paris"));

        Map<String, Object> trRaw = new HashMap<>();
        trRaw.put("toolCall", tcRaw);
        trRaw.put("success", true);
        trRaw.put("content", "Paris is the capital of France");
        trRaw.put("durationMs", 1200);

        Map<String, Object> raw = agentResultPayload("Done");
        raw.put("toolResults", List.of(trRaw));

        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant", raw, true, null, "agent", Instant.now());

        invoke(pending, result);

        ArgumentCaptor<AgentExecutionResult> captor = ArgumentCaptor.forClass(AgentExecutionResult.class);
        verify(conversationManager).saveAssistantResponse(
            eq("conv-x"), eq("tenant-1"), captor.capture(), eq("exec-z"));
        var toolResults = captor.getValue().getToolResults();
        assertThat(toolResults).hasSize(1);
        assertThat(toolResults.get(0).toolCall().id()).isEqualTo("tool-call-1");
        assertThat(toolResults.get(0).toolCall().toolName()).isEqualTo("web_search");
        assertThat(toolResults.get(0).success()).isTrue();
        assertThat(toolResults.get(0).content()).isEqualTo("Paris is the capital of France");
        assertThat(toolResults.get(0).durationMs()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("Best-effort - a save failure is swallowed so successor traversal never blocks on conversation-service outage")
    void swallowsSaveFailures() throws Exception {
        PendingAgent pending = pendingWithConv("conv-x", "stream-y", "exec-z");
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            agentResultPayload("hello"), true, null, "agent", Instant.now());
        doThrow(new RuntimeException("conversation-service down"))
            .when(conversationManager).saveAssistantResponse(any(), any(), any(), any());

        // Must not throw - best-effort contract matches the inline path.
        invoke(pending, result);
    }

    @Test
    @DisplayName("Wiring guard - onAgentResult delivery actually invokes persistConversationOnDelivery (not just the helper in isolation)")
    void deliverUnderLockWiresConversationPersist() throws Exception {
        // This test catches the prod regression class: removing the call site in
        // deliverUnderLock would leave all helper-level tests passing while
        // reintroducing the empty-conversation bug. We force the full delivery
        // pipeline via onAgentResult and assert saveAssistantResponse was called.
        // Conversation save runs BEFORE rebuildExecution in the post-fix code, so
        // we don't need workflowResumeService wired - the rebuild will NPE/fail
        // and re-registration will fire AFTER the message has landed.
        PendingAgent pending = pendingWithConv("conv-int", "stream-int", "exec-int");
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            agentResultPayload("integration body"), true, null, "agent", Instant.now());
        org.mockito.Mockito.when(registry.consume("corr-1"))
            .thenReturn(java.util.Optional.of(pending));

        service.onAgentResult(result);

        verify(conversationManager).saveAssistantResponse(
            eq("conv-int"), eq("tenant-1"), any(AgentExecutionResult.class), eq("exec-int"));
    }

    @Test
    @DisplayName("Idempotency - a second onAgentResult for the same correlationId does NOT double-save (registry consumes the entry)")
    void doesNotDoubleSaveOnRedelivery() throws Exception {
        PendingAgent pending = pendingWithConv("conv-id", "stream-id", "exec-id");
        AgentResultMessage result = new AgentResultMessage(
            "corr-1", "run-1", "agent:smart_assistant",
            agentResultPayload("once"), true, null, "agent", Instant.now());
        // First delivery: registry returns the pending entry.
        // Second delivery: registry returns empty (consumed) - happy-path single-save guarantee.
        org.mockito.Mockito.when(registry.consume("corr-1"))
            .thenReturn(java.util.Optional.of(pending))
            .thenReturn(java.util.Optional.empty());

        service.onAgentResult(result);
        service.onAgentResult(result);

        // Exactly one save - the second delivery short-circuits before reaching
        // persistConversationOnDelivery because registry.consume returned empty.
        verify(conversationManager, times(1))
            .saveAssistantResponse(any(), any(), any(), any());
    }
}
