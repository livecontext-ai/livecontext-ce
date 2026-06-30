// @vitest-environment jsdom
/**
 * Regression tests for the inspector status-filter plumbing on
 * {@link useRunOutputData}. Before the fix, statusFilter was applied
 * client-side after fetch (`allItems.filter(it => mapped === statusFilter)`),
 * which made the dropdown "feel client-side" because totals/counts didn't
 * change. The fix forwards statusFilter to {@code getRunStepsPaged} so the
 * backend filters before paginating.
 *
 * A second guard: {@code availableStatuses} must continue to surface every
 * status that exists for the alias so the user can switch back to a different
 * filter - that's why we reuse {@link useStepData} (unfiltered) instead of
 * deriving from the now-filtered main query.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const getRunStepsPaged = vi.fn();
const getRun = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunStepsPaged: (...args: unknown[]) => getRunStepsPaged(...args),
    getRun: (...args: unknown[]) => getRun(...args),
    execution: {
      getStepOutputSkeleton: vi.fn().mockResolvedValue(null),
      getStepOutputValueAtPath: vi.fn().mockResolvedValue(null),
      getStepOutputObjectAtPath: vi.fn().mockResolvedValue(null),
    },
  },
}));

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));

import { useRunOutputData } from '../useRunOutputData';

function wrapper({ children }: { children: React.ReactNode }) {
  // New client per test so caches don't bleed between scenarios.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const RUN_PUB = 'run-public-1';
const RUN_UUID = 'run-uuid-1';
const ALIAS = 'mcp:my_step';

describe('useRunOutputData - statusFilter plumbing', () => {
  beforeEach(() => {
    getRunStepsPaged.mockReset();
    getRun.mockReset();
    getRun.mockResolvedValue({ id: RUN_UUID });
  });

  it('forwards statusFilter to getRunStepsPaged so the backend filters before pagination', async () => {
    getRunStepsPaged.mockResolvedValue({ content: [] });

    renderHook(
      () =>
        useRunOutputData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          epoch: 2,
          statusFilter: 'completed',
        }),
      { wrapper },
    );

    // Two queries fire: the filtered main query and the unfiltered query
    // shared via useStepData. Both go through getRunStepsPaged. The main
    // query MUST include statusFilter; the availableStatuses query must NOT
    // (otherwise the dropdown collapses to a single status).
    await waitFor(() => {
      expect(getRunStepsPaged).toHaveBeenCalled();
    });

    const callsWithStatus = getRunStepsPaged.mock.calls.filter(
      (call) => call[5] === 'completed',
    );
    const callsWithoutStatus = getRunStepsPaged.mock.calls.filter(
      (call) => call[5] === undefined || call[5] === null,
    );

    expect(callsWithStatus.length).toBeGreaterThan(0);
    expect(callsWithoutStatus.length).toBeGreaterThan(0);

    // Both queries must scope to the same alias / run UUID and use the
    // PAGE_SIZE in useRunOutputData (raised from 200 → 500 on 2026-05-13 so
    // that the inspector navigator surfaces the full per-alias history of
    // workflows like Daily Email Digest, which routinely hold 3000+ rows per
    // stepAlias). The hook now uses useInfiniteQuery - additional pages
    // beyond the first are fetched lazily via the lookahead effect.
    const filteredCall = callsWithStatus[0];
    expect(filteredCall[0]).toBe(RUN_UUID);
    expect(filteredCall[1]).toBe(ALIAS);
    expect(filteredCall[3]).toBe(500);
    expect(filteredCall[4]).toBe(2);
  });

  it('does NOT forward a status param when statusFilter is null/undefined', async () => {
    getRunStepsPaged.mockResolvedValue({ content: [] });

    renderHook(
      () =>
        useRunOutputData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          statusFilter: null,
        }),
      { wrapper },
    );

    await waitFor(() => {
      expect(getRunStepsPaged).toHaveBeenCalled();
    });

    // Every call must omit the status arg (5th param undefined or null).
    for (const call of getRunStepsPaged.mock.calls) {
      expect(call[5] == null).toBe(true);
    }
  });

  it('exposes availableStatuses derived from the unfiltered set so the dropdown lists every status the alias has produced', async () => {
    // Even when the main query is filtered to only "completed", the
    // dropdown should show {completed, failed} so the user can switch back.
    getRunStepsPaged.mockImplementation(async (
      _runUuid: string,
      _alias: string,
      _page: number,
      _size: number,
      _epoch?: number | null,
      status?: string | null,
    ) => {
      if (status) {
        return { content: [{ id: 1, status: 'completed' }] };
      }
      return {
        content: [
          { id: 1, status: 'completed' },
          { id: 2, status: 'failed' },
        ],
      };
    });

    const { result } = renderHook(
      () =>
        useRunOutputData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          statusFilter: 'completed',
        }),
      { wrapper },
    );

    await waitFor(() => {
      expect(result.current.availableStatuses.length).toBeGreaterThan(0);
    });

    expect(new Set(result.current.availableStatuses)).toEqual(
      new Set(['completed', 'failed']),
    );
  });
});
