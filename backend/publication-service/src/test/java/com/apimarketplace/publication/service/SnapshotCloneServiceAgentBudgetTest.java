package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduction for the publication-snapshot audit HIGH "H1": a workflow-embedded agent's credit
 * budget is captured at publish but DROPPED on acquire, so the acquired agent runs UNCAPPED.
 *
 * <p>{@code WorkflowPublicationService.enrichPlanWithAgentData} writes
 * {@code _snapshot_agent_creditBudget} + {@code _snapshot_agent_budgetResetMode} into the plan
 * (its own comment: "Budget cap - without this, an acquired agent runs uncapped"), but the live
 * acquire path {@code SnapshotCloneService.cloneAgentsForTenant} never reads those keys into the
 * {@code agentClient.cloneFromSnapshot} request - even though agent-service's clone endpoint
 * honours {@code creditBudget}/{@code budgetResetMode}. The fields silently vanish in transit.</p>
 *
 * <p>This test captures the clone request and fails on current code (no budget keys); it passes
 * once {@code cloneAgentsForTenant} forwards the captured budget fields.</p>
 */
@DisplayName("SnapshotCloneService - acquired agent keeps its credit budget cap (audit H1)")
class SnapshotCloneServiceAgentBudgetTest {

    private static final String TENANT = "acquirer";
    private static final String ORG = "org-acme";
    private static final UUID PUBLICATION_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private AgentClient agentClient;
    private SnapshotCloneService service;
    private Method cloneAgentsForTenant;

    @BeforeEach
    void setUp() throws Exception {
        agentClient = mock(AgentClient.class);
        service = new SnapshotCloneService(
                mock(OrchestratorInternalClient.class),
                agentClient,
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        cloneAgentsForTenant = SnapshotCloneService.class.getDeclaredMethod(
                "cloneAgentsForTenant", Map.class, String.class, UUID.class, String.class,
                Map.class, Map.class, String.class);
        cloneAgentsForTenant.setAccessible(true);
    }

    @Test
    @DisplayName("clone request forwards the captured creditBudget + budgetResetMode")
    void cloneRequestCarriesCreditBudget() throws Exception {
        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", "new-agent"));

        Map<String, Object> agentNode = new HashMap<>();
        agentNode.put("agentConfigId", "old-agent");
        agentNode.put("_snapshot_agent_name", "Budgeted Agent");
        agentNode.put("_snapshot_agent_creditBudget", 500);
        agentNode.put("_snapshot_agent_budgetResetMode", "monthly");

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentNode);
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", agents);

        cloneAgentsForTenant.invoke(service, plan, TENANT, PUBLICATION_ID, "acquired-wf",
                new HashMap<String, String>(), new HashMap<String, String>(), ORG);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentClient).cloneFromSnapshot(captor.capture());
        Map<String, Object> req = captor.getValue();

        assertThat(req.get("creditBudget"))
                .as("the acquired agent MUST keep its credit budget cap - without it the agent runs "
                  + "uncapped (the publish side captures it precisely to prevent this)")
                .isEqualTo(500);
        assertThat(req.get("budgetResetMode"))
                .as("the budget reset cadence must travel with the clone too")
                .isEqualTo("monthly");
    }

    @Test
    @DisplayName("clone request forwards loop-guard, compaction-model, and reasoning-effort overrides (M1/M3)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cloneRequestCarriesLoopCompactionReasoning() throws Exception {
        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", "new-agent"));

        Map<String, Object> agentNode = new HashMap<>();
        agentNode.put("agentConfigId", "old-agent");
        agentNode.put("_snapshot_agent_name", "Tuned Agent");
        agentNode.put("_snapshot_agent_maxPerResourcePerTurn", 3);
        agentNode.put("_snapshot_agent_loopIdenticalStop", 2);
        agentNode.put("_snapshot_agent_loopConsecutiveStop", 4);
        agentNode.put("_snapshot_agent_compactionModelProvider", "anthropic");
        agentNode.put("_snapshot_agent_compactionModelName", "claude-haiku-4-5");
        agentNode.put("_snapshot_agent_reasoningEffort", "high");

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentNode);
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", agents);

        cloneAgentsForTenant.invoke(service, plan, TENANT, PUBLICATION_ID, "acquired-wf",
                new HashMap<String, String>(), new HashMap<String, String>(), ORG);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentClient).cloneFromSnapshot(captor.capture());
        Map<String, Object> req = captor.getValue();

        assertThat(req.get("maxPerResourcePerTurn")).as("loop per-resource cap forwarded").isEqualTo(3);
        assertThat(req.get("loopIdenticalStop")).as("loop identical-stop forwarded").isEqualTo(2);
        assertThat(req.get("loopConsecutiveStop")).as("loop consecutive-stop forwarded").isEqualTo(4);
        assertThat(req.get("compactionModelProvider")).as("COLD-summariser provider forwarded").isEqualTo("anthropic");
        assertThat(req.get("compactionModelName")).as("COLD-summariser model forwarded").isEqualTo("claude-haiku-4-5");
        assertThat(req.get("reasoningEffort")).as("reasoning-effort forwarded").isEqualTo("high");
    }

    @Test
    @DisplayName("acquire recreates the workflow-embedded agent's webhook + schedule (M2)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cloneRecreatesAgentWebhookAndSchedule() throws Exception {
        String newAgentId = "33333333-3333-3333-3333-333333333333";
        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", newAgentId));

        Map<String, Object> agentNode = new HashMap<>();
        agentNode.put("agentConfigId", "old-agent");
        agentNode.put("_snapshot_agent_name", "Scheduled Agent");
        Map<String, Object> webhook = new HashMap<>();
        webhook.put("httpMethod", "POST");
        webhook.put("memoryEnabled", true);
        agentNode.put("_snapshot_agent_webhookConfig", webhook);
        Map<String, Object> schedule = new HashMap<>();
        schedule.put("cronExpression", "0 9 * * *");
        schedule.put("timezone", "Europe/Paris");
        agentNode.put("_snapshot_agent_scheduleConfig", schedule);

        List<Map<String, Object>> agents = new ArrayList<>();
        agents.add(agentNode);
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", agents);

        cloneAgentsForTenant.invoke(service, plan, TENANT, PUBLICATION_ID, "acquired-wf",
                new HashMap<String, String>(), new HashMap<String, String>(), ORG);

        UUID newAgentUuid = UUID.fromString(newAgentId);
        ArgumentCaptor<Map<String, Object>> webhookCap = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentClient).createOrUpdateWebhook(eq(newAgentUuid), webhookCap.capture(), eq(TENANT));
        assertThat(webhookCap.getValue().get("httpMethod"))
                .as("the publisher's webhook must be recreated on the acquired agent (consistency with the agent path)")
                .isEqualTo("POST");

        ArgumentCaptor<Map<String, Object>> schedCap = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentClient).createOrUpdateSchedule(eq(newAgentUuid), schedCap.capture(), eq(TENANT));
        assertThat(schedCap.getValue().get("cronExpression"))
                .as("the publisher's cron must travel with the acquired workflow-embedded agent")
                .isEqualTo("0 9 * * *");
        assertThat(schedCap.getValue().get("timezone")).isEqualTo("Europe/Paris");
    }
}
