package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Regression test for the prod fire on 2026-05-20 16:56 UTC: the Redis listener
 * thread ({@code AgentResultSubscriber.onMessage} → {@code AgentAsyncCompletionService.onAgentResult})
 * had no {@link TenantResolver} binding, so every downstream {@code OrgScopedEntity}
 * persist (storage rows, step_data, etc.) tripped the @PrePersist fail-loud listener
 * post-V263 and silently dropped {@code output_storage_id} on 224/270 epoch-13 rows.
 * The downstream {@code {{...output.text}}} templates resolved to {@code null} →
 * Telegram API received {@code {"text": null}}.
 *
 * <p>The fix wraps the delivery pipeline in {@code TenantResolver.runWithOrgScope(
 * pending.organizationId(), ...)}. {@code PendingAgent} captures the orgId at
 * {@code AgentNode}-yield time (and the Redis hydrate path
 * {@link RedisPendingAgentStore} round-trips it via toMap/fromMap), so the orgId
 * is available even on recovery-scanner re-deliveries.
 *
 * <p>This test pins the contract: when {@code onAgentResult} runs on a thread
 * with NO existing TenantResolver binding (the prod failure mode), the first
 * collaborator inside {@code deliverUnderLock} MUST see
 * {@code pending.organizationId()} as the current request org. After the call
 * returns (success or failure), the ThreadLocal MUST be restored to its prior
 * state (null in this scenario) so the listener thread doesn't leak org context
 * into the next message.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentAsyncCompletionService - onAgentResult binds pending.organizationId on the listener thread")
class AgentAsyncCompletionOrgScopeBindingTest {

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private AgentClient agentClient;
    @Mock private WorkflowRunRepository runRepository;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry,
            stepCompletionOrchestrator,
            splitContextManager,
            runningNodeTracker,
            splitCoalesceTracker,
            new NodeSearchService(),
            runRepository);
    }

    @Test
    @DisplayName("binds pending.organizationId on the listener thread before deliverUnderLock and unbinds after (prod fire 2026-05-20 regression)")
    void organizationIdBoundDuringDeliveryAndRestoredAfter() {
        String correlationId = "corr-prod-fire-2026-05-20";
        String runId = "run_test_v264_orgwrap";
        String expectedOrg = "00000000-0000-0000-0000-000000000000";

        PendingAgent pending = makePending(correlationId, runId, expectedOrg);
        when(registry.consume(correlationId)).thenReturn(Optional.of(pending));

        // Capture the ThreadLocal-bound org from inside the delivery pipeline.
        // {@code workflowResumeService} is not injected here, so {@code rebuildExecution}
        // throws → null bubbles up → {@code deliverUnderLock} enters the
        // "could not rebuild execution" branch which calls {@code registry.register(pending)}.
        // The wrap MUST still be active at that point (it's inside the synchronized block),
        // so capturing from the re-register stub proves the org was bound for the entire
        // delivery pipeline.
        AtomicReference<String> orgInsidePipeline = new AtomicReference<>();
        doAnswer(inv -> {
            orgInsidePipeline.set(TenantResolver.currentRequestOrganizationId());
            return null;
        }).when(registry).register(any(PendingAgent.class));

        // Pre-condition: listener thread has NO org bound (matches prod redisMessageListenerContainer-N).
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();

        AgentResultMessage result = new AgentResultMessage(
            correlationId, runId, "agent:classify",
            Map.of(), true, null, "classify", Instant.now());
        boolean delivered = service.onAgentResult(result);

        // The transient DB-miss path returns false (and registry re-registers for retry).
        assertThat(delivered).isFalse();

        // Contract #1: during the delivery pipeline, the org was the PendingAgent's org.
        assertThat(orgInsidePipeline.get())
            .as("organizationId must be bound on the listener thread before any downstream persist")
            .isEqualTo(expectedOrg);

        // Contract #2: after delivery completes, the ThreadLocal is unbound (no leak across messages).
        assertThat(TenantResolver.currentRequestOrganizationId())
            .as("ThreadLocal must be restored after onAgentResult returns")
            .isNull();
    }

    @Test
    @DisplayName("preserves prior org binding when wrapper executes nested (defense in depth)")
    void priorOrgBindingRestoredAfterCall() {
        String correlationId = "corr-nested";
        String runId = "run_test_nested";
        String pendingOrg = "11111111-1111-1111-1111-111111111111";
        String outerOrg   = "22222222-2222-2222-2222-222222222222";

        PendingAgent pending = makePending(correlationId, runId, pendingOrg);
        when(registry.consume(correlationId)).thenReturn(Optional.of(pending));

        AgentResultMessage result = new AgentResultMessage(
            correlationId, runId, "agent:classify",
            Map.of(), true, null, "classify", Instant.now());

        TenantResolver.runWithOrgScope(outerOrg, () -> {
            assertThat(TenantResolver.currentRequestOrganizationId()).isEqualTo(outerOrg);
            service.onAgentResult(result);
            // After the inner runWithOrgScope returns, the outer binding must be restored.
            assertThat(TenantResolver.currentRequestOrganizationId())
                .as("outer org scope must be restored after nested onAgentResult call")
                .isEqualTo(outerOrg);
        });

        // After the outer scope exits, no leak.
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
    }

    private PendingAgent makePending(String correlationId, String runId, String organizationId) {
        return new PendingAgent(
            correlationId,
            runId,
            "agent:classify",
            "classify",
            "trigger:cron",
            13,                            // epoch - matches the prod fire epoch
            0,                             // itemIndex
            "0",                           // itemId
            "classify",                    // agentType
            "1",                           // tenantId
            null,                          // splitItemData - null so restoreSplitContextIfAny is a no-op
            null,                          // resolvedInputData
            null,                          // conversationId - null so persistConversationOnDelivery returns early
            null,                          // streamId
            null,                          // executionId
            "deepseek-chat",               // model
            null,                          // resolvedSystemPrompt
            null,                          // resolvedUserPrompt
            Instant.now().minusMillis(100),
            organizationId);
    }
}
