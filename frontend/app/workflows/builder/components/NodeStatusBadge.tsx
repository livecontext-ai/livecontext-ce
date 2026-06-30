'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Check, X, PlayCircle, MinusCircle, PauseCircle } from 'lucide-react';
import type { DerivedNodeStatus, StatusCounts } from '../types';

interface NodeStatusBadgeProps {
  status?: DerivedNodeStatus;
  statusCounts?: StatusCounts;
}

/**
 * Optimized node status badge component
 * Displays status icons with counts on nodes
 */
export const NodeStatusBadge = React.memo(function NodeStatusBadge({
  status,
  statusCounts,
}: NodeStatusBadgeProps) {
  const t = useTranslations('nodeStatus');
  // Extract counts from statusCounts. Accept BOTH uppercase canonical keys
  // (the {@link normalizeStatusCounts} output, used by SSE step updates via
  // statusUpdater.ts) AND lowercase keys (the polling /status-counts endpoint
  // returns raw lowercase keys via WorkflowEpochService.buildNodeCountsFromRows
  // line 289, which runStateSyncService.applyStatusCountsToReactFlow forwards
  // verbatim without normalizing - known asymmetry across the two write paths).
  // Without the lowercase fallback, switching to the "All" tab (which triggers
  // a polling refresh of node.data.statusCounts) silently drops the SKIPPED
  // badge for nodes like classify on multi-epoch runs. Mirrors the same
  // tolerance already in deriveStatusFromCounts (statusCounts.ts:191).
  const counts = React.useMemo(() => {
    if (!statusCounts) return null;
    const read = (upper: string, lower: string): number =>
      (statusCounts[upper] ?? statusCounts[lower] ?? 0);
    // BUDGET_EXHAUSTED is a SUBSET of FAILED on the backend
    // (AgentMetricsQueryService carves it out for visibility). Subtract it
    // from the red "failed" tally so a single throttled execution shows up
    // as one amber Coins chip - not one red + one amber double-count.
    const rawFailed = read('FAILED', 'failed') + read('ERROR', 'error');
    const budgetExhausted = read('BUDGET_EXHAUSTED', 'budget_exhausted');
    return {
      completed: read('COMPLETED', 'completed') + read('SUCCESS', 'success'),
      failed: Math.max(0, rawFailed - budgetExhausted),
      budgetExhausted,
      running: read('RUNNING', 'running') + read('RETRYING', 'retrying'),
      skipped: read('SKIPPED', 'skipped'),
      awaitingSignal: read('AWAITING_SIGNAL', 'awaiting_signal') + (statusCounts.awaitingSignal ?? 0),
    };
  }, [statusCounts]);

  // Background color based on status
  const bgColor = React.useMemo(() => {
    if (!status || status === 'pending') {
      return 'bg-gray-100 dark:bg-gray-800';
    }
    switch (status) {
      case 'running':
        return 'bg-blue-100 dark:bg-blue-900/30';
      case 'completed':
        return 'bg-green-100 dark:bg-green-900/30';
      case 'failed':
        return 'bg-red-100 dark:bg-red-900/30';
      case 'skipped':
        return 'bg-gray-100 dark:bg-gray-800';
      case 'partial_success':
        return 'bg-amber-100 dark:bg-amber-900/30';
      case 'awaiting_signal':
        return 'bg-amber-100 dark:bg-amber-900/30';
      default:
        return 'bg-gray-100 dark:bg-gray-800';
    }
  }, [status]);

  // Text color based on status
  const textColor = React.useMemo(() => {
    if (!status || status === 'pending') {
      return 'text-gray-600 dark:text-gray-400';
    }
    switch (status) {
      case 'running':
        return 'text-blue-700 dark:text-blue-300';
      case 'completed':
        return 'text-green-700 dark:text-green-300';
      case 'failed':
        return 'text-red-700 dark:text-red-300';
      case 'skipped':
        return 'text-gray-500 dark:text-gray-400';
      case 'partial_success':
        return 'text-amber-700 dark:text-amber-300';
      case 'awaiting_signal':
        return 'text-amber-700 dark:text-amber-300';
      default:
        return 'text-gray-600 dark:text-gray-400';
    }
  }, [status]);

  // Build title from status and counts
  const title = React.useMemo(() => {
    const parts: string[] = [];
    if (status && status !== 'pending') {
      parts.push(t(status));
    }
    if (counts) {
      if (counts.completed > 0) parts.push(`${counts.completed} ${t('completed').toLowerCase()}`);
      if (counts.failed > 0) parts.push(`${counts.failed} ${t('failed').toLowerCase()}`);
      if (counts.budgetExhausted > 0) parts.push(`${counts.budgetExhausted} ${t('budget_exhausted').toLowerCase()}`);
      if (counts.running > 0) parts.push(`${counts.running} ${t('running').toLowerCase()}`);
      if (counts.awaitingSignal > 0) parts.push(`${counts.awaitingSignal} ${t('awaiting_signal').toLowerCase()}`);
      if (counts.skipped > 0) parts.push(`${counts.skipped} ${t('skipped').toLowerCase()}`);
    }
    return parts.join(' • ') || t('pending');
  }, [status, counts, t]);

  // Don't show if no status and no counts
  if (!status && !counts) return null;

  return (
    <div className="flex items-center gap-1" title={title} data-testid="node-status-badge">
      {counts ? (
        <>
          {counts.completed > 0 && (
            <span className="flex items-center gap-0.5 text-green-600 dark:text-green-400" title={`${counts.completed} ${t('completed').toLowerCase()}`} data-testid="node-status-completed">
              <Check className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.completed >= 100 ? '99+' : counts.completed}</span>
            </span>
          )}
          {counts.failed > 0 && (
            <span className="flex items-center gap-0.5 text-red-600 dark:text-red-400" title={`${counts.failed} ${t('failed').toLowerCase()}`}>
              <X className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.failed >= 100 ? '99+' : counts.failed}</span>
            </span>
          )}
          {counts.budgetExhausted > 0 && (
            <span
              className="flex items-center gap-0.5 text-red-600 dark:text-red-400"
              title={`${counts.budgetExhausted} ${t('budget_exhausted').toLowerCase()}`}
            >
              {/* Red X - matches the FAILED chip color and the edge color used
                  for failed states. BUDGET_EXHAUSTED is a hard failure (the
                  agent never ran), not a partial-success warning, so it shares
                  the visual vocabulary of FAILED. The tooltip text discriminates
                  "throttled by insufficient credits" from generic failures. */}
              <X className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.budgetExhausted >= 100 ? '99+' : counts.budgetExhausted}</span>
            </span>
          )}
          {counts.running > 0 && (
            <span className="flex items-center gap-0.5 text-blue-600 dark:text-blue-400" title={`${counts.running} ${t('running').toLowerCase()}`}>
              <PlayCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.running >= 100 ? '99+' : counts.running}</span>
            </span>
          )}
          {counts.skipped > 0 && (
            <span className="flex items-center gap-0.5 text-gray-500 dark:text-gray-400" title={`${counts.skipped} ${t('skipped').toLowerCase()}`}>
              <MinusCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.skipped >= 100 ? '99+' : counts.skipped}</span>
            </span>
          )}
          {counts.awaitingSignal > 0 && (
            <span className="flex items-center gap-0.5 text-amber-500 dark:text-amber-400" title={`${counts.awaitingSignal} ${t('awaiting_signal').toLowerCase()}`}>
              <PauseCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.awaitingSignal >= 100 ? '99+' : counts.awaitingSignal}</span>
            </span>
          )}
        </>
      ) : (
        // Fallback: show status icon if no counts but status exists
        <>
          {status === 'running' && <PlayCircle className="h-3 w-3 text-blue-600 dark:text-blue-400" />}
          {status === 'completed' && <Check className="h-3 w-3 text-green-600 dark:text-green-400" />}
          {status === 'failed' && <X className="h-3 w-3 text-red-600 dark:text-red-400" />}
          {status === 'skipped' && <MinusCircle className="h-3 w-3 text-gray-500 dark:text-gray-400" />}
          {status === 'partial_success' && (
            <>
              <Check className="h-3 w-3 text-green-600 dark:text-green-400" />
              <X className="h-3 w-3 text-red-600 dark:text-red-400" />
            </>
          )}
          {status === 'awaiting_signal' && <PauseCircle className="h-3 w-3 text-amber-500 dark:text-amber-400" />}
        </>
      )}
    </div>
  );
});

