'use client';

import * as React from 'react';
import type { WorkflowExecutionMode } from './useWorkflowPauseResume';

interface PauseResumeActions {
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  reset: () => void;
  setMode: (mode: WorkflowExecutionMode) => void;
  setExecutionMode: (mode: 'automatic' | 'step_by_step') => Promise<void>;
  executeStep: (stepId: string, epoch?: number) => Promise<void>;
  executeCore: (coreId: string) => Promise<any>;
  rerunStep: (stepId: string) => Promise<any>;
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
}

interface PauseResumeState {
  mode: WorkflowExecutionMode;
  isPaused: boolean;
}

export interface ExecutionError {
  type: 'epoch_limit' | 'queue_timeout' | 'generic';
  message: string;
}

interface UseStepByStepHandlersOptions {
  pauseResumeState: PauseResumeState;
  pauseResumeActions: PauseResumeActions;
  onExecutionError?: (error: ExecutionError) => void;
}

interface UseStepByStepHandlersReturn {
  handlePauseWorkflow: () => Promise<void>;
  handleResumeWorkflow: () => Promise<void>;
  handleResetWorkflow: () => void;
  handleToggleStepByStep: () => void;
  handleExecuteStep: (stepId: string, epoch?: number) => Promise<void>;
  handleExecuteControlNode: (coreId: string) => Promise<any>;
  handleRerunStep: (stepId: string) => Promise<any>;
  handleResolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  setWorkflowStatus: React.Dispatch<React.SetStateAction<'cancelled' | 'running' | 'paused' | 'completed' | 'failed'>>;
  workflowStatus: 'cancelled' | 'running' | 'paused' | 'completed' | 'failed';
}

/**
 * Hook to manage step-by-step execution handlers
 * Consolidates all pause/resume/step-by-step execution logic
 */
export function useStepByStepHandlers({
  pauseResumeState,
  pauseResumeActions,
  onExecutionError,
}: UseStepByStepHandlersOptions): UseStepByStepHandlersReturn {
  const [workflowStatus, setWorkflowStatus] = React.useState<'cancelled' | 'running' | 'paused' | 'completed' | 'failed'>('cancelled');

  // Pause/Resume handlers
  const handlePauseWorkflow = React.useCallback(async () => {
    try {
      await pauseResumeActions.pause();
      setWorkflowStatus('paused');
    } catch (err) {
      console.error('[StepByStep] Failed to pause workflow:', err);
    }
  }, [pauseResumeActions]);

  const handleResumeWorkflow = React.useCallback(async () => {
    try {
      await pauseResumeActions.resume();
      setWorkflowStatus('running');
    } catch (err) {
      console.error('[StepByStep] Failed to resume workflow:', err);
    }
  }, [pauseResumeActions]);

  const handleResetWorkflow = React.useCallback(() => {
    setWorkflowStatus('cancelled');
    pauseResumeActions.setMode('automatic');
  }, [pauseResumeActions]);

  const handleToggleStepByStep = React.useCallback(async () => {
    const newMode: WorkflowExecutionMode = pauseResumeState.mode === 'step_by_step' ? 'automatic' : 'step_by_step';
    // Persist to backend so trigger execution respects the mode
    try {
      await pauseResumeActions.setExecutionMode(newMode);
    } catch (err) {
      console.error('[StepByStep] Failed to persist execution mode:', err);
    }
    pauseResumeActions.setMode(newMode);
  }, [pauseResumeState.mode, pauseResumeActions]);

  // Execute a single step (for step-by-step mode)
  const handleExecuteStep = React.useCallback(async (stepId: string, epoch?: number) => {
    try {
      console.log('[StepByStep] Executing step:', stepId, 'epoch:', epoch);
      await pauseResumeActions.executeStep(stepId, epoch);
      console.log('[StepByStep] Step executed, waiting for next user action');
    } catch (err: any) {
      console.error('[StepByStep] Failed to execute step:', err);
      if (onExecutionError) {
        const msg = err?.message || String(err);
        if (msg.includes('Max concurrent epochs')) {
          onExecutionError({ type: 'epoch_limit', message: msg });
        } else if (msg.includes('queue timeout')) {
          onExecutionError({ type: 'queue_timeout', message: msg });
        } else {
          onExecutionError({ type: 'generic', message: msg });
        }
      }
    }
  }, [pauseResumeActions, onExecutionError]);

  // Execute a core node (decision) - for step-by-step mode
  const handleExecuteControlNode = React.useCallback(async (coreId: string) => {
    try {
      console.log('[StepByStep] Executing control node:', coreId);
      return await pauseResumeActions.executeCore(coreId);
    } catch (err) {
      console.error('[StepByStep] Failed to execute control node:', err);
      return null;
    }
  }, [pauseResumeActions]);

  // Re-run a step (and reset all downstream steps) - for step-by-step mode
  const handleRerunStep = React.useCallback(async (stepId: string) => {
    try {
      console.log('[StepByStep] Re-running step:', stepId);
      return await pauseResumeActions.rerunStep(stepId);
    } catch (err) {
      console.error('[StepByStep] Failed to re-run step:', err);
      return null;
    }
  }, [pauseResumeActions]);

  // Resolve a user approval signal (approve or reject)
  const handleResolveApproval = React.useCallback(async (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
    try {
      console.log('[StepByStep] Resolving approval:', nodeId, resolution, 'epoch:', epoch, 'itemId:', itemId);
      await pauseResumeActions.resolveApproval(nodeId, resolution, epoch, itemId);
    } catch (err) {
      console.error('[StepByStep] Failed to resolve approval:', err);
    }
  }, [pauseResumeActions]);

  return {
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
  };
}
