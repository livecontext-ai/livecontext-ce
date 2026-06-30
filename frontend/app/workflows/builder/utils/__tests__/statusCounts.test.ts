import { describe, it, expect } from 'vitest';
import {
  normalizeStatusCounts,
  metricsToStatusCounts,
  statusCountsToMetrics,
  deriveStatusFromCounts,
} from '../statusCounts';

// ==================== normalizeStatusCounts ====================

describe('normalizeStatusCounts', () => {
  // --- Alias mapping ---
  it('keeps "COMPLETED" as canonical key', () => {
    const result = normalizeStatusCounts({ COMPLETED: 5 });
    expect(result).toEqual({ COMPLETED: 5 });
  });

  it('maps "SUCCESS" to "COMPLETED"', () => {
    const result = normalizeStatusCounts({ SUCCESS: 3 });
    expect(result).toEqual({ COMPLETED: 3 });
  });

  it('maps "SUCCESSFUL" to "COMPLETED"', () => {
    const result = normalizeStatusCounts({ SUCCESSFUL: 3 });
    expect(result).toEqual({ COMPLETED: 3 });
  });

  it('maps "FAILURE" to "FAILED"', () => {
    const result = normalizeStatusCounts({ FAILURE: 2 });
    expect(result).toEqual({ FAILED: 2 });
  });

  it('maps "ERROR" to "FAILED"', () => {
    const result = normalizeStatusCounts({ ERROR: 1 });
    expect(result).toEqual({ FAILED: 1 });
  });

  it('maps "SKIP" to "SKIPPED"', () => {
    const result = normalizeStatusCounts({ SKIP: 4 });
    expect(result).toEqual({ SKIPPED: 4 });
  });

  it('maps case-insensitive aliases (e.g., "completed" lowercase)', () => {
    const result = normalizeStatusCounts({ completed: 7 });
    expect(result).toEqual({ COMPLETED: 7 });
  });

  it('maps case-insensitive aliases (e.g., "success" lowercase)', () => {
    const result = normalizeStatusCounts({ success: 7 });
    expect(result).toEqual({ COMPLETED: 7 });
  });

  it('maps mixed-case aliases (e.g., "Failure")', () => {
    const result = normalizeStatusCounts({ Failure: 2 });
    expect(result).toEqual({ FAILED: 2 });
  });

  // --- Value validation ---
  it('filters out negative values', () => {
    const result = normalizeStatusCounts({ COMPLETED: -1, RUNNING: 3 });
    expect(result).toEqual({ RUNNING: 3 });
  });

  it('filters out NaN values', () => {
    const result = normalizeStatusCounts({ COMPLETED: NaN, RUNNING: 2 } as any);
    expect(result).toEqual({ RUNNING: 2 });
  });

  it('filters out Infinity values', () => {
    const result = normalizeStatusCounts({ COMPLETED: Infinity, RUNNING: 1 } as any);
    expect(result).toEqual({ RUNNING: 1 });
  });

  it('filters out zero values (rounds to 0 = not positive)', () => {
    const result = normalizeStatusCounts({ COMPLETED: 0, RUNNING: 5 });
    expect(result).toEqual({ RUNNING: 5 });
  });

  it('rounds floating-point values', () => {
    const result = normalizeStatusCounts({ COMPLETED: 3.7 });
    expect(result).toEqual({ COMPLETED: 4 });
  });

  it('rounds down values below 0.5', () => {
    const result = normalizeStatusCounts({ COMPLETED: 0.3 });
    // 0.3 rounds to 0, which is not > 0, so filtered out
    expect(result).toBeUndefined();
  });

  // --- Empty/null/undefined ---
  it('returns undefined for undefined input', () => {
    expect(normalizeStatusCounts(undefined)).toBeUndefined();
  });

  it('returns undefined for null input', () => {
    expect(normalizeStatusCounts(null)).toBeUndefined();
  });

  it('returns undefined for empty object', () => {
    expect(normalizeStatusCounts({})).toBeUndefined();
  });

  it('returns undefined when all values are zero', () => {
    expect(normalizeStatusCounts({ COMPLETED: 0, FAILED: 0 })).toBeUndefined();
  });

  // --- Multiple keys combined ---
  it('normalizes multiple keys simultaneously', () => {
    const result = normalizeStatusCounts({
      SUCCESS: 10,
      FAILURE: 2,
      RUNNING: 3,
      SKIP: 1,
    });
    expect(result).toEqual({
      COMPLETED: 10,
      FAILED: 2,
      RUNNING: 3,
      SKIPPED: 1,
    });
  });

  // --- Key sanitization ---
  it('sanitizes keys with extra whitespace', () => {
    const result = normalizeStatusCounts({ '  COMPLETED  ': 5 });
    expect(result).toEqual({ COMPLETED: 5 });
  });

  it('sanitizes keys with hyphens to underscores', () => {
    const result = normalizeStatusCounts({ 'PARTIAL-SUCCESS': 2 });
    expect(result).toEqual({ PARTIAL_SUCCESS: 2 });
  });

  it('passes through unknown keys in uppercase', () => {
    const result = normalizeStatusCounts({ PENDING: 3 });
    expect(result).toEqual({ PENDING: 3 });
  });

  it('handles non-object types gracefully', () => {
    expect(normalizeStatusCounts('hello' as any)).toBeUndefined();
    expect(normalizeStatusCounts(42 as any)).toBeUndefined();
  });
});

