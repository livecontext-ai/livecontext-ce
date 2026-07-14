// @vitest-environment jsdom
/**
 * Tests for the loaded-output publisher of {@link RunDataPreview}
 * (onLoadedOutputChange): the inspector lifts the currently loaded output
 * object up to the Output column header, where the settings menu offers
 * "Use as mock output" on it. The publisher must report null while loading
 * and on unmount so the menu never acts on stale or torn-down data.
 */
import * as React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
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
  workflowBuilder: {
    inspector: { item: 'Item', loading: 'Loading', noData: 'No data' },
    mock: { mockedBadge: 'Mocked' },
  },
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
    totalItems: 1,
    isLoading: false,
    error: null,
    currentIndex: 0,
    currentItem: { id: 100 },
    goToIndex: vi.fn(),
    getObjectAtPath: vi.fn(),
    availableStatuses: [],
    items: [],
    hasNext: false,
    hasPrev: false,
    goToNext: vi.fn(),
    goToPrev: vi.fn(),
    skeleton: null,
    isLoadingSkeleton: false,
    getValueAtPath: vi.fn(),
    refetch: vi.fn(),
  });
}

describe('RunDataPreview - loaded-output publisher', () => {
  beforeEach(resetHookState);

  it('publishes the loaded output object once the fetch lands (null while loading)', async () => {
    const output = { result: { score: 42 } };
    hookState.getObjectAtPath = vi.fn().mockResolvedValue(output);
    const onLoadedOutputChange = vi.fn();

    renderWithIntl(
      <RunDataPreview
        workflowId="wf"
        runId="run"
        stepAlias="prep"
        dataType="output"
        onLoadedOutputChange={onLoadedOutputChange}
      />,
    );

    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(output),
    );
    // Every call BEFORE the final one reported null (loading / initial state) -
    // the consumer never saw a half-loaded object.
    const calls = onLoadedOutputChange.mock.calls;
    for (const call of calls.slice(0, -1)) {
      expect(call[0]).toBeNull();
    }
  });

  it('publishes null on unmount so the header menu cannot act on torn-down data', async () => {
    const output = { result: { ok: true } };
    hookState.getObjectAtPath = vi.fn().mockResolvedValue(output);
    const onLoadedOutputChange = vi.fn();

    const view = renderWithIntl(
      <RunDataPreview
        workflowId="wf"
        runId="run"
        stepAlias="prep"
        dataType="output"
        onLoadedOutputChange={onLoadedOutputChange}
      />,
    );
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(output),
    );

    view.unmount();
    expect(onLoadedOutputChange).toHaveBeenLastCalledWith(null);
  });

  it('REGRESSION: switching to a step with ZERO items publishes null (no stale node-A output for node B)', async () => {
    // The preview instance is REUSED across node switches. Pre-fix, the load
    // effect early-returned on totalItems === 0 without clearing `data`, so
    // the header menu kept node A's output and could write it as node B's mock.
    const outputA = { result: { from: 'node-a' } };
    hookState.getObjectAtPath = vi.fn().mockResolvedValue(outputA);
    const onLoadedOutputChange = vi.fn();

    const view = renderWithIntl(
      <RunDataPreview
        workflowId="wf"
        runId="run"
        stepAlias="node-a"
        dataType="output"
        onLoadedOutputChange={onLoadedOutputChange}
      />,
    );
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(outputA),
    );

    // Node B: nothing executed for it in this run.
    hookState.totalItems = 0;
    hookState.currentItem = undefined;
    view.rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview
          workflowId="wf"
          runId="run"
          stepAlias="node-b"
          dataType="output"
          onLoadedOutputChange={onLoadedOutputChange}
        />
      </NextIntlClientProvider>,
    );

    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(null),
    );
  });

  it('publishes null while the items hook is loading and when it errors', async () => {
    const output = { result: { ok: 1 } };
    hookState.getObjectAtPath = vi.fn().mockResolvedValue(output);
    const onLoadedOutputChange = vi.fn();

    const view = renderWithIntl(
      <RunDataPreview
        workflowId="wf"
        runId="run"
        stepAlias="prep"
        dataType="output"
        onLoadedOutputChange={onLoadedOutputChange}
      />,
    );
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(output),
    );

    // Hook re-loading (e.g. run/step switch in flight).
    hookState.isLoading = true;
    view.rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview
          workflowId="wf"
          runId="run"
          stepAlias="prep"
          dataType="output"
          onLoadedOutputChange={onLoadedOutputChange}
        />
      </NextIntlClientProvider>,
    );
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(null),
    );

    // Hook error.
    hookState.isLoading = false;
    hookState.error = 'boom';
    view.rerender(
      <NextIntlClientProvider locale="en" messages={messages as any}>
        <RunDataPreview
          workflowId="wf"
          runId="run"
          stepAlias="prep"
          dataType="output"
          onLoadedOutputChange={onLoadedOutputChange}
        />
      </NextIntlClientProvider>,
    );
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(null),
    );
  });

  it('publishes null again when a failed fetch clears the data', async () => {
    hookState.getObjectAtPath = vi.fn().mockRejectedValue(new Error('boom'));
    const onLoadedOutputChange = vi.fn();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    renderWithIntl(
      <RunDataPreview
        workflowId="wf"
        runId="run"
        stepAlias="prep"
        dataType="output"
        onLoadedOutputChange={onLoadedOutputChange}
      />,
    );

    await waitFor(() => expect(hookState.getObjectAtPath).toHaveBeenCalled());
    await waitFor(() =>
      expect(onLoadedOutputChange).toHaveBeenLastCalledWith(null),
    );
    expect(
      onLoadedOutputChange.mock.calls.every((call) => call[0] === null),
    ).toBe(true);
    consoleError.mockRestore();
  });
});
