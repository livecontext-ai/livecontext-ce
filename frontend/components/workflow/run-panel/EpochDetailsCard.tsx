'use client';

import type { ReactNode } from 'react';
import { useTranslations } from 'next-intl';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { getRunStatusLabel, getStatusClasses } from '@/lib/utils/runStatusUtils';
import { EpochStatusIcon } from '@/components/workflow/EpochStatusIcon';
import { formatCompactDuration } from './runFormatting';

export interface EpochDetailsCardProps {
  epoch: number;
  /** The epoch's own outcome (`resolveEpochBadgeStatus`); null = the payload carries none. */
  status: string | null;
  startedAt: string | null | undefined;
  endedAt: string | null | undefined;
  /** null = no measured window: renders "-", never a zero. */
  durationMs: number | null;
  /** Extra rows under the duration (e.g. the epoch's cost on the analysis chart). */
  extraRows?: Array<{ key: string; label: string; value: ReactNode }>;
}

/**
 * The body of the epoch hover popup: number, outcome, start, end, duration. One component so
 * the epoch list, the canvas pill's timeline and the analysis chart all show an epoch the same way.
 * The caller supplies the frame (a TooltipContent, or the chart's own tooltip box).
 */
export function EpochDetailsCard({ epoch, status, startedAt, endedAt, durationMs, extraRows }: EpochDetailsCardProps) {
  const t = useTranslations();
  const isRunning = status === 'RUNNING';
  const statusLabel = status ? getRunStatusLabel(status, (k) => t(k)) : null;
  const liveValue = isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-900 dark:text-gray-100';

  return (
    <div className="flex flex-col gap-2 text-xs" data-epoch-details={epoch}>
      {/* Header: epoch number + status */}
      <div className="flex items-center justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
        <span className="font-semibold text-gray-900 dark:text-gray-100 tabular-nums">
          {t('workflow.runSteps.epochTooltip.epoch', { epoch })}
        </span>
        {/* The epoch's OWN outcome. An end timestamp says the epoch was closed, not that it
            worked: reading "completed" off it made a failed epoch announce success. */}
        <span className="inline-flex items-center gap-1.5">
          <EpochStatusIcon status={status} size="sm" />
          {/* No status = the payload carries none. Render a dash, never a word: "Pending"
              would be a confident claim about an epoch that has long since finished. */}
          <span className={`font-medium px-1.5 py-0.5 rounded ${
            status ? getStatusClasses(status) : 'text-gray-500 dark:text-gray-400'
          }`}>
            {statusLabel ?? '-'}
          </span>
        </span>
      </div>

      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">{t('workflow.runSteps.epochTooltip.started')}</span>
        <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums" data-epoch-details-started>
          {startedAt ? formatUtcDateTime(startedAt, { withSeconds: true }) : '-'}
        </span>
      </div>

      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">{t('workflow.runSteps.epochTooltip.ended')}</span>
        <span className={`font-medium tabular-nums ${liveValue}`} data-epoch-details-ended>
          {endedAt
            ? formatUtcDateTime(endedAt, { withSeconds: true })
            : isRunning
              ? t('workflow.runSteps.epochTooltip.stillRunning')
              : '-'}
        </span>
      </div>

      <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5">
        <span className="text-gray-500 dark:text-gray-400">{t('workflow.runSteps.epochTooltip.duration')}</span>
        <span className={`font-medium tabular-nums ${liveValue}`}>
          {durationMs != null ? formatCompactDuration(durationMs) : '-'}
        </span>
      </div>

      {extraRows?.map(row => (
        <div key={row.key} className="flex items-center justify-between gap-3">
          <span className="text-gray-500 dark:text-gray-400">{row.label}</span>
          <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">{row.value}</span>
        </div>
      ))}
    </div>
  );
}
