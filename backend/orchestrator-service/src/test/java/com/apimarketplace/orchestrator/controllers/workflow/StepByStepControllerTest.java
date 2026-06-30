package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.execution.v2.scheduler.V2StepByStepScheduler;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepByStepController")
class StepByStepControllerTest {

    @Mock
    private WorkflowResumeService resumeService;

    @Mock
    private V2StepByStepService v2StepByStepService;

    @Mock
    private V2StepByStepScheduler v2StepByStepScheduler;

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private WorkflowRunRepository runRepository;

    @InjectMocks
    private StepByStepController controller;

    @BeforeEach
    void setUp() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("user-1");
        when(runRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));
        when(creditClient.checkCredits("user-1")).thenReturn(true);
    }

    @Test
    @DisplayName("Rejects non-ready V2 SBS node without executing or marking it skipped")
    void rejectsNonReadyV2SbsNodeWithoutExecution() {
        when(stateSnapshotService.claimNodeForExecution("run-1", "core:downstream"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.executeSingleStepInStepByStepMode(
                "run-1",
                "core:downstream",
                null,
                "user-1",
                null,
                Map.of());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("success", false)
                .containsEntry("error", "NODE_NOT_READY")
                .containsEntry("stepId", "core:downstream");

        verify(v2StepByStepService, never()).executeNode(anyString(), anyString(), anyString());
        verify(v2StepByStepService, never()).executeSplitItems(anyString(), anyString(), org.mockito.ArgumentMatchers.anySet());
        verify(resumeService, never()).executeSingleStepInStepByStepMode(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }
}
