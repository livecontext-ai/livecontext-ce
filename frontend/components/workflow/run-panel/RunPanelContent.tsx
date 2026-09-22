'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ArrowLeft, ArrowRight, FileText, History, Loader2, Play, Workflow } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { WorkflowRun } from '@/lib/api/orchestrator';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { usePathname } from '@/i18n/navigation';
import { RunSummaryBar } from './RunSummaryBar';
import { RunStepsPanel } from './RunStepsPanel';
import { RunHistoryList } from './RunHistoryList';
import { isRunStatusActive } from './runFormatting';
import { markEpochPickedByUser, useDefaultEpochSelection } from './useDefaultEpochSelection';
import {
  getCachedRunPanelData,
  requestBindRun,
  subscribeRunPanelData,
  type RunPanelData,
} from './runPanelBus';
import { useRunActions } from './useRunActions';

export type RunPanelView = 'history' | 'run';

export interface RunPanelContentProps {
  workflowId: string;
  /**
   * Run history is the PARENT level: picking a run drills into its epochs/steps.
   * Disabled where the run is fixed by the surface itself - the marketplace
   * preview (frozen on a published version) and the application panel, which is
   * bound to the run its page opened.
   */
  allowHistory?: boolean;
  /**
   * Level requested from the outside (version chip → history, panel button →
   * run). Carries a sequence number so re-requesting the SAME level - e.g.
   * clicking the history button again after the user navigated away from it -
   * still applies. Must be a stable object identity per request.
   */
  viewRequest?: { view: RunPanelView; seq: number };
  /**
   * Which surface this panel belongs to, so the run it binds reaches that
   * surface's canvas and no other. Omitted on the workflow page, which owns the
   * route; a side-panel workflow tab passes its own id, otherwise a pick made
   * there would rewrite the page's URL whenever both show the same workflow.
   */
  surfaceId?: string;
  /**
   * Return to the workflow view of the host panel. Set only where there IS one to
   * return to (the workflow canvas sub-tab); the run bar then carries it as its
   * left-most control.
   *
   * The sub-tab bar already offers the same jump, but it sits at the BOTTOM of the
   * panel, below a run's epochs and steps: from the top of a long run the way back
   * to the canvas is off screen. This puts it next to the run identity it belongs to.
   */
  onBackToWorkflow?: () => void;
  /** Open the logs child view for the run currently shown. */
  onOpenLogs?: () => void;
}

/**
 * The Run tab of the workflow / application panel.
 *
 * Two levels, one hierarchy:
 *   history (which run?) → run (which epoch? which step?)
 * The run level always shows the run identity bar on top - the same component
 * the canvas pill uses - so the user never loses track of which run the epochs
 * and steps below belong to, and can walk back up with one click.
 */
