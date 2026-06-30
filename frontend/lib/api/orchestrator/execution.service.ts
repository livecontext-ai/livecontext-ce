/**
 * Execution Service
 *
 * Handles workflow execution control: pause, resume, step-by-step mode, triggers.
 * Single Responsibility: Only execution control operations.
 */

import { apiClient } from '../api-client';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import type {
  WorkflowRunState,
  PauseResumeResponse,
  StepExecutionResponse,
  ReadyStepsResponse,
  ExecutionMode,
  ExecutionModeResponse,
  CoreExecutionResponse,
  TriggerResponse,
  TriggerInfo,
  TriggerTypeValue,
  StepRerunResponse,
  StepAttemptRecord,
  AggregatedStepTiming,
  EpochSignalInfo,
  EpochState
} from './types';

/**
 * Execution calls (triggers, step execution, rerun) run the workflow synchronously
 * on the backend and may invoke multiple LLM providers. The frontend aborting the
 * HTTP request does NOT stop backend execution - it just causes a spurious error
 * in the UI while the backend keeps running and sends WebSocket events normally.
 * timeout=0 disables the client-side abort entirely.
 */
const EXECUTION_TIMEOUT = 0;

export class ExecutionService {
  // ========================================
  // Run State
  // ========================================

  /**
   * In-flight `/state` GETs by runId. Run-page mount fires up to 4 concurrent
   * callers (manager init, useWorkflowLoader hydration, WS-driven refresh,
   * historical chat block) - sharing one Promise collapses them to a single
   * HTTP. Map entries auto-clear on settle (resolve OR reject) so retries
   * after failure hit the network fresh.
   *
   * Layering note: the seq guard in WorkflowRunManager.refreshStateInternal
   * runs POST-await per-caller, so a force=true caller piggybacking on a
   * force=false in-flight Promise still bypasses the guard correctly - both
   * callers run their own application path on the shared response. Don't
   * move the seq guard into this layer; that would defeat force semantics.
   */
  private inFlightStateGets = new Map<string, Promise<WorkflowRunState>>();

