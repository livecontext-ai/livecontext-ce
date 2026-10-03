package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor;
import com.apimarketplace.orchestrator.services.streaming.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
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

    @Mock
    private StorageService storageService;

    /** Real instance: it's a pure derivation over a plan map, no need to mock it. */
    private final InterfacePlanExtractor interfacePlanExtractor = new InterfacePlanExtractor();

    private InternalAccessController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAccessController(runRepository, workflowRepository, snapshotService,
                interfacePlanExtractor, storageService);
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

    @Nested
    @DisplayName("isInterfaceReferencedByWorkflow() - CASA LC-037 gap 1")
    class IsInterfaceReferencedByWorkflowTests {

        @Test
        @DisplayName("Should return true when the workflow's current plan references the interface")
        void shouldReturnTrueWhenReferenced() {
            UUID workflowId = UUID.randomUUID();
            UUID interfaceId = UUID.randomUUID();
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("tenant-1");
            when(wf.getPlan()).thenReturn(Map.of(
                    "interfaces", List.of(Map.of("id", interfaceId.toString(), "label", "Page"))));
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));

            ResponseEntity<Boolean> response =
                    controller.isInterfaceReferencedByWorkflow(workflowId.toString(), interfaceId.toString());

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("pre-fix regression: should return false for an interface NOT referenced by this workflow's plan")
        void shouldReturnFalseWhenNotReferenced() {
            UUID workflowId = UUID.randomUUID();
            UUID interfaceId = UUID.randomUUID();
            UUID otherInterfaceId = UUID.randomUUID();
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getTenantId()).thenReturn("tenant-1");
            when(wf.getPlan()).thenReturn(Map.of(
                    "interfaces", List.of(Map.of("id", otherInterfaceId.toString(), "label", "Page"))));
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));

            ResponseEntity<Boolean> response =
                    controller.isInterfaceReferencedByWorkflow(workflowId.toString(), interfaceId.toString());

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return false (fail closed) when the workflow does not exist")
        void shouldReturnFalseWhenWorkflowMissing() {
            UUID workflowId = UUID.randomUUID();
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());

            ResponseEntity<Boolean> response =
                    controller.isInterfaceReferencedByWorkflow(workflowId.toString(), UUID.randomUUID().toString());

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return false (fail closed) for an unparsable workflow id")
        void shouldReturnFalseForInvalidWorkflowId() {
            ResponseEntity<Boolean> response =
                    controller.isInterfaceReferencedByWorkflow("not-a-uuid", UUID.randomUUID().toString());

            assertThat(response.getBody()).isFalse();
            verifyNoInteractions(workflowRepository);
        }

        @Test
        @DisplayName("Should return false (fail closed) for an unparsable interface id")
        void shouldReturnFalseForInvalidInterfaceId() {
            ResponseEntity<Boolean> response =
                    controller.isInterfaceReferencedByWorkflow(UUID.randomUUID().toString(), "not-a-uuid");

            assertThat(response.getBody()).isFalse();
        }
    }

    @Nested
    @DisplayName("isSubWorkflowDescendant() - CASA LC-037 gap 2")
    class IsSubWorkflowDescendantTests {

        @Test
        @DisplayName("Should delegate to StorageService and return its answer when true")
        void shouldReturnTrueWhenStorageServiceConfirmsLineage() {
            UUID workflowId = UUID.randomUUID();
            String childRunId = "run-1";
            when(storageService.existsSubWorkflowInvocation(workflowId.toString(), childRunId)).thenReturn(true);

            ResponseEntity<Boolean> response = controller.isSubWorkflowDescendant(workflowId.toString(), childRunId);

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("pre-fix regression: should return false when StorageService finds no matching step output")
        void shouldReturnFalseWhenNoLineageEvidence() {
            UUID workflowId = UUID.randomUUID();
            String childRunId = "run-1";
            when(storageService.existsSubWorkflowInvocation(workflowId.toString(), childRunId)).thenReturn(false);

            ResponseEntity<Boolean> response = controller.isSubWorkflowDescendant(workflowId.toString(), childRunId);

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return false (fail closed) for an unparsable workflow id, without querying storage")
        void shouldReturnFalseForInvalidWorkflowId() {
            ResponseEntity<Boolean> response = controller.isSubWorkflowDescendant("not-a-uuid", "run-1");

            assertThat(response.getBody()).isFalse();
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("pre-fix regression: should return false (fail closed) for a blank childRunId, without "
                + "querying storage - mirrors the workflowId shape guard above")
        void shouldReturnFalseForBlankChildRunId() {
            ResponseEntity<Boolean> response = controller.isSubWorkflowDescendant(UUID.randomUUID().toString(), "   ");

            assertThat(response.getBody()).isFalse();
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("pre-fix regression: should return false (fail closed) for a childRunId longer than the "
                + "run_id_public column width (255), without querying storage")
        void shouldReturnFalseForOversizedChildRunId() {
            String oversized = "run_" + "x".repeat(300);

            ResponseEntity<Boolean> response =
                    controller.isSubWorkflowDescendant(UUID.randomUUID().toString(), oversized);

            assertThat(response.getBody()).isFalse();
            verifyNoInteractions(storageService);
        }
    }
}
