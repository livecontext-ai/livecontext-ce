'use client';

import { useCallback, type ReactNode } from 'react';
import { useTranslations, useLocale } from 'next-intl';
import { StepForward, Calendar, ChevronLeft, ChevronRight, History, Pin } from 'lucide-react';
import { canvasChromeChipRadiusClass } from '@/components/ui/canvas-chrome';
import { formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus, getStatusClasses, getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { useHorizontalScrollHint } from '@/hooks/useHorizontalScrollHint';
import { RunActionButton } from './RunActionButton';
import type { RunPanelAction } from './runPanelBus';

export interface RunSummaryRunInfo {
  runId?: string;
  id?: string;
  status?: string;
  metadata?: Record<string, unknown> | null;
  planVersion?: number | null;
  startedAt?: string | null;
}

export interface RunSummaryBarProps {
  currentRunInfo: RunSummaryRunInfo;
  /** Hide the status chip when the caller knows the run summary could not be loaded. */
  showStatus?: boolean;
  /** Pinned (production) version of the workflow, null if unpinned. */
  pinnedVersion?: number | null;
  /**
   * How many epochs this run HAS - the length of the list the epoch selector
   * shows, not the engine's epoch cursor.
   *
   * The two differ by one for most of a run's life, and the cursor is the wrong
   * one to print: once an epoch finishes, the engine immediately PREPARES the
   * next cycle (`prepareNextCycle`), which moves the cursor to N+1 while that
   * epoch is dormant and has no row of its own. A run fired once therefore
   * reports cursor 2, and the bar used to say "All epochs (2)" next to a selector
   * listing one.
   */
  epochCount?: number;
  /** Epoch currently being viewed - shown in the epoch chip when set. */
  selectedEpoch?: number | null;
  isStepByStep?: boolean;
  /** Graceful stop (RUNNING/PAUSED → WAITING_TRIGGER). Omit to hide. */
  onStop?: () => void;
  /** Hard cancel (WAITING_TRIGGER → CANCELLED), asks for confirmation. Omit to hide. */
  onCancel?: () => void;
  /** Reactivate a terminal run (→ WAITING_TRIGGER). Omit to hide. */
  onReactivate?: () => void;
  /**
   * Click handler on the version chip. Wired to "open the run history" so the
   * bar is a shortcut into the list of every run of this workflow.
   */
  onVersionClick?: () => void;
  /** Rendered at the far left, before the chips (e.g. a back arrow). */
  leading?: ReactNode;
  /** Rendered after the identity chips, before run lifecycle actions. */
  trailing?: ReactNode;
  /**
   * Type scale. `compact` is the canvas pill, which must stay exactly as it was
   * (it shares the top of the canvas with the nodes). `panel` matches the run
   * history / step rows it sits above in the side panel, where the same `text-xs`
   * read as noticeably smaller than the list underneath it.
   */
  size?: 'compact' | 'panel';
  /** Action currently in flight, so the control can spin instead of looking dead. */
  actionPending?: RunPanelAction | null;
  /** Whether the last action failed, surfaced on the control's title. */
  actionFailed?: boolean;
  className?: string;
}

/**
 * The compact run identity bar: status · version (+pin) · epoch · started-at ·
 * step-by-step, with stop/cancel/reactivate pinned at the FAR RIGHT.
 *
 * One component, two surfaces: the floating pill on the canvas and the header of
 * the side-panel Run tab. Keeping them on the same component is what guarantees
 * "same info, same look" between the two.
 *
 * Responsiveness is a horizontal scroll, not a set of breakpoints: the chips
 * live in a scrollable track that fades and grows an arrow on whichever side
 * still hides content. Hiding chips below `sm`/`md` (what this did before) made
 * the version - the ONLY route into the run history - vanish on a narrow canvas
 * or in a side panel, with nothing telling the user it was there.
 */
export function RunSummaryBar({
  currentRunInfo,
  showStatus = true,
  pinnedVersion,
  epochCount = 0,
  selectedEpoch = null,
  isStepByStep = false,
  onStop,
  onCancel,
  onReactivate,
  onVersionClick,
  leading,
  trailing,
  size = 'compact',
  actionPending = null,
  actionFailed = false,
  className = '',
}: RunSummaryBarProps) {
  const t = useTranslations();
  const locale = useLocale();
  const formatRel = useCallback(
    (d: string | Date | null | undefined) =>
      formatRelativeDateI18n(d, (k, p) => t(`runs.${k}`, p), locale),
    [t, locale],
  );

  // RunSummaryRunInfo intentionally allows a missing status while a surface is
  // still loading the run. Keep the shared bar renderable during that gap.
  const displayStatus = getRunDisplayStatus(currentRunInfo.status || 'PENDING', currentRunInfo.metadata);
  // One switch for the whole bar so the chips, their icons and the separators
  // scale together instead of drifting apart.
  const isPanel = size === 'panel';
  const textCls = isPanel ? 'text-sm' : 'text-xs';
  const iconCls = isPanel ? 'w-3.5 h-3.5' : 'w-3 h-3';
  const arrowBtnCls = isPanel ? 'w-6 h-6' : 'w-5 h-5';
  const arrowIconCls = isPanel ? 'w-3.5 h-3.5' : 'w-3 h-3';

  const { scrollRef, hint, onScroll, nudge } = useHorizontalScrollHint<HTMLDivElement>();

  // Fade the edge the arrow sits on, so the chevron reads over the chips instead
  // of covering them opaquely. A mask (not a gradient overlay) because this bar
  // renders on two different backgrounds - the white canvas pill and the side
  // panel - and a coloured gradient would only match one of them.
  const fadeMask = (hint.left || hint.right)
    ? `linear-gradient(to right, ${hint.left ? 'transparent 0, black 24px' : 'black 0'}, ${hint.right ? 'black calc(100% - 24px), transparent 100%' : 'black 100%'})`
    : undefined;

  /** Vertical wheel over the strip scrolls it sideways - a trackpad-less user
   *  otherwise cannot move a strip whose scrollbar is hidden. */
  const handleWheel = useCallback((e: React.WheelEvent<HTMLDivElement>) => {
    const el = e.currentTarget;
    if (el.scrollWidth <= el.clientWidth) return;
    const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    if (!delta) return;
    el.scrollLeft += delta;
    e.stopPropagation();
  }, []);

  return (
    <>
      <div className={`flex items-center gap-2 px-3 sm:px-4 py-2 flex-shrink-0 min-w-0 overflow-hidden ${className}`}>
        {leading}

        {/* Chip track - scrolls sideways instead of dropping chips at breakpoints.
            `flex-auto` (basis auto), not `flex-1`: the canvas pill is width:fit-content,
            and a 0 flex-basis makes it under-report its max-content size there, so the
            pill would render narrower than the chips it holds even with room to spare. */}
        <div className="relative flex-auto min-w-0 flex items-center">
          <div
            ref={scrollRef}
            onScroll={onScroll}
            onWheel={handleWheel}
            data-run-summary-chips
            className="flex items-center gap-2 min-w-0 overflow-x-auto scrollbar-hide"
            style={fadeMask ? { maskImage: fadeMask, WebkitMaskImage: fadeMask } : undefined}
          >
            {/* Status badge. The testid is the stable handle for it: the shape is a
                styling detail (it used to be the only `div.rounded-full` on the
                canvas, which is what e2e keyed on), the identity is not. */}
            {showStatus && (
              <div
                data-testid="run-status-badge"
                className={`flex items-center gap-1 px-2 py-0.5 ${canvasChromeChipRadiusClass} ${textCls} font-medium whitespace-nowrap flex-shrink-0 ${getStatusClasses(displayStatus)}`}
              >
                {getRunStatusLabel(displayStatus, (k) => t(k))}
              </div>
            )}

            {/* No version to show, but the history still has to be reachable: the chip
                IS the canvas entry point, so a run whose version is unknown (older row,
                info not loaded yet) must not strand the user with no way in. Same slot,
                same click target, a History glyph instead of "vN". */}
            {currentRunInfo.planVersion == null && onVersionClick && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <button
                  type="button"
                  data-run-version-chip
                  onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
                  title={t('runs.title')}
                  className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap ${canvasChromeChipRadiusClass} px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
                >
                  <History className={iconCls} />
                </button>
              </span>
            )}

            {/* Version badge + pinned indicator. Clicking it jumps to the run history
                (every run of this workflow) in the side panel. */}
            {currentRunInfo.planVersion != null && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                {onVersionClick ? (
                  <button
                    type="button"
                    data-run-version-chip
                    onClick={(e) => { e.stopPropagation(); onVersionClick(); }}
                    title={t('runs.title')}
                    className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap ${canvasChromeChipRadiusClass} px-1 -mx-1 hover:bg-gray-100 dark:hover:bg-gray-700/60 transition-colors`}
                  >
                    v{currentRunInfo.planVersion}
                    {pinnedVersion != null && currentRunInfo.planVersion === pinnedVersion && (
                      <Pin className={`${iconCls} text-amber-500 dark:text-amber-400`} />
                    )}
                  </button>
                ) : (
                  <span className={`flex items-center gap-0.5 ${textCls} font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap`}>
                    v{currentRunInfo.planVersion}
                    {pinnedVersion != null && currentRunInfo.planVersion === pinnedVersion && (
                      <Pin className={`${iconCls} text-amber-500 dark:text-amber-400`} />
                    )}
                  </span>
                )}
              </span>
            )}

            {/* Epoch indicator - right after version.
                A bare number can only mean "epoch N": showing the epoch COUNT
                the same way made the default view read as "you are on epoch 3"
                while the panel next to it said "All epochs". The cumulative view
                says so in words, and keeps the count that tells you how many
                times the run fired. */}
            {epochCount > 0 && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span
                  data-run-epoch-chip
                  data-all-epochs={selectedEpoch == null || undefined}
                  className={`flex items-center gap-1 ${textCls} font-medium text-gray-600 dark:text-gray-300 ${selectedEpoch != null ? 'tabular-nums' : ''} whitespace-nowrap`}
                >
                  <Calendar className={iconCls} />
                  {selectedEpoch != null
                    ? selectedEpoch
                    : t('workflow.runSteps.allEpochsCount', { count: epochCount })}
                </span>
              </span>
            )}

            {/* Started at */}
            {currentRunInfo.startedAt && (
              <span className="flex items-center gap-0.5 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span
                  data-run-started-at={currentRunInfo.startedAt}
                  className={`${textCls} text-gray-500 dark:text-gray-400 whitespace-nowrap`}
                >
                  {formatRel(currentRunInfo.startedAt)}
                </span>
              </span>
            )}

            {/* Step by step badge */}
            {isStepByStep && (
              <span className="flex items-center gap-2 flex-shrink-0">
                <span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>
                <span className={`flex items-center gap-1.5 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 ${canvasChromeChipRadiusClass}`}>
                  <StepForward className={iconCls} />
                  <span className={`${textCls} font-medium whitespace-nowrap`}>{t('workflow.mode.stepByStep')}</span>
                </span>
              </span>
            )}
          </div>

          {/* Scroll arrows - only on the side that still hides a chip */}
          {hint.left && (
            <button
              type="button"
              data-run-summary-scroll="left"
              aria-label={t('workflow.runInfo.scrollLeft')}
              title={t('workflow.runInfo.scrollLeft')}
              onClick={(e) => { e.stopPropagation(); nudge(-1); }}
              className={`absolute left-0 top-1/2 -translate-y-1/2 flex items-center justify-center ${arrowBtnCls} ${canvasChromeChipRadiusClass} text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors`}
            >
              <ChevronLeft className={arrowIconCls} />
            </button>
          )}
          {hint.right && (
            <button
              type="button"
              data-run-summary-scroll="right"
              aria-label={t('workflow.runInfo.scrollRight')}
              title={t('workflow.runInfo.scrollRight')}
              onClick={(e) => { e.stopPropagation(); nudge(1); }}
              className={`absolute right-0 top-1/2 -translate-y-1/2 flex items-center justify-center ${arrowBtnCls} ${canvasChromeChipRadiusClass} text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors`}
            >
              <ChevronRight className={arrowIconCls} />
            </button>
          )}
        </div>

        {trailing}

        {/* Stop / Cancel / Reactivate button - FAR RIGHT, never scrolled away:
            the one destructive control of the bar must sit in a fixed place, and
            it must stay reachable when the chips overflow. The control itself is
            shared with every other surface that shows a live run, so they all
            offer the same affordance. */}
        <RunActionButton
          status={currentRunInfo.status}
          pinnedVersion={pinnedVersion}
          onStop={onStop}
          onCancel={onCancel}
          onReactivate={onReactivate}
          pendingAction={actionPending}
          failed={actionFailed}
          size={size}
          separator={<span className={`${textCls} text-gray-400 dark:text-gray-500`}>·</span>}
        />
      </div>

    </>
  );
}
