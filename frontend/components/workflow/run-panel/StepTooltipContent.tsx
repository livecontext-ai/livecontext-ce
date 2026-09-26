'use client';

import { useTranslations } from 'next-intl';
import { CheckCircle2, XCircle, Loader2, CircleSlash, PauseCircle } from 'lucide-react';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { deriveEffectiveStatus, formatCompactDuration, stepDisplayDurationMs, type StepEntry } from './runFormatting';

type StepTooltipStep = Pick<
  StepEntry,
  'alias' | 'status' | 'startTime' | 'endTime' | 'executionTimeMs' | 'totalExecutionTimeMs' | 'statusCounts'
>;

/**
 * Reusable hover-tooltip body for a step row (list AND waterfall views, canvas
 * bar AND side-panel Run tab). Surfaces precise UTC timestamps, duration
 * (cumulative-aware) and the per-status execution breakdown that the compact
 * row only shows as inline ✓/✗/⊘/⟳ glyphs.
 */
export function StepTooltipContent({ step, label, showCumulative }: { step: StepTooltipStep; label: string; showCumulative: boolean }) {
  const t = useTranslations();
  const effectiveStatus = deriveEffectiveStatus(step.status, step.statusCounts);
  const isRunning = effectiveStatus === 'running';
  const isAwaiting = effectiveStatus === 'awaiting_signal';
  // Same figure as the row's gauge, by construction: both read stepDisplayDurationMs.
  const durationMs = stepDisplayDurationMs(step, showCumulative);
  const useTotal = showCumulative && step.totalExecutionTimeMs != null && durationMs != null;
  // One epoch: the row shows elapsed time. When parallel items made the summed work longer than
  // that, say so, instead of leaving the user to wonder where the rest of the time went.
  const totalWorkMs = !showCumulative && durationMs != null && step.executionTimeMs != null
    && step.executionTimeMs > durationMs + 1000
    ? step.executionTimeMs
    : null;
  const hasAnyCount = !!step.statusCounts && (
    (step.statusCounts.completed ?? 0) +
    (step.statusCounts.failed ?? 0) +
    (step.statusCounts.skipped ?? 0) +
    (step.statusCounts.running ?? 0) +
    (step.statusCounts.awaitingSignal ?? 0)
  ) > 0;

  const statusLabel = (() => {
    switch (effectiveStatus) {
      case 'running': return t('workflow.runSteps.stepTooltip.running');
      case 'completed': return t('workflow.runSteps.stepTooltip.completed');
      case 'failed': return t('workflow.runSteps.stepTooltip.failed');
      case 'partial_success': return t('workflow.runSteps.stepTooltip.partialSuccess');
      case 'skipped': return t('workflow.runSteps.stepTooltip.skipped');
      case 'awaiting_signal': return t('workflow.runSteps.stepTooltip.awaitingSignal');
      case 'pending': return t('workflow.runSteps.stepTooltip.pending');
      default: return effectiveStatus;
    }
  })();
  const statusCls = (() => {
    switch (effectiveStatus) {
      case 'running': return 'text-blue-500 dark:text-blue-400';
      case 'completed': return 'text-emerald-600 dark:text-emerald-400';
      case 'failed': return 'text-red-500 dark:text-red-400';
      case 'partial_success': return 'text-amber-600 dark:text-amber-400';
      case 'skipped': return 'text-gray-500 dark:text-gray-400';
      case 'awaiting_signal': return 'text-amber-500 dark:text-amber-400';
      default: return 'text-gray-500 dark:text-gray-400';
    }
  })();

  return (
    <div className="flex flex-col gap-2 text-xs min-w-[260px] max-w-[320px]">
      {/* Header: label + status badge */}
      <div className="flex items-start justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
        <span className="font-semibold text-gray-900 dark:text-gray-100 break-words flex-1 min-w-0">
          {label}
        </span>
        <span className={`inline-flex items-center gap-1 font-medium shrink-0 ${statusCls}`}>
          {isRunning && (
            <span className="relative flex h-1.5 w-1.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
            </span>
          )}
          {isAwaiting && <PauseCircle className="w-3 h-3" />}
          {statusLabel}
        </span>
      </div>

      {/* Started */}
      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">
          {t('workflow.runSteps.stepTooltip.started')}
        </span>
        <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
          {step.startTime
            ? formatUtcDateTime(step.startTime, { withSeconds: true })
            : '-'}
        </span>
      </div>

      {/* Ended */}
      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">
          {t('workflow.runSteps.stepTooltip.ended')}
        </span>
        <span
          className={`font-medium tabular-nums ${
            isRunning || isAwaiting
              ? 'text-blue-500 dark:text-blue-400'
              : 'text-gray-900 dark:text-gray-100'
          }`}
        >
          {step.endTime
            ? formatUtcDateTime(step.endTime, { withSeconds: true })
            : isRunning || isAwaiting
              ? t('workflow.runSteps.stepTooltip.stillRunning')
              : '-'}
        </span>
      </div>

      {/* Duration */}
      <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5">
        <span className="text-gray-500 dark:text-gray-400 inline-flex items-baseline gap-1">
          {t('workflow.runSteps.stepTooltip.duration')}
          {useTotal && (
            <span className="text-[10px] text-gray-400 dark:text-gray-500">
              ({t('workflow.runSteps.stepTooltip.cumulative')})
            </span>
          )}
        </span>
        <span
          className={`font-medium tabular-nums ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-900 dark:text-gray-100'
          }`}
        >
          {durationMs != null ? formatCompactDuration(durationMs) : '-'}
        </span>
      </div>

      {totalWorkMs != null && (
        <div className="flex items-center justify-between gap-3" data-step-total-work>
          <span className="text-gray-500 dark:text-gray-400">
            {t('workflow.runSteps.stepTooltip.totalWork')}
          </span>
          <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
            {formatCompactDuration(totalWorkMs)}
          </span>
        </div>
      )}

      {/* Per-status execution breakdown */}
      {hasAnyCount && (
        <div className="flex flex-col gap-1 border-t border-gray-100 dark:border-gray-700 pt-1.5">
          <span className="text-[10px] uppercase tracking-wide text-gray-400 dark:text-gray-500 font-medium">
            {t('workflow.runSteps.stepTooltip.executions')}
          </span>
          <div className="grid grid-cols-1 gap-y-0.5">
            {(step.statusCounts!.completed ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.completed')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.completed}
                </span>
              </div>
            )}
            {(step.statusCounts!.failed ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-red-500 dark:text-red-400">
                  <XCircle className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.failed')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.failed}
                </span>
              </div>
            )}
            {(step.statusCounts!.skipped ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-gray-500 dark:text-gray-400">
                  <CircleSlash className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.skipped')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.skipped}
                </span>
              </div>
            )}
            {(step.statusCounts!.running ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-blue-500 dark:text-blue-400">
                  <Loader2 className="w-3 h-3 animate-spin" />
                  {t('workflow.runSteps.stepTooltip.running')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.running}
                </span>
              </div>
            )}
            {(step.statusCounts!.awaitingSignal ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-amber-500 dark:text-amber-400">
                  <PauseCircle className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.awaitingSignal')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.awaitingSignal}
                </span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
