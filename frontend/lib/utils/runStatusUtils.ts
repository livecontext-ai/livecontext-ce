/**
 * Utility for resolving the display status of a workflow run.
 *
 * When a reusable-trigger workflow completes a cycle it goes back to
 * WAITING_TRIGGER but stores the cycle result in metadata.lastCycleResult
 * ("completed" or "failed"). For display purposes we want to show that
 * result to the user rather than the raw WAITING_TRIGGER status.
 *
 * IMPORTANT: This utility is ONLY for display/UI rendering.
 * Execution logic (polling, streaming, step-by-step, etc.) must continue
 * to use the raw status value.
 */

import type { WorkflowRun } from '@/lib/api';
import { formatUtcDate, parseUtcAware } from './dateFormatters';

/**
 * Get the display status for a workflow run.
 *
 * For WAITING_TRIGGER runs that have completed at least one cycle,
 * returns the last cycle result ("COMPLETED" or "FAILED") instead
 * of "WAITING_TRIGGER".
 *
 * @param status   - The raw run status from the backend (e.g. "WAITING_TRIGGER", "RUNNING")
 * @param metadata - Optional run metadata containing lastCycleResult
 * @returns The status string to use for display purposes
 */
export function getRunDisplayStatus(
  status: string,
  metadata?: Record<string, any> | null,
): string {
  // WAITING_TRIGGER runs between trigger fires: show the last cycle result
  // (e.g. "COMPLETED" or "FAILED") instead of "WAITING_TRIGGER".
  // CANCELLED runs always display as CANCELLED (lastCycleResult is cleared on cancel).
  if (status === 'WAITING_TRIGGER' && metadata?.lastCycleResult) {
    return String(metadata.lastCycleResult).toUpperCase();
  }
  return status;
}

/**
 * Derive the cycle result the run badge should display for a reusable-trigger run that is resting
 * at WAITING_TRIGGER between fires, so the badge reflects the OUTCOME (green / red / amber) rather
 * than the idle "waiting_trigger".
 *
 * A cycle with BOTH a failed node AND a real (non-trigger) completed node is a PARTIAL_SUCCESS;
 * all-failed is FAILED, all-completed is COMPLETED. The trigger always completes, so it is excluded
 * from the "real completion" check (otherwise an all-failed cycle would read as partial). Returns
 * `undefined` for any non-WAITING_TRIGGER status or an empty cycle (nothing ran yet) - the caller
 * then keeps the raw status. Output is the lowercase enum value consumed by
 * {@link getRunDisplayStatus} via `metadata.lastCycleResult`.
 */
export function deriveBadgeCycleResult(
  runStatus: string,
  completedStepIds: string[],
  hasFailed: boolean,
): string | undefined {
  if ((runStatus || '').toUpperCase() !== 'WAITING_TRIGGER') return undefined;
  const completedNonTrigger = completedStepIds.some((id) => !String(id).startsWith('trigger:'));
  if (hasFailed && completedNonTrigger) return 'partial_success';
  if (hasFailed) return 'failed';
  if (completedNonTrigger) return 'completed';
  return undefined;
}

/**
 * Format an ISO date string as a relative time (e.g. "5m ago", "2h ago").
 */
export function formatRelativeTime(isoDate: string): string {
  const date = parseUtcAware(isoDate);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return 'just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;

  return formatUtcDate(date);
}

/**
 * Run statuses that have a translation in the `status.*` i18n namespace.
 * Anything outside this set falls back to its lowercased raw value, which is
 * never worse than the previous (always-English, untranslated) rendering.
 */
const TRANSLATABLE_RUN_STATUSES = new Set([
  'running', 'completed', 'failed', 'cancelled', 'pending',
  'paused', 'waiting_trigger', 'skipped', 'timeout',
  'partial_success', 'success', 'starting',
]);

/**
 * Localize a run/display status for the run-info badge.
 *
 * `t` is a next-intl translator scoped to the ROOT (so it can resolve a
 * `status.<key>` path). Pass it as `(k) => t(k)` from the component. A status
 * without a known key returns the lowercased raw value rather than a missing-key
 * placeholder.
 */
export function getRunStatusLabel(
  displayStatus: string,
  t: (key: string) => string,
): string {
  const key = displayStatus.toLowerCase();
  return TRANSLATABLE_RUN_STATUSES.has(key) ? t(`status.${key}`) : key;
}

/**
 * Get Tailwind CSS classes for a display status badge.
 */
export function getStatusClasses(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-300';
    case 'RUNNING':
      return 'bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300';
    case 'FAILED':
      return 'bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-300';
    // A run/cycle with BOTH failed and completed nodes is a partial success: amber/orange,
    // distinct from the plain-failed red and the idle-yellow default. Handles the uppercase
    // run status and the lowercase enum value (lastCycleResult).
    case 'PARTIAL_SUCCESS':
    case 'partial_success':
      return 'bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300';
    case 'CANCELLED':
      return 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-400';
    default:
      return 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-700 dark:text-yellow-300';
  }
}

/**
 * Format a workflow run's duration as a human-readable string.
 */
export function formatDuration(runInfo: WorkflowRun): string | null {
  const endTime = runInfo.endedAt || runInfo.completedAt;

  if (runInfo.durationMs) {
    const mins = Math.round(runInfo.durationMs / 60000);
    return mins < 1 ? '< 1min' : `${mins}min`;
  } else if (runInfo.startedAt && endTime) {
    const duration = parseUtcAware(endTime).getTime() - parseUtcAware(runInfo.startedAt).getTime();
    const mins = Math.round(duration / 60000);
    return mins < 1 ? '< 1min' : `${mins}min`;
  }

  return null;
}
