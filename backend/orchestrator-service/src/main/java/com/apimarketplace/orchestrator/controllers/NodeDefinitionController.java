package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.NodeDefinitionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST endpoint for querying registered NodeDefinitions.
 * Returns output schema in a format compatible with the frontend OutputSchema interface.
 */
@RestController
@RequestMapping("/api/node-definitions")
public class NodeDefinitionController {

    private final NodeDefinitionRegistry registry;

    public NodeDefinitionController(NodeDefinitionRegistry registry) {
        this.registry = registry;
    }

    /**
     * GET /api/node-definitions - returns all registered definitions.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<Map<String, Object>> result = registry.getAll().stream()
            .map(this::toResponseMap)
            .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/node-definitions/{nodeType} - returns a single definition.
     */
    @GetMapping("/{nodeType}")
    public ResponseEntity<Map<String, Object>> getByType(@PathVariable String nodeType) {
        Optional<NodeDefinition> def = registry.get(nodeType);
        return def.map(d -> ResponseEntity.ok(toResponseMap(d)))
            .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> toResponseMap(NodeDefinition def) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeType", def.nodeType());
        map.put("label", def.label());
        map.put("category", def.category());
        map.put("variablePrefix", def.variablePrefix());
        map.put("description", def.description());
        map.put("terminal", def.terminal());
        map.put("branching", def.branching());
        map.put("keywords", def.keywords());
        map.put("outputs", def.outputs().stream()
            .map(this::toFieldMap)
            .collect(Collectors.toList()));
        // Metadata exposes iconSlug, apiName, executionMode for MCP nodes registered
        // dynamically by CatalogMcpRegistrar - needed by the frontend for icon rendering.
        if (def.metadata() != null && !def.metadata().isEmpty()) {
            map.put("metadata", def.metadata());
        }
        return map;
    }

    private Map<String, Object> toFieldMap(OutputFieldDef field) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", field.key());
        map.put("type", field.type());
        map.put("description", field.description());
        if (Boolean.TRUE.equals(field.runtimeOnly())) {
            map.put("runtimeOnly", true);
        }
        if (!field.children().isEmpty()) {
            map.put("children", field.children().stream()
                .map(this::toFieldMap)
                .collect(Collectors.toList()));
        }
        return map;
    }
}
