'use client';

import { memo, useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { ChevronLeft, ChevronRight, CircleX, Layers } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { formatUtcTime } from '@/lib/utils/dateFormatters';
import { getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { EpochDetailsCard } from './EpochDetailsCard';
import {
  epochDisplayDurationMs,
  epochTone,
  formatCompactDuration,
  isEpochLive,
  resolveEpochBadgeStatus,
  type EpochTimestamp,
  type EpochTone,
} from './runFormatting';

/**
 * Above this many epochs the timeline groups consecutive epochs into one bar, so a
 * run fired thousands of times still draws a bounded number of elements. Picking
 * stays per epoch: within a group, the pointer position picks the epoch.
 */
export const EPOCH_NAV_MAX_BARS = 120;

/** From this many epochs the canvas pill opens with the navigator unfolded. */
export const EPOCH_NAV_AUTO_OPEN_FROM = 5;

/** Beyond this many bars the 1 px gaps alone would outgrow a narrow pill, so they go. */
export const EPOCH_NAV_GAPLESS_FROM = 60;

/** How long keyboard stepping waits before committing, so holding an arrow key
 *  does not load the canvas state of every epoch it passes through. */
export const EPOCH_NAV_KEY_COMMIT_MS = 200;

/** PageUp / PageDown move by a tenth of the run (at least one epoch). */
const pageStep = (count: number) => Math.max(1, Math.round(count / 10));

const TONE_CLASS: Record<EpochTone, string> = {
  failed: 'bg-red-500',
  running: 'bg-blue-500 animate-pulse',
  stopped: 'bg-gray-400',
  ok: 'bg-emerald-500',
  none: 'bg-gray-300 dark:bg-gray-600',
};

/** A bar that stands for several epochs shows the worst of them. */
const TONE_RANK: Record<EpochTone, number> = { failed: 4, running: 3, stopped: 2, ok: 1, none: 0 };

interface EpochPoint {
  epoch: number;
  startedAt: string;
  endedAt: string | null;
  status: string | null;
  tone: EpochTone;
  durationMs: number | null;
}

interface Bar {
  /** Index of the first and last epoch (in `points`) this bar stands for. */
  from: number;
  to: number;
  tone: EpochTone;
  heightPct: number;
}

/** One bar per epoch, or per group of epochs once there are more than `maxBars`. */
export function buildEpochBars(points: EpochPoint[], maxBars: number = EPOCH_NAV_MAX_BARS): Bar[] {
  if (points.length === 0) return [];
  const size = Math.ceil(points.length / Math.max(1, maxBars));
  const maxDuration = points.reduce((m, p) => Math.max(m, p.durationMs ?? 0), 0);
  const bars: Bar[] = [];
  for (let from = 0; from < points.length; from += size) {
    const to = Math.min(points.length, from + size) - 1;
    let tone: EpochTone = 'none';
    let longest = 0;
    for (let i = from; i <= to; i++) {
      if (TONE_RANK[points[i].tone] > TONE_RANK[tone]) tone = points[i].tone;
      longest = Math.max(longest, points[i].durationMs ?? 0);
    }
    // A live epoch has no final duration: a fixed mid height, never a figure that
    // would force a per-second re-render to stay honest.
    const heightPct = tone === 'running' && longest === 0
      ? 40
      : maxDuration > 0 ? Math.max(14, Math.round((longest / maxDuration) * 100)) : 14;
    bars.push({ from, to, tone, heightPct });
  }
  return bars;
}

interface RunEpochNavigatorProps {
  /** Referenced by the chip's aria-controls. */
  id?: string;
  epochTimestamps: EpochTimestamp[];
  /** Epoch on screen, null for "All epochs". */
  selectedEpoch: number | null;
  runStatus?: string | null;
  onSelectEpoch: (epoch: number | null) => void;
  /** Escape from anywhere in the navigator folds it back. */
  onClose?: () => void;
}

/**
 * The unfolded part of the canvas run pill: step through the epochs, jump to the
 * next failure, and scan the whole run on a timeline (bar height = duration,
 * colour = outcome).
 *
 * Built to cost nothing while nobody touches it:
 * - it only exists while unfolded, and draws at most EPOCH_NAV_MAX_BARS plain divs;
 * - hovering or dragging only moves a local preview; the canvas epoch (which loads
 *   that epoch's node states) changes on release, and keyboard stepping commits
 *   once the key is let go;
 * - no timer: a live epoch is drawn at a fixed height and labelled as running.
 *
 * It never sets its own width (`w-0 min-w-full`): the pill keeps the width of its
 * identity row, and unfolding only adds height.
 */
export const RunEpochNavigator = memo(function RunEpochNavigator({
  id,
  epochTimestamps,
  selectedEpoch,
  runStatus,
  onSelectEpoch,
  onClose,
}: RunEpochNavigatorProps) {
  const t = useTranslations();

  const points = useMemo<EpochPoint[]>(() => {
    const now = Date.now();
    return [...epochTimestamps]
      .sort((a, b) => a.epoch - b.epoch)
      .map(entry => {
        const live = isEpochLive(entry, runStatus);
        const status = resolveEpochBadgeStatus(entry, runStatus);
        return {
          epoch: entry.epoch,
          startedAt: entry.startedAt,
          endedAt: entry.endedAt ?? null,
          status,
          tone: epochTone(status),
          // A live epoch's figure would go stale without a ticker; show none.
          durationMs: live ? null : epochDisplayDurationMs(entry, now, false),
        };
      });
  }, [epochTimestamps, runStatus]);

  const bars = useMemo(() => buildEpochBars(points), [points]);

  const indexOfEpoch = useCallback(
    (epoch: number | null) => (epoch == null ? -1 : points.findIndex(p => p.epoch === epoch)),
    [points],
  );

  /** Epoch under the pointer or reached with the keyboard, not committed yet. */
  const [preview, setPreview] = useState<number | null>(null);
  /** The epoch card is up: pointer over the timeline, or a key step. Outlives the commit that clears
   *  the preview, so a key step or a click does not close the card it just opened. */
  const [cardOpen, setCardOpen] = useState(false);
  const draggingRef = useRef(false);
  const keyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (keyTimerRef.current) clearTimeout(keyTimerRef.current);
  }, []);

  const commit = useCallback((epoch: number | null) => {
    if (keyTimerRef.current) {
      clearTimeout(keyTimerRef.current);
      keyTimerRef.current = null;
    }
    setPreview(null);
    if (epoch !== selectedEpoch) onSelectEpoch(epoch);
  }, [onSelectEpoch, selectedEpoch]);

  const selectedIndex = indexOfEpoch(selectedEpoch);
  const last = points.length - 1;

  const goPrevious = useCallback(() => {
    if (points.length === 0) return;
    // From "All epochs", stepping back lands on the latest epoch.
    commit(points[selectedIndex < 0 ? last : Math.max(0, selectedIndex - 1)].epoch);
  }, [commit, points, selectedIndex, last]);

  const goNext = useCallback(() => {
    if (selectedIndex < 0 || selectedIndex >= last) return;
    commit(points[selectedIndex + 1].epoch);
  }, [commit, points, selectedIndex, last]);

  const failedIndexes = useMemo(
    () => points.reduce<number[]>((acc, p, i) => (p.tone === 'failed' ? (acc.push(i), acc) : acc), []),
    [points],
  );

  const goNextFailure = useCallback(() => {
    if (failedIndexes.length === 0) return;
    const after = failedIndexes.find(i => i > selectedIndex);
    commit(points[after ?? failedIndexes[0]].epoch);
  }, [commit, failedIndexes, points, selectedIndex]);

  /**
   * The epoch under the pointer. It first finds the BAR under it (bars share the width
   * equally, and a group can hold fewer epochs than the others), then the epoch within
   * that bar, so what is picked is always inside the bar that lights up.
   */
  const epochAtPointer = useCallback((e: PointerEvent<HTMLDivElement>): number | null => {
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0 || bars.length === 0) return null;
    const position = Math.min(0.9999, Math.max(0, (e.clientX - rect.left) / rect.width)) * bars.length;
    const bar = bars[Math.floor(position)];
    const within = Math.floor((position - Math.floor(position)) * (bar.to - bar.from + 1));
    return points[bar.from + within].epoch;
  }, [bars, points]);

  const onPointerDown = useCallback((e: PointerEvent<HTMLDivElement>) => {
    // Primary button only: a right click opens the context menu, it does not pick.
    if (e.button !== 0) return;
    draggingRef.current = true;
    e.currentTarget.setPointerCapture?.(e.pointerId);
    setCardOpen(true);
    setPreview(epochAtPointer(e));
  }, [epochAtPointer]);

  const onPointerMove = useCallback((e: PointerEvent<HTMLDivElement>) => {
    const epoch = epochAtPointer(e);
    // Same epoch under the pointer: no state update, no render.
    setCardOpen(true);
    setPreview(prev => (prev === epoch ? prev : epoch));
  }, [epochAtPointer]);

  const onPointerUp = useCallback((e: PointerEvent<HTMLDivElement>) => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    commit(epochAtPointer(e));
  }, [commit, epochAtPointer]);

  const onPointerLeave = useCallback(() => {
    if (draggingRef.current) return;
    setPreview(null);
    setCardOpen(false);
  }, []);

  const onPointerCancel = useCallback(() => {
    draggingRef.current = false;
    setPreview(null);
    setCardOpen(false);
  }, []);

  /** Escape from any control of the navigator folds it (a pending key step is dropped). */
  const onRootKeyDown = useCallback((e: KeyboardEvent<HTMLDivElement>) => {
    // The pill around this is a button: no key pressed in here may reach it.
    e.stopPropagation();
    if (e.key !== 'Escape') return;
    e.preventDefault();
    if (keyTimerRef.current) {
      clearTimeout(keyTimerRef.current);
      keyTimerRef.current = null;
    }
    setPreview(null);
    setCardOpen(false);
    onClose?.();
  }, [onClose]);

  const onSliderKeyDown = useCallback((e: KeyboardEvent<HTMLDivElement>) => {
    if (points.length === 0) return;
    const from = indexOfEpoch(preview ?? selectedEpoch);
    const page = pageStep(points.length);
    let target: number | null = null;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') target = from < 0 ? last : Math.max(0, from - 1);
    else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') target = from < 0 ? last : Math.min(last, from + 1);
    else if (e.key === 'PageDown') target = from < 0 ? last : Math.max(0, from - page);
    else if (e.key === 'PageUp') target = from < 0 ? last : Math.min(last, from + page);
    else if (e.key === 'Home') target = 0;
    else if (e.key === 'End') target = last;
    if (target == null) return;
    e.preventDefault();
    const epoch = points[target].epoch;
    setPreview(epoch);
    setCardOpen(true);
    if (keyTimerRef.current) clearTimeout(keyTimerRef.current);
    keyTimerRef.current = setTimeout(() => commit(epoch), EPOCH_NAV_KEY_COMMIT_MS);
  }, [commit, indexOfEpoch, last, points, preview, selectedEpoch]);

  const shownEpoch = preview ?? selectedEpoch;
  const shown = shownEpoch == null ? null : points[indexOfEpoch(shownEpoch)] ?? null;
  const highlightIndex = indexOfEpoch(shownEpoch);
  const dimOthers = highlightIndex >= 0;
  /** The bar the previewed epoch sits in: the hover card hangs under it. */
  const previewBarIndex = !cardOpen || highlightIndex < 0
    ? -1
    : bars.findIndex(bar => highlightIndex >= bar.from && highlightIndex <= bar.to);

  /** Counts per outcome; an epoch that carries no outcome yet is in none of them. */
  const summary = useMemo(() => {
    const counts: Record<EpochTone, number> = { ok: 0, failed: 0, running: 0, stopped: 0, none: 0 };
    for (const p of points) counts[p.tone]++;
    return { ok: counts.ok, failed: counts.failed, running: counts.running, stopped: counts.stopped };
  }, [points]);

  const detail = shown
    ? [
        shown.status ? getRunStatusLabel(shown.status, (k) => t(k)) : null,
        shown.startedAt ? formatUtcTime(shown.startedAt, { withSeconds: true }) : null,
        shown.tone === 'running'
          ? t('workflow.runSteps.epochTooltip.stillRunning')
          : shown.durationMs != null ? formatCompactDuration(shown.durationMs) : null,
      ].filter(Boolean).join(' · ')
    : t('workflow.runInfo.epochNav.summary', summary);

  const navLabel = selectedEpoch == null
    ? t('workflow.runSteps.allEpochs')
    : t('workflow.runSteps.epochTooltip.epoch', { epoch: selectedEpoch });

  const iconButton = 'flex h-6 w-6 items-center justify-center rounded-md text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700/60 disabled:opacity-30 disabled:hover:bg-transparent';
  const pillButton = 'flex h-6 items-center gap-1 rounded-md border px-1.5 text-xs transition-colors';

  return (
    <div
      id={id}
      data-run-epoch-navigator
      // Sits under the pill's "open the Run panel" row: nothing done in here may
      // reach a handler above it.
      onClick={(e) => e.stopPropagation()}
      onKeyDown={onRootKeyDown}
      className="w-0 min-w-full cursor-default border-t border-[var(--border-color)] px-2.5 pb-2 pt-1.5"
    >
      <div className="flex items-center justify-between gap-1.5">
        <div className="flex min-w-0 items-center gap-0.5">
          <button
            type="button"
            data-epoch-nav="previous"
            onClick={goPrevious}
            disabled={points.length === 0 || selectedIndex === 0}
            aria-label={t('workflow.runInfo.epochNav.previous')}
            title={t('workflow.runInfo.epochNav.previous')}
            className={iconButton}
          >
            <ChevronLeft className="h-3 w-3" />
          </button>
          <span data-epoch-nav-current className="truncate px-0.5 text-xs font-semibold tabular-nums text-gray-900 dark:text-gray-100">
            {navLabel}
          </span>
          <button
            type="button"
            data-epoch-nav="next"
            onClick={goNext}
            disabled={selectedIndex < 0 || selectedIndex >= last}
            aria-label={t('workflow.runInfo.epochNav.next')}
            title={t('workflow.runInfo.epochNav.next')}
            className={iconButton}
          >
            <ChevronRight className="h-3 w-3" />
          </button>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {failedIndexes.length > 0 && (
            <button
              type="button"
              data-epoch-nav="next-failure"
              onClick={goNextFailure}
              aria-label={t('workflow.runInfo.epochNav.nextFailure')}
              title={t('workflow.runInfo.epochNav.nextFailure')}
              className={`${pillButton} border-[var(--border-color)] text-red-600 dark:text-red-400 hover:bg-[var(--bg-secondary)]`}
            >
              <CircleX className="h-3 w-3" />
            </button>
          )}
          <button
            type="button"
            data-epoch-nav="all"
            aria-pressed={selectedEpoch == null}
            onClick={() => commit(null)}
            aria-label={t('workflow.runSteps.allEpochs')}
            title={t('workflow.runSteps.allEpochs')}
            className={`${pillButton} ${selectedEpoch == null
              ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)] text-[var(--accent-foreground)]'
              : 'border-[var(--border-color)] text-gray-700 dark:text-gray-200 hover:bg-[var(--bg-secondary)]'}`}
          >
            <Layers className="h-3 w-3" />
          </button>
        </div>
      </div>

      <div
        role="slider"
        tabIndex={0}
        data-epoch-timeline
        aria-label={t('workflow.runInfo.epochNav.timeline')}
        aria-valuemin={points[0]?.epoch ?? 0}
        aria-valuemax={points[last]?.epoch ?? 0}
        // A slider always has a value: in the all-epochs view it rests on the latest
        // epoch (where the first step lands), and the text says "All epochs".
        aria-valuenow={shownEpoch ?? points[last]?.epoch ?? 0}
        aria-valuetext={shown ? `${t('workflow.runSteps.epochTooltip.epoch', { epoch: shown.epoch })}, ${detail}` : navLabel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerLeave={onPointerLeave}
        onPointerCancel={onPointerCancel}
        onKeyDown={onSliderKeyDown}
        onBlur={() => setCardOpen(false)}
        // Beyond ~60 bars the 1 px gaps alone would outgrow a narrow pill: drop them, so
        // the bars always share exactly the timeline's width (what the pointer maps onto).
        className={`relative mt-1.5 flex h-9 touch-none cursor-pointer items-end ${bars.length > EPOCH_NAV_GAPLESS_FROM ? 'gap-0' : 'gap-px'} rounded-sm border-b border-[var(--border-color)] px-px pt-0.5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--accent-primary)]`}
      >
        {bars.map((bar, i) => {
          const active = highlightIndex >= bar.from && highlightIndex <= bar.to;
          return (
            <div
              key={i}
              aria-hidden="true"
              data-epoch-bar={points[bar.from].epoch}
              data-active={active || undefined}
              className={`min-w-0 flex-1 rounded-t-[2px] ${TONE_CLASS[bar.tone]} ${
                active ? 'opacity-100 ring-1 ring-gray-900 dark:ring-gray-100' : dimOthers ? 'opacity-35' : 'opacity-80'
              }`}
              style={{ height: `${bar.heightPct}%` }}
            />
          );
        })}
        {/* The same epoch card as the Run tab's epoch list, opened below the bar under the
            pointer (or reached with the keyboard). ONE tooltip anchored on an invisible marker
            that follows the previewed bar, not one per bar: a long run draws up to
            EPOCH_NAV_MAX_BARS of them. Its own provider: the canvas pill is outside the side
            panel's. */}
        {shown && previewBarIndex >= 0 && (
          <TooltipProvider>
            <Tooltip open>
              <TooltipTrigger asChild>
                <span
                  aria-hidden="true"
                  data-epoch-nav-card-anchor
                  className="pointer-events-none absolute bottom-0 h-0 w-0"
                  style={{ left: `${((previewBarIndex + 0.5) / bars.length) * 100}%` }}
                />
              </TooltipTrigger>
              {/* "always": the anchor moves by a style change, which nothing else would report. */}
              <TooltipContent side="bottom" sideOffset={8} align="center" updatePositionStrategy="always" className="px-3 py-2.5 min-w-[240px]">
                <EpochDetailsCard
                  epoch={shown.epoch}
                  status={shown.status}
                  startedAt={shown.startedAt}
                  endedAt={shown.endedAt}
                  durationMs={shown.durationMs}
                />
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        )}
      </div>

      <p data-epoch-nav-detail className="mt-1.5 flex items-start gap-1.5 text-xs leading-4 text-gray-600 dark:text-gray-300">
        {shown && preview != null && preview !== selectedEpoch && (
          <span className="font-semibold text-gray-900 dark:text-gray-100 tabular-nums">
            {t('workflow.runSteps.epochTooltip.epoch', { epoch: shown.epoch })}
          </span>
        )}
        <span className="tabular-nums">{detail}</span>
      </p>
    </div>
  );
});
