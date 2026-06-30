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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the publisher-snapshot bug observed in prod 2026-05-26→27.
 *
 * <p><b>Pre-fix symptom</b>: at every (re)publish, the controller forwarded
 * {@code publisherName} from the frontend request body verbatim, which
 * resolved to the admin's session displayName (e.g. {@code "livecontext"}).
 * Combined with the publisher-curator setting {@code publisher_id} to a
 * persona, the marketplace card rendered Léa's avatar with "livecontext"
 * as the displayed name - name/avatar mismatch.
 *
 * <p><b>Post-fix</b>: {@link WorkflowPublicationService#publishWorkflow} ignores
 * the request body publisher fields and snapshots {@code displayName / email /
 * avatarUrl} server-side via {@link AuthClient#getPublisherProfile(String)},
 * keyed on the authenticated {@code tenantId}. The frontend can keep sending
 * the fields (API contract preserved) but they are not load-bearing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("publishWorkflow: publisher identity always snapshots server-side from AuthClient")
class WorkflowPublicationServicePublisherSnapshotTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID WORKFLOW_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID INTERFACE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String TENANT = "1";

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
    }

    @Test
    @DisplayName("First publish: stores AuthClient values, ignores request-body publisher fields")
    void firstPublishStoresAuthClientValuesIgnoringRequestBody() {
        stubFirstPublish();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(new PublisherProfileDto(
                TENANT, "Real Admin Name", "admin@example.com", "avatar-uuid-admin"));

        // Service signature no longer carries publisherName/Email/AvatarUrl
        // parameters - M-1 cleanup removed them entirely so the bug class is
        // structurally impossible at this layer. The remaining assertion is
        // that the stored fields come from AuthClient.
        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        assertThat(pub.getPublisherName()).isEqualTo("Real Admin Name");
        assertThat(pub.getPublisherEmail()).isEqualTo("admin@example.com");
        assertThat(pub.getPublisherAvatarUrl()).isEqualTo("avatar-uuid-admin");
        verify(authClient).getPublisherProfile(TENANT);
    }

    @Test
    @DisplayName("Republish: refreshes publisher snapshot from AuthClient even when row already exists")
    void republishRefreshesPublisherSnapshotFromAuthClient() {
        WorkflowPublicationEntity existing = new WorkflowPublicationEntity();
        existing.setId(PUBLICATION_ID);
        existing.setWorkflowId(WORKFLOW_ID);
        existing.setPublisherId(TENANT);
        existing.assignOwnerFromContext(TENANT, null);
        existing.setPublisherName("stale-snapshot-from-last-publish");
        existing.setPublisherEmail("stale@example.com");
        existing.setPublisherAvatarUrl("stale-avatar");

        stubRepublish(existing);
        when(authClient.getPublisherProfile(TENANT)).thenReturn(new PublisherProfileDto(
                TENANT, "Updated Admin Name", "fresh@example.com", "fresh-avatar"));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        // Pre-fix, republish persisted the request body's publisherName
        // verbatim, leaving stale snapshots whenever the body diverged from
        // auth.users. Post-fix the body has no publisherName slot at all and
        // the AuthClient value wins by construction.
        assertThat(pub.getPublisherName()).isEqualTo("Updated Admin Name");
        assertThat(pub.getPublisherEmail()).isEqualTo("fresh@example.com");
        assertThat(pub.getPublisherAvatarUrl()).isEqualTo("fresh-avatar");
    }

    @Test
    @DisplayName("AuthClient returns null → publish fails loud (no silent drift)")
    void authClientNullSnapshotFailsPublish() {
        stubFirstPublish();
        when(authClient.getPublisherProfile(TENANT)).thenReturn(null);

        assertThatThrownBy(() -> service.publishWorkflow(
                WORKFLOW_ID, TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of()))
                .isInstanceOf(PublisherProfileUnavailableException.class)
                .hasMessageContaining("Failed to resolve publisher identity");
    }

    @Test
    @DisplayName("AuthClient profile with partial nulls (no avatar / email) is persisted as-is")
    void partialAuthClientProfileIsPersistedAsIs() {
        stubFirstPublish();
        // Persona without avatar uploaded → avatarUrl is null in auth.users.
        // The publication freezes null, not the request body's stale value.
        when(authClient.getPublisherProfile(TENANT)).thenReturn(new PublisherProfileDto(
                TENANT, "Léa T.", null, null));

        WorkflowPublicationEntity pub = service.publishWorkflow(
                WORKFLOW_ID, TENANT, null,
                "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0,
                PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());

        assertThat(pub.getPublisherName()).isEqualTo("Léa T.");
        assertThat(pub.getPublisherEmail()).isNull();
        assertThat(pub.getPublisherAvatarUrl()).isNull();
    }

    // ========================================================================
    // Stubs
    // ========================================================================

    private void stubFirstPublish() {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));

        // lenient - the null-snapshot test fails at the AuthClient call so
        // stubs after that point are unused; harmless for the other tests
        // that exercise the full save path.
        lenient().when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT, null))
                .thenReturn(workflowData);
        lenient().when(orchestratorClient.validateShowcaseRun("run-1", TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        lenient().when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());
        lenient().when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> {
                    WorkflowPublicationEntity pub = invocation.getArgument(0);
                    if (pub.getId() == null) pub.setId(PUBLICATION_ID);
                    return pub;
                });
        lenient().when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        lenient().when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT)).thenReturn(1);
        lenient().when(orchestratorClient.captureShowcaseSnapshot("run-1", TENANT, null, null))
                .thenReturn(new HashMap<>(Map.of("runState", new HashMap<>())));
    }

    private void stubRepublish(WorkflowPublicationEntity existing) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(),
                "interfaces", List.of(),
                "cores", List.of(),
                "edges", List.of())));

        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT, null))
                .thenReturn(workflowData);
        when(orchestratorClient.validateShowcaseRun("run-1", TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(existing));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotVersionRepository.getMaxVersion(PUBLICATION_ID)).thenReturn(Optional.empty());
        when(orchestratorClient.getLatestPlanVersion(WORKFLOW_ID, TENANT)).thenReturn(1);
        when(orchestratorClient.captureShowcaseSnapshot("run-1", TENANT, null, null))
                .thenReturn(new HashMap<>(Map.of("runState", new HashMap<>())));
    }
}
