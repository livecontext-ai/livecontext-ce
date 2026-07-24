/**
 * Handle resolution shared by the two hover-"+" insert paths (clicking an existing
 * node in BuilderCanvas, and picking from the NodeCreator in WorkflowBuilder).
 *
 * Both need the same two decisions, and they were copy-pasted; keeping them here
 * keeps the two paths in agreement and lets the fix below be tested once.
 */

/**
 * The target handle to connect an inserted edge to, or `null` to let ReactFlow pick
 * the node's default target.
 *
 * A plain FlowNode target handle renders with NO `id`, so `HoverEdgeManager` hands
 * back a SYNTHESIZED id of the form `target-<side>` - `target-left` in horizontal,
 * `target-top` in vertical. Neither is a real handle id, so passing it to `onConnect`
 * makes ReactFlow fail to resolve the handle and the edge never renders. Normalize
 * every `target-<side>` form to null, which ReactFlow resolves to the node's default
 * (sole) target - correct for every node that has a single target, including the few
 * that set an explicit `id="target-left"` (Aggregate/Exit/Fork/Response/Split), which
 * `HoverEdgeManager` reports under that same string. Real MULTI-handle ids (Merge and
 * While inputs, e.g. `merge-1-input-2`, `while-x-loop-back`) do not match the anchored
 * pattern, so they pass through unchanged and resolve to their specific handle.
 */
export function resolveInsertedTargetHandle(handleId: string): string | null {
  return /^target-(left|top|right|bottom)$/.test(handleId) ? null : handleId;
}
