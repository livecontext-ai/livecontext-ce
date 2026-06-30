package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acquire-clone remap of resource UUID references that live OUTSIDE a resource's
 * primary array.
 *
 * <p>When an application is acquired, each agent/interface/datasource is cloned
 * into the acquirer's tenant with a NEW id, and the clone remaps the PRIMARY
 * arrays ({@code agents[].agentConfigId}, {@code interfaces[].id},
 * {@code tables[].dataSourceId}). But two reference sites embed those ids
 * elsewhere in the plan and were left pointing at the SOURCE tenant:
 * <ul>
 *   <li>{@code core:task.agentId} / {@code task.reviewerAgentId} - a Task node
 *       assigns work to a specific agent by literal UUID. Stale ⇒ at fire time
 *       TaskNode hands the publisher's agent id to agent-service in the
 *       acquirer's tenant ⇒ "the application's agent no longer exists".</li>
 *   <li>{@code triggers[].interfaceIds[]} / {@code mcps[].interfaceIds[]} -
 *       deprecated but still read by InterfacePlanExtractor at run time.</li>
 * </ul>
 *
 * <p>The end-to-end test drives the public {@link SnapshotCloneService#cloneFromSnapshot}
 * and fails on the pre-fix code (the Task node keeps the publisher's agent id);
 * the focused tests pin the two remap helpers in isolation.
 */
@DisplayName("SnapshotCloneService - acquire-clone task.agentId / interfaceIds remap")
class SnapshotCloneServiceTaskAgentRemapTest {

    private static final String TENANT = "acquirer";
    private static final UUID PUBLICATION_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String OLD_AGENT = "11111111-1111-1111-1111-111111111111";
    private static final String NEW_AGENT = "22222222-2222-2222-2222-222222222222";

    private OrchestratorInternalClient orchestratorClient;
    private AgentClient agentClient;
    private InterfaceClient interfaceClient;
    private SnapshotCloneService service;

    private Method remapAgentReferencesInCores;
    private Method remapInterfaceIdRefs;

    @BeforeEach
    void setUp() throws Exception {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        agentClient = mock(AgentClient.class);
        interfaceClient = mock(InterfaceClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                agentClient,
                interfaceClient,
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));

        remapAgentReferencesInCores = SnapshotCloneService.class.getDeclaredMethod(
                "remapAgentReferencesInCores", Map.class, Map.class);
        remapAgentReferencesInCores.setAccessible(true);
        remapInterfaceIdRefs = SnapshotCloneService.class.getDeclaredMethod(
                "remapInterfaceIdRefs", Map.class, Map.class);
        remapInterfaceIdRefs.setAccessible(true);
    }

    // ========================================================================
    // End-to-end: cloneFromSnapshot rewrites cores[].task.agentId (regression)
    // ========================================================================

    @Test
    @DisplayName("cloneFromSnapshot remaps core:task.agentId/reviewerAgentId to the cloned agent id (was left stale pre-fix)")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_remapsTaskAgentIdToClonedAgent() {
        // Agent node referenced by the Task node, enriched at publish time.
        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("id", "agent-node-1");
        agentNode.put("type", "agent");
        agentNode.put("label", "Support Agent");
        agentNode.put("agentConfigId", OLD_AGENT);
        agentNode.put("_snapshot_agent_name", "Support Agent");

        // Task node that assigns work to that agent by literal UUID.
        Map<String, Object> taskConfig = new LinkedHashMap<>();
        taskConfig.put("operation", "create_task");
        taskConfig.put("title", "Do the thing");
        taskConfig.put("agentId", OLD_AGENT);
        taskConfig.put("reviewerAgentId", OLD_AGENT);
        Map<String, Object> taskCore = new LinkedHashMap<>();
        taskCore.put("id", "core-task-1");
        taskCore.put("type", "task");
        taskCore.put("label", "Create Task");
        taskCore.put("task", taskConfig);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new ArrayList<>(List.of(agentNode)));
        plan.put("cores", new ArrayList<>(List.of(taskCore)));

        // Agent clone returns the NEW agent id for the acquirer's tenant.
        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", NEW_AGENT));
        // Orchestrator persists the cloned plan; capture the request to inspect it.
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "cloned-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My App", "desc", null);

        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).createApplicationWorkflow(reqCaptor.capture(), eq(TENANT));

        Map<String, Object> clonedPlan = (Map<String, Object>) reqCaptor.getValue().get("plan");
        List<Map<String, Object>> cores = (List<Map<String, Object>>) clonedPlan.get("cores");
        Map<String, Object> clonedTask = (Map<String, Object>) cores.get(0).get("task");

        // The agent node itself is remapped (existing behavior) ...
        List<Map<String, Object>> agents = (List<Map<String, Object>>) clonedPlan.get("agents");
        assertThat(agents.get(0).get("agentConfigId")).isEqualTo(NEW_AGENT);
        // ... and so is the Task node's reference (the fix). Pre-fix these stayed OLD_AGENT.
        assertThat(clonedTask.get("agentId")).isEqualTo(NEW_AGENT);
        assertThat(clonedTask.get("reviewerAgentId")).isEqualTo(NEW_AGENT);
    }

    @Test
    @DisplayName("cloneFromSnapshot keeps an INLINE agent's model + provider + prompt in the cloned plan (install copies the FULL raw config)")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_keepsInlineAgentModelAndPrompt() {
        // "Echo Watch" shape: a guardrail agent configured INLINE on the node (no
        // agentConfigId / no _snapshot_agent_* entity enrichment). cloneAgentsForTenant
        // skips it (it only clones agents that carry _snapshot_agent_name), so the node
        // rides along in the deep-copied plan with its model/provider/prompt intact.
        // Guards the user concern (2026-06-25): the install must copy the real prompt +
        // model, never the marketplace-preview sanitized plan (which strips the prompt
        // and, pre-fix, the model). The preview sanitizer has a single call site in the
        // read endpoint and is never reached by this acquire/clone path.
        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("id", "agent-Risk Screen-1");
        agentNode.put("type", "guardrail");
        agentNode.put("label", "Risk Screen");
        agentNode.put("model", "deepseek-chat");
        agentNode.put("provider", "deepseek");
        agentNode.put("prompt", "You are screening recent news mentions about a brand...");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new ArrayList<>(List.of(agentNode)));

        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "cloned-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My App", "desc", null);

        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).createApplicationWorkflow(reqCaptor.capture(), eq(TENANT));
        Map<String, Object> clonedPlan = (Map<String, Object>) reqCaptor.getValue().get("plan");
        List<Map<String, Object>> agents = (List<Map<String, Object>>) clonedPlan.get("agents");

        // The publisher's full inline config survives into the acquirer's clone.
        assertThat(agents.get(0)).containsEntry("model", "deepseek-chat");
        assertThat(agents.get(0)).containsEntry("provider", "deepseek");
        assertThat(agents.get(0).get("prompt").toString()).startsWith("You are screening recent news");
    }

    @Test
    @DisplayName("cloneFromSnapshot remaps deprecated triggers[]/mcps[].interfaceIds[] to the cloned interface id (top-level wiring)")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_remapsInterfaceIdsBackReferences() {
        UUID newIface = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String oldIface = "iface-source-1";

        Map<String, Object> ifaceNode = new LinkedHashMap<>();
        ifaceNode.put("id", oldIface);
        ifaceNode.put("_snapshot_htmlTemplate", "<div>{{title}}</div>");
        ifaceNode.put("_snapshot_name", "Landing");

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "manual");
        trigger.put("interfaceIds", new ArrayList<>(List.of(oldIface)));

        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "slug/tool");
        mcp.put("interfaceIds", new ArrayList<>(List.of(oldIface)));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(newIface);
        when(interfaceClient.createInterface(any(), anyString())).thenReturn(savedIface);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "cloned-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My App", "desc", null);

        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).createApplicationWorkflow(reqCaptor.capture(), eq(TENANT));
        Map<String, Object> clonedPlan = (Map<String, Object>) reqCaptor.getValue().get("plan");

        // Primary array remapped (existing behavior) ...
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) clonedPlan.get("interfaces");
        assertThat(ifaces.get(0).get("id")).isEqualTo(newIface.toString());
        // ... and the deprecated back-references too (the fix).
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) clonedPlan.get("triggers");
        List<Map<String, Object>> mcps = (List<Map<String, Object>>) clonedPlan.get("mcps");
        assertThat((List<Object>) triggers.get(0).get("interfaceIds")).containsExactly(newIface.toString());
        assertThat((List<Object>) mcps.get(0).get("interfaceIds")).containsExactly(newIface.toString());
    }

    @Test
    @DisplayName("Sub-workflow path: nested core:task.agentId and trigger.interfaceIds are remapped to the cloned ids")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_remapsNestedSubWorkflowReferences() {
        String oldSubAgent = "77777777-7777-7777-7777-777777777777";
        String newSubAgent = "88888888-8888-8888-8888-888888888888";
        String oldSubIface = "iface-sub-1";
        UUID newSubIface = UUID.fromString("99999999-9999-9999-9999-999999999999");

        // Agent node + Task node + interfaceIds trigger, all INSIDE a sub-workflow snapshot.
        Map<String, Object> subAgentNode = new LinkedHashMap<>();
        subAgentNode.put("id", "sub-agent-node");
        subAgentNode.put("type", "agent");
        subAgentNode.put("agentConfigId", oldSubAgent);
        subAgentNode.put("_snapshot_agent_name", "Nested Agent");

        Map<String, Object> subTask = new LinkedHashMap<>();
        subTask.put("operation", "create_task");
        subTask.put("title", "Nested task");
        subTask.put("agentId", oldSubAgent);
        Map<String, Object> subTaskCore = new LinkedHashMap<>();
        subTaskCore.put("id", "sub-task-core");
        subTaskCore.put("type", "task");
        subTaskCore.put("task", subTask);

        Map<String, Object> subTrigger = new LinkedHashMap<>();
        subTrigger.put("type", "manual");
        subTrigger.put("interfaceIds", new ArrayList<>(List.of(oldSubIface)));

        Map<String, Object> subIfaceNode = new LinkedHashMap<>();
        subIfaceNode.put("id", oldSubIface);
        subIfaceNode.put("_snapshot_htmlTemplate", "<div></div>");
        subIfaceNode.put("_snapshot_name", "Nested UI");

        Map<String, Object> subPlan = new LinkedHashMap<>();
        subPlan.put("agents", new ArrayList<>(List.of(subAgentNode)));
        subPlan.put("cores", new ArrayList<>(List.of(subTaskCore)));
        subPlan.put("triggers", new ArrayList<>(List.of(subTrigger)));
        subPlan.put("interfaces", new ArrayList<>(List.of(subIfaceNode)));

        Map<String, Object> subSnapshot = new LinkedHashMap<>();
        subSnapshot.put("name", "Sub WF");
        subSnapshot.put("plan", subPlan);

        Map<String, Object> subWorkflows = new LinkedHashMap<>();
        subWorkflows.put("old-sub-workflow-id", subSnapshot);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("_snapshot_subworkflows", subWorkflows);

        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(newSubIface);
        when(interfaceClient.createInterface(any(), anyString())).thenReturn(savedIface);
        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", newSubAgent));
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "any-workflow-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "My App", "desc", null);

        // Two createApplicationWorkflow calls: the sub-workflow first, then the top-level.
        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient, org.mockito.Mockito.atLeast(1))
                .createApplicationWorkflow(reqCaptor.capture(), eq(TENANT));

        Map<String, Object> subRequest = reqCaptor.getAllValues().stream()
                .filter(r -> "Sub WF".equals(r.get("title")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sub-workflow create request not captured"));
        Map<String, Object> clonedSubPlan = (Map<String, Object>) subRequest.get("plan");

        Map<String, Object> clonedTask = (Map<String, Object>)
                ((List<Map<String, Object>>) clonedSubPlan.get("cores")).get(0).get("task");
        assertThat(clonedTask.get("agentId")).isEqualTo(newSubAgent);

        List<Map<String, Object>> clonedTriggers = (List<Map<String, Object>>) clonedSubPlan.get("triggers");
        assertThat((List<Object>) clonedTriggers.get(0).get("interfaceIds"))
                .containsExactly(newSubIface.toString());
    }

    // ========================================================================
    // remapAgentReferencesInCores - unit
    // ========================================================================

    private void invokeAgentRemap(Map<String, Object> plan, Map<String, String> mapping) throws Exception {
        remapAgentReferencesInCores.invoke(service, plan, mapping);
    }

    private Map<String, Object> taskCore(String agentId, String reviewerAgentId) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("operation", "create_task");
        if (agentId != null) task.put("agentId", agentId);
        if (reviewerAgentId != null) task.put("reviewerAgentId", reviewerAgentId);
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("type", "task");
        core.put("task", task);
        return core;
    }

    @SafeVarargs
    private final Map<String, Object> planWithCores(Map<String, Object>... cores) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(Arrays.asList(cores)));
        return plan;
    }

    @Test
    @DisplayName("Happy path: remaps both task.agentId and task.reviewerAgentId via the agent mapping")
    @SuppressWarnings("unchecked")
    void remapsBothTaskAgentFields() throws Exception {
        Map<String, Object> core = taskCore(OLD_AGENT, "33333333-3333-3333-3333-333333333333");
        Map<String, Object> plan = planWithCores(core);
        Map<String, String> mapping = Map.of(
                OLD_AGENT, NEW_AGENT,
                "33333333-3333-3333-3333-333333333333", "44444444-4444-4444-4444-444444444444");

        invokeAgentRemap(plan, mapping);

        Map<String, Object> task = (Map<String, Object>) core.get("task");
        assertThat(task.get("agentId")).isEqualTo(NEW_AGENT);
        assertThat(task.get("reviewerAgentId")).isEqualTo("44444444-4444-4444-4444-444444444444");
    }

    @Test
    @DisplayName("Template-expression agentId is left untouched (only literal UUIDs in the mapping are rewritten)")
    @SuppressWarnings("unchecked")
    void expressionAgentId_keptAsIs() throws Exception {
        String expr = "{{core:pick_agent.output.agent_id}}";
        Map<String, Object> core = taskCore(expr, null);
        Map<String, Object> plan = planWithCores(core);

        invokeAgentRemap(plan, Map.of(OLD_AGENT, NEW_AGENT));

        Map<String, Object> task = (Map<String, Object>) core.get("task");
        assertThat(task.get("agentId")).isEqualTo(expr);
    }

    @Test
    @DisplayName("Unmapped agentId (agent not part of the publication) is kept as-is - fail-soft")
    @SuppressWarnings("unchecked")
    void unmappedAgentId_keptAsIs() throws Exception {
        Map<String, Object> core = taskCore(OLD_AGENT, null);
        Map<String, Object> plan = planWithCores(core);

        invokeAgentRemap(plan, Map.of("99999999-9999-9999-9999-999999999999", NEW_AGENT));

        Map<String, Object> task = (Map<String, Object>) core.get("task");
        assertThat(task.get("agentId")).isEqualTo(OLD_AGENT);
    }

    @Test
    @DisplayName("Core without a task config and a task with no agent fields are skipped without NPE")
    void nonTaskCoreAndTaskWithoutAgentFields_skipped() throws Exception {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "decision");
        Map<String, Object> listTask = taskCore(null, null); // list_tasks-style: no agent fields
        Map<String, Object> plan = planWithCores(decision, listTask);

        invokeAgentRemap(plan, Map.of(OLD_AGENT, NEW_AGENT));

        // No exception, and nothing inappropriate added.
        assertThat(decision).doesNotContainKey("task");
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) listTask.get("task");
        assertThat(task).doesNotContainKeys("agentId", "reviewerAgentId");
    }

    @Test
    @DisplayName("Empty agent mapping short-circuits without touching cores")
    @SuppressWarnings("unchecked")
    void emptyMapping_noop() throws Exception {
        Map<String, Object> core = taskCore(OLD_AGENT, null);
        Map<String, Object> plan = planWithCores(core);

        invokeAgentRemap(plan, new HashMap<>());

        Map<String, Object> task = (Map<String, Object>) core.get("task");
        assertThat(task.get("agentId")).isEqualTo(OLD_AGENT);
    }

    @Test
    @DisplayName("Plan with no cores field is a no-op (defensive)")
    void planWithoutCores_noop() throws Exception {
        Map<String, Object> plan = new LinkedHashMap<>();
        invokeAgentRemap(plan, Map.of(OLD_AGENT, NEW_AGENT));
        assertThat(plan).doesNotContainKey("cores");
    }

    // ========================================================================
    // remapInterfaceIdRefs - unit
    // ========================================================================

    private void invokeIfaceRemap(Map<String, Object> plan, Map<String, String> mapping) throws Exception {
        remapInterfaceIdRefs.invoke(service, plan, mapping);
    }

    @Test
    @DisplayName("Remaps deprecated triggers[].interfaceIds and mcps[].interfaceIds to cloned interface ids")
    @SuppressWarnings("unchecked")
    void remapsInterfaceIdsOnTriggersAndMcps() throws Exception {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "manual");
        trigger.put("interfaceIds", new ArrayList<>(List.of("iface-old-1", "iface-old-2")));

        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "slug/tool");
        mcp.put("interfaceIds", new ArrayList<>(List.of("iface-old-1")));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("mcps", new ArrayList<>(List.of(mcp)));

        Map<String, String> mapping = Map.of("iface-old-1", "iface-new-1", "iface-old-2", "iface-new-2");

        invokeIfaceRemap(plan, mapping);

        assertThat((List<Object>) trigger.get("interfaceIds")).containsExactly("iface-new-1", "iface-new-2");
        assertThat((List<Object>) mcp.get("interfaceIds")).containsExactly("iface-new-1");
    }

    @Test
    @DisplayName("Unmapped interface id is kept; null element is preserved in place")
    @SuppressWarnings("unchecked")
    void unmappedInterfaceIdKept_nullPreserved() throws Exception {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "manual");
        trigger.put("interfaceIds", new ArrayList<>(Arrays.asList("iface-old-1", "iface-foreign", null)));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));

        invokeIfaceRemap(plan, Map.of("iface-old-1", "iface-new-1"));

        assertThat((List<Object>) trigger.get("interfaceIds"))
                .containsExactly("iface-new-1", "iface-foreign", null);
    }

    @Test
    @DisplayName("Empty interface mapping short-circuits without touching interfaceIds")
    @SuppressWarnings("unchecked")
    void emptyInterfaceMapping_noop() throws Exception {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("interfaceIds", new ArrayList<>(List.of("iface-old-1")));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));

        invokeIfaceRemap(plan, new HashMap<>());

        assertThat((List<Object>) trigger.get("interfaceIds")).containsExactly("iface-old-1");
    }
}
