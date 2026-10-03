package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.ApplicationRunVersionBatchService;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Intra-organization read gate on the run surface (LC-012, security audit 2026-08-13).
 *
 * <p>What was wrong: these endpoints authorized with {@code ScopeGuard} alone, which is
 * org-id equality. A MEMBER explicitly denied the Gmail workflow was still in the
 * organization, so every one of them answered: the run list, the run, the steps, the run
 * status, and above all {@code GET /workflows/storage/{id}}, which returns the raw
 * persisted third-party payload the Gmail step fetched.
 *
 * <p>Each test below stubs the deny-list to refuse ONE workflow and asserts the endpoint
 * refuses with it, plus the mirror case where the same call succeeds for a member who is
 * not restricted - a gate that refused everybody would pass the first half alone while
 * breaking the product.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowRunQueryController - organization read gate (LC-012)")
class WorkflowRunQueryControllerOrgReadGateTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowEpochRepository workflowEpochRepository;
    @Mock private ApplicationRunVersionBatchService applicationRunVersionBatchService;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowRunQueryController controller;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STORAGE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String RUN_ID_PUBLIC = "run-public-1";
    /** The run's owner. The caller below is a DIFFERENT member of the same organization. */
    private static final String OWNER = "user-owner";
    private static final String DENIED_MEMBER = "user-denied";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() {
        controller = new WorkflowRunQueryController(
                workflowRunRepository,
                workflowStepDataRepository,
                workflowRunStatusService,
                workflowEpochService,
                productionRunResolver,
                storageService,
                new ObjectMapper(),
                workflowEpochRepository,
                applicationRunVersionBatchService,
                orgAccessGuard);
    }

    /** A run of the organization's Gmail workflow, owned by another member. */
    private WorkflowRunEntity orgRun() {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(workflow);
        run.setRunIdPublic(RUN_ID_PUBLIC);
        run.setTenantId(OWNER);
        run.setOrganizationId(ORG);
        run.setStatus(RunStatus.COMPLETED);
        run.setStartedAt(Instant.now());
        return run;
    }

    private void denyWorkflow(String caller) {
        when(orgAccessGuard.canAccess(ORG, caller, "workflow", WORKFLOW_ID.toString(), "MEMBER"))
                .thenReturn(false);
    }

    private void allowWorkflow(String caller) {
        when(orgAccessGuard.canAccess(ORG, caller, "workflow", WORKFLOW_ID.toString(), "MEMBER"))
                .thenReturn(true);
    }

    @Nested
    @DisplayName("listRuns")
    class ListRuns {
        @Test
        @DisplayName("a deny-listed member cannot list the workflow's runs")
        void deniedMemberCannotList() {
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() ->
                    controller.listRuns(WORKFLOW_ID, 15, 0, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);

            // The refusal happens before the query: nothing is read to be filtered afterwards.
            verify(workflowRunRepository, never())
                    .findRunSummariesByWorkflowIdInScope(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("an unrestricted member still lists them")
        void unrestrictedMemberStillLists() {
            allowWorkflow(DENIED_MEMBER);
            when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                    eq(WORKFLOW_ID), eq(DENIED_MEMBER), eq(ORG), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            ResponseEntity<?> response =
                    controller.listRuns(WORKFLOW_ID, 15, 0, DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("getRunByPublicId / listSteps / getStatusCounts")
    class RunReads {
        @Test
        @DisplayName("a deny-listed member cannot read the run")
        void deniedMemberCannotReadRun() {
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(orgRun()));
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() ->
                    controller.getRunByPublicId(RUN_ID_PUBLIC, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("a deny-listed member cannot list the run's steps")
        void deniedMemberCannotListSteps() {
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(orgRun()));
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() ->
                    controller.listSteps(RUN_UUID, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);

            verify(workflowStepDataRepository, never()).findByWorkflowRunIdLightweightAll(any());
        }

        @Test
        @DisplayName("a deny-listed member cannot poll the run's status counts")
        void deniedMemberCannotPollCounts() {
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(orgRun()));
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() ->
                    controller.getStatusCounts(RUN_ID_PUBLIC, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);

            verify(workflowEpochService, never()).getAccumulatedCounts(anyString());
        }

        @Test
        @DisplayName("an unrestricted member still reads the run")
        void unrestrictedMemberStillReadsRun() {
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(orgRun()));
            allowWorkflow(DENIED_MEMBER);

            ResponseEntity<?> response =
                    controller.getRunByPublicId(RUN_ID_PUBLIC, DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a personal run has no role or deny-list to enforce")
        void personalRunIsUntouched() {
            WorkflowRunEntity personal = orgRun();
            personal.setOrganizationId(null);
            personal.setTenantId(OWNER);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID_PUBLIC)).thenReturn(Optional.of(personal));

            ResponseEntity<?> response =
                    controller.getRunByPublicId(RUN_ID_PUBLIC, OWNER, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(orgAccessGuard, never()).canAccess(any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getStorage - the endpoint that returns the raw persisted payload")
    class GetStorage {

        private StorageEntity rowOfWorkflow(String workflowId) {
            StorageEntity row = new StorageEntity();
            row.setTenantId(OWNER);
            row.setOrganizationId(ORG);
            row.setWorkflowId(workflowId);
            row.setData(Map.of("messages", List.of("secret body")));
            return row;
        }

        @Test
        @DisplayName("a deny-listed member cannot read a step output of that workflow")
        void deniedMemberCannotReadStepOutput() {
            when(storageService.getEntityByIdForScope(STORAGE_ID, DENIED_MEMBER, ORG))
                    .thenReturn(Optional.of(rowOfWorkflow(WORKFLOW_ID.toString())));
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() ->
                    controller.getStorage(STORAGE_ID, DENIED_MEMBER, ORG, "MEMBER", null))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("an unrestricted member still reads it")
        void unrestrictedMemberStillReads() {
            when(storageService.getEntityByIdForScope(STORAGE_ID, DENIED_MEMBER, ORG))
                    .thenReturn(Optional.of(rowOfWorkflow(WORKFLOW_ID.toString())));
            allowWorkflow(DENIED_MEMBER);

            ResponseEntity<?> response =
                    controller.getStorage(STORAGE_ID, DENIED_MEMBER, ORG, "MEMBER", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("an ORG row with no owning workflow fails closed (audit round 2)")
        void rowWithoutWorkflowFailsClosed() {
            // The deny-list is keyed on the workflow id; with none recorded the question
            // cannot be answered, and an unanswerable authorization question is a refusal.
            when(storageService.getEntityByIdForScope(STORAGE_ID, DENIED_MEMBER, ORG))
                    .thenReturn(Optional.of(rowOfWorkflow(null)));

            assertThatThrownBy(() ->
                    controller.getStorage(STORAGE_ID, DENIED_MEMBER, ORG, "MEMBER", null))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("applications/run-version-batch")
    class Batch {
        @Test
        @DisplayName("a restricted workflow is absent from the batch answer")
        void restrictedWorkflowIsFilteredOut() {
            when(orgAccessGuard.getRestrictedResourceIds(ORG, DENIED_MEMBER, "workflow", "MEMBER"))
                    .thenReturn(Set.of(WORKFLOW_ID.toString()));

            ResponseEntity<?> response = controller.getApplicationRunVersionBatch(
                    Map.of("workflowIds", List.of(WORKFLOW_ID.toString())), DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getBody()).isEqualTo(Map.of());
            // Every requested id was restricted, so the batch service is never consulted.
            verify(applicationRunVersionBatchService, never()).resolve(any(), any(), any());
        }

        @Test
        @DisplayName("an unrestricted workflow still resolves")
        void unrestrictedWorkflowStillResolves() {
            when(orgAccessGuard.getRestrictedResourceIds(ORG, DENIED_MEMBER, "workflow", "MEMBER"))
                    .thenReturn(Set.of());
            when(applicationRunVersionBatchService.resolve(any(), eq(ORG), eq(DENIED_MEMBER)))
                    .thenReturn(Map.of());

            controller.getApplicationRunVersionBatch(
                    Map.of("workflowIds", List.of(WORKFLOW_ID.toString())), DENIED_MEMBER, ORG, "MEMBER");

            verify(applicationRunVersionBatchService).resolve(any(), eq(ORG), eq(DENIED_MEMBER));
        }
    }

    /**
     * The five handlers that were covered by the ArchUnit rule alone (audit follow-up).
     * An ArchUnit rule proves a gate is CALLED; only a behavioural test proves it DECIDES,
     * and the calls it accepts are an allow-list of names.
     */
    @Nested
    @DisplayName("getLatestRun / getPinnedRun / getApplicationRun / listStepsPaged")
    class RemainingRunReads {

        /** Empty epoch lookups: {@code mapRunWithEpochLookup} dereferences all three. */
        private void stubEmptyEpochLookups() {
            when(workflowEpochRepository.getMaxEpochByRunIds(any())).thenReturn(Map.of());
            when(workflowEpochRepository.getLatestEpochStartedAtByRunIds(any())).thenReturn(Map.of());
            when(workflowEpochService.getLatestEpochWorkDurationByRunIds(any())).thenReturn(Map.of());
        }

        @Test
        @DisplayName("a deny-listed member cannot read the workflow's latest run")
        void deniedMemberCannotReadLatestRun() {
            denyWorkflow(DENIED_MEMBER);

            assertThatThrownBy(() -> controller.getLatestRun(WORKFLOW_ID, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);

            // Refused before the query, so there is no run summary to leak through the response.
            verify(workflowRunRepository, never())
                    .findRunSummariesByWorkflowIdInScope(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("an unrestricted member reaches the query")
        void unrestrictedMemberReachesTheLatestRunQuery() {
            allowWorkflow(DENIED_MEMBER);
            when(workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                    eq(WORKFLOW_ID), eq(DENIED_MEMBER), eq(ORG), any()))
                    .thenReturn(org.springframework.data.domain.Page.empty());

            ResponseEntity<?> response = controller.getLatestRun(WORKFLOW_ID, DENIED_MEMBER, ORG, "MEMBER");

            // 404 = "this workflow has no run", i.e. the gate let the call through and the
            // query answered. A gate that refused would have thrown instead.
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(workflowRunRepository)
                    .findRunSummariesByWorkflowIdInScope(eq(WORKFLOW_ID), eq(DENIED_MEMBER), eq(ORG), any());
        }

        @Test
        @DisplayName("a deny-listed member cannot read the pinned run")
        void deniedMemberCannotReadPinnedRun() {
            denyWorkflow(DENIED_MEMBER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                            Optional.of(orgRun()),
                            com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                            "Gmail"));

            assertThatThrownBy(() -> controller.getPinnedRun(WORKFLOW_ID, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("an unrestricted member still reads the pinned run")
        void unrestrictedMemberStillReadsPinnedRun() {
            allowWorkflow(DENIED_MEMBER);
            stubEmptyEpochLookups();
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any()))
                    .thenReturn(new com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Resolution(
                            Optional.of(orgRun()),
                            com.apimarketplace.orchestrator.trigger.ProductionRunResolver.Outcome.FOUND,
                            "Gmail"));

            ResponseEntity<?> response = controller.getPinnedRun(WORKFLOW_ID, DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a deny-listed member cannot read the application run")
        void deniedMemberCannotReadApplicationRun() {
            denyWorkflow(DENIED_MEMBER);
            when(workflowRunRepository.findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq("application"), eq("pub-1")))
                    .thenReturn(Optional.of(orgRun()));

            assertThatThrownBy(() ->
                    controller.getApplicationRun(WORKFLOW_ID, "pub-1", DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("an unrestricted member still reads the application run")
        void unrestrictedMemberStillReadsApplicationRun() {
            allowWorkflow(DENIED_MEMBER);
            stubEmptyEpochLookups();
            when(workflowRunRepository.findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                    eq(WORKFLOW_ID), eq("application"), eq("pub-1")))
                    .thenReturn(Optional.of(orgRun()));

            ResponseEntity<?> response =
                    controller.getApplicationRun(WORKFLOW_ID, "pub-1", DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a deny-listed member cannot page the run's steps")
        void deniedMemberCannotPageSteps() {
            denyWorkflow(DENIED_MEMBER);
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(orgRun()));

            assertThatThrownBy(() -> controller.listStepsPaged(
                    RUN_UUID, "gmail_fetch", 0, 1, null, null, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);

            // The step page is the payload itself: it must not even be queried.
            verify(workflowStepDataRepository, never())
                    .findByWorkflowRunIdAndStepAliasPagedFiltered(any(), anyString(), any(), any());
        }
    }

    /**
     * {@code getRunStatus} gates on the PARENT run, which the scope check only loads when the
     * caller sent {@code X-Organization-ID}. The deny-list is keyed on the RUN's organization,
     * not on the caller's active workspace, so a guard placed under "the caller sent an org
     * header" is a guard the default (absent header) skips.
     */
    @Nested
    @DisplayName("getRunStatus")
    class RunStatusReads {

        private com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity statusRow() {
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            var status = new com.apimarketplace.orchestrator.domain.WorkflowRunStatusEntity();
            status.setRunId(RUN_UUID);
            status.setWorkflow(workflow);
            status.setTenantId(OWNER);
            status.setOrganizationId(ORG);
            status.setStatus(RunStatus.COMPLETED);
            status.setPayload(Map.of("from", "victim@example.com"));
            return status;
        }

        @Test
        @DisplayName("a deny-listed member cannot poll the run status")
        void deniedMemberCannotPollStatus() {
            denyWorkflow(DENIED_MEMBER);
            when(workflowRunStatusService.findByRunId(RUN_UUID)).thenReturn(Optional.of(statusRow()));
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(orgRun()));

            assertThatThrownBy(() -> controller.getRunStatus(RUN_UUID, DENIED_MEMBER, ORG, "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("an unrestricted member still polls it")
        void unrestrictedMemberStillPolls() {
            allowWorkflow(DENIED_MEMBER);
            when(workflowRunStatusService.findByRunId(RUN_UUID)).thenReturn(Optional.of(statusRow()));
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(orgRun()));

            ResponseEntity<?> response = controller.getRunStatus(RUN_UUID, DENIED_MEMBER, ORG, "MEMBER");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("the gate still fires when the caller sends NO organization header")
        void gateFiresWithoutOrganizationHeader() {
            // The run owner polling their own org run from a personal workspace: the scope
            // check does not need the parent run, so before this fix the deny-list was never
            // consulted on this branch and the trigger payload was returned.
            when(orgAccessGuard.canAccess(ORG, OWNER, "workflow", WORKFLOW_ID.toString(), null))
                    .thenReturn(false);
            when(workflowRunStatusService.findByRunId(RUN_UUID)).thenReturn(Optional.of(statusRow()));
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(orgRun()));

            assertThatThrownBy(() -> controller.getRunStatus(RUN_UUID, OWNER, null, null))
                    .isInstanceOf(OrgAccessDeniedException.class);
        }

        @Test
        @DisplayName("a personal run with no organization header is unaffected")
        void personalRunWithoutOrganizationHeaderIsUnaffected() {
            // Anti-vacuity: the unconditional lookup must not turn "no org header" into a refusal.
            var status = statusRow();
            status.setOrganizationId(null);
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(WORKFLOW_ID);
            WorkflowRunEntity personalRun = new WorkflowRunEntity();
            personalRun.setWorkflow(workflow);
            personalRun.setRunIdPublic(RUN_ID_PUBLIC);
            personalRun.setTenantId(OWNER);
            personalRun.setStatus(RunStatus.COMPLETED);
            when(workflowRunStatusService.findByRunId(RUN_UUID)).thenReturn(Optional.of(status));
            when(workflowRunRepository.findById(RUN_UUID)).thenReturn(Optional.of(personalRun));

            ResponseEntity<?> response = controller.getRunStatus(RUN_UUID, OWNER, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }
}
