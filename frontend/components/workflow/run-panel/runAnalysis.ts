/**
 * Pure derivations behind the run Analysis tab: KPIs, the nodes x epochs grid, the insights
 * sentence and the two-epoch comparison. React-free so every rule is unit-testable.
 *
 * Every figure is read from the `/runs/{runId}/analysis` payload, whose epoch timing and outcome
 * are the very rows the Run tab's epoch list shows, so the two tabs can never disagree.
 */

import type { RunAnalysisEpoch, RunAnalysisNodeCell } from '@/lib/api/orchestrator/types';
import { resolveEpochBadgeStatus } from './runFormatting';

/** What one grid cell says about one node in one epoch. */
export type CellStatus = 'ok' | 'failed' | 'partial' | 'skipped' | 'running' | 'waiting' | 'none';

/** Maps the aggregated step vocabulary (completed, error, partial_success...) to a cell status. */
export function cellStatus(cell: RunAnalysisNodeCell | undefined): CellStatus {
  if (!cell) return 'none';
  switch ((cell.status || '').toLowerCase()) {
    case 'completed':
    case 'success':
      return 'ok';
    case 'error':
    case 'failed':
      return 'failed';
    case 'partial_success':
      return 'partial';
    case 'skipped':
      return 'skipped';
    case 'awaiting_signal':
      return 'waiting';
    case 'running':
      return 'running';
    // "pending" = the node has rows but none was processed yet: it has not run.
    default:
      return 'none';
  }
}

/**
 * The epoch's badge status, upper-case, or null: the same rule as the Run tab's epoch list, which
 * needs the RUN status to tell an open epoch that is executing from one a stopped run abandoned.
 */
export function epochOutcome(epoch: RunAnalysisEpoch, runStatus?: string | null): string | null {
  return resolveEpochBadgeStatus(epoch, runStatus);
}

/** An epoch whose outcome is final and binary (the only ones a success rate may count). */
function isSettled(outcome: string | null): outcome is 'COMPLETED' | 'FAILED' {
  return outcome === 'COMPLETED' || outcome === 'FAILED';
}

export interface AnalysisKpis {
  /** Epochs in the window. */
  shown: number;
  /** Epochs the run has in total. */
  total: number;
  completed: number;
  failed: number;
  /** completed / (completed + failed), 0..100 rounded; null when no epoch has settled. */
  successRate: number | null;
  /** Mean work duration of the settled epochs that carry one; null when none does. */
  avgDurationMs: number | null;
  maxDurationMs: number | null;
  /** Sum of the recorded costs; null when no epoch of the window recorded any. */
  totalCost: number | null;
  /** totalCost over the epochs that recorded a cost. */
  avgCost: number | null;
}

export function computeKpis(epochs: RunAnalysisEpoch[], totalEpochs: number, runStatus?: string | null): AnalysisKpis {
  let completed = 0;
  let failed = 0;
  const durations: number[] = [];
  const costs: number[] = [];
  for (const epoch of epochs) {
    const outcome = epochOutcome(epoch, runStatus);
    if (outcome === 'COMPLETED') completed++;
    if (outcome === 'FAILED') failed++;
    // A live epoch's measured window is still growing: averaging it in would drag the mean down.
    if (isSettled(outcome) && epoch.workDurationMs != null) durations.push(Math.max(0, epoch.workDurationMs));
    if (epoch.costCredits != null) costs.push(epoch.costCredits);
  }
  const settled = completed + failed;
  const totalCost = costs.length ? costs.reduce((a, b) => a + b, 0) : null;
  return {
    shown: epochs.length,
    total: totalEpochs,
    completed,
    failed,
    successRate: settled ? Math.round((completed / settled) * 100) : null,
    avgDurationMs: durations.length ? durations.reduce((a, b) => a + b, 0) / durations.length : null,
    maxDurationMs: durations.length ? Math.max(...durations) : null,
    totalCost,
    avgCost: totalCost != null ? totalCost / costs.length : null,
  };
}

/** One row of the grid: a node, and its cell in each epoch that ran it. */
export interface NodeRow {
  alias: string;
  cells: Map<number, RunAnalysisNodeCell>;
  /**
   * Epochs where the node failed: outright, or partly in an epoch that FAILED (the node's failed
   * items are what failed the epoch, e.g. a split with 1 of 10 items down).
   */
  failures: number;
  /** Epochs where some of its items failed but the epoch still completed (continue on failure). */
  partials: number;
  /** Mean elapsed time over the epochs where the node succeeded; null when none carries one. */
  avgElapsedMs: number | null;
  /** Longest elapsed time of the node in the window, the scale of its own duration colours. */
  maxElapsedMs: number;
}

