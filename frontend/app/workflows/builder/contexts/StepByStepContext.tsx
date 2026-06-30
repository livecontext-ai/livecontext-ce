'use client';

import * as React from 'react';
import type { WorkflowRunState, StepState, CoreExecutionResponse, StepRerunResponse } from '@/lib/api';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import { normalizeLabel } from '../utils/labelNormalizer';
import { getPrefixForKind } from '../registry/nodeRegistry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

export interface StepByStepContextValue {
  // Mode
  isStepByStepMode: boolean;
  isPaused: boolean;

  // State
  readySteps: Set<string>;
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps: Set<string>;
  runningSteps: Set<string>;
  awaitingSignalSteps: Set<string>;
  evaluatedCores: Set<string>;

  // Step state details
  getStepState: (stepId: string) => StepState | undefined;
  /** Resolve a React Flow node ID to its backend step ID using the backend-provided mapping */
  resolveNodeId: (nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }) => string;

  // Actions
  executeStep: (stepId: string, epoch?: number) => Promise<void>;
  executeCore: (coreId: string) => Promise<CoreExecutionResponse | null>;
  canExecuteStep: (stepId: string) => boolean;
  canExecuteCore: (coreId: string) => boolean;
  isCore: (nodeId: string) => boolean;

  // Re-run actions
  rerunStep: (stepId: string) => Promise<StepRerunResponse | null>;
  canRerunStep: (stepId: string) => boolean;
  isRerunning: boolean;

  // Approval actions
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  getPendingSignalCount: (nodeId: string) => number;
  getPendingSignalsForNode: (nodeId: string) => PendingSignal[];
  /** ALL pending USER_APPROVAL signals across every node (run-wide queue). */
  getAllPendingSignals: () => PendingSignal[];

  // Loading
  isExecutingStep: string | null;

  // Last decision result (for UI display)
  lastDecisionResult: CoreExecutionResponse | null;

  // Epoch data for parallel SBS support
  activeEpochs: number[];
}

const StepByStepContext = React.createContext<StepByStepContextValue | null>(null);

interface StepByStepProviderProps {
  children: React.ReactNode;
  isEnabled: boolean;
  isPaused: boolean;
  isRunTerminal?: boolean;
  readySteps: Set<string>;
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps?: Set<string>;
  runningSteps?: Set<string>;
  awaitingSignalSteps?: Set<string>;
  evaluatedCores?: Set<string>;
  stepStates?: Map<string, StepState>;
  /** Backend-provided mapping: React Flow node ID → backend step ID */
  nodeIdToStepId?: Map<string, string>;
  lastDecisionResult?: CoreExecutionResponse | null;
  onExecuteStep: (stepId: string, epoch?: number) => Promise<void>;
  onExecuteCore?: (coreId: string) => Promise<CoreExecutionResponse | null>;
  // Re-run support
  onRerunStep?: (stepId: string) => Promise<StepRerunResponse | null>;
  isRerunning?: boolean;
  // Approval support
  onResolveApproval?: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  pendingSignals?: PendingSignal[];
  // Epoch data for parallel SBS support
  activeEpochs?: number[];
}

