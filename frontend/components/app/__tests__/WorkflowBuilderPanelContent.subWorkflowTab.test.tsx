/**
 * @vitest-environment jsdom
 *
 * This panel carries a SECOND `workflowOpenSubWorkflow` listener, twin of the one
 * in WorkflowDetailView. Both sit on `window`, and sub-workflow tabs are opened
 * `keepMounted`, so once one is open BOTH handlers answer the same click. They
 * must therefore build the SAME tab id: openTab merges on the id, so two ids mean
 * two tabs for one sub-workflow, the second stealing focus.
 *
 * Pinning the id here and in WorkflowDetailView.subWorkflowTab.test.tsx keeps the
 * twins in agreement; tabResource.noHandBuiltIds.test.ts stops a third one from
 * appearing.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render } from '@testing-library/react';

const openTab = vi.hoisted(() => vi.fn());
const getPinnedWorkflowRun = vi.hoisted(() => vi.fn());

vi.mock('@/contexts/WorkflowModeContext', () => ({
  WorkflowModeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useWorkflowMode: () => ({ runId: null, setRunId: vi.fn() }),
}));
vi.mock('@/contexts/WorkflowRunContext', () => ({
  WorkflowRunProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => ({ openTab, tabs: [] }) }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => undefined }));
vi.mock('@/lib/api', () => ({ orchestratorApi: { getPinnedWorkflowRun } }));
vi.mock('@/components/app/DataSourcePanelContent', () => ({ DataSourcePanelContent: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AgentPanelContent: () => null }));
vi.mock('@/components/app/WorkflowPanelContent', () => ({
  WorkflowPanelContent: (props: { workflowCanvasSlot?: React.ReactNode }) => <div>{props.workflowCanvasSlot}</div>,
}));
vi.mock('@/components/workflow/WorkflowRunCanvas', () => ({ WorkflowRunCanvas: () => null }));

import { WorkflowBuilderPanelContent } from '@/components/app/WorkflowBuilderPanelContent';

const WF = 'f54f378a-c4ff-4398-a003-107c87e9f2a6';
const SUB_WF = 'ef1d124a-610b-4c6b-b1d8-8fb6a6f20604';
const RUN = '9c3f1b2e-77aa-4d61-9d0e-51d2b6a4c8f0';

afterEach(() => { openTab.mockReset(); getPinnedWorkflowRun.mockReset(); cleanup(); });

async function openSubWorkflowFromPanel(
  props: Record<string, unknown> = {},
  detail: Record<string, unknown> = {},
) {
  render(<WorkflowBuilderPanelContent workflowId={WF} {...props} />);
  await dispatchOpen(detail);
}

async function dispatchOpen(detail: Record<string, unknown> = {}) {
  await act(async () => {
    window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
      detail: { workflowId: SUB_WF, workflowName: 'Sub', nodeId: 'node-1', ...detail },
    }));
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('WorkflowBuilderPanelContent - opening a nested sub-workflow', () => {
  it('uses the workflow own id, the same one WorkflowDetailView uses', async () => {
    getPinnedWorkflowRun.mockResolvedValue(null);

    await openSubWorkflowFromPanel();

    expect(openTab).toHaveBeenCalledTimes(1);
    // Pre-fix this listener kept building 'workflow-builder-<id>' while its twin
    // had moved to 'workflow-<id>': one click, two tabs for one sub-workflow.
    expect(openTab.mock.calls[0][0].id).toBe(`workflow-${SUB_WF}`);
    expect(openTab.mock.calls[0][0].id).not.toContain('builder-');
  });

  it('uses the run-scoped id when the sub-workflow has a pinned run', async () => {
    getPinnedWorkflowRun.mockResolvedValue({ runId: RUN });

    await openSubWorkflowFromPanel();

    expect(openTab.mock.calls[0][0].id).toBe(`workflow-run-${SUB_WF}-${RUN}`);
    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.hostTabId).toBe(`workflow-run-${SUB_WF}-${RUN}`);
  });

  it('opens a sub-workflow of SOMEONE ELSE publication locked, and its own children too', async () => {
    // The child tab defaulted to editable, so a Save and a palette were offered on
    // a workflow belonging to the publisher - one hop out of a panel that refuses
    // both on the parent for exactly that reason.
    getPinnedWorkflowRun.mockResolvedValue(null);
    await openSubWorkflowFromPanel({ canEditWorkflow: false, canEditRelatedWorkflows: false });

    expect(openTab).toHaveBeenCalledTimes(1);
    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.canEditWorkflow).toBe(false);
    // ...and it hands the same answer on, so the child refuses a sub-workflow the
    // same way. That closes the EVENT path only: the child's own canvas opens in
    // edit mode (no run), where the node's direct opener does not carry this.
    expect(content.props.canEditRelatedWorkflows).toBe(false);
  });

  it('keeps a sub-workflow of an INSTALLED application editable, because the clone is one of ours', async () => {
    // The application's own plan is frozen, but the sub-workflows it calls were
    // cloned as ordinary workflows in this tenant: locking them would deny an edit
    // the backend accepts.
    getPinnedWorkflowRun.mockResolvedValue(null);
    await openSubWorkflowFromPanel({ canEditWorkflow: false, canEditRelatedWorkflows: true });

    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.canEditWorkflow).toBe(true);
  });

  it('carries the same answer to a sub-workflow opened on its pinned run', async () => {
    // The shared opener gives the run tab one host identity and carries the
    // permission to this workflow and any child it opens.
    getPinnedWorkflowRun.mockResolvedValue({ runId: RUN });
    await openSubWorkflowFromPanel({ canEditWorkflow: false, canEditRelatedWorkflows: false });

    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.canEditWorkflow).toBe(false);
    expect(content.props.canEditRelatedWorkflows).toBe(false);
  });

  it('answers only for the canvas that asked, so another mounted panel cannot re-open it editable', async () => {
    // Every listener is on `window` and they all build the same tab id, so an
    // unaddressed request is answered by all of them and the last one wins. That
    // was invisible while every answer was identical; it stopped being identical
    // once the tab carried the host's permission - an ordinary workflow tab
    // mounted elsewhere would re-open this sub-workflow editable, over the locked
    // one the application panel had just opened.
    getPinnedWorkflowRun.mockResolvedValue(null);
    render(<WorkflowBuilderPanelContent workflowId={WF} canEditWorkflow={false} canEditRelatedWorkflows={false} />);
    render(<WorkflowBuilderPanelContent workflowId="other-wf-9d1c" />);

    await dispatchOpen({ sourceWorkflowId: WF });

    expect(openTab, 'the unaddressed panel stayed out of it').toHaveBeenCalledTimes(1);
    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.canEditWorkflow).toBe(false);
  });

  it('still reaches every listener when the request names no source', async () => {
    // Existing callers send none, and they must keep working exactly as before.
    getPinnedWorkflowRun.mockResolvedValue(null);
    render(<WorkflowBuilderPanelContent workflowId={WF} />);
    render(<WorkflowBuilderPanelContent workflowId="other-wf-9d1c" />);

    await dispatchOpen();

    expect(openTab).toHaveBeenCalledTimes(2);
    // And the default is EDITABLE: every ordinary workflow surface opens its
    // sub-workflows without saying anything about permissions, so flipping this
    // default would quietly make all of them read-only.
    const content = openTab.mock.calls[0][0].content as React.ReactElement<Record<string, unknown>>;
    expect(content.props.canEditWorkflow ?? true).not.toBe(false);
  });
});
