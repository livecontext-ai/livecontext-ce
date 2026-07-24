package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("V273 regression: updatePublicationInfo showcase epoch pin")
class WorkflowPublicationServiceShowcaseEpochClearTest {

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
    private static final UUID INTERFACE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String TENANT_ID = "tenant-001";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient);
        // Default AuthClient.getPublisherProfile stub for every test - the
        // (re)publish path snapshots publisher identity server-side via this
        // call, see WorkflowPublicationService line ~384. Lenient so tests
        // that never invoke publishWorkflow don't trip strict-stubbing.
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "test-avatar-uuid"));
    }

    @Test
    @DisplayName("clearShowcaseEpoch removes the stored epoch pin")
    void clearShowcaseEpochRemovesStoredEpochPin() {
        WorkflowPublicationEntity publication = publicationWithEpoch(3);
        stubUpdate(publication);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                null,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.WORKFLOW,
                null,
                true,
                true, Map.of());

        assertThat(updated.getShowcaseChosenEpoch()).isNull();
    }

    @Test
    @DisplayName("omitted showcaseEpoch preserves the stored epoch pin")
    void omittedShowcaseEpochPreservesStoredEpochPin() {
        WorkflowPublicationEntity publication = publicationWithEpoch(2);
        stubUpdate(publication);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                INTERFACE_ID,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.INTERFACE,
                null,
                false,
                true, Map.of());

        assertThat(updated.getShowcaseChosenEpoch()).isEqualTo(2);
    }

    @Test
    @DisplayName("non-null showcaseEpoch replaces the stored epoch pin")
    void nonNullShowcaseEpochReplacesStoredEpochPin() {
        WorkflowPublicationEntity publication = publicationWithEpoch(1);
        stubUpdate(publication);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                INTERFACE_ID,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.INTERFACE,
                5,
                false,
                true, Map.of());

        assertThat(updated.getShowcaseChosenEpoch()).isEqualTo(5);
    }

    @Test
    @DisplayName("same showcase run and interface recaptures snapshot when only the epoch pin changes")
    void sameShowcaseRunAndInterfaceRecapturesSnapshotWhenOnlyEpochPinChanges() {
        WorkflowPublicationEntity publication = publicationWithEpoch(2);
        publication.setDisplayMode(DisplayMode.INTERFACE);
        publication.setShowcaseRunId("run-same");
        publication.setShowcaseSnapshot(showcaseSnapshotWithEpochs(2));
        stubUpdate(publication);
        when(orchestratorClient.validateShowcaseRun("run-same", TENANT_ID, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        Map<String, Object> recaptured = showcaseSnapshotWithEpochs(4);
        when(orchestratorClient.captureShowcaseSnapshot("run-same", TENANT_ID, null, 4))
                .thenReturn(recaptured);

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                INTERFACE_ID,
                "run-same",
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.INTERFACE,
                4,
                false,
                true, Map.of());

        assertThat(updated.getShowcaseChosenEpoch()).isEqualTo(4);
        assertThat(updated.getShowcaseSnapshot()).isSameAs(recaptured);
        verify(orchestratorClient).captureShowcaseSnapshot("run-same", TENANT_ID, null, 4);
    }

    @Test
    @DisplayName("nonexistent showcaseEpoch is rejected instead of storing an arbitrary pin")
    void nonexistentShowcaseEpochIsRejected() {
        WorkflowPublicationEntity publication = publicationWithEpoch(1);
        stubUpdateWithoutSave(publication);

        assertThatThrownBy(() -> service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                INTERFACE_ID,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.INTERFACE,
                999,
                false,
                true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("negative showcaseEpoch is rejected")
    void negativeShowcaseEpochIsRejected() {
        WorkflowPublicationEntity publication = publicationWithEpoch(1);
        stubUpdateWithoutSave(publication);

        assertThatThrownBy(() -> service.updatePublicationInfo(
                PUBLICATION_ID,
                TENANT_ID,
                null,
                "Updated title",
                "Updated description",
                INTERFACE_ID,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                DisplayMode.INTERFACE,
                -1,
                false,
                true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or greater");
    }

    @Test
    @DisplayName("publishWorkflow preserves selectedCredentialId in the publisher APPLICATION plan while scrubbing the public snapshot")
    void publishWorkflowPreservesSelectedCredentialIdInPublisherApplicationPlan() {
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "mcp:echo");
        mcp.put("type", "mcp");
        mcp.put("label", "Echo");
        mcp.put("selectedCredentialId", 42);
        mcp.put("credentialSource", "user");

        Map<String, Object> workflowPlan = new HashMap<>();
        workflowPlan.put("triggers", List.of());
        workflowPlan.put("mcps", List.of(mcp));
        workflowPlan.put("interfaces", List.of());
        workflowPlan.put("cores", List.of());
        workflowPlan.put("edges", List.of());

        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", workflowPlan);
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(workflowData);
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity publication = invocation.getArgument(0);
                    if (publication.getId() == null) {
                        publication.setId(PUBLICATION_ID);
                    }
                    return publication;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.createApplicationWorkflow(any(), eq(TENANT_ID)))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));

        WorkflowPublicationEntity published = service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                null,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                null,
                DisplayMode.WORKFLOW,
                null,
                true, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> createCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).createApplicationWorkflow(createCaptor.capture(), eq(TENANT_ID));

        @SuppressWarnings("unchecked")
        Map<String, Object> applicationPlan = (Map<String, Object>) createCaptor.getValue().get("plan");
        @SuppressWarnings("unchecked")
        Map<String, Object> applicationMcp = ((List<Map<String, Object>>) applicationPlan.get("mcps")).get(0);
        assertThat(applicationMcp)
                .containsEntry("selectedCredentialId", 42)
                .containsEntry("credentialSource", "user");

        @SuppressWarnings("unchecked")
        Map<String, Object> publicMcp = ((List<Map<String, Object>>) published.getPlanSnapshot().get("mcps")).get(0);
        assertThat(publicMcp)
                .containsEntry("selectedCredentialId", "[redacted]")
                .doesNotContainKey("credentialSource");
    }

    @Test
    @DisplayName("publishWorkflow creates publisher APPLICATION when existing lookup returns no id")
    void publishWorkflowCreatesPublisherApplicationWhenExistingLookupReturnsNoId() {
        Map<String, Object> workflowPlan = new HashMap<>();
        workflowPlan.put("triggers", List.of());
        workflowPlan.put("interfaces", List.of());
        workflowPlan.put("cores", List.of());
        workflowPlan.put("edges", List.of());

        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", workflowPlan);
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(workflowData);
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity publication = invocation.getArgument(0);
                    if (publication.getId() == null) {
                        publication.setId(PUBLICATION_ID);
                    }
                    return publication;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.findBySourcePublication(PUBLICATION_ID, TENANT_ID))
                .thenReturn(Map.of("title", "stale application without id"));
        when(orchestratorClient.createApplicationWorkflow(any(), eq(TENANT_ID)))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));

        service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                null,
                null,
                null,
                0,
                PublicationVisibility.PRIVATE,
                null,
                DisplayMode.WORKFLOW,
                null,
                true, Map.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> createCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).createApplicationWorkflow(createCaptor.capture(), eq(TENANT_ID));
        assertThat(createCaptor.getValue())
                .containsEntry("sourcePublicationId", PUBLICATION_ID.toString());
        verify(orchestratorClient, never()).cleanupApplicationRuns(any(UUID.class), any(), any());
        verify(orchestratorClient, never()).refreshApplicationFromPublication(any(UUID.class), any(), any());
    }

    @Test
    @DisplayName("publishWorkflow rejects a showcaseEpoch missing from the captured snapshot")
    void publishWorkflowRejectsMissingCapturedShowcaseEpoch() {
        stubPublishWorkflow(filteredShowcaseSnapshotWithoutEpoch(999), 999);

        assertThatThrownBy(() -> service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                INTERFACE_ID,
                "run-publish",
                null,
                0,
                PublicationVisibility.PUBLIC,
                null,
                DisplayMode.INTERFACE,
                999,
                true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("publishWorkflow keeps the source showcaseEpoch when filtered snapshot keys are renumbered")
    void publishWorkflowAcceptsCapturedShowcaseEpoch() {
        stubPublishWorkflow(filteredShowcaseSnapshot(2), 2);

        WorkflowPublicationEntity published = service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                INTERFACE_ID,
                "run-publish",
                null,
                0,
                PublicationVisibility.PUBLIC,
                null,
                DisplayMode.INTERFACE,
                2,
                true, Map.of());

        assertThat(published.getShowcaseChosenEpoch()).isEqualTo(2);
    }

    @Test
    @DisplayName("publishWorkflow rejects an empty aggregated byEpoch key as a nonexistent showcase epoch")
    void publishWorkflowRejectsEmptyAggregatedEpochKey() {
        Map<String, Object> emptyEpochSnapshot = Map.of(
                "aggregatedSteps", Map.of("byEpoch", Map.of("999", List.of())),
                "epochStates", Map.of("999", Map.of(
                        "epoch", 999,
                        "nodes", Map.of(),
                        "edges", Map.of())));
        stubPublishWorkflow(emptyEpochSnapshot, 999);

        assertThatThrownBy(() -> service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                null,
                "run-publish",
                null,
                0,
                PublicationVisibility.PUBLIC,
                null,
                DisplayMode.WORKFLOW,
                999,
                true, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("publishWorkflow requests the source epoch but validates the renumbered snapshot epoch")
    void publishWorkflowRequestsSourceEpochButValidatesRenumberedSnapshotEpoch() {
        Map<String, Object> capturedSnapshot = filteredShowcaseSnapshot(2);
        stubPublishWorkflow(capturedSnapshot, 2);

        WorkflowPublicationEntity published = service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                null,
                "Published title",
                "Published description",
                INTERFACE_ID,
                "run-publish",
                null,
                0,
                PublicationVisibility.PUBLIC,
                null,
                DisplayMode.INTERFACE,
                2,
                true, Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> interfaceRenders = (Map<String, Object>) published.getShowcaseSnapshot().get("interfaceRenders");
        @SuppressWarnings("unchecked")
        Map<String, Object> interfaceEntry = (Map<String, Object>) interfaceRenders.get(INTERFACE_ID.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> byEpoch = (Map<String, Object>) interfaceEntry.get("byEpoch");

        assertThat(published.getShowcaseChosenEpoch()).isEqualTo(2);
        assertThat(published.getShowcaseSnapshot()).containsEntry("sourceEpoch", 2);
        assertThat(byEpoch.keySet()).containsExactly("1");
        verify(orchestratorClient).captureShowcaseSnapshot("run-publish", TENANT_ID, null, 2);
    }

    @Test
    @DisplayName("publishWorkflow passes organization scope and chosen epoch into showcase snapshot capture")
    void publishWorkflowPassesOrganizationScopeAndChosenEpochToSnapshotCapture() {
        String organizationId = "org-acme";
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", "workflow-owner");
        workflowData.put("organizationId", organizationId);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, organizationId))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-publish", TENANT_ID, organizationId))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity publication = invocation.getArgument(0);
                    if (publication.getId() == null) {
                        publication.setId(PUBLICATION_ID);
                    }
                    return publication;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-publish", TENANT_ID, organizationId, 4))
                .thenReturn(filteredShowcaseSnapshot(4));

        service.publishWorkflow(
                WORKFLOW_ID,
                TENANT_ID,
                organizationId,
                "Published title",
                "Published description",
                INTERFACE_ID,
                "run-publish",
                null,
                0,
                PublicationVisibility.PUBLIC,
                null,
                DisplayMode.INTERFACE,
                4,
                true, Map.of());

        verify(orchestratorClient).validateShowcaseRun("run-publish", TENANT_ID, organizationId);
        verify(orchestratorClient).captureShowcaseSnapshot("run-publish", TENANT_ID, organizationId, 4);
    }

    @Test
    @DisplayName("unpublish hides marketplace listing but preserves showcase snapshot and epoch for acquired users")
    void unpublishPreservesShowcaseSnapshotAndEpoch() {
        WorkflowPublicationEntity publication = publicationWithEpoch(3);
        Map<String, Object> snapshot = publication.getShowcaseSnapshot();
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setAverageRating(4.8);
        publication.setReviewCount(9);
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(publication));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowPublicationEntity unpublished = service.unpublishWorkflow(WORKFLOW_ID, TENANT_ID, null);

        assertThat(unpublished.getStatus()).isEqualTo(PublicationStatus.INACTIVE);
        assertThat(unpublished.getAverageRating()).isEqualTo(0.0);
        assertThat(unpublished.getReviewCount()).isZero();
        assertThat(unpublished.getShowcaseSnapshot()).isSameAs(snapshot);
        assertThat(unpublished.getShowcaseChosenEpoch()).isEqualTo(3);
        verify(reviewRepository).deleteByPublicationId(PUBLICATION_ID);
        verify(orchestratorClient).cleanupApplicationRuns(WORKFLOW_ID, PUBLICATION_ID.toString(), TENANT_ID);
        verify(publicationRepository).save(publication);
    }

    @Test
    @DisplayName("DataInput publish snapshot copies files from the publisher tenant into the publication namespace")
    void snapshotDataInputFilesUsesPublisherSourceTenant() {
        Map<String, Object> file = new HashMap<>();
        file.put("path", TENANT_ID + "/workflow/run/upload.txt");
        file.put("name", "upload.txt");
        file.put("mimeType", "text/plain");
        Map<String, Object> item = Map.of("type", "file", "file", file);
        Map<String, Object> core = Map.of(
                "id", "upload_step",
                "dataInput", Map.of("items", List.of(item)));
        Map<String, Object> plan = Map.of("cores", List.of(core));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + PUBLICATION_ID + "/upload.txt"));

        service.snapshotDataInputFiles(plan, PUBLICATION_ID, TENANT_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(orchestratorClient).copyFile(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue()).containsEntry("sourceTenantId", TENANT_ID);
        assertThat(requestCaptor.getValue()).containsEntry("tenantId", "_publications");
        assertThat(file).containsEntry("path", "_publications/" + PUBLICATION_ID + "/upload.txt");
    }

    @Test
    @DisplayName("sub-workflow enrichment forwards organization scope when fetching nested workflow plans")
    void subWorkflowEnrichmentForwardsOrganizationScope() {
        String organizationId = "org-acme";
        UUID subWorkflowId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of(Map.of(
                "type", "sub_workflow",
                "subWorkflow", Map.of("workflowId", subWorkflowId.toString()))));
        when(orchestratorClient.getWorkflowForPublication(subWorkflowId, TENANT_ID, organizationId))
                .thenReturn(Map.of(
                        "tenantId", "teammate",
                        "organizationId", organizationId,
                        "plan", new HashMap<String, Object>(),
                        "name", "Nested"));

        service.enrichPlanWithSubWorkflowData(plan, TENANT_ID, organizationId, WORKFLOW_ID);

        verify(orchestratorClient).getWorkflowForPublication(subWorkflowId, TENANT_ID, organizationId);
        @SuppressWarnings("unchecked")
        Map<String, Object> subflows = (Map<String, Object>) plan.get("_snapshot_subworkflows");
        assertThat(subflows).containsKey(subWorkflowId.toString());
    }

    @Test
    @DisplayName("sub-workflow enrichment skips out-of-scope nested workflow plans")
    void subWorkflowEnrichmentSkipsOutOfScopeNestedWorkflowPlans() {
        String organizationId = "org-acme";
        UUID subWorkflowId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of(Map.of(
                "type", "sub_workflow",
                "subWorkflow", Map.of("workflowId", subWorkflowId.toString()))));
        when(orchestratorClient.getWorkflowForPublication(subWorkflowId, TENANT_ID, organizationId))
                .thenReturn(Map.of(
                        "tenantId", "foreign-owner",
                        "organizationId", "org-foreign",
                        "plan", new HashMap<String, Object>(),
                        "name", "Foreign nested"));

        service.enrichPlanWithSubWorkflowData(plan, TENANT_ID, organizationId, WORKFLOW_ID);

        assertThat(plan).doesNotContainKey("_snapshot_subworkflows");
    }

    @Test
    @DisplayName("interface enrichment skips out-of-scope batch rows before snapshotting templates")
    void interfaceEnrichmentSkipsOutOfScopeBatchRowsBeforeSnapshottingTemplates() {
        String organizationId = "org-acme";
        Map<String, Object> interfaceNode = new HashMap<>();
        interfaceNode.put("id", INTERFACE_ID.toString());
        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", List.of(interfaceNode));

        InterfaceDto foreign = new InterfaceDto();
        foreign.setId(INTERFACE_ID);
        foreign.setTenantId("foreign-owner");
        foreign.setOrganizationId("org-foreign");
        foreign.setHtmlTemplate("<img src=\"secret.png\" />");
        foreign.setCssTemplate(".secret{}");
        foreign.setJsTemplate("window.secret = true");
        when(interfaceClient.getInterfacesByIds(List.of(INTERFACE_ID), TENANT_ID, organizationId))
                .thenReturn(List.of(foreign));

        service.enrichPlanWithInterfaceData(plan, TENANT_ID, organizationId, null);

        assertThat(interfaceNode)
                .containsEntry("_snapshot_htmlTemplate", null)
                .containsEntry("_snapshot_cssTemplate", null)
                .containsEntry("_snapshot_jsTemplate", null)
                .containsEntry("_snapshot_data", null);
    }

    @Test
    @DisplayName("publish writes the interface's format into the plan snapshot, under the key the clone reads")
    void interfaceEnrichmentWritesTheFormatUnderTheAgreedKey() {
        // Producer side of the format's publish -> acquire journey. The consumer
        // (SnapshotCloneService) is tested against a hand-written fixture, so without this the
        // two can drift apart silently: rename the key here and every acquired app loses its
        // shape with 1185 green tests and no error anywhere.
        String organizationId = "org-acme";
        Map<String, Object> interfaceNode = new HashMap<>();
        interfaceNode.put("id", INTERFACE_ID.toString());
        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", List.of(interfaceNode));

        InterfaceDto owned = new InterfaceDto();
        owned.setId(INTERFACE_ID);
        owned.setTenantId(TENANT_ID);
        owned.setOrganizationId(organizationId);
        owned.setHtmlTemplate("<div>story</div>");
        owned.setFormat("vertical");
        when(interfaceClient.getInterfacesByIds(List.of(INTERFACE_ID), TENANT_ID, organizationId))
                .thenReturn(List.of(owned));

        service.enrichPlanWithInterfaceData(plan, TENANT_ID, organizationId, null);

        // The literal key is the contract with SnapshotCloneService.cloneFromSnapshot and with
        // PlanSnapshotSanitizer's allowlist - assert it verbatim, not via a shared constant.
        assertThat(interfaceNode).containsEntry("_snapshot_format", "vertical");
    }

    @Test
    @DisplayName("an interface with no declared shape publishes a null format, never a default")
    void interfaceEnrichmentKeepsUnsetFormatNull() {
        // Null is a real value (full-page capture). Defaulting it at publish time would crop
        // every acquired copy of a tall page.
        String organizationId = "org-acme";
        Map<String, Object> interfaceNode = new HashMap<>();
        interfaceNode.put("id", INTERFACE_ID.toString());
        Map<String, Object> plan = new HashMap<>();
        plan.put("interfaces", List.of(interfaceNode));

        InterfaceDto owned = new InterfaceDto();
        owned.setId(INTERFACE_ID);
        owned.setTenantId(TENANT_ID);
        owned.setOrganizationId(organizationId);
        owned.setHtmlTemplate("<div>tall</div>");
        when(interfaceClient.getInterfacesByIds(List.of(INTERFACE_ID), TENANT_ID, organizationId))
                .thenReturn(List.of(owned));

        service.enrichPlanWithInterfaceData(plan, TENANT_ID, organizationId, null);

        assertThat(interfaceNode).containsEntry("_snapshot_format", null);
    }

    @Test
    @DisplayName("datasource enrichment skips out-of-scope batch rows before snapshotting items")
    void datasourceEnrichmentSkipsOutOfScopeBatchRowsBeforeSnapshottingItems() {
        String organizationId = "org-acme";
        Map<String, Object> tableNode = new HashMap<>();
        tableNode.put("dataSourceId", "42");
        Map<String, Object> plan = new HashMap<>();
        plan.put("tables", List.of(tableNode));

        DataSourceDto foreign = new DataSourceDto(
                42L,
                "foreign-owner",
                "Foreign table",
                "Foreign description",
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                "org-foreign");
        when(dataSourceClient.bulkFind(List.of(42L), TENANT_ID, organizationId))
                .thenReturn(List.of(foreign));

        service.enrichPlanWithDatasourceData(plan, TENANT_ID, organizationId);
        service.enrichPlanWithDatasourceItems(plan, TENANT_ID, organizationId);

        assertThat(tableNode)
                .doesNotContainKey("_snapshot_ds_name")
                .doesNotContainKey("_snapshot_ds_items");
        verify(dataSourceClient, never()).getAllItems(42L, TENANT_ID, organizationId);
    }

    @Test
    @DisplayName("agent enrichment forwards organization scope when snapshotting embedded agents")
    void agentEnrichmentForwardsOrganizationScopeWhenSnapshottingEmbeddedAgents() {
        String organizationId = "org-acme";
        UUID agentId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        Map<String, Object> agentNode = new HashMap<>();
        agentNode.put("agentConfigId", agentId.toString());
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", List.of(agentNode));

        AgentDto agent = new AgentDto();
        agent.setId(agentId);
        agent.setTenantId("teammate");
        agent.setOrganizationId(organizationId);
        agent.setName("Org agent");
        agent.setToolsConfig(Map.of("mode", "custom"));
        when(agentClient.bulkFind(List.of(agentId), TENANT_ID, organizationId))
                .thenReturn(List.of(agent));
        when(agentClient.getSkillsForAgent(agentId, TENANT_ID, organizationId)).thenReturn(List.of());

        service.enrichPlanWithAgentData(plan, TENANT_ID, organizationId);

        verify(agentClient).bulkFind(List.of(agentId), TENANT_ID, organizationId);
        verify(agentClient).getWebhookConfig(agentId, TENANT_ID, organizationId);
        verify(agentClient).getScheduleConfig(agentId, TENANT_ID, organizationId);
        verify(agentClient).getSkillsForAgent(agentId, TENANT_ID, organizationId);
        assertThat(agentNode).containsEntry("_snapshot_agent_name", "Org agent");
    }

    @Test
    @DisplayName("agent enrichment captures the agent's reasoningEffort into the snapshot (M3 publish-capture)")
    void agentEnrichmentCapturesReasoningEffort() {
        String organizationId = "org-acme";
        UUID agentId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        Map<String, Object> agentNode = new HashMap<>();
        agentNode.put("agentConfigId", agentId.toString());
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", List.of(agentNode));

        AgentDto agent = new AgentDto();
        agent.setId(agentId);
        agent.setTenantId("teammate");
        agent.setOrganizationId(organizationId);
        agent.setName("Tuned agent");
        agent.setReasoningEffort("high");
        when(agentClient.bulkFind(List.of(agentId), TENANT_ID, organizationId)).thenReturn(List.of(agent));
        when(agentClient.getSkillsForAgent(agentId, TENANT_ID, organizationId)).thenReturn(List.of());

        service.enrichPlanWithAgentData(plan, TENANT_ID, organizationId);

        assertThat(agentNode)
                .as("reasoningEffort must be captured at publish (from the AgentDto) so the acquired "
                  + "agent keeps the publisher's effort setting (M3)")
                .containsEntry("_snapshot_agent_reasoningEffort", "high");
    }

    private void stubUpdate(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubUpdateWithoutSave(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
    }

    private void stubPublishWorkflow(Map<String, Object> capturedSnapshot, Integer epochFilter) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));

        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-publish", TENANT_ID, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity publication = invocation.getArgument(0);
                    if (publication.getId() == null) {
                        publication.setId(PUBLICATION_ID);
                    }
                    return publication;
                });
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-publish", TENANT_ID, null, epochFilter))
                .thenReturn(capturedSnapshot);
    }

    private WorkflowPublicationEntity publicationWithEpoch(Integer epoch) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setPublisherId(TENANT_ID);
        publication.setOwnerType(OwnerType.USER);
        publication.setOwnerId(TENANT_ID);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PRIVATE);
        publication.setDisplayMode(DisplayMode.WORKFLOW);
        publication.setTitle("Original title");
        publication.setDescription("Original description");
        publication.setCreditsPerUse(0);
        publication.setShowcaseInterfaceId(INTERFACE_ID);
        publication.setShowcaseSnapshot(showcaseSnapshotWithEpochs(1, 2, 3, 5));
        publication.setShowcaseChosenEpoch(epoch);
        return publication;
    }

    private Map<String, Object> showcaseSnapshotWithEpochs(int... epochs) {
        Map<String, Object> byEpoch = new HashMap<>();
        Map<String, Object> epochStates = new HashMap<>();
        for (int epoch : epochs) {
            byEpoch.put(String.valueOf(epoch), Map.of("items", List.of(Map.of("epoch", epoch))));
            epochStates.put(String.valueOf(epoch), Map.of("epoch", epoch));
        }
        return Map.of(
                "interfaceRenders", Map.of(
                        INTERFACE_ID.toString(), Map.of("byEpoch", byEpoch)),
                "epochStates", epochStates);
    }

    private Map<String, Object> filteredShowcaseSnapshot(int sourceEpoch) {
        Map<String, Object> snapshot = new HashMap<>(showcaseSnapshotWithEpochs(1));
        snapshot.put("sourceEpoch", sourceEpoch);
        return snapshot;
    }

    private Map<String, Object> filteredShowcaseSnapshotWithoutEpoch(int sourceEpoch) {
        return Map.of(
                "sourceEpoch", sourceEpoch,
                "interfaceRenders", Map.of(INTERFACE_ID.toString(), Map.of("byEpoch", Map.of())),
                "epochStates", Map.of());
    }
}
