package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for a node type's metadata and output schema.
 *
 * Replaces the need to duplicate field names across:
 * - OutputSchemaMapper (backend)
 * - node_type_documentation.outputs (DB/LLM docs)
 * - UnifiedNodeOutput.*_SCHEMA (frontend)
 *
 * @param nodeType       Node type identifier (e.g., "STOP", "FILTER", "MERGE")
 * @param label          Human-readable label (e.g., "Stop", "Filter", "Merge")
 * @param category       Node category: "core", "agent", "mcp", "trigger", "table", "interface"
 * @param variablePrefix SpEL variable prefix (e.g., "core", "agent")
 * @param description    Brief description of the node's purpose
 * @param outputs        Output field definitions (ordered)
 * @param terminal       Whether this node stops execution (no successors)
 * @param branching      Whether this node has multiple output ports
 * @param keywords       Search/discovery keywords
 * @param metadata       Arbitrary additional metadata
 */
@Builder
public record NodeDefinition(
    String nodeType,
    String label,
    String category,
    String variablePrefix,
    String description,
    @Builder.Default List<OutputFieldDef> outputs,
    @Builder.Default boolean terminal,
    @Builder.Default boolean branching,
    @Builder.Default List<String> keywords,
    @Builder.Default Map<String, Object> metadata
) {
    public NodeDefinition {
        if (outputs == null) outputs = List.of();
        if (keywords == null) keywords = List.of();
        if (metadata == null) metadata = Map.of();
    }

    /**
     * Converts the output definitions to the JSONB format used by node_type_documentation.outputs.
     * Format: { "field_name": { "type": "string", "description": "..." }, ... }
     */
    public Map<String, Object> outputsToDocMap() {
        Map<String, Object> docMap = new LinkedHashMap<>();
        for (OutputFieldDef field : outputs) {
            Map<String, Object> fieldDoc = new LinkedHashMap<>();
            fieldDoc.put("type", field.type());
            fieldDoc.put("description", field.description());
            if (!field.children().isEmpty()) {
                Map<String, Object> childMap = new LinkedHashMap<>();
                for (OutputFieldDef child : field.children()) {
                    Map<String, Object> childDoc = new LinkedHashMap<>();
                    childDoc.put("type", child.type());
                    childDoc.put("description", child.description());
                    childMap.put(child.key(), childDoc);
                }
                fieldDoc.put("children", childMap);
            }
            docMap.put(field.key(), fieldDoc);
        }
        return docMap;
    }
}
