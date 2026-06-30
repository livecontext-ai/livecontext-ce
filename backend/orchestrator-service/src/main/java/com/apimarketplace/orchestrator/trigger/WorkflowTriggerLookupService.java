package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Finds workflows whose persisted plan contains workflow or error triggers.
 *
 * <p>Production uses PostgreSQL JSONB queries from {@link WorkflowRepository}.
 * H2-backed test profiles run with {@code orchestrator.mock.enabled=true} and
 * do not support {@code jsonb_array_elements}; in that mode, preserve dispatcher
 * behavior by filtering active workflow plans in Java.
 */
@Service
public class WorkflowTriggerLookupService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowTriggerLookupService.class);

    private final WorkflowRepository workflowRepository;
    private final boolean h2CompatibleMode;

    public WorkflowTriggerLookupService(
            WorkflowRepository workflowRepository,
            @Value("${orchestrator.mock.enabled:false}") boolean h2CompatibleMode) {
        this.workflowRepository = workflowRepository;
        this.h2CompatibleMode = h2CompatibleMode;
    }

    List<WorkflowEntity> findByWorkflowTrigger(String parentWorkflowId) {
        return findByTriggerType("workflow", parentWorkflowId, workflowRepository::findByWorkflowTrigger);
    }

    List<WorkflowEntity> findByErrorTrigger(String parentWorkflowId) {
        return findByTriggerType("error", parentWorkflowId, workflowRepository::findByErrorTrigger);
    }

    private List<WorkflowEntity> findByTriggerType(
            String triggerType,
            String parentWorkflowId,
            Function<String, List<WorkflowEntity>> postgresFinder) {
        if (parentWorkflowId == null || parentWorkflowId.isBlank()) {
            return List.of();
        }
        if (!h2CompatibleMode) {
            return postgresFinder.apply(parentWorkflowId);
        }
        return workflowRepository.findByIsActiveTrue().stream()
                .filter(workflow -> hasMatchingTrigger(workflow, triggerType, parentWorkflowId))
                .toList();
    }

    private boolean hasMatchingTrigger(WorkflowEntity workflow, String triggerType, String parentWorkflowId) {
        Map<String, Object> planMap = workflow.getPlan();
        if (planMap == null) {
            return false;
        }
        try {
            WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
            for (Trigger trigger : plan.getTriggers()) {
                if (triggerType.equals(trigger.type()) && parentWorkflowId.equalsIgnoreCase(trigger.id())) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Skipping workflow {} during {} trigger lookup because its plan could not be parsed: {}",
                    workflow.getId(), triggerType, e.getMessage());
        }
        return false;
    }
}
