/**
 * @vitest-environment jsdom
 *
 * Pins the UX contract: while an application's workflow run is EXECUTING
 * (runStatus === 'running'), the app stays VISIBLE and a pulsing blue border is
 * overlaid on top to signal it is running. When the run is NOT running (e.g.
 * 'awaiting_signal' where the user interacts with the interface, or
 * 'completed'), the app is shown WITHOUT the border.
 *
 * Because ApplicationTabContent is the single component rendered by the side
 * panel, application detail, carousel, visualize card and fullscreen, overlaying
 * the border in its shared `iframeContent` covers "everywhere the app renders".
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act } from '@testing-library/react';
import * as React from 'react';

// Flush the passive plan-fetch effect (getWorkflow) so its trailing setState
// lands inside act() - keeps the React "not wrapped in act(...)" warning out of
// the output. Our border/iframe assertions are synchronous on first render, so
// flushing before/after them doesn't change what is asserted.
async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

// Mutable run-state the mocked useRun returns - flipped per test.
const runStateRef = vi.hoisted(() => ({
  current: { runStatus: 'idle', executionTotal: 0 } as Record<string, unknown>,
}));

vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn() }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  // isRunMode true so useRun is wired with the runId and runStatus is read.
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

// useInterfaceRender ALWAYS returns a non-empty htmlTemplate so the app iframe
// renders in every state - the only variable under test is the border overlay.
vi.mock('@/app/workflows/builder/hooks/useInterfaces', () => ({
  useInterfaceById: () => ({ data: undefined }),
  useInterfaceRender: () => ({
    data: {
      htmlTemplate: '<div>app</div>',
      items: [{ data: { foo: 'bar' }, itemIndex: 0 }],
      pagination: { totalPages: 1 },
    },
    isLoading: false,
    isFetching: false,
    isPlaceholderData: false,
    refetch: vi.fn(),
  }),
}));

vi.mock('@/lib/stores/interface-pagination-store', () => ({
  useSharedInterfacePage: () => [0, () => undefined],
}));

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { getTokenProvider: () => null },
}));

vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/execution.service', () => ({ executionService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: { getWorkflow: vi.fn().mockResolvedValue({ plan: { triggers: [] } }) },
}));

vi.mock('@/app/workflows/builder/components/interface/InterfaceToolbar', () => ({
  InterfaceToolbar: () => <div data-testid="toolbar-stub" />,
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
  mergeTriggerDataIntoResolved: () => ({ foo: 'bar' }),
}));

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '' }));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => (key === 'running' ? 'Running' : key),
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

function renderApp() {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={baseConfig as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
    />,
  );
}

describe('ApplicationTabContent - pulsing running border (app stays visible)', () => {
  beforeEach(() => {
    runStateRef.current = { runStatus: 'idle', executionTotal: 0 };
  });
  afterEach(cleanup);

  it('overlays the running border AND keeps the app visible while runStatus is running (panel mode)', async () => {
    runStateRef.current = { runStatus: 'running', executionTotal: 0 };
    // renderApp() with the default (collapsed) state exercises the PANEL path.
    const { queryByTestId } = renderApp();
    // Border present...
    expect(queryByTestId('application-running-border')).not.toBeNull();
    // ...and the app iframe is STILL shown underneath (not hidden).
    expect(queryByTestId('iframe-stub')).not.toBeNull();
    await flushEffects();
  });

  it('shows the app WITHOUT the border when paused awaiting a user signal', async () => {
    runStateRef.current = { runStatus: 'awaiting_signal', executionTotal: 0 };
    const { queryByTestId } = renderApp();
    expect(queryByTestId('iframe-stub')).not.toBeNull();
    expect(queryByTestId('application-running-border')).toBeNull();
    await flushEffects();
  });

  it('shows the app WITHOUT the border once the run has completed', async () => {
    runStateRef.current = { runStatus: 'completed', executionTotal: 0 };
    const { queryByTestId } = renderApp();
    expect(queryByTestId('iframe-stub')).not.toBeNull();
    expect(queryByTestId('application-running-border')).toBeNull();
    await flushEffects();
  });

  it('fullscreen mode ALSO overlays the running border while running', async () => {
    // Fullscreen portals to document.body and shares the same `iframeContent`
    // const - assert the overlay holds on that render path too (query globally
    // via `screen` since the portal escapes the render container).
    runStateRef.current = { runStatus: 'running', executionTotal: 0 };
    render(
      <ApplicationTabContent
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        config={baseConfig as any}
        runId="run_1"
        workflowId="wf-1"
        onAction={() => undefined}
        isExpanded
        onExpandedChange={() => undefined}
      />,
    );
    expect(screen.queryByTestId('application-running-border')).not.toBeNull();
    expect(screen.queryByTestId('iframe-stub')).not.toBeNull();
    await flushEffects();
  });
});
