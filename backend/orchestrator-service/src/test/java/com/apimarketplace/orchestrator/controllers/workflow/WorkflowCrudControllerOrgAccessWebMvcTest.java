package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.access.OrgAccessDeniedExceptionHandler;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowResponseFactory;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.trigger.TriggerTypeDetector;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

/**
 * Standalone-MockMvc integration test for the
 * {@code GET /api/v2/workflows/dag/{workflowId}} endpoint focused on the
 * post-V261 strict-org scope gate.
 *
 * <p>Pins four contracts:
 * <ol>
 *   <li>Same-org call (caller-org == workflow-org) returns 200.</li>
 *   <li>Cross-workspace call (different org) returns 404 (NOT 200 with leaked
 *       payload - the pre-fix bug treated workflows as in-scope as long as
 *       the workflow's tenant_id matched the caller, leaking cross-org rows
 *       for users with multiple memberships).</li>
 *   <li>Missing X-User-ID header is still 404 (no auth-fall-through).</li>
 *   <li>Workflow not found returns 404 (no information leak about IDs).</li>
 * </ol>
 *
 * <p>Uses standalone MockMvc + manual reflection injection rather than
 * {@code @WebMvcTest} because the orchestrator main app enables JPA at the
 * config level, which forces a full {@code entityManagerFactory} bootstrap
 * the test slice doesn't need.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudController - strict-org gate on GET /{workflowId}")
class WorkflowCrudControllerOrgAccessWebMvcTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowManagementService workflowManagementService;
    @Mock private WorkflowResponseFactory responseFactory;
    @Mock private TriggerClient triggerClient;
    @Mock private TriggerTypeDetector triggerTypeDetector;
    @Mock private WorkflowPlanVersionService versionService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowControllerHelper helper;
    private MockMvc mockMvc;

    private static final String CALLER_TENANT = "user-99";
    private static final String CALLER_ORG = "org-aaa-bbb";
    private static final String OTHER_ORG = "org-xxx-yyy";

    @BeforeEach
    void setUp() {
        // Real WorkflowControllerHelper but with its triggerClient field set to
        // our mock so buildWorkflowResponse(...) doesn't NPE on token lookup.
        helper = new WorkflowControllerHelper();
        ReflectionTestUtils.setField(helper, "triggerClient", triggerClient);

        WorkflowCrudController controller = new WorkflowCrudController();
        // Field injection (controller uses @Autowired field-level).
        ReflectionTestUtils.setField(controller, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(controller, "workflowManagementService", workflowManagementService);
        ReflectionTestUtils.setField(controller, "responseFactory", responseFactory);
        ReflectionTestUtils.setField(controller, "helper", helper);
        ReflectionTestUtils.setField(controller, "triggerClient", triggerClient);
        ReflectionTestUtils.setField(controller, "triggerTypeDetector", triggerTypeDetector);
        ReflectionTestUtils.setField(controller, "versionService", versionService);
        ReflectionTestUtils.setField(controller, "breakdownService", breakdownService);
        ReflectionTestUtils.setField(controller, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(controller, "orgAccessGuard", orgAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OrgAccessDeniedExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Same-org caller (caller-org == workflow-org) returns 200")
    void sameOrgScopeAllows200() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setTenantId(CALLER_TENANT);
        workflow.setOrganizationId(CALLER_ORG);
        workflow.setName("My Workflow");
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        when(workflowManagementService.getWorkflow(id)).thenReturn(Optional.of(workflow));
        when(orgAccessGuard.canAccess(CALLER_ORG, CALLER_TENANT, "workflow", id.toString(), "MEMBER"))
                .thenReturn(true);
        when(triggerClient.getTokensForWorkflow(any())).thenReturn(Map.of());

        mockMvc.perform(get("/api/v2/workflows/dag/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Same-org caller denied by org restriction returns 403")
    void sameOrgRestrictedCallerReturns403() throws Exception {
        UUID id = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setTenantId(CALLER_TENANT);
        workflow.setOrganizationId(CALLER_ORG);
        workflow.setName("Restricted Workflow");
        workflow.setStatus(WorkflowEntity.WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        when(workflowManagementService.getWorkflow(id)).thenReturn(Optional.of(workflow));
        when(orgAccessGuard.canAccess(eq(CALLER_ORG), eq(CALLER_TENANT), eq("workflow"), eq(id.toString()), eq("MEMBER")))
                .thenReturn(false);

        mockMvc.perform(get("/api/v2/workflows/dag/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("workflow"));
    }

    @Test
    @DisplayName("Cross-workspace caller (caller-org != workflow-org) returns 404 - strict-org gate (V261 regression)")
    void crossWorkspaceCallerReturns404() throws Exception {
        // Pre-fix the scope check was a tenant-only match: as long as
        // workflow.tenantId == caller.userId, the row was leaked. A user in two
        // orgs (or who created a workflow in personal then was added to a team)
        // could read across workspaces.
        UUID id = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setTenantId(CALLER_TENANT);                 // caller IS the owner
        workflow.setOrganizationId(OTHER_ORG);               // ... but workflow lives in a different workspace
        workflow.setName("Off-workspace Workflow");
        when(workflowManagementService.getWorkflow(id)).thenReturn(Optional.of(workflow));

        mockMvc.perform(get("/api/v2/workflows/dag/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Workflow not found returns 404")
    void notFoundReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowManagementService.getWorkflow(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v2/workflows/dag/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Malformed workflowId returns 400")
    void malformedWorkflowIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v2/workflows/dag/not-a-uuid")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /dag threads X-Organization-Role into the write-gate and maps its denial to 403 (not 500)")
    void saveWorkflowThreadsOrgRoleAndMaps403() throws Exception {
        UUID id = UUID.randomUUID();
        // The org per-resource write-gate lives in WorkflowManagementService.saveWorkflow(...,orgRole).
        // eq("MEMBER") on the 5th arg PROVES the controller threads X-Organization-Role - a null/absent
        // role would not match the stub, so no 403 would be produced. The OrgAccessDeniedException must
        // surface as 403 via the re-throw + OrgAccessDeniedExceptionHandler, NOT catch(Exception) → 500.
        when(workflowManagementService.saveWorkflow(any(), any(), any(), eq(CALLER_ORG), eq("MEMBER")))
                .thenThrow(new com.apimarketplace.auth.client.access.OrgAccessDeniedException("workflow", id.toString()));

        mockMvc.perform(post("/api/v2/workflows/dag")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Organization-Role", "MEMBER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planJson\":\"{}\",\"workflowId\":\"" + id + "\",\"dataInputs\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ORG_ACCESS_DENIED"))
                .andExpect(jsonPath("$.resourceType").value("workflow"));
    }
}
