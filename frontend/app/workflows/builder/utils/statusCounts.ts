/**
 * Status Counts Utilities
 * 
 * Handles normalization, validation, and derivation of status from statusCounts.
 * Follows single responsibility principle - each function has one clear purpose.
 */

import type { DerivedNodeStatus } from '../types';

/**
 * Canonical status count keys mapping
 * Maps various status count key formats to canonical uppercase keys
 */
const STATUS_COUNT_ALIAS_MAP: Record<string, string> = {
  SUCCESS: 'COMPLETED',
  SUCCESSFUL: 'COMPLETED',
  FAILURE: 'FAILED',
  ERROR: 'FAILED',
  SKIP: 'SKIPPED',
  AWAITING_SIGNAL: 'AWAITING_SIGNAL',
  AWAITINGSIGNAL: 'AWAITING_SIGNAL',
};

/**
 * Normalizes a raw status count key to a canonical format
 * @param rawKey - The raw key from backend (e.g., "completed", "SUCCESS", "success")
 * @returns Canonical uppercase key (e.g., "COMPLETED", "FAILED")
 */
function canonicalizeStatusCountKey(rawKey: string): string {
  const sanitized = rawKey.trim().replace(/[\s-]+/g, '_').replace(/__+/g, '_');
  const upperKey = sanitized.toUpperCase();
  return STATUS_COUNT_ALIAS_MAP[upperKey] ?? upperKey;
}

/**
 * Normalizes status counts object by:
 * - Converting keys to canonical format
 * - Validating numeric values
 * - Filtering out invalid entries
 * 
 * @param counts - Raw status counts object from backend
 * @returns Normalized status counts or undefined if invalid/empty
 */
export function normalizeStatusCounts(
  counts: Record<string, number> | undefined | null
): Record<string, number> | undefined {
  if (!counts || typeof counts !== 'object' || counts === null) {
    return undefined;
  }

  try {
    const normalized: Record<string, number> = {};

    for (const [rawKey, rawValue] of Object.entries(counts)) {
      try {
        const numeric = Number(rawValue);
        
        // Validate: must be finite and non-negative
        if (!Number.isFinite(numeric) || numeric < 0) {
          continue;
        }

        const canonicalKey = canonicalizeStatusCountKey(rawKey);
        const rounded = Math.round(numeric);
        
        // Only include positive values
        if (rounded > 0) {
          normalized[canonicalKey] = rounded;
        }
      } catch (err) {
        // Skip invalid entries, log in development
        if (process.env.NODE_ENV !== 'production') {
          console.debug('[statusCounts] Error normalizing key:', rawKey, err);
        }
        continue;
      }
    }

    return Object.keys(normalized).length > 0 ? normalized : undefined;
  } catch (err) {
    if (process.env.NODE_ENV !== 'production') {
      console.debug('[statusCounts] Error normalizing status counts:', err);
    }
    return undefined;
  }
}

/**
 * Edge item metrics interface
 */
export interface EdgeItemMetrics {
  running?: number;
  completed?: number;
  failed?: number;
  skipped?: number;
  processed?: number;
  total?: number;
  httpStatusCounts?: Record<number, number>;
}

/**
 * Converts edge item metrics to normalized status counts
 * @param metrics - Edge item metrics
 * @returns Normalized status counts
 */
export function metricsToStatusCounts(metrics?: EdgeItemMetrics): Record<string, number> | undefined {
  if (!metrics) {
    return undefined;
  }

  const counts: Record<string, number> = {};
  
  if (metrics.running !== undefined && metrics.running > 0) {
    counts.RUNNING = Math.round(metrics.running);
  }
  if (metrics.completed !== undefined && metrics.completed > 0) {
    counts.COMPLETED = Math.round(metrics.completed);
  }
  if (metrics.failed !== undefined && metrics.failed > 0) {
    counts.FAILED = Math.round(metrics.failed);
  }
  if (metrics.skipped !== undefined && metrics.skipped > 0) {
    counts.SKIPPED = Math.round(metrics.skipped);
  }
  if (metrics.processed !== undefined && metrics.processed > 0) {
    counts.PROCESSED = Math.round(metrics.processed);
  }
  if (metrics.total !== undefined && metrics.total > 0) {
    counts.TOTAL = Math.round(metrics.total);
  }

  return Object.keys(counts).length > 0 ? counts : undefined;
}

