'use client';

import * as React from 'react';
import { ApiError } from '@/lib/api/api-client';
import type { WorkflowExecutionMode } from './useWorkflowPauseResume';

interface PauseResumeActions {
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  reset: () => void;
  setMode: (mode: WorkflowExecutionMode) => void;
  setExecutionMode: (mode: 'automatic' | 'step_by_step') => Promise<void>;
  executeStep: (stepId: string, epoch?: number) => Promise<void>;
  executeCore: (coreId: string) => Promise<any>;
  rerunStep: (stepId: string, epoch?: number) => Promise<any>;
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
}

interface PauseResumeState {
  mode: WorkflowExecutionMode;
  isPaused: boolean;
}

export interface ExecutionError {
  type: 'epoch_limit' | 'queue_timeout' | 'rerun_refused' | 'read_only' | 'generic';
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
  handleRerunStep: (stepId: string, epoch?: number) => Promise<any>;
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

  // Re-run a step (and reset all downstream steps) - available in both execution modes.
  //
  // A refused rerun is REPORTED, through the same toast channel a refused step execution
  // uses. The backend refuses on state it alone can see (an epoch that has since been reopened
  // elsewhere, a sibling fire that started between render and click) and answers with a
  // sentence saying which; swallowing it into a console line left the user clicking a button
  // that did nothing and said nothing.
  const handleRerunStep = React.useCallback(async (stepId: string, epoch?: number) => {
    try {
      console.log('[StepByStep] Re-running step:', stepId, 'epoch:', epoch);
      return await pauseResumeActions.rerunStep(stepId, epoch);
    } catch (err: any) {
      console.error('[StepByStep] Failed to re-run step:', err);
      // A REFUSAL and a fault are different news, and only the status tells them apart.
      //
      // A refusal (the backend declined this restart: the run has not settled, the step has no
      // result in that epoch, the run was stopped) gets its own type, because its sentences are
      // written for the MCP agent - they name tool calls and pass raw run ids - and showing one
      // verbatim would put `workflow(action='get_run', run_id='<uuid>')` in front of a builder
      // user, in English, in a product where every visible string goes through next-intl.
      //
      // Anything else (500, gateway, offline) is a fault, and dressing it as a refusal would
      // name a cause nobody checked. Those keep the generic path, which shows the message
      // as-is. The 400 bucket is deliberately broad (it also carries "run not found" and
      // "step not found", which no amount of waiting fixes), so the refusal copy makes its
      // retry advice CONDITIONAL on the run still going rather than stating it flatly.
      // `instanceof`, not a duck-typed `.status`: a bare Response carries one too, and only
      // an ApiError means the backend actually answered with a refusal.
      const refused = err instanceof ApiError && (err.status === 409 || err.status === 400);
      // 403 = the workspace role is read-only (VIEWER): its own translated sentence, not the
      // backend's English and not the "try again later" refusal copy.
      const forbidden = err instanceof ApiError && err.status === 403;
      onExecutionError?.({
        type: forbidden ? 'read_only' : refused ? 'rerun_refused' : 'generic',
        message: err?.message || String(err),
      });
      return null;
    }
  }, [pauseResumeActions, onExecutionError]);

  // Resolve a user approval signal (approve or reject)
  const handleResolveApproval = React.useCallback(async (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => {
    try {
      console.log('[StepByStep] Resolving approval:', nodeId, resolution, 'epoch:', epoch, 'itemId:', itemId);
      await pauseResumeActions.resolveApproval(nodeId, resolution, epoch, itemId);
    } catch (err) {
      console.error('[StepByStep] Failed to resolve approval:', err);
      // A read-only role (VIEWER) is refused with 403: say so instead of a silent no-op.
      if (err instanceof ApiError && err.status === 403) {
        onExecutionError?.({ type: 'read_only', message: err.message });
      }
    }
  }, [pauseResumeActions, onExecutionError]);

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