/**
 * Every node seen in the window, in first-seen order (callers re-sort by DAG order). `runStatus`
 * decides, like everywhere else, what an open epoch's outcome is.
 */
export function buildNodeRows(epochs: RunAnalysisEpoch[], runStatus?: string | null): NodeRow[] {
  const failedEpochs = new Set(epochs.filter(e => epochOutcome(e, runStatus) === 'FAILED').map(e => e.epoch));
  const rows = new Map<string, NodeRow>();
  for (const epoch of epochs) {
    for (const cell of epoch.nodes) {
      let row = rows.get(cell.alias);
      if (!row) {
        row = { alias: cell.alias, cells: new Map(), failures: 0, partials: 0, avgElapsedMs: null, maxElapsedMs: 0 };
        rows.set(cell.alias, row);
      }
      row.cells.set(epoch.epoch, cell);
    }
  }
  for (const row of rows.values()) {
    const elapsed: number[] = [];
    for (const [epoch, cell] of row.cells) {
      const status = cellStatus(cell);
      if (status === 'failed' || (status === 'partial' && failedEpochs.has(epoch))) row.failures++;
      else if (status === 'partial') row.partials++;
      // Only successes are coloured by duration, so only they set the scale: one 5-minute timeout
      // would otherwise push every success of the row into the "fast" bucket.
      if (status === 'ok' && cell.elapsedMs != null) {
        row.maxElapsedMs = Math.max(row.maxElapsedMs, cell.elapsedMs);
        elapsed.push(cell.elapsedMs);
      }
    }
    row.avgElapsedMs = elapsed.length ? elapsed.reduce((a, b) => a + b, 0) / elapsed.length : null;
  }
  return [...rows.values()];
}

/**
 * Duration colour step of a cell, 0 (fastest) to 4 (slowest), relative to the node's OWN longest
 * run in the window: a 30 s agent and a 20 ms decision are each judged against themselves, or
 * every cell but the agent's would read "fast". Null when the cell has no elapsed time.
 */
export function heatLevel(elapsedMs: number | null | undefined, rowMaxMs: number): 0 | 1 | 2 | 3 | 4 | null {
  if (elapsedMs == null) return null;
  if (rowMaxMs <= 0) return 0;
  const ratio = elapsedMs / rowMaxMs;
  if (ratio < 0.2) return 0;
  if (ratio < 0.4) return 1;
  if (ratio < 0.6) return 2;
  if (ratio < 0.8) return 3;
  return 4;
}

