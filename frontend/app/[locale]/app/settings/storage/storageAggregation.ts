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

/** Sum quotas across workspaces. If ANY workspace is unlimited, the total is unlimited. */
export function aggregateStorageQuotas(quotas: StorageQuota[]): StorageQuota {
  const sum = (sel: (q: StorageQuota) => number) => quotas.reduce((acc, q) => acc + (sel(q) || 0), 0);
  const anyUnlimited = quotas.some((q) => q.unlimited === true);
  const usedBytes = sum((q) => q.usedBytes);
  const maxBytes = anyUnlimited ? 0 : sum((q) => q.maxBytes);
  return {
    tenantId: 'all-workspaces',
    usedBytes,
    maxBytes,
    softLimitBytes: anyUnlimited ? 0 : sum((q) => q.softLimitBytes),
    hardLimitBytes: anyUnlimited ? 0 : sum((q) => q.hardLimitBytes),
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
