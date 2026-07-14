package com.apimarketplace.orchestrator.execution.v2.constants;

/**
 * Centralized constants for execution metadata keys.
 *
 * This class consolidates all magic strings used throughout the execution engine
 * to prevent duplication and typos.
 *
 * Categories:
 * - Node output keys (terminated, continue, reason, etc.)
 * - Split metadata keys (spawn_items, split_state, etc.)
 * - Loop metadata keys (max_iterations, iteration, etc.)
 * - Context data keys (current_item, current_index, etc.)
 * - Status messages
 */
public final class ExecutionMetadataKeys {

    private ExecutionMetadataKeys() {
        // Utility class - prevent instantiation
    }

    // ========================================================================
    // NODE OUTPUT KEYS - Keys used in NodeExecutionResult.output()
    // ========================================================================

    /**
     * Key indicating whether a loop or split has terminated.
     * Value: Boolean (true = terminated, false/absent = continuing)
     */
    public static final String TERMINATED = "terminated";

    /**
     * Marker placed by a node that returns {@link com.apimarketplace.orchestrator.domain.execution.NodeStatus#SKIPPED}
     * AND wants the engine to cascade SKIPPED to its successors - i.e. the SKIPPED
     * is a "no items to process" terminal, not a routing skip (decision/switch
     * branches naturally have their own per-port routing and must NOT use this).
     *
     * <p>The default {@link com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode#getNextNodes}
     * only filters successors on {@code isFailure()}; a SKIPPED result still
     * exposes its successors. This metadata key tells
     * {@link com.apimarketplace.orchestrator.execution.v2.engine.UnifiedExecutionEngine}
     * to invoke {@code V2SkipPropagationService.cascadeFailureToSuccessors}
     * (which propagates SKIPPED, despite its name) for these explicit cases.
     *
     * <p>Current callers:
     * <ul>
     *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler}
     *   when an aggregate's predecessor branch routed 0 items in the split
     *   (e.g. classify routed every email to a non-urgent category but the
     *   aggregate sits on the urgent branch).</li>
     * </ul>
     *
     * <p>Value: Boolean (true = cascade; absent/false = engine routes normally).
     */
    public static final String CASCADE_SKIP_TO_SUCCESSORS = "cascade_skip_to_successors";

    /**
     * Key indicating whether a split should continue processing items.
     * Value: Boolean (true = continue, false/absent = stop)
     */
    public static final String CONTINUE = "continue";

    /**
     * Key containing the reason for termination/exit.
     * Value: String (e.g., "condition_false", "all_items_processed", "max_iterations")
     */
    public static final String REASON = "reason";

    /**
     * Key containing the exit reason for loops/split.
     * Alias for REASON, used in some contexts.
     */
    public static final String EXIT_REASON = "exit_reason";

    /**
     * Key containing the maximum number of iterations for a loop.
     * Value: Integer
     */
    public static final String MAX_ITERATIONS = "max_iterations";

    /**
     * Key containing the selected branch index for decision nodes.
     * Value: Integer (0, 1, 2, etc.)
     */
    public static final String SELECTED_BRANCH_INDEX = "selected_branch_index";

    /**
     * Key containing the selected port for branching signal nodes (e.g., UserApproval).
     * Value: String (e.g., "approved", "rejected", "timeout")
     */
    public static final String SELECTED_PORT = "selected_port";

    /**
     * Key containing the node type in output.
     * Value: String (e.g., "decision", "loop", "split")
     */
    public static final String NODE_TYPE = "node_type";

    /**
     * Key containing the current iteration number for loops.
     * Value: Integer (0, 1, 2, etc.)
     */
    public static final String ITERATION = "iteration";

    /**
     * Key containing the total number of items in a split/merge.
     * Value: Integer
     */
    public static final String ITEM_COUNT = "item_count";

    /**
     * Key containing the current item being processed in a split.
     * Value: Object (the item data)
     */
    public static final String CURRENT_ITEM = "current_item";

    /**
     * Key containing the current index in a split iteration.
     * Value: Integer (0, 1, 2, etc.)
     */
    public static final String CURRENT_INDEX = "current_index";

