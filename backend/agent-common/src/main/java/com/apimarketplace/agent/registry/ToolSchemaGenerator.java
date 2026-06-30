package com.apimarketplace.agent.registry;

import com.apimarketplace.agent.domain.ToolParameter;

import java.util.*;

/**
 * Utility class for generating JSON Schemas from tool definitions.
 */
public final class ToolSchemaGenerator {

    private ToolSchemaGenerator() {
        // Utility class
    }

    /**
     * Generate input schema from tool parameters.
     */
    public static Map<String, Object> generateInputSchema(
            List<ToolParameter> parameters,
            List<String> requiredParams) {

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (parameters == null || parameters.isEmpty()) {
            schema.put("properties", Map.of());
            return schema;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (ToolParameter param : parameters) {
            properties.put(param.name(), generateParameterSchema(param));
        }
        schema.put("properties", properties);

        if (requiredParams != null && !requiredParams.isEmpty()) {
            schema.put("required", requiredParams);
        }

        return schema;
    }

    /**
     * Generate schema for a single parameter.
     */
    public static Map<String, Object> generateParameterSchema(ToolParameter param) {
        Map<String, Object> paramSchema = new LinkedHashMap<>();

        // Map type
        String type = mapType(param.type());
        paramSchema.put("type", type);

        // Add description
        if (param.description() != null && !param.description().isBlank()) {
            paramSchema.put("description", param.description());
        }

        // Add enum values
        if (param.enumValues() != null && !param.enumValues().isEmpty()) {
            paramSchema.put("enum", param.enumValues());
        }

        // Add default value
        if (param.defaultValue() != null) {
            paramSchema.put("default", param.defaultValue());
        }

        // Handle nested properties for objects
        if ("object".equals(type) && param.properties() != null) {
            paramSchema.put("properties", param.properties());
        }

        // Handle array items
        if ("array".equals(type)) {
            paramSchema.put("items", Map.of("type", "string")); // Default to string items
        }

        return paramSchema;
    }

    /**
     * Map tool parameter type to JSON Schema type.
     */
    private static String mapType(String type) {
        if (type == null) return "string";
        return switch (type.toLowerCase()) {
            case "string", "text" -> "string";
            case "int", "integer", "long" -> "integer";
            case "float", "double", "number", "decimal" -> "number";
            case "bool", "boolean" -> "boolean";
            case "array", "list" -> "array";
            case "object", "map", "json" -> "object";
            default -> "string";
        };
    }

    /**
     * Create a simple string parameter.
     */
    public static ToolParameter stringParam(String name, String description, boolean required) {
        return ToolParameter.builder()
            .name(name)
            .type("string")
            .description(description)
            .required(required)
            .build();
    }

    /**
     * Create an integer parameter.
     */
    public static ToolParameter intParam(String name, String description, boolean required, Integer defaultValue) {
        return ToolParameter.builder()
            .name(name)
            .type("integer")
            .description(description)
            .required(required)
            .defaultValue(defaultValue)
            .build();
    }

    /**
     * Create a boolean parameter.
     */
    public static ToolParameter boolParam(String name, String description, boolean required, Boolean defaultValue) {
        return ToolParameter.builder()
            .name(name)
            .type("boolean")
            .description(description)
            .required(required)
            .defaultValue(defaultValue)
            .build();
    }

    /**
     * Create an enum parameter.
     */
    public static ToolParameter enumParam(String name, String description, boolean required, List<String> values) {
        return ToolParameter.builder()
            .name(name)
            .type("string")
            .description(description)
            .required(required)
            .enumValues(values)
            .build();
    }

    /**
     * Create an object parameter.
     */
    public static ToolParameter objectParam(String name, String description, boolean required) {
        return ToolParameter.builder()
            .name(name)
            .type("object")
            .description(description)
            .required(required)
            .build();
    }

    /**
     * Create an array parameter.
     */
    public static ToolParameter arrayParam(String name, String description, boolean required) {
        return ToolParameter.builder()
            .name(name)
            .type("array")
            .description(description)
            .required(required)
            .build();
    }

    /**
     * Create an object parameter with nested properties schema.
     */
    public static ToolParameter objectParamWithProperties(String name, String description, boolean required, Map<String, ToolParameter> properties) {
        return ToolParameter.builder()
            .name(name)
            .type("object")
            .description(description)
            .required(required)
            .properties(properties)
            .build();
    }

    /**
     * Generate schema for workflow plan resource.
     *
     * Uses 7-prefix system:
     * - trigger: Entry points (webhook, chat, schedule, datasource, manual)
     * - mcp: MCP catalog tool calls
     * - table: CRUD operations (database tables)
     * - agent: AI agents (agent, guardrail, classify)
     * - core: Control flow (decision, switch, loop, split, merge, fork, transform, wait)
     * - note: Notes
     * - interface: UI interfaces
     */
    public static Map<String, Object> getWorkflowPlanSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        // Triggers - Entry points
        properties.put("triggers", Map.of(
            "type", "array",
            "description", "Entry points for the workflow (prefix: trigger:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "Trigger identifier"),
                    "type", Map.of("type", "string", "enum", List.of("webhook", "chat", "schedule", "datasource", "manual"), "description", "Trigger type"),
                    "label", Map.of("type", "string", "description", "Unique label for the trigger"),
                    "strategy", Map.of("type", "string", "enum", List.of("single", "all")),
                    "input", Map.of("type", "object", "description", "Input parameters"),
                    "position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")))
                ),
                "required", List.of("id", "type")
            )
        ));

        // MCPs - MCP catalog tool calls only
        properties.put("mcps", Map.of(
            "type", "array",
            "description", "MCP catalog tool calls (prefix: mcp:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "Tool slug (e.g., api-slug/tool-slug)"),
                    "type", Map.of("type", "string", "enum", List.of("mcp"), "default", "mcp"),
                    "label", Map.of("type", "string", "description", "Unique label for the step"),
                    "input", Map.of("type", "object", "description", "Input parameters for the tool"),
                    "position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")))
                ),
                "required", List.of("id", "label")
            )
        ));

        // Tables - CRUD operations
        properties.put("tables", Map.of(
            "type", "array",
            "description", "CRUD operations on datasources (prefix: table:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "type", Map.of("type", "string", "enum", List.of("crud-create-row", "crud-read-row", "crud-update-row", "crud-delete-row", "crud-create-column"), "description", "CRUD operation type"),
                    "label", Map.of("type", "string", "description", "Unique label for the operation"),
                    "dataSourceId", Map.of("type", "string", "description", "Target datasource ID"),
                    "crud", Map.of("type", "object", "description", "CRUD-specific configuration (where, set, rows, columns, limit)"),
                    "input", Map.of("type", "object", "description", "Input parameters"),
                    "position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")))
                ),
                "required", List.of("type", "label")
            )
        ));

        // Agents - AI agents
        properties.put("agents", Map.of(
            "type", "array",
            "description", "AI agents (prefix: agent:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "Agent identifier"),
                    "type", Map.of("type", "string", "enum", List.of("agent", "guardrail", "classify"), "description", "Agent type"),
                    "label", Map.of("type", "string", "description", "Unique label for the agent"),
                    "provider", Map.of("type", "string", "description", "AI provider (openai, anthropic, etc.)"),
                    "model", Map.of("type", "string", "description", "Model name"),
                    "systemPrompt", Map.of("type", "string", "description", "System prompt"),
                    "prompt", Map.of("type", "string", "description", "User prompt template"),
                    "tools", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Tool references (mcp:label or agent:label)"),
                    "input", Map.of("type", "object", "description", "Input parameters"),
                    "position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")))
                ),
                "required", List.of("id", "type", "label")
            )
        ));

        // Cores - Control flow nodes
        properties.put("cores", Map.of(
            "type", "array",
            "description", "Control flow nodes (prefix: core:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                    Map.entry("id", Map.of("type", "string", "description", "Core identifier")),
                    Map.entry("type", Map.of("type", "string", "enum", List.of("decision", "switch", "loop", "split", "merge", "fork", "transform", "wait"), "description", "Core type")),
                    Map.entry("label", Map.of("type", "string", "description", "Label for the core")),
                    Map.entry("decisionConditions", Map.of("type", "array", "description", "Conditions for decision nodes")),
                    Map.entry("switchExpression", Map.of("type", "string", "description", "Expression for switch nodes")),
                    Map.entry("switchCases", Map.of("type", "array", "description", "Cases for switch nodes")),
                    Map.entry("loopCondition", Map.of("type", "string", "description", "Condition for loop nodes")),
                    Map.entry("list", Map.of("type", "string", "description", "List expression for split nodes")),
                    Map.entry("transform", Map.of("type", "object", "description", "Transform mappings for transform nodes")),
                    Map.entry("wait", Map.of("type", "object", "description", "Wait duration for wait nodes")),
                    Map.entry("input", Map.of("type", "object", "description", "Input parameters")),
                    Map.entry("position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number"))))
                ),
                "required", List.of("id", "type")
            )
        ));

        // Notes
        properties.put("notes", Map.of(
            "type", "array",
            "description", "Documentation notes (prefix: note:)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "id", Map.of("type", "string", "description", "Note identifier"),
                    "type", Map.of("type", "string", "enum", List.of("note"), "default", "note"),
                    "text", Map.of("type", "string", "description", "Note content"),
                    "color", Map.of("type", "string", "description", "Background color"),
                    "position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")))
                ),
                "required", List.of("id", "text")
            )
        ));

        // Interfaces
        properties.put("interfaces", Map.of(
            "type", "array",
            "description", "Interface nodes (prefix: interface:). 3 modes: DISPLAY (variable_mapping), APPLICATION (action_mapping + triggers = interactive app), MULTI-PAGE (navigate between interfaces).",
            "items", Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                    Map.entry("id", Map.of("type", "string", "description", "Interface identifier (UUID from interface(action='create'))")),
                    Map.entry("label", Map.of("type", "string", "description", "Interface label")),
                    Map.entry("variable_mapping", Map.of("type", "object", "description", "Map generic template variable names to workflow expressions. Example: {\"title\": \"{{mcp:fetch.output.name}}\", \"price\": \"{{mcp:fetch.output.price}}\"}")),
                    Map.entry("action_mapping", Map.of("type", "object", "description", "Map CSS selectors to workflow triggers or other interfaces. Format: {\"cssSelector\": \"trigger:label:actiontype\"}. Action types: click (manual trigger), submit (form trigger), message (chat trigger), navigate (switch interface).")),
                    Map.entry("position", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number"))))
                ),
                "required", List.of("id")
            )
        ));

        // Edges
        properties.put("edges", Map.of(
            "type", "array",
            "description", "Connections between nodes using prefixed references (e.g., trigger:my_trigger, mcp:fetch_data, core:check)",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "from", Map.of("type", "string", "description", "Source node reference with optional port (e.g., core:check:if)"),
                    "to", Map.of("type", "string", "description", "Target node reference"),
                    "input", Map.of("type", "object", "description", "Optional input mapping for the target node")
                ),
                "required", List.of("from", "to")
            )
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Workflow plan definition using 7-prefix system: trigger:, mcp:, table:, agent:, core:, note:, interface:");
        schema.put("properties", properties);
        schema.put("required", List.of("triggers", "edges"));

        return schema;
    }

    /**
     * Generate schema for agent configuration resource.
     */
    public static Map<String, Object> getAgentConfigSchema() {
        return Map.of(
            "type", "object",
            "description", "AI Agent configuration",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", "Agent name"),
                "systemPrompt", Map.of("type", "string", "description", "System prompt for the agent"),
                "provider", Map.of("type", "string", "enum", List.of("openai", "anthropic", "google", "mistral")),
                "model", Map.of("type", "string", "description", "Model name (e.g., gpt-4, claude-3)"),
                "temperature", Map.of("type", "number", "minimum", 0, "maximum", 2),
                "maxTokens", Map.of("type", "integer"),
                "maxIterations", Map.of("type", "integer", "default", 10),
                "tools", Map.of("type", "array", "items", Map.of("type", "string"))
            ),
            "required", List.of("name")
        );
    }

    /**
     * Generate schema for interface resource.
     */
    public static Map<String, Object> getInterfaceSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Interface name"));
        properties.put("description", Map.of("type", "string", "description", "Description of the interface (display data, interactive app, or multi-page navigation)"));
        properties.put("htmlTemplate", Map.of("type", "string", "description", "HTML template with generic {{variable|default}} syntax. Use pipe (|) for inline defaults. Variables are mapped to workflow data on the interface node."));
        properties.put("cssTemplate", Map.of("type", "string", "description", "Optional CSS stylesheet injected via <style> tag inside the iframe. Use for complex styling that doesn't fit inline."));
        properties.put("jsTemplate", Map.of("type", "string", "description", "Optional custom JavaScript executed inside the iframe after HTML renders. For dynamic behavior (charts, animations). The action_mapping bridge script is auto-injected separately."));
        properties.put("targetTable", Map.of("type", "string", "description", "Target database table"));
        properties.put("dataSourceId", Map.of("type", "integer"));
        properties.put("variable_mapping", Map.of("type", "object", "description", "Map generic template variable names to workflow expressions. Configured on the workflow interface node. Example: {\"title\": \"{{mcp:fetch.output.name}}\"}"));
        properties.put("action_mapping", Map.of("type", "object", "description", "Map CSS selectors to workflow triggers or interfaces. Format: {\"cssSelector\": \"trigger:label:actiontype\"}. Action types: submit (form→trigger), click (button→trigger), message (chat→trigger), navigate (→interface), __pagination:prev/next (navigate between items by itemIndex). A bridge script is auto-injected - no JavaScript needed."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Interface definition. 3 modes: DISPLAY (variable_mapping), APPLICATION (action_mapping + triggers = interactive app), MULTI-PAGE (navigate). Templates use generic {{variable|default}} syntax.");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        return schema;
    }

    /**
     * Generate schema for datasource resource.
     */
    public static Map<String, Object> getDataSourceSchema() {
        return Map.of(
            "type", "object",
            "description", "Data source for storing collections of data items",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", "Data source name"),
                "description", Map.of("type", "string", "description", "Optional description"),
                "data", Map.of("type", "array", "description", "Array of data objects", "items", Map.of("type", "object"))
            ),
            "required", List.of("name", "data")
        );
    }
}
