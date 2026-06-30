'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';
import { applyDagreLayout } from '../services/LayoutService';

interface UseWorkflowEventListenersOptions {
  workflowId?: string;
  isRunMode: boolean;
  setNodes: (nodes: Node<BuilderNodeData>[] | ((prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[])) => void;
  setEdges: (edges: Edge[] | ((prev: Edge[]) => Edge[])) => void;
  nodesRef: React.MutableRefObject<Node<BuilderNodeData>[]>;
  edgesRef: React.MutableRefObject<Edge[]>;
  runContext?: {
    initRun: (runId: string) => Promise<void>;
  } | null;
}

interface ApplicationModeState {
  isOpen: boolean;
  interfaceId: string | null;
  actionMapping: Record<string, string>;
}

interface UseWorkflowEventListenersReturn {
  currentSchedule: { cron?: string } | null;
  currentScheduleRef: React.MutableRefObject<{ cron?: string } | null>;
  isSyncingPlan: boolean;
  pendingHoverConnection: PendingHoverConnection | null;
  pendingHoverConnectionRef: React.MutableRefObject<PendingHoverConnection | null>;
  applicationModeState: ApplicationModeState;
  setApplicationModeState: React.Dispatch<React.SetStateAction<ApplicationModeState>>;
}

interface PendingHoverConnection {
  nodeId: string;
  handleId: string;
  handleType: 'source' | 'target';
  handlePosition: 'left' | 'right' | 'top';
  position: { x: number; y: number };
}

/**
 * Hook to manage all workflow-related event listeners
 * Consolidates window event subscriptions into a single hook
 */
export function useWorkflowEventListeners({
  workflowId,
  isRunMode,
  setNodes,
  setEdges,
  nodesRef,
  edgesRef,
  runContext,
}: UseWorkflowEventListenersOptions): UseWorkflowEventListenersReturn {
  const queryClient = useQueryClient();

  // Schedule state
  const [currentSchedule, setCurrentSchedule] = React.useState<{ cron?: string } | null>(null);
  const currentScheduleRef = React.useRef<{ cron?: string } | null>(null);

  // Syncing state
  const [isSyncingPlan, setIsSyncingPlan] = React.useState(false);

  // Pending hover connection state
  const [pendingHoverConnection, setPendingHoverConnection] = React.useState<PendingHoverConnection | null>(null);
  const pendingHoverConnectionRef = React.useRef<PendingHoverConnection | null>(null);

  // Application mode state
  const [applicationModeState, setApplicationModeState] = React.useState<ApplicationModeState>({
    isOpen: false,
    interfaceId: null,
    actionMapping: {},
  });

  // Keep ref in sync
  React.useEffect(() => {
    pendingHoverConnectionRef.current = pendingHoverConnection;
  }, [pendingHoverConnection]);

  // Listen for schedule changes from ChatHeader
  React.useEffect(() => {
    const handleScheduleChange = (event: CustomEvent<{ schedule: { cron?: string } | null }>) => {
      console.log('[Schedule] Received workflowScheduleChanged event:', event.detail?.schedule);
      setCurrentSchedule(event.detail?.schedule || null);
      currentScheduleRef.current = event.detail?.schedule || null;
    };

    window.addEventListener('workflowScheduleChanged', handleScheduleChange as EventListener);
    console.log('[Schedule] Event listener set up, requesting current schedule');

    // Request current schedule from ChatHeader (in case it was already set before listener was ready)
    window.dispatchEvent(new CustomEvent('requestCurrentSchedule'));

    return () => {
      window.removeEventListener('workflowScheduleChanged', handleScheduleChange as EventListener);
    };
  }, []);

  // Listen for workflow plan modifications from LLM (via StreamingContext)
  React.useEffect(() => {
    if (!workflowId) return;

    const handlePlanModified = async () => {
      console.log('[WorkflowEventListeners] 🔄 Received workflowPlanModified event');

      // Don't refresh in run mode - the plan is fixed for the run
      if (isRunMode) {
        console.log('[WorkflowEventListeners] Skipping refresh in run mode');
        return;
      }

      // In a publication preview the plan is the frozen planSnapshot - never
      // refetch the live tenant workflow. The publisher viewing their own
      // preview MUST see the snapshot, not their post-publication edits.
      if (getActivePublicPreview()) {
        console.log('[WorkflowEventListeners] Skipping refresh in publication preview');
        return;
      }

      setIsSyncingPlan(true);

      try {
        // Fetch the updated workflow plan
        const workflow = await orchestratorApi.getWorkflow(workflowId);
        const plan = workflow.plan;

        // Keep the React Query cache coherent with the canvas. This handler
        // fetches the plan via the raw API (bypassing React Query), so the
        // ['workflow', id] cache would otherwise stay stale for up to its
        // 5-minute staleTime - any component reading useWorkflow(id), or a
        // remount of this builder, would then resurrect the pre-mutation plan.
        // Seed the cache with the freshly-fetched workflow and refresh the list.
        queryClient.setQueryData(['workflow', workflowId], workflow);
        queryClient.invalidateQueries({ queryKey: ['workflows'] });

        if (plan) {
          // Import the updated plan using startTransition for non-blocking update
          React.startTransition(async () => {
            try {
              const planJson = JSON.stringify(plan);
              const importResult = await WorkflowPlanImporter.importPlan(planJson, []);

              if (importResult.success) {
                // Always apply Dagre layout after sync (same algo as the toolbox auto-layout button)
                // The importer may use applyMixedLayout (simple heuristic) when only some nodes
                // lack positions, which produces poor results. Dagre gives consistent, clean layouts.
                let layoutedNodes = applyDagreLayout(importResult.nodes, importResult.edges);

                // Resolve agent avatars for nodes with agentConfigId but no agentAvatarUrl
                const agentNodesNeedingAvatar = layoutedNodes.filter(
                  n => (n.data as any).agentConfigId && !(n.data as any).agentAvatarUrl
                );
                if (agentNodesNeedingAvatar.length > 0) {
                  try {
                    const agents: Agent[] = await orchestratorApi.getAgents();
                    const agentMap = new Map(agents.map(a => [a.id, a]));
                    layoutedNodes = layoutedNodes.map(n => {
                      const configId = (n.data as any).agentConfigId;
                      if (configId && !(n.data as any).agentAvatarUrl) {
                        const agent = agentMap.get(configId);
                        if (agent?.avatarUrl) {
                          return { ...n, data: { ...n.data, agentAvatarUrl: agent.avatarUrl } };
                        }
                      }
                      return n;
                    });
                  } catch (err) {
                    console.warn('[WorkflowEventListeners] Failed to resolve agent avatars:', err);
                  }
                }

                setNodes(layoutedNodes);
                setEdges(importResult.edges);
                nodesRef.current = layoutedNodes;
                edgesRef.current = importResult.edges;
                console.log('[WorkflowEventListeners] ✅ Plan refreshed with Dagre layout');

                // Trigger fit view after a short delay to let React render the new nodes
                setTimeout(() => {
                  window.dispatchEvent(new CustomEvent('workflowViewFitView', {
                    detail: { animated: true }
                  }));
                }, 100);
              } else {
                console.warn('[WorkflowEventListeners] ⚠️ Plan import failed:', importResult.error);
              }
            } catch (importError) {
              console.error('[WorkflowEventListeners] ❌ Plan import threw error:', importError);
            }
          });
        } else {
          console.warn('[WorkflowEventListeners] ⚠️ Fetched workflow has no plan');
        }
      } catch (error) {
        console.error('[WorkflowEventListeners] Failed to refresh plan:', error);
      } finally {
        // Small delay to show the syncing indicator
        setTimeout(() => setIsSyncingPlan(false), 300);
      }
    };

    window.addEventListener('workflowPlanModified', handlePlanModified as EventListener);

    return () => {
      window.removeEventListener('workflowPlanModified', handlePlanModified as EventListener);
    };
  }, [workflowId, isRunMode, setNodes, setEdges, nodesRef, edgesRef, queryClient]);

  // Listen for workflow execution started events (from LLM executing workflow via conversation)
  React.useEffect(() => {
    if (!workflowId) return;

    const handleExecutionStarted = (event: CustomEvent<{ workflowId: string; runId: string; runIndex?: number }>) => {
      const { workflowId: executedWorkflowId, runId: executedRunId } = event.detail;
      console.log('[WorkflowEventListeners] 🚀 Received workflowExecutionStarted event:', event.detail);

      // Only handle if this workflow is being executed
      if (executedWorkflowId === workflowId) {
        console.log('[WorkflowEventListeners] Starting streaming for runId:', executedRunId);

        // Initialize run state IMMEDIATELY for datasource triggers
        if (runContext) {
          console.log('[WorkflowEventListeners] Initializing run via WorkflowRunContext');
          runContext.initRun(executedRunId);
        }
      }
    };

    window.addEventListener('workflowExecutionStarted', handleExecutionStarted as EventListener);

    return () => {
      window.removeEventListener('workflowExecutionStarted', handleExecutionStarted as EventListener);
    };
  }, [workflowId, runContext]);


  // Application mode events are now intercepted in WorkflowBuilder
  // and redirected to WorkflowMessagesPanel tabs via workflowOpenApplicationTab

  // Listen for hover edge pending connection events
  React.useEffect(() => {
    const handlePendingConnection = (event: CustomEvent<PendingHoverConnection>) => {
      setPendingHoverConnection(event.detail);
    };

    const handleNodeCreated = () => {
      // Clear pending connection when node is created
      setPendingHoverConnection(null);
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setPendingHoverConnection(null);
      }
    };

    window.addEventListener('hoverEdgePendingConnection', handlePendingConnection as EventListener);
    window.addEventListener('workflowNodeCreated', handleNodeCreated);
    window.addEventListener('keydown', handleKeyDown);

    return () => {
      window.removeEventListener('hoverEdgePendingConnection', handlePendingConnection as EventListener);
      window.removeEventListener('workflowNodeCreated', handleNodeCreated);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return {
    currentSchedule,
    currentScheduleRef,
    isSyncingPlan,
    pendingHoverConnection,
    pendingHoverConnectionRef,
    applicationModeState,
    setApplicationModeState,
  };
}
