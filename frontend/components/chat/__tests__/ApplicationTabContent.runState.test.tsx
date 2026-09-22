/**
 * @vitest-environment jsdom
 *
 * Pins the UX contract of the application's run state: the app is ALWAYS
 * visible, and an overlay says what the run is doing.
 *
 * Two states, and the second one is why this file was rewritten. The run row
 * stays RUNNING for the whole time a node is parked on a signal (the backend
 * never writes AWAITING_SIGNAL to it), so the previous version of this test,
 * which fed `runStatus: 'awaiting_signal'`, was pinning a state the real
 * pipeline cannot produce. It passed, and it was blind to the actual defect:
 * the app swept a busy blue while it was in fact waiting for the user, with the
 * only action hidden inside a toolbar that is collapsed by default.
 *
 * So waiting is derived from the PENDING SIGNALS instead, and the fixtures
 * below are shaped the way production sends them.
 *
 * Because ApplicationTabContent is the single component rendered by the right
 * side panel, application detail, carousel, visualize card and fullscreen,
 * putting both the indicator and the action bar in its shared `iframeContent`
 * covers "everywhere the app renders" - which is why fullscreen is asserted too.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act, fireEvent, waitFor } from '@testing-library/react';
import * as React from 'react';
import {
  INTERFACE_CONTINUE_EVENT,
  INTERFACE_CONTINUE_RESPONSE_EVENT,
  type InterfaceContinueDetail,
} from '@/lib/workflow/interfaceContinue';

// Flush the passive plan-fetch effect (getWorkflow) so its trailing setState
// lands inside act() - keeps the React "not wrapped in act(...)" warning out of
// the output. Our assertions are synchronous on first render, so flushing
// before/after them doesn't change what is asserted.
async function flushEffects() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

// jsdom has no ResizeObserver, and the preview branch measures its container
// with one. Environment gap, not behaviour under test.
if (typeof globalThis.ResizeObserver === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

// Mutable run-state the mocked useRun returns - flipped per test.
const runStateRef = vi.hoisted(() => ({
  current: { runStatus: 'idle', executionTotal: 0 } as Record<string, unknown>,
}));

vi.mock('@/i18n/navigation', () => ({ usePathname: () => '/app/workflow/wf-1' }));

const resolveApproval = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  useRun: () => [runStateRef.current, { executeStep: vi.fn(), resolveApproval }],
}));

vi.mock('@/contexts/WorkflowModeContext', () => ({
  // isRunMode true so useRun is wired with the runId and runStatus is read.
  useWorkflowMode: () => ({ isRunMode: true, isPreviewOnly: false }),
}));

// useInterfaceRender ALWAYS returns a non-empty htmlTemplate so the app iframe
// renders in every state - the only variables under test are the overlays.
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
  apiClient: { getTokenProvider: () => null, getAuthToken: async () => null },
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

vi.mock('@/app/workflows/builder/utils/safeCenteringCss', () => ({ SAFE_CENTERING_CSS: '', centeringCssFor: () => '' }));

vi.mock('@/lib/utils/dateFormatters', () => ({
  parseUtcAware: (s: string) => new Date(s),
  formatUtcTime: (s: string) => s,
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => (key === 'running' ? 'Running' : key),
}));

import { ApplicationTabContent } from '../ApplicationTabContent';

const baseConfig = { interfaceId: 'iface-1', label: 'tab', actionMapping: {} };

/** The interface on screen IS blocking, so its own Continue is in play. */
const blockingConfig = {
  interfaceId: 'iface-1',
  label: 'tab',
  nodeId: 'interface:tab',
  actionMapping: { submit: '__continue' },
};

/** Executing, nothing parked. */
const RUNNING = { runStatus: 'running', executionTotal: 0, pendingSignals: [] };

/** Executing AND parked on an approval that belongs to a DIFFERENT node. */
const PARKED_ON_APPROVAL = {
  runStatus: 'running',
  executionTotal: 0,
  pendingSignals: [{
    id: 11,
    nodeId: 'agent:review_draft',
    signalType: 'USER_APPROVAL',
    status: 'PENDING',
    epoch: 2,
    itemId: '0',
    approvalContext: 'Send the 3 reminders?',
  }],
};

