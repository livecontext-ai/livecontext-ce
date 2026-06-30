package com.apimarketplace.catalog.mapping.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JSONPath evaluator for validating and extracting data from JSON using strict paths.
 * 
 * This component provides methods to check if JSONPath expressions exist in JSON data
 * and extract values using various path types including relative (@.), absolute ($.), 
 * and parent navigation (^^.).
 */
@Component
public class JsonPathEvaluator {
    
    private static final Logger logger = LoggerFactory.getLogger(JsonPathEvaluator.class);
    
    private final ObjectMapper objectMapper;
    
    public JsonPathEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * Checks if a JSONPath exists in the given JSON data.
     * 
     * @param json The JSON string to evaluate
     * @param path The JSONPath expression to check
     * @param itemsPathOrNull Optional items path context for relative paths
     * @return true if the path exists and returns non-null values
     */
    public boolean pathExists(String json, String path, String itemsPathOrNull) {
        try {
            List<Object> results = extractAll(json, path, itemsPathOrNull);
            return !results.isEmpty() && results.stream().anyMatch(obj -> obj != null);
        } catch (Exception e) {
            logger.debug("Path evaluation failed for '{}': {}", path, e.getMessage());
            return false;
        }
    }
    
    /**
     * Extracts all values matching the given JSONPath from the JSON data.
     * 
     * @param json The JSON string to evaluate
     * @param path The JSONPath expression to evaluate
     * @param itemsPathOrNull Optional items path context for relative paths
     * @return List of extracted values (empty if none found)
     */
    public List<Object> extractAll(String json, String path, String itemsPathOrNull) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<Object> results = new ArrayList<>();
            
            if (itemsPathOrNull != null && !itemsPathOrNull.trim().isEmpty()) {
                // Extract items using items path first
                List<JsonNode> items = extractItems(root, itemsPathOrNull);
                for (JsonNode item : items) {
                    List<Object> itemResults = evaluatePathOnNode(item, path, root);
                    results.addAll(itemResults);
                }
            } else {
                // Evaluate path directly on root
                results.addAll(evaluatePathOnNode(root, path, root));
            }
            
