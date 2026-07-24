'use client';

import * as React from 'react';
import { Edge, Node, useEdgesState, useNodesState } from 'reactflow';
import { useRouter } from 'next/navigation';
import { deriveBadgeCycleResult } from '@/lib/utils/runStatusUtils';
import { useQuery } from '@tanstack/react-query';

import { INITIAL_EDGES, INITIAL_NODES } from '../data/initialGraph';
import type { BuilderNodeData } from '../types';
import { BuilderCanvas } from './BuilderCanvas';
import { InspectorPanel } from './InspectorPanel';
import { nodeRegistry } from '../registry/nodeRegistry';
import { useOAuthCredentialCallback } from '../hooks/useOAuthCredentialCallback';
import { NodeCreatorPanel, getPaletteItemDataFromId } from './NodeCreatorPanel';
import { useApprovalReviewSelection } from '../hooks/useApprovalReviewSelection';
import { WorkflowRunsHistoryPanel } from '@/components/workflow/WorkflowRunsHistoryPanel';
import type { ConnectionType } from './ConnectionTypeSelector';
import { generateWorkflowPlan } from '../utils/workflowPlanGenerator';
import { resolveInsertedTargetHandle } from '../utils/hoverConnectHandles';
import { reconcilePlanCredentials } from '@/lib/credentials/reconcilePlanCredentials';
import type { Credential } from '@/lib/api/orchestrator';
import { normalizeLabel, triggerKey, agentKey } from '../utils/labelNormalizer';
import { findLiveFormTriggerNode } from '../utils/formTriggerNodeMatcher';
import { syncRunStateToReactFlow } from '../services/runStateSyncService';
import type { TriggerPanelConfig } from './TriggerPanel';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import type { AgentSnapshotConfig } from '../types/agentSnapshot';

import { useHistory } from '../hooks/useHistory';
import { useSelection } from '../hooks/useSelection';
import { useGraphOperations } from '../hooks/useGraphOperations';
import { useCanvasContextMenuActions } from '../hooks/useCanvasContextMenuActions';
import { usePreparedGraph } from '../hooks/usePreparedGraph';
import { useDirtyState } from '../hooks/useDirtyState';
import { useRunStateProcessing } from '../hooks/useRunStateProcessing';
import { useEpochStateViewing } from '../hooks/useEpochStateViewing';
import { useStepByStepHandlers } from '../hooks/useStepByStepHandlers';
import { useWorkflowEventListeners } from '../hooks/useWorkflowEventListeners';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useWorkflowPauseResume } from '../hooks/useWorkflowPauseResume';
import { useWorkflowLoader } from '../hooks/useWorkflowLoader';
import { useWorkflowExecution } from '../hooks/useWorkflowExecution';
import { useWorkflowStreaming } from '../hooks/execution';
import { orchestratorApi, webhookSettingsService, chatEndpointSettingsService, formEndpointSettingsService } from '@/lib/api';
import { StepByStepProvider } from '../contexts/StepByStepContext';
import { ValidationProvider } from '../contexts/ValidationContext';
import { setCanvasNodes } from '../services/canvasNodesStore';
import { useWorkflowRunContext } from '@/contexts/WorkflowRunContext';
import { calculateNodePosition } from '../utils/nodePositioning';
import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { resolveEffectiveRunId } from '../utils/effectiveRunId';
import Toast, { useToast } from '@/components/Toast';
import { useTranslations } from 'next-intl';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';

export interface RunInfoData {
  runInfo: any | null;
  isStepByStep: boolean;
  currentEpoch: number;
  epochTimestamps: Array<{ epoch: number; startedAt: string; endedAt: string | null }>;
  /** Aggregated steps derived from WebSocket batch-updates (includes timing). */
  streamedSteps?: Array<{
    alias: string;
    toolId: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    executionTimeMs?: number;
    totalExecutionTimeMs?: number;
    statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number };
  }>;
}

export interface TriggerDataForPanel {
  configs: TriggerPanelConfig[];
  activeTriggerId?: string;
  readySteps?: Set<string>;
  runStatus?: string;
  isStepByStepMode?: boolean;
  /** The run bound to the canvas (contextRunId || runId prop). Lets the side-panel
   *  render the interface against the in-place run even when no runId is in the URL
   *  or prop (e.g. a workflow opened from the + menu / a chat event). */
  runId?: string;
}

interface WorkflowBuilderProps {
  workflowId?: string;
  runId?: string;
  planOverride?: any; // Enriched plan (e.g. publication planSnapshot) to use instead of fetching from run
  onDirtyChange?: (isDirty: boolean) => void;
  onRefreshBlocked?: () => void;
  onWorkflowLoaded?: (info: { name?: string; id?: string }) => void;
  onRunInfoChange?: (data: RunInfoData) => void;
  onTriggerConfigsChange?: (data: TriggerDataForPanel) => void;
  onApplicationConfigsChange?: (configs: ApplicationConfig[]) => void;
  onAgentConfigsChange?: (configs: AgentSnapshotConfig[]) => void;
  onHasChatFormTrigger?: (has: boolean) => void;
  executeTriggerRef?: React.MutableRefObject<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>;
  applicationActionRef?: React.MutableRefObject<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>;
  saveRef?: React.MutableRefObject<(() => Promise<void>) | null>;
  nodesRef?: React.MutableRefObject<Node<BuilderNodeData>[]>;
  isRunsHistoryOpen?: boolean;
  onOpenRunsHistory?: () => void;
  onCloseRunsHistory?: () => void;
  onSettingsOpenChange?: (isOpen: boolean) => void;
}

