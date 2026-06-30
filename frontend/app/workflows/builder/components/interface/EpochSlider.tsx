'use client';

import * as React from 'react';
import clsx from 'clsx';
import { formatUtcDateTime, formatUtcTime, parseUtcAware } from '@/lib/utils/dateFormatters';

export interface EpochTimestampEntry {
  /** Backend epoch number for API calls */
  epoch?: number;
  startedAt: string | null;
  endedAt: string | null;
}

interface EpochSliderProps {
  /** Total number of pages (epochs) */
  totalPages: number;
  /** Current page index (0-based) */
  value: number;
  /** Called on selection (visual feedback) */
  onValueChange: (page: number) => void;
  /** Called on selection commit */
  onValueCommit: (page: number) => void;
  /** Page-index-aligned epoch timestamp entries */
  epochTimestamps?: EpochTimestampEntry[];
  /** Show "All" option at top (like RunInfo EpochSelector) */
  showAllOption?: boolean;
  /** Whether "All" is currently selected (viewingEpoch === null) */
  isAllSelected?: boolean;
  /** Called when user clicks "All" */
  onAllSelected?: () => void;
  className?: string;
}

/**
 * Format an ISO timestamp for display.
 */
export function formatTimestamp(isoString: string): string {
  try {
    const date = parseUtcAware(isoString);
    if (isNaN(date.getTime())) return '';
    const now = new Date();
    const sameDay =
      date.getUTCFullYear() === now.getUTCFullYear() &&
      date.getUTCMonth() === now.getUTCMonth() &&
      date.getUTCDate() === now.getUTCDate();
    if (sameDay) {
      return formatUtcTime(date, { withSeconds: true });
    }
    return formatUtcDateTime(date, { withSeconds: true });
  } catch {
    return '';
  }
}

/**
 * Format a duration in ms into a compact human-readable string.
 */
function formatCompactDuration(ms: number): string {
  if (ms < 1000) return '< 1s';
  const totalSec = Math.floor(ms / 1000);
  if (totalSec < 60) return `${totalSec}s`;
  const minutes = Math.floor(totalSec / 60);
  const seconds = totalSec % 60;
  if (minutes < 60) return `${minutes}m ${String(seconds).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h ${String(remainMin).padStart(2, '0')}m`;
}

