package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the #2a decouple-to-editable-workflow acquire hook: acquiring an application
 * ALSO creates a freely-editable, DECOUPLED WORKFLOW twin, billed WORKFLOW-quota only,
 * and a twin failure NEVER fails the acquire (the run-only APPLICATION clone already
 * succeeded) - it compensates ONLY its own rows.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - acquire also duplicates to an editable WORKFLOW")
class WorkflowPublicationServiceDuplicateAcquireTest {

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
    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String APP_CLONE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String DUP_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String DUP_ROOT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String DUP_CHILD = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient);
    }

    private WorkflowPublicationEntity activePublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setTitle("Cool App");
        publication.setPublisherId("publisher-1");
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        publication.setCreditsPerUse(0);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        publication.setPlanSnapshot(plan);
        return publication;
    }

    private void wireFirstTimeAcquire(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // The application clone succeeds and returns its (run-only) workflow id.
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(Map.of("workflowId", APP_CLONE_ID, "title", "Cool App"));
    }

    @Test
    @DisplayName("Acquire creates BOTH the run-only APPLICATION clone AND a decoupled editable WORKFLOW twin (billed WORKFLOW-quota), returning the application id")
    void acquireCreatesApplicationAndEditableDuplicate() {
        WorkflowPublicationEntity publication = activePublication();
        wireFirstTimeAcquire(publication);
        when(snapshotCloneService.duplicateToEditableWorkflow(any(), anyString(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(Map.of("workflowId", DUP_ID));

        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);

        // The acquire still returns the APPLICATION clone id (the duplicate is a side twin).
        assertThat(result.get("workflowId")).isEqualTo(APP_CLONE_ID);

        // The editable twin is created from the publication's plan, in the acquirer's scope,
        // with fileNamespaceId = the source publication id and lineage = the application clone id.
        verify(snapshotCloneService).duplicateToEditableWorkflow(
                eq(publication.getPlanSnapshot()), eq(BUYER), eq(BUYER_ORG),
                eq("Cool App"), any(), any(), eq(PUBLICATION_ID), eq(APP_CLONE_ID));
        // Billed WORKFLOW quota (NOT a second APPLICATION/credit charge).
        verify(entitlementGuard).check(eq(BUYER), eq(ResourceType.WORKFLOW), any());
    }

    @Test
    @DisplayName("A duplicate-twin failure NEVER fails the acquire and compensates ONLY the twin's own rows (never deleteAcquiredWorkflow)")
    void duplicateFailureDoesNotFailAcquireAndCompensatesOwnRows() {
        WorkflowPublicationEntity publication = activePublication();
        wireFirstTimeAcquire(publication);
        // The twin clone explodes mid-pipeline, surfacing the EXACT rows it created.
        when(snapshotCloneService.duplicateToEditableWorkflow(any(), anyString(), anyString(), any(), any(), any(), any(), anyString()))
                .thenThrow(new AcquireCloneFailedException(
                        Set.of(DUP_ROOT, DUP_CHILD), new RuntimeException("twin exploded")));

        // The acquire SUCCEEDS - the run-only application clone already landed + the receipt saved.
        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);
        assertThat(result.get("workflowId")).isEqualTo(APP_CLONE_ID);

        // Compensation deletes ONLY the twin's rows, via the decoupled-duplicate guard.
        verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(DUP_ROOT), BUYER, BUYER_ORG);
        verify(orchestratorClient).deleteDecoupledDuplicateWorkflow(UUID.fromString(DUP_CHILD), BUYER, BUYER_ORG);
        // NEVER the acquired-application delete (its pubId.equals(sourcePublicationId) guard NPEs on null).
        verify(orchestratorClient, never()).deleteAcquiredWorkflow(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Over WORKFLOW quota: the twin is skipped (never cloned) and the acquire still succeeds with the run-only application")
    void overWorkflowQuotaSkipsDuplicateButAcquireSucceeds() {
        WorkflowPublicationEntity publication = activePublication();
        wireFirstTimeAcquire(publication);
        // The WORKFLOW-quota guard denies the twin (the APPLICATION check earlier in acquire is a
        // separate, un-stubbed no-op call - mark this stub lenient so strict-stubs doesn't flag it).
        lenient().doThrow(new RuntimeException("workflow quota reached"))
                .when(entitlementGuard).check(eq(BUYER), eq(ResourceType.WORKFLOW), any());

        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);
        assertThat(result.get("workflowId")).isEqualTo(APP_CLONE_ID);

        // Quota denial short-circuits before any twin clone.
        verify(snapshotCloneService, never())
                .duplicateToEditableWorkflow(any(), anyString(), anyString(), any(), any(), any(), any(), anyString());
    }
}
