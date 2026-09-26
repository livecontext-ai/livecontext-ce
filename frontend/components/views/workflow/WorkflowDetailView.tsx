'use client';

import React, { useEffect, useState, useCallback, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { TriggerDataForPanel } from '@/app/workflows/builder/components/WorkflowBuilder';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import type { AgentSnapshotConfig } from '@/app/workflows/builder/types/agentSnapshot';
import { UnsavedChangesModal } from '@/components/modals/UnsavedChangesModal';
import { WorkflowRunCanvas, type RunInfoChangeData } from '@/components/workflow/WorkflowRunCanvas';
import { useUnsavedChanges } from '@/app/workflows/builder/hooks/state';
import { markRunAsJustExecuted } from '@/app/workflows/builder/hooks/useWorkflowLoader';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  setPendingActivateTab,
  requestPresentApplication,
  APP_TAB_ID,
  INSPECTOR_TAB_ID,
  NODE_CREATOR_TAB_ID,
  RUN_TAB_ID,
} from '@/components/app/WorkflowPanelContent';
import {
  OPEN_INSPECTOR_PANEL_EVENT,
  isInspectorOpenRequestFor,
  type OpenInspectorPanelDetail,
} from '@/lib/workflow/inspectorDockBus';
import { WORKFLOW_PANEL_TAB_ID } from '@/lib/sidePanel/workflowPanelTab';
import {
  OPEN_NODE_CREATOR_EVENT,
  OPEN_RUN_PANEL_EVENT,
  openRunPanel,
  subscribeBindRun,
  type OpenRunPanelDetail,
} from '@/components/workflow/run-panel/runPanelBus';
import { Table, Bot } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

import { WorkflowLoadingState } from './WorkflowLoadingState';
import { WorkflowUnauthorizedState } from './WorkflowUnauthorizedState';
import { runRoutePathFor } from '@/lib/workflow/runRoutePath';
import { useAutoCollapseSidebar } from './hooks';
import { OPEN_TRIGGER_TAB_EVENT, findTriggerTabConfig, type OpenTriggerTabDetail } from '@/lib/workflow/triggerTabEvent';
import { openWorkflowBuilderTab } from '@/lib/sidePanel/openWorkflowBuilderTab';

// ============================================
// Types
// ============================================

interface WorkflowDetailViewProps {
  workflowId: string;
  runId?: string;
  autoOpenApp?: boolean;
}

// ============================================
// Component
// ============================================

/**
 * WorkflowDetailView - Dedicated view for displaying and editing a specific workflow
 * Integrates the workflow builder (WorkflowBuilder) for visual editing.
 *
 * The workflow panel (AI chat/trigger/application) is now rendered inside the
 * unified SidePanel (via WorkflowPanelContent). This component dispatches
 * CustomEvents to pass dynamic data (triggerData, applicationConfigs) to
 * WorkflowPanelContent and listens for execution request events.
 */
