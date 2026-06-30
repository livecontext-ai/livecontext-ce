package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.WorkflowExecutionServiceV2;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying that createExecution() does NOT consume credits.
 * Credits are now consumed per-node in StepCompletionOrchestrator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowExecutionService - No per-run credit consumption")
class WorkflowExecutionServiceCreditTest {

    @Mock
    private WorkflowExecutionServiceV2 executionServiceV2;

    @Mock
    private WorkflowPersistenceService persistenceService;

    @Mock
    private WorkflowExecutionConfig config;

    @Mock
    private TriggerResolverService triggerResolverService;

    @Mock
    private com.apimarketplace.interfaces.client.InterfaceClient interfaceClient;

    @Mock
    private com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor interfacePlanExtractor;

    @Mock
    private ExecutionGraphService executionGraphService;

    @Mock
    private CreditConsumptionClient creditClient;

    @Mock
    private com.apimarketplace.orchestrator.metrics.WorkflowMetrics workflowMetrics;

    @InjectMocks
    private WorkflowExecutionService workflowExecutionService;

    private static final String TENANT_ID = "tenant-credit-test";
    private static final String PLAN_ID = "plan-123";

    private WorkflowPlan buildMinimalPlan() {
        return new WorkflowPlan(
            PLAN_ID, TENANT_ID,
            List.of(),   // triggers
            List.of(),   // mcps
            List.of(),   // agents
            List.of(),   // edges
            List.of(),   // cores
            List.of(),   // tables
            List.of(),   // notes
            List.of(),   // interfaces
            Map.of()     // originalPlan
        );
    }

    @BeforeEach
    void setUp() {
        lenient().when(executionGraphService.getExecutionGraph(any(WorkflowPlan.class)))
            .thenReturn(mock(ExecutionGraph.class));
        lenient().doNothing().when(persistenceService).recordWorkflowStart(any(WorkflowExecution.class));
        lenient().doNothing().when(persistenceService).restoreExecutionState(any(WorkflowExecution.class));
    }

    @Test
    @DisplayName("createExecution should NOT consume credits (credits are per-node now)")
    void shouldNotConsumeCreditsOnCreate() {
        WorkflowPlan plan = buildMinimalPlan();

        WorkflowExecution execution = workflowExecutionService.createExecution(plan, Map.of());

        assertThat(execution).isNotNull();
        assertThat(execution.getRunId()).isNotBlank();

        // No credit consumption at execution creation level
        verifyNoInteractions(creditClient);
    }

    @Test
    @DisplayName("DB persistence should still work without credit consumption")
    void shouldStillPersistWithoutCreditConsumption() {
        WorkflowPlan plan = buildMinimalPlan();

        WorkflowExecution execution = workflowExecutionService.createExecution(plan, Map.of());

        assertThat(execution).isNotNull();
        verify(persistenceService).recordWorkflowStart(any(WorkflowExecution.class));
        verify(persistenceService).restoreExecutionState(any(WorkflowExecution.class));
        verifyNoInteractions(creditClient);
    }
}