export function StepByStepProvider({
  children,
  isEnabled,
  isPaused,
  isRunTerminal = false,
  readySteps,
  completedSteps,
  failedSteps,
  skippedSteps = new Set(),
  runningSteps = new Set(),
  awaitingSignalSteps = new Set(),
  evaluatedCores = new Set(),
  stepStates = new Map(),
  nodeIdToStepId = new Map(),
  lastDecisionResult = null,
  onExecuteStep,
  onExecuteCore,
  onRerunStep,
  isRerunning = false,
  onResolveApproval,
  pendingSignals = [],
  activeEpochs = [],
}: StepByStepProviderProps) {
  const [executingStep, setExecutingStep] = React.useState<string | null>(null);

  const executeStep = React.useCallback(async (stepId: string, epoch?: number) => {
    setExecutingStep(stepId);
    try {
      await onExecuteStep(stepId, epoch);
    } finally {
      setExecutingStep(null); // Clear when API returns (step processed)
    }
  }, [onExecuteStep]);

  const executeCore = React.useCallback(async (coreId: string): Promise<CoreExecutionResponse | null> => {
    if (!onExecuteCore) return null;
    setExecutingStep(coreId);
    try {
      return await onExecuteCore(coreId);
    } finally {
      setExecutingStep(null); // Clear when API returns (step processed)
    }
  }, [onExecuteCore]);

  const canExecuteStep = React.useCallback((stepId: string): boolean => {
    // Terminal runs (CANCELLED, COMPLETED, FAILED, TIMEOUT) - no interaction allowed
    if (isRunTerminal) return false;

    // In automatic mode, manual/chat triggers can still be executed when ready (WAITING_TRIGGER state)
    // They need user interaction to start the workflow
    const isTriggerNode = stepId.startsWith('trigger:');
    if (isTriggerNode && readySteps.has(stepId)) {
      return true; // Manual/chat triggers are always executable when ready
    }

    // For other steps, require step-by-step mode or paused state
    if (!isEnabled && !isPaused) return false;

    // Allow all ready steps including core nodes (decisions/switches).
    // Core nodes now route through the V2 engine on the backend,
    // getting the same execution pipeline as regular steps.
    return readySteps.has(stepId);
  }, [isEnabled, isPaused, isRunTerminal, readySteps]);

  const canExecuteCore = React.useCallback((coreId: string): boolean => {
    if (isRunTerminal) return false;
    if (!isEnabled && !isPaused) return false;
    return readySteps.has(coreId) && !evaluatedCores.has(coreId);
  }, [isEnabled, isPaused, isRunTerminal, readySteps, evaluatedCores]);

  const isCore = React.useCallback((nodeId: string): boolean => {
    return isCoreNodeId(nodeId);
  }, []);

  const getStepState = React.useCallback((stepId: string): StepState | undefined => {
    return stepStates.get(stepId);
  }, [stepStates]);

  // Resolve React Flow node ID → backend step ID using backend-provided mapping.
  // Falls back to computeBackendStepId only if the mapping doesn't have the ID yet.
  const resolveNodeId = React.useCallback((nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }): string => {
    const mapped = nodeIdToStepId.get(nodeId);
    if (mapped) return mapped;
    // Fallback for nodes not yet in the mapping (e.g. before first execution)
    return nodeData ? computeBackendStepId(nodeId, nodeData) : normalizeNodeId(nodeId);
  }, [nodeIdToStepId]);

  // Re-run a step (and reset all downstream steps)
  // For triggers: rerun = selective reset (same as other nodes), NOT re-fire.
  // This resets the trigger and all downstream to READY, showing PLAY buttons again.
  // The user then clicks PLAY to actually fire the trigger (new epoch).
  const rerunStep = React.useCallback(async (stepId: string): Promise<StepRerunResponse | null> => {
    if (!onRerunStep) return null;
    return await onRerunStep(stepId);
  }, [onRerunStep]);

  // Check if a step can be re-run (must be COMPLETED, FAILED, or RUNNING)
  // NOTE: SKIPPED steps cannot be retried (branch wasn't taken - retry from decision instead)
  // RUNNING check is kept for while loops and long-running agents.
  // NOTE: In the simplified split system, split completes immediately after spawning items,
  // so split nodes will be in completedSteps, not runningSteps.
  // Triggers use the same rerun logic as other nodes (selective reset).
  const canRerunStep = React.useCallback((stepId: string): boolean => {
    if (isRunTerminal) return false;
    if (!isEnabled) return false;
    return completedSteps.has(stepId) || failedSteps.has(stepId) || runningSteps.has(stepId);
  }, [isEnabled, isRunTerminal, completedSteps, failedSteps, runningSteps]);

  // Resolve a user approval signal
  const resolveApproval = React.useCallback(async (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
    if (!onResolveApproval) return;
    setExecutingStep(nodeId);
    try {
      await onResolveApproval(nodeId, resolution, epoch, itemId);
    } finally {
      setExecutingStep(null); // Clear when API returns (approval processed)
    }
  }, [onResolveApproval]);

  // Count pending USER_APPROVAL signals for a specific node
  const getPendingSignalCount = React.useCallback((nodeId: string): number => {
    return pendingSignals.filter(
      s => s.nodeId === nodeId && s.signalType === 'USER_APPROVAL'
    ).length;
  }, [pendingSignals]);

  // Get pending signals for a specific node (for per-item approval UI)
  const getPendingSignalsForNode = React.useCallback((nodeId: string): PendingSignal[] => {
    return pendingSignals.filter(
      s => s.nodeId === nodeId && s.signalType === 'USER_APPROVAL'
    );
  }, [pendingSignals]);

  // ALL pending USER_APPROVAL signals across every node - feeds the run-wide
  // approval queue so the ApprovalReviewBar can navigate between approvals that
  // belong to different nodes, not just items of the inspected one.
  const getAllPendingSignals = React.useCallback((): PendingSignal[] => {
    return pendingSignals.filter(s => s.signalType === 'USER_APPROVAL');
  }, [pendingSignals]);

  // Clear executingStep when backend confirms status change via batch-update
  React.useEffect(() => {
    if (!executingStep) return;
    const isConfirmed =
      completedSteps.has(executingStep) ||
      failedSteps.has(executingStep) ||
      runningSteps.has(executingStep) ||
      skippedSteps.has(executingStep) ||
      awaitingSignalSteps.has(executingStep);
    if (isConfirmed) {
      setExecutingStep(null);
    }
  }, [executingStep, completedSteps, failedSteps, runningSteps, skippedSteps, awaitingSignalSteps]);

  const value: StepByStepContextValue = React.useMemo(() => ({
    isStepByStepMode: isEnabled && !isRunTerminal,
    isPaused,
    readySteps,
    completedSteps,
    failedSteps,
    skippedSteps,
    runningSteps,
    awaitingSignalSteps,
    evaluatedCores,
    getStepState,
    resolveNodeId,
    executeStep,
    executeCore,
    canExecuteStep,
    canExecuteCore,
    isCore,
    rerunStep,
    canRerunStep,
    isRerunning,
    resolveApproval,
    getPendingSignalCount,
    getPendingSignalsForNode,
    getAllPendingSignals,
    isExecutingStep: executingStep,
    lastDecisionResult,
    activeEpochs,
  }), [
    isEnabled,
    isRunTerminal,
    isPaused,
    readySteps,
    completedSteps,
    failedSteps,
    skippedSteps,
    runningSteps,
    awaitingSignalSteps,
    evaluatedCores,
    getStepState,
    resolveNodeId,
    executeStep,
    executeCore,
    canExecuteStep,
    canExecuteCore,
    isCore,
    rerunStep,
    canRerunStep,
    isRerunning,
    resolveApproval,
    getPendingSignalCount,
    getPendingSignalsForNode,
    getAllPendingSignals,
    executingStep,
    lastDecisionResult,
    activeEpochs,
  ]);

  return (
    <StepByStepContext.Provider value={value}>
      {children}
    </StepByStepContext.Provider>
  );
}

