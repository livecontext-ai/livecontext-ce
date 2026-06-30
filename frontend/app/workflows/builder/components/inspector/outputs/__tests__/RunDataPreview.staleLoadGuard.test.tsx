// @vitest-environment jsdom
/**
 * Regression tests for the out-of-order lazy-load guard in
 * {@link RunDataPreview}. Before the fix, the content effect fired one
 * {@code getObjectAtPath('')} per navigation with NO staleness guard: when the
 * navigator moved while a fetch was in flight (approval-review targeted jump,
 * follow-tip reposition after a page merge), the SLOW response for the old row
 * could resolve last and overwrite the data of the row the navigator now
 * points at - label said "Item 2 / 3" while the tree showed item 1's payload.
 *
 * The fix: a loadSeqRef sequence token discards stale responses, and the
 * effect is additionally keyed on currentItem.id so swapping WHICH row sits at
 * the same index re-fetches.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));
vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

const hookState: any = {};
vi.mock('../../../../hooks/useRunData', () => ({
  useRunData: () => hookState,
}));

import { RunDataPreview } from '../RunDataPreview';

const messages = {
  workflowBuilder: { inspector: { item: 'Item', loading: 'Loading', noData: 'No data' } },
  dataTable: { allStatuses: 'All' },
};

function renderWithIntl(ui: React.ReactNode) {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      {ui}
    </NextIntlClientProvider>,
  );
}

function resetHookState() {
  Object.assign(hookState, {
    totalItems: 3,
    isLoading: false,
    error: null,
    currentIndex: 0,
    currentItem: { id: 100 },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(),
    availableStatuses: [],
    items: [],
    hasNext: true,
    hasPrev: false,
    goToNext: vi.fn(),
    goToPrev: vi.fn(),
    skeleton: null,
    isLoadingSkeleton: false,
    getValueAtPath: vi.fn(),
    refetch: vi.fn(),
  });
}

describe('RunDataPreview - stale lazy-load guard', () => {
  beforeEach(resetHookState);

  it('discards a slow stale response that resolves after a newer navigation', async () => {
    let resolveSlow: (v: unknown) => void = () => {};
    const slow = new Promise((resolve) => { resolveSlow = resolve; });
    // First render (row id 100) → slow fetch; after navigation (row id 200)
    // → fast fetch.
    hookState.getObjectAtPath = vi
      .fn()
      .mockImplementationOnce(() => slow)
      .mockImplementationOnce(async () => ({ marker: 'row-200' }));

    const { rerender } = renderWithIntl(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />,
    );

    // Navigate to another row while the first fetch is still in flight.
    hookState.currentIndex = 1;
    hookState.currentItem = { id: 200 };
    rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />
      </NextIntlClientProvider>,
    );

    await waitFor(() => expect(screen.getByText(/row-200/)).toBeTruthy());

    // The OLD row's fetch finally resolves - it must NOT overwrite the data.
    await act(async () => {
      resolveSlow({ marker: 'row-100-stale' });
    });
    expect(screen.queryByText(/row-100-stale/)).toBeNull();
    expect(screen.getByText(/row-200/)).toBeTruthy();
  });

  it('re-fetches when the row at the same index changes identity (page merge / targeted jump)', async () => {
    hookState.getObjectAtPath = vi
      .fn()
      .mockImplementationOnce(async () => ({ marker: 'row-100' }))
      .mockImplementationOnce(async () => ({ marker: 'row-300' }));

    const { rerender } = renderWithIntl(
      <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />,
    );
    await waitFor(() => expect(screen.getByText(/row-100/)).toBeTruthy());

    // Same index, different underlying row (anchored reposition after merge).
    hookState.currentItem = { id: 300 };
    rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview workflowId="wf" runId="run" stepAlias="prep" dataType="output" />
      </NextIntlClientProvider>,
    );

    await waitFor(() => expect(screen.getByText(/row-300/)).toBeTruthy());
    expect(hookState.getObjectAtPath).toHaveBeenCalledTimes(2);
  });
});
