/**
 * WorkflowRunManager - Facade that coordinates all workflow run operations.
 *
 * SOLID Principles:
 * - SRP: Coordinates, doesn't implement
 * - OCP: New operations can be added without modifying existing code
 * - LSP: All operations follow same pattern (async, return result)
 * - ISP: Clean public API, internal details hidden
 * - DIP: Depends on abstractions (stores, controllers)
 *
 * Key Design Decisions:
 * 1. API response is authoritative for readySteps
 * 2. Real-time updates come through WebSocket (via useChannel hook)
 * 3. No refreshState after operations - trust API response
 * 4. Clean separation between state and execution
 */

import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { is402Error, is413StorageError } from '@/lib/api/error-utils';
import { showInsufficientCreditsModal } from '@/components/billing/InsufficientCreditsModal';
import { showInsufficientStorageModal } from '@/components/billing/InsufficientStorageModal';
import { handleCeRelayError } from '@/lib/billing/ceRelayErrorModals';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';
import { wsClient } from '@/lib/websocket';
import { RunStateStore, getRunStateStore, deleteRunStateStore, TERMINAL_STATUSES, type RunState } from './RunStateStore';
import { streamDebug } from './streamingDebug';

/**
 * Fetch the run state for this manager's runId.
 *
 * <p>When the marketplace preview page is mounted anonymously, the
 * PublicationSnapshotProvider publishes {publicationId, showcaseRunId} to a
 * module-level store. If our runId matches that frozen clone we route to the
 * public {@code /api/publications/by-id/.../run-state} endpoint instead of
 * the auth'd {@code /v2/workflows/dag/runs/.../state} - the latter throws
 * "No authentication token available" for signed-out visitors and would keep
 * epochTimestamps / statusCounts empty in the toolbar.
 */
async function fetchRunStatePossiblyPublic(runId: string): Promise<any> {
  const publicCtx = getActivePublicPreview();
  if (publicCtx && publicCtx.showcaseRunId === runId) {
    return publicationService.getShowcaseRunState(publicCtx.publicationId, publicCtx.remote);
  }
  return orchestratorApi.getRunState(runId);
}

// ============================================================================
// TYPES
// ============================================================================

export interface StepExecutionResult {
  success: boolean;
  stepId: string;
  status: string;
  readySteps: string[];
  completedSteps?: string[];
  failedSteps?: string[];
  skippedSteps?: string[];
  output?: any;
}

export interface CoreExecutionResult {
  success: boolean;
  coreId: string;
  selectedBranch?: string;
  skippedBranches?: string[];
  readySteps: string[];
}

export interface RerunResult {
  success: boolean;
  stepId: string;
  resetSteps: string[];
  readySteps: string[];
  epoch: number;
  spawn: number;
  seq: number;
}

type StateListener = (state: RunState) => void;

/** Valid trigger types for reusable triggers. */
const REUSABLE_TRIGGER_TYPES = ['manual', 'chat', 'form', 'webhook', 'datasource', 'schedule', 'workflow'] as const;
type ReusableTriggerType = (typeof REUSABLE_TRIGGER_TYPES)[number];

/** Known workflow statuses from the backend. Unknown statuses are ignored. */
const KNOWN_WORKFLOW_STATUSES = new Set([
  'pending', 'waiting_trigger', 'running', 'paused', 'completed', 'success',
  'failed', 'error', 'stopped', 'resuming', 'cancelled', 'timeout', 'partial_success',
]);


/** Delay before safety refresh when real-time events may have been lost. */
const SAFETY_REFRESH_DELAY_MS = 3000;

/** Delay after workflow completion before refreshing state from DB. */
const POST_COMPLETION_REFRESH_DELAY_MS = 500;


// ============================================================================
// WORKFLOW RUN MANAGER CLASS
// ============================================================================

export class WorkflowRunManager {
  private runId: string;
  private store: RunStateStore;
  private initialized: boolean = false;
  private initPromise: Promise<void> | null = null;

  /** Active timers that must be cleared on destroy. */
  private pendingTimers: Set<ReturnType<typeof setTimeout>> = new Set();

  /** In-flight request deduplication. */
  private inFlightRequests: Map<string, Promise<any>> = new Map();

  /** Monotonic sequence number for stale-event filtering (replaces time-based rerun guard). */
  private lastKnownSeq: number = -1;

  /** Steps recently reset by rerunStep() - excluded from completedSteps during API refresh. */
  private recentlyResetSteps: Set<string> = new Set();
  /** The seq at which the last rerun was performed - batch-updates with seq > this are authoritative. */
  private rerunSeq: number = -1;

  /** Browser-agent sessions whose `agentBrowseStep` bootstrap we already
   *  patched into batchSteps. Guards against consumer-group replays /
   *  network retries - without this, every duplicate event would fire a
   *  redundant patchBrowserLiveCoords + render.
   *
   *  Key shape: `${nodeId}:${sessionId}`. Parallel sessions on the same
   *  node (e.g. a split with item-parallelism feeding the same browser
   *  agent) produce distinct sessionIds and therefore distinct keys -
   *  both pass through. Only the second arrival of the SAME (node,
   *  session) pair is skipped. Mirrors the de-facto contract of the
   *  Java-side idempotency cache in
   *  BrowserSessionLifecycleService.onCdpReady, which also skips on
   *  (toolId aka nodeId, sessionId) collisions via its
   *  `live_view:session:{nodeId}` cache value containing sessionId. */
  private bootstrappedBrowserSessions: Set<string> = new Set();

  // Callbacks for external integrations
  private getCurrentPlan: (() => Record<string, unknown> | null) | null = null;

  constructor(runId: string) {
    this.runId = runId;
    this.store = getRunStateStore(runId);
  }

  /**
   * Returns `true` iff this run is currently being viewed in publication-preview
   * mode (showcase clone). The value is **dynamic** - it's recomputed at every
   * call by reading {@link getActivePublicPreview}. DO NOT cache this in a field
   * or pass it to a constructor: the active preview state can flip during the
   * manager's lifetime (e.g. user opens a popup preview while a live run is
   * loaded in the chat sidebar). Callers must re-evaluate. (Phase A1 fix F7.)
   */
  isPublic(): boolean {
    const publicCtx = getActivePublicPreview();
    return !!publicCtx && publicCtx.showcaseRunId === this.runId;
  }

  // --------------------------------------------------------------------------
  // INITIALIZATION
  // --------------------------------------------------------------------------

  /**
   * Initialize run state from REST API.
   * Must be called before any operations.
   */
  async initialize(): Promise<void> {

    if (this.initialized) {
      console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'manager.initialize NO-OP (already initialized)', this.runId);
      return;
    }

