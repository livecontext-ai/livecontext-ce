/**
 * @vitest-environment jsdom
 *
 * Regression: the workflow panel's sub-tabs (AI Chat / Trigger / Application) are
 * built from the trigger/application/agent configs WorkflowDetailView forwards to
 * WorkflowPanelContent. Those dispatches used to be gated on `runIdProp` (the URL
 * runId). Once an agent-launched run is bound IN PLACE (setRunId, no /run/ URL),
 * `runIdProp` is undefined, so the configs went out empty and the Trigger /
 * Application sub-tabs never appeared in run mode. The gate must be `effectiveRunId`
 * (set for a URL run AND an in-place run; null in edit mode).
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const canvasProps = vi.hoisted(() => ({ current: null as any }));
const modeState = vi.hoisted(() => ({ current: { isPreviewOnly: false, runId: null as string | null, setRunId: vi.fn() } }));

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }) }));
vi.mock('@/contexts/WorkflowModeContext', () => ({ useWorkflowMode: () => modeState.current }));
vi.mock('@/app/workflows/builder/hooks/useWorkflowLoader', () => ({ markRunAsJustExecuted: vi.fn() }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/app/workflows/builder/hooks/state', () => ({
  useUnsavedChanges: () => ({
    handleDirtyChange: vi.fn(), handleRefreshBlocked: vi.fn(), saveRef: { current: null },
    showModal: false, handleSave: vi.fn(), handleDiscard: vi.fn(), handleCancel: vi.fn(), isSaving: false,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ setPendingActivateTab: vi.fn() }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
// Capture the canvas props so the test can drive the config emissions.
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({
  WorkflowRunCanvas: (props: any) => { canvasProps.current = props; return null; },
}));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';

function collect(eventName: string): any[] {
  const seen: any[] = [];
  window.addEventListener(eventName, (e) => seen.push((e as CustomEvent).detail));
  return seen;
}

describe('WorkflowDetailView - forwards panel sub-tab configs during an IN-PLACE run', () => {
  afterEach(() => {
    canvasProps.current = null;
    modeState.current = { isPreviewOnly: false, runId: null, setRunId: vi.fn() };
    cleanup();
  });

  it('dispatches non-empty trigger + application configs when the run is bound in place (no runId prop)', () => {
    // In-place run: the run is bound via context (setRunId), NOT via the URL, so
    // runIdProp is undefined while contextRunId is set.
    modeState.current = { isPreviewOnly: false, runId: 'run-inplace', setRunId: vi.fn() };
    const trigEvents = collect('workflowPanelTriggerDataChange');
    const appEvents = collect('workflowPanelApplicationConfigsChange');

    render(<WorkflowDetailView workflowId="wf-1" />); // NO runId prop
    expect(canvasProps.current).not.toBeNull();

    act(() => {
      canvasProps.current.onTriggerConfigsChange({
        configs: [{ triggerId: 'trigger:start', triggerLabel: 'Start', type: 'form' }],
        runStatus: 'WAITING_TRIGGER',
        runId: 'run-inplace', // the canvas reports its bound run id here
      });
      canvasProps.current.onApplicationConfigsChange([{ interfaceId: 'iface-1', label: 'Page', actionMapping: {} }]);
    });

    const lastTrig = trigEvents[trigEvents.length - 1];
    const lastApp = appEvents[appEvents.length - 1];
    expect(lastTrig.configs).toHaveLength(1);
    expect(lastTrig.configs[0].triggerId).toBe('trigger:start');
    // The bound run id must be forwarded so the panel's Application/interface can
    // resolve its run in place (no /run/ URL). Without it the app shows "not available".
    expect(lastTrig.runId).toBe('run-inplace');
    expect(lastApp.configs).toHaveLength(1);
    expect(lastApp.configs[0].interfaceId).toBe('iface-1');
  });

  it('keeps configs EMPTY in edit mode (no bound run) even if the canvas emits', () => {
    // Edit mode: no contextRunId, no runIdProp → effectiveRunId null → suppress.
    const trigEvents = collect('workflowPanelTriggerDataChange');
    const appEvents = collect('workflowPanelApplicationConfigsChange');

    render(<WorkflowDetailView workflowId="wf-1" />);
    act(() => {
      canvasProps.current.onTriggerConfigsChange({
        configs: [{ triggerId: 'trigger:start', triggerLabel: 'Start', type: 'form' }],
        runStatus: 'WAITING_TRIGGER',
      });
      canvasProps.current.onApplicationConfigsChange([{ interfaceId: 'iface-1', label: 'Page', actionMapping: {} }]);
    });

    expect(trigEvents[trigEvents.length - 1].configs).toHaveLength(0);
    expect(appEvents[appEvents.length - 1].configs).toHaveLength(0);
  });
});
