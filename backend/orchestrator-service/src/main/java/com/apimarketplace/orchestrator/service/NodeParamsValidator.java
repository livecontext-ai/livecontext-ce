package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.validation.ValidationError;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Validates node parameters against their schema from the database.
 * Returns errors and suggestions for corrections.
 */
@Service
public class NodeParamsValidator {

    private final NodeLibraryService nodeLibraryService;
    private final ModelCatalogEnricher modelCatalogEnricher;

    /**
     * Known parameter aliases per node type.
     * LLMs often use alternative names for the same parameter.
     * These aliases are accepted by the creators, so the validator should not reject them.
     * Key: node type, Value: map of alias -> canonical parameter name.
     */
    private static final Map<String, Map<String, String>> PARAM_ALIASES = Map.ofEntries(
        Map.entry("agent", Map.ofEntries(
            // Valid prompt aliases (these map to the node's 'prompt' parameter)
            Map.entry("instruction", "prompt"), Map.entry("message", "prompt"),
            Map.entry("task", "prompt"), Map.entry("input", "prompt"),
            // Memory alias
            Map.entry("with_memory", "withMemory")
            // NOTE: system_prompt, model_provider, model_name are ENTITY params, NOT node params.
            // They are detected and flagged by the entity-param detector below.
        )),
        Map.entry("classify", Map.of(
            "instruction", "prompt", "system_prompt", "prompt",
            "content", "prompt", "input", "prompt",
            "text", "prompt", "data", "prompt"
        )),
        Map.entry("guardrail", Map.of(
            "content", "input", "text", "input",
            "system_prompt", "prompt", "instruction", "prompt"
        )),
        Map.entry("decision", Map.of(
            "branches", "conditions", "rules", "conditions",
            "cases", "conditions"
        )),
        Map.entry("split", Map.of(
            "list", "items", "input", "items", "array", "items",
            "data", "items", "collection", "items", "source", "items"
        )),
        Map.entry("aggregate", Map.ofEntries(
            Map.entry("mappings", "fields"), Map.entry("transformations", "fields"),
            Map.entry("values", "fields"), Map.entry("aggregations", "fields"),
            Map.entry("collect", "fields"), Map.entry("outputs", "fields")
        )),
        Map.entry("transform", Map.of(
            "fields", "mappings", "transformations", "mappings",
            "values", "mappings", "outputs", "mappings"
        )),
        Map.entry("loop", Map.of(
            "maxIterations", "max_iterations"
        )),
        Map.entry("switch", Map.of(
            "switchExpression", "expression", "switchCases", "cases"
        )),
        Map.entry("fork", Map.of(
            "outputs", "branches"
        )),
        Map.entry("merge", Map.of()),
        Map.entry("wait", Map.of(
            "delay", "duration", "seconds", "duration"
        )),
        Map.entry("download_file", Map.ofEntries(
            Map.entry("source", "url"), Map.entry("link", "url"),
            Map.entry("file_url", "url"), Map.entry("href", "url"), Map.entry("src", "url"),
            Map.entry("file_name", "filename"), Map.entry("output", "filename")
        )),
        Map.entry("response", Map.of(
            "text", "message", "content", "message", "body", "message"
        )),
        Map.entry("http_request", Map.ofEntries(
            Map.entry("auth_type", "authType"), Map.entry("auth_config", "authConfig"),
            Map.entry("query_params", "queryParams"), Map.entry("body_type", "bodyType"),
            Map.entry("endpoint", "url"), Map.entry("uri", "url")
        )),
        // Find rows (crud-find) aliases - hybrid CRUD read + split parallel
        // #TC3: help docs for find_rows recommend `dataSourceId` - validator must
        // accept that alias too or it rejects plans that follow the help verbatim.
        Map.entry("find_rows", Map.ofEntries(
            Map.entry("tableId", "table_id"), Map.entry("datasource_id", "table_id"),
            Map.entry("dataSourceId", "table_id"),
            Map.entry("filter", "where"), Map.entry("condition", "where"),
            Map.entry("max", "maxItems"), Map.entry("limit", "maxItems"),
            Map.entry("max_items", "maxItems"), Map.entry("batch_size", "maxItems"),
            Map.entry("items", "list"), Map.entry("source", "list"),
            Map.entry("expression", "list"), Map.entry("data", "list")
        )),
        // Table CRUD common aliases (LLMs often use camelCase or alternate names)
        // #TC3: dataSourceId accepted everywhere for consistency with find_rows docs.
        Map.entry("insert_row", Map.of(
            "tableId", "table_id", "datasource_id", "table_id", "dataSourceId", "table_id",
            "data", "columns", "row", "columns", "values", "columns"
        )),
        Map.entry("get_rows", Map.of(
            "tableId", "table_id", "datasource_id", "table_id", "dataSourceId", "table_id",
            "filter", "where", "condition", "where"
        )),
        Map.entry("update_row", Map.ofEntries(
            Map.entry("tableId", "table_id"), Map.entry("datasource_id", "table_id"),
            Map.entry("dataSourceId", "table_id"),
            Map.entry("filter", "where"), Map.entry("condition", "where"),
            Map.entry("values", "set"), Map.entry("data", "set"), Map.entry("update", "set")
        )),
        Map.entry("delete_row", Map.of(
            "tableId", "table_id", "datasource_id", "table_id", "dataSourceId", "table_id",
            "filter", "where", "condition", "where"
        )),
        Map.entry("create_column", Map.of(
            "tableId", "table_id", "datasource_id", "table_id", "dataSourceId", "table_id"
        )),
        // Interface aliases - agents may use snake_case (consistent with interface_id, variable_mapping)
        // OR camelCase (the canonical DB doc keys). Both must validate.
        Map.entry("interface", Map.of(
            "is_entry_interface", "isEntryInterface",
            "generate_screenshot", "generateScreenshot",
            "expose_rendered_source", "exposeRenderedSource"
        ))
    );

