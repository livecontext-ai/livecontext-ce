package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentPublicationService per-family grant snapshot scope")
class AgentPublicationServiceWorkflowSnapshotTest {

    private static final String TENANT_ID = "tenant-publisher";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private WorkflowPublicationService workflowPublicationService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private DataSourceFileCloneService fileCloneService;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private AuthClient authClient;

    // ====================================================================
    // Policy (2026-07): a publication never carries a grant of "all".
    // publishAgent refuses up-front with the AGGREGATED violation list; the
    // snapshot builder never enumerates the publisher's tenant.
    // ====================================================================

    @Test
    @DisplayName("publish REFUSES an all-granted builder agent with the aggregated violation list (all 5 families) and persists nothing")
    @SuppressWarnings("unchecked")
    void publishRefusesAllGrantedBuilderAgentWithAggregatedViolations() {
        UUID landingInterfaceId = UUID.fromString("33333333-3333-4333-8333-333333333333");

        // An all-granted builder agent: every family granted "all", catalogue mode "all".
        Map<String, Object> toolsConfig = new LinkedHashMap<>();
        toolsConfig.put("mode", "all");
        toolsConfig.put("workflowsGrant", "all");
        toolsConfig.put("tablesGrant", "all");
        toolsConfig.put("interfacesGrant", "all");
        toolsConfig.put("agentsGrant", "all");
        toolsConfig.put("applicationsGrant", "all");
        toolsConfig.put("webSearch", true);

        AgentDto agent = orgAgent(toolsConfig);

        when(landingInterfaceSnapshotter.parseInterfaceId(landingInterfaceId.toString()))
                .thenReturn(landingInterfaceId);
        when(agentClient.getAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(agent);
        when(publicationRepository.findByAgentConfigId(AGENT_ID)).thenReturn(Optional.empty());
        when(authClient.getPublisherProfile(TENANT_ID))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Publisher", "publisher@example.test", null));

        AgentPublicationService service = newService();
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                TenantResolver.runWithOrgScope(ORG_ID, () ->
                        service.publishAgent(publishAgentRequest(landingInterfaceId), TENANT_ID, ORG_ID)));

        PublicationValidationException refusal = unwrapValidation(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) refusal.getDetails().get("violations");
        assertThat(violations).hasSize(1);
        Map<String, Object> violation = violations.get(0);
        assertThat(violation).containsEntry("agentId", AGENT_ID.toString());
        assertThat(violation).containsEntry("agentName", "Agent");
        assertThat(violation).containsEntry("root", true);
        assertThat((List<String>) violation.get("families"))
                .containsExactly("workflows", "tables", "interfaces", "agents", "applications");

        // Refusal is a pure validation: nothing enumerated, nothing persisted.
        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
        verify(agentClient, never()).getAgents(any(), any(), any());
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("persisted snapshot toolsConfig preserves a custom grant + its explicit id list verbatim")
    @SuppressWarnings("unchecked")
    void persistedSnapshotPreservesCustomGrantAndList() {
        Map<String, Object> toolsConfig = new LinkedHashMap<>();
        toolsConfig.put("mode", "custom");
        toolsConfig.put("workflowsGrant", "custom");
        toolsConfig.put("workflows", List.of(WORKFLOW_ID.toString()));
        toolsConfig.put("tablesGrant", "none");

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, List.of(WORKFLOW_ID.toString()));
        Map<String, Object> agentData = (Map<String, Object>) snapshot.get("agent");
        Map<String, Object> persisted = (Map<String, Object>) agentData.get("toolsConfig");

        assertThat(persisted).containsEntry("workflowsGrant", "custom");
        assertThat(persisted).containsEntry("workflows", List.of(WORKFLOW_ID.toString()));
        assertThat(persisted).containsEntry("tablesGrant", "none");
    }

    // ====================================================================
    // Workflows family - driven by workflowsGrant
    // ====================================================================