    /**
     * Global context key tracking nested sub-workflow call depth.
     * Value: Integer.
     */
    public static final String SUB_WORKFLOW_DEPTH = "subWorkflowDepth";

    /**
     * Global context key tracking workflow IDs already present in the current
     * sub-workflow call chain. Used to fail fast on self/cyclic calls.
     * Value: List&lt;String&gt;.
     */
    public static final String SUB_WORKFLOW_ANCESTRY = "subWorkflowAncestry";

    /**
     * Key containing the number of items processed by split-aware execution.
     * Value: Integer
     */
    public static final String SPLIT_ITEM_COUNT = "split_item_count";

    /**
     * Key containing errors from split item execution.
     * Value: List<String>
     */
    public static final String SPLIT_ERRORS = "split_errors";

    /**
     * Flag indicating this was a split execution.
     * Value: Boolean
     */
    public static final String SPLIT_EXECUTION = "split_execution";

    /**
     * Key containing the item index within a split.
     * Value: Integer
     */
    public static final String ITEM_INDEX = "item_index";

    // ========================================================================
    // SPLIT METADATA KEYS - Keys used in Split execution
    // ========================================================================

    /**
     * Key containing the list of items to spawn for parallel split.
     * Value: List<Object>
     * Used in NodeExecutionResult.metadata()
     */
    public static final String SPAWN_ITEMS = "spawn_items";

    /**
     * Key containing the SplitState object.
     * Value: SplitState
     * Used in NodeExecutionResult.metadata()
     */
    public static final String SPLIT_STATE = "split_state";

    /**
     * Key containing the state key for split.
     * Value: String (e.g., "split_state:split_my_loop")
     * Used in NodeExecutionResult.metadata()
     */
    public static final String SPLIT_STATE_KEY = "split_state_key";

    /**
     * Prefix for split state keys in context global data.
     * Full key format: "split_state:{nodeId}"
     */
    public static final String SPLIT_STATE_PREFIX = "split_state:";

    /**
     * Flag indicating that a split node has already persisted results for each item.
     * When true, the engine should skip emitNodeComplete to avoid duplicate persistence.
     * Value: Boolean
     */
    public static final String SPLIT_ALREADY_PERSISTED = "split_already_persisted";

    /**
     * Flag indicating that split skip item rows were persisted before the node-level
     * SKIPPED completion. The completion path must emit the final aggregated
     * SKIPPED event after it increments the node-level count, otherwise the UI sees
     * the pre-final batch count.
     * Value: Boolean
     */
    public static final String DEFER_SKIPPED_AGGREGATE_EVENT = "defer_skipped_aggregate_event";

    /**
     * Flag indicating that a split node has already launched parallel traversals for successors.
     * When true, the engine should skip successor traversal to avoid duplicate execution.
     * Value: Boolean
     */
    public static final String SPLIT_SUCCESSORS_HANDLED = "split_successors_handled";

    /**
     * Output key set on a partial-failure split summary (≥1 success and ≥1 failure).
     * The summary's global status remains COMPLETED; this flag tells consumers
     * (inspector, restart-replay readiness walker, error-trigger gating) that some
     * items inside the split failed.
     * Value: Boolean (true)
     */
    public static final String SPLIT_PARTIAL_FAILURE = "split_partial_failure";

    /**
     * Output key listing the indices of items that failed inside a split.
     * Value: List&lt;Integer&gt;
     */
    public static final String SPLIT_FAILED_ITEM_INDICES = "split_failed_item_indices";

    /**
     * Output key listing the indices of items that completed successfully inside a split.
     * Value: List&lt;Integer&gt;
     */
    public static final String SPLIT_COMPLETED_ITEM_INDICES = "split_completed_item_indices";


    // ========================================================================
    // NODE POLICY KEYS - Keys stamped by NodePolicyRunner (retry / continuation)
    // ========================================================================

    /**
     * 1-based attempt number of the execution that produced this result.
     * Stamped in BOTH output (queryable, survives schema mappers - same dual-write
     * pattern as {@link #SPLIT_PARTIAL_FAILURE}) and metadata, but ONLY when the
     * node has a non-default policy with retries (default-policy executions stay
     * byte-identical to pre-policy behavior).
     * Value: Integer
     */
    public static final String POLICY_ATTEMPT = "policy_attempt";

