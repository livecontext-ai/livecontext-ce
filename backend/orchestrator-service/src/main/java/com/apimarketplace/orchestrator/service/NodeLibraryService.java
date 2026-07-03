package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.repository.NodeTypeDocumentationRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for accessing node type documentation from the database.
 * Replaces the hardcoded NodeLibrary with database-driven data.
 *
 * The database table node_type_documentation is the single source of truth
 * for all node type definitions, organized by variable_prefix:
 * - triggers (prefix: trigger:)
 * - agents (prefix: agent:)
 * - cores (prefix: core:)
 * - tables (prefix: table:)
 * - mcps (prefix: mcp:)
 * - interfaces (prefix: interface:)
 */
@Service
public class NodeLibraryService {

    private final NodeTypeDocumentationRepository repository;

    // Variable prefix to plural category name mapping
    private static final Map<String, String> PREFIX_TO_CATEGORY = Map.of(
        "trigger", "triggers",
        "agent", "agents",
        "core", "cores",
        "table", "tables",
        "mcp", "mcps",
        "interface", "interfaces"
    );

    public NodeLibraryService(NodeTypeDocumentationRepository repository) {
        this.repository = repository;
    }

    /**
     * Get node types organized by variable prefix category.
     * Used by workflow init/load responses.
     *
     * @return Map of category -> Map of node type -> description
     */
    public Map<String, Object> getNodeTypesMap() {
        List<NodeTypeDocumentationEntity> allNodes = repository.findByEnabledTrueOrderByVariablePrefixAscLabelAsc();

        Map<String, Object> result = new LinkedHashMap<>();

        // Group nodes by variable_prefix
        Map<String, Map<String, String>> grouped = new LinkedHashMap<>();
        for (String prefix : PREFIX_TO_CATEGORY.keySet()) {
            grouped.put(PREFIX_TO_CATEGORY.get(prefix), new LinkedHashMap<>());
        }

        for (NodeTypeDocumentationEntity node : allNodes) {
            String prefix = node.getVariablePrefix();
            if (prefix != null && PREFIX_TO_CATEGORY.containsKey(prefix)) {
                String category = PREFIX_TO_CATEGORY.get(prefix);
                grouped.get(category).put(node.getType(), node.getDescription());
            }
        }

        // Only include non-empty categories
        for (Map.Entry<String, Map<String, String>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Find a node by its type identifier.
     *
     * @param type Node type (e.g., "webhook", "split", "agent")
     * @return Optional containing the node if found
     */
    public Optional<NodeTypeDocumentationEntity> findByType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return repository.findByType(type.toLowerCase().trim());
    }

    /**
     * Get all nodes in a specific variable prefix category.
     *
     * @param variablePrefix The prefix (e.g., "trigger", "agent", "core")
     * @return List of nodes in that category
     */
    public List<NodeTypeDocumentationEntity> getNodesByPrefix(String variablePrefix) {
        if (variablePrefix == null || variablePrefix.isBlank()) {
            return List.of();
        }
        return repository.findByEnabledTrueAndVariablePrefix(variablePrefix.toLowerCase().trim());
    }

    /**
     * Get multiple nodes at once (batch query).
     *
     * @param nodeTypes List of node type identifiers
     * @return Map with batch results
     */
    public Map<String, Object> getNodesBatch(List<String> nodeTypes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batch", true);
        result.put("requested", nodeTypes.size());

        Map<String, Object> nodes = new LinkedHashMap<>();
        List<String> notFound = new ArrayList<>();

        for (String type : nodeTypes) {
            Optional<NodeTypeDocumentationEntity> node = findByType(type);
            if (node.isPresent() && node.get().isEnabled()) {
                nodes.put(node.get().getType(), node.get().toMap());
            } else {
                notFound.add(type);
            }
        }

        result.put("found", nodes.size());
        result.put("nodes", nodes);

        if (!notFound.isEmpty()) {
            result.put("not_found", notFound);
        }

        return result;
    }

    /**
     * Search nodes by query string using full-text search.
     *
     * @param query Search query
     * @param limit Max results
     * @return List of matching nodes
     */
    public List<NodeTypeDocumentationEntity> searchNodes(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // Try full-text search first (only enabled nodes)
        List<NodeTypeDocumentationEntity> results = repository.searchByQueryEnabled(query, limit);

        // Fallback to pattern matching if no results (only enabled nodes)
        if (results.isEmpty()) {
            results = repository.searchByPatternEnabled("%" + query + "%", limit);
        }

        return results;
    }

    /**
     * Get all nodes from all categories.
     *
     * @return Map with all nodes organized by variable prefix category
     */
    public Map<String, Object> getAllNodes() {
        List<NodeTypeDocumentationEntity> allNodes = repository.findByEnabledTrueOrderByVariablePrefixAscLabelAsc();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Complete Node Library");
        result.put("total_nodes", allNodes.size());

        Map<String, Object> byCategory = new LinkedHashMap<>();

        // Group by variable prefix
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String category : PREFIX_TO_CATEGORY.values()) {
            grouped.put(category, new ArrayList<>());
        }

        for (NodeTypeDocumentationEntity node : allNodes) {
            String prefix = node.getVariablePrefix();
            if (prefix != null && PREFIX_TO_CATEGORY.containsKey(prefix)) {
                String category = PREFIX_TO_CATEGORY.get(prefix);
                grouped.get(category).add(node.toMap());
            }
        }

        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                Map<String, Object> categoryMap = new LinkedHashMap<>();
                categoryMap.put("label", capitalizeFirst(entry.getKey()));
                categoryMap.put("nodes", entry.getValue());
                byCategory.put(entry.getKey(), categoryMap);
            }
        }

        result.put("categories", byCategory);
        return result;
    }

    /**
     * Get variable syntax documentation.
     */
    public Map<String, String> getVariableSyntaxMap() {
        Map<String, String> syntax = new LinkedHashMap<>();
        syntax.put("unified_pattern", "{{type:label.output.field}} - Workflow params: ALL types use .output.");
        syntax.put("trigger", "{{trigger:label.output.field}} - Access trigger data");
        syntax.put("mcp", "{{mcp:label.output.field}} - Access step output");
        syntax.put("agent", "{{agent:label.output.response}} - Access agent response");
        syntax.put("core", "{{core:label.output.field}} - Access control flow output");
        syntax.put("table", "{{table:label.output.field}} - Access table operation output");
        syntax.put("vars", "{{$vars.name}} - Reusable workspace variable (user-defined config, no .output. segment, works in any param or condition). Alias: {{vars:name}}.");
        syntax.put("interface_templates", "{{variable|default}} - Interface templates use GENERIC names with pipe defaults. Mapping to workflow data via variable_mapping. Add action_mapping to make interactive apps (forms → triggers → workflow → display results).");
        return syntax;
    }

    /**
     * Get categories summary.
     */
    public Map<String, Object> getCategories() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Workflow Node Library");
        result.put("description", "Available node types organized by variable prefix");

        List<Map<String, Object>> categories = new ArrayList<>();

        for (Map.Entry<String, String> entry : PREFIX_TO_CATEGORY.entrySet()) {
            String prefix = entry.getKey();
            String categoryName = entry.getValue();

            List<NodeTypeDocumentationEntity> nodes = repository.findByEnabledTrueAndVariablePrefix(prefix);
            if (!nodes.isEmpty()) {
                Map<String, Object> cat = new LinkedHashMap<>();
                cat.put("id", categoryName);
                cat.put("prefix", prefix);
                cat.put("node_count", nodes.size());
                cat.put("nodes", nodes.stream().map(NodeTypeDocumentationEntity::getType).toList());
                categories.add(cat);
            }
        }

        result.put("categories", categories);
        return result;
    }

    /**
     * Get nodes by category for workflow_help.
     */
    public Map<String, Object> getNodesByCategory(String categoryId) {
        // Map category ID to variable prefix
        String prefix = null;
        for (Map.Entry<String, String> entry : PREFIX_TO_CATEGORY.entrySet()) {
            if (entry.getValue().equals(categoryId) || entry.getKey().equals(categoryId)) {
                prefix = entry.getKey();
                break;
            }
        }

        if (prefix == null) {
            return Map.of(
                "error", "Unknown category: " + categoryId,
                "available_categories", PREFIX_TO_CATEGORY.values()
            );
        }

        List<NodeTypeDocumentationEntity> nodes = repository.findByEnabledTrueAndVariablePrefix(prefix);

        // Return lightweight summary: type + description only.
        // Full details available via workflow(action='help', topics=['<node_type>'])
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("category", PREFIX_TO_CATEGORY.get(prefix));
        result.put("prefix", prefix);
        result.put("node_count", nodes.size());
        result.put("nodes", nodes.stream().map(n -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("type", n.getType());
            summary.put("description", n.getDescription());
            return summary;
        }).toList());
        result.put("detail", "For full parameters, outputs, and examples of a specific node: workflow(action='help', topics=['<node_type>'])");

        return result;
    }

    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Get always-available help text for the tool.
     * Comprehensive reference shown in tool help.
     */
    public String getAlwaysAvailableHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("WORKFLOW NODE LIBRARY\n\n");

        // Categories summary from DB
        sb.append("=== CATEGORIES ===\n");
        for (Map.Entry<String, String> entry : PREFIX_TO_CATEGORY.entrySet()) {
            List<NodeTypeDocumentationEntity> nodes = repository.findByEnabledTrueAndVariablePrefix(entry.getKey());
            if (!nodes.isEmpty()) {
                sb.append("- ").append(entry.getValue()).append(" (prefix: ").append(entry.getKey()).append(":): ");
                sb.append(nodes.stream().map(NodeTypeDocumentationEntity::getType).limit(5)
                    .reduce((a, b) -> a + ", " + b).orElse(""));
                if (nodes.size() > 5) sb.append("...");
                sb.append("\n");
            }
        }

        // Variable syntax
        sb.append("\n=== VARIABLE SYNTAX ===\n");
        sb.append("WORKFLOW PARAMS: {{type:label.output.field}}\n");
        sb.append("- trigger: {{trigger:label.output.field}}\n");
        sb.append("- mcp: {{mcp:label.output.field}}\n");
        sb.append("- agent: {{agent:label.output.response}}\n");
        sb.append("- core: {{core:label.output.iteration}}\n");
        sb.append("- table: {{table:label.output.field}}\n");
        sb.append("\nINTERFACE:\n");
        sb.append("- Templates use GENERIC names: {{title|default}} mapped to workflow data via variable_mapping\n");
        sb.append("- Registers non-blocking signal, __continue resumes DAG. Omit action_mapping for display-only.\n");
        sb.append("- Call interface(action='help') for full documentation before creating interfaces\n");

        // Workflow modes
        sb.append("\n=== WORKFLOW MODES ===\n");
        sb.append("- Creating: workflow(action='init', name='...', description='...')\n");
        sb.append("- Editing: workflow(action='load', id='...')\n");
        sb.append("- Running: workflow(action='execute')\n");

        sb.append("\nHELP: workflow(action='help', topics=['webhook', 'agent']) for detailed syntax");

        return sb.toString();
    }

    /**
     * Get quick reference text for tool description.
     */
    public String getQuickReference() {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            Build REUSABLE automation workflows (scheduled/triggered tasks).
            IMPORTANT: This tool is STATEFUL. Call it ONE AT A TIME - wait for each result before the next call. Never batch multiple workflow calls in a single response.

            USE FOR:
            - Scheduled tasks: "every day at 9am", "weekly report"
            - Triggered automation: "when new row", "on webhook"
            - Multi-step processes that need to be saved

            DON'T USE FOR:
            - One-time actions -> use catalog(action='execute')
            - Immediate queries -> use catalog(action='execute')

            FLOW: workflow(action='init') -> [wait for result] -> workflow(action='add_node', type='form|webhook|schedule|...') -> [wait for result] -> workflow(action='add_node', type='...') -> [wait for result] -> workflow(action='finish')

            NODE CATEGORIES:
            """);

        for (Map.Entry<String, String> entry : PREFIX_TO_CATEGORY.entrySet()) {
            List<NodeTypeDocumentationEntity> nodes = repository.findByEnabledTrueAndVariablePrefix(entry.getKey());
            if (!nodes.isEmpty()) {
                sb.append("- ").append(entry.getValue()).append(" (").append(nodes.size()).append("): ");
                sb.append(nodes.stream().map(NodeTypeDocumentationEntity::getType).limit(4)
                    .reduce((a, b) -> a + ", " + b).orElse(""));
                if (nodes.size() > 4) sb.append("...");
                sb.append("\n");
            }
        }

        sb.append("""

            VARIABLE SYNTAX: {{type:label.output.field}}
            HELP: workflow(action='help', topics=['triggers']) for details
            """);

        return sb.toString();
    }
}
