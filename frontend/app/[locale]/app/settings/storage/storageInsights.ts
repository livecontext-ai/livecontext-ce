import type { StorageBreakdown, StorageHistoryPoint } from '@/lib/api';

/**
 * The figures the Storage page derives from what it already loads (the category breakdown and the
 * daily snapshots). Pure, so every number on the page is testable without rendering it.
 */

export interface CategoryRow {
  category: string;
  usedBytes: number;
  itemCount: number;
  /** Share of everything the breakdown counts, 0..1. */
  share: number;
  /** Average size of one item, or null when the category reports no items. */
  avgItemBytes: number | null;
}

/** Categories that hold something, largest first, with their share and average item size. */
export function categoryRows(breakdown: StorageBreakdown[] | null | undefined): CategoryRow[] {
  const rows = (Array.isArray(breakdown) ? breakdown : []).filter((b) => b.usedBytes > 0);
  const total = rows.reduce((s, b) => s + b.usedBytes, 0);
  return rows
    .map((b) => ({
      category: b.category,
      usedBytes: b.usedBytes,
      itemCount: b.itemCount ?? 0,
      share: total > 0 ? b.usedBytes / total : 0,
      avgItemBytes: b.itemCount > 0 ? b.usedBytes / b.itemCount : null,
    }))
    .sort((a, b) => b.usedBytes - a.usedBytes);
}

export interface StorageGrowth {
  firstDate: string;
  lastDate: string;
  startBytes: number;
  endBytes: number;
  growthBytes: number;
  /** Average change per day between the first and the last snapshot (negative when it shrank). */
  perDayBytes: number;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Growth between the first and the last daily snapshot of the period, or null with fewer than two
 * snapshot days: one point is a level, not a trend, and a per-day figure from it would be invented.
 */
export function storageGrowth(history: StorageHistoryPoint[] | null | undefined): StorageGrowth | null {
  const totals = new Map<string, number>();
  for (const p of history ?? []) {
    totals.set(p.snapshotDate, (totals.get(p.snapshotDate) ?? 0) + (Number(p.usedBytes) || 0));
  }
  const dates = [...totals.keys()].sort();
  if (dates.length < 2) return null;
  const firstDate = dates[0];
  const lastDate = dates[dates.length - 1];
  const days = Math.round((Date.parse(lastDate) - Date.parse(firstDate)) / DAY_MS);
  if (!(days > 0)) return null;
  const startBytes = totals.get(firstDate)!;
  const endBytes = totals.get(lastDate)!;
  return {
    firstDate,
    lastDate,
    startBytes,
    endBytes,
    growthBytes: endBytes - startBytes,
    perDayBytes: (endBytes - startBytes) / days,
  };
}

/**
 * Days until the allowance is full at the given daily growth, or null when that cannot be said:
 * no limit, storage not growing (it will never fill at this pace), or already full (the page says
 * so with its own status instead of "0 days").
 */
export function daysUntilFull(usedBytes: number, limitBytes: number | null | undefined, perDayBytes: number): number | null {
  if (!limitBytes || limitBytes <= 0 || perDayBytes <= 0) return null;
  const remaining = limitBytes - usedBytes;
  if (remaining <= 0) return null;
  return Math.floor(remaining / perDayBytes);
}