export function WorkflowBuilder({
  workflowId,
  runId,
  planOverride,
  onDirtyChange,
  onRefreshBlocked,
  onWorkflowLoaded,
  onRunInfoChange,
  onTriggerConfigsChange,
  onApplicationConfigsChange,
  onAgentConfigsChange,
  onHasChatFormTrigger,
  executeTriggerRef,
  applicationActionRef,
  saveRef,
  nodesRef: externalNodesRef,
  isRunsHistoryOpen,
  onOpenRunsHistory,
  onCloseRunsHistory,
  onSettingsOpenChange,
}: WorkflowBuilderProps = {}) {
  const router = useRouter();
  // Drives where a hover-"+" drops the new node (below in vertical, right in horizontal).
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const t = useTranslations('workflowBuilder');
  const tCredentials = useTranslations('credentials');
  const { toasts, addToast, removeToast } = useToast();

  // Context hooks
  const { isRunMode, isPreviewOnly, runId: contextRunId, setRunId: setContextRunId, viewingEpoch } = useWorkflowMode();
  const runContext = useWorkflowRunContext();
  // The context run id wins; the static `runId` prop is only a fallback WHILE in
  // run mode. In edit mode effectiveRunId must be undefined (like the prop-less +
  // path) so a later edit→run toggle to the SAME run still changes effectiveRunId
  // and re-fires the status-count repaint blanked by the run→edit reset. See
  // resolveEffectiveRunId for the full regression note.
  const effectiveRunId = resolveEffectiveRunId(contextRunId, runId, isRunMode);

  // Real-time updates via WebSocket channel subscription.
  // Gating only on `effectiveRunId` (no isRunMode guard) restores the SSE-era
  // behavior: as soon as a run is bound to the canvas (e.g. user fires a manual
  // trigger from edit mode → setContextRunId → effectiveRunId set), the WS
  // subscribes and live updates flow. The isRunMode guard added in the WS
  // migration (commit 726859d21) was over-strict - it broke live updates for
  // any run started from edit mode, including the user-triggered run scenario
  // that worked pre-migration. The hook itself short-circuits when runId is
  // null and skips subscribe in publication-preview mode.
  useWorkflowStreaming(effectiveRunId);

  // Core state
  const [nodes, setNodesRaw, onNodesChangeBase] = useNodesState<BuilderNodeData>(INITIAL_NODES);
  const [edges, setEdges, onEdgesChange] = useEdgesState(INITIAL_EDGES);
  const [webhookTokens, setWebhookTokens] = React.useState<Record<string, string>>({});


  const [workflowNameState, setWorkflowNameState] = React.useState<string>('');

  // DEBUG WATCHDOG: wrap setNodes to detect when core: node statusCounts are cleared
  const prevCoreCountsRef = React.useRef<Map<string, Record<string, number>>>(new Map());
  const setNodes = React.useCallback((update: Node<BuilderNodeData>[] | ((prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[])) => {
    if (typeof update === 'function') {
      setNodesRaw((prev: Node<BuilderNodeData>[]) => {
        const result = update(prev);
        const prevMap = prevCoreCountsRef.current;
        for (const node of result) {
          if (nodeRegistry.isCoreNode(node)) {
            const prevCounts = prevMap.get(node.id);
            if (prevCounts && !node.data.statusCounts) {
              streamDebug.warn('WATCHDOG', `⚠️ Core node "${node.data.label}" (${node.id}) LOST statusCounts via functional setNodes!`, {
                prevCounts,
                newStatus: node.data.status,
              });
              console.trace('[WATCHDOG] statusCounts disappeared (functional setNodes)');
            }
          }
        }
        return result;
      });
    } else {
      const prevMap = prevCoreCountsRef.current;
      for (const node of update) {
        if (nodeRegistry.isCoreNode(node)) {
          const prevCounts = prevMap.get(node.id);
          if (prevCounts && !node.data.statusCounts) {
            streamDebug.warn('WATCHDOG', `⚠️ Core node "${node.data.label}" (${node.id}) LOST statusCounts via direct setNodes!`, {
              prevCounts,
              newStatus: node.data.status,
            });
            console.trace('[WATCHDOG] statusCounts disappeared (direct setNodes)');
          }
        }
      }
      setNodesRaw(update);
    }
  }, [setNodesRaw]);

  // Track core: node statusCounts across renders
  React.useEffect(() => {
    const map = new Map<string, Record<string, number>>();
    for (const node of nodes) {
      if (nodeRegistry.isCoreNode(node) && node.data.statusCounts) {
        map.set(node.id, node.data.statusCounts);
      }
    }
    prevCoreCountsRef.current = map;
  }, [nodes]);

  // Refs for tracking state changes
  const nodesRef = React.useRef(nodes);
  const edgesRef = React.useRef(edges);
  // The active reading direction, kept in a ref so the (few-dep) save callbacks can
  // stamp it into the generated plan without re-creating on every direction change.
  const layoutDirectionRef = React.useRef(layoutDirection);
  layoutDirectionRef.current = layoutDirection;

  // Keep save/runtime refs in sync before the browser can process the next UI action.
  React.useLayoutEffect(() => {
    nodesRef.current = nodes;
    if (externalNodesRef) externalNodesRef.current = nodes;
  }, [nodes, externalNodesRef]);
  React.useLayoutEffect(() => { edgesRef.current = edges; }, [edges]);

  // Event listeners hook (schedule, plan modifications, execution started, interface fullscreen, hover connections)
  const {
    currentSchedule,
    currentScheduleRef,
    isSyncingPlan,
    pendingHoverConnection,
    pendingHoverConnectionRef,
    applicationModeState,
    setApplicationModeState,
  } = useWorkflowEventListeners({
    workflowId,
    isRunMode,
    setNodes,
    setEdges,
    nodesRef,
    edgesRef,
    runContext,
  });

  // Workflow loader hook - must be before useDirtyState which uses workflowLoaded
  const {
    isLoadingWorkflow,
    workflowLoaded,
    workflowData,
    loadError,
    retryLoad,
  } = useWorkflowLoader({
    workflowId,
    runId: effectiveRunId,
    planOverride,
    setNodes,
    setEdges,
    nodesRef,
    edgesRef,
  });

  // Dirty state management hook
  const { isDirty, resetDirtyState } = useDirtyState({
    nodes,
    edges,
    workflowLoaded,
    isRunMode,
    onDirtyChange,
    onRefreshBlocked,
  });

  // UI state
  const [selectedNodeIds, setSelectedNodeIdsRaw] = React.useState<string[]>([]);
  // Stable setter: only update state when array contents actually change.
  // Without this, setting selectedNodeIds to a new [] on every render causes
  // an infinite loop: new ref → usePreparedGraph recomputes → ReactFlow re-renders → repeat.
  const setSelectedNodeIds = React.useCallback<React.Dispatch<React.SetStateAction<string[]>>>((action) => {
    setSelectedNodeIdsRaw((prev) => {
      const next = typeof action === 'function' ? action(prev) : action;
      if (prev.length === next.length && prev.every((id, i) => id === next[i])) {
        return prev; // Same content - return same reference to avoid re-render
      }
      return next;
    });
  }, []);
  const [previewModeNodes, setPreviewModeNodes] = React.useState<Set<string>>(new Set());
  const [isAdvancedMode, setIsAdvancedMode] = React.useState(() => isRunMode);
  const [isFullscreenMode, setIsFullscreenMode] = React.useState(false);
  const [isInspectorMinimized, setIsInspectorMinimized] = React.useState(false);
  const [hoveredEdgeId, setHoveredEdgeId] = React.useState<string | null>(null);
  const [isNodeCreatorOpen, setIsNodeCreatorOpen] = React.useState(false);
  const nodeCreationCounterRef = React.useRef(0);
  const [inspectorConnectionType, setInspectorConnectionType] = React.useState<ConnectionType>(() => {
    if (typeof window !== 'undefined') {
      return (localStorage.getItem('workflow:connectionType') as ConnectionType) || 'bezier';
    }
    return 'bezier';
  });
  const [reactFlowConnectionType, setReactFlowConnectionType] = React.useState<ConnectionType>(() => {
    if (typeof window !== 'undefined') {
      return (localStorage.getItem('workflow:connectionType') as ConnectionType) || 'bezier';
    }
    return 'bezier';
  });

  const handleConnectionTypeChange = React.useCallback((type: ConnectionType) => {
    setInspectorConnectionType(type);
    setReactFlowConnectionType(type);
    localStorage.setItem('workflow:connectionType', type);
  }, []);

  // Auto-activate advanced mode in run mode
  React.useEffect(() => {
    if (isRunMode && !isAdvancedMode) {
      setIsAdvancedMode(true);
    }
  }, [isRunMode, isAdvancedMode]);

  // Sync previewModeNodes from node data (showPreview persisted in plan)
  // Default is preview mode ON unless explicitly set to false
  React.useEffect(() => {
    if (workflowLoaded && nodes.length > 0) {
      const interfaceNodes = nodes.filter(node => (node.data as any)?.interfaceData);
      const previewIds = interfaceNodes
        .filter(node => (node.data as any)?.interfaceData?.showPreview !== false)
        .map(n => n.id)
        .sort();
      // Only update if the set content actually changed - creating a new Set on every
      // nodes change would cause usePreparedGraph to recompute → infinite render loop.
      setPreviewModeNodes(prev => {
        const prevIds = [...prev].sort();
        if (prevIds.length === previewIds.length && prevIds.every((id, i) => id === previewIds[i])) {
          return prev; // Same content, keep same reference
        }
        return new Set(previewIds);
      });
    }
  }, [workflowLoaded, nodes]); // Re-sync when workflow loads or nodes change

  // Toggle preview handler - also updates node data for persistence
  const handleTogglePreview = React.useCallback((nodeId: string) => {
    setPreviewModeNodes(prev => {
      const newSet = new Set(prev);
      const isNowPreview = !newSet.has(nodeId);
      if (isNowPreview) {
        newSet.add(nodeId);
      } else {
        newSet.delete(nodeId);
      }

      // Update node's interfaceData.showPreview for persistence
      setNodes(currentNodes => currentNodes.map(node => {
        if (node.id === nodeId && (node.data as any)?.interfaceData) {
          return {
            ...node,
            data: {
              ...node.data,
              interfaceData: {
                ...(node.data as any).interfaceData,
                showPreview: isNowPreview,
              },
            },
          };
        }
        return node;
      }));

      return newSet;
    });
  }, [setNodes]);

  // Core save function - single source of truth for all save operations.
  // Handles API call, webhook tokens update, dirty state reset, and standalone webhook sync.
  const saveWorkflowPlan = React.useCallback(async (id: string, plan: any) => {
    const result = await orchestratorApi.updateWorkflowPlan(id, plan, currentScheduleRef.current);
    const savedWebhookTokens = (result as any)?.webhookTokens;
    if (savedWebhookTokens && Object.keys(savedWebhookTokens).length > 0) {
      setWebhookTokens(savedWebhookTokens);
    }
    resetDirtyState(nodesRef.current, edgesRef.current);

    // Sync workflow reference on standalone endpoints (fire-and-forget)
    const currentNodes = nodesRef.current;
    const workflowName = plan?.name || workflowNameState || '';
    const webhookNodes = currentNodes.filter((n: any) => n.data?.standaloneWebhookId);
    for (const node of webhookNodes) {
      const webhookId = (node.data as any).standaloneWebhookId;
      webhookSettingsService.updateWorkflowReference(webhookId, id, workflowName).catch(() => {});
    }
    const chatNodes = currentNodes.filter((n: any) => n.data?.standaloneChatEndpointId);
    for (const node of chatNodes) {
      const endpointId = (node.data as any).standaloneChatEndpointId;
      chatEndpointSettingsService.updateWorkflowReference(endpointId, id, workflowName).catch(() => {});
    }
    const formNodes = currentNodes.filter((n: any) => n.data?.standaloneFormEndpointId);
    for (const node of formNodes) {
      const endpointId = (node.data as any).standaloneFormEndpointId;
      formEndpointSettingsService.updateWorkflowReference(endpointId, id, workflowName).catch(() => {});
    }
  }, [resetDirtyState, workflowNameState]);

  // Save-in-run: updates only run.plan, no version creation, no webhook/schedule sync.
  const saveRunPlan = React.useCallback(async (_id: string, plan: any) => {
    if (!effectiveRunId) return;
    await orchestratorApi.updateRunPlan(effectiveRunId, plan);
    resetDirtyState(nodesRef.current, edgesRef.current);
  }, [effectiveRunId, resetDirtyState]);

  // Current user credentials (shared ['user-credentials'] cache with the inspector
  // and the validator). Used to reconcile pinned credential ids in EVERY plan we
  // hand to the backend - the launch path (autoSaveBeforeExecute), the run-view
  // re-fire / rerun / step-by-step path (getCurrentPlan) - so a deleted or
  // reconnected credential never reaches execution as a dead id (backend strict
  // resolver → credentials_required). Held in a ref so the getter useCallbacks
  // (stable identities) read the latest list without re-creating.
  // Shares the inspector/validator ['user-credentials'] cache (identical config).
  // Refreshed on connect/reconnect via useOAuthCredentialCallback's predicate
  // refetch and on wizard-complete. Residual: a credential DELETED from Settings
  // doesn't invalidate this key, so a delete-then-immediate-rerun within staleTime
  // could see a ≤30s-stale list - bounded because reconcilePlanCredentials only
  // falls back to the integration default (never to another integration), and the
  // launch path (resolvePlanJson) always re-fetches fresh.
  const { data: userCredentialsList } = useQuery({
    queryKey: ['user-credentials'],
    queryFn: () => orchestratorApi.getAllCredentials(),
    staleTime: 30_000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });
  const userCredentialsRef = React.useRef<Credential[] | undefined>(userCredentialsList);
  userCredentialsRef.current = userCredentialsList;

  // Auto-save before execution. Returns planJson so the same plan
  // is reused for execution (single generateWorkflowPlan call prevents version drift).
  // Reconciles pinned credential ids so a deleted/reconnected credential never
  // reaches execution as a dead id (backend strict resolver → credentials_required);
  // reconciling here also heals the persisted workflow.plan for backend passive fires.
  const autoSaveBeforeExecute = React.useCallback(async (): Promise<string | undefined> => {
    if (!workflowId) return undefined;
    try {
      const plan = reconcilePlanCredentials(
        generateWorkflowPlan(nodesRef.current, edgesRef.current, layoutDirectionRef.current),
        userCredentialsRef.current,
      );
      await saveWorkflowPlan(workflowId, plan);
      return JSON.stringify(plan);
    } catch (error) {
      console.error('[AutoSave] Error saving workflow plan:', error);
      return undefined;
    }
  }, [workflowId, saveWorkflowPlan]);

  // Current plan for step-by-step / run-view re-fire (persists InspectorPanel changes to run).
  // Same credential reconciliation as the launch path so run-view re-fires never
  // send a dead credential id. (reconcilePlanCredentials no-ops when the list is
  // not yet loaded - it never drops valid pins.)
  const getCurrentPlan = React.useCallback((): Record<string, unknown> | null => {
    return reconcilePlanCredentials(
      generateWorkflowPlan(nodesRef.current, edgesRef.current, layoutDirectionRef.current),
      userCredentialsRef.current,
    ) as unknown as Record<string, unknown>;
  }, []);

  // Pause/Resume hook
  const [pauseResumeState, pauseResumeActions] = useWorkflowPauseResume(
    effectiveRunId || null,
    (state) => {
      // Suppress live state sync when viewing a historical epoch - epoch data is the source of truth.
      // Without this guard, syncRunStateToReactFlow overwrites epoch-specific node/edge state
      // with accumulated live data (statusCounts from all epochs).
      if (viewingEpoch != null) return;

      const { nodes: updatedNodes, edges: updatedEdges, hasChanges } = syncRunStateToReactFlow(
        state,
        nodesRef.current,
        edgesRef.current
      );
      if (hasChanges) {
        if (updatedNodes !== nodesRef.current) {
          setNodes(updatedNodes as any);
          nodesRef.current = updatedNodes as any;
        }
        if (updatedEdges !== edgesRef.current) {
          setEdges(updatedEdges);
          edgesRef.current = updatedEdges;
        }
      }
    },
    getCurrentPlan
  );

  // Execution error handler for toast notifications
  const handleExecutionError = React.useCallback((error: { type: string; message: string }) => {
    if (error.type === 'epoch_limit') {
      addToast({
        type: 'warning',
        title: t('executionErrors.epochLimitTitle'),
        message: t('executionErrors.epochLimitMessage'),
        duration: 8000,
      });
    } else if (error.type === 'queue_timeout') {
      addToast({
        type: 'warning',
        title: t('executionErrors.queueTimeoutTitle'),
        message: t('executionErrors.queueTimeoutMessage'),
        duration: 8000,
      });
    } else {
      addToast({
        type: 'error',
        title: t('executionErrors.genericTitle'),
        message: error.message,
        duration: 6000,
      });
    }
  }, [addToast, t]);

  // Listen for runtime error events from WorkflowRunManager (workflow-level failures only)
  // Node failures are already visible on the node itself - no toast needed.
  React.useEffect(() => {
    const onWorkflowFailed = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      const isTimeout = detail.message === 'timeout';
      addToast({
        type: 'error',
        title: isTimeout
          ? t('executionErrors.workflowTimeoutTitle')
          : t('executionErrors.workflowFailedTitle'),
        message: isTimeout
          ? t('executionErrors.workflowTimeoutMessage')
          : t('executionErrors.workflowFailedMessage'),
        duration: 8000,
      });
    };

    window.addEventListener('workflowFailed', onWorkflowFailed);
    return () => {
      window.removeEventListener('workflowFailed', onWorkflowFailed);
    };
  }, [addToast, t]);

  // OAuth credential redirect callback (success/error toast + one-time
  // credentials-cache refresh + URL cleanup). Extracted to a hook so it stays
  // unit-testable and so the refetch-vs-invalidate rationale lives next to the
  // code it governs - see useOAuthCredentialCallback.
  useOAuthCredentialCallback({ addToast, tCredentials });

  // Step-by-step handlers hook
  const {
    handlePauseWorkflow,
    handleResumeWorkflow,
    handleResetWorkflow,
    handleToggleStepByStep,
    handleExecuteStep,
    handleExecuteControlNode,
    handleRerunStep,
    handleResolveApproval,
    setWorkflowStatus,
    workflowStatus,
  } = useStepByStepHandlers({
    pauseResumeState,
    pauseResumeActions,
    onExecutionError: handleExecutionError,
  });

  // Run-info step popover → fire a trigger by stepId. WorkflowModeToggle (the
  // popover) is a sibling of StepByStepProvider and cannot call the in-canvas
  // play directly, so it dispatches this event and we reuse the exact same
  // firing path (handleExecuteStep → pauseResumeActions.executeStep) the node
  // bottom-bar play uses. epoch=undefined fires THIS trigger into a fresh epoch.
  // The workflowId filter scopes the event so a concurrently mounted sub-workflow
  // builder panel (its own WorkflowBuilder + listener) does not also fire - same
  // guard the workflowViewSave / workflowNameChangeFromBreadcrumb handlers use.
  React.useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent<{ stepId?: string; workflowId?: string }>).detail;
      if (!detail?.stepId) return;
      if (detail.workflowId && detail.workflowId !== workflowId) return;
      void handleExecuteStep(detail.stepId, undefined);
    };
    window.addEventListener('workflowExecuteStep', handler as EventListener);
    return () => window.removeEventListener('workflowExecuteStep', handler as EventListener);
  }, [handleExecuteStep, workflowId]);

  // Dispatch event when step_by_step mode changes (for ChatHeader Save button)
  React.useEffect(() => {
    const isStepByStep = pauseResumeState.mode === 'step_by_step';
    window.dispatchEvent(new CustomEvent('workflowStepByStepModeChange', {
      detail: { isEnabled: isStepByStep }
    }));
  }, [pauseResumeState.mode]);

  // Workflow execution hook
  const { backendValidationErrors, setBackendValidationErrors } = useWorkflowExecution({
    workflowId,
    nodes,
    edges,
    router,
    setWorkflowStatus,
    pauseResumeActions: {
      setMode: pauseResumeActions.setMode,
      updateReadySteps: pauseResumeActions.updateReadySteps,
    },
    onSaveBeforeExecute: autoSaveBeforeExecute,
    layoutDirection,
  });

  // Apply workflow metadata from loader (no extra API call - reuses workflowData from useWorkflowLoader)
  React.useEffect(() => {
    if (!workflowId || !workflowLoaded) return;

    // workflowData is null for cross-tenant (marketplace preview) - that's expected
    if (!workflowData) {
      onWorkflowLoaded?.({ name: undefined, id: workflowId });
      return;
    }

    // Load webhook tokens (multi-DAG support)
    const loadedWebhookTokens = workflowData.webhookTokens;
    if (loadedWebhookTokens && Object.keys(loadedWebhookTokens).length > 0) {
      setWebhookTokens(loadedWebhookTokens);
    }

    const schedule = workflowData.schedule;
    if (schedule) {
      currentScheduleRef.current = schedule;
    }

    setWorkflowNameState(workflowData.name || '');

    onWorkflowLoaded?.({ name: workflowData.name, id: workflowId });
  }, [workflowId, workflowLoaded, workflowData, onWorkflowLoaded]);

  // Run state from context
  // Streaming is managed by WorkflowRunManager (auto-connects in automatic mode, auto-disconnects when finished)
  // Force re-render counter for run state updates
  // Note: runStateVersion is intentionally unused - its only purpose is to trigger re-renders
  // when the singleton run state changes. The setter is called by the subscription callback.
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const [runStateVersion, setRunStateVersion] = React.useState(0);

  const runState = effectiveRunId ? runContext?.getState(effectiveRunId) : null;


  // Subscribe to run state updates - trigger re-render when state changes
  React.useEffect(() => {
    if (!effectiveRunId || !runContext) return;
    const unsubscribe = runContext.subscribeToRun(effectiveRunId, () => {
      // Increment version to trigger re-render when singleton state changes
      setRunStateVersion(v => v + 1);
    });
    return () => { unsubscribe(); };
  }, [effectiveRunId, runContext]);

  // Track previous runId to detect mode changes
  const prevEffectiveRunIdRef = React.useRef<string | undefined>(undefined);

  // Initialize run context when entering run mode
  React.useEffect(() => {
    if (!effectiveRunId || !runContext || !workflowLoaded) return;

    const isNewRun = prevEffectiveRunIdRef.current !== effectiveRunId;
    const prevRunId = prevEffectiveRunIdRef.current;
    prevEffectiveRunIdRef.current = effectiveRunId;

    // Clean up the previous run's manager, store, and streaming controller
    if (isNewRun && prevRunId) {
      console.log('[WorkflowBuilder] Cleaning up previous run:', prevRunId);
      runContext.resetRun(prevRunId);
    }

    // Use refreshState for new runs to ensure fresh data,
    // initRun for continuing same run (already initialized)
    const initializeRun = isNewRun ? runContext.refreshState : runContext.initRun;

    // Capture runId to detect stale closure after async operation
    const capturedRunId = effectiveRunId;
    const tInit = performance.now();
    console.log('[RUN-MOUNT]', tInit.toFixed(0), `WorkflowBuilder ${isNewRun ? 'refreshState' : 'initRun'} START`, capturedRunId);
    initializeRun(effectiveRunId)
      .then(() => {
        console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'WorkflowBuilder initializeRun DONE', capturedRunId, `(${(performance.now() - tInit).toFixed(0)}ms)`);
        // Guard against stale closure: if the user switched runs during initialization, skip
        if (prevEffectiveRunIdRef.current !== capturedRunId) {
          console.log('[WorkflowBuilder] Run changed during initialization, skipping for:', capturedRunId);
          return;
        }
        // Real-time updates are handled by useWorkflowStreaming hook via WebSocket channel
      })
      .catch((err) => {
        // Manager init/refresh rethrows on failure (network, 4xx/5xx). Surface for
        // diagnostics; the loader's plan import already painted the bare graph,
        // and WS will catch up when the connection recovers.
        console.error('[WorkflowBuilder] initRun/refreshState failed for', capturedRunId, err);
      });
  }, [effectiveRunId, runContext, workflowLoaded]);

  // Epoch state viewing - applies historical epoch data to canvas nodes.
  // IMPORTANT: Must be called BEFORE useRunStateProcessing so that when returning
  // to live mode (viewingEpoch: N → null), the epoch hook restores structural nodes
  // first, then useRunStateProcessing applies current batchSteps on top.
  // If reversed, useRunStateProcessing applies correct live data but then
  // useEpochStateViewing overwrites it with stale saved state.
  // Flips false→true once the canvas nodes are committed. Threaded into the run
  // painters so a pinned/async-loaded graph (whose workflowLoaded can turn true
  // before its nodes exist) re-fires the status paint the moment nodes appear -
  // fixes WS status badges/edge counts staying blank in a pinned run until a
  // workflow switch forced a remount. Stable during execution (count doesn't
  // change), so it adds no extra paints.
  const nodesReady = nodes.length > 0;

  useEpochStateViewing({
    viewingEpoch,
    runId: effectiveRunId,
    nodesRef,
    edgesRef,
    setNodes,
    setEdges,
    workflowLoaded,
    nodesReady,
    snapshotSeq: runState?.snapshotSeq,
    // Live all-mode batch data: source of the running ITEM counts the focus
    // view overlays onto the ACTIVE epoch (running counts aren't persisted
    // per-epoch, so they exist only in this live stream).
    batchSteps: runState?.batchSteps,
    batchEdges: runState?.batchEdges,
  });

  // Run state processing hook - applies live batchSteps/batchEdges to canvas
  useRunStateProcessing({
    runState,
    workflowLoaded,
    nodesReady,
    nodesRef,
    edgesRef,
    setNodes,
    setEdges,
    setWorkflowStatus,
    workflowId,
    effectiveRunId,
    isViewingHistoricalEpoch: viewingEpoch != null,
  });

  // Build run info for BuilderCanvas
  const currentRunInfo = React.useMemo(() => {
    if (!effectiveRunId || !runState?.rawRunState) return null;
    // Derive the cycle result so the run badge shows the OUTCOME (green / red / amber) instead of
    // the raw idle "waiting_trigger" once a reusable-trigger run rests between fires. currentRunInfo
    // is assembled from run-state (which carries no run.metadata), so we synthesize the
    // metadata.lastCycleResult shape that getRunDisplayStatus already consumes.
    const lastCycleResult = deriveBadgeCycleResult(
      runState.runStatus,
      runState.completedSteps ? Array.from(runState.completedSteps) : [],
      (runState.failedSteps?.size ?? 0) > 0,
    );
    return {
      id: runState.workflowId || effectiveRunId,
      runId: effectiveRunId,
      workflowId: runState.workflowId || workflowId || '',
      status: runState.runStatus.toUpperCase(),
      executionMode: runState.executionMode,
      startedAt: runState.startedAt,
      endedAt: runState.endedAt,
      completedAt: runState.completedAt,
      durationMs: runState.durationMs,
      totalNodes: runState.totalNodes,
      // Progress counts from run state
      completedCount: runState.completedSteps?.size ?? 0,
      failedCount: runState.failedSteps?.size ?? 0,
      runningCount: runState.runningSteps?.size ?? 0,
      skippedCount: runState.skippedSteps?.size ?? 0,
      // Sum of per-node execution counts (changes with each epoch even if node statuses don't)
      executionTotal: runState.executionTotal ?? 0,
      // Plan version this run uses (for version badge in run info header)
      planVersion: runState.rawRunState?.planVersion ?? undefined,
      // Accumulated run cost (all epochs) + per-epoch breakdown + workflow
      // budget, for the RunInfo panel's "Cost of this run" row. Live-updated by
      // the runCost WS event.
      costCredits: runState.costCredits ?? null,
      costByEpoch: runState.costByEpoch ?? {},
      budgetCredits: runState.budgetCredits ?? null,
      // Synthesized cycle result -> drives the badge's display status/color (see above).
      metadata: lastCycleResult ? { lastCycleResult } : undefined,
    };
  }, [effectiveRunId, runState, workflowId]);

  const isStepByStep = runState?.executionMode === 'step_by_step';

  // Build aggregated step list from WebSocket batch-updates (includes timing from backend).
  // This eliminates the need for WorkflowModeToggle to poll /steps/aggregated via REST.
  const streamedSteps = React.useMemo(() => {
    if (!runState?.batchSteps) return undefined;  // no data yet
    if (runState.batchSteps.length === 0) return [];  // data arrived, no steps
    return runState.batchSteps
      .filter((s: any) => {
        const id = s.id || s.nodeId || '';
        // Skip gateway/virtual nodes
        return id && !id.startsWith('gateway_');
      })
      .map((s: any) => ({
        alias: s.id || s.nodeId || '',
        toolId: s.toolId || '',
        status: (s.status || 'pending').toLowerCase(),
        startTime: s.startTime || null,
        endTime: s.endTime || null,
        executionTimeMs: s.executionTimeMs ?? undefined,
        totalExecutionTimeMs: s.totalExecutionTimeMs ?? undefined,
        statusCounts: s.statusCounts ? {
          completed: s.statusCounts.completed ?? 0,
          failed: s.statusCounts.failed ?? 0,
          skipped: s.statusCounts.skipped ?? 0,
          running: s.statusCounts.running ?? 0,
          awaitingSignal: s.statusCounts.awaitingSignal ?? 0,
        } : undefined,
      }));
  }, [runState?.batchSteps]);

  // Log runInfo + streamedSteps for debugging "all epochs" view
  React.useEffect(() => {
    if (!currentRunInfo && !streamedSteps) return;
    streamDebug.log('WorkflowBuilder', 'runInfo updated:', {
      status: currentRunInfo?.status,
      completedCount: currentRunInfo?.completedCount,
      failedCount: currentRunInfo?.failedCount,
      executionTotal: currentRunInfo?.executionTotal,
      totalNodes: currentRunInfo?.totalNodes,
      epoch: runState?.currentEpoch,
      streamedStepsCount: streamedSteps?.length,
      streamedStepsCounts: streamedSteps?.slice(0, 5).map(
        (s: any) => `${s.alias}:${s.status}(c=${s.statusCounts?.completed},f=${s.statusCounts?.failed})`
      ),
    });
  }, [currentRunInfo, streamedSteps, runState?.currentEpoch]);

  // Notify parent of run info changes
  React.useEffect(() => {
    onRunInfoChange?.({
      runInfo: currentRunInfo,
      isStepByStep,
      currentEpoch: runState?.currentEpoch ?? 0,
      epochTimestamps: runState?.epochTimestamps ?? [],
      streamedSteps,
    });
  }, [currentRunInfo, isStepByStep, runState?.currentEpoch, runState?.epochTimestamps, streamedSteps, onRunInfoChange]);

  // Trigger panel state removed - triggers now shown in WorkflowMessagesPanel

  // Extract trigger configurations for chat/form triggers that are ACTUALLY waiting
  // Uses availableTriggers from backend (source of truth) crossed with plan triggers for form fields
  const triggerPanelConfigs = React.useMemo(() => {


    // Use availableTriggers from backend as source of truth
    // This fixes race condition where isWaitingForTrigger becomes false before triggers are loaded
    // Manual/schedule/workflow triggers fire from a dedicated button - only chat/form/webhook
    // open the side panel.
    const waitingTriggers = pauseResumeState.availableTriggers.filter(
      t => t.type === 'chat' || t.type === 'form' || t.type === 'webhook'
    );


    // If backend says there are chat/form triggers waiting, show them
    if (waitingTriggers.length === 0) {
      // No triggers from backend - check if we should wait for them to load
      if (!pauseResumeState.isWaitingForTrigger) {
        return [];
      }
      // Still waiting for triggers to load
      return [];
    }

    // Get plan triggers for additional config (form fields, etc.)
    const planTriggers = runState?.rawRunState?.plan?.triggers || [];

    return waitingTriggers.map((trigger) => {
      const type = trigger.type as 'chat' | 'form' | 'webhook';
      const triggerId = trigger.triggerId;
      const triggerLabel = trigger.label || (type === 'chat' ? 'Chat' : type === 'webhook' ? 'Webhook' : 'Form');

      if (type === 'chat') {
        return {
          triggerId,
          triggerLabel,
          type: 'chat' as const,
        };
      }

      if (type === 'webhook') {
        // Pull defaults from the plan trigger params if present
        const planTrigger = planTriggers.find((pt: any) => {
          const ptId = triggerKey(pt.label) || `trigger:${pt.id}`;
          return ptId === triggerId || pt.label === trigger.label;
        });
        const params = planTrigger?.params || {};
        return {
          triggerId,
          triggerLabel,
          type: 'webhook' as const,
          webhookMethod: (params.method as any) || 'POST',
          webhookUrlPreview: params.url || params.path || '',
          webhookDefaultHeaders: typeof params.headers === 'string'
            ? params.headers
            : params.headers
              ? JSON.stringify(params.headers, null, 2)
              : '{\n  "Content-Type": "application/json"\n}',
          webhookDefaultBody: typeof params.body === 'string'
            ? params.body
            : params.body
              ? JSON.stringify(params.body, null, 2)
              : '{\n  \n}',
        };
      }

      // Form trigger - find matching plan trigger for form fields
      const planTrigger = planTriggers.find((pt: any) => {
        const ptId = triggerKey(pt.label) || `trigger:${pt.id}`;
        return ptId === triggerId || pt.label === trigger.label;
      });

      // Also check live node data (reflects unsaved form edits made in the builder).
      // Bind the form node to THIS specific trigger by its normalized label - a naive
      // "form-trigger-" prefix match would return the first form node for every waiting
      // trigger, so two distinct forms rendered identically in the run panel.
      const formTriggerNode = findLiveFormTriggerNode(nodes, triggerId, trigger.label);

      const formTriggerData = (formTriggerNode?.data as any)?.formTriggerData;
      const configFields = trigger.config?.fields || [];
      const planParams = planTrigger?.params || {};
      const rawFields = formTriggerData?.fields || configFields || planParams.fields || [];

      return {
        triggerId,
        triggerLabel,
        type: 'form' as const,
        formTitle: formTriggerData?.title || trigger.config?.formTitle || planParams.formTitle || triggerLabel,
        formDescription: formTriggerData?.description || trigger.config?.formDescription || planParams.formDescription || '',
        submitButtonText: formTriggerData?.submitButtonText || trigger.config?.submitButtonText || planParams.submitButtonText || 'Submit',
        fields: rawFields.map((field: any, index: number) => ({
          id: field.id || `field-${index}`,
          name: field.name || field.id || `field-${index}`,
          label: field.label || field.name || `Field ${index + 1}`,
          type: field.type || 'text',
          placeholder: field.placeholder || '',
          required: field.required || false,
          options: field.options || [],
          accept: field.accept || '',
        })),
      };
    });
  }, [pauseResumeState.isWaitingForTrigger, pauseResumeState.availableTriggers, runState?.rawRunState?.plan?.triggers, nodes]);

  // Log final configs
  React.useEffect(() => {
  }, [triggerPanelConfigs]);

  // Handle trigger success - panel stays in place, state updates via streaming
  const handleTriggerSuccess = React.useCallback((triggerId?: string, readySteps?: string[]) => {
    // State updates are handled by streaming now, no need to refresh
    console.log('[WorkflowBuilder] Trigger success:', triggerId, 'readySteps:', readySteps);
  }, []);

  // Execute trigger with payload (form/chat) - routes through WorkflowRunManager
  // for proper streaming lifecycle (connect before API) and state management (resetForNewCycle).
  const handleExecuteTrigger = React.useCallback(async (
    triggerId: string,
    triggerType: 'chat' | 'form' | 'webhook',
    payload: Record<string, any>
  ): Promise<string[] | undefined> => {
    if (isPreviewOnly) return undefined;
    if (!effectiveRunId || !runContext) return undefined;

    try {
      console.log('[WorkflowBuilder] Executing trigger via manager:', triggerId, triggerType, 'payload:', Object.keys(payload));

      await runContext.executeStep(effectiveRunId, triggerId, payload, triggerType);

      return [];
    } catch (error) {
      console.error('[WorkflowBuilder] Trigger execution failed:', error);
      throw error;
    }
  }, [effectiveRunId, runContext, isPreviewOnly]);

  // Expose handleExecuteTrigger via ref for WorkflowMessagesPanel
  React.useEffect(() => {
    if (executeTriggerRef) {
      executeTriggerRef.current = handleExecuteTrigger;
    }
    return () => {
      if (executeTriggerRef) {
        executeTriggerRef.current = null;
      }
    };
  }, [handleExecuteTrigger, executeTriggerRef]);

  // Push trigger configs up to WorkflowDetailView for display in WorkflowMessagesPanel.
  // In preview mode, send empty configs to hide trigger tab entirely.
  //
  // Guarded by content comparison: triggerPanelConfigs is rebuilt by useMemo on every
  // `nodes` change (i.e. every node drag position event). Without the guard each drag
  // event fires onTriggerConfigsChange → parent setState → parent re-render storm,
  // which in extreme cases trips React error #185 (max update depth) and is the root
  // cause of "drag a node too often" crashes in production.
  const lastTriggerConfigsKeyRef = React.useRef<string>('');
  React.useEffect(() => {
    const payload = {
      configs: isPreviewOnly ? [] : triggerPanelConfigs,
      activeTriggerId: isPreviewOnly ? undefined : pauseResumeState.availableTriggers.find(
        t => t.type === 'chat' || t.type === 'form'
      )?.triggerId,
      readySteps: isPreviewOnly ? new Set<string>() : pauseResumeState.readySteps,
      runStatus: runState?.runStatus,
      isStepByStepMode: pauseResumeState.mode === 'step_by_step',
      runId: effectiveRunId,
    };
    // Sets are not JSON-serializable; convert readySteps to a sorted array for the key.
    // Defensive: tolerate readySteps being undefined or array-shaped (callers in tests / WIP code).
    const readyStepsArr = payload.readySteps instanceof Set
      ? [...payload.readySteps].sort()
      : Array.isArray(payload.readySteps)
        ? [...(payload.readySteps as string[])].sort()
        : [];
    const key = JSON.stringify({
      configs: payload.configs,
      activeTriggerId: payload.activeTriggerId,
      readySteps: readyStepsArr,
      runStatus: payload.runStatus,
      isStepByStepMode: payload.isStepByStepMode,
      runId: payload.runId,
    });
    if (key === lastTriggerConfigsKeyRef.current) return;
    lastTriggerConfigsKeyRef.current = key;
    onTriggerConfigsChange?.(payload);
  }, [triggerPanelConfigs, pauseResumeState.availableTriggers, pauseResumeState.readySteps, pauseResumeState.mode, runState?.runStatus, onTriggerConfigsChange, isPreviewOnly, effectiveRunId]);

  // Detect all interface nodes and push configs up for the Application carousel
  // Sorted by x-position (left to right) to match visual canvas order
  const applicationConfigs = React.useMemo((): ApplicationConfig[] => {
    if (!isRunMode) return [];
    return nodes
      .filter(node => {
        const iData = (node.data as any)?.interfaceData;
        return !!iData;
      })
      .sort((a, b) => (a.position?.x ?? 0) - (b.position?.x ?? 0))
      .map(node => {
        const iData = (node.data as any).interfaceData;
        const interfaceId = iData.interfaceId || (node.id.startsWith('interface-')
          ? node.id.replace('interface-', '').replace(/--\d+$/, '')
          : node.id);
        const label = (node.data as any).label || 'Application';
        return {
          interfaceId,
          label,
          actionMapping: iData.actionMapping || {},
          nodeId: `interface:${normalizeLabel(label)}`,
          isEntryInterface: iData.isEntryInterface === true,
        };
      });
  }, [nodes, isRunMode, workflowId]);

  // Render-storm guard for the parent. The useMemo above rebuilds on every drag
  // tick (nodes ref changes), so a naive .map+JSON.stringify on the WHOLE
  // config (including positions) would still fire repeatedly.
  //
  // Fix v5: composite content-key derived from the FIELDS the parent actually
  // consumes (interfaceId, label, actionMapping). This:
  //   • is drag-safe - none of these change on x-position drag
  //   • catches label rename + actionMapping edits + reorder + add/remove
  //   • does NOT poison the ref with an opaque stringified state across the
  //     mount boundary (which is what blocked legitimate fires after a parent
  //     remount and produced the marketplace-preview blank-screen bug).
  const lastApplicationConfigsKeyRef = React.useRef<string>('');
  React.useEffect(() => {
    const key = applicationConfigs
      .map(c => `${c.interfaceId}|${c.label}|${c.isEntryInterface ? 1 : 0}|${JSON.stringify(c.actionMapping)}`)
      .join('||');
    if (key === lastApplicationConfigsKeyRef.current) return;
    lastApplicationConfigsKeyRef.current = key;
    console.log('[AppDebug] WorkflowBuilder.onApplicationConfigsChange FIRING', {
      workflowId,
      length: applicationConfigs.length,
      ids: applicationConfigs.map(c => c.interfaceId),
    });
    onApplicationConfigsChange?.(applicationConfigs);
  }, [applicationConfigs, onApplicationConfigsChange, workflowId]);

  // Mount/unmount lifecycle marker - pairs with ApplicationDetailView's instance
  // mount log to discriminate parent-remount vs child-storm in any future regression.
  React.useEffect(() => {
    console.log('[AppDebug] WorkflowBuilder MOUNT', { workflowId, runId: effectiveRunId });
    return () => console.log('[AppDebug] WorkflowBuilder UNMOUNT', { workflowId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Detect all agent nodes and build snapshot configs for the Agent tab
  const agentConfigs = React.useMemo((): AgentSnapshotConfig[] => {
    if (!isRunMode) return [];
    return nodes
      .filter(node => nodeRegistry.isAgentNode(node))
      .map(node => {
        const data = node.data as BuilderNodeData;
        const nodeKey = agentKey(data.label || '') || 'agent:';

        // Get run result from step states if available
        const stepState = runState?.stepStates?.get(nodeKey);
        const stepOutput = stepState?.output;
        const snapshot = stepOutput?.agent_config_snapshot ?? null;

        return {
          nodeId: nodeKey,
          nodeLabel: data.label || 'Agent',
          agentConfigId: data.agentConfigId,
          agentName: snapshot?.agentName ?? data.agentConfigName,
          avatarUrl: snapshot?.avatarUrl,
          snapshot: snapshot ? {
            provider: snapshot.provider,
            model: snapshot.model,
            temperature: snapshot.temperature,
            maxTokens: snapshot.maxTokens,
            maxIterations: snapshot.maxIterations,
            withMemory: snapshot.withMemory,
            toolsMode: snapshot.toolsMode,
            tools: snapshot.tools,
            allowedTables: snapshot.allowedTables,
            allowedInterfaces: snapshot.allowedInterfaces,
            allowedAgents: snapshot.allowedAgents,
            allowedWorkflows: snapshot.allowedWorkflows,
            enabledModules: snapshot.enabledModules,
            systemPromptHash: snapshot.systemPromptHash,
            agentDescription: snapshot.agentDescription,
          } : undefined,
          runResult: stepOutput ? {
            iterations: stepOutput.iterations,
            toolCalls: stepOutput.tool_calls,
            tokensUsed: stepOutput.tokens_used,
            durationMs: stepOutput.durationMs,
            toolCallsDetail: stepOutput.tool_calls_detail,
          } : undefined,
        };
      });
  }, [nodes, isRunMode, runState?.stepStates]);

  // Guarded: see lastTriggerConfigsKeyRef above for the same render-storm rationale.
  // agentConfigs useMemo rebuilds on every drag because it depends on `nodes`.
  const lastAgentConfigsKeyRef = React.useRef<string>('');
  React.useEffect(() => {
    const key = JSON.stringify(agentConfigs);
    if (key === lastAgentConfigsKeyRef.current) return;
    lastAgentConfigsKeyRef.current = key;
    onAgentConfigsChange?.(agentConfigs);
  }, [agentConfigs, onAgentConfigsChange]);

  // Static detection of chat/form trigger nodes (for auto-opening the panel)
  const hasChatFormTrigger = React.useMemo(() => {
    if (!isRunMode) return false;
    return nodes.some(node => {
      const id = node.data?.id || '';
      return id.startsWith('chat-trigger') || id.startsWith('form-trigger');
    });
  }, [nodes, isRunMode]);

  React.useEffect(() => {
    onHasChatFormTrigger?.(hasChatFormTrigger);
  }, [hasChatFormTrigger, onHasChatFormTrigger]);

  // Handle application mode action (interface action mapping → trigger execution)
  const handleApplicationAction = React.useCallback(async (
    triggerRef: string,
    data: Record<string, unknown>
  ) => {
    if (isPreviewOnly) return;

    // Navigate action: pure frontend tab switch - no API call.
    // This is handled in WorkflowPanelContent, but guard here as a safety net
    // in case the event reaches WorkflowBuilder through an alternative path.
    if (triggerRef.endsWith(':navigate')) {
      const parts = triggerRef.split(':');
      const targetLabel = parts.length >= 3 ? parts.slice(1, -1).join(':') : null;
      if (targetLabel) {
        const normalizedTarget = normalizeLabel(targetLabel);
        const matchingConfig = applicationConfigs.find(c => {
          const normalizedConfigLabel = normalizeLabel(c.label);
          return normalizedConfigLabel === normalizedTarget;
        });
        if (matchingConfig) {
          // Dispatch tab switch event so WorkflowPanelContent can pick it up
          window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', {
            detail: { interfaceId: matchingConfig.interfaceId },
          }));
        } else {
          console.warn('[WorkflowBuilder] Navigate target not found:', targetLabel, 'normalized:', normalizedTarget);
        }
      }
      return;
    }

    if (!effectiveRunId || !runContext) {
      console.error('[WorkflowBuilder] No run context for application mode action');
      return;
    }

    try {
      // Parse trigger ref: "trigger:label:actiontype"
      const parts = triggerRef.split(':');
      const actionType = parts.length >= 3 ? parts[parts.length - 1] : 'click';
      const triggerType = actionType === 'submit' ? 'form' :
                         actionType === 'message' ? 'chat' : 'manual';

      // Extract trigger key without action type suffix: "trigger:label:actiontype" → "trigger:label"
      const triggerKey = parts.length >= 3 ? parts.slice(0, -1).join(':') : triggerRef;

      console.log('[WorkflowBuilder] Application mode action:', { triggerRef, triggerKey, actionType, triggerType, data });

      await runContext.executeStep(effectiveRunId, triggerKey, data, triggerType);
    } catch (error) {
      console.error('[WorkflowBuilder] Application mode action failed:', error);
      throw error;
    }
  }, [effectiveRunId, runContext, isPreviewOnly, applicationConfigs]);

  // Expose handleApplicationAction via ref for side panel
  React.useEffect(() => {
    if (applicationActionRef) {
      applicationActionRef.current = handleApplicationAction;
    }
    return () => {
      if (applicationActionRef) {
        applicationActionRef.current = null;
      }
    };
  }, [handleApplicationAction, applicationActionRef]);

  // Intercept openApplicationMode events to open side panel instead of fullscreen modal
  React.useEffect(() => {
    const handleOpenAppMode = (event: CustomEvent<{ interfaceId: string; actionMapping: Record<string, string> }>) => {
      const { interfaceId } = event.detail;
      // Dispatch to side panel instead of opening the fullscreen modal
      window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', {
        detail: { interfaceId },
      }));
    };

    window.addEventListener('openApplicationMode', handleOpenAppMode as EventListener);
    return () => {
      window.removeEventListener('openApplicationMode', handleOpenAppMode as EventListener);
    };
  }, []);

  // Reset dirty state after version restore (plan re-imported by useWorkflowLoader)
  React.useEffect(() => {
    const handlePlanRestored = () => {
      // Use a short delay so nodes/edges are updated first
      setTimeout(() => {
        resetDirtyState(nodesRef.current, edgesRef.current);
      }, 100);
    };

    window.addEventListener('workflowPlanRestore', handlePlanRestored);
    return () => {
      window.removeEventListener('workflowPlanRestore', handlePlanRestored);
    };
  }, [resetDirtyState, nodesRef, edgesRef]);

  // Save and update workflow name - used by breadcrumb edit.
  // Generates plan, saves with name, notifies parent.
  const saveWithName = React.useCallback(async (name: string) => {
    if (!workflowId) return;

    try {
      const plan = generateWorkflowPlan(nodesRef.current, edgesRef.current, layoutDirectionRef.current);
      await saveWorkflowPlan(workflowId, { ...plan, name });

      setWorkflowNameState(name);
      onWorkflowLoaded?.({ name, id: workflowId });

      window.dispatchEvent(new CustomEvent('workflowViewSaveComplete', {
        detail: { success: true, workflowId }
      }));
    } catch (error) {
      console.error('Error saving workflow:', error);
      window.dispatchEvent(new CustomEvent('workflowViewSaveComplete', {
        detail: { success: false, workflowId, error: String(error) }
      }));
    }
  }, [workflowId, saveWorkflowPlan, onWorkflowLoaded]);

  // Expose save function to parent via ref
  React.useEffect(() => {
    if (saveRef && workflowId) {
      saveRef.current = async () => {
        await saveWithName(workflowNameState);
      };
    }
    return () => { if (saveRef) saveRef.current = null; };
  }, [saveRef, workflowId, saveWithName, workflowNameState]);

  // Listen for workflow name changes from breadcrumb edit
  React.useEffect(() => {
    if (!workflowId) return;

    const handleNameChange = async (event: CustomEvent) => {
      const { workflowId: eventWorkflowId, newName } = event.detail;
      if (eventWorkflowId !== workflowId || !newName) return;
      await saveWithName(newName);
    };

    window.addEventListener('workflowNameChangeFromBreadcrumb', handleNameChange as EventListener);
    return () => window.removeEventListener('workflowNameChangeFromBreadcrumb', handleNameChange as EventListener);
  }, [workflowId, saveWithName]);

  // Clear backend validation errors when leaving run mode
  React.useEffect(() => {
    if (!isRunMode) {
      setBackendValidationErrors([]);
    }
  }, [isRunMode, setBackendValidationErrors]);

  // Reset statuses when switching from run mode to edit mode
  // Skip in readOnly/previewOnly mode - mode is locked and should never switch
  const prevIsRunModeRef = React.useRef(isRunMode);
  React.useEffect(() => {
    if (isPreviewOnly) {
      prevIsRunModeRef.current = isRunMode;
      return;
    }
    if (prevIsRunModeRef.current === true && isRunMode === false) {
      pauseResumeActions.reset();
      setContextRunId(null);

      // Reset node statuses
      const resetNodes = nodesRef.current.map((node) => ({
        ...node,
        data: {
          ...node.data,
          status: undefined,
          statusCounts: undefined,
          currentIteration: undefined,
          maxIterations: undefined,
        },
      }));
      setNodes(resetNodes);
      nodesRef.current = resetNodes;

      // Reset edge statuses
      const resetEdges = edgesRef.current.map((edge) => ({
        ...edge,
        data: { ...edge.data, status: undefined, statusCounts: undefined },
      }));
      setEdges(resetEdges);
      edgesRef.current = resetEdges;

      // Reset dirty state - auto-save before execution already persisted the plan
      resetDirtyState(resetNodes, resetEdges);
    }
    prevIsRunModeRef.current = isRunMode;
  }, [isRunMode, isPreviewOnly, setNodes, setEdges, pauseResumeActions, setContextRunId, resetDirtyState]);

  // Graph operation hooks
  const { handleSelectionChange, onNodesChange } = useSelection({
    selectedNodeIds,
    setSelectedNodeIds,
    setNodes,
    onNodesChangeBase,
    setIsAdvancedMode,
    setSelectedLoopChild: () => {}, // No-op: loop child selection removed
    isNodeCreatorOpen,
    nodes,
  });

  // Approval review: a click on a pending item row (UserApprovalNode) selects
  // that node + opens the advanced inspector; the hook also clears the target
  // when the selection moves elsewhere or the builder unmounts.
  const handleApprovalReviewSelect = React.useCallback((nodeId: string) => {
    handleSelectionChange([nodeId]);
  }, [handleSelectionChange]);
  const handleApprovalReviewAdvanced = React.useCallback(() => {
    setIsAdvancedMode(true);
  }, []);
  useApprovalReviewSelection({
    selectedNodeIds,
    onSelectNode: handleApprovalReviewSelect,
    onEnterAdvancedMode: handleApprovalReviewAdvanced,
  });

  const {
    handleDeleteEdge,
    handleDeleteNode,
    handleDuplicateNode,
    handleConnect,
    handleCreateNode,
    handleNodeUpdate,
  } = useGraphOperations(
    nodes,
    setNodes,
    edges,
    setEdges,
    setSelectedNodeIds,
    reactFlowConnectionType,
    setHoveredEdgeId,
  );

  // Raw-graph operations for the canvas right-click menu + keyboard shortcuts.
  const contextMenuActions = useCanvasContextMenuActions({
    nodes,
    edges,
    selectedNodeIds,
    setNodes,
    setEdges,
    setSelectedNodeIds,
    onCreateNode: handleCreateNode,
  });

  const { undo, redo, canUndo, canRedo } = useHistory(nodes, edges, setNodes, setEdges);

  const { preparedNodes, preparedEdges, selectedNode } = usePreparedGraph(
    nodes,
    edges,
    selectedNodeIds,
    reactFlowConnectionType,
    {
      handleDeleteNode,
      handleDuplicateNode,
      handleTogglePreview,
      handleNodeUpdate,
      previewModeNodes,
      onCreateNode: handleCreateNode,
      onConnect: handleConnect,
    },
  );

  // Publish preparedNodes to the module-level canvas store for external consumers (icons).
  // We do NOT also overwrite `nodesRef.current` here - the upstream effect at line ~191
  // already syncs `nodesRef.current = nodes` (raw nodes) on every nodes change. Writing
  // `preparedNodes` here would race with that effect and leak runtime callbacks (added by
  // usePreparedGraph) into consumers that expect the raw plan shape.
  React.useEffect(() => {
    setCanvasNodes(preparedNodes);
  }, [preparedNodes]);

  // UI handlers
  const handleNodeDoubleClick = React.useCallback((nodeId: string) => {
    setSelectedNodeIds([nodeId]);
    setIsAdvancedMode(true);
  }, []);

  const handleDeselectAll = React.useCallback(() => {
    setSelectedNodeIds([]);
    setIsAdvancedMode(false);
    setIsFullscreenMode(false);
  }, []);

  // Close node creator when in advanced/fullscreen mode
  React.useEffect(() => {
    if (isAdvancedMode || isFullscreenMode) {
      setIsNodeCreatorOpen(false);
    }
  }, [isAdvancedMode, isFullscreenMode]);

  // Handle node selection from NodeCreatorPanel
  const handleNodeCreatorSelect = React.useCallback((nodeIdOrData: any) => {
    const itemData = typeof nodeIdOrData === 'object' && nodeIdOrData !== null
      ? nodeIdOrData
      : getPaletteItemDataFromId(nodeIdOrData as string);

    if (!itemData) return;

    const currentPendingConnection = pendingHoverConnectionRef.current;
    const targetPosition = calculateNodePosition(
      currentPendingConnection,
      nodeCreationCounterRef.current,
      layoutDirection,
    );

    handleCreateNode(itemData, targetPosition);
    nodeCreationCounterRef.current += 1;

    // If there was a pending connection, create the edge
    if (currentPendingConnection) {
      setTimeout(() => {
        const newNodes = nodesRef.current;
        const newNode = newNodes[newNodes.length - 1];

        if (newNode) {
          const { handleType, nodeId, handleId } = currentPendingConnection;

          // Decide by handle ROLE, not geometric side, so a vertical "+" (source on
          // bottom, target on top) connects the same way a horizontal one does. The
          // old 'right'/'top'/'left' switch fell through to 'left' (backward) for any
          // vertical position. The former 'top' branch (fleet agent-tool wiring via
          // `source-bottom-tools`) was dead here: this manager runs on the DAG builder
          // only, which never rendered a top source handle.
          if (handleType === 'source') {
            // "+" on the source → the existing node feeds the new node.
            handleConnect({
              source: nodeId,
              target: newNode.id,
              sourceHandle: handleId,
              targetHandle: null,
            });
          } else {
            // "+" on the target → the new node feeds the existing node.
            let sourceHandle = 'source-right'; // logical id, geometry-independent
            if (nodeRegistry.isDecisionLikeNode(newNode)) {
              sourceHandle = `${newNode.id}-if`;
            }
            handleConnect({
              source: newNode.id,
              target: nodeId,
              sourceHandle: sourceHandle,
              targetHandle: resolveInsertedTargetHandle(handleId),
            });
          }
          // A node was just ADDED through the hover "+" (a pending connection was
          // present), so the graph should re-tidy. This fires ONLY here - not for a
          // drag-drop, a plain node click, or a toolbox/context-menu add, which never
          // set a pending connection. BuilderCanvas listens and runs auto-layout.
          window.dispatchEvent(new CustomEvent('hoverPlusNodeInserted'));
        }
      }, 50);
    }

    window.dispatchEvent(new CustomEvent('workflowNodeCreated'));
  }, [handleCreateNode, handleConnect, nodesRef]);

  return (
    <StepByStepProvider
      isEnabled={pauseResumeState.mode === 'step_by_step'}
      isPaused={pauseResumeState.isPaused}
      isRunTerminal={!!runState?.runStatus && TERMINAL_STATUSES.has(runState.runStatus)}
      readySteps={pauseResumeState.readySteps}
      completedSteps={pauseResumeState.completedSteps}
      failedSteps={pauseResumeState.failedSteps}
      skippedSteps={pauseResumeState.skippedSteps}
      runningSteps={pauseResumeState.runningSteps}
      awaitingSignalSteps={pauseResumeState.awaitingSignalSteps}
      evaluatedCores={pauseResumeState.evaluatedCores}
      stepStates={pauseResumeState.stepStates}
      nodeIdToStepId={pauseResumeState.nodeIdToStepId}
      lastDecisionResult={pauseResumeState.lastDecisionResult}
      onExecuteStep={handleExecuteStep}
      onExecuteCore={handleExecuteControlNode}
      onRerunStep={handleRerunStep}
      onResolveApproval={handleResolveApproval}
      isRerunning={pauseResumeState.isRerunning}
      pendingSignals={pauseResumeState.pendingSignals}
      activeEpochs={pauseResumeState.activeEpochs}
    >
      <ValidationProvider nodes={nodes} edges={edges} backendErrors={backendValidationErrors}>
        <div className="h-full w-full relative">
          {/* Syncing indicator */}
          {isSyncingPlan && (
            <div className="absolute top-3 left-1/2 -translate-x-1/2 z-50 pointer-events-none">
              <div className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-500/90 text-white text-xs font-medium rounded-full shadow-lg animate-pulse">
                <svg className="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
                Syncing...
              </div>
            </div>
          )}

          <BuilderCanvas
            nodes={preparedNodes}
            edges={preparedEdges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={handleConnect}
            onCreateNode={handleCreateNode}
            onSelectionChange={handleSelectionChange}
            onNodeDoubleClick={handleNodeDoubleClick}
            onDeselectAll={handleDeselectAll}
            hoveredEdgeId={hoveredEdgeId}
            onHoverEdge={setHoveredEdgeId}
            onDeleteEdge={handleDeleteEdge}
            onOpenNodeCreator={() => setIsNodeCreatorOpen(true)}
            isNodeCreatorOpen={isNodeCreatorOpen}
            hasSelectedNodes={selectedNodeIds.length > 0}
            isFullscreen={isFullscreenMode}
            isAdvancedMode={isAdvancedMode}
            readonly={!!effectiveRunId || isPreviewOnly}
            onForceNodesUpdate={setNodes}
            onForceEdgesUpdate={setEdges}
            onUndo={undo}
            onRedo={redo}
            canUndo={canUndo}
            canRedo={canRedo}
            inspectorConnectionType={inspectorConnectionType}
            reactFlowConnectionType={reactFlowConnectionType}
            onInspectorConnectionTypeChange={handleConnectionTypeChange}
            onReactFlowConnectionTypeChange={handleConnectionTypeChange}
            selectedNodeIdsFromParent={selectedNodeIds}
            workflowId={workflowId}
            workflowName={workflowNameState}
            runId={effectiveRunId}
            onSaveWorkflow={workflowId
              ? (effectiveRunId ? saveRunPlan : saveWorkflowPlan)
              : undefined}
            isLoadingWorkflow={isLoadingWorkflow}
            loadError={loadError}
            onRetryLoad={retryLoad}
            onSettingsOpenChange={onSettingsOpenChange}
            pendingHoverConnectionRef={pendingHoverConnectionRef}
            contextMenuActions={contextMenuActions}
            inspectorPanel={(
              <InspectorPanel
                node={selectedNode}
                selectedNodeIds={selectedNodeIds}
                onUpdate={isPreviewOnly ? () => {} : handleNodeUpdate}
                onClose={() => {
                  handleSelectionChange([]);
                  setIsAdvancedMode(false);
                  setIsFullscreenMode(false);
                }}
                isAdvanced={isAdvancedMode}
                onAdvancedChange={setIsAdvancedMode}
                isFullscreen={isFullscreenMode}
                onFullscreenChange={setIsFullscreenMode}
                onDeleteNode={isPreviewOnly ? undefined : handleDeleteNode}
                onDuplicateNode={isPreviewOnly ? undefined : handleDuplicateNode}
                onUndo={isPreviewOnly ? undefined : undo}
                canUndo={isPreviewOnly ? false : canUndo}
                connectionType={inspectorConnectionType}
                onConnectionTypeChange={handleConnectionTypeChange}
                allNodes={preparedNodes}
                edges={edges}
                runId={effectiveRunId}
                workflowId={workflowId}
                onSelectNode={(nodeIdOrLoopId) => {
                  const nodeId = typeof nodeIdOrLoopId === 'string' ? nodeIdOrLoopId : nodeIdOrLoopId?.id || '';
                  handleSelectionChange([nodeId]);
                }}
                webhookTokens={webhookTokens}
                isMinimized={isInspectorMinimized}
                onMinimizedChange={setIsInspectorMinimized}
              />
            )}
          >
            {!isFullscreenMode && !isPreviewOnly && (
              <NodeCreatorPanel
                isOpen={isNodeCreatorOpen}
                onClose={() => setIsNodeCreatorOpen(false)}
                onSelectNode={handleNodeCreatorSelect}
                currentWorkflowId={workflowId}
              />
            )}

            {!isFullscreenMode && isRunMode && (
              <WorkflowRunsHistoryPanel
                isOpen={isRunsHistoryOpen || false}
                onClose={onCloseRunsHistory || (() => {})}
                workflowId={workflowId}
                currentRunId={effectiveRunId}
              />
            )}
          </BuilderCanvas>

          {/* Execution error toasts */}
          {toasts.length > 0 && (
            <div className="fixed top-4 right-4 z-[9999] flex flex-col gap-2">
              {toasts.map(toast => (
                <Toast key={toast.id} {...toast} onClose={removeToast} />
              ))}
            </div>
          )}

        </div>
      </ValidationProvider>
    </StepByStepProvider>
  );
}