export function useStepByStep(): StepByStepContextValue | null {
  return React.useContext(StepByStepContext);
}

/**
 * Hook to get execution status for a specific node (step or core node)
 * @param nodeId - The frontend node ID
 * @param nodeData - Optional node data containing label and kind for accurate backend ID mapping
 */
export function useNodeExecutionStatus(nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }) {
  const ctx = useStepByStep();
  const { viewingEpoch } = useWorkflowMode();

  // Interactive ONLY in "All epochs" view (viewingEpoch == null).
  // When viewing any specific epoch (active or historical), all buttons are hidden -
  // users must return to "All" view to interact (fire triggers, rerun, play steps).
  const isInteractive = viewingEpoch == null;

  if (!ctx) {
    return {
      isStepByStepMode: false,
      canExecute: false,
      isReady: false,
      isReadyRaw: false,
      isCompleted: false,
      isFailed: false,
      isSkipped: false,
      isRunning: false,
      isAwaitingSignal: false,
      isExecuting: false,
      isCore: false,
      isEvaluated: false,
      executeStep: async () => {},
      fireFromAnyEpoch: async () => {},
      executeCore: async () => null,
      // Re-run
      canRerun: false,
      isRerunning: false,
      rerunStep: async () => null,
      // Approval
      resolveApproval: async () => {},
      pendingSignalCount: 0,
      pendingSignals: [],
    };
  }

  // Resolve React Flow node ID → backend step ID.
  // Primary: backend-provided mapping (nodeIdToStepId). Fallback: computeBackendStepId.
  const normalizedId = ctx.resolveNodeId(nodeId, nodeData);
  const isControl = ctx.isCore(normalizedId);

  // SSE sets are the PRIMARY source - they update in real-time during streaming.
  // Backend stepStates (REST) may be stale during SSE streaming.
  // deriveNodeStatus() handles priority: running > failed > skipped > completed > ready > pending
  const isRunning = ctx.runningSteps.has(normalizedId);
  const isFailed = ctx.failedSteps.has(normalizedId);
  const isSkipped = ctx.skippedSteps.has(normalizedId);
  const isCompleted = ctx.completedSteps.has(normalizedId);
  const isReady = ctx.readySteps.has(normalizedId);
  const isAwaitingSignal = ctx.awaitingSignalSteps.has(normalizedId);

  return {
    // Only true if explicitly in step-by-step mode AND interactive.
    // Historical epoch viewing disables all controls - epoch data determines visuals.
    isStepByStepMode: ctx.isStepByStepMode && isInteractive,
    canExecute: isInteractive && (isControl ? ctx.canExecuteCore(normalizedId) : ctx.canExecuteStep(normalizedId)),
    isReady: isInteractive && isReady,
    // Raw readiness, ignoring the focus-epoch interactive gate. Lets the focus
    // view show a trigger's play button (which returns to all-epochs + fires
    // that trigger on click) even though normal controls are hidden in focus.
    isReadyRaw: isReady,
    isCompleted,
    isFailed,
    isSkipped,
    isRunning,
    isAwaitingSignal,
    isExecuting: isInteractive && ctx.isExecutingStep === normalizedId,
    isCore: isControl,
    isEvaluated: ctx.evaluatedCores.has(normalizedId),
    executeStep: () => ctx.executeStep(normalizedId, viewingEpoch ?? undefined),
    // Fire this step against ALL-epochs (epoch=undefined) regardless of the
    // currently-viewed epoch - used by the focus-epoch trigger play after it
    // returns to the all-epochs view, so the clicked trigger actually fires.
    fireFromAnyEpoch: () => ctx.executeStep(normalizedId, undefined),
    executeCore: () => ctx.executeCore(normalizedId),
    // Re-run: available for COMPLETED, FAILED steps (only in live view)
    // For triggers: rerun = fire again (new epoch), determined by canRerunStep using backend state
    canRerun: isInteractive && ctx.canRerunStep(normalizedId),
    isRerunning: ctx.isRerunning,
    rerunStep: () => ctx.rerunStep(normalizedId),
    // Approval - epochOverride lets per-signal UIs (item rows, ApprovalReviewBar)
    // target the signal's OWN epoch: in 'All epochs' view viewingEpoch is null,
    // which would otherwise leave the backend to guess when several epochs are pending.
    resolveApproval: (resolution: 'APPROVED' | 'REJECTED', itemId?: string, epochOverride?: number) =>
      ctx.resolveApproval(normalizedId, resolution, epochOverride ?? viewingEpoch ?? undefined, itemId),
    pendingSignalCount: ctx.getPendingSignalCount(normalizedId),
    pendingSignals: ctx.getPendingSignalsForNode(normalizedId),
  };
}

