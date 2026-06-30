// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import { useRunData } from '../useRunData';
import {
  requestApprovalReview,
  clearApprovalReview,
} from '../../services/approvalReviewStore';

// Approval review: clicking a pending item in UserApprovalNode (or the
// ApprovalReviewBar auto-advance) publishes an (epoch, itemIndex) target -
// every mounted useRunData navigator must jump to the matching row so the
// Input/Params/Output columns all land on the SAME item. These tests pin the
// matching rules: epoch+itemIndex both honored, missing axes are permissive,
// latest spawn wins, re-requests re-apply, and unmatched targets stay pending
// until a refresh delivers the row.

const RUN_PUB = 'run_approval';
const RUN_UUID = 'uuid-appr';
const ALIAS = 'my_approval';

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

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ viewingEpoch: null }),
}));

function wrap(client: QueryClient) {
  const Wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  Wrapper.displayName = 'TestQueryClientWrapper';
  return Wrapper;
}

interface Row {
  id: number;
  epoch: number | null;
  itemIndex: number | null;
  spawn?: number;
}

// Backend sorts DESC by id (most recent first); rows arg given newest-first.
function page(rows: Row[]) {
  const content = rows.map((r) => ({
    id: r.id,
    runId: RUN_PUB,
    stepAlias: ALIAS,
    epoch: r.epoch,
    itemIndex: r.itemIndex,
    spawn: r.spawn ?? 0,
    status: 'AWAITING_SIGNAL',
    inputData: { marker: r.id },
  }));
  return { content, totalElements: rows.length, totalPages: 1, page: 0, size: 500 };
}

function mountHook() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return {
    client,
    ...renderHook(
      () =>
        useRunData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: ALIAS,
          dataType: 'input',
          enabled: true,
        }),
      { wrapper: wrap(client) },
    ),
  };
}

