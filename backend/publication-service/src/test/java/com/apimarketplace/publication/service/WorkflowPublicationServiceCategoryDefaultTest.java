package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every workflow publication MUST carry a category - when a publish/update omits
 * one the service defaults to the seeded "automation" category, and a category
 * can no longer be cleared. (Supersedes the old D-2 "clear category" behavior:
 * the publish wizard's "No Category" option was removed.)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Category is mandatory: publish/update default to Automation, never clear")
class WorkflowPublicationServiceCategoryDefaultTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_ID = "tenant-001";

    // Seeded system-default category (V300), the fallback when a publish omits one.
    private static final UUID AUTOMATION_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOM_ID = UUID.fromString("a0000000-0000-4000-8000-000000000006"); // marketing

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient);
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "avatar-uuid"));
    }

    @Test
    @DisplayName("publishWorkflow with a null categoryId defaults to the Automation category")
    void publishWithNullCategoryDefaultsToAutomation() {
        stubPublish();
        when(orchestratorClient.getCategoryById(AUTOMATION_ID)).thenReturn(automationCategory());

        WorkflowPublicationEntity published = service.publishWorkflow(
                WORKFLOW_ID, TENANT_ID, null, "Title", "Description",
                null, null,
                null, // categoryId omitted
                0, PublicationVisibility.PRIVATE, null, DisplayMode.WORKFLOW, null, true, Map.of());

        verify(orchestratorClient).getCategoryById(AUTOMATION_ID);
        assertThat(published.getCategoryId()).isEqualTo(AUTOMATION_ID);
        assertThat(published.getCategorySlug()).isEqualTo("automation");
        assertThat(published.getCategoryName()).isEqualTo("Automation");
        assertThat(published.getCategoryIconSlug()).isEqualTo("zap");
        assertThat(published.getCategoryColor()).isEqualTo("#6366f1");
    }

    @Test
    @DisplayName("publishWorkflow with an explicit categoryId uses it (no default fallback)")
    void publishWithExplicitCategoryUsesIt() {
        stubPublish();
        when(orchestratorClient.getCategoryById(CUSTOM_ID)).thenReturn(Map.of(
                "slug", "marketing", "name", "Marketing", "iconSlug", "megaphone", "color", "#ec4899"));

        WorkflowPublicationEntity published = service.publishWorkflow(
                WORKFLOW_ID, TENANT_ID, null, "Title", "Description",
                null, null,
                CUSTOM_ID,
                0, PublicationVisibility.PRIVATE, null, DisplayMode.WORKFLOW, null, true, Map.of());

        verify(orchestratorClient).getCategoryById(CUSTOM_ID);
        verify(orchestratorClient, never()).getCategoryById(AUTOMATION_ID);
        assertThat(published.getCategorySlug()).isEqualTo("marketing");
    }

    @Test
    @DisplayName("updatePublicationInfo with a null categoryId KEEPS the existing category (no clear, no resolve)")
    void updateWithNullCategoryKeepsExisting() {
        WorkflowPublicationEntity publication = categorizedPublication(CUSTOM_ID, "marketing", "Marketing", "megaphone", "#ec4899");
        stubUpdate(publication);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID, TENANT_ID, null, "New title", "New description",
                null, null,
                null, // categoryId omitted on update
                0, PublicationVisibility.PRIVATE, DisplayMode.WORKFLOW, null, true, true, Map.of());

        // Existing category preserved; the default is NOT applied and nothing is cleared.
        assertThat(updated.getCategoryId()).isEqualTo(CUSTOM_ID);
        assertThat(updated.getCategorySlug()).isEqualTo("marketing");
        verify(orchestratorClient, never()).getCategoryById(any());
    }

    @Test
    @DisplayName("updatePublicationInfo with a null categoryId never clears the category (no resolve)")
    void updateWithNullCategoryDoesNotClear() {
        // A previously-uncategorized publication stays uncategorized on a category-less
        // update - the update path must not resolve or null the category fields. The
        // "always categorized" guarantee is enforced at publish time, not here.
        WorkflowPublicationEntity publication = categorizedPublication(null, null, null, null, null);
        stubUpdate(publication);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID, TENANT_ID, null, "New title", "New description",
                null, null,
                null,
                0, PublicationVisibility.PRIVATE, DisplayMode.WORKFLOW, null, true, true, Map.of());

        assertThat(updated.getCategoryId()).isNull();
        verify(orchestratorClient, never()).getCategoryById(any());
    }

    // ---- helpers ----

    private Map<String, Object> automationCategory() {
        return Map.of("slug", "automation", "name", "Automation", "iconSlug", "zap", "color", "#6366f1");
    }

    private WorkflowPublicationEntity categorizedPublication(UUID catId, String slug, String name, String icon, String color) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(PUBLICATION_ID);
        p.setWorkflowId(WORKFLOW_ID);
        p.setPublisherId(TENANT_ID);
        p.setOwnerType(OwnerType.USER);
        p.setOwnerId(TENANT_ID);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setVisibility(PublicationVisibility.PRIVATE);
        p.setDisplayMode(DisplayMode.WORKFLOW);
        p.setTitle("Original title");
        p.setDescription("Original description");
        p.setCreditsPerUse(0);
        p.setCategoryId(catId);
        p.setCategorySlug(slug);
        p.setCategoryName(name);
        p.setCategoryIconSlug(icon);
        p.setCategoryColor(color);
        return p;
    }

    private void stubUpdate(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubPublish() {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(), "interfaces", List.of(), "cores", List.of(), "edges", List.of())));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null)).thenReturn(workflowData);
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity p = invocation.getArgument(0);
                    if (p.getId() == null) p.setId(PUBLICATION_ID);
                    return p;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.createApplicationWorkflow(any(), eq(TENANT_ID)))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
    }
}
