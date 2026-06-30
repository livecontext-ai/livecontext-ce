package com.apimarketplace.conversation.service.ai.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Classifies tool calls by category for progressive masking, help deduplication,
 * and structured truncation in conversation history.
 */
@Slf4j
public final class ToolCallClassifier {

    private ToolCallClassifier() {}

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public enum ToolCategory {
        HELP, SEARCH, READ, MUTATION, EXECUTION, OTHER
    }

    /**
     * Classify a tool call based on toolName and arguments JSON.
     */
    public static ToolCategory classify(String toolName, String argumentsJson) {
        if (toolName == null) return ToolCategory.OTHER;

        String action = extractAction(argumentsJson);

        // Help detection
        if (isHelpCall(toolName, argumentsJson)) {
            return ToolCategory.HELP;
        }

        // Search
        if ("search".equals(action)) {
            return ToolCategory.SEARCH;
        }

        // Read
        if ("get".equals(action) || "list".equals(action) || "get_plan".equals(action)
                || "fetch".equals(action) || "describe".equals(action)
                || "runs".equals(action) || "response_schema".equals(action)) {
            return ToolCategory.READ;
        }

        // Mutation
        if ("create".equals(action) || "update".equals(action) || "delete".equals(action)
                || "save".equals(action) || "set_plan".equals(action) || "init".equals(action)
                || "add_node".equals(action) || "connect".equals(action)
                || "remove_node".equals(action) || "disconnect".equals(action)
                || "publish".equals(action) || "load".equals(action)) {
            return ToolCategory.MUTATION;
        }

        // Execution
        if ("execute".equals(action) || "run".equals(action) || "acquire".equals(action)) {
            return ToolCategory.EXECUTION;
        }

        return ToolCategory.OTHER;
    }

    /**
     * Return a dedup key for help calls. Same key = same help content.
     * Example: "workflow:help", "interface:help", "workflow:help:triggers,agent"
     */
    public static String helpDedupKey(String toolName, String argumentsJson) {
        if (toolName == null) return "unknown:help";

        String topics = extractStringField(argumentsJson, "topics");
        if (topics != null && !topics.isEmpty()) {
            return toolName + ":help:" + topics;
        }
        return toolName + ":help";
    }

    /**
     * Detect if this is a help call (action='help' or workflow help topics).
     */
    public static boolean isHelpCall(String toolName, String argumentsJson) {
        String action = extractAction(argumentsJson);
        return "help".equals(action);
    }

    /**
     * Extract a concise outcome summary (~50-100 chars) from a tool result.
     */
    public static String extractOutcomeSummary(String toolName, String argumentsJson,
                                               String resultContent, boolean success) {
        String action = extractAction(argumentsJson);
        String status = success ? "OK" : "ERROR";
        String tool = toolName != null ? toolName : "unknown";

        if (!success) {
            String errorPreview = resultContent != null
                    ? resultContent.substring(0, Math.min(60, resultContent.length()))
                    : "unknown error";
            return tool + " " + (action != null ? action : "call") + " → ERROR: " + errorPreview;
        }

        // Help calls
        if ("help".equals(action)) {
            String topics = extractStringField(argumentsJson, "topics");
            if (topics != null) {
                return tool + " help [" + topics + "]: OK";
            }
            return tool + " help: OK";
        }

        // Search with query
        if ("search".equals(action)) {
            String query = extractStringField(argumentsJson, "query");
            int resultCount = countResultItems(resultContent);
            String queryPart = query != null ? " '" + truncate(query, 30) + "'" : "";
            return tool + " search" + queryPart + ": " + resultCount + " results";
        }

        // Create/update with name/label
        if ("create".equals(action) || "init".equals(action)) {
            String name = extractNameFromArgs(argumentsJson);
            String id = extractIdFromResult(resultContent);
            String namePart = name != null ? " '" + truncate(name, 25) + "'" : "";
            String idPart = id != null ? ": id=" + truncate(id, 20) : ": OK";
            return tool + " " + action + namePart + idPart;
        }

        if ("update".equals(action) || "save".equals(action)) {
            return tool + " " + action + ": OK";
        }

        if ("execute".equals(action) || "run".equals(action)) {
            return tool + " " + action + ": " + status;
        }

        // Default
        return tool + " " + (action != null ? action : "call") + ": " + status;
    }

    /**
     * Extract key parameters for structured truncation (action, query, id, etc.)
     */
    public static String extractKeyParams(String toolName, String argumentsJson) {
        String action = extractAction(argumentsJson);
        if (action == null) return "";

        StringBuilder params = new StringBuilder("action='").append(action).append("'");

        String query = extractStringField(argumentsJson, "query");
        if (query != null) {
            params.append(", query='").append(truncate(query, 30)).append("'");
        }

        String id = extractStringField(argumentsJson, "id");
        if (id != null) {
            params.append(", id='").append(truncate(id, 20)).append("'");
        }

        String toolId = extractStringField(argumentsJson, "tool_id");
        if (toolId != null) {
            params.append(", tool_id='").append(truncate(toolId, 30)).append("'");
        }

        return params.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private static String extractAction(String argumentsJson) {
        return extractStringField(argumentsJson, "action");
    }

    private static String extractStringField(String argumentsJson, String fieldName) {
        if (argumentsJson == null || argumentsJson.isBlank()) return null;
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            JsonNode field = args.get(fieldName);
            if (field != null && field.isTextual()) {
                return field.asText();
            }
            // Handle arrays (e.g., topics=['triggers', 'agent'])
            if (field != null && field.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < field.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(field.get(i).asText());
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.trace("Failed to parse arguments JSON for field '{}': {}", fieldName, e.getMessage());
        }
        return null;
    }

    private static String extractNameFromArgs(String argumentsJson) {
        String name = extractStringField(argumentsJson, "name");
        if (name != null) return name;
        name = extractStringField(argumentsJson, "label");
        if (name != null) return name;
        return extractStringField(argumentsJson, "title");
    }

    private static String extractIdFromResult(String resultContent) {
        if (resultContent == null) return null;
        // Look for common ID patterns: "id": "xxx" or id=xxx
        int idIdx = resultContent.indexOf("\"id\"");
        if (idIdx == -1) idIdx = resultContent.indexOf("\"Id\"");
        if (idIdx >= 0) {
            int colonIdx = resultContent.indexOf(":", idIdx);
            if (colonIdx >= 0) {
                int startQuote = resultContent.indexOf("\"", colonIdx + 1);
                if (startQuote >= 0 && startQuote < colonIdx + 20) {
                    int endQuote = resultContent.indexOf("\"", startQuote + 1);
                    if (endQuote > startQuote && endQuote - startQuote < 60) {
                        return resultContent.substring(startQuote + 1, endQuote);
                    }
                }
            }
        }
        return null;
    }

    private static int countResultItems(String resultContent) {
        if (resultContent == null || resultContent.isEmpty()) return 0;
        // Count occurrences of common list markers
        int count = 0;
        int idx = 0;
        // Count JSON array items by commas between objects
        if (resultContent.trim().startsWith("[")) {
            for (char c : resultContent.toCharArray()) {
                if (c == '{') count++;
            }
            return count;
        }
        // Count line-based results
        for (String line : resultContent.split("\n")) {
            if (line.trim().startsWith("-") || line.trim().startsWith("*")
                    || line.trim().matches("^\\d+\\..*")) {
                count++;
            }
        }
        return Math.max(count, resultContent.isEmpty() ? 0 : 1);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen - 3) + "...";
    }
}
