'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { AppRouterInstance } from 'next/dist/shared/lib/app-router-context.shared-runtime';
import type { BuilderNodeData } from '../types';
import { orchestratorApi, type TriggerTypeValue } from '@/lib/api';
import { is402Error, is413StorageError } from '@/lib/api/error-utils';
import { showInsufficientCreditsModal } from '@/components/billing/InsufficientCreditsModal';
import { showInsufficientStorageModal } from '@/components/billing/InsufficientStorageModal';
import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';
import { generateWorkflowPlan } from '../utils/workflowPlanGenerator';
import { reconcilePlanCredentials } from '@/lib/credentials/reconcilePlanCredentials';
import { normalizeLabel } from '../utils/labelNormalizer';
import type { WorkflowExecutionMode } from './useWorkflowPauseResume';
import { markRunAsJustExecuted } from './useWorkflowLoader';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

export interface ValidationError {
  elementKey?: string;
  message: string;
  context?: Record<string, unknown>;
}

export interface UseWorkflowExecutionConfig {
  workflowId?: string;
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  router: AppRouterInstance;
  setWorkflowStatus: (status: 'cancelled' | 'running' | 'paused' | 'completed' | 'failed') => void;
  pauseResumeActions: {
    setMode: (mode: WorkflowExecutionMode) => void;
    updateReadySteps: (steps: string[]) => void;
  };
  /** Optional callback to save workflow before execution (ensures webhookTokens exist).
   *  Returns the planJson string if save was successful, so the same plan can be reused for execution. */
  onSaveBeforeExecute?: () => Promise<string | undefined>;
}

export interface UseWorkflowExecutionResult {
  backendValidationErrors: ValidationError[];
  setBackendValidationErrors: React.Dispatch<React.SetStateAction<ValidationError[]>>;
  onValidate: () => Promise<void>;
}

/**
 * Derives a node key from various formats.
 */
function deriveNodeKey(raw: unknown): string | null {
  if (typeof raw !== 'string') return null;
  const trimmed = raw.trim();
  if (!trimmed) return null;

  // If already in XXX:label format, use as-is
  if (trimmed.match(/^(mcp|trigger|core|agent):[a-z0-9_]+$/)) {
    return trimmed;
  }

  // If it's just a label, we can't determine the type - return null
  return null;
}

/**
 * Converts backend error details to frontend ValidationError format.
 */
function convertBackendErrors(errorDetails: any[]): ValidationError[] {
  return errorDetails.map((err: any) => {
    const ctx = (err?.context || {}) as Record<string, unknown>;
    const baseMessage = err.message ?? err.type ?? 'Validation error';

    let elementKey: string | undefined;

    // Direct node hints
    const directCandidates: Array<unknown> = [
      ctx.nodeId,
      ctx.loopNodeId,
      ctx.stepId,
      ctx.nodeAlias,
      ctx.target,
      ctx.from,
      ctx.source,
      ctx.incomingFrom,
    ];

    for (const candidate of directCandidates) {
      const key = deriveNodeKey(candidate);
      if (key) {
        elementKey = key;
        break;
      }
    }

    // If no direct match, check impacted nodes
    if (!elementKey) {
      const impactedNodes = Array.isArray(ctx.impactedNodes) ? ctx.impactedNodes : [];
      for (const node of impactedNodes) {
        const key = deriveNodeKey(node);
        if (key) {
          elementKey = key;
          break;
        }
      }
    }

    // Check impacted aliases
    if (!elementKey) {
      const impactedAliases = Array.isArray(ctx.impactedNodeAliases) ? ctx.impactedNodeAliases : [];
      for (const alias of impactedAliases) {
        const key = deriveNodeKey(alias);
        if (key) {
          elementKey = key;
          break;
        }
      }
    }

    // For global errors (like "no_triggers"), use 'global' as elementKey
    if (!elementKey && (err.type === 'no_triggers' || err.type === 'no_steps' || err.type === 'no_edges')) {
      elementKey = 'global';
    }

    return {
      elementKey,
      message: baseMessage,
      context: ctx,
    };
  });
}

