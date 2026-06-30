package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.OrganizationService;
import com.apimarketplace.auth.service.WorkspacePauseReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceReconcileInternalControllerTest {

    @Mock private OrganizationService organizationService;
    @Mock private WorkspacePauseReconciler reconciler;
    @InjectMocks private WorkspaceReconcileInternalController controller;

    @Test
    @DisplayName("POST /reconcile/{userId} reconciles that owner's workspaces and reports success")
    void reconcileDelegatesToService() {
        ResponseEntity<Map<String, Object>> response = controller.reconcile(7L);

        verify(organizationService).reconcileWorkspacePauseState(7L);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("userId", 7L)
                .containsEntry("reconciled", true);
    }

    @Test
    @DisplayName("POST /reconcile-all runs the full sweep and reports the owner count")
    void reconcileAllDelegatesToReconciler() {
        when(reconciler.reconcileAll()).thenReturn(4);

        ResponseEntity<Map<String, Object>> response = controller.reconcileAll();

        verify(reconciler).reconcileAll();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("processed", 4);
    }
}
