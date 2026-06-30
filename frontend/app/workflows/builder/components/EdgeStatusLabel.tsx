'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Check, X, PlayCircle, MinusCircle } from 'lucide-react';
import type { DerivedNodeStatus, StatusCounts } from '../types';

interface EdgeStatusLabelProps {
  status: DerivedNodeStatus;
  statusCounts?: StatusCounts;
  isSkipped?: boolean;
  strokeColor: string;
}

/**
 * Optimized edge status label component
 * Displays status text and statusCounts badges
 */
export const EdgeStatusLabel = React.memo(function EdgeStatusLabel({
  status,
  statusCounts,
  isSkipped,
  strokeColor,
}: EdgeStatusLabelProps) {
  const t = useTranslations('nodeStatus');

  // Compute label text from status
  const labelText = React.useMemo(() => {
    if (!status || status === 'pending' || status === 'ready') return '';
    return t(status);
  }, [status, t]);

  // Extract counts from statusCounts (canonical uppercase keys only)
  const counts = React.useMemo(() => {
    if (!statusCounts) return null;
    return {
      completed: (statusCounts.COMPLETED ?? 0) + (statusCounts.SUCCESS ?? 0),
      failed: (statusCounts.FAILED ?? 0) + (statusCounts.ERROR ?? 0),
      running: (statusCounts.RUNNING ?? 0) + (statusCounts.RETRYING ?? 0),
      skipped: statusCounts.SKIPPED ?? 0,
    };
  }, [statusCounts]);

  // Background color based on status
  const bgColor = React.useMemo(() => {
    switch (status) {
      case 'running':
        return '#3b82f6'; // blue-500
      case 'completed':
        return '#10b981'; // emerald-500
      case 'failed':
        return '#ef4444'; // red-500
      case 'skipped':
        return '#94a3b8'; // slate-400
      case 'partial_success':
        return '#f59e0b'; // amber-500
      default:
        return strokeColor;
    }
  }, [status, strokeColor]);

  // Build title from status and counts (must be before early return for hooks rules)
  const title = React.useMemo(() => {
    const parts: string[] = [];
    if (labelText) parts.push(labelText);
    if (counts) {
      if (counts.completed > 0) parts.push(`${counts.completed} ${t('completed').toLowerCase()}`);
      if (counts.failed > 0) parts.push(`${counts.failed} ${t('failed').toLowerCase()}`);
      if (counts.running > 0) parts.push(`${counts.running} ${t('running').toLowerCase()}`);
      if (counts.skipped > 0) parts.push(`${counts.skipped} ${t('skipped').toLowerCase()}`);
    }
    return parts.join(' • ');
  }, [labelText, counts, t]);

  // Only show if we have counts or a valid status
  if (!counts && !status) return null;

  return (
    <div className="flex items-center gap-1 rounded-full bg-white dark:bg-gray-800 px-2 py-0.5" title={title}>
      {counts ? (
        <>
          {counts.completed > 0 && (
            <span className="flex items-center gap-0.5 text-green-600 dark:text-green-400" title={`${counts.completed} ${t('completed').toLowerCase()}`}>
              <Check className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.completed >= 100 ? '99+' : counts.completed}</span>
            </span>
          )}
          {counts.failed > 0 && (
            <span className="flex items-center gap-0.5 text-red-600 dark:text-red-400" title={`${counts.failed} ${t('failed').toLowerCase()}`}>
              <X className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.failed >= 100 ? '99+' : counts.failed}</span>
            </span>
          )}
          {counts.running > 0 && (
            <span className="flex items-center gap-0.5 text-blue-600 dark:text-blue-400" title={`${counts.running} ${t('running').toLowerCase()}`}>
              <PlayCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.running >= 100 ? '99+' : counts.running}</span>
            </span>
          )}
          {counts.skipped > 0 && (
            <span className="flex items-center gap-0.5 text-gray-500 dark:text-gray-400" title={`${counts.skipped} ${t('skipped').toLowerCase()}`}>
              <MinusCircle className="h-3 w-3" />
              <span className="text-[10px] font-medium">{counts.skipped >= 100 ? '99+' : counts.skipped}</span>
            </span>
          )}
        </>
      ) : (
        // Fallback: show status icon if no counts but status exists
        <>
          {status === 'running' && <PlayCircle className="h-3 w-3 text-blue-600 dark:text-blue-400" />}
          {status === 'completed' && <Check className="h-3 w-3 text-green-600 dark:text-green-400" />}
          {status === 'failed' && <X className="h-3 w-3 text-red-600 dark:text-red-400" />}
          {status === 'skipped' && <MinusCircle className="h-3 w-3 text-gray-500 dark:text-gray-400" />}
          {status === 'partial_success' && (
            <>
              <Check className="h-3 w-3 text-green-600 dark:text-green-400" />
              <X className="h-3 w-3 text-red-600 dark:text-red-400" />
            </>
          )}
        </>
      )}
    </div>
  );
});