    /**
     * Total attempt budget for the node (retryCount + 1). Paired with
     * {@link #POLICY_ATTEMPT} so the frontend can render "attempt 2/3".
     * Value: Integer
     */
    public static final String POLICY_MAX_ATTEMPTS = "policy_max_attempts";

    /**
     * Flag set on a node's FINAL FAILED result when its policy declares
     * {@code continueOnFailure=true}. Tells the engine to (a) NOT cascade SKIPPED
     * to successors and (b) continue traversal through them - reusing the
     * SKIPPED-with-error continuation semantic (BaseNode.getNextNodes exposes
     * successors for non-failed results; ExecutionContext.isCompleted already
     * treats FAILED as resolved for readiness). The node itself remains FAILED
     * everywhere (WS, DB, snapshot) - run status derives from existing semantics.
     * Value: Boolean
     */
    public static final String POLICY_CONTINUE_ON_FAILURE = "policy_continue_on_failure";

    /**
     * Flag set on a FAILED attempt result produced by the per-attempt execution
     * timeout ({@code nodePolicy.timeoutMs}, enforced by
     * {@code NodePolicyRunner.callWithTimeout}). Stamped in BOTH output and
     * metadata (same dual-write pattern as {@link #POLICY_ATTEMPT}).
     *
     * <p>Best-effort semantics: the flag means the engine STOPPED WAITING after
     * timeoutMs - the node body was interrupted but may still be running and its
     * side effects are NOT cancelled. A timed-out attempt is an ordinary failed
     * attempt for retry/continueOnFailure/billing purposes.
     * Value: Boolean
     */
    public static final String POLICY_TIMEOUT = "policy_timeout";

    // ========================================================================
    // MOCK KEYS - Keys stamped by MockNodeResultFactory (per-node mock mode)
    // ========================================================================

    /**
     * Flag set on every result produced by the per-node mock mode instead of a
     * real execution. Dual-written into output AND metadata (the
     * {@link #POLICY_ATTEMPT} pattern: output is what persists for COMPLETED
     * results, metadata is the in-flight engine/WS signal), and re-injected after
     * schema transformation by {@code StepPayloadService} so the badge survives
     * {@code transformToDbSchema}. Surfaces "this output was configured, not
     * produced" to the inspector and the agent's {@code get_node_output}.
     * Value: Boolean (true)
     */
    public static final String MOCKED = "__mocked__";

    /**
     * The mock's source ({@code static} / {@code catalog_example} / {@code error}),
     * paired with {@link #MOCKED}. Same dual-write + persistence rules.
     * Value: String
     */
    public static final String MOCK_SOURCE = "__mock_source__";

    /**
     * Check if a result output or metadata map carries the mock marker.
     *
     * @param map output or metadata map (may be null)
     * @return true when the result was produced by the mock mode
     */
    public static boolean isMocked(java.util.Map<String, Object> map) {
        return map != null && Boolean.TRUE.equals(map.get(MOCKED));
    }

    // ========================================================================
    // CONTEXT DATA KEYS - Keys used in ExecutionContext global data
    // ========================================================================

    /**
     * Suffix for current item in split context.
     * Full key format: "{splitNodeId}.current_item"
     */
    public static final String CURRENT_ITEM_SUFFIX = ".current_item";

    /**
     * Suffix for current index in split context.
     * Full key format: "{splitNodeId}.current_index"
     */
    public static final String CURRENT_INDEX_SUFFIX = ".current_index";

    /**
     * Key for the current split ID being processed.
     * Value: String (split node ID)
     */
    public static final String CURRENT_SPLIT_ID = "current_split_id";

    /**
     * Legacy key for split item (used in some paths).
     * Value: Object (current item data)
     */
    public static final String SPLIT_ITEM = "split_item";

    /**
     * Legacy key for split index (used in some paths).
     * Value: Integer
     */
    public static final String SPLIT_INDEX = "split_index";

    /**
     * Key for parent item ID in nested split.
     * Value: String
     */
    public static final String PARENT_ITEM_ID = "parent_item_id";

