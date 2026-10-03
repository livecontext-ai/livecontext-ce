package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.NestedPaginationResponse;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageColumnDefinition;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageNestedRow;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.StorageNestedService;
import com.apimarketplace.orchestrator.services.StorageSkeletonService;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowStepService;
import com.apimarketplace.orchestrator.stepdata.DetailedStepDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The step-output surface obeys the same intra-organization deny-list as the run surface
 * (LC-012, security audit 2026-08-13, second remediation pass).
 *
 * <p>The first pass gated {@code WorkflowRunQueryController} and left this sibling, in the
 * same package and under the same {@code /api/workflows} prefix, serving the identical
 * persisted payload through eleven handlers: a member denied the Gmail workflow got a 404 on
 * the run summary and then read the mailbox itself at
 * {@code .../steps/{id}/output/value?path=output...}, drillable by JSON path. Workspace scope
 * ({@code isRunInScope}) was the only check, and workspace scope is exactly what a
 * deny-listed member still satisfies.
 *
 * <p>Each handler here is tested as a deny/allow pair: a deny-only test passes just as well
 * against a handler that refuses everybody.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("WorkflowStepOutputController - organization read gate (LC-012)")
class WorkflowStepOutputControllerOrgReadGateTest {

    private static final String RUN_ID = "run_<id>";
    private static final String CALLER = "member-1";
    private static final String ORG = "org-1";
    private static final String OWNER = "owner-1";
    private static final UUID WORKFLOW_ID = UUID.fromString("00000000-0000-4000-8000-00000000000f");
    private static final UUID STORAGE_ID = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final Long STEP_ID = 7L;

