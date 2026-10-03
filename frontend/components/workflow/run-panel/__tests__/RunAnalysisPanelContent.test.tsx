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
const selectAllEpochs = vi.hoisted(() => vi.fn((_runId: string, set: (e: number | null) => void) => set(null)));
const setRunId = vi.hoisted(() => vi.fn());
const restoreEpoch = vi.hoisted(() => vi.fn());
/** What the mode provider exposes. Empty = the no-provider stub (workflowId undefined): picks stay local. */
const mode = vi.hoisted(() => ({ value: {} as Record<string, unknown> }));
/** What the chart last drew and the props its tooltip got, to render that tooltip for a column. */
const chart = vi.hoisted(() => ({ data: [] as Array<Record<string, unknown>>, tooltip: null as null | Record<string, any> }));

vi.mock('@/lib/api', () => ({ orchestratorApi: api }));
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string, params?: Record<string, unknown>) =>
    `${ns ? `${ns}.` : ''}${k}${params ? `(${Object.values(params).join(',')})` : ''}`,
}));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isReady: true, isAuthenticated: true }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => ({ setViewingEpoch, setRunId, ...mode.value }) }));
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
vi.mock('../useDefaultEpochSelection', () => ({ markEpochPickedByUser, selectAllEpochs, useDefaultEpochSelection: restoreEpoch }));
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
    // A click anywhere in a column reaches the chart's onClick with that column's index, as
    // recharts reports it: each column is a button here.
    ComposedChart: ({ children, data, onClick }: {
      children?: React.ReactNode;
      data: Array<{ epoch: number; durationMs: number | null }>;
      onClick?: (state: { activeIndex?: number; activeLabel?: number }) => void;
    }) => {
      chart.data = data;
      return (
      <div data-testid="chart">
        {data.map((d, i) => (
          <button
            key={d.epoch}
            type="button"
            data-bar={d.epoch}
            data-testid={`column-epoch-${d.epoch}`}
            data-duration={String(d.durationMs)}
            onClick={() => onClick?.({ activeIndex: i, activeLabel: d.epoch })}
          />
        ))}
        {children}
      </div>
      );
    },
    Bar: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
    Cell: (props: { 'data-epoch-chart-bar'?: number; fillOpacity?: number }) => (
      <span data-chart-cell={props['data-epoch-chart-bar']} data-opacity={String(props.fillOpacity)} />
    ),
    Line: () => null,
    XAxis: () => null,
    YAxis: (props: { yAxisId?: string; width?: number | string; ticks?: number[] }) => (
      <span data-y-axis={props.yAxisId} data-width={String(props.width)} data-ticks={JSON.stringify(props.ticks ?? null)} />
    ),
    CartesianGrid: () => null,
    Tooltip: (props: Record<string, unknown>) => { chart.tooltip = props; return null; },
  };
});

// Pass-through spy: counts how many grid cells get (re)computed, to prove a hover does not redraw the grid.
vi.mock('../runAnalysis', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../runAnalysis')>();
  return { ...actual, cellStatus: vi.fn(actual.cellStatus) };
});

import { RunAnalysisPanelContent, durationAxis, epochFromChartState } from '../RunAnalysisPanelContent';
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

