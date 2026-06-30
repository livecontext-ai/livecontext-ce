/**
 * RunStateStore - Single source of truth for workflow run state.
 *
 * SOLID Principles:
 * - SRP: Only manages state, no side effects
 * - OCP: Extensible via event listeners
 * - DIP: No dependencies on external services
 *
 * Key Design Decisions:
 * 1. Immutable state updates
 * 2. Subscribers notified on any change
 * 3. API response is authoritative for readySteps (not streaming)
 * 4. Streaming updates only affect visualization data
 */

import { streamDebug } from './streamingDebug';
import type { DerivedNodeStatus } from '@/app/workflows/builder/types';
import type { PendingSignal } from '@/lib/websocket/ws-types';

// ============================================================================
// TYPES
// ============================================================================

export type RunStatus = 'pending' | 'waiting_trigger' | 'running' | 'paused' | 'completed' | 'failed' | 'partial_success' | 'cancelled' | 'timeout' | 'stopped';
export type TriggerType = 'manual' | 'chat' | 'form' | 'webhook' | 'datasource' | 'schedule' | 'workflow' | null;
export type ExecutionMode = 'automatic' | 'step_by_step';

export interface NodeState {
  nodeId: string;
  label?: string;
  status: DerivedNodeStatus;
  durationMs?: number;
  statusCounts?: Record<string, number>;
  error?: string;
}

export interface WorkflowStatusState {
  status: RunStatus;
  progress?: number;
  message?: string;
  stepsCompleted?: number;
  totalSteps?: number;
  durationMs?: number;
}

export interface RunState {
  // Connection
  isConnected: boolean;
  isLoading: boolean;
  isRerunning: boolean;
  error: string | null;

  // Run metadata
  runId: string | null;
  workflowId: string | null;
  runStatus: RunStatus;
  triggerType: TriggerType;
  executionMode: ExecutionMode;
  startedAt: string | null;
  endedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  /** Cumulative execution duration across all epochs (closed + active), from backend */
  totalDurationMs: number | null;
  totalNodes: number;
  currentEpoch: number;
  epochTimestamps: Array<{ epoch: number; startedAt: string; endedAt: string | null }>;
  /** Monotonic counter from backend StateSnapshot. Bumps on every state change. */
  snapshotSeq: number;
  /** Sum of all per-node completed+failed+skipped counts from streaming statusCounts.
   *  Increases with each epoch completion even when node statuses don't change. */
  executionTotal: number;

  // Step tracking (authoritative from API)
  readySteps: Set<string>;
  /** Per-epoch ready steps for SBS parallel epoch support.
   *  Maps epoch number (as string) to list of ready node IDs for that epoch. */
  epochReadySteps: Record<string, string[]>;
  /** Currently active epochs (not yet closed). */
  activeEpochs: number[];
  completedSteps: Set<string>;
  failedSteps: Set<string>;
  skippedSteps: Set<string>;
  runningSteps: Set<string>;
  awaitingSignalSteps: Set<string>;
  evaluatedCores: Set<string>;
  stepStates: Map<string, any>;
  /** Maps React Flow node ID (toolId) → backend step ID (stepId).
   *  Built from backend StepState.toolId - single source of truth, no prefix guessing. */
  nodeIdToStepId: Map<string, string>;
  pendingSignals: PendingSignal[];

  // Visualization data (from streaming)
  nodes: NodeState[];
  batchSteps: any[];
  batchEdges: any[];
  loops: any[];
  decisionEvaluations: any[];
  workflowStatus: WorkflowStatusState | null;

  // Raw state for advanced use
  rawRunState: any | null;
}

type StateListener = (state: RunState) => void;

/**
 * Payload accepted by both `applyMetadata` and `applyTrackingFromApi` (and
 * the legacy `initializeFromApi` wrapper). Defined once so the splitter
 * methods share a single source of truth.
 */
export interface InitializeFromApiPayload {
  runId: string;
  workflowId?: string;
  status: string;
  executionMode?: string;
  triggerType?: TriggerType;
  startedAt?: string;
  endedAt?: string;
  completedAt?: string;
  durationMs?: number;
  readySteps?: string[];
  completedStepIds?: string[];
  failedStepIds?: string[];
  skippedStepIds?: string[];
  runningStepIds?: string[];
  steps?: any[];
  edges?: any[];
  rawState?: any;
  currentEpoch?: number;
  epochTimestamps?: Array<{ epoch: number; startedAt: string; endedAt: string | null }>;
  totalDurationMs?: number;
  /** Backend monotonic sequence (StateSnapshot.seq) - used by manager for partial-apply gating. */
  seq?: number;
}

// ============================================================================
// STATE MACHINE
// ============================================================================

/**
 * Terminal states are "sticky" - once reached, only initializeFromApi (REST API)
 * can override them. Streaming events cannot revert a terminal state to a non-terminal one.
 * This prevents race conditions where stale streaming events arrive after completion.
 */
// Mirrors the backend RunStatus.isTerminal() set (a finished run, by definition).
// partial_success IS terminal (a run that completed with some failed steps) - omitting
// it would let a partial_success run skip terminal reconciliation (e.g. a node stuck
// "running"/Thinking would never be cleared by the view-layer guard).
export const TERMINAL_STATUSES: ReadonlySet<RunStatus> = new Set(['completed', 'failed', 'partial_success', 'stopped', 'cancelled', 'timeout']);

// ============================================================================
// HELPERS
// ============================================================================

/**
 * Shallow content-equality for the epochTimestamps array. Length + per-index
 * tuple match (epoch + startedAt + endedAt). Used by {@link setEpochData} to
 * skip a state write when the WS-decoded array carries identical data to the
 * current state - preserves array identity downstream so React memoizations
 * can short-circuit.
 */
function isEpochTimestampsContentEqual(
  a: Array<{ epoch: number; startedAt: string; endedAt: string | null }>,
  b: Array<{ epoch: number; startedAt: string; endedAt: string | null }>,
): boolean {
  if (a === b) return true;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const x = a[i];
    const y = b[i];
    if (x.epoch !== y.epoch || x.startedAt !== y.startedAt || x.endedAt !== y.endedAt) {
      return false;
    }
  }
  return true;
}

