package com.apimarketplace.orchestrator.services.state.patch;

/**
 * Centralized JSON path constants and constructors for {@link JsonbPatch}.
 *
 * <p>Keeping path strings in one file makes the {@code JsonbPatchSchemaContractTest}
 * cross-check trivial: the test reads the declared {@link com.apimarketplace.orchestrator.domain.execution.EpochState}
 * fields and asserts every set field has a path constructor here (and vice
 * versa - no orphan path constructor referencing a non-existent field).
 *
 * <p>The path elements are:
 * <ul>
 *   <li>{@code seq} - top-level JSONB key, monotonic version</li>
 *   <li>{@code dags.{triggerId}.epochs.{epoch}.{setName}} - per-epoch sets</li>
 *   <li>{@code nodes.{nodeId}} - full NodeCounts replacement</li>
 * </ul>
 */
public final class PatchPaths {

    private PatchPaths() {}

    // Top-level keys
    public static final String SEQ = "seq";

    // EpochState set field names - must match @JsonProperty in EpochState.java
    public static final String COMPLETED_NODE_IDS = "completedNodeIds";
    public static final String FAILED_NODE_IDS = "failedNodeIds";
    public static final String PARTIAL_FAILED_NODE_IDS = "partialFailedNodeIds";
    public static final String SKIPPED_NODE_IDS = "skippedNodeIds";
    public static final String RUNNING_NODE_IDS = "runningNodeIds";
    public static final String READY_NODE_IDS = "readyNodeIds";
    public static final String AWAITING_SIGNAL_NODE_IDS = "awaitingSignalNodeIds";

    /** Path to a per-epoch set (e.g. {@code dags.t.epochs.5.completedNodeIds}). */
    public static String[] epochSet(String triggerId, int epoch, String setName) {
        return new String[] { "dags", triggerId, "epochs", Integer.toString(epoch), setName };
    }

    /** Path to a full {@code NodeCounts} replacement. */
    public static String[] nodeCounts(String nodeId) {
        return new String[] { "nodes", nodeId };
    }

    /**
     * Plan v4 §2b - path to a single {@code NodeCounts} field for DELTA
     * patches (e.g. {@code nodes.X.completed} for the integer counter).
     * Field names match the {@code @JsonProperty} on the
     * {@code NodeCounts} record (currently the field name itself, since
     * the record uses default JSON serialization).
     */
    public static String[] nodeCountsField(String nodeId, String field) {
        return new String[] { "nodes", nodeId, field };
    }

    /** Path to top-level seq. */
    public static String[] seq() {
        return new String[] { SEQ };
    }
}
