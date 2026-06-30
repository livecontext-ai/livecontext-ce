package com.apimarketplace.orchestrator.execution.v2.state;

/**
 * Immutable back-edge state.
 * Tracks iteration progress for a single back-edge (loop edge).
 *
 * A back-edge is any edge pointing backward in the graph that triggers
 * re-execution of the subgraph between target and source when a SpEL condition is true.
 */
public record BackEdgeState(
    String edgeId,        // "mcp:source->mcp:target"
    int iteration,
    int maxIterations,
    String condition,
    boolean terminated
) {

    /**
     * Create initial state. Starts at iteration=0 - the iteration of the body
     * that has just completed (the LoopNode initial body entry, which is iter 0).
     * The next body re-entry (driven by BackEdgeHandler) will run at iter 1, then 2,
     * etc., produced by {@link #incrementIteration()}.
     */
    public static BackEdgeState create(String edgeId, int maxIterations, String condition) {
        return new BackEdgeState(edgeId, 0, maxIterations, condition, false);
    }

    public BackEdgeState incrementIteration() {
        return new BackEdgeState(edgeId, iteration + 1, maxIterations, condition, terminated);
    }

    public BackEdgeState terminate() {
        return new BackEdgeState(edgeId, iteration, maxIterations, condition, true);
    }

    /**
     * True when another body iteration (iteration + 1) would still fit within
     * {@code maxIterations}. Read as "can the loop fire one more body run?".
     * The {@code iteration} field tracks the body iteration that just completed,
     * so the next body iter would be {@code iteration + 1} and must satisfy
     * {@code (iteration + 1) < maxIterations} to be admissible.
     */
    public boolean shouldContinue() {
        return !terminated && (iteration + 1) < maxIterations;
    }
}
