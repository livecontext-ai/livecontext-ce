package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Représente un payload minimal contenant un plan JSON et d'éventuelles données d'entrée.
 *
 * Note: Schedule configuration is handled via Schedule Trigger nodes in the plan,
 * not via a separate schedule field. See ScheduleSyncService.syncFromPinnedVersion().
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowPlanRequest {

    @NotBlank(message = "planJson is required")
    private final String planJson;

    private final Map<String, Object> dataInputs;

    private final String workflowId;

    /**
     * Optional execution mode: "automatic" (default) or "step_by_step"
     * When "step_by_step", the workflow starts in PAUSED state and waits for manual step execution.
     */
    private final String executionMode;

    /**
     * Optional run source tag: "application" for application-dedicated runs.
     */
    private final String source;

    /**
     * Optional publication ID (required when source = "application").
     */
    private final String publicationId;

    /**
     * Optional run-level mock override for editor runs: absent/null = default
     * (nodes carrying an enabled {@code mock} block return their mock, others run
     * real), {@code "off"} = ignore ALL mocks this run (config untouched),
     * {@code "all_mcp"} = also mock every mcp catalog-tool node without a block
     * with its catalog example (full dry-run). Ignored on non-editor paths.
     */
    private final String mockMode;

    @JsonCreator
    public WorkflowPlanRequest(@JsonProperty("planJson") String planJson,
                               @JsonProperty("dataInputs") Map<String, Object> dataInputs,
                               @JsonProperty("workflowId") String workflowId,
                               @JsonProperty("executionMode") String executionMode,
                               @JsonProperty("source") String source,
                               @JsonProperty("publicationId") String publicationId,
                               @JsonProperty("mockMode") String mockMode) {
        this.planJson = planJson;
        this.dataInputs = dataInputs != null ? new HashMap<>(dataInputs) : new HashMap<>();
        this.workflowId = workflowId;
        this.executionMode = executionMode;
        this.source = source;
        this.publicationId = publicationId;
        this.mockMode = mockMode;
    }

    /** Back-compat constructor (pre-mockMode call sites). */
    public WorkflowPlanRequest(String planJson,
                               Map<String, Object> dataInputs,
                               String workflowId,
                               String executionMode,
                               String source,
                               String publicationId) {
        this(planJson, dataInputs, workflowId, executionMode, source, publicationId, null);
    }

    public String getPlanJson() {
        return planJson;
    }

    public Map<String, Object> getDataInputs() {
        return Collections.unmodifiableMap(dataInputs);
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public boolean isStepByStepMode() {
        return "step_by_step".equalsIgnoreCase(executionMode);
    }

    public String getSource() {
        return source;
    }

    public String getPublicationId() {
        return publicationId;
    }

    public String getMockMode() {
        return mockMode;
    }
}
