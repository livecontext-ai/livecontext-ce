import type { PendingSignal } from '@/lib/websocket/ws-types';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { signalContextText, sortPendingSignals } from '@/lib/workflow/pendingSignals';

/**
 * What a run is waiting on that THE PERSON LOOKING AT THE APPLICATION can answer.
 *
 * <p>The application surface used to know about exactly one of these: the
 * `__continue` of the interface it happens to be showing. Everything else that
 * parks a run - an approval on any node - was invisible there and actionable
 * only from the workflow canvas. Someone who uses an app and never opens its
 * workflow was simply stuck, with no explanation on screen.
 *
 * <p>The data was already present and being thrown away: `runState.pendingSignals`
 * is RUN-wide, and the application filtered it down to its own node's interface
 * signals. This module reads the same list and answers the honest question
 * instead: what is this run waiting for a human to do?
 */

/** An interface node parked on a blocking `__continue`. */
export interface InterfaceContinueBlocker {
  kind: 'interface_continue';
  /** Backend step id of the interface node. */
  nodeId: string;
  /**
   * The fire this continue will actually move, when it can be known. The fire
   * endpoint takes no epoch and resolves the node's NEWEST parked signal, so
   * this is that signal's epoch - not the epoch on screen. Undefined when the
   * signal list has not hydrated, which is the only case where it cannot be
   * attributed to a fire at all.
   */
  epoch?: number;
}

/** A node parked on a USER_APPROVAL, anywhere in the run. */
export interface ApprovalBlocker {
  kind: 'approval';
  /** Backend step id of the approval node - NOT necessarily the displayed one. */
  nodeId: string;
  signalId: PendingSignal['id'];
  epoch?: number;
  itemId?: string;
  /** The question the workflow author wrote, when there is one. */
  context?: string;
}

export type RunBlocker = InterfaceContinueBlocker | ApprovalBlocker;

interface RunStateLike {
  pendingSignals?: PendingSignal[];
  runStatus?: string | null;
}

export interface RunBlockerScope {
  /**
   * The epoch on screen, or null for the all-epochs view. A focused epoch is a
   * RECORD, so only what it is itself waiting on may be offered there: the app
   * would otherwise show, and resolve, an approval belonging to a fire the
   * reader is not looking at. Same rule the canvas gave its own Continue button.
   */
  viewingEpoch?: number | null;
  /** The displayed interface is parked on a blocking `__continue`. */
  interfaceIsAwaiting?: boolean;
  /**
   * Split index of the item the application is showing - the one a continue
   * would carry. Undefined on a caller that does not paginate.
   */
  displayedItemIndex?: number;
  /** Backend step id of the interface this application is showing. */
  displayedInterfaceNodeId?: string | null;
}

/**
 * Epoch of the signal an interface `__continue` fire would actually resolve.
 *
 * <p>Keyed on the ITEM as well as the node, because that is what the endpoint
 * keys on: the fire carries `itemId` and no epoch, and the server takes
 * `max(epoch)` **restricted to that item**. Taking the max across the node's
 * items instead gets it wrong in both directions on a split whose items park in
 * different epochs - it hides a legitimate continue for the epoch on screen when
 * a LATER item is parked further ahead, and it shows one that resolves an
 * EARLIER epoch when the displayed item is the one left behind.
 */
function resolvedInterfaceEpoch(
  signals: PendingSignal[] | undefined,
  nodeId: string,
  itemIndex: number | undefined,
): number | undefined {
  let newest: number | undefined;
  for (const signal of signals ?? []) {
    if (signal.nodeId !== nodeId || signal.signalType !== 'INTERFACE_SIGNAL') continue;
    if (signal.epoch == null) continue;
    // A caller that cannot name its item accepts the node-wide answer; the
    // server's no-item branch makes the same choice.
    if (itemIndex != null && Number(signal.itemId ?? 0) !== itemIndex) continue;
    if (newest == null || signal.epoch > newest) newest = signal.epoch;
  }
  return newest;
}

