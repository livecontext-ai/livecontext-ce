'use client';

import * as React from 'react';
import {
  useWorkflowRunContext,
  type RunState,
} from '@/contexts/WorkflowRunContext';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type {
  WorkflowRunState,
  StepState,
  ExecutionMode,
  CoreExecutionResponse,
  StepRerunResponse,
  TriggerInfo,
} from '@/lib/api';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import { executionService } from '@/lib/api/orchestrator/execution.service';

export type WorkflowExecutionMode = 'automatic' | 'step_by_step' | 'paused';

export interface PauseResumeState {
  mode: WorkflowExecutionMode;
  isPaused: boolean;
  isWaitingForTrigger: boolean;
  isLoading: boolean;
  readySteps: Set<string>;
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps: Set<string>;
  runningSteps: Set<string>;
  awaitingSignalSteps: Set<string>;
  stepStates: Map<string, StepState>;
  nodeIdToStepId: Map<string, string>;
  runState: WorkflowRunState | null;
  error: string | null;
  evaluatedCores: Set<string>;
  lastDecisionResult: CoreExecutionResponse | null;
  currentEpoch: number;
  /** Active epochs that haven't been closed yet (for SBS parallel epoch support). */
  activeEpochs: number[];
  lastRerunResult: StepRerunResponse | null;
  isRerunning: boolean;
  // Pending signals (for pending signal count on approval nodes)
  pendingSignals: PendingSignal[];
  // Multi-DAG trigger support
  availableTriggers: TriggerInfo[];
  selectedTriggerId: string | null;
  isLoadingTriggers: boolean;
}

export interface PauseResumeActions {
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  cancel: () => Promise<void>;
  executeStep: (stepId: string, epoch?: number) => Promise<void>;
  executeCore: (coreId: string) => Promise<CoreExecutionResponse | null>;
  refreshState: () => Promise<void>;
  setMode: (mode: WorkflowExecutionMode) => void;
  setExecutionMode: (mode: ExecutionMode) => Promise<void>;
  startStepByStepMode: () => Promise<void>;
  canExecuteStep: (stepId: string) => boolean;
  canExecuteCore: (coreId: string) => boolean;
  isCore: (nodeId: string) => boolean;
  reset: () => void;
  updateReadySteps: (readyStepsArray: string[]) => void;
  rerunStep: (stepId: string) => Promise<StepRerunResponse | null>;
  canRerunStep: (stepId: string) => boolean;
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  // Multi-DAG trigger support
  loadAvailableTriggers: () => Promise<void>;
  selectTrigger: (triggerId: string) => void;
  hasMultipleTriggers: () => boolean;
}

/**
 * Convert RunState from singleton to PauseResumeState for backwards compatibility.
 */