/** Parked on the blocking `__continue` of the interface on screen. */
const PARKED_ON_INTERFACE = {
  runStatus: 'running',
  executionTotal: 0,
  awaitingSignalSteps: new Set(['interface:tab']),
  pendingSignals: [{
    id: 21,
    nodeId: 'interface:tab',
    signalType: 'INTERFACE_SIGNAL',
    status: 'PENDING',
    epoch: 1,
    itemId: '0',
  }],
};

function renderApp(config: Record<string, unknown> = baseConfig, extra: Record<string, unknown> = {}) {
  return render(
    <ApplicationTabContent
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      config={config as any}
      runId="run_1"
      workflowId="wf-1"
      onAction={() => undefined}
      {...extra}
    />,
  );
}

/**
 * A stand-in for the workflow event bridge, so the application's OWN Continue
 * can be clicked end to end. Without it nothing in this suite ever pressed that
 * button, and reverting the awaited `requestInterfaceContinue` back to a
 * fire-and-forget dispatch passed the whole file.
 */
let continueEvents: InterfaceContinueDetail[];
let bridgeAnswer: { ok: boolean; alreadyResolved?: boolean; status?: number } | null;
const bridge = (e: Event) => {
  const detail = (e as CustomEvent<InterfaceContinueDetail>).detail;
  continueEvents.push(detail);
  if (!bridgeAnswer || !detail.requestId) return;
  window.dispatchEvent(new CustomEvent(INTERFACE_CONTINUE_RESPONSE_EVENT, {
    detail: { requestId: detail.requestId, ...bridgeAnswer },
  }));
};

