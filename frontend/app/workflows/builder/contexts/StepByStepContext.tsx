'use client';

import * as React from 'react';
import type { WorkflowRunState, StepState, CoreExecutionResponse, StepRerunResponse } from '@/lib/api';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import type { DerivedNodeStatus } from '../types';
import { normalizeLabel, extractLabelFromKey } from '../utils/labelNormalizer';
import { getPrefixForKind } from '../registry/nodeRegistry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { RerunConfirmModal } from '../components/RerunConfirmModal';

/**
 * The signal kinds a NODE can be parked on and a user can resolve from the
 * canvas. Mirrors backend `SignalType`; only the two that carry an on-node
 * control are named, because a kind nothing can resolve has no queue to read.
 */
export type PendingSignalKind = 'USER_APPROVAL' | 'INTERFACE_SIGNAL';

export interface StepByStepContextValue {
  // Mode
  isStepByStepMode: boolean;
  /**
   * The run's persisted execution mode, WITHOUT folding in terminality the way
   * {@link isStepByStepMode} does. A finished step-by-step run has
   * {@code isStepByStepMode === false} but is still stepped, so anything describing what a
   * rerun will DO (the backend keys off the persisted mode) must read this instead: on a
   * stepped run a rerun executes nothing and waits for the user.
   */
  isSteppedRun: boolean;
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
  rerunStep: (stepId: string, epoch?: number) => Promise<StepRerunResponse | null>;
  canRerunStep: (stepId: string) => boolean;
  isRerunning: boolean;

  // Approval actions
  resolveApproval: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  getPendingSignalCount: (nodeId: string) => number;
  /**
   * Pending signals of ONE type parked on a node, newest epoch first.
   *
   * <p>The type is a required argument rather than a default, because the two
   * kinds drive two unrelated affordances: `USER_APPROVAL` feeds the approve /
   * reject buttons, `INTERFACE_SIGNAL` feeds the interface node's Continue
   * button. A default would let a new caller silently read the wrong queue and
   * render an empty control that no user can explain.
   */
  getPendingSignalsForNode: (nodeId: string, signalType: PendingSignalKind) => PendingSignal[];
  /** ALL pending USER_APPROVAL signals across every node (run-wide queue). */
  getAllPendingSignals: () => PendingSignal[];

  // Loading
  isExecutingStep: string | null;

  // Last decision result (for UI display)
  lastDecisionResult: CoreExecutionResponse | null;

  // Epoch data for parallel SBS support
  activeEpochs: number[];

  /**
   * The run's newest epoch. Used to tell "reading the live state through its
   * epoch" apart from "reading a historical epoch" - only the latter is
   * read-only. See the `isInteractive` note in {@link useNodeExecutionStatus}.
   */
  currentEpoch: number;
}

const StepByStepContext = React.createContext<StepByStepContextValue | null>(null);

interface StepByStepProviderProps {
  children: React.ReactNode;
  isEnabled: boolean;
  isPaused: boolean;
  isRunTerminal?: boolean;
  /**
   * The run was deliberately put down (stopped/cancelled) or timed out. Distinct from
   * {@link isRunTerminal}, which also covers runs that simply FINISHED and stay rerunnable.
   */
  isRunUnrevivable?: boolean;
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
  onRerunStep?: (stepId: string, epoch?: number) => Promise<StepRerunResponse | null>;
  isRerunning?: boolean;
  // Approval support
  onResolveApproval?: (nodeId: string, resolution: 'APPROVED' | 'REJECTED', epoch?: number, itemId?: string) => Promise<void>;
  pendingSignals?: PendingSignal[];
  // Epoch data for parallel SBS support
  activeEpochs?: number[];
  /** The run's newest epoch (0 when the run has not fired yet). */
  currentEpoch?: number;
}

/**
 * A rerun waiting for the user's answer on an AUTOMATIC run: the caller's promise is
 * parked here until the confirmation is accepted or dismissed.
 */
interface PendingRerun {
  stepId: string;
  /** The epoch the confirmed rerun must replay, or undefined for the run's most recent one. */
  epoch?: number;
  resolve: (value: StepRerunResponse | null) => void;
  reject: (reason: unknown) => void;
}

