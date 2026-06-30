/**
 * Pure predicates for interface-node awaiting-signal detection.
 *
 * <p>Extracted from {@link ApplicationTabContent} so the invariant
 * "Continue button shows iff the interface is blocking AND the DAG is paused
 * on that node" can be unit-tested without rendering React.
 *
 * <p>Mirrors backend {@code InterfaceNode.java:63} +
 * {@code SignalConfig.interfaceSignal():87} which set {@code blocking =
 * actionMapping.containsValue("__continue")}.
 */

/**
 * True iff the interface is blocking (its {@code actionMapping} contains at
 * least one entry whose value is {@code "__continue"}). Non-blocking
 * interfaces complete via {@code success()}; the Continue button would be a
 * no-op because the DAG has already moved past the node.
 */
export function hasContinueAction(actionMapping?: Record<string, string> | null): boolean {
  if (!actionMapping) return false;
  return Object.values(actionMapping).includes('__continue');
}

/**
 * True iff the application UI should show the Continue button for this
 * interface node:
 *
 * <ul>
 *   <li>the interface is blocking (has {@code __continue}); AND</li>
 *   <li>the node is currently in {@code awaitingSignalSteps} (backend-authoritative)
 *       OR in {@code runningSteps} (transient running→awaiting window).</li>
 * </ul>
 *
 * Non-blocking interfaces return {@code false} unconditionally - clicking
 * Continue would hit the backend fallback but the workflow has already
 * advanced, making it a silent no-op.
 */
export function computeIsAwaitingSignal(
  nodeId: string | undefined | null,
  runState: { awaitingSignalSteps?: Set<string>; runningSteps?: Set<string> } | null | undefined,
  actionMapping?: Record<string, string> | null,
): boolean {
  if (!nodeId || !runState) return false;
  if (!hasContinueAction(actionMapping)) return false;
  return (runState.awaitingSignalSteps?.has(nodeId) ?? false)
      || (runState.runningSteps?.has(nodeId) ?? false);
}

/**
 * True iff the Continue button should be enabled for the rendered item.
 *
 * Some application surfaces only receive the coarse awaiting/running sets on
 * first paint; the per-signal list can arrive empty even though the interface
 * node is already paused. For a single-page interface, the coarse awaiting
 * state is enough to enable the fallback toolbar Continue button.
 */
export function isCurrentInterfaceItemPending(
  pendingSignals: Array<{ itemId?: string | null }>,
  currentItemIndex: number,
  fallbackAwaitingSinglePage: boolean,
): boolean {
  if (pendingSignals.length === 0) return fallbackAwaitingSinglePage;
  if (pendingSignals.length === 1 && pendingSignals[0].itemId == null) return true;
  return pendingSignals.some(signal => signal.itemId === String(currentItemIndex));
}
