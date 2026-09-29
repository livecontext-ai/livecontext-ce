/**
 * @vitest-environment jsdom
 *
 * The workflow page follows the agent chatting in ITS workflow panel:
 *  1. the agent changes the stored plan while a run is on screen -> back to editing (the new
 *     version), URL included, and only for that panel's conversation;
 *  2. the agent launches / replays / resumes a run -> the run is bound, overlaying the canvas
 *     only when the canvas shows the version that run executes;
 *  3. the user toggles edit/run while that agent is still working -> the panel opens on the
 *     agent's chat instead of jumping to the Run tab.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const setRunId = vi.hoisted(() => vi.fn());
const setViewingEpoch = vi.hoisted(() => vi.fn());
const markRunAsJustExecuted = vi.hoisted(() => vi.fn());
const setPendingActivateTab = vi.hoisted(() => vi.fn());
const streamingConversations = vi.hoisted(() => ({ current: new Set<string>() }));
const canvasProps = vi.hoisted(() => ({ current: null as any }));
const modeState = vi.hoisted(() => ({
  current: {
    isPreviewOnly: false, runId: null as string | null, setRunId, setViewingEpoch,
    activeVersion: null as number | null, currentVersion: 13 as number | null,
  },
}));

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => modeState.current }));
vi.mock('@/contexts/StreamingContext', () => ({
  useStreamingSafe: () => ({ isStreamingConversation: (id: string) => streamingConversations.current.has(id) }),
}));
vi.mock('@/app/workflows/builder/hooks/useWorkflowLoader', () => ({ markRunAsJustExecuted }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/app/workflows/builder/hooks/state', () => ({
  useUnsavedChanges: () => ({
    handleDirtyChange: vi.fn(), handleRefreshBlocked: vi.fn(), saveRef: { current: null },
    showModal: false, handleSave: vi.fn(), handleDiscard: vi.fn(), handleCancel: vi.fn(), isSaving: false,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  setPendingActivateTab,
  requestPresentApplication: vi.fn(),
  APP_TAB_ID: '__application__',
  RUN_TAB_ID: '__run__',
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: any) => { canvasProps.current = props; return null; },
}));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';
import { OPEN_RUN_PANEL_EVENT } from '@/components/workflow/run-panel/runPanelBus';
import {
  consumeUserModeToggle,
  markUserModeToggle,
  rememberWorkflowPanelConversation,
  WORKFLOW_PANEL_CHAT_TAB_ID,
} from '@/lib/workflow/workflowPanelChat';

const WF = 'wf-1';
const PANEL_CONV = 'conv-panel';

function fireMarker(detail: Record<string, unknown>) {
  act(() => {
    window.dispatchEvent(new CustomEvent('sidePanelAutoOpen', { detail }));
  });
}

const listeners: Array<[string, EventListener]> = [];
function capture(eventName: string): any[] {
  const seen: any[] = [];
  const listener: EventListener = (e) => seen.push((e as CustomEvent).detail);
  window.addEventListener(eventName, listener);
  listeners.push([eventName, listener]);
  return seen;
}

beforeEach(() => {
  rememberWorkflowPanelConversation(WF, PANEL_CONV);
});

afterEach(() => {
  modeState.current = {
    isPreviewOnly: false, runId: null, setRunId, setViewingEpoch, activeVersion: null, currentVersion: 13,
  };
  streamingConversations.current = new Set();
  canvasProps.current = null;
  setRunId.mockReset(); setViewingEpoch.mockReset(); markRunAsJustExecuted.mockReset(); setPendingActivateTab.mockReset();
  window.history.replaceState(null, '', '/');
  consumeUserModeToggle(WF, null); // spend any mark a test left behind
  for (const [name, listener] of listeners.splice(0)) window.removeEventListener(name, listener);
  cleanup();
});

describe('the panel agent edits the plan while a run is on screen', () => {
  it('goes back to editing and brings the address bar along', () => {
    window.history.replaceState(null, '', `/fr/app/workflow/${WF}/run/run-1`);
    modeState.current = { ...modeState.current, runId: 'run-1' };
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    fireMarker({ type: 'workflow', id: WF, planChanged: true, conversationId: PANEL_CONV });

    expect(setRunId).toHaveBeenCalledWith(null);
    expect(window.location.pathname).toBe(`/fr/app/workflow/${WF}`);
  });

  it('stays on the run when another chat edited the workflow', () => {
    window.history.replaceState(null, '', `/app/workflow/${WF}/run/run-1`);
    modeState.current = { ...modeState.current, runId: 'run-1' };
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    fireMarker({ type: 'workflow', id: WF, planChanged: true, conversationId: 'conv-elsewhere' });

    expect(setRunId).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe(`/app/workflow/${WF}/run/run-1`);
  });

  it('stays on the run when the action did not change the plan (a load)', () => {
    modeState.current = { ...modeState.current, runId: 'run-1' };
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    fireMarker({ type: 'workflow', id: WF, planChanged: false, conversationId: PANEL_CONV });

    expect(setRunId).not.toHaveBeenCalled();
  });
});

describe('the agent runs this workflow: bound on the right version', () => {
  it('overlays the canvas when it shows the version the run executes', () => {
    modeState.current = { ...modeState.current, activeVersion: 13 };
    render(<WorkflowDetailView workflowId={WF} />);

    fireMarker({ type: 'workflow_run', id: WF, runId: 'run-2', planVersion: 13 });

    expect(markRunAsJustExecuted).toHaveBeenCalledWith('run-2');
    expect(setRunId).toHaveBeenCalledWith('run-2');
  });

  it("loads the run's own plan when it runs another version (e.g. the pinned one)", () => {
    modeState.current = { ...modeState.current, activeVersion: 13 };
    render(<WorkflowDetailView workflowId={WF} />);

    fireMarker({ type: 'workflow_run', id: WF, runId: 'run-2', planVersion: 10 });

    expect(markRunAsJustExecuted).not.toHaveBeenCalled();
    expect(setRunId).toHaveBeenCalledWith('run-2');
  });

  it("loads the run's own plan when another run was on screen", () => {
    modeState.current = { ...modeState.current, runId: 'run-1', activeVersion: 13 };
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    fireMarker({ type: 'workflow_run', id: WF, runId: 'run-2', planVersion: 13 });

    expect(markRunAsJustExecuted).not.toHaveBeenCalled();
    expect(setRunId).toHaveBeenCalledWith('run-2');
  });
});

describe('toggling edit/run while the panel agent works', () => {
  function renderAt(runId: string | null) {
    modeState.current = { ...modeState.current, runId };
    return render(<WorkflowDetailView workflowId={WF} runId={runId ?? undefined} />);
  }

  it('opens the panel on the agent chat instead of the Run tab (edit -> run)', () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const runPanelOpens = capture(OPEN_RUN_PANEL_EVENT);
    const view = renderAt(null);

    markUserModeToggle(WF, 'run-1'); // the toggle's Run click
    modeState.current = { ...modeState.current, runId: 'run-1' };
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-1" />);
    // The run turns live, which is what normally pops the Run tab.
    act(() => { canvasProps.current.onTriggerConfigsChange({ configs: [], runStatus: 'RUNNING', runId: 'run-1' }); });

    expect(activations).toContainEqual({ tabId: WORKFLOW_PANEL_CHAT_TAB_ID, workflowId: WF });
    expect(setPendingActivateTab).toHaveBeenCalledWith(WORKFLOW_PANEL_CHAT_TAB_ID, WF);
    expect(runPanelOpens).toHaveLength(0);
  });

  it('opens the panel on the agent chat on the way back too (run -> edit)', () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const view = renderAt('run-1');

    markUserModeToggle(WF, null); // the toggle's Edit click
    modeState.current = { ...modeState.current, runId: null };
    view.rerender(<WorkflowDetailView workflowId={WF} />);

    expect(activations).toContainEqual({ tabId: WORKFLOW_PANEL_CHAT_TAB_ID, workflowId: WF });
  });

  it('leaves the panel alone when the agent is idle, and a live run still opens the Run tab', () => {
    const activations = capture('workflowPanelActivateTab');
    const runPanelOpens = capture(OPEN_RUN_PANEL_EVENT);
    const view = renderAt(null);

    markUserModeToggle(WF, 'run-1');
    modeState.current = { ...modeState.current, runId: 'run-1' };
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-1" />);
    act(() => { canvasProps.current.onTriggerConfigsChange({ configs: [], runStatus: 'RUNNING', runId: 'run-1' }); });

    expect(activations.filter((d) => d?.tabId === WORKFLOW_PANEL_CHAT_TAB_ID)).toHaveLength(0);
    expect(runPanelOpens.length).toBeGreaterThan(0);
  });

  it('shows the Run tab for a run the user launches with Run, or picks from the edit page (not a toggle)', () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const runPanelOpens = capture(OPEN_RUN_PANEL_EVENT);
    const view = renderAt(null);

    // No toggle mark: the canvas Run button (or a history pick) bound this run.
    modeState.current = { ...modeState.current, runId: 'run-1' };
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-1" />);
    act(() => { canvasProps.current.onTriggerConfigsChange({ configs: [], runStatus: 'RUNNING', runId: 'run-1' }); });

    expect(activations.filter((d) => d?.tabId === WORKFLOW_PANEL_CHAT_TAB_ID)).toHaveLength(0);
    expect(runPanelOpens.length).toBeGreaterThan(0);
  });

  it('does not mistake a later change for a toggle whose run never came (the mark is spent)', () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const view = renderAt(null);

    markUserModeToggle(WF, 'run-1');                 // toggle clicked...
    modeState.current = { ...modeState.current, runId: 'run-7' }; // ...but another run got bound
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-7" />);

    expect(activations.filter((d) => d?.tabId === WORKFLOW_PANEL_CHAT_TAB_ID)).toHaveLength(0);
  });

  it('stays on the Run tab when switching from one run to another (a history pick is not a toggle)', () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const view = renderAt('run-1');

    modeState.current = { ...modeState.current, runId: 'run-2' };
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-2" />);

    expect(activations.filter((d) => d?.tabId === WORKFLOW_PANEL_CHAT_TAB_ID)).toHaveLength(0);
  });

  it("still shows the Run tab for a run the agent itself launched (its binding is not the user's toggle)", () => {
    streamingConversations.current = new Set([PANEL_CONV]);
    const activations = capture('workflowPanelActivateTab');
    const runPanelOpens = capture(OPEN_RUN_PANEL_EVENT);
    const view = renderAt(null);

    fireMarker({ type: 'workflow_run', id: WF, runId: 'run-9', planVersion: 13, conversationId: PANEL_CONV });
    expect(setRunId).toHaveBeenCalledWith('run-9');
    // What the provider does with that call: the page now shows run-9.
    modeState.current = { ...modeState.current, runId: 'run-9' };
    view.rerender(<WorkflowDetailView workflowId={WF} runId="run-9" />);
    act(() => { canvasProps.current.onTriggerConfigsChange({ configs: [], runStatus: 'RUNNING', runId: 'run-9' }); });

    expect(activations.filter((d) => d?.tabId === WORKFLOW_PANEL_CHAT_TAB_ID)).toHaveLength(0);
    expect(runPanelOpens.length).toBeGreaterThan(0);
  });
});

describe('preview (marketplace) never follows an agent', () => {
  it('neither binds nor leaves a run', () => {
    modeState.current = { ...modeState.current, isPreviewOnly: true, runId: 'run-1' };
    render(<WorkflowDetailView workflowId={WF} runId="run-1" />);

    fireMarker({ type: 'workflow', id: WF, planChanged: true, conversationId: PANEL_CONV });
    fireMarker({ type: 'workflow_run', id: WF, runId: 'run-2', planVersion: 13 });

    expect(setRunId).not.toHaveBeenCalled();
  });
});
