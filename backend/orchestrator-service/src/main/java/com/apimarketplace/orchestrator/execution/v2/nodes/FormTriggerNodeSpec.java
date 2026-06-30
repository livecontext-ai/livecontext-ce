package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for Form Trigger with custom transform.
 *
 * Handles dynamic form fields and FileRef flattening - cannot be expressed
 * as simple field-by-field mapping because the output includes user-defined
 * form fields that vary per workflow.
 */
@Component
public class FormTriggerNodeSpec implements NodeSpec {

    private static final Set<String> METADATA_FIELDS = Set.of(
        "itemId",
        "iteration", "currentIteration", "status", "http_status",
        "tenant_id", "tenantId", "trigger_id", "triggerId",
        "epoch", "spawn", "absoluteIndex",
        // FormDispatchService internal tracking keys - must not leak to persisted JSONB
        "_source", "_formEndpointId", "_formEndpointName"
        // engine-envelope keys (node_type, item_index, itemIndex, item_id, resolved_params)
        // are stripped via GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS in customTransform
    );

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("FORM_TRIGGER")
            .label("Form Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered by a form submission")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("submission_id")
                    .type("string")
                    .description("Form submission identifier (UUID)")
                    .aliases(List.of("submissionId"))
                    .build(),
                OutputFieldDef.builder()
                    .key("submitted_at")
                    .type("datetime")
                    .description("When the form was submitted")
                    .defaultValue("__NOW__")
                    .aliases(List.of("submittedAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("form_data")
                    .type("object")
                    .description("All submitted form fields grouped as object")
                    .aliases(List.of("formData"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when the form was submitted (alias of submitted_at for cross-trigger consistency)")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the user who submitted the form (empty when anonymous). Never the raw tenantId.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
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
            ))
            .keywords(List.of("form", "submit", "trigger", "input"))
            .build();
    }

    /**
     * Custom transform: FormDispatchService now emits all canonical fields
     * (submission_id, submitted_at, form_data, FileRef flattening) at dispatch time,
     * so this transform is a passthrough - it copies all keys verbatim.
     *
     * Internal metadata keys that must not leak into persisted output are stripped.
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>(backendOutput);
        // Remove internal orchestrator metadata that should not appear in persisted output
        for (String key : METADATA_FIELDS) {
            dbOutput.remove(key);
        }
        // Remove engine-envelope keys shared across all node specs
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(dbOutput::remove);
        return dbOutput;
    }
}
