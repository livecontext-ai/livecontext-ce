/**
 * @vitest-environment jsdom
 *
 * The Run tab's LIST row prints the same duration as the waterfall and the tooltip
 * (stepDisplayDurationMs): per epoch, the time a node held the epoch (a parallel split is not
 * summed); across epochs, the cumulative total; nothing, never "<1s", for a skipped node.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/react';

const api = vi.hoisted(() => ({ getEpochAggregatedSteps: vi.fn() }));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseAggregatedSteps: vi.fn() },
}));
vi.mock('@/contexts/PublicationSnapshotContext', () => ({ getActivePublicPreview: () => null }));
vi.mock('@/components/workflow/StepRowActions', () => ({ StepRowActions: () => null }));
vi.mock('./../EpochSelector', () => ({ EpochSelector: () => null }));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => 'x',
  NodeIcon: () => null,
  nodeIconRadiusClass: () => 'rounded-md',
}));

import { RunStepsPanel } from '@/components/workflow/run-panel/RunStepsPanel';

afterEach(() => {
  cleanup();
  api.getEpochAggregatedSteps.mockReset();
});

const row = (container: HTMLElement, alias: string) =>
  container.querySelector(`[data-run-step-row="${alias}"]`) as HTMLElement;

function renderPanel(props: Record<string, unknown>) {
  return render(
    <RunStepsPanel
      currentRunInfo={{ runId: 'run-1' }}
      epochTimestamps={[]}
      onSelectEpoch={vi.fn()}
      workflowId="wf-1"
      {...(props as object)}
      selectedEpoch={(props.selectedEpoch as number | null) ?? null}
    />,
  );
}

describe('RunStepsPanel list row duration', () => {
  it('one epoch: prints the time a parallel split held the epoch, not its summed items', async () => {
    api.getEpochAggregatedSteps.mockResolvedValue([
      { alias: 'agent:summarize', status: 'completed', startTime: '2026-09-26T10:00:00Z', endTime: '2026-09-26T10:00:05Z',
        executionTimeMs: 50_000, elapsedMs: 5_000, statusCounts: { completed: 10 } },
    ]);

    const { container } = renderPanel({ selectedEpoch: 2, streamedSteps: [] });

    await waitFor(() => expect(row(container, 'agent:summarize')).not.toBeNull());
    expect(api.getEpochAggregatedSteps).toHaveBeenCalledWith('run-1', 2);
    expect(row(container, 'agent:summarize')).toHaveTextContent('· 5.0s');
    expect(row(container, 'agent:summarize')).not.toHaveTextContent('50s');
  });

  it('all epochs: prints the cumulative total, and nothing at all for a skipped node', () => {
    const { container } = renderPanel({
      selectedEpoch: null,
      streamedSteps: [
        { alias: 'mcp:fetch', status: 'completed', startTime: '2026-09-26T10:00:00Z', endTime: '2026-09-26T10:00:01Z',
          executionTimeMs: 800, totalExecutionTimeMs: 24_000, statusCounts: { completed: 30 } },
        { alias: 'core:branch', status: 'skipped', startTime: '2026-09-26T10:00:01Z', endTime: '2026-09-26T10:00:01Z',
          executionTimeMs: 0, statusCounts: { skipped: 3 } },
      ],
    });

    expect(row(container, 'mcp:fetch')).toHaveTextContent('· 24s');
    expect(row(container, 'core:branch')).not.toHaveTextContent('<1s');
    expect(row(container, 'core:branch')).not.toHaveTextContent('·');
  });
});
