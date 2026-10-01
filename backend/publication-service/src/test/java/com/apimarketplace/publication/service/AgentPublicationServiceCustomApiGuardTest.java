package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.CatalogInternalClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SHARED agent publication (PUBLIC / UNLISTED) may not grant a tool of a custom API:
 * that API exists only in the publisher's tenant, so the acquired agent would carry
 * tools it can never call. A PRIVATE publication stays in the owner's account and is
 * deliberately exempt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Custom APIs cannot be shared (agent publication)")
class AgentPublicationServiceCustomApiGuardTest {

    private static final String TENANT_ID = "tenant-publisher";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final List<Map<String, Object>> CUSTOM_API_HIT = List.of(Map.of(
            "apiSlug", "my-private-api",
            "apiName", "My Private API",
            "toolIdentifiers", List.of("my-private-api:do-thing")));

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
    @Mock private CatalogInternalClient catalogInternalClient;

    @Test
    @DisplayName("PUBLIC agent granting a custom-API tool is refused with CUSTOM_API_NOT_PUBLISHABLE and nothing is saved")
    @SuppressWarnings("unchecked")
    void publicAgentWithCustomApiToolIsRefused() {
        stubAgentGranting("my-private-api:do-thing");
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        Throwable thrown = catchThrowable(() -> publish("PUBLIC"));

        PublicationValidationException refusal = unwrap(thrown);
        assertThat(refusal.getErrorCode())
                .isEqualTo(PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
        assertThat(refusal.getMessage()).contains("My Private API");
        assertThat((List<Map<String, Object>>) refusal.getDetails().get("customApis"))
                .isEqualTo(CUSTOM_API_HIT);
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("UNLISTED agent granting a custom-API tool is refused too")
    void unlistedAgentWithCustomApiToolIsRefused() {
        stubAgentGranting("my-private-api:do-thing");
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        Throwable thrown = catchThrowable(() -> publish("UNLISTED"));

        assertThat(unwrap(thrown).getErrorCode())
                .isEqualTo(PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
    }

    @Test
    @DisplayName("PRIVATE agent with the SAME grant publishes and never consults the catalog")
    void privateAgentWithCustomApiToolIsAllowed() {
        stubAgentGranting("my-private-api:do-thing");

        WorkflowPublicationEntity published = publish("PRIVATE");

        assertThat(published).isNotNull();
        verify(publicationRepository).save(any(WorkflowPublicationEntity.class));
        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("PUBLIC agent granting only shipped catalog tools publishes")
    void publicAgentWithCatalogToolsOnlyPublishes() {
        stubAgentGranting("github:get-user");
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());

        WorkflowPublicationEntity published = publish("PUBLIC");

        assertThat(published).isNotNull();
        verify(catalogInternalClient).findCustomApiRefs(any(), any(), any());
    }

    /**
     * The grant is stored as {@code apiSlug:toolSlug} (what the agent tool picker writes),
     * NOT as the {@code apiSlug/toolSlug} an mcp node uses. Regression guard: a lookup that
     * only understood the slash form saw no api slug here, matched nothing, and let every
     * colon-form grant through - the primary case this gate exists for.
     */
    @Test
    @DisplayName("the canonical colon-form grant reaches the catalog VERBATIM, with the publisher's scope")
    @SuppressWarnings("unchecked")
    void colonFormGrantIsForwardedVerbatim() {
        stubAgentGranting("my-private-api:do-thing");
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());

        publish("PUBLIC");

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(catalogInternalClient).findCustomApiRefs(captor.capture(), eq(TENANT_ID), eq(ORG_ID));
        assertThat(captor.getValue()).contains("my-private-api:do-thing");
    }

    // ==================== fixture ====================

    /** An agent whose only resource grant is the explicit catalog-tool list. */
    private void stubAgentGranting(String toolIdentifier) {
        Map<String, Object> toolsConfig = new LinkedHashMap<>();
        toolsConfig.put("mode", "custom");
        toolsConfig.put("tools", List.of(toolIdentifier));
        toolsConfig.put("workflowsGrant", "none");
        toolsConfig.put("tablesGrant", "none");
        toolsConfig.put("interfacesGrant", "none");
        toolsConfig.put("agentsGrant", "none");
        toolsConfig.put("applicationsGrant", "none");

        AgentDto agent = new AgentDto();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORG_ID);
        agent.setName("Root Copilot");
        agent.setToolsConfig(toolsConfig);
        when(agentClient.getAgent(AGENT_ID, TENANT_ID, ORG_ID)).thenReturn(agent);
        when(agentClient.getSkillsForAgent(any(UUID.class), any(), any())).thenReturn(List.of());
        when(publicationRepository.findByAgentConfigId(AGENT_ID)).thenReturn(Optional.empty());
        when(authClient.getPublisherProfile(TENANT_ID)).thenReturn(
                new PublisherProfileDto(TENANT_ID, "Publisher", "publisher@example.test", null, null));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private WorkflowPublicationEntity publish(String visibility) {
        AgentPublicationService service = newService();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentConfigId", AGENT_ID.toString());
        request.put("title", "Root Copilot");
        request.put("visibility", visibility);
        WorkflowPublicationEntity[] out = new WorkflowPublicationEntity[1];
        TenantResolver.runWithOrgScope(ORG_ID, () ->
                out[0] = service.publishAgent(request, TENANT_ID, ORG_ID));
        return out[0];
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
        AgentPublicationService service = new AgentPublicationService(
                publicationRepository, receiptRepository, agentClient, interfaceClient,
                dataSourceClient, orchestratorClient, breakdownService, snapshotCloneService,
                new ObjectMapper(), workflowPublicationService, entitlementGuard,
                fileCloneService, landingInterfaceSnapshotter, authClient);
        service.customApiPublishGuard = new CustomApiPublishGuard(catalogInternalClient);
        return service;
    }
}
