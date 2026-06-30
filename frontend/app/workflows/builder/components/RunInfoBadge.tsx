'use client';

import * as React from 'react';
import { useTranslations, useLocale } from 'next-intl';
import { History, Square, StepForward } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { WorkflowRun } from '@/lib/api';
import { getRunDisplayStatus, getStatusClasses, formatDuration, getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { formatRelativeDateI18n } from '@/lib/utils/dateFormatters';

interface RunInfoBadgeProps {
  currentRunInfo: WorkflowRun | null;
  isRunsHistoryOpen: boolean;
  isStepByStep: boolean;
  onOpenRunsHistory?: () => void;
  onStop?: () => void;
}

export function RunInfoBadge({
  currentRunInfo,
  isRunsHistoryOpen,
  isStepByStep,
  onOpenRunsHistory,
  onStop,
}: RunInfoBadgeProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const tRoot = useTranslations();
  const tRuns = useTranslations('runs');
  const locale = useLocale();
  const durationText = currentRunInfo ? formatDuration(currentRunInfo) : null;
  const displayStatus = currentRunInfo
    ? getRunDisplayStatus(currentRunInfo.status, currentRunInfo.metadata)
    : '';

  return (
    <div className="absolute top-4 right-2 sm:right-4 z-10 flex items-center gap-2 sm:gap-3 max-w-[calc(100vw-24px)]">
      {/* Run info section - only show when data is loaded and history panel closed */}
      {currentRunInfo && !isRunsHistoryOpen && (
        <div className="flex items-center gap-2 px-2.5 sm:px-4 py-2 bg-white/95 dark:bg-gray-800/95 backdrop-blur rounded-full overflow-hidden">
          {/* Stop button - leftmost */}
          {onStop && (() => {
            const raw = currentRunInfo.status?.toUpperCase();
            return raw === 'RUNNING' || raw === 'PAUSED' || raw === 'WAITING_TRIGGER';
          })() && (
            <button
              onClick={onStop}
              className="flex items-center justify-center w-5 h-5 rounded-full bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors"
              title={t('stopWorkflow')}
            >
              <Square className="w-2.5 h-2.5" />
            </button>
          )}

          {/* Separator between stop and status */}
          {onStop && (() => {
            const raw = currentRunInfo.status?.toUpperCase();
            return raw === 'RUNNING' || raw === 'PAUSED' || raw === 'WAITING_TRIGGER';
          })() && (
            <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
          )}

          {/* Status badge */}
          <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${getStatusClasses(displayStatus)}`}>
            {getRunStatusLabel(displayStatus, (k) => tRoot(k))}
          </div>

          {/* Started at */}
          {currentRunInfo.startedAt && (
            <>
              <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
              <span className="text-xs text-gray-600 dark:text-gray-400">
                {formatRelativeDateI18n(currentRunInfo.startedAt, tRuns, locale)}
              </span>
            </>
          )}

          {/* Total nodes - hidden on small screens */}
          {currentRunInfo.totalNodes !== undefined && (
            <>
              <span className="hidden sm:inline text-xs text-gray-400 dark:text-gray-500">·</span>
              <span className="hidden sm:inline text-xs text-gray-600 dark:text-gray-400">
                {t('nodeCount', { count: currentRunInfo.totalNodes })}
              </span>
            </>
          )}

          {/* Duration - hidden on small screens */}
          {durationText && (
            <>
              <span className="hidden sm:inline text-xs text-gray-400 dark:text-gray-500">·</span>
              <span className="hidden sm:inline text-xs text-gray-600 dark:text-gray-400">
                {durationText}
              </span>
            </>
          )}

          {/* Step by step badge */}
          {isStepByStep && (
            <>
              <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
              <div className="flex items-center gap-1.5 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 rounded-full">
                <StepForward className="w-3 h-3" />
                <span className="text-xs font-medium whitespace-nowrap">{t('stepByStep')}</span>
              </div>
            </>
          )}

        </div>
      )}

      {/* History button - always visible in run mode */}
      {onOpenRunsHistory && !isRunsHistoryOpen && (
        <Button
          onClick={onOpenRunsHistory}
          className="w-11 h-11 rounded-full p-0 shadow-none"
          title={t('runsHistory')}
        >
          <History className="w-[22px] h-[22px]" />
        </Button>
      )}
    </div>
  );
}