  /**
   * Get the complete state of a workflow run including all step statuses.
   * Concurrent calls for the same runId share one HTTP - see inFlightStateGets.
   */
  async getRunState(runId: string): Promise<WorkflowRunState> {
    const existing = this.inFlightStateGets.get(runId);
    if (existing) {
      console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'getRunState COALESCED (piggyback)', runId);
      return existing;
    }
    const t0 = performance.now();
    console.log('[RUN-MOUNT]', t0.toFixed(0), 'getRunState HTTP START', runId);
    const p = apiClient
      .get<WorkflowRunState>(`/v2/workflows/dag/runs/${runId}/state`)
      .finally(() => {
        console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'getRunState HTTP END', runId, `(${(performance.now() - t0).toFixed(0)}ms)`);
        this.inFlightStateGets.delete(runId);
      });
    this.inFlightStateGets.set(runId, p);
    return p;
  }

  // ========================================
  // Pause / Resume
  // ========================================

  /**
   * Pause a running workflow
   */
  async pauseWorkflow(runId: string): Promise<PauseResumeResponse> {
    return apiClient.post<PauseResumeResponse>(`/v2/workflows/dag/runs/${runId}/pause`, {});
  }

  /**
   * Resume a paused workflow
   */
  async resumeWorkflow(runId: string): Promise<PauseResumeResponse> {
    return apiClient.post<PauseResumeResponse>(`/v2/workflows/dag/runs/${runId}/resume`, {});
  }

  /**
   * Check if a workflow is paused
   */
  async isPaused(runId: string): Promise<{ runId: string; isPaused: boolean }> {
    return apiClient.get<{ runId: string; isPaused: boolean }>(`/v2/workflows/dag/runs/${runId}/is-paused`);
  }

  /**
   * Hard-cancel a workflow run (terminal stop).
   * Accepts RUNNING, PAUSED, or WAITING_TRIGGER. Sets status to CANCELLED.
   */
  async cancelWorkflow(runId: string): Promise<PauseResumeResponse> {
    return apiClient.post<PauseResumeResponse>(`/v2/workflows/dag/runs/${runId}/cancel`, {});
  }

  /**
   * Graceful stop: close active epochs and return run to WAITING_TRIGGER.
   * Unlike cancel, the run stays alive for future trigger fires.
   */
  async stopWorkflow(runId: string): Promise<PauseResumeResponse> {
    return apiClient.post<PauseResumeResponse>(`/v2/workflows/dag/runs/${runId}/stop`, {});
  }

  /**
   * Reactivate a cancelled workflow run, returning it to WAITING_TRIGGER.
   * This reverses a cancel and allows triggers to fire again.
   */
  async reactivateWorkflow(runId: string): Promise<PauseResumeResponse> {
    return apiClient.post<PauseResumeResponse>(`/v2/workflows/dag/runs/${runId}/reactivate`, {});
  }

  // ========================================
  // Step Execution
  // ========================================

  /**
   * Execute a single step in step-by-step mode
   */
  async executeSingleStep(runId: string, stepId: string): Promise<StepExecutionResponse> {
    return apiClient.post<StepExecutionResponse>(
      `/v2/workflows/dag/runs/${runId}/step/${encodeURIComponent(stepId)}/execute`, {},
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Get the list of steps that are ready to execute
   */
  async getReadySteps(runId: string): Promise<ReadyStepsResponse> {
    return apiClient.get<ReadyStepsResponse>(`/v2/workflows/dag/runs/${runId}/ready-steps`);
  }

  // ========================================
  // Step-by-Step V2 Execution
  // ========================================

  /**
   * Set the execution mode for a workflow run
   */
  async setExecutionMode(runId: string, mode: ExecutionMode): Promise<ExecutionModeResponse> {
    return apiClient.post<ExecutionModeResponse>(`/v2/workflows/dag/runs/${runId}/execution-mode`, { mode });
  }

  /**
   * Get the current execution mode for a workflow run
   */
  async getExecutionMode(runId: string): Promise<ExecutionModeResponse> {
    return apiClient.get<ExecutionModeResponse>(`/v2/workflows/dag/runs/${runId}/execution-mode`);
  }

  /**
   * Start a workflow in step-by-step mode
   * @param runId - The run ID
   * @param plan - Optional plan to persist before starting (ensures fresh plan is used)
   */
  async startInStepByStepMode(runId: string, plan?: Record<string, unknown>): Promise<ExecutionModeResponse> {
    return apiClient.post<ExecutionModeResponse>(
      `/v2/workflows/dag/runs/${runId}/start-step-by-step`,
      plan ? { plan } : {}
    );
  }

  /**
   * Execute (evaluate) a core node (decision) in step-by-step mode
   * @param runId - The run ID
   * @param coreId - The core node ID
   * @param plan - Optional plan to persist before execution (ensures fresh plan is used)
   */
  async executeCore(runId: string, coreId: string, plan?: Record<string, unknown>): Promise<CoreExecutionResponse> {
    return apiClient.post<CoreExecutionResponse>(
      `/v2/workflows/dag/runs/${runId}/core/${encodeURIComponent(coreId)}/execute`,
      plan ? { plan } : {},
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Execute a control node (decision, switch, guardrail, classify) in step-by-step mode
   * Alias for executeCore for backward compatibility
   */
  async executeControlNode(runId: string, nodeId: string): Promise<CoreExecutionResponse> {
    return this.executeCore(runId, nodeId);
  }

  /**
   * Execute a single step in step-by-step mode (no auto-propagation)
   * @param runId - The run ID
   * @param stepId - The step ID to execute
   * @param inputData - Optional input data for the step
   * @param itemId - Optional item ID for parallel execution
   * @param plan - Optional plan map to persist before execution (for InspectorPanel parameter changes)
   */
  async executeSingleStepInStepByStepMode(
    runId: string,
    stepId: string,
    inputData?: Record<string, unknown>,
    itemId?: string,
    plan?: Record<string, unknown>
  ): Promise<StepExecutionResponse> {
    const body = {
      ...(inputData || {}),
      itemId: itemId || '0',
      ...(plan ? { plan } : {})
    };
    return apiClient.post<StepExecutionResponse>(
      `/v2/workflows/dag/runs/${runId}/step-by-step/${encodeURIComponent(stepId)}/execute`,
      body,
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  // ========================================
  // Triggers (Manual, Chat, Datasource)
  // ========================================

  /**
   * Force-fire a schedule trigger immediately, bypassing the cron timing.
   * Calls the orchestrator `/schedule/execute-now/{triggerId}` endpoint -
   * the same path the schedule-inspector "Execute now" button hits. Returns
   * the new runId on success.
   */
  async scheduleExecuteNow(
    workflowId: string,
    triggerId: string,
  ): Promise<{ success: boolean; triggerId?: string; runId?: string; message?: string; error?: string }> {
    return apiClient.post(
      `/v2/workflows/${workflowId}/schedule/execute-now/${encodeURIComponent(triggerId)}`,
      {},
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Trigger a manual trigger for a run in WAITING_TRIGGER status.
   * @param runId - The run ID
   * @param payload - Optional payload data
   * @param plan - Optional plan to persist before triggering (ensures fresh plan is used)
   */
  async triggerManual(
    runId: string,
    payload?: Record<string, unknown>,
    plan?: Record<string, unknown>
  ): Promise<TriggerResponse> {
    return apiClient.post<TriggerResponse>(
      `/v2/workflows/runs/${runId}/trigger/manual`,
      { ...(payload || {}), ...(plan ? { plan } : {}) },
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Trigger a chat trigger for a run in WAITING_TRIGGER status.
   * @param runId - The run ID
   * @param message - The chat message
   * @param additionalData - Optional additional data
   * @param plan - Optional plan to persist before triggering (ensures fresh plan is used)
   */
  async triggerChat(
    runId: string,
    message: string,
    additionalData?: Record<string, unknown>,
    plan?: Record<string, unknown>
  ): Promise<TriggerResponse> {
    return apiClient.post<TriggerResponse>(
      `/v2/workflows/runs/${runId}/trigger/chat`,
      { message, ...additionalData, ...(plan ? { plan } : {}) },
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Trigger a datasource trigger for a run in WAITING_TRIGGER status.
   * @param runId - The run ID
   * @param triggerConfig - Optional trigger configuration
   * @param plan - Optional plan to persist before triggering (ensures fresh plan is used)
   */
  async triggerDatasource(
    runId: string,
    triggerConfig?: Record<string, unknown>,
    plan?: Record<string, unknown>
  ): Promise<TriggerResponse> {
    return apiClient.post<TriggerResponse>(
      `/v2/workflows/runs/${runId}/trigger/datasource`,
      { ...(triggerConfig || {}), ...(plan ? { plan } : {}) },
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Trigger a form trigger for a run in WAITING_TRIGGER status.
   * @param runId - The run ID
   * @param formData - The form data
   * @param plan - Optional plan to persist before triggering (ensures fresh plan is used)
   */
  async triggerForm(
    runId: string,
    formData: Record<string, unknown>,
    plan?: Record<string, unknown>
  ): Promise<TriggerResponse> {
    return apiClient.post<TriggerResponse>(
      `/v2/workflows/runs/${runId}/trigger/form`,
      { ...formData, ...(plan ? { plan } : {}) },
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  // ========================================
  // Multi-DAG Trigger Support
  // ========================================

  /**
   * Get available triggers for a run.
   * Used for multi-DAG workflows to display trigger selection UI.
   */
  async getAvailableTriggers(runId: string): Promise<TriggerInfo[]> {
    return apiClient.get<TriggerInfo[]>(`/v2/workflows/runs/${runId}/triggers`);
  }

  /**
   * Trigger a specific trigger by its ID.
   * Used for multi-DAG workflows where multiple triggers exist.
   *
   * @param runId - The run ID
   * @param triggerId - The specific trigger ID (e.g., "trigger:my_webhook")
   * @param triggerType - The trigger type (manual, chat, datasource, form)
   * @param payload - Optional payload data (required for chat triggers)
   * @param plan - Optional plan to persist before triggering (ensures fresh plan is used)
   */
  async triggerSpecific(
    runId: string,
    triggerId: string,
    triggerType: TriggerTypeValue,
    payload?: Record<string, unknown>,
    plan?: Record<string, unknown>
  ): Promise<TriggerResponse> {
    return apiClient.post<TriggerResponse>(
      `/v2/workflows/runs/${runId}/trigger/${triggerType}/${encodeURIComponent(triggerId)}`,
      { ...(payload || {}), ...(plan ? { plan } : {}) },
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  // ========================================
  // Signal Resolution (User Approval)
  // ========================================

  /**
   * Resolve a signal (approve/reject) on a node awaiting user approval.
   * @param runId - The run ID
   * @param nodeId - The backend node ID (e.g., "core:my_approval")
   * @param resolution - 'APPROVED' or 'REJECTED'
   * @param comment - Optional comment for the resolution
   */
  async resolveSignal(
    runId: string,
    nodeId: string,
    resolution: 'APPROVED' | 'REJECTED',
    comment?: string,
    epoch?: number,
    itemId?: string
  ): Promise<{ status: string }> {
    return apiClient.post<{ status: string }>(
      `/v2/workflows/dag/runs/${runId}/signals/${encodeURIComponent(nodeId)}/resolve`,
      { resolution, ...(comment ? { comment } : {}), ...(epoch != null ? { epoch } : {}), ...(itemId != null ? { itemId } : {}) }
    );
  }

  /**
   * Resolve ALL pending USER_APPROVAL signals for a node across all epochs.
   * Used in "all epoch" view to bulk-approve/reject accumulated signals.
   */
  async resolveAllSignals(
    runId: string,
    nodeId: string,
    resolution: 'APPROVED' | 'REJECTED',
    comment?: string,
    epoch?: number
  ): Promise<{ status: string; count: number }> {
    return apiClient.post<{ status: string; count: number }>(
      `/v2/workflows/dag/runs/${runId}/signals/${encodeURIComponent(nodeId)}/resolve-all`,
      { resolution, ...(comment ? { comment } : {}), ...(epoch != null ? { epoch } : {}) }
    );
  }

  // ========================================
  // Step Data & History
  // ========================================

  /**
   * Get step data for a run
   */
  async getStepData(runId: string, stepAlias: string): Promise<any> {
    return apiClient.get<any>(`/v2/workflows/dag/instances/${runId}/steps/${stepAlias}/data`);
  }

  // ========================================
  // Step Re-run
  // ========================================

  /**
   * Re-run a workflow from a specific step.
   * @param runId - The run ID
   * @param stepId - The step ID to re-run from
   * @param plan - Optional plan to persist before re-running (ensures fresh plan is used)
   */
  async rerunFromStep(runId: string, stepId: string, plan?: Record<string, unknown>): Promise<StepRerunResponse> {
    return apiClient.post<StepRerunResponse>(
      `/v2/workflows/dag/runs/${runId}/rerun/${encodeURIComponent(stepId)}`,
      plan ? { plan } : {},
      { timeout: EXECUTION_TIMEOUT }
    );
  }

  /**
   * Get the history of execution attempts for a specific step.
   */
  async getStepHistory(runId: string, stepId: string): Promise<StepAttemptRecord[]> {
    return apiClient.get<StepAttemptRecord[]>(
      `/v2/workflows/dag/runs/${runId}/steps/${encodeURIComponent(stepId)}/history`
    );
  }

  // ========================================
  // Aggregated Steps (per-epoch node timing)
  // ========================================

  /**
   * Get aggregated steps for a run, optionally filtered by epoch.
   * Used for per-epoch node timing display in the epoch timeline.
   */
  async getAggregatedSteps(runId: string, epoch?: number): Promise<AggregatedStepTiming[]> {
    const params = epoch !== undefined ? { params: { epoch: String(epoch) } } : {};
    return apiClient.get<AggregatedStepTiming[]>(
      `/v2/workflows/dag/instances/${runId}/steps/aggregated`,
      params
    );
  }

  /**
   * Get all active signals for a run, across epochs. Same payload shape as the
   * WS batch-update `pendingSignals` (id, nodeId, signalType, status, epoch,
   * itemId, expiresAt, config, itemContext). Used to hydrate the run state on
   * page load: GET /state carries no signal rows, so without this fetch a
   * reload during a pending approval loses the per-item signal lists until the
   * next live WS batch-update.
   */
  async getRunSignals(runId: string): Promise<PendingSignal[]> {
    return apiClient.get(`/v2/workflows/dag/runs/${runId}/signals`);
  }

  /**
   * Get active signals for a specific epoch.
   * Used to show pending signals (user approval, interface, etc.) when viewing historical epochs.
   */
  async getEpochSignals(runId: string, epoch: number): Promise<EpochSignalInfo[]> {
    return apiClient.get<EpochSignalInfo[]>(
      `/v2/workflows/dag/runs/${runId}/epochs/${epoch}/signals`
    );
  }

  /**
   * Get pre-aggregated node and edge status counts for a specific epoch.
   * Returns { epoch, nodes: {key: {status: count}}, edges: {key: {status: count}} }.
   */
  async getEpochState(runId: string, epoch: number): Promise<EpochState> {
    return apiClient.get<EpochState>(
      `/v2/workflows/dag/runs/${runId}/epochs/${epoch}/state`
    );
  }

  // ========================================
  // Step Output Skeleton & Lazy Loading
  // ========================================

  /**
   * Get only the structure skeleton of a step output (lightweight, ~200 bytes).
   * Used for building the tree view without loading full payload.
   */
  async getStepOutputSkeleton(
    workflowId: string,
    runId: string,
    stepId: number
  ): Promise<StepOutputSkeleton | null> {
    try {
      return await apiClient.get<StepOutputSkeleton>(
        `/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/skeleton`
      );
    } catch {
      return null;
    }
  }

  /**
   * Get a value at a specific JSON path from step output (lazy loading).
   */
  async getStepOutputValueAtPath(
    workflowId: string,
    runId: string,
    stepId: number,
    path: string
  ): Promise<string | null> {
    try {
      return await apiClient.get<string>(
        `/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/value`,
        { params: { path } }
      );
    } catch {
      return null;
    }
  }

  /**
   * Get a JSON object at a specific path from step output (lazy loading sub-objects).
   */
  async getStepOutputObjectAtPath(
    workflowId: string,
    runId: string,
    stepId: number,
    path: string
  ): Promise<any | null> {
    try {
      return await apiClient.get<any>(
        `/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/object`,
        { params: { path } }
      );
    } catch {
      return null;
    }
  }

  /**
   * Get array info (length) at a specific path.
   */
  async getStepOutputArrayInfo(
    workflowId: string,
    runId: string,
    stepId: number,
    path: string
  ): Promise<{ path: string; length: number; hasItems: boolean } | null> {
    try {
      return await apiClient.get<{ path: string; length: number; hasItems: boolean }>(
        `/workflows/${workflowId}/runs/${runId}/steps/${stepId}/output/array-info`,
        { params: { path } }
      );
    } catch {
      return null;
    }
  }

  /**
   * Updates a run's plan without creating a version or modifying workflow.plan.
   * Used for saving parameter changes made during run mode.
   */
  async updateRunPlan(runId: string, plan: Record<string, unknown>): Promise<{ success: boolean }> {
    return apiClient.put<{ success: boolean }>(`/v2/workflows/dag/runs/${runId}/plan`, { plan });
  }
}

// ========================================
// Skeleton Types
// ========================================

export interface StepOutputSkeleton {
  _t: 'obj' | 'arr';
  props?: Record<string, StepOutputSkeleton | string>;
  items?: StepOutputSkeleton | string;
}

export const executionService = new ExecutionService();
