package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.activity.WorkflowEditorsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CASA LC-037 round-3: {@code GET /api/v2/workflows/dag/{workflowId}/versions} is the
 * {@code v2/workflows/dag/(?:[uuid]/versions|...)} branch of the gateway/CE
 * {@code SHARE_APPLICATION_GET_ALLOW} allow-list. Same gap class as the bare workflow GET
 * (see {@link WorkflowListControllerShareGateTest}): pre-fix, {@code canReadHistory}'s
 * ScopeGuard check alone authorized an APPLICATION share holder (resolved to the OWNER's
 * identity) against the version history of ANY workflow the owner ever built.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVersionController - CASA LC-037 share scope on GET /{workflowId}/versions")
class WorkflowVersionControllerShareGateTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowPinService pinService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private WorkflowManagementService workflowManagementService;
    @Mock private WorkflowEditorsService editorsService;

    private MockMvc mockMvc;

    private static final String CALLER_TENANT = "user-99";
    private static final String CALLER_ORG = "org-aaa-bbb";

    @BeforeEach
    void setUp() {
        WorkflowVersionController controller = new WorkflowVersionController(
                versionService, workflowRepository, workflowRunRepository, pinService,
                new ObjectMapper(), orgAccessGuard, workflowManagementService, editorsService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // Since the merge with main, the share scope is main's SharedApplicationScope: the visitor is
    // bound to the shared PUBLICATION (X-Share-Resource-Token), and a workflow is served only when
    // it belongs to that publication (sourcePublicationId). That covers every workflow of a
    // multi-workflow application, which the old single-workflow-id binding wrongly refused.
    private static WorkflowEntity workflowOf(UUID id, UUID publicationId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setTenantId(CALLER_TENANT);
        workflow.setOrganizationId(CALLER_ORG);
        workflow.setSourcePublicationId(publicationId);
        return workflow;
    }

    @Test
    @DisplayName("LC-037: an APPLICATION share token cannot read the version history of a workflow "
            + "outside the shared publication (the owner-wide history read stays closed)")
    void shareContextOtherPublicationWorkflowReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sharedPublication = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.of(workflowOf(id, UUID.randomUUID())));

        mockMvc.perform(get("/api/v2/workflows/dag/" + id + "/versions")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isNotFound());

        Mockito.verifyNoInteractions(versionService, workflowRunRepository);
    }

    @Test
    @DisplayName("LC-037: a share without a publication token is refused (fail closed)")
    void shareContextWithoutTokenReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(workflowRepository.findById(id)).thenReturn(Optional.of(workflowOf(id, UUID.randomUUID())));

        mockMvc.perform(get("/api/v2/workflows/dag/" + id + "/versions")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION"))
                .andExpect(status().isNotFound());

        Mockito.verifyNoInteractions(versionService, workflowRunRepository);
    }

    @Test
    @DisplayName("A workflow of the shared publication keeps its version list readable through the share")
    void shareContextSharedPublicationWorkflowProceeds() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sharedPublication = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.of(workflowOf(id, sharedPublication)));
        when(orgAccessGuard.canAccess(CALLER_ORG, CALLER_TENANT, "workflow", id.toString(), null))
                .thenReturn(true);
        when(versionService.listVersions(id)).thenReturn(Collections.emptyList());
        when(versionService.getCurrentVersion(id)).thenReturn(1);
        when(workflowRunRepository.countRunsByPlanVersion(id)).thenReturn(List.of());

        mockMvc.perform(get("/api/v2/workflows/dag/" + id + "/versions")
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isOk());
    }
}