/**
 * Hook for managing workflow execution (start, validation, error handling).
 */
export function useWorkflowExecution(config: UseWorkflowExecutionConfig): UseWorkflowExecutionResult {
  const {
    workflowId,
    nodes,
    edges,
    router,
    setWorkflowStatus,
    pauseResumeActions,
    onSaveBeforeExecute,
  } = config;

  const { isPreviewOnly } = useWorkflowMode();
  const [backendValidationErrors, setBackendValidationErrors] = React.useState<ValidationError[]>([]);

  // Ref for pauseResumeActions to avoid dependency changes
  const pauseResumeActionsRef = React.useRef(pauseResumeActions);
  React.useEffect(() => {
    pauseResumeActionsRef.current = pauseResumeActions;
  }, [pauseResumeActions]);

  // Ref for onSaveBeforeExecute to avoid dependency changes
  const onSaveBeforeExecuteRef = React.useRef(onSaveBeforeExecute);
  React.useEffect(() => {
    onSaveBeforeExecuteRef.current = onSaveBeforeExecute;
  }, [onSaveBeforeExecute]);

  // ==================== Shared helpers ====================

  /**
   * Resolves the planJson by auto-saving first (single generateWorkflowPlan call)
   * or falling back to generating a new plan.
   */
  const resolvePlanJson = async (): Promise<{ planJson: string; plan: any }> => {
    let planJson: string | undefined;
    if (onSaveBeforeExecuteRef.current) {
      planJson = await onSaveBeforeExecuteRef.current();
    }
    if (!planJson) {
      const fallbackPlan = generateWorkflowPlan(nodes, edges);
      planJson = JSON.stringify(fallbackPlan);
    }
    let plan = JSON.parse(planJson);
    // A node may still pin a selectedCredentialId that points to a credential the
    // user has since deleted/reconnected. The backend resolves pinned credentials
    // strictly (no fallback) → such a step fails at execution with
    // `credentials_required`. Reconcile against the user's CURRENT credentials so
    // the run plan only ever carries live credential ids (or none → backend
    // resolves by the integration default). Fresh fetch: a run is deliberate and
    // the credential list may have changed since the last inspector open.
    try {
      const userCreds = await orchestratorApi.getAllCredentials();
      const reconciled = reconcilePlanCredentials(plan, userCreds);
      if (reconciled !== plan) {
        plan = reconciled;
        planJson = JSON.stringify(reconciled);
      }
    } catch (err) {
      // Non-fatal: fall back to the unreconciled plan (prior behavior).
      console.warn('[resolvePlanJson] Credential reconciliation skipped:', err);
    }
    return { planJson, plan };
  };

  /**
   * Navigates to run mode for a given runId.
   */
  const navigateToRunMode = (runId: string) => {
    if (!workflowId) return;
    markRunAsJustExecuted(runId);
    router.push(`/app/workflow/${workflowId}/run/${runId}`);
  };

  /**
   * Handles execution errors (validation errors from backend or network errors).
   */
  const handleExecutionError = (error: any, context: string) => {
    console.error(`${context}:`, error);

    // CE cloud-relay errors (insufficient cloud credit / unmanaged model) pop their own
    // actionable modal; routed before the generic 402 path (no-op in the Cloud edition).
    if (handleCeRelayError(error)) {
      return;
    }

    if (is402Error(error)) {
      showInsufficientCreditsModal();
      return;
    }

    if (is413StorageError(error)) {
      showInsufficientStorageModal();
      return;
    }

    if (error?.status === 400 && error?.details) {
      const errorDetails = error.details?.errorDetails || error.details?.errors || [];

      if (Array.isArray(errorDetails) && errorDetails.length > 0) {
        setBackendValidationErrors(convertBackendErrors(errorDetails));
      } else {
        const errorMessage = error.details?.message || error.message || 'Workflow execution failed';
        setBackendValidationErrors([{
          elementKey: 'global',
          message: errorMessage,
          context: {},
        }]);
      }
    } else {
      const errorMessage = error?.message || 'Failed to start workflow';
      setBackendValidationErrors([{
        elementKey: 'global',
        message: `Execution error: ${errorMessage}`,
        context: {},
      }]);
    }
  };

  // ==================== Validate ====================

  // Validate workflow plan on server
  const onValidate = React.useCallback(async () => {
    try {
      // Generate workflow plan
      const plan = generateWorkflowPlan(nodes, edges);
      const planJson = JSON.stringify(plan);

      const result = await orchestratorApi.validateWorkflow({
        planJson,
        dataInputs: { sample: 'data' },
      });

      // Convert backend errors to format expected by validation service
      const backendErrors = Array.isArray(result.errorDetails)
        ? convertBackendErrors(result.errorDetails)
        : [];

      // Update backend errors - ValidationContext will automatically
      // re-validate with these errors and dispatch events
      setBackendValidationErrors(backendErrors);
    } catch (error) {
      console.error(`[Validation] Network error during validation: ${String(error)}`);
      // Set network error as backend error - the hook will handle the rest
      setBackendValidationErrors([{
        elementKey: undefined,
        message: `Network error: ${String(error)}`,
        context: {},
      }]);
    }
  }, [nodes, edges]);

  // ==================== Normal execution ====================

  React.useEffect(() => {
    const handleStartEvent = async (event: CustomEvent) => {
      if (isPreviewOnly) {
        console.warn('Cannot start workflow: workflow is in preview-only mode');
        return;
      }
      if (!workflowId) {
        console.error('Cannot start workflow: workflowId is required');
        return;
      }

      const schedule = event.detail?.schedule || null;
      // Bottom-of-node Auto launcher passes the clicked trigger; the header run
      // button does not (and keeps its all-root-triggers behavior).
      const startFromNode: string | undefined = event.detail?.startFromNode;

      try {
        const { planJson, plan } = await resolvePlanJson();

        const data = await orchestratorApi.executeWorkflow({
          workflowId,
          planJson,
          dataInputs: {},
          schedule,
        });

        if (data && data.runId) {
          const backendStatus = (data.status || '').toLowerCase();
          const isWaitingTrigger = backendStatus === 'waiting_trigger';

          navigateToRunMode(data.runId);

          if (isWaitingTrigger) {
            // Dispatch readySteps so triggers show shimmer buttons immediately
            // (same pattern as step-by-step start path)
            const readySteps = (data as any).readySteps as string[] | undefined;
            if (readySteps && readySteps.length > 0) {
              window.dispatchEvent(new CustomEvent('workflowSbsReadySteps', {
                detail: { runId: data.runId, readySteps },
              }));
            }
            window.dispatchEvent(new CustomEvent('workflowWaitingTrigger', {
              detail: {
                workflowId,
                runId: data.runId,
                workflowName: plan.name || 'Workflow',
                webhookTokens: data.webhookTokens,
              }
            }));

            // Auto launcher (bottom of a trigger node): fire ONLY the clicked
            // trigger instead of leaving every root trigger waiting. Restricted to
            // no-payload trigger types - chat/form/webhook/workflow keep waiting for
            // their input. Fires via the same triggerSpecific path the run manager
            // uses, with the explicit new runId (no stale-runId race).
            if (startFromNode && readySteps && readySteps.length > 0) {
              const node = nodes.find(n => n.id === startFromNode);
              const nid = (node?.data?.id || node?.id || '') as string;
              // No-payload trigger types only; gate on entry kind so a non-trigger
              // node sharing a "*-trigger-*" id can't be misclassified.
              const triggerType: TriggerTypeValue | null = node?.data?.kind !== 'entry' ? null
                : (nid === 'manual-trigger' || nid.startsWith('manual-trigger-')) ? 'manual'
                : (nid === 'schedule-trigger' || nid.startsWith('schedule-trigger-')) ? 'schedule'
                : (nid === 'tables-trigger' || nid.startsWith('tables-trigger-')) ? 'datasource'
                : null;
              if (node && triggerType) {
                // Exact deterministic key only - no substring fallback, which could
                // match a sibling trigger whose key contains this one's.
                const triggerStepId = readySteps.find(s => s === `trigger:${normalizeLabel(node.data?.label || '')}`);
                if (triggerStepId) {
                  try {
                    await orchestratorApi.triggerSpecific(data.runId, triggerStepId, triggerType, undefined, plan);
                    setWorkflowStatus('running');
                  } catch (fireErr) {
                    console.error('[Auto launcher] Failed to fire selected trigger:', fireErr);
                  }
                }
              }
            }
          } else {
            setWorkflowStatus('running');
            window.dispatchEvent(new CustomEvent('workflowExecutionStarted', {
              detail: {
                workflowId,
                runId: data.runId,
                workflowName: plan.name || 'Workflow',
              }
            }));
          }
        } else {
          console.error('Failed to start workflow: no runId returned');
        }
      } catch (error: any) {
        handleExecutionError(error, 'Failed to start workflow');
      }
    };

    window.addEventListener('workflowViewStart', handleStartEvent as EventListener);

    return () => {
      window.removeEventListener('workflowViewStart', handleStartEvent as EventListener);
    };
  }, [workflowId, nodes, edges, router, setWorkflowStatus, isPreviewOnly]);

  // ==================== Step-by-step execution ====================

  React.useEffect(() => {
    const handleStepByStepStart = async (event: CustomEvent) => {
      if (isPreviewOnly) {
        console.warn('Cannot start workflow: workflow is in preview-only mode');
        return;
      }
      if (!workflowId) {
        console.error('Cannot start workflow: workflowId is required');
        return;
      }

      const startFromNode = event.detail?.startFromNode;

      try {
        const { planJson } = await resolvePlanJson();

        const data = await orchestratorApi.executeWorkflow({
          workflowId,
          planJson,
          dataInputs: {},
          schedule: null,
          executionMode: 'step_by_step',
        });

        if (data && data.runId) {
          setWorkflowStatus('running');
          pauseResumeActionsRef.current.setMode('step_by_step');
          navigateToRunMode(data.runId);

          // Determine initial readySteps for the new run
          let finalReadySteps = (data as any).readySteps as string[] | undefined;

          if (startFromNode && finalReadySteps && finalReadySteps.length > 0) {
            const clickedNode = nodes.find(n => n.id === startFromNode);
            if (clickedNode) {
              const isTriggerNode = clickedNode.data?.kind === 'entry';

              if (!isTriggerNode) {
                const nodeLabel = normalizeLabel(clickedNode.data?.label || '') || '';
                const backendStepId = finalReadySteps.find(stepId => stepId.includes(nodeLabel));

                if (backendStepId) {
                  try {
                    const execResult = await orchestratorApi.executeSingleStepInStepByStepMode(data.runId, backendStepId);
                    if (execResult.readySteps && Array.isArray(execResult.readySteps)) {
                      finalReadySteps = execResult.readySteps;
                    }
                  } catch (execError) {
                    console.error('[StepByStep] Failed to execute first step:', execError);
                  }
                }
              }
            }
          }

          // Dispatch readySteps with the NEW runId - pauseResumeActions.updateReadySteps
          // uses a stale runId closure (URL hasn't navigated yet), so we use an event instead.
          if (finalReadySteps && finalReadySteps.length > 0) {
            window.dispatchEvent(new CustomEvent('workflowSbsReadySteps', {
              detail: { runId: data.runId, readySteps: finalReadySteps },
            }));
          }

          setWorkflowStatus('paused');
        }
      } catch (error: any) {
        handleExecutionError(error, '[StepByStep] Failed to start workflow');
      }
    };

    window.addEventListener('workflowStartStepByStep', handleStepByStepStart as EventListener);

    return () => {
      window.removeEventListener('workflowStartStepByStep', handleStepByStepStart as EventListener);
    };
  }, [workflowId, nodes, edges, router, setWorkflowStatus, isPreviewOnly]);

  return {
    backendValidationErrors,
    setBackendValidationErrors,
    onValidate,
  };
}