/**
 * Converts normalized status counts to edge item metrics
 * @param counts - Normalized status counts
 * @returns Edge item metrics
 */
export function statusCountsToMetrics(counts?: Record<string, number>): EdgeItemMetrics | undefined {
  if (!counts) {
    return undefined;
  }

  const read = (key: string): number => {
    return Number(counts[key] ?? 0);
  };

  const running = read('RUNNING');
  const completed = read('COMPLETED');
  const failed = read('FAILED');
  const skipped = read('SKIPPED');
  const processed = read('PROCESSED') || completed + failed + skipped;
  const total = read('TOTAL') || processed + running;

  // Only return if there's at least one non-zero value
  if (running === 0 && completed === 0 && failed === 0 && skipped === 0 && processed === 0 && total === 0) {
    return undefined;
  }

  return {
    running: running > 0 ? running : undefined,
    completed: completed > 0 ? completed : undefined,
    failed: failed > 0 ? failed : undefined,
    skipped: skipped > 0 ? skipped : undefined,
    processed: processed > 0 ? processed : undefined,
    total: total > 0 ? total : undefined,
  };
}

/**
 * Derives node status from normalized status counts
 * 
 * Priority order:
 * 1. RUNNING if there are running items
 * 2. ERROR if there are failures
 * 3. PARTIAL_SUCCESS if there are both successes and failures/skipped
 * 4. COMPLETED if all items succeeded
 * 5. SKIPPED if all items were skipped
 * 6. PENDING otherwise
 * 
 * @param counts - Normalized status counts
 * @returns Derived node status
 */
export function deriveStatusFromCounts(counts?: Record<string, number>): DerivedNodeStatus {
  if (!counts) {
    return 'pending';
  }

  const running = Number(counts.RUNNING ?? counts.running ?? 0);
  const completed = Number(counts.COMPLETED ?? counts.completed ?? 0);
  const failure = Number(counts.FAILED ?? counts.failed ?? 0);
  const skipped = Number(counts.SKIPPED ?? counts.skipped ?? 0);
  const awaitingSignal = Number(counts.AWAITING_SIGNAL ?? counts.awaiting_signal ?? 0);
  const processed = Number(counts.PROCESSED ?? counts.processed ?? completed + failure + skipped);
  const total = Number(counts.TOTAL ?? counts.total ?? processed + running + awaitingSignal);

  // Awaiting signal: interface node waiting for user action
  if (awaitingSignal > 0) {
    return 'awaiting_signal';
  }

  // Running: items are currently being processed
  if (running > 0 && (processed < total || total === 0)) {
    return 'running';
  }

  // Skipped: all items were skipped (no completed, no failure)
  if (skipped > 0 && completed === 0 && failure === 0) {
    return 'skipped';
  }

  // Completed/Failed with skipped: skipped doesn't count, use completed/failed status
  // 9 skipped + 1 completed => status = completed
  // 9 skipped + 1 failed => status = failed
  if (skipped > 0 && (completed > 0 || failure > 0)) {
    if (completed > 0 && failure === 0) {
      return 'completed';
    }
    if (failure > 0 && completed === 0) {
      return 'failed';
    }
  }

  // Partial success: mix of completed and failed (no skipped, or both completed and failed)
  if (completed > 0 && failure > 0) {
    return 'partial_success';
  }

  // Failed: all items failed (no completed, no skipped)
  if (failure > 0 && completed === 0 && skipped === 0) {
    return 'failed';
  }

  // Completed: all items succeeded (no failures, no skipped)
  if (completed > 0 && failure === 0 && skipped === 0) {
    return 'completed';
  }

  // Pending: no processing yet
  return 'pending';
}


