import type { DailyUsageEntry, ModelUsageEntry } from '@/lib/api';

/**
 * The figures the Usage Analytics panel shows, computed from the analytics payload.
 *
 * <p>Pure functions, kept out of the component so every number on the panel is testable without
 * rendering a chart. Amounts stay in the ledger's unit (credits); the panel converts for display.
 */

export interface UsageTotals {
  credits: number;
  calls: number;
  tokens: number;
}

export interface UsageShare extends UsageTotals {
  /** Share of the period's credits, 0..1. */
  share: number;
}

export interface TypeUsage extends UsageShare {
  sourceType: string;
}

export interface ModelUsage extends UsageShare {
  provider: string | null;
  model: string | null;
}

type Included = (sourceType: string) => boolean;
const everything: Included = () => true;

const num = (v: unknown): number => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};

function sum(rows: { sourceType: string; credits: number; count: number; tokens: number }[], include: Included): UsageTotals {
  return rows.reduce<UsageTotals>((acc, r) => {
    if (!include(r.sourceType)) return acc;
    acc.credits += num(r.credits);
    acc.calls += num(r.count);
    acc.tokens += num(r.tokens);
    return acc;
  }, { credits: 0, calls: 0, tokens: 0 });
}

/** Totals of the period shown, from the daily rows the chart draws. */
export function periodTotals(daily: DailyUsageEntry[] | undefined, include: Included = everything): UsageTotals {
  return sum(daily ?? [], include);
}

/**
 * Totals of the period before, or null when the server sent no comparison (an older backend):
 * "no data" must not read as "you spent nothing", which would show every figure as a rise.
 */
export function previousTotals(previous: ModelUsageEntry[] | undefined, include: Included = everything): UsageTotals | null {
  if (!previous) return null;
  return sum(previous, include);
}

/**
 * Relative change from `before` to `now`, or null when there is no base to compare to (nothing
 * spent before): a rise from zero has no percentage, and inventing one ("+100%") would lie.
 */
export function relativeChange(now: number, before: number | null | undefined): number | null {
  if (before == null || before <= 0) return null;
  return (now - before) / before;
}

/** Spend per type, largest first. */
export function usageByType(daily: DailyUsageEntry[] | undefined, include: Included = everything): TypeUsage[] {
  const byType = new Map<string, TypeUsage>();
  for (const r of daily ?? []) {
    if (!include(r.sourceType)) continue;
    const row = byType.get(r.sourceType) ?? { sourceType: r.sourceType, credits: 0, calls: 0, tokens: 0, share: 0 };
    row.credits += num(r.credits);
    row.calls += num(r.count);
    row.tokens += num(r.tokens);
    byType.set(r.sourceType, row);
  }
  return withShares([...byType.values()]);
}

/**
 * Spend per model, largest first, across the types it was billed under. The rows with no model
 * (platform API calls, web tools) are one row with a null model, so they are not dropped from a
 * table whose shares would then add up to less than the total.
 */
export function usageByModel(rows: ModelUsageEntry[] | undefined, include: Included = everything): ModelUsage[] {
  const byModel = new Map<string, ModelUsage>();
  for (const r of rows ?? []) {
    if (!include(r.sourceType)) continue;
    const provider = r.model ? r.provider ?? null : null;
    const model = r.model ?? null;
    const key = `${provider ?? ''}\u0000${model ?? ''}`;
    const row = byModel.get(key) ?? { provider, model, credits: 0, calls: 0, tokens: 0, share: 0 };
    row.credits += num(r.credits);
    row.calls += num(r.count);
    row.tokens += num(r.tokens);
    byModel.set(key, row);
  }
  return withShares([...byModel.values()]);
}

function withShares<T extends UsageShare>(rows: T[]): T[] {
  const total = rows.reduce((s, r) => s + r.credits, 0);
  for (const r of rows) r.share = total > 0 ? r.credits / total : 0;
  return rows.sort((a, b) => b.credits - a.credits);
}

/**
 * The first `limit` rows, with the rest folded into one line so the table stays short and its
 * shares still add up to the whole. `rest` is null when nothing was folded.
 */
export function topWithRest<T extends UsageShare>(rows: T[], limit: number): { top: T[]; rest: (UsageShare & { count: number }) | null } {
  if (rows.length <= limit) return { top: rows, rest: null };
  const folded = rows.slice(limit);
  const rest = folded.reduce(
    (acc, r) => ({
      credits: acc.credits + r.credits,
      calls: acc.calls + r.calls,
      tokens: acc.tokens + r.tokens,
      share: acc.share + r.share,
      count: acc.count + 1,
    }),
    { credits: 0, calls: 0, tokens: 0, share: 0, count: 0 },
  );
  return { top: rows.slice(0, limit), rest };
}

/** The day that cost the most, or null when nothing was spent. */
export function peakDay(daily: DailyUsageEntry[] | undefined, include: Included = everything): { date: string; credits: number } | null {
  const byDay = new Map<string, number>();
  for (const r of daily ?? []) {
    if (!include(r.sourceType)) continue;
    byDay.set(r.date, (byDay.get(r.date) ?? 0) + num(r.credits));
  }
  let best: { date: string; credits: number } | null = null;
  for (const [date, credits] of byDay) {
    if (credits > 0 && (!best || credits > best.credits)) best = { date, credits };
  }
  return best;
}

/**
 * How many days the balance lasts at the average daily spend of the period, or null when that
 * cannot be said: no balance known, nothing left, or nothing spent (the balance then lasts
 * indefinitely, which is not a number of days).
 */
export function runwayDays(balance: number | null | undefined, avgDailyCredits: number): number | null {
  if (balance == null || balance <= 0 || avgDailyCredits <= 0) return null;
  return Math.floor(balance / avgDailyCredits);
}
