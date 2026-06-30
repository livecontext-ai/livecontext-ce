package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("WorkflowListController role write gate")
class WorkflowListControllerRoleWriteGateTest {

    private static final String ORG = "org-1";

    @Test
    @DisplayName("VIEWER cannot rename an org workflow even when no resource restriction exists")
    void viewerCannotRenameOrgWorkflow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity("98", "Original", "98");
        workflow.setId(workflowId);
        workflow.setOrganizationId(ORG);

        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        OrgAccessGuard accessGuard = mock(OrgAccessGuard.class);
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));

        WorkflowListController controller = new WorkflowListController(
                workflowRepository,
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                mock(PublicationClient.class),
                mock(WorkflowManagementService.class),
                mock(WorkflowBoardService.class),
                accessGuard);

        ResponseEntity<?> response = controller.updateWorkflowMetadata(
                workflowId,
                "99",
                ORG,
                "VIEWER",
                Map.of("name", "Changed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(workflow.getName()).isEqualTo("Original");
        verify(workflowRepository, never()).save(any());
        verifyNoInteractions(accessGuard);
    }

    @Test
    @DisplayName("READ-only resource restriction blocks workflow metadata rename")
    void readOnlyRestrictionBlocksWorkflowMetadataRename() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity("99", "Original", "99");
        workflow.setId(workflowId);
        workflow.setOrganizationId(ORG);

        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        OrgAccessGuard accessGuard = mock(OrgAccessGuard.class);
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
        when(accessGuard.canWrite(ORG, "99", "workflow", workflowId.toString(), "MEMBER"))
                .thenReturn(false);

        WorkflowListController controller = new WorkflowListController(
                workflowRepository,
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                mock(PublicationClient.class),
                mock(WorkflowManagementService.class),
                mock(WorkflowBoardService.class),
                accessGuard);

        assertThatThrownBy(() -> controller.updateWorkflowMetadata(
                workflowId,
                "99",
                ORG,
                "MEMBER",
                Map.of("name", "Changed")))
                .isInstanceOf(OrgAccessDeniedException.class);
        assertThat(workflow.getName()).isEqualTo("Original");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("GET workflow detail applies org resource restrictions")
    void getWorkflowDetailAppliesOrgRestriction() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity("99", "Restricted", "99");
        workflow.setId(workflowId);
        workflow.setOrganizationId(ORG);

        WorkflowRepository workflowRepository = mock(WorkflowRepository.class);
        OrgAccessGuard accessGuard = mock(OrgAccessGuard.class);
        when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
        when(accessGuard.canAccess(ORG, "99", "workflow", workflowId.toString(), "MEMBER"))
                .thenReturn(false);

        WorkflowListController controller = new WorkflowListController(
                workflowRepository,
                mock(WorkflowRunRepository.class),
                mock(SignalWaitRepository.class),
                mock(TriggerClient.class),
                mock(PublicationClient.class),
                mock(WorkflowManagementService.class),
                mock(WorkflowBoardService.class),
                accessGuard);

        assertThatThrownBy(() -> controller.getWorkflow(workflowId, "99", ORG, "MEMBER"))
                .isInstanceOf(OrgAccessDeniedException.class);
    }
}
