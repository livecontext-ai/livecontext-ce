'use client';

import { useTranslations } from 'next-intl';
import { Circle, CheckCircle2, XCircle, Clock, AlertCircle, Eye, Pause, Trash2 } from 'lucide-react';
import type { TaskStatus, TaskPriority } from '@/lib/api/orchestrator/task.types';

/**
 * Small shared task badges (status icon + priority pill), used by the detail
 * panel. Extracted from the removed TaskListView (the list view itself was
 * never rendered; only these two exports were consumed).
 */

export function StatusIcon({ status, className = '' }: { status: TaskStatus; className?: string }) {
  const size = `h-3.5 w-3.5 ${className}`;
  switch (status) {
    case 'pending':
      return <Pause className={`${size} text-gray-400`} />;
    case 'in_progress':
      return <Clock className={`${size} text-blue-500 animate-pulse`} />;
    case 'in_review':
      return <Eye className={`${size} text-orange-500`} />;
    case 'completed':
      return <CheckCircle2 className={`${size} text-green-500`} />;
    case 'failed':
      return <XCircle className={`${size} text-red-500`} />;
    case 'cancelled':
      return <AlertCircle className={`${size} text-gray-400`} />;
    case 'deleted':
      return <Trash2 className={`${size} text-red-400`} />;
    default:
      return <Circle className={`${size} text-gray-400`} />;
  }
}

export function PriorityBadge({ priority }: { priority: TaskPriority }) {
  const t = useTranslations('taskBoard');
  const colors: Record<TaskPriority, string> = {
    urgent: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    high: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
    normal: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400',
    low: 'bg-gray-50 text-gray-400 dark:bg-gray-800/50 dark:text-gray-500',
  };

  return (
    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${colors[priority] || colors.normal}`}>
      {t(`priority.${priority}`)}
    </span>
  );
}