    @Test
    @DisplayName("workflowsGrant=all is REFUSED by the snapshot builder and never enumerates tenant workflows (fail-closed defense-in-depth)")
    void workflowsGrantAllIsRefusedAndNeverEnumerates() {
        Map<String, Object> toolsConfig = Map.of("workflowsGrant", "all");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        PublicationValidationException refusal = unwrapValidation(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        assertThat(refusal.getMessage()).contains("workflows");
        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(orchestratorClient, never()).getWorkflowForPublication(any(), any(), any());
    }

    @Test
    @DisplayName("workflowsGrant=custom snapshots exactly the explicit workflows list (NOT all tenant workflows)")
    @SuppressWarnings("unchecked")
    void workflowsGrantCustomSnapshotsExplicitList() {
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()));
        stubWorkflow(WORKFLOW_ID);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(orchestratorClient).getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, ORG_ID);
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        assertThat(workflows).containsOnlyKeys(WORKFLOW_ID.toString());
    }

    @Test
    @DisplayName("workflowsGrant=none snapshots NO workflows and never lists tenant workflows")
    void workflowsGrantNoneSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "none",
                // even with a populated list, none must deny
                "workflows", List.of(WORKFLOW_ID.toString()));

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(orchestratorClient, never()).getWorkflowForPublication(any(), any(), any());
        assertThat(snapshot).doesNotContainKey("workflows");
    }

    @Test
    @DisplayName("absent workflowsGrant snapshots NO workflows (absent ≠ all)")
    void absentWorkflowsGrantSnapshotsNothing() {
        // catalogue mode=all present, but NO workflowsGrant → deny (no fallback to mode)
        Map<String, Object> toolsConfig = Map.of("mode", "all");

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        assertThat(snapshot).doesNotContainKey("workflows");
    }

    @Test
    @DisplayName("MIXED config: catalogue mode=custom but workflowsGrant=all is still REFUSED (grant-driven, not mode-driven)")
    void mixedModeCustomButGrantAllIsRefused() {
        // The catalogue `mode` axis never influences the internal families: the refusal
        // keys on the grant alone, whatever the mode says.
        Map<String, Object> toolsConfig = Map.of(
                "mode", "custom",
                "workflowsGrant", "all");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        assertThat(unwrapValidation(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
    }

    @Test
    @DisplayName("MIXED config: catalogue mode=all but workflowsGrant=custom snapshots ONLY the listed workflow (not all publisher workflows / no over-share)")
    @SuppressWarnings("unchecked")
    void mixedModeAllButGrantCustomSnapshotsOnlyListed() {
        // Pre-fix mode-based code: mode='all' ⇒ isUnrestricted=true ⇒ would enumerate ALL
        // tenant workflows (over-share). Post-fix: workflowsGrant='custom' ⇒ only the list.
        Map<String, Object> toolsConfig = Map.of(
                "mode", "all",
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()));
        stubWorkflow(WORKFLOW_ID);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(orchestratorClient).getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, ORG_ID);
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        assertThat(workflows).containsOnlyKeys(WORKFLOW_ID.toString());
    }

    // ====================================================================
    // Applications family - driven by applicationsGrant (separate axis)
    // ====================================================================

    @Test
    @DisplayName("applicationsGrant=custom resolves the explicit applications list (publication→workflow)")
    @SuppressWarnings("unchecked")
    void applicationsGrantCustomResolvesPublicationList() {
        UUID pubId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID resolvedWfId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        Map<String, Object> toolsConfig = Map.of(
                "applicationsGrant", "custom",
                "applications", List.of(pubId.toString()));

        WorkflowPublicationEntity appPub = new WorkflowPublicationEntity();
        appPub.setWorkflowId(resolvedWfId);
        when(publicationRepository.findById(pubId)).thenReturn(Optional.of(appPub));
        stubWorkflow(resolvedWfId);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient).getWorkflowForPublication(resolvedWfId, TENANT_ID, ORG_ID);
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        // Keyed by the ORIGINAL publication id so remapping works on acquisition.
        assertThat(workflows).containsKey(pubId.toString());
    }

    @Test
    @DisplayName("applicationsGrant=all is REFUSED (the verbatim-persisted toolsConfig must never ship an 'all' grant)")
    void applicationsGrantAllIsRefused() {
        Map<String, Object> toolsConfig = Map.of(
                "applicationsGrant", "all",
                "applications", List.of(UUID.randomUUID().toString()));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        assertThat(unwrapValidation(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        verify(publicationRepository, never()).findById(any());
        verify(orchestratorClient, never()).getWorkflowForPublication(any(), any(), any());
    }

    @Test
    @DisplayName("applicationsGrant=none/absent snapshots no applications")
    void applicationsGrantNoneSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of(
                "applicationsGrant", "none",
                "applications", List.of(UUID.randomUUID().toString()));

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(publicationRepository, never()).findById(any());
        assertThat(snapshot).doesNotContainKey("workflows");
    }

    // ====================================================================
    // Interfaces family - driven by interfacesGrant (all vs none split)
    // ====================================================================

    @Test
    @DisplayName("interfacesGrant=all is REFUSED and never lists tenant interfaces")
    void interfacesGrantAllIsRefused() {
        Map<String, Object> toolsConfig = Map.of("interfacesGrant", "all");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        assertThat(unwrapValidation(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
    }

    @Test
    @DisplayName("interfacesGrant=none/absent never lists tenant interfaces")
    void interfacesGrantNoneNeverListsInterfaces() {
        Map<String, Object> toolsConfig = Map.of("interfacesGrant", "none");

        buildSnapshot(toolsConfig, null);

        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
    }

    @Test
    @DisplayName("absent interfacesGrant never lists tenant interfaces even with catalogue mode=all")
    void absentInterfacesGrantNeverListsInterfaces() {
        Map<String, Object> toolsConfig = Map.of("mode", "all");

        buildSnapshot(toolsConfig, null);

        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
    }

    // ====================================================================
    // Tables family - driven by tablesGrant (all vs none split)
    // ====================================================================

    @Test
    @DisplayName("tablesGrant=all is REFUSED and never lists tenant datasources")
    void tablesGrantAllIsRefused() {
        Map<String, Object> toolsConfig = Map.of("tablesGrant", "all");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        assertThat(unwrapValidation(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
    }

    @Test
    @DisplayName("tablesGrant=none/absent never lists tenant datasources")
    void tablesGrantNoneNeverListsDatasources() {
        Map<String, Object> toolsConfig = Map.of("tablesGrant", "none");

        buildSnapshot(toolsConfig, null);

        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
    }

    @Test
    @DisplayName("absent tablesGrant never lists tenant datasources even with catalogue mode=all")
    void absentTablesGrantNeverListsDatasources() {
        Map<String, Object> toolsConfig = Map.of("mode", "all");

        buildSnapshot(toolsConfig, null);

        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
    }

    // ====================================================================
    // Sub-agents family - driven by agentsGrant (all vs none split)
    // ====================================================================

    @Test
    @DisplayName("agentsGrant=all is REFUSED and never lists tenant agents")
    void agentsGrantAllIsRefused() {
        Map<String, Object> toolsConfig = Map.of("agentsGrant", "all");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> buildSnapshot(toolsConfig, null));

        assertThat(unwrapValidation(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        verify(agentClient, never()).getAgents(any(), any(), any());
    }

    @Test
    @DisplayName("agentsGrant=none/absent never lists tenant agents")
    void agentsGrantNoneNeverListsAgents() {
        Map<String, Object> toolsConfig = Map.of("agentsGrant", "none");

        buildSnapshot(toolsConfig, null);

        verify(agentClient, never()).getAgents(any(), any(), any());
    }

    @Test
    @DisplayName("absent agentsGrant never lists tenant agents even with catalogue mode=all")
    void absentAgentsGrantNeverListsAgents() {
        Map<String, Object> toolsConfig = Map.of("mode", "all");

        buildSnapshot(toolsConfig, null);

        verify(agentClient, never()).getAgents(any(), any(), any());
    }

    // ====================================================================
    // Interfaces family - custom-list population (was only all/none/absent covered)
    // ====================================================================

    @Test
    @DisplayName("interfacesGrant=custom snapshots EXACTLY the explicit interface ids and never lists all tenant interfaces")
    @SuppressWarnings("unchecked")
    void interfacesGrantCustomSnapshotsExplicitList() {
        UUID ifaceId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        Map<String, Object> toolsConfig = Map.of(
                "interfacesGrant", "custom",
                "interfaces", List.of(ifaceId.toString()));
        stubInterface(ifaceId);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // custom branch fetches each id individually; the all-tenant fetch is never called.
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        verify(interfaceClient).getInterface(ifaceId, TENANT_ID, ORG_ID);
        Map<String, Object> interfaces = (Map<String, Object>) snapshot.get("interfaces");
        assertThat(interfaces).containsOnlyKeys(ifaceId.toString());
    }

    // ====================================================================
    // Tables family - custom-list population (was only all/none/absent covered)
    // ====================================================================

    @Test
    @DisplayName("tablesGrant=custom snapshots EXACTLY the explicit datasource ids and never lists all tenant datasources")
    @SuppressWarnings("unchecked")
    void tablesGrantCustomSnapshotsExplicitList() {
        long tableId = 4242L;
        Map<String, Object> toolsConfig = Map.of(
                "tablesGrant", "custom",
                "tables", List.of(Long.toString(tableId)));
        stubDataSource(tableId);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // custom branch fetches each id individually; the all-tenant fetch is never called.
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
        verify(dataSourceClient).findByIdAndTenantId(tableId, TENANT_ID, ORG_ID);
        Map<String, Object> datasources = (Map<String, Object>) snapshot.get("datasources");
        assertThat(datasources).containsOnlyKeys(Long.toString(tableId));
    }

    // ====================================================================
    // Sub-agents family - custom-list population (was only all/none/absent covered)
    // ====================================================================

    @Test
    @DisplayName("agentsGrant=custom snapshots EXACTLY the explicit sub-agent ids and never lists all tenant agents")
    @SuppressWarnings("unchecked")
    void agentsGrantCustomSnapshotsExplicitList() {
        UUID subAgentId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        Map<String, Object> toolsConfig = Map.of(
                "agentsGrant", "custom",
                "agents", List.of(subAgentId.toString()));
        stubSubAgent(subAgentId);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // custom branch recurses into each id individually; the all-tenant fetch is never called.
        verify(agentClient, never()).getAgents(any(), any(), any());
        verify(agentClient).getAgent(subAgentId, TENANT_ID, ORG_ID);
        Map<String, Object> subAgents = (Map<String, Object>) snapshot.get("subAgents");
        assertThat(subAgents).containsOnlyKeys(subAgentId.toString());
    }

    // ====================================================================
    // Workflow-plan dedup-skip - a standalone interface/datasource id that is
    // already captured inside a snapshotted workflow plan is NOT double-fetched
    // ====================================================================

    @Test
    @DisplayName("custom interface/datasource ids already referenced inside a snapshotted workflow plan are NOT double-fetched as standalone (dedup-skip)")
    @SuppressWarnings("unchecked")
    void workflowPlanReferencedIdsAreNotDoubleFetchedAsStandalone() {
        UUID ifaceId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        long tableId = 7373L;

        // Agent granted: the workflow (custom) whose plan references interface X AND datasource Y,
        // AND interfaces=custom[X] AND tables=custom[Y]. The standalone loops must SKIP X/Y because
        // they are already captured inside the workflow plan (interfaceIdsInWorkflows.contains /
        // datasourceIdsInWorkflows.contains → continue).
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()),
                "interfacesGrant", "custom",
                "interfaces", List.of(ifaceId.toString()),
                "tablesGrant", "custom",
                "tables", List.of(Long.toString(tableId)));
        // Workflow plan references interface X (plan.interfaces[].id) and datasource Y (plan.tables[].dataSourceId).
        stubWorkflowReferencing(WORKFLOW_ID, ifaceId.toString(), Long.toString(tableId));

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // The workflow itself IS snapshotted (so X/Y ARE captured - via the workflow plan).
        verify(orchestratorClient).getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, ORG_ID);
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        assertThat(workflows).containsOnlyKeys(WORKFLOW_ID.toString());

        // Dedup-skip observable #1: the standalone per-id fetches are NEVER issued for the
        // already-captured ids (no double-fetch).
        verify(interfaceClient, never()).getInterface(eq(ifaceId), any(), any());
        verify(dataSourceClient, never()).findByIdAndTenantId(eq(tableId), any(), any());
        // …and the all-tenant fetches are never used either (custom grant, not all).
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());

        // Dedup-skip observable #2: X/Y do NOT appear as STANDALONE entries (the standalone
        // maps stay empty → the keys are absent from the snapshot root).
        assertThat(snapshot).doesNotContainKey("interfaces");
        assertThat(snapshot).doesNotContainKey("datasources");
    }

    @Test
    @DisplayName("a custom interface id NOT referenced by the workflow plan IS still fetched as standalone (dedup-skip targets only plan-captured ids)")
    @SuppressWarnings("unchecked")
    void unreferencedCustomInterfaceIsStillFetchedAsStandalone() {
        UUID referencedIface = UUID.fromString("88888888-8888-4888-8888-888888888888");
        UUID standaloneIface = UUID.fromString("99999999-9999-4999-8999-999999999999");

        // Plan references referencedIface; the agent also grants a SECOND interface that the
        // plan does NOT reference - only the referenced one is deduped, the other is fetched.
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()),
                "interfacesGrant", "custom",
                "interfaces", List.of(referencedIface.toString(), standaloneIface.toString()));
        stubWorkflowReferencing(WORKFLOW_ID, referencedIface.toString(), null);
        stubInterface(standaloneIface);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // Referenced one: deduped (never fetched standalone).
        verify(interfaceClient, never()).getInterface(eq(referencedIface), any(), any());
        // Unreferenced one: fetched standalone and present under the standalone map.
        verify(interfaceClient).getInterface(standaloneIface, TENANT_ID, ORG_ID);
        Map<String, Object> interfaces = (Map<String, Object>) snapshot.get("interfaces");
        assertThat(interfaces).containsOnlyKeys(standaloneIface.toString());
    }

    // ====================================================================
    // Custom grant with an EMPTY (or absent) id list - deny at the snapshot
    // level: the family map is empty and NO fetch happens (neither per-id nor
    // all-tenant). Pins the deny-side of custom-with-no-ids.
    // ====================================================================

    @Test
    @DisplayName("interfacesGrant=custom with an EMPTY list snapshots NO interfaces and never fetches (neither per-id nor all-tenant)")
    void interfacesGrantCustomEmptyListSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of(
                "interfacesGrant", "custom",
                "interfaces", List.of());

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(interfaceClient, never()).getInterface(any(), any(), any());
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        assertThat(snapshot).doesNotContainKey("interfaces");
    }

    @Test
    @DisplayName("interfacesGrant=custom with an ABSENT list snapshots NO interfaces and never fetches")
    void interfacesGrantCustomAbsentListSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of("interfacesGrant", "custom");

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(interfaceClient, never()).getInterface(any(), any(), any());
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        assertThat(snapshot).doesNotContainKey("interfaces");
    }

    @Test
    @DisplayName("tablesGrant=custom with an EMPTY list snapshots NO datasources and never fetches (neither per-id nor all-tenant)")
    void tablesGrantCustomEmptyListSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of(
                "tablesGrant", "custom",
                "tables", List.of());

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(dataSourceClient, never()).findByIdAndTenantId(any(Long.class), any(), any());
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
        assertThat(snapshot).doesNotContainKey("datasources");
    }

    @Test
    @DisplayName("agentsGrant=custom with an EMPTY list snapshots NO sub-agents and never fetches (neither per-id nor all-tenant)")
    void agentsGrantCustomEmptyListSnapshotsNothing() {
        Map<String, Object> toolsConfig = Map.of(
                "agentsGrant", "custom",
                "agents", List.of());

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // No all-tenant enumeration, and getAgent is called EXACTLY once - only for the root
        // subject itself, never for a (non-existent) sub-agent id.
        verify(agentClient, never()).getAgents(any(), any(), any());
        verify(agentClient, times(1)).getAgent(any(UUID.class), eq(TENANT_ID), eq(ORG_ID));
        verify(agentClient).getAgent(AGENT_ID, TENANT_ID, ORG_ID);
        assertThat(snapshot).doesNotContainKey("subAgents");
    }

    // ====================================================================
    // Mixed grants on a single agent - all three families resolve independently
    // ====================================================================

    @Test
    @DisplayName("MIXED grants on one agent: workflowsGrant=custom + tablesGrant=custom + interfacesGrant=none all resolve independently in the same snapshot")
    @SuppressWarnings("unchecked")
    void mixedPerFamilyGrantsResolveIndependentlyInSameSnapshot() {
        long tableId = 909L;
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()),
                "tablesGrant", "custom",
                "tables", List.of(Long.toString(tableId)),
                "interfacesGrant", "none");

        stubWorkflow(WORKFLOW_ID);
        // tables=custom → only the listed datasource
        stubDataSource(tableId);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        // Workflows: explicit list only - never the all-tenant enumeration.
        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        assertThat(workflows).containsOnlyKeys(WORKFLOW_ID.toString());

        // Tables: custom list only - never the all-tenant fetch.
        verify(dataSourceClient, never()).getDataSources(any(), any(), any());
        verify(dataSourceClient).findByIdAndTenantId(tableId, TENANT_ID, ORG_ID);
        Map<String, Object> datasources = (Map<String, Object>) snapshot.get("datasources");
        assertThat(datasources).containsOnlyKeys(Long.toString(tableId));

        // Interfaces: none - neither all-tenant nor per-id fetch, key absent.
        verify(interfaceClient, never()).listInterfaces(any(), any(), any());
        verify(interfaceClient, never()).getInterface(any(), any(), any());
        assertThat(snapshot).doesNotContainKey("interfaces");
    }

    // ====================================================================
    // Snapshot → clone grant-fidelity seam
    // ====================================================================

    @Test
    @DisplayName("custom-grant snapshot's persisted toolsConfig still reads workflowsGrant=custom through the shared AgentModuleResolver grant seam (the clone/runtime read path)")
    @SuppressWarnings("unchecked")
    void customGrantSnapshotIsStillAccessibleThroughSharedGrantSeam() {
        // This is the snapshot→clone grant-fidelity seam: the acquirer/runtime never
        // re-derives access from `mode`; it reads each family's grant via the SHARED
        // AgentModuleResolver.isResourceAccessible. If the persist step stripped the
        // toolsConfig (the old coercion), this read would return false and the clone
        // would silently lose workflow access. grant=all never reaches this seam any
        // more: it is refused at publish (see the refusal tests above).
        Map<String, Object> toolsConfig = new LinkedHashMap<>();
        toolsConfig.put("mode", "all");
        toolsConfig.put("workflowsGrant", "custom");
        toolsConfig.put("workflows", List.of(WORKFLOW_ID.toString()));
        stubWorkflow(WORKFLOW_ID);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);
        Map<String, Object> agentData = (Map<String, Object>) snapshot.get("agent");
        Map<String, Object> persisted = (Map<String, Object>) agentData.get("toolsConfig");

        // Read the persisted snapshot's grant exactly as the clone/runtime would.
        assertThat(com.apimarketplace.agent.config.AgentModuleResolver
                .isResourceAccessible(persisted, "workflows")).isTrue();
        // Sanity: a family with no grant in this config must still read as deny (absent ≠ all).
        assertThat(com.apimarketplace.agent.config.AgentModuleResolver
                .isResourceAccessible(persisted, "interfaces")).isFalse();
    }

    // ====================================================================
    // Landing interface scope (retained from prior coverage)
    // ====================================================================

    @Test
    @DisplayName("agent publication snapshots landing interface through active organization scope")
    void agentPublicationSnapshotsLandingInterfaceThroughActiveOrganizationScope() {
        UUID landingInterfaceId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Map<String, Object> landingSnapshot = Map.of(
                "interfaceId", landingInterfaceId.toString(),
                "name", "Org Landing");

        AgentDto agent = orgAgent(Map.of("mode", "all"));
        agent.setDescription("Org-scoped agent");

        when(landingInterfaceSnapshotter.parseInterfaceId(landingInterfaceId.toString()))
                .thenReturn(landingInterfaceId);
        when(agentClient.getAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(agent);
        when(publicationRepository.findByAgentConfigId(AGENT_ID)).thenReturn(Optional.empty());
        when(authClient.getPublisherProfile(TENANT_ID))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Publisher", "publisher@example.test", null));
        when(agentClient.getSkillsForAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(List.of());
        when(landingInterfaceSnapshotter.buildSnapshot(landingInterfaceId, TENANT_ID, ORG_ID))
                .thenReturn(landingSnapshot);
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AgentPublicationService service = newService();
        WorkflowPublicationEntity[] published = new WorkflowPublicationEntity[1];

        TenantResolver.runWithOrgScope(ORG_ID, () -> published[0] = service.publishAgent(
                publishAgentRequest(landingInterfaceId),
                TENANT_ID,
                ORG_ID));

        verify(landingInterfaceSnapshotter).buildSnapshot(landingInterfaceId, TENANT_ID, ORG_ID);
        verify(landingInterfaceSnapshotter, never()).buildSnapshot(landingInterfaceId, TENANT_ID);
        assertThat(published[0].getOwnerType()).isEqualTo(WorkflowPublicationEntity.OwnerType.ORG);
        assertThat(published[0].getOwnerId()).isEqualTo(ORG_ID);
        assertThat(published[0].getAgentSnapshot()).containsEntry("landingInterface", landingSnapshot);
    }

    @Test
    @DisplayName("agent publishes WITHOUT a landing interface: no snapshot, null showcase id (regression: interfaceId used to be mandatory)")
    void agentPublishesWithoutLandingInterface() {
        AgentDto agent = orgAgent(Map.of("mode", "all"));

        when(agentClient.getAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(agent);
        when(publicationRepository.findByAgentConfigId(AGENT_ID)).thenReturn(Optional.empty());
        when(authClient.getPublisherProfile(TENANT_ID))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Publisher", "publisher@example.test", null));
        when(agentClient.getSkillsForAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(List.of());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentConfigId", AGENT_ID.toString());
        request.put("title", "Agent");
        request.put("visibility", "PUBLIC");

        AgentPublicationService service = newService();
        WorkflowPublicationEntity[] published = new WorkflowPublicationEntity[1];
        TenantResolver.runWithOrgScope(ORG_ID, () ->
                published[0] = service.publishAgent(request, TENANT_ID, ORG_ID));

        assertThat(published[0].getShowcaseInterfaceId()).isNull();
        assertThat(published[0].getAgentSnapshot()).doesNotContainKey("landingInterface");
        verify(landingInterfaceSnapshotter, never()).buildSnapshot(any(UUID.class), any(), any());
    }

    @Test
    @DisplayName("org agent with workflowsGrant=custom snapshots workflows through org-aware orchestrator calls")
    @SuppressWarnings("unchecked")
    void orgAgentSnapshotsWorkflowsThroughOrgAwareOrchestratorCalls() {
        Map<String, Object> toolsConfig = Map.of(
                "workflowsGrant", "custom",
                "workflows", List.of(WORKFLOW_ID.toString()));
        stubWorkflow(WORKFLOW_ID);

        Map<String, Object> snapshot = buildSnapshot(toolsConfig, null);

        verify(orchestratorClient, never()).getWorkflowIdsByTenant(any(), any());
        verify(orchestratorClient).getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, ORG_ID);
        verify(workflowPublicationService).enrichWorkflowPlan(anyMap(), eq(TENANT_ID), eq(ORG_ID), eq(WORKFLOW_ID));
        Map<String, Object> workflows = (Map<String, Object>) snapshot.get("workflows");
        assertThat(workflows).containsKey(WORKFLOW_ID.toString());
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Unwrap the reflective-call wrapper (RuntimeException → InvocationTargetException)
     * down to the expected {@link PublicationValidationException}. Fails the test with
     * the original throwable if none is found in the cause chain.
     */
    private static PublicationValidationException unwrapValidation(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof PublicationValidationException)) {
            cause = cause.getCause();
        }
        assertThat(cause)
                .as("expected a PublicationValidationException in the cause chain of: " + thrown)
                .isInstanceOf(PublicationValidationException.class);
        return (PublicationValidationException) cause;
    }

    /**
     * Invoke buildAgentSnapshot reflectively for the given toolsConfig and return the
     * root agent snapshot. {@code allTenantWorkflowIds} is the canned response of
     * getWorkflowIdsByTenant when a test wants the "all" branch; pass null to leave it
     * unstubbed (the test stubs it itself, or the branch is never taken).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSnapshot(Map<String, Object> toolsConfig, List<String> allTenantWorkflowIds) {
        AgentDto agent = orgAgent(toolsConfig);
        when(agentClient.getAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(agent);
        when(agentClient.getSkillsForAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(List.of());
        if (allTenantWorkflowIds != null) {
            when(orchestratorClient.getWorkflowIdsByTenant(TENANT_ID, ORG_ID)).thenReturn(allTenantWorkflowIds);
        }

        AgentPublicationService service = newService();
        try {
            Method buildAgentSnapshot = AgentPublicationService.class.getDeclaredMethod(
                    "buildAgentSnapshot",
                    UUID.class, String.class, String.class, Set.class, Set.class, int.class);
            buildAgentSnapshot.setAccessible(true);
            return (Map<String, Object>) buildAgentSnapshot.invoke(
                    service, AGENT_ID, TENANT_ID, ORG_ID,
                    new HashSet<UUID>(), new HashSet<UUID>(), 0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Stub orchestrator to return a minimal valid workflow for the given id. */
    private void stubWorkflow(UUID workflowId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of());
        plan.put("interfaces", List.of());
        plan.put("cores", List.of());
        plan.put("edges", List.of());
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", "teammate");
        workflowData.put("organizationId", ORG_ID);
        workflowData.put("name", "Workflow " + workflowId);
        workflowData.put("description", "Shared workflow");
        workflowData.put("plan", plan);
        lenient().when(orchestratorClient.getWorkflowForPublication(workflowId, TENANT_ID, ORG_ID))
                .thenReturn(workflowData);
    }

    /**
     * Stub orchestrator to return a workflow whose plan REFERENCES the given standalone
     * interface id (via {@code plan.interfaces[].id}) and datasource id (via
     * {@code plan.tables[].dataSourceId}) - the exact shapes the dedup-skip reads in
     * collectInterfaceIdsFromWorkflows / collectDatasourceIdsFromWorkflows. Pass null to
     * omit either reference.
     */
    private void stubWorkflowReferencing(UUID workflowId, String interfaceId, String dataSourceId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of());
        plan.put("cores", List.of());
        plan.put("edges", List.of());
        if (interfaceId != null) {
            Map<String, Object> ifaceNode = new LinkedHashMap<>();
            ifaceNode.put("id", interfaceId);
            plan.put("interfaces", List.of(ifaceNode));
        } else {
            plan.put("interfaces", List.of());
        }
        if (dataSourceId != null) {
            Map<String, Object> tableNode = new LinkedHashMap<>();
            tableNode.put("dataSourceId", dataSourceId);
            plan.put("tables", List.of(tableNode));
        } else {
            plan.put("tables", List.of());
        }
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", "teammate");
        workflowData.put("organizationId", ORG_ID);
        workflowData.put("name", "Workflow " + workflowId);
        workflowData.put("description", "Shared workflow referencing standalone resources");
        workflowData.put("plan", plan);
        lenient().when(orchestratorClient.getWorkflowForPublication(workflowId, TENANT_ID, ORG_ID))
                .thenReturn(workflowData);
    }

    /** Stub interface-client to return a minimal valid standalone interface for the given id. */
    private void stubInterface(UUID interfaceId) {
        InterfaceDto iface = new InterfaceDto();
        iface.setId(interfaceId);
        iface.setName("Interface " + interfaceId);
        iface.setDescription("Shared interface");
        iface.setHtmlTemplate("<div></div>");
        iface.setInterfaceType("html");
        lenient().when(interfaceClient.getInterface(interfaceId, TENANT_ID, ORG_ID)).thenReturn(iface);
    }

    /** Stub datasource-client to return a minimal valid datasource (no items) for the given id. */
    private void stubDataSource(long dataSourceId) {
        DataSourceDto ds = new DataSourceDto(
                dataSourceId, "teammate", "DataSource " + dataSourceId, "Shared datasource",
                null, null, null, null, null, null, null, null, null, null, null, ORG_ID);
        lenient().when(dataSourceClient.findByIdAndTenantId(dataSourceId, TENANT_ID, ORG_ID)).thenReturn(ds);
        lenient().when(dataSourceClient.getAllItems(dataSourceId, TENANT_ID, ORG_ID)).thenReturn(List.of());
    }

    /** Stub agent-client so the recursive sub-agent snapshot resolves to a minimal leaf agent. */
    private void stubSubAgent(UUID subAgentId) {
        AgentDto sub = new AgentDto();
        sub.setId(subAgentId);
        sub.setTenantId("teammate");
        sub.setOrganizationId(ORG_ID);
        sub.setName("Sub-Agent " + subAgentId);
        // Empty toolsConfig → the leaf agent grants nothing, so recursion stops at this node.
        sub.setToolsConfig(new LinkedHashMap<>());
        lenient().when(agentClient.getAgent(subAgentId, TENANT_ID, ORG_ID)).thenReturn(sub);
        lenient().when(agentClient.getSkillsForAgent(subAgentId, TENANT_ID, ORG_ID)).thenReturn(List.of());
    }

    private static AgentDto orgAgent(Map<String, Object> toolsConfig) {
        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setTenantId("teammate");
        agent.setOrganizationId(ORG_ID);
        agent.setName("Agent");
        agent.setToolsConfig(toolsConfig);
        return agent;
    }

    private AgentPublicationService newService() {
        return new AgentPublicationService(
                publicationRepository,
                receiptRepository,
                agentClient,
                interfaceClient,
                dataSourceClient,
                orchestratorClient,
                breakdownService,
                snapshotCloneService,
                new ObjectMapper(),
                workflowPublicationService,
                entitlementGuard,
                fileCloneService,
                landingInterfaceSnapshotter,
                authClient);
    }

    private static Map<String, Object> publishAgentRequest(UUID landingInterfaceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentConfigId", AGENT_ID.toString());
        request.put("interfaceId", landingInterfaceId.toString());
        request.put("title", "Agent");
        request.put("description", "Org-scoped agent publication");
        request.put("visibility", "PUBLIC");
        return request;
    }
}
