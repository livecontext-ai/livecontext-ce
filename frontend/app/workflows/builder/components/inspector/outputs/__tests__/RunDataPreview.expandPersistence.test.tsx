// @vitest-environment jsdom
/**
 * Regression tests for the inspector tree expand/collapse persistence in
 * {@link RunDataPreview}. Before the fix, every {@code JsonNode} owned its own
 * {@code useState(false)} for the chevron-rotated expanded flag. When the user
 * navigated to a different item via {@code ItemNavigator} (e.g. 35 → 34),
 * {@code data} swapped in {@code RunDataPreview} → all child {@code JsonNode}
 * components were re-instantiated → all {@code useState(false)} reset → every
 * branch the user had opened collapsed silently.
 *
 * The fix lifts a {@code Set<string>} of expanded JSON paths up to
 * {@link RunDataPreview} and threads it down through props. The Set survives
 * the data swap.
 *
 * A second guard: when the user switches to a different step / run / column,
 * the Set MUST be cleared - otherwise paths opened on node A would re-apply
 * to node B's structurally-similar tree.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

// Mock the hook entirely so the test controls (data, currentIndex, totalItems)
// without spinning up react-query. Each test reassigns this object to swap the
// data RunDataPreview sees, simulating an item navigation.
const hookState: any = {
  totalItems: 2,
  isLoading: false,
  error: null,
  currentIndex: 0,
  goToIndex: vi.fn(),
  getObjectAtPath: vi.fn(),
  availableStatuses: [],
  items: [],
  hasNext: true,
  hasPrev: false,
  goToNext: vi.fn(),
  goToPrev: vi.fn(),
  currentItem: null,
  skeleton: null,
  isLoadingSkeleton: false,
  getValueAtPath: vi.fn(),
  refetch: vi.fn(),
};
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

describe('RunDataPreview - expand/collapse persistence', () => {
  beforeEach(() => {
    hookState.totalItems = 2;
    hookState.currentIndex = 0;
    hookState.getObjectAtPath = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: 'item-35' }] } }],
    });
  });

  it('keeps an expanded object branch open when the user navigates from item 35 to item 34', async () => {
    const { rerender } = renderWithIntl(
      <RunDataPreview
        workflowId="wf-1"
        runId="run-1"
        stepAlias="mcp:step"
        dataType="output"
      />,
    );

    // Wait for the async data fetch to resolve and render the tree.
    await screen.findByText('candidates');

    // Click the "candidates" row to expand. The chevron is inside the same
    // clickable row, so a click on the row toggles the path.
    fireEvent.click(screen.getByText('candidates'));
    // The first child of the array (`0`) is rendered after expansion.
    await screen.findByText('0');

    // Simulate "navigate to item 34": currentIndex changes, the hook returns
    // a new payload from getObjectAtPath. Triggering a useEffect-driven reload
    // is what the real component does on currentIndex change.
    hookState.currentIndex = 1;
    hookState.getObjectAtPath = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: 'item-34' }] } }],
    });
    await act(async () => {
      rerender(
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <RunDataPreview
            workflowId="wf-1"
            runId="run-1"
            stepAlias="mcp:step"
            dataType="output"
          />
        </NextIntlClientProvider>,
      );
      // Let the loadData() effect resolve.
      await Promise.resolve();
      await Promise.resolve();
    });

    // Same data SHAPE (different leaf text), and the expansion was preserved:
    // `candidates` is still expanded → the array index `0` row is still in DOM.
    await screen.findByText('candidates');
    expect(screen.getByText('0')).toBeTruthy();
  });

  it('clears the expand state when the user switches to a different stepAlias (no bleed across nodes)', async () => {
    const { rerender } = renderWithIntl(
      <RunDataPreview
        workflowId="wf-1"
        runId="run-1"
        stepAlias="mcp:step_a"
        dataType="output"
      />,
    );

    await screen.findByText('candidates');
    fireEvent.click(screen.getByText('candidates'));
    await screen.findByText('0');

    // Switch to a different node that happens to have the same top-level shape.
    // Without the reset effect, the `candidates` path from step_a would carry
    // over and auto-expand step_b - which the user never asked to see.
    hookState.currentIndex = 0;
    hookState.getObjectAtPath = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: 'step-b-data' }] } }],
    });
    await act(async () => {
      rerender(
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <RunDataPreview
            workflowId="wf-1"
            runId="run-1"
            stepAlias="mcp:step_b"
            dataType="output"
          />
        </NextIntlClientProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    await screen.findByText('candidates');
    // `0` (the array's first element) is NOT visible because the path map was
    // reset on stepAlias change → `candidates` is collapsed in step_b.
    expect(screen.queryByText('0')).toBeNull();
  });

  it('clears the expand state when runId changes (re-running the same workflow must not bleed open branches into the new run)', async () => {
    const { rerender } = renderWithIntl(
      <RunDataPreview
        workflowId="wf-1"
        runId="run-1"
        stepAlias="mcp:step"
        dataType="output"
      />,
    );

    await screen.findByText('candidates');
    fireEvent.click(screen.getByText('candidates'));
    await screen.findByText('0');

    hookState.getObjectAtPath = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: 'run-2' }] } }],
    });
    await act(async () => {
      rerender(
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <RunDataPreview
            workflowId="wf-1"
            runId="run-2"
            stepAlias="mcp:step"
            dataType="output"
          />
        </NextIntlClientProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    await screen.findByText('candidates');
    expect(screen.queryByText('0')).toBeNull();
  });

  it('clears the expand state when dataType flips (input ↔ output never share open paths)', async () => {
    const { rerender } = renderWithIntl(
      <RunDataPreview
        workflowId="wf-1"
        runId="run-1"
        stepAlias="mcp:step"
        dataType="output"
      />,
    );

    await screen.findByText('candidates');
    fireEvent.click(screen.getByText('candidates'));
    await screen.findByText('0');

    // Same step, but switching to the input column. The two columns show
    // different shapes (resolved_params vs the actual output payload) so
    // expanded paths must NOT carry over.
    hookState.getObjectAtPath = vi.fn().mockResolvedValue({
      candidates: [{ content: { parts: [{ text: 'input-side' }] } }],
    });
    await act(async () => {
      rerender(
        <NextIntlClientProvider locale="en" messages={messages as any}>
          <RunDataPreview
            workflowId="wf-1"
            runId="run-1"
            stepAlias="mcp:step"
            dataType="input"
          />
        </NextIntlClientProvider>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    await screen.findByText('candidates');
    expect(screen.queryByText('0')).toBeNull();
  });

  it('toggles a branch closed when re-clicked (the lifted Set must support delete, not just add)', async () => {
    renderWithIntl(
      <RunDataPreview
        workflowId="wf-1"
        runId="run-1"
        stepAlias="mcp:step"
        dataType="output"
      />,
    );

    await screen.findByText('candidates');
    fireEvent.click(screen.getByText('candidates'));
    await screen.findByText('0');

    fireEvent.click(screen.getByText('candidates'));
    expect(screen.queryByText('0')).toBeNull();

    // Re-open: the toggle path is reversible (Set.delete then Set.add again).
    fireEvent.click(screen.getByText('candidates'));
    await screen.findByText('0');
  });
});
