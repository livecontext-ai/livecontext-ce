/**
 * Client-side "All workspaces" aggregation for the Storage page.
 *
 * Storage is genuinely per-workspace (each workspace has its own quota / usage)
 * and the backend exposes no cross-workspace aggregate. So the "All workspaces"
 * view sums each workspace's quota / stats / breakdown / trend client-side - the
 * same "total across everything" semantic the Quota page gives for the wallet.
 *
 * All functions are pure (no fetching) so they can be unit-tested directly.
 */
import type { StorageQuota, TenantStats, StorageBreakdown, StorageHistoryPoint } from '@/lib/api';

/**
 * Total what the account STORES, against what its plan ALLOWS once.
 *
 * <p>Usage adds up across workspaces; the allowance does not. The plan grants its
 * included_storage_bytes to the customer, and the backend now enforces that as a single pool
 * shared by every workspace the account owns, blocking all of them once it is full. Summing the
 * limits here was how the card came to read "300 GB" to a TEAM customer on a 100 GB plan, with
 * three workspaces each carrying a copy of the same allowance.
 *
 * <p>The ceiling is the LARGEST workspace allowance rather than the first: every row of one
 * account carries the same figure (PlanStorageQuotaSyncer writes them together), so they agree
 * in practice, and taking the max means a single stale row cannot make the card understate what
 * the customer is actually allowed.
 */
export function aggregateStorageQuotas(quotas: StorageQuota[]): StorageQuota {
  const sum = (sel: (q: StorageQuota) => number) => quotas.reduce((acc, q) => acc + (sel(q) || 0), 0);
  const largest = (sel: (q: StorageQuota) => number) =>
    quotas.reduce((acc, q) => Math.max(acc, sel(q) || 0), 0);
  const anyUnlimited = quotas.some((q) => q.unlimited === true);
  const usedBytes = sum((q) => q.usedBytes);
  const maxBytes = anyUnlimited ? 0 : largest((q) => q.maxBytes);
  return {
    tenantId: 'all-workspaces',
    usedBytes,
    maxBytes,
    softLimitBytes: anyUnlimited ? 0 : largest((q) => q.softLimitBytes),
    hardLimitBytes: anyUnlimited ? 0 : largest((q) => q.hardLimitBytes),
    availableBytes: anyUnlimited ? 0 : Math.max(0, maxBytes - usedBytes),
    usagePercentage: maxBytes > 0 ? Math.min(100, (usedBytes / maxBytes) * 100) : 0,
    status: 'OK',
    unlimited: anyUnlimited,
  };
}

/** Sum the per-resource counts across workspaces. */
export function aggregateTenantStats(list: TenantStats[]): TenantStats {
  const sum = (sel: (s: TenantStats) => number) => list.reduce((acc, s) => acc + (sel(s) || 0), 0);
  return {
    tenantId: 'all-workspaces',
    workflowCount: sum((s) => s.workflowCount),
    interfaceCount: sum((s) => s.interfaceCount),
    tableCount: sum((s) => s.tableCount),
    agentCount: sum((s) => s.agentCount),
  };
}

/** Merge per-category breakdowns across workspaces (sum bytes + item counts). */
export function aggregateBreakdowns(lists: StorageBreakdown[][]): StorageBreakdown[] {
  const byCategory = new Map<string, StorageBreakdown>();
  for (const list of lists) {
    for (const b of list) {
      const existing = byCategory.get(b.category);
      if (existing) {
        existing.usedBytes += b.usedBytes;
        existing.itemCount += b.itemCount;
        if (b.calculatedAt > existing.calculatedAt) existing.calculatedAt = b.calculatedAt;
      } else {
        byCategory.set(b.category, { ...b });
      }
    }
  }
  return Array.from(byCategory.values());
}

/** Merge daily history across workspaces by (snapshotDate, category) for the "All" trend. */
export function mergeStorageHistories(lists: StorageHistoryPoint[][]): StorageHistoryPoint[] {
  const byKey = new Map<string, StorageHistoryPoint>();
  for (const list of lists) {
    for (const p of list) {
      const key = `${p.snapshotDate}|${p.category}`;
      const existing = byKey.get(key);
      if (existing) {
        existing.usedBytes += p.usedBytes;
        existing.itemCount += p.itemCount;
      } else {
        byKey.set(key, { ...p });
      }
    }
  }
  return Array.from(byKey.values());
}

/** Everything the "All workspaces" view needs from ONE workspace. */
export interface WorkspaceStoragePart {
  quota: StorageQuota;
  stats: TenantStats | null;
  breakdown: StorageBreakdown[];
}

/** What the aggregate view renders, plus how many workspaces it had to leave out. */
export interface AggregatedWorkspaces {
  quota: StorageQuota | null;
  stats: TenantStats | null;
  breakdown: StorageBreakdown[];
  /** Workspaces excluded because their bytes could not be read. */
  unreadable: number;
}

/**
 * Decide one workspace's contribution from its three settled requests.
 *
 * <p>QUOTA AND BREAKDOWN ARE THE PAIR THAT MUST HOLD: the gauge comes from one and the categories
 * from the other, so a workspace that supplied only one of them would put bytes on screen that
 * nothing underneath accounts for. Either both, or the workspace sits out.
 *
 * <p>STATS ARE NOT BYTES. `workflowCount` and friends are shown in a separate card, so losing them
 * must not remove a workspace's storage from the total. A failed stats call degrades to null stats
 * and the workspace still counts, which is also what {@link aggregateWorkspaces} tolerates: the
 * rule lives in one place and both layers obey the same one.
 */
export function workspacePartFromSettled(
  quota: PromiseSettledResult<StorageQuota | null>,
  stats: PromiseSettledResult<TenantStats | null>,
  breakdown: PromiseSettledResult<StorageBreakdown[] | null>,
): WorkspaceStoragePart | null {
  if (quota.status !== 'fulfilled' || !quota.value) return null;
  if (breakdown.status !== 'fulfilled' || !Array.isArray(breakdown.value)) return null;
  return {
    quota: quota.value,
    stats: stats.status === 'fulfilled' ? stats.value ?? null : null,
    breakdown: breakdown.value,
  };
}

/**
 * Combine the per-workspace results into the aggregate view.
 *
 * <p>A workspace is an ATOMIC contribution: a `null` entry (its quota, stats or breakdown could
 * not be read) is left out of BOTH the gauge and the categories, and counted in `unreadable`.
 * Summing a workspace's quota while dropping its breakdown would put bytes in the gauge that no
 * category accounts for, which is exactly the gauge-vs-categories mismatch this page exists to
 * report correctly.
 *
 * <p>Returns a null quota when nothing could be read at all, so the caller can show an error
 * rather than an empty aggregate (a zero `maxBytes` would otherwise read as "unlimited").
 */
export function aggregateWorkspaces(parts: Array<WorkspaceStoragePart | null>): AggregatedWorkspaces {
  const ok = parts.filter((p): p is WorkspaceStoragePart => !!p);
  const unreadable = parts.length - ok.length;
  if (ok.length === 0) {
    // The caller shows an error instead of a total here, but the count is still the truth about
    // what happened: a field that reports "none unreadable" after reading none of them is the kind
    // of small lie that gets believed later.
    return { quota: null, stats: null, breakdown: [], unreadable };
  }
  return {
    quota: aggregateStorageQuotas(ok.map((p) => p.quota)),
    stats: aggregateTenantStats(ok.map((p) => p.stats).filter((s): s is TenantStats => !!s)),
    breakdown: aggregateBreakdowns(ok.map((p) => p.breakdown)),
    unreadable,
  };
}
