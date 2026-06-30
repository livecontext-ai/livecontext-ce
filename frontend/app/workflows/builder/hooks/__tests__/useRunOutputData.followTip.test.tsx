// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { useRunOutputData } from '../useRunOutputData';

// Regression coverage for the 2026-05-30 "inspector content stuck on the old
// epoch" bug. When a step re-runs, the run-step-data query refetches and a new
// (higher-id) row lands at the tip - the navigator's "N / total" count grows,
// but the seed effect used to KEEP the user anchored to the previous row's id
// (the page-merge anchor), so the displayed content stayed on the stale epoch
// until the user manually stepped ◄ ►. The fix: if the user was viewing the tip
// (the latest result) when growth happens, follow to the new tip. A user who
// navigated back into history is still left anchored (pagination contract).

const RUN_PUB = 'run_abc';
const RUN_UUID = 'uuid-1';
const ALIAS = 'send_email';

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

// Single page, newest-first (Hibernate DESC by id). `ids` is given newest-first
// so the test reads naturally; totalElements/totalPages reflect a single page.
function singlePage(ids: number[]) {
  const content = ids.map((id) => ({
    id,
    runId: RUN_PUB,
    stepAlias: ALIAS,
    epoch: 1,
    itemIndex: 0,
    spawn: 0,
    status: 'COMPLETED',
  }));
  return { content, totalElements: ids.length, totalPages: 1, page: 0, size: 500 };
}

describe('useRunOutputData - follow the tip on re-run (2026-05-30 staleness regression)', () => {
  beforeEach(() => {
    getRunStepsPaged.mockReset();
    getRun.mockReset();
    getRun.mockResolvedValue({ id: RUN_UUID });
  });

  it('advances to the freshly-executed row when the user was viewing the latest result', async () => {
    // Start with rows 10,11,12 (12 = newest). After reverse → [10,11,12], the
    // seed effect lands currentIndex on the tip (id 12).
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunOutputData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));
    expect(result.current.currentItem?.id).toBe(12); // at the tip

    // A re-run completes: a new row (id 13) lands at the tip. Simulate the
    // refetch the stepExecutionCompleted invalidation would cause.
    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-output-data'] });

    await waitFor(() => expect(result.current.items.length).toBe(4));

    // The displayed item now follows the new tip - content is fresh, no manual
    // ◄ ► needed.
    expect(result.current.currentItem?.id).toBe(13);
    expect(result.current.currentIndex).toBe(3);
  });

  it('leaves the user anchored on the row they navigated to when a newer row arrives (history browsing)', async () => {
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunOutputData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));

    // User steps back to the oldest item (id 10, index 0) - deliberately leaving
    // the tip to inspect an earlier result.
    result.current.goToIndex(0);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(10));

    // A re-run appends id 13 at the tip.
    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-output-data'] });

    await waitFor(() => expect(result.current.items.length).toBe(4));

    // The user is NOT yanked to the tip - they stay on the row they were reading.
    expect(result.current.currentItem?.id).toBe(10);
    expect(result.current.currentIndex).toBe(0);
  });

  it('re-enables follow-the-tip once the user navigates back to the newest row', async () => {
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunOutputData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));

    // Step into history → follow disabled.
    result.current.goToIndex(1);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(11));

    // A re-run arrives while browsing history → stays anchored on id 11.
    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-output-data'] });
    await waitFor(() => expect(result.current.items.length).toBe(4));
    expect(result.current.currentItem?.id).toBe(11);

    // User navigates back to the (new) tip → follow re-enabled.
    result.current.goToIndex(3);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(13));

    // Another re-run now advances them automatically again.
    rows = [14, 13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-output-data'] });
    await waitFor(() => expect(result.current.items.length).toBe(5));
    expect(result.current.currentItem?.id).toBe(14);
    expect(result.current.currentIndex).toBe(4);
  });
});
