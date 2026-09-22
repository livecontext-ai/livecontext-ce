import { describe, it, expect } from 'vitest';
import {
  aggregateStorageQuotas,
  aggregateTenantStats,
  aggregateBreakdowns,
  aggregateWorkspaces,
  workspacePartFromSettled,
  mergeStorageHistories,
} from '../storageAggregation';
import type { WorkspaceStoragePart } from '../storageAggregation';
import type { StorageQuota, TenantStats, StorageBreakdown, StorageHistoryPoint } from '@/lib/api';

const quota = (over: Partial<StorageQuota>): StorageQuota => ({
  tenantId: 't', usedBytes: 0, maxBytes: 0, softLimitBytes: 0, hardLimitBytes: 0,
  availableBytes: 0, usagePercentage: 0, status: 'OK', unlimited: false, ...over,
});

describe('storageAggregation', () => {
  describe('aggregateStorageQuotas', () => {
    it('sums what is USED but keeps the allowance single: the plan is a pool, not a per-workspace grant', () => {
      // Each workspace row carries a copy of the same plan allowance. Adding them up is what
      // showed "300 GB" to a TEAM customer on a 100 GB plan with three workspaces, and the
      // backend now enforces the pool, so the card has to read the pool too.
      const result = aggregateStorageQuotas([
        quota({ usedBytes: 30, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
        quota({ usedBytes: 20, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
      ]);
      expect(result.usedBytes).toBe(50);
      expect(result.maxBytes).toBe(100);
      expect(result.softLimitBytes).toBe(80);
      expect(result.hardLimitBytes).toBe(100);
      expect(result.availableBytes).toBe(50);
      expect(result.usagePercentage).toBeCloseTo(50); // 50 of the pool's 100
      expect(result.unlimited).toBe(false);
    });

    it('takes the LARGEST allowance, so one stale row cannot understate what the plan allows', () => {
      // The rows are written together and normally agree; if one lags behind a plan upgrade,
      // erring high keeps the card from telling a paying customer they have less than they do.
      const result = aggregateStorageQuotas([
        quota({ usedBytes: 10, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
        quota({ usedBytes: 5, maxBytes: 20, softLimitBytes: 16, hardLimitBytes: 20 }),
      ]);
      expect(result.maxBytes).toBe(100);
      expect(result.softLimitBytes).toBe(80);
      expect(result.hardLimitBytes).toBe(100);
      expect(result.usedBytes).toBe(15);
    });

    it('a single workspace is unchanged: the pool of a one-workspace account is that workspace', () => {
      const result = aggregateStorageQuotas([
        quota({ usedBytes: 30, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
      ]);
      expect(result.maxBytes).toBe(100);
      expect(result.usedBytes).toBe(30);
      expect(result.usagePercentage).toBeCloseTo(30);
    });

    it('treats the total as UNLIMITED when any workspace is unlimited (cap sum is meaningless)', () => {
      const result = aggregateStorageQuotas([
        quota({ usedBytes: 40, maxBytes: 100 }),
        quota({ usedBytes: 5, maxBytes: 0, unlimited: true }),
      ]);
      // Used still sums (your real footprint), but the cap collapses to unlimited.
      expect(result.usedBytes).toBe(45);
      expect(result.unlimited).toBe(true);
      expect(result.maxBytes).toBe(0);
      expect(result.usagePercentage).toBe(0);
    });

    it('caps the percentage at 100 when over the summed limit', () => {
      const result = aggregateStorageQuotas([quota({ usedBytes: 300, maxBytes: 100 })]);
      expect(result.usagePercentage).toBe(100);
      expect(result.availableBytes).toBe(0);
    });
  });

  describe('aggregateTenantStats', () => {
    it('sums every per-resource count', () => {
      const s = (over: Partial<TenantStats>): TenantStats => ({
        tenantId: 't', workflowCount: 0, interfaceCount: 0, tableCount: 0, agentCount: 0, ...over,
      });
      const result = aggregateTenantStats([
        s({ workflowCount: 2, interfaceCount: 1, tableCount: 3, agentCount: 0 }),
        s({ workflowCount: 5, interfaceCount: 0, tableCount: 1, agentCount: 4 }),
      ]);
      expect(result).toMatchObject({ workflowCount: 7, interfaceCount: 1, tableCount: 4, agentCount: 4 });
    });
  });

  describe('aggregateBreakdowns', () => {
    it('merges by category, summing bytes + item counts and keeping the latest calculatedAt', () => {
      const a: StorageBreakdown[] = [
        { category: 'FILES', usedBytes: 100, itemCount: 2, calculatedAt: '2026-06-01T00:00:00Z' },
        { category: 'AGENTS', usedBytes: 10, itemCount: 1, calculatedAt: '2026-06-01T00:00:00Z' },
      ];
      const b: StorageBreakdown[] = [
        { category: 'FILES', usedBytes: 50, itemCount: 3, calculatedAt: '2026-06-05T00:00:00Z' },
      ];
      const result = aggregateBreakdowns([a, b]);
      const files = result.find((r) => r.category === 'FILES')!;
      expect(files.usedBytes).toBe(150);
      expect(files.itemCount).toBe(5);
      expect(files.calculatedAt).toBe('2026-06-05T00:00:00Z'); // latest
      expect(result.find((r) => r.category === 'AGENTS')!.usedBytes).toBe(10);
    });

    it('does not mutate the input breakdown objects', () => {
      const a: StorageBreakdown[] = [{ category: 'FILES', usedBytes: 100, itemCount: 2, calculatedAt: 'x' }];
      const b: StorageBreakdown[] = [{ category: 'FILES', usedBytes: 50, itemCount: 1, calculatedAt: 'x' }];
      aggregateBreakdowns([a, b]);
      expect(a[0].usedBytes).toBe(100); // first source untouched
    });
  });

  describe('aggregateWorkspaces', () => {
    const part = (usedBytes: number, breakdownBytes: number): WorkspaceStoragePart => ({
      quota: quota({ usedBytes, maxBytes: 1000, softLimitBytes: 800, hardLimitBytes: 1000 }),
      stats: { tenantId: 't', workflowCount: 1, interfaceCount: 0, tableCount: 0, agentCount: 0 },
      breakdown: [{ category: 'FILES', usedBytes: breakdownBytes, itemCount: 1, calculatedAt: 'x' }],
    });

    it('sums the workspaces that loaded and reports none unreadable', () => {
      const result = aggregateWorkspaces([part(30, 30), part(20, 20)]);
      expect(result.quota!.usedBytes).toBe(50);
      expect(result.breakdown.find((b) => b.category === 'FILES')!.usedBytes).toBe(50);
      expect(result.unreadable).toBe(0);
    });

    it('the gauge equals the categories: a workspace that failed contributes to NEITHER', () => {
      // The regression this guards: the three calls used to be gathered independently, so a
      // workspace whose quota answered but whose breakdown did not added its bytes to the gauge
      // and nothing to the bar underneath. That is the same gauge-vs-categories mismatch the
      // page exists to report, reproduced by the page itself.
      const result = aggregateWorkspaces([part(30, 30), null, part(20, 20)]);
      const categoryTotal = result.breakdown.reduce((sum, b) => sum + b.usedBytes, 0);
      expect(result.quota!.usedBytes).toBe(categoryTotal);
      expect(result.unreadable).toBe(1);
    });

    it('counts every workspace it had to leave out', () => {
      const result = aggregateWorkspaces([part(30, 30), null, null]);
      expect(result.unreadable).toBe(2);
      expect(result.quota!.usedBytes).toBe(30);
    });

    it('returns a null quota when nothing could be read, so the caller shows an error', () => {
      // Not an empty aggregate: maxBytes 0 is how this page spells "unlimited", so summing
      // nothing would paint an infinity symbol over a workspace that simply failed to load.
      const result = aggregateWorkspaces([null, null]);
      expect(result.quota).toBeNull();
      expect(result.breakdown).toEqual([]);
      expect(result.unreadable).toBe(2); // the count stays true even when there is no total to show
    });

    it('tolerates a workspace whose stats are missing while its bytes are known', () => {
      const withoutStats: WorkspaceStoragePart = { ...part(10, 10), stats: null };
      const result = aggregateWorkspaces([part(30, 30), withoutStats]);
      expect(result.quota!.usedBytes).toBe(40);
      expect(result.stats!.workflowCount).toBe(1);
      expect(result.unreadable).toBe(0);
    });
  });

  describe('workspacePartFromSettled', () => {
    const q: StorageQuota = quota({ usedBytes: 10, maxBytes: 100 });
    const st: TenantStats = { tenantId: 't', workflowCount: 2, interfaceCount: 0, tableCount: 0, agentCount: 0 };
    const bd: StorageBreakdown[] = [{ category: 'FILES', usedBytes: 10, itemCount: 1, calculatedAt: 'x' }];
    const ok = <T,>(value: T): PromiseSettledResult<T> => ({ status: 'fulfilled', value });
    const ko = <T,>(): PromiseSettledResult<T> => ({ status: 'rejected', reason: new Error('boom') });

    it('keeps the workspace when all three answered', () => {
      expect(workspacePartFromSettled(ok(q), ok(st), ok(bd))).toEqual({ quota: q, stats: st, breakdown: bd });
    });

    it('drops the workspace when the breakdown failed but the quota answered', () => {
      // This is the pair that must hold: the gauge comes from the quota and the categories from
      // the breakdown, so keeping one without the other puts bytes on screen that the bar below
      // does not account for. That is the very mismatch this page exists to report.
      expect(workspacePartFromSettled(ok(q), ok(st), ko())).toBeNull();
    });

    it('drops the workspace when the quota failed but the breakdown answered', () => {
      expect(workspacePartFromSettled(ko(), ok(st), ok(bd))).toBeNull();
    });

    it('KEEPS the workspace when only the stats failed, because stats are not bytes', () => {
      // workflowCount and friends live in a different card. Losing them must not remove this
      // workspace's storage from the total, and the aggregator tolerates null stats for exactly
      // this case: one rule, both layers.
      expect(workspacePartFromSettled(ok(q), ko(), ok(bd))).toEqual({ quota: q, stats: null, breakdown: bd });
    });

    it('treats a fulfilled-but-null quota as unreadable', () => {
      expect(workspacePartFromSettled(ok(null), ok(st), ok(bd))).toBeNull();
    });

    it('treats a non-array breakdown as unreadable rather than coercing it to empty', () => {
      // Coercing to [] would silently contribute this workspace's gauge bytes and no categories.
      expect(workspacePartFromSettled(ok(q), ok(st), ok(null))).toBeNull();
    });
  });

  describe('mergeStorageHistories', () => {
    it('sums daily points across workspaces by (date, category)', () => {
      const wsA: StorageHistoryPoint[] = [
        { snapshotDate: '2026-06-01', category: 'FILES', usedBytes: 100, itemCount: 2 },
        { snapshotDate: '2026-06-02', category: 'FILES', usedBytes: 120, itemCount: 2 },
      ];
      const wsB: StorageHistoryPoint[] = [
        { snapshotDate: '2026-06-01', category: 'FILES', usedBytes: 40, itemCount: 1 },
        { snapshotDate: '2026-06-01', category: 'AGENTS', usedBytes: 5, itemCount: 1 },
      ];
      const result = mergeStorageHistories([wsA, wsB]);
      const jun1Files = result.find((p) => p.snapshotDate === '2026-06-01' && p.category === 'FILES')!;
      expect(jun1Files.usedBytes).toBe(140); // 100 + 40
      expect(jun1Files.itemCount).toBe(3);
      expect(result.find((p) => p.snapshotDate === '2026-06-02' && p.category === 'FILES')!.usedBytes).toBe(120);
      expect(result.find((p) => p.snapshotDate === '2026-06-01' && p.category === 'AGENTS')!.usedBytes).toBe(5);
    });
  });
});
