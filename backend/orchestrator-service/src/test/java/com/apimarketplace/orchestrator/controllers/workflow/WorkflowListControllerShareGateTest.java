package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CASA LC-037 round-3: {@code GET /api/workflows/{workflowId}} (the bare workflow read) is the
 * handler the gateway/CE {@code SHARE_APPLICATION_GET_ALLOW} allow-list actually matches for the
 * {@code workflows/[uuid](/runs/...)?} branch - NOT the identically-named
 * {@link WorkflowCrudController#getWorkflow}, which lives under {@code /v2/workflows/dag} and is
 * not reachable by that pattern. Before this fix the handler authorized purely on
 * {@code ScopeGuard.isInStrictScope(tenantId, orgId, ...)}: an APPLICATION share token resolves
 * the visitor to the OWNER's identity, so that check alone let a share holder read ANY workflow
 * the owner ever built by swapping the {@code workflowId} path segment - the same class of gap
 * {@code ShareContextResourceBinding} already closed for interfaces and files.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowListController - CASA LC-037 share scope on GET /{workflowId}")
class WorkflowListControllerShareGateTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Mock private TriggerClient triggerClient;
    @Mock private PublicationClient publicationClient;
    @Mock private WorkflowManagementService workflowService;
    @Mock private WorkflowBoardService boardService;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private WorkflowFolderService folderService;

    private MockMvc mockMvc;

    private static final String CALLER_TENANT = "user-99";
    private static final String CALLER_ORG = "org-aaa-bbb";

    @BeforeEach
    void setUp() {
        WorkflowListController controller = new WorkflowListController(
                workflowRepository, workflowRunRepository, signalWaitRepository, triggerClient,
                publicationClient, workflowService, boardService, orgAccessService, folderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // The share scope is main's SharedApplicationScope: the visitor is bound to the shared
    // PUBLICATION (X-Share-Resource-Token) and is served only its workflows (sourcePublicationId),
    // every workflow of a multi-workflow application included.
    private WorkflowEntity workflowOf(UUID id, UUID publicationId) {
        WorkflowEntity workflow = ownedWorkflow(id);
        workflow.setSourcePublicationId(publicationId);
        return workflow;
    }

    private WorkflowEntity ownedWorkflow(UUID id) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setTenantId(CALLER_TENANT);
        workflow.setOrganizationId(CALLER_ORG);
        workflow.setName("Owner's Workflow");
        return workflow;
    }

    @Test
    @DisplayName("LC-037: an APPLICATION share token cannot read a workflow outside the shared publication, "
            + "even one of the same owner - closes the owner-wide read the bare workflow GET allowed")
    void shareContextOtherPublicationWorkflowReturns404() throws Exception {
        UUID requestedWorkflowId = UUID.randomUUID();
        UUID sharedPublication = UUID.randomUUID();
        // Same owner, same tenant/org - proves the rejection comes from the resource binding,
        // not from ScopeGuard (which would otherwise happily allow this).
        when(workflowRepository.findById(requestedWorkflowId))
                .thenReturn(Optional.of(workflowOf(requestedWorkflowId, UUID.randomUUID())));

        mockMvc.perform(get("/api/workflows/" + requestedWorkflowId)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A workflow of the shared publication stays readable through the share")
    void shareContextSharedPublicationWorkflowProceeds() throws Exception {
        UUID id = UUID.randomUUID();
        UUID sharedPublication = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.of(workflowOf(id, sharedPublication)));
        when(orgAccessService.canAccess(CALLER_ORG, CALLER_TENANT, "workflow", id.toString(), null))
                .thenReturn(true);
        when(triggerClient.getTokensForWorkflow(any())).thenReturn(Map.of());

        mockMvc.perform(get("/api/workflows/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", sharedPublication.toString()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A non-share (authenticated builder) call is unaffected by the gate - no X-Share-Context "
            + "header means the share scope does not apply")
    void nonShareCallUnaffected() throws Exception {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findById(id)).thenReturn(Optional.of(ownedWorkflow(id)));
        when(orgAccessService.canAccess(CALLER_ORG, CALLER_TENANT, "workflow", id.toString(), null))
                .thenReturn(true);
        when(triggerClient.getTokensForWorkflow(any())).thenReturn(Map.of());

        mockMvc.perform(get("/api/workflows/" + id)
                        .header("X-User-ID", CALLER_TENANT)
                        .header("X-Organization-ID", CALLER_ORG))
                .andExpect(status().isOk());

        Mockito.verifyNoInteractions(signalWaitRepository);
    }
}
