package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.PendingSignalRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

/**
 * Tests that deleting a workflow cleans up all related data:
 * - storage entries (soft-deleted)
 * - S3 files (deleted via FileStorageService)
 * - workflow_epochs (deleted)
 * - workflow_signal_waits (deleted)
 * - workflow_pending_signals (deleted)
 */
@ExtendWith(MockitoExtension.class)
class WorkflowDeletionCascadeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private StorageRepository storageRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private WorkflowEpochRepository workflowEpochRepository;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private PendingSignalRepository pendingSignalRepository;
    @Mock private PublicationClient publicationClient;
    @Mock private TriggerClient triggerClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private ObjectMapper objectMapper;
    @Mock private EntityManager entityManager;
    @Mock private OrgAccessGuard orgAccessService;

    @InjectMocks
    private WorkflowManagementService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";

    private WorkflowEntity workflow;
    private List<WorkflowRunEntity> runs;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowEntity(TENANT_ID, "Test Workflow", TENANT_ID);
        workflow.setId(WORKFLOW_ID);
        org.mockito.Mockito.lenient().when(orgAccessService.canWrite(any(), any(), any(), any(), any()))
                .thenReturn(true);

        WorkflowRunEntity run1 = new WorkflowRunEntity();
        run1.setRunIdPublic("run-001");

        WorkflowRunEntity run2 = new WorkflowRunEntity();
        run2.setRunIdPublic("run-002");

        runs = List.of(run1, run2);
    }

    @Nested
    @DisplayName("deleteWorkflow cleanup cascade")
    class DeleteCascadeTests {

        @Test
        @DisplayName("Soft-deletes storage entries for the workflow")
        void softDeletesStorageEntries() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(storageRepository.softDeleteByWorkflowId(WORKFLOW_ID.toString())).thenReturn(5);

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(result).isTrue();
            verify(storageRepository).softDeleteByWorkflowId(WORKFLOW_ID.toString());
        }

        @Test
        @DisplayName("Deletes S3 files for each run")
        void deletesS3Files() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            verify(fileStorageService).deleteRunFiles(TENANT_ID, WORKFLOW_ID.toString(), "run-001");
            verify(fileStorageService).deleteRunFiles(TENANT_ID, WORKFLOW_ID.toString(), "run-002");
        }

        @Test
        @DisplayName("Deletes workflow_epochs for all runs")
        void deletesEpochs() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(workflowEpochRepository.deleteByRunIds(List.of("run-001", "run-002"))).thenReturn(10);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            verify(workflowEpochRepository).deleteByRunIds(List.of("run-001", "run-002"));
        }

        @Test
        @DisplayName("Deletes signal_waits for all runs")
        void deletesSignalWaits() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(signalWaitRepository.deleteByRunIdIn(List.of("run-001", "run-002"))).thenReturn(3);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            verify(signalWaitRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
        }

        @Test
        @DisplayName("Deletes pending_signals for all runs")
        void deletesPendingSignals() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(pendingSignalRepository.deleteByRunIdIn(List.of("run-001", "run-002"))).thenReturn(2);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            verify(pendingSignalRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
        }

        @Test
        @DisplayName("Handles workflow with no runs gracefully")
        void noRunsGraceful() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(List.of());

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(result).isTrue();
            verify(storageRepository, never()).softDeleteByWorkflowId(anyString());
            verify(fileStorageService, never()).deleteRunFiles(anyString(), anyString(), anyString());
            verify(workflowEpochRepository, never()).deleteByRunIds(anyList());
            verify(signalWaitRepository, never()).deleteByRunIdIn(anyList());
            verify(pendingSignalRepository, never()).deleteByRunIdIn(anyList());
        }

        @Test
        @DisplayName("Continues cleanup even if one step fails")
        void continuesOnPartialFailure() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(storageRepository.softDeleteByWorkflowId(anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            // Should still proceed with remaining cleanup steps
            assertThat(result).isTrue();
            verify(fileStorageService).deleteRunFiles(eq(TENANT_ID), eq(WORKFLOW_ID.toString()), eq("run-001"));
            verify(workflowEpochRepository).deleteByRunIds(anyList());
            verify(signalWaitRepository).deleteByRunIdIn(anyList());
            verify(pendingSignalRepository).deleteByRunIdIn(anyList());
        }

        @Test
        @DisplayName("All @Modifying repository methods are called during deletion")
        void allModifyingRepositoryMethodsCalledDuringDeletion() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(storageRepository.softDeleteByWorkflowId(WORKFLOW_ID.toString())).thenReturn(3);
            when(signalWaitRepository.deleteByRunIdIn(List.of("run-001", "run-002"))).thenReturn(2);
            when(pendingSignalRepository.deleteByRunIdIn(List.of("run-001", "run-002"))).thenReturn(1);

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(result).isTrue();

            // All three @Modifying JPA calls must execute within the @Transactional boundary
            InOrder inOrder = inOrder(storageRepository, signalWaitRepository, pendingSignalRepository, workflowRepository);
            inOrder.verify(storageRepository).softDeleteByWorkflowId(WORKFLOW_ID.toString());
            inOrder.verify(signalWaitRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
            inOrder.verify(pendingSignalRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
            inOrder.verify(workflowRepository).deleteById(WORKFLOW_ID);
        }

        @Test
        @DisplayName("deleteWorkflow with org role keeps bulk cleanup inside a transaction")
        void deleteWorkflowWithOrgRoleKeepsBulkCleanupTransactional() throws Exception {
            Method method = WorkflowManagementService.class.getMethod(
                    "deleteWorkflow", UUID.class, String.class, String.class);

            assertThat(method.getAnnotation(Transactional.class))
                    .as("HTTP callers use the 3-arg overload; its @Modifying cleanup must run in a transaction")
                    .isNotNull();
        }

        @Test
        @DisplayName("Detaches loaded runs before bulk DML to avoid CHECK_ON_FLUSH against DELETED workflow")
        void detachesRunsBeforeBulkDml() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            InOrder inOrder = inOrder(entityManager, storageRepository, signalWaitRepository, workflowRepository);
            inOrder.verify(entityManager).detach(runs.get(0));
            inOrder.verify(entityManager).detach(runs.get(1));
            inOrder.verify(storageRepository).softDeleteByWorkflowId(WORKFLOW_ID.toString());
            inOrder.verify(signalWaitRepository).deleteByRunIdIn(anyList());
            inOrder.verify(workflowRepository).deleteById(WORKFLOW_ID);
        }

        @Test
        @DisplayName("External HTTP failures do not prevent @Modifying cleanup")
        void externalHttpFailuresDoNotPreventCleanup() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            // Both fire-and-forget HTTP calls fail (v5: schedule cascade now routes
            // through archiveSchedulesByWorkflow + 3 sibling archive methods instead
            // of a single hard-delete; just throw on the first to prove the cascade
            // is resilient).
            doThrow(new RuntimeException("trigger-service down"))
                    .when(triggerClient).archiveSchedulesByWorkflow(eq(WORKFLOW_ID), anyString());
            doThrow(new RuntimeException("publication-service down"))
                    .when(publicationClient).unpublishByWorkflowId(WORKFLOW_ID, TENANT_ID);

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            // Deletion still succeeds and all @Modifying repo calls still happen
            assertThat(result).isTrue();
            verify(storageRepository).softDeleteByWorkflowId(WORKFLOW_ID.toString());
            verify(signalWaitRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
            verify(pendingSignalRepository).deleteByRunIdIn(List.of("run-001", "run-002"));
            verify(workflowRepository).deleteById(WORKFLOW_ID);
        }

        /**
         * Audit-A MUST-FIX #1 regression guard: the F4 bug-class motivated the v5
         * shift from hard-DELETE to 4-way archive cascade. Without this positive
         * assertion, a future regression restoring the old single-DELETE path
         * would pass every other test (deny-list + resilience + invariants are all
         * `never()` or "still runs" assertions). This pins the contract: every
         * successful deleteWorkflow MUST archive all 4 standalone trigger types
         * with reason WORKFLOW_DELETED.
         */
        @Test
        @DisplayName("v5 archive cascade: deleteWorkflow archives all 4 standalone trigger types with reason=WORKFLOW_DELETED")
        void deleteWorkflowArchivesAllFourTriggerTypesWithWorkflowDeletedReason() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(result).isTrue();
            verify(triggerClient).archiveSchedulesByWorkflow(WORKFLOW_ID, "WORKFLOW_DELETED");
            verify(triggerClient).archiveWebhooksByWorkflow(WORKFLOW_ID, "WORKFLOW_DELETED");
            verify(triggerClient).archiveChatEndpointsByWorkflow(WORKFLOW_ID, "WORKFLOW_DELETED");
            verify(triggerClient).archiveFormEndpointsByWorkflow(WORKFLOW_ID, "WORKFLOW_DELETED");
        }
    }

    @Nested
    @DisplayName("deleteWorkflow returns false for invalid requests")
    class InvalidRequestTests {

        @Test
        @DisplayName("Returns false when workflow not found")
        void workflowNotFound() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            boolean result = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(result).isFalse();
            verify(workflowRunRepository, never()).findByWorkflowIdOrderByStartedAtDesc(any());
        }

        @Test
        @DisplayName("Returns false when tenant mismatch")
        void tenantMismatch() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            boolean result = service.deleteWorkflow(WORKFLOW_ID, "wrong-tenant");

            assertThat(result).isFalse();
            verify(workflowRunRepository, never()).findByWorkflowIdOrderByStartedAtDesc(any());
        }
    }

    @Nested
    @DisplayName("PR-2: org-scoped deny-list enforcement on delete")
    class OrgAccessDenyList {

        /**
         * Binds a MockHttpServletRequest with X-Organization-ID so that
         * TenantResolver.currentRequestOrganizationId() returns the given orgId
         * inside the production code under test. Without this, the static method
         * falls back to null (no HTTP context in a Mockito unit test), and
         * ScopeGuard.isInStrictScope treats the caller as personal-workspace,
         * rejecting any org-scoped entity before the deny-list gate is reached.
         */
        private void bindOrgRequestContext(String orgId) {
            org.springframework.mock.web.MockHttpServletRequest request =
                    new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader("X-Organization-ID", orgId);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));
        }

        @org.junit.jupiter.api.AfterEach
        void clearRequestContext() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }

        @Test
        @DisplayName("Restricted MEMBER triggers OrgAccessDeniedException - no cascade, no side-effects, no DB delete")
        void restrictedMemberDenied() {
            workflow.setOrganizationId("org-42");
            bindOrgRequestContext("org-42");
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(orgAccessService.canWrite("org-42", TENANT_ID, "workflow", WORKFLOW_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            assertThat(org.assertj.core.api.Assertions
                    .catchThrowable(() -> service.deleteWorkflow(WORKFLOW_ID, TENANT_ID, "MEMBER")))
                    .isInstanceOf(com.apimarketplace.auth.client.access.OrgAccessDeniedException.class)
                    .hasMessageContaining("workflow");

            // None of the cascade side-effects must fire - the org-access check must
            // gate the entire pipeline. A future refactor that moves the check below
            // any of these would slip through if we only asserted on deleteById.
            verify(workflowRunRepository, never()).findByWorkflowIdOrderByStartedAtDesc(any());
            verify(storageRepository, never()).softDeleteByWorkflowId(anyString());
            verify(triggerClient, never()).archiveSchedulesByWorkflow(any(), any());
            verify(triggerClient, never()).archiveWebhooksByWorkflow(any(), any());
            verify(triggerClient, never()).archiveChatEndpointsByWorkflow(any(), any());
            verify(triggerClient, never()).archiveFormEndpointsByWorkflow(any(), any());
            verify(triggerClient, never()).deleteDatasourceSubscriptionsByWorkflow(any());
            verify(workflowEpochRepository, never()).deleteByRunIds(anyList());
            // signalWaitRepository / pendingSignalRepository / publicationClient
            // are also wired into the cascade ; their specific method signatures
            // depend on the run IDs which the previous gate already prevented
            // from loading. Asserting on the upstream collaborators above is
            // sufficient - those are the entry points to the rest of the cascade.
            verify(workflowRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Tenant mismatch takes precedence over org-restriction - 404 path, not 403, no info-leak")
        void tenantMismatchTakesPrecedenceOverOrgRestriction() {
            // Setup: workflow is org-scoped AND in deny-list for the caller, BUT the
            // caller is on the wrong tenant. The tenant check fires first → returns
            // false (404 path) without ever consulting orgAccessService. Pinning
            // this ordering prevents an existence-leak: if the org-check fired first,
            // a wrong-tenant caller would receive 403 (resource exists in some org I
            // can't access) instead of 404 (resource doesn't belong to my tenant).
            workflow.setOrganizationId("org-42");
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            boolean deleted = service.deleteWorkflow(WORKFLOW_ID, "wrong-tenant", "MEMBER");

            assertThat(deleted).isFalse();
            // Critical: orgAccessService must NOT be called when tenant mismatches.
            verify(orgAccessService, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("OWNER bypass - guard returns true, delete proceeds")
        void ownerBypasses() {
            workflow.setOrganizationId("org-42");
            bindOrgRequestContext("org-42");
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(orgAccessService.canWrite("org-42", TENANT_ID, "workflow", WORKFLOW_ID.toString(), "OWNER"))
                    .thenReturn(true);

            boolean deleted = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID, "OWNER");

            assertThat(deleted).isTrue();
            verify(workflowRepository).deleteById(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Personal workflow (organization_id IS NULL) skips OrgAccess check entirely")
        void personalWorkflowSkipsCheck() {
            // workflow.organizationId left null by default
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            boolean deleted = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID, "MEMBER");

            assertThat(deleted).isTrue();
            verify(workflowRepository).deleteById(WORKFLOW_ID);
            verify(orgAccessService, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Non-restricted MEMBER on org-scoped workflow proceeds with delete")
        void nonRestrictedMemberAllowed() {
            workflow.setOrganizationId("org-42");
            bindOrgRequestContext("org-42");
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);
            when(orgAccessService.canWrite("org-42", TENANT_ID, "workflow", WORKFLOW_ID.toString(), "MEMBER"))
                    .thenReturn(true);

            boolean deleted = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID, "MEMBER");

            assertThat(deleted).isTrue();
            verify(workflowRepository).deleteById(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Backward-compat overload deleteWorkflow(id, tenantId) forwards orgRole=null")
        void backwardCompatOverload() {
            // Old 2-arg signature must still work for internal callers (tool modules,
            // cascade tests). orgRole=null means the guard applies non-admin semantics,
            // but a personal workflow short-circuits before the guard is consulted.
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            boolean deleted = service.deleteWorkflow(WORKFLOW_ID, TENANT_ID);

            assertThat(deleted).isTrue();
            verify(orgAccessService, never()).canWrite(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Cascade delete forwards the workflow's org to the conversation client (org-blind regression)")
        void cascadeForwardsWorkflowOrgToConversationClient() {
            var conversationClient = org.mockito.Mockito.mock(
                    com.apimarketplace.conversation.client.ConversationClient.class);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "conversationServiceClient", conversationClient);

            workflow.setOrganizationId("org-77");
            bindOrgRequestContext("org-77");
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID)).thenReturn(runs);

            service.deleteWorkflow(WORKFLOW_ID, TENANT_ID, "OWNER");

            // Pre-fix the cascade called the 2-arg overload (orgId defaulted to null), which
            // made the conversation-service strict-scope gate skip the org-tagged workflow
            // conversation → orphaned, still-active row. The fix forwards the workflow's org.
            verify(conversationClient)
                    .deleteConversationsByWorkflowId(WORKFLOW_ID.toString(), TENANT_ID, "org-77");
            verify(conversationClient, never())
                    .deleteConversationsByWorkflowId(anyString(), anyString());
        }
    }
}
