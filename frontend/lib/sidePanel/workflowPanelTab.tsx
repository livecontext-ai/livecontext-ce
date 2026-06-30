'use client';

import * as React from 'react';
import { useEffect, useRef } from 'react';
import { LayoutDashboard } from 'lucide-react';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { useSidePanelSafe, type SidePanelTab } from '@/contexts/SidePanelContext';

export const WORKFLOW_PANEL_TAB_ID = 'workflow-panel';

export function buildWorkflowPanelTab(workflowId: string): SidePanelTab {
  return {
    id: WORKFLOW_PANEL_TAB_ID,
    label: 'Workflow Panel',
    icon: <LayoutDashboard className="w-4 h-4" />,
    pinned: true,
    scope: ['/app/workflow/*'],
    content: <WorkflowPanelContent workflowId={workflowId} />,
  };
}

/**
 * Auto-register the Workflow Panel pinned tab on `/app/workflow/:id` pages.
 *
 * Without this, the tab was only added on user toggle when the panel was closed,
 * so reaching the workflow page with the panel already open (carried-over tab
 * from a same-group navigation, or a programmatic auto-open elsewhere) left the
 * Workflow Panel missing - clicking the toggle just closed the panel.
 *
 * Re-registering when `workflowId` changes also refreshes the tab content after
 * cross-workflow navigation: the SidePanel scope filter keeps the tab across
 * `/app/workflow/A` → `/app/workflow/B` (both match `/app/workflow/*`), but its
 * `content` was bound to the OLD workflowId - leaving stale content.
 *
 * Gate on `shouldRegister` (e.g. `isWorkflowViewWithWorkflow`) so the hook is a
 * no-op outside workflow pages - that gate already implies the URL hydrated to
 * a real workflowId, so no extra `authLoading` / `pathname` guards are needed.
 */
export function useAutoRegisterWorkflowPanelTab(
  shouldRegister: boolean,
  workflowId: string | null | undefined,
): void {
  const sidePanel = useSidePanelSafe();
  const hasTab = !!sidePanel?.tabs.some(t => t.id === WORKFLOW_PANEL_TAB_ID);
  const lastRegisteredWorkflowIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!sidePanel?.addTab) return;
    if (!shouldRegister || !workflowId) return;
    if (hasTab && lastRegisteredWorkflowIdRef.current === workflowId) return;
    lastRegisteredWorkflowIdRef.current = workflowId;
    sidePanel.addTab(buildWorkflowPanelTab(workflowId));
  }, [sidePanel, shouldRegister, workflowId, hasTab]);
}
