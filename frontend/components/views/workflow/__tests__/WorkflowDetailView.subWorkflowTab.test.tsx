/**
 * @vitest-environment jsdom
 *
 * Opening a sub-workflow from a workflow node. The tab id is what the panel
 * merges on AND what "Go to page" turns into a URL, so it must be the workflow's
 * own id, never an entry-point-specific variant: the old `workflow-builder-<id>`
 * form gave the same workflow a second tab and navigated to
 * /app/workflow/builder-<id>, which does not exist ("Failed to load this
 * workflow").
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const setRunId = vi.hoisted(() => vi.fn());
const openTab = vi.hoisted(() => vi.fn());
const getPinnedWorkflowRun = vi.hoisted(() => vi.fn());
const modeState = vi.hoisted(() => ({ current: { isPreviewOnly: false, runId: null as string | null, setRunId } }));
const panelState = vi.hoisted(() => ({ current: { isOpen: false, activeTabId: null as string | null, setActiveTab: vi.fn(), open: vi.fn(), openTab } }));

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }) }));
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
  setPendingActivateTab: vi.fn(), RUN_TAB_ID: '__run__', NODE_CREATOR_TAB_ID: '__add_node__',
}));
vi.mock('@/components/app/WorkflowBuilderPanelContent', () => ({
  WorkflowBuilderPanelContent: () => null,
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('../hooks', () => ({ useAutoCollapseSidebar: () => undefined }));
vi.mock('@/components/modals/UnsavedChangesModal', () => ({ UnsavedChangesModal: () => null }));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));

import { WorkflowDetailView } from '@/components/views/workflow/WorkflowDetailView';

const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const SUB_WF = 'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

afterEach(() => {
  modeState.current = { isPreviewOnly: false, runId: null, setRunId };
  openTab.mockReset();
  getPinnedWorkflowRun.mockReset();
  cleanup();
});

/** Fire the event the sub-workflow node button dispatches, and let the async handler settle. */
async function openSubWorkflow(detail: Record<string, unknown> = {}) {
  render(<WorkflowDetailView workflowId={WF} />);
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
      detail: { workflowId: SUB_WF, workflowName: 'Sub', nodeId: 'node-1', ...detail },
    }));
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('WorkflowDetailView - opening a sub-workflow', () => {
  it('opens it under the workflow own id, so "Go to page" resolves to a real workflow', async () => {
    getPinnedWorkflowRun.mockResolvedValue(null);

    await openSubWorkflow();

    expect(openTab).toHaveBeenCalledTimes(1);
    // Pre-fix: 'workflow-builder-ef1d124a-610b-4c6b-b1d8-8fb6a6f20604'.
    expect(openTab.mock.calls[0][0].id).toBe(`workflow-${SUB_WF}`);
    expect(openTab.mock.calls[0][0].id).not.toContain('builder-');
  });

  it('opens the pinned run under the run id, which stays distinct from the workflow tab', async () => {
    getPinnedWorkflowRun.mockResolvedValue({ runId: RUN });

    await openSubWorkflow();

    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab.mock.calls[0][0].id).toBe(`workflow-run-${SUB_WF}-${RUN}`);
    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.hostTabId).toBe(`workflow-run-${SUB_WF}-${RUN}`);
  });

  it('still opens the workflow when the pinned-run lookup fails', async () => {
    getPinnedWorkflowRun.mockRejectedValue(new Error('offline'));

    await openSubWorkflow();

    expect(openTab).toHaveBeenCalledTimes(1);
    expect(openTab.mock.calls[0][0].id).toBe(`workflow-${SUB_WF}`);
  });

  it('ignores a request addressed to a canvas other than its own', async () => {
    // This listener is the twin of the side panel's, on the same `window` and
    // building the same tab id, so an unaddressed request is answered by both and
    // the last to resolve wins. This page always opens sub-workflows editable, so
    // answering for someone else's canvas is how a locked application panel's tab
    // got replaced by an editable one.
    getPinnedWorkflowRun.mockResolvedValue(null);
    await openSubWorkflow({ sourceWorkflowId: 'another-canvas-7f3a' });

    expect(openTab).not.toHaveBeenCalled();
  });

  it('answers a request that names no source, as every existing caller sends', async () => {
    getPinnedWorkflowRun.mockResolvedValue(null);
    await openSubWorkflow();

    expect(openTab).toHaveBeenCalledTimes(1);
  });
});
