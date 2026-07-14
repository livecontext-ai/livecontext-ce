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
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Publish-time guards of {@link AgentPublicationService#publishAgent}:
 * the grant=all refusal over the full agent closure (regression for the
 * "share an agent with All on every resource dumps the whole tenant" bug)
 * and the snapshot size caps (rows per table, total serialized bytes).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentPublicationService publish guards (grant=all refusal + size caps)")
class AgentPublicationServicePublishGuardsTest {

    private static final String TENANT_ID = "tenant-publisher";
    private static final String ORG_ID = "org-acme";
    private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SUB_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

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
    // grant=all refusal across the sub-agent closure
    // ====================================================================

    @Test
    @DisplayName("a sub-agent with tablesGrant=all is refused with root=false and the referencedVia chain naming its parent")
    @SuppressWarnings("unchecked")
    void subAgentAllGrantViolationCarriesReferencedViaChain() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        rootConfig.put("agentsGrant", "custom");
        rootConfig.put("agents", List.of(SUB_ID.toString()));
        stubRootAgent(rootConfig);

        AgentDto sub = new AgentDto();
        sub.setId(SUB_ID);
        sub.setName("Research Helper");
        sub.setToolsConfig(new LinkedHashMap<>(Map.of("tablesGrant", "all")));
        when(agentClient.getAgent(SUB_ID, TENANT_ID, ORG_ID)).thenReturn(sub);

        Throwable thrown = catchThrowable(() -> publish());

        PublicationValidationException refusal = assertRefusal(thrown);
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) refusal.getDetails().get("violations");
        assertThat(violations).hasSize(1);
        Map<String, Object> violation = violations.get(0);
        assertThat(violation).containsEntry("agentId", SUB_ID.toString());
        assertThat(violation).containsEntry("agentName", "Research Helper");
        assertThat(violation).containsEntry("root", false);
        assertThat((List<String>) violation.get("referencedVia")).containsExactly("Root Copilot");
        assertThat((List<String>) violation.get("families")).containsExactly("tables");
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("root AND sub-agent violations are AGGREGATED in one refusal (root listed first) so the publisher fixes everything in one pass")
    @SuppressWarnings("unchecked")
    void rootAndSubAgentViolationsAreAggregated() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        rootConfig.put("tablesGrant", "all");
        rootConfig.put("agentsGrant", "custom");
        rootConfig.put("agents", List.of(SUB_ID.toString()));
        stubRootAgent(rootConfig);

        AgentDto sub = new AgentDto();
        sub.setId(SUB_ID);
        sub.setName("Research Helper");
        sub.setToolsConfig(new LinkedHashMap<>(Map.of("interfacesGrant", "all")));
        when(agentClient.getAgent(SUB_ID, TENANT_ID, ORG_ID)).thenReturn(sub);

        Throwable thrown = catchThrowable(() -> publish());

        PublicationValidationException refusal = assertRefusal(thrown);
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) refusal.getDetails().get("violations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).containsEntry("agentId", ROOT_ID.toString());
        assertThat(violations.get(0)).containsEntry("root", true);
        assertThat((List<String>) violations.get(0).get("families")).containsExactly("tables");
        assertThat(violations.get(1)).containsEntry("agentId", SUB_ID.toString());
        assertThat((List<String>) violations.get(1).get("families")).containsExactly("interfaces");
    }

    @Test
    @DisplayName("all-custom/none grants publish successfully and persist the toolsConfig verbatim (grants survive into the snapshot)")
    @SuppressWarnings("unchecked")
    void customAndNoneGrantsPublishSuccessfully() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        rootConfig.put("mode", "all");
        rootConfig.put("workflowsGrant", "none");
        rootConfig.put("tablesGrant", "none");
        rootConfig.put("interfacesGrant", "none");
        rootConfig.put("agentsGrant", "none");
        rootConfig.put("applicationsGrant", "none");
        stubRootAgent(rootConfig);

        WorkflowPublicationEntity published = publish();

        assertThat(published).isNotNull();
        verify(publicationRepository).save(any(WorkflowPublicationEntity.class));
        Map<String, Object> agentData = (Map<String, Object>) published.getAgentSnapshot().get("agent");
        Map<String, Object> persisted = (Map<String, Object>) agentData.get("toolsConfig");
        assertThat(persisted).containsEntry("workflowsGrant", "none");
        assertThat(persisted).containsEntry("mode", "all");
    }

    // ====================================================================
    // Size caps
    // ====================================================================

    @Test
    @DisplayName("a table exceeding the per-table row cap refuses the publish with AGENT_SNAPSHOT_TOO_LARGE and a named breakdown entry")
    @SuppressWarnings("unchecked")
    void tableRowCapRefusesOversizedTable() {
        long tableId = 4242L;
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        rootConfig.put("tablesGrant", "custom");
        rootConfig.put("tables", List.of(Long.toString(tableId)));
        stubRootAgent(rootConfig);
        stubDataSource(tableId, 6);

        AgentPublicationService service = newService();
        service.agentSnapshotMaxTableRows = 5;

        Throwable thrown = catchThrowable(() -> publish(service));

        PublicationValidationException refusal = unwrap(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE);
        assertThat(refusal.getDetails()).containsEntry("maxTableRows", 5);
        List<Map<String, Object>> breakdown =
                (List<Map<String, Object>>) refusal.getDetails().get("breakdown");
        assertThat(breakdown).hasSize(1);
        assertThat(breakdown.get(0)).containsEntry("type", "datasource");
        assertThat(breakdown.get(0)).containsEntry("name", "DataSource " + tableId);
        assertThat(breakdown.get(0)).containsEntry("items", 6);
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a snapshot exceeding the total byte cap refuses the publish with sizeBytes/maxBytes and a heaviest-first breakdown")
    @SuppressWarnings("unchecked")
    void byteCapRefusesOversizedSnapshot() {
        UUID ifaceId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        rootConfig.put("interfacesGrant", "custom");
        rootConfig.put("interfaces", List.of(ifaceId.toString()));
        stubRootAgent(rootConfig);

        InterfaceDto iface = new InterfaceDto();
        iface.setId(ifaceId);
        iface.setName("Heavy Interface");
        iface.setHtmlTemplate("<div>" + "x".repeat(2000) + "</div>");
        iface.setInterfaceType("html");
        when(interfaceClient.getInterface(ifaceId, TENANT_ID, ORG_ID)).thenReturn(iface);

        AgentPublicationService service = newService();
        service.agentSnapshotMaxBytes = 100;

        Throwable thrown = catchThrowable(() -> publish(service));

        PublicationValidationException refusal = unwrap(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE);
        assertThat(((Number) refusal.getDetails().get("sizeBytes")).longValue()).isGreaterThan(100L);
        assertThat(((Number) refusal.getDetails().get("maxBytes")).longValue()).isEqualTo(100L);
        List<Map<String, Object>> breakdown =
                (List<Map<String, Object>>) refusal.getDetails().get("breakdown");
        assertThat(breakdown).isNotEmpty();
        assertThat(breakdown.get(0)).containsEntry("type", "interface");
        assertThat(breakdown.get(0)).containsEntry("name", "Heavy Interface");
        assertThat(((Number) breakdown.get(0).get("approxBytes")).longValue()).isGreaterThan(2000L);
        verify(publicationRepository, never()).save(any());
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private void stubRootAgent(Map<String, Object> toolsConfig) {
        AgentDto root = new AgentDto();
        root.setId(ROOT_ID);
        root.setTenantId("teammate");
        root.setOrganizationId(ORG_ID);
        root.setName("Root Copilot");
        root.setToolsConfig(toolsConfig);
        when(agentClient.getAgent(ROOT_ID, TENANT_ID, ORG_ID)).thenReturn(root);
        when(agentClient.getSkillsForAgent(any(UUID.class), any(), any())).thenReturn(List.of());
        when(publicationRepository.findByAgentConfigId(ROOT_ID)).thenReturn(Optional.empty());
        when(authClient.getPublisherProfile(TENANT_ID))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Publisher", "publisher@example.test", null));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubDataSource(long dataSourceId, int itemCount) {
        DataSourceDto ds = new DataSourceDto(
                dataSourceId, "teammate", "DataSource " + dataSourceId, "Shared datasource",
                null, null, null, null, null, null, null, null, null, null, null, ORG_ID);
        when(dataSourceClient.findByIdAndTenantId(dataSourceId, TENANT_ID, ORG_ID)).thenReturn(ds);
        List<DataSourceItemDto> items = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            items.add(new DataSourceItemDto((long) i, dataSourceId, TENANT_ID, Map.of("col", "v" + i), i, null));
        }
        when(dataSourceClient.getAllItems(dataSourceId, TENANT_ID, ORG_ID)).thenReturn(items);
    }

    private WorkflowPublicationEntity publish() {
        return publish(newService());
    }

    private WorkflowPublicationEntity publish(AgentPublicationService service) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentConfigId", ROOT_ID.toString());
        request.put("title", "Root Copilot");
        request.put("visibility", "PUBLIC");
        WorkflowPublicationEntity[] out = new WorkflowPublicationEntity[1];
        TenantResolver.runWithOrgScope(ORG_ID, () ->
                out[0] = service.publishAgent(request, TENANT_ID, ORG_ID));
        return out[0];
    }

    private PublicationValidationException assertRefusal(Throwable thrown) {
        PublicationValidationException refusal = unwrap(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE);
        return refusal;
    }

    private static PublicationValidationException unwrap(Throwable thrown) {
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof PublicationValidationException)) {
            cause = cause.getCause();
        }
        assertThat(cause)
                .as("expected a PublicationValidationException in the cause chain of: " + thrown)
                .isInstanceOf(PublicationValidationException.class);
        return (PublicationValidationException) cause;
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
}
