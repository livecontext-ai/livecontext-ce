package com.apimarketplace.orchestrator.execution.v2.engine;

/**
 * Immutable coordinates identifying a specific execution point within a DAG.
 *
 * <p>Every node execution happens at a specific (triggerId, epoch, spawn) triple:
 * <ul>
 *   <li>{@code triggerId} - which DAG (trigger) this execution belongs to</li>
 *   <li>{@code epoch} - which fire of that trigger (monotonic per-DAG)</li>
 *   <li>{@code spawn} - which re-execution within the same epoch (rerun counter)</li>
 * </ul>
 */
public record DagCoordinates(String triggerId, int epoch, int spawn) {

    /**
     * Creates coordinates from an ExecutionContext's explicit fields.
     */
    public static DagCoordinates fromContext(ExecutionContext ctx) {
        return new DagCoordinates(ctx.triggerId(), ctx.epoch(), ctx.spawn());
    }

    /**
     * Factory method.
     */
    public static DagCoordinates of(String triggerId, int epoch, int spawn) {
        return new DagCoordinates(triggerId, epoch, spawn);
    }

    /**
     * Returns coordinates for the next epoch (spawn resets to 0).
     */
    public DagCoordinates nextEpoch() {
        return new DagCoordinates(triggerId, epoch + 1, 0);
    }

    /**
     * Returns coordinates for the next spawn (same epoch).
     */
    public DagCoordinates nextSpawn() {
        return new DagCoordinates(triggerId, epoch, spawn + 1);
    }

    /**
     * Returns coordinates with a different epoch.
     */
    public DagCoordinates withEpoch(int newEpoch) {
        return new DagCoordinates(triggerId, newEpoch, spawn);
    }

    /**
     * Returns coordinates with a different spawn.
     */
    public DagCoordinates withSpawn(int newSpawn) {
        return new DagCoordinates(triggerId, epoch, newSpawn);
    }

    @Override
    public String toString() {
        return triggerId + "@epoch=" + epoch + ",spawn=" + spawn;
    }
}
