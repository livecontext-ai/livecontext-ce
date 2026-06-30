package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves workflow triggers.
 * Workflow triggers are fired when a parent workflow completes.
 *
 * The workflow trigger receives the parent workflow's result via resolvedInputs:
 * - result: The parent workflow's final output
 * - status: The parent workflow's completion status (COMPLETED, FAILED)
 * - parentWorkflowId: ID of the parent workflow
 * - parentRunId: ID of the parent workflow run
 * - triggeredAt: Timestamp of when the trigger was fired
 */
@Slf4j
@Component
public class WorkflowTriggerResolver implements TriggerTypeHandler {

    @Autowired
    private TriggerUserResolver triggerUserResolver;

    @Override
    public boolean canHandle(String triggerType) {
        return "workflow".equalsIgnoreCase(triggerType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.info("Resolving workflow trigger: {} for tenant: {}", trigger.id(), tenantId);

        Map<String, Object> result = new HashMap<>();
        result.put("triggerId", trigger.id());
        result.put("type", "workflow");
        result.put("source", "workflow");

        // Preserve parent run's triggered_at when chained via workflow-trigger; accept
        // both snake_case (new) and camelCase (legacy) from upstream payloads.
        String triggeredAt = null;
        if (resolvedInputs != null) {
            Object tsSnake = resolvedInputs.get("triggered_at");
            Object tsCamel = resolvedInputs.get("triggeredAt");
            if (tsSnake instanceof String s && !s.isBlank()) triggeredAt = s;
            else if (tsCamel instanceof String s && !s.isBlank()) triggeredAt = s;
        }
        if (triggeredAt == null) triggeredAt = java.time.Instant.now().toString();
        String triggeredBy = triggerUserResolver.resolveDisplayName(tenantId);
        result.put("triggered_at", triggeredAt);
        result.put("triggered_by", triggeredBy);

        // Extract parent workflow result from resolved inputs
        Map<String, Object> parentResult = new HashMap<>();
        if (resolvedInputs != null) {
            // Copy the result from parent workflow
            if (resolvedInputs.containsKey("result")) {
                Object resultObj = resolvedInputs.get("result");
                if (resultObj instanceof Map) {
                    parentResult.putAll((Map<String, Object>) resultObj);
                }
            }

            // Include parent workflow metadata
            if (resolvedInputs.containsKey("parentWorkflowId")) {
                result.put("parentWorkflowId", resolvedInputs.get("parentWorkflowId"));
            }
            if (resolvedInputs.containsKey("parentRunId")) {
                result.put("parentRunId", resolvedInputs.get("parentRunId"));
            }
            if (resolvedInputs.containsKey("status")) {
                result.put("parentStatus", resolvedInputs.get("status"));
            }
            if (resolvedInputs.containsKey("statistics")) {
                result.put("parentStatistics", resolvedInputs.get("statistics"));
            }
        }

        result.put("result", parentResult);

        // Flatten parent workflow outputs to root so SpEL can resolve
        // {{trigger:child.output.<parent_key>}} without the .result. prefix.
        // Reserved keys (triggerId, type, source, triggered_at/by, parentWorkflowId,
        // parentRunId, parentStatus, parentStatistics, result) are never overwritten.
        for (Map.Entry<String, Object> entry : parentResult.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }

        log.info("Workflow trigger {} resolved with parent result keys: {}", trigger.id(), parentResult.keySet());
        return result;
    }
}