function renderPanel(onBack = vi.fn(), onOpenLogs?: () => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <RunAnalysisPanelContent workflowId="wf-1" runId="run-1" onBack={onBack} onOpenLogs={onOpenLogs} />
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
  mode.value = {};
  setViewingEpoch.mockClear();
  markEpochPickedByUser.mockClear();
  selectAllEpochs.mockClear();
  setRunId.mockClear();
  restoreEpoch.mockClear();
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
    expect(compare.querySelector('[data-compare-pick="reference"]')).toHaveAttribute('data-compare-epoch', '1');
    expect(compare.querySelector('[data-compare-pick="target"]')).toHaveAttribute('data-compare-epoch', '2');
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
    const headers = compare.querySelectorAll('thead [data-compare-epoch]');
    expect([...headers].map(h => h.getAttribute('data-compare-epoch'))).toEqual(['2', '1']);
  });

  it('re-targets the comparison when an epoch column of the chart is clicked, bar or not', async () => {
    renderPanel();
    fireEvent.click(await screen.findByTestId('column-epoch-1'));

    const headers = document.querySelectorAll('[data-run-analysis-compare] thead [data-compare-epoch]');
    expect([...headers].map(h => h.getAttribute('data-compare-epoch'))).toEqual(['2', '1']);
  });

  it('hovering a chart column shows the epoch card below the chart: dates, outcome, duration and cost', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    // Below the chart, never over the bars: y is pinned past its bottom and allowed out of it.
    const chartHeight = parseFloat((document.querySelector('[data-run-analysis-chart]') as HTMLElement).style.height);
    expect(chart.tooltip!.position.y).toBeGreaterThan(chartHeight);
    expect(chart.tooltip!.allowEscapeViewBox).toEqual({ x: false, y: true });

    const column = chart.data.find(d => d.epoch === 2)!;
    render(<>{chart.tooltip!.content({ active: true, payload: [{ payload: column }] })}</>);

    const card = document.querySelector('[data-epoch-details="2"]') as HTMLElement;
    expect(card.querySelector('[data-epoch-details-started]')!.textContent).toMatch(/2026/);
    expect(card.querySelector('[data-epoch-details-ended]')!.textContent).toMatch(/2026/);
    expect(card.textContent).toContain('status.failed');
    expect(card.textContent).toContain('4.0s');
    expect(card.textContent).toContain('workflow.runAnalysis.chart.cost');
    expect(card.textContent).toContain('c3');
  });

  it('gives an epoch without a recorded cost a "-" cost, and a run without any cost no cost row', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], { ...ANALYSIS.epochs[1], costCredits: null }],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const { unmount } = render(<>{chart.tooltip!.content({ active: true, payload: [{ payload: chart.data.find(d => d.epoch === 2) }] })}</>);
    const costRow = [...document.querySelectorAll('[data-epoch-details="2"] > div')].find(r => r.textContent!.includes('chart.cost'))!;
    expect(costRow.lastElementChild!.textContent).toBe('-');
    unmount();
    cleanup();

    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: ANALYSIS.epochs.map(e => ({ ...e, costCredits: null })),
    });
    renderPanel();
    await screen.findByTestId('chart');
    render(<>{chart.tooltip!.content({ active: true, payload: [{ payload: chart.data.find(d => d.epoch === 2) }] })}</>);
    expect(document.querySelector('[data-epoch-details="2"]')!.textContent).not.toContain('chart.cost');
  });

  it('shows no chart card when the pointer is off the columns', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    expect(chart.tooltip!.content({ active: false, payload: [] })).toBeNull();
  });

  it('selects a node on the canvas when its label is clicked in the comparison, as in the grid', async () => {
    const focused: string[] = [];
    const listener = (e: Event) => focused.push((e as CustomEvent<{ stepAlias: string }>).detail.stepAlias);
    window.addEventListener('workflowFocusNode', listener);
    try {
      renderPanel();
      const label = await waitFor(() => {
        const el = document.querySelector('[data-run-analysis-compare] [data-compare-node="mcp:send"]') as HTMLButtonElement;
        expect(el).not.toBeNull();
        return el;
      });
      expect(label.tagName).toBe('BUTTON');

      fireEvent.click(label);

      expect(focused).toEqual(['mcp:send']);
    } finally {
      window.removeEventListener('workflowFocusNode', listener);
    }
  });

  it('lets both compared epochs be chosen, the reference one included', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], ANALYSIS.epochs[1], { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' }],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;

    // By default the latest failure (2) against the success before it (1).
    expect(pick('reference').value).toBe('1');
    fireEvent.change(pick('reference'), { target: { value: '3' } });

    expect(pick('reference').value).toBe('3');
    expect(pick('target').value).toBe('2');
  });

  it('offers every epoch in both columns, newest first', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    const options = (which: string) =>
      [...(document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement).options].map(o => o.value);
    expect(options('reference')).toEqual(['2', '1']);
    expect(options('target')).toEqual(['2', '1']);
  });

  it('swaps the two columns when one takes the epoch the other shows', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;

    // 2 against 1: choose 1 as the compared epoch, and 2 becomes the reference.
    fireEvent.change(pick('target'), { target: { value: '1' } });
    expect(pick('target').value).toBe('1');
    expect(pick('reference').value).toBe('2');

    // And back, from the reference column.
    fireEvent.change(pick('reference'), { target: { value: '1' } });
    expect(pick('reference').value).toBe('1');
    expect(pick('target').value).toBe('2');
  });

  it('puts the oldest epoch in the compared column, against the reference the user chose', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], ANALYSIS.epochs[1], { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' }],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;

    // The oldest epoch into the compared column, in one move: it was the reference, so the
    // columns swap.
    fireEvent.change(pick('target'), { target: { value: '1' } });
    expect(pick('target').value).toBe('1');
    expect(pick('reference').value).toBe('2');
    fireEvent.change(pick('reference'), { target: { value: '3' } });
    expect(pick('reference').value).toBe('3');
  });

  it('moves the canvas when another compared epoch is chosen in the table (it is the shared one)', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    // Default 2 against 1: epoch 1 into the compared column.
    fireEvent.change(document.querySelector('[data-compare-pick="target"]') as HTMLSelectElement, { target: { value: '1' } });

    expect(markEpochPickedByUser).toHaveBeenCalledWith('run-1', 1);
    expect(setViewingEpoch).toHaveBeenCalledWith(1);
  });

  it('never moves the canvas for a reference choice: the reference is this table\'s own', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], ANALYSIS.epochs[1], { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' }],
    });
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.change(document.querySelector('[data-compare-pick="reference"]') as HTMLSelectElement, { target: { value: '3' } });

    expect((document.querySelector('[data-compare-pick="reference"]') as HTMLSelectElement).value).toBe('3');
    expect(setViewingEpoch).not.toHaveBeenCalled();
    expect(markEpochPickedByUser).not.toHaveBeenCalled();
  });

  it('moves the canvas to the old reference when the reference column takes the compared epoch', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    // Default 2 against 1: the reference column takes 2, so 1 becomes the compared (shared) epoch.
    fireEvent.change(document.querySelector('[data-compare-pick="reference"]') as HTMLSelectElement, { target: { value: '2' } });

    expect(markEpochPickedByUser).toHaveBeenCalledWith('run-1', 1);
    expect(setViewingEpoch).toHaveBeenCalledWith(1);
  });

  it('keeps table choices away from the canvas for a run the panel is not bound to', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.change(document.querySelector('[data-compare-pick="target"]') as HTMLSelectElement, { target: { value: '1' } });
    fireEvent.change(document.querySelector('[data-compare-pick="reference"]') as HTMLSelectElement, { target: { value: '1' } });

    expect(setViewingEpoch).not.toHaveBeenCalled();
    expect(markEpochPickedByUser).not.toHaveBeenCalled();
  });

  it('forgets a chosen reference once the compared epoch moves from the grid, back to the last success', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [
        ANALYSIS.epochs[0],
        ANALYSIS.epochs[1],
        { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' },
        { ...ANALYSIS.epochs[1], epoch: 4, startedAt: '2026-09-26T12:00:00Z' },
      ],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;
    // Default: the latest failure (4) against the success before it (3). Swap in the table.
    expect([pick('reference').value, pick('target').value]).toEqual(['3', '4']);
    fireEvent.change(pick('target'), { target: { value: '3' } });
    expect([pick('reference').value, pick('target').value]).toEqual(['4', '3']);

    // A grid click on epoch 2 (a failure): compared against the success before it (1), not the
    // failure (4) the swap had put in the reference column.
    const epoch2Cell = [...document.querySelectorAll<HTMLElement>('[data-run-analysis-grid] [data-cell-status]')][1];
    fireEvent.click(epoch2Cell);

    expect([pick('reference').value, pick('target').value]).toEqual(['1', '2']);
  });

  it('keeps a table-chosen reference once the shared epoch it moved to comes back from the canvas', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 2 };
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], ANALYSIS.epochs[1], { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' }],
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const tree = () => (
      <QueryClientProvider client={client}>
        <RunAnalysisPanelContent workflowId="wf-1" runId="run-1" onBack={vi.fn()} />
      </QueryClientProvider>
    );
    const view = render(tree());
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;
    fireEvent.change(pick('reference'), { target: { value: '3' } });
    fireEvent.change(pick('target'), { target: { value: '1' } });
    expect(setViewingEpoch).toHaveBeenLastCalledWith(1);

    // The shared epoch arrives back from the provider: the table-chosen reference still holds.
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 };
    view.rerender(tree());

    expect([pick('reference').value, pick('target').value]).toEqual(['3', '1']);
  });

  it('keeps a reference chosen in the table when the compared epoch is changed from the table too', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [
        ANALYSIS.epochs[0],
        ANALYSIS.epochs[1],
        { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' },
      ],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;
    fireEvent.change(pick('reference'), { target: { value: '3' } });

    fireEvent.change(pick('target'), { target: { value: '1' } });

    expect([pick('reference').value, pick('target').value]).toEqual(['3', '1']);
  });

  it('tells how each epoch went in the pickers, so a reference is not chosen blind', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    const labels = [...(document.querySelector('[data-compare-pick="reference"]') as HTMLSelectElement).options]
      .map(o => o.textContent);
    expect(labels).toEqual([
      'workflow.runAnalysis.compare.epoch(2) · status.failed',
      'workflow.runAnalysis.compare.epoch(1) · status.completed',
    ]);
  });

  it('shows the "choose the epochs" hint only with a table to choose them in', async () => {
    api.getRunAnalysis.mockResolvedValue({ ...ANALYSIS, epochs: [ANALYSIS.epochs[0]] });
    renderPanel();
    await screen.findByTestId('chart');

    const compare = document.querySelector('[data-run-analysis-compare]') as HTMLElement;
    expect(compare.textContent).toContain('workflow.runAnalysis.compare.needTwo');
    expect(compare.textContent).not.toContain('workflow.runAnalysis.compare.hint');
  });

  it('swaps a chosen reference out when the compared column takes it', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [ANALYSIS.epochs[0], ANALYSIS.epochs[1], { ...ANALYSIS.epochs[0], epoch: 3, startedAt: '2026-09-26T11:00:00Z' }],
    });
    renderPanel();
    await screen.findByTestId('chart');
    const pick = (which: 'reference' | 'target') =>
      document.querySelector(`[data-compare-pick="${which}"]`) as HTMLSelectElement;
    fireEvent.change(pick('reference'), { target: { value: '3' } });

    fireEvent.change(pick('target'), { target: { value: '3' } });

    // Epoch 3 cannot be compared with itself: the reference takes the epoch it gave up (2).
    expect(pick('target').value).toBe('3');
    expect(pick('reference').value).toBe('2');
  });

  it('offers no "show on the canvas" button any more: picking already shows the epoch there', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    expect(document.querySelector('[data-show-epoch-on-canvas]')).toBeNull();
  });

  it('leads on to Logs from the right of its header, in place of a refresh button', async () => {
    const onOpenLogs = vi.fn();
    renderPanel(vi.fn(), onOpenLogs);
    await screen.findByTestId('chart');

    const toLogs = document.querySelector('[data-run-analysis-to-logs]') as HTMLElement;
    expect(toLogs.textContent).toContain('sidePanel.logs');
    fireEvent.click(toLogs);
    expect(onOpenLogs).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'workflow.runAnalysis.refresh' })).toBeNull();
  });

  it('shows no Logs button where the host offers none', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    expect(document.querySelector('[data-run-analysis-to-logs]')).toBeNull();
  });

  it('lets the duration axis measure its own labels, on the round ticks it is given', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    const axis = document.querySelector('[data-y-axis="d"]') as HTMLElement;
    expect(axis).toHaveAttribute('data-width', 'auto');
    expect(JSON.parse(axis.getAttribute('data-ticks') ?? 'null')).toEqual(durationAxis([2000, 4000]).ticks);
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

  // Regression: a node's only success was its own maximum, so it was painted "slowest"
  // (darkest) even at a few milliseconds, the case of any run with a single epoch.
  it('paints a node with a single success as fast, not slow, keeping failures red', async () => {
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(screen.getByRole('button', { name: 'workflow.runAnalysis.grid.byDuration' }));

    const fetchCells = [...document.querySelectorAll('[data-run-analysis-grid] [data-cell-status]')].slice(0, 2);
    expect(fetchCells[0].className, 'nothing to compare the lone success with').toContain('bg-indigo-100');
    expect(fetchCells[0].className).not.toContain('bg-indigo-800');
    expect(fetchCells[1].className, 'a failure stays a failure').toContain('bg-red-500');
  });

  it('spreads a node\'s successes from palest (fastest) to darkest (slowest) of its own row', async () => {
    api.getRunAnalysis.mockResolvedValue({
      ...ANALYSIS,
      epochs: [
        ANALYSIS.epochs[0],
        { ...ANALYSIS.epochs[1], status: 'COMPLETED', nodes: [{ alias: 'mcp:fetch', status: 'completed', executionTimeMs: 3_000, elapsedMs: 3_000 }] },
      ],
    });
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(screen.getByRole('button', { name: 'workflow.runAnalysis.grid.byDuration' }));

    const fetchCells = [...document.querySelectorAll('[data-run-analysis-grid] [data-cell-status]')].slice(0, 2);
    expect(fetchCells[0].className, 'the fastest success, 1 s').toContain('bg-indigo-100');
    expect(fetchCells[1].className, 'the slowest success, 3 s').toContain('bg-indigo-800');
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

describe('RunAnalysisPanelContent - the epoch is the run\'s shared one', () => {
  const compareHeaders = () =>
    [...document.querySelectorAll('[data-run-analysis-compare] thead [data-compare-epoch]')]
      .map(h => h.getAttribute('data-compare-epoch'));

  it('moves the canvas (and every view) when an epoch is picked in the grid, recorded as a user choice', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    fireEvent.click(document.querySelector('[data-run-analysis-grid] [data-cell-status]') as HTMLElement);

    expect(markEpochPickedByUser).toHaveBeenCalledWith('run-1', 1);
    expect(setViewingEpoch).toHaveBeenCalledWith(1);
  });

  it('does the same from a chart column', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();

    fireEvent.click(await screen.findByTestId('column-epoch-2'));

    expect(markEpochPickedByUser).toHaveBeenCalledWith('run-1', 2);
    expect(setViewingEpoch).toHaveBeenCalledWith(2);
  });

  it('compares the epoch picked elsewhere (the canvas, the pill, the Run tab, Logs)', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 };
    renderPanel();
    await screen.findByTestId('chart');

    expect(compareHeaders()).toEqual(['2', '1']);
  });

  it('offers the way back to all epochs only while one is picked, through the recording helper', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');
    expect(document.querySelector('[data-run-analysis-all-epochs]')).toBeNull();
    cleanup();

    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 };
    renderPanel();
    await screen.findByTestId('chart');
    fireEvent.click(document.querySelector('[data-run-analysis-all-epochs]') as HTMLElement);

    expect(selectAllEpochs).toHaveBeenCalledWith('run-1', setViewingEpoch);
    expect(setViewingEpoch).toHaveBeenCalledWith(null);
  });

  it('keeps the pick local for a run the panel is not bound to', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'another-run', viewingEpoch: 2 };
    renderPanel();
    await screen.findByTestId('chart');

    // The bound run's epoch is not this run's: the click re-targets this tab's comparison only.
    fireEvent.click(document.querySelector('[data-run-analysis-grid] [data-cell-status]') as HTMLElement);

    expect(setViewingEpoch).not.toHaveBeenCalled();
    expect(markEpochPickedByUser).not.toHaveBeenCalled();
    expect(compareHeaders()).toEqual(['2', '1']);
  });

  it('returns a local pick to the default comparison from the all-epochs button', async () => {
    renderPanel();
    await screen.findByTestId('chart');
    const byDefault = compareHeaders();
    fireEvent.click(await screen.findByTestId('column-epoch-1'));
    expect(compareHeaders()).toEqual(['2', '1']);
    expect(compareHeaders()).not.toEqual(byDefault);

    fireEvent.click(document.querySelector('[data-run-analysis-all-epochs]') as HTMLElement);

    expect(compareHeaders()).toEqual(byDefault);
    expect(document.querySelector('[data-run-analysis-all-epochs]')).toBeNull();
    expect(selectAllEpochs).not.toHaveBeenCalled();
  });

  it('binds a panel that is not bound yet to its run, and keeps the pick local until it is', async () => {
    // Opened straight on Analysis: the Run tab, which binds the panel, never mounted. An epoch
    // broadcast without a run id would be adopted by the canvases of every other run.
    mode.value = { workflowId: 'wf-1', runId: null, viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    expect(setRunId).toHaveBeenCalledWith('run-1');
    fireEvent.click(await screen.findByTestId('column-epoch-1'));
    expect(setViewingEpoch).not.toHaveBeenCalled();
    expect(markEpochPickedByUser).not.toHaveBeenCalled();
  });

  it('restores the epoch remembered for the run once bound, like the Run tab', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    expect(restoreEpoch).toHaveBeenCalledWith(expect.objectContaining({
      runId: 'run-1', selectedEpoch: null, onSelectEpoch: setViewingEpoch, enabled: true,
    }));
  });

  it('never restores into a panel bound to another run', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'another-run', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    expect(restoreEpoch).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }));
    expect(setRunId).not.toHaveBeenCalled();
  });
});