// ============================================================================
// DEFAULT STATE
// ============================================================================

function createDefaultState(): RunState {
  return {
    isConnected: false,
    isLoading: false,
    isRerunning: false,
    error: null,
    runId: null,
    workflowId: null,
    runStatus: 'pending',
    triggerType: null,
    executionMode: 'automatic',
    startedAt: null,
    endedAt: null,
    completedAt: null,
    durationMs: null,
    totalDurationMs: null,
    totalNodes: 0,
    currentEpoch: 0,
    epochTimestamps: [],
    snapshotSeq: -1,
    executionTotal: 0,
    readySteps: new Set(),
    epochReadySteps: {},
    activeEpochs: [],
    completedSteps: new Set(),
    failedSteps: new Set(),
    skippedSteps: new Set(),
    runningSteps: new Set(),
    awaitingSignalSteps: new Set(),
    evaluatedCores: new Set(),
    stepStates: new Map(),
    nodeIdToStepId: new Map(),
    pendingSignals: [],
    nodes: [],
    batchSteps: [],
    batchEdges: [],
    loops: [],
    decisionEvaluations: [],
    workflowStatus: null,
    rawRunState: null,
  };
}

// ============================================================================
// RUN STATE STORE CLASS
// ============================================================================

const MIN_SHIMMER_MS = 600;

export class RunStateStore {
  private state: RunState;
  private listeners: Set<StateListener> = new Set();
  private runId: string;
  /** Tracks when each step first entered running state (for minimum shimmer duration). */
  private runningStartTimes: Map<string, number> = new Map();
  private shimmerTimers: Map<string, ReturnType<typeof setTimeout>> = new Map();
  /** Latest backend-authoritative running set (unmodified by shimmer logic). */
  private backendRunningSteps: Set<string> = new Set();

  constructor(runId: string) {
    this.runId = runId;
    this.state = { ...createDefaultState(), runId };
  }

  /**
   * Schedule shimmer cleanup for a step after remaining ms.
   * The callback only removes the step if it's not in the current backend running set.
   */
  private scheduleShimmerCleanup(id: string, remainingMs: number): void {
    if (this.shimmerTimers.has(id)) return;
    this.shimmerTimers.set(id, setTimeout(() => {
      this.shimmerTimers.delete(id);
      this.runningStartTimes.delete(id);
      if (this.backendRunningSteps.has(id)) return;
      const current = this.state.runningSteps;
      if (current.has(id)) {
        const next = new Set(current);
        next.delete(id);
        this.update({ runningSteps: next });
      }
    }, remainingMs));
  }

  private clearAllShimmerState(): void {
    for (const timer of this.shimmerTimers.values()) clearTimeout(timer);
    this.shimmerTimers.clear();
    this.runningStartTimes.clear();
    this.backendRunningSteps = new Set();
  }

  // --------------------------------------------------------------------------
  // GETTERS (Read-only access)
  // --------------------------------------------------------------------------

  getState(): Readonly<RunState> {
    return this.state;
  }

  getRunId(): string {
    return this.runId;
  }

  isReady(stepId: string): boolean {
    return this.state.readySteps.has(stepId);
  }

  isCompleted(stepId: string): boolean {
    return this.state.completedSteps.has(stepId);
  }

  canRerun(stepId: string): boolean {
    return (
      this.state.completedSteps.has(stepId) ||
      this.state.failedSteps.has(stepId) ||
      this.state.skippedSteps.has(stepId)
    );
  }

  isStepByStepMode(): boolean {
    return this.state.executionMode === 'step_by_step';
  }

  // --------------------------------------------------------------------------
  // SETTERS (Immutable updates)
  // --------------------------------------------------------------------------

  /**
   * Update state with partial changes. Notifies all listeners.
   */
  private update(changes: Partial<RunState>): void {
    this.state = { ...this.state, ...changes };
    this.notifyListeners();
  }

  /**
   * Set connection status.
   */
  setConnected(connected: boolean): void {
    this.update({ isConnected: connected });
  }

  /**
   * Set loading status.
   */
  setLoading(loading: boolean): void {
    this.update({ isLoading: loading });
  }

  /**
   * Set error message.
   */
  setError(error: string | null): void {
    this.update({ error, isLoading: false });
  }

  /**
   * Set ready steps - ONLY from API response (authoritative source).
   * This is the key method that prevents race conditions.
   */
  setReadySteps(readySteps: string[]): void {
    streamDebug.log('RunStateStore', 'setReadySteps from API:', readySteps);
    this.update({ readySteps: new Set(readySteps) });
  }

  /**
   * Set per-epoch ready steps and active epochs (from batch-update).
   * Used in SBS mode to show epoch-specific ready steps.
   */
  setEpochReadySteps(epochReadySteps: Record<string, string[]>, activeEpochs: number[]): void {
    this.update({ epochReadySteps, activeEpochs });
  }

  /**
   * Set completed steps from API response.
   */
  setCompletedSteps(completedSteps: string[]): void {
    this.update({ completedSteps: new Set(completedSteps) });
  }

  /**
   * Add a single step to completed (after execution).
   */
  addCompletedStep(stepId: string): void {
    const newCompleted = new Set(this.state.completedSteps);
    newCompleted.add(stepId);
    // Remove from failed/skipped/awaiting (step may have been re-executed or resolved)
    const newFailed = new Set(this.state.failedSteps);
    const newSkipped = new Set(this.state.skippedSteps);
    const newAwaiting = new Set(this.state.awaitingSignalSteps);
    const changes: Partial<RunState> = { completedSteps: newCompleted };
    if (newFailed.delete(stepId)) changes.failedSteps = newFailed;
    if (newSkipped.delete(stepId)) changes.skippedSteps = newSkipped;
    if (newAwaiting.delete(stepId)) changes.awaitingSignalSteps = newAwaiting;
    // Track evaluated core nodes in real-time
    if (stepId.startsWith('core:')) {
      const newCores = new Set(this.state.evaluatedCores);
      newCores.add(stepId);
      changes.evaluatedCores = newCores;
    }
    this.update(changes);
  }

