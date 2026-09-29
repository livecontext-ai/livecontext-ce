package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.controllers.interfaces.InterfaceActionController;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression for the signal / interface-action write gap: resolving an approval gate,
 * resolving every pending signal of a node, cancelling a signal and firing an interface
 * action all checked org SCOPE only, so a read-only VIEWER could advance or kill a run
 * whose next nodes execute with the owner's credentials (the same thing execute already
 * refuses them). Reading the pending signals stays open to a VIEWER.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Signals and interface actions refuse the VIEWER role")
class RunSignalAndInterfaceActionViewerGateTest {

    private static final String RUN_ID = "run_<id>";
    private static final String NODE_ID = "core:user_approval";
    private static final String USER = "user-9";
    private static final String ORG = "org-9";

    @Mock private UnifiedSignalService signalService;
    @Mock private SignalResumeService signalResumeService;
    @Mock private InterfaceActionService interfaceActionService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WorkflowRunRepository runRepository;

    private WorkflowSignalController signalController;
    private InterfaceActionController interfaceController;

    @BeforeEach
    void setUp() {
        signalController = new WorkflowSignalController(signalService, signalResumeService, runRepository,
                com.apimarketplace.orchestrator.testsupport.OrgAccessGuardStubs.allowAll());
        interfaceController = new InterfaceActionController(signalService, interfaceActionService, runRepository, interfaceClient,
                com.apimarketplace.orchestrator.testsupport.OrgAccessGuardStubs.allowAll());
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("owner-9");
        run.setOrganizationId(ORG);
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Nested
    @DisplayName("VIEWER writes are 403 and reach no service")
    class ViewerRefused {

        @Test
        @DisplayName("resolve (approve an approval gate) by a VIEWER is 403")
        void resolveRefused() {
            assertForbidden(signalController.resolveSignal(RUN_ID, NODE_ID,
                    Map.of("resolution", "APPROVED"), USER, ORG, "VIEWER"));
            verifyNoInteractions(signalService, signalResumeService);
        }

        @Test
        @DisplayName("resolve-all by a VIEWER is 403")
        void resolveAllRefused() {
            assertForbidden(signalController.resolveAllSignals(RUN_ID, NODE_ID,
                    Map.of("resolution", "REJECTED"), USER, ORG, "viewer"));
            verifyNoInteractions(signalService, signalResumeService);
        }

        @Test
        @DisplayName("cancel a signal by a VIEWER is 403")
        void cancelRefused() {
            assertForbidden(signalController.cancelSignal(RUN_ID, NODE_ID, USER, ORG, "VIEWER"));
            verifyNoInteractions(signalService, signalResumeService);
        }

        @Test
        @DisplayName("fire an interface action by a VIEWER is 403")
        void fireActionRefused() {
            assertForbidden(interfaceController.fireAction(RUN_ID, "interface:form",
                    Map.of("actionKey", "submit"), USER, ORG, "VIEWER"));
            verifyNoInteractions(signalService, interfaceActionService);
        }
    }

    @Nested
    @DisplayName("reads and non-VIEWER writes are unchanged")
    class Unchanged {

        @Test
        @DisplayName("a VIEWER can still LIST the pending signals of a run")
        void viewerListsSignals() {
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());

            ResponseEntity<List<Map<String, Object>>> response = signalController.listSignals(RUN_ID, USER, ORG);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("a MEMBER resolve goes past the gate to the signal lookup")
        void memberResolveProceeds() {
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> response = signalController.resolveSignal(RUN_ID, NODE_ID,
                    Map.of("resolution", "APPROVED"), USER, ORG, "MEMBER");

            assertThat(response.getStatusCode().value()).isNotEqualTo(403);
            verify(signalService).getActiveSignals(RUN_ID);
        }

        @Test
        @DisplayName("a MEMBER cancel goes past the gate and cancels the node's signal")
        void memberCancelProceeds() {
            ResponseEntity<Map<String, Object>> response =
                    signalController.cancelSignal(RUN_ID, NODE_ID, USER, ORG, "MEMBER");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(signalService).cancelForNodes(RUN_ID, java.util.Set.of(NODE_ID), -1);
        }

        @Test
        @DisplayName("a MEMBER fireAction goes past the gate to the signal lookup")
        void memberFireProceeds() {
            when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());

            ResponseEntity<Map<String, Object>> response = interfaceController.fireAction(RUN_ID, "interface:form",
                    Map.of("actionKey", "submit"), USER, ORG, "MEMBER");

            assertThat(response.getStatusCode().value()).isNotEqualTo(403);
            verify(signalService).getActiveSignals(RUN_ID);
        }
    }
}
