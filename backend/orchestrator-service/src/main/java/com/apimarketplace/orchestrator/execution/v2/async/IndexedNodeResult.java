package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;

/**
 * Pairs a {@code NodeExecutionResult} with the absolute {@code itemIndex} the result
 * was produced for.
 *
 * <p>Split items can arrive at the async barrier with non-contiguous indices when
 * upstream nodes (e.g. {@code is_new} decision that skips already-processed items)
 * filter out some items. For a 15-item split where items 3..6 and 8..14 were
 * short-circuited, only items {0, 1, 2, 7} reach classify and arrive at the barrier.
 *
 * <p>Without this pairing, callers would iterate the sealed batch by position
 * (0, 1, 2, 3) and attribute results to the wrong items, causing:
 * <ul>
 *   <li>edge counts off (emitted for positional itemIndex, not the real one);</li>
 *   <li>SplitContextManager storing outputs at the wrong slot;</li>
 *   <li>one per-barrier item silently dropped (the warning
 *       "Missing item at index N in sealed barrier").</li>
 * </ul>
 *
 * <p>The coalesce tracker returns {@code List<IndexedNodeResult>} ordered by
 * ascending {@code itemIndex}; downstream emitters iterate this list and use
 * {@code itemIndex()} as the authoritative per-item index.
 */
public record IndexedNodeResult(int itemIndex, NodeExecutionResult result) {
}