    public NodeParamsValidator(NodeLibraryService nodeLibraryService,
                               ModelCatalogEnricher modelCatalogEnricher) {
        this.nodeLibraryService = nodeLibraryService;
        this.modelCatalogEnricher = modelCatalogEnricher;
    }

    /**
     * Validate parameters for a node type.
     *
     * @param nodeType The node type (e.g., "agent", "decision", "split")
     * @param params   The parameters to validate
     * @return ValidationResult with errors and suggestions
     */
    @SuppressWarnings("unchecked")
    public ValidationResult validate(String nodeType, Map<String, Object> params) {
        if (nodeType == null || nodeType.isBlank()) {
            return ValidationResult.invalid(
                List.of(new ValidationError("type", "MISSING_TYPE", "Node type is required")),
                List.of("Specify a valid node type (e.g., 'agent', 'decision', 'split')")
            );
        }

        Optional<NodeTypeDocumentationEntity> nodeOpt = nodeLibraryService.findByType(nodeType);
        if (nodeOpt.isEmpty()) {
            return ValidationResult.invalid(
                List.of(new ValidationError("type", "UNKNOWN_TYPE", "Unknown node type: " + nodeType)),
                List.of("Use workflow(action='help', topics=['nodes']) to see available node types")
            );
        }

        NodeTypeDocumentationEntity node = nodeOpt.get();
        Map<String, Object> schemaParams = node.getParameters();

        if (schemaParams == null || schemaParams.isEmpty()) {
            // No parameters defined for this node type
            return ValidationResult.success();
        }

        // For classify/guardrail: rewrite provider.enum from the live model catalog so
        // validation accepts the same providers the help layer advertises (same source
        // of truth as NodeHelpFormatter). Deep-copied - entity map is never mutated.
        schemaParams = modelCatalogEnricher.enrichIfLlm(nodeType, schemaParams);

        List<ValidationError> errors = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        Map<String, Object> actualParams = params != null ? params : Map.of();

        // Get aliases for this node type (if any)
        Map<String, String> aliases = PARAM_ALIASES.getOrDefault(nodeType, Map.of());

        // Check required parameters - skip if an alias is present
        for (Map.Entry<String, Object> entry : schemaParams.entrySet()) {
            String paramName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) continue;

            // Skip 'label' - it's always passed at the top level, not inside params
            if ("label".equals(paramName)) continue;

            Map<String, Object> paramSchema = (Map<String, Object>) entry.getValue();
            boolean required = Boolean.TRUE.equals(paramSchema.get("required"));

            if (required && !actualParams.containsKey(paramName)) {
                // Check if any alias for this canonical param is present
                boolean aliasPresent = aliases.entrySet().stream()
                    .anyMatch(a -> a.getValue().equals(paramName) && actualParams.containsKey(a.getKey()));
                if (aliasPresent) continue; // alias covers the required param

                errors.add(new ValidationError(
                    paramName,
                    "MISSING_REQUIRED",
                    "Required parameter '" + paramName + "' is missing"
                ));

                // Build suggestion with example if available
                Object example = paramSchema.get("example");
                String desc = (String) paramSchema.get("description");
                if (example != null) {
                    suggestions.add("Add '" + paramName + "': " + formatExample(example) + (desc != null ? " (" + desc + ")" : ""));
                } else if (desc != null) {
                    suggestions.add("Add '" + paramName + "': " + desc);
                } else {
                    suggestions.add("Add '" + paramName + "'");
                }
            }
        }

