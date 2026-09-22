/**
 * @vitest-environment jsdom
 *
 * The Logs modal (run header) lists a run's steps in the order the aggregate
 * endpoint returned them, which is execution order: a workflow with parallel
 * branches listed its nodes shuffled, and the order moved between two openings
 * of the same run.
 *
 * Same rule as the run panel's step list: read the canvas graph and order by
 * DAG. These assert the rendered row order, that an unresolvable step is kept
 * rather than dropped, and that no canvas means no reordering.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';

const getAggregatedSteps = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: { getAggregatedSteps } }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { delete: vi.fn() } }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => 'x',
  NodeIcon: () => null,
  // The no-icon placeholder reads its corner from here. Its real value is
  // pinned in nodeIconShape.test.tsx; this file is about row ORDER.
  nodeIconRadiusClass: () => 'rounded-md',
}));

import StepTable from '@/components/StepTable';
import {
  clearCanvasNodes,
  setCanvasEdges,
  setCanvasNodes,
} from '@/app/workflows/builder/services/canvasNodesStore';

/**
 *   Start -> Fetch -> Enrich -> Save
 *         -> Notify -----------^
 *
 * `Notify` is one hop from the trigger but must still render above `Save`,
 * which consumes both branches.
 */
const NODES = [
  { id: 'n-start', position: { x: 0, y: 100 }, data: { id: 'trigger:manual', label: 'Start', kind: 'entry' } },
  { id: 'n-fetch', position: { x: 200, y: 0 }, data: { id: 'mcp:fetch', label: 'Fetch' } },
  { id: 'n-enrich', position: { x: 400, y: 0 }, data: { id: 'mcp:enrich', label: 'Enrich' } },
  { id: 'n-notify', position: { x: 200, y: 200 }, data: { id: 'mcp:notify', label: 'Notify' } },
  { id: 'n-save', position: { x: 600, y: 100 }, data: { id: 'mcp:save', label: 'Save' } },
];

const EDGES = [
  { source: 'n-start', target: 'n-fetch' },
  { source: 'n-fetch', target: 'n-enrich' },
  { source: 'n-enrich', target: 'n-save' },
  { source: 'n-start', target: 'n-notify' },
  { source: 'n-notify', target: 'n-save' },
];

const DAG_ORDER = ['Start', 'Fetch', 'Enrich', 'Notify', 'Save'];

const row = (alias: string) => ({
  alias,
  toolId: `tool-${alias}`,
  status: 'COMPLETED',
  startTime: '2026-01-01T00:00:00Z',
  endTime: '2026-01-01T00:00:01Z',
});

/** Aggregate as the endpoint really returns it: not in graph order. */
const SCRAMBLED = [row('save'), row('notify'), row('enrich'), row('start'), row('fetch')];

/** Alias-cell texts, in DOM row order. */
function renderedLabels(container: HTMLElement, expected: string[]): string[] {
  const known = new Set(expected);
  return [...container.querySelectorAll('tbody tr td:nth-child(3) span')]
    .map((el) => el.textContent ?? '')
    .filter((text) => known.has(text));
}

function publishCanvas() {
  act(() => {
    setCanvasNodes(NODES as never, 'wf-1');
    setCanvasEdges(EDGES as never, 'wf-1');
  });
}

afterEach(() => {
  getAggregatedSteps.mockReset();
  clearCanvasNodes();
  cleanup();
});

describe('StepTable (Logs modal) - rows follow the DAG', () => {
  it('does not expose selection or deletion on execution history', async () => {
    getAggregatedSteps.mockResolvedValue(SCRAMBLED);
    render(<StepTable workflowId="wf-1" runId="run-1" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(6));
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
  });
  it('renders a scrambled aggregate trigger-first, terminal-node-last', async () => {
    getAggregatedSteps.mockResolvedValue(SCRAMBLED);
    publishCanvas();

    const { container } = render(<StepTable workflowId="wf-1" runId="run-1" />);

    await waitFor(() => expect(screen.getByText('Save')).toBeTruthy());
    expect(renderedLabels(container, DAG_ORDER)).toEqual(DAG_ORDER);
  });

  it('keeps a step whose node no longer exists, at the end', async () => {
    getAggregatedSteps.mockResolvedValue([row('ghost'), ...SCRAMBLED]);
    publishCanvas();

    const { container } = render(<StepTable workflowId="wf-1" runId="run-1" />);

    await waitFor(() => expect(screen.getByText('ghost')).toBeTruthy());
    expect(renderedLabels(container, [...DAG_ORDER, 'ghost'])).toEqual([...DAG_ORDER, 'ghost']);
  });

  it('leaves the rows alone when no canvas has published', async () => {
    // The modal can be open with no canvas mounted. No graph means no opinion,
    // never a reshuffle into an arbitrary order.
    getAggregatedSteps.mockResolvedValue(SCRAMBLED);

    const { container } = render(<StepTable workflowId="wf-1" runId="run-1" />);

    await waitFor(() => expect(screen.getByText('save')).toBeTruthy());
    const aliases = ['save', 'notify', 'enrich', 'start', 'fetch'];
    expect(renderedLabels(container, aliases)).toEqual(aliases);
  });
});