export function WorkflowDetailView({ workflowId, runId: runIdProp, autoOpenApp }: WorkflowDetailViewProps) {
  const router = useRouter();
  const { isAuthenticated, isAuthChecking } = useAuthGuard();
  const { isPreviewOnly, runId: contextRunId, setRunId } = useWorkflowMode();
  const effectiveRunId = contextRunId || runIdProp || null;
  const sidePanel = useSidePanelSafe();

  // Custom hooks
  useAutoCollapseSidebar(workflowId);

  // Unsaved changes management (consolidated hook)
  const unsavedChanges = useUnsavedChanges();

  // Workflow info state
  const [workflowName, setWorkflowName] = useState<string | undefined>(undefined);

  // Trigger data from WorkflowBuilder (dispatched to WorkflowPanelContent via events)
  const [triggerData, setTriggerData] = useState<TriggerDataForPanel | null>(null);

  // Nodes ref (needed for agent auto-open - WDV-specific)
  const canvasNodesRef = useRef<Node<BuilderNodeData>[]>([]);

  // Application data from WorkflowBuilder (dispatched to WorkflowPanelContent via events)
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>([]);

  // Agent snapshot data from WorkflowBuilder (dispatched to WorkflowPanelContent via events)
  const [agentConfigs, setAgentConfigs] = useState<AgentSnapshotConfig[]>([]);

  // Static detection: workflow has chat/form trigger nodes (for auto-opening panel)
  const [hasChatFormTrigger, setHasChatFormTrigger] = useState(false);

  // Phase 6c (2026-05-19) - clear workflow-bound config arrays on
  // workspace switch. The view can remain on the same workflow URL
  // while the user switches workspace; without this reset the previous
  // workspace's application/agent snapshots and trigger data leak into
  // the side panel until the WorkflowBuilder re-emits (or fails to
  // load, leaving the stale values in state).
  useOrgScopedReset(() => {
    setApplicationConfigs([]);
    setAgentConfigs([]);
    setTriggerData(null);
    setWorkflowName(undefined);
    setHasChatFormTrigger(false);
  });

  // ── Open the workflow panel on the Run / Add Node tab ──
  // The canvas entry points (version chip, run-panel button, "+") dispatch these.
  // They are handled HERE, not inside WorkflowPanelContent, because the panel
  // body is unmounted while the side panel is closed - the very case these
  // buttons must handle. `setPendingActivateTab` carries the target sub-tab
  // across that mount. The panel keeps whatever width it already had: these tabs
  // never resize it.
  const openWorkflowPanelOnTab = useCallback((subTabId: string) => {
    // The workflow panel tab is NOT keepMounted, so its body only exists while it
    // is the ACTIVE tab. "Panel open" is therefore not enough: open on a
    // sub-workflow / application / files tab means the body is unmounted and no
    // in-panel listener exists yet.
    // isOpen-is-deliberate: the question here is whether that body is MOUNTED and
    // listening, not whether the user can see it. Shading is a render mode over a
    // panel whose active tab keeps its body, so a collapsed window still has a live
    // listener and the event reaches it. Asking `isForward` would send this down the
    // pending-tab branch instead, re-creating a listener that already exists.
    const isWorkflowPanelShowing = !!sidePanel?.isOpen
      && sidePanel.activeTabId === WORKFLOW_PANEL_TAB_ID;

    if (isWorkflowPanelShowing) {
      // Mounted: it switches its own sub-tab from the open event. A pending tab
      // here would sit unconsumed until an unrelated re-render picks it up and
      // yanks the panel long after the click.
      sidePanel!.setActiveTab(WORKFLOW_PANEL_TAB_ID);
      return;
    }
    // Body unmounted (panel closed, or showing another tab): the target sub-tab
    // has to survive the mount. That is what setPendingActivateTab is for.
    setPendingActivateTab(subTabId, workflowId);
    if (sidePanel?.isOpen) {
      sidePanel.setActiveTab(WORKFLOW_PANEL_TAB_ID);
    } else {
      window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
        detail: { isOpen: true },
      }));
    }
  }, [sidePanel, workflowId]);

  useEffect(() => {
    const handleOpenRun = (event: Event) => {
      const detail = (event as CustomEvent<OpenRunPanelDetail>).detail ?? {};
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      openWorkflowPanelOnTab(RUN_TAB_ID);
    };
    const handleOpenNodeCreator = (event: Event) => {
      const detail = (event as CustomEvent<{ workflowId?: string }>).detail ?? {};
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      openWorkflowPanelOnTab(NODE_CREATOR_TAB_ID);
    };
    // Selecting a node while the inspector is docked to the panel. Same page-level
    // handling as the two above, for the same reason: the panel body is unmounted
    // while the panel is closed, so the FIRST selection has nothing in-panel to
    // listen for it - and that is precisely the click that must open the panel.
    const handleOpenInspector = (event: Event) => {
      const detail = (event as CustomEvent<OpenInspectorPanelDetail>).detail;
      // 'page' only: an embedded canvas (the Application panel, a sub-workflow
      // tab) docks inside the panel that already shows it, and its own in-panel
      // listener handles the focus. Reacting here would drag the side panel back
      // to the workflow tab of the page behind it.
      if (!isInspectorOpenRequestFor(detail, workflowId, 'page')) return;
      openWorkflowPanelOnTab(INSPECTOR_TAB_ID);
    };
    window.addEventListener(OPEN_RUN_PANEL_EVENT, handleOpenRun);
    window.addEventListener(OPEN_NODE_CREATOR_EVENT, handleOpenNodeCreator);
    window.addEventListener(OPEN_INSPECTOR_PANEL_EVENT, handleOpenInspector);
    return () => {
      window.removeEventListener(OPEN_RUN_PANEL_EVENT, handleOpenRun);
      window.removeEventListener(OPEN_NODE_CREATOR_EVENT, handleOpenNodeCreator);
      window.removeEventListener(OPEN_INSPECTOR_PANEL_EVENT, handleOpenInspector);
    };
  }, [workflowId, openWorkflowPanelOnTab]);

  // ── Switch the canvas to another run of this workflow, IN PLACE ──
  // Picking a run in the panel's history used to `router.push` the run route,
  // which remounts everything and reads as a full-page refresh. Binding the run
  // through the context is the same mechanism an agent-launched run already uses
  // (see the sidePanelAutoOpen handler below), so the canvas swaps its run
  // without tearing the page down.
  //
  // The address bar is then brought along with the NATIVE History API, which
  // Next supports precisely for this (it updates `usePathname` without fetching
  // the route again, so nothing remounts). Leaving the URL on the previous run
  // was silent until the user pressed F5: the reload restored the run named by
  // the URL, i.e. the one they had just navigated away from. It also made the
  // run they were looking at impossible to share or bookmark.
  //
  // Swapping one run for another REPLACES the entry: it is not a navigation,
  // and one entry per pick would make Back walk through every run you glanced
  // at. Coming from the edit page PUSHES instead, because that page is
  // somewhere the user was and must be able to go back to.
  //
  // `urlSynced` tells the provider this binding IS the URL, so its URL-driven
  // effect stays in charge. A latched "programmatic" flag would make the
  // provider ignore the pathname for the rest of the session, so a later real
  // navigation (the Edit toggle) would move the URL with nothing following it.
  useEffect(() => {
    // "Is this request for me?" is the shared rule (see subscribeBindRun) - every
    // surface that can show this workflow hears the same event.
    return subscribeBindRun(workflowId, (boundRunId) => {
      const nextPath = runRoutePathFor(window.location.pathname, workflowId, boundRunId);
      const urlAlreadyNamesIt = nextPath === window.location.pathname;
      // Picking the run already on screen is not always a no-op: an
      // agent-launched run is bound in place on the EDIT url, and picking it in
      // the history is how a user says "keep this one" - so the address bar is
      // still written, even though the binding does not change.
      const wroteUrl = !!nextPath && !urlAlreadyNamesIt;
      // Nothing to change: same run, and no address bar of ours to align.
      if (boundRunId === effectiveRunId && !wroteUrl) return;
      if (wroteUrl) {
        const url = `${nextPath}${window.location.search}${window.location.hash}`;
        const leavingEditPage = !window.location.pathname.includes('/run/');
        if (leavingEditPage) window.history.pushState(null, '', url);
        else window.history.replaceState(null, '', url);
      }
      // Re-bound even when the run does not change: writing the URL is what
      // hands the binding back to the pathname, and the provider only learns
      // that through `urlSynced`. Skipping it here left an agent-launched run
      // latched, so the Back this push exists for changed the URL with nothing
      // following it.
      setRunId(boundRunId, { urlSynced: !!nextPath });
    });
  }, [workflowId, effectiveRunId, setRunId]);

  // Handle workflow loaded - store name for chat panel
  const handleWorkflowLoaded = useCallback((info: { name?: string; id?: string }) => {
    setWorkflowName(info.name);
  }, []);

  // ── Overlay the live run on the main canvas when the agent (chatting in the
  // workflow panel) launches THIS workflow. Every agent-run emits a global
  // `sidePanelAutoOpen` marker (`type:'workflow_run'`, `id`=workflowId, `runId`).
  // On chat pages AppHeader reacts to it, but that handler is gated to chat pages,
  // so on the workflow page nothing reacted and the left canvas stayed in edit
  // mode while the run executed.
  //
  // We flip IN PLACE, not by navigating: `setRunId` binds the run without a URL
  // change, and `markRunAsJustExecuted` tells the loader to KEEP the current
  // canvas plan (the one the agent just saved) instead of reloading the run's
  // plan. So the run statuses overlay on the existing nodes with zero refresh -
  // the user's canvas view and context are preserved. (An earlier version did a
  // `router.push` to the run URL, which reloaded the plan and wiped the context.)
  // Only a run of THIS workflow flips the canvas - a different workflow's run is
  // not this canvas's concern. ──
  useEffect(() => {
    if (isPreviewOnly) return;
    const handleWorkflowRunAutoOpen = (event: CustomEvent<{ type: string; id: string; runId?: string }>) => {
      const { type, id, runId: eventRunId } = event.detail;
      // Other present_* views (table, file, agent...) open from AppHeader, on every page.
      if ((type !== 'workflow_run' && type !== 'present_run' && type !== 'present_application') || !eventRunId) return;
      if (id !== workflowId) return;
      if (effectiveRunId !== eventRunId) {
        markRunAsJustExecuted(eventRunId); // keep the current plan - overlay, don't reload
        setRunId(eventRunId);              // bind run in place (no navigation)
      }
      // workflow(action='present', view='application'): the agent shows the run's
      // interfaces. A request, not a switch: the panel takes it once it shows that
      // run with its interfaces (see requestPresentApplication).
      if (type === 'present_application') {
        requestPresentApplication(workflowId, eventRunId);
        openWorkflowPanelOnTab(APP_TAB_ID);
      }
    };
    window.addEventListener('sidePanelAutoOpen', handleWorkflowRunAutoOpen as EventListener);
    return () => window.removeEventListener('sidePanelAutoOpen', handleWorkflowRunAutoOpen as EventListener);
  }, [workflowId, effectiveRunId, isPreviewOnly, setRunId, openWorkflowPanelOnTab]);

  // ── Dispatch triggerData changes to WorkflowPanelContent ──
  // Gate on `effectiveRunId`, NOT `runIdProp`: an agent-launched run is now bound
  // IN PLACE (setRunId, no /run/ URL), so `runIdProp` is undefined during that
  // run and gating on it dropped the trigger/application/agent sub-tabs to []
  // (they never appeared in the workflow panel). `effectiveRunId` is set for both
  // a URL run and an in-place run, and null in edit mode - so configs flow in
  // either run mode and stay empty while editing. (Same reason
  // WorkflowBuilderPanelContent forwards its canvas configs un-gated.)
  useEffect(() => {
    const dataToDispatch = effectiveRunId ? triggerData : null;
    window.dispatchEvent(new CustomEvent('workflowPanelTriggerDataChange', {
      detail: {
        workflowId,
        configs: dataToDispatch?.configs ?? [],
        activeTriggerId: dataToDispatch?.activeTriggerId,
        readySteps: dataToDispatch?.readySteps ?? new Set(),
        runStatus: dataToDispatch?.runStatus,
        // Forward the canvas's bound run id so the panel's Application/interface can
        // resolve its run when it is bound IN PLACE (no /run/ URL to read it from).
        runId: dataToDispatch?.runId,
        isStepByStepMode: dataToDispatch?.isStepByStepMode,
      },
    }));
  }, [triggerData, effectiveRunId, workflowId]);

  // ── Dispatch applicationConfigs changes to WorkflowPanelContent ──
  useEffect(() => {
    const configs = effectiveRunId ? applicationConfigs : [];
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId, configs },
    }));
  }, [applicationConfigs, effectiveRunId, workflowId]);

  // ── Dispatch agentConfigs changes to WorkflowPanelContent ──
  useEffect(() => {
    const configs = effectiveRunId ? agentConfigs : [];
    window.dispatchEvent(new CustomEvent('workflowPanelAgentConfigsChange', {
      detail: { workflowId, configs },
    }));
  }, [agentConfigs, effectiveRunId, workflowId]);

  // ── Intercept trigger/application tab open events to ensure SidePanel is open ──
  useEffect(() => {
    const handleOpenTriggerTab = (event: CustomEvent<OpenTriggerTabDetail>) => {
      if (sidePanel?.isOpen) {
        // Panel already open (e.g. showing agent tab) - switch to workflow-panel,
        // then let WorkflowPanelContent's own listener handle the internal trigger tab switch
        sidePanel.setActiveTab('workflow-panel');
      } else {
        // Set pending tab and open via the AppHeader's Sparkles handler
        const match = findTriggerTabConfig(
          effectiveRunId ? triggerData?.configs : [],
          event.detail,
        );
        if (match) {
          setPendingActivateTab(match.triggerId, workflowId);
        }
        // Dispatch event for AppHeader to open the workflow panel
        window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
          detail: { isOpen: true },
        }));
      }
    };

    const handleOpenApplicationTab = (event: CustomEvent<{ interfaceId: string }>) => {
      const match = (effectiveRunId ? applicationConfigs : []).find(
        c => c.interfaceId === event.detail.interfaceId
      );
      if (!match) return;

      const tabId = `app-${match.interfaceId}`;
      if (sidePanel?.isOpen) {
        sidePanel.setActiveTab('workflow-panel');
      } else {
        setPendingActivateTab(tabId, workflowId);
        window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
          detail: { isOpen: true },
        }));
      }
    };

    window.addEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => {
      window.removeEventListener(OPEN_TRIGGER_TAB_EVENT, handleOpenTriggerTab as EventListener);
      window.removeEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    };
  }, [sidePanel?.isOpen, triggerData, applicationConfigs, effectiveRunId]);

  // ── Listen for datasource tab open requests (from step focus on table nodes) ──
  useEffect(() => {
    const handleOpenDatasourceTab = (event: CustomEvent<{ dataSourceId: string; label: string }>) => {
      const { dataSourceId, label } = event.detail;
      if (!sidePanel || !dataSourceId) return;
      // Lazy import to avoid circular dependencies
      import('@/components/app/DataSourcePanelContent').then(({ DataSourcePanelContent }) => {
        sidePanel.openTab({
          id: `datasource-${dataSourceId}`,
          label,
          icon: React.createElement(Table, { className: 'w-4 h-4' }),
          content: React.createElement(DataSourcePanelContent, { dataSourceId, readOnly: isPreviewOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenDatasourceTab', handleOpenDatasourceTab as EventListener);
    return () => window.removeEventListener('workflowOpenDatasourceTab', handleOpenDatasourceTab as EventListener);
  }, [sidePanel, isPreviewOnly]);

  // ── Listen for agent tab open requests (from step focus on agent nodes) ──
  useEffect(() => {
    const handleOpenAgentTab = (event: CustomEvent<{ agentId: string; label: string; conversationId?: string }>) => {
      const { agentId, label, conversationId: triggerConvId } = event.detail;
      if (!sidePanel || !agentId) return;
      import('@/components/app/AgentPanelContent').then(({ AgentPanelContent }) => {
        sidePanel.openTab({
          id: `agent-${agentId}`,
          label,
          icon: React.createElement(Bot, { className: 'w-4 h-4' }),
          content: React.createElement(AgentPanelContent, { agentId, conversationId: triggerConvId, readOnly: isPreviewOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenAgentTab', handleOpenAgentTab as EventListener);
    return () => window.removeEventListener('workflowOpenAgentTab', handleOpenAgentTab as EventListener);
  }, [sidePanel, isPreviewOnly]);

  // Agent running panel auto-open removed - agent buttons with shimmer on the node
  // now indicate that the agent panel is available (conversation + configuration).

  // ── Manual open sub-workflow in side panel (run mode button click) ──
  // In run mode: show the pinned run if one exists, otherwise fall back to builder.
  useEffect(() => {
    const handleOpenSubWorkflow = async (event: CustomEvent<{ workflowId: string; workflowName: string; nodeId: string; sourceWorkflowId?: string }>) => {
      const { workflowId: subWfId, workflowName: wfName, sourceWorkflowId } = event.detail;
      if (!sidePanel || !subWfId) return;
      // Twin of the panel's listener: both sit on `window` and build the same tab
      // id, so an addressed request must be answered only by the view hosting the
      // canvas that sent it. Refuses only a DIFFERENT source, so an unaddressed
      // request still reaches every listener.
      if (sourceWorkflowId && sourceWorkflowId !== workflowId) return;

      // Resolve pinned run via dedicated endpoint (same logic as ProductionRunResolver)
      let pinnedRunId: string | undefined;
      try {
        const pinnedRun = await orchestratorApi.getPinnedWorkflowRun(subWfId);
        if (pinnedRun?.runId) pinnedRunId = pinnedRun.runId;
      } catch { /* ignore - will fall back to builder panel */ }

      openWorkflowBuilderTab(sidePanel, {
        workflowId: subWfId,
        runId: pinnedRunId,
        workflowName: wfName,
        readOnly: isPreviewOnly,
      });
    };
    window.addEventListener('workflowOpenSubWorkflow', handleOpenSubWorkflow as EventListener);
    return () => window.removeEventListener('workflowOpenSubWorkflow', handleOpenSubWorkflow as EventListener);
  }, [sidePanel, isPreviewOnly, workflowId]);

  // Sub-workflow auto-open removed - persistent button on the node handles this.

  // ── Auto-open application panel when interface node reaches awaiting_signal ──
  // Buffer: the event may arrive before applicationConfigs is populated.
  const pendingInterfaceStepIdRef = useRef<string | null>(null);

  useEffect(() => {
    const handleInterfaceAwaiting = (event: CustomEvent<{ stepId: string; interfaceId: string; label: string }>) => {
      const { stepId, interfaceId } = event.detail;
      if (!sidePanel || !stepId) return;

      // Match by nodeId (stepId from WS = "interface:normalized_label") or interfaceId
      const match = applicationConfigs.find(c =>
        c.nodeId === stepId || (interfaceId && c.interfaceId === interfaceId)
      );
      if (match) {
        const tabId = `app-${match.interfaceId}`;
        if (sidePanel.isOpen) {
          sidePanel.setActiveTab('workflow-panel');
          setPendingActivateTab(tabId, workflowId);
        } else {
          setPendingActivateTab(tabId, workflowId);
          window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
            detail: { isOpen: true },
          }));
        }
      } else {
        // applicationConfigs not yet populated - buffer and open panel anyway
        pendingInterfaceStepIdRef.current = stepId;
        if (!sidePanel.isOpen) {
          window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
            detail: { isOpen: true },
          }));
        }
      }
    };
    window.addEventListener('workflowInterfaceAwaiting', handleInterfaceAwaiting as EventListener);
    return () => window.removeEventListener('workflowInterfaceAwaiting', handleInterfaceAwaiting as EventListener);
  }, [sidePanel, applicationConfigs]);

  // Replay buffered interface awaiting event once applicationConfigs is populated
  useEffect(() => {
    const pendingStepId = pendingInterfaceStepIdRef.current;
    if (!pendingStepId || applicationConfigs.length === 0 || !sidePanel) return;
    const match = applicationConfigs.find(c => c.nodeId === pendingStepId);
    if (match) {
      pendingInterfaceStepIdRef.current = null;
      const tabId = `app-${match.interfaceId}`;
      sidePanel.setActiveTab('workflow-panel');
      setPendingActivateTab(tabId, workflowId);
    }
  }, [applicationConfigs, sidePanel]);

  // ── Auto-open SidePanel when entering run mode ──
  // Priority: 0) Run tab (the run that was just launched)  1) Application
  // (interface) tab  2) Trigger tab (chat/form). Effect 0 claims the run id, so
  // for a given run only ONE of the three fires: pressing Run shows the run, and
  // the Application / Trigger tabs stay one click away in the tab bar.
  // Only auto-opens once per runId to avoid re-opening after user closes the panel.
  // Only opens when run is active (RUNNING, WAITING_TRIGGER, PAUSED) - not for terminal runs.
  const hasAutoOpenedForRunRef = useRef<string | null>(null);
  const ACTIVE_RUN_STATUSES = ['RUNNING', 'WAITING_TRIGGER', 'PAUSED'];
  const currentRunStatus = triggerData?.runStatus;
  const isActiveRun = !!currentRunStatus && ACTIVE_RUN_STATUSES.includes(currentRunStatus);

  // 0) A run just started (or was bound in place): show it. The Run tab comes
  //    FIRST - the user pressed Run, so what they want to see is the run itself
  //    (status, epochs, steps). Claiming `hasAutoOpenedForRunRef` here also stops
  //    the application/trigger auto-opens below from stealing the focus for this
  //    run; both remain one click away in the tab bar.
  //
  //    Unlike those two, this one does NOT skip when the panel is already open:
  //    launching a run is an explicit action whose result belongs on screen, so it
  //    focuses the Run tab even if another panel tab was showing. That is
  //    deliberate, not an oversight.
  useEffect(() => {
    if (!effectiveRunId || isPreviewOnly) return;
    // Same gate as the two auto-opens below: only a LIVE run pops the panel.
    // Opening a terminal run's URL must not force the panel open - the user is
    // reading history, not launching anything.
    if (!isActiveRun) return;
    if (hasAutoOpenedForRunRef.current === effectiveRunId) return;
    hasAutoOpenedForRunRef.current = effectiveRunId;
    // Explicitly ask for the RUN level: the tab would otherwise reuse whatever
    // level was last requested (a stale "history" from an earlier click), and
    // what the user wants right after pressing Run is the run itself.
    openRunPanel({ workflowId, view: 'run' });
  }, [effectiveRunId, isPreviewOnly, isActiveRun, workflowId]);

  // 1) Auto-open for applications (interfaces)
  useEffect(() => {
    if (!runIdProp || applicationConfigs.length === 0 || sidePanel?.isOpen) return;
    if (!isActiveRun) return;
    if (hasAutoOpenedForRunRef.current === runIdProp) return;
    hasAutoOpenedForRunRef.current = runIdProp;
    setPendingActivateTab(`app-${applicationConfigs[0].interfaceId}`, workflowId);
    window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
      detail: { isOpen: true },
    }));
  }, [runIdProp, applicationConfigs, sidePanel?.isOpen, isActiveRun]);

  // 2) Fallback: auto-open for trigger tabs (chat/form) when no application exists
  //    Uses static node detection (hasChatFormTrigger) so the panel opens immediately
  //    without waiting for the workflow runtime to pause on a trigger.
  useEffect(() => {
    if (!runIdProp || applicationConfigs.length > 0 || sidePanel?.isOpen) return;
    if (!isActiveRun) return;
    if (hasAutoOpenedForRunRef.current === runIdProp) return;
    if (!hasChatFormTrigger) return;
    hasAutoOpenedForRunRef.current = runIdProp;
    // Open on the chat tab - WorkflowPanelContent will auto-switch to
    // the specific trigger tab once triggerData arrives from the runtime.
    window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', {
      detail: { isOpen: true },
    }));
  }, [runIdProp, applicationConfigs, hasChatFormTrigger, sidePanel?.isOpen, isActiveRun]);

  // Redirect if workflowId is 'new' - this route should not exist
  useEffect(() => {
    if (workflowId === 'new') {
      router.push('/app/workflow');
    }
  }, [workflowId, router]);

  // Early returns for special states
  if (workflowId === 'new') {
    return <WorkflowLoadingState />;
  }

  if (isAuthChecking) {
    return <WorkflowLoadingState />;
  }

  if (!isAuthenticated) {
    return <WorkflowUnauthorizedState />;
  }

  return (
    <div className="absolute inset-0 overflow-hidden">
      {/* Workflow Builder - canvas fills the available space (SidePanel is flex sibling) */}
      <div className="absolute inset-0 z-10">
        <WorkflowRunCanvas
          workflowId={workflowId}
          runId={runIdProp}
          onDirtyChange={unsavedChanges.handleDirtyChange}
          onRefreshBlocked={unsavedChanges.handleRefreshBlocked}
          saveRef={unsavedChanges.saveRef}
          onWorkflowLoaded={handleWorkflowLoaded}
          onTriggerConfigsChange={setTriggerData}
          onApplicationConfigsChange={setApplicationConfigs}
          onAgentConfigsChange={setAgentConfigs}
          onHasChatFormTrigger={setHasChatFormTrigger}
          nodesRef={canvasNodesRef}
        />
      </div>

      {/* Unsaved Changes Modal */}
      <UnsavedChangesModal
        isOpen={unsavedChanges.showModal}
        onSave={unsavedChanges.handleSave}
        onDiscard={unsavedChanges.handleDiscard}
        onCancel={unsavedChanges.handleCancel}
        isSaving={unsavedChanges.isSaving}
      />
    </div>
  );
}