describe('RunAnalysisPanelContent - the picked epoch stays inside its own column', () => {
  it('rings the compared cells INSIDE the cell, never over the neighbouring epochs', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 };
    renderPanel();
    await screen.findByTestId('chart');

    const cells = [...document.querySelectorAll<HTMLElement>('[data-run-analysis-grid] [data-cell-status]')];
    const ringed = cells.filter(c => /\bring-/.test(c.className));
    expect(ringed.length).toBeGreaterThan(0);
    for (const cell of ringed) {
      expect(cell.className).toContain('ring-inset');
      expect(cell.className).not.toContain('ring-offset');
    }
  });

  it('shows every bar at full strength while no epoch is picked', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: null };
    renderPanel();
    await screen.findByTestId('chart');

    expect(document.querySelector('[data-chart-cell="1"]')).toHaveAttribute('data-opacity', '0.9');
    expect(document.querySelector('[data-chart-cell="2"]')).toHaveAttribute('data-opacity', '0.9');
  });

  it('marks the picked bar by dimming the others, with no outline around it', async () => {
    mode.value = { workflowId: 'wf-1', runId: 'run-1', viewingEpoch: 1 };
    renderPanel();
    await screen.findByTestId('chart');

    expect(document.querySelector('[data-chart-cell="1"]')).toHaveAttribute('data-opacity', '1');
    expect(document.querySelector('[data-chart-cell="2"]')).toHaveAttribute('data-opacity', '0.3');
  });
});

