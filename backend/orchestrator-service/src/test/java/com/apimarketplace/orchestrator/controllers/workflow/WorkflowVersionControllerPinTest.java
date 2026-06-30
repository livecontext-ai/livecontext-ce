package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the WorkflowVersionController pin/unpin endpoint.
 *
 * The controller now delegates pin/unpin decisions to {@link WorkflowPinService}.
 * This test focuses on HTTP-level mapping of {@link WorkflowPinService.PinResult}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowVersionController - Pin/Unpin Endpoint")
class WorkflowVersionControllerPinTest {

    @Mock private WorkflowPlanVersionService versionService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowPinService pinService;
    @Mock private com.apimarketplace.auth.client.access.OrgAccessGuard orgAccessGuard;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService;

    private WorkflowVersionController controller;

    private static final UUID WORKFLOW_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String WORKFLOW_ID_STR = WORKFLOW_ID.toString();
    private static final String TENANT_ID = "tenant-owner";

    @BeforeEach
    void setUp() {
        controller = new WorkflowVersionController(versionService, workflowRepository, workflowRunRepository, pinService,
                new com.fasterxml.jackson.databind.ObjectMapper(), orgAccessGuard, workflowManagementService);
    }

    // ==================== Restore Version on APPLICATION ====================

    @Nested
    @DisplayName("Restore Version - APPLICATION owner-writable gate")
    class RestoreVersionApplicationGate {

        @Test
        @DisplayName("POST /versions/{v}/restore on APPLICATION consults the application write-gate and proceeds (no longer 409 APPLICATION_PLAN_IMMUTABLE)")
        void restoreVersionOnApplicationConsultsGateAndProceeds() {
            WorkflowEntity app = new WorkflowEntity();
            org.springframework.test.util.ReflectionTestUtils.setField(app, "id", WORKFLOW_ID);
            app.setTenantId(TENANT_ID);
            app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(app));
            WorkflowPlanVersionEntity versioned = new WorkflowPlanVersionEntity();
            versioned.setVersion(3);
            versioned.setPlan(new HashMap<>(Map.of("name", "v3")));
            when(versionService.getVersion(WORKFLOW_ID, 3)).thenReturn(Optional.of(versioned));
            when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(4);

            ResponseEntity<?> response = controller.restoreVersion(WORKFLOW_ID_STR, 3, TENANT_ID, null, null);

