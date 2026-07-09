/**
 * @vitest-environment jsdom
 *
 * Regression: when the agent (chatting in the workflow panel) launches THIS
 * workflow, the main canvas on the left must enter run mode. Every agent-run
 * emits a global `sidePanelAutoOpen` marker (type 'workflow_run', id=workflowId,
 * runId). On chat pages AppHeader reacts to it, but that handler is gated to chat
 * pages - so on the workflow page nothing reacted and the left canvas stayed in
 * edit mode.
 *
 * WorkflowDetailView reacts to the marker for its OWN workflow and binds the run
 * IN PLACE (no navigation): `setRunId(runId)` flips to run mode without a URL
 * change, and `markRunAsJustExecuted(runId)` keeps the current canvas plan so the
 * run statuses overlay on the existing nodes with zero refresh (an earlier
 * version did a `router.push` to the run URL, which reloaded the plan and wiped
 * the user's context).
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const push = vi.hoisted(() => vi.fn());
const setRunId = vi.hoisted(() => vi.fn());
const markRunAsJustExecuted = vi.hoisted(() => vi.fn());
const modeState = vi.hoisted(() => ({ current: { isPreviewOnly: false, runId: null as string | null, setRunId } }));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => modeState.current,
}));
vi.mock('@/app/workflows/builder/hooks/useWorkflowLoader', () => ({ markRunAsJustExecuted }));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/app/workflows/builder/hooks/state', () => ({
  useUnsavedChanges: () => ({
    handleDirtyChange: vi.fn(),
    handleRefreshBlocked: vi.fn(),
    saveRef: { current: null },
    showModal: false,
    handleSave: vi.fn(),
    handleDiscard: vi.fn(),
    handleCancel: vi.fn(),
    isSaving: false,
  }),
}));
vi.mock('@/components/app/WorkflowPanelContent', () => ({ setPendingActivateTab: vi.fn() }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun: vi.fn() } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';

function fireAutoOpen(detail: { type: string; id: string; runId?: string }) {
  act(() => {
    window.dispatchEvent(new CustomEvent('sidePanelAutoOpen', { detail }));
  });
}

describe('WorkflowDetailView - overlays an agent-launched run on the canvas in place', () => {
  afterEach(() => {
    push.mockReset();
    setRunId.mockReset();
    markRunAsJustExecuted.mockReset();
    modeState.current = { isPreviewOnly: false, runId: null, setRunId };
    cleanup();
  });

  it('binds the run IN PLACE (setRunId + markRunAsJustExecuted, no navigation) for THIS workflow', () => {
    render(<WorkflowDetailView workflowId="wf-1" />);
    fireAutoOpen({ type: 'workflow_run', id: 'wf-1', runId: 'run-abc' });
    expect(setRunId).toHaveBeenCalledWith('run-abc');
    // keeps the current plan (overlay, not reload) so context is preserved
    expect(markRunAsJustExecuted).toHaveBeenCalledWith('run-abc');
    // never navigates - navigation was the context-wiping behavior we removed
    expect(push).not.toHaveBeenCalled();
  });

  it('ignores a run of a DIFFERENT workflow (that would need its own panel tab)', () => {
    render(<WorkflowDetailView workflowId="wf-1" />);
    fireAutoOpen({ type: 'workflow_run', id: 'wf-other', runId: 'run-abc' });
    expect(setRunId).not.toHaveBeenCalled();
    expect(markRunAsJustExecuted).not.toHaveBeenCalled();
  });

  it('ignores non-workflow_run markers (datasource/table/agent open their own tabs)', () => {
    render(<WorkflowDetailView workflowId="wf-1" />);
    fireAutoOpen({ type: 'datasource', id: 'wf-1', runId: 'run-abc' });
    expect(setRunId).not.toHaveBeenCalled();
  });

  it('ignores a workflow_run marker with no runId (showcase marker)', () => {
    render(<WorkflowDetailView workflowId="wf-1" />);
    fireAutoOpen({ type: 'workflow_run', id: 'wf-1' });
    expect(setRunId).not.toHaveBeenCalled();
  });

  it('does not re-bind when already on that run (no redundant flip loop)', () => {
    // runIdProp already equals the incoming run → effectiveRunId matches → no-op.
    render(<WorkflowDetailView workflowId="wf-1" runId="run-abc" />);
    fireAutoOpen({ type: 'workflow_run', id: 'wf-1', runId: 'run-abc' });
    expect(setRunId).not.toHaveBeenCalled();
    expect(markRunAsJustExecuted).not.toHaveBeenCalled();
  });

  it('does nothing in preview-only mode (marketplace preview must not bind a run)', () => {
    modeState.current = { isPreviewOnly: true, runId: null, setRunId };
    render(<WorkflowDetailView workflowId="wf-1" />);
    fireAutoOpen({ type: 'workflow_run', id: 'wf-1', runId: 'run-abc' });
    expect(setRunId).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });
});