  /**
   * Add a single step to failed (after execution).
   */
  addFailedStep(stepId: string): void {
    const newFailed = new Set(this.state.failedSteps);
    newFailed.add(stepId);
    // Remove from completed/skipped/awaiting (step may have been re-executed)
    const newCompleted = new Set(this.state.completedSteps);
    const newSkipped = new Set(this.state.skippedSteps);
    const newAwaiting = new Set(this.state.awaitingSignalSteps);
    const changes: Partial<RunState> = { failedSteps: newFailed };
    if (newCompleted.delete(stepId)) changes.completedSteps = newCompleted;
    if (newSkipped.delete(stepId)) changes.skippedSteps = newSkipped;
    if (newAwaiting.delete(stepId)) changes.awaitingSignalSteps = newAwaiting;
    this.update(changes);
  }

  /**
   * Remove a step from all terminal sets (when it re-enters pending/running).
   */
  clearStepFromTerminalSets(stepId: string): void {
    const newCompleted = new Set(this.state.completedSteps);
    const newFailed = new Set(this.state.failedSteps);
    const newSkipped = new Set(this.state.skippedSteps);
    const c = newCompleted.delete(stepId);
    const f = newFailed.delete(stepId);
    const s = newSkipped.delete(stepId);
    if (c || f || s) {
      this.update({
        ...(c ? { completedSteps: newCompleted } : {}),
        ...(f ? { failedSteps: newFailed } : {}),
        ...(s ? { skippedSteps: newSkipped } : {}),
      });
    }
  }

  /**
   * Add a step to awaiting signal (user approval pending).
   * This set is NOT overwritten by streaming batch-updates.
   */
  addAwaitingSignalStep(stepId: string): void {
    const newAwaiting = new Set(this.state.awaitingSignalSteps);
    newAwaiting.add(stepId);
    this.update({ awaitingSignalSteps: newAwaiting });
  }

  /**
   * Remove a step from awaiting signal (after resolution).
   */
  removeAwaitingSignalStep(stepId: string): void {
    const newAwaiting = new Set(this.state.awaitingSignalSteps);
    if (newAwaiting.delete(stepId)) {
      this.update({ awaitingSignalSteps: newAwaiting });
    }
  }

  /**
   * Set failed steps from API response.
   */
  setFailedSteps(failedSteps: string[]): void {
    this.update({ failedSteps: new Set(failedSteps) });
  }

  /**
   * Set skipped steps from API response.
   */
  setSkippedSteps(skippedSteps: string[]): void {
    this.update({ skippedSteps: new Set(skippedSteps) });
  }

  /**
   * Set completed steps from backend batch-update (authoritative).
   * Also derives evaluatedCores atomically from the completed set.
   */
  setCompletedStepsFromBackend(completedStepIds: string[]): void {
    const completedSteps = new Set(completedStepIds);
    const evaluatedCores = new Set<string>();
    for (const stepId of completedStepIds) {
      if (stepId.startsWith('core:')) {
        evaluatedCores.add(stepId);
      }
    }

    // A completed step is NEVER injected into runningSteps. Doing so (the old
    // flash-shimmer for fast nodes) re-flashed prior-epoch / other-trigger-DAG
    // terminal nodes 'running' when an epoch closed: every batch re-applies the
    // ACCUMULATED completed set (NodeCounts span all epochs AND all DAGs), so any
    // terminal node not already tracked looked "newly completed" and got a 600ms
    // shimmer. Running shimmer now comes solely from setRunningSteps (the
    // backend-authoritative running set + min-shimmer retention) - only
    // genuinely-running nodes shimmer. Trade-off: a sub-100ms node that completes
    // without ever being reported running won't flash; that is not worth
    // re-flashing every terminal node on each epoch close.
    this.update({ completedSteps, evaluatedCores });
  }

  /**
   * Set running steps from backend batch-update (authoritative).
   * Enforces a minimum shimmer duration so fast-completing nodes
   * (task, transform, etc.) still flash visually on the canvas.
   */
  setRunningSteps(runningStepIds: string[]): void {
    const now = Date.now();
    this.backendRunningSteps = new Set(runningStepIds);
    const merged = new Set(runningStepIds);

    for (const id of runningStepIds) {
      if (!this.runningStartTimes.has(id)) {
        this.runningStartTimes.set(id, now);
      }
    }

    for (const [id, startTime] of this.runningStartTimes) {
      if (!merged.has(id)) {
        const elapsed = now - startTime;
        if (elapsed < MIN_SHIMMER_MS) {
          merged.add(id);
          this.scheduleShimmerCleanup(id, MIN_SHIMMER_MS - elapsed);
        } else {
          this.runningStartTimes.delete(id);
        }
      }
    }

    this.update({ runningSteps: merged });
  }

  /**
   * Set awaiting signal steps from backend batch-update (authoritative).
   */
  setAwaitingSignalSteps(awaitingStepIds: string[]): void {
    this.update({ awaitingSignalSteps: new Set(awaitingStepIds) });
  }

  /**
   * Set pending signals from backend batch-update (authoritative).
   */
  setPendingSignals(signals: PendingSignal[]): void {
    this.update({ pendingSignals: signals });
  }

  /**
   * Set run status with state machine validation.
   * Terminal states (completed, failed, stopped) are sticky:
   * only initializeFromApi() can override them.
   */
  setRunStatus(status: RunStatus): void {
    const current = this.state.runStatus;
    if (TERMINAL_STATUSES.has(current) && !TERMINAL_STATUSES.has(status)) {
      streamDebug.warn('RunStateStore', `Rejected status transition: ${current} → ${status} (terminal state is sticky)`);
      return;
    }
    this.update({ runStatus: status });
  }

  /**
   * Set execution mode.
   */
  setExecutionMode(mode: ExecutionMode): void {
    this.update({ executionMode: mode });
  }

  /**
   * Set trigger type.
   */
  setTriggerType(type: TriggerType): void {
    this.update({ triggerType: type });
  }

  /**
   * Set current epoch (for rerun tracking).
   */
  setEpoch(epoch: number): void {
    this.update({ currentEpoch: epoch });
  }

