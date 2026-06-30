package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for Workflow Trigger with custom transform.
 *
 * Flattens parent workflow outputs to root level - dynamic fields from
 * the parent cannot be expressed as static field definitions.
 */
@Component
public class WorkflowTriggerNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("WORKFLOW_TRIGGER")
            .label("Workflow Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered by a parent workflow")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when triggered")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the workflow owner. Empty string when the parent workflow ran in a system context.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build(),
                OutputFieldDef.builder()
                    .key("parentWorkflowId")
                    .type("string")
                    .description("ID of the parent workflow that triggered this one")
                    .aliases(List.of("parent_workflow_id"))
                    .build(),
                OutputFieldDef.builder()
                    .key("parentRunId")
                    .type("string")
                    .description("Run ID of the parent workflow execution")
                    .aliases(List.of("parent_run_id"))
                    .build(),
                OutputFieldDef.builder()
                    .key("parentStatus")
                    .type("string")
                    .description("Status of the parent workflow run")
                    .aliases(List.of("parent_status"))
                    .build(),
                OutputFieldDef.builder()
                    .key("result")
                    .type("object")
                    .description("Outputs from the parent workflow (also flattened to root level)")
                    .build(),
                OutputFieldDef.builder()
                    .key("parentStatistics")
                    .type("object")
                    .description("Execution statistics from the parent workflow run (completedSteps, failedSteps, totalSteps). Present only when the parent recorded statistics.")
                    .aliases(List.of("parent_statistics"))
                    .build(),
                OutputFieldDef.builder()
                    .key("trigger_id")
                    .type("string")
                    .description("Internal id of the trigger that fired")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_id")
                    .type("string")
                    .description("Item identifier for split-context tracking")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_index")
                    .type("number")
                    .description("Index when iterating over items")
                    .build()
                // Dynamic parent-workflow output fields are also stored at root level.
                // Each output key from the parent's last completed node becomes a top-level
                // field here (e.g. {{trigger:child.output.my_parent_key}}). Field names vary
                // per parent configuration and cannot be declared statically.
            ))
            .keywords(List.of("workflow", "sub", "trigger", "child"))
            .build();
    }

    /**
     * Reserved keys that customTransform writes explicitly, plus engine-envelope keys that
     * must never be flattened as parent-workflow output. Any key in backendOutput NOT in
     * this set represents a flattened parent-workflow output that the resolver already
     * placed at root level - copy them through so the DB shape is identical to the runtime
     * SpEL shape.
     */
    private static final Set<String> RESERVED_KEYS = Set.of(
        "triggerId", "type", "source",
        "triggered_at", "triggeredAt",
        "triggered_by", "triggeredBy",
        "parentWorkflowId", "parentRunId", "parentStatus",
        "parentStatistics", "result"
        // engine-envelope keys (node_type, item_index, itemIndex, item_id, resolved_params)
        // are removed via GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS in customTransform
    );

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>();

        Object triggeredAt = backendOutput.get("triggered_at");
        if (triggeredAt == null) triggeredAt = backendOutput.get("triggeredAt");
        dbOutput.put("triggered_at", triggeredAt != null ? triggeredAt : Instant.now().toString());

        Object triggeredBy = backendOutput.get("triggered_by");
        if (triggeredBy == null) triggeredBy = backendOutput.get("triggeredBy");
        dbOutput.put("triggered_by", triggeredBy != null ? triggeredBy : "");

        // Parent workflow metadata
        copyIfPresent(backendOutput, dbOutput, "parentWorkflowId");
        copyIfPresent(backendOutput, dbOutput, "parentRunId");
        copyIfPresent(backendOutput, dbOutput, "parentStatus");
        copyIfPresent(backendOutput, dbOutput, "result");
        copyIfPresent(backendOutput, dbOutput, "parentStatistics");

        // Flattened parent-workflow outputs - the resolver already placed these at root
        // level so SpEL resolves them at runtime; mirror them here so the DB shape is
        // identical to the runtime shape.
        for (Map.Entry<String, Object> entry : backendOutput.entrySet()) {
            String key = entry.getKey();
            if (!RESERVED_KEYS.contains(key) && !GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.contains(key)) {
                dbOutput.putIfAbsent(key, entry.getValue());
            }
        }
        // Remove engine-envelope keys shared across all node specs
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(dbOutput::remove);

        return dbOutput;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }
}