function convertToPauseResumeState(
  runState: RunState | null,
  localMode: WorkflowExecutionMode,
  availableTriggers: TriggerInfo[],
  selectedTriggerId: string | null,
  isLoadingTriggers: boolean
): PauseResumeState {
  if (!runState) {
    return {
      mode: localMode,
      isPaused: false,
      isWaitingForTrigger: false,
      isLoading: false,
      readySteps: new Set(),
      completedSteps: new Set(),
      failedSteps: new Set(),
      skippedSteps: new Set(),
      runningSteps: new Set(),
      awaitingSignalSteps: new Set(),
      stepStates: new Map(),
      nodeIdToStepId: new Map(),
      runState: null,
      error: null,
      evaluatedCores: new Set(),
      lastDecisionResult: null,
      currentEpoch: 0,
      activeEpochs: [],
      lastRerunResult: null,
      isRerunning: false,
      pendingSignals: [],
      availableTriggers,
      selectedTriggerId,
      isLoadingTriggers,
    };
  }

  // Determine mode from backend execution mode (authoritative source of truth).
  // localMode is NOT used here to avoid stale state after mode toggles.
  let mode: WorkflowExecutionMode;
  if (runState.executionMode === 'step_by_step') {
    mode = 'step_by_step';
  } else if (runState.runStatus === 'paused') {
    mode = 'paused';
  } else {
    mode = 'automatic';
  }

  // Terminal runs should not have interactive elements (no play buttons on nodes)
  const isTerminalRun = TERMINAL_STATUSES.has(runState.runStatus);

  // Check if triggers are back in readySteps (cyclic workflow re-trigger after completion)
  const hasTriggerReady = Array.from(runState.readySteps).some(s => s.startsWith('trigger:'));

  // In step-by-step mode with non-empty readySteps, the backend explicitly set
  // these steps as ready (via streaming readySteps event or API). Trust them even if
  // runStatus is terminal - a stale batch-update may have set a "failed" status
  // during signal resolution while the workflow is actually still active.
  const isStepByStepWithReady = runState.executionMode === 'step_by_step' && runState.readySteps.size > 0;

  const isPaused = (!isTerminalRun || isStepByStepWithReady) && (
                   runState.runStatus === 'paused' ||
                   runState.runStatus === 'waiting_trigger' ||
                   (runState.executionMode === 'step_by_step' && runState.readySteps.size > 0));

  // For terminal runs, only keep trigger steps in readySteps (allow re-trigger)
  // but clear non-trigger steps (no play buttons on nodes).
  // Exception: in step-by-step mode with readySteps, keep ALL readySteps.
  const terminalReadySteps = isStepByStepWithReady
    ? runState.readySteps
    : isTerminalRun && hasTriggerReady
      ? new Set(Array.from(runState.readySteps).filter(s => s.startsWith('trigger:')))
      : isTerminalRun ? new Set<string>() : runState.readySteps;

  return {
    mode,
    isPaused,
    // isWaitingForTrigger is true when a trigger is available:
    // - initial state: runStatus === 'waiting_trigger'
    // - post-cycle: runStatus is completed/failed but trigger is back in readySteps
    isWaitingForTrigger: runState.runStatus === 'waiting_trigger' || hasTriggerReady,
    isLoading: runState.isLoading,
    readySteps: terminalReadySteps,
    completedSteps: runState.completedSteps,
    failedSteps: runState.failedSteps,
    skippedSteps: runState.skippedSteps,
    runningSteps: runState.runningSteps,
    awaitingSignalSteps: runState.awaitingSignalSteps,
    stepStates: runState.stepStates,
    nodeIdToStepId: runState.nodeIdToStepId,
    runState: runState.rawRunState,
    error: runState.error,
    evaluatedCores: runState.evaluatedCores,
    lastDecisionResult: null, // Not stored in singleton (transient)
    currentEpoch: runState.currentEpoch,
    activeEpochs: runState.activeEpochs,
    lastRerunResult: null, // Not stored in singleton (transient)
    isRerunning: runState.isRerunning,
    pendingSignals: runState.pendingSignals,
    availableTriggers,
    selectedTriggerId,
    isLoadingTriggers,
  };
}

/**
 * Hook for managing workflow pause/resume state.
 * This is a wrapper around the unified WorkflowRunContext singleton.
 */
