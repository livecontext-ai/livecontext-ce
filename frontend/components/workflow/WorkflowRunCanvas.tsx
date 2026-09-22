'use client';

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import type { AgentSnapshotConfig } from '@/app/workflows/builder/types/agentSnapshot';
import {
  WorkflowBuilder,
  type TriggerDataForPanel,
  type RunInfoData,
} from '@/app/workflows/builder/components/WorkflowBuilder';
import type { ApplicationConfig } from '@/components/chat/ApplicationTabContent';
import { WorkflowModeToggle } from '@/components/workflow/WorkflowModeToggle';
import { orchestratorApi } from '@/lib/api';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { isEventForWorkflow } from '@/lib/workflow/workflowEventScope';
import { useWorkflowRunContext } from '@/contexts/WorkflowRunContext';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useWorkflowEventBridge } from '@/components/views/workflow/hooks';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';
import {
  makeEmptyRunPanelData,
  publishRunPanelData,
  RUN_PANEL_ACTION_EVENT,
  type RunPanelAction,
  type RunPanelActionDetail,
} from '@/components/workflow/run-panel/runPanelBus';
import { useDefaultEpochSelection } from '@/components/workflow/run-panel/useDefaultEpochSelection';

// ============================================
// Types
// ============================================

/** Data forwarded to parent when run info changes. */
export interface RunInfoChangeData {
  runInfo: any | null;
  isStepByStep: boolean;
  currentEpoch: number;
  epochTimestamps: Array<{ epoch: number; startedAt: string; endedAt: string | null; workDurationMs?: number | null; status?: string | null }>;
  streamedSteps?: any[];
}

export interface WorkflowRunCanvasProps {
  workflowId: string;
  runId?: string;
  /** Pair this canvas with one keep-alive side-panel surface. Omitted on the page. */
  surfaceId?: string;
  /** Hide the edit/run mode toggle (application mode: always in run) */
  hideToggle?: boolean;
  /** Publication snapshot plan to use instead of fetching */
  planOverride?: any;

  // Edit mode pass-through to WorkflowBuilder
  onDirtyChange?: (isDirty: boolean) => void;
  onRefreshBlocked?: () => void;
  saveRef?: React.MutableRefObject<(() => Promise<void>) | null>;

  // Lifecycle callbacks to parent
  onWorkflowLoaded?: (info: { name?: string; id?: string }) => void;
  onRunInfoChange?: (data: RunInfoChangeData) => void;
  onTriggerConfigsChange?: (data: TriggerDataForPanel | null) => void;
  onApplicationConfigsChange?: (configs: ApplicationConfig[]) => void;
  onAgentConfigsChange?: (configs: AgentSnapshotConfig[]) => void;
  onHasChatFormTrigger?: (has: boolean) => void;

  // Refs (parent may need access)
  executeTriggerRef?: React.MutableRefObject<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>;
  applicationActionRef?: React.MutableRefObject<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>;
  nodesRef?: React.MutableRefObject<Node<BuilderNodeData>[]>;
}

// ============================================
// Component
// ============================================

/**
 * WorkflowRunCanvas - Shared component encapsulating WorkflowModeToggle + WorkflowBuilder + run state.
 * Used by both WorkflowDetailView and ApplicationDetailView to avoid duplicating
 * run state management, event bridges, and toggle/builder rendering.
 */