describe('epochFromChartState', () => {
  const data = [{ epoch: 4 }, { epoch: 7 }, { epoch: 9 }];

  it('reads the column the click landed in, by its index', () => {
    expect(epochFromChartState({ activeIndex: 1 }, data)).toBe(7);
    expect(epochFromChartState({ activeIndex: '2' }, data)).toBe(9);
  });

  it('falls back on the column label when there is no usable index', () => {
    expect(epochFromChartState({ activeIndex: undefined, activeLabel: 4 }, data)).toBe(4);
    expect(epochFromChartState({ activeIndex: 12, activeLabel: '9' }, data)).toBe(9);
  });

  it('picks nothing for a click outside any column', () => {
    expect(epochFromChartState(null, data)).toBeNull();
    expect(epochFromChartState({}, data)).toBeNull();
    expect(epochFromChartState({ activeIndex: -1, activeLabel: 99 }, data)).toBeNull();
  });
});

describe('durationAxis', () => {
  it('rounds the top tick up past the longest epoch, and ends on it', () => {
    const axis = durationAxis([12_000, 59_000]);
    expect(axis.top).toBeGreaterThanOrEqual(59_000);
    expect(axis.ticks[axis.ticks.length - 1]).toBe(axis.top);
  });

  it('starts at zero, climbs in equal round steps and covers the longest epoch', () => {
    const axis = durationAxis([400, 12 * 60_000 + 30_000]);
    expect(axis.ticks[0]).toBe(0);
    const steps = axis.ticks.slice(1).map((v, i) => v - axis.ticks[i]);
    expect(new Set(steps).size).toBe(1);
    expect(axis.top).toBeGreaterThanOrEqual(12 * 60_000 + 30_000);
    expect(axis.ticks.length).toBeLessThanOrEqual(6);
  });

  it('steps on round clock values, never a decimal like 3m20s', () => {
    // 12m30s over four intervals: 5-minute steps, not 3m20s ones.
    expect(durationAxis([12 * 60_000 + 30_000]).ticks).toEqual([0, 300_000, 600_000, 900_000]);
    // Under a minute: 15-second steps.
    expect(durationAxis([50_000]).ticks).toEqual([0, 15_000, 30_000, 45_000, 60_000]);
    // Hours long: hour steps.
    expect(durationAxis([3 * 3_600_000 + 60_000]).ticks).toEqual([0, 3_600_000, 7_200_000, 10_800_000, 14_400_000]);
  });

  it('draws a bare zero axis for an empty or untimed chart', () => {
    expect(durationAxis([])).toEqual({ ticks: [0], top: 1 });
    expect(durationAxis([null, null])).toEqual({ ticks: [0], top: 1 });
  });
});
