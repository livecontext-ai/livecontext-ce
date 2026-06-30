package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating an interface snapshot for a workflow run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SnapshotCreateRequest {

    @JsonProperty("interface_id")
    private UUID interfaceId;

    @JsonProperty("workflow_run_id")
    private UUID workflowRunId;

    @JsonProperty("variable_mappings")
    private Map<String, String> variableMappings;

    @JsonProperty("action_mappings")
    private Map<String, String> actionMappings;

    public SnapshotCreateRequest() {}

    public SnapshotCreateRequest(UUID interfaceId, UUID workflowRunId,
                                  Map<String, String> variableMappings,
                                  Map<String, String> actionMappings) {
        this.interfaceId = interfaceId;
        this.workflowRunId = workflowRunId;
        this.variableMappings = variableMappings;
        this.actionMappings = actionMappings;
    }

    public UUID getInterfaceId() { return interfaceId; }
    public void setInterfaceId(UUID interfaceId) { this.interfaceId = interfaceId; }

    public UUID getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(UUID workflowRunId) { this.workflowRunId = workflowRunId; }

    public Map<String, String> getVariableMappings() { return variableMappings; }
    public void setVariableMappings(Map<String, String> variableMappings) { this.variableMappings = variableMappings; }

    public Map<String, String> getActionMappings() { return actionMappings; }
    public void setActionMappings(Map<String, String> actionMappings) { this.actionMappings = actionMappings; }
}
