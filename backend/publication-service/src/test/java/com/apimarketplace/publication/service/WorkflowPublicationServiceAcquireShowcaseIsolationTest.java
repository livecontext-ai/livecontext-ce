package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the showcase-isolation invariant of the acquire/clone path: acquiring a
 * published application produces an INDEPENDENT clone that does NOT carry the
 * publisher's showcase run / interface reference.
 *
 * <p>Why it is load-bearing: the publisher's showcase ({@code showcaseRunId} /
 * {@code showcaseInterfaceId}) is bound to the immutable publication snapshot and
 * drives the marketplace preview. If acquisition leaked that reference into the
 * buyer's clone (or, worse, let the buyer's acquire write back to the publication
 * row), a buyer running their clone could mutate or surface inside the publisher's
 * showcase. {@code acquirePublication} -> {@code cloneWorkflowSnapshot} only ever
 * feeds {@code SnapshotCloneService.cloneFromSnapshot} the immutable
 * {@code planSnapshot} plus the acquirer's own identity (tenant/org) - never the
 * showcase reference - and mints a brand-new clone workflow id. This test proves
 * all three facets at the acquisition layer with mocks, no live stack required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - acquire clones independently of the publisher's showcase run")
class WorkflowPublicationServiceAcquireShowcaseIsolationTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    // The publisher's own source workflow + showcase references (bound to the snapshot).
    private static final UUID PUBLISHER_WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHOWCASE_INTERFACE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SHOWCASE_RUN_ID = "showcase_publisher_run_001";
    // The buyer's brand-new clone workflow id returned by SnapshotCloneService.
    private static final UUID NEW_CLONE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";

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
    @DisplayName("first-time acquire: clone is built from the immutable planSnapshot only - the publisher's showcase run/interface never flows into the clone inputs, and the publication row is left untouched")
    void acquireDoesNotCarryPublisherShowcaseReference() {
        WorkflowPublicationEntity publication = publisherApplication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        // Not yet acquired, no receipt -> first-time acquisition path.
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // The clone succeeds and mints a NEW workflow id, distinct from anything the publisher owns.
        when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(new HashMap<>(Map.of("workflowId", NEW_CLONE_ID.toString())));

        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);

        // (1) The clone is fed the immutable planSnapshot + the ACQUIRER's identity only.
        ArgumentCaptor<Map<String, Object>> planCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> tenantCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> pubIdCap = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> titleCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List> iconsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> orgCap = ArgumentCaptor.forClass(String.class);
        verify(snapshotCloneService).cloneFromSnapshot(
                planCap.capture(), tenantCap.capture(), pubIdCap.capture(),
                titleCap.capture(), descCap.capture(), iconsCap.capture(), orgCap.capture());

        assertThat(planCap.getValue()).isEqualTo(publication.getPlanSnapshot());
        assertThat(tenantCap.getValue()).isEqualTo(BUYER);
        assertThat(pubIdCap.getValue()).isEqualTo(PUBLICATION_ID);
        assertThat(orgCap.getValue()).isEqualTo(BUYER_ORG);

        // The publisher's showcase reference must appear in NONE of the clone inputs:
        // not as any String argument, and not as the (only) UUID argument.
        assertThat(List.of(tenantCap.getValue(), titleCap.getValue(), descCap.getValue(), orgCap.getValue()))
                .doesNotContain(SHOWCASE_RUN_ID)
                .doesNotContain(SHOWCASE_INTERFACE_ID.toString());
        assertThat(pubIdCap.getValue())
                .isNotEqualTo(SHOWCASE_INTERFACE_ID)
                .isNotEqualTo(PUBLISHER_WORKFLOW_ID);

        // (2) The acquirer gets a brand-new, independent clone workflow id - distinct
        // from the publisher's showcase run, showcase interface, and source workflow.
        String clonedWorkflowId = (String) result.get("workflowId");
        assertThat(clonedWorkflowId).isEqualTo(NEW_CLONE_ID.toString());
        assertThat(clonedWorkflowId)
                .isNotEqualTo(SHOWCASE_RUN_ID)
                .isNotEqualTo(SHOWCASE_INTERFACE_ID.toString())
                .isNotEqualTo(PUBLISHER_WORKFLOW_ID.toString());

        // (3) The publication row (showcase reference + source workflow) is never mutated
        // or persisted by the buyer's acquire - the showcase stays bound to the snapshot.
        assertThat(publication.getShowcaseRunId()).isEqualTo(SHOWCASE_RUN_ID);
        assertThat(publication.getShowcaseInterfaceId()).isEqualTo(SHOWCASE_INTERFACE_ID);
        assertThat(publication.getWorkflowId()).isEqualTo(PUBLISHER_WORKFLOW_ID);
        verify(publicationRepository, never()).save(any());
    }

    private WorkflowPublicationEntity publisherApplication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(PUBLISHER_WORKFLOW_ID);
        publication.setPublisherId("publisher-1");
        publication.setTitle("Publisher App");
        publication.setDescription("desc");
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        publication.setCreditsPerUse(0);
        publication.setShowcaseInterfaceId(SHOWCASE_INTERFACE_ID);
        publication.setShowcaseRunId(SHOWCASE_RUN_ID);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        publication.setPlanSnapshot(plan);
        return publication;
    }
}
