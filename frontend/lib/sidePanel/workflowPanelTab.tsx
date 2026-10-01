'use client';

import * as React from 'react';
import { useEffect, useRef } from 'react';
import { LayoutDashboard } from 'lucide-react';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { useSidePanelSafe, type SidePanelTab } from '@/contexts/SidePanelContext';
import { WORKFLOW_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';
import { WORKFLOW_PANEL_CHAT_TAB_ID } from '@/lib/workflow/workflowPanelChat';
import { wasOnScreenBeforeReload } from '@/lib/sidePanel/onScreenAcrossReload';
import { usePathname } from 'next/navigation';

// Re-exported from its owner so the existing import path keeps working.
export { WORKFLOW_PANEL_TAB_ID };

export function buildWorkflowPanelTab(workflowId: string): SidePanelTab {
  return {
    id: WORKFLOW_PANEL_TAB_ID,
    label: 'Workflow Panel',
    icon: <LayoutDashboard className="w-4 h-4" />,
    pinned: true,
    scope: ['/app/workflow/*'],
    content: <WorkflowPanelContent workflowId={workflowId} hostTabId={WORKFLOW_PANEL_TAB_ID} />,
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
 *
 * A page left by a reload while the panel's AI chat was on screen (an OAuth connect started from
 * that chat, an F5) registers the tab OPEN, and WorkflowPanelContent lands on the chat: the
 * conversation that was waiting can then carry on. See onScreenAcrossReload.
 */
export function useAutoRegisterWorkflowPanelTab(
  shouldRegister: boolean,
  workflowId: string | null | undefined,
): void {
  const sidePanel = useSidePanelSafe();
  const pathname = usePathname();
  const hasTab = !!sidePanel?.tabs.some(t => t.id === WORKFLOW_PANEL_TAB_ID);
  const lastRegisteredWorkflowIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!sidePanel?.addTab) return;
    if (!shouldRegister || !workflowId) return;
    if (hasTab && lastRegisteredWorkflowIdRef.current === workflowId) return;
    const firstRegistration = lastRegisteredWorkflowIdRef.current === null;
    lastRegisteredWorkflowIdRef.current = workflowId;
    if (firstRegistration && wasOnScreenBeforeReload(WORKFLOW_PANEL_CHAT_TAB_ID, pathname)) {
      sidePanel.openTab(buildWorkflowPanelTab(workflowId));
      return;
    }
    sidePanel.addTab(buildWorkflowPanelTab(workflowId));
  }, [sidePanel, shouldRegister, workflowId, hasTab, pathname]);
}
