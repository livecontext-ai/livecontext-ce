package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow Status Toggle")
class WorkflowStatusToggleTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @InjectMocks
    private WorkflowManagementService service;

    private UUID workflowId;
    private String tenantId;
    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() {
        workflowId = UUID.randomUUID();
        tenantId = "tenant-123";
        workflow = new WorkflowEntity();
        workflow.setId(workflowId);
        workflow.setTenantId(tenantId);
        workflow.setName("Test Workflow");
    }

    @Nested
    @DisplayName("DRAFT to ACTIVE")
    class DraftToActive {

        @Test
        @DisplayName("should toggle DRAFT to ACTIVE and set isActive=true")
        void toggleDraftToActive() {
            workflow.setStatus(WorkflowStatus.DRAFT);
            workflow.setIsActive(false);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            WorkflowEntity result = service.updateWorkflowStatus(workflowId, tenantId, WorkflowStatus.ACTIVE);

            assertThat(result.getStatus()).isEqualTo(WorkflowStatus.ACTIVE);
            assertThat(result.getIsActive()).isTrue();
            verify(workflowRepository).save(workflow);
        }
    }

    @Nested
    @DisplayName("ACTIVE to DRAFT")
    class ActiveToDraft {

        @Test
        @DisplayName("should toggle ACTIVE to DRAFT and set isActive=false")
        void toggleActiveToDraft() {
            workflow.setStatus(WorkflowStatus.ACTIVE);
            workflow.setIsActive(true);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            WorkflowEntity result = service.updateWorkflowStatus(workflowId, tenantId, WorkflowStatus.DRAFT);

            assertThat(result.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
            assertThat(result.getIsActive()).isFalse();
            verify(workflowRepository).save(workflow);
        }
    }

    @Nested
    @DisplayName("Invalid status")
    class InvalidStatus {

        @Test
        @DisplayName("should reject ARCHIVED status")
        void rejectArchivedStatus() {
            assertThatThrownBy(() ->
                service.updateWorkflowStatus(workflowId, tenantId, WorkflowStatus.ARCHIVED)
            )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only DRAFT and ACTIVE");

            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject INACTIVE status")
        void rejectInactiveStatus() {
            assertThatThrownBy(() ->
                service.updateWorkflowStatus(workflowId, tenantId, WorkflowStatus.INACTIVE)
            )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only DRAFT and ACTIVE");

            verify(workflowRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Authorization")
    class Authorization {

        @Test
        @DisplayName("should reject unauthorized tenant")
        void rejectUnauthorizedTenant() {
            workflow.setStatus(WorkflowStatus.DRAFT);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(workflow));

            assertThatThrownBy(() ->
                service.updateWorkflowStatus(workflowId, "other-tenant", WorkflowStatus.ACTIVE)
            )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unauthorized");

            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("should reject when workflow not found")
        void rejectWhenNotFound() {
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                service.updateWorkflowStatus(workflowId, tenantId, WorkflowStatus.ACTIVE)
            )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow not found");

            verify(workflowRepository, never()).save(any());
        }
    }
}
