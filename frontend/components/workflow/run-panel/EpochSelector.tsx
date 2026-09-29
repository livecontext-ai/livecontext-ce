'use client';

import { memo, useEffect, useMemo, useState } from 'react';
import { List as VirtualList, useListRef, type RowComponentProps } from 'react-window';
import { Calendar } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { formatUtcTime, formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { getRunStatusLabel, getStatusClasses } from '@/lib/utils/runStatusUtils';
import { EpochStatusIcon } from '@/components/workflow/EpochStatusIcon';
import {
  formatCompactDuration,
  epochDisplayDurationMs,
  epochTone,
  isEpochLive,
  resolveEpochBadgeStatus,
  type EpochTimestamp,
} from './runFormatting';

interface EpochSelectorProps {
  epochTimestamps: EpochTimestamp[];
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  viewMode: 'list' | 'waterfall';
  /** Raw run status - decides whether an OPEN epoch is executing or merely unclosed. */
  runStatus?: string | null;
}

function formatTime(isoString: string): string {
  return formatUtcTime(isoString, { withSeconds: true });
}

// Row height kept fixed so the virtual list can virtualize without measuring.
// 40px = py-2.5 (10 + 10) + text-sm line-height (20). Matches the run-history row
// scale; the selected state's border is on the left edge and adds no height.
const EPOCH_ROW_HEIGHT = 40;
// Derived, not a literal: a hardcoded cap stops being a whole number of rows the
// moment the row height changes, and the list then ends mid-row - which reads as
// a rendering glitch rather than as "there is more below".
const EPOCH_LIST_VISIBLE_ROWS = 4;
const EPOCH_LIST_MAX_HEIGHT = EPOCH_ROW_HEIGHT * EPOCH_LIST_VISIBLE_ROWS;

interface EpochRowProps {
  entries: EpochTimestamp[];
  /** null = the payload carried no measured window; render nothing, never a zero. */
  durations: Array<number | null>;
  maxDuration: number;
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  viewMode: 'list' | 'waterfall';
  runStatus?: string | null;
}

// Row component defined at module scope so its identity is stable across renders
// (List re-renders rows when rowProps changes). All per-row data flows via
// `rowProps`, never closure capture, so re-renders only fire when rowProps changes.
function EpochRow({ index, style, entries, durations, maxDuration, selectedEpoch, onSelectEpoch, viewMode, runStatus }: RowComponentProps<EpochRowProps>) {
  const t = useTranslations();
  const entry = entries[index];
  const isSelected = selectedEpoch === entry.epoch;
  // What this epoch is DOING (drives the blue live styling), vs what it ACHIEVED
  // (the badge). An unclosed epoch is not necessarily running: a stopped or cancelled
  // run abandons it, and it would otherwise keep a blue pulse for good.
  const badgeStatus = resolveEpochBadgeStatus(entry, runStatus);
  const isRunning = badgeStatus === 'RUNNING';
  const statusLabel = badgeStatus ? getRunStatusLabel(badgeStatus, (k) => t(k)) : null;
  const duration = durations[index];
  const barPct = maxDuration > 0 ? Math.max(5, ((duration ?? 0) / maxDuration) * 100) : 5;

  const epochButton = (
    <button
      type="button"
      style={style}
      data-epoch-option={entry.epoch}
      data-selected={isSelected || undefined}
      aria-current={isSelected || undefined}
      onClick={(e) => { e.stopPropagation(); onSelectEpoch(entry.epoch); }}
      className={`w-full flex items-center gap-1.5 px-3 py-2.5 text-sm transition-colors ${
        isSelected
          ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
          : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
      }`}
    >
      <div className={`w-6 shrink-0 text-center tabular-nums ${
        isSelected ? 'font-bold text-gray-900 dark:text-gray-100' : 'font-medium text-gray-500 dark:text-gray-400'
      }`}>
        {entry.epoch}
      </div>
      {/* Per-epoch status badge (green check / red cross / live pulse). The slot is
          always reserved (same width + gap whatever the status, none included) so the
          gauge / "HH:mm → HH:mm" / duration to the right never shift horizontally as
          an epoch transitions from running to its outcome. The status WORD is in the
          row tooltip - a text pill would not fit the 240px-wide side panel. */}
      <EpochStatusIcon status={badgeStatus} size="sm" />
      {/* The badge is a colour + a shape; the WORD has to reach a screen reader too,
          and the tooltip is hover-only. */}
      {statusLabel && <span className="sr-only" data-epoch-status={badgeStatus}>{statusLabel}</span>}

      {viewMode === 'waterfall' ? (
        <>
          <span className="w-[96px] min-w-[96px] shrink-0" />
          <div className="flex-1 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden min-w-0">
            {/* The gauge follows the epoch's outcome too: an all-emerald bar under a
                red badge reads as a contradiction, and the gauge view is the one
                where the badge is furthest from the eye. */}
            <div
              className={`h-full rounded-full transition-all ${
                isRunning ? 'bg-blue-500 animate-pulse'
                  : epochTone(badgeStatus) === 'failed' ? (isSelected ? 'bg-red-500' : 'bg-red-500/70')
                  : epochTone(badgeStatus) === 'stopped' ? (isSelected ? 'bg-gray-400' : 'bg-gray-400/70')
                  : isSelected ? 'bg-emerald-500' : 'bg-emerald-500/70'
              }`}
              style={{ width: `${barPct}%` }}
            />
          </div>
          <span className={`min-w-[40px] text-right text-xs tabular-nums shrink-0 ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {duration != null ? formatCompactDuration(duration) : ''}
          </span>
        </>
      ) : (
        <>
          {/* Three fixed-width slots (start | → | end) inside a flex-1 centered
              wrapper. The end-time slot keeps its width when it renders "..."
              so the live "HH:mm:ss → ..." → "HH:mm:ss → HH:mm:ss" transition
              never shifts the block, and the whole group stays visually
              centered within the row. */}
          <span className={`flex-1 inline-flex items-center justify-center gap-1 text-xs tabular-nums whitespace-nowrap ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {entry.startedAt ? (
              <>
                <span className="w-[88px] text-right">{formatTime(entry.startedAt)}</span>
                <span aria-hidden="true">→</span>
                <span className="w-[88px] text-left">{entry.endedAt ? formatTime(entry.endedAt) : '...'}</span>
              </>
            ) : (
              <span>-</span>
            )}
          </span>
          {/* Fixed width (48px) sized for the worst case "59m59s" / "99h59m"
              so the ticking duration on a running epoch doesn't expand the
              column and squeeze the flex-1 timestamps span to its left. */}
          <span className={`w-14 text-right text-xs tabular-nums font-medium shrink-0 ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {duration != null ? formatCompactDuration(duration) : ''}
          </span>
        </>
      )}
    </button>
  );

  return (
    <Tooltip delayDuration={150}>
      <TooltipTrigger asChild>{epochButton}</TooltipTrigger>
      <TooltipContent
        side="left"
        sideOffset={8}
        align="center"
        className="px-3 py-2.5 min-w-[240px]"
      >
        <div className="flex flex-col gap-2 text-xs">
          {/* Header: epoch number + status */}
          <div className="flex items-center justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
            <span className="font-semibold text-gray-900 dark:text-gray-100 tabular-nums">
              {t('workflow.runSteps.epochTooltip.epoch', { epoch: entry.epoch })}
            </span>
            {/* The epoch's OWN outcome. This used to read "completed" for any epoch
                carrying an end timestamp, so a failed epoch announced success - the
                end timestamp says the epoch was closed, not that it worked. */}
            <span className="inline-flex items-center gap-1.5">
              <EpochStatusIcon status={badgeStatus} size="sm" />
              {/* No status = the payload carries none (an epoch that ran nothing but its
                  trigger, or a showcase snapshot frozen before the field existed). Render
                  a dash, never a word: "Pending" would be a confident claim about an
                  epoch that has long since finished. */}
              <span className={`font-medium px-1.5 py-0.5 rounded ${
                badgeStatus ? getStatusClasses(badgeStatus) : 'text-gray-500 dark:text-gray-400'
              }`}>
                {statusLabel ?? '-'}
              </span>
            </span>
          </div>

          {/* Started */}
          <div className="flex items-center justify-between gap-3">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.started')}
            </span>
            <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
              {entry.startedAt
                ? formatUtcDateTime(entry.startedAt, { withSeconds: true })
                : '-'}
            </span>
          </div>

          {/* Ended */}
          <div className="flex items-center justify-between gap-3">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.ended')}
            </span>
            <span
              className={`font-medium tabular-nums ${
                isRunning
                  ? 'text-blue-500 dark:text-blue-400'
                  : 'text-gray-900 dark:text-gray-100'
              }`}
            >
              {entry.endedAt
                ? formatUtcDateTime(entry.endedAt, { withSeconds: true })
                : isRunning
                  ? t('workflow.runSteps.epochTooltip.stillRunning')
                  : '-'}
            </span>
          </div>

          {/* Duration */}
          <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.duration')}
            </span>
            <span
              className={`font-medium tabular-nums ${
                isRunning
                  ? 'text-blue-500 dark:text-blue-400'
                  : 'text-gray-900 dark:text-gray-100'
              }`}
            >
              {duration != null ? formatCompactDuration(duration) : '-'}
            </span>
          </div>
        </div>
      </TooltipContent>
    </Tooltip>
  );
}

export const EpochSelector = memo(function EpochSelector({ epochTimestamps, selectedEpoch, onSelectEpoch, viewMode, runStatus }: EpochSelectorProps) {
  const t = useTranslations();

  // Sort once per epochTimestamps reference change. Show all epochs regardless of
  // status filter - the filter already applies to the step list. Hiding epochs
  // based on active/ended was too aggressive: an active epoch can contain
  // completed nodes, and a finished epoch can be relevant when filtering by any status.
  const sorted = useMemo(
    () => [...epochTimestamps].sort((a, b) => b.epoch - a.epoch),
    [epochTimestamps]
  );

  // Conditional 1s ticker: only runs when at least one epoch is still in-flight.
  // Closed-only timelines pay zero wake-up cost, and the heavy WS-event re-render
  // cadence stops driving duration recomputation (the previous code re-derived
  // every duration on every parent render because `Date.now()` was read inline).
  // Keyed off the same "is it live" answer as the durations: an epoch left unclosed by
  // a run that has stopped is NOT live, so the ticker stops instead of re-rendering the
  // whole list every second behind a figure that no longer moves.
  const hasRunningEpoch = useMemo(
    () => sorted.some(e => isEpochLive(e, runStatus)),
    [sorted, runStatus]
  );
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!hasRunningEpoch) return;
    const id = setInterval(() => setTick(prev => prev + 1), 1000);
    return () => clearInterval(id);
  }, [hasRunningEpoch]);

  const durations = useMemo(() => {
    const now = Date.now();
    return sorted.map((entry) => epochDisplayDurationMs(entry, now, isEpochLive(entry, runStatus)));
    // `tick` is a deliberate dep: forces recompute every second when a running
    // epoch exists, so the bar / label tick forward without leaning on SSE cadence.
  }, [sorted, tick, runStatus]);

  const maxDuration = useMemo(
    () => durations.reduce((m, d) => ((d ?? 0) > m ? (d ?? 0) : m), 1),
    [durations]
  );

  // Stable rowProps reference - List re-renders rows when this object's identity
  // changes; memoizing means SSE pushes that don't actually alter epoch data are no-ops.
  const rowProps = useMemo<EpochRowProps>(() => ({
    entries: sorted,
    durations,
    maxDuration,
    selectedEpoch,
    onSelectEpoch,
    viewMode,
    runStatus,
  }), [sorted, durations, maxDuration, selectedEpoch, onSelectEpoch, viewMode, runStatus]);

  // Auto-scroll to the selected epoch when selection changes. Sorted newest-first,
  // so the index lines up with position in `sorted`.
  const listRef = useListRef(null);
  useEffect(() => {
    if (selectedEpoch == null) return;
    const idx = sorted.findIndex(e => e.epoch === selectedEpoch);
    if (idx >= 0) listRef.current?.scrollToRow({ index: idx, align: 'smart' });
  }, [selectedEpoch, sorted]);

  // Height shrinks to fit when there are fewer epochs than the cap - matches
  // the original `max-h-[120px]` visual behaviour for small lists.
  const listHeight = Math.min(sorted.length * EPOCH_ROW_HEIGHT, EPOCH_LIST_MAX_HEIGHT);

  return (
    <div className="pt-1.5 pb-1.5">
      {/* "All epochs" button */}
      <button
        type="button"
        data-epoch-option="all"
        data-selected={selectedEpoch === null || undefined}
        aria-current={selectedEpoch === null || undefined}
        onClick={(e) => { e.stopPropagation(); onSelectEpoch(null); }}
        className={`w-full flex items-center gap-1.5 px-3 py-2.5 text-sm transition-colors ${
          selectedEpoch === null
            ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
            : 'border-l-2 border-transparent text-gray-500 dark:text-gray-400 hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
        }`}
      >
        <div className="w-6 shrink-0 flex items-center justify-center">
          <Calendar className="w-4 h-4" />
        </div>
        <span className="w-[96px] min-w-[96px] shrink-0 truncate text-left">{t('workflow.runSteps.allEpochs')}</span>
      </button>

      {/* Virtualized epoch rows */}
      {sorted.length > 0 && (
        <div className="mt-1">
          <VirtualList
            listRef={listRef}
            rowCount={sorted.length}
            rowHeight={EPOCH_ROW_HEIGHT}
            rowComponent={EpochRow}
            rowProps={rowProps}
            overscanCount={5}
            style={{ height: listHeight }}
            className="scrollbar-thin"
          />
        </div>
      )}

      {/* Separator */}
      <div className="border-t border-slate-200 dark:border-slate-700 mt-2 mx-3" />
    </div>
  );
});
