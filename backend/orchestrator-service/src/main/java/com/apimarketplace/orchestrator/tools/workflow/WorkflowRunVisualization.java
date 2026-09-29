package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code visualization} a workflow tool result carries when an action launched, replayed or
 * resumed a run: the page showing that workflow binds the run, and the chat opens it elsewhere.
 *
 * <p>{@code planVersion} lets the page decide whether the plan already on its canvas IS the one
 * the run executes (overlay without reloading) or whether it must load the run's own plan: a run
 * of a pinned version, or one replayed on an older version, is not the HEAD the editor shows.
 * Built in one place so every run-acting action stays in step with {@code execute}.
 */
@Slf4j
public final class WorkflowRunVisualization {

    private WorkflowRunVisualization() {
    }

    public static Map<String, Object> metadata(String workflowId, String workflowName, String runId, Integer planVersion) {
        Map<String, Object> viz = new LinkedHashMap<>();
        viz.put("type", "workflow_run");
        viz.put("id", workflowId);
        viz.put("title", workflowName != null ? workflowName : "Workflow");
        viz.put("runId", runId);
        if (planVersion != null) {
            viz.put("planVersion", planVersion);
        }
        return Map.of("visualization", viz);
    }

    /**
     * Same, for a run read back after the action. Never throws: the action has already happened
     * (a replay ran, a signal was recorded), so failing to describe it for the page must not turn
     * it into a reported failure the agent would retry.
     *
     * <p>{@code run.getWorkflow()} is a LAZY association and these callers hold no session, so
     * only its id is read off it (a proxy answers that without loading); the name comes from
     * {@code nameLookup}, which loads the workflow itself.
     */
    public static Map<String, Object> metadataFor(WorkflowRunEntity run, Function<UUID, String> nameLookup) {
        try {
            WorkflowEntity workflow = run != null ? run.getWorkflow() : null;
            UUID workflowId = workflow != null ? workflow.getId() : null;
            if (workflowId == null) {
                return null;
            }
            String name = null;
            try {
                name = nameLookup.apply(workflowId);
            } catch (RuntimeException e) {
                log.debug("No workflow name for run visualization of {}: {}", run.getRunIdPublic(), e.getMessage());
            }
            return metadata(workflowId.toString(), name, run.getRunIdPublic(), run.getPlanVersion());
        } catch (RuntimeException e) {
            log.warn("Run visualization skipped for {}: {}", run != null ? run.getRunIdPublic() : null, e.getMessage());
            return null;
        }
    }
}
