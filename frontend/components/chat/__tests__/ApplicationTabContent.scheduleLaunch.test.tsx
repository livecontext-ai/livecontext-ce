/**
 * @vitest-environment jsdom
 *
 * Regression test - pins the contract that the toolbar Launch button, when the
 * workflow's only launchable trigger is a {@code schedule}, dispatches the fire
 * through the INTERNAL (run-scoped) trigger path - NOT the workflow-scoped
 * pin-gated {@code scheduleExecuteNow}.
 *
 * Why: the previous routing called {@code executionService.scheduleExecuteNow
 * (workflowId, triggerId)}, which hits {@code POST /api/v2/workflows/{wfId}/
 * schedule/execute-now/{trigId}} - the same path the cron daemon uses, gated by
 * {@code ProductionRunResolver} requiring {@code workflow.pinned_version IS NOT
 * NULL}. Acquired application clones land unpinned, so the request silently
 * returned 400 ({@code "No active run found..."}) and the frontend catch block
 * only {@code console.error}'d it - the user saw NOTHING when clicking
 * "Daily Scan".
 *
 * The fix mirrors the manual branch directly above it: when the user has a
 * runId in scope (they always do on the application page), the schedule fire
 * goes through {@code runContext.executeStep(runId, scheduleId, undefined,
 * 'schedule')} which routes to {@code POST /api/v2/workflows/runs/{runId}/
 * trigger/schedule/{triggerId}} - the same un-gated path used by manual / chat
 * / form. The pin-gated endpoint stays reserved for the cron daemon and the
 * builder's schedule-inspector "Execute now" button (no runId in scope there).
 *
 * Without this test, a refactor that "simplifies" the schedule branch back to
 * the workflow-scoped endpoint (it superficially looks like the cleaner choice
 * - single argument, no runId dependency) would silently re-introduce the
 * silent-fail UX on every acquired app where the clone is unpinned.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, fireEvent, waitFor, act } from '@testing-library/react';
import * as React from 'react';

// ── Capture handles for the assertions ──────────────────────────────────────
const executeStepMock = vi.hoisted(() => vi.fn());
const scheduleExecuteNowMock = vi.hoisted(() => vi.fn());
const triggerSpecificMock = vi.hoisted(() => vi.fn());
const triggerManualMock = vi.hoisted(() => vi.fn());
const getWorkflowMock = vi.hoisted(() => vi.fn());

// Toggle whether useRun returns a runContext with executeStep (primary path)
// or an empty object (forces the executionService.triggerSpecific fallback).
const runContextRef = vi.hoisted(() => ({
  current: { executeStep: executeStepMock } as Record<string, unknown> | null,
}));

// ── Service / hook mocks ────────────────────────────────────────────────────
vi.mock('@/lib/api/orchestrator/execution.service', () => ({
  executionService: {
    scheduleExecuteNow: scheduleExecuteNowMock,
    triggerSpecific: triggerSpecificMock,
    triggerManual: triggerManualMock,
  },
}));

vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getWorkflow: getWorkflowMock,
  },
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [
    // runState - minimum shape the component reads (executionTotal for the
    // debounced refetch effect). Nothing this test cares about.
    { executionTotal: 0 },
    runContextRef.current,
  ],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => ({ isRunMode: false, isPreviewOnly: false }),
}));

vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: undefined,
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/components/app/WorkflowPanelContent', () => ({
  setPendingActivateTab: () => undefined,
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
    // ApplicationTabContent reads getTokenProvider() in a passive effect for
    // the iframe authToken. Returning null short-circuits the fetch and keeps
    // this test focused on the launch-button contract.
    getTokenProvider: () => null,
  },
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: {},
}));

// ── Render-side passthroughs / stubs ────────────────────────────────────────
// InterfaceToolbar: render the extraControls inline so the Launch button lands
// in the DOM where Testing Library can find + click it.
vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: (props: { extraControls?: React.ReactNode }) => (
    <div data-testid="toolbar-stub">{props.extraControls}</div>
  ),
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceIframe', () => ({
  InterfaceIframe: () => <div data-testid="iframe-stub" />,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="loading-spinner" />,
}));

vi.mock('@/app/workflows/builder/components/TriggerPanel', () => ({
  TriggerPanel: () => <div data-testid="trigger-panel-stub" />,
}));

vi.mock('@/app/workflows/builder/utils/interfaceHtmlUtils', () => ({
  mergeTriggerDataIntoResolved: () => ({}),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({
  SAFE_CENTERING_CSS: '',
  centeringCssFor: () => '',
}));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, params?: Record<string, unknown>) => {
    if (params && 'label' in params) return `Launch ${params.label}`;
    return key;
  },
}));

// Import AFTER the mocks so the module captures the stubbed deps.
import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  actionMapping: {},
};

function renderWithSchedule(props: { runId: string | null; workflowId?: string }) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId={props.runId}
      workflowId={props.workflowId}
      onAction={() => undefined}
      // Force-mount the toolbar in the expanded state. Without these two
      // props the component's `(hasActions || carouselControls)` gate is
      // false (no htmlTemplate, no epochs, no pagination in the mocked
      // useInterfaceRender), and the launch button never reaches the DOM.
      // `toolbarOpen=true` selects the InterfaceToolbar branch over the
      // collapsed Grip button.
      carouselControls={<span data-testid="carousel-controls-stub" />}
      toolbarOpen
    />,
  );
}

describe('ApplicationTabContent - schedule Launch button (internal trigger contract)', () => {
  beforeEach(() => {
    executeStepMock.mockReset();
    scheduleExecuteNowMock.mockReset();
    triggerSpecificMock.mockReset();
    triggerManualMock.mockReset();
    getWorkflowMock.mockReset();
    runContextRef.current = { executeStep: executeStepMock };

    // Plan with a single schedule trigger named "Daily Scan" - the same shape
    // the application page sees for the Competitive Intelligence app where
    // the bug was first reported.
    getWorkflowMock.mockResolvedValue({
      plan: {
        triggers: [
          { id: 'sched-1', label: 'Daily Scan', type: 'schedule' },
        ],
      },
    });
  });

  it('click → runContext.executeStep with type=schedule (NOT scheduleExecuteNow)', async () => {
    const { getByTitle } = renderWithSchedule({
      runId: 'run_abc',
      workflowId: 'wf-1',
    });

    // Wait for the plan fetch effect to populate launchable.firstSchedule.
    const button = await waitFor(() => getByTitle('Launch Daily Scan'));
    expect((button as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(button);

    await waitFor(() => expect(executeStepMock).toHaveBeenCalledTimes(1));
    // (runId, normalizedKey 'trigger:daily_scan', payload=undefined, type='schedule')
    expect(executeStepMock).toHaveBeenCalledWith('run_abc', 'trigger:daily_scan', undefined, 'schedule');

    // The pin-gated daemon path MUST NOT be invoked from the UI button.
    expect(scheduleExecuteNowMock).not.toHaveBeenCalled();
    // No fallback needed when runContext.executeStep is available.
    expect(triggerSpecificMock).not.toHaveBeenCalled();
  });

  it('click falls back to executionService.triggerSpecific when runContext has no executeStep', async () => {
    // Simulate a context where the run-manager hasn't wired executeStep (e.g.
    // share-token pages without WorkflowRunProvider). The fallback also stays
    // on the run-scoped, un-gated endpoint - never the daemon path.
    runContextRef.current = {};

    const { getByTitle } = renderWithSchedule({
      runId: 'run_abc',
      workflowId: 'wf-1',
    });

    const button = await waitFor(() => getByTitle('Launch Daily Scan'));
    fireEvent.click(button);

    await waitFor(() => expect(triggerSpecificMock).toHaveBeenCalledTimes(1));
    expect(triggerSpecificMock).toHaveBeenCalledWith('run_abc', 'trigger:daily_scan', 'schedule');

    expect(executeStepMock).not.toHaveBeenCalled();
    expect(scheduleExecuteNowMock).not.toHaveBeenCalled();
  });

  it('no runId + workflowId → falls back to scheduleExecuteNow (pin-gated workflow-scoped path)', async () => {
    // Carousel / visualize-popup / share-page contexts may mount this
    // component without an authoritative runId. PINNED schedule-only
    // workflows must still be force-fireable from those entry points -
    // routing through scheduleExecuteNow lets the backend resolve the
    // production WAITING_TRIGGER run via ProductionRunResolver (which only
    // succeeds when pinned, by design). Pre-fix behavior preserved.
    const { getByTitle } = renderWithSchedule({
      runId: null,
      workflowId: 'wf-1',
    });

    const button = await waitFor(() => getByTitle('Launch Daily Scan'));
    expect((button as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(button);

    await waitFor(() => expect(scheduleExecuteNowMock).toHaveBeenCalledTimes(1));
    expect(scheduleExecuteNowMock).toHaveBeenCalledWith('wf-1', 'trigger:daily_scan');

    expect(executeStepMock).not.toHaveBeenCalled();
    expect(triggerSpecificMock).not.toHaveBeenCalled();
  });

  it('no runId AND no workflowId → no launch button at all (the plan fetch effect short-circuits on missing workflowId)', async () => {
    // The launchable-state useEffect (lines ~131-195) bails out early when
    // workflowId is falsy, so firstSchedule stays null → hasAnyLaunchable is
    // false → launchButton renders as null. Test asserts the *absence* of
    // the button, which is even stronger than disabled - and pins that the
    // fix didn't accidentally leak the schedule button into contexts that
    // have no anchor at all.
    const { queryByTitle } = renderWithSchedule({
      runId: null,
      workflowId: undefined,
    });

    // Flush the (unused) plan-fetch effect so any deferred state lands before
    // we assert absence.
    await act(async () => {
      await Promise.resolve();
    });

    expect(queryByTitle('Launch Daily Scan')).toBeNull();
    expect(executeStepMock).not.toHaveBeenCalled();
    expect(scheduleExecuteNowMock).not.toHaveBeenCalled();
    expect(triggerSpecificMock).not.toHaveBeenCalled();
  });
});
