/**
 * @vitest-environment jsdom
 *
 * The Analysis tab: one call feeds the KPIs, the chart, the nodes x epochs grid and the
 * two-epoch comparison. These tests pin what the user reads and what each control does.
 */
import React from 'react';
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RunAnalysis } from '@/lib/api/orchestrator/types';

const api = vi.hoisted(() => ({ getRunAnalysis: vi.fn() }));
const setViewingEpoch = vi.hoisted(() => vi.fn());
const markEpochPickedByUser = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string, params?: Record<string, unknown>) =>
    `${ns ? `${ns}.` : ''}${k}${params ? `(${Object.values(params).join(',')})` : ''}`,
}));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isReady: true, isAuthenticated: true }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ setViewingEpoch }) }));
vi.mock('@/lib/format-cost', () => ({ formatCostCompact: (v: number) => `c${v}` }));
vi.mock('@/app/workflows/builder/services/canvasNodesStore', () => ({
  getCanvasNodes: () => [],
  getCanvasEdges: () => [],
  subscribeCanvasNodes: () => () => {},
}));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => '',
  NodeIcon: () => <span />,
  nodeIconRadiusClass: () => 'rounded',
}));
vi.mock('../useDefaultEpochSelection', () => ({ markEpochPickedByUser }));
vi.mock('../RunSummaryBar', () => ({
  RunSummaryBar: (props: { leading?: React.ReactNode; trailing?: React.ReactNode }) => (
    <div data-testid="summary">{props.leading}{props.trailing}</div>
  ),
}));
// jsdom has no layout: the chart library renders nothing measurable, so the chart is a marker
// that exposes its bars as buttons to test the "click a bar" path.
vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <>{children}</>;
  return {
    ResponsiveContainer: Pass,
    ComposedChart: ({ children, data }: { children?: React.ReactNode; data: Array<{ epoch: number; durationMs: number | null }> }) => (
      <div data-testid="chart">
        {data.map(d => <span key={d.epoch} data-bar={d.epoch} data-duration={String(d.durationMs)} />)}
        {children}
      </div>
    ),
    Bar: ({ onClick }: { onClick: (e: { payload: { epoch: number } }) => void }) => (
      <button type="button" data-testid="bar-epoch-1" onClick={() => onClick({ payload: { epoch: 1 } })}>bar</button>
    ),
    Cell: () => null,
    Line: () => null,
    XAxis: () => null,
    YAxis: () => null,
    CartesianGrid: () => null,
    Tooltip: () => null,
  };
});

// Pass-through spy: counts how many grid cells get (re)computed, to prove a hover does not redraw the grid.
vi.mock('../runAnalysis', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../runAnalysis')>();
  return { ...actual, cellStatus: vi.fn(actual.cellStatus) };
});

import { RunAnalysisPanelContent } from '../RunAnalysisPanelContent';
import { cellStatus } from '../runAnalysis';
import { clearRunPanelCache, makeEmptyRunPanelData, publishRunPanelData } from '../runPanelBus';

const ANALYSIS: RunAnalysis = {
  runId: 'run-1',
  totalEpochs: 12,
  epochs: [
    {
      epoch: 1, startedAt: '2026-09-26T10:00:00Z', endedAt: '2026-09-26T10:00:05Z', workDurationMs: 2000, status: 'COMPLETED', costCredits: 1,
      nodes: [
        { alias: 'mcp:fetch', status: 'completed', executionTimeMs: 1000, elapsedMs: 1000 },
        { alias: 'mcp:send', status: 'completed', executionTimeMs: 500, elapsedMs: 500 },
      ],
    },
    {
      epoch: 2, startedAt: '2026-09-26T10:30:00Z', endedAt: '2026-09-26T10:30:01Z', workDurationMs: 4000, status: 'FAILED', costCredits: 3,
      nodes: [
        { alias: 'mcp:fetch', status: 'error', executionTimeMs: 300, elapsedMs: 300, errorMessage: 'HTTP 429' },
        { alias: 'mcp:send', status: 'skipped', executionTimeMs: 0, elapsedMs: 0 },
      ],
    },
  ],
};

function renderPanel(onBack = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <RunAnalysisPanelContent workflowId="wf-1" runId="run-1" onBack={onBack} />
    </QueryClientProvider>,
  );
  return onBack;
}