  /**
   * Set epoch data from SSE batch-update (run info panel live updates).
   *
   * Content-equality short-circuits the `epochTimestamps` write when the new
   * array carries the same data as the current state. WS batch-updates ship
   * a freshly-decoded array on every push (new reference every time); without
   * this guard, every WS event invalidates the downstream `useMemo`/`memo`
   * chain in EpochSelector even when nothing changed - which made the
   * memoization theatre and re-rendered all visible virtualized rows.
   */
  setEpochData(epoch: number, timestamps?: Array<{ epoch: number; startedAt: string; endedAt: string | null }>): void {
    const epochChanged = this.state.currentEpoch !== epoch;
    const timestampsChanged = timestamps != null
      && !isEpochTimestampsContentEqual(this.state.epochTimestamps, timestamps);
    if (!epochChanged && !timestampsChanged) {
      // No-op write - skip the spread+notify so every consumer of useRun
      // doesn't re-render on every SSE batch that ships identical epoch data.
      return;
    }
    const changes: Partial<RunState> = {};
    if (epochChanged) changes.currentEpoch = epoch;
    if (timestampsChanged) changes.epochTimestamps = timestamps;
    this.update(changes);
  }

  /**
   * Set snapshot sequence number from SSE batch-update.
   * Used by useEpochStateViewing to detect backend state changes and re-fetch epoch data.
   */
  setSnapshotSeq(seq: number): void {
    this.update({ snapshotSeq: seq });
  }

  /**
   * Set cumulative total duration from backend.
   */
  setTotalDurationMs(totalDurationMs: number | null): void {
    this.update({ totalDurationMs });
  }

  /**
   * Set rerunning status.
   */
  setRerunning(isRerunning: boolean): void {
    this.update({ isRerunning });
  }

  /**
   * Add evaluated core node.
   */
  addEvaluatedCore(coreId: string): void {
    const newCores = new Set(this.state.evaluatedCores);
    newCores.add(coreId);
    this.update({ evaluatedCores: newCores });
  }

  /**
   * Set workflow status for visualization.
   * Respects the same state machine as setRunStatus:
   * terminal states cannot be overridden by non-terminal ones.
   */
  setWorkflowStatus(status: WorkflowStatusState): void {
    const current = this.state.runStatus;
    streamDebug.log('RunStateStore', `setWorkflowStatus: ${current} → ${status.status}`, {
      runId: this.runId,
      durationMs: status.durationMs,
      stepsCompleted: status.stepsCompleted,
      totalSteps: status.totalSteps,
    });
    if (TERMINAL_STATUSES.has(current) && !TERMINAL_STATUSES.has(status.status)) {
      streamDebug.warn('RunStateStore', `Rejected workflowStatus transition: ${current} → ${status.status} (terminal state is sticky)`);
      return;
    }
    this.update({ workflowStatus: status, runStatus: status.status });
  }

  // --------------------------------------------------------------------------
  // VISUALIZATION UPDATES (from streaming - does NOT affect readySteps)
  // --------------------------------------------------------------------------

  /**
   * Update visualization data from streaming batch update.
   * This does NOT update readySteps - that comes only from API.
   * Derives runningSteps directly from the backend-provided step/node statuses.
   */
  updateVisualization(data: {
    steps?: any[];
    edges?: any[];
    loops?: any[];
  }): void {
    const updates: Partial<RunState> = {};

    streamDebug.log('RunStateStore', 'updateVisualization:', {
      runId: this.runId,
      steps: data.steps?.map((s: any) => `${s.id || s.nodeId || s.normalizedStepId}=${s.status}`),
      edges: data.edges?.map((e: any) => `${e.from || e.source}→${e.to || e.target}=${e.status}`),
      loopsCount: data.loops?.length ?? 0,
    });

    if (data.steps) {
      // Defense-in-depth merge: if the incoming batch is smaller than existing,
      // preserve existing steps that aren't in the new batch (still pending).
      // The backend now sends all plan nodes, but transient snapshot races could
      // still produce an incomplete batch during rapid async completions.
      //
      // Live-coord preservation: workflow-builder pushes browser-agent live
      // coords (session_id, cdp_token, cdp_ws_url, run_id, step_index) into
      // a step entry via WorkflowRunManager.patchBrowserLiveCoords. The
      // backend snapshot doesn't track those - naively replacing the array
      // would wipe them between cdp_ready and node completion. Always copy
      // them forward from the matching existing entry into the incoming
      // entry (incoming wins on every other field).
      const LIVE_KEYS = ['session_id', 'cdp_token', 'cdp_ws_url', 'run_id',
        'step_index', 'current_url'] as const;
      const existingSteps = this.state.batchSteps;
      const existingById = new Map<string, any>();
      for (const e of existingSteps) {
        const id = e.id || e.nodeId || e.stepId || e.normalizedStepId || '';
        if (id) existingById.set(id, e);
      }
      const adoptLive = (incoming: any): any => {
        const id = incoming.id || incoming.nodeId || incoming.stepId || incoming.normalizedStepId || '';
        if (!id) return incoming;
        const prior = existingById.get(id);
        if (!prior) return incoming;
        let next: any | null = null;
        for (const k of LIVE_KEYS) {
          if (prior[k] !== undefined && incoming[k] === undefined) {
            if (!next) next = { ...incoming };
            next[k] = prior[k];
          }
        }
        return next ?? incoming;
      };
      if (existingSteps.length > 0 && data.steps.length < existingSteps.length) {
        const incomingById = new Map<string, any>();
        for (const s of data.steps) {
          const id = s.id || s.nodeId || s.stepId || s.normalizedStepId || '';
          if (id) incomingById.set(id, s);
        }
        const merged: any[] = [];
        const mergedIds = new Set<string>();
        for (const existing of existingSteps) {
          const id = existing.id || existing.nodeId || existing.stepId || existing.normalizedStepId || '';
          const incoming = incomingById.get(id);
          merged.push(incoming ? adoptLive(incoming) : existing);
          if (incoming) mergedIds.add(id);
        }
        for (const s of data.steps) {
          const id = s.id || s.nodeId || s.stepId || s.normalizedStepId || '';
          if (id && !mergedIds.has(id)) merged.push(adoptLive(s));
        }
        updates.batchSteps = merged;
      } else {
        // Always run incoming through adoptLive - when existingById is
        // empty (first batch ever), every lookup returns undefined and
        // adoptLive falls through to the incoming entry unchanged. Net
        // cost: a single Map.get per step on the cold path; eliminates
        // a conditional that was easy to misread as "live coords are
        // dropped on the first batch" when actually they hadn't arrived
        // yet at that point either.
        updates.batchSteps = data.steps.map(adoptLive);
      }

      // DEBUG: trace core:while statusCounts lifecycle
      const whileSteps = data.steps.filter((s: any) => {
        const id = s.id || s.nodeId || s.stepId || s.normalizedStepId || '';
        return id.includes('core:while') || id.includes('core:') ;
      });
      if (whileSteps.length > 0) {
        streamDebug.log('RunStateStore', '🔍 updateVisualization core steps:', whileSteps.map((s: any) => ({
          id: s.id || s.stepId || s.normalizedStepId,
          status: s.status,
          statusCounts: s.statusCounts,
        })));
      } else {
        streamDebug.warn('RunStateStore', '⚠️ updateVisualization: NO core: steps in batch! stepsCount=' + data.steps.length,
          data.steps.map((s: any) => s.id || s.stepId || s.normalizedStepId || 'unknown'));
      }

      // Keep totalNodes in sync with streaming data (it was only set during initializeFromApi).
      // This ensures the runInfo badge reflects new steps that appear after signal resolution.
      // Use the merged steps for node count (includes preserved pending nodes),
      // but only sum execution totals from the backend-authoritative incoming steps.
      const stepsForCount = updates.batchSteps!;
      let nonGatewayCount = 0;
      for (const s of stepsForCount) {
        const id = s.id || s.nodeId || s.stepId || '';
        if (!id.startsWith('gateway_')) {
          nonGatewayCount++;
        }
      }
      let execTotal = 0;
      for (const s of stepsForCount) {
        const sc = s.statusCounts;
        if (sc) {
          execTotal += (sc.completed || 0) + (sc.failed || 0) + (sc.skipped || 0);
        }
      }
      if (nonGatewayCount > this.state.totalNodes) {
        updates.totalNodes = nonGatewayCount;
      }
      updates.executionTotal = execTotal;
      streamDebug.log('RunStateStore', 'updateVisualization executionTotal:', {
        execTotal,
        prevExecTotal: this.state.executionTotal,
        stepsCount: stepsForCount.length,
        nonGatewayCount,
      });
    }
    if (data.edges) {
      updates.batchEdges = data.edges;
    }
    if (data.loops) {
      updates.loops = data.loops;
    }

    if (Object.keys(updates).length > 0) {
      this.update(updates);
    } else {
      streamDebug.warn('RunStateStore', 'updateVisualization: no updates to apply (empty data)');
    }
  }

