package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.repository.NodeTypeDocumentationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for searching workflow node type documentation.
 * Provides full-text search with caching for optimal performance.
 *
 * Agent-facing methods only return enabled node types.
 * Admin methods return all node types (including disabled).
 */
@Service
public class NodeTypeSearchService {

    private static final Logger logger = LoggerFactory.getLogger(NodeTypeSearchService.class);

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final NodeTypeDocumentationRepository repository;

    public NodeTypeSearchService(NodeTypeDocumentationRepository repository) {
        this.repository = repository;
    }

    /**
     * Search node types by query string (agent-facing, enabled only).
     */
    public List<Map<String, Object>> search(String query, Integer limit) {
        if (query == null || query.isBlank()) {
            logger.debug("[NodeTypeSearch] Empty query, returning all enabled types");
            return getAllTypes();
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        String normalizedQuery = normalizeQuery(query);

        logger.info("[NodeTypeSearch] Searching for: '{}' (normalized: '{}', limit: {})",
                query, normalizedQuery, effectiveLimit);

        // Try full-text search first (enabled only)
        List<NodeTypeDocumentationEntity> results = repository.searchByQueryEnabled(normalizedQuery, effectiveLimit);

        // Fallback to pattern matching if no results (enabled only)
        if (results.isEmpty()) {
            logger.debug("[NodeTypeSearch] Full-text search returned 0 results, trying pattern match");
            String pattern = "%" + normalizedQuery.replace(" ", "%") + "%";
            results = repository.searchByPatternEnabled(pattern, effectiveLimit);
        }

        logger.info("[NodeTypeSearch] Found {} results for query '{}'", results.size(), query);

        return results.stream()
                .map(NodeTypeDocumentationEntity::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Search node types by query and category (agent-facing, enabled only).
     */
    public List<Map<String, Object>> searchByCategory(String query, String category, Integer limit) {
        if (category == null || category.isBlank()) {
            return search(query, limit);
        }

        int effectiveLimit = (limit != null && limit > 0) ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        String normalizedQuery = query != null ? normalizeQuery(query) : "";

        logger.info("[NodeTypeSearch] Searching category '{}' for: '{}'", category, normalizedQuery);

        List<NodeTypeDocumentationEntity> results;

        if (normalizedQuery.isBlank()) {
            // Filter by category + enabled
            results = repository.findByCategory(category).stream()
                    .filter(NodeTypeDocumentationEntity::isEnabled)
                    .collect(Collectors.toList());
        } else {
            results = repository.searchByQueryAndCategory(normalizedQuery, category, effectiveLimit).stream()
                    .filter(NodeTypeDocumentationEntity::isEnabled)
                    .collect(Collectors.toList());
        }

        return results.stream()
                .map(NodeTypeDocumentationEntity::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific node type by its type identifier.
     */
    public Optional<Map<String, Object>> getByType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }

        logger.debug("[NodeTypeSearch] Getting type: {}", type);

        return repository.findByType(type)
                .map(NodeTypeDocumentationEntity::toMap);
    }

    /**
     * Get all node types grouped by category (agent-facing, enabled only).
     */
    public Map<String, List<Map<String, Object>>> getAllGroupedByCategory() {
        logger.debug("[NodeTypeSearch] Getting all enabled types grouped by category");

        return repository.findByEnabledTrueOrderByCategoryAscLabelAsc().stream()
                .map(NodeTypeDocumentationEntity::toMap)
                .collect(Collectors.groupingBy(m -> (String) m.get("category")));
    }

    /**
     * Get all enabled node types (agent-facing).
     */
    public List<Map<String, Object>> getAllTypes() {
        logger.debug("[NodeTypeSearch] Getting all enabled types");

        return repository.findByEnabledTrueOrderByCategoryAscLabelAsc().stream()
                .map(NodeTypeDocumentationEntity::toMap)
                .collect(Collectors.toList());
    }

    /**
     * Get all node types including disabled (for admin settings page).
     */
    public List<NodeTypeDocumentationEntity> getAllTypesIncludingDisabled() {
        return repository.findAllByOrderByCategoryAscLabelAsc();
    }

    /**
     * Check if a node type is enabled.
     * Returns true if the type is not found in the documentation (unknown types are allowed).
     */
    public boolean isNodeTypeEnabled(String type) {
        return repository.findByType(type)
                .map(NodeTypeDocumentationEntity::isEnabled)
                .orElse(true);
    }

    /**
     * Toggle the enabled status of a node type.
     * Evicts all node type caches to reflect the change immediately.
     */
    public NodeTypeDocumentationEntity toggleEnabled(String type, boolean enabled) {
        NodeTypeDocumentationEntity entity = repository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("Node type not found: " + type));
        entity.setEnabled(enabled);
        return repository.save(entity);
    }

    /**
     * Get available categories.
     */
    public List<Map<String, String>> getCategories() {
        return List.of(
                Map.of("id", "trigger", "label", "Triggers", "description", "Entry points that start the workflow"),
                Map.of("id", "action", "label", "Actions", "description", "API calls and database operations"),
                Map.of("id", "control_flow", "label", "Control Flow", "description", "Loops, conditions, and branching"),
                Map.of("id", "ai", "label", "AI", "description", "AI-powered agents and processing")
        );
    }

    /**
     * Normalize query for better search results.
     */
    private String normalizeQuery(String query) {
        if (query == null) return "";

        String normalized = query.trim().toLowerCase();

        // Remove extra whitespace
        normalized = normalized.replaceAll("\\s+", " ");

        return normalized;
    }
}
