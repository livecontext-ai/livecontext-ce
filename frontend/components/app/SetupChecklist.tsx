'use client';

import * as React from 'react';
import Link from 'next/link';
import { useLocale, useTranslations } from 'next-intl';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ChevronDown, ChevronUp, Circle, ListChecks } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { refreshSetupChecklist, useSetupChecklist } from '@/hooks/useSetupChecklist';
import type { SetupProgress, SetupTaskId } from '@/lib/onboarding/setupChecklist';
import { track } from '@/lib/analytics/analytics';

/** Per-viewer convenience only: whether the sidebar card is folded. Losing it just unfolds it. */
const COLLAPSED_KEY = 'lc.setupChecklist.collapsed';

function readCollapsed(): boolean {
  try {
    return typeof window !== 'undefined' && window.localStorage.getItem(COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

function writeCollapsed(value: boolean) {
  try {
    window.localStorage.setItem(COLLAPSED_KEY, value ? '1' : '0');
  } catch {
    // Storage blocked: the card simply starts unfolded next time.
  }
}

interface TaskListProps {
  progress: SetupProgress;
  /** Show why each open task matters. The popover has room for it; the sidebar card does not. */
  withWhy: boolean;
  /** Receives the followed task, so the host can report which one led somewhere. */
  onNavigate: (task: SetupTaskId) => void;
}

/** The four tasks: done ones ticked (never a link), open ones linking to where they are done. */
function TaskList({ progress, withWhy, onNavigate }: TaskListProps) {
  const t = useTranslations('setupChecklist');
  const locale = useLocale();
  return (
    <ul className="space-y-1.5" data-testid="setup-checklist-tasks">
      {progress.tasks.map((task) => (
        <li key={task.id} className="flex items-start gap-2" data-testid={`setup-task-${task.id}`}
          data-done={task.done ? 'true' : 'false'}>
          {task.done
            ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-500" aria-hidden />
            : <Circle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[var(--text-secondary)]" aria-hidden />}
          <div className="min-w-0 flex-1">
            {task.done ? (
              <p className="text-sm text-[var(--text-secondary)] line-through">
                {t(`tasks.${task.id}.title`)}
                <span className="sr-only"> ({t('statusDone')})</span>
              </p>
            ) : (
              <>
                <Link
                  href={`/${locale}${task.href}`}
                  onClick={() => onNavigate(task.id)}
                  title={t(`tasks.${task.id}.why`)}
                  className="text-sm text-[var(--text-primary)] hover:text-[var(--accent-primary)] hover:underline"
                >
                  {t(`tasks.${task.id}.title`)}
                  <span className="sr-only"> ({t('statusTodo')})</span>
                </Link>
                {withWhy && <p className="text-sm text-[var(--text-secondary)]">{t(`tasks.${task.id}.why`)}</p>}
              </>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}

function ProgressBar({ progress }: { progress: SetupProgress }) {
  const percent = progress.total === 0 ? 0 : Math.round((progress.done / progress.total) * 100);
  return (
    <div className="h-1 w-full overflow-hidden rounded-full bg-[var(--bg-tertiary)]" aria-hidden>
      <div className="h-full rounded-full bg-emerald-500 transition-all" style={{ width: `${percent}%` }} />
    </div>
  );
}

interface SetupChecklistProps {
  /**
   * `panel`: the expanded sidebar, a card just above the user block that folds to one line.
   * `rail`: the collapsed sidebar, the "2/4" alone, opening the same list beside the rail.
   */
  variant: 'panel' | 'rail';
  /** Called when a task link is followed, so the host can close a mobile drawer. */
  onNavigate?: () => void;
}

/**
 * What a new account still has to do, always in sight at the bottom of the sidebar, above the user.
 *
 * <p>Renders nothing until every task's evidence has been read, and nothing once everything is
 * done. It cannot be hidden before that: four short tasks, each a link to where it is done. Unfolding or opening it re-reads every task, so what was
 * just done elsewhere is ticked by the time the list is read.
 */
export function SetupChecklist({ variant, onNavigate }: SetupChecklistProps) {
  const t = useTranslations('setupChecklist');
  const { progress, visible } = useSetupChecklist();
  const queryClient = useQueryClient();
  const [collapsed, setCollapsed] = React.useState(readCollapsed);
  const [open, setOpen] = React.useState(false);

  const refresh = React.useCallback(() => {
    void refreshSetupChecklist(queryClient);
  }, [queryClient]);

  const reportOpened = () => {
    track('setup_checklist_opened', { done: progress.done, total: progress.total, variant });
  };
  // Only open tasks are links, so task_done is false today; it is sent anyway so the event reads
  // the same if a done task ever becomes clickable.
  const reportTaskClicked = (taskId: SetupTaskId) => {
    const task = progress.tasks.find((candidate) => candidate.id === taskId);
    track('setup_checklist_task_clicked', {
      task: taskId,
      task_done: task?.done ?? false,
      done: progress.done,
      total: progress.total,
    });
  };

  if (!visible) {
    return null;
  }

  const count = t('pill', { done: progress.done, total: progress.total });
  const countAria = t('pillAria', { done: progress.done, total: progress.total });

  if (variant === 'rail') {
    return (
      <Popover open={open} onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          refresh();
          reportOpened();
        }
      }}>
        <PopoverTrigger asChild>
          <Button
            variant="ghost"
            size="sm"
            className="h-8 w-full flex-col gap-0 px-0 text-xs text-[var(--text-primary)]"
            // The visible "2/4" starts the accessible name (WCAG 2.5.3, label in name).
            aria-label={countAria}
            data-testid="setup-checklist-pill"
          >
            <ListChecks className="h-3.5 w-3.5" />
            <span>{count}</span>
          </Button>
        </PopoverTrigger>
        <PopoverContent
          side="right"
          align="end"
          className="w-72 p-3 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl"
        >
          <div className="space-y-3">
            <div>
              <p className="text-sm font-semibold text-[var(--text-primary)]">{t('title')}</p>
              <p className="text-sm text-[var(--text-secondary)]">
                {t('subtitle', { done: progress.done, total: progress.total })}
              </p>
            </div>
            <TaskList progress={progress} withWhy onNavigate={(task) => {
              reportTaskClicked(task);
              setOpen(false);
              onNavigate?.();
            }} />
          </div>
        </PopoverContent>
      </Popover>
    );
  }

  const toggle = () => {
    const next = !collapsed;
    setCollapsed(next);
    writeCollapsed(next);
    if (!next) {
      refresh();
      reportOpened();
    }
  };

  return (
    <section className="mx-3 mb-2 rounded-lg border border-theme p-2" data-testid="setup-checklist-card">
      <button
        type="button"
        onClick={toggle}
        aria-expanded={!collapsed}
        aria-controls="setup-checklist-body"
        className="flex w-full items-center gap-2 text-left"
        data-testid="setup-checklist-toggle"
      >
        <ListChecks className="h-3.5 w-3.5 shrink-0 text-[var(--text-secondary)]" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-[var(--text-primary)]">{t('title')}</span>
        <span className="text-xs text-[var(--text-secondary)]" aria-hidden>{count}</span>
        <span className="sr-only">{countAria}</span>
        {/* Accordion convention: points down while folded (it will open), up while open (it will close). */}
        {collapsed
          ? <ChevronDown className="h-3.5 w-3.5 shrink-0 text-[var(--text-secondary)]" aria-hidden />
          : <ChevronUp className="h-3.5 w-3.5 shrink-0 text-[var(--text-secondary)]" aria-hidden />}
      </button>
      <div className="mt-1.5">
        <ProgressBar progress={progress} />
      </div>
      <div id="setup-checklist-body" className="mt-2 space-y-1" hidden={collapsed}>
        <TaskList progress={progress} withWhy={false} onNavigate={(task) => {
          reportTaskClicked(task);
          onNavigate?.();
        }} />
      </div>
    </section>
  );
}
