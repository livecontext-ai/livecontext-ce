package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-MockMvc security test for the org per-resource write-gate added to
 * the three mutating {@link WorkflowVersionController} endpoints:
 * <ul>
 *   <li>{@code POST /{id}/versions/{v}/restore} - rewrites the live plan (the
 *       exact mutation the saveWorkflow gate blocks).</li>
 *   <li>{@code PATCH /{id}/pin} - flips the production version pointer.</li>
 *   <li>{@code PATCH /{id}/versions/{v}} - renames a version label.</li>
 * </ul>
 *
 * <p>Before this gate a member restricted to READ on a workflow (passing only a
 * strict-scope check, no {@code canWrite}) could side-step the headline
 * saveWorkflow write-gate through restore/pin. Each test asserts:
 * <ol>
 *   <li><b>Deny</b> - {@code OrgAccessGuard.canWrite == false} ⇒ HTTP 403 with the
 *       {@code ORG_ACCESS_DENIED} body, and the underlying mutation is NEVER
 *       performed (no save / no pinService.pin / no rename).</li>
 *   <li><b>Allow</b> - {@code canWrite == true} ⇒ the endpoint proceeds past the
 *       gate (no 403).</li>
 * </ol>
 *
 * <p>Mutation-verify: neutering the new {@code canWrite} check (so it always
 * proceeds) makes every {@code *DeniedReturns403} test fail - the deny path then
 * reaches the mutation and returns 200, not 403.
 *
 * <p>Uses standalone MockMvc + the real {@link OrgAccessDeniedExceptionHandler}
 * so the 403 mapping is exercised end-to-end (not just an in-method throw).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVersionController - org per-resource write-gate (restore/pin/rename)")
class WorkflowVersionControllerOrgWriteGateTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowPinService pinService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowManagementService workflowManagementService;

    private MockMvc mockMvc;

    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String WORKFLOW_ID_STR = WORKFLOW_ID.toString();
    private static final String CALLER_TENANT = "user-77";
    private static final String ORG_ID = "org-team-123";
    private static final UUID SOURCE_PUB_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        WorkflowVersionController controller = new WorkflowVersionController(
                versionService, workflowRepository, workflowRunRepository, pinService,
                new ObjectMapper(), orgAccessGuard, workflowManagementService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OrgAccessDeniedExceptionHandler())
                .build();
    }

    /** Org-scoped, non-APPLICATION workflow owned by the caller's tenant, in CALLER's active org. */
    private WorkflowEntity orgScopedWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(CALLER_TENANT);
        wf.setOrganizationId(ORG_ID);
        wf.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        return wf;
    }

    /** Org-scoped APPLICATION clone owned by the caller, with a source publication id (the deny-list key). */
    private WorkflowEntity applicationWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(CALLER_TENANT);
        wf.setOrganizationId(ORG_ID);
        wf.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        wf.setSourcePublicationId(SOURCE_PUB_ID);
        return wf;
    }

    // ==================== restoreVersion ====================

    @Test
    @DisplayName("restore: READ-restricted member (canWrite=false) → 403, plan NEVER rewritten")
    void restoreDeniedReturns403() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(false);

        mockMvc.perform(post("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/3/restore")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("workflow"));

        // The gate fires BEFORE the version lookup + save - the live plan is never
        // touched, which is the whole point of closing this bypass.
        verify(versionService, never()).getVersion(any(), any(Integer.class));
        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("restore: writer (canWrite=true) → proceeds past the gate (200, plan restored)")
    void restoreAllowedProceeds() throws Exception {
        WorkflowEntity wf = orgScopedWorkflow();
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(true);
        WorkflowPlanVersionEntity versioned = new WorkflowPlanVersionEntity();
        versioned.setVersion(3);
        versioned.setPlan(new HashMap<>(Map.of("name", "v3")));
        when(versionService.getVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(versioned));
        when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(4);

        mockMvc.perform(post("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/3/restore")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Not blocked by the gate - the mutation actually ran.
        verify(workflowRepository).save(any());
    }

    // ==================== restoreVersion on APPLICATION (editable acquired clone) ====================

    @Test
    @DisplayName("restore on APPLICATION: OWNER passes the application gate → 200 (no longer 409 APPLICATION_PLAN_IMMUTABLE)")
    void restoreApplicationOwnerAllowed() throws Exception {
        WorkflowEntity app = applicationWorkflow();
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(app));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("OWNER")))
                .thenReturn(true);
        // The application-scoped gate is the single source of truth (mocked here as a
        // no-op for the owner; its real allow/deny logic is covered in
        // WorkflowManagementServiceSaveOrgWriteGateTest).
        WorkflowPlanVersionEntity versioned = new WorkflowPlanVersionEntity();
        versioned.setVersion(2);
        versioned.setPlan(new HashMap<>(Map.of("name", "owner edit v2")));
        when(versionService.getVersion(WORKFLOW_ID, 2)).thenReturn(Optional.of(versioned));
        when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(3);

        mockMvc.perform(post("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/2/restore")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.restoredFromVersion").value(2));

        // The application gate was consulted, and the restore actually ran (no 409).
        verify(workflowManagementService).assertApplicationInstanceWritable(app, CALLER_TENANT, "OWNER");
        verify(workflowRepository).save(any());
    }

    @Test
    @DisplayName("restore on APPLICATION: deny-list-restricted member → 403 (application gate), plan NEVER rewritten")
    void restoreApplicationRestrictedMemberDenied() throws Exception {
        WorkflowEntity app = applicationWorkflow();
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(app));
        // Member is NOT restricted on the generic "workflow" resource (that gate passes)...
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(true);
        // ...but IS restricted on the application: the application gate throws, keyed on the source publication id.
        doThrow(new OrgAccessDeniedException("application", SOURCE_PUB_ID.toString()))
                .when(workflowManagementService).assertApplicationInstanceWritable(app, CALLER_TENANT, "MEMBER");

        mockMvc.perform(post("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/2/restore")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("application"));

        // Blocked at the application gate, before any version lookup / plan rewrite.
        verify(versionService, never()).getVersion(any(), any(Integer.class));
        verify(workflowRepository, never()).save(any());
    }

    // ==================== pinVersion ====================

    @Test
    @DisplayName("pin: READ-restricted member (canWrite=false) → 403, pinService.pin NEVER called")
    void pinDeniedReturns403() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(false);

        mockMvc.perform(patch("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/pin")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("workflow"));

        // The production version pointer is never flipped on the deny path.
        verify(pinService, never()).pin(any(), any(), any(), any());
    }

    @Test
    @DisplayName("pin: writer (canWrite=true) → proceeds to pinService.pin (200)")
    void pinAllowedProceeds() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(true);
        when(pinService.pin(eq(WORKFLOW_ID), eq(CALLER_TENANT), eq(ORG_ID), eq(5)))
                .thenReturn(new WorkflowPinService.PinResult.Success(5, "run_1_a"));

        mockMvc.perform(patch("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/pin")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.pinnedVersion").value(5));

        verify(pinService).pin(eq(WORKFLOW_ID), eq(CALLER_TENANT), eq(ORG_ID), eq(5));
    }

    // ==================== renameVersion ====================

    @Test
    @DisplayName("rename: READ-restricted member (canWrite=false) → 403, label NEVER changed")
    void renameDeniedReturns403() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(false);

        mockMvc.perform(patch("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/3")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"hacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("workflow"));

        verify(versionService, never()).renameVersion(any(), any(Integer.class), any());
    }

    @Test
    @DisplayName("rename: writer (canWrite=true) → proceeds to renameVersion (200)")
    void renameAllowedProceeds() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq("MEMBER")))
                .thenReturn(true);
        WorkflowPlanVersionEntity updated = new WorkflowPlanVersionEntity();
        updated.setVersion(3);
        updated.setLabel("renamed");
        when(versionService.renameVersion(WORKFLOW_ID, 3, "renamed")).thenReturn(updated);

        mockMvc.perform(patch("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/3")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID)
                        .header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.label").value("renamed"));

        verify(versionService).renameVersion(WORKFLOW_ID, 3, "renamed");
    }

    // ==================== null orgRole still enforces (canWrite contract) ====================

    @Test
    @DisplayName("restore: absent X-Organization-Role still hits canWrite (gate not skipped on null role)")
    void restoreNullRoleStillEnforced() throws Exception {
        when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(orgScopedWorkflow()));
        // orgRole=null is forwarded to canWrite - the guard (not the controller)
        // decides; here it denies, proving the controller does not short-circuit
        // the gate just because the role header is absent.
        when(orgAccessGuard.canWrite(eq(ORG_ID), eq(CALLER_TENANT), eq("workflow"), eq(WORKFLOW_ID_STR), eq(null)))
                .thenReturn(false);

        mockMvc.perform(post("/api/v2/workflows/dag/" + WORKFLOW_ID_STR + "/versions/3/restore")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", ORG_ID))
                .andExpect(status().isForbidden());

        verify(workflowRepository, never()).save(any());
    }
}