// ==================== metricsToStatusCounts ====================

describe('metricsToStatusCounts', () => {
  it('returns undefined for undefined input', () => {
    expect(metricsToStatusCounts(undefined)).toBeUndefined();
  });

  it('returns undefined for empty metrics (all zero or undefined)', () => {
    expect(metricsToStatusCounts({})).toBeUndefined();
  });

  it('converts running metric', () => {
    const result = metricsToStatusCounts({ running: 5 });
    expect(result).toEqual({ RUNNING: 5 });
  });

  it('converts completed metric', () => {
    const result = metricsToStatusCounts({ completed: 10 });
    expect(result).toEqual({ COMPLETED: 10 });
  });

  it('converts failed metric', () => {
    const result = metricsToStatusCounts({ failed: 3 });
    expect(result).toEqual({ FAILED: 3 });
  });

  it('converts skipped metric', () => {
    const result = metricsToStatusCounts({ skipped: 2 });
    expect(result).toEqual({ SKIPPED: 2 });
  });

  it('converts processed metric', () => {
    const result = metricsToStatusCounts({ processed: 15 });
    expect(result).toEqual({ PROCESSED: 15 });
  });

  it('converts total metric', () => {
    const result = metricsToStatusCounts({ total: 20 });
    expect(result).toEqual({ TOTAL: 20 });
  });

  it('converts all metrics together', () => {
    const result = metricsToStatusCounts({
      running: 2,
      completed: 5,
      failed: 1,
      skipped: 3,
      processed: 9,
      total: 11,
    });
    expect(result).toEqual({
      RUNNING: 2,
      COMPLETED: 5,
      FAILED: 1,
      SKIPPED: 3,
      PROCESSED: 9,
      TOTAL: 11,
    });
  });

  it('skips zero-value metrics', () => {
    const result = metricsToStatusCounts({ running: 0, completed: 5 });
    expect(result).toEqual({ COMPLETED: 5 });
  });

  it('skips undefined metrics', () => {
    const result = metricsToStatusCounts({ running: undefined, completed: 3 });
    expect(result).toEqual({ COMPLETED: 3 });
  });

  it('rounds floating point values', () => {
    const result = metricsToStatusCounts({ completed: 3.7 });
    expect(result).toEqual({ COMPLETED: 4 });
  });
});