  /**
   * Add decision evaluation result.
   */
  addDecisionEvaluation(evaluation: any): void {
    this.update({
      decisionEvaluations: [...this.state.decisionEvaluations, evaluation],
    });
  }

  /**
   * Patch live-view fields onto a single step in batchSteps without
   * touching any tracking set or other step. Used for mid-execution
   * browser-agent bootstrap events arriving on the workflow channel
   * before the step's terminal completion. If the step doesn't exist
   * yet, a synthetic 'running' entry is appended so the BrowserAgentNode
   * can still pick up the live coords.
   */
  patchBrowserLiveCoords(nodeId: string, fields: Record<string, unknown>): void {
    if (!nodeId) return;
    const existing = this.state.batchSteps;
    let found = false;
    const merged = existing.map((s: any) => {
      const id = s.id || s.nodeId || s.stepId || s.normalizedStepId || '';
      if (id === nodeId) {
        found = true;
        return { ...s, ...fields };
      }
      return s;
    });
    if (!found) {
      merged.push({ id: nodeId, status: 'running', ...fields });
    }
    this.update({ batchSteps: merged });
  }

  // --------------------------------------------------------------------------
  // BULK INITIALIZATION (from REST API)
  // --------------------------------------------------------------------------

  /**
   * Initialize state from REST API response (legacy entry point).
   * This is the authoritative initial state.
   *
   * As of 2026-05-04 (Phase A1 archi-refoundation), `initializeFromApi` is a
   * thin wrapper around `applyMetadata` + `applyTrackingFromApi`. The two
   * sub-methods can be called separately by `WorkflowRunManager.doInitialize`
   * to enable partial-apply when a fresher WebSocket event has already
   * bumped `lastKnownSeq` - `applyMetadata` always applies (rawState,
   * triggerType, plan, etc. are needed by 5+ consumers), while
   * `applyTrackingFromApi` (which clobbers `batchSteps`/`batchEdges`/tracking
   * sets) is skipped if WS is fresher. See `RUN_PAGE_ARCHITECTURE_ISSUES.md` #1.
   */
  initializeFromApi(data: InitializeFromApiPayload): void {
    this.applyMetadata(data);
    this.applyTrackingFromApi(data);
  }

  /**
   * Apply metadata + raw state from REST response. Always safe to call -
   * does NOT clobber tracking sets (batchSteps, completedSteps, ...). Used
   * by `WorkflowRunManager.doInitialize` even when a fresher WS event has
   * already bumped `lastKnownSeq`. The 5 consumers of `rawRunState` (and
   * `triggerType`, `executionMode`, plan via `rawState.plan`) need this
   * data unconditionally - the only thing that can be stale is the
   * tracking-derived view.
   */
  applyMetadata(data: InitializeFromApiPayload): void {
    // Sticky guard for runStatus on metadata-only path: do not downgrade a
    // terminal WS-set state to a non-terminal API value when we don't have
    // tracking data to corroborate. Full readySteps-aware override happens
    // in applyTrackingFromApi.
    const apiStatus = this.normalizeRunStatus(data.status);
    const currentStatus = this.state.runStatus;
    const safeRunStatus = (TERMINAL_STATUSES.has(currentStatus) && apiStatus === 'running')
      ? currentStatus  // keep WS-set terminal
      : apiStatus;

    this.update({
      runId: data.runId,
      workflowId: data.workflowId || null,
      runStatus: safeRunStatus,
      executionMode: data.executionMode?.toLowerCase() === 'step_by_step' ? 'step_by_step' : 'automatic',
      triggerType: data.triggerType || null,
      startedAt: data.startedAt || null,
      endedAt: data.endedAt || null,
      completedAt: data.completedAt || null,
      durationMs: data.durationMs || null,
      totalDurationMs: data.totalDurationMs ?? null,
      currentEpoch: data.currentEpoch ?? 0,
      epochTimestamps: data.epochTimestamps ?? [],
      rawRunState: data.rawState || null,
      isLoading: false,
      error: null,
    });
  }

