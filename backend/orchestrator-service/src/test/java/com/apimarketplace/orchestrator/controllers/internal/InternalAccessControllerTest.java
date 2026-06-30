package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAccessController (Orchestrator)")
class InternalAccessControllerTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private SnapshotService snapshotService;

    private InternalAccessController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAccessController(runRepository, workflowRepository, snapshotService);
    }

    @Nested
    @DisplayName("checkRunAccess()")
    class CheckRunAccessTests {

        @Test
        @DisplayName("Should return false when run not found")
        void shouldReturnFalseWhenNotFound() {
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.empty());

            ResponseEntity<Boolean> response = controller.checkRunAccess("run-1", "user-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return true when user owns the run")
        void shouldReturnTrueForOwner() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("user-1");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<Boolean> response = controller.checkRunAccess("run-1", "user-1", null);

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("Should return false when different user")
        void shouldReturnFalseForDifferentUser() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getTenantId()).thenReturn("user-1");
            when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            ResponseEntity<Boolean> response = controller.checkRunAccess("run-1", "user-2", null);

            assertThat(response.getBody()).isFalse();
        }
    }

    @Nested
    @DisplayName("checkWorkflowAccess()")
    class CheckWorkflowAccessTests {

        @Test
        @DisplayName("Should return false for invalid UUID")
        void shouldReturnFalseForInvalidUuid() {
            ResponseEntity<Boolean> response = controller.checkWorkflowAccess("not-a-uuid", "user-1", null);

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return false when workflow not found")
        void shouldReturnFalseWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(workflowRepository.findById(id)).thenReturn(Optional.empty());

            ResponseEntity<Boolean> response = controller.checkWorkflowAccess(id.toString(), "user-1", null);

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return true when user owns the workflow")
        void shouldReturnTrueForOwner() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("user-1");
            when(workflowRepository.findById(id)).thenReturn(Optional.of(wf));

            ResponseEntity<Boolean> response = controller.checkWorkflowAccess(id.toString(), "user-1", null);

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("Should return false when different user")
        void shouldReturnFalseForDifferentUser() {
            UUID id = UUID.randomUUID();
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("user-1");
            when(workflowRepository.findById(id)).thenReturn(Optional.of(wf));

            ResponseEntity<Boolean> response = controller.checkWorkflowAccess(id.toString(), "user-2", null);

            assertThat(response.getBody()).isFalse();
        }
    }

    @Nested
    @DisplayName("triggerSnapshot()")
    class TriggerSnapshotTests {

        @Test
        @DisplayName("Should call snapshotService and return OK")
        void shouldTriggerSnapshot() {
            ResponseEntity<Void> response = controller.triggerSnapshot("run-1");

            verify(snapshotService).sendSnapshotImmediate("run-1");
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return OK even when snapshot fails")
        void shouldReturnOkEvenOnError() {
            doThrow(new RuntimeException("DB error")).when(snapshotService).sendSnapshotImmediate("run-1");

            ResponseEntity<Void> response = controller.triggerSnapshot("run-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
