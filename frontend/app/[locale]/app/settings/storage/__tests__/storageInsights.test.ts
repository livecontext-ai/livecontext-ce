import { describe, it, expect } from 'vitest';
import { categoryRows, storageGrowth, daysUntilFull } from '../storageInsights';

const MB = 1024 * 1024;

describe('categoryRows', () => {
  it('ranks the categories that hold something, with their share and average item size', () => {
    const rows = categoryRows([
      { category: 'FILES', usedBytes: 30 * MB, itemCount: 3, calculatedAt: '' },
      { category: 'STEP_OUTPUTS', usedBytes: 70 * MB, itemCount: 700, calculatedAt: '' },
      { category: 'AGENTS', usedBytes: 0, itemCount: 4, calculatedAt: '' },
    ]);
    expect(rows.map((r) => r.category)).toEqual(['STEP_OUTPUTS', 'FILES']);
    expect(rows[0].share).toBeCloseTo(0.7);
    expect(rows[0].avgItemBytes).toBeCloseTo(0.1 * MB);
    expect(rows[1].avgItemBytes).toBeCloseTo(10 * MB);
  });

  it('has no average for a category that reports no items, rather than a division by zero', () => {
    expect(categoryRows([{ category: 'FILES', usedBytes: MB, itemCount: 0, calculatedAt: '' }])[0].avgItemBytes).toBeNull();
  });

  it('survives a breakdown that is not an array (an error envelope)', () => {
    expect(categoryRows(null)).toEqual([]);
    expect(categoryRows({} as never)).toEqual([]);
  });
});

describe('storageGrowth', () => {
  const point = (snapshotDate: string, category: string, usedBytes: number) => ({ snapshotDate, category, usedBytes, itemCount: 0 });

  it('measures the change between the first and the last snapshot, summed over categories', () => {
    const g = storageGrowth([
      point('2026-09-01', 'FILES', 10 * MB),
      point('2026-09-01', 'STEP_OUTPUTS', 10 * MB),
      point('2026-09-05', 'FILES', 20 * MB),
      point('2026-09-11', 'FILES', 25 * MB),
      point('2026-09-11', 'STEP_OUTPUTS', 15 * MB),
    ]);
    expect(g).toMatchObject({ firstDate: '2026-09-01', lastDate: '2026-09-11', startBytes: 20 * MB, endBytes: 40 * MB, growthBytes: 20 * MB });
    // 20 MB over 10 days.
    expect(g!.perDayBytes).toBeCloseTo(2 * MB);
  });

  it('reports a shrink as a negative change', () => {
    const g = storageGrowth([point('2026-09-01', 'FILES', 10 * MB), point('2026-09-03', 'FILES', 6 * MB)]);
    expect(g!.growthBytes).toBe(-4 * MB);
    expect(g!.perDayBytes).toBe(-2 * MB);
  });

  it('regression - one snapshot day is a level, not a trend: no per-day figure is invented', () => {
    expect(storageGrowth([point('2026-09-01', 'FILES', 10 * MB), point('2026-09-01', 'AGENTS', MB)])).toBeNull();
    expect(storageGrowth([])).toBeNull();
    expect(storageGrowth(undefined)).toBeNull();
  });
});

describe('daysUntilFull', () => {
  it('divides the room left by the daily growth, rounded down', () => {
    expect(daysUntilFull(60, 100, 3)).toBe(13);
  });

  it('says nothing it cannot back: no limit, not growing, or already full', () => {
    expect(daysUntilFull(60, 0, 3)).toBeNull();
    expect(daysUntilFull(60, null, 3)).toBeNull();
    expect(daysUntilFull(60, 100, 0)).toBeNull();
    expect(daysUntilFull(60, 100, -1)).toBeNull();
    expect(daysUntilFull(100, 100, 3)).toBeNull();
  });
});
