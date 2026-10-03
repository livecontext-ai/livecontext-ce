package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LC-004: {@code workflow(action='get_node_output')} tags its response metadata when the run it
 * reads from holds Gmail / Drive data, so the agent loop and the CLI bridge withhold the node
 * output from a provider outside the restricted-data allow-list, exactly like a file or a live
 * agent turn.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule.runSensitivityMetadata")
class WorkflowCrudModuleRestrictedDataTest {

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe cancellationProbe;
    @Mock StepPayloadService stepPayloadService;

    private WorkflowCrudModule module;

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe,
                mock(com.apimarketplace.orchestrator.tools.common.RunStopToolHandler.class),
                mock(com.apimarketplace.orchestrator.services.resume.StepRerunService.class),
                mock(com.apimarketplace.orchestrator.services.resume.AutoRestartExecutionService.class));
    }

    @Test
    @DisplayName("restricted run: metadata carries the tag")
    void restrictedRunIsTagged() {
        module.setStepPayloadService(stepPayloadService);
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(true);

        assertThat(module.runSensitivityMetadata("run-1"))
                .containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");
    }

    @Test
    @DisplayName("ordinary run: no tag")
    void ordinaryRunIsNotTagged() {
        module.setStepPayloadService(stepPayloadService);
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(false);

        assertThat(module.runSensitivityMetadata("run-1")).isEmpty();
    }

    @Test
    @DisplayName("stepPayloadService not wired: empty metadata, never throws")
    void missingServiceIsHandledGracefully() {
        assertThat(module.runSensitivityMetadata("run-1")).isEmpty();
    }
}
