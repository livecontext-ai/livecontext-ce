'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';
import { dispatchLayoutApplied } from '@/lib/workflow/layoutAppliedEvent';
import { planSyncLayoutOptions } from '../utils/planLayoutDirection';
import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { isRunCameraFollowEnabled } from '../services/runCameraFollowStore';
import { findAddedNodeIds } from '../services/buildFollow';
import { WORKFLOW_FOLLOW_NODES_EVENT } from '../services/runFollowEvent';
import { isEventForWorkflow } from '@/lib/workflow/workflowEventScope';

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
  handlePosition: 'left' | 'right' | 'top' | 'bottom';
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
  // An agent-pushed plan must land in the direction the canvas is wired for.
  const { direction: layoutDirection, setWorkflowDirection } = useWorkflowLayoutDirectionSafe();
  // Read through a ref, NOT a dependency: this value must be the direction at the
  // moment the plan is imported, but adding it to the effect deps below would
  // re-register the listener (and, in the loader, re-fetch the workflow) every time
  // the user flips the preference. A dependency-free read would instead capture the
  // context's SEED value ('horizontal'): the provider restores the stored direction
  // in a mount effect, and React flushes child effects BEFORE ancestor ones, so a
  // hard load straight onto a builder URL would lay the graph out horizontally while
  // every handle rendered vertically.
  const layoutDirectionRef = React.useRef(layoutDirection);
  layoutDirectionRef.current = layoutDirection;
  const setWorkflowDirectionRef = React.useRef(setWorkflowDirection);
  setWorkflowDirectionRef.current = setWorkflowDirection;

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

  // Whether the agent chat of THIS workflow is streaming. Following the build frames
  // only the newest nodes, and the canvas renders only what is on screen, so once the
  // agent is done the finished graph is framed whole: the user sees what was built,
  // not its last node.
  // 'unknown' until this workflow's chat reports a stream (an agent driving the plan
  // from elsewhere never does): the build is followed then too, just never re-framed
  // whole at the end.
  const agentStreamRef = React.useRef<'unknown' | 'streaming' | 'ended'>('unknown');
  const followedDuringStreamRef = React.useRef(false);
  React.useEffect(() => {
    if (!workflowId) return;
    const handleStreamingChange = (event: Event) => {
      const detail = (event as CustomEvent<{ isStreaming?: boolean; workflowId?: string }>).detail;
      if (!isEventForWorkflow(detail, workflowId)) return;
      const streaming = !!detail?.isStreaming;
      const ended = agentStreamRef.current === 'streaming' && !streaming;
      if (streaming) agentStreamRef.current = 'streaming';
      else if (agentStreamRef.current === 'streaming') agentStreamRef.current = 'ended';
      if (!ended || !followedDuringStreamRef.current) return;
      followedDuringStreamRef.current = false;
      window.dispatchEvent(new CustomEvent('workflowViewFitView', { detail: { animated: true } }));
    };
    window.addEventListener('workflowStreamingStateChange', handleStreamingChange);
    return () => window.removeEventListener('workflowStreamingStateChange', handleStreamingChange);
  }, [workflowId]);

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
              const importResult = await WorkflowPlanImporter.importPlan(
                planJson, [], planSyncLayoutOptions(layoutDirectionRef.current), { queryClient, isRunMode },
              );

              if (importResult.success) {
                // The importer keeps every stored position and only places the nodes the
                // agent just added. A forced Dagre pass here re-laid the whole graph after
                // every agent action and threw away the layout the user had saved.
                let layoutedNodes = importResult.nodes;

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

                // Read BEFORE nodesRef is overwritten: it is the canvas the agent built on.
                const addedNodeIds = findAddedNodeIds(nodesRef.current, layoutedNodes);

                // The direction the importer placed the nodes in: the canvas's own, unless
                // the agent stated another one in its plan. Beside setNodes so both land
                // in one render.
                setWorkflowDirectionRef.current(importResult.layoutDirection);
                setNodes(layoutedNodes);
                setEdges(importResult.edges);
                nodesRef.current = layoutedNodes;
                edgesRef.current = importResult.edges;
                console.log('[WorkflowEventListeners] ✅ Plan refreshed');

                // When the plan had no position at all, the importer just laid the whole
                // graph out from label ESTIMATES (nothing is measured at import time), and
                // those estimates decide both where a node is centred and how much room the
                // next rank gets. Say so, and let MeasuredLayoutSync replay the layout on
                // the real sizes once the browser has painted them. Only then: that replay
                // recomputes EVERY node, so announcing a plan whose positions were kept
                // would move the nodes the user placed. This is the ONLY announcement in
                // the app: a load is laid out from estimates too, but a correction there
                // necessarily lands after the dirty and undo baselines have settled and
                // would mark a workflow the user merely opened as edited
                // (postLoadPositionWriteArmsBaselines.test.tsx is that fact, executable).
                if (importResult.laidOutFromScratch) {
                  dispatchLayoutApplied(workflowId);
                }

                // After a short delay to let React render the new nodes. Unless the
                // agent's stream is known to have ENDED, with camera follow on (the
                // default), frame
                // what it just ADDED, through the same event a run uses, so the user
                // watches the build node by node; an edit that adds nothing leaves the
                // camera where it is. Otherwise (follow off, or a sync that lands after
                // the stream ended) the whole graph is framed as before. Both are read
                // at fire time so a toggle flipped in between is honoured.
                setTimeout(() => {
                  if (isRunCameraFollowEnabled() && agentStreamRef.current !== 'ended') {
                    if (addedNodeIds.length === 0) return;
                    followedDuringStreamRef.current = true;
                    window.dispatchEvent(new CustomEvent(WORKFLOW_FOLLOW_NODES_EVENT, {
                      detail: { workflowId, nodeIds: addedNodeIds },
                    }));
                    return;
                  }
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
