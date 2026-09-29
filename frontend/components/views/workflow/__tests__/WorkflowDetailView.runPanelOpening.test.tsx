/**
 * @vitest-environment jsdom
 *
 * The workflow page owns three behaviours the side panel cannot own itself,
 * because the panel BODY is unmounted while the panel is closed:
 *
 *  1. pressing Run shows the run that was just launched (Run tab, run level),
 *     and only for a LIVE run - opening a finished run's URL must not pop the
 *     panel open;
 *  2. the canvas entry points (version chip, panel button, "+") focus the right
 *     sub-tab whether the panel is open or closed, without leaving a stale
 *     "pending tab" behind when it is already open;
 *  3. picking a run in the history rebinds the canvas IN PLACE, with no
 *     navigation (a router push remounted the whole route and read as a full
 *     page refresh just to look at a sibling run).
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const setPendingActivateTab = vi.hoisted(() => vi.fn());
const setActiveTab = vi.hoisted(() => vi.fn());
const modeState = vi.hoisted(() => ({ current: { isPreviewOnly: false, runId: null as string | null, setRunId } }));
const panelState = vi.hoisted(() => ({ current: { isOpen: false, activeTabId: null as string | null, setActiveTab, open: vi.fn(), openTab: vi.fn() } }));
const canvasProps = vi.hoisted(() => ({ current: null as any }));

vi.mock('next/navigation', () => ({ useRouter: () => ({ push, replace: vi.fn() }) }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => modeState.current }));
vi.mock('@/app/workflows/builder/hooks/useWorkflowLoader', () => ({ markRunAsJustExecuted: vi.fn() }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => panelState.current }));
vi.mock('@/app/workflows/builder/hooks/state', () => ({
  useUnsavedChanges: () => ({
    handleDirtyChange: vi.fn(), handleRefreshBlocked: vi.fn(), saveRef: { current: null },
    showModal: false, handleSave: vi.fn(), handleDiscard: vi.fn(), handleCancel: vi.fn(), isSaving: false,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  setPendingActivateTab,
  RUN_TAB_ID: '__run__',
  ANALYSIS_TAB_ID: '__analysis__',
  NODE_CREATOR_TAB_ID: '__add_node__',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: any) => { canvasProps.current = props; return null; },
}));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';
import {
  openNodeCreatorPanel,
  openRunPanel,
  requestBindRun,
  OPEN_RUN_PANEL_EVENT,
} from '@/components/workflow/run-panel/runPanelBus';

const WF = 'wf-1';

/** Report a run status the way the canvas does (drives isActiveRun). */
function reportRunStatus(runStatus: string, runId = 'run-1') {
  act(() => {
    canvasProps.current.onTriggerConfigsChange({ configs: [], runStatus, runId });
  });
}

function captureRunPanelOpens(): { detail: any }[] {
  const seen: { detail: any }[] = [];
  window.addEventListener(OPEN_RUN_PANEL_EVENT, (e) => seen.push({ detail: (e as CustomEvent).detail }));
  return seen;
}

afterEach(() => {
  modeState.current = { isPreviewOnly: false, runId: null, setRunId };
  panelState.current = { isOpen: false, activeTabId: null, setActiveTab, open: vi.fn(), openTab: vi.fn() };
  canvasProps.current = null;
  push.mockReset(); setRunId.mockReset(); setPendingActivateTab.mockReset(); setActiveTab.mockReset();
  // Binding a run rewrites the jsdom URL: restore it, or the next test starts
  // on a path it never set.
  window.history.replaceState(null, '', '/');
  cleanup();
});

describe('WorkflowDetailView - showing the run that was just launched', () => {
  it('opens the Run tab on the RUN level for a live run', () => {
    const seen = captureRunPanelOpens();
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    reportRunStatus('RUNNING');

    expect(seen.map(s => s.detail)).toContainEqual({ workflowId: WF, view: 'run' });
    // Panel is closed: the target sub-tab has to survive the mount.
    expect(setPendingActivateTab).toHaveBeenCalledWith('__run__', WF);
  });

  it('does NOT pop the panel open for a run that is already finished', () => {
    const seen = captureRunPanelOpens();
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    reportRunStatus('COMPLETED');

    // Reading a finished run is not launching one.
    expect(seen).toHaveLength(0);
    expect(setPendingActivateTab).not.toHaveBeenCalled();
  });

  it('opens once per run, not on every status push', () => {
    const seen = captureRunPanelOpens();
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    reportRunStatus('RUNNING');
    reportRunStatus('PAUSED');
    reportRunStatus('WAITING_TRIGGER');

    // Re-opening after the user closed the panel would be a fight, not a feature.
    expect(seen).toHaveLength(1);
  });

  it('never auto-opens in preview mode', () => {
    modeState.current = { isPreviewOnly: true, runId: null, setRunId };
    const seen = captureRunPanelOpens();
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    reportRunStatus('RUNNING');

    expect(seen).toHaveLength(0);
  });
});

