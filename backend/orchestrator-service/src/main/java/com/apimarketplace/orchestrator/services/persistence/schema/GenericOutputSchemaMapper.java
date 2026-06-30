package com.apimarketplace.orchestrator.services.persistence.schema;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic output schema mapper that uses NodeDefinition metadata to transform
 * backend node output to DB schema format.
 *
 * This mapper uses NodeDefinition metadata from NodeSpec companions to transform
 * backend node output to DB schema format. It uses the OutputFieldDef declarations to:
 * 1. Extract fields by primary key, camelCase/snakeCase variants, or aliases
 * 2. Apply default values when fields are missing
 * 3. Handle special sentinels like __NOW__ for timestamps
 * 4. Coerce Number values to int when the default is an integer
 * 5. Skip conditional fields (no default) when not present in raw output
 */
@Service
public class GenericOutputSchemaMapper {

    private static final Logger logger = LoggerFactory.getLogger(GenericOutputSchemaMapper.class);

    private static final String NOW_SENTINEL = "__NOW__";

    /**
     * Engine-envelope keys injected by the execution engine or SftpNode.buildErrorResult()
     * that must NEVER be persisted to the DB output JSONB.
     * <ul>
     *   <li>{@code node_type} - engine type tag</li>
     *   <li>{@code item_index} / {@code itemIndex} - split-context index variants</li>
     *   <li>{@code item_id} - split-context item identifier</li>
     *   <li>{@code resolved_params} - inspector-only resolved parameter snapshot</li>
     * </ul>
     * NodeSpec implementations that override {@link com.apimarketplace.agent.domain.NodeSpec#customTransform}
     * should remove these keys from their output to avoid leaking engine internals into the
     * persisted JSONB.
     */
    public static final java.util.Set<String> ENGINE_ENVELOPE_KEYS = java.util.Set.of(
        "node_type", "item_index", "itemIndex", "item_id", "resolved_params"
    );

    private final NodeDefinitionRegistry registry;

    public GenericOutputSchemaMapper(NodeDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Transform backend output using the NodeDefinition for the given node type.
     *
     * @param nodeType      The node type (e.g., "EXIT", "FILTER")
     * @param backendOutput The raw output from node execution
     * @return Transformed output, or null if no definition found
     */
    public Map<String, Object> transform(String nodeType, Map<String, Object> backendOutput) {
        if (nodeType == null || backendOutput == null) {
            return null;
        }

        Optional<NodeDefinition> defOpt = registry.get(nodeType);
        if (defOpt.isEmpty()) {
            return null;
        }

        // Check for custom transform on the NodeSpec first
        Optional<NodeSpec> specOpt = registry.getSpec(nodeType);
        if (specOpt.isPresent()) {
            Map<String, Object> customResult = specOpt.get().customTransform(backendOutput);
            if (customResult != null) {
                logger.debug("Custom transform for {} output: keys={}", nodeType, customResult.keySet());
                return customResult;
            }
        }

        // Generic field-by-field mapping
        NodeDefinition def = defOpt.get();
        Map<String, Object> dbOutput = new LinkedHashMap<>();

        for (OutputFieldDef field : def.outputs()) {
            Object value = resolveFieldValue(field, backendOutput);

            if (value != null) {
                dbOutput.put(field.key(), value);
            } else if (field.hasDefault()) {
                dbOutput.put(field.key(), applyDefault(field.defaultValue()));
            }
            // Conditional fields (no default) are skipped when null
        }

        logger.debug("Generic mapper transformed {} output: keys={}", nodeType, dbOutput.keySet());
        return dbOutput;
    }

    /**
     * Check if this mapper can handle the given node type.
     */
    public boolean canHandle(String nodeType) {
        return registry.has(nodeType);
    }

    /**
     * Resolve field value from backend output, trying:
     * 1. Primary key
     * 2. camelCase variant (if key is snake_case)
     * 3. snakeCase variant (if key is camelCase)
     * 4. Each alias in order
     */
    private Object resolveFieldValue(OutputFieldDef field, Map<String, Object> backendOutput) {
        // 1. Try primary key
        Object value = backendOutput.get(field.key());
        if (value != null) {
            return coerceValue(value, field);
        }

        // 2. Try camelCase variant of snake_case key
        String camelCase = snakeToCamel(field.key());
        if (!camelCase.equals(field.key())) {
            value = backendOutput.get(camelCase);
            if (value != null) {
                return coerceValue(value, field);
            }
        }

        // 3. Try snake_case variant of camelCase key
        String snakeCase = camelToSnake(field.key());
        if (!snakeCase.equals(field.key())) {
            value = backendOutput.get(snakeCase);
            if (value != null) {
                return coerceValue(value, field);
            }
        }

        // 4. Try aliases
        for (String alias : field.aliases()) {
            value = backendOutput.get(alias);
            if (value != null) {
                return coerceValue(value, field);
            }
        }

        return null;
    }

    /**
     * Apply type coercion based on the field definition.
     * Numbers with integer defaults get .intValue() coercion.
     */
    private Object coerceValue(Object value, OutputFieldDef field) {
        if (value instanceof Number number && isIntegerDefault(field)) {
            return number.intValue();
        }
        return value;
    }

    /**
     * Check if the field's default value is an integer (for Number coercion).
     */
    private boolean isIntegerDefault(OutputFieldDef field) {
        return field.defaultValue() instanceof Integer;
    }

    /**
     * Apply default value, handling special sentinels.
     */
    private Object applyDefault(Object defaultValue) {
        if (NOW_SENTINEL.equals(defaultValue)) {
            return Instant.now().toString();
        }
        return defaultValue;
    }

    /**
     * Convert snake_case to camelCase: "sorted_items" -> "sortedItems"
     */
    static String snakeToCamel(String snake) {
        if (snake == null || !snake.contains("_")) {
            return snake;
        }
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Convert camelCase to snake_case: "sortedItems" -> "sorted_items"
     */
    static String camelToSnake(String camel) {
        if (camel == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