export function RunPanelContent({ workflowId, allowHistory = false, viewRequest, surfaceId, onBackToWorkflow, onOpenLogs }: RunPanelContentProps) {
  const t = useTranslations();
  const { runId: contextRunId, setRunId, viewingEpoch, setViewingEpoch } = useWorkflowMode();

  const [data, setData] = useState<RunPanelData>(() => getCachedRunPanelData(workflowId, surfaceId));
  useEffect(() => {
    setData(getCachedRunPanelData(workflowId, surfaceId));
    return subscribeRunPanelData(workflowId, setData, surfaceId);
  }, [workflowId, surfaceId]);

  /**
   * Run the user just picked in the history, until the canvas catches up.
   *
   * The canvas is the source of truth for `data.runId`, but rebinding it is a
   * round trip: it recomputes its run from freshly fetched run info, not on the
   * next tick. Preferring the bus during that window bounced the panel straight
   * back to the previous run - and the provider with it, which clears the
   * interface stores on every run change, so the application carousel reset
   * twice for one click.
   */
  const justPickedRunRef = useRef<string | null>(null);
  if (data.runId && data.runId === justPickedRunRef.current) justPickedRunRef.current = null;

  const runId = justPickedRunRef.current ?? data.runId ?? contextRunId ?? null;
  const canBrowseHistory = allowHistory && !data.isPreviewOnly;
  /**
   * A run is bound as soon as an id is known, even before the canvas has
   * published its first snapshot. The two are distinct on purpose: the run
   * level stays open (showing a placeholder) while the details load, instead of
   * bouncing back to the history the moment the tab mounts.
   */
  const hasRun = !!data.runInfo || !!runId;

  const [view, setView] = useState<RunPanelView>(() => {
    if (!canBrowseHistory) return 'run';
    if (viewRequest) return viewRequest.view;
    return hasRun ? 'run' : 'history';
  });

  // A surface that cannot browse history is always on the run level.
  useEffect(() => {
    if (!canBrowseHistory && view !== 'run') setView('run');
  }, [canBrowseHistory, view]);

  // Honour an external "open on this level" request (version chip / history button).
  // Keyed on the request sequence so the same level can be re-requested.
  useEffect(() => {
    if (!viewRequest) return;
    if (!canBrowseHistory && viewRequest.view === 'history') return;
    setView(viewRequest.view);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- react to explicit requests only
  }, [viewRequest?.seq]);

  // With no run at ALL there is nothing to detail - fall back to the history
  // list. Gated on `hasRun`, not on `runInfo`: a run whose snapshot has not
  // arrived yet is still a run, and bouncing to the history there is exactly
  // what made "open the run I just launched" land on the wrong level.
  useEffect(() => {
    if (canBrowseHistory && !hasRun && view === 'run') setView('history');
  }, [canBrowseHistory, hasRun, view]);

  /**
   * Tell THIS panel's WorkflowModeProvider which run it is showing.
   *
   * The side panel mounts its own provider, and the workflow-panel tab does not
   * name a run when it does - the run arrives later, over the bus. Until the
   * provider knows it, its `viewingEpoch` broadcast carries `runId: null`, which
   * `shouldAdoptEpochEvent` treats as "for everyone": picking an epoch here
   * dragged every other mounted canvas (a sub-workflow tab, an application tab)
   * to the same epoch. Scoping is the whole point of that event's runId.
   */
  useEffect(() => {
    // Never while a freshly picked run is waiting for the canvas: `data.runId` is
    // still the PREVIOUS run then, and adopting it would undo the pick.
    if (justPickedRunRef.current) return;
    if (data.runId && data.runId !== contextRunId) setRunId(data.runId);
  }, [data.runId, contextRunId, setRunId]);

  const handleSelectEpoch = useCallback((epoch: number | null) => {
    // Remember the choice for this run so the other surface, and this panel
    // after it is closed and reopened, show the same thing. `null` (All epochs)
    // is a real choice, not an absence of one.
    markEpochPickedByUser(runId, epoch);
    setViewingEpoch(epoch);
  }, [runId, setViewingEpoch]);

  /**
   * The panel opens on the same view as the canvas beside it: all epochs, unless
   * the user picked one. Nothing is selected here either - this only restores a
   * pick made on the other surface, since the panel body does not exist while
   * the side panel is closed and `viewingEpoch` dies with its provider.
   *
   * Gated on the provider having ADOPTED this run (`contextRunId === runId`),
   * not merely on knowing it: `setRunId` above is a state update, so restoring in
   * the same commit would broadcast the epoch with `runId: null`, which every
   * other mounted provider adopts - the cross-talk this panel's scoping exists
   * to prevent, on the very first selection.
   */
  useDefaultEpochSelection({
    runId,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: setViewingEpoch,
    enabled: hasRun && !!runId && contextRunId === runId,
  });

  const handleSelectRun = useCallback((run: WorkflowRun) => {
    const nextRunId = run.runId || run.id;
    if (!nextRunId) return;
    // Bind the panel immediately so the run level renders without waiting for
    // the canvas to publish its own snapshot, and remember the pick so the
    // still-stale bus snapshot cannot pull us back to the previous run...
    justPickedRunRef.current = nextRunId;
    setRunId(nextRunId);
    setView('run');
    // ...then ask the page to rebind the canvas IN PLACE. No router.push: that
    // remounted the whole route and read as a full-page refresh just to look at
    // another run. The page also realigns the address bar (it owns the route;
    // this panel is mounted on surfaces whose URL says nothing about a run), so
    // a reload comes back on the run that was picked here.
    //
    // Sent even for the run already on screen: an agent-launched run is bound
    // in place on the edit URL, and picking it here is how a user says "keep
    // this one". The page no-ops when there is nothing left to align.
    requestBindRun({ workflowId, runId: nextRunId, surfaceId });
  }, [workflowId, setRunId, surfaceId]);

  // Routed through the shared performer, not a bare event dispatch: the canvas
  // acts when it is mounted, and the REST call is made when it is not. The panel
  // is reachable from surfaces the canvas is not (and can outlive its unmount),
  // where the event alone was a click that did nothing at all.
  const { pending: actionPending, failed: actionFailed, perform } = useRunActions(workflowId, runId, surfaceId);
  /**
   * Same gate the two new surfaces carry, and it belongs here MORE, not less:
   * this bar offers the hard cancel and the reactivate as well as the stop. A
   * gate that stopped a VIEWER pressing stop in the tab bar while leaving them
   * free to CANCEL the same run one tab away closed nothing.
   */
  const canMutate = useCanMutateInCurrentOrg();
  /**
   * And the same share-route exclusion the two new surfaces carry.
   *
   * This bar sits in the very subtree those guard, and offers MORE: cancel and
   * reactivate as well as stop. What keeps it off a share page today is a
   * `display:none` wrapper, which is layout, not authorization - and the role
   * check does not help, because an anonymous visitor has no organisation and
   * reads as a personal workspace. Stopping is not in the gateway's share
   * allow-list, so every one of these would 403.
   */
  const pathname = usePathname();
  const canAct = canMutate && !data.isPreviewOnly && !(pathname ?? '').startsWith('/s/');

  const isRunActive = useMemo(() => isRunStatusActive(data.runInfo?.status), [data.runInfo?.status]);

  /**
   * Back to the canvas. Rendered on BOTH levels of this tab.
   *
   * The history is not a lesser case: a workflow with no runs opens the Run tab
   * straight onto it, and a long list of runs pushes the panel's sub-tab bar off
   * the bottom exactly like a long list of steps does. Leaving it out made the
   * one state a user reaches before their first run a dead end.
   */
  const backToWorkflowButton = onBackToWorkflow ? (
    <button
      type="button"
      data-run-panel-to-workflow
      onClick={onBackToWorkflow}
      title={t('workflow.runInfo.backToWorkflow')}
      className="flex items-center gap-1 h-6 pl-1.5 pr-2 rounded-lg border border-theme text-sm font-medium text-theme-secondary hover:bg-theme-secondary hover:text-theme-primary transition-colors min-w-0"
    >
      <Workflow className="w-3.5 h-3.5 flex-shrink-0" />
      {/* The LABEL gives up the room, not the run identity. This control sits in
          the bar's leading slot, before an overflow-hidden chip track: pinned at
          its full width it would push the run's status and version out of the bar
          entirely once the panel is narrow (a detached window goes down to 320px).
          Shrinking to the icon degrades to exactly what the history arrow beside
          it already is. */}
      <span className="truncate">{t('common.workflow')}</span>
    </button>
  ) : null;

  // ── History level ──
  if (view === 'history') {
    return (
      <div className="flex-1 min-h-0 flex flex-col">
        {backToWorkflowButton && (
          <div className="flex items-center px-3 sm:px-4 py-2 flex-shrink-0 border-b border-theme">
            {backToWorkflowButton}
          </div>
        )}
        <RunHistoryList
          workflowId={workflowId}
          currentRunId={runId}
          onSelectRun={handleSelectRun}
        />
      </div>
    );
  }

  // ── Run level ──
  if (!data.runInfo) {
    // A run id without a snapshot = the canvas is still attaching to it (the
    // usual case right after pressing Run). Say "loading", not "no runs".
    return (
      <div className="flex-1 min-h-0 flex flex-col items-center justify-center gap-2 p-6 text-center" data-run-panel-detail>
        {runId ? (
          <>
            <Loader2 className="w-8 h-8 text-theme-secondary opacity-40 animate-spin" />
            <p className="text-sm text-theme-secondary">{t('runs.loading')}</p>
          </>
        ) : (
          <>
            <Play className="w-10 h-10 text-theme-secondary opacity-40" />
            <p className="text-sm text-theme-secondary">{t('runs.noRuns')}</p>
          </>
        )}
        {(onBackToWorkflow || canBrowseHistory) && (
          <div className="flex items-center gap-2">
            {/* Same escape hatch as the run header below. Without it this state is
                the one place in the Run tab with no way back to the canvas - and it
                is the state the tab opens on while a freshly launched run attaches. */}
            {onBackToWorkflow && (
              <Button variant="outline" size="sm" data-run-panel-to-workflow onClick={onBackToWorkflow}>
                <Workflow className="w-3.5 h-3.5 mr-1.5" />
                {t('common.workflow')}
              </Button>
            )}
            {canBrowseHistory && (
              <Button variant="outline" size="sm" onClick={() => setView('history')}>
                <History className="w-3.5 h-3.5 mr-1.5" />
                {t('runs.title')}
              </Button>
            )}
          </div>
        )}
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 flex flex-col" data-run-panel-detail>
      <RunSummaryBar
        currentRunInfo={data.runInfo}
        pinnedVersion={data.pinnedVersion}
        /* The epochs the run HAS - the same list the selector below renders -
           never the engine's cursor, which already points at the next, dormant
           epoch as soon as one finishes. */
        epochCount={data.epochTimestamps?.length ?? 0}
        selectedEpoch={viewingEpoch}
        isStepByStep={data.isStepByStep}
        onStop={canAct ? () => perform('stop') : undefined}
        onCancel={canAct ? () => perform('cancel') : undefined}
        onReactivate={canAct ? () => perform('reactivate') : undefined}
        actionPending={actionPending}
        actionFailed={actionFailed}
        onVersionClick={canBrowseHistory ? () => setView('history') : undefined}
        size="panel"
        className="border-b border-theme"
        leading={(onBackToWorkflow || canBrowseHistory) ? (
          <span className="flex items-center gap-1 min-w-0">
            {/* Back to the canvas. Labelled, unlike the history arrow next to it:
                it leaves the Run view entirely, so it names where it goes instead
                of relying on a glyph, and it comes first as the outermost step out. */}
            {backToWorkflowButton}
            {canBrowseHistory && (
              /* Arrow + history icon: the arrow alone says "back", the icon says
                 back TO WHAT - the list of runs this one was picked from. */
              <button
                type="button"
                data-run-panel-back
                onClick={() => setView('history')}
                title={t('runs.title')}
                className="flex items-center gap-0.5 h-5 pl-1 pr-1.5 rounded-lg text-theme-secondary hover:bg-theme-secondary hover:text-theme-primary transition-colors flex-shrink-0"
              >
                <ArrowLeft className="w-3 h-3" />
                <History className="w-3.5 h-3.5" />
              </button>
            )}
          </span>
        ) : undefined}
        trailing={onOpenLogs ? (
          <button
            type="button"
            onClick={onOpenLogs}
            title={t('workflow.logs.openLogs')}
            aria-label={t('workflow.logs.openLogs')}
            className="flex h-7 flex-shrink-0 items-center gap-1 rounded-lg px-1.5 text-theme-secondary transition-colors hover:bg-theme-secondary hover:text-theme-primary"
          >
            <FileText className="h-3.5 w-3.5" />
            <ArrowRight className="h-3.5 w-3.5" />
          </button>
        ) : undefined}
      />

      <RunStepsPanel
        currentRunInfo={data.runInfo}
        streamedSteps={data.streamedSteps}
        epochTimestamps={data.epochTimestamps}
        selectedEpoch={viewingEpoch}
        onSelectEpoch={handleSelectEpoch}
        workflowId={workflowId}
        isStepByStep={data.isStepByStep}
        isRunActive={isRunActive}
      />
    </div>
  );
}
