'use client';

/**
 * WorkflowBuilderPanelContent - Mounts a workflow inside the SidePanel with
 * proper sub-tabs (AI Chat, Workflow canvas, Triggers, Application).
 *
 * Follows the same composition pattern as ApplicationDetailView:
 * WorkflowPanelContent receives the canvas as a `workflowCanvasSlot`,
 * so the user gets full sub-tab navigation instead of the old overlay.
 */

import React, { useState, useEffect, useRef } from 'react';
import { Table, Bot, Workflow } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { TriggerDataForPanel } from '@/app/workflows/builder/components/WorkflowBuilder';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import type { AgentSnapshotConfig } from '@/app/workflows/builder/types/agentSnapshot';
import { WorkflowRunCanvas } from '@/components/workflow/WorkflowRunCanvas';
import { WorkflowPanelContent } from '@/components/app/WorkflowPanelContent';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { WorkflowRunProvider } from '@/contexts/WorkflowRunContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { orchestratorApi } from '@/lib/api';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

interface WorkflowBuilderPanelContentProps {
  workflowId: string;
  runId?: string;
  readOnly?: boolean;
}

export function WorkflowBuilderPanelContent({ workflowId, runId, readOnly = false }: WorkflowBuilderPanelContentProps) {
  const sidePanel = useSidePanelSafe();
  const canvasNodesRef = useRef<Node<BuilderNodeData>[]>([]);

  const [triggerData, setTriggerData] = useState<TriggerDataForPanel | null>(null);
  const [applicationConfigs, setApplicationConfigs] = useState<ApplicationConfig[]>([]);
  const [agentConfigs, setAgentConfigs] = useState<AgentSnapshotConfig[]>([]);

  useOrgScopedReset(() => {
    setTriggerData(null);
    setApplicationConfigs([]);
    setAgentConfigs([]);
  });

  // ── Dispatch trigger data / applicationConfigs / agentConfigs to WorkflowPanelContent ──
  //
  // We forward the canvas's emitted configs AS-IS, NOT gated on the `runId` prop.
  // WorkflowBuilder already gates these on its own run mode (`if (!isRunMode)
  // return []` for applicationConfigs / waiting-trigger configs), so a non-empty
  // emission means the canvas IS in run mode. Gating again on the `runId` prop
  // suppressed the trigger + application sub-tabs whenever the panel was opened
  // WITHOUT a runId (the `+`-menu and chat workflow events both open in edit
  // mode) and the canvas later entered run mode IN PLACE - the run showed on the
  // canvas but the Triggers/Application tabs never appeared. Driving the dispatch
  // off the live canvas state fixes that; edit mode still emits empty → no tabs.
  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelTriggerDataChange', {
      detail: {
        workflowId,
        configs: triggerData?.configs ?? [],
        activeTriggerId: triggerData?.activeTriggerId,
        readySteps: triggerData?.readySteps ?? new Set(),
        runStatus: triggerData?.runStatus,
        isStepByStepMode: triggerData?.isStepByStepMode,
      },
    }));
  }, [triggerData, workflowId]);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelApplicationConfigsChange', {
      detail: { workflowId, configs: applicationConfigs },
    }));
  }, [applicationConfigs, workflowId]);

  useEffect(() => {
    window.dispatchEvent(new CustomEvent('workflowPanelAgentConfigsChange', {
      detail: { workflowId, configs: agentConfigs },
    }));
  }, [agentConfigs, workflowId]);

  // ── Listen for datasource tab open requests ──
  useEffect(() => {
    const handler = (event: CustomEvent<{ dataSourceId: string; label: string }>) => {
      const { dataSourceId, label } = event.detail;
      if (!sidePanel || !dataSourceId) return;
      import('@/components/app/DataSourcePanelContent').then(({ DataSourcePanelContent }) => {
        sidePanel.openTab({
          id: `datasource-${dataSourceId}`,
          label,
          icon: React.createElement(Table, { className: 'w-4 h-4' }),
          content: React.createElement(DataSourcePanelContent, { dataSourceId, readOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenDatasourceTab', handler as EventListener);
    return () => window.removeEventListener('workflowOpenDatasourceTab', handler as EventListener);
  }, [sidePanel, readOnly]);

  // ── Listen for agent tab open requests ──
  useEffect(() => {
    const handler = (event: CustomEvent<{ agentId: string; label: string; conversationId?: string }>) => {
      const { agentId, label, conversationId: triggerConvId } = event.detail;
      if (!sidePanel || !agentId) return;
      import('@/components/app/AgentPanelContent').then(({ AgentPanelContent }) => {
        sidePanel.openTab({
          id: `agent-${agentId}`,
          label,
          icon: React.createElement(Bot, { className: 'w-4 h-4' }),
          content: React.createElement(AgentPanelContent, { agentId, conversationId: triggerConvId, readOnly }),
          preferredWidth: 0.35,
        });
      });
    };
    window.addEventListener('workflowOpenAgentTab', handler as EventListener);
    return () => window.removeEventListener('workflowOpenAgentTab', handler as EventListener);
  }, [sidePanel, readOnly]);

  // ── Listen for sub-workflow open requests ──
  useEffect(() => {
    const handler = async (event: CustomEvent<{ workflowId: string; workflowName: string; nodeId: string }>) => {
      const { workflowId: subWfId, workflowName: wfName } = event.detail;
      if (!sidePanel || !subWfId) return;

      let pinnedRunId: string | undefined;
      try {
        const pinnedRun = await orchestratorApi.getPinnedWorkflowRun(subWfId);
        if (pinnedRun?.runId) pinnedRunId = pinnedRun.runId;
      } catch { /* fall back to builder */ }

      if (pinnedRunId) {
        sidePanel.openTab({
          id: `workflow-run-${subWfId}-${pinnedRunId}`,
          label: wfName,
          icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
          content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, runId: pinnedRunId, readOnly }),
          preferredWidth: 0.5,
          keepMounted: true,
        });
      } else {
        sidePanel.openTab({
          id: `workflow-builder-${subWfId}`,
          label: wfName,
          icon: React.createElement(Workflow, { className: 'w-4 h-4' }),
          content: React.createElement(WorkflowBuilderPanelContent, { workflowId: subWfId, readOnly }),
          preferredWidth: 0.5,
          keepMounted: true,
        });
      }
    };
    window.addEventListener('workflowOpenSubWorkflow', handler as EventListener);
    return () => window.removeEventListener('workflowOpenSubWorkflow', handler as EventListener);
  }, [sidePanel, readOnly]);

  return (
    <WorkflowModeProvider
      key={runId || 'edit'}
      workflowId={workflowId}
      readOnly={readOnly}
      initialRunId={runId}
    >
      <WorkflowRunProvider>
        <div className="relative w-full h-full overflow-hidden">
          <WorkflowPanelContent
            workflowId={workflowId}
            /* Fall back to the canvas's in-place run id (reported via triggerData)
               so the Application interface renders against the live run even when
               the panel was opened without a runId (the + menu / chat-event path,
               where the URL has no /run/<id>). Without this the interface tab shows
               "No template configured" because it has no run to render against. */
            runId={runId ?? triggerData?.runId}
            workflowCanvasSlot={
              <WorkflowModeProvider workflowId={workflowId} initialRunId={runId} readOnly={readOnly}>
                <div className="h-full w-full relative overflow-x-auto">
                  <WorkflowRunCanvas
                    workflowId={workflowId}
                    runId={runId}
                    onTriggerConfigsChange={setTriggerData}
                    onApplicationConfigsChange={setApplicationConfigs}
                    onAgentConfigsChange={setAgentConfigs}
                    nodesRef={canvasNodesRef}
                  />
                </div>
              </WorkflowModeProvider>
            }
          />
        </div>
      </WorkflowRunProvider>
    </WorkflowModeProvider>
  );
}