  /**
   * Apply tracking sets + visualization (batchSteps/batchEdges) from REST.
   * Skipped by `WorkflowRunManager.doInitialize` when a fresher WS event has
   * already bumped `lastKnownSeq` (otherwise REST would clobber WS-fresh state).
   *
   * IMPORTANT: this method runs the full readySteps-aware runStatus override
   * (terminal-vs-readySteps disambiguation) - `applyMetadata` only does the
   * basic sticky guard. When called separately, the runStatus may be refined
   * here.
   */
  applyTrackingFromApi(data: InitializeFromApiPayload): void {
    // Derive tracking sets from individual step statuses when available.
    // Step statuses are derived from global NodeCounts (accumulated across all epochs),
    // while completedStepIds/failedStepIds from the API use flat views (active epochs only).
    // After epoch close, flat views become empty but NodeCounts retain terminal data.
    // Always prefer step-status-based derivation for accuracy, consistent with processBatchUpdate.
    let completedStepIds: string[] = [];
    let failedStepIds: string[] = [];
    let skippedStepIds: string[] = [];
    let runningStepIds: string[] = [];

    if ((data.steps || []).length > 0) {
      for (const step of (data.steps || [])) {
        const status = (step.status || '').toUpperCase();
        const stepId = step.stepId || step.normalizedStepId;
        if (!stepId) continue;
        const sc = step.statusCounts;

        if (status === 'COMPLETED' || status === 'SUCCESS') {
          completedStepIds.push(stepId);
        } else if (status === 'FAILED' || status === 'ERROR' || status === 'FAILURE') {
          failedStepIds.push(stepId);
        } else if (status === 'SKIPPED' || status === 'SKIP') {
          skippedStepIds.push(stepId);
        } else if (status === 'RUNNING' || status === 'IN_PROGRESS') {
          runningStepIds.push(stepId);
        } else if (status === 'AWAITING_SIGNAL' || status === 'AWAITING') {
          runningStepIds.push(stepId);
        } else if (status === 'PENDING') {
          // A "pending" node with historical completions/failures is ready for rerun.
          // Backend overrides status to "pending" when node is in readyNodeIds.
          if (sc?.completed > 0) {
            completedStepIds.push(stepId);
          } else if (sc?.failed > 0) {
            failedStepIds.push(stepId);
          }
        }
      }
    } else {
      // No steps data - fall back to flat view arrays
      completedStepIds = data.completedStepIds || [];
      failedStepIds = data.failedStepIds || [];
      skippedStepIds = data.skippedStepIds || [];
      runningStepIds = data.runningStepIds || [];
    }

    // readySteps (from flat views) is the authoritative CURRENT state.
    // NodeCounts-derived "completed" is historical (never reset across epochs).
    // If backend says a NON-TRIGGER step is "ready", it must NOT be in completedStepIds -
    // otherwise deriveNodeStatus (completed > ready) shows RERUN instead of PLAY.
    // TRIGGERS are excluded: they appear in readySteps during normal WAITING_TRIGGER
    // cycle (ready for NEXT epoch fire), and should still show RERUN (completed).
    if (data.readySteps?.length) {
      const readySet = new Set(data.readySteps);
      completedStepIds = completedStepIds.filter(id => !readySet.has(id) || id.startsWith('trigger:'));
      failedStepIds = failedStepIds.filter(id => !readySet.has(id) || id.startsWith('trigger:'));
    }

    streamDebug.log('RunStateStore', 'initializeFromApi:', {
      runId: data.runId,
      status: data.status,
      executionMode: data.executionMode,
      readySteps: data.readySteps,
      completedStepIds,
      failedStepIds,
      derivedFromSteps: (data.completedStepIds || []).length === 0 && completedStepIds.length > 0,
    });

    const nodes = this.buildNodes(data.steps || []);

    // Build stepStates map + nodeIdToStepId mapping (toolId → stepId)
    const stepStates = new Map<string, any>();
    const nodeIdToStepId = new Map<string, string>();
    (data.steps || []).forEach((step: any) => {
      stepStates.set(step.stepId, step);
      if (step.stepAlias && step.stepAlias !== step.stepId) {
        stepStates.set(step.stepAlias, step);
      }
      // toolId is the React Flow node ID - map it to the backend stepId
      if (step.toolId && step.stepId) {
        nodeIdToStepId.set(step.toolId, step.stepId);
      }
    });

    // Build awaitingSignalSteps from step statuses
    const awaitingSignalSteps = new Set<string>();
    for (const step of (data.steps || [])) {
      const status = (step.status || '').toUpperCase();
      const stepId = step.stepId || step.normalizedStepId;
      if (!stepId) continue;
      if (status === 'AWAITING_SIGNAL' || status === 'AWAITING') {
        awaitingSignalSteps.add(stepId);
      }
    }

    // Build evaluatedCores from completed steps
    const evaluatedCores = new Set<string>();
    completedStepIds.forEach(stepId => {
      if (stepId.startsWith('core:')) {
        evaluatedCores.add(stepId);
      }
    });

    const apiStatus = this.normalizeRunStatus(data.status);
    const currentStatus = this.state.runStatus;

    // Protect against stale DB reads overwriting correct streaming state.
    // The backend sends streaming COMPLETED before committing to DB, so a refresh
    // right after streaming disconnect may get stale RUNNING from the DB.
    // Don't downgrade from terminal (completed/failed/stopped) to 'running'.
    // Only resetForNewCycle() should transition terminal → running.
    //
    // Exception: if the API returns non-empty readySteps, the backend considers
    // the workflow active (e.g., signal resolution in step-by-step mode).
    // A stale batch-update may have set "failed" via streaming, but the API's readySteps
    // prove the workflow is still running. Trust the API in this case.
    const hasApiReadySteps = (data.readySteps || []).length > 0;
    const runStatus = (TERMINAL_STATUSES.has(currentStatus) && apiStatus === 'running')
      ? hasApiReadySteps
        ? (() => {
            streamDebug.log('RunStateStore', `initializeFromApi: API has readySteps [${data.readySteps}], overriding terminal '${currentStatus}' with '${apiStatus}'`);
            return apiStatus;
          })()
        : (() => {
            streamDebug.warn('RunStateStore', `initializeFromApi: stale API status '${apiStatus}' ignored, keeping terminal '${currentStatus}'`);
            return currentStatus;
          })()
      : apiStatus;

    // DEBUG: trace core steps from API
    const apiCoreSteps = (data.steps || []).filter((s: any) => {
      const id = s.stepId || s.normalizedStepId || s.id || '';
      return id.startsWith('core:');
    });
    streamDebug.log('RunStateStore', '🔍 initializeFromApi core steps:', apiCoreSteps.map((s: any) => ({
      stepId: s.stepId,
      status: s.status,
      statusCounts: s.statusCounts,
    })));

    // Convert REST steps to batchSteps format for useRunStateProcessing.
    // REST steps use {stepId, stepAlias, status, statusCounts},
    // streaming batchSteps use {id, normalizedStepId, status, statusCounts}.
    const batchSteps = (data.steps || []).map((s: any) => ({
      id: s.stepId || s.normalizedStepId,
      normalizedStepId: s.stepId || s.normalizedStepId,
      stepAlias: s.stepAlias,
      status: s.status,
      statusCounts: s.statusCounts,
      output: s.output,
      executionTimeMs: s.executionTimeMs,
      totalExecutionTimeMs: s.totalExecutionTimeMs,
      startTime: s.startTime,
      endTime: s.endTime,
    }));

    // Convert REST edges to batchEdges format.
    // REST edges use {from, to, completedCount, skippedCount},
    // streaming batchEdges use {id, from, to, running, completed, skipped}.
    const batchEdges = (data.edges || []).map((e: any) => ({
      id: `${e.from}->${e.to}`,
      from: e.from,
      to: e.to,
      running: e.runningCount ?? e.running ?? 0,
      completed: e.completedCount ?? e.completed ?? 0,
      skipped: e.skippedCount ?? e.skipped ?? 0,
      statusCounts: e.statusCounts,
    }));

    // Tracking-only update - metadata fields (runId, workflowId, executionMode,
    // triggerType, dates, currentEpoch, rawRunState, isLoading, error) are
    // written by `applyMetadata`. This split allows `WorkflowRunManager.doInitialize`
    // to skip this block when a fresher WS event has already bumped lastKnownSeq.
    this.update({
      runStatus,
      totalNodes: nodes.length,
      readySteps: TERMINAL_STATUSES.has(runStatus) ? new Set() : new Set(data.readySteps || []),
      completedSteps: new Set(completedStepIds),
      failedSteps: new Set(failedStepIds),
      skippedSteps: new Set(skippedStepIds),
      runningSteps: new Set(runningStepIds),
      awaitingSignalSteps,
      evaluatedCores,
      stepStates,
      nodeIdToStepId,
      nodes,
      batchSteps,
      batchEdges,
      workflowStatus: {
        status: runStatus,
        stepsCompleted: completedStepIds.length,
        totalSteps: nodes.length,
      },
    });
  }