    if (this.initPromise) {
      console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'manager.initialize PIGGYBACK on in-flight initPromise', this.runId);
      return this.initPromise;
    }

    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'manager.initialize START', this.runId);
    this.initPromise = this.doInitialize();

    try {
      await this.initPromise;
      this.initialized = true;
      console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'manager.initialize DONE', this.runId);
    } finally {
      this.initPromise = null;
    }
  }

  private async doInitialize(): Promise<void> {
    this.store.setLoading(true);

    try {
      const t0 = performance.now();
      console.log('[RUN-MOUNT]', t0.toFixed(0), 'doInitialize → fetchRunStatePossiblyPublic START', this.runId);
      const runState = await fetchRunStatePossiblyPublic(this.runId);
      console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'doInitialize → fetchRunStatePossiblyPublic DONE', this.runId, `(${(performance.now() - t0).toFixed(0)}ms)`);

      // Phase A1 partial-apply (RUN_PAGE_ARCHITECTURE_ISSUES.md #1):
      // ALWAYS apply metadata (rawState, plan, workflowId, triggerType, dates,
      // currentEpoch) - 5+ consumers depend on these regardless of WS staleness.
      this.store.applyMetadata({
        runId: this.runId,
        workflowId: runState.workflowId,
        status: runState.status || 'pending',
        executionMode: runState.executionMode,
        triggerType: this.extractTriggerType(runState),
        startedAt: runState.startedAt,
        endedAt: runState.endedAt,
        completedAt: runState.completedAt,
        durationMs: runState.durationMs,
        rawState: runState,
        currentEpoch: runState.currentEpoch,
        epochTimestamps: runState.epochTimestamps,
        seq: runState.seq,
      });

      // Pre-populate failureEmitted ALWAYS (drives toast dedup on subsequent WS events).
      // Independent of seq guard: if a step has historically failed, we must remember
      // even when skipping tracking apply.
      if (runState.failedStepIds) {
        for (const stepId of runState.failedStepIds) {
          this.failureEmitted.add(stepId);
        }
      }
      if (runState.steps) {
        for (const step of runState.steps) {
          const stepId = step.id || step.nodeId;
          if (stepId && ['failed', 'error', 'failure'].includes((step.status || '').toLowerCase())) {
            this.failureEmitted.add(stepId);
          }
        }
      }

      // Tracking sets + batchSteps/batchEdges: skip if a fresher WS event already
      // bumped lastKnownSeq while the REST request was in-flight. Without this guard,
      // a slow GET /state would clobber WS-fresh state. (Phase A1 fix for #1.)
      const apiSeq = typeof runState.seq === 'number' ? runState.seq : -1;
      if (apiSeq >= 0 && apiSeq < this.lastKnownSeq) {
        streamDebug.warn('WorkflowRunManager',
          `doInitialize: skipping tracking apply - apiSeq=${apiSeq} < lastKnown=${this.lastKnownSeq} (WS is fresher)`);
      } else {
        const readySteps = this.injectReusableTriggers(runState);
        this.store.applyTrackingFromApi({
          runId: this.runId,
          workflowId: runState.workflowId,
          status: runState.status || 'pending',
          executionMode: runState.executionMode,
          triggerType: this.extractTriggerType(runState),
          startedAt: runState.startedAt,
          endedAt: runState.endedAt,
          completedAt: runState.completedAt,
          durationMs: runState.durationMs,
          readySteps,
          completedStepIds: runState.completedStepIds,
          failedStepIds: runState.failedStepIds,
          skippedStepIds: runState.skippedStepIds,
          runningStepIds: runState.runningStepIds,
          steps: runState.steps,
          edges: runState.edges,
          rawState: runState,
          currentEpoch: runState.currentEpoch,
          epochTimestamps: runState.epochTimestamps,
          seq: runState.seq,
        });

        // Bump seq AFTER apply so subsequent WS events ≥ this seq are recognized.
        if (apiSeq >= 0) {
          this.lastKnownSeq = apiSeq;
        }
      }

      streamDebug.log('WorkflowRunManager', 'Initialized:', {
        runId: this.runId,
        status: runState.status,
        executionMode: runState.executionMode,
        apiSeq,
        lastKnown: this.lastKnownSeq,
      });
    } catch (err) {
      console.error('[WorkflowRunManager] Initialization failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    }
  }

  /**
   * Handle a batch-update received via WebSocket channel.
   * Public entry point for the useChannel hook to forward events.
   * Applies debouncing to prevent render storms.
   */
  handleBatchUpdate(data: any): void {
    if (!data) return;

    // Seq-based stale event filtering: discard batch-updates strictly older than our known state.
    // Uses strict < (not <=) because the backend sends batch-update and readySteps as
    // separate WS events with the SAME seq - both must be processed.
    //
    // 2026-05-05 fix: bumping `lastKnownSeq` is now deferred until we know the
    // payload is actually a snapshot (see `hasSnapshotData` below). Previously,
    // any non-snapshot event mis-routed here (e.g. a `stepStatus` that slipped
    // through `INDIVIDUAL_EVENT_TYPES`) would silently advance `lastKnownSeq`
    // and starve the next legitimate batch-update / REST `/state` snapshot via
    // the strict-`<` guard.
    if (typeof data.seq === 'number') {
      if (data.seq < this.lastKnownSeq) {
        streamDebug.log('WorkflowRunManager', `Discarding stale batch-update: seq=${data.seq}, lastKnown=${this.lastKnownSeq}`);
        return;
      }
    }

    // workflowStatus is critical for state transitions - process immediately
    if (data.workflowStatus?.status) {
      const newStatus = this.normalizeStatus(data.workflowStatus.status);
      streamDebug.log('WorkflowRunManager', 'batch-update workflowStatus:', {
        seq: data.seq,
        status: newStatus,
        currentEpoch: data.currentEpoch,
        stepsCount: (data.steps || data.nodes || []).length,
      });
      this.store.setWorkflowStatus({
        status: newStatus,
        durationMs: data.workflowStatus.durationMs,
        stepsCompleted: data.workflowStatus.stepsCompleted,
        totalSteps: data.workflowStatus.totalSteps,
      });

      // Handle workflow status transitions (terminal, waiting_trigger)
      this.handleWorkflowStatusTransition(newStatus);
    }

    // Phase A1 patch (RUN_PAGE_ARCHITECTURE_ISSUES.md #2): keep rawRunState fresh
    // for the 5 consumers reading currentRunInfo / planVersion / plan.triggers /
    // useWorkflowPauseResume / InspectorPanel. Without this, on cold revisit
    // (manager singleton survived in background, WS dropped), rawRunState was
    // frozen on the initial REST snapshot. Targeted patch - no full re-serialize.
    if (data.workflowStatus?.status || typeof data.currentEpoch === 'number') {
      this.store.patchRawRunState({
        runStatus: data.workflowStatus?.status,
        currentEpoch: typeof data.currentEpoch === 'number' ? data.currentEpoch : undefined,
      });
    }

    // Process snapshot immediately - each snapshot is a complete state picture,
    // so the latest one is always authoritative. No debounce needed.
    // Skip if no step/edge/tracking data exists (e.g. workflowStatus-only events).
    const hasSnapshotData = data.steps || data.nodes || data.stepStates || data.edges ||
      data.loops || data.completedStepIds || data.failedStepIds || data.skippedStepIds ||
      data.runningStepIds || data.readyStepIds || data.awaitingSignalStepIds || typeof data.currentEpoch === 'number';
    if (hasSnapshotData) {
      this.processBatchUpdate(data);
      // Bump high-water mark only after a real snapshot is applied, so a
      // mis-routed non-snapshot event cannot starve subsequent legitimate
      // snapshots/REST refreshes via the strict-`<` guard.
      if (typeof data.seq === 'number') {
        this.lastKnownSeq = data.seq;
      }
    }

    // Expose seq to the store so useEpochStateViewing can re-fetch epoch data
    // when viewing a specific historical epoch and backend state changes.
    if (typeof data.seq === 'number') {
      this.store.setSnapshotSeq(data.seq);
    }
  }

  /**
   * Handle individual events received via WebSocket that are not batch-updates.
   * Routes workflowStatus, readySteps, and decisionEvaluated events
   * that may arrive as separate messages outside of batch-update snapshots.
   */
  handleEvent(eventType: string, data: any): void {
    if (!data) return;

    switch (eventType) {
      case 'workflowStatus': {
        // Guard: skip non-workflow statuses (e.g. 'CONNECTED' from handshake)
        if (!data.status || !KNOWN_WORKFLOW_STATUSES.has(data.status.toLowerCase())) {
          streamDebug.log('WorkflowRunManager', 'Ignoring non-workflow status:', data.status);
          return;
        }
        const newStatus = this.normalizeStatus(data.status);
        this.store.setWorkflowStatus({
          status: newStatus,
          durationMs: data.durationMs,
          stepsCompleted: data.stepsCompleted,
          totalSteps: data.totalSteps,
          progress: data.progress,
          message: data.message,
        });

        this.handleWorkflowStatusTransition(newStatus);
        break;
      }

      case 'readySteps': {
        // Seq-based stale event filtering: uses strict < (not <=) because
        // batch-update and readySteps share the same seq from a single state change.
        if (typeof data.seq === 'number') {
          if (data.seq < this.lastKnownSeq) {
            streamDebug.log('WorkflowRunManager', `Discarding stale readySteps: seq=${data.seq}, lastKnown=${this.lastKnownSeq}`);
            break;
          }
          this.lastKnownSeq = data.seq;
        }
        const state = this.store.getState();
        if (state.executionMode === 'step_by_step') {
          streamDebug.log('WorkflowRunManager', 'WS readySteps (step-by-step mode):', data.readySteps);
          this.store.setReadySteps(data.readySteps || []);

          // Backend enriches readySteps events with authoritative step ID sets
          // from StateSnapshot, so the frontend has a coherent view immediately.
          if (data.completedStepIds) {
            this.store.setCompletedStepsFromBackend(data.completedStepIds);
          }
          if (data.failedStepIds) {
            this.store.setFailedSteps(data.failedStepIds);
          }
          if (data.skippedStepIds) {
            this.store.setSkippedSteps(data.skippedStepIds);
          }
        }
        break;
      }

      case 'decisionEvaluated': {
        this.store.addDecisionEvaluation(data);
        break;
      }

      case 'workflowPaused': {
        this.store.setWorkflowStatus({
          status: 'paused',
          message: data.message,
        });
        // Pause events may include ready steps for SBS mode
        if (data.readySteps) {
          this.store.setReadySteps(data.readySteps);
        }
        break;
      }

      case 'workflowResuming': {
        this.store.setWorkflowStatus({
          status: 'running',
          message: data.message,
        });
        break;
      }

      case 'stepRerun': {
        // Always refresh - seq-based filtering protects against stale data.
        // Whether self-initiated or from another client, refresh ensures we have
        // the latest state; seq will discard any stale WS events that arrive afterward.
        streamDebug.log('WorkflowRunManager', 'stepRerun event received, refreshing state');
        this.refreshStateInternal().catch(err => {
          streamDebug.warn('WorkflowRunManager', 'Post-rerun refresh failed:', err);
        });
        break;
      }

      case 'agentBrowseStep': {
        // Mid-execution browser-agent live-view bootstrap. The backend
        // BrowserSessionLifecycleService publishes one of these on cdp_ready
        // (workflow path only) before the blocking tool call returns. The
        // payload carries snake_case fields the existing statusUpdater
        // already maps to lastBrowser* on the node - just patch the matching
        // step in batchSteps so useRunStateProcessing picks it up on the
        // next render. No tracking-set side effects.
        const nodeId = data.nodeId || data.node_id;
        if (!nodeId) break;
        const sessionId = typeof data.session_id === 'string' ? data.session_id : null;
        // Idempotency: a Redis pub/sub replay or duplicate publish would
        // otherwise trigger a redundant batchSteps update + node re-render
        // (and via updateTab → BrowserLiveCdpPanel WS reset, since the
        // panel keys its reconnect bookkeeping on cdpToken/sessionId/
        // cdpWsUrl). Java side already dedups by the toolId+sessionId
        // cache key - mirror it here for defense in depth.
        const dedupKey = sessionId ? `${nodeId}:${sessionId}` : nodeId;
        if (this.bootstrappedBrowserSessions.has(dedupKey)) {
          streamDebug.log('WorkflowRunManager',
            `agentBrowseStep skipped (already bootstrapped): ${dedupKey}`);
          break;
        }
        this.bootstrappedBrowserSessions.add(dedupKey);
        const fields: Record<string, unknown> = {};
        if (sessionId) fields.session_id = sessionId;
        if (typeof data.cdp_token === 'string') fields.cdp_token = data.cdp_token;
        if (typeof data.cdp_ws_url === 'string') fields.cdp_ws_url = data.cdp_ws_url;
        if (typeof data.run_id === 'string') fields.run_id = data.run_id;
        if (typeof data.runId === 'string' && !fields.run_id) fields.run_id = data.runId;
        if (typeof data.step_index === 'number') fields.step_index = data.step_index;
        if (typeof data.current_url === 'string') fields.current_url = data.current_url;
        this.store.patchBrowserLiveCoords(nodeId, fields);
        streamDebug.log('WorkflowRunManager', 'agentBrowseStep patched:', { nodeId, fields });
        break;
      }

      // 2026-05-05 wire-types alignment with backend EVENT_TYPE_WIRE_NAMES.
      // These camelCase types used to fall through to handleBatchUpdate where
      // they polluted lastKnownSeq without applying any state. Routed here as
      // explicit no-ops: the authoritative state still arrives via the
      // coalesced `batch-update` snapshot from SnapshotService.markDirty.
      // Per-event sub-updates (stepStatus, edgeStatus) are intentionally NOT
      // applied to the store here - the snapshot path is the single source
      // of truth for visualization (see processBatchUpdate).
      case 'stepStatus':
      case 'edgeStatus':
      case 'workflowStatistics':
      case 'loopEvent':
      case 'retryEvent':
      case 'debugLog':
      case 'mergeEvent':
      case 'agentToolCall':
      case 'workflowConfiguration': {
        streamDebug.log('WorkflowRunManager', `Sub-event ignored (covered by batch-update): ${eventType}`);
        break;
      }

      // Live run-cost update: an agent execution settled its credits. Payload
      // { epoch, epochCostCredits, totalCostCredits, budgetCredits }. The total
      // is authoritative (accumulated across all epochs).
      case 'runCost': {
        this.store.setRunCost(
          typeof data.totalCostCredits === 'number' ? data.totalCostCredits : undefined,
          data.budgetCredits ?? null,
          typeof data.epoch === 'number' ? data.epoch : undefined,
          typeof data.epochCostCredits === 'number' ? data.epochCostCredits : undefined,
        );
        break;
      }

      // Budget reached: a new epoch was refused (the in-flight epoch, if any,
      // still finishes). Surface an explanatory toast to any live viewer via a
      // global CustomEvent (this manager is not a React component).
      case 'runBudgetBlocked': {
        try {
          if (typeof window !== 'undefined') {
            window.dispatchEvent(new CustomEvent('workflow:runBudgetBlocked', {
              detail: {
                runId: data.runId ?? this.runId,
                spentCredits: data.spentCredits ?? null,
                budgetCredits: data.budgetCredits ?? null,
              },
            }));
          }
        } catch {
          // best-effort toast only
        }
        break;
      }

      default:
        streamDebug.log('WorkflowRunManager', `Unhandled event type: ${eventType}`);
    }
  }

  // --------------------------------------------------------------------------
  // STEP EXECUTION
  // --------------------------------------------------------------------------

  /**
   * Execute a step or trigger.
   * Returns the result with updated ready steps.
   * Requests are deduplicated -- calling with the same stepId while in-flight returns the same promise.
   */
  async executeStep(stepId: string, payload?: Record<string, unknown>, triggerTypeOverride?: string, epoch?: number): Promise<StepExecutionResult> {
    const dedupeKey = epoch != null ? `execute:${stepId}:e${epoch}` : `execute:${stepId}`;
    const existing = this.inFlightRequests.get(dedupeKey);
    if (existing) {
      streamDebug.log('WorkflowRunManager', `Deduplicated executeStep for ${stepId}`);
      return existing;
    }

    const promise = this.doExecuteStep(stepId, payload, triggerTypeOverride, epoch).finally(() => {
      this.inFlightRequests.delete(dedupeKey);
    });

    this.inFlightRequests.set(dedupeKey, promise);
    return promise;
  }

  private async doExecuteStep(stepId: string, payload?: Record<string, unknown>, triggerTypeOverride?: string, epoch?: number): Promise<StepExecutionResult> {
    const state = this.store.getState();
    this.store.setLoading(true);

    try {
      const isTrigger = stepId.startsWith('trigger:');
      const isStepByStep = state.executionMode === 'step_by_step';

      streamDebug.log('WorkflowRunManager', 'executeStep routing:', {
        stepId, isTrigger, isStepByStep,
        runStatus: state.runStatus,
        readySteps: Array.from(state.readySteps),
      });

      // Triggers ALWAYS use the dedicated trigger endpoint (triggerSpecific),
      // regardless of current runStatus. The UI gates when the trigger button
      // is shown (via isWaitingForTrigger / availableTriggers), so if the user
      // can click it, we should route to the trigger path.
      if (isTrigger) {
        streamDebug.log('WorkflowRunManager', 'Routing to executeTrigger (trigger path)');
        return await this.executeTrigger(stepId, payload, triggerTypeOverride);
      }

      if (isStepByStep) {
        streamDebug.log('WorkflowRunManager', 'Routing to executeStepByStep, epoch:', epoch);
        return await this.executeStepByStep(stepId, epoch);
      }

      // Automatic mode - simple execution
      streamDebug.log('WorkflowRunManager', 'Routing to executeAutomatic (fallthrough)');
      return await this.executeAutomatic(stepId);
    } catch (err: any) {
      // CE cloud-relay errors (insufficient cloud credit / unmanaged model) pop their own
      // actionable modal; route them before the generic 402 path (whose Cloud Stripe modal is a
      // no-op in CE). No-op in the Cloud edition, so the 402 path below is unchanged there.
      if (handleCeRelayError(err)) {
        return { success: false, stepId, status: 'CLOUD_RELAY_BLOCKED', readySteps: [] };
      }
      // Handle 402 PAYMENT REQUIRED (insufficient credits)
      if (is402Error(err)) {
        showInsufficientCreditsModal();
        return { success: false, stepId, status: 'INSUFFICIENT_CREDITS', readySteps: [] };
      }
      // Handle 413 PAYLOAD TOO LARGE (storage quota exceeded)
      if (is413StorageError(err)) {
        showInsufficientStorageModal();
        return { success: false, stepId, status: 'STORAGE_QUOTA_EXCEEDED', readySteps: [] };
      }
      // Handle 409 CONFLICT (node state changed by another user or already claimed)
      if (err?.status === 409 || err?.response?.status === 409) {
        streamDebug.log('WorkflowRunManager', 'Node conflict (409), refreshing state');
        await this.refreshStateInternal();
        this.store.setError('Node is no longer available (state changed by another user)');
        return { success: false, stepId, status: 'CONFLICT', readySteps: [] };
      }
      console.error('[WorkflowRunManager] Step execution failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  /**
   * Look up the parent workflowId for a given workflow-trigger stepId in the
   * current plan. Workflow triggers carry the parent workflow's UUID in the
   * trigger.id field; this matches by normalized key (trigger:label).
   */
  private findWorkflowTriggerParentId(plan: any, stepId: string): string | null {
    if (!plan || !Array.isArray(plan.triggers)) return null;
    for (const t of plan.triggers) {
      if (t?.type !== 'workflow') continue;
      const label = t.label ?? t.name ?? '';
      const key = `trigger:${normalizeLabel(label)}`;
      if (key === stepId) return t.id ?? null;
    }
    return null;
  }

  private async executeTrigger(stepId: string, payload?: Record<string, unknown>, triggerTypeOverride?: string): Promise<StepExecutionResult> {
    const state = this.store.getState();
    const plan = this.getCurrentPlan?.() ?? undefined;
    const triggerType = triggerTypeOverride || state.triggerType || 'manual';

    // Workflow-trigger simulate: auto-fill payload with the parent workflow's
    // latest cycle result, mirroring WorkflowTriggerDispatchService.buildTriggerPayload.
    // The parent run's metadata.lastCycleResult is the same shape that
    // dispatchCycleCompletion sends to downstream workflows in production:
    // a map of normalized stepId → step output (built from execution.getStepOutputs()).
    if (triggerType === 'workflow' && (!payload || Object.keys(payload).length === 0)) {
      try {
        const parentWorkflowId = this.findWorkflowTriggerParentId(plan, stepId);
        if (parentWorkflowId) {
          const parentRun = await orchestratorApi.getLatestWorkflowRun(parentWorkflowId);
          if (parentRun) {
            const lastCycleResult = (parentRun.metadata?.lastCycleResult ?? {}) as Record<string, unknown>;
            // Mirror WorkflowTriggerDispatchService.buildTriggerPayload shape:
            // { result, status, triggeredAt, parentWorkflowId, parentRunId, statistics }.
            // Statistics are populated from in-memory ExecutionStatistics in
            // production; for simulate we send an empty shim so downstream
            // expressions like `{{trigger:wf.output.statistics.completedSteps}}`
            // resolve to 0 instead of crashing.
            payload = {
              result: lastCycleResult,
              status: parentRun.status,
              triggeredAt: new Date().toISOString(),
              parentWorkflowId,
              parentRunId: parentRun.runId ?? parentRun.id,
              statistics: { completedSteps: 0, failedSteps: 0, totalSteps: 0 },
            };
            streamDebug.log('WorkflowRunManager', 'Workflow-trigger simulate auto-payload built', {
              parentWorkflowId, parentRunId: parentRun.runId, resultKeys: Object.keys(lastCycleResult),
            });
          }
        }
      } catch (e) {
        streamDebug.warn('WorkflowRunManager', 'Failed to auto-fill workflow trigger payload, firing with empty payload', e);
      }
    }

    // Reset state for new cycle: clears previous cycle's completed/failed/skipped steps,
    // sets status to 'running', and bypasses the terminal state guard.
    // Must reset in ALL modes (including step-by-step) to prevent stale epoch N-1
    // status sets from leaking into epoch N (e.g. user_approval showing "completed"
    // from epoch 1 instead of "awaiting_signal" in epoch 2).
    streamDebug.log('WorkflowRunManager', 'executeTrigger: resetForNewCycle BEFORE', {
      stepId,
      prevStatus: state.runStatus,
      prevCompleted: state.completedSteps?.size,
      prevExecutionTotal: state.executionTotal,
      prevBatchStepsCount: state.batchSteps?.length,
      prevEpoch: state.currentEpoch,
    });
    this.store.resetForNewCycle();
    this.recentlyResetSteps.clear(); // Fresh epoch - no stale reset steps
    streamDebug.log('WorkflowRunManager', 'executeTrigger: resetForNewCycle AFTER (batchSteps preserved)');

    // Call trigger API (backend executes workflow during this request)
    const response = await orchestratorApi.triggerSpecific(
      this.runId,
      stepId,
      triggerType,
      payload,
      plan
    );

    // Safety fallback: if WebSocket events are lost, refresh after
    // a longer delay to eventually correct the state.
    this.scheduleTimer(() => {
      const currentStatus = this.store.getState().runStatus;
      if (currentStatus === 'running') {
        streamDebug.log('WorkflowRunManager', 'Safety refresh: state still running after trigger');
        this.refreshStateInternal().catch(err => {
          streamDebug.warn('WorkflowRunManager', 'Safety refresh failed:', err);
        });
      }
    }, SAFETY_REFRESH_DELAY_MS);

    // In step-by-step mode, set ready steps from trigger API response (authoritative REST data).
    if (state.executionMode === 'step_by_step') {
      if (response.readySteps && response.readySteps.length > 0) {
        this.store.setReadySteps(response.readySteps);
      }
    }

    return {
      success: response.status === 'triggered',
      stepId,
      status: response.status,
      readySteps: response.readySteps ?? [],
    };
  }

  /**
   * Execute any node (step, core, trigger) via WebSocket action.
   * Results arrive via WS events (batch-update, readySteps, decisionEvaluated).
   *
   * @param nodeId The node to execute
   * @param epoch Optional epoch for parallel epoch execution
   */
  private async doExecuteNode(nodeId: string, epoch?: number): Promise<void> {
    const plan = this.getCurrentPlan?.() ?? undefined;

    await wsClient.sendAction('sbs.execute', {
      runId: this.runId,
      nodeId,
      ...(plan ? { plan } : {}),
      ...(epoch != null ? { epoch } : {}),
    });

    // Ack received - execution is in progress.
    // WS events (batch-update, readySteps, decisionEvaluated) will update the store.
    streamDebug.log('WorkflowRunManager', `SBS execute ack received for: ${nodeId} epoch: ${epoch}`);
  }

  private async executeStepByStep(stepId: string, epoch?: number): Promise<StepExecutionResult> {
    await this.doExecuteNode(stepId, epoch);

    // The ack means the backend accepted the request. Results arrive via WS events:
    // - batch-update: node status changes (RUNNING -> COMPLETED/FAILED)
    // - readySteps: next executable nodes
    // - decisionEvaluated: branch selection data
    // The store is updated by handleEvent() and handleBatchUpdate().

    return {
      success: true,
      stepId,
      status: 'ACCEPTED',
      readySteps: [], // Will be updated by WS readySteps event
    };
  }

  private async executeAutomatic(stepId: string): Promise<StepExecutionResult> {
    await orchestratorApi.executeSingleStep(this.runId, stepId);
    await this.refreshStateInternal();

    return {
      success: true,
      stepId,
      status: 'COMPLETED',
      readySteps: Array.from(this.store.getState().readySteps),
    };
  }

  // --------------------------------------------------------------------------
  // CORE NODE EXECUTION (Decision, etc.)
  // --------------------------------------------------------------------------

  /**
   * Execute a core node (decision, switch, etc.).
   * Requests are deduplicated.
   */
  async executeCore(coreId: string): Promise<CoreExecutionResult> {
    const dedupeKey = `core:${coreId}`;
    const existing = this.inFlightRequests.get(dedupeKey);
    if (existing) {
      streamDebug.log('WorkflowRunManager', `Deduplicated executeCore for ${coreId}`);
      return existing;
    }

    const promise = this.doExecuteCore(coreId).finally(() => {
      this.inFlightRequests.delete(dedupeKey);
    });

    this.inFlightRequests.set(dedupeKey, promise);
    return promise;
  }

  private async doExecuteCore(coreId: string): Promise<CoreExecutionResult> {
    this.store.setLoading(true);

    try {
      // Use the same WS path as regular steps - core nodes are executed
      // through V2StepByStepService.executeNode() on the backend.
      // Results (selectedBranch, skippedBranches, readySteps) arrive via WS events:
      // - decisionEvaluated: branch selection data
      // - batch-update: edge/node status changes
      // - readySteps: next executable nodes
      await this.doExecuteNode(coreId);

      return {
        success: true,
        coreId,
        selectedBranch: '', // Updated by WS decisionEvaluated event
        skippedBranches: [],
        readySteps: [], // Updated by WS readySteps event
      };
    } catch (err) {
      console.error('[WorkflowRunManager] Core execution failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  // --------------------------------------------------------------------------
  // RERUN
  // --------------------------------------------------------------------------

  /**
   * Rerun a step (reset downstream and re-execute).
   */
  async rerunStep(stepId: string): Promise<RerunResult> {
    this.store.setLoading(true);

    try {
      const plan = this.getCurrentPlan?.() ?? undefined;
      const response = await orchestratorApi.rerunFromStep(this.runId, stepId, plan);

      // Update seq from response (rerun wrote to StateSnapshot, seq was bumped).
      // This ensures any stale WS events from before the rerun are discarded.
      if (typeof response.seq === 'number') {
        this.lastKnownSeq = response.seq;
      }

      // Update state from response (pass actual status from backend)
      this.store.resetForRerun(
        response.resetSteps || [],
        response.readySteps || [],
        response.epoch || 0,
        response.status
      );

      // Cancel ALL pending timers to prevent stale refreshes (e.g., POST_COMPLETION_REFRESH
      // or safety refresh) from overwriting the freshly-reset state with pre-rerun data.
      // This is critical: a getRunState() request dispatched before the rerun could return
      // stale state and overwrite the correct post-rerun state via initializeFromApi().
      for (const timerId of this.pendingTimers) {
        clearTimeout(timerId);
      }
      this.pendingTimers.clear();

      // Track recently reset steps. When initializeFromApi runs (from WS stepRerun refresh),
      // it will exclude these from completedSteps because NodeCounts-based completedStepIds
      // are stale (NodeCounts never reset). The recentlyResetSteps are cleared when the
      // next batch-update or trigger fire provides fresh state.
      this.recentlyResetSteps = new Set(response.resetSteps || []);
      this.rerunSeq = typeof response.seq === 'number' ? response.seq : this.lastKnownSeq;

      const readySteps = response.readySteps || [];

      return {
        success: true,
        stepId,
        resetSteps: response.resetSteps || [],
        readySteps,
        epoch: response.epoch || 0,
        spawn: response.spawn || 0,
        seq: response.seq || 0,
      };
    } catch (err) {
      console.error('[WorkflowRunManager] Rerun failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  // --------------------------------------------------------------------------
  // SIGNAL RESOLUTION (User Approval)
  // --------------------------------------------------------------------------

  /**
   * Resolve a user approval signal (approve or reject).
   * When epoch is undefined (all-epoch view), resolves ALL pending USER_APPROVAL signals
   * for this node across all epochs. When epoch is specified, resolves only that epoch's signal.
   */
  async resolveApproval(nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string): Promise<void> {
    this.store.setLoading(true);

    try {
      if (itemId != null) {
        // Per-item resolution (split context): resolve only this specific item's signal
        const response = await orchestratorApi.resolveSignal(this.runId, nodeId, resolution, undefined, epoch, itemId);
        streamDebug.log('WorkflowRunManager', 'Item signal resolved:', { nodeId, resolution, epoch, itemId, status: response.status });
      } else if (epoch === undefined) {
        // All-epoch view: bulk resolve all pending signals for this node
        const response = await orchestratorApi.resolveAllSignals(this.runId, nodeId, resolution);
        streamDebug.log('WorkflowRunManager', 'All signals resolved:', { nodeId, resolution, count: response.count });
      } else {
        // Epoch-specific view: resolve all signals for this epoch (handles split items)
        const response = await orchestratorApi.resolveAllSignals(this.runId, nodeId, resolution, undefined, epoch);
        streamDebug.log('WorkflowRunManager', 'Epoch signals resolved:', { nodeId, resolution, epoch, count: response.count });
      }

      // No optimistic updates - but don't depend on a live WS batch either:
      // re-hydrate authoritative state (incl. pendingSignals via the /signals
      // fetch in refreshStateInternal) from REST so sessions without a
      // connected batch stream still see the resolved signal disappear and
      // the approval UI converge. Non-force (like the cancel/reactivate
      // siblings): the resolution advanced the backend seq, so a fresh
      // snapshot passes the staleness check on its own, while WS-connected
      // sessions whose batch already arrived correctly skip the older REST
      // snapshot.
      await this.refreshStateInternal();
    } catch (err) {
      console.error('[WorkflowRunManager] Signal resolution failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  // --------------------------------------------------------------------------
  // EXECUTION MODE
  // --------------------------------------------------------------------------

  /**
   * Set execution mode (automatic or step_by_step).
   */
  async setExecutionMode(mode: 'automatic' | 'step_by_step'): Promise<void> {
    this.store.setLoading(true);

    try {
      const response = await orchestratorApi.setExecutionMode(this.runId, mode);

      this.store.setExecutionMode(mode);
      if (response.readySteps) {
        this.store.setReadySteps(response.readySteps);
      }
    } catch (err) {
      console.error('[WorkflowRunManager] Set execution mode failed:', err);
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  // --------------------------------------------------------------------------
  // PAUSE/RESUME
  // --------------------------------------------------------------------------

  async pause(): Promise<void> {
    this.store.setLoading(true);
    try {
      await orchestratorApi.pauseWorkflow(this.runId);
      this.store.setRunStatus('paused');
    } catch (err) {
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  async resume(): Promise<void> {
    this.store.setLoading(true);
    try {
      await orchestratorApi.resumeWorkflow(this.runId);
      this.store.setRunStatus('running');
    } catch (err) {
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  async cancel(): Promise<void> {
    this.store.setLoading(true);
    try {
      // Graceful stop: close active epochs, return to WAITING_TRIGGER.
      // The run stays alive for future trigger fires (pinned version safe).
      await orchestratorApi.stopWorkflow(this.runId);
      await this.refreshStateInternal();
    } catch (err) {
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  /**
   * Hard cancel: permanently terminate the run (terminal CANCELLED status).
   * Unlike cancel() which gracefully stops and returns to WAITING_TRIGGER,
   * this is irreversible - the run cannot be resumed or re-triggered.
   */
  async hardCancel(): Promise<void> {
    this.store.setLoading(true);
    try {
      await orchestratorApi.cancelWorkflow(this.runId);
      await this.refreshStateInternal();
    } catch (err) {
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  /**
   * Reactivate a cancelled run: return it to WAITING_TRIGGER so triggers can fire again.
   */
  async reactivate(): Promise<void> {
    this.store.setLoading(true);
    try {
      await orchestratorApi.reactivateWorkflow(this.runId);
      await this.refreshStateInternal();
    } catch (err) {
      this.store.setError(this.getErrorMessage(err));
      throw err;
    } finally {
      this.store.setLoading(false);
    }
  }

  // --------------------------------------------------------------------------
  // STATE ACCESS (public API -- no (manager as any).store needed)
  // --------------------------------------------------------------------------

  /**
   * Get current state (read-only).
   */
  getState(): Readonly<RunState> {
    return this.store.getState();
  }

  /**
   * Subscribe to state changes.
   */
  subscribe(listener: StateListener): () => void {
    return this.store.subscribe(listener);
  }

  /**
   * Check if step is ready.
   */
  canExecuteStep(stepId: string): boolean {
    return this.store.isReady(stepId);
  }

  /**
   * Check if step can be rerun.
   */
  canRerunStep(stepId: string): boolean {
    return this.store.canRerun(stepId);
  }

  // --------------------------------------------------------------------------
  // PUBLIC STORE DELEGATES (encapsulated access, no `as any` needed)
  // --------------------------------------------------------------------------

  /** Set ready steps from an external source. */
  setReadySteps(steps: string[]): void {
    this.store.setReadySteps(steps);
  }

  /** Set run status from an external source. */
  setRunStatus(status: RunState['runStatus']): void {
    this.store.setRunStatus(status);
  }

  /** Set connection status from an external source. */
  setConnected(connected: boolean): void {
    this.store.setConnected(connected);
  }

  // --------------------------------------------------------------------------
  // CONFIGURATION
  // --------------------------------------------------------------------------

  /**
   * Set callback for getting current plan.
   */
  setCurrentPlanGetter(getter: (() => Record<string, unknown> | null) | null): void {
    this.getCurrentPlan = getter;
  }

  // --------------------------------------------------------------------------
  // CLEANUP
  // --------------------------------------------------------------------------

  /**
   * Full cleanup.
   */
  destroy(): void {
    // Clear all pending timers
    for (const timerId of this.pendingTimers) {
      clearTimeout(timerId);
    }
    this.pendingTimers.clear();

    // Clear in-flight requests
    this.inFlightRequests.clear();

    this.store.reset();
    this.initialized = false;
    this.getCurrentPlan = null;
    // 2026-05-04 hot-fix (audit C2): reset lastKnownSeq so a fresh init after
    // destroy doesn't get stuck filtering all REST tracking as "stale".
    // Bug pattern: WS bumped lastKnownSeq=200 (WsEventSequencer counter); REST
    // returns seq=30 (StateSnapshot counter); doInitialize partial-apply
    // skip-if-stale → tracking never applied → UI frozen.
    this.lastKnownSeq = -1;
    this.rerunSeq = -1;
  }

  /**
   * Phase E (archi-refoundation 2026-05-04) - soft pause: clear pending timers
   * and abandon in-flight requests, but KEEP the store contents so a subsequent
   * {@link wakeUp} can resume without re-fetching the full plan.
   *
   * <p>Called by {@code WorkflowRunContext} when the tab has been hidden
   * (visibilityState='hidden') for more than 5 minutes - at that point the WS
   * has very likely been dropped by the browser anyway, and any data we hold
   * is stale. Unlike {@link destroy}, suspend does NOT reset the store: the
   * user's last known view is preserved until they come back, at which point
   * {@link wakeUp} fetches a fresh state.
   */
  suspend(): void {
    // Drop pending timers - they'd fire on a stale state
    for (const timerId of this.pendingTimers) {
      clearTimeout(timerId);
    }
    this.pendingTimers.clear();
    // Drop in-flight requests - they'd resolve into stale state
    this.inFlightRequests.clear();
    // Mark not-initialized so subsequent initialize() actually re-fetches.
    // The store contents remain (frozen view); they will be refreshed by wakeUp.
    this.initialized = false;
    // 2026-05-04 hot-fix (audit C2): reset lastKnownSeq. Otherwise a tab
    // resumed after >5min hidden has lastKnownSeq from old WS counter;
    // wakeUp's REST returns a smaller StateSnapshot.seq → partial-apply
    // skips tracking → UI stuck on the frozen pre-suspend view forever.
    this.lastKnownSeq = -1;
    this.rerunSeq = -1;
    streamDebug.log('WorkflowRunManager', 'suspended', { runId: this.runId });
  }

  /**
   * Phase E - counterpart of {@link suspend}: re-fetch fresh state via the
   * normal initialize() path. Idempotent: if the manager is already
   * initialized (suspend never ran), this is a no-op.
   */
  async wakeUp(): Promise<void> {
    if (this.initialized) return;
    streamDebug.log('WorkflowRunManager', 'wakeUp → re-initializing', { runId: this.runId });
    await this.initialize();
  }

  // --------------------------------------------------------------------------
  // STATE REFRESH
  // --------------------------------------------------------------------------

  /**
   * Force refresh state from REST API.
   * Unlike initialize(), this always fetches fresh data.
   * Uses force=true to bypass seq guard (user-initiated refresh should always apply).
   */
  async refresh(): Promise<void> {

    await this.refreshStateInternal(/* force */ true);
  }

  // --------------------------------------------------------------------------
  // PRIVATE HELPERS
  // --------------------------------------------------------------------------

  /**
   * Refresh state from REST API.
   * Guards against stale API responses overwriting fresher WS-derived state
   * by comparing the API's seq with our lastKnownSeq.
   *
   * @param force If true, bypass the seq guard (for user-initiated refreshes)
   */
  private async refreshStateInternal(force: boolean = false): Promise<void> {
    const t0 = performance.now();
    console.log('[RUN-MOUNT]', t0.toFixed(0), `refreshStateInternal force=${force} START`, this.runId);
    const runState = await fetchRunStatePossiblyPublic(this.runId);
    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'refreshStateInternal fetch DONE', this.runId, `(${(performance.now() - t0).toFixed(0)}ms)`, `apiSeq=${runState.seq}`, `lastKnown=${this.lastKnownSeq}`);

    // Protect against stale API data overwriting fresher WS-derived state.
    // This prevents a race condition where an async refresh (e.g., from stepRerun WS event)
    // returns stale data that overwrites correct state derived from WS batch-updates.
    if (!force && typeof runState.seq === 'number' && runState.seq < this.lastKnownSeq) {
      streamDebug.log('WorkflowRunManager',
        `Skipping stale API refresh: apiSeq=${runState.seq}, lastKnown=${this.lastKnownSeq}`);
      return;
    }

    // Update seq from API response (authoritative state from backend)
    if (typeof runState.seq === 'number') {
      this.lastKnownSeq = runState.seq;
    }

    // Inject reusable triggers into readySteps (same logic as doInitialize).
    // After completion, the backend may not include triggers in readySteps,
    // but the UI still needs them so the user can re-trigger the workflow.
    const readySteps = this.injectReusableTriggers(runState);

    // Filter out recently-reset steps from completedStepIds AND steps data.
    // After rerun, the backend's NodeCounts still show historical completions,
    // so completedStepIds includes steps that were just reset. We exclude them
    // so the UI shows PLAY (ready) instead of RERUN (completed).
    // The filter is cleared on the next trigger fire or batch-update with readyStepIds confirmation.
    let completedStepIds = runState.completedStepIds || [];
    let steps = runState.steps;
    if (this.recentlyResetSteps.size > 0) {
      const resetSet = this.recentlyResetSteps;
      completedStepIds = completedStepIds.filter((id: string) => !resetSet.has(id));
      // Also patch steps data: initializeFromApi re-derives completedStepIds from steps
      // (ignoring the completedStepIds param). Mark reset steps PENDING so they are not
      // re-derived as completed (they show a Play button), but PRESERVE their statusCounts
      // (do NOT zero) - a rerun must not blank the node: the accumulated badge, and via the
      // counts the accumulated border, stay visible until the step actually re-runs.
      if (steps?.length) {
        steps = steps.map((step: any) => {
          const stepId = step.stepId || step.normalizedStepId || step.id;
          if (stepId && resetSet.has(stepId)) {
            return { ...step, status: 'PENDING' };
          }
          return step;
        });
      }
      streamDebug.log('WorkflowRunManager', 'Filtered recentlyResetSteps from completedStepIds (counts preserved):', Array.from(resetSet));
    }

    this.store.initializeFromApi({
      runId: this.runId,
      workflowId: runState.workflowId,
      status: runState.status || 'pending',
      executionMode: runState.executionMode,
      triggerType: this.extractTriggerType(runState),
      startedAt: runState.startedAt,
      endedAt: runState.endedAt,
      completedAt: runState.completedAt,
      durationMs: runState.durationMs,
      readySteps,
      completedStepIds,
      failedStepIds: runState.failedStepIds,
      skippedStepIds: runState.skippedStepIds,
      runningStepIds: runState.runningStepIds,
      steps,
      edges: runState.edges,
      rawState: runState,
      currentEpoch: runState.currentEpoch,
      epochTimestamps: runState.epochTimestamps,
    });

    // GET /state carries no signal rows - hydrate pendingSignals from the
    // signals endpoint so a page load during a pending approval / interface
    // wait still shows the per-item signal lists (WS batch-updates only reach
    // sessions that were already connected when the signal registered).
    // Best-effort: a failure here leaves the WS path as the only source,
    // which was the previous behavior.
    const seqBeforeSignalFetch = this.lastKnownSeq;
    try {
      const signals = await orchestratorApi.execution.getRunSignals(this.runId);
      // A WS batch landing while this GET was in flight is fresher than the
      // REST snapshot (it may carry a just-registered signal) - keep the
      // file's seq discipline and let the batch win.
      if (this.lastKnownSeq === seqBeforeSignalFetch) {
        this.store.setPendingSignals(
          (signals || []).filter((signal) => signal.status === 'PENDING'),
        );
      }
    } catch (err) {
      streamDebug.log('WorkflowRunManager', 'pendingSignals hydration failed (non-fatal):', err);
    }

    // Safety net: detect running agents/sub-workflows from REST data in case WS batch-update was missed.
    // Fires at most once per stepId per epoch (idempotent).
    for (const step of (runState.steps || [])) {
      const stepId = step.stepId || step.normalizedStepId;
      const status = (step.status || '').toUpperCase();
      if (stepId?.startsWith('agent:') && status === 'RUNNING') {
        this.handleAgentStepRunning(stepId, step);
      }
      if (stepId?.startsWith('core:') && status === 'RUNNING') {
        this.handleSubWorkflowStepRunning(stepId, step);
      }
    }
  }

  /**
   * Handle workflow status transitions that require side effects
   * (terminal status refresh, waiting_trigger state refresh).
   */
  private handleWorkflowStatusTransition(newStatus: RunState['runStatus']): void {
    // Transition to waiting_trigger (multi-DAG: one DAG completed, another has trigger ready).
    // No REST refresh needed - the WS batch-update already delivers readyStepIds,
    // tracking sets, and epoch data. With 12 parallel epochs this would fire 12x.
    if (newStatus === 'waiting_trigger') {
      // Reset tracking for next epoch
      this.interfaceAwaitingEmitted.clear();
      this.agentRunningEmitted.clear();
      this.subWorkflowRunningEmitted.clear();
      this.failureEmitted.clear();
      const st = this.store.getState();
      streamDebug.log('WorkflowRunManager', 'waiting_trigger transition - runInfo snapshot:', {
        completedSteps: st.completedSteps?.size,
        failedSteps: st.failedSteps?.size,
        executionTotal: st.executionTotal,
        batchStepsCount: st.batchSteps?.length,
        epoch: st.currentEpoch,
      });
      return;
    }

    // Workflow finished -- refresh state for reusable triggers
    if (TERMINAL_STATUSES.has(newStatus)) {
      // Notify UI of workflow failure/timeout/cancellation for toast
      if (newStatus === 'failed' || newStatus === 'timeout') {
        this.handleWorkflowFailed(newStatus);
      }
      // A terminal run has nothing running by definition. Clear the running set
      // here so a node never stays painted "running"/"Thinking..." forever when its
      // terminal status event was dropped (mid-run subscribe, missed batch-update,
      // lost snapshot). The backend also reports zero running for terminal runs
      // (SnapshotService + StateReconstructor terminal short-circuit + overlay purge
      // on cleanup); this view-layer clear hardens the heal against event loss. The
      // only residual delay is the <=600ms min-shimmer window for a node that had
      // just started - irrelevant to the stuck-forever case this fixes.
      this.store.setRunningSteps([]);
      streamDebug.log('WorkflowRunManager', 'Workflow finished, scheduling post-completion refresh');

      // For reusable triggers: refresh state from API after a delay
      // to pick up readySteps for the next cycle.
      this.scheduleTimer(() => {
        streamDebug.log('WorkflowRunManager', 'Refreshing state after workflow completion');
        this.refreshStateInternal().catch(err => {
          streamDebug.warn('WorkflowRunManager', 'Post-completion refresh failed:', err);
        });
      }, POST_COMPLETION_REFRESH_DELAY_MS);
    }
  }

  private processBatchUpdate(data: any): void {
    const batchStepsSource = data.steps || data.nodes || data.stepStates;
    // DEBUG: trace core steps in batch-update
    const coreStepsInBatch = (batchStepsSource || []).filter((s: any) => {
      const id = s.id || s.nodeId || s.stepId || '';
      return id.startsWith('core:');
    });
    streamDebug.log('WorkflowRunManager', `🔍 processBatchUpdate core steps (total: ${(batchStepsSource || []).length})`, coreStepsInBatch.map((s: any) => ({
      id: s.id || s.stepId,
      status: s.status,
      statusCounts: s.statusCounts,
    })));

    this.store.updateVisualization({
      steps: batchStepsSource,
      edges: data.edges,
      loops: data.loops,
    });

    // Backend sends two sources of truth for tracking sets:
    // 1. data.steps - per-step status derived from global NodeCounts (accurate across ALL epochs)
    // 2. data.completedStepIds etc. - from StateSnapshot flat views (only active epochs)
    //
    // After epoch close, flat views become empty (no active epochs) but NodeCounts
    // still have terminal data. Derive accumulated statuses (completed/failed/skipped/running)
    // from steps for accuracy. Keep ephemeral statuses (ready/awaitingSignal) from flat views.
    const stepsArray = data.steps || data.nodes || [];

    if (stepsArray.length > 0) {
      const derived = this.deriveTrackingSetsFromSteps(stepsArray);
      // readyStepIds from flat views is the authoritative CURRENT state.
      // NodeCounts-derived "completed" is historical (never reset across epochs).
      // If backend says a step is "ready", it should NOT be in completedSteps -
      // otherwise deriveNodeStatus (completed > ready) shows RERUN instead of PLAY.
      // Triggers are included in this filter: they no longer have a rerun concept,
      // they always show play/shimmer when ready (each fire = new epoch).
      const readySet = new Set<string>(data.readyStepIds || []);
      let completedForStore = derived.completed;
      if (readySet.size > 0) {
        completedForStore = completedForStore.filter((id: string) =>
          !readySet.has(id));
      }
      // Also filter recentlyResetSteps - NodeCounts never reset, so steps
      // just reset by rerun still appear "completed" until the backend progresses past the rerun.
      if (this.recentlyResetSteps.size > 0) {
        const batchSeq = typeof data.seq === 'number' ? data.seq : -1;
        if (batchSeq > this.rerunSeq) {
          streamDebug.log('WorkflowRunManager', 'Batch seq > rerun seq, clearing recentlyResetSteps',
            { batchSeq, rerunSeq: this.rerunSeq });
          this.recentlyResetSteps.clear();
        } else {
          completedForStore = completedForStore.filter((id: string) => !this.recentlyResetSteps.has(id));
          streamDebug.log('WorkflowRunManager', 'Filtered recentlyResetSteps from batch completed:',
            { recentlyReset: Array.from(this.recentlyResetSteps), batchSeq, rerunSeq: this.rerunSeq });
        }
      }
      this.store.setCompletedStepsFromBackend(completedForStore);
      this.store.setFailedSteps(derived.failed);
      this.store.setSkippedSteps(derived.skipped);
      this.store.setRunningSteps(derived.running);

      // Log runInfo-relevant data for debugging "all epochs" view
      const execTotal = stepsArray.reduce((sum: number, s: any) => {
        const sc = s.statusCounts;
        return sum + (sc ? (sc.completed || 0) + (sc.failed || 0) + (sc.skipped || 0) : 0);
      }, 0);
      streamDebug.log('WorkflowRunManager', 'runInfo tracking sets:', {
        completed: derived.completed.length,
        failed: derived.failed.length,
        skipped: derived.skipped.length,
        running: derived.running.length,
        executionTotal: execTotal,
        epoch: data.currentEpoch,
        stepsWithCounts: stepsArray
          .filter((s: any) => s.statusCounts && (s.statusCounts.completed > 0 || s.statusCounts.failed > 0))
          .map((s: any) => `${s.id}:c=${s.statusCounts.completed},f=${s.statusCounts.failed},s=${s.statusCounts.skipped}`),
      });
    } else {
      // No steps data - fall back to flat view arrays
      if (data.completedStepIds) {
        let completedIds = data.completedStepIds;
        if (this.recentlyResetSteps.size > 0) {
          const batchSeq = typeof data.seq === 'number' ? data.seq : -1;
          if (batchSeq > this.rerunSeq) {
            this.recentlyResetSteps.clear();
          } else {
            completedIds = completedIds.filter((id: string) => !this.recentlyResetSteps.has(id));
          }
        }
        this.store.setCompletedStepsFromBackend(completedIds);
      }
      if (data.failedStepIds) {
        this.store.setFailedSteps(data.failedStepIds);
      }
      if (data.skippedStepIds) {
        this.store.setSkippedSteps(data.skippedStepIds);
      }
      if (data.runningStepIds) {
        this.store.setRunningSteps(data.runningStepIds);
      }
    }

    // Ephemeral statuses: always from flat views (context-dependent on active epochs)
    if (data.readyStepIds) {
      this.store.setReadySteps(data.readyStepIds);
    }
    if (data.awaitingSignalStepIds) {
      this.store.setAwaitingSignalSteps(data.awaitingSignalStepIds);
    }
    if (data.pendingSignals) {
      this.store.setPendingSignals(data.pendingSignals);
    }

    // Update epoch data from snapshot (keeps run info panel in sync)
    if (typeof data.currentEpoch === 'number') {
      this.store.setEpochData(data.currentEpoch, data.epochTimestamps);
    }

    // Per-epoch ready steps for SBS parallel epoch support
    if (data.epochReadySteps || data.activeEpochs) {
      this.store.setEpochReadySteps(
        data.epochReadySteps || {},
        data.activeEpochs || [],
      );
    }

    // Update cumulative duration from backend (accumulated across all epochs)
    if (typeof data.totalDurationMs === 'number') {
      this.store.setTotalDurationMs(data.totalDurationMs);
    }

    // Handle interface node status updates (for pending-interfaces store)
    const stepsSource = data.steps || data.nodes;
    if (stepsSource) {
      for (const step of stepsSource) {
        const stepId = step.id || step.nodeId;
        if (stepId?.startsWith('interface:')) {
          this.handleInterfaceStepUpdate(stepId, step);
        }
        // Detect agent node starting execution → dispatch event for auto-open conversation panel
        if (stepId?.startsWith('agent:') && (step.status || '').toLowerCase() === 'running') {
          this.handleAgentStepRunning(stepId, step);
        }
        // Detect sub-workflow node starting execution → dispatch event for auto-open workflow panel
        // We can't check step.type here (WS doesn't send node type), so dispatch for all core: RUNNING
        // and let WorkflowDetailView filter by matching against canvas nodes
        if (stepId?.startsWith('core:') && (step.status || '').toLowerCase() === 'running') {
          this.handleSubWorkflowStepRunning(stepId, step);
        }
        // Detect agent node completion → dispatch event so conversation panel reloads messages
        if (stepId?.startsWith('agent:') && ['completed', 'success'].includes((step.status || '').toLowerCase())) {
          this.handleAgentStepCompleted(stepId, step);
        }
        // Detect step failure → dispatch event for toast notification
        if (stepId && ['failed', 'error', 'failure'].includes((step.status || '').toLowerCase())) {
          this.handleStepFailed(stepId, step);
        }
      }
    }
  }

  /**
   * Derive tracking sets from individual step statuses (NodeCounts-based).
   * More accurate than flat views after epoch close, since NodeCounts accumulate
   * across all epochs while flat views only reflect active epochs.
   */
  private deriveTrackingSetsFromSteps(stepsArray: any[]): {
    completed: string[];
    failed: string[];
    skipped: string[];
    running: string[];
  } {
    const completed: string[] = [];
    const failed: string[] = [];
    const skipped: string[] = [];
    const running: string[] = [];

    for (const step of stepsArray) {
      const stepId = step.id || step.nodeId || step.stepId;
      if (!stepId) continue;
      const status = (step.status || '').toLowerCase();
      const sc = step.statusCounts;

      switch (status) {
        case 'completed':
        case 'success':
          completed.push(stepId);
          break;
        case 'failed':
        case 'error':
        case 'failure':
          failed.push(stepId);
          break;
        case 'skipped':
          skipped.push(stepId);
          break;
        case 'running':
          running.push(stepId);
          break;
        case 'pending':
          // Backend sets status="pending" when node is in readyNodeIds or has no
          // terminal state in the current epoch. Do NOT promote to completed/failed
          // based on historical NodeCounts - those accumulate across epochs and would
          // cause stale "completed" state after a new trigger fire (new epoch).
          // Non-trigger nodes that need rerun buttons get them via the readySet filter
          // + canRerun logic in StepByStepContext, not from being in completedSteps.
          break;
        // 'awaiting_signal' - ephemeral state handled separately
      }
    }

    return { completed, failed, skipped, running };
  }

  /**
   * Handle interface node status updates from batch events.
   * Publishes to the pending-interfaces store for UI consumption.
   */
  private handleInterfaceStepUpdate(stepId: string, step: any): void {
    try {
      // Dynamic import to avoid circular dependencies
      const { usePendingInterfacesStore } = require('@/lib/stores/pending-interfaces-store');
      const store = usePendingInterfacesStore.getState();
      const status = (step.status || '').toLowerCase();

      if (status === 'awaiting_signal') {
        store.addPending({
          nodeId: stepId,
          interfaceId: step.output?.interface_id || step.interfaceId || '',
          label: step.label || stepId.replace('interface:', ''),
          status: 'awaiting',
          actionMapping: step.output?.action_mapping || {},
          addedAt: Date.now(),
          isEntryInterface: step.output?.is_entry_interface === true,
        });
        // Dispatch event for auto-opening the application panel (fires once per stepId per epoch)
        this.handleInterfaceAwaiting(stepId, step);
      } else if (status === 'completed' || status === 'success') {
        store.removePending(stepId);
      } else if (status === 'failed' || status === 'error' || status === 'skipped') {
        store.removePending(stepId);
      }
    } catch {
      // Store not available (SSR or test environment)
    }
  }

  /**
   * Dispatch a CustomEvent when an interface node reaches awaiting_signal so the UI
   * can auto-open the application panel. Fires only once per stepId per epoch.
   */
  private handleInterfaceAwaiting(stepId: string, step: any): void {
    if (this.interfaceAwaitingEmitted.has(stepId)) return;
    this.interfaceAwaitingEmitted.add(stepId);

    try {
      window.dispatchEvent(new CustomEvent('workflowInterfaceAwaiting', {
        detail: {
          stepId,
          interfaceId: step.output?.interface_id || step.interfaceId || '',
          label: step.label || stepId.replace('interface:', ''),
        },
      }));
    } catch {
      // SSR safety
    }
  }

  /** Track which interface steps have already triggered the auto-open event (per epoch). */
  private interfaceAwaitingEmitted = new Set<string>();
  /** Track which agent steps have already triggered the auto-open event (per epoch). */
  private agentRunningEmitted = new Set<string>();
  /** Track which sub-workflow steps have already triggered the auto-open event (per epoch). */
  private subWorkflowRunningEmitted = new Set<string>();
  /** Track which agent steps have already triggered the completion event (per epoch). */
  private agentCompletedEmitted = new Set<string>();
  /** Track which step failures have already triggered a toast (avoid duplicates). */
  private failureEmitted = new Set<string>();

  /**
   * Dispatch a CustomEvent when an agent node starts running so the UI can
   * auto-open the conversation panel. Fires only once per stepId per epoch.
   */
  private handleAgentStepRunning(stepId: string, step: any): void {
    if (this.agentRunningEmitted.has(stepId)) return;
    this.agentRunningEmitted.add(stepId);

    try {
      window.dispatchEvent(new CustomEvent('workflowAgentRunning', {
        detail: {
          stepId,
          label: step.label || stepId.replace('agent:', ''),
        },
      }));
    } catch {
      // SSR safety
    }
  }

  /**
   * Dispatch a CustomEvent when a sub-workflow node starts running so the UI can
   * auto-open the workflow panel. Fires only once per stepId per epoch.
   */
  private handleSubWorkflowStepRunning(stepId: string, step: any): void {
    if (this.subWorkflowRunningEmitted.has(stepId)) return;
    this.subWorkflowRunningEmitted.add(stepId);

    try {
      window.dispatchEvent(new CustomEvent('workflowSubWorkflowRunning', {
        detail: {
          stepId,
          label: step.label || stepId.replace('core:', ''),
        },
      }));
    } catch {
      // SSR safety
    }
  }

  /**
   * Dispatch a CustomEvent when an agent node completes so the conversation
   * panel can reload messages from DB (handles race where WS events were missed).
   */
  private handleAgentStepCompleted(stepId: string, step: any): void {
    if (this.agentCompletedEmitted.has(stepId)) return;
    this.agentCompletedEmitted.add(stepId);

    try {
      window.dispatchEvent(new CustomEvent('workflowAgentCompleted', {
        detail: {
          stepId,
          label: step.label || stepId.replace('agent:', ''),
        },
      }));
    } catch {
      // SSR safety
    }
  }

  /**
   * Dispatch a CustomEvent when a step fails so the UI can show a toast.
   * Fires only once per stepId to avoid duplicate toasts.
   */
  private handleStepFailed(stepId: string, step: any): void {
    if (this.failureEmitted.has(stepId)) return;
    this.failureEmitted.add(stepId);

    const errorMessage = step.errorMessage || step.output?.error || '';
    // CE: an AUTOMATIC-run agent node that failed because the cloud relay refused the model
    // (MODEL_NOT_SUPPORTED) or the linked account is out of credit (INSUFFICIENT_CREDITS) gets
    // the same actionable modal as chat. No-op in the Cloud edition.
    handleCeRelayError(errorMessage);

    try {
      window.dispatchEvent(new CustomEvent('workflowStepFailed', {
        detail: {
          stepId,
          label: step.label || stepId,
          errorMessage,
        },
      }));
    } catch {
      // SSR safety
    }
  }

  /**
   * Dispatch a CustomEvent when the workflow transitions to a terminal failure status.
   */
  private handleWorkflowFailed(message?: string): void {
    try {
      window.dispatchEvent(new CustomEvent('workflowFailed', {
        detail: { message: message || '' },
      }));
    } catch {
      // SSR safety
    }
  }

  /**
   * Normalize backend status to RunState status.
   * Uses the KNOWN_WORKFLOW_STATUSES constant for canonical mapping.
   */
  private normalizeStatus(status: string): RunState['runStatus'] {
    const s = status?.toLowerCase() || 'pending';
    switch (s) {
      case 'waiting_trigger': return 'waiting_trigger';
      case 'running': return 'running';
      case 'paused': return 'paused';
      case 'completed': case 'success': return 'completed';
      case 'failed': case 'error': return 'failed';
      case 'resuming': return 'running';
      case 'cancelled': case 'stopped': return 'cancelled';
      case 'timeout': return 'timeout';
      case 'partial_success': return 'partial_success';
      default: return 'pending';
    }
  }

  /**
   * Normalize a label to a stable key.
   * Uses the shared labelNormalizer from the builder utils.
   */
  private normalizeLabelValue(label: string): string {
    return normalizeLabel(label) || label
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_|_$/g, '');
  }

  /**
   * Inject reusable triggers into readySteps for completed/failed workflows
   * (so the user can re-trigger cyclic workflows).
   *
   * For waiting_trigger: trust the backend's readySteps (from StateSnapshot),
   * which only includes triggers whose DAG is actually waiting.  Injecting all
   * triggers here would override the backend and show already-fired triggers as active.
   */
  private injectReusableTriggers(runState: any): string[] {
    let readySteps = runState.readySteps || [];
    const status = runState.status?.toLowerCase();
    const needsTriggers = status === 'completed' || status === 'failed' || status === 'waiting_trigger';

    if (needsTriggers && runState.plan?.triggers) {
      for (const trigger of runState.plan.triggers) {
        if (this.isReusableTrigger(trigger.type)) {
          const label = trigger.label || trigger.id || 'trigger';
          const normalized = this.normalizeLabelValue(label);
          const triggerKey = `trigger:${normalized}`;
          if (!readySteps.includes(triggerKey)) {
            readySteps = [...readySteps, triggerKey];
          }
        }
      }
    }
    return readySteps;
  }

  private isReusableTrigger(type: string | undefined): type is ReusableTriggerType {
    if (!type) return false;
    return (REUSABLE_TRIGGER_TYPES as readonly string[]).includes(type.toLowerCase());
  }

  private extractTriggerType(runState: any): ReusableTriggerType | null {
    if (runState.plan?.triggers?.length > 0) {
      const rawType = runState.plan.triggers[0].type;
      if (typeof rawType === 'string' && this.isReusableTrigger(rawType.toLowerCase())) {
        return rawType.toLowerCase() as ReusableTriggerType;
      }
    }
    return null;
  }

  /** Schedule a timer that will be cleaned up on destroy. */
  private scheduleTimer(callback: () => void, delayMs: number): void {
    const timerId = setTimeout(() => {
      this.pendingTimers.delete(timerId);
      callback();
    }, delayMs);
    this.pendingTimers.add(timerId);
  }

  /** Extract error message from unknown error. */
  private getErrorMessage(err: unknown): string {
    return err instanceof Error ? err.message : String(err);
  }
}

// ============================================================================
// MANAGER REGISTRY (Singleton per runId)
// ============================================================================

const managers = new Map<string, WorkflowRunManager>();

export function getWorkflowRunManager(runId: string): WorkflowRunManager {
  let manager = managers.get(runId);
  if (manager) {
    return manager;
  }

  manager = new WorkflowRunManager(runId);
  managers.set(runId, manager);
  return manager;
}

export function deleteWorkflowRunManager(runId: string): void {
  const manager = managers.get(runId);
  if (manager) {
    manager.destroy();
    managers.delete(runId);
  }
  deleteRunStateStore(runId);
}

export function hasWorkflowRunManager(runId: string): boolean {
  return managers.has(runId);
}

// Phase 6 (2026-05-18) - HMR-safe module-singleton subscriber. The `managers`
// Map caches every WorkflowRunManager keyed by runId for the app lifetime;
// without this reset, switching workspace and opening an old run URL reuses
// the cached manager (with the prior workspace's plan / steps / channels).
// On workspace switch we destroy every manager and clear the registry - the
// next getWorkflowRunManager call re-creates a fresh manager that re-fetches
// against the new workspace.
if (typeof window !== 'undefined') {
  const HMR_KEY = Symbol.for('__lc_orgReset:WorkflowRunManager:managers');
  const g = globalThis as unknown as Record<symbol, (() => void) | undefined>;
  if (typeof g[HMR_KEY] === 'function') g[HMR_KEY]!();
  import('@/lib/stores/current-org-store').then(({ useCurrentOrgStore }) => {
    g[HMR_KEY] = useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        managers.forEach((m) => {
          try { m.destroy(); } catch { /* best-effort */ }
        });
        managers.clear();
      },
    );
  }).catch(() => {});
}

/**
 * Phase E (archi-refoundation 2026-05-04) - iterate every active manager.
 * Used by the visibility-suspend listener in {@code WorkflowRunContext} to
 * pause all known managers when the tab has been hidden long enough that
 * the WS has likely been dropped by the browser.
 */
export function forEachWorkflowRunManager(fn: (manager: WorkflowRunManager, runId: string) => void): void {
  for (const [runId, manager] of managers.entries()) {
    fn(manager, runId);
  }
}
