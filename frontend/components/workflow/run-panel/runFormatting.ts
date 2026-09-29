/**
 * Pure formatting / status constants and helpers shared by every run surface:
 * the compact canvas run bar, the side-panel Run tab (summary + steps + epochs)
 * and the run history list.
 *
 * Dependency-free on purpose so they stay trivially unit-testable and can be
 * imported from a server-rendered module - or from a plain node test - without
 * pulling in React.
 */

import { TERMINAL_STATUSES, UNREVIVABLE_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

/**
 * Class that plays the "this is the run you came back from" cue on a history row.
 *
 * Applied and removed imperatively by `scrollToAndFlash`, so its whole lifetime
 * lives in the stylesheet: the animation defined for it in globals.css MUST end
 * (an `animation: none` under `prefers-reduced-motion` would never fire
 * `animationend` and the ring would stay on screen for good).
 */
export const RUN_ROW_FLASH_CLASS = 'run-row-focus-flash';

/**
 * Run statuses a trigger can no longer be fired into (the dispatcher rejects it).
 *
 * Derived from {@link TERMINAL_STATUSES}, the single source of truth that mirrors
 * the backend's {@code RunStatus.isTerminal()}. The run surfaces used to carry
 * their own hardcoded copy, which had drifted: it omitted `stopped`, so a STOPPED
 * run was never offered the Reactivate button (right under a comment claiming
 * "every terminal status is reactivatable") and still showed a fire button the
 * dispatcher would refuse. Upper-cased here because the run REST payload reports
 * the status upper-case while the store keeps it lower-case.
 */
export const TERMINAL_RUN_STATUSES: ReadonlySet<string> = new Set(
  [...TERMINAL_STATUSES].map(s => s.toUpperCase()),
);

export interface EpochTimestamp {
  epoch: number;
  startedAt: string;
  endedAt: string | null;
  /**
   * How long the epoch spent EXECUTING, from its first node starting to its last
   * node finishing. Absent while nothing has run yet.
   *
   * `endedAt - startedAt` is NOT this figure: the close is stamped when the epoch is
   * reconciled, which can be a resume or a restart recovery sweep long after the last
   * node finished, so that span counts the idle tail and reported things like 32h42m
   * for epochs that really execute in seconds. Use the timestamps to place the epoch
   * on the timeline, this to say how long it took.
   */
  workDurationMs?: number | null;
  /**
   * What this epoch ACHIEVED, as the backend tallied its nodes: COMPLETED or FAILED
   * (absent while the epoch has executed nothing but its trigger).
   *
   * Never says RUNNING: an epoch's header stays open long after its last node
   * finished, for the same deferred-close reason as above. `resolveEpochBadgeStatus`
   * combines this with the RUN's status to decide what the row badges.
   */
  status?: string | null;
}

export interface StepStatusCounts {
  completed?: number;
  failed?: number;
  skipped?: number;
  running?: number;
  awaitingSignal?: number;
}

export interface StepEntry {
  alias: string;
  toolId?: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  executionTimeMs?: number;
  totalExecutionTimeMs?: number;
  /** One epoch only: how long the node held the epoch (see stepDisplayDurationMs). */
  elapsedMs?: number;
  statusCounts?: StepStatusCounts;
}

/**
 * `1.4s` / `42s` / `3m07s` / `2h05m` - never wider than 6 chars.
 *
 * Rounds the WHOLE figure before splitting it into units. Rounding only the remainder printed
 * `1m60s` for 119.7 s and `59m60s` for 3599.7 s, and the one-decimal branch printed `10.0s`.
 */
export function formatCompactDuration(ms: number): string {
  if (ms < 1000) return '<1s';
  const sec = ms / 1000;
  if (sec < 9.95) return `${sec.toFixed(1)}s`;
  const totalSec = Math.round(sec);
  if (totalSec < 60) return `${totalSec}s`;
  const minutes = Math.floor(totalSec / 60);
  const remSec = totalSec % 60;
  if (minutes < 60) return `${minutes}m${String(remSec).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h${String(remainMin).padStart(2, '0')}m`;
}

/** Total executions a step's status counts report, 0 when it carries none. */
function executionCount(counts?: StepStatusCounts): number {
  if (!counts) return 0;
  return (counts.completed ?? 0) + (counts.failed ?? 0) + (counts.skipped ?? 0)
    + (counts.running ?? 0) + (counts.awaitingSignal ?? 0);
}

/**
 * How long to say a step took, for the step gauge and its tooltip. Null means "no honest
 * figure": callers render nothing, never `<1s`.
 *
 * The backend sends several durations under overlapping names, and this is the one place that
 * picks among them:
 * - **All epochs** (`cumulative`): `totalExecutionTimeMs`, the sum over every epoch. The
 *   single-execution `executionTimeMs` is only an acceptable stand-in when the step ran at most
 *   once; for a node that ran many times it is ONE execution, and drawing it on the same scale as
 *   its neighbours' totals made a 30-epoch node look shorter than a one-off.
 * - **One epoch**: `elapsedMs`, how long the node HELD the epoch, as the backend measures it
 *   (per loop iteration, the span of its rows; iterations added up). Neither obvious figure
 *   is right: the per-epoch `executionTimeMs` ADDS UP the rows (ten parallel split items of 5 s
 *   read 50 s), and `endTime - startTime` counts, for a loop body, every other node of every
 *   iteration in between. Falls back to `executionTimeMs` from a backend that does not send it.
 * - **A node still executing in one epoch**: before anything of it was timed, elapsed since its
 *   start, so it ticks. Once part of it was timed (a loop body between iterations, a split with
 *   finished items), that timed figure: elapsed-since-first-start would count the other nodes of
 *   the earlier iterations, then drop when the node finished. Never in the all-epochs view, whose
 *   first start belongs to the run's first epoch.
 * - **Skipped only**: null. A skipped node did not run; its rows start and end together and
 *   printed `<1s`.
 */
export function stepDisplayDurationMs(
  step: Pick<StepEntry, 'status' | 'startTime' | 'endTime' | 'executionTimeMs' | 'totalExecutionTimeMs' | 'statusCounts' | 'elapsedMs'>,
  cumulative: boolean,
  now: number = Date.now(),
): number | null {
  const effective = deriveEffectiveStatus(step.status, step.statusCounts);
  if (effective === 'skipped') return null;
  if (cumulative) {
    if (step.totalExecutionTimeMs != null) return Math.max(0, step.totalExecutionTimeMs);
    if (step.executionTimeMs != null && executionCount(step.statusCounts) <= 1) return Math.max(0, step.executionTimeMs);
    return null;
  }
  const start = step.startTime ? parseUtcAware(step.startTime).getTime() : NaN;
  if (effective === 'running' && step.elapsedMs == null) {
    // Tick from the start only for a node none of whose rows has finished: otherwise the start is
    // that of an earlier attempt (a rerun), and "now - start" would read hours.
    const c = step.statusCounts;
    const finished = (c?.completed ?? 0) + (c?.failed ?? 0) + (c?.skipped ?? 0);
    return finished === 0 && !isNaN(start) ? Math.max(0, now - start) : null;
  }
  if (step.elapsedMs != null) return Math.max(0, step.elapsedMs);
  return step.executionTimeMs != null ? Math.max(0, step.executionTimeMs) : null;
}

/** Tailwind classes for a waterfall duration bar, by effective step status. */
export function getBarColor(status: string): string {
  switch (status) {
    // Amber, like the node border, the status badge and the edge stroke. It read red here while
    // every other surface showed amber, so the same node looked failed in the waterfall and
    // partial everywhere else.
    case 'partial_success':
      return 'bg-amber-400/80 dark:bg-amber-500/70';
    case 'error':
    case 'failed':
      return 'bg-red-400/80 dark:bg-red-500/70';
    case 'running':
    case 'pending':
      return 'bg-blue-500 animate-pulse';
    case 'skipped':
      return 'bg-gray-300 dark:bg-gray-600';
    case 'awaiting_signal':
      return 'bg-amber-400/80 dark:bg-amber-500/70';
    default:
      return 'bg-emerald-500/80 dark:bg-emerald-500/70';
  }
}

/**
 * Derive the effective display status from statusCounts (multi-epoch aggregate).
 * Priority: running > awaiting_signal > failed/partial > completed > skipped > raw.
 */
export function deriveEffectiveStatus(
  rawStatus: string,
  statusCounts?: StepStatusCounts,
): string {
  if (!statusCounts) return rawStatus;
  const { completed = 0, failed = 0, running = 0, awaitingSignal = 0, skipped = 0 } = statusCounts;
  if (running > 0) return 'running';
  if (awaitingSignal > 0) return 'awaiting_signal';
  if (failed > 0) return completed > 0 ? 'partial_success' : 'failed';
  if (completed > 0) return 'completed';
  if (skipped > 0) return 'skipped';
  return rawStatus;
}

/** True while the run can still accept a trigger fire (non-terminal status). */
export function isRunStatusActive(status: string | null | undefined): boolean {
  const upper = (status || '').toUpperCase();
  return !!upper && !TERMINAL_RUN_STATUSES.has(upper);
}

/**
 * How long to say an epoch took, in milliseconds.
 *
 * An epoch that is CLOSED reports the window its nodes executed, which the backend
 * measures on the step rows. `endedAt - startedAt` must NOT be used: the close is
 * stamped when the epoch is reconciled, which can be a resume or a restart recovery
 * sweep hours or days after the last node finished. That span is where the run
 * history's 32h42m came from, for epochs whose nodes ran for seconds.
 *
 * An epoch that is LIVE reports elapsed-since-start, and it ticks: it is executing, or
 * blocked on an approval or an in-flight agent. The settled window would under-report
 * it - an epoch three minutes into an approval is not a two-second epoch.
 *
 * "Live" is NOT "has no endedAt". A cycle normally closes its epoch as it ends, but the
 * close is DEFERRED when a blocking signal or an in-flight agent is still around, and a
 * run that is then stopped, cancelled or timed out leaves that epoch unclosed for good.
 * Counting elapsed time for it is how a settled epoch reached "2h05m and rising". Callers
 * pass `isLive` from {@link resolveEpochBadgeStatus}, which asks the RUN. The default
 * keeps the old open-means-live reading for callers that have no run status to offer.
 *
 * Returns null for "unknown", which is NOT the same as 0. Zero is a measurement (an
 * all-skipped epoch really does start and end at the same instant); null means the
 * payload never carried a window, so callers must render nothing rather than assert
 * the epoch was instantaneous.
 */
export function epochDisplayDurationMs(
  entry: Pick<EpochTimestamp, 'startedAt' | 'endedAt' | 'workDurationMs'>,
  now: number,
  isLive: boolean = entry.endedAt == null,
): number | null {
  const measured = entry.workDurationMs != null ? Math.max(0, entry.workDurationMs) : null;
  if (!entry.startedAt) return measured;
  // Settled epoch: only the measured window can answer. Null means the payload never
  // carried one - a showcase snapshot frozen before this field existed, or a
  // frontend running ahead of its orchestrator mid-deploy. Callers render nothing.
  // Returning 0 here would print "<1s", a confident claim that the epoch was
  // instantaneous, on every epoch of every application published before this shipped.
  if (entry.endedAt || !isLive) return measured;
  const start = parseUtcAware(entry.startedAt).getTime();
  if (isNaN(start)) return measured;
  // Live epoch: the larger of the two. Elapsed is the truth for the epoch as a
  // whole, and the measured window guards against a start timestamp in the future
  // (a client clock ahead of the server) collapsing a real duration to zero.
  return Math.max(measured ?? 0, Math.max(0, now - start));
}

/**
 * Run statuses whose ending ABANDONS whatever epoch was still open: the run was killed
 * mid-flight, so that epoch never reached the ending its own tally would suggest.
 * Reuses the store's set rather than restating it.
 */
const ABANDONING_RUN_STATUSES = UNREVIVABLE_STATUSES;

/**
 * Run statuses during which an epoch that is still open really is executing.
 *
 * The RunStatus union minus the terminal ones (they end the run) and minus
 * WAITING_TRIGGER (the run is parked between fires, doing nothing). Enumerated rather
 * than computed as "not terminal": an UNKNOWN status - a value from a newer backend, a
 * typo, a payload from another product surface - must not be read as "executing", which
 * would put a live pulse on a settled epoch and start its duration counting again.
 */
const EXECUTING_RUN_STATUSES: ReadonlySet<string> = new Set([
  'pending', 'running', 'paused', 'awaiting_signal',
]);

/**
 * The status to badge for ONE epoch row, upper-case, or null when there is nothing
 * honest to say yet.
 *
 * A run accumulates many epochs and its own status can only describe the last one, so
 * each row carries the outcome the backend derived for it (`entry.status`: COMPLETED or
 * FAILED). The backend sends NO status for an epoch it cannot speak for - one that ran
 * nothing but its trigger, and one that is still ACTIVE, whose stored state is the one
 * written when it opened. So in practice a status arrives only with a close timestamp.
 *
 * What the backend deliberately does not answer is whether an open epoch is executing
 * right now: the epoch row cannot tell, because the close is deferred and
 * `endedAt == null` means "not reconciled yet". Only the RUN knows, hence this function.
 */
export function resolveEpochBadgeStatus(
  entry: Pick<EpochTimestamp, 'endedAt' | 'status'> | null | undefined,
  runStatus?: string | null,
): string | null {
  if (!entry) return null;
  const outcome = entry.status ? String(entry.status).toUpperCase() : null;
  // Closed epoch: its outcome is final and outranks the run, which may already be
  // executing the NEXT epoch.
  if (entry.endedAt) return outcome;
  const lower = (runStatus || '').toLowerCase();
  // Killed mid-flight: the epoch never reached the ending its own tally would suggest.
  if (ABANDONING_RUN_STATUSES.has(lower as never)) return lower.toUpperCase();
  if (EXECUTING_RUN_STATUSES.has(lower)) return 'RUNNING';
  // Open epoch, run neither executing nor abandoned (parked at WAITING_TRIGGER, or a
  // status this build does not know). Defensive: today the backend attaches no outcome
  // to an open epoch, so this yields no badge rather than a stale one.
  return outcome;
}

/**
 * The colour family of an epoch, from its badge status (`resolveEpochBadgeStatus`):
 * running (blue), failed (red), stopped by a user or a timeout (gray), completed
 * (emerald), or `none` when the epoch carries no outcome yet.
 */
export type EpochTone = 'running' | 'failed' | 'stopped' | 'ok' | 'none';

export function epochTone(badgeStatus: string | null | undefined): EpochTone {
  switch (badgeStatus) {
    case 'RUNNING':
      return 'running';
    case 'FAILED':
      return 'failed';
    case 'CANCELLED':
    case 'STOPPED':
    case 'TIMEOUT':
      return 'stopped';
    case 'COMPLETED':
      return 'ok';
    default:
      return 'none';
  }
}

/** Whether an epoch row is genuinely executing (drives the ticking duration + live styling). */
export function isEpochLive(
  entry: Pick<EpochTimestamp, 'endedAt' | 'status'>,
  runStatus?: string | null,
): boolean {
  return resolveEpochBadgeStatus(entry, runStatus) === 'RUNNING';
}
