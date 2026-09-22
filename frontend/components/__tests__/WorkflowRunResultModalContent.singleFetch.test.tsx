/**
 * @vitest-environment jsdom
 *
 * Opening the Logs modal used to issue the aggregated-steps request TWICE: once from StepTable,
 * which needs the rows to render, and once from this wrapper, which needs them only to resolve the
 * step it was asked to pre-select. That request is the modal's most expensive read - the SQL walks
 * every epoch of the run - so on a long-lived run the duplicate was the difference between waiting
 * once and waiting twice, for one screen.
 *
 * These pin the wiring that removed it: StepTable hands its rows up (`onStepsLoaded`) and the
 * wrapper consumes them, so one open means one request while auto-selection keeps working.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';

const getAggregatedSteps = vi.hoisted(() => vi.fn());
const apiClientGet = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/orchestrator', () => ({ orchestratorApi: { getAggregatedSteps } }));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: apiClientGet, delete: vi.fn() } }));
vi.mock('@/lib/api', () => ({ apiClient: { get: apiClientGet, delete: vi.fn() } }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/app/workflows/builder/components/nodes/shared', () => ({
  getIconSlug: () => 'x',
  NodeIcon: () => null,
  nodeIconRadiusClass: () => 'rounded-md',
}));
// The two views that replace the table once a step is selected. Stubbed because this file is about
// how many requests the modal makes and which step it lands on, not about what those views draw.
vi.mock('@/components/workflow/WorkflowStepTable', () => ({
  WorkflowStepTable: ({ stepAlias }: { stepAlias: string }) => (
    <div data-testid="merged-calls">{stepAlias}</div>
  ),
}));
vi.mock('@/components/DataTable', () => ({ default: () => <div data-testid="data-table" /> }));

import { WorkflowRunResultModalContent } from '@/components/WorkflowRunResultModalContent';
import StepTable from '@/components/StepTable';
import { clearCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';

const step = (alias: string) => ({
  alias,
  toolId: `tool-${alias}`,
  status: 'COMPLETED',
  startTime: '2026-01-01T00:00:00Z',
  endTime: '2026-01-01T00:00:01Z',
});

const AGGREGATE = [step('fetch'), step('enrich')];

afterEach(() => {
  getAggregatedSteps.mockReset();
  apiClientGet.mockReset();
  clearCanvasNodes();
  cleanup();
});

describe('Logs modal - the aggregate is fetched once per open', () => {
  it('issues exactly one aggregated-steps request when opened on the step list', async () => {
    getAggregatedSteps.mockResolvedValue(AGGREGATE);

    render(<WorkflowRunResultModalContent workflowId="wf-1" runId="run-1" />);

    await waitFor(() => expect(getAggregatedSteps).toHaveBeenCalledTimes(1));
    expect(getAggregatedSteps).toHaveBeenCalledWith('run-1');
    // The wrapper's own copy of the request went through apiClient.get, which must now stay unused:
    // a second caller here is the duplicate coming back under another name.
    expect(apiClientGet).not.toHaveBeenCalled();
  });

  it('still auto-selects initialStepAlias, from the rows StepTable loaded', async () => {
    getAggregatedSteps.mockResolvedValue(AGGREGATE);

    render(<WorkflowRunResultModalContent workflowId="wf-1" runId="run-1" initialStepAlias="enrich" />);

    // Landing on the merged-calls view for `enrich` proves the wrapper received the rows it no
    // longer fetches: without them it would sit on the step list forever.
    expect((await screen.findByTestId('merged-calls')).textContent).toBe('enrich');
    expect(getAggregatedSteps).toHaveBeenCalledTimes(1);
  });

  it('leaves the step list showing when initialStepAlias matches nothing', async () => {
    getAggregatedSteps.mockResolvedValue(AGGREGATE);

    render(<WorkflowRunResultModalContent workflowId="wf-1" runId="run-1" initialStepAlias="zzz-unknown" />);

    await waitFor(() => expect(getAggregatedSteps).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('merged-calls')).toBeNull();
  });

  it('an empty aggregate is delivered too, so a run with no steps settles instead of hanging', async () => {
    // The wrapper is told about [] as well as about rows: the empty case is what an open on a run
    // that has not produced a step yet looks like, and it must not leave the caller waiting.
    getAggregatedSteps.mockResolvedValue([]);

    render(<WorkflowRunResultModalContent workflowId="wf-1" runId="run-1" initialStepAlias="fetch" />);

    await waitFor(() => expect(getAggregatedSteps).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('merged-calls')).toBeNull();
  });
});

/**
 * The parent stopped fetching, so it now learns the outcome ONLY through this callback. Every path
 * out of the load must therefore report, including the two that produce no rows: leave one silent
 * and the parent keeps the previous attempt's steps, which is a regression against the request that
 * was deleted (it set them to [] in exactly these cases).
 *
 * These are asserted on StepTable directly rather than through the modal because the modal cannot
 * observe them: switching a mounted modal to another run auto-selects from the rows it still holds
 * before the new request resolves, a race that predates this change and would mask the difference.
 */