    /**
     * Key for parent item index in nested split.
     * Value: Integer
     */
    public static final String PARENT_ITEM_INDEX = "parent_item_index";

    /**
     * Prefix for item data keys in context (parallel split).
     * Full key format: "item_data:{itemId}"
     */
    public static final String ITEM_DATA_PREFIX = "item_data:";

    /**
     * Prefix for item index keys in context (parallel split).
     * Full key format: "item_index:{itemId}"
     */
    public static final String ITEM_INDEX_PREFIX = "item_index:";

    /**
     * Prefix for split ID keys in context (parallel split).
     * Full key format: "split_id:{itemId}"
     */
    public static final String SPLIT_ID_PREFIX = "split_id:";

    /**
     * Prefix for body completion tracking in loops.
     * Full key format: "body_completed:{nodeId}:iter:{iteration}"
     */
    public static final String BODY_COMPLETED_PREFIX = "body_completed:";

    /**
     * Separator for iteration-scoped keys.
     * Example: "body_completed:step_x:iter:2"
     */
    public static final String ITER_SEPARATOR = ":iter:";

    // ========================================================================
    // STATUS MESSAGES - Standardized messages for node execution
    // ========================================================================

    /**
     * Message for skipped nodes (prerequisites not met).
     */
    public static final String SKIPPED_MESSAGE = "Prerequisites not met or condition false";

    /**
     * Reason for loop termination when condition becomes false.
     */
    public static final String LOOP_EXIT_CONDITION_FALSE = "condition_false";

    /**
     * Reason for split termination when all items are processed.
     */
    public static final String SPLIT_EXIT_ALL_ITEMS_PROCESSED = "all_items_processed";

    /**
     * Reason for loop termination when max iterations reached.
     */
    public static final String LOOP_EXIT_MAX_ITERATIONS = "max_iterations_reached";

    // ========================================================================
    // HELPER METHODS - Utility methods for key construction
    // ========================================================================

    /**
     * Build split state key for a given node ID.
     *
     * @param nodeId The split node ID
     * @return Key in format "split_state:{nodeId}"
     */
    public static String splitStateKey(String nodeId) {
        return SPLIT_STATE_PREFIX + nodeId;
    }

    /**
     * Build current item key for a split node.
     *
     * @param splitNodeId The split node ID
     * @return Key in format "{splitNodeId}.current_item"
     */
    public static String currentItemKey(String splitNodeId) {
        return splitNodeId + CURRENT_ITEM_SUFFIX;
    }

    /**
     * Build current index key for a split node.
     *
     * @param splitNodeId The split node ID
     * @return Key in format "{splitNodeId}.current_index"
     */
    public static String currentIndexKey(String splitNodeId) {
        return splitNodeId + CURRENT_INDEX_SUFFIX;
    }

    /**
     * Build item data key for a child item (parallel split).
     *
     * @param itemId The child item ID
     * @return Key in format "item_data:{itemId}"
     */
    public static String itemDataKey(String itemId) {
        return ITEM_DATA_PREFIX + itemId;
    }

    /**
     * Build item index key for a child item (parallel split).
     *
     * @param itemId The child item ID
     * @return Key in format "item_index:{itemId}"
     */
    public static String itemIndexKey(String itemId) {
        return ITEM_INDEX_PREFIX + itemId;
    }

    /**
     * Build split ID key for a child item (parallel split).
     *
     * @param itemId The child item ID
     * @return Key in format "split_id:{itemId}"
     */
    public static String splitIdKey(String itemId) {
        return SPLIT_ID_PREFIX + itemId;
    }

    /**
     * Build body completion key for a loop iteration.
     *
     * @param nodeId The body node ID
     * @param iteration The iteration number
     * @return Key in format "body_completed:{nodeId}:iter:{iteration}"
     */
    public static String bodyCompletedKey(String nodeId, int iteration) {
        return BODY_COMPLETED_PREFIX + nodeId + ITER_SEPARATOR + iteration;
    }

    // ========================================================================
    // METADATA FLAG CHECKERS - Helper methods to check result metadata flags
    // ========================================================================

