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
 * Node specification for Option node with custom transform.
 *
 * Computes selected_branches from selected_label, and restructures
 * evaluations - logic that cannot be expressed as simple field mapping.
 */
@Component
public class OptionNodeSpec implements NodeSpec {

    /** Legacy keys emitted by older OptionNode implementations that must not leak into persisted JSONB. */
    private static final Set<String> OPTION_LEGACY_KEYS = Set.of("option_node", "choices_evaluated");

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("OPTION")
            .label("Option")
            .category("core")
            .variablePrefix("core")
            .description("Evaluates choices and routes to the first matching option")
            .branching(true)
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("selected_choice")
                    .type("string")
                    .description("ID of the choice that matched")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_label")
                    .type("string")
                    .description("Label of the selected choice")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_choice_index")
                    .type("number")
                    .description("Index of the selected choice")
                    .build(),
                OutputFieldDef.builder()
                    .key("selected_branches")
                    .type("array")
                    .description("Array containing the selected branch label (or empty if none matched)")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("skipped_branches")
                    .type("array")
                    .description("Labels of branches that were not selected")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("evaluations")
                    .type("array")
                    .description("Detailed evaluation results for each choice (choice_id, choice_label, expression, resolved_expression, result, error?)")
                    .defaultValue(List.of())
                    .build()
            ))
            .keywords(List.of("option", "choice", "select"))
            .build();
    }

    /**
     * Strips engine-envelope keys and legacy OptionNode keys from the persisted output.
     * execute() already emits the final persisted shape (selected_branches, skipped_branches,
     * restructured evaluations); no further reshaping is needed.
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        OPTION_LEGACY_KEYS.forEach(result::remove);
        return result;
    }
}
