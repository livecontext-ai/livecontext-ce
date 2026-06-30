/**
 * Simplified Split execution infrastructure.
 *
 * <p>This package contains the simplified split implementation that follows
 * the principle: "split is a PRODUCER of data, nothing else."
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitContext} -
 *       Immutable context storing spawned items and results per node</li>
 *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager} -
 *       Service managing SplitContext instances per run</li>
 *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor} -
 *       Executes split nodes: evaluate expression, create context, COMPLETED</li>
 *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor} -
 *       Wraps node execution to handle split context (execute for all items in parallel)</li>
 *   <li>{@link com.apimarketplace.orchestrator.execution.v2.split.SplitMergeHandler} -
 *       Handles merge nodes after split, aggregates results and closes context</li>
 * </ul>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li><b>Single Responsibility:</b> split only spawns items, downstream nodes consume them</li>
 *   <li><b>Immutability:</b> SplitContext is immutable, mutations return new instances</li>
 *   <li><b>No coupling:</b> split doesn't know about downstream nodes</li>
 *   <li><b>Parallel execution:</b> Downstream nodes execute for ALL items in parallel</li>
 * </ul>
 *
 * <h2>Flow</h2>
 * <pre>
 * 1. Split executes:
 *    - Evaluates source expression → [item1, item2, item3]
 *    - Creates SplitContext with items
 *    - Returns COMPLETED immediately
 *
 * 2. Downstream node executes:
 *    - Detects SplitContext via SplitContextManager
 *    - Executes for ALL items in parallel
 *    - Stores results in context
 *
 * 3. Merge node (optional):
 *    - Aggregates all results
 *    - Closes the SplitContext scope
 * </pre>
 *
 * @see com.apimarketplace.orchestrator.execution.v2.split.SplitContext
 * @see com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager
 * @see com.apimarketplace.orchestrator.execution.v2.split.SplitNodeExecutor
 */
package com.apimarketplace.orchestrator.execution.v2.split;