// ==================== statusCountsToMetrics ====================

describe('statusCountsToMetrics', () => {
  it('returns undefined for undefined input', () => {
    expect(statusCountsToMetrics(undefined)).toBeUndefined();
  });

  it('returns undefined for empty counts (all zero)', () => {
    expect(statusCountsToMetrics({})).toBeUndefined();
  });

  it('converts COMPLETED to completed', () => {
    const result = statusCountsToMetrics({ COMPLETED: 10 });
    expect(result).toBeDefined();
    expect(result!.completed).toBe(10);
  });

  it('converts FAILED to failed', () => {
    const result = statusCountsToMetrics({ FAILED: 3 });
    expect(result).toBeDefined();
    expect(result!.failed).toBe(3);
  });

  it('converts RUNNING to running', () => {
    const result = statusCountsToMetrics({ RUNNING: 5 });
    expect(result).toBeDefined();
    expect(result!.running).toBe(5);
  });

  it('converts SKIPPED to skipped', () => {
    const result = statusCountsToMetrics({ SKIPPED: 2 });
    expect(result).toBeDefined();
    expect(result!.skipped).toBe(2);
  });

  it('calculates processed from completed + failed + skipped when PROCESSED not provided', () => {
    const result = statusCountsToMetrics({ COMPLETED: 5, FAILED: 2, SKIPPED: 1 });
    expect(result).toBeDefined();
    expect(result!.processed).toBe(8); // 5 + 2 + 1
  });

  it('uses explicit PROCESSED when provided', () => {
    const result = statusCountsToMetrics({ COMPLETED: 5, FAILED: 2, PROCESSED: 10 });
    expect(result).toBeDefined();
    expect(result!.processed).toBe(10);
  });

  it('calculates total from processed + running when TOTAL not provided', () => {
    const result = statusCountsToMetrics({ COMPLETED: 5, RUNNING: 3 });
    expect(result).toBeDefined();
    expect(result!.total).toBe(8); // processed(5) + running(3)
  });

  it('uses explicit TOTAL when provided', () => {
    const result = statusCountsToMetrics({ COMPLETED: 5, RUNNING: 3, TOTAL: 20 });
    expect(result).toBeDefined();
    expect(result!.total).toBe(20);
  });

  it('omits zero-value fields from output', () => {
    const result = statusCountsToMetrics({ COMPLETED: 5 });
    expect(result).toBeDefined();
    expect(result!.running).toBeUndefined();
    expect(result!.failed).toBeUndefined();
    expect(result!.skipped).toBeUndefined();
  });

  it('converts full set of counts to metrics', () => {
    const result = statusCountsToMetrics({
      RUNNING: 2,
      COMPLETED: 5,
      FAILED: 1,
      SKIPPED: 3,
      PROCESSED: 9,
      TOTAL: 11,
    });
    expect(result).toEqual({
      running: 2,
      completed: 5,
      failed: 1,
      skipped: 3,
      processed: 9,
      total: 11,
    });
  });
});

// ==================== deriveStatusFromCounts ====================

