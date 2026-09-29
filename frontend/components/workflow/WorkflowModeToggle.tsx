'use client';

import { useRef, useEffect, useState, useCallback, useId } from 'react';
import { Edit3, History, Play, Eye } from 'lucide-react';
import { formatCost } from '@/lib/format-cost';
import { useTranslations } from 'next-intl';
import { budgetCapNeverResets, budgetPeriodLabelKey, resolveBudgetPeriod } from '@/components/budget/budgetPeriod';
import { useRouter, usePathname } from 'next/navigation';
import { orchestratorApi, type WorkflowRun } from '@/lib/api';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { canvasChromeButtonClass, canvasChromeSurfaceClass, canvasChromeCompactButtonClass } from '@/components/ui/canvas-chrome';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';
import { isEmbeddedWorkflowCanvas } from '@/lib/workflow/canvasEmbedding';
import { computeRunInfoPanelWidths } from '@/components/workflow/runInfoPanelWidth';
import { RunSummaryBar } from '@/components/workflow/run-panel/RunSummaryBar';
import type { RunPanelAction } from '@/components/workflow/run-panel/runPanelBus';
import { openRunPanel } from '@/components/workflow/run-panel/runPanelBus';
import { markUserModeToggle } from '@/lib/workflow/workflowPanelChat';
import { EPOCH_NAV_AUTO_OPEN_FROM, RunEpochNavigator } from '@/components/workflow/run-panel/RunEpochNavigator';
import { markEpochPickedByUser } from '@/components/workflow/run-panel/useDefaultEpochSelection';
import type { EpochTimestamp } from '@/components/workflow/run-panel/runFormatting';

interface WorkflowModeToggleProps {
  mode: 'edit' | 'run';
  onModeChange?: (mode: 'edit' | 'run') => void;
  workflowId?: string;
  /** Hide the edit/run mode toggle (for application mode, always in run) */
  hideToggle?: boolean;
  /** Show a "Read only" badge instead of the toggle */
  showReadOnlyBadge?: boolean;
  // Run info props
  currentRunInfo?: (WorkflowRun & {
    completedCount?: number;
    failedCount?: number;
    runningCount?: number;
    skippedCount?: number;
    executionTotal?: number;
    /** Per-epoch cost breakdown, epoch number (as string) -> credits. */
    costByEpoch?: Record<string, number>;
  }) | null;
  isStepByStep?: boolean;
  onStop?: () => void;
  onCancel?: () => void;
  onReactivate?: () => void;
  /** Action in flight, so the pill's control spins instead of looking dead. */
  actionPending?: RunPanelAction | null;
  /** Whether the last action failed, marked on the pill's control. */
  actionFailed?: boolean;
  /** How many epochs the run has, i.e. how many rows its epoch selector lists. */
  epochCount?: number;
  /** The run's epochs, for the navigator the epoch chip unfolds. Omit to keep the chip a label. */
  epochTimestamps?: EpochTimestamp[];
  /** Pinned (production) version of the workflow, null if unpinned */
  pinnedVersion?: number | null;
  /** When the settings panel is open, hide the run bar & history button */
  isSettingsOpen?: boolean;
}

/**
 * Canvas chrome for a workflow: the edit/run toggle (centered) and the compact
 * run bar + history button (top right).
 *
 * The run bar is IDENTITY ONLY - status · version · epoch · start ·
 * step-by-step, with stop/cancel at the far right. Everything that used to
 * expand out of it (epoch selector, step list, cost) now lives in the side
 * panel's Run tab, so nothing floats over the ReactFlow canvas any more.
 *
 * The bar IS the entry point - there is no separate history button and no panel
 * icon next to it: clicking the BAR opens the CURRENT run, clicking the version
 * chip inside it opens the run HISTORY.
 */
