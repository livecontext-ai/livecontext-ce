package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Node specification for Transform node.
 *
 * execute() already emits the canonical persisted shape ({transformed, evaluations}),
 * so customTransform is identity-with-envelope-strip - same pattern as the 11 nodes
 * aligned in be1c9e59e (Compression, ConvertToFile, DownloadFile, Sftp, ChatTrigger,
 * FormTrigger, Option, Limit, WorkflowTrigger, TableTrigger, Summarize).
 */
@Component
public class TransformNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("TRANSFORM")
            .label("Transform")
            .category("core")
            .variablePrefix("core")
            .description("Transforms data using field mappings and expressions")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("evaluations")
                    .type("array")
                    .description("Evaluation details for each mapping (field, expression, resolved_expression, value)")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("transformed")
                    .type("object")
                    .description("Final transformed object with all mapped fields")
                    .build()
            ))
            .keywords(List.of("transform", "map", "convert", "reshape"))
            .build();
    }

    /**
     * Strips engine-envelope keys from the persisted output.
     * execute() already emits the final canonical shape ({transformed, evaluations});
     * no further reshaping is needed.
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        return result;
    }
}