  /**
   * Patch a subset of `rawRunState` fields without re-serializing the entire
   * snapshot. Called by `WorkflowRunManager.handleBatchUpdate` so that the
   * 5 consumers reading `currentRunInfo`/`planVersion`/`plan.triggers`/
   * `useWorkflowPauseResume`/`InspectorPanel` see fresh values without needing
   * a REST refetch. Phase A1 fix for `RUN_PAGE_ARCHITECTURE_ISSUES.md` #2.
   *
   * Only `runStatus` and `currentEpoch` are patched - the rest of the raw
   * state (plan structure, triggers list, planVersion) changes rarely and
   * is fetched on REST init.
   */
  patchRawRunState(patch: { runStatus?: string; currentEpoch?: number }): void {
    if (!this.state.rawRunState) return;
    const merged: any = { ...this.state.rawRunState };
    if (patch.runStatus !== undefined) merged.status = patch.runStatus;
    if (patch.currentEpoch !== undefined) merged.currentEpoch = patch.currentEpoch;
    this.update({ rawRunState: merged });
  }

  /**
   * Reset state for a new trigger cycle (reusable triggers).
   * Called before triggering a new cycle - bypasses terminal state guard
   * because starting a new cycle is an authoritative user action.
   *
   * IMPORTANT: Preserves batchSteps/batchEdges so the "all epochs" view keeps
   * showing accumulated data until the next batch-update replaces it.
   * Clearing them caused a blank flash in the step list and canvas between
   * the trigger fire and the first WebSocket batch-update arrival.
   *
   * NOTE (audit 2026-05-04): this is called ONLY by `WorkflowRunManager.executeTrigger`
   * (optimistic reset before the trigger HTTP call). Cron-tick cycles (backend-driven
   * trigger fires) do NOT call this - backend snapshots are self-contained with the new
   * `currentEpoch` and a bumped `seq`, and the next batch-update naturally replaces the
   * tracking sets. A future dev seeing the front-only `resetForNewCycle()` call should
   * NOT add a "missing" call from a WS event handler.
   */
  resetForNewCycle(): void {
    this.clearAllShimmerState();
    this.update({
      runStatus: 'running',
      completedSteps: new Set(),
      failedSteps: new Set(),
      skippedSteps: new Set(),
      runningSteps: new Set(),
      awaitingSignalSteps: new Set(),
      evaluatedCores: new Set(),
      nodes: [],
      // Preserve batchSteps and batchEdges: they contain accumulated statusCounts
      // from global NodeCounts (never reset). The next batch-update from WebSocket
      // will authoritatively replace them with fresh data including the new epoch.
      loops: [],
      decisionEvaluations: [],
      workflowStatus: { status: 'running' },
      error: null,
      endedAt: null,
      completedAt: null,
      durationMs: null,
    });
  }

