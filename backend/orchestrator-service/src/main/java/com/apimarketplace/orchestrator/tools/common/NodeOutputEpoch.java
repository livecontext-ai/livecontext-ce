package com.apimarketplace.orchestrator.tools.common;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which epoch a get_node_output reads, shared by the workflow and application tools so the two
 * cannot drift. An explicit epoch is used as given. An omitted one defaults to the most recent
 * epoch in which THIS node wrote a row, and the report says so in {@code epoch_note}. Three
 * outcomes never collapse into one: a node that never ran (RESOURCE_NOT_FOUND, with the two
 * reasons that produce it), a lookup that could not be answered (EXECUTION_FAILED: telling the
 * agent "never ran" there would be a wrong fact), and a resolved epoch.
 */
@Slf4j
public final class NodeOutputEpoch {

    /** Either an epoch to read (with the note to add when it was defaulted) or a failure to return. */
    public record Resolution(Integer epoch, String note, ToolExecutionResult failure) {
        public boolean failed() {
            return failure != null;
        }

        /** The report with {@code epoch_note} added when the epoch was defaulted. */
        public Map<String, Object> annotate(Map<String, Object> report) {
            if (note == null || report == null) return report;
            Map<String, Object> out = new LinkedHashMap<>(report);
            out.put("epoch_note", note);
            return out;
        }
    }

    private NodeOutputEpoch() {
    }

    /**
     * @param requested the epoch the caller passed, or null when omitted
     * @param run       the run being read (its public id is the lookup key, never the caller's text)
     * @param tool      the tool name used in the hints ("workflow" or "application")
     */
    public static Resolution resolve(Integer requested, WorkflowRunEntity run, String nodeId, String tool,
                                     AgentWorkflowFireService fireService) {
        if (requested != null) {
            return new Resolution(requested, null, null);
        }
        String runId = run.getRunIdPublic();
        Integer latest;
        try {
            latest = fireService.latestEpochForNode(runId, nodeId);
        } catch (RuntimeException e) {
            log.warn("Latest epoch lookup failed for {}/{}: {}", runId, nodeId, e.getMessage());
            return new Resolution(null, null, ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Could not resolve the latest epoch of node '" + nodeId + "' in run " + runId
                    + " right now. Pass epoch=N explicitly (" + tool + "(action='get_run', run_id='"
                    + runId + "') lists the epochs), or retry."));
        }
        if (latest == null) {
            return new Resolution(null, null, ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "Node '" + nodeId + "' has no recorded execution in run " + runId + ": it may "
                    + "never have been reached (skipped, or on a branch that did not run), or '" + nodeId
                    + "' is not the node's key (use the node_id exactly as "
                    + tool + "(action='get_run', run_id='" + runId + "', epoch=N) lists it, e.g. "
                    + "'mcp:fetch_data', not the display label). Pass epoch=N to read one fire explicitly."));
        }
        return new Resolution(latest, "epoch was omitted: showing epoch " + latest
                + ", the most recent one in which this node ran. Pass epoch=N to read another fire.", null);
    }
}