/**
 * Human-readable name for a backend step id (`mcp:my_tool` -> `my tool`), shown in the
 * rerun confirmation so the user can vet WHICH node the restart starts from. Falls back
 * to the id itself when it carries no prefix.
 */
function stepDisplayLabel(stepId: string): string {
  const label = extractLabelFromKey(stepId);
  return label ? label.replace(/_/g, ' ') : stepId;
}

export function StepByStepProvider({
  children,
  isEnabled,
  isPaused,
  isRunTerminal = false,
  isRunUnrevivable = false,
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
  currentEpoch = 0,
}: StepByStepProviderProps) {
  const [executingStep, setExecutingStep] = React.useState<string | null>(null);
  const [pendingRerun, setPendingRerun] = React.useState<PendingRerun | null>(null);
  // Mirror of `pendingRerun` readable from callbacks and from the unmount cleanup, where
  // the state value captured at render time would already be stale.
  const pendingRerunRef = React.useRef<PendingRerun | null>(null);
  React.useEffect(() => {
    pendingRerunRef.current = pendingRerun;
  }, [pendingRerun]);
  // Never leave a caller awaiting a promise nobody can answer any more.
  React.useEffect(() => () => {
    pendingRerunRef.current?.resolve(null);
    pendingRerunRef.current = null;
  }, []);

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
  //
  // AUTOMATIC runs go through a confirmation first. On a stepped run the rerun stops at the
  // target and waits for the user, so it costs nothing; in automatic mode the same click
  // reruns the target AND lets the whole downstream chain run again unattended, which can
  // spend paid calls and send real messages. That asymmetry is invisible on the button, so
  // the gate lives here rather than on each surface: the canvas bar, the context menu and
  // the inspector all call this one function and inherit it.
  const rerunStep = React.useCallback(async (stepId: string, epoch?: number): Promise<StepRerunResponse | null> => {
    if (!onRerunStep) return null;
    if (isEnabled) return await onRerunStep(stepId, epoch);
    return await new Promise<StepRerunResponse | null>((resolve, reject) => {
      setPendingRerun((previous) => {
        // A second rerun click while the gate is open supersedes the first; the superseded
        // caller must not be left awaiting forever.
        previous?.resolve(null);
        return { stepId, epoch, resolve, reject };
      });
    });
  }, [onRerunStep, isEnabled]);

  const confirmPendingRerun = React.useCallback(async () => {
    const pending = pendingRerunRef.current;
    if (!pending) return;
    pendingRerunRef.current = null;
    setPendingRerun(null);
    try {
      pending.resolve(onRerunStep ? await onRerunStep(pending.stepId, pending.epoch) : null);
    } catch (err) {
      // Surface the failure to whoever awaited the rerun, exactly as the ungated path does.
      pending.reject(err);
    }
  }, [onRerunStep]);

  const cancelPendingRerun = React.useCallback(() => {
    const pending = pendingRerunRef.current;
    pendingRerunRef.current = null;
    setPendingRerun(null);
    // A dismissed rerun is a no-op, not an error: resolve with the same "nothing happened"
    // value the callers already handle.
    pending?.resolve(null);
  }, []);

  // Check if a step can be re-run (must be COMPLETED, FAILED, or RUNNING)
  // NOTE: SKIPPED steps cannot be retried (branch wasn't taken - retry from decision instead)
  // RUNNING check is kept for while loops and long-running agents.
  // NOTE: In the simplified split system, split completes immediately after spawning items,
  // so split nodes will be in completedSteps, not runningSteps.
  // Triggers use the same rerun logic as other nodes (selective reset).
  //
  // NOT gated on step-by-step mode: the backend rerun path is mode-blind, and in AUTOMATIC
  // mode it reruns the target then drives the rest of the chain itself. Gating here was what
  // made "restart from a node" unreachable outside step-by-step.
  //
  // Still refused on a run the user (or a timeout) put down: reviving one is a re-trigger
  // decision, not a rerun. A run that merely FINISHED stays rerunnable, which is the whole
  // point - restarting mid-graph on a completed run is the common case.
  const canRerunStep = React.useCallback((stepId: string): boolean => {
    if (isRunUnrevivable) return false;
    // A node executing RIGHT NOW on a run nobody is stepping, refused BEFORE the terminal
    // check because that check is satisfied by cumulative counts: a node that completed in an
    // earlier epoch and is running in this one reads as rerunnable, and restarting it would
    // race the in-flight execution, which still writes its own completion (double execution,
    // lost write). The backend refuses it for that reason. This lives here, not on one
    // surface, because the canvas bar happened to be covered by `showsNodeRunActions`'s
    // `!isRunning` while the context menu and the inspector header gate on `canRerun` alone
    // and offered the restart anyway.
    //
    // A node PARKED on a signal is excluded from that, and the overlap it needs excluding from
    // is PERMANENT, not a blink: `applyTrackingFromApi` derives its sets from the step rows and
    // puts an `AWAITING_SIGNAL` node in `runningSteps` (deliberately - the node keeps its
    // running shimmer while it waits) as well as in `awaitingSignalSteps`. So on any run
    // hydrated over REST, every parked approval sits in both sets for as long as it waits.
    // (`setRunningSteps` adds a second, transient overlap of up to MIN_SHIMMER_MS, and the
    // socket path keeps the two apart - neither changes the conclusion.)
    //
    // The backend's own `runningNodeIds` drops the node the moment it parks
    // (EpochState.markNodeAwaitingSignal), so it ACCEPTS that restart. Without this clause the
    // canvas would refuse every waiting approval, which is a node whose restart is the whole
    // point of the affordance.
    if (!isEnabled && runningSteps.has(stepId) && !awaitingSignalSteps.has(stepId)) return false;
    if (completedSteps.has(stepId) || failedSteps.has(stepId)) return true;
    // A node still RUNNING is only an escape hatch for a run the user drives by hand (a stuck
    // while-loop, a long agent). On an automatic run the invocation is genuinely in flight and
    // will write its own completion, and the backend accepts the rerun anyway when an EARLIER
    // epoch completed the node - so offering it here buys a double execution and a lost write,
    // not a clean refusal.
    return isEnabled && runningSteps.has(stepId);
  }, [isEnabled, isRunUnrevivable, completedSteps, failedSteps, runningSteps, awaitingSignalSteps]);

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

  // Get pending signals of one kind for a specific node (per-item approval UI,
  // interface Continue button).
  const getPendingSignalsForNode = React.useCallback(
    (nodeId: string, signalType: PendingSignalKind): PendingSignal[] => {
      return pendingSignals.filter(
        s => s.nodeId === nodeId && s.signalType === signalType
      );
    },
    [pendingSignals],
  );

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
    isSteppedRun: isEnabled,
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
    currentEpoch,
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
    currentEpoch,
  ]);

  return (
    <StepByStepContext.Provider value={value}>
      {children}
      {pendingRerun && (
        <RerunConfirmModal
          stepLabel={stepDisplayLabel(pendingRerun.stepId)}
          epoch={pendingRerun.epoch}
          onConfirm={confirmPendingRerun}
          onCancel={cancelPendingRerun}
        />
      )}
    </StepByStepContext.Provider>
  );
}