describe('ApplicationTabContent - run state indicator', () => {
  beforeEach(() => {
    runStateRef.current = { runStatus: 'idle', executionTotal: 0 };
    resolveApproval.mockClear();
    continueEvents = [];
    bridgeAnswer = { ok: true };
    window.addEventListener(INTERFACE_CONTINUE_EVENT, bridge);
  });
  afterEach(() => {
    window.removeEventListener(INTERFACE_CONTINUE_EVENT, bridge);
    cleanup();
  });

  it('sweeps BLUE while the engine is executing, with the app still visible', async () => {
    runStateRef.current = { ...RUNNING };
    const { queryByTestId } = renderApp();
    const indicator = queryByTestId('application-run-state');
    expect(indicator).not.toBeNull();
    expect(indicator?.dataset.runState).toBe('running');
    expect(
      indicator?.querySelector('.app-run-state__sweep'),
      'the sweep is the "work is advancing" cue',
    ).not.toBeNull();
    // The app iframe is STILL shown underneath, never replaced by a skeleton.
    expect(queryByTestId('iframe-stub')).not.toBeNull();
    // Nothing to answer, so no action bar.
    expect(queryByTestId('application-run-action-bar')).toBeNull();
    await flushEffects();
  });

  it('turns AMBER and stops sweeping while the run waits on a human', async () => {
    // The run row still says `running` here - exactly as production sends it.
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp();
    const indicator = queryByTestId('application-run-state');
    expect(indicator?.dataset.runState).toBe('awaiting');
    expect(
      indicator?.querySelector('.app-run-state__sweep'),
      'an app waiting on a person must not look busy',
    ).toBeNull();
    await flushEffects();
  });

  it('offers the approval ON the application, for a node the app is not even showing', async () => {
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp();
    const bar = queryByTestId('application-run-action-bar');
    expect(bar, 'an approval parked anywhere used to leave this screen frozen and silent').not.toBeNull();
    expect(bar?.dataset.blockerKind).toBe('approval');
    // The question the workflow author wrote is what the user reads.
    expect(queryByTestId('application-run-action-prompt')?.textContent).toBe('Send the 3 reminders?');
    // ...and it is named as living elsewhere in the workflow.
    expect(queryByTestId('application-run-action-elsewhere')).not.toBeNull();

    await act(async () => {
      queryByTestId('application-run-action-approve')?.dispatchEvent(
        new MouseEvent('click', { bubbles: true }),
      );
    });
    expect(resolveApproval).toHaveBeenCalledWith('run_1', 'agent:review_draft', 'APPROVED', 2, '0');
    await flushEffects();
  });

  it('asks the bridge for an ANSWER when its own Continue is pressed', async () => {
    // The button used to dispatch and hope: a 403 or a 404 looked exactly like
    // success and only a 10 s timer ever cleared the spinner.
    runStateRef.current = { ...PARKED_ON_INTERFACE };
    const { queryByTestId } = renderApp(blockingConfig);
    fireEvent.click(queryByTestId('application-run-action-continue')!);

    await waitFor(() => expect(continueEvents).toHaveLength(1), { timeout: 2000 });
    expect(continueEvents[0]).toMatchObject({ runId: 'run_1', nodeId: 'interface:tab', actionKey: '__continue' });
    expect(continueEvents[0].requestId, 'without it the bridge cannot answer').toBeTruthy();
    await flushEffects();
  });

  it('says so when its own Continue is refused, in words the reader can read', async () => {
    bridgeAnswer = { ok: false, status: 403 };
    runStateRef.current = { ...PARKED_ON_INTERFACE };
    const { queryByTestId } = renderApp(blockingConfig);
    fireEvent.click(queryByTestId('application-run-action-continue')!);

    await waitFor(() => expect(queryByTestId('application-run-action-failed')).not.toBeNull(), { timeout: 2000 });
    // A permission refusal is named, and never as the server raw English.
    expect(queryByTestId('application-run-action-failed')?.textContent).toBe('continueInterfaceForbidden');
    await flushEffects();
  });

  it('does not call an already-resolved continue a failure - the run DID move', async () => {
    bridgeAnswer = { ok: false, alreadyResolved: true, status: 404 };
    runStateRef.current = { ...PARKED_ON_INTERFACE };
    const { queryByTestId } = renderApp(blockingConfig);
    fireEvent.click(queryByTestId('application-run-action-continue')!);

    await waitFor(() => expect(continueEvents).toHaveLength(1), { timeout: 2000 });
    await flushEffects();
    expect(queryByTestId('application-run-action-failed')).toBeNull();
  });

  it('hangs the bar clear of the app toolbar and lets clicks through beside it', async () => {
    // The strip spans the application; only the bar itself may take a click, or
    // it swallows the app own bottom controls. And it sits ABOVE the toolbar:
    // at bottom-16 it landed exactly on the fullscreen one.
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp();
    const strip = queryByTestId('application-run-action-bar')?.parentElement;
    expect(strip?.className).toContain('pointer-events-none');
    expect(strip?.className).toContain('bottom-20');
    expect(queryByTestId('application-run-action-bar')?.className).toContain('pointer-events-auto');
    await flushEffects();
  });

  it('withholds a Continue that would advance a fire the reader is not on', async () => {
    // The fire carries no epoch and resolves the node NEWEST parked signal, so
    // from epoch 1 this button moves epoch 3 and reports success. The rule was
    // applied to approvals first and not to the continue beside them.
    runStateRef.current = {
      runStatus: 'running',
      executionTotal: 0,
      awaitingSignalSteps: new Set(['interface:tab']),
      pendingSignals: [{
        id: 31, nodeId: 'interface:tab', signalType: 'INTERFACE_SIGNAL',
        status: 'PENDING', epoch: 3, itemId: '0',
      }],
    };
    const { queryByTestId } = renderApp(blockingConfig, { viewingEpoch: 1, onViewingEpochChange: () => undefined });
    expect(queryByTestId('application-run-action-continue')).toBeNull();
    await flushEffects();
  });

  it('still says the run is parked on an older epoch, without claiming it is busy', async () => {
    // Scope belongs to the ACTION, not to the STATEMENT: deriving the indicator
    // from the actionable list made a parked run sweep a busy blue here.
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp(baseConfig, { viewingEpoch: 1, onViewingEpochChange: () => undefined });
    const indicator = queryByTestId('application-run-state');
    expect(indicator?.dataset.runState).toBe('awaiting');
    expect(indicator?.querySelector('.app-run-state__sweep')).toBeNull();
    // ...and it does not claim to be waiting on THIS reader, who cannot act here.
    expect(indicator?.textContent).toContain('awaitingSomeone');
    expect(queryByTestId('application-run-action-bar')).toBeNull();
    await flushEffects();
  });

  it('offers Continue on the application when the interface itself is parked', async () => {
    runStateRef.current = { ...PARKED_ON_INTERFACE };
    const { queryByTestId } = renderApp(blockingConfig);
    const bar = queryByTestId('application-run-action-bar');
    expect(bar?.dataset.blockerKind).toBe('interface_continue');
    // The Continue button used to live only inside a toolbar collapsed by
    // default, which is what made it effectively invisible.
    expect(queryByTestId('application-run-action-continue')).not.toBeNull();
    expect(queryByTestId('application-run-state')?.dataset.runState).toBe('awaiting');
    await flushEffects();
  });

  it('shows the app with no indicator at all once the run has completed', async () => {
    // The signal rows are STILL THERE on purpose: nothing cancels them on a
    // natural completion, so a fixture with an empty list would pass whatever
    // the code did. This is the shape that caught the bug.
    runStateRef.current = { ...PARKED_ON_APPROVAL, runStatus: 'completed' };
    const { queryByTestId } = renderApp();
    expect(queryByTestId('iframe-stub')).not.toBeNull();
    expect(queryByTestId('application-run-state')).toBeNull();
    expect(
      queryByTestId('application-run-action-bar'),
      'a finished run is waiting for nobody, whatever its stale signal rows say',
    ).toBeNull();
    await flushEffects();
  });

  it('offers nothing on a FAILED run either, not just a clean one', async () => {
    runStateRef.current = { ...PARKED_ON_APPROVAL, runStatus: 'failed' };
    const { queryByTestId } = renderApp();
    expect(queryByTestId('application-run-action-bar')).toBeNull();
    expect(queryByTestId('application-run-state')).toBeNull();
    await flushEffects();
  });

  it('hides an approval that belongs to an epoch the reader is not looking at', async () => {
    // Browsing epoch 1 of a multi-fire run: the approval parked in epoch 2 is
    // about work that is not on screen, and resolving it from here would
    // advance a fire the reader cannot see.
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp(baseConfig, { viewingEpoch: 1, onViewingEpochChange: () => undefined });
    expect(queryByTestId('application-run-action-bar')).toBeNull();
    await flushEffects();
  });

  it('offers it again on the epoch it actually belongs to', async () => {
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp(baseConfig, { viewingEpoch: 2, onViewingEpochChange: () => undefined });
    expect(queryByTestId('application-run-action-bar')).not.toBeNull();
    await flushEffects();
  });

  it('carries both the indicator and the action bar into fullscreen', async () => {
    // Fullscreen portals to document.body and shares the same `iframeContent`
    // const, which is what gives every surface the same treatment from one
    // place. Queried through `screen` since the portal escapes the container.
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    renderApp(baseConfig, { isExpanded: true, onExpandedChange: () => undefined });
    expect(screen.queryByTestId('application-run-state')?.dataset.runState).toBe('awaiting');
    expect(screen.queryByTestId('application-run-action-bar')).not.toBeNull();
    expect(screen.queryByTestId('iframe-stub')).not.toBeNull();
    await flushEffects();
  });

  it('offers nothing to act on in a preview, where the run belongs to the publisher', async () => {
    runStateRef.current = { ...PARKED_ON_APPROVAL };
    const { queryByTestId } = renderApp(baseConfig, { previewMode: true });
    expect(queryByTestId('application-run-action-bar')).toBeNull();
    // ...but the run IS parked, and saying "running" here would be the very lie
    // this change exists to remove. Amber, with a label that does not claim to
    // be waiting on a viewer who cannot act.
    expect(queryByTestId('application-run-state')?.dataset.runState).toBe('awaiting');
    expect(queryByTestId('application-run-state')?.textContent).toContain('awaitingSomeone');
    await flushEffects();
  });
});
