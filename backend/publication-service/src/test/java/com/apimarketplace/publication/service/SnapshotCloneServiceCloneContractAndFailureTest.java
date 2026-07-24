package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Output-contract and resource-creation failure modes of the public
 * {@link SnapshotCloneService#cloneFromSnapshot} acquire path.
 *
 * <p>The existing focused suites (workflow-type stamping, interface/datasource
 * remap, task/agent remap, FileRef rewrite, agent budget) only drive the HAPPY
 * path - every collaborator returns a non-null result. This suite pins the
 * branches that fire when a downstream clone returns {@code null} (interface,
 * datasource, agent) or {@code null}/throws on the root workflow create, plus
 * the shape of the value cloneFromSnapshot returns and the security ordering of
 * the credential strip relative to the orchestrator request. These are the
 * branches a corrupted snapshot or a transient downstream failure exercises.
 */
@DisplayName("SnapshotCloneService - cloneFromSnapshot output contract + resource-creation failure modes")
class SnapshotCloneServiceCloneContractAndFailureTest {

    private static final String TENANT = "acquirer";
    private static final UUID PUBLICATION_ID = UUID.fromString("12121212-1212-1212-1212-121212121212");

    private OrchestratorInternalClient orchestratorClient;
    private AgentClient agentClient;
    private InterfaceClient interfaceClient;
    private DataSourceClient dataSourceClient;
    private SnapshotCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        agentClient = mock(AgentClient.class);
        interfaceClient = mock(InterfaceClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
    }

    private Map<String, Object> trivialPlan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>());
        plan.put("cores", new ArrayList<>());
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedRootRequest() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(orchestratorClient).createApplicationWorkflow(captor.capture(), eq(TENANT));
        return captor.getValue();
    }

    // ========================================================================
    // Root workflow create: null / exception
    // ========================================================================

    @Test
    @DisplayName("Null orchestrator response on the root create is wrapped in AcquireCloneFailedException carrying the reserved root id")
    void rootCreateReturningNull_throwsAcquireCloneFailedWithRootId() {
        when(orchestratorClient.createApplicationWorkflow(any(), anyString())).thenReturn(null);

        assertThatThrownBy(() ->
                service.cloneFromSnapshot(trivialPlan(), TENANT, PUBLICATION_ID, "App", "desc", null))
                // The RuntimeException raised at the null check is wrapped by the
                // mid-pipeline compensation guard so the caller can scoped-delete.
                .isInstanceOf(AcquireCloneFailedException.class)
                .satisfies(ex -> {
                    AcquireCloneFailedException ace = (AcquireCloneFailedException) ex;
                    // The root id was reserved BEFORE the create call so a half-created row is cleaned.
                    assertThat(ace.getCreatedWorkflowIds())
                            .as("the reserved root workflow id must be carried for scoped compensation")
                            .hasSize(1);
                    assertThat(ace.getCause())
                            .isInstanceOf(RuntimeException.class)
                            .hasMessageContaining(PUBLICATION_ID.toString());
                });
    }

    // ========================================================================
    // Output contract: { workflowId, title }
    // ========================================================================

    @Test
    @DisplayName("cloneFromSnapshot returns exactly workflowId + title, with workflowId echoing the orchestrator's created id")
    void cloneFromSnapshot_returnsExactlyWorkflowIdAndTitle() {
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        Map<String, Object> result = service.cloneFromSnapshot(
                trivialPlan(), TENANT, PUBLICATION_ID, "My Title", "desc", null);

        assertThat(result)
                .as("the acquire result is a 2-key contract consumed by the caller")
                .containsOnlyKeys("workflowId", "title");
        assertThat(result.get("workflowId")).isEqualTo("created-wf-id");
        assertThat(result.get("title")).isEqualTo("My Title");
    }

    // ========================================================================
    // Security: strip credentials BEFORE the orchestrator request
    // ========================================================================

    @Test
    @DisplayName("HTTP authConfig and email credentialIds are absent from the plan AND basePlan sent to the orchestrator")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_stripsCredentialsBeforeOrchestratorRequest() {
        // Core nodes carrying secrets that the publish snapshot may still contain.
        Map<String, Object> httpCore = new LinkedHashMap<>();
        Map<String, Object> httpRequest = new LinkedHashMap<>();
        httpRequest.put("url", "https://example.com");
        httpRequest.put("authConfig", Map.of("type", "bearer", "token", "secret-token"));
        httpCore.put("httpRequest", httpRequest);

        Map<String, Object> emailCore = new LinkedHashMap<>();
        emailCore.put("sendEmail", new LinkedHashMap<>(Map.of("credentialId", 77, "smtpPassword", "raw-smtp-pw")));

        // SSH / SFTP / Database carry an inline password / privateKey RAW fallback that must
        // also be stripped from the acquired clone (they were a strip-gap: the publisher's raw
        // secret would otherwise survive into the acquirer's plan).
        Map<String, Object> sshCore = new LinkedHashMap<>();
        sshCore.put("ssh", new LinkedHashMap<>(Map.of("host", "h", "password", "sshpw", "privateKey", "sshpk")));
        Map<String, Object> sftpCore = new LinkedHashMap<>();
        sftpCore.put("sftp", new LinkedHashMap<>(Map.of("host", "h2", "password", "sftppw", "privateKey", "sftppk")));
        Map<String, Object> dbCore = new LinkedHashMap<>();
        dbCore.put("database", new LinkedHashMap<>(Map.of("host", "h3", "password", "dbpw")));

        Map<String, Object> mcpStep = new LinkedHashMap<>();
        mcpStep.put("id", "mcp:tool");
        mcpStep.put("selectedCredentialId", 88);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(List.of(httpCore, emailCore, sshCore, sftpCore, dbCore)));
        plan.put("mcps", new ArrayList<>(List.of(mcpStep)));

        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        Map<String, Object> req = capturedRootRequest();
        for (String planKey : List.of("plan", "basePlan")) {
            Map<String, Object> sentPlan = (Map<String, Object>) req.get(planKey);
            List<Map<String, Object>> cores = (List<Map<String, Object>>) sentPlan.get("cores");
            Map<String, Object> sentHttp = (Map<String, Object>) cores.get(0).get("httpRequest");
            Map<String, Object> sentEmail = (Map<String, Object>) cores.get(1).get("sendEmail");
            List<Map<String, Object>> mcps = (List<Map<String, Object>>) sentPlan.get("mcps");

            assertThat(sentHttp)
                    .as("HTTP authConfig must be stripped before the orchestrator sees the %s", planKey)
                    .doesNotContainKey("authConfig");
            assertThat(sentEmail).doesNotContainKeys("credentialId", "smtpPassword");
            assertThat(mcps.get(0)).doesNotContainKey("selectedCredentialId");

            Map<String, Object> sentSsh = (Map<String, Object>) cores.get(2).get("ssh");
            Map<String, Object> sentSftp = (Map<String, Object>) cores.get(3).get("sftp");
            Map<String, Object> sentDb = (Map<String, Object>) cores.get(4).get("database");
            assertThat(sentSsh).as("ssh inline secrets stripped in %s", planKey).doesNotContainKeys("password", "privateKey");
            assertThat(sentSftp).as("sftp inline secrets stripped in %s", planKey).doesNotContainKeys("password", "privateKey");
            assertThat(sentDb).as("database inline password stripped in %s", planKey).doesNotContainKey("password");
        }
    }

    // ========================================================================
    // Standalone trigger UUID stripping (F4 PUB-HIJACK)
    // ========================================================================

    @Test
    @DisplayName("Standalone trigger back-reference UUIDs in triggers[].params are stripped from the cloned plan before the orchestrator create")
    @SuppressWarnings("unchecked")
    void cloneFromSnapshot_stripsStandaloneTriggerUuids() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scheduleId", "src-schedule-uuid");
        params.put("webhookId", "src-webhook-uuid");
        params.put("chatEndpointId", "src-chat-uuid");
        params.put("formEndpointId", "src-form-uuid");
        params.put("keepMe", "still-here");

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "schedule");
        trigger.put("params", params);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("cores", new ArrayList<>());

        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        Map<String, Object> sentParams = (Map<String, Object>)
                ((List<Map<String, Object>>) sentPlan.get("triggers")).get(0).get("params");

        assertThat(sentParams)
                .as("the source tenant's standalone-row back-references must not survive into the clone")
                .doesNotContainKeys("scheduleId", "webhookId", "chatEndpointId", "formEndpointId");
        assertThat(sentParams.get("keepMe"))
                .as("non back-reference params are untouched")
                .isEqualTo("still-here");
    }

    // ========================================================================
    // Interface create returning null
    // ========================================================================

    @Test
    @DisplayName("Null interface create response skips the interface: no mapping, interfaceIds back-reference stays at the source id")
    @SuppressWarnings("unchecked")
    void interfaceCreateReturningNull_skipsAndLeavesBackReferenceUnmapped() {
        String oldIface = "iface-src-1";

        Map<String, Object> ifaceNode = new LinkedHashMap<>();
        ifaceNode.put("id", oldIface);
        ifaceNode.put("_snapshot_htmlTemplate", "<div></div>");
        ifaceNode.put("_snapshot_name", "Landing");

        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", "manual");
        trigger.put("interfaceIds", new ArrayList<>(List.of(oldIface)));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));
        plan.put("triggers", new ArrayList<>(List.of(trigger)));

        // Interface creation fails (returns null) - the clone must continue.
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(null);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) sentPlan.get("interfaces");
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) sentPlan.get("triggers");

        // The skipped interface keeps its source id (no new id put back) ...
        assertThat(ifaces.get(0).get("id")).isEqualTo(oldIface);
        // ... so the empty mapping leaves the deprecated back-reference at the source id (unmapped, fail-soft).
        assertThat((List<Object>) triggers.get(0).get("interfaceIds")).containsExactly(oldIface);
    }

    @Test
    @DisplayName("The published format reaches the cloned interface, and _snapshot_format is scrubbed from the plan")
    @SuppressWarnings("unchecked")
    void interfaceCloneCarriesTheFormatAndScrubsTheSnapshotKey() {
        // The format travels with the templates: acquiring an app published from a vertical
        // interface must install a vertical interface, not a full-page 1280x800 one. And the
        // transport key must not survive into the cloned plan, where nothing reads it.
        Map<String, Object> ifaceNode = new LinkedHashMap<>();
        ifaceNode.put("id", "iface-src-1");
        ifaceNode.put("_snapshot_htmlTemplate", "<div>story</div>");
        ifaceNode.put("_snapshot_name", "Story");
        ifaceNode.put("_snapshot_format", "vertical");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(ifaceNode)));

        InterfaceDto created = new InterfaceDto();
        created.setId(UUID.randomUUID());
        ArgumentCaptor<InterfaceCreateRequest> captor = ArgumentCaptor.forClass(InterfaceCreateRequest.class);
        when(interfaceClient.createInterface(captor.capture(), anyString())).thenReturn(created);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        assertThat(captor.getValue().getFormat()).isEqualTo("vertical");

        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        List<Map<String, Object>> ifaces = (List<Map<String, Object>>) sentPlan.get("interfaces");
        assertThat(ifaces.get(0)).doesNotContainKey("_snapshot_format");
    }

    // ========================================================================
    // Datasource create returning null
    // ========================================================================

    @Test
    @DisplayName("Null datasource create response skips the datasource: datasource trigger id stays at the source PK (unmapped)")
    @SuppressWarnings("unchecked")
    void datasourceCreateReturningNull_skipsAndLeavesTriggerIdUnmapped() {
        Map<String, Object> tableNode = new LinkedHashMap<>();
        tableNode.put("dataSourceId", "42");
        tableNode.put("_snapshot_ds_name", "Customers");
        tableNode.put("_snapshot_ds_sourceType", "INLINE");

        Map<String, Object> dsTrigger = new LinkedHashMap<>();
        dsTrigger.put("type", "datasource");
        dsTrigger.put("id", "42");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(tableNode)));
        plan.put("triggers", new ArrayList<>(List.of(dsTrigger)));

        // Datasource clone fails (returns null) - so no old->new mapping is recorded.
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(null);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) sentPlan.get("tables");
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) sentPlan.get("triggers");

        // Snapshot fields are cleaned even on failure, and the table keeps the source dataSourceId.
        assertThat(tables.get(0)).doesNotContainKey("_snapshot_ds_name");
        assertThat(tables.get(0).get("dataSourceId")).isEqualTo("42");
        // The datasource trigger was not remapped (empty dsMapping) so it stays at the source PK.
        assertThat(triggers.get(0).get("id")).isEqualTo("42");
        // No item injection happens when the datasource was not created.
        verify(dataSourceClient, never()).bulkInsertItems(any(), any(), anyString());
    }

    // ========================================================================
    // Agent clone returning null
    // ========================================================================

    @Test
    @DisplayName("Null agent clone response leaves the agent unmapped: core:task.agentId stays at the source id (fail-soft)")
    @SuppressWarnings("unchecked")
    void agentCloneReturningNull_skipsRemapAndLeavesTaskAgentIdStale() {
        String oldAgent = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("agentConfigId", oldAgent);
        agentNode.put("_snapshot_agent_name", "Support Agent");

        Map<String, Object> taskConfig = new LinkedHashMap<>();
        taskConfig.put("operation", "create_task");
        taskConfig.put("agentId", oldAgent);
        Map<String, Object> taskCore = new LinkedHashMap<>();
        taskCore.put("type", "task");
        taskCore.put("task", taskConfig);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new ArrayList<>(List.of(agentNode)));
        plan.put("cores", new ArrayList<>(List.of(taskCore)));

        // Agent clone fails (returns null) - agent mapping stays empty.
        when(agentClient.cloneFromSnapshot(any())).thenReturn(null);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        // The failed agent is absent from the agent id mapping, so the cores remap pass below leaves the
        // Task node's agentId at the source id. (The best-effort toolsConfig pass may still invoke
        // remapToolsConfig with an empty agents map; it is wrapped in try/catch and not asserted here.)
        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        Map<String, Object> sentTask = (Map<String, Object>)
                ((List<Map<String, Object>>) sentPlan.get("cores")).get(0).get("task");
        // Empty agent mapping leaves the Task node's agent reference at the source id (fail-soft).
        assertThat(sentTask.get("agentId")).isEqualTo(oldAgent);
    }

    // ========================================================================
    // Agent toolsConfig remap (second pass) on a successful clone
    // ========================================================================

    @Test
    @DisplayName("A successfully cloned agent gets remapToolsConfig called with the tables/interfaces/agents/workflowId mapping dict")
    @SuppressWarnings("unchecked")
    void successfulAgentClone_invokesRemapToolsConfigWithMappings() {
        String oldAgent = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        UUID newAgent = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("agentConfigId", oldAgent);
        agentNode.put("_snapshot_agent_name", "Toolful Agent");
        agentNode.put("_snapshot_agent_toolsConfig", new LinkedHashMap<>(Map.of("tables", List.of("42"))));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new ArrayList<>(List.of(agentNode)));

        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", newAgent.toString()));
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(agentClient, times(1)).remapToolsConfig(eq(newAgent), mapCaptor.capture());

        Map<String, Object> mappings = mapCaptor.getValue();
        assertThat(mappings)
                .as("remapToolsConfig must receive the four mapping buckets so toolsConfig resource ids are rewritten")
                .containsKeys("tables", "interfaces", "agents", "workflowId");
        // workflowId is the root acquired workflow id (the pre-generated temp id), not null.
        assertThat(mappings.get("workflowId")).isNotNull();
    }

    @Test
    @DisplayName("An exception from remapToolsConfig in the second pass is swallowed: the acquire still completes and the root workflow is created")
    @SuppressWarnings("unchecked")
    void remapToolsConfigThrowing_isSwallowedAndAcquireCompletes() {
        String oldAgent = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        UUID newAgent = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

        Map<String, Object> agentNode = new LinkedHashMap<>();
        agentNode.put("agentConfigId", oldAgent);
        agentNode.put("_snapshot_agent_name", "Toolful Agent");
        agentNode.put("_snapshot_agent_toolsConfig", new LinkedHashMap<>(Map.of("tables", List.of("42"))));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("agents", new ArrayList<>(List.of(agentNode)));

        when(agentClient.cloneFromSnapshot(any())).thenReturn(Map.of("agentId", newAgent.toString()));
        // Second-pass remap blows up - the clone must catch it and keep going.
        org.mockito.Mockito.doThrow(new RuntimeException("agent-service unavailable"))
                .when(agentClient).remapToolsConfig(eq(newAgent), any());
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        Map<String, Object> result =
                service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        // The throwing remap is best-effort: it does not abort the acquire.
        assertThat(result.get("workflowId")).isEqualTo("created-wf-id");
        verify(agentClient).remapToolsConfig(eq(newAgent), any());
        // The root workflow was still created despite the second-pass failure.
        verify(orchestratorClient).createApplicationWorkflow(any(), eq(TENANT));
    }

    // ========================================================================
    // Duplicate datasource references across tables (clone-once optimization)
    // ========================================================================

    @Test
    @DisplayName("Two table nodes referencing the same dataSourceId clone the datasource once and both adopt the same cloned id")
    @SuppressWarnings("unchecked")
    void duplicateDataSourceReference_clonedOnceAndReused() {
        // Both tables point at source PK 42; only the FIRST is cloned, the SECOND reuses the clone id.
        Map<String, Object> tableA = new LinkedHashMap<>();
        tableA.put("dataSourceId", "42");
        tableA.put("_snapshot_ds_name", "Customers");
        tableA.put("_snapshot_ds_sourceType", "INLINE");

        Map<String, Object> tableB = new LinkedHashMap<>();
        tableB.put("dataSourceId", "42");
        tableB.put("_snapshot_ds_name", "Customers");
        tableB.put("_snapshot_ds_sourceType", "INLINE");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(tableA, tableB)));

        DataSourceDto clonedDs = mock(DataSourceDto.class);
        when(clonedDs.id()).thenReturn(99L);
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(clonedDs);
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null);

        // The optimization clones the shared datasource exactly once...
        verify(dataSourceClient, times(1)).createFromSnapshot(any(), anyString());

        // ...and BOTH tables end up bound to the single cloned id (99), not the source PK 42.
        Map<String, Object> req = capturedRootRequest();
        Map<String, Object> sentPlan = (Map<String, Object>) req.get("plan");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) sentPlan.get("tables");
        assertThat(tables.get(0).get("dataSourceId")).isEqualTo("99");
        assertThat(tables.get(1).get("dataSourceId")).isEqualTo("99");
        // Snapshot scaffolding is cleaned off both tables.
        assertThat(tables.get(0)).doesNotContainKey("_snapshot_ds_name");
        assertThat(tables.get(1)).doesNotContainKey("_snapshot_ds_name");
    }

    // ========================================================================
    // CE (null organizationId) vs Cloud (organizationId set) divergence
    // ========================================================================

    @Test
    @DisplayName("CE path (no organizationId) omits organizationId from the orchestrator create request")
    @SuppressWarnings("unchecked")
    void ceCloneWithoutOrganizationId_omitsOrgFromCreateRequest() {
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        // The 6-arg overload (CE-only callers) passes a null organizationId downstream.
        service.cloneFromSnapshot(trivialPlan(), TENANT, PUBLICATION_ID, "App", "desc", null);

        Map<String, Object> req = capturedRootRequest();
        // hasText(null) is false, so the key is never put - the row is created CE-scoped, no org.
        assertThat(req)
                .as("a null organizationId (CE) must not surface as an organizationId in the create request")
                .doesNotContainKey("organizationId");
    }

    @Test
    @DisplayName("Cloud path (organizationId set) includes organizationId in the orchestrator create request")
    @SuppressWarnings("unchecked")
    void cloudCloneWithOrganizationId_includesOrgInCreateRequest() {
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", "created-wf-id"));

        // The 7-arg overload (Cloud callers) carries the acquirer's org through.
        service.cloneFromSnapshot(trivialPlan(), TENANT, PUBLICATION_ID, "App", "desc", null, "acq-org-uuid");

        Map<String, Object> req = capturedRootRequest();
        assertThat(req.get("organizationId"))
                .as("a present organizationId (Cloud) must scope the created workflow row")
                .isEqualTo("acq-org-uuid");
    }

    // ========================================================================
    // Orchestrator root result missing the 'id' field
    // ========================================================================

    @Test
    @DisplayName("Root create response without an 'id' field yields a null workflowId (null-safe) rather than an NPE")
    void rootCreateWithoutIdField_returnsNullWorkflowIdSafely() {
        // result is non-null (passes the null guard) but carries no 'id'; result.get("id") is null
        // and HashMap.put tolerates a null value - the contract degrades gracefully, no NPE.
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("status", "created"));

        Map<String, Object> result =
                service.cloneFromSnapshot(trivialPlan(), TENANT, PUBLICATION_ID, "My Title", "desc", null);

        assertThat(result).containsOnlyKeys("workflowId", "title");
        assertThat(result.get("workflowId"))
                .as("a missing orchestrator 'id' must not NPE; it degrades to a null workflowId")
                .isNull();
        assertThat(result.get("title")).isEqualTo("My Title");
    }

    // ========================================================================
    // DataInput file copy throws
    // ========================================================================

    @Test
    @DisplayName("An exception from copyFile during DataInput file copy aborts the acquire (wrapped in AcquireCloneFailedException)")
    void dataInputFileCopyThrowing_abortsAcquire() {
        Map<String, Object> fileMap = new LinkedHashMap<>();
        fileMap.put("path", "1/general/data-input/source.csv");
        fileMap.put("name", "source.csv");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "file");
        item.put("file", fileMap);

        Map<String, Object> dataInput = new LinkedHashMap<>();
        dataInput.put("items", new ArrayList<>(List.of(item)));

        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "core:data_input");
        core.put("dataInput", dataInput);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", new ArrayList<>(List.of(core)));

        // copyFile blows up - DataInput copy rethrows as RuntimeException, which the acquire guard wraps.
        when(orchestratorClient.copyFile(any(), any()))
                .thenThrow(new RuntimeException("storage unavailable"));

        assertThatThrownBy(() ->
                service.cloneFromSnapshot(plan, TENANT, PUBLICATION_ID, "App", "desc", null))
                .isInstanceOf(AcquireCloneFailedException.class)
                .hasMessageContaining("DataInput file copy failed");

        // The root workflow create must never run once the DataInput copy fails.
        verify(orchestratorClient, never()).createApplicationWorkflow(any(), anyString());
    }
}
