'use client';

/**
 * WorkflowRunContext - React Context wrapper for WorkflowRunManager.
 *
 * This is a simplified context that delegates all logic to WorkflowRunManager.
 * It only provides:
 * 1. React context for dependency injection
 * 2. Hooks for easy consumption
 * 3. Backward compatibility with existing components
 *
 * All business logic is in WorkflowRunManager (SOLID architecture).
 */

import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import {
  WorkflowRunManager,
  getWorkflowRunManager,
  deleteWorkflowRunManager,
  type RunState,
  type StepExecutionResult,
  type CoreExecutionResult,
  type RerunResult,
} from './workflow-run';
import { streamDebug } from './workflow-run/streamingDebug';
// Re-export types for backward compatibility
export type { RunState };
export type RunStatus = RunState['runStatus'];
export type TriggerType = RunState['triggerType'];
export type ExecutionMode = RunState['executionMode'];
export type WorkflowRunNodeState = RunState['nodes'][0];

export interface WorkflowRunStatusState {
  status: RunStatus;
  progress?: number;
  message?: string;
  stepsCompleted?: number;
  totalSteps?: number;
  durationMs?: number;
}

// ============================================================================
// CONTEXT API (Backward Compatible)
// ============================================================================

interface WorkflowRunContextValue {
  // Subscription
  subscribeToRun: (runId: string, onUpdate: () => void) => () => void;
  getState: (runId: string) => RunState | null;

  // Lifecycle
  initRun: (runId: string) => Promise<void>;
  handleBatchUpdate: (data: any) => void;
  handleEvent: (eventType: string, data: any) => void;
  refreshState: (runId: string) => Promise<void>;
  resetRun: (runId: string) => void;

  // Actions
  executeStep: (runId: string, stepId: string, payload?: Record<string, unknown>, triggerTypeOverride?: string, epoch?: number) => Promise<StepExecutionResult | undefined>;
  executeCore: (runId: string, coreId: string) => Promise<any>;
  pauseRun: (runId: string) => Promise<void>;
  resumeRun: (runId: string) => Promise<void>;
  cancelRun: (runId: string) => Promise<void>;
  hardCancelRun: (runId: string) => Promise<void>;
  reactivateRun: (runId: string) => Promise<void>;
  setExecutionMode: (runId: string, mode: 'automatic' | 'step_by_step') => Promise<void>;
  rerunStep: (runId: string, stepId: string) => Promise<any>;
  resolveApproval: (runId: string, nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  updateReadySteps: (runId: string, readyStepsArray: string[]) => void;
  updateStatus: (runId: string, status: WorkflowRunStatusState) => void;
  setConnected: (runId: string, connected: boolean) => void;

  // Checks
  canExecuteStep: (runId: string, stepId: string) => boolean;
  canExecuteCore: (runId: string, coreId: string) => boolean;
  canRerunStep: (runId: string, stepId: string) => boolean;
  isCore: (nodeId: string) => boolean;

  // Configuration
  setChatTriggerInput: (runId: string, input: { message: string; triggeredAt: string }) => void;
  setCurrentPlanGetter: (runId: string, callback: (() => Record<string, unknown> | null) | null) => void;

  // Polling (for backward compatibility)
  startPolling: (runId: string) => void;
  stopPolling: (runId: string) => void;
}

const WorkflowRunContext = createContext<WorkflowRunContextValue | null>(null);

// ============================================================================
// PROVIDER
// ============================================================================

export function WorkflowRunProvider({ children }: { children: React.ReactNode }) {
  // Track chat trigger inputs separately (not part of core state)
  const chatTriggerInputs = useMemo(() => new Map<string, { message: string; triggeredAt: string }>(), []);

  // Get or create manager for a runId
  const getManager = useCallback((runId: string): WorkflowRunManager => {
    return getWorkflowRunManager(runId);
  }, []);

  // 2026-05-04 hot-fix (audit MEGA R0 + R0-bis):
  // The Phase E visibility-suspend listener was REMOVED.
  //
  // The original design called `forEachWorkflowRunManager((m) => m.suspend())`
  // when the tab returned visible after >=5min hidden. The intent: free
  // pending timers + in-flight requests for a stale tab. The bug:
  // `suspend()` set `initialized=false` on ALL managers (not just hidden
  // ones - module-global iteration), but `useChannel` stayed active and
  // the component never unmounted, so `wakeUp()` was never called by any
  // caller (verified: 0 call sites for `wakeUp` outside the class).
  // Result: managers stuck in frozen state, multi-tab users seeing
  // "events temps réel s'arrêtent" because the active tab's run got
  // suspended when ANOTHER tab came back visible.
  //
  // Right behavior: the browser's own WS-reconnect logic + the manager's
  // existing seq guard handle stale state correctly. Hard refresh remains
  // available for users who want a fresh REST fetch after long inactivity.
  //
  // Aligned with feedback_simplicity_first.md - no speculative abstraction.

  // Context API implementation
  const value: WorkflowRunContextValue = useMemo(() => ({
    // Subscription
    subscribeToRun: (runId: string, onUpdate: () => void) => {
      const manager = getManager(runId);
      return manager.subscribe(() => onUpdate());
    },

    getState: (runId: string) => {
      try {
        const manager = getManager(runId);
        return manager.getState() as RunState;
      } catch {
        return null;
      }
    },

    // Lifecycle
    initRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.initialize();
    },

    handleBatchUpdate: (data: any) => {
      // Route batch-update to the correct manager based on the runId in the data
      const runId = data?.runId;
      if (!runId) return;
      try {
        const manager = getManager(runId);
        manager.handleBatchUpdate(data);
      } catch {
        // Manager not initialized yet - ignore
      }
    },

    handleEvent: (eventType: string, data: any) => {
      // Route individual events (readySteps, decisionEvaluated, workflowStatus)
      // to the correct manager based on the runId in the data
      const runId = data?.runId;
      if (!runId) return;
      try {
        const manager = getManager(runId);
        manager.handleEvent(eventType, data);
      } catch {
        // Manager not initialized yet - ignore
      }
    },

    refreshState: async (runId: string) => {
      // Force refresh state from API (unlike initialize which is idempotent)
      const manager = getManager(runId);
      await manager.refresh();
    },

    resetRun: (runId: string) => {
      deleteWorkflowRunManager(runId);
    },

    // Actions
    executeStep: async (runId: string, stepId: string, payload?: Record<string, unknown>, triggerTypeOverride?: string, epoch?: number) => {
      const manager = getManager(runId);
      // Return the manager's StepExecutionResult so callers can read
      // {readySteps} (e.g. TriggerPanel auto-refreshes its disable state).
      return await manager.executeStep(stepId, payload, triggerTypeOverride, epoch);
    },

    executeCore: async (runId: string, coreId: string) => {
      const manager = getManager(runId);
      return await manager.executeCore(coreId);
    },

    pauseRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.pause();
    },

    resumeRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.resume();
    },

    cancelRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.cancel();
    },

    hardCancelRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.hardCancel();
    },

    reactivateRun: async (runId: string) => {
      const manager = getManager(runId);
      await manager.reactivate();
    },

    setExecutionMode: async (runId: string, mode: 'automatic' | 'step_by_step') => {
      const manager = getManager(runId);
      await manager.setExecutionMode(mode);
    },

    rerunStep: async (runId: string, stepId: string) => {
      const manager = getManager(runId);
      return await manager.rerunStep(stepId);
    },

    resolveApproval: async (runId: string, nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
      const manager = getManager(runId);
      await manager.resolveApproval(nodeId, resolution, epoch, itemId);
    },

    updateReadySteps: (runId: string, readyStepsArray: string[]) => {
      const manager = getManager(runId);
      manager.setReadySteps(readyStepsArray);
    },

    updateStatus: (runId: string, status: WorkflowRunStatusState) => {
      const manager = getManager(runId);
      manager.setRunStatus(status.status);
    },

    setConnected: (runId: string, connected: boolean) => {
      const manager = getManager(runId);
      manager.setConnected(connected);
    },

    // Checks
    canExecuteStep: (runId: string, stepId: string) => {
      const manager = getManager(runId);
      return manager.canExecuteStep(stepId);
    },

    canExecuteCore: (runId: string, coreId: string) => {
      const manager = getManager(runId);
      const state = manager.getState();
      return state.readySteps.has(coreId) && !state.completedSteps.has(coreId);
    },

    canRerunStep: (runId: string, stepId: string) => {
      const manager = getManager(runId);
      return manager.canRerunStep(stepId);
    },

    isCore: (nodeId: string) => {
      return nodeId.startsWith('core:');
    },

    // Configuration
    setChatTriggerInput: (runId: string, input: { message: string; triggeredAt: string }) => {
      chatTriggerInputs.set(runId, input);
    },

    setCurrentPlanGetter: (runId: string, callback: (() => Record<string, unknown> | null) | null) => {
      const manager = getManager(runId);
      manager.setCurrentPlanGetter(callback);
    },

    // Polling (stub - handled internally by manager)
    startPolling: (_runId: string) => {
      streamDebug.log('WorkflowRunContext', 'startPolling called - handled internally');
    },

    stopPolling: (_runId: string) => {
      streamDebug.log('WorkflowRunContext', 'stopPolling called - handled internally');
    },
  }), [getManager, chatTriggerInputs]);

  return (
    <WorkflowRunContext.Provider value={value}>
      {children}
    </WorkflowRunContext.Provider>
  );
}

// ============================================================================
// HOOKS
// ============================================================================

/**
 * Hook to access the run context.
 */
export function useWorkflowRunContext() {
  const context = useContext(WorkflowRunContext);
  if (!context) {
    throw new Error('useWorkflowRunContext must be used within WorkflowRunProvider');
  }
  return context;
}

/**
 * Hook for components that want to observe a run's state.
 * Automatically initializes from REST.
 */
export function useRun(runId: string | undefined): [RunState | null, WorkflowRunContextValue | null] {
  const context = useContext(WorkflowRunContext);
  const [state, setState] = useState<RunState | null>(null);

  useEffect(() => {
    if (!runId || !context) {
      setState(null);
      return;
    }

    // Initialize
    context.initRun(runId);

    // Subscribe to updates
    let prevStatus: string | null = null;
    let prevBatchLen = 0;
    const unsubscribe = context.subscribeToRun(runId, () => {
      const currentState = context.getState(runId);
      const newStatus = currentState?.runStatus ?? null;
      const newBatchLen = currentState?.batchSteps?.length ?? 0;
      // Only log on meaningful changes (status or batch data)
      if (newStatus !== prevStatus || newBatchLen !== prevBatchLen) {
        streamDebug.log('useRun', 'State changed:', {
          runId,
          runStatus: newStatus,
          batchSteps: newBatchLen,
          batchEdges: currentState?.batchEdges?.length ?? 0,
          readySteps: currentState?.readySteps ? Array.from(currentState.readySteps) : [],
          completed: currentState?.completedSteps?.size ?? 0,
          running: currentState?.runningSteps?.size ?? 0,
        });
        prevStatus = newStatus;
        prevBatchLen = newBatchLen;
      }
      setState(currentState);
    });

    return unsubscribe;
  }, [runId, context]);

  return [state, context];
}

