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
import { setPendingActivateTab } from '@/components/app/WorkflowPanelContent';
import { Table, Bot, Workflow } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

import { WorkflowLoadingState } from './WorkflowLoadingState';
import { WorkflowUnauthorizedState } from './WorkflowUnauthorizedState';
import { useAutoCollapseSidebar } from './hooks';

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

  // Runs history panel state
  const [isRunsHistoryOpen, setIsRunsHistoryOpen] = useState(false);

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
    setIsRunsHistoryOpen(false);
  });

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
      if (type !== 'workflow_run' || !eventRunId) return;
      if (id !== workflowId) return;
      if (effectiveRunId === eventRunId) return; // already bound to this run
      markRunAsJustExecuted(eventRunId); // keep the current plan - overlay, don't reload
      setRunId(eventRunId);              // bind run in place (no navigation)
    };
    window.addEventListener('sidePanelAutoOpen', handleWorkflowRunAutoOpen as EventListener);
    return () => window.removeEventListener('sidePanelAutoOpen', handleWorkflowRunAutoOpen as EventListener);
  }, [workflowId, effectiveRunId, isPreviewOnly, setRunId]);

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
    const handleOpenTriggerTab = (event: CustomEvent<{ nodeId: string; triggerType: 'chat' | 'form' | 'webhook' }>) => {
      if (sidePanel?.isOpen) {
        // Panel already open (e.g. showing agent tab) - switch to workflow-panel,
        // then let WorkflowPanelContent's own listener handle the internal trigger tab switch
        sidePanel.setActiveTab('workflow-panel');
      } else {
        // Set pending tab and open via the AppHeader's Sparkles handler
        const match = (effectiveRunId ? triggerData?.configs : [])?.find(
          c => c.type === event.detail.triggerType
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

    window.addEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
    window.addEventListener('workflowOpenApplicationTab', handleOpenApplicationTab as EventListener);
    return () => {
      window.removeEventListener('workflowOpenTriggerTab', handleOpenTriggerTab as EventListener);
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
    const handleOpenSubWorkflow = async (event: CustomEvent<{ workflowId: string; workflowName: string; nodeId: string }>) => {
      const { workflowId: subWfId, workflowName: wfName } = event.detail;
      if (!sidePanel || !subWfId) return;

      // Resolve pinned run via dedicated endpoint (same logic as ProductionRunResolver)
      let pinnedRunId: string | undefined;
      try {
        const pinnedRun = await orchestratorApi.getPinnedWorkflowRun(subWfId);
        if (pinnedRun?.runId) pinnedRunId = pinnedRun.runId;
      } catch { /* ignore - will fall back to builder panel */ }

      if (pinnedRunId) {
        import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
          sidePanel.openTab({
            id: `workflow-run-${subWfId}-${pinnedRunId}`,
            label: wfName,
            icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
            content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, runId: pinnedRunId!, readOnly: isPreviewOnly }),
            preferredWidth: 0.5,
            keepMounted: true,
          });
        });
      } else {
        import('@/components/app/WorkflowBuilderPanelContent').then(({ WorkflowBuilderPanelContent }) => {
          sidePanel.openTab({
            id: `workflow-builder-${subWfId}`,
            label: wfName,
            icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
            content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, readOnly: isPreviewOnly }),
            preferredWidth: 0.5,
            keepMounted: true,
          });
        });
      }
    };
    window.addEventListener('workflowOpenSubWorkflow', handleOpenSubWorkflow as EventListener);
    return () => window.removeEventListener('workflowOpenSubWorkflow', handleOpenSubWorkflow as EventListener);
  }, [sidePanel, isPreviewOnly]);

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
  // Priority: 1) Application (interface) tab  2) Trigger tab (chat/form)
  // Only auto-opens once per runId to avoid re-opening after user closes the panel.
  // Only opens when run is active (RUNNING, WAITING_TRIGGER, PAUSED) - not for terminal runs.
  const hasAutoOpenedForRunRef = useRef<string | null>(null);
  const ACTIVE_RUN_STATUSES = ['RUNNING', 'WAITING_TRIGGER', 'PAUSED'];
  const currentRunStatus = triggerData?.runStatus;
  const isActiveRun = !!currentRunStatus && ACTIVE_RUN_STATUSES.includes(currentRunStatus);

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
          isRunsHistoryOpen={isRunsHistoryOpen}
          onOpenRunsHistory={() => setIsRunsHistoryOpen(true)}
          onCloseRunsHistory={() => setIsRunsHistoryOpen(false)}
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