describe('deriveStatusFromCounts', () => {
  // --- Undefined/null/empty ---
  it('returns "pending" for undefined counts', () => {
    expect(deriveStatusFromCounts(undefined)).toBe('pending');
  });

  it('returns "pending" for empty counts', () => {
    expect(deriveStatusFromCounts({})).toBe('pending');
  });

  it('returns "pending" when all counts are zero', () => {
    expect(deriveStatusFromCounts({ COMPLETED: 0, FAILED: 0, RUNNING: 0 })).toBe('pending');
  });

  // --- Running ---
  it('returns "running" when there are running items and processing is incomplete', () => {
    expect(deriveStatusFromCounts({ RUNNING: 5, TOTAL: 10 })).toBe('running');
  });

  it('returns "running" when running > 0 and total = 0 (unknown total)', () => {
    expect(deriveStatusFromCounts({ RUNNING: 3 })).toBe('running');
  });

  it('returns "running" when running with some completed but still processing', () => {
    expect(deriveStatusFromCounts({ RUNNING: 2, COMPLETED: 5, TOTAL: 10 })).toBe('running');
  });

  // --- Completed ---
  it('returns "completed" when only completed', () => {
    expect(deriveStatusFromCounts({ COMPLETED: 10 })).toBe('completed');
  });

  it('returns "completed" when completed with no failures or skipped', () => {
    expect(deriveStatusFromCounts({ COMPLETED: 5, RUNNING: 0 })).toBe('completed');
  });

  // --- Failed ---
  it('returns "failed" when only failures', () => {
    expect(deriveStatusFromCounts({ FAILED: 5 })).toBe('failed');
  });

  it('returns "failed" when failures with no completed and no skipped', () => {
    expect(deriveStatusFromCounts({ FAILED: 3, RUNNING: 0, COMPLETED: 0 })).toBe('failed');
  });

  // --- Skipped ---
  it('returns "skipped" when only skipped items', () => {
    expect(deriveStatusFromCounts({ SKIPPED: 10 })).toBe('skipped');
  });

  it('returns "skipped" when skipped with no completed and no failure', () => {
    expect(deriveStatusFromCounts({ SKIPPED: 5, RUNNING: 0 })).toBe('skipped');
  });

  // --- Partial success ---
  it('returns "partial_success" for mix of completed and failure', () => {
    expect(deriveStatusFromCounts({ COMPLETED: 5, FAILED: 2 })).toBe('partial_success');
  });

  it('returns "partial_success" for completed + failure + skipped', () => {
    expect(deriveStatusFromCounts({ COMPLETED: 3, FAILED: 1, SKIPPED: 2 })).toBe('partial_success');
  });

  // --- Skipped + completed = completed ---
  it('returns "completed" for skipped + completed (no failures)', () => {
    expect(deriveStatusFromCounts({ SKIPPED: 9, COMPLETED: 1 })).toBe('completed');
  });

  // --- Skipped + failure = failed ---
  it('returns "failed" for skipped + failure (no completed)', () => {
    expect(deriveStatusFromCounts({ SKIPPED: 9, FAILED: 1 })).toBe('failed');
  });

  // --- Lowercase key fallback ---
  it('handles lowercase keys (running)', () => {
    expect(deriveStatusFromCounts({ running: 5 })).toBe('running');
  });

  it('handles lowercase keys (completed)', () => {
    expect(deriveStatusFromCounts({ completed: 10 })).toBe('completed');
  });

  it('handles lowercase keys (failed)', () => {
    expect(deriveStatusFromCounts({ failed: 3 })).toBe('failed');
  });

  it('handles lowercase keys (skipped)', () => {
    expect(deriveStatusFromCounts({ skipped: 5 })).toBe('skipped');
  });

  // --- Complex scenarios ---
  it('returns "running" for large in-progress workflow', () => {
    expect(deriveStatusFromCounts({
      RUNNING: 10,
      COMPLETED: 50,
      FAILED: 2,
      TOTAL: 100,
    })).toBe('running');
  });

  it('returns "partial_success" when all done with mixed results', () => {
    expect(deriveStatusFromCounts({
      RUNNING: 0,
      COMPLETED: 8,
      FAILED: 2,
      TOTAL: 10,
      PROCESSED: 10,
    })).toBe('partial_success');
  });

  it('returns "completed" for fully successful batch', () => {
    expect(deriveStatusFromCounts({
      COMPLETED: 100,
      PROCESSED: 100,
      TOTAL: 100,
    })).toBe('completed');
  });

  it('returns "failed" for fully failed batch', () => {
    expect(deriveStatusFromCounts({
      FAILED: 50,
      PROCESSED: 50,
      TOTAL: 50,
    })).toBe('failed');
  });
});
