'use client';

/**
 * ApprovalReviewBar - Approve/Reject action bar shown at the top of the
 * inspector when the selected node is a User Approval node with pending
 * signals in run mode.
 *
 * Acts on the CURRENT review target (the item the user clicked in the node's
 * per-item list, kept in approvalReviewStore) or, without a target, the first
 * pending signal. After a resolution it auto-advances the review target to the
 * next pending item so the Input/Params/Output navigators jump there directly.
 *
 * Navigation is RUN-WIDE: the ◄ / ► buttons and the auto-advance walk EVERY
 * pending approval in the run (all approval nodes, all epochs, all split items)
 * - not just the items of the inspected node. Crossing a node boundary
 * re-selects that node through the same approvalReviewStore request that drives
 * useApprovalReviewSelection. Falls back to the inspected node's own pending
 * items when the run-wide queue is unavailable (e.g. outside the provider).
 */

import * as React from 'react';
import clsx from 'clsx';
import { CheckCircle, XCircle, ChevronLeft, ChevronRight, Maximize2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import LoadingSpinner from '@/components/LoadingSpinner';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import type { BuilderNodeData } from '../../types';
import { useStepByStep } from '../../contexts/StepByStepContext';
import { buildApprovalQueue, type ApprovalQueueEntry } from '../../services/approvalQueue';
import { ApprovalContextDialog } from '../ApprovalContextDialog';
import {
  useApprovalReviewTarget,
  requestApprovalReview,
  clearApprovalReview,
} from '../../services/approvalReviewStore';

interface ApprovalReviewBarProps {
  /** ReactFlow node id of the inspected approval node. */
  rfNodeId: string;
  /** Pending USER_APPROVAL signals for this node (live, from WS state). */
  pendingSignals: PendingSignal[];
  /** Resolver bound to this node (signature from useNodeExecutionStatus). */
  resolveApproval: (
    resolution: 'APPROVED' | 'REJECTED',
    itemId?: string,
    epoch?: number,
  ) => Promise<void>;
  /** Full graph - lets the bar map run-wide pending signals back to RF nodes. */
  allNodes?: Node<BuilderNodeData>[];
}

/** Numeric item index of a signal; non-numeric/missing itemId sorts first (0). */
function itemIndexOf(signal: PendingSignal): number {
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
 * The signal the bar acts on: the one matching the review target's
 * coordinates, else the first pending one. Exported for unit tests.
 */
export function pickCurrentSignal(
  sorted: PendingSignal[],
  target: { epoch: number | null; itemIndex: number | null } | null,
): PendingSignal | null {
  if (sorted.length === 0) return null;
  if (target) {
    const match = sorted.find((s) => {
      const epochOk = target.epoch == null || s.epoch == null || s.epoch === target.epoch;
      const itemOk =
        target.itemIndex == null || Number(s.itemId ?? 0) === target.itemIndex;
      return epochOk && itemOk;
    });
    if (match) return match;
  }
  return sorted[0];
}

export function ApprovalReviewBar({
  rfNodeId,
  pendingSignals,
  resolveApproval,
  allNodes,
}: ApprovalReviewBarProps) {
  const tRun = useTranslations('runMode');
  const [resolving, setResolving] = React.useState<'APPROVED' | 'REJECTED' | null>(null);
  const target = useApprovalReviewTarget();
  const targetForNode = target?.rfNodeId === rfNodeId ? target : null;
  const ctx = useStepByStep();

  const sorted = React.useMemo(() => sortPendingSignals(pendingSignals), [pendingSignals]);
  const current = React.useMemo(
    () => pickCurrentSignal(sorted, targetForNode),
    [sorted, targetForNode],
  );

  // Node-local fallback queue (every item of the inspected node), used when the
  // run-wide queue is unavailable or doesn't yet contain the current signal.
  const localQueue = React.useMemo<ApprovalQueueEntry[]>(
    () =>
      sorted.map((s) => {
        const n = Number(s.itemId ?? NaN);
        return {
          signalId: s.id,
          rfNodeId,
          epoch: s.epoch ?? null,
          itemIndex: Number.isFinite(n) ? n : null,
        };
      }),
    [sorted, rfNodeId],
  );

  // Run-wide queue across all approval nodes (needs the provider + the graph).
  const globalQueue = React.useMemo<ApprovalQueueEntry[] | null>(() => {
    if (!ctx || !allNodes || allNodes.length === 0) return null;
    return buildApprovalQueue(ctx.getAllPendingSignals(), allNodes, ctx.resolveNodeId);
  }, [ctx, allNodes]);

  // Use the run-wide queue only when it actually contains the signal we're on -
  // otherwise the inspected item would have no position and prev/next/advance
  // would skip it. The local queue always contains it, so it is the safe floor.
  const navQueue = React.useMemo<ApprovalQueueEntry[]>(() => {
    if (globalQueue && current && globalQueue.some((e) => e.signalId === current.id)) {
      return globalQueue;
    }
    return localQueue;
  }, [globalQueue, localQueue, current]);

  const currentNavIndex = React.useMemo(
    () => (current ? navQueue.findIndex((e) => e.signalId === current.id) : -1),
    [navQueue, current],
  );

  const navigateTo = React.useCallback((entry: ApprovalQueueEntry | undefined) => {
    if (!entry) return;
    requestApprovalReview(entry.rfNodeId, entry.epoch, entry.itemIndex);
  }, []);

  const handleResolve = React.useCallback(
    async (resolution: 'APPROVED' | 'REJECTED') => {
      if (!current || resolving) return;
      setResolving(resolution);
      try {
        // Contract: advance ONLY on a successful resolve - if this throws, the
        // signal is still pending and the bar must stay on it. (In production
        // wiring resolution errors are swallowed upstream and the pending list
        // is the source of truth: a failed resolve leaves the signal in
        // pendingSignals, so the WS refresh brings the item back anyway.)
        await resolveApproval(resolution, current.itemId, current.epoch);
      } catch {
        return;
      } finally {
        setResolving(null);
      }
      // Auto-advance: jump the review target to the next still-pending approval
      // AFTER the one just resolved (wrapping to the first), walking the RUN-WIDE
      // queue so the next item can live on another approval node. The WS update
      // removing the resolved signal lags the API response, so we exclude it by
      // id. Only re-target when a review session is active for this node (the
      // user clicked an item row / used prev-next) - when the bar is used as a
      // plain approve button, re-targeting would force re-selection and
      // re-collapse the output column the user may have arranged themselves.
      const idx = navQueue.findIndex((e) => e.signalId === current.id);
      const wrapped = [...navQueue.slice(idx + 1), ...navQueue.slice(0, Math.max(idx, 0))];
      const next = wrapped.find((e) => e.signalId !== current.id);
      if (!next) {
        clearApprovalReview();
      } else if (targetForNode) {
        requestApprovalReview(next.rfNodeId, next.epoch, next.itemIndex);
      }
    },
    [current, resolving, resolveApproval, navQueue, targetForNode],
  );

  if (!current) return null;

  const itemNumber =
    current.itemId != null && Number.isFinite(Number(current.itemId))
      ? Number(current.itemId) + 1
      : null;

  const total = navQueue.length;
  const showQueueNav = total > 1 && currentNavIndex >= 0;
  const prevEntry = currentNavIndex > 0 ? navQueue[currentNavIndex - 1] : undefined;
  const nextEntry =
    currentNavIndex >= 0 && currentNavIndex < total - 1 ? navQueue[currentNavIndex + 1] : undefined;

  return (
    <div
      data-testid="approval-review-bar"
      className="flex items-center gap-2 px-4 py-2 border-b border-amber-200/70 dark:border-amber-500/30 bg-amber-50/60 dark:bg-amber-500/10 flex-shrink-0"
    >
      {showQueueNav && (
        <div className="flex items-center gap-0.5 flex-shrink-0">
          <button
            type="button"
            data-testid="approval-review-prev"
            aria-label={tRun('approvalBar.prev')}
            title={tRun('approvalBar.prev')}
            onClick={() => navigateTo(prevEntry)}
            disabled={!prevEntry}
            className="w-6 h-6 p-0 rounded-full inline-flex items-center justify-center text-amber-700 dark:text-amber-300 hover:bg-amber-100 dark:hover:bg-amber-500/20 disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent transition-colors"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
          </button>
          <span
            data-testid="approval-review-position"
            className="text-xs font-medium text-amber-800 dark:text-amber-200 font-mono whitespace-nowrap min-w-[36px] text-center"
          >
            {tRun('approvalBar.queuePosition', { current: currentNavIndex + 1, total })}
          </span>
          <button
            type="button"
            data-testid="approval-review-next"
            aria-label={tRun('approvalBar.next')}
            title={tRun('approvalBar.next')}
            onClick={() => navigateTo(nextEntry)}
            disabled={!nextEntry}
            className="w-6 h-6 p-0 rounded-full inline-flex items-center justify-center text-amber-700 dark:text-amber-300 hover:bg-amber-100 dark:hover:bg-amber-500/20 disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent transition-colors"
          >
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      )}
      <span className="flex items-center gap-1.5 min-w-0">
        {itemNumber != null && (
          <span className="text-sm font-medium text-amber-800 dark:text-amber-200 font-mono whitespace-nowrap">
            {tRun('itemBadge', { number: itemNumber })}
          </span>
        )}
        {current.epoch != null && (
          <span className="text-xs px-1.5 rounded-full bg-amber-100 dark:bg-amber-500/20 text-amber-700 dark:text-amber-300 whitespace-nowrap">
            {tRun('epochBadge', { number: current.epoch })}
          </span>
        )}
        {!showQueueNav && !current.approvalContext && (
          <span className="text-sm text-amber-700/80 dark:text-amber-300/80 truncate">
            {tRun('approvalBar.pendingCount', { count: total })}
          </span>
        )}
      </span>
      {current.approvalContext ? (
        <ApprovalContextDialog
          signals={sorted}
          resolve={resolveApproval}
          initialSignalId={current.id}
          data-testid="approval-review-context"
          className="group/ctx flex-1 min-w-0 flex items-center gap-1.5 text-sm text-amber-800/90 dark:text-amber-200/90 hover:text-amber-900 dark:hover:text-amber-100 transition-colors text-left"
        >
          <span className="truncate">{current.approvalContext}</span>
          <Maximize2 className="h-3 w-3 flex-shrink-0 opacity-50 group-hover/ctx:opacity-100" />
        </ApprovalContextDialog>
      ) : (
        <div className="flex-1" />
      )}
      <button
        type="button"
        data-testid="approval-review-approve"
        onClick={() => handleResolve('APPROVED')}
        disabled={resolving != null}
        className={clsx(
          'flex items-center gap-1 px-3 py-1.5 rounded-full text-sm font-medium transition-colors shadow-sm',
          'bg-emerald-100 text-emerald-700 hover:bg-emerald-200 dark:bg-emerald-500/20 dark:text-emerald-300 dark:hover:bg-emerald-500/30',
          resolving != null && 'opacity-50 cursor-not-allowed',
        )}
      >
        {resolving === 'APPROVED' ? (
          <LoadingSpinner size="xs" />
        ) : (
          <CheckCircle className="h-3.5 w-3.5" />
        )}
        {tRun('approvalBar.approve')}
      </button>
      <button
        type="button"
        data-testid="approval-review-reject"
        onClick={() => handleResolve('REJECTED')}
        disabled={resolving != null}
        className={clsx(
          'flex items-center gap-1 px-3 py-1.5 rounded-full text-sm font-medium transition-colors shadow-sm',
          'bg-red-100 text-red-700 hover:bg-red-200 dark:bg-red-500/20 dark:text-red-300 dark:hover:bg-red-500/30',
          resolving != null && 'opacity-50 cursor-not-allowed',
        )}
      >
        {resolving === 'REJECTED' ? (
          <LoadingSpinner size="xs" />
        ) : (
          <XCircle className="h-3.5 w-3.5" />
        )}
        {tRun('approvalBar.reject')}
      </button>
    </div>
  );
}