function median(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export type Insight =
  | { kind: 'failing'; alias: string; count: number; total: number; lastError: string | null }
  | { kind: 'epochFailures'; count: number }
  | { kind: 'slower'; alias: string; sinceEpoch: number; beforeMs: number; afterMs: number };

/** Minimum successful samples on EACH side before a slowdown is claimed. */
export const SLOWDOWN_MIN_SAMPLES = 3;
/** A node must be at least this much slower (median over median) to be reported. */
export const SLOWDOWN_RATIO = 1.5;
/** ...and slower by at least this much in absolute terms, so 20 ms to 40 ms is not news. */
export const SLOWDOWN_MIN_DELTA_MS = 1000;

/**
 * At most three plain-language findings: the node that failed most often (with its latest error),
 * the epochs that failed with NO failing node row (a trigger or engine failure, whose reason only
 * the logs hold), and the node whose successful runs slowed down the most between the older and
 * the newer half of the window. Medians, not means, so one outlier does not manufacture a trend.
 * An empty answer therefore means no failure at all.
 */
export function computeInsights(epochs: RunAnalysisEpoch[], rows: NodeRow[], runStatus?: string | null): Insight[] {
  const insights: Insight[] = [];
  const ordered = [...epochs].sort((a, b) => a.epoch - b.epoch);

  const worst = [...rows].filter(r => r.failures > 0).sort((a, b) => b.failures - a.failures)[0];
  if (worst) {
    let lastError: string | null = null;
    for (let i = ordered.length - 1; i >= 0 && lastError == null; i--) {
      const cell = worst.cells.get(ordered[i].epoch);
      if (cell && cellStatus(cell) !== 'ok' && cell.errorMessage) lastError = cell.errorMessage;
    }
    // Out of the epochs the node RAN in: "3 of 60" would understate a node that runs in 4.
    const ran = [...worst.cells.values()].filter(c => { const s = cellStatus(c); return s !== 'skipped' && s !== 'none'; }).length;
    insights.push({ kind: 'failing', alias: worst.alias, count: worst.failures, total: ran, lastError });
  }

  const unexplained = ordered.filter(e => epochOutcome(e, runStatus) === 'FAILED'
    && !e.nodes.some(c => { const s = cellStatus(c); return s === 'failed' || s === 'partial'; })).length;
  if (unexplained > 0) insights.push({ kind: 'epochFailures', count: unexplained });

  let slowest: Extract<Insight, { kind: 'slower' }> | null = null;
  for (const row of rows) {
    const samples = ordered
      .map(e => ({ epoch: e.epoch, cell: row.cells.get(e.epoch) }))
      .filter((s): s is { epoch: number; cell: RunAnalysisNodeCell } =>
        !!s.cell && cellStatus(s.cell) === 'ok' && s.cell.elapsedMs != null);
    if (samples.length < SLOWDOWN_MIN_SAMPLES * 2) continue;
    const half = Math.floor(samples.length / 2);
    const before = median(samples.slice(0, half).map(s => s.cell.elapsedMs!));
    const after = median(samples.slice(half).map(s => s.cell.elapsedMs!));
    if (after < before * SLOWDOWN_RATIO || after - before < SLOWDOWN_MIN_DELTA_MS) continue;
    if (!slowest || after / Math.max(before, 1) > slowest.afterMs / Math.max(slowest.beforeMs, 1)) {
      slowest = { kind: 'slower', alias: row.alias, sinceEpoch: samples[half].epoch, beforeMs: before, afterMs: after };
    }
  }
  if (slowest) insights.push(slowest);
  return insights;
}

/**
 * The comparison opened by default: the most recent FAILED epoch against the last COMPLETED one
 * before it (the question a user asks first: "what changed?"). Without a failure, the two most
 * recent settled epochs. Null when fewer than two epochs exist.
 */
export function defaultComparison(
  epochs: RunAnalysisEpoch[],
  runStatus?: string | null,
): { reference: number; target: number } | null {
  if (epochs.length < 2) return null;
  const ordered = [...epochs].sort((a, b) => a.epoch - b.epoch);
  const lastFailed = [...ordered].reverse().find(e => epochOutcome(e, runStatus) === 'FAILED');
  const target = lastFailed ?? [...ordered].reverse().find(e => isSettled(epochOutcome(e, runStatus))) ?? ordered[ordered.length - 1];
  const reference = referenceFor(ordered, target.epoch, runStatus);
  return reference == null ? null : { reference, target: target.epoch };
}

/**
 * The epoch to compare `target` against: the last COMPLETED epoch before it, else the epoch right
 * before it, else (target is the oldest) the one right after it.
 */
export function referenceFor(epochs: RunAnalysisEpoch[], target: number, runStatus?: string | null): number | null {
  const ordered = [...epochs].sort((a, b) => a.epoch - b.epoch);
  const before = ordered.filter(e => e.epoch < target);
  const lastOk = [...before].reverse().find(e => epochOutcome(e, runStatus) === 'COMPLETED');
  if (lastOk) return lastOk.epoch;
  if (before.length) return before[before.length - 1].epoch;
  return ordered.find(e => e.epoch > target)?.epoch ?? null;
}

export interface CompareRow {
  alias: string;
  reference?: RunAnalysisNodeCell;
  target?: RunAnalysisNodeCell;
  /** The two epochs disagree on this node: another status, or a much slower success. */
  differs: boolean;
}

/** One row per node that ran in either epoch, in `aliasOrder` order (unknown aliases last). */
export function compareEpochs(
  reference: RunAnalysisEpoch,
  target: RunAnalysisEpoch,
  aliasOrder: string[],
): CompareRow[] {
  const refCells = new Map(reference.nodes.map(c => [c.alias, c]));
  const targetCells = new Map(target.nodes.map(c => [c.alias, c]));
  const aliases = [...new Set([...refCells.keys(), ...targetCells.keys()])];
  const rank = new Map(aliasOrder.map((a, i) => [a, i]));
  aliases.sort((a, b) => (rank.get(a) ?? Number.MAX_SAFE_INTEGER) - (rank.get(b) ?? Number.MAX_SAFE_INTEGER));
  return aliases.map(alias => {
    const a = refCells.get(alias);
    const b = targetCells.get(alias);
    const sa = cellStatus(a);
    const sb = cellStatus(b);
    let differs = sa !== sb;
    if (!differs && sa === 'ok' && a?.elapsedMs != null && b?.elapsedMs != null) {
      const slow = Math.max(a.elapsedMs, b.elapsedMs);
      const fast = Math.min(a.elapsedMs, b.elapsedMs);
      differs = slow >= fast * SLOWDOWN_RATIO && slow - fast >= SLOWDOWN_MIN_DELTA_MS;
    }
    return { alias, reference: a, target: b, differs };
  });
}