beforeEach(() => {
  publishRunPanelData({
    ...makeEmptyRunPanelData('wf-1'),
    runId: 'run-1',
    runInfo: { runId: 'run-1', status: 'WAITING_TRIGGER' },
    epochTimestamps: [{ epoch: 1, startedAt: 'a', endedAt: 'b' }, { epoch: 2, startedAt: 'c', endedAt: 'd' }],
  } as never);
  api.getRunAnalysis.mockResolvedValue(ANALYSIS);
});

afterEach(() => {
  cleanup();
  clearRunPanelCache();
  api.getRunAnalysis.mockReset();
  setViewingEpoch.mockReset();
  markEpochPickedByUser.mockReset();
});

describe('RunAnalysisPanelContent', () => {
  it('asks for the default 60-epoch window and shows the KPIs of what came back', async () => {
    renderPanel();

    await screen.findByTestId('chart');
    expect(api.getRunAnalysis).toHaveBeenCalledWith('run-1', 60);
    const kpis = document.querySelector('[data-run-analysis-kpis]') as HTMLElement;
    expect(kpis).toHaveTextContent('50%');                      // 1 completed, 1 failed
    expect(kpis).toHaveTextContent('workflow.runAnalysis.kpi.failedCount(1)');
    expect(kpis).toHaveTextContent('3.0s');                     // mean of 2 s and 4 s
    expect(kpis).toHaveTextContent('c4');                       // 1 + 3
    // The window is said once, next to its picker, not repeated in a tile.
    expect(kpis).not.toHaveTextContent('windowOf');
    expect(screen.getByText('workflow.runAnalysis.windowOf(2,12)')).toBeInTheDocument();
  });

  it('draws one grid row per node with one cell per epoch, coloured by status', async () => {
    renderPanel();

    await screen.findByTestId('chart');
    const rowLabels = [...document.querySelectorAll('[data-run-analysis-row]')].map(r => r.getAttribute('data-run-analysis-row'));
    expect(rowLabels).toEqual(['mcp:fetch', 'mcp:send']);
    const cells = document.querySelectorAll('[data-run-analysis-grid] [data-cell-status]');
    expect([...cells].map(c => c.getAttribute('data-cell-status'))).toEqual(['ok', 'failed', 'ok', 'skipped']);
  });

  it('names the failing node and its latest error', async () => {
    renderPanel();

    const insight = await screen.findByText(/insight\.failing/);
    expect(insight).toHaveTextContent('workflow.runAnalysis.insight.failing(fetch,1,2)');
    expect(screen.getByText(/insight\.lastError/)).toHaveTextContent('HTTP 429');
  });

  it('opens the comparison on the latest failure against the success before it, and flags what changed', async () => {
    renderPanel();

    const compare = await waitFor(() => {
      const el = document.querySelector('[data-run-analysis-compare]') as HTMLElement;
      expect(el.querySelector('[data-compare-row]')).not.toBeNull();
      return el;
    });
    expect(compare.querySelector('[data-show-epoch-on-canvas="1"]')).not.toBeNull();
    expect(compare.querySelector('[data-show-epoch-on-canvas="2"]')).not.toBeNull();
    expect(compare.querySelector('[data-compare-row="mcp:fetch"]')).toHaveAttribute('data-differs', 'true');
    expect(within(compare).getByText('HTTP 429')).toBeInTheDocument();
  });

  it('re-targets the comparison when a grid cell is clicked', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    // Clicking epoch 1 (a success, the oldest) compares it against the next epoch.
    const firstCell = document.querySelector('[data-run-analysis-grid] [data-cell-status]') as HTMLElement;
    fireEvent.click(firstCell);

    const compare = document.querySelector('[data-run-analysis-compare]') as HTMLElement;
    const headers = compare.querySelectorAll('thead [data-show-epoch-on-canvas]');
    expect([...headers].map(h => h.getAttribute('data-show-epoch-on-canvas'))).toEqual(['2', '1']);
  });

  it('re-targets the comparison when a chart bar is clicked', async () => {
    renderPanel();
    fireEvent.click(await screen.findByTestId('bar-epoch-1'));

    const headers = document.querySelectorAll('[data-run-analysis-compare] thead [data-show-epoch-on-canvas]');
    expect([...headers].map(h => h.getAttribute('data-show-epoch-on-canvas'))).toEqual(['2', '1']);
  });

  it('"show on canvas" selects that epoch for the run and returns to the Run tab', async () => {
    const onBack = renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(document.querySelector('[data-show-epoch-on-canvas="2"]') as HTMLElement);

    expect(markEpochPickedByUser).toHaveBeenCalledWith('run-1', 2);
    expect(setViewingEpoch).toHaveBeenCalledWith(2);
    expect(onBack).toHaveBeenCalled();
  });

  it('the header arrow goes back to the Run tab', async () => {
    const onBack = renderPanel();
    fireEvent.click(await waitFor(() => document.querySelector('[data-run-analysis-back]') as HTMLElement));
    expect(onBack).toHaveBeenCalled();
  });

  it('asks for another window when one is picked', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(screen.getByRole('button', { name: 'workflow.runAnalysis.windowOption(200)' }));

    await waitFor(() => expect(api.getRunAnalysis).toHaveBeenCalledWith('run-1', 200));
  });

  it('says so when the run has no epoch yet', async () => {
    api.getRunAnalysis.mockResolvedValue({ runId: 'run-1', totalEpochs: 0, epochs: [] });
    renderPanel();

    await waitFor(() => expect(document.querySelector('[data-run-analysis-empty]')).not.toBeNull());
    expect(screen.queryByTestId('chart')).toBeNull();
  });

  it('shows an error with a retry that calls the endpoint again', async () => {
    api.getRunAnalysis.mockRejectedValueOnce(new Error('boom'));
    renderPanel();

    const retry = await screen.findByRole('button', { name: 'workflow.runAnalysis.retry' });
    api.getRunAnalysis.mockResolvedValue(ANALYSIS);
    fireEvent.click(retry);

    await screen.findByTestId('chart');
    expect(api.getRunAnalysis).toHaveBeenCalledTimes(2);
  });

  it('refetches when the newest epoch CLOSES, though the epoch count did not move', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    expect(api.getRunAnalysis).toHaveBeenCalledTimes(1);

    act(() => {
      publishRunPanelData({
        ...makeEmptyRunPanelData('wf-1'),
        runId: 'run-1',
        runInfo: { runId: 'run-1', status: 'WAITING_TRIGGER' },
        epochTimestamps: [{ epoch: 1, startedAt: 'a', endedAt: 'b' }, { epoch: 2, startedAt: 'c', endedAt: 'z-closed-later' }],
      } as never);
    });

    await waitFor(() => expect(api.getRunAnalysis).toHaveBeenCalledTimes(2));
  });

  it('polls while the run is executing, and only then', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      renderPanel();
      await screen.findByTestId('chart');
      const idleCalls = api.getRunAnalysis.mock.calls.length;
      await act(async () => { vi.advanceTimersByTime(6_000); });
      expect(api.getRunAnalysis.mock.calls.length, 'a run parked on its trigger is not polled').toBe(idleCalls);

      act(() => {
        publishRunPanelData({
          ...makeEmptyRunPanelData('wf-1'),
          runId: 'run-1',
          runInfo: { runId: 'run-1', status: 'RUNNING' },
          epochTimestamps: [{ epoch: 1, startedAt: 'a', endedAt: 'b' }, { epoch: 2, startedAt: 'c', endedAt: 'd' }],
        } as never);
      });
      await waitFor(() => expect(api.getRunAnalysis.mock.calls.length).toBe(idleCalls + 1)); // the status change
      await act(async () => { vi.advanceTimersByTime(5_100); });
      await waitFor(() => expect(api.getRunAnalysis.mock.calls.length).toBeGreaterThanOrEqual(idleCalls + 2));
    } finally {
      vi.useRealTimers();
    }
  });

  it('says epochs failed without a failing node rather than "no failure"', async () => {
    api.getRunAnalysis.mockResolvedValue({
      runId: 'run-1', totalEpochs: 2, epochs: [
        { ...ANALYSIS.epochs[0] },
        { ...ANALYSIS.epochs[1], nodes: [{ alias: 'mcp:fetch', status: 'completed', executionTimeMs: 10, elapsedMs: 10 }] },
      ],
    });
    renderPanel();

    const insights = await waitFor(() => {
      const el = document.querySelector('[data-run-analysis-insights]') as HTMLElement;
      expect(el).not.toBeNull();
      return el;
    });
    expect(insights.querySelector('[data-insight="epoch-failures"]')).toHaveTextContent('workflow.runAnalysis.insight.epochFailures(1)');
    expect(insights).not.toHaveTextContent('insight.none');
  });

  it('draws no bar for an epoch without a measured duration (never a zero)', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS, epochs: [ANALYSIS.epochs[0], { ...ANALYSIS.epochs[1], workDurationMs: null }],
    });
    renderPanel();

    await screen.findByTestId('chart');
    expect(document.querySelector('[data-bar="2"]')).toHaveAttribute('data-duration', 'null');
    expect(document.querySelector('[data-bar="1"]')).toHaveAttribute('data-duration', '2000');
  });

  it('recolours successful cells by duration relative to their own row, keeping failures red', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(screen.getByRole('button', { name: 'workflow.runAnalysis.grid.byDuration' }));

    const fetchCells = [...document.querySelectorAll('[data-run-analysis-grid] [data-cell-status]')].slice(0, 2);
    expect(fetchCells[0].className, 'the slowest success of its row').toContain('bg-indigo-800');
    expect(fetchCells[1].className, 'a failure stays a failure').toContain('bg-red-500');
  });

  it('describes the hovered cell: node, epoch, status, time, and the summed work of parallel items', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [{ ...ANALYSIS.epochs[0], nodes: [
        { alias: 'mcp:fetch', status: 'completed', executionTimeMs: 50_000, elapsedMs: 5_000, statusCounts: { completed: 10 } },
      ] }, ANALYSIS.epochs[1]],
    });
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.mouseEnter(document.querySelector('[data-run-analysis-grid] [data-cell-status]') as HTMLElement);

    const detail = document.querySelector('[data-run-analysis-hover]') as HTMLElement;
    expect(detail).toHaveTextContent('fetch');
    expect(detail).toHaveTextContent('5.0s');
    expect(detail).toHaveTextContent('workflow.runAnalysis.grid.items(10)');
    expect(detail).toHaveTextContent('workflow.runAnalysis.grid.totalWork(50s)');
  });

  it('does not count skipped items as executions in the hovered-cell line', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [{ ...ANALYSIS.epochs[0], nodes: [
        { alias: 'mcp:fetch', status: 'completed', executionTimeMs: 1_000, elapsedMs: 1_000, statusCounts: { completed: 1, skipped: 4 } },
      ] }, ANALYSIS.epochs[1]],
    });
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.mouseEnter(document.querySelector('[data-run-analysis-grid] [data-cell-status]') as HTMLElement);

    expect(document.querySelector('[data-run-analysis-hover]')).not.toHaveTextContent('grid.items');
  });

  it('keeps a legend entry for every colour each mode can draw', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    const legend = () => (document.querySelector('[data-run-analysis-grid]')?.parentElement?.textContent ?? '');

    for (const key of ['ok', 'failed', 'partial', 'skipped', 'running', 'waiting', 'none']) {
      expect(legend()).toContain(`legend.${key}`);
    }
    fireEvent.click(screen.getByRole('button', { name: 'workflow.runAnalysis.grid.byDuration' }));
    for (const key of ['fast', 'slow', 'failed', 'partial', 'skipped', 'running', 'waiting', 'none']) {
      expect(legend()).toContain(`legend.${key}`);
    }
  });

  it('hovering a cell redraws the one-line detail, not the grid', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    const cells = document.querySelectorAll('[data-run-analysis-grid] [data-cell-status]');
    expect(cells.length).toBe(4);

    vi.mocked(cellStatus).mockClear();
    fireEvent.mouseEnter(cells[1] as HTMLElement);
    fireEvent.mouseEnter(cells[2] as HTMLElement);

    // One lookup per hover for the detail line; a grid redraw would add one per cell (4 each time).
    expect(vi.mocked(cellStatus).mock.calls.length).toBeLessThanOrEqual(2);
    expect(document.querySelector('[data-run-analysis-hover]')).toHaveTextContent('send');
  });

  it('refetches when the run gains an epoch', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    expect(api.getRunAnalysis).toHaveBeenCalledTimes(1);

    publishRunPanelData({
      ...makeEmptyRunPanelData('wf-1'),
      runId: 'run-1',
      runInfo: { runId: 'run-1', status: 'WAITING_TRIGGER' },
      epochTimestamps: [{ epoch: 1, startedAt: 'a', endedAt: 'b' }, { epoch: 2, startedAt: 'c', endedAt: 'd' }, { epoch: 3, startedAt: 'e', endedAt: 'f' }],
    } as never);

    await waitFor(() => expect(api.getRunAnalysis).toHaveBeenCalledTimes(2));
  });
});