export function WorkflowRunCanvas({
  workflowId,
  runId,
  surfaceId,
  hideToggle = false,
  planOverride,
  onDirtyChange,
  onRefreshBlocked,
  saveRef,
  onWorkflowLoaded,
  onRunInfoChange,
  onTriggerConfigsChange,
  onApplicationConfigsChange,
  onAgentConfigsChange,
  onHasChatFormTrigger,
  executeTriggerRef: externalExecuteTriggerRef,
  applicationActionRef: externalApplicationActionRef,
  nodesRef: externalNodesRef,
}: WorkflowRunCanvasProps) {
  const tRoot = useTranslations();
  const { mode: workflowMode, isPreviewOnly, viewingEpoch, setViewingEpoch, runId: contextRunId } = useWorkflowMode();
  const runContext = useWorkflowRunContext();
  const canMutate = useCanMutateInCurrentOrg();

  // ── Diagnostic: confirm whether this component renders on the marketplace
  // preview route. If MOUNT log fires but no WorkflowBuilder MOUNT follows,
  // the gap is between RunCanvas and Builder (rare). If MOUNT never fires,
  // the gap is upstream (WorkflowPanelContent slot, or SidePanel keepMounted). ──
  useEffect(() => {
    console.log('[AppDebug] WorkflowRunCanvas MOUNT', { workflowId, runId, isPreviewOnly });
    return () => console.log('[AppDebug] WorkflowRunCanvas UNMOUNT', { workflowId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Internal run state ──
  const [currentRunInfo, setCurrentRunInfo] = useState<any | null>(null);
  const [isStepByStep, setIsStepByStep] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [currentEpoch, setCurrentEpoch] = useState(0);
  const [epochTimestamps, setEpochTimestamps] = useState<Array<{ epoch: number; startedAt: string; endedAt: string | null; workDurationMs?: number | null; status?: string | null }>>([]);
  const [streamedSteps, setStreamedSteps] = useState<any[] | undefined>(undefined);
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);

  // ── Fetch pinnedVersion when entering run mode (drives the pin badge) ──
  useEffect(() => {
    if (workflowMode !== 'run' || !workflowId) return;
    orchestratorApi.listVersions(workflowId)
      .then((vData) => setPinnedVersion(vData.pinnedVersion ?? null))
      .catch(() => {});
  }, [workflowMode, workflowId]);

  // ── Listen for pin/unpin changes from WorkflowVersionHistory ──
  // Scoped: a second version control now exists in the side panel, for another
  // workflow, and its pin must not show on this canvas' badge.
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (!isEventForWorkflow(detail, workflowId)) return;
      setPinnedVersion(detail?.pinnedVersion ?? null);
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, [workflowId]);

  // ── Internal refs (use external if provided) ──
  const internalExecuteTriggerRef = useRef<((triggerId: string, triggerType: 'chat' | 'form' | 'webhook', payload: Record<string, any>) => Promise<string[] | undefined>) | null>(null);
  const internalApplicationActionRef = useRef<((triggerRef: string, data: Record<string, unknown>) => Promise<void>) | null>(null);
  const internalNodesRef = useRef<Node<BuilderNodeData>[]>([]);

  const executeTriggerRef = externalExecuteTriggerRef ?? internalExecuteTriggerRef;
  const applicationActionRef = externalApplicationActionRef ?? internalApplicationActionRef;
  const canvasNodesRef = externalNodesRef ?? internalNodesRef;

  // ── Shared event bridge (trigger exec, application actions, __continue) ──
  useWorkflowEventBridge(executeTriggerRef, applicationActionRef, runContext, workflowId);

  // ── Handle run info changes from WorkflowBuilder ──
  const handleRunInfoChange = useCallback((data: RunInfoData) => {
    setCurrentRunInfo(data.runInfo);
    setIsStepByStep(data.isStepByStep);
    setCurrentEpoch(data.currentEpoch);
    setEpochTimestamps(data.epochTimestamps);
    setStreamedSteps((prev) => {
      const next = data.streamedSteps;
      if (prev !== next) {
        streamDebug.log('WorkflowRunCanvas', 'streamedSteps CHANGED:', {
          prevLength: prev?.length ?? 0,
          nextLength: next?.length ?? 0,
          nextSteps: next?.map((s: any) => `${s.alias}:c=${s.statusCounts?.completed},f=${s.statusCounts?.failed},s=${s.statusCounts?.skipped}`),
        });
      }
      return next;
    });

    // Forward to parent
    onRunInfoChange?.({
      runInfo: data.runInfo,
      isStepByStep: data.isStepByStep,
      currentEpoch: data.currentEpoch,
      epochTimestamps: data.epochTimestamps,
      streamedSteps: data.streamedSteps,
    });
  }, [onRunInfoChange]);

  // ── Run actions (stop / hard cancel / reactivate) ──
  //
  // Two call shapes on purpose. `runAction` REJECTS, so a caller that has its own
  // way of reporting (the panel surfaces, whose button shows the failure on
  // itself) can see what went wrong. The `handle*` wrappers below add the toast
  // and are what the canvas pill uses, since a pill click has nowhere else to
  // put an error.
  // The SAME run this canvas publishes to the bus, `contextRunId` included: a run
  // bound only through the mode context was published and then not claimable, so
  // actions on it silently took the REST path instead of the run manager the
  // whole delegation exists to keep in sync.
  const actionableRunId = currentRunInfo?.runId || currentRunInfo?.id || runId || contextRunId || null;
  const canRunAction = !!actionableRunId && !!runContext;
  const runAction = useCallback(async (action: RunPanelAction, requestedRunId?: string | null) => {
    // The CALLER's run wins. A panel binds the run the user just picked before
    // asking the page to rebind this canvas, so for that window the canvas is
    // still on the previous one - and acting on it would stop a different run
    // from the bar the button sits in.
    const targetRunId = requestedRunId || currentRunInfo?.runId || currentRunInfo?.id || runId || contextRunId;
    if (!targetRunId || !runContext) {
      // Never silently: a stop that does nothing and says nothing is the whole
      // bug. Callers turn this into a visible failure; `canRunAction` keeps the
      // bus from claiming a request this canvas could not have taken anyway.
      throw new Error('This canvas has no run bound to it');
    }
    if (action === 'stop') await runContext.cancelRun(targetRunId);
    else if (action === 'cancel') await runContext.hardCancelRun(targetRunId);
    else await runContext.reactivateRun(targetRunId);
  }, [currentRunInfo, runId, contextRunId, runContext]);

  // What the PILL shows while it works: same spinner and same "it failed" mark as
  // every other surface carrying this control, so the most-used stop of the
  // product is not the one that looks dead while it runs.
  /** The run on screen right now, for callbacks that settle after a rebind. */
  const actionableRunIdRef = useRef<string | null>(actionableRunId);
  useEffect(() => { actionableRunIdRef.current = actionableRunId; }, [actionableRunId]);
  const [pillAction, setPillAction] = useState<RunPanelAction | null>(null);
  /**
   * The run a failure was raised on, or `undefined` when nothing has failed.
   *
   * Three-valued on purpose: a stop pressed with NO run bound fails too, and it
   * fails on `null` - which a two-valued flag would read as "no failure" and
   * swallow, reintroducing the silent dead click.
   */
  const [pillFailed, setPillFailed] = useState<string | null | undefined>(undefined);
  const toastRunAction = useCallback((action: RunPanelAction) => {
    // Remembered up front: by the time this settles the canvas may be bound to
    // another run, and a failure mark belongs to the run it was raised on.
    const attemptedRunId = actionableRunIdRef.current;
    setPillFailed(undefined);
    setPillAction(action);
    runAction(action)
      .catch((err: unknown) => {
        console.error(`[WorkflowRunCanvas] Failed to ${action} workflow:`, err);
        setPillFailed(attemptedRunId);
        // Through next-intl like every other user-facing string: these three
        // messages were hardcoded English, and this is the pass that moved them.
        window.dispatchEvent(new CustomEvent('workflowToast', {
          detail: { type: 'error', message: tRoot('workflow.runAction.failed') },
        }));
      })
      // Run-keyed like the failure mark: a late settle must not stop the spinner
      // of a run this canvas has since rebound to.
      .finally(() => { if (actionableRunIdRef.current === attemptedRunId) setPillAction(null); });
  }, [runAction, tRoot]);

  const handleStopRun = useCallback(() => toastRunAction('stop'), [toastRunAction]);
  const handleCancelRun = useCallback(() => toastRunAction('cancel'), [toastRunAction]);
  const handleReactivateRun = useCallback(() => toastRunAction('reactivate'), [toastRunAction]);

  // ── The run the surfaces are bound to (URL run, in-place run, or the one the
  // run info itself reports) ──
  const activeRunId = actionableRunId;

  // ── Publish the run snapshot to the side-panel Run tab ──
  // The panel lives in the app-layout tree (no shared provider), so the state
  // travels through the run-panel bus, which also caches it for a panel that
  // mounts later. Mirrors how trigger/application configs already reach it.
  useEffect(() => {
    if (!workflowId) return;
    publishRunPanelData({
      workflowId,
      surfaceId,
      runId: activeRunId,
      runInfo: currentRunInfo,
      isStepByStep,
      currentEpoch,
      epochTimestamps,
      streamedSteps,
      pinnedVersion,
      isPreviewOnly,
    });
  }, [workflowId, surfaceId, activeRunId, currentRunInfo, isStepByStep, currentEpoch, epochTimestamps, streamedSteps, pinnedVersion, isPreviewOnly]);

  // Drop the cached snapshot when this canvas goes away. The cache exists so a
  // panel mounting LATER is not empty; kept past the canvas's life it answers the
  // next visit to the same workflow with the previous run, and the Run tab and the
  // application carousel render that stale run for a frame before the new canvas
  // publishes.
  useEffect(() => {
    if (!workflowId) return;
    return () => { publishRunPanelData(makeEmptyRunPanelData(workflowId, surfaceId)); };
  }, [workflowId, surfaceId]);

  // ── Run actions requested from the panel (it cannot call these handlers) ──
  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent<RunPanelActionDetail>).detail;
      if (!detail) return;
      // Both sides must name the same workflow. `workflowId` is required, so the
      // first term guards a shape the type system already rules out - kept
      // because CLAIMING is now a promise to act: a canvas that answered for a
      // workflow it does not own would suppress the caller's fallback.
      if (!workflowId || (detail.workflowId && detail.workflowId !== workflowId)) return;
      if ((detail.surfaceId ?? null) !== (surfaceId ?? null)) return;
      // Already taken by another canvas of the same workflow (a self-referencing
      // sub-workflow mounts two): one action, not two identical REST calls.
      if (detail.handled) return;

      // Declining is an ANSWER: a marketplace preview claims the request and does
      // nothing, so the caller does not fall back to the REST call and work around
      // a surface that is read-only by contract.
      if (isPreviewOnly) { detail.handled = true; return; }

      // Only claim what this canvas can actually carry out. Claiming
      // unconditionally re-created the very bug this protocol exists to kill: a
      // canvas with no bound run swallowed the request, suppressed the fallback,
      // and the click did nothing at all.
      if (!canRunAction) return;
      detail.handled = true;
      // The promise goes back to the caller, so `pending` is real and a failure
      // reaches the button that was pressed - not only the canvas' own toast,
      // which the application page and the share link do not even host.
      detail.result = runAction(detail.action, detail.runId);
    };
    window.addEventListener(RUN_PANEL_ACTION_EVENT, handler);
    return () => window.removeEventListener(RUN_PANEL_ACTION_EVENT, handler);
  }, [workflowId, surfaceId, isPreviewOnly, canRunAction, runAction]);

  // ── Entering run mode shows ALL epochs ──
  //
  // The canvas opens on the cumulative view: node badges carry the counts of the
  // whole run, not of one fire picked for the user. A single epoch is shown only
  // once it is chosen in the selector, and that choice is what this hook carries
  // across a remount of either surface.
  useDefaultEpochSelection({
    runId: activeRunId,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: setViewingEpoch,
    enabled: workflowMode === 'run',
  });

  return (
    <>
      {/* Workflow Mode Toggle (stop button hidden in preview) */}
      <WorkflowModeToggle
        mode={workflowMode}
        workflowId={workflowId}
        hideToggle={hideToggle}
        showReadOnlyBadge={isPreviewOnly}
        currentRunInfo={currentRunInfo}
        isStepByStep={isStepByStep}
        /* The workspace role gates these three like every other run control: a
           VIEWER may watch a workspace, not stop, cancel or re-arm its runs. */
        onStop={isPreviewOnly || !canMutate ? undefined : handleStopRun}
        onCancel={isPreviewOnly || hideToggle || !canMutate ? undefined : handleCancelRun}
        onReactivate={isPreviewOnly || !canMutate ? undefined : handleReactivateRun}
        actionPending={pillAction}
        /* Keyed on the RUN it was raised on: rebinding the canvas to another run
           must not paint that run's control as having failed. */
        actionFailed={pillFailed !== undefined && pillFailed === activeRunId}
        epochCount={epochTimestamps.length}
        pinnedVersion={pinnedVersion}
        isSettingsOpen={isSettingsOpen}
      />

      <WorkflowBuilder
        workflowId={workflowId}
        runId={runId}
        planOverride={planOverride}
        onDirtyChange={onDirtyChange}
        onRefreshBlocked={onRefreshBlocked}
        onWorkflowLoaded={onWorkflowLoaded}
        onRunInfoChange={handleRunInfoChange}
        onTriggerConfigsChange={onTriggerConfigsChange}
        onApplicationConfigsChange={onApplicationConfigsChange}
        onAgentConfigsChange={onAgentConfigsChange}
        onHasChatFormTrigger={onHasChatFormTrigger}
        executeTriggerRef={executeTriggerRef}
        applicationActionRef={applicationActionRef}
        saveRef={saveRef}
        nodesRef={canvasNodesRef}
        onSettingsOpenChange={setIsSettingsOpen}
      />
    </>
  );
}