  /**
   * Reset state for rerun.
   * Called after API confirms the rerun - bypasses state machine guard
   * via direct update() since this is an authoritative API response.
   */
  resetForRerun(resetSteps: string[], newReadySteps: string[], epoch: number, status?: string): void {
    for (const id of resetSteps) {
      const timer = this.shimmerTimers.get(id);
      if (timer) { clearTimeout(timer); this.shimmerTimers.delete(id); }
      this.runningStartTimes.delete(id);
      this.backendRunningSteps.delete(id);
    }
    const resetSet = new Set(resetSteps);
    const resolvedStatus = (status?.toLowerCase() || 'running') as RunStatus;

    // Reset visualization: mark reset steps as PENDING in batchSteps.
    // PRESERVE statusCounts: they are the node's cumulative/global execution counts
    // (e.g. {completed: 3, failed: 1}) and on the backend NodeCounts deliberately
    // SURVIVE resetDag() (StateSnapshot.resetDag preserves the `nodes` map; only the
    // per-epoch EpochState is reset). Clearing them here made the badge disappear from
    // the current node + every successor on a rerun and stay gone until re-execution -
    // they must remain visible. The next run-state batch overwrites them with fresh
    // counts when the node actually re-runs.
    const updatedBatchSteps = this.state.batchSteps.map((step: any) => {
      const stepId = step.id || step.nodeId || step.normalizedStepId;
      if (stepId && resetSet.has(stepId)) {
        return { ...step, status: 'pending' };
      }
      return step;
    });

    // Reset edge statuses for edges originating from reset steps.
    // Edge `from` can have port suffixes (e.g., "core:decision:if"),
    // so also check if the base nodeId (without port) is in resetSet.
    const updatedBatchEdges = this.state.batchEdges.map((edge: any) => {
      const from = edge.from || edge.source;
      if (!from) return edge;
      if (resetSet.has(from)) return { ...edge, status: 'pending' };
      // Check base nodeId for port-suffixed edges (e.g., "core:x:if" → "core:x")
      const parts = from.split(':');
      if (parts.length >= 3) {
        const baseId = parts.slice(0, 2).join(':');
        if (resetSet.has(baseId)) return { ...edge, status: 'pending' };
      }
      return edge;
    });

    this.update({
      currentEpoch: epoch,
      runStatus: resolvedStatus,
      readySteps: new Set(newReadySteps),
      completedSteps: new Set([...this.state.completedSteps].filter(s => !resetSet.has(s))),
      failedSteps: new Set([...this.state.failedSteps].filter(s => !resetSet.has(s))),
      skippedSteps: new Set([...this.state.skippedSteps].filter(s => !resetSet.has(s))),
      runningSteps: new Set([...this.state.runningSteps].filter(s => !resetSet.has(s))),
      awaitingSignalSteps: new Set([...this.state.awaitingSignalSteps].filter(s => !resetSet.has(s))),
      evaluatedCores: new Set([...this.state.evaluatedCores].filter(s => !resetSet.has(s))),
      batchSteps: updatedBatchSteps,
      batchEdges: updatedBatchEdges,
    });
  }

  /**
   * Full reset to default state.
   */
  reset(): void {
    this.clearAllShimmerState();
    this.state = { ...createDefaultState(), runId: this.runId };
    this.notifyListeners();
  }

  // --------------------------------------------------------------------------
  // SUBSCRIPTION
  // --------------------------------------------------------------------------

  /**
   * Subscribe to state changes.
   * Returns unsubscribe function.
   */
  subscribe(listener: StateListener): () => void {
    this.listeners.add(listener);
    // Immediately notify with current state
    listener(this.state);

    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Get subscriber count (for cleanup decisions).
   */
  getSubscriberCount(): number {
    return this.listeners.size;
  }

  private notifyListeners(): void {

    this.listeners.forEach(listener => listener(this.state));
  }

  // --------------------------------------------------------------------------
  // HELPERS
  // --------------------------------------------------------------------------

  private normalizeRunStatus(status: string): RunStatus {
    const normalized = status.toLowerCase();
    switch (normalized) {
      case 'waiting_trigger':
        return 'waiting_trigger';
      case 'running':
      case 'step_by_step_ready':
      case 'step_by_step_paused':
        return 'running';
      case 'paused':
        return 'paused';
      case 'completed':
      case 'success':
        return 'completed';
      case 'failed':
      case 'error':
        return 'failed';
      case 'cancelled':
      case 'stopped':
        return 'cancelled';
      case 'timeout':
        return 'timeout';
      case 'partial_success':
        return 'partial_success';
      default:
        return 'pending';
    }
  }

  private buildNodes(steps: any[]): NodeState[] {
    return steps
      .filter((step: any) => !step.stepId?.startsWith('gateway_'))
      .map((step: any) => ({
        nodeId: step.stepId || step.normalizedStepId || '',
        label: step.stepAlias || step.label || '',
        status: this.normalizeNodeStatus(step.status || 'pending'),
        durationMs: step.executionTimeMs || step.durationMs,
        statusCounts: step.statusCounts,
        error: step.errorMessage,
      }));
  }

  private normalizeNodeStatus(status: string): DerivedNodeStatus {
    const normalized = status.toLowerCase();
    switch (normalized) {
      case 'completed':
      case 'success':
        return 'completed';
      case 'failed':
      case 'error':
      case 'failure':
        return 'failed';
      case 'skipped':
      case 'skip':
        return 'skipped';
      case 'running':
      case 'in_progress':
        return 'running';
      case 'partial_success':
        return 'partial_success';
      default:
        return 'pending';
    }
  }

}

// ============================================================================
// STORE REGISTRY (Singleton per runId)
// ============================================================================

const stores = new Map<string, RunStateStore>();

export function getRunStateStore(runId: string): RunStateStore {
  let store = stores.get(runId);
  if (!store) {
    store = new RunStateStore(runId);
    stores.set(runId, store);
  }
  return store;
}

export function deleteRunStateStore(runId: string): void {
  stores.delete(runId);
}

export function hasRunStateStore(runId: string): boolean {
  return stores.has(runId);
}

// Phase 6 (2026-05-18) - HMR-safe workspace-switch reset. Mirrors the same
// pattern in WorkflowRunManager.ts. The store registry caches RunStateStore
// instances keyed by runId for the app lifetime; resetting on org switch
// prevents reusing a store hydrated with the previous workspace's run data.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:RunStateStore:stores');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => { stores.clear(); },
    );
  }).catch(() => {});
}