            return results;
        } catch (Exception e) {
            logger.debug("JSONPath extraction failed for '{}': {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * Extracts items from JSON using the items path.
     */
    private List<JsonNode> extractItems(JsonNode root, String itemsPath) {
        List<JsonNode> items = new ArrayList<>();
        try {
            List<JsonNode> nodes = evaluatePathOnNodeRaw(root, itemsPath, root);
            for (JsonNode node : nodes) {
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        items.add(item);
                    }
                } else {
                    items.add(node);
                }
            }
        } catch (Exception e) {
            logger.debug("Items extraction failed for '{}': {}", itemsPath, e.getMessage());
        }
        return items;
    }
    
    /**
     * Evaluates a JSONPath on a specific node and returns raw JsonNode results.
     */
    private List<JsonNode> evaluatePathOnNodeRaw(JsonNode node, String path, JsonNode root) {
        try {
            PathContext context = parsePathPrefix(path);
            JsonNode startNode = getStartNode(node, root, context);

            if (startNode == null) {
                return new ArrayList<>();
            }

            return evaluatePathBody(startNode, context.body);
        } catch (Exception e) {
            logger.debug("Path evaluation failed for '{}': {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Evaluates a JSONPath on a specific node with context.
     */
    private List<Object> evaluatePathOnNode(JsonNode node, String path, JsonNode root) {
        List<Object> results = new ArrayList<>();

        List<JsonNode> pathResults = evaluatePathOnNodeRaw(node, path, root);

        // Convert to primitive values
        for (JsonNode result : pathResults) {
            Object value = extractPrimitiveValue(result);
            if (value != null) {
                results.add(value);
            }
        }

        return results;
    }
    
    /**
     * Parses the path prefix to determine the starting context.
     */
    private PathContext parsePathPrefix(String path) {
        if (path.startsWith("@.")) {
            return new PathContext(PathType.RELATIVE, 0, path.substring(2));
        } else if (path.startsWith("$.")) {
            return new PathContext(PathType.ABSOLUTE, 0, path.substring(2));
        } else if (path.startsWith("$")) {
            return new PathContext(PathType.ABSOLUTE, 0, path.substring(1));
        } else if (path.startsWith("^^")) {
            // Count parent navigation levels
            int upLevels = 0;
            String remaining = path;
            while (remaining.startsWith("^^.")) {
                upLevels++;
                remaining = remaining.substring(3);
            }
            return new PathContext(PathType.PARENT, upLevels, remaining);
        } else {
            return new PathContext(PathType.RELATIVE, 0, path);
        }
    }
    
    /**
     * Gets the starting node based on path context.
     */
    private JsonNode getStartNode(JsonNode currentNode, JsonNode rootNode, PathContext context) {
        switch (context.type) {
            case RELATIVE:
                return currentNode;
            case ABSOLUTE:
                return rootNode;
            case PARENT:
                // Navigate up the parent chain (simplified implementation)
                // In a real implementation, you'd need to maintain parent references
                return navigateUp(currentNode, context.upLevels);
            default:
                return currentNode;
        }
    }
    
    /**
     * Navigates up the parent chain (simplified implementation).
     */
    private JsonNode navigateUp(JsonNode node, int levels) {
        // This is a simplified implementation
        // In practice, you'd need to maintain parent references during traversal
        if (levels <= 0) return node;
        
        // For now, return the current node as we can't easily navigate up
        // without maintaining parent references during the initial traversal
        logger.debug("Parent navigation not fully implemented, returning current node");
        return node;
    }
    
    /**
     * Evaluates the path body on the starting node.
     */
    private List<JsonNode> evaluatePathBody(JsonNode startNode, String pathBody) {
        List<JsonNode> results = new ArrayList<>();
        
        if (pathBody == null || pathBody.trim().isEmpty()) {
            results.add(startNode);
            return results;
        }
        
        // Split path into segments, handling array access
        List<String> segments = tokenizePath(pathBody);
        List<JsonNode> current = List.of(startNode);
        
        for (String segment : segments) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                next.addAll(processSegment(node, segment));
            }
            current = next;
            if (current.isEmpty()) break;
        }
        
        results.addAll(current);
        return results;
    }
    
    /**
     * Tokenizes a path into segments, handling array notation.
     */
    private List<String> tokenizePath(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bracketDepth = 0;
        
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '[') bracketDepth++;
            if (c == ']') bracketDepth--;
            
            if (c == '.' && bracketDepth == 0) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        
        return tokens;
    }
    
    /**
     * Processes a single path segment (field access or array access).
     */
    private List<JsonNode> processSegment(JsonNode node, String segment) {
        List<JsonNode> results = new ArrayList<>();
        
        if (node == null || node.isNull()) {
            return results;
        }
        
        // Handle array access like field[0] or field[*]
        if (segment.contains("[")) {
            String fieldName = segment.substring(0, segment.indexOf("["));
            String indexStr = segment.substring(segment.indexOf("[") + 1, segment.indexOf("]"));
            
            JsonNode target = node;
            if (!fieldName.isEmpty()) {
                target = node.get(fieldName);
            }
            
            if (target != null && target.isArray()) {
                if ("*".equals(indexStr)) {
                    // Wildcard: add all elements
                    for (JsonNode element : target) {
                        results.add(element);
                    }
                } else {
                    // Specific index
                    try {
                        int index = Integer.parseInt(indexStr);
                        if (index >= 0 && index < target.size()) {
                            results.add(target.get(index));
                        }
                    } catch (NumberFormatException e) {
                        logger.debug("Invalid array index: {}", indexStr);
                    }
                }
            }
        } else {
            // Simple field access
            JsonNode child = node.get(segment);
            if (child != null) {
                results.add(child);
            }
        }
        
        return results;
    }
    
    /**
     * Extracts primitive values from JsonNode.
     */
    private Object extractPrimitiveValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            if (node.isInt()) {
                return node.asInt();
            } else if (node.isLong()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isArray()) {
            // For arrays, return the array as-is for further processing
            return node;
        } else if (node.isObject()) {
            // For objects, return null as we only want primitive values
            return null;
        }
        
        return null;
    }
    
    /**
     * Path context information.
     */
    private static class PathContext {
        final PathType type;
        final int upLevels;
        final String body;
        
        PathContext(PathType type, int upLevels, String body) {
            this.type = type;
            this.upLevels = upLevels;
            this.body = body;
        }
    }
    
    /**
     * Path type enumeration.
     */
    private enum PathType {
        RELATIVE,   // @.field
        ABSOLUTE,   // $.field
        PARENT      // ^^.field
    }
}