/**
 * Check if an ID is a core node (decision or switch ONLY)
 * Core nodes require special handling in step-by-step mode (evaluated, not executed)
 *
 * IMPORTANT: Loop and Split are NOT core nodes - they are executed like regular steps
 */
/**
 * Check if an ID is a core node.
 * Core nodes use the unified core: prefix.
 */
function isCoreNodeId(nodeId: string): boolean {
  return nodeId.startsWith('core:');
}

/**
 * Normalize node ID to match backend step IDs.
 * This is a fallback when nodeData is not available.
 *
 * === SIMPLIFIED PREFIX SYSTEM (4 categories) ===
 *
 * | Prefix     | Category | Applies To                                              |
 * |------------|----------|--------------------------------------------------------|
 * | trigger:   | Entry    | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:       | Action   | Tools, CRUD (external API calls)                        |
 * | agent:     | AI       | Agent, Guardrail, Classify                              |
 * | core:      | Control  | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Stop, Response, Download File, HTTP Request, Data Input, User Approval |
 */
function normalizeNodeId(nodeId: string): string {
  // Already has a valid prefix - return as is
  if (nodeId.startsWith('trigger:') ||
      nodeId.startsWith('mcp:') ||
      nodeId.startsWith('agent:') ||
      nodeId.startsWith('core:') ||
      nodeId.startsWith('table:') ||
      nodeId.startsWith('interface:')) {
    return nodeId;
  }

  // Extract label and normalize it
  const label = nodeId.replace(/-\d+$/, ''); // Remove trailing numbers like "-123"
  const normalized = normalizeLabel(label) || label;

  // Determine prefix based on node ID pattern
  // Triggers - must check before 'agent' since 'tables-trigger' doesn't contain 'agent'
  if (nodeId.startsWith('trigger-') || nodeId.startsWith('trigger:') || nodeId.startsWith('tables-trigger-')) {
    return `trigger:${normalized}`;
  }

  // CRUD table nodes - check before generic patterns to avoid false matches
  if (nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') ||
      nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-') ||
      nodeId.startsWith('table-')) {
    return `table:${normalized}`;
  }

  // Control flow nodes (decision, switch, loop, split, merge, transform, wait, fork, exit, response, download_file, http_request, data_input)
  if (nodeId.includes('if-else') || nodeId.includes('decision') || nodeId.includes('switch') ||
      nodeId.includes('loop') || nodeId.includes('while') ||
      nodeId.includes('split') ||
      nodeId.includes('merge') || nodeId.includes('transform') ||
      nodeId.includes('wait') || nodeId.includes('fork') ||
      nodeId.includes('exit') || nodeId.includes('response') ||
      nodeId.includes('download_file') || nodeId.includes('download-file') ||
      nodeId.includes('http_request') || nodeId.includes('http-request') ||
      nodeId.includes('data_input') || nodeId.includes('data-input')) {
    return `core:${normalized}`;
  }

  // Agent nodes
  if (nodeId.includes('ai-agent') || nodeId.startsWith('agent-') || nodeId.startsWith('agent:')) {
    return `agent:${normalized}`;
  }

  // Interface nodes
  if (nodeId.startsWith('interface-') || nodeId.startsWith('interface:')) {
    return `interface:${normalized}`;
  }

  // Default: mcp (tool call)
  return `mcp:${normalized}`;
}