describe('useRunData - approval review target jump', () => {
  beforeEach(() => {
    getRunStepsPaged.mockReset();
    getRun.mockReset();
    getRun.mockResolvedValue({ id: RUN_UUID });
    clearApprovalReview();
  });

  it('jumps to the row matching (epoch, itemIndex) instead of staying on the tip', async () => {
    getRunStepsPaged.mockImplementation(async () =>
      page([
        { id: 13, epoch: 1, itemIndex: 2 },
        { id: 12, epoch: 1, itemIndex: 1 },
        { id: 11, epoch: 1, itemIndex: 0 },
      ]),
    );
    const { result } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(3));
    expect(result.current.currentItem?.id).toBe(13); // seeded at the tip

    act(() => requestApprovalReview('node-1', 1, 1));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(12));
  });

  it('honors the epoch axis when the same itemIndex exists in several epochs', async () => {
    getRunStepsPaged.mockImplementation(async () =>
      page([
        { id: 22, epoch: 2, itemIndex: 0 },
        { id: 11, epoch: 1, itemIndex: 0 },
      ]),
    );
    const { result } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(2));

    act(() => requestApprovalReview('node-1', 1, 0));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(11));
  });

  it('picks the latest spawn when an item was re-executed', async () => {
    getRunStepsPaged.mockImplementation(async () =>
      page([
        { id: 15, epoch: 1, itemIndex: 1, spawn: 1 },
        { id: 12, epoch: 1, itemIndex: 1, spawn: 0 },
      ]),
    );
    const { result } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(2));
    act(() => result.current.goToIndex(0));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(12));

    act(() => requestApprovalReview('node-1', 1, 1));
    // Both rows match - the scan from the newest end must pick id 15 (spawn 1).
    await waitFor(() => expect(result.current.currentItem?.id).toBe(15));
  });

  it('re-applies the jump when the user re-clicks the same item after navigating away', async () => {
    getRunStepsPaged.mockImplementation(async () =>
      page([
        { id: 13, epoch: 1, itemIndex: 2 },
        { id: 12, epoch: 1, itemIndex: 1 },
        { id: 11, epoch: 1, itemIndex: 0 },
      ]),
    );
    const { result } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(3));

    act(() => requestApprovalReview('node-1', 1, 1));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(12));

    act(() => result.current.goToIndex(0));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(11));

    act(() => requestApprovalReview('node-1', 1, 1)); // same coordinates, new requestId
    await waitFor(() => expect(result.current.currentItem?.id).toBe(12));
  });

  it('keeps an unmatched target pending and lands it when a refresh delivers the row', async () => {
    let rows: Row[] = [{ id: 11, epoch: 1, itemIndex: 0 }];
    getRunStepsPaged.mockImplementation(async () => page(rows));
    const { result, client } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(1));

    act(() => requestApprovalReview('node-1', 1, 4));
    // No row with itemIndex 4 yet - navigator must not move/crash.
    expect(result.current.currentItem?.id).toBe(11);

    rows = [
      { id: 15, epoch: 1, itemIndex: 4 },
      { id: 11, epoch: 1, itemIndex: 0 },
    ];
    act(() => {
      client.invalidateQueries({ queryKey: ['run-step-data'] });
    });
    await waitFor(() => expect(result.current.currentItem?.id).toBe(15));
  });

  it('re-applies the jump after the navigator switches to another step alias (cross-node consumption regression)', async () => {
    // The inspector panel is NOT remounted per node: the same useRunData
    // instance survives a node switch with a new stepAlias. A pseudo-match
    // against the PREVIOUS node's rows (single row, itemIndex null → matches
    // an item-0 target) must not consume the request for good - the jump must
    // re-apply once the approval node's own rows load.
    getRunStepsPaged.mockImplementation(async (_run, alias) =>
      alias === 'other_node'
        ? page([{ id: 99, epoch: 1, itemIndex: null }])
        : page([
            { id: 13, epoch: 1, itemIndex: 2 },
            { id: 12, epoch: 1, itemIndex: 1 },
            { id: 11, epoch: 1, itemIndex: 0 },
          ]),
    );
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { result, rerender } = renderHook(
      ({ alias }: { alias: string }) =>
        useRunData({
          workflowId: 'wf-1',
          runId: RUN_PUB,
          stepAlias: alias,
          dataType: 'input',
          enabled: true,
        }),
      { wrapper: wrap(client), initialProps: { alias: 'other_node' } },
    );
    await waitFor(() => expect(result.current.items.length).toBe(1));

    // Request lands while the OLD node is inspected - pseudo-match consumes it.
    act(() => requestApprovalReview('node-1', 1, 0));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(99));

    // The panel switches to the approval node's alias - the jump must re-apply.
    rerender({ alias: ALIAS });
    await waitFor(() => expect(result.current.currentItem?.id).toBe(11));
  });

  it('does not drift off the reviewed item when a newer row is appended after the jump (follow-tip regression)', async () => {
    let rows: Row[] = [
      { id: 13, epoch: 1, itemIndex: 2 },
      { id: 12, epoch: 1, itemIndex: 1 },
      { id: 11, epoch: 1, itemIndex: 0 },
    ];
    getRunStepsPaged.mockImplementation(async () => page(rows));
    const { result, client } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(3));

    // Jump to the LAST item (the tip) - would normally re-enable follow-tip.
    act(() => requestApprovalReview('node-1', 1, 2));
    await waitFor(() => expect(result.current.currentItem?.id).toBe(13));

    // A re-run appends a newer row: the navigator must stay on the reviewed
    // item, not follow the new tip.
    rows = [{ id: 20, epoch: 2, itemIndex: 0 }, ...rows];
    act(() => {
      client.invalidateQueries({ queryKey: ['run-step-data'] });
    });
    await waitFor(() => expect(result.current.items.length).toBe(4));
    expect(result.current.currentItem?.id).toBe(13);
  });

  it('ignores a missing axis: itemIndex-only target still matches rows without epoch mismatch', async () => {
    // A pre-split parent column has a single row with no itemIndex - a target
    // with itemIndex=2 must NOT move it (no match), while a null-itemIndex
    // target matches anything on that axis.
    getRunStepsPaged.mockImplementation(async () =>
      page([{ id: 30, epoch: 1, itemIndex: null }]),
    );
    const { result } = mountHook();
    await waitFor(() => expect(result.current.items.length).toBe(1));

    act(() => requestApprovalReview('node-1', null, 0));
    // (itemIndex ?? 0) === 0 → the single row matches an itemIndex-0 target.
    await waitFor(() => expect(result.current.currentItem?.id).toBe(30));
  });
});
