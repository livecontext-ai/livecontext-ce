// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { useRunOutputData } from '../useRunOutputData';

// Regression coverage for the 2026-05-13 InspectorPanel "max 100 results" bug.
// The hook used to issue a single page-0 fetch (size=200, backend cap=100) so
// workflows with >100 items per stepAlias silently truncated to the latest
// page. Post-fix: useInfiniteQuery streams older pages, totalItems exposes the
// true backend count, and the lookahead effect transparently prefetches the
// next page when the navigator approaches the loaded boundary.

const RUN_PUB = 'run_abc';
const RUN_UUID = 'uuid-1';
const ALIAS = 'parse_headers';

const getRunStepsPaged = vi.fn();
const getRun = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunStepsPaged: (...args: unknown[]) => getRunStepsPaged(...args),
    getRun: (...args: unknown[]) => getRun(...args),
    execution: {
      getStepOutputSkeleton: vi.fn().mockResolvedValue(null),
    },
  },
}));

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));

vi.mock('../useStepData', async () => {
  const actual = await vi.importActual<typeof import('../useStepData')>('../useStepData');
  return {
    ...actual,
    useStepData: () => ({
      stepData: [],
      loading: false,
      error: null,
      hasNextPage: false,
      fetchNextPage: vi.fn(),
      isFetchingNextPage: false,
      totalElements: 0,
    }),
  };
});

function wrap(client: QueryClient) {
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

function pageContent(startId: number, count: number, totalElements: number, totalPages: number) {
  const content = [] as Array<Record<string, unknown>>;
  for (let i = 0; i < count; i++) {
    content.push({
      id: startId + i,
      runId: RUN_PUB,
      stepAlias: ALIAS,
      epoch: 1,
      itemIndex: i,
      spawn: 0,
      status: 'COMPLETED',
    });
  }
  return { content, totalElements, totalPages, page: 0, size: count };
}

describe('useRunOutputData - infinite-query pagination (Bug 1 regression)', () => {
  beforeEach(() => {
    getRunStepsPaged.mockReset();
    getRun.mockReset();
    getRun.mockResolvedValue({ id: RUN_UUID });
  });

  it('totalItems exposes the backend totalElements (not items.length) - fix navigator "N / 3490" label', async () => {
    // First page: 500 rows; backend says there are 3490 total across 7 pages.
    getRunStepsPaged.mockResolvedValueOnce(pageContent(1, 500, 3490, 7));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () =>
        useRunOutputData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          enabled: true,
        }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => {
      expect(result.current.items.length).toBe(500);
    });

    // Pre-fix: totalItems returned items.length (500) → navigator showed "N / 500".
    // Post-fix: totalItems uses backend's totalElements so the user sees the true
    // size of the per-alias history.
    expect(result.current.totalItems).toBe(3490);
  });

  it('after an older page lands, the navigator stays on the SAME logical row by shifting currentIndex', async () => {
    // This pins the deciding fix: when page 1 merges, the post-merge effect must
    // re-locate the tracked item by id and shift currentIndex accordingly so the
    // user is still on the row they were inspecting. Pre-fix this re-located
    // index would either (a) point to a different row or (b) trigger a max-
    // update-depth loop with the stale currentItemIdRef.
    getRunStepsPaged.mockImplementation(async (_run, _alias, page) => {
      if (page === 0) {
        const content = [] as Array<Record<string, unknown>>;
        // Page 0 = newest 500 rows (ids 1001..1500), DESC sorted.
        for (let id = 1500; id >= 1001; id--) {
          content.push({ id, runId: RUN_PUB, stepAlias: ALIAS, epoch: 1, itemIndex: id, spawn: 0, status: 'COMPLETED' });
        }
        return { content, totalElements: 1500, totalPages: 3, page: 0, size: 500 };
      }
      if (page === 1) {
        const content = [] as Array<Record<string, unknown>>;
        for (let id = 1000; id >= 501; id--) {
          content.push({ id, runId: RUN_PUB, stepAlias: ALIAS, epoch: 1, itemIndex: id, spawn: 0, status: 'COMPLETED' });
        }
        return { content, totalElements: 1500, totalPages: 3, page: 1, size: 500 };
      }
      return { content: [], totalElements: 1500, totalPages: 3, page, size: 500 };
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunOutputData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => {
      expect(result.current.items.length).toBe(500);
    });

    // After initial seed: currentItem is the NEWEST (id=1500). Note this id.
    const newestId = result.current.currentItem?.id;
    expect(newestId).toBe(1500);

    // Navigate into the LOOKAHEAD window. Pick an item in the middle of page 0
    // (id 1011 sits at items[10] after the reverse).
    result.current.goToIndex(10);
    await waitFor(() => {
      expect(result.current.currentItem?.id).toBe(1011);
    });

    // Wait for page 1 to land (it should - currentIndex=10 ≤ LOOKAHEAD=50).
    await waitFor(() => {
      expect(result.current.items.length).toBe(1000);
    }, { timeout: 2000 });

    // The user is STILL on id 1011 - the navigator has not been clobbered by the
    // page-merge effect. After the merge, items are sorted [501..1500] ASC so
    // id 1011 now lives at index 510 (offset by 500 prepended older rows).
    expect(result.current.currentItem?.id).toBe(1011);
    expect(result.current.currentIndex).toBe(510);
  });

  it('lookahead effect triggers fetchNextPage as the navigator approaches the oldest loaded item', async () => {
    // Page 0 = newest 500 rows (ids 1001..1500), Hibernate DESC by id.
    // Page 1 = next 500 older rows (ids 501..1000). Backend reports 3 total
    // pages so the hook keeps offering hasNextPage past page 0.
    getRunStepsPaged.mockImplementation(async (_run, _alias, page) => {
      if (page === 0) {
        const content = [] as Array<Record<string, unknown>>;
        for (let id = 1500; id >= 1001; id--) {
          content.push({ id, runId: RUN_PUB, stepAlias: ALIAS, epoch: 1, itemIndex: id, spawn: 0, status: 'COMPLETED' });
        }
        return { content, totalElements: 1500, totalPages: 3, page: 0, size: 500 };
      }
      if (page === 1) {
        const content = [] as Array<Record<string, unknown>>;
        for (let id = 1000; id >= 501; id--) {
          content.push({ id, runId: RUN_PUB, stepAlias: ALIAS, epoch: 1, itemIndex: id, spawn: 0, status: 'COMPLETED' });
        }
        return { content, totalElements: 1500, totalPages: 3, page: 1, size: 500 };
      }
      return { content: [], totalElements: 1500, totalPages: 3, page, size: 500 };
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () =>
        useRunOutputData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          enabled: true,
        }),
      { wrapper: wrap(client) },
    );

    // Wait for the first page to land (default currentIndex = items.length - 1
    // = the newest item; navigator at the right edge).
    await waitFor(() => {
      expect(result.current.items.length).toBe(500);
    });

    // Jump straight to an index within the LOOKAHEAD window (50) so the
    // lookahead effect fires exactly once and triggers a page-1 fetch. This
    // simulates the user scrolling back through ~450 items with the ◄ arrow.
    result.current.goToIndex(10);

    await waitFor(() => {
      const page1Calls = getRunStepsPaged.mock.calls.filter(c => c[2] === 1);
      expect(page1Calls.length).toBeGreaterThanOrEqual(1);
    }, { timeout: 2000 });
  });
});
