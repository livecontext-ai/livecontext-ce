import { describe, it, expect } from 'vitest';
import {
  aggregateStorageQuotas,
  aggregateTenantStats,
  aggregateBreakdowns,
  mergeStorageHistories,
} from '../storageAggregation';
import type { StorageQuota, TenantStats, StorageBreakdown, StorageHistoryPoint } from '@/lib/api';

const quota = (over: Partial<StorageQuota>): StorageQuota => ({
  tenantId: 't', usedBytes: 0, maxBytes: 0, softLimitBytes: 0, hardLimitBytes: 0,
  availableBytes: 0, usagePercentage: 0, status: 'OK', unlimited: false, ...over,
});

describe('storageAggregation', () => {
  describe('aggregateStorageQuotas', () => {
    it('sums used + limits across workspaces and derives percentage / available', () => {
      const result = aggregateStorageQuotas([
        quota({ usedBytes: 30, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
        quota({ usedBytes: 20, maxBytes: 100, softLimitBytes: 80, hardLimitBytes: 100 }),
      ]);
      expect(result.usedBytes).toBe(50);
      expect(result.maxBytes).toBe(200);
      expect(result.softLimitBytes).toBe(160);
      expect(result.hardLimitBytes).toBe(200);
      expect(result.availableBytes).toBe(150);
      expect(result.usagePercentage).toBeCloseTo(25); // 50 / 200
      expect(result.unlimited).toBe(false);
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
