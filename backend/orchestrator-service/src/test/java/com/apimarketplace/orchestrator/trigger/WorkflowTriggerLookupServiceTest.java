package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowTriggerLookupService")
class WorkflowTriggerLookupServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    private static final String PARENT_WORKFLOW_ID = UUID.randomUUID().toString();

    @Test
    @DisplayName("H2-compatible mode filters workflow triggers in Java without native JSONB query")
    void h2CompatibleModeFiltersWorkflowTriggersInJava() {
        WorkflowEntity matching = workflowWithTrigger("workflow", PARENT_WORKFLOW_ID);
        WorkflowEntity errorTrigger = workflowWithTrigger("error", PARENT_WORKFLOW_ID);
        WorkflowEntity otherParent = workflowWithTrigger("workflow", UUID.randomUUID().toString());
        WorkflowEntity withoutPlan = workflowWithoutPlan();
        when(workflowRepository.findByIsActiveTrue())
                .thenReturn(List.of(matching, errorTrigger, otherParent, withoutPlan));

        WorkflowTriggerLookupService service = new WorkflowTriggerLookupService(workflowRepository, true);

        assertThat(service.findByWorkflowTrigger(PARENT_WORKFLOW_ID)).containsExactly(matching);
        verify(workflowRepository).findByIsActiveTrue();
        verify(workflowRepository, never()).findByWorkflowTrigger(anyString());
    }

    @Test
    @DisplayName("H2-compatible mode filters error triggers in Java without native JSONB query")
    void h2CompatibleModeFiltersErrorTriggersInJava() {
        WorkflowEntity matching = workflowWithTrigger("error", PARENT_WORKFLOW_ID);
        WorkflowEntity workflowTrigger = workflowWithTrigger("workflow", PARENT_WORKFLOW_ID);
        WorkflowEntity otherParent = workflowWithTrigger("error", UUID.randomUUID().toString());
        when(workflowRepository.findByIsActiveTrue())
                .thenReturn(List.of(matching, workflowTrigger, otherParent));

        WorkflowTriggerLookupService service = new WorkflowTriggerLookupService(workflowRepository, true);

        assertThat(service.findByErrorTrigger(PARENT_WORKFLOW_ID)).containsExactly(matching);
        verify(workflowRepository).findByIsActiveTrue();
        verify(workflowRepository, never()).findByErrorTrigger(anyString());
    }

    @Test
    @DisplayName("Production mode delegates workflow trigger lookup to PostgreSQL JSONB query")
    void productionModeDelegatesWorkflowTriggerLookupToPostgresQuery() {
        WorkflowEntity matching = workflowWithTrigger("workflow", PARENT_WORKFLOW_ID);
        when(workflowRepository.findByWorkflowTrigger(PARENT_WORKFLOW_ID)).thenReturn(List.of(matching));

        WorkflowTriggerLookupService service = new WorkflowTriggerLookupService(workflowRepository, false);

        assertThat(service.findByWorkflowTrigger(PARENT_WORKFLOW_ID)).containsExactly(matching);
        verify(workflowRepository).findByWorkflowTrigger(PARENT_WORKFLOW_ID);
        verify(workflowRepository, never()).findByIsActiveTrue();
    }

    private WorkflowEntity workflowWithTrigger(String type, String parentWorkflowId) {
        WorkflowEntity workflow = new WorkflowEntity("tenant-test", "Workflow " + type, "tester");
        workflow.setId(UUID.randomUUID());
        workflow.setPlan(planWithTrigger(type, parentWorkflowId));
        return workflow;
    }

    private WorkflowEntity workflowWithoutPlan() {
        WorkflowEntity workflow = new WorkflowEntity("tenant-test", "Workflow without plan", "tester");
        workflow.setId(UUID.randomUUID());
        return workflow;
    }

    private Map<String, Object> planWithTrigger(String type, String parentWorkflowId) {
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("id", parentWorkflowId);
        trigger.put("label", type + "_trigger");
        trigger.put("strategy", "single");
        trigger.put("type", type);

        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(trigger));
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        plan.put("cores", List.of());
        return plan;
    }
}
