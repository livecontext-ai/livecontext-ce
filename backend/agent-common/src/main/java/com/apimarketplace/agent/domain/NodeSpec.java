package com.apimarketplace.agent.domain;

import java.util.Map;

/**
 * Interface for node specification companions.
 *
 * Implement this interface in a Spring @Component co-located with the node class
 * to declare the node's metadata and output schema. The NodeDefinitionRegistry
 * auto-discovers all implementations via Spring DI.
 *
 * For simple nodes, only {@link #definition()} is needed - the GenericOutputSchemaMapper
 * will handle field-by-field mapping using OutputFieldDef declarations.
 *
 * For complex nodes (e.g., FormTrigger with dynamic fields, Transform with remainder
 * aggregation), override {@link #customTransform(Map)} to provide custom logic.
 *
 * Example:
 * <pre>
 * {@code @Component}
 * public class ExitNodeSpec implements NodeSpec {
 *     public NodeDefinition definition() {
 *         return NodeDefinition.builder()
 *             .nodeType("EXIT")
 *             .label("Exit")
 *             .category("core")
 *             .outputs(List.of(...))
 *             .build();
 *     }
 * }
 * </pre>
 */
public interface NodeSpec {

    /**
     * Returns the node definition with metadata and output schema.
     */
    NodeDefinition definition();

    /**
     * Optional custom transformation for complex nodes that cannot be handled
     * by generic field-by-field mapping alone.
     *
     * When this method returns a non-null value, the GenericOutputSchemaMapper
     * uses it instead of the generic field mapping. Return null to fall through
     * to the default generic mapping.
     *
     * @param backendOutput raw output from node execution
     * @return transformed output map, or null to use generic mapping
     */
    default Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        return null;
    }
}