    /**
     * Check if a result indicates that split has already persisted items.
     * Used to avoid duplicate persistence in the execution engine.
     *
     * @param metadata The result metadata map (may be null)
     * @return true if split has already persisted, false otherwise
     */
    public static boolean isSplitAlreadyPersisted(java.util.Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(SPLIT_ALREADY_PERSISTED));
    }

    /**
     * Check if a result indicates that split has already handled successors.
     * Used to avoid duplicate successor traversal in the execution engine.
     *
     * @param metadata The result metadata map (may be null)
     * @return true if split has already handled successors, false otherwise
     */
    public static boolean isSplitSuccessorsHandled(java.util.Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(SPLIT_SUCCESSORS_HANDLED));
    }

    /**
     * Check if a FINAL failed result carries the continue-on-failure continuation
     * flag (see {@link #POLICY_CONTINUE_ON_FAILURE}). The engine then suppresses
     * the SKIPPED cascade and traverses the node's successors.
     *
     * @param metadata The result metadata map (may be null)
     * @return true if traversal must continue past this failure
     */
    public static boolean isContinueOnFailure(java.util.Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(POLICY_CONTINUE_ON_FAILURE));
    }

    /**
     * Check if a FAILED result was produced by the per-attempt execution timeout
     * (see {@link #POLICY_TIMEOUT}). Best-effort marker - the node body may still
     * be running; side effects are not cancelled.
     *
     * @param metadata The result metadata map (may be null)
     * @return true if this failure is a policy timeout
     */
    public static boolean isPolicyTimeout(java.util.Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(POLICY_TIMEOUT));
    }

    // ========================================================================
    // OUTPUT VALUE HELPERS - Methods to extract values from node output
    // ========================================================================

    /**
     * Check if output indicates termination.
     *
     * @param output The node output map (may be null)
     * @return true if terminated, false otherwise
     */
    public static boolean isTerminated(java.util.Map<String, Object> output) {
        return output != null && Boolean.TRUE.equals(output.get(TERMINATED));
    }

    /**
     * Check if output indicates continuation.
     *
     * @param output The node output map (may be null)
     * @return true if should continue, false otherwise
     */
    public static boolean shouldContinue(java.util.Map<String, Object> output) {
        return output != null && Boolean.TRUE.equals(output.get(CONTINUE));
    }

    /**
     * Get the reason from output.
     *
     * @param output The node output map (may be null)
     * @return the reason string, or null if not present
     */
    public static String getReason(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object reason = output.get(REASON);
        return reason != null ? String.valueOf(reason) : null;
    }

    /**
     * Get the iteration number from output.
     *
     * @param output The node output map (may be null)
     * @return the iteration number, or null if not present
     */
    public static Integer getIteration(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object iter = output.get(ITERATION);
        if (iter instanceof Number) {
            return ((Number) iter).intValue();
        }
        return null;
    }

    /**
     * Get the max iterations from output.
     *
     * @param output The node output map (may be null)
     * @return the max iterations, or null if not present
     */
    public static Integer getMaxIterations(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object maxIter = output.get(MAX_ITERATIONS);
        if (maxIter instanceof Number) {
            return ((Number) maxIter).intValue();
        }
        return null;
    }

    /**
     * Get the selected branch index from output.
     *
     * @param output The node output map (may be null)
     * @return the selected branch index, or null if not present
     */
    public static Integer getSelectedBranchIndex(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object index = output.get(SELECTED_BRANCH_INDEX);
        if (index instanceof Number) {
            return ((Number) index).intValue();
        }
        return null;
    }

    /**
     * Get the node type from output.
     *
     * @param output The node output map (may be null)
     * @return the node type string, or null if not present
     */
    public static String getNodeType(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object nodeType = output.get(NODE_TYPE);
        return nodeType != null ? String.valueOf(nodeType) : null;
    }

    /**
     * Get the item count from output.
     *
     * @param output The node output map (may be null)
     * @return the item count, or null if not present
     */
    public static Integer getItemCount(java.util.Map<String, Object> output) {
        if (output == null) return null;
        Object count = output.get(ITEM_COUNT);
        if (count instanceof Number) {
            return ((Number) count).intValue();
        }
        return null;
    }
}