describe('WorkflowDetailView - canvas entry points', () => {
  it('when the panel is CLOSED: carries the sub-tab across the mount and opens the panel', () => {
    let toggled = 0;
    window.addEventListener('workflowViewToggleMessagesPanel', () => { toggled += 1; });
    render(<WorkflowDetailView workflowId={WF} />);

    act(() => openNodeCreatorPanel({ workflowId: WF }));

    expect(setPendingActivateTab).toHaveBeenCalledWith('__add_node__', WF);
    expect(toggled).toBeGreaterThan(0);
  });

  it('opens the panel on the Run tab, or on its Analysis sibling when asked (page header menu)', () => {
    render(<WorkflowDetailView workflowId={WF} />);

    act(() => openRunPanel({ workflowId: WF, view: 'run', tab: 'analysis' }));
    expect(setPendingActivateTab).toHaveBeenLastCalledWith('__analysis__', WF);

    act(() => openRunPanel({ workflowId: WF, view: 'run', tab: 'run' }));
    expect(setPendingActivateTab).toHaveBeenLastCalledWith('__run__', WF);
  });

  it('when the panel is OPEN ON THIS TAB: focuses it and leaves NO stale pending tab', () => {
    panelState.current = {
      isOpen: true, activeTabId: 'workflow-panel', setActiveTab, open: vi.fn(), openTab: vi.fn(),
    };
    render(<WorkflowDetailView workflowId={WF} />);

    act(() => openNodeCreatorPanel({ workflowId: WF }));

    expect(setActiveTab).toHaveBeenCalledWith('workflow-panel');
    // The mounted panel switches its own sub-tab; a pending value here would be
    // consumed later by an unrelated re-render and yank the panel then.
    expect(setPendingActivateTab).not.toHaveBeenCalled();
  });

  it('when the panel is OPEN ON ANOTHER TAB: still carries the sub-tab across the mount', () => {
    // The workflow panel tab is not keepMounted, so its body does not exist while
    // another tab is active - "panel is open" alone is not enough, and skipping
    // the pending tab there landed the user on AI Chat.
    panelState.current = {
      isOpen: true, activeTabId: 'application-panel', setActiveTab, open: vi.fn(), openTab: vi.fn(),
    };
    render(<WorkflowDetailView workflowId={WF} />);

    act(() => openNodeCreatorPanel({ workflowId: WF }));

    expect(setPendingActivateTab).toHaveBeenCalledWith('__add_node__', WF);
    expect(setActiveTab).toHaveBeenCalledWith('workflow-panel');
  });

  it('ignores entry points aimed at another workflow', () => {
    render(<WorkflowDetailView workflowId={WF} />);

    act(() => openNodeCreatorPanel({ workflowId: 'other-wf' }));
    act(() => openRunPanel({ workflowId: 'other-wf', view: 'history' }));

    expect(setPendingActivateTab).not.toHaveBeenCalled();
    expect(setActiveTab).not.toHaveBeenCalled();
  });
});

describe('WorkflowDetailView - binding another run in place', () => {
  it('rebinds the canvas without navigating, and takes the URL along', () => {
    window.history.replaceState(null, '', `/app/workflow/${WF}/run/run-1`);
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    act(() => requestBindRun({ workflowId: WF, runId: 'run-2' }));

    expect(setRunId).toHaveBeenCalledWith('run-2', { urlSynced: true });
    // The address bar follows so a reload lands on the picked run, but through
    // the History API - no router navigation, so nothing remounts.
    expect(window.location.pathname).toBe(`/app/workflow/${WF}/run/run-2`);
    expect(push).not.toHaveBeenCalled();
  });

  it('ignores a bind for the run already shown, and for another workflow', () => {
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    act(() => requestBindRun({ workflowId: WF, runId: 'run-1' }));
    act(() => requestBindRun({ workflowId: 'other-wf', runId: 'run-9' }));

    expect(setRunId).not.toHaveBeenCalled();
  });
});
