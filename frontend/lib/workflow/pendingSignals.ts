import type { PendingSignal } from '@/lib/websocket/ws-types';

/**
 * Ordering helpers for the pending-signal queues a node can be parked on.
 *
 * <p>Pure and component-free on purpose, and in `lib/` rather than under the
 * builder: four callers across two trees need "which one is first" - the
 * approval review bar, the approval context dialog, the interface node's
 * Continue button, and the application's own action bar. The last of those
 * lives in `lib/workflow/runBlockers`, and a lib module reaching into the
 * component tree for a comparator is how that comparator ends up copied
 * instead (it did, once, and the copy was byte-identical).
 */

/** Numeric item index of a signal; non-numeric/missing itemId sorts first (0). */
export function itemIndexOf(signal: PendingSignal): number {
  const n = Number(signal.itemId ?? 0);
  return Number.isFinite(n) ? n : 0;
}

/** Sort pending signals on the (epoch, itemIndex) review axis. */
export function sortPendingSignals(signals: PendingSignal[]): PendingSignal[] {
  return [...signals].sort((a, b) => {
    const ea = a.epoch ?? 0;
    const eb = b.epoch ?? 0;
    if (ea !== eb) return ea - eb;
    return itemIndexOf(a) - itemIndexOf(b);
  });
}

/**
 * The signal an interface `__continue` fire will ACTUALLY resolve.
 *
 * <p>Not `sortPendingSignals(...)[0]`. The fire endpoint takes an `itemId` and
 * no epoch, and then picks `max(epoch)` among the node's signals carrying that
 * item. So a node parked on item 0 in epoch 1 AND epoch 3 - ordinary on a
 * reusable trigger - resolves epoch 3 whatever the caller had in mind. Labelling
 * the button from the review order (epoch ASCENDING) would therefore name epoch 1
 * and release epoch 3: the count drops, the user believes the older fire moved,
 * and nothing reports otherwise.
 *
 * <p>So this orders the way the BACKEND does - newest epoch first, then lowest
 * item - and the label is built from what comes back. Whatever the button says
 * is what the click does.
 */
export function pickInterfaceContinueTarget(signals: PendingSignal[]): PendingSignal | null {
  if (signals.length === 0) return null;
  return [...signals].sort((a, b) => {
    const ea = a.epoch ?? 0;
    const eb = b.epoch ?? 0;
    if (ea !== eb) return eb - ea;
    return itemIndexOf(a) - itemIndexOf(b);
  })[0];
}

/**
 * The human sentence a pending signal carries: the author's `approvalContext`,
 * else the first non-empty string of its split `itemContext`, else null.
 *
 * <p>Shared because two surfaces show the SAME signal and were describing it
 * differently. The canvas dialog read the split item ("Invoice #4412 from
 * Acme") while the application's action bar fell back to naming the node, and
 * a blank-but-present context rendered an empty question card with live
 * Approve / Reject under it - truthiness is not enough, the string has to be
 * trimmed.
 */
export function signalContextText(signal: PendingSignal | undefined): string | null {
  if (!signal) return null;
  if (signal.approvalContext && signal.approvalContext.trim() !== '') return signal.approvalContext;
  const itemContext = signal.itemContext;
  if (itemContext && typeof itemContext === 'object' && !Array.isArray(itemContext)) {
    const first = Object.values(itemContext as Record<string, unknown>).find(
      (value): value is string => typeof value === 'string' && value.trim() !== '',
    );
    if (first) return first;
  }
  return null;
}