/**
 * Compute the backend step ID from node data
 * Used for mapping frontend node IDs to backend step IDs
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix     | Category  | Applies To                                              |
 * |------------|-----------|--------------------------------------------------------|
 * | trigger:   | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:       | MCP       | Tools (MCP tool calls)                                  |
 * | table:     | Table     | CRUD operations (database tables)                       |
 * | agent:     | AI        | Agent, Guardrail, Classify                              |
 * | core:      | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Stop, Response, Download File, HTTP Request, Data Input, User Approval |
 * | note:      | Note      | Notes                                                   |
 * | interface: | Interface | Interfaces                                              |
 */
export function computeBackendStepId(nodeId: string, nodeData?: { label?: string; kind?: string; crudOperation?: string }): string {
  if (nodeData?.label) {
    const normalizedLabelValue = normalizeLabel(nodeData.label);

    if (normalizedLabelValue) {
      const kind = nodeData.kind;

      // CRUD/table nodes have kind='action' but crudOperation identifies them as table: prefix
      if (nodeData.crudOperation) return `table:${normalizedLabelValue}`;

      // Lookup prefix from nodeRegistry - single source of truth, zero config needed for new nodes
      if (kind) {
        const prefix = getPrefixForKind(kind);
        if (prefix) return `${prefix}:${normalizedLabelValue}`;
      }

      // Fallback for nodes without kind - derive from nodeId pattern (use startsWith for precision)
      if (nodeId.startsWith('while-') || nodeId.startsWith('while:')) return `core:${normalizedLabelValue}`;
      if (nodeId.startsWith('trigger-') || nodeId.startsWith('trigger:') || nodeId.startsWith('tables-trigger-')) return `trigger:${normalizedLabelValue}`;
      if (nodeId.startsWith('agent-') || nodeId.startsWith('ai-agent-') || nodeId.startsWith('agent:')) return `agent:${normalizedLabelValue}`;
      if (nodeId.startsWith('interface-') || nodeId.startsWith('interface:')) return `interface:${normalizedLabelValue}`;
      if (nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') ||
          nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-') ||
          nodeId.startsWith('table-')) return `table:${normalizedLabelValue}`;
      return `mcp:${normalizedLabelValue}`;
    }
  }

  return normalizeNodeId(nodeId);
}