            // The blanket 409 is gone: the OWNER may now roll back to an accrued
            // version of their editable application clone (basePlan stays the floor
            // via reset-plan). The application deny-list gate is still consulted - its
            // allow/deny logic is the single source of truth in WorkflowManagementService
            // (mocked here; the real allow/deny behaviour is covered in
            // WorkflowVersionControllerOrgWriteGateTest + WorkflowManagementServiceSaveOrgWriteGateTest).
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(workflowManagementService).assertApplicationInstanceWritable(app, TENANT_ID, null);
            verify(workflowRepository).save(any());
        }
    }

    // ==================== Pin Valid Version ====================

    @Nested
    @DisplayName("Pin Valid Version")
    class PinValidVersionTests {

        @Test
        @DisplayName("Should pin version and return 200 with success response")
        void shouldPinVersionSuccessfully() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(5)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(5, "run_<id>"));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("pinnedVersion", 5);
            assertThat(body).containsEntry("productionRunIdPublic", "run_<id>");
            assertThat((String) body.get("message")).contains("pinned as production");
        }

        @Test
        @DisplayName("Should return 200 when overwriting existing pin with new version")
        void shouldOverwriteExistingPin() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(7)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(7, "run_<id>"));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 7));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("pinnedVersion", 7);
        }

        @Test
        @DisplayName("Pin response includes productionRunIdPublic for frontend auto-redirect (regression: live ticks invisible in builder edit URL, 2026-05-06)")
        void pinResponseExposesProductionRunIdPublic() {
            // The frontend uses this field to redirect to /run/{id} so the user can
            // watch the schedule fire live. Without it, the builder edit URL doesn't
            // subscribe to the WS channel (see WorkflowModeContext) and ticks are
            // invisible until the user manually opens the run from the run list.
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(3)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(3, "run_1234_abc"));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 3));

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("productionRunIdPublic", "run_1234_abc");
        }

        @Test
        @DisplayName("Pin version 1 (minimum) should work")
        void shouldPinVersionOne() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(1)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(1, "run_<id>"));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 1));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ==================== Unpin ====================

    @Nested
    @DisplayName("Unpin (version = null)")
    class UnpinTests {

        @Test
        @DisplayName("Should unpin and return success response")
        void shouldUnpinSuccessfully() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), isNull()))
                    .thenReturn(new WorkflowPinService.PinResult.Success(null, null));

            Map<String, Object> request = new HashMap<>();
            request.put("version", null);

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("pinnedVersion", null);
            assertThat((String) body.get("message")).contains("Unpinned");
        }

        @Test
        @DisplayName("Unpin already unpinned workflow should succeed (idempotent)")
        void shouldUnpinAlreadyUnpinnedWorkflow() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), isNull()))
                    .thenReturn(new WorkflowPinService.PinResult.Success(null, null));

            Map<String, Object> request = new HashMap<>();
            request.put("version", null);

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ==================== Error Cases ====================

    @Nested
    @DisplayName("Error Cases")
    class ErrorCaseTests {

        @Test
        @DisplayName("Pin version with no run should return 400")
        void shouldReturn400WhenNoRunExistsForVersion() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(5)))
                    .thenReturn(new WorkflowPinService.PinResult.NoSuccessfulRun(5));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat((String) body.get("error")).contains("No successful run exists for version 5");
        }

        @Test
        @DisplayName("Pin non-existent version should return 400")
        void shouldReturn400ForNonExistentVersion() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(999)))
                    .thenReturn(new WorkflowPinService.PinResult.VersionNotFound(999));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 999));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat((String) body.get("error")).contains("Version 999 not found");
        }

        @Test
        @DisplayName("Pin workflow not found should return 404")
        void shouldReturn404WhenWorkflowNotFound() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(5)))
                    .thenReturn(new WorkflowPinService.PinResult.NotFound());

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Pin with wrong tenant should return 404 (not 403)")
        void shouldReturn404WhenWrongTenant() {
            when(pinService.pin(eq(WORKFLOW_ID), any(), isNull(), eq(5)))
                    .thenReturn(new WorkflowPinService.PinResult.Forbidden());

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, "tenant-other", null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Pin with invalid UUID should return 400")
        void shouldReturn400ForInvalidUuid() {
            ResponseEntity<?> response = controller.pinVersion(
                    "not-a-uuid", TENANT_ID, null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(pinService);
        }

        @Test
        @DisplayName("Pin with service error should return 500")
        void shouldReturn500OnDbError() {
            when(pinService.pin(eq(WORKFLOW_ID), eq(TENANT_ID), isNull(), eq(5)))
                    .thenThrow(new RuntimeException("DB connection lost"));

            ResponseEntity<?> response = controller.pinVersion(
                    WORKFLOW_ID_STR, TENANT_ID, null, null, Map.of("version", 5));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ==================== List Versions with pinnedVersion ====================

    @Nested
    @DisplayName("List Versions - pinnedVersion field")
    class ListVersionsPinnedTests {

        @Test
        @DisplayName("List versions should include pinnedVersion in response")
        void shouldIncludePinnedVersionInListResponse() {
            WorkflowEntity workflow = createWorkflow(5);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(versionService.listVersions(WORKFLOW_ID)).thenReturn(List.of());
            when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(7);
            when(workflowRunRepository.countRunsByPlanVersion(WORKFLOW_ID)).thenReturn(List.of());

            ResponseEntity<?> response = controller.listVersions(WORKFLOW_ID_STR, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("pinnedVersion", 5);
            assertThat(body).containsEntry("currentVersion", 7);
        }

        @Test
        @DisplayName("List versions should return null pinnedVersion when unpinned")
        void shouldReturnNullPinnedVersionWhenUnpinned() {
            WorkflowEntity workflow = createWorkflow(null);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(versionService.listVersions(WORKFLOW_ID)).thenReturn(List.of());
            when(versionService.getCurrentVersion(WORKFLOW_ID)).thenReturn(3);
            when(workflowRunRepository.countRunsByPlanVersion(WORKFLOW_ID)).thenReturn(List.of());

            ResponseEntity<?> response = controller.listVersions(WORKFLOW_ID_STR, TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("pinnedVersion", null);
        }
    }

    // ==================== Helpers ====================

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setPinnedVersion(pinnedVersion);
        return workflow;
    }
}