export function EpochSlider({
  totalPages,
  value,
  onValueChange,
  onValueCommit,
  epochTimestamps,
  showAllOption,
  isAllSelected,
  onAllSelected,
  className,
}: EpochSliderProps) {
  const scrollRef = React.useRef<HTMLDivElement>(null);
  const [hoveredIdx, setHoveredIdx] = React.useState<number | null>(null);

  // Compute durations (ms) for each epoch
  const durations = React.useMemo(() => {
    if (!epochTimestamps) return [];
    return epochTimestamps.slice(0, totalPages).map((entry) => {
      if (!entry.startedAt) return 0;
      const start = parseUtcAware(entry.startedAt).getTime();
      if (isNaN(start)) return 0;
      const end = entry.endedAt ? parseUtcAware(entry.endedAt).getTime() : Date.now();
      return isNaN(end) ? 0 : Math.max(0, end - start);
    });
  }, [epochTimestamps, totalPages]);

  const maxDuration = React.useMemo(
    () => Math.max(...durations, 1),
    [durations],
  );

  const handleSelect = React.useCallback((index: number) => {
    onValueChange(index);
    onValueCommit(index);
  }, [onValueChange, onValueCommit]);

  // Auto-scroll to selected epoch on mount
  React.useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const selected = container.querySelector('[data-selected="true"]');
    if (selected) {
      selected.scrollIntoView({ block: 'nearest' });
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Determine which epoch to show in footer: hovered takes priority, then selected
  const footerIdx = hoveredIdx ?? value;
  const footerEntry = epochTimestamps?.[footerIdx] ?? null;
  const footerIsRunning = footerEntry?.startedAt != null && footerEntry?.endedAt == null;
  const footerEpochNumber = footerEntry?.epoch ?? (totalPages - footerIdx);
  const footerDuration = durations[footerIdx] ?? 0;

  const hasAnyTimestamps = epochTimestamps?.some(e => e.startedAt) ?? false;

  return (
    <div className={clsx('flex flex-col select-none', className)}>
      {/* Scrollable epoch list - reversed so newest is on top */}
      <div
        ref={scrollRef}
        className="flex flex-col overflow-y-auto max-h-[200px] scrollbar-thin"
      >
        {/* "All" option - shows all epochs in sequence */}
        {showAllOption && (
          <button
            type="button"
            onClick={() => onAllSelected?.()}
            className={clsx(
              'flex items-center gap-1.5 px-2 py-[5px] rounded-md transition-colors text-sm',
              'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-blue-500',
              isAllSelected
                ? 'bg-gray-100/80 dark:bg-white/[0.06]'
                : 'hover:bg-gray-50/80 dark:hover:bg-white/[0.03]',
            )}
          >
            <span className={clsx(
              'w-3 text-[10px] leading-none shrink-0',
              isAllSelected ? 'text-gray-900 dark:text-gray-100' : 'text-transparent',
            )}>
              ▸
            </span>
            <span className={clsx(
              'text-xs',
              isAllSelected
                ? 'font-bold text-gray-900 dark:text-gray-100'
                : 'font-medium text-gray-500 dark:text-gray-400',
            )}>
              All
            </span>
          </button>
        )}
        {Array.from({ length: totalPages }, (_, i) => {
          // Display newest epoch first (index totalPages-1 at top)
          const idx = totalPages - 1 - i;
          const entry = epochTimestamps?.[idx];
          const epochNum = entry?.epoch ?? (totalPages - idx);
          const isSelected = idx === value;
          const isRunning = entry?.startedAt != null && entry?.endedAt == null;
          const duration = durations[idx] ?? 0;
          const barPercent = maxDuration > 0 ? Math.max(5, (duration / maxDuration) * 100) : 5;
          const durationLabel = entry?.startedAt ? formatCompactDuration(duration) : '';

          return (
            <button
              key={idx}
              type="button"
              data-selected={isSelected}
              onClick={() => handleSelect(idx)}
              onMouseEnter={() => setHoveredIdx(idx)}
              onMouseLeave={() => setHoveredIdx(null)}
              className={clsx(
                'flex items-center gap-1.5 px-2 py-[5px] rounded-md transition-colors text-sm',
                'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-blue-500',
                isSelected
                  ? 'bg-gray-100/80 dark:bg-white/[0.06]'
                  : 'hover:bg-gray-50/80 dark:hover:bg-white/[0.03]',
              )}
            >
              {/* Selection arrow */}
              <span className={clsx(
                'w-3 text-[10px] leading-none shrink-0',
                isSelected ? 'text-gray-900 dark:text-gray-100' : 'text-transparent',
              )}>
                ▸
              </span>

              {/* Epoch number */}
              <span className={clsx(
                'w-5 text-right tabular-nums shrink-0 text-xs',
                isSelected
                  ? 'font-bold text-gray-900 dark:text-gray-100'
                  : 'font-medium text-gray-500 dark:text-gray-400',
              )}>
                {epochNum}
              </span>

              {/* Duration bar */}
              <div className="flex-1 h-[6px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden">
                <div
                  className={clsx(
                    'h-full rounded-full transition-all',
                    isRunning
                      ? 'bg-blue-500 animate-pulse'
                      : isSelected
                        ? 'bg-emerald-500'
                        : 'bg-emerald-500/70',
                  )}
                  style={{ width: `${barPercent}%` }}
                />
              </div>

              {/* Running ping dot */}
              {isRunning && (
                <span className="relative flex h-1.5 w-1.5 shrink-0">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                  <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                </span>
              )}

              {/* Duration label */}
              <span className={clsx(
                'min-w-[42px] text-right tabular-nums text-xs shrink-0',
                isRunning
                  ? 'text-blue-500 dark:text-blue-400'
                  : 'text-gray-500 dark:text-gray-400',
              )}>
                {durationLabel}
              </span>
            </button>
          );
        })}
      </div>

      {/* Detail footer */}
      {hasAnyTimestamps && footerEntry?.startedAt && (
        <>
          <div className="border-t border-slate-200 dark:border-slate-700 my-1.5" />
          <div className="px-2 pb-0.5 space-y-0.5">
            {/* Line 1: Epoch N + status badge */}
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-gray-900 dark:text-gray-100">
                Epoch {footerEpochNumber}
              </span>
              {footerIsRunning ? (
                <span className="inline-flex items-center gap-1 text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400">
                  <span className="relative flex h-1 w-1">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-500 opacity-75" />
                    <span className="relative inline-flex rounded-full h-1 w-1 bg-blue-500" />
                  </span>
                  Running
                </span>
              ) : (
                <span className="text-[10px] font-medium px-1.5 py-0.5 rounded-full bg-emerald-100 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400">
                  Completed
                </span>
              )}
            </div>
            {/* Line 2: start → end + duration */}
            <div className="flex items-center gap-1 text-xs text-gray-500 dark:text-gray-400 tabular-nums">
              <span>{formatTimestamp(footerEntry.startedAt)}</span>
              <span>→</span>
              <span className={clsx(footerIsRunning && 'text-blue-500 dark:text-blue-400 animate-pulse')}>
                {footerIsRunning ? 'In progress...' : footerEntry.endedAt ? formatTimestamp(footerEntry.endedAt) : ''}
              </span>
              {footerDuration > 0 && (
                <>
                  <span className="text-gray-300 dark:text-gray-600">·</span>
                  <span className="font-medium text-gray-700 dark:text-gray-300">
                    {formatCompactDuration(footerDuration)}
                  </span>
                </>
              )}
            </div>

          </div>
        </>
      )}
    </div>
  );
}
