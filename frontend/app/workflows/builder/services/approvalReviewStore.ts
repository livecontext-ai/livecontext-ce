/**
 * Lightweight module-level store for the "approval review" inspector target.
 *
 * Written by UserApprovalNode when the user clicks a pending item row in the
 * node's per-item list, and by ApprovalReviewBar when auto-advancing to the
 * next pending item after a resolution. Read by:
 *  - WorkflowBuilder: selects the node + switches the inspector to advanced
 *  - InspectorPanel: collapses the output column / widens the input column
 *  - useRunData: jumps every Input/Params/Output navigator to the target item
 *    (matched on epoch + itemIndex, latest spawn wins)
 *
 * Same pattern as canvasNodesStore but with subscriptions, because consumers
 * must react to a click that happens outside their React tree.
 */

import { useSyncExternalStore } from 'react';

export interface ApprovalReviewTarget {
  /** ReactFlow node id of the approval node under review. */
  rfNodeId: string;
  /** Epoch of the targeted pending signal (null = unknown/single-epoch). */
  epoch: number | null;
  /** Split item index of the targeted pending signal (null = non-split). */
  itemIndex: number | null;
  /**
   * Monotonic id bumped on every request - lets consumers re-apply the jump
   * even when the user re-clicks the exact same item.
   */
  requestId: number;
}

let _target: ApprovalReviewTarget | null = null;
let _requestSeq = 0;
const _listeners = new Set<() => void>();

function notify() {
  for (const listener of _listeners) listener();
}

export function requestApprovalReview(
  rfNodeId: string,
  epoch: number | null,
  itemIndex: number | null,
): void {
  _requestSeq += 1;
  _target = { rfNodeId, epoch, itemIndex, requestId: _requestSeq };
  notify();
}

export function clearApprovalReview(): void {
  if (_target === null) return;
  _target = null;
  notify();
}

export function getApprovalReviewTarget(): ApprovalReviewTarget | null {
  return _target;
}

export function subscribeApprovalReview(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/** React binding - re-renders the consumer whenever the target changes. */
export function useApprovalReviewTarget(): ApprovalReviewTarget | null {
  return useSyncExternalStore(
    subscribeApprovalReview,
    getApprovalReviewTarget,
    () => null,
  );
}
