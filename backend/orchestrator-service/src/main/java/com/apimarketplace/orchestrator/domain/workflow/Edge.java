package com.apimarketplace.orchestrator.domain.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

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
 * <h2>Two ways to declare a loop-back</h2>
 *
 * <b>1. Loop hub (core:loop).</b> The edge targets the loop's :iterate port; condition and
 * maxIterations live on the Core:
 * <pre>{ "from": "mcp:last_body_step", "to": "core:my_loop:iterate" }</pre>
 *
 * <b>2. Declared back-edge (no hub).</b> Any edge may carry a {@link BackEdge} marker, which
 * makes it a loop-back to an ancestor node without requiring a loop Core:
 * <pre>{ "from": "core:check:else", "to": "mcp:fetch", "backEdge": { "maxIterations": 10 } }</pre>
 * The edge's TARGET is the re-entry point. Condition and maxIterations live on the edge itself.
 *
 * Both forms are recognised by {@code WorkflowPlan.isBackEdge(Edge)} and resolved into the same
 * {@code BackEdgeSpec}, so the execution engine keeps a single loop path.
 *
 * <p><b>The marker is a first-class component, deliberately NOT a {@code params} key:</b>
 * {@code WorkflowPlan.getStepParams} exposes {@code edge.params} as the TARGET STEP's params, so
 * a marker stored there would be injected into the target node's configuration.
 *
 * Conditions are stored in Cores, not in edges.
 * Edges only reference control nodes via ports in from/to strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Edge(
    String from,
    String to,
    Map<String, Object> params,
    @JsonInclude(JsonInclude.Include.NON_NULL) BackEdge backEdge
) {

    /**
     * Loop-back metadata carried by a declared back-edge.
     *
     * @param condition     optional expression re-evaluated before each re-entry. Blank means
     *                      "iterate whenever this edge is traversed" - for a ported source
     *                      (e.g. a Decision's else) the branch selection IS the condition.
     * @param maxIterations optional per-edge cap. When null, the global
     *                      {@code workflow.execution.default-max-iterations} applies.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BackEdge(
        String condition,
        Integer maxIterations,
        // maxIterations written as a {{...}} template: an Integer cannot hold it, and the parser
        // used to drop it for the run's default cap. Resolved when the loop decides. Never serialized.
        @com.fasterxml.jackson.annotation.JsonIgnore
        String maxIterationsTemplate
    ) {
        public BackEdge(String condition, Integer maxIterations) {
            this(condition, maxIterations, null);
        }
    }

    public Edge(String from, String to) {
        this(from, to, null, null);
    }

    public Edge(String from, String to, Map<String, Object> params) {
        this(from, to, params, null);
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
