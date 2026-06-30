package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Reproduction for the publication-snapshot audit HIGH "H2": a sub-workflow that references the
 * ROOT via an agent's {@code toolsConfig.workflows} or a {@code workflow}/{@code error} trigger id
 * (not a core {@code sub_workflow} node) was NOT marked {@code __self__} at publish, and NOT
 * remapped at acquire - so the acquired clone kept the publisher-tenant root id and dangled.
 *
 * <p>Pre-fix both {@code markSelfRefNodes} (publish) and {@code remapSelfReferences} (acquire)
 * handled ONLY core sub_workflow nodes. These two direct tests pin the agent + trigger shapes.</p>
 */
@DisplayName("Publication self-ref: agent toolsConfig.workflows + workflow/error triggers (audit H2)")
class PublicationSelfRefAgentTriggerTest {

    private static final String MAIN = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACTUAL = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private WorkflowPublicationService publicationService;
    private SnapshotCloneService cloneService;
    private Method markSelfRefNodes;
    private Method remapSelfReferences;

    @BeforeEach
    void setUp() throws Exception {
        publicationService = new WorkflowPublicationService(
                mock(WorkflowPublicationRepository.class), mock(PublicationSnapshotVersionRepository.class),
                mock(PublicationReceiptRepository.class), mock(PublicationReviewRepository.class),
                mock(OrchestratorInternalClient.class), mock(AgentClient.class), mock(InterfaceClient.class),
                mock(DataSourceClient.class), mock(StorageBreakdownService.class), new ObjectMapper(),
                mock(SnapshotCloneService.class), mock(EntitlementGuard.class), mock(AuthClient.class));
        cloneService = new SnapshotCloneService(
                mock(OrchestratorInternalClient.class), mock(AgentClient.class), mock(InterfaceClient.class),
                mock(DataSourceClient.class), mock(StorageBreakdownService.class), new ObjectMapper(),
                mock(DataSourceFileCloneService.class));

        markSelfRefNodes = WorkflowPublicationService.class.getDeclaredMethod(
                "markSelfRefNodes", Map.class, String.class);
        markSelfRefNodes.setAccessible(true);
        remapSelfReferences = SnapshotCloneService.class.getDeclaredMethod(
                "remapSelfReferences", Map.class, String.class);
        remapSelfReferences.setAccessible(true);
    }

    @Test
    @DisplayName("publish marks agent-workflow + workflow-trigger references to the root as __self__")
    void publishMarksAgentAndTriggerSelfRefs() throws Exception {
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(subWorkflowCore(MAIN))));
        plan.put("agents", new ArrayList<>(List.of(agentWithWorkflows(MAIN, "other-wf"))));
        plan.put("triggers", new ArrayList<>(List.of(
                trigger("workflow", MAIN), trigger("schedule", "keep-me"))));

        markSelfRefNodes.invoke(publicationService, plan, MAIN);

        assertThat(agentWorkflows(plan))
                .as("an agent's workflow access to the root must become __self__ (kept the literal "
                  + "root id pre-fix → dangling cross-tenant ref on acquire)")
                .containsExactly("__self__", "other-wf");
        assertThat(triggerId(plan, 0)).as("workflow trigger to root → __self__").isEqualTo("__self__");
        assertThat(triggerId(plan, 1)).as("unrelated trigger id untouched").isEqualTo("keep-me");
        assertThat(coreWorkflowId(plan)).as("core sub_workflow to root → __self__ (already worked)").isEqualTo("__self__");
    }

    @Test
    @DisplayName("acquire remaps __self__ in agent-workflow + workflow/error trigger refs to the actual id")
    void acquireRemapsAgentAndTriggerSelfRefs() throws Exception {
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", new ArrayList<>(List.of(subWorkflowCore("__self__"))));
        plan.put("agents", new ArrayList<>(List.of(agentWithWorkflows("__self__", "x"))));
        plan.put("triggers", new ArrayList<>(List.of(trigger("error", "__self__"))));

        remapSelfReferences.invoke(cloneService, plan, ACTUAL);

        assertThat(agentWorkflows(plan))
                .as("agent __self__ workflow ref must resolve to the cloned root id, not stay __self__")
                .containsExactly(ACTUAL, "x");
        assertThat(triggerId(plan, 0)).as("error trigger __self__ → actual id").isEqualTo(ACTUAL);
        assertThat(coreWorkflowId(plan)).as("core __self__ → actual id (already worked)").isEqualTo(ACTUAL);
    }

    // ---- builders / accessors -------------------------------------------------
    private static Map<String, Object> subWorkflowCore(String workflowId) {
        Map<String, Object> sub = new HashMap<>();
        sub.put("workflowId", workflowId);
        Map<String, Object> core = new HashMap<>();
        core.put("type", "sub_workflow");
        core.put("subWorkflow", sub);
        return core;
    }

    private static Map<String, Object> agentWithWorkflows(String... workflowIds) {
        Map<String, Object> tc = new HashMap<>();
        tc.put("workflows", new ArrayList<>(Arrays.asList(workflowIds)));
        Map<String, Object> agent = new HashMap<>();
        agent.put("_snapshot_agent_toolsConfig", tc);
        return agent;
    }

    private static Map<String, Object> trigger(String type, String id) {
        Map<String, Object> t = new HashMap<>();
        t.put("type", type);
        t.put("id", id);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> agentWorkflows(Map<String, Object> plan) {
        Map<String, Object> agent = (Map<String, Object>) ((List<Object>) plan.get("agents")).get(0);
        Map<String, Object> tc = (Map<String, Object>) agent.get("_snapshot_agent_toolsConfig");
        return (List<Object>) tc.get("workflows");
    }

    @SuppressWarnings("unchecked")
    private static String triggerId(Map<String, Object> plan, int idx) {
        return String.valueOf(((Map<String, Object>) ((List<Object>) plan.get("triggers")).get(idx)).get("id"));
    }

    @SuppressWarnings("unchecked")
    private static String coreWorkflowId(Map<String, Object> plan) {
        Map<String, Object> core = (Map<String, Object>) ((List<Object>) plan.get("cores")).get(0);
        return String.valueOf(((Map<String, Object>) core.get("subWorkflow")).get("workflowId"));
    }
}
