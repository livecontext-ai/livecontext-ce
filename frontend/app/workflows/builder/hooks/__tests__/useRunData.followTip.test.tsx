// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { useRunData } from '../useRunData';

// Regression coverage for the 2026-05-30 "inspector content stuck on the old
// epoch" bug, on the hook that backs the Input / Output / Parameter columns
// (RunDataPreview + ResolvedParamsView both consume useRunData). When a step
// re-runs, the run-step-data query refetches and a new (higher-id) row lands at
// the tip - the navigator count grows, but the seed effect used to keep the
// user anchored to the previous row's id, so the resolved data stayed on the
// stale epoch until a manual ◄ ►. Fix: if the user was viewing the tip, follow
// the new tip; if they had navigated into history, leave them anchored.

const RUN_PUB = 'run_xyz';
const RUN_UUID = 'uuid-2';
const ALIAS = 'send_email';

const getRunStepsPaged = vi.fn();
const getRun = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getRunStepsPaged: (...args: unknown[]) => getRunStepsPaged(...args),
    getRun: (...args: unknown[]) => getRun(...args),
    execution: {
      getStepOutputSkeleton: vi.fn().mockResolvedValue(null),
      getStepOutputObjectAtPath: vi.fn().mockResolvedValue(null),
      getStepOutputValueAtPath: vi.fn().mockResolvedValue(null),
    },
  },
}));

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));

// Mutable viewingEpoch so a test can flip it and assert the key-change reseed
// (the one behavioural difference between useRunData and its useRunOutputData
// twin: the seed-effect key includes viewingEpoch).
const mode = vi.hoisted(() => ({ viewingEpoch: null as number | null }));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ viewingEpoch: mode.viewingEpoch }),
}));

function wrap(client: QueryClient) {
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

function singlePage(ids: number[]) {
  const content = ids.map((id) => ({
    id,
    runId: RUN_PUB,
    stepAlias: ALIAS,
    epoch: 1,
    itemIndex: 0,
    spawn: 0,
    status: 'COMPLETED',
    inputData: { marker: id },
  }));
  return { content, totalElements: ids.length, totalPages: 1, page: 0, size: 500 };
}

describe('useRunData - follow the tip on re-run (2026-05-30 staleness regression)', () => {
  beforeEach(() => {
    getRunStepsPaged.mockReset();
    getRun.mockReset();
    getRun.mockResolvedValue({ id: RUN_UUID });
    mode.viewingEpoch = null;
  });

  it('advances to the freshly-executed row when the user was viewing the latest result', async () => {
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, dataType: 'output', enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));
    expect(result.current.currentItem?.id).toBe(12); // at the tip

    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-step-data'] });

    await waitFor(() => expect(result.current.items.length).toBe(4));

    expect(result.current.currentItem?.id).toBe(13);
    expect(result.current.currentIndex).toBe(3);
  });

  it('leaves the user anchored on the row they navigated to when a newer row arrives', async () => {
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, dataType: 'output', enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));

    result.current.goToIndex(0);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(10));

    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-step-data'] });

    await waitFor(() => expect(result.current.items.length).toBe(4));

    expect(result.current.currentItem?.id).toBe(10);
    expect(result.current.currentIndex).toBe(0);
  });

  it('re-enables follow-the-tip once the user navigates back to the newest row', async () => {
    let rows = [12, 11, 10];
    getRunStepsPaged.mockImplementation(async () => singlePage(rows));

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result } = renderHook(
      () => useRunData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, dataType: 'output', enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));

    result.current.goToIndex(1);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(11));

    // Re-run while browsing history → stays anchored.
    rows = [13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-step-data'] });
    await waitFor(() => expect(result.current.items.length).toBe(4));
    expect(result.current.currentItem?.id).toBe(11);

    // Back to the (new) tip → follow re-enabled → next re-run advances them.
    result.current.goToIndex(3);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(13));
    rows = [14, 13, 12, 11, 10];
    client.invalidateQueries({ queryKey: ['run-step-data'] });
    await waitFor(() => expect(result.current.items.length).toBe(5));
    expect(result.current.currentItem?.id).toBe(14);
    expect(result.current.currentIndex).toBe(4);
  });

  it('reseeds to the newest row of the new epoch when viewingEpoch changes (key-change branch)', async () => {
    // useRunData keys the seed effect on viewingEpoch; a change must reseed to
    // the tip + re-enable follow, even if the user had navigated into history.
    getRunStepsPaged.mockImplementation(async (_run, _alias, _page, _size, epoch) => {
      return epoch === 2 ? singlePage([22, 21]) : singlePage([12, 11, 10]);
    });

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result, rerender } = renderHook(
      () => useRunData({ workflowId: 'wf-1', runId: RUN_PUB, stepAlias: ALIAS, dataType: 'output', enabled: true }),
      { wrapper: wrap(client) },
    );

    await waitFor(() => expect(result.current.items.length).toBe(3));
    // Navigate into history (follow disabled) before the epoch switch.
    result.current.goToIndex(0);
    await waitFor(() => expect(result.current.currentItem?.id).toBe(10));

    // Switch the viewed epoch → key changes → fresh fetch + reseed to its tip.
    mode.viewingEpoch = 2;
    rerender();

    await waitFor(() => expect(result.current.items.length).toBe(2));
    expect(result.current.currentItem?.id).toBe(22); // newest of epoch 2, not anchored to old id 10
    expect(result.current.currentIndex).toBe(1);
  });
});