export function WorkflowModeToggle({
  mode,
  onModeChange,
  workflowId,
  hideToggle = false,
  showReadOnlyBadge = false,
  currentRunInfo,
  isStepByStep = false,
  onStop,
  onCancel,
  onReactivate,
  actionPending = null,
  actionFailed = false,
  epochCount = 0,
  epochTimestamps,
  pinnedVersion,
  isSettingsOpen = false,
}: WorkflowModeToggleProps) {
  const t = useTranslations();
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, addToast, removeToast } = useToast();

  // Surface an explanatory toast when the backend refuses to open a new epoch
  // because the workflow budget was reached (the in-flight epoch still finishes).
  // WorkflowRunManager (non-React) dispatches this window CustomEvent on the
  // runBudgetBlocked WS event; we only react to the run this bar is showing.
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as {
        runId?: string; spentCredits?: number; budgetCredits?: number; periodMode?: string | null;
      } | undefined;
      if (!detail) return;
      const panelRunId = currentRunInfo?.runId;
      if (panelRunId && detail.runId && detail.runId !== panelRunId) return;
      // A cap that never resets gets its OWN sentence. The ordinary message
      // ends with "it resumes on its own next period", which is true of the
      // monthly and weekly cadences and a plain falsehood for a lifetime cap:
      // there is no next period, and the workflow stays stopped until its owner
      // changes the cap. Sending a blocked user away to wait for a reset that
      // will never come is the worst thing this toast could do.
      // RESOLVED, like the popover: `budgetPeriodLabelKey` two lines below
      // falls back on a cadence nobody implements and this would not, so on such
      // a value the toast would name one period and promise the other's reset.
      // Same answer as before on every cadence in use; see resolveBudgetPeriod.
      const neverResets = budgetCapNeverResets(resolveBudgetPeriod(detail.periodMode));
      addToast({
        type: 'warning',
        title: t('workflow.runInfo.budgetBlockedTitle'),
        message: neverResets
          ? t('workflow.runInfo.budgetBlockedMessageCumulative', {
              spent: formatCost(detail.spentCredits ?? null),
              budget: formatCost(detail.budgetCredits ?? null),
            })
          : t('workflow.runInfo.budgetBlockedMessage', {
              spent: formatCost(detail.spentCredits ?? null),
              budget: formatCost(detail.budgetCredits ?? null),
              period: t(budgetPeriodLabelKey(detail.periodMode)),
            }),
      });
    };
    window.addEventListener('workflow:runBudgetBlocked', handler as EventListener);
    return () => window.removeEventListener('workflow:runBudgetBlocked', handler as EventListener);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentRunInfo?.runId]);

  // Canvas run identity - used to scope the cross-tree viewingEpochChanged event
  // so side-panel tabs bound to OTHER runs don't move this canvas's epoch chip.
  const { setViewingEpoch, setRunId, runId: canvasRunId } = useWorkflowMode();
  /** Epoch shown in the chip. Mirrors the panel's selection through the scoped event. */
  const [selectedEpoch, setSelectedEpoch] = useState<number | null>(null);

  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail as EpochEventDetail | undefined;
      // Ignore epoch changes broadcast for a DIFFERENT run (e.g. a sibling app
      // tab in the side panel) so this canvas only follows its own run's epoch.
      if (!shouldAdoptEpochEvent(detail?.runId, canvasRunId)) return;
      setSelectedEpoch(detail?.epoch ?? null);
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler);
  }, [canvasRunId]);

  /**
   * Whether the pill's epoch navigator is unfolded. By default it is unfolded on a run
   * with enough epochs to be worth browsing (EPOCH_NAV_AUTO_OPEN_FROM), folded below.
   * A click on the chip is an explicit choice that wins, for the run and mode it was
   * made on: moving to another run or mode drops it in that same render (no effect, no
   * frame showing the old choice), so the next run opens on ITS default again.
   */
  const epochNavKey = `${canvasRunId ?? ''}|${mode}`;
  const [epochNavChoice, setEpochNavChoice] = useState<{ key: string; open: boolean } | null>(null);
  if (epochNavChoice !== null && epochNavChoice.key !== epochNavKey) setEpochNavChoice(null);
  const epochNavDefaultOpen = (epochTimestamps?.length ?? 0) >= EPOCH_NAV_AUTO_OPEN_FROM;
  const epochNavOpen = epochNavChoice?.key === epochNavKey ? epochNavChoice.open : epochNavDefaultOpen;
  const epochNavId = useId();
  const runPillRef = useRef<HTMLDivElement>(null);
  const toggleEpochNav = useCallback(
    () => setEpochNavChoice({ key: epochNavKey, open: !epochNavOpen }),
    [epochNavKey, epochNavOpen],
  );
  /** Folding from inside the navigator hands focus back to the chip that unfolded it. */
  const closeEpochNav = useCallback(() => {
    setEpochNavChoice({ key: epochNavKey, open: false });
    runPillRef.current?.querySelector<HTMLElement>('[data-run-epoch-chip]')?.focus();
  }, [epochNavKey]);

  /** A pick in the pill is a user choice, recorded like one made in the Run panel. */
  const handleNavigatorSelectEpoch = useCallback((epoch: number | null) => {
    markEpochPickedByUser(canvasRunId, epoch);
    setViewingEpoch(epoch);
  }, [canvasRunId, setViewingEpoch]);

  /** Invisible probe element to measure the real available container width
   *  (accounts for SidePanel, sidebar, etc. - not just window.innerWidth). */
  const probeRef = useRef<HTMLDivElement>(null);
  /** Available width of the canvas container (updated via ResizeObserver). */
  const [containerWidth, setContainerWidth] = useState(
    typeof window !== 'undefined' ? window.innerWidth : 1200,
  );

  const isRunMode = mode === 'run';
  const showRunInfo = isRunMode && !!currentRunInfo;

  // Measure the real available container width via ResizeObserver on the
  // invisible probe element. This correctly handles SidePanel open/close,
  // sidebar collapse, and window resize - unlike window.innerWidth.
  useEffect(() => {
    const el = probeRef.current?.parentElement;
    if (!el) return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Derive isWideEnough from containerWidth - center the toggle only when there
  // is enough room so it won't collide with the run bar. Without a run bar the
  // only thing sharing the top edge is the add-node button in the corner, so a
  // phone-width canvas can still centre the ~88px toggle: the old 640 floor
  // pushed it into the left corner on every mobile EDIT canvas for no reason.
  const isWideEnough = containerWidth >= (showRunInfo ? 900 : 420);
  /** Centred when it fits, left-anchored otherwise (the run bar owns the right
   *  edge). The tighter inset on a phone keeps it clear of the canvas border. */
  const toggleAnchorClass = isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-2 sm:left-4';

  // Drop the epoch chip when the mode changes so the view never carries over
  // the epoch of whatever was on screen before. This is NOT a user choice, so
  // it must not be recorded as one (only a click may do that): a run the user
  // had pinned to an epoch keeps that pick and restores it.
  useEffect(() => {
    setSelectedEpoch(null);
    setViewingEpoch(null);
  }, [mode, setViewingEpoch]);

  const isEmbedded = isEmbeddedWorkflowCanvas(pathname, workflowId);

  const handleModeClick = async (newMode: 'edit' | 'run') => {
    if (!workflowId) return;

    // Allow re-clicking Run mode to load latest run (refresh)
    if (newMode === mode && newMode === 'edit') return;

    if (newMode === 'run') {
      // Show the most recent run regardless of version (debug/observation tool).
      try {
        const latestRun = await orchestratorApi.getLatestWorkflowRun(workflowId);
        if (latestRun) {
          const actualRunId = latestRun.runId || (latestRun as any).id;
          // Re-clicking Run on the run already shown changes nothing: no mark to leave behind.
          if (actualRunId !== canvasRunId) markUserModeToggle(workflowId, actualRunId);
          if (isEmbedded) {
            setRunId(actualRunId);
          } else {
            router.push(`/app/workflow/${workflowId}/run/${actualRunId}`);
          }
          onModeChange?.(newMode);
        } else {
          addToast({
            type: 'info',
            title: t('workflow.mode.noRunsTitle'),
            message: t('workflow.mode.noRunsMessage'),
          });
        }
      } catch (error) {
        console.error('[WorkflowModeToggle] Failed to load run:', error);
      }
    } else {
      markUserModeToggle(workflowId, null);
      if (isEmbedded) {
        setRunId(null);
      } else {
        // ALWAYS clear the binding, then navigate if there is a /run/ URL to
        // leave. Relying on the URL alone dead-ends: an agent-launched run is
        // bound in place and latches the provider's "programmatic" flag, which
        // makes it ignore pathname changes from then on - so the push landed on
        // the edit URL while the canvas stayed in run mode and the toggle
        // snapped back to Run. (A run picked in the panel's history does NOT
        // latch it: that binding carries the URL with it.)
        setRunId(null);
        if (pathname?.includes('/run/')) {
          router.push(`/app/workflow/${workflowId}`);
        }
      }
      onModeChange?.(newMode);
    }
  };

  const openPanel = useCallback((view: 'history' | 'run') => {
    openRunPanel({ workflowId, view });
  }, [workflowId]);

  // Width bound for the run bar - it must never exceed the container (a narrow
  // side-panel canvas used to push it off-screen, out of reach).
  const { maxWidth: runInfoMaxWidth } = computeRunInfoPanelWidths(
    containerWidth,
    !hideToggle && !showReadOnlyBadge,
  );

  return (
    <>
      {/* Invisible probe - spans the full container width so ResizeObserver
          can measure the real available space (not window.innerWidth). */}
      <div ref={probeRef} className="absolute inset-x-0 top-0 h-0 pointer-events-none" aria-hidden />

      {/* Mode Toggle - Centered (hidden in application/preview mode). A segmented
          pair of canvas-chrome controls: the active one wears the same resting
          fill a selected panel tab does, which is why the old absolutely
          positioned slider is gone - the state now lives on the button itself. */}
      {!hideToggle && !showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${toggleAnchorClass}`}>
          <div className={`inline-flex items-center gap-0.5 p-1 ${canvasChromeSurfaceClass}`}>
            {/* Edit Mode Button */}
            <button
              type="button"
              aria-pressed={mode === 'edit'}
              data-testid="workflow-mode-edit"
              onClick={() => handleModeClick('edit')}
              title={t('workflow.mode.edit')}
              className={canvasChromeCompactButtonClass(mode === 'edit')}
            >
              <Edit3 className="w-3.5 h-3.5" />
            </button>

            {/* Run Mode Button */}
            <button
              type="button"
              aria-pressed={mode === 'run'}
              data-testid="workflow-mode-run"
              onClick={() => handleModeClick('run')}
              title={t('workflow.mode.run')}
              className={canvasChromeCompactButtonClass(mode === 'run')}
            >
              <Play className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Read-only badge (shown in preview mode instead of the toggle) */}
      {showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${toggleAnchorClass}`}>
          <div className={`inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-[var(--text-secondary)] ${canvasChromeSurfaceClass}`}>
            <Eye className="w-4 h-4" />
            <span>{t('workflow.mode.readOnly')}</span>
          </div>
        </div>
      )}

      {/* Run bar & history button - Right side */}
      {isRunMode && !isSettingsOpen && (
        <div className="absolute top-4 right-2 sm:right-4 z-[40] flex items-start gap-2 sm:gap-3 pointer-events-none" style={{ maxWidth: runInfoMaxWidth }}>
          {/* No run info (still loading, or its fetch failed): the bar cannot
              render, and with it went the ONLY canvas route into the run history -
              on dev the History button was a separate control that did not depend
              on it. This standalone chip keeps that route open. */}
          {!showRunInfo && (
            <button
              type="button"
              data-run-history-fallback
              onClick={() => openPanel('history')}
              title={t('runs.title')}
              // A standalone floating control, so it carries the chrome surface
              // itself instead of sitting on a card. Passed through the helper's
              // className slot (not concatenated) so twMerge resolves the two
              // background/border layers instead of leaving both in the class list.
              className={canvasChromeButtonClass(false, 'pointer-events-auto border-[var(--border-color)] bg-[var(--bg-primary)]/95 backdrop-blur')}
            >
              <History className="w-4 h-4" />
            </button>
          )}
          {/* Compact run bar - identity only, the panel holds the detail.
              The WHOLE bar is the way into the run panel: a dedicated icon for
              that was one more glyph competing with the chips for room in a pill
              that already overflows on a narrow canvas. Its inner controls
              (version chip -> history, epoch chip -> navigator, stop/cancel/
              reactivate, scroll arrows) stop the click, so they keep their own
              behaviour.
              The surface is a plain wrapper and the button is the row inside it:
              the epoch navigator unfolds UNDER the row as its sibling, so it is
              not a child of a role=button (whose content assistive tech flattens)
              and hovering it does not light up the "open the panel" target. */}
          {showRunInfo && (
            <div
              ref={runPillRef}
              data-run-info-panel
              className={`pointer-events-auto w-fit max-w-full min-w-0 ${canvasChromeSurfaceClass}`}
            >
            <div
              data-run-pill-row
              data-run-open-panel
              role="button"
              tabIndex={0}
              title={t('workflow.runInfo.openInPanel')}
              onClick={() => openPanel('run')}
              onKeyDown={(e) => {
                // The bar ONLY - never a key pressed on a control inside it.
                // The inner buttons stop the click, but a keydown has no such
                // guard: Enter on the version chip would open the history AND
                // this run level, and the preventDefault below would swallow
                // Space on the stop button before the browser could activate it.
                if (e.target !== e.currentTarget) return;
                if (e.key !== 'Enter' && e.key !== ' ') return;
                e.preventDefault();
                openPanel('run');
              }}
              // The affordances of a control: the whole row is clickable. Hover and
              // ring go through the theme tokens the rest of the chrome uses rather
              // than a fixed gray, rounded like the surface so neither bleeds past it.
              className={`cursor-pointer transition-colors hover:bg-[var(--bg-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] ${epochNavOpen ? 'rounded-t-2xl' : 'rounded-2xl'}`}
            >
              <RunSummaryBar
                currentRunInfo={currentRunInfo!}
                pinnedVersion={pinnedVersion}
                epochCount={epochCount}
                selectedEpoch={selectedEpoch}
                isStepByStep={isStepByStep}
                onStop={onStop}
                onCancel={onCancel}
                onReactivate={onReactivate}
                actionPending={actionPending}
                actionFailed={actionFailed}
                onVersionClick={() => openPanel('history')}
                onEpochChipClick={epochTimestamps && epochTimestamps.length > 0 ? toggleEpochNav : undefined}
                epochChipExpanded={epochNavOpen}
                epochChipControls={epochNavId}
              />
            </div>
              {/* Unfolds DOWNWARD only: the navigator takes the width the identity
                  row already has and never widens the pill. */}
              {epochNavOpen && epochTimestamps && epochTimestamps.length > 0 && (
                <RunEpochNavigator
                  id={epochNavId}
                  epochTimestamps={epochTimestamps}
                  selectedEpoch={selectedEpoch}
                  runStatus={currentRunInfo?.status}
                  onSelectEpoch={handleNavigatorSelectEpoch}
                  onClose={closeEpochNav}
                />
              )}
            </div>
          )}
        </div>
      )}

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </>
  );
}
