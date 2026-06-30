package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Map;

/**
 * V2 Edge - Pure graph model with ports.
 *
 * Each edge has simple from/to fields with optional ports:
 * - from: "nodeType:label:port" (e.g., "core:check:if", "core:process:body")
 * - to: "nodeType:label:port" (e.g., "mcp:fetch", "core:process:iterate")
 *
 * Ports for outputs:
 * - decision: :if, :else, :elseif_0, :elseif_1, ...
 * - switch: :case_0, :case_1, ..., :default
 * - loop: :body, :exit
 *
 * Ports for inputs:
 * - loop: :iterate (for loop-back connections)
 *
 * Loop-back connections use the :iterate port on the target:
 * { "from": "mcp:last_body_step", "to": "core:my_loop:iterate" }
 * Loop condition and maxIterations are stored in the Core (type="loop"), not in edges.
 *
 * Conditions are stored in Cores, not in edges.
 * Edges only reference control nodes via ports in from/to strings.
 */
public record Edge(
    String from,
    String to,
    Map<String, Object> params
) {

    public Edge(String from, String to) {
        this(from, to, null);
    }

    /**
     * Get a unique identifier for this edge.
     */
    public String getEdgeId() {
        return from + "->" + to;
    }

    /**
     * Check if this edge has params data.
     */
    public boolean hasParams() {
        return params != null && !params.isEmpty();
    }
}