        // Check provided parameters - accept known aliases
        for (Map.Entry<String, Object> entry : actualParams.entrySet()) {
            String paramName = entry.getKey();
            Object value = entry.getValue();

            // Skip 'label' as it's always valid
            if ("label".equals(paramName)) continue;

            // Accept if it's a known alias
            if (aliases.containsKey(paramName)) continue;

            if (!schemaParams.containsKey(paramName)) {
                errors.add(new ValidationError(
                    paramName,
                    "UNKNOWN_PARAM",
                    "Unknown parameter '" + paramName + "' for node type '" + nodeType + "'"
                ));
                suggestions.add("Remove '" + paramName + "' or use workflow(action='help', topics=['" + nodeType + "']) to see valid parameters");
                continue;
            }

            Map<String, Object> paramSchema = (Map<String, Object>) schemaParams.get(paramName);
            if (paramSchema == null) continue;

            // Type validation
            String expectedType = (String) paramSchema.get("type");
            if (expectedType != null && !isValidType(value, expectedType)) {
                errors.add(new ValidationError(
                    paramName,
                    "INVALID_TYPE",
                    "Parameter '" + paramName + "' expected type " + expectedType + ", got " + getTypeName(value)
                ));
            }

            // Enum validation
            Object enumValues = paramSchema.get("enum");
            if (enumValues instanceof List<?> allowedValues) {
                if (!allowedValues.contains(value)) {
                    errors.add(new ValidationError(
                        paramName,
                        "INVALID_ENUM",
                        "Parameter '" + paramName + "' must be one of: " + allowedValues
                    ));
                }
            }

            // Numeric range validation
            if (value instanceof Number numValue) {
                Number min = (Number) paramSchema.get("min");
                Number max = (Number) paramSchema.get("max");

                if (min != null && numValue.doubleValue() < min.doubleValue()) {
                    errors.add(new ValidationError(
                        paramName,
                        "BELOW_MIN",
                        "Parameter '" + paramName + "' must be >= " + min
                    ));
                }
                if (max != null && numValue.doubleValue() > max.doubleValue()) {
                    errors.add(new ValidationError(
                        paramName,
                        "ABOVE_MAX",
                        "Parameter '" + paramName + "' must be <= " + max
                    ));
                }
            }

            // Array minItems validation
            if (value instanceof List<?> listValue) {
                Number minItems = (Number) paramSchema.get("minItems");
                if (minItems != null && listValue.size() < minItems.intValue()) {
                    errors.add(new ValidationError(
                        paramName,
                        "TOO_FEW_ITEMS",
                        "Parameter '" + paramName + "' must have at least " + minItems + " items"
                    ));
                }
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.success();
        }

        // For interface nodes with unknown params: these are likely template variables
        // that should go inside variable_mapping, not as direct params
        if ("interface".equals(nodeType)) {
            // variable_mapping and action_mapping are known interface params - accept them even if not yet in DB schema
            Set<String> knownInterfaceParams = Set.of("variable_mapping", "action_mapping");

            List<String> unknownNames = errors.stream()
                .filter(e -> "UNKNOWN_PARAM".equals(e.code()))
                .map(ValidationError::parameter)
                .filter(name -> !knownInterfaceParams.contains(name))
                .toList();

            // Remove errors for known interface params (variable_mapping)
            errors.removeIf(e -> "UNKNOWN_PARAM".equals(e.code()) && knownInterfaceParams.contains(e.parameter()));

            // After removing known params, if no errors remain, it's valid
            if (errors.isEmpty()) {
                return ValidationResult.success();
            }

            if (!unknownNames.isEmpty()) {
                suggestions.clear();
                suggestions.add("These look like template variables, NOT node parameters. " +
                    "Put them inside 'variable_mapping': params={interface_id: '<uuid>', variable_mapping: {" +
                    unknownNames.stream()
                        .map(v -> "'" + v + "': '{{mcp:<previous_step>.output." + v + "}}'")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("") +
                    "}}");
            }
        }

        // Detect common LLM confusion: passing agent entity params as node params
        if ("agent".equals(nodeType)) {
            Set<String> agentEntityParams = Set.of(
                "agent_name", "system_prompt", "name", "model_provider", "model_name",
                "model", "provider", "temperature", "max_tokens", "max_iterations",
                "tools_mode", "tools", "description", "execution_timeout");
            List<String> detectedEntityParams = errors.stream()
                .filter(e -> "UNKNOWN_PARAM".equals(e.code()))
                .map(ValidationError::parameter)
                .filter(agentEntityParams::contains)
                .toList();
            if (!detectedEntityParams.isEmpty()) {
                suggestions.clear();
                suggestions.add("You passed agent ENTITY parameters (" + String.join(", ", detectedEntityParams)
                    + ") as workflow NODE parameters. These belong on the agent entity, not the workflow node.");
                suggestions.add("Agent nodes require 2 steps:");
                suggestions.add("1. agent(action='create', name='...', system_prompt='...') → returns agent_id UUID");
                suggestions.add("2. workflow(action='add_node', type='agent', params={agent_id: '<uuid>', prompt: '...'})");
                suggestions.add("Valid node params: agent_id (required), prompt (optional), withMemory (optional)");
            }
        }

        // Add general help suggestion
        suggestions.add("Use workflow(action='help', topics=['" + nodeType + "']) for full parameter documentation");

        return ValidationResult.invalid(errors, suggestions);
    }

    /**
     * Check if a value matches the expected type.
     */
    private boolean isValidType(Object value, String expectedType) {
        if (value == null) return true; // null is allowed for optional params

        return switch (expectedType.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer", "int" -> value instanceof Integer || value instanceof Long;
            case "number", "float", "double" -> value instanceof Number;
            case "boolean", "bool" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            case "uuid" -> value instanceof String && isValidUuid((String) value);
            case "string|object" -> value instanceof String || value instanceof Map;
            case "any", "varies" -> true;
            default -> true;
        };
    }

    /**
     * Get a human-readable type name for a value.
     */
    private String getTypeName(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "string";
        if (value instanceof Integer || value instanceof Long) return "integer";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List) return "array";
        if (value instanceof Map) return "object";
        return value.getClass().getSimpleName();
    }

    /**
     * Check if a string is a valid UUID.
     */
    private boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Format an example value for display.
     */
    private String formatExample(Object example) {
        if (example instanceof String) {
            return "'" + example + "'";
        }
        return String.valueOf(example);
    }
}
