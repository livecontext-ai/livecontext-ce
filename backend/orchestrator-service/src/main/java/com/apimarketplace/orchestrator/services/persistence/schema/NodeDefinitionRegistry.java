package com.apimarketplace.orchestrator.services.persistence.schema;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Registry for NodeDefinition instances, auto-discovered via NodeSpec beans.
 *
 * Provides lookup by node type (case-insensitive) and by category prefix.
 * Powers the GenericOutputSchemaMapper for output schema transformation.
 */
@Service
public class NodeDefinitionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(NodeDefinitionRegistry.class);

    // Concurrent maps so dynamic registrations (e.g., catalog MCP tools loaded at
    // startup by CatalogMcpRegistrar) can be added safely after the initial scan.
    private final Map<String, NodeDefinition> definitionsByType = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, NodeSpec> specsByType = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<NodeSpec> specs;

    public NodeDefinitionRegistry(List<NodeSpec> specs) {
        this.specs = specs;
    }

    @PostConstruct
    public void init() {
        for (NodeSpec spec : specs) {
            NodeDefinition def = spec.definition();
            String key = def.nodeType().toUpperCase();
            definitionsByType.put(key, def);
            specsByType.put(key, spec);
            logger.info("Registered node definition: {} (category={})", key, def.category());
        }
        logger.info("Node definition registry initialized with {} definitions", definitionsByType.size());
    }

    /**
     * Get definition by node type (case-insensitive).
     */
    public Optional<NodeDefinition> get(String nodeType) {
        if (nodeType == null) return Optional.empty();
        return Optional.ofNullable(definitionsByType.get(nodeType.toUpperCase()));
    }

    /**
     * Check if a definition exists for the given node type.
     */
    public boolean has(String nodeType) {
        return nodeType != null && definitionsByType.containsKey(nodeType.toUpperCase());
    }

    /**
     * Get all registered definitions.
     */
    public List<NodeDefinition> getAll() {
        return List.copyOf(definitionsByType.values());
    }

    /**
     * Get all definitions matching a category prefix (e.g., "core", "agent").
     */
    public List<NodeDefinition> getByCategory(String category) {
        if (category == null) return List.of();
        return definitionsByType.values().stream()
            .filter(def -> category.equalsIgnoreCase(def.category()))
            .collect(Collectors.toList());
    }

    /**
     * Get NodeSpec by node type (case-insensitive).
     */
    public Optional<NodeSpec> getSpec(String nodeType) {
        if (nodeType == null) return Optional.empty();
        return Optional.ofNullable(specsByType.get(nodeType.toUpperCase()));
    }

    /**
     * Register a node definition dynamically at runtime.
     *
     * Used by the typed-execution refactor to plug catalog API tools (MCP nodes) into the
     * SAME registry as core/agent/trigger nodes. Once registered, MCPs are returned by
     * GET /api/node-definitions and the frontend variable picker shows their typed outputs.
     *
     * Replaces an existing entry if the nodeType is already registered (idempotent reload).
     */
    public synchronized void registerDynamic(NodeDefinition def) {
        if (def == null || def.nodeType() == null) {
            throw new IllegalArgumentException("NodeDefinition and nodeType must be non-null");
        }
        String key = def.nodeType().toUpperCase();
        definitionsByType.put(key, def);
        // Dynamic registrations have no NodeSpec backing - they are pure metadata for now.
        // GenericOutputSchemaMapper falls back to the per-OutputFieldDef path when no spec exists.
        specsByType.remove(key);
        logger.info("Dynamically registered node definition: {} (category={})", key, def.category());
    }
}
