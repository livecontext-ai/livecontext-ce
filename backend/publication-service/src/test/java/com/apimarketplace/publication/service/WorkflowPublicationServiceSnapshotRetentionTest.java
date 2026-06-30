package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationSnapshotVersionEntity;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the acquisition-based snapshot-version retention policy
 * that REPLACED the old keep-last-5 cap ({@code DEFAULT_MAX_SNAPSHOT_VERSIONS}).
 *
 * <p>On publish ({@code saveSnapshotVersion}):
 * <ul>
 *   <li>a publication ANY user has acquired (≥1 receipt) keeps EVERY version -
 *       no cap, never purged;</li>
 *   <li>a never-acquired publication keeps NO version history - the whole
 *       history is dropped (the live plan_snapshot on the publication row is all
 *       a fresh acquisition needs).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Snapshot retention: acquired keeps all (no 5-cap), never-acquired keeps none")
class WorkflowPublicationServiceSnapshotRetentionTest {

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
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient);
        lenient().when(authClient.getPublisherProfile(TENANT))
                .thenReturn(new PublisherProfileDto(TENANT, "Publisher", "p@example.com", "avatar"));
    }

    @Test
    @DisplayName("Never-acquired publish: drops the whole history, stores NO version")
    void neverAcquiredPublishDropsHistoryAndSavesNoVersion() {
        stubPublish(null);
        lenient().when(receiptRepository.existsByPublicationId(PUBLICATION_ID)).thenReturn(false);

        publish();

        verify(snapshotVersionRepository).deleteAllByPublicationId(PUBLICATION_ID);
        verify(snapshotVersionRepository, never()).save(any(PublicationSnapshotVersionEntity.class));
    }

    @Test
    @DisplayName("Acquired republish: keeps the new version even beyond 5 (no cap), never purges")
    void acquiredRepublishKeepsVersionBeyondFive() {
        WorkflowPublicationEntity existing = new WorkflowPublicationEntity();
        existing.setId(PUBLICATION_ID);
        existing.setWorkflowId(WORKFLOW_ID);
        existing.setPublisherId(TENANT);
        existing.assignOwnerFromContext(TENANT, null);
        existing.setSnapshotVersion(7); // already at 7 - the old 5-cap would have pruned this
        stubPublish(existing);
        lenient().when(receiptRepository.existsByPublicationId(PUBLICATION_ID)).thenReturn(true);

        publish();

        ArgumentCaptor<PublicationSnapshotVersionEntity> captor =
                ArgumentCaptor.forClass(PublicationSnapshotVersionEntity.class);
        verify(snapshotVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(8); // 7 + 1, kept (no cap)
        verify(snapshotVersionRepository, never()).deleteAllByPublicationId(any());
    }

    @Test
    @DisplayName("On acquisition: the current snapshot version is retained when absent")
    void ensureRetainedStoresCurrentVersionWhenAbsent() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setSnapshotVersion(3);
        pub.setPlanSnapshot(new HashMap<>(Map.of("plan", "x")));
        when(snapshotVersionRepository.findByPublicationIdAndVersion(PUBLICATION_ID, 3)).thenReturn(Optional.empty());

        service.ensureSnapshotVersionRetained(pub);

        ArgumentCaptor<PublicationSnapshotVersionEntity> captor =
                ArgumentCaptor.forClass(PublicationSnapshotVersionEntity.class);
        verify(snapshotVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("On re-acquisition: idempotent - does NOT re-insert an already-retained version")
    void ensureRetainedIsIdempotent() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setSnapshotVersion(3);
        pub.setPlanSnapshot(new HashMap<>(Map.of("plan", "x")));
        when(snapshotVersionRepository.findByPublicationIdAndVersion(PUBLICATION_ID, 3))
                .thenReturn(Optional.of(new PublicationSnapshotVersionEntity(PUBLICATION_ID, 3, Map.of())));

        service.ensureSnapshotVersionRetained(pub);

        verify(snapshotVersionRepository, never()).save(any(PublicationSnapshotVersionEntity.class));
    }

    @Test
    @DisplayName("Legacy pub with null version: no-op (no lookup, no save)")
    void ensureRetainedNoOpWhenVersionNull() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_ID);
        pub.setSnapshotVersion(null);
        pub.setPlanSnapshot(new HashMap<>(Map.of("plan", "x")));

        service.ensureSnapshotVersionRetained(pub);

        verify(snapshotVersionRepository, never()).findByPublicationIdAndVersion(any(UUID.class), any());
        verify(snapshotVersionRepository, never()).save(any(PublicationSnapshotVersionEntity.class));
    }

    // ------------------------------------------------------------------------

    private void publish() {
        service.publishWorkflow(
                WORKFLOW_ID, TENANT, null, "Title", "Desc", INTERFACE_ID, "run-1",
                null, 0, PublicationVisibility.PRIVATE, null, DisplayMode.INTERFACE, null, false, Map.of());
    }

    /** Stubs the publish path; pass {@code existing=null} for a first publish. */
    private void stubPublish(WorkflowPublicationEntity existing) {
        Map<String, Object> workflowData = new HashMap<>();
        workflowData.put("tenantId", TENANT);
        workflowData.put("workflowType", "WORKFLOW");
        workflowData.put("plan", new HashMap<>(Map.of(
                "triggers", List.of(), "interfaces", List.of(), "cores", List.of(), "edges", List.of())));

        lenient().when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT, null)).thenReturn(workflowData);
        lenient().when(orchestratorClient.validateShowcaseRun("run-1", TENANT, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        lenient().when(publicationRepository.findByWorkflowId(WORKFLOW_ID))
                .thenReturn(Optional.ofNullable(existing));
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
}
