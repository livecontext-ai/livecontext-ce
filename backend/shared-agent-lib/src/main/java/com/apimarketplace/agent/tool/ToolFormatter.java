package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;

import java.util.*;

/**
 * Utility class for formatting tools for different LLM providers.
 * Each provider has slightly different tool/function calling formats.
 */
public final class ToolFormatter {

    private ToolFormatter() {
        // Utility class - no instantiation
    }

    /**
     * Format tools for the specified provider.
     *
     * @param tools        The tools to format
     * @param providerName The target provider (openai, anthropic, google, mistral, deepseek)
     * @return Formatted tools ready for the LLM API
     */
    public static List<Map<String, Object>> formatToolsForProvider(List<ToolDefinition> tools, String providerName) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }

        return switch (providerName.toLowerCase()) {
            case "anthropic" -> formatToolsForClaude(tools);
            case "google" -> formatToolsForGemini(tools);
            default -> formatToolsForOpenAI(tools); // OpenAI, Mistral, DeepSeek use same format
        };
    }

    /**
     * Format tools for OpenAI-compatible APIs (OpenAI, Mistral, DeepSeek).
     * Uses the function calling format with type: "function".
     */
    public static List<Map<String, Object>> formatToolsForOpenAI(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", buildParametersSchema(tool));

            result.add(Map.of(
                "type", "function",
                "function", function
            ));
        }
        return result;
    }

    /**
     * Format tools for Anthropic Claude API.
     * Uses the tool format with input_schema.
     */
    public static List<Map<String, Object>> formatToolsForClaude(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> claudeTool = new HashMap<>();
            claudeTool.put("name", tool.name());
            claudeTool.put("description", tool.description());
            claudeTool.put("input_schema", buildParametersSchema(tool));
            result.add(claudeTool);
        }
        return result;
    }

    /**
     * Format tools for Google Gemini API.
     * Uses the functionDeclarations format.
     */
    public static List<Map<String, Object>> formatToolsForGemini(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> functionDecl = new HashMap<>();
            functionDecl.put("name", tool.name());
            functionDecl.put("description", tool.description());
            functionDecl.put("parameters", buildParametersSchema(tool));
            result.add(functionDecl);
        }
        return result;
    }

    /**
     * Build JSON Schema for tool parameters.
     * Standard format used by most providers.
     */
    public static Map<String, Object> buildParametersSchema(ToolDefinition tool) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        if (tool.parameters() != null && !tool.parameters().isEmpty()) {
            Map<String, Object> properties = new HashMap<>();

            for (ToolParameter param : tool.parameters()) {
                Map<String, Object> paramSchema = new HashMap<>();
                paramSchema.put("type", mapTypeToJsonSchema(param.type()));
                if (param.description() != null) {
                    paramSchema.put("description", param.description());
                }
                if (param.enumValues() != null && !param.enumValues().isEmpty()) {
                    paramSchema.put("enum", param.enumValues());
                }
                if (param.defaultValue() != null) {
                    paramSchema.put("default", param.defaultValue());
                }
                properties.put(param.name(), paramSchema);
            }

            schema.put("properties", properties);
            if (tool.requiredParameters() != null && !tool.requiredParameters().isEmpty()) {
                schema.put("required", tool.requiredParameters());
            }
        } else {
            schema.put("properties", Map.of());
        }

        return schema;
    }

    /**
     * Map common type names to JSON Schema types.
     */
    private static String mapTypeToJsonSchema(String type) {
        if (type == null) {
            return "string";
        }

        return switch (type.toLowerCase()) {
            case "string", "text", "str" -> "string";
            case "integer", "int", "long" -> "integer";
            case "number", "float", "double", "decimal" -> "number";
            case "boolean", "bool" -> "boolean";
            case "array", "list" -> "array";
            case "object", "map", "dict" -> "object";
            default -> "string";
        };
    }

    /**
     * Sanitize tool name for LLM function calling.
     * Most LLMs require alphanumeric characters and underscores only.
     *
     * @param name The original tool name
     * @return Sanitized name safe for function calling
     */
    public static String sanitizeToolName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown_tool";
        }
        // Replace non-alphanumeric chars with underscore, lowercase
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        // Ensure result is not empty after sanitization
        if (sanitized.isBlank() || sanitized.matches("^_+$")) {
            return "unknown_tool";
        }
        return sanitized;
    }

    /**
     * Validate that a tool name is valid for LLM function calling.
     *
     * @param name The tool name to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidToolName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        // Must match: alphanumeric and underscore, start with letter or underscore
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }
}
