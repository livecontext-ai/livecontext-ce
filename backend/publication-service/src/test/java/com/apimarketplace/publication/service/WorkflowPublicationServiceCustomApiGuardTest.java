package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.CatalogInternalClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A SHARED workflow publication (PUBLIC / UNLISTED) may not be built on a custom API:
 * the API lives only in the publisher's tenant, so the acquirer's nodes could never run.
 * A PRIVATE publication is the publisher's own deployment and stays allowed.
 *
 * <p>Covers the wiring of {@link CustomApiPublishGuard} into both snapshot paths
 * ({@code publishWorkflow} and {@code updatePublicationInfo}), and proves the refusal
 * happens BEFORE anything is persisted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Custom APIs cannot be shared (workflow publication)")
class WorkflowPublicationServiceCustomApiGuardTest {

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
    @Mock private CatalogInternalClient catalogInternalClient;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CATEGORY_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final String TENANT_ID = "tenant-001";
    private static final String SHOWCASE_RUN_ID = "run_public_1";

    /** What catalog-service answers when the plan's tool belongs to a custom API. */
    private static final List<Map<String, Object>> CUSTOM_API_HIT = List.of(Map.of(
            "apiSlug", "my-private-api",
            "apiName", "My Private API",
            "toolIdentifiers", List.of("my-private-api/do-thing")));

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient,
                new PublicationFileUrlResolver(
                        new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner(
                                "test-secret-32-bytes-long-enough-for-hmac")));
        service.customApiPublishGuard = new CustomApiPublishGuard(catalogInternalClient);

        lenient().when(authClient.getPublisherProfile(any())).thenReturn(
                new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "avatar-uuid", null));
        lenient().when(orchestratorClient.getCategoryById(CATEGORY_ID)).thenReturn(Map.of(
                "slug", "automation", "name", "Automation", "iconSlug", "zap", "color", "#6366f1"));
    }

    /** A plan whose single mcp node runs a tool of the publisher's own custom API. */
    private static Map<String, Object> planUsingCustomApi() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(new HashMap<>(Map.of("type", "manual", "label", "Start")))));
        plan.put("mcps", new ArrayList<>(List.of(
                new HashMap<>(Map.of("id", "my-private-api/do-thing", "label", "Call it")))));
        plan.put("cores", new ArrayList<>());
        plan.put("interfaces", new ArrayList<>());
        plan.put("edges", new ArrayList<>());
        return plan;
    }

    private void stubWorkflowWithPlan(Map<String, Object> plan) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT_ID);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", plan);
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null)).thenReturn(workflowData);
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity p = invocation.getArgument(0);
                    if (p.getId() == null) p.setId(PUBLICATION_ID);
                    return p;
                });
        when(snapshotVersionRepository.getMaxVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(1);
        when(orchestratorClient.createApplicationWorkflow(any(), eq(TENANT_ID)))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
    }

    /** A publishable showcase run, required for every PUBLIC / UNLISTED publication. */
    private void stubPublishableShowcaseRun() {
        when(orchestratorClient.validateShowcaseRun(eq(SHOWCASE_RUN_ID), eq(TENANT_ID), any()))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(orchestratorClient.captureShowcaseSnapshot(eq(SHOWCASE_RUN_ID), eq(TENANT_ID), any(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> snapshot = new HashMap<>();
                    snapshot.put("runState", new HashMap<>());
                    return snapshot;
                });
    }

    private WorkflowPublicationEntity publish(PublicationVisibility visibility, String showcaseRunId) {
        return service.publishWorkflow(
                WORKFLOW_ID, TENANT_ID, null, "Title", "Description",
                null, showcaseRunId, CATEGORY_ID, 0, visibility, null,
                DisplayMode.WORKFLOW, null, true, Map.of(), null);
    }

    // ==================== publish ====================

    @Test
    @DisplayName("PUBLIC publish of a plan using a custom API is refused with the CUSTOM_API_NOT_PUBLISHABLE code")
    void publicPublishIsRefused() {
        stubWorkflowWithPlan(planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        assertThatThrownBy(() -> publish(PublicationVisibility.PUBLIC, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class)
                .hasMessageContaining("My Private API")
                .extracting(e -> ((PublicationValidationException) e).getErrorCode())
                .isEqualTo(PublicationValidationException.CUSTOM_API_NOT_PUBLISHABLE);
    }

    @Test
    @DisplayName("the refusal happens before anything is persisted or captured")
    void refusalLeavesNoState() {
        stubWorkflowWithPlan(planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        assertThatThrownBy(() -> publish(PublicationVisibility.PUBLIC, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class);

        verify(publicationRepository, never()).save(any());
        verify(orchestratorClient, never()).captureShowcaseSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("UNLISTED publish is refused too - a link still hands the app to another account")
    void unlistedPublishIsRefused() {
        stubWorkflowWithPlan(planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        assertThatThrownBy(() -> publish(PublicationVisibility.UNLISTED, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class);
    }

    @Test
    @DisplayName("PRIVATE publish of the SAME plan succeeds and never consults the catalog")
    void privatePublishIsAllowed() {
        stubWorkflowWithPlan(planUsingCustomApi());

        WorkflowPublicationEntity published = publish(PublicationVisibility.PRIVATE, null);

        assertThat(published.getVisibility()).isEqualTo(PublicationVisibility.PRIVATE);
        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("PUBLIC publish proceeds when every tool is a shipped catalog integration")
    void publicPublishWithCatalogToolsOnlyProceeds() {
        stubWorkflowWithPlan(planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(List.of());

        WorkflowPublicationEntity published = publish(PublicationVisibility.PUBLIC, SHOWCASE_RUN_ID);

        assertThat(published.getVisibility()).isEqualTo(PublicationVisibility.PUBLIC);
        // Twice on purpose: once on the raw plan (fails fast before any file is copied),
        // once on the enriched plan (agent tool grants and sub-workflow plans only exist
        // after enrichment). Both must be consulted for the gate to be complete.
        verify(catalogInternalClient, times(2)).findCustomApiRefs(any(), any(), any());
    }

    // ==================== snapshot size budget ====================

    @Test
    @DisplayName("regression (budget): a workflow whose table is over the row limit is refused, named, before anything is saved")
    void workflowWithATableOverTheRowLimitIsRefused() {
        Map<String, Object> plan = planUsingCustomApi();
        plan.put("mcps", new ArrayList<>());
        plan.put("tables", new ArrayList<>(List.of(new HashMap<>(Map.of("dataSourceId", 9, "label", "Find orders")))));
        stubWorkflowWithPlan(plan);
        when(dataSourceClient.bulkFind(any(), eq(TENANT_ID), any())).thenReturn(List.of(new DataSourceDto(
                9L, TENANT_ID, "Orders", "Order book", null, null, null, null, null, null, null, null, null,
                null, null, null)));
        List<DataSourceItemDto> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(new DataSourceItemDto((long) i, 9L, TENANT_ID, Map.of("n", i), 0, null));
        }
        when(dataSourceClient.copyAllItems(eq(9L), eq(TENANT_ID), any())).thenReturn(rows);
        service.snapshotBudget = new PublicationSnapshotBudget(new ObjectMapper(),
                PublicationSnapshotBudget.DEFAULT_MAX_BYTES, 3);

        assertThatThrownBy(() -> publish(PublicationVisibility.PRIVATE, null))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE);
                    assertThat(e.getMessage()).contains("'Orders'").contains("4 rows").contains("max 3");
                });
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("regression (silent empty publish): a workflow whose table copy fails is refused (retryable), not published empty")
    void workflowWhoseTableCopyFailsIsRefused() {
        Map<String, Object> plan = planUsingCustomApi();
        plan.put("mcps", new ArrayList<>());
        plan.put("tables", new ArrayList<>(List.of(new HashMap<>(Map.of("dataSourceId", 9, "label", "Find orders")))));
        stubWorkflowWithPlan(plan);
        when(dataSourceClient.bulkFind(any(), eq(TENANT_ID), any())).thenReturn(List.of(new DataSourceDto(
                9L, TENANT_ID, "Orders", "Order book", null, null, null, null, null, null, null, null, null,
                null, null, null)));
        when(dataSourceClient.copyAllItems(eq(9L), eq(TENANT_ID), any()))
                .thenThrow(new com.apimarketplace.datasource.client.TableCopyException(9L, "down", null));

        assertThatThrownBy(() -> publish(PublicationVisibility.PRIVATE, null))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.TABLE_COPY_FAILED);
                    assertThat(e.getMessage()).contains("'Orders' (id 9)");
                });
        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a workflow whose table is exactly at the row limit publishes")
    void workflowWithATableAtTheRowLimitPublishes() {
        Map<String, Object> plan = planUsingCustomApi();
        plan.put("mcps", new ArrayList<>());
        plan.put("tables", new ArrayList<>(List.of(new HashMap<>(Map.of("dataSourceId", 9, "label", "Find orders")))));
        stubWorkflowWithPlan(plan);
        when(dataSourceClient.bulkFind(any(), eq(TENANT_ID), any())).thenReturn(List.of(new DataSourceDto(
                9L, TENANT_ID, "Orders", "Order book", null, null, null, null, null, null, null, null, null,
                null, null, null)));
        List<DataSourceItemDto> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new DataSourceItemDto((long) i, 9L, TENANT_ID, Map.of("n", i), 0, null));
        }
        when(dataSourceClient.copyAllItems(eq(9L), eq(TENANT_ID), any())).thenReturn(rows);
        service.snapshotBudget = new PublicationSnapshotBudget(new ObjectMapper(),
                PublicationSnapshotBudget.DEFAULT_MAX_BYTES, 3);

        WorkflowPublicationEntity published = publish(PublicationVisibility.PRIVATE, null);

        assertThat(published.getPlanSnapshot().get("tables")).asList().hasSize(1);
    }

    // ==================== what only enrichment can reveal ====================

    /**
     * The raw plan's agent node carries normalised {@code mcp:<label>} refs, never catalog
     * tool ids: the real grant only materialises as
     * {@code agents[]._snapshot_agent_toolsConfig.tools} during enrichment, and it is
     * shipped verbatim to the acquirer. So the pre-enrichment pass CANNOT see it and the
     * post-enrichment pass must. Regression guard for a publish that went through with a
     * custom-API tool granted to an agent node.
     */
    @Test
    @DisplayName("a custom API granted to an agent NODE of the plan is refused (only enrichment reveals it)")
    void agentNodeGrantInsidePlanIsRefused() {
        UUID agentConfigId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(new HashMap<>(Map.of("type", "manual", "label", "Start")))));
        // The only mcp node runs a SHIPPED integration: the first pass must find nothing.
        plan.put("mcps", new ArrayList<>(List.of(new HashMap<>(Map.of("id", "github/get-user", "label", "Fetch")))));
        plan.put("agents", new ArrayList<>(List.of(new HashMap<>(Map.of(
                "agentConfigId", agentConfigId.toString(), "type", "agent", "label", "Helper")))));
        plan.put("cores", new ArrayList<>());
        plan.put("interfaces", new ArrayList<>());
        plan.put("edges", new ArrayList<>());
        stubWorkflowWithPlan(plan);
        stubPublishableShowcaseRun();

        AgentDto agent = new AgentDto();
        agent.setId(agentConfigId);
        agent.setTenantId(TENANT_ID);
        agent.setName("Helper");
        agent.setToolsConfig(new LinkedHashMap<>(Map.of(
                "mode", "custom", "tools", List.of("my-private-api/do-thing"))));
        when(agentClient.bulkFind(List.of(agentConfigId), TENANT_ID, null)).thenReturn(List.of(agent));
        when(agentClient.getSkillsForAgent(any(UUID.class), any(), any())).thenReturn(List.of());

        // Answer per call: only the pass that sees the agent's granted tool gets a hit.
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenAnswer(invocation -> {
            Collection<String> identifiers = invocation.getArgument(0);
            return identifiers.contains("my-private-api/do-thing") ? CUSTOM_API_HIT : List.of();
        });

        assertThatThrownBy(() -> publish(PublicationVisibility.PUBLIC, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class)
                .hasMessageContaining("My Private API");

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("the enriched pass is skipped for a PRIVATE publication (no second catalog round trip)")
    void privatePublicationSkipsBothPasses() {
        stubWorkflowWithPlan(planUsingCustomApi());

        publish(PublicationVisibility.PRIVATE, null);

        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    // ==================== update (re-share) ====================

    private WorkflowPublicationEntity existingPublication(PublicationVisibility visibility) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setPublisherId(TENANT_ID);
        publication.setVisibility(visibility);
        publication.setDisplayMode(DisplayMode.WORKFLOW);
        publication.assignOwnerFromContext(TENANT_ID, null);
        return publication;
    }

    private void stubUpdate(WorkflowPublicationEntity publication, Map<String, Object> plan) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", plan, "tenantId", TENANT_ID));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotVersionRepository.getMaxVersion(any(UUID.class))).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT_ID)).thenReturn(2);
    }

    private WorkflowPublicationEntity update(PublicationVisibility visibility, String showcaseRunId) {
        return service.updatePublicationInfo(
                PUBLICATION_ID, TENANT_ID, null, "Title", "Description",
                null, showcaseRunId, CATEGORY_ID, 0, visibility,
                DisplayMode.WORKFLOW, null, false, true, Map.of(), null);
    }

    @Test
    @DisplayName("flipping an existing PRIVATE publication to PUBLIC is refused when the plan uses a custom API")
    void updateFlipToPublicIsRefused() {
        stubUpdate(existingPublication(PublicationVisibility.PRIVATE), planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        assertThatThrownBy(() -> update(PublicationVisibility.PUBLIC, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class)
                .hasMessageContaining("My Private API");

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("updating a PRIVATE publication that uses a custom API stays allowed")
    void updateStayingPrivateIsAllowed() {
        stubUpdate(existingPublication(PublicationVisibility.PRIVATE), planUsingCustomApi());

        assertThatCode(() -> update(PublicationVisibility.PRIVATE, null)).doesNotThrowAnyException();

        verify(catalogInternalClient, never()).findCustomApiRefs(any(), any(), any());
    }

    @Test
    @DisplayName("re-sharing an already PUBLIC publication is refused once a custom-API node was added")
    void updateKeepingPublicIsRefusedAfterCustomApiAdded() {
        // visibility omitted on the request: the effective value is the stored PUBLIC.
        stubUpdate(existingPublication(PublicationVisibility.PUBLIC), planUsingCustomApi());
        stubPublishableShowcaseRun();
        when(catalogInternalClient.findCustomApiRefs(any(), any(), any())).thenReturn(CUSTOM_API_HIT);

        assertThatThrownBy(() -> update(null, SHOWCASE_RUN_ID))
                .isInstanceOf(PublicationValidationException.class);
    }
}
