/**
 * Builds the run-wide "approval queue": the ordered list of EVERY pending
 * USER_APPROVAL signal across the whole run (all approval nodes, all epochs,
 * all split items), so the inspector's ApprovalReviewBar can step (prev/next)
 * through them and approve/reject auto-advances to the next one anywhere in the
 * run - not just the next item of the current node.
 *
 * Each pending signal carries the backend STEP id (`nodeId`, e.g.
 * `core:my_approval`); to drive node selection the bar needs the React Flow
 * node id. We rebuild the step-id -> rf-node-id map from the live graph using
 * the same {@code resolveNodeId} the StepByStepContext exposes, so a signal is
 * mapped exactly the way {@code getPendingSignalsForNode} matched it.
 */

import type { PendingSignal } from '@/lib/websocket/ws-types';

export interface ApprovalQueueEntry {
  /** PendingSignal.id - globally unique, used to locate the current entry. */
  signalId: number;
  /** React Flow node id of the approval node owning this signal. */
  rfNodeId: string;
  /** Epoch of the signal (null = single-epoch / unknown). */
  epoch: number | null;
  /** Split item index of the signal (null = non-split / non-numeric itemId). */
  itemIndex: number | null;
}

type NodeLike = {
  id: string;
  data?: { label?: string; kind?: string; crudOperation?: string };
};

type ResolveNodeId = (
  nodeId: string,
  nodeData?: { label?: string; kind?: string; crudOperation?: string },
) => string;

/** Numeric item index of a signal, or null when itemId is missing/non-numeric. */
function itemIndexOf(signal: PendingSignal): number | null {
  if (signal.itemId == null) return null;
  const n = Number(signal.itemId);
  return Number.isFinite(n) ? n : null;
}

/**
 * Build the ordered run-wide approval queue.
 *
 * Order: node appearance order in {@code nodes} -> epoch -> item index. This
 * gives a stable, predictable walk (one node fully drained before the next,
 * earliest epoch/item first) so prev/next and auto-advance are deterministic.
 *
 * Signals whose owning step cannot be mapped back to a graph node are dropped
 * (they would not be navigable - selecting a node we can't find is a no-op).
 */
export function buildApprovalQueue(
  signals: PendingSignal[],
  nodes: NodeLike[],
  resolveNodeId: ResolveNodeId,
): ApprovalQueueEntry[] {
  const stepToRf = new Map<string, string>();
  const rfOrder = new Map<string, number>();

  nodes.forEach((n, idx) => {
    if (!rfOrder.has(n.id)) rfOrder.set(n.id, idx);
    const stepId = resolveNodeId(n.id, n.data);
    // First graph node that resolves to a given step id wins - approval nodes
    // are 1:1 with their step id, so collisions are not expected in practice.
    if (!stepToRf.has(stepId)) stepToRf.set(stepId, n.id);
  });

  const entries: ApprovalQueueEntry[] = [];
  for (const s of signals) {
    const rfNodeId = stepToRf.get(s.nodeId);
    if (!rfNodeId) continue;
    entries.push({
      signalId: s.id,
      rfNodeId,
      epoch: s.epoch ?? null,
      itemIndex: itemIndexOf(s),
    });
  }

  entries.sort((a, b) => {
    const oa = rfOrder.get(a.rfNodeId) ?? Number.MAX_SAFE_INTEGER;
    const ob = rfOrder.get(b.rfNodeId) ?? Number.MAX_SAFE_INTEGER;
    if (oa !== ob) return oa - ob;
    const ea = a.epoch ?? 0;
    const eb = b.epoch ?? 0;
    if (ea !== eb) return ea - eb;
    return (a.itemIndex ?? 0) - (b.itemIndex ?? 0);
  });

  return entries;
}