    @Mock private WorkflowStepService workflowStepService;
    @Mock private StorageNestedService storageNestedService;
    @Mock private StorageSkeletonService storageSkeletonService;
    @Mock private StepOutputService stepOutputService;
    @Mock private DetailedStepDataService detailedStepDataService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowStepOutputController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowStepOutputController(workflowStepService, storageNestedService,
                storageSkeletonService, stepOutputService, detailedStepDataService, runRepository,
                orgAccessGuard);

        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "tenantId", OWNER);
        ReflectionTestUtils.setField(run, "organizationId", ORG);
        WorkflowEntity workflow = new WorkflowEntity();
        ReflectionTestUtils.setField(workflow, "id", WORKFLOW_ID);
        ReflectionTestUtils.setField(run, "workflow", workflow);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

        when(workflowStepService.getOutputStorageId(STEP_ID, RUN_ID, OWNER))
                .thenReturn(Optional.of(STORAGE_ID));
    }

    /** The caller is inside the workspace; only the deny-list decides. */
    private MockHttpServletRequest request(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", CALLER);
        request.addHeader("X-Organization-ID", ORG);
        if (role != null) {
            request.addHeader("X-Organization-Role", role);
        }
        return request;
    }

    private void allowedFor(String role) {
        when(orgAccessGuard.canAccess(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), role))
                .thenReturn(true);
    }

    private void deniedFor(String role) {
        when(orgAccessGuard.canAccess(ORG, CALLER, "workflow", WORKFLOW_ID.toString(), role))
                .thenReturn(false);
    }

    // ──────────────── the raw payload readers ────────────────

    @Test
    @DisplayName("a denied MEMBER cannot read a value at a JSON path (the mailbox payload itself)")
    void deniedMemberCannotReadValueAtPath() {
        deniedFor("MEMBER");

        ResponseEntity<String> response = controller.getStepOutputValueAtPath(
                "wf", RUN_ID, STEP_ID, request("MEMBER"), null, "output.messages.0.body");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(storageSkeletonService, never()).getValueAtPath(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an allowed MEMBER still reads a value at a JSON path")
    void allowedMemberReadsValueAtPath() {
        allowedFor("MEMBER");
        when(storageSkeletonService.getValueAtPath(STORAGE_ID, OWNER, "output.subject"))
                .thenReturn(Optional.of("hello"));

        ResponseEntity<String> response = controller.getStepOutputValueAtPath(
                "wf", RUN_ID, STEP_ID, request("MEMBER"), null, "output.subject");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("hello");
    }

    @Test
    @DisplayName("a denied MEMBER cannot read an object at a path")
    void deniedMemberCannotReadObjectAtPath() {
        deniedFor("MEMBER");

        assertThat(controller.getStepOutputObjectAtPath("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output.messages.0").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read an array item, and an allowed one can")
    void arrayItemIsGatedBothWays() {
        deniedFor("MEMBER");
        assertThat(controller.getStepOutputArrayItem("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output.messages", 0).getStatusCode().value()).isEqualTo(404);

        allowedFor("ADMIN");
        when(storageSkeletonService.getArrayItemAtIndex(STORAGE_ID, OWNER, "output.messages", 0))
                .thenReturn(Optional.of(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));
        assertThat(controller.getStepOutputArrayItem("wf", RUN_ID, STEP_ID, request("ADMIN"),
                null, "output.messages", 0).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a denied MEMBER cannot list the items, and an allowed one can")
    void itemsAreGatedBothWays() {
        deniedFor("MEMBER");
        assertThat(controller.getStepOutputItems("wf", RUN_ID, STEP_ID, request("MEMBER"), null, 10, 1)
                .getStatusCode().value()).isEqualTo(404);

        allowedFor("MEMBER");
        NestedPaginationResponse<StorageNestedRow> page =
                new NestedPaginationResponse<>(List.of(), 0, null, false, 0, "", null, List.of());
        when(storageNestedService.getNestedData(any(), anyString(), anyString(), any())).thenReturn(page);
        assertThat(controller.getStepOutputItems("wf", RUN_ID, STEP_ID, request("MEMBER"), null, 10, 1)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read the nested items")
    void deniedMemberCannotReadNestedItems() {
        deniedFor("MEMBER");

        assertThat(controller.getStepOutputNestedData("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output", 1, 20, null, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read the skeleton (the payload's shape)")
    void deniedMemberCannotReadSkeleton() {
        deniedFor("MEMBER");

        assertThat(controller.getStepOutputSkeleton("wf", RUN_ID, STEP_ID, request("MEMBER"), null)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read the array info")
    void deniedMemberCannotReadArrayInfo() {
        deniedFor("MEMBER");

        assertThat(controller.getStepOutputArrayInfo("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output.messages").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read the detailed step data, by alias or by id")
    void deniedMemberCannotReadDetailedData() {
        deniedFor("MEMBER");

        assertThat(controller.getDetailedStepData("wf", RUN_ID, "mcp:gmail", request("MEMBER"),
                null, 1, 20, null, null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.getDetailedStepDataById("wf", RUN_ID, STEP_ID, request("MEMBER"), null)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a denied MEMBER cannot read the merged outputs for an alias")
    void deniedMemberCannotReadMergedOutputs() {
        deniedFor("MEMBER");

        assertThat(controller.getMergedStepOutputItems("wf", RUN_ID, "mcp:gmail", request("MEMBER"),
                null, 10, 1).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("the column definitions are gated too: the shape of a payload nobody may read")
    void columnDefinitionsAreGated() {
        deniedFor("MEMBER");
        assertThat(controller.getStepOutputColumnDefinitions("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output").getStatusCode().value()).isEqualTo(404);

        allowedFor("MEMBER");
        when(storageSkeletonService.getColumnDefinitions(STORAGE_ID, OWNER, "output"))
                .thenReturn(List.<StorageColumnDefinition>of());
        assertThat(controller.getStepOutputColumnDefinitions("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output").getStatusCode().value()).isEqualTo(200);
    }

    // ──────────────── the shapes the gate must NOT change ────────────────

    @Test
    @DisplayName("a personal run (no organization) is readable with no role at all")
    void personalRunNeedsNoRole() {
        // The default case, explicitly: outside a workspace there is no role and no deny-list,
        // so a guard that failed closed here would break every personal run.
        WorkflowRunEntity personal = new WorkflowRunEntity();
        ReflectionTestUtils.setField(personal, "tenantId", CALLER);
        ReflectionTestUtils.setField(personal, "organizationId", null);
        when(runRepository.findByRunIdPublic("run_personal")).thenReturn(Optional.of(personal));
        when(workflowStepService.getOutputStorageId(STEP_ID, "run_personal", CALLER))
                .thenReturn(Optional.of(STORAGE_ID));
        when(storageSkeletonService.getValueAtPath(STORAGE_ID, CALLER, "output.subject"))
                .thenReturn(Optional.of("hello"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", CALLER);

        assertThat(controller.getStepOutputValueAtPath("wf", "run_personal", STEP_ID, request,
                null, "output.subject").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("an out-of-workspace caller is still refused before the deny-list is consulted")
    void outOfScopeCallerIsRefusedFirst() {
        // The scope check has not been replaced by the role check: both must hold.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", CALLER);
        request.addHeader("X-Organization-ID", "org-other");
        request.addHeader("X-Organization-Role", "OWNER");

        assertThat(controller.getStepOutputValueAtPath("wf", RUN_ID, STEP_ID, request, null, "output")
                .getStatusCode().value()).isEqualTo(404);
        verify(orgAccessGuard, never()).canAccess(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a missing caller header is refused without asking the guard")
    void missingCallerIsRefused() {
        assertThat(controller.getStepOutputValueAtPath("wf", RUN_ID, STEP_ID,
                new MockHttpServletRequest(), null, "output").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("an absent OrgAccessGuard bean fails closed rather than silently allowing")
    void absentGuardFailsClosed() {
        WorkflowStepOutputController unguarded = new WorkflowStepOutputController(
                workflowStepService, storageNestedService, storageSkeletonService, stepOutputService,
                detailedStepDataService, runRepository, null);

        assertThat(unguarded.getStepOutputValueAtPath("wf", RUN_ID, STEP_ID, request("MEMBER"),
                null, "output").getStatusCode().value()).isEqualTo(404);
    }
}
