package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.List;

/**
 * Definition of a single output field for a node type.
 *
 * Used by NodeDefinition to declare the output schema of a node.
 * The GenericOutputSchemaMapper uses this to transform backend output
 * to DB schema format without needing a dedicated mapper class.
 *
 * @param key         Primary field name in the output (e.g., "sorted_items")
 * @param type        Field type: "string", "number", "boolean", "array", "object", "datetime"
 * @param description Human-readable description of the field
 * @param defaultValue Default value when field is missing (null = conditional/excluded)
 * @param aliases     Alternative field names to try (e.g., ["received_branches", "merged_data"])
 * @param children    Nested field definitions for object/array types
 * @param runtimeOnly True when the field is injected only during execution context and is not persisted
 */
@Builder
public record OutputFieldDef(
    String key,
    String type,
    String description,
    Object defaultValue,
    List<String> aliases,
    List<OutputFieldDef> children,
    Boolean runtimeOnly
) {
    public OutputFieldDef {
        if (aliases == null) aliases = List.of();
        if (children == null) children = List.of();
        if (runtimeOnly == null) runtimeOnly = Boolean.FALSE;
    }

    /**
     * Whether this field has a default value (should be included even when missing from raw output).
     */
    public boolean hasDefault() {
        return defaultValue != null;
    }

    /**
     * Whether this field is conditional (no default = excluded when null).
     */
    public boolean isConditional() {
        return defaultValue == null;
    }
}
