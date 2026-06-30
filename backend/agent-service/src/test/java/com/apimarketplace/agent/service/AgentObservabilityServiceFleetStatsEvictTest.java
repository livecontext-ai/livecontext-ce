package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pins the fleet-stats cache eviction wired into the execution-finalize choke-point
 * ({@code doRecordFromRequest} step 9). A finished agent run must drop its workspace's
 * cached {@code /agents/stats} payload so the badges reflect the run on the next fleet
 * open - but ONLY for real-agent runs in a real workspace (the four fleet aggregations
 * all filter {@code agent_entity_id IS NOT NULL}, so general-chat / null-org rows can't
 * change the badges and must not churn the cache).
 *
 * <p>The cache evictor is field-injected ({@code @Autowired(required=false)}), so it is
 * set here via reflection - mirroring how it wires in production when the bean is present.
 */
@DisplayName("AgentObservabilityService - fleet stats cache eviction on finalize")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceFleetStatsEvictTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-acme";
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CALLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private StorageService storageService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AgentMetricsAggregationService aggregationService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private AgentRepository agentRepository;
    @Mock private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;
    @Mock private com.apimarketplace.agent.service.budget.BudgetReservationService budgetReservationService;
    @Mock private FleetStatsCacheService fleetStatsCacheService;

    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService);
        ReflectionTestUtils.setField(service, "fleetStatsCacheService", fleetStatsCacheService);
    }

    private AgentObservabilityRequest baseRequest() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId(TENANT);
        req.setAgentEntityId(AGENT_ID);
        req.setOrganizationId(ORG);
        req.setAgentType("agent");
        req.setNodeId("agent:my_agent");
        req.setRunId("run-123");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus("COMPLETED");
        req.setStopReason("end_turn");
        req.setDurationMs(5000L);
        req.setPromptTokens(1000);
        req.setCompletionTokens(500);
        req.setTotalTokens(1500);
        req.setIterationCount(2);
        req.setTotalToolCalls(3);
        req.setLoopDetected(false);
        return req;
    }

    @Test
    @DisplayName("a real-agent run in a real workspace evicts that workspace's fleet stats cache once")
    void evictsForRealAgentRun() {
        service.recordFromRequest(baseRequest());

        // Reaching the aggregation step (6) proves the finalize tail ran; eviction is step 9,
        // after only try-catch'd steps, so it is guaranteed reached.
        verify(aggregationService).updateAggregations(
            eq(TENANT), eq(ORG), eq(AGENT_ID), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
            anyString(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        verify(fleetStatsCacheService).evict(ORG);
    }

    @Test
    @DisplayName("a sub-agent run (caller present) feeds the callee-side sub-agent rollup with the RESOLVED status")
    void feedsSubAgentRollupFromCallee() {
        AgentObservabilityRequest req = baseRequest();
        req.setCallerAgentId(CALLER_ID);

        service.recordFromRequest(req);

        // Step 6b: caller = this execution's caller, callee = this execution's agent, and the
        // status is the RESOLVED exec.getStatus() (baseRequest resolves to COMPLETED) - NOT the
        // raw request status field. This pins the observability-layer wiring of the callee feed.
        verify(aggregationService).recordSubAgentCallFromCallee(ORG, TENANT, CALLER_ID, AGENT_ID, "COMPLETED");
    }

    @Test
    @DisplayName("a run with no workspace (org id null) does NOT touch the cache")
    void noEvictWhenOrgMissing() {
        AgentObservabilityRequest req = baseRequest();
        req.setOrganizationId(null);

        service.recordFromRequest(req);

        verify(fleetStatsCacheService, never()).evict(anyString());
    }

    @Test
    @DisplayName("a general-chat run (no agent_entity_id) does NOT touch the cache - it can't change the badges")
    void noEvictWhenNoAgentEntity() {
        AgentObservabilityRequest req = baseRequest();
        req.setAgentEntityId(null);

        service.recordFromRequest(req);

        verify(fleetStatsCacheService, never()).evict(anyString());
    }

    @Test
    @DisplayName("the chat record path (recordFromChat) also evicts - both finalize paths funnel through the same choke-point")
    void evictsForChatPath() {
        // An agent-chat run (agent_entity_id set, source=CHAT) DOES feed the fleet badges
        // (the aggregations don't filter on source), so its workspace cache must drop too.
        service.recordFromChat(TENANT, ORG, chatRequest());

        verify(fleetStatsCacheService).evict(ORG);
    }

    private ChatAgentObservabilityRequest chatRequest() {
        // 40 positional args - kept aligned with ChatAgentObservabilityRequest (see
        // AgentObservabilityServiceRecordFromChatTest#minimalRequest).
        return new ChatAgentObservabilityRequest(
            AGENT_ID.toString(),                 // agentEntityId
            "anthropic", "claude-3-sonnet",      // provider, model
            0.7, 4096, 10,                       // temperature, maxTokens, maxIterations
            true, "end_turn", null, null,        // success, stopReason, budgetScope, errorMessage
            100L, 1, 0, 0, 0, 1,                 // durationMs, iterationCount, 3x toolCall counts, messageCount
            10, 5, 15,                           // promptTokens, completionTokens, totalTokens
            null, null, null, null,              // cache + reasoning tokens
            null, null, false, null, null,       // toolSequence, distinctTools, loopDetected, loopType, loopToolName
            "system prompt", "user prompt",      // systemPrompt, userPrompt
            "conv-abc", "CHAT",                  // conversationId, source
            null, UUID.randomUUID().toString(),  // taskId, executionId
            null, null, null, null, null, null   // toolResults, conversationHistory, 4x per-iteration lists
        );
    }
}
