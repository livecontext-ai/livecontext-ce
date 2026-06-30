package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationSupportController scope checks")
class InternalPublicationSupportControllerScopeTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private InterfaceClient interfaceClient;

    private InternalPublicationSupportController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalPublicationSupportController(
                null, workflowRunRepository, null, null, null, null, null, null,
                null, null, null, null, null, null, null, interfaceClient, null, null, null, null);
    }

    @Test
    @DisplayName("Rejects personal caller for org run owned by same tenant")
    void rejectsPersonalCallerForOwnedOrgRun() {
        WorkflowRunEntity run = run("run-org", "user-1", "org-a");
        when(workflowRunRepository.findByRunIdPublic("run-org")).thenReturn(Optional.of(run));

        ResponseEntity<?> response = controller.getInterfaceSnapshotsForRun("run-org", "user-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(interfaceClient, never()).getSnapshotsForRun(run.getId(), "user-1", null);
    }

    @Test
    @DisplayName("Allows active organization caller for org run")
    void allowsActiveOrganizationCallerForOrgRun() {
        WorkflowRunEntity run = run("run-org", "owner-1", "org-a");
        when(workflowRunRepository.findByRunIdPublic("run-org")).thenReturn(Optional.of(run));
        when(interfaceClient.getSnapshotsForRun(run.getId(), "member-1", "org-a")).thenReturn(List.of());

        ResponseEntity<?> response = controller.getInterfaceSnapshotsForRun("run-org", "member-1", "org-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(interfaceClient).getSnapshotsForRun(run.getId(), "member-1", "org-a");
    }

    private static WorkflowRunEntity run(String runIdPublic, String tenantId, String organizationId) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setRunIdPublic(runIdPublic);
        run.setTenantId(tenantId);
        run.setOrganizationId(organizationId);
        return run;
    }
}