/**
 * Everything blocking this run on a human, in the order to ask about it.
 *
 * <p>Approvals come first, and deliberately: an approval carries a QUESTION the
 * workflow author wrote, with consequences behind the answer, while a continue
 * is the generic "next". Within the approvals, the run's own review order
 * (epoch, then item), so the application asks in the same sequence the canvas
 * review bar walks.
 *
 * <p>`interfaceIsAwaiting` is PASSED IN rather than re-derived here, and that is
 * the point: the caller already computes it with `computeIsAwaitingSignal` for
 * its toolbar button, which folds in the two rules that matter (the interface
 * must carry a `__continue`, and its node must be parked). Deriving it a second
 * time would let the bar and the button disagree about whether continuing does
 * anything - and it would drag a component-tree import into this module.
 */
export function computeRunBlockers(
  runState: RunStateLike | null | undefined,
  scope: RunBlockerScope = {},
): RunBlocker[] {
  if (!runState) return [];

  // A FINISHED run is waiting for nobody, whatever its signal rows still say.
  // Those rows outlive it: `cancelByRun` is called on cancel/reset, never on a
  // natural completion, and the signal list is emitted regardless of run status.
  // Without this the application painted an amber "waiting for you" and a live
  // Approve/Reject bar over a run that had already ended - and the window is not
  // exotic, it is every resolution of the LAST blocker, between the resolve
  // landing and the state refresh. The engine's own overlay learned this: the
  // snapshot zeroes running counts on a terminal run for the same reason.
  if (runState.runStatus && TERMINAL_STATUSES.has(runState.runStatus as never)) return [];

  const {
    viewingEpoch = null,
    interfaceIsAwaiting = false,
    displayedInterfaceNodeId = null,
    displayedItemIndex,
  } = scope;

  const approvals: ApprovalBlocker[] = sortPendingSignals(
    (runState.pendingSignals ?? [])
      .filter(signal => signal.signalType === 'USER_APPROVAL')
      // A signal with no epoch cannot be attributed to the fire on screen, so
      // it is never hidden by the epoch filter.
      .filter(signal => viewingEpoch == null || signal.epoch == null || signal.epoch === viewingEpoch),
  )
    .map(signal => ({
      kind: 'approval' as const,
      nodeId: signal.nodeId,
      signalId: signal.id,
      epoch: signal.epoch,
      itemId: signal.itemId,
      // Trimmed, and falling back to the split item the signal carries: a
      // blank-but-present context rendered an empty question card with live
      // Approve / Reject under it, and the canvas dialog shows the item.
      context: signalContextText(signal) ?? undefined,
    }));

  const blockers: RunBlocker[] = [...approvals];

  // The interface on screen, when the caller says it is parked on a blocking
  // `__continue`. Last, so an authored question is always asked first.
  if (displayedInterfaceNodeId && interfaceIsAwaiting) {
    // The epoch the fire will REALLY move: the endpoint carries no epoch and
    // takes the node's newest parked signal. `interfaceIsAwaiting` is run-wide
    // (it reads awaitingSignalSteps), so without this the focused-epoch rule
    // applied to approvals and not to the continue beside them - reading epoch
    // 1 offered a button that advances epoch 3 and reports success.
    const epoch = resolvedInterfaceEpoch(runState.pendingSignals, displayedInterfaceNodeId, displayedItemIndex);
    // Unknown epoch is never hidden: it cannot be attributed to a fire, same
    // rule the approvals above apply.
    if (viewingEpoch == null || epoch == null || epoch === viewingEpoch) {
      blockers.push({ kind: 'interface_continue', nodeId: displayedInterfaceNodeId, epoch });
    }
  }

  return blockers;
}

/**
 * Human-readable name for a backend step id (`agent:review_draft` -> `review draft`).
 *
 * <p>Used to name an approval node that is NOT the interface on screen, so the
 * reader can tell "this app is waiting on something else in the workflow" from
 * "this app is waiting on you, here".
 */
export function blockerNodeLabel(nodeId: string): string {
  const withoutPrefix = nodeId.includes(':') ? nodeId.slice(nodeId.indexOf(':') + 1) : nodeId;
  return withoutPrefix.replace(/_/g, ' ').trim() || nodeId;
}