export function useWorkflowPauseResume(
  runId: string | null,
  onStateUpdate?: (state: WorkflowRunState) => void,
  getCurrentPlan?: () => Record<string, unknown> | null
): [PauseResumeState, PauseResumeActions] {
  // Get context directly - NO subscription here
  // Parent (WorkflowBuilder) handles the subscription
  const context = useWorkflowRunContext();
  const runState = runId ? context.getState(runId) : null;
  const { viewingEpoch } = useWorkflowMode();

  // Local state for mode (to allow setMode without backend call)
  const [localMode, setLocalMode] = React.useState<WorkflowExecutionMode>('automatic');

  // Track last decision and rerun results (transient, not in singleton)
  const [lastDecisionResult, setLastDecisionResult] = React.useState<CoreExecutionResponse | null>(null);
  const [lastRerunResult, setLastRerunResult] = React.useState<StepRerunResponse | null>(null);

  // Multi-DAG trigger state
  const [availableTriggers, setAvailableTriggers] = React.useState<TriggerInfo[]>([]);
  const [selectedTriggerId, setSelectedTriggerId] = React.useState<string | null>(null);
  const [isLoadingTriggers, setIsLoadingTriggers] = React.useState(false);
  const [triggersLoadedForRunId, setTriggersLoadedForRunId] = React.useState<string | null>(null);

  // Set up plan getter callback for step-by-step parameter persistence
  React.useEffect(() => {
    if (runId && context && getCurrentPlan) {
      context.setCurrentPlanGetter(runId, getCurrentPlan);
    }
    return () => {
      if (runId && context) {
        context.setCurrentPlanGetter(runId, null);
      }
    };
  }, [runId, context, getCurrentPlan]);

  // Call onStateUpdate when runState changes
  const onStateUpdateRef = React.useRef(onStateUpdate);
  React.useEffect(() => {
    onStateUpdateRef.current = onStateUpdate;
  }, [onStateUpdate]);

  React.useEffect(() => {
    if (runState?.rawRunState && onStateUpdateRef.current) {
      onStateUpdateRef.current(runState.rawRunState);
    }
  }, [runState?.rawRunState]);

  // Listen for SBS readySteps dispatched with explicit runId (from useWorkflowExecution)
  React.useEffect(() => {
    if (!context) return;

    const handleSbsReadySteps = (event: CustomEvent<{ runId: string; readySteps: string[] }>) => {
      const { runId: targetRunId, readySteps } = event.detail;
      if (targetRunId && readySteps) {
        context.updateReadySteps(targetRunId, readySteps);
      }
    };

    window.addEventListener('workflowSbsReadySteps', handleSbsReadySteps as EventListener);
    return () => {
      window.removeEventListener('workflowSbsReadySteps', handleSbsReadySteps as EventListener);
    };
  }, [context]);

  // Listen for chat trigger message event
  React.useEffect(() => {
    if (!runId || !context) return;

    const handleChatTriggerMessage = (event: CustomEvent<{ nodeId: string; message: string; triggeredAt: string }>) => {
      context.setChatTriggerInput(runId, {
        message: event.detail.message,
        triggeredAt: event.detail.triggeredAt,
      });
    };

    window.addEventListener('workflowChatTriggerMessage', handleChatTriggerMessage as EventListener);
    return () => {
      window.removeEventListener('workflowChatTriggerMessage', handleChatTriggerMessage as EventListener);
    };
  }, [runId, context]);

  // Convert state for backwards compatibility
  const state = React.useMemo(() => {
    const converted = convertToPauseResumeState(
      runState,
      localMode,
      availableTriggers,
      selectedTriggerId,
      isLoadingTriggers
    );
    // Inject transient state
    converted.lastDecisionResult = lastDecisionResult;
    converted.lastRerunResult = lastRerunResult;

    // Epoch-aware ready step filtering for SBS parallel epochs.
    // When viewing a specific epoch, only show that epoch's ready steps.
    if (viewingEpoch !== null && runState?.epochReadySteps) {
      const epochKey = String(viewingEpoch);
      const epochSteps = runState.epochReadySteps[epochKey];
      if (epochSteps) {
        // Intersect with terminalReadySteps to preserve terminal filtering logic
        const epochSet = new Set(epochSteps);
        converted.readySteps = new Set(
          Array.from(converted.readySteps).filter(s => epochSet.has(s))
        );
      } else {
        // Viewing an epoch with no ready steps (closed or not yet started)
        // Keep triggers ready (they're always available) but clear non-trigger steps
        converted.readySteps = new Set(
          Array.from(converted.readySteps).filter(s => s.startsWith('trigger:'))
        );
      }
    }

    return converted;
  }, [runState, localMode, lastDecisionResult, lastRerunResult, availableTriggers, selectedTriggerId, isLoadingTriggers, viewingEpoch]);

  // Actions
  const pause = React.useCallback(async () => {
    if (!runId || !context) return;
    await context.pauseRun(runId);
  }, [runId, context]);

  const resume = React.useCallback(async () => {
    if (!runId || !context) return;
    await context.resumeRun(runId);
  }, [runId, context]);

  const cancel = React.useCallback(async () => {
    if (!runId || !context) return;
    await context.cancelRun(runId);
  }, [runId, context]);

  const executeStep = React.useCallback(async (stepId: string, epoch?: number) => {
    if (!runId || !context) return;
    await context.executeStep(runId, stepId, undefined, undefined, epoch);
  }, [runId, context]);

  const executeCore = React.useCallback(async (coreId: string): Promise<CoreExecutionResponse | null> => {
    if (!runId || !context) return null;
    const result = await context.executeCore(runId, coreId);
    if (result) {
      setLastDecisionResult(result);
    }
    return result;
  }, [runId, context]);

  const refreshState = React.useCallback(async () => {
    if (!runId || !context) return;
    await context.refreshState(runId);
  }, [runId, context]);

  const setMode = React.useCallback((mode: WorkflowExecutionMode) => {
    setLocalMode(mode);
  }, []);

  const setExecutionMode = React.useCallback(async (mode: ExecutionMode) => {
    if (!runId || !context) return;
    await context.setExecutionMode(runId, mode as 'automatic' | 'step_by_step');
  }, [runId, context]);

  const startStepByStepMode = React.useCallback(async () => {
    if (!runId || !context) return;
    await context.setExecutionMode(runId, 'step_by_step');
    setLocalMode('step_by_step');
  }, [runId, context]);

  const canExecuteStep = React.useCallback((stepId: string): boolean => {
    if (!runId || !context) return false;
    return context.canExecuteStep(runId, stepId);
  }, [runId, context]);

  const canExecuteCore = React.useCallback((coreId: string): boolean => {
    if (!runId || !context) return false;
    return context.canExecuteCore(runId, coreId);
  }, [runId, context]);

  const isCore = React.useCallback((nodeId: string): boolean => {
    if (!context) return false;
    return context.isCore(nodeId);
  }, [context]);

  const reset = React.useCallback(() => {
    if (!runId || !context) return;
    context.resetRun(runId);
    setLocalMode('automatic');
    setLastDecisionResult(null);
    setLastRerunResult(null);
    // Reset trigger state
    setAvailableTriggers([]);
    setSelectedTriggerId(null);
    setIsLoadingTriggers(false);
    setTriggersLoadedForRunId(null);
  }, [runId, context]);

  const updateReadySteps = React.useCallback((readyStepsArray: string[]) => {
    if (!runId || !context) return;
    context.updateReadySteps(runId, readyStepsArray);
  }, [runId, context]);

  const rerunStep = React.useCallback(async (stepId: string): Promise<StepRerunResponse | null> => {
    if (!runId || !context) return null;
    const result = await context.rerunStep(runId, stepId);
    if (result) {
      setLastRerunResult(result);
    }
    return result;
  }, [runId, context]);

  const canRerunStep = React.useCallback((stepId: string): boolean => {
    if (!runId || !context) return false;
    return context.canRerunStep(runId, stepId);
  }, [runId, context]);

  const resolveApproval = React.useCallback(async (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
    if (!runId || !context) return;
    await context.resolveApproval(runId, nodeId, resolution, epoch, itemId);
  }, [runId, context]);

  // Multi-DAG trigger actions
  const loadAvailableTriggers = React.useCallback(async () => {
    if (!runId) return;
    setIsLoadingTriggers(true);
    try {
      console.log('[useWorkflowPauseResume] Loading available triggers for runId:', runId);
      const triggers = await executionService.getAvailableTriggers(runId);
      console.log('[useWorkflowPauseResume] API returned triggers:', triggers);
      console.log('[useWorkflowPauseResume] Triggers count:', triggers.length);
      triggers.forEach((t, i) => {
        console.log(`[useWorkflowPauseResume] Trigger[${i}]:`, t.triggerId, t.label, t.type);
      });
      setAvailableTriggers(triggers);
      setTriggersLoadedForRunId(runId);  // Track which runId these triggers are for
      // Auto-select if only one trigger
      if (triggers.length === 1) {
        setSelectedTriggerId(triggers[0].triggerId);
      }
    } catch (error) {
      console.error('[useWorkflowPauseResume] Failed to load triggers:', error);
      setAvailableTriggers([]);
      setTriggersLoadedForRunId(runId);  // Mark as loaded even on error to prevent infinite retry
    } finally {
      setIsLoadingTriggers(false);
    }
  }, [runId]);

  const selectTrigger = React.useCallback((triggerId: string) => {
    setSelectedTriggerId(triggerId);
  }, []);

  const hasMultipleTriggers = React.useCallback((): boolean => {
    return availableTriggers.length > 1;
  }, [availableTriggers]);

  // Auto-load triggers when run has triggers available.
  // This covers: initial waiting_trigger state AND post-cycle completed/failed states
  // where the trigger is back in readySteps for the next cycle.
  const hasTriggerInReadySteps = React.useMemo(() => {
    if (!runState?.readySteps) return false;
    return Array.from(runState.readySteps).some(s => s.startsWith('trigger:'));
  }, [runState?.readySteps]);

  const shouldLoadTriggers = runState?.runStatus === 'waiting_trigger' || hasTriggerInReadySteps;

  // Track whether triggers were loaded in a terminal state (completed/failed/stopped)
  // so we can reload when the run transitions to a terminal state with triggers available
  const triggersLoadedInTerminalRef = React.useRef(false);

  React.useEffect(() => {
    if (runId && shouldLoadTriggers && !isLoadingTriggers) {
      const isTerminal = !!runState?.runStatus && TERMINAL_STATUSES.has(runState.runStatus);

      // Reload if: runId changed, or triggers not loaded yet, or run just reached terminal state with triggers
      const needsLoad = triggersLoadedForRunId !== runId ||
                        (isTerminal && !triggersLoadedInTerminalRef.current);

      if (needsLoad) {
        if (isTerminal) triggersLoadedInTerminalRef.current = true;
        console.log('[useWorkflowPauseResume] Loading triggers for runId:', runId, 'status:', runState?.runStatus);
        loadAvailableTriggers();
      }
    }
    // Reset terminal flag when runId changes
    if (triggersLoadedForRunId !== runId) {
      triggersLoadedInTerminalRef.current = false;
    }
  }, [runId, shouldLoadTriggers, isLoadingTriggers, triggersLoadedForRunId, loadAvailableTriggers, runState?.runStatus]);

  const actions: PauseResumeActions = React.useMemo(() => ({
    pause,
    resume,
    cancel,
    executeStep,
    executeCore,
    refreshState,
    setMode,
    setExecutionMode,
    startStepByStepMode,
    canExecuteStep,
    canExecuteCore,
    isCore,
    reset,
    updateReadySteps,
    rerunStep,
    canRerunStep,
    resolveApproval,
    loadAvailableTriggers,
    selectTrigger,
    hasMultipleTriggers,
  }), [
    pause, resume, cancel, executeStep, executeCore, refreshState,
    setMode, setExecutionMode, startStepByStepMode,
    canExecuteStep, canExecuteCore, isCore,
    reset, updateReadySteps, rerunStep, canRerunStep, resolveApproval,
    loadAvailableTriggers, selectTrigger, hasMultipleTriggers
  ]);

  return [state, actions];
}
