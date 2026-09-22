import React from 'react';
import { Workflow } from 'lucide-react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import { workflowPanelTabId } from '@/lib/sidePanel/tabResource';

/** Just the slice of the side panel this needs - keeps callers testable without the provider. */
export interface WorkflowTabOpener {
  openTab: (tab: SidePanelTab) => void;
}

export interface OpenWorkflowBuilderTabOptions {
  workflowId: string;
  /** Open the workflow on a specific run and give that run its own tab identity. */
  runId?: string;
  /** Tab label. Falls back to a generic one so a relation with no resolvable name still reads. */
  workflowName?: string | null;
  /** Mount the canvas read-only (marketplace/publisher views). Omitted = editable, as before. */
  readOnly?: boolean;
  /**
   * The opened workflow is the caller's to change. Passed false by the sub-workflow
   * handoff of a panel showing SOMEONE ELSE's application, so the workflows reachable
   * from it do not open with a Save that would be refused.
   *
   * Omitted = true. That is right for a workflow reached AS a workflow, and it is what
   * the canvas-level openers (a sub-workflow node, its hover buttons, the relations
   * menu) still pass: they sit inside the canvas and cannot read the host panel's
   * permission. The reachable gap is one hop further down than it looks: a locked child
   * tab opens with no run, so its own canvas is in EDIT mode, and those openers take
   * the direct branch there - so a GRANDCHILD of a foreign publication opens editable.
   * The backend refuses the write either way; closing it means carrying the permission
   * in context rather than in props, which is its own change.
   */
  canEditWorkflow?: boolean;
}

/**
 * Open a workflow's builder in the right side panel.
 *
 * <p>This is the one place that decides what such a tab looks like: its id (so re-opening the same
 * workflow re-activates the existing tab rather than stacking duplicates), its half-width default,
 * and `keepMounted` - a workflow canvas is expensive to rebuild and holds a live run subscription,
 * so it must survive the panel being closed or another tab taking over.
 *
 * <p>The panel content is imported lazily inside the call: it pulls in the whole builder, and the
 * surfaces that offer this (a card footer, a canvas toolbar, a node button) must not pay for it
 * until someone actually clicks.
 */
export function openWorkflowBuilderTab(
  sidePanel: WorkflowTabOpener | null | undefined,
  { workflowId, runId, workflowName, readOnly, canEditWorkflow }: OpenWorkflowBuilderTabOptions,
): void {
  if (!sidePanel || !workflowId) return;
  import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
    const tabId = workflowPanelTabId(workflowId, runId);
    sidePanel.openTab({
      id: tabId,
      label: workflowName || 'Workflow',
      icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
      content: React.createElement(WorkflowBuilderPanelContent, {
        workflowId,
        runId,
        hostTabId: tabId,
        readOnly,
        canEditWorkflow,
        canEditRelatedWorkflows: canEditWorkflow,
      }),
      preferredWidth: runId ? 0.55 : 0.5,
      keepMounted: true,
    });
  });
}

/**
 * Ask the surrounding workflow view to open a related workflow, letting it resolve the PINNED RUN
 * first and show that instead of the editable builder.
 *
 * <p>Only meaningful inside a workflow view: `WorkflowDetailView` and `WorkflowBuilderPanelContent`
 * are what listen for this. From a card grid, where no such view is mounted, call
 * {@link openWorkflowBuilderTab} directly - the event would be dropped on the floor.
 */
export function requestOpenRelatedWorkflow(
  workflowId: string,
  workflowName?: string | null,
  /**
   * The node that asked, when one did. No listener reads it today, but it is what identifies the
   * call SITE inside the plan, so it is carried rather than dropped: an opener that is not a node
   * (the toolbar's relations menu) simply sends none.
   */
  nodeId = '',
  /**
   * The workflow the request comes FROM, so the view hosting that workflow is the one that
   * answers it.
   *
   * Every listener sits on `window` and they all build the same tab id, so before this they all
   * answered every request and the last one to finish its pinned-run lookup won. That was
   * invisible while every answer was identical - it stopped being identical once the tab content
   * started carrying the host's permission to edit what it opens: a workflow tab mounted
   * elsewhere would re-open the same sub-workflow editable, over the locked one an application
   * panel had just opened.
   *
   * A listener refuses only a request that names a DIFFERENT source, so a caller that sends none
   * still reaches every listener exactly as before.
   */
  sourceWorkflowId?: string,
): void {
  if (typeof window === 'undefined' || !workflowId) return;
  window.dispatchEvent(new CustomEvent('workflowOpenSubWorkflow', {
    detail: { workflowId, workflowName: workflowName || 'Workflow', nodeId, sourceWorkflowId },
  }));
}
