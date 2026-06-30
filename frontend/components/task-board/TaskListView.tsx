'use client';

import { useTranslations } from 'next-intl';
import { Circle, CheckCircle2, XCircle, Clock, AlertCircle, ChevronRight, Eye, Pause, Trash2 } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import type { Task, TaskStatus, TaskPriority } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

interface TaskListViewProps {
  tasks: Task[];
  agents: Agent[];
  loading: boolean;
  onSelectTask: (taskId: string) => void;
  selectedTaskId: string | null;
  emptyMessage: string;
}

export function TaskListView({ tasks, agents, loading, onSelectTask, selectedTaskId, emptyMessage }: TaskListViewProps) {
  const t = useTranslations('taskBoard');

  if (loading && tasks.length === 0) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner />
      </div>
    );
  }

  if (tasks.length === 0) {
    return (
      <div className="text-center py-12">
        <p className="text-sm text-theme-muted">{emptyMessage}</p>
        <p className="text-xs text-theme-muted mt-1">{t('empty.description')}</p>
      </div>
    );
  }

  const agentMap = new Map(agents.map(a => [a.id, a]));

  return (
    <div className="border border-theme rounded-lg overflow-hidden">
      {/* Header */}
      <div className="grid grid-cols-[auto_1fr_130px_130px_130px_80px_60px] gap-2 px-3 py-2 bg-surface-secondary text-xs font-medium text-theme-muted border-b border-theme">
        <div className="w-5" />
        <div>{t('columns.title')}</div>
        <div>{t('columns.assignedTo')}</div>
        <div>{t('actions.reviewer')}</div>
        <div>{t('columns.createdBy')}</div>
        <div>{t('columns.priority')}</div>
        <div>{t('columns.age')}</div>
      </div>

      {/* Rows */}
      {tasks.map((task) => (
        <button
          key={task.id}
          type="button"
          onClick={() => onSelectTask(task.id)}
          className={`w-full grid grid-cols-[auto_1fr_130px_130px_130px_80px_60px] gap-2 px-3 py-2.5 text-sm border-b border-theme last:border-b-0 hover:bg-surface-hover transition-colors text-left ${
            selectedTaskId === task.id ? 'bg-surface-hover' : ''
          }`}
        >
          {/* Status icon */}
          <div className="flex items-center w-5">
            <StatusIcon status={task.status} />
          </div>

          {/* Title + parent indicator */}
          <div className="flex items-center gap-1.5 min-w-0">
            {task.depth > 0 && (
              <span className="text-xs text-theme-muted flex-shrink-0">
                {'  '.repeat(task.depth)}
              </span>
            )}
            <span className="truncate text-theme-primary">{task.title}</span>
            {task.parentTaskId && (
              <ChevronRight className="h-3 w-3 text-theme-muted flex-shrink-0" />
            )}
          </div>

          {/* Assigned to */}
          <div className="text-theme-muted truncate">
            {task.assignedToAgentId
              ? agentMap.get(task.assignedToAgentId)?.name || 'Unknown'
              : <span className="text-xs italic">{t('filters.unassigned')}</span>
            }
          </div>

          {/* Reviewer */}
          <div className="text-theme-muted truncate">
            {task.reviewerAgentId
              ? agentMap.get(task.reviewerAgentId)?.name || 'Unknown'
              : <span className="text-xs text-theme-muted">-</span>
            }
          </div>

          {/* Created by */}
          <div className="text-theme-muted truncate">
            {task.createdByAgentId
              ? agentMap.get(task.createdByAgentId)?.name || 'Agent'
              : 'User'
            }
          </div>

          {/* Priority */}
          <div>
            <PriorityBadge priority={task.priority} />
          </div>

          {/* Age */}
          <div className="text-xs text-theme-muted">
            {formatAge(task.createdAt)}
          </div>
        </button>
      ))}
    </div>
  );
}

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

function formatAge(isoDate: string): string {
  const ms = Date.now() - parseUtcAware(isoDate).getTime();
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return 'now';
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}