describe('StepTable - every outcome of the load is reported to the parent', () => {
  it('reports the rows it mapped', async () => {
    getAggregatedSteps.mockResolvedValue(AGGREGATE);
    const onStepsLoaded = vi.fn();

    render(<StepTable workflowId="wf-1" runId="run-1" onStepsLoaded={onStepsLoaded} />);

    await waitFor(() => expect(onStepsLoaded).toHaveBeenCalledTimes(1));
    expect(onStepsLoaded.mock.calls[0][0].map((s: { stepAlias: string }) => s.stepAlias))
      .toEqual(['fetch', 'enrich']);
  });

  it('reports [] when the body is not an array', async () => {
    // A 204 or an empty body resolves to `undefined` and takes the "empty or invalid response"
    // guard, a different call site from the mapped path above.
    getAggregatedSteps.mockResolvedValue(undefined as unknown as never);
    const onStepsLoaded = vi.fn();

    render(<StepTable workflowId="wf-1" runId="run-1" onStepsLoaded={onStepsLoaded} />);

    await waitFor(() => expect(onStepsLoaded).toHaveBeenCalledTimes(1));
    expect(onStepsLoaded).toHaveBeenCalledWith([]);
  });

  it('shows a load error and reports [] when the request fails', async () => {
    getAggregatedSteps.mockRejectedValue(new Error('boom'));
    const onStepsLoaded = vi.fn();

    render(<StepTable workflowId="wf-1" runId="run-1" onStepsLoaded={onStepsLoaded} />);

    await waitFor(() => expect(onStepsLoaded).toHaveBeenCalledTimes(1));
    expect(onStepsLoaded).toHaveBeenCalledWith([]);
    expect((await screen.findByTestId('workflow-logs-steps-error')).textContent)
      .toBe('workflow.logs.loadStepsError');
    expect(screen.queryByText('No steps found')).toBeNull();
  });

  it('does not refetch when the parent re-renders with a fresh callback identity', async () => {
    // StepTable holds onStepsLoaded in a ref precisely so the callback's identity cannot re-arm its
    // load effect. Passing a brand-new inline lambda on every render is what a careless parent does;
    // without the ref this loops, and one open would cost many of the modal's most expensive request.
    getAggregatedSteps.mockResolvedValue(AGGREGATE);

    const Parent = ({ tick }: { tick: number }) => (
      <>
        <span data-testid="tick">{tick}</span>
        <StepTable workflowId="wf-1" runId="run-1" onStepsLoaded={() => undefined} />
      </>
    );

    const { rerender } = render(<Parent tick={0} />);
    await waitFor(() => expect(getAggregatedSteps).toHaveBeenCalledTimes(1));

    for (let tick = 1; tick <= 5; tick++) {
      rerender(<Parent tick={tick} />);
    }
    await waitFor(() => expect(screen.getByTestId('tick').textContent).toBe('5'));

    expect(getAggregatedSteps).toHaveBeenCalledTimes(1);
  });
});
