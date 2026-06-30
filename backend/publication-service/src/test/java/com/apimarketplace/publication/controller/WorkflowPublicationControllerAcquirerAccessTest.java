package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the acquirer bypass in
 * {@link WorkflowPublicationController#getPublicationById}.
 *
 * <p>Bug: after a user installs (acquires) an application, the publisher could
 * unpublish it (status -&gt; INACTIVE) or set it PRIVATE. The acquirer's
 * installed-app view at {@code /app/applications/{publicationId}} reads the
 * publication metadata first, and the visibility gate 404'd every non-owner for
 * any non-(ACTIVE+PUBLIC) state, so the page dead-ended with "Failed to load
 * application" even though the acquirer owns an independent clone of the
 * workflow. The fix lets a receipt-holding acquirer read the (PII-stripped)
 * metadata while keeping non-acquirers locked out.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController.getPublicationById - acquirer visibility bypass")
class WorkflowPublicationControllerAcquirerAccessTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowPublicationController controller;

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String PUBLISHER_ID = "publisher-001";
    private static final String ACQUIRER_ID = "acquirer-002";
    private static final String ACQUIRER_ORG = "org-acquirer";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);
    }

    private WorkflowPublicationEntity publication(PublicationStatus status, PublicationVisibility visibility) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setWorkflowId(WORKFLOW_ID);
        pub.setPublicationType(PublicationType.WORKFLOW);
        pub.setOwnerType(OwnerType.USER);
        pub.setOwnerId(PUBLISHER_ID);
        pub.setPublisherId(PUBLISHER_ID);
        pub.setPublisherName("Publisher Person");
        pub.setPublisherEmail("publisher@example.com");
        pub.setTitle("Installed App");
        pub.setDescription("An app the acquirer installed");
        pub.setStatus(status);
        pub.setVisibility(visibility);
        pub.setDisplayMode(DisplayMode.WORKFLOW);
        pub.setCreditsPerUse(0);
        return pub;
    }

    /**
     * A plan whose single MCP node carries a secret (apiKey) that is NOT in
     * {@code PlanSnapshotSanitizer.MCP_KEYS} (id/type/label/position), so the
     * sanitized preview drops it while the raw snapshot keeps it. Lets the tests
     * prove which branch a caller is routed through.
     */
    private Map<String, Object> planWithSecretMcp() {
        Map<String, Object> mcp = new HashMap<>();
        mcp.put("id", "mcp:secret");
        mcp.put("type", "mcp");
        mcp.put("label", "Secret");
        mcp.put("apiKey", "sk_live_supersecret");
        Map<String, Object> plan = new HashMap<>();
        plan.put("mcps", List.of(mcp));
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstMcp(Object planSnapshot) {
        Map<String, Object> plan = (Map<String, Object>) planSnapshot;
        List<Map<String, Object>> mcps = (List<Map<String, Object>>) plan.get("mcps");
        return mcps.get(0);
    }

    private ResponseEntity<?> getAsAcquirer() {
        return controller.getPublicationById(
                PUBLICATION_ID.toString(), ACQUIRER_ID, ACQUIRER_ORG, null, null, null);
    }

    @Test
    @DisplayName("acquirer can read a soft-deleted (INACTIVE) app they installed - 200, publisher email stripped")
    void acquirerReadsSoftDeletedApplication() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("title", "Installed App");
        // Non-owner: publisher email must never be exposed.
        assertThat(body).doesNotContainKey("publisherEmail");
        // Non-owner acquirer is NOT the publication owner - the in-app edit/publish
        // affordances must stay hidden, so ownedByMe is server-set to false.
        assertThat(body).containsEntry("ownedByMe", false);
    }

    @Test
    @DisplayName("acquirer can read an app the publisher set PRIVATE - 200")
    void acquirerReadsPrivatisedApplication() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("non-acquirer (no receipt) still gets 404 on an INACTIVE publication - enumeration stays blocked")
    void nonAcquirerBlockedOnInactive() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("non-acquirer (no receipt) still gets 404 on a PRIVATE publication")
    void nonAcquirerBlockedOnPrivate() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("owner reading their own INACTIVE publication never triggers a receipt lookup (short-circuit)")
    void ownerReadsInactiveWithoutReceiptLookup() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, PUBLISHER_ID, null)).thenReturn(true);

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(), PUBLISHER_ID, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // Server-authoritative owner signal: the publisher viewing their own app gets
        // ownedByMe=true, which the installed-app view uses to bind to the editable
        // source workflow + surface "Publish update".
        assertThat(body).containsEntry("ownedByMe", true);
        verify(publicationService, never()).callerHoldsReceipt(any(), anyString(), any());
    }

    @Test
    @DisplayName("public ACTIVE publication stays readable without any receipt lookup (hot path untouched)")
    void publicActiveReadableWithoutReceiptLookup() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(eq(pub), any(), any())).thenReturn(false);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // visibleToPublic short-circuits the acquirer check: receipts are never queried for a public app.
        verify(publicationService, never()).callerHoldsReceipt(any(), anyString(), any());
    }

    @Test
    @DisplayName("acquirer of a now-PRIVATE WORKFLOW gets the SANITIZED plan snapshot, not the publisher's secrets")
    void acquirerReceivesSanitizedPlanSnapshot() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE);
        pub.setPlanSnapshot(planWithSecretMcp());
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> mcp = firstMcp(body.get("planSnapshot"));
        // The acquirer is a non-owner, so the WORKFLOW snapshot is sanitized: the
        // whitelisted node identity survives but the publisher's secret is dropped.
        assertThat(mcp).containsEntry("id", "mcp:secret").containsEntry("label", "Secret");
        assertThat(mcp).doesNotContainKey("apiKey");
    }

    @Test
    @DisplayName("owner of the same INACTIVE WORKFLOW still gets the RAW plan snapshot (acquirer routed differently)")
    void ownerReceivesRawPlanSnapshot() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PRIVATE);
        pub.setPlanSnapshot(planWithSecretMcp());
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, PUBLISHER_ID, null)).thenReturn(true);

        ResponseEntity<?> response = controller.getPublicationById(
                PUBLICATION_ID.toString(), PUBLISHER_ID, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> mcp = firstMcp(body.get("planSnapshot"));
        // Owner sees the unsanitized snapshot - confirms the sanitize branch is owner-gated,
        // so the acquirer-sanitized assertion above is meaningful (not a vacuous no-op).
        assertThat(mcp).containsEntry("apiKey", "sk_live_supersecret");
    }

    @Test
    @DisplayName("acquirer of a non-WORKFLOW (INTERFACE) publication gets the raw snapshot - that IS the public content")
    void acquirerOfNonWorkflowReceivesRawSnapshot() {
        WorkflowPublicationEntity pub = publication(PublicationStatus.INACTIVE, PublicationVisibility.PUBLIC);
        pub.setPublicationType(PublicationType.INTERFACE);
        pub.setPlanSnapshot(planWithSecretMcp());
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(pub, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(false);
        when(publicationService.callerHoldsReceipt(PUBLICATION_ID, ACQUIRER_ID, ACQUIRER_ORG)).thenReturn(true);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        // For TABLE/INTERFACE/SKILL the snapshot is the resource content (the !isWorkflow
        // branch), so it stays raw even for a non-owner acquirer - pre-existing behavior the
        // acquirer bypass must not alter. Still PII-stripped at the publication level.
        Map<String, Object> mcp = firstMcp(body.get("planSnapshot"));
        assertThat(mcp).containsEntry("apiKey", "sk_live_supersecret");
        assertThat(body).doesNotContainKey("publisherEmail");
    }

    /**
     * A plan whose single agent node carries a {@code model} + {@code provider}
     * (now whitelisted, so the preview keeps them) and a {@code prompt} (NOT
     * whitelisted, so the preview drops it).
     */
    private Map<String, Object> planWithAgentModelAndPrompt() {
        Map<String, Object> agent = new HashMap<>();
        agent.put("id", "agent:risk-screen");
        agent.put("type", "guardrail");
        agent.put("label", "Risk Screen");
        agent.put("model", "deepseek-chat");
        agent.put("provider", "deepseek");
        agent.put("prompt", "You are screening recent news mentions about a brand...");
        Map<String, Object> plan = new HashMap<>();
        plan.put("agents", List.of(agent));
        return plan;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstAgent(Object planSnapshot) {
        Map<String, Object> plan = (Map<String, Object>) planSnapshot;
        List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
        return agents.get(0);
    }

    @Test
    @DisplayName("public marketplace preview (non-owner) keeps the agent's model + provider so the canvas shows the real LLM, but still strips the prompt")
    void previewKeepsAgentModelButStripsPrompt() {
        // Mirrors the anonymous marketplace preview of a PUBLIC app (the Echo Watch
        // repro): a non-owner gets the sanitized WORKFLOW snapshot. Pre-fix the agent
        // model was stripped, so the frontend ModelPicker fell back to its default
        // Claude id and a deepseek app rendered as Claude.
        WorkflowPublicationEntity pub = publication(PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        pub.setPlanSnapshot(planWithAgentModelAndPrompt());
        when(publicationService.getPublicationById(PUBLICATION_ID)).thenReturn(Optional.of(pub));
        when(publicationService.isCallerInOwnerScope(eq(pub), any(), any())).thenReturn(false);

        ResponseEntity<?> response = getAsAcquirer();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        Map<String, Object> agent = firstAgent(body.get("planSnapshot"));
        // The fix: the real model identity reaches the preview (no Claude fallback).
        assertThat(agent).containsEntry("model", "deepseek-chat").containsEntry("provider", "deepseek");
        // The prompt (publisher IP) is still dropped - proving the sanitize branch ran,
        // so the model-survival assertion above is meaningful, not a vacuous raw passthrough.
        assertThat(agent).doesNotContainKey("prompt");
    }
}
