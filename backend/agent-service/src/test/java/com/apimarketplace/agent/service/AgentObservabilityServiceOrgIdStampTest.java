package com.apimarketplace.agent.service;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionIterationEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * PR20 regression - proves the "agent visible / history empty in team workspace"
 * bug is closed at the persistence layer.
 *
 * <p>Pre-PR20 the producer (orchestrator AgentNode) stamped only {@code tenant_id}
 * on the observability row, and the consumer ({@code AgentExecutionHistoryPanel}
 * → {@code findByAgentEntityIdAndTenantId…}) filtered on tenant_id only. Result:
 * a user in a team workspace saw zero rows because their inbound X-User-ID
 * matched the workspace tenantId but the rows had been persisted under the
 * account-root tenant_id.</p>
 *
 * <p>This test pins the post-PR20 contract: the {@code organization_id} field
 * from the request DTO is stamped onto the persisted execution header AND
 * mirrored onto every child row (iterations / messages / tool calls). With the
 * stamp in place the strict-isolation finders surface the row to the matching
 * workspace.</p>
 */
@DisplayName("PR20 - AgentObservabilityService stamps organization_id on all 4 tables")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityServiceOrgIdStampTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

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

    @Captor private ArgumentCaptor<AgentExecutionEntity> execCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionIterationEntity>> iterationsCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionMessageEntity>> messagesCaptor;
    @Captor private ArgumentCaptor<List<AgentExecutionToolCallEntity>> toolCallsCaptor;

    private AgentObservabilityService service;

    @BeforeEach
    void setUp() {
        service = new AgentObservabilityService(
            executionRepository, iterationRepository, messageRepository,
            toolCallRepository, storageService, creditClient,
            aggregationService, breakdownService, agentRepository,
            prometheusMetrics, budgetReservationService);
        // Credit + aggregation side-effects are unrelated to the org_id stamp
        // and are mocked elsewhere - leaving them un-stubbed here returns null,
        // which the service's try/catch demotes to a warn log. No interference.
    }

    private AgentObservabilityRequest baseRequest(String organizationId) {
        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId(TENANT_ID);
        req.setOrganizationId(organizationId);
        req.setAgentEntityId(AGENT_ENTITY_ID);
        req.setAgentType("agent");
        req.setNodeId("agent:my_agent");
        req.setRunId("run-123");
        req.setProvider("anthropic");
        req.setModel("claude-3-sonnet");
        req.setStatus("COMPLETED");
        req.setStopReason("end_turn");
        req.setDurationMs(1234L);
        req.setIterationCount(1);

        var iter = new AgentObservabilityRequest.IterationData();
        iter.setIterationNumber(1);
        iter.setToolCallCount(1);
        req.setIterations(List.of(iter));

        var msg = new AgentObservabilityRequest.MessageData();
        msg.setSequenceNumber(0);
        msg.setRole("USER");
        msg.setContent("hello");
        req.setMessages(new ArrayList<>(List.of(msg)));

        var toolCall = new AgentObservabilityRequest.ToolCallData();
        toolCall.setSequenceNumber(0);
        toolCall.setIterationNumber(1);
        toolCall.setToolName("dummy");
        toolCall.setSuccess(true);
        req.setToolCalls(new ArrayList<>(List.of(toolCall)));

        return req;
    }

    @Test
    @DisplayName("organizationId on request → stamped on execution header + all 3 child tables (regression for empty-history bug)")
    void organizationIdStampedAcrossAllFourTables() {
        AgentObservabilityRequest request = baseRequest(ORG_ID);

        service.recordFromRequest(request);

        // Header: capture both saves (initial + post-stats re-save); both must carry the org_id.
        verify(executionRepository, org.mockito.Mockito.atLeastOnce()).save(execCaptor.capture());
        AgentExecutionEntity savedHeader = execCaptor.getValue();
        assertThat(savedHeader.getOrganizationId())
            .as("Header row must carry org_id so the strict org finder returns it.")
            .isEqualTo(ORG_ID);
        assertThat(savedHeader.getTenantId())
            .as("Tenant id must still be set; org scope augments rather than replaces tenant.")
            .isEqualTo(TENANT_ID);

        // Iterations
        verify(iterationRepository).saveAll(iterationsCaptor.capture());
        assertThat(iterationsCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isEqualTo(ORG_ID));

        // Messages
        verify(messageRepository).saveAll(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isEqualTo(ORG_ID));

        // Tool calls
        verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
        assertThat(toolCallsCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isEqualTo(ORG_ID));
    }

    @Test
    @DisplayName("organizationId null on request → all 4 tables receive NULL org_id (personal scope)")
    void nullOrgIdPersistedAsNullAcrossAllFourTables() {
        AgentObservabilityRequest request = baseRequest(null);

        service.recordFromRequest(request);

        verify(executionRepository, org.mockito.Mockito.atLeastOnce()).save(execCaptor.capture());
        assertThat(execCaptor.getValue().getOrganizationId())
            .as("Personal-scope row must have org_id NULL - the strict personal finder filters on this.")
            .isNull();

        verify(iterationRepository).saveAll(iterationsCaptor.capture());
        assertThat(iterationsCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isNull());

        verify(messageRepository).saveAll(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isNull());

        verify(toolCallRepository).saveAll(toolCallsCaptor.capture());
        assertThat(toolCallsCaptor.getValue())
            .allSatisfy(row -> assertThat(row.getOrganizationId()).isNull());
    }
}