export function useStepByStep(): StepByStepContextValue | null {
  return React.useContext(StepByStepContext);
}

/**
 * Hook to get execution status for a specific node (step or core node)
 * @param nodeId - The frontend node ID
 * @param nodeData - Optional node data containing label and kind for accurate backend ID mapping,
 *   plus the status the canvas currently paints on the node. While ONE epoch is focused that
 *   status is that epoch's (useEpochStateViewing writes it), which is the only per-epoch fact
 *   this hook can see: the context's own sets accumulate across every epoch of the run.
 */
export function useNodeExecutionStatus(
  nodeId: string,
  nodeData?: { label?: string; kind?: string; crudOperation?: string; status?: DerivedNodeStatus | null },
) {
  const ctx = useStepByStep();
  const { viewingEpoch, isPreviewOnly } = useWorkflowMode();

  // Interactive in the "All epochs" view (viewingEpoch == null) AND while reading
  // the run's NEWEST epoch - only a HISTORICAL epoch is read-only.
  //
  // This used to require "All epochs" alone, which was correct while a run opened
  // unselected. Since the run history moved into the side panel, every run surface
  // seeds a default epoch on the shared provider (useDefaultEpochSelection: "a run
  // is always read THROUGH an epoch"), so the canvas is never on "All" by default.
  // With the old rule that silently hid EVERY play and rerun button on non-trigger
  // nodes, which made a step-by-step run impossible to step from the canvas - the
  // one surface that drives it. (Triggers looked fine only because FlowNode already
  // re-exposes a focus-epoch play button.)
  //
  // Reading the live state through its own epoch is not history, so it stays
  // interactive; an older epoch is a record of what happened and stays read-only.
  //
  // Decided by epoch IDENTITY alone. This used to also demand `currentEpoch > 0`, to keep an
  // absent value (the store defaults the field to 0) from unlocking controls - but epoch 0 is
  // a real, common FIRST fire, so the guard killed every play and rerun on a run that had
  // fired exactly once and was being read through its only epoch.
  //
  // What the old guard bought is nearly nothing. `currentEpoch` is written by `applyMetadata`,
  // which every load runs BEFORE the step sets land, and until those sets land every control is
  // gated off by them anyway. The one residual it covered was a payload that carries the sets
  // but NOT an epoch, which the store would have coalesced to 0 - so that coalesce is now
  // conditional (see RunStateStore.applyMetadata), and a payload without the field leaves the
  // epoch the client already knew instead of silently claiming 0.
  const isInteractive = viewingEpoch == null
    || (ctx != null && viewingEpoch === ctx.currentEpoch);

  if (!ctx) {
    return {
      isStepByStepMode: false,
      isSteppedRun: false,
      canExecute: false,
      isReady: false,
      canExecuteRaw: false,
      isCompleted: false,
      isFailed: false,
      isSkipped: false,
      isRunning: false,
      isAwaitingSignal: false,
      isExecuting: false,
      isCore: false,
      isEvaluated: false,
      isInteractive: false,
      // Outside a run there is no backend step id to give - a React Flow node id
      // here would be a lie that silently reaches cross-component events.
      stepId: undefined as string | undefined,
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
      // Interface `__continue`
      interfaceSignals: [],
    };
  }

  // Resolve React Flow node ID → backend step ID.
  // Primary: backend-provided mapping (nodeIdToStepId). Fallback: computeBackendStepId.
  const normalizedId = ctx.resolveNodeId(nodeId, nodeData);
  const isControl = ctx.isCore(normalizedId);

  // SSE sets are the PRIMARY source - they update in real-time during streaming.
  // Backend stepStates (REST) may be stale during SSE streaming.
  // deriveNodeStatus() handles priority: running > failed > skipped > completed > ready > pending
  //
  // Those sets are RUN-wide: they are derived from cumulative NodeCounts, so a node that
  // completed in ANY epoch reads COMPLETED in all of them. That is right for the all-epochs
  // view and wrong for a FOCUSED one, where the canvas paints the epoch's own outcome - a
  // node whose branch was not taken in the epoch on screen shows SKIPPED there while these
  // sets still say COMPLETED. Inside a focused epoch the node's painted status is therefore
  // the source of truth for the terminal flags, so every control derived from them (the
  // play/rerun button's status, the rerun gate below) speaks about the epoch being read.
  //
  // "Focused" = an epoch that is not the one the run is living in, the exact complement of
  // `isInteractive` above. It holds whether or not the epoch painted anything on this node:
  // "that epoch has no result for it" is itself the epoch's answer, and falling back to the
  // run-wide sets there would paint a node the epoch never reached with another epoch's
  // outcome (InterfacePreviewNode reads these flags with no viewingEpoch short-circuit).
  //
  // Two things `currentEpoch` is NOT, both of which put ordinary epochs on this side of the
  // line rather than the "live" one:
  //   - it is not the newest EXECUTED epoch. A settled automatic run has already staged the
  //     next fire (DagState.prepareNextCycle points currentEpoch at a dormant epoch holding
  //     nothing but the trigger), so the last epoch the selector offers reads as focused and
  //     its restart NAMES it. That is the common case, not an exception, and it is the same
  //     work: the epoch-less path resolves to that very epoch and reopens it too.
  //   - it is not per-DAG; it is the MAX across the run's DAGs. On a multi-trigger run a node
  //     whose own DAG is behind that max reads as focused while sitting on its DAG's live
  //     epoch, and its restart names it.
  // Naming an epoch is not free, but it is not harmful either: it reopens nothing the default
  // would not have reopened, leaves the same `dagLastEpoch` behind (StepRerunService keys that
  // skip on the pointer moving BACKWARD, not on the parameter being present), and takes a
  // spawn FLOOR that is never below what the default would have taken. What it adds is one
  // check the default skips - that the node really ran in the named epoch - which is a refusal
  // this gate has already applied client-side.
  const readsFocusedEpoch = viewingEpoch != null && viewingEpoch !== ctx.currentEpoch;
  const focusedEpochStatus = readsFocusedEpoch ? (nodeData?.status ?? null) : null;
  const isRunning = readsFocusedEpoch
    ? focusedEpochStatus === 'running'
    : ctx.runningSteps.has(normalizedId);
  const isFailed = readsFocusedEpoch
    ? focusedEpochStatus === 'failed'
    : ctx.failedSteps.has(normalizedId);
  const isSkipped = readsFocusedEpoch
    ? focusedEpochStatus === 'skipped'
    : ctx.skippedSteps.has(normalizedId);
  // 'partial_success' is a COMPLETED node carrying a failure in its tally - the same bucket
  // the run-wide sets put it in, and what keeps its rerun button.
  const isCompleted = readsFocusedEpoch
    ? (focusedEpochStatus === 'completed' || focusedEpochStatus === 'partial_success')
    : ctx.completedSteps.has(normalizedId);
  // Readiness is deliberately NOT switched: it is a fact about what the run can execute next,
  // which a historical epoch has no opinion on - and the returned `isReady` is gated on
  // `isInteractive` anyway, so a focused epoch reports false whatever this reads.
  const isReady = ctx.readySteps.has(normalizedId);
  const isAwaitingSignal = readsFocusedEpoch
    ? focusedEpochStatus === 'awaiting_signal'
    : ctx.awaitingSignalSteps.has(normalizedId);

  // A rerun stays available while an OLDER epoch is on screen, on an AUTOMATIC run.
  //
  // Reading a past epoch is read-only for everything that ADVANCES the run, but a rerun
  // replays a fire that already happened: the backend takes the epoch by name (?epoch=N) and
  // `rerunStep` below sends the focused one, so the restart repairs the epoch the user is
  // reading instead of silently redoing the newest. Without this the button was reachable
  // only from the run's last epoch, which is the one epoch that rarely needs repairing.
  //
  // Stepped runs are excluded: the backend refuses to REOPEN an epoch on one (nothing on a
  // hand-stepped run closes a cycle, so the reopened epoch would stay open indefinitely), and
  // reopening is exactly what reading an older epoch asks for. It does accept a named epoch
  // there when that epoch is already the DAG's current one, but that is the same work as
  // omitting it - so nothing is lost by declining the whole shape.
  //
  // Gated on the epoch's OWN outcome (terminal in THIS epoch), so a node the epoch skipped -
  // or never reached - offers no button in the cases the canvas can SEE. Not full parity, and
  // it cannot be, in three ways this side cannot close:
  //   - it reads the epoch's COUNTER ROWS (useEpochStateViewing) while the backend checks that
  //     epoch HEADER's own node sets, and StepRerunService says outright that the two can
  //     disagree (a node a previous rerun cleared without re-executing is absent from the
  //     header while its step rows remain);
  //   - it asks for the epoch across ALL triggers, while the backend checks the OWNING one -
  //     and epoch numbers are per-DAG, so on a multi-trigger run the paint can come from
  //     another trigger's fire of the same number;
  //   - a run with no epoch header at all (a legacy one, or a fire never closed through the
  //     epoch service) still paints from its counter rows, and the backend refuses a NAMED
  //     replay there rather than degrading into the dormant epoch the way the epoch-less path
  //     does. Refusing is the safer half of that trade: degrading would run the node with its
  //     upstream resolving to nothing, on a run that reports success.
  // So a refusal still gets through, which is why it is reported rather than assumed away. Deliberately NARROWER than
  // the backend, which also replays a node left AWAITING_SIGNAL or READY in the named epoch:
  // neither offers a restart in the all-epochs view either, and inventing one here would make
  // a focused epoch the only place a parked approval can be restarted from.
  //
  // Two more refusals the backend owns, mirrored here because a button that only ever answers
  // with an error is worse than no button:
  //   - another epoch of this run is still executing. Reopening an older one then runs two
  //     fires of the same DAG at once, so the replay is refused while that holds. The set read
  //     here is a SUPERSET of the one the refusal reads: the backend looks at the owning
  //     trigger's DAG, this is the union across every DAG (epoch 1 of trigger A and epoch 1 of
  //     trigger B are the same number here). So on a multi-trigger run this can hide a button
  //     the backend would have accepted - the safe direction, and the only one available
  //     without a per-node owning-trigger map the canvas does not have.
  // The node executing RIGHT NOW in ANOTHER epoch is refused too, by `canRerunStep` (which
  // every branch below is ANDed with). It has to be checked there rather than here: `isRunning`
  // in this scope speaks about the epoch on screen, so on its own it says nothing about a node
  // completed in epoch 1 and running in epoch 3.
  //
  // One backend refusal is knowingly NOT mirrored: a run migrated from the V2 engine can own
  // its nodes through the `trigger:default` sentinel beside a real DAG, and a named-epoch
  // replay is refused there outright. The canvas has no node-to-owning-trigger map and the
  // snapshot's DAG keys never reach it, so there is nothing here to test - the click is
  // answered by the reported refusal instead of a hidden button. Legacy runs only.
  const canRerunFocusedEpoch = readsFocusedEpoch
    && !ctx.isSteppedRun
    && (isCompleted || isFailed)
    && !ctx.activeEpochs.some((epoch) => epoch !== viewingEpoch);

  return {
    // Only true if explicitly in step-by-step mode AND interactive.
    // Historical epoch viewing disables all controls - epoch data determines visuals.
    isStepByStepMode: ctx.isStepByStepMode && isInteractive,
    isSteppedRun: ctx.isSteppedRun,
    /**
     * The view is one the run can be ACTED on from: the all-epochs view, or the
     * epoch the run is living in. A historical epoch is a record and stays
     * read-only. Folded into most controls already; exposed for the ones that
     * are not derived from a step set, such as the interface node's Continue
     * button (an interface fire carries no epoch, so the backend resolves the
     * node's newest signal - offering it while an older epoch is on screen
     * would advance an epoch the user is not looking at).
     */
    isInteractive,
    canExecute: isInteractive && (isControl ? ctx.canExecuteCore(normalizedId) : ctx.canExecuteStep(normalizedId)),
    isReady: isInteractive && isReady,
    /**
     * Executability ignoring the focus-epoch interactive gate - but NOT the run's
     * own state: `canExecuteStep` still returns false on a terminal run, which the
     * backend dispatcher would refuse anyway.
     *
     * Lets the focus view offer a trigger's play button (which returns to
     * all-epochs and fires that trigger) while normal controls stay hidden. Read
     * this, never a bare readiness flag: a terminal run keeps its steps in
     * `readySteps`, so readiness alone promises a run that cannot happen.
     */
    canExecuteRaw: isControl ? ctx.canExecuteCore(normalizedId) : ctx.canExecuteStep(normalizedId),
    isCompleted,
    isFailed,
    isSkipped,
    isRunning,
    isAwaitingSignal,
    isExecuting: isInteractive && ctx.isExecutingStep === normalizedId,
    isCore: isControl,
    isEvaluated: ctx.evaluatedCores.has(normalizedId),
    // Backend step id (`trigger:<label>` for a trigger). Surfaced so callers can
    // name THIS node in cross-component events instead of letting the receiver
    // guess from the node type - several triggers can share one type.
    stepId: normalizedId,
    executeStep: () => ctx.executeStep(normalizedId, viewingEpoch ?? undefined),
    // Fire this step against ALL-epochs (epoch=undefined) regardless of the
    // currently-viewed epoch - used by the focus-epoch trigger play after it
    // returns to the all-epochs view, so the clicked trigger actually fires.
    fireFromAnyEpoch: () => ctx.executeStep(normalizedId, undefined),
    executeCore: () => ctx.executeCore(normalizedId),
    // Re-run: available for COMPLETED / FAILED steps, in the live view and - on an automatic
    // run - on any focused epoch that finished this node (see canRerunFocusedEpoch above).
    // Triggers are NOT re-fired by this: StepRerunService increments the spawn, never the
    // epoch, so a trigger restart replays its DAG in place. Every surface excludes them anyway
    // (the canvas bar, the context menu and the inspector header all test isTriggerNode),
    // because "restart from here" on a trigger is the whole DAG and none of them label it so.
    // Never on a read-only surface (marketplace preview, an application a visitor has not
    // acquired): a restart there resets the publisher's run. FlowNode already drops its whole
    // bar under preview, but the ten other node renderers gate their bar on
    // `showsNodeRunActions` alone, so the refusal belongs on the flag they all read.
    //
    // It does not reach a SHARE-TOKEN visitor: that page mounts the mode provider without
    // `readOnly`, so `isPreviewOnly` is false there and the control still renders (the gateway
    // then 403s the non-GET). Pre-existing and not narrowed to this feature - every run action
    // on that page has it - so it is named here rather than papered over from this flag.
    canRerun: !isPreviewOnly && (isInteractive || canRerunFocusedEpoch) && ctx.canRerunStep(normalizedId),
    isRerunning: ctx.isRerunning,
    // The focused epoch is named ONLY when the view is not the one the run is living in. The
    // all-epochs view always stays epoch-less; a single epoch usually does not, including the
    // newest EXECUTED one on a settled run (`currentEpoch` points at the fire staged for next
    // time by then - see `readsFocusedEpoch` above). Naming it resolves to the same replay the
    // default would have run.
    rerunStep: () => ctx.rerunStep(normalizedId, canRerunFocusedEpoch ? viewingEpoch ?? undefined : undefined),
    // Approval - epochOverride lets per-signal UIs (item rows, ApprovalReviewBar)
    // target the signal's OWN epoch: in 'All epochs' view viewingEpoch is null,
    // which would otherwise leave the backend to guess when several epochs are pending.
    resolveApproval: (resolution: 'APPROVED' | 'REJECTED', itemId?: string, epochOverride?: number) =>
      ctx.resolveApproval(normalizedId, resolution, epochOverride ?? viewingEpoch ?? undefined, itemId),
    pendingSignalCount: ctx.getPendingSignalCount(normalizedId),
    pendingSignals: ctx.getPendingSignalsForNode(normalizedId, 'USER_APPROVAL'),
    /**
     * Pending INTERFACE_SIGNAL waits parked on this node - what the interface
     * node's Continue button resolves. Kept apart from `pendingSignals` (which
     * is the APPROVAL queue every approval surface already reads) because the
     * two are resolved through different endpoints and must never be counted
     * together in one badge.
     *
     * Present on a node that is NOT awaiting too: a non-blocking interface
     * registers a signal and completes anyway, so this list alone never means
     * "the run is waiting here" - gate the control on `isAwaitingSignal`.
     */
    interfaceSignals: ctx.getPendingSignalsForNode(normalizedId, 'INTERFACE_SIGNAL'),
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
 * | agent:     | AI       | Agent, Browser Agent, Guardrail, Classify, Generate     |
 * | core:      | Control  | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Stop, Response, Download File, HTTP Request, Data Input, User Approval |
 */
/** Exported for its own tests: every branch here fails by producing a key that
 *  addresses nothing, which reads downstream as a node that simply never ran. */
export function normalizeNodeId(nodeId: string): string {
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
      nodeId.includes('public_link') || nodeId.includes('public-link') ||
      // 'media' must be a PREFIX match: MCP tool ids like create_media_container-123
      // CONTAIN "media" but are mcp: nodes, not core: nodes.
      nodeId === 'media' || nodeId.startsWith('media-') ||
      nodeId.includes('http_request') || nodeId.includes('http-request') ||
      nodeId.includes('data_input') || nodeId.includes('data-input')) {
    return `core:${normalized}`;
  }

  // Agent nodes. Generate ids are minted `generate-<ts>-<rand>`, so the prefix
  // test below never matches one; without naming it the node falls to the mcp
  // default and the step key addresses nothing.
  if (nodeId.includes('ai-agent') || nodeId.startsWith('agent-') || nodeId.startsWith('agent:')
      || nodeId === 'generate' || nodeId.startsWith('generate-')) {
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
