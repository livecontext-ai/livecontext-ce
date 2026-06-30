'use client';

import React, { useState, useMemo, useCallback, useRef, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import {
  Plus, Search, Pause, Clock, CheckCircle2, XCircle, AlertCircle,
  ClipboardList, Columns3, Check, Info, Eye, ArrowUpDown,
  Trash2, RotateCcw, Ban, X, Settings2,
  Paperclip, ListChecks, Timer, CalendarClock, Lock, SlidersHorizontal,
} from 'lucide-react';
import { getClientLocale } from '@/lib/utils/locale';
import { Button } from '@/components/ui/button';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { AvatarDisplay } from '@/components/agents';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import { useTaskBoard, type TaskSortField } from './useTaskBoard';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { formatDueShort } from './taskBadgeFormat';
import { TaskDetailPanel } from './TaskDetailPanel';
import { CreateTaskDialog } from './CreateTaskDialog';
import { ColumnManagerDialog } from './ColumnManagerDialog';
import { taskService, type BulkTaskAction } from '@/lib/api/orchestrator/task.service';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { selectTaskActivityAgentIds } from './taskActivitySubscriptions';
import {
  useAgentActivitySubscriber,
  useAgentActivityStore,
} from '@/components/agent-fleet/hooks/useAgentActivityStream';

import type { Task, TaskStatus, TaskPerson, TaskLabel } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';

/** A resolved board column. Sourced from the server config (F4) or the defaults below. */
interface ColumnDef {
  key: string;
  color: string;
  icon: typeof Pause;
  /** WIP limit (F3); null = no limit. */
  wipLimit: number | null;
  /** Custom display label; null = a built-in key that uses the i18n status.<key>. */
  label: string | null;
}

/** The seven built-in status keys (rendered via i18n; custom keys use their stored label). */
const DEFAULT_STATUS_KEYS = new Set(['pending', 'in_progress', 'in_review', 'completed', 'failed', 'cancelled', 'deleted']);
const ICON_BY_CATEGORY: Record<string, typeof Pause> = {
  pending: Pause, in_progress: Clock, in_review: Eye, done: CheckCircle2,
  failed: XCircle, cancelled: AlertCircle, deleted: Trash2,
};
const COLOR_BY_CATEGORY: Record<string, string> = {
  pending: 'text-gray-400', in_progress: 'text-blue-500', in_review: 'text-orange-500',
  done: 'text-green-500', failed: 'text-red-500', cancelled: 'text-gray-400', deleted: 'text-red-400',
};

/** Fallback columns when the server config hasn't loaded (matches the historical board). */
const DEFAULT_COLUMNS: ColumnDef[] = [
  { key: 'pending',     color: 'text-gray-400',   icon: Pause,        wipLimit: null, label: null },
  { key: 'in_progress', color: 'text-blue-500',   icon: Clock,        wipLimit: null, label: null },
  { key: 'in_review',   color: 'text-orange-500', icon: Eye,          wipLimit: null, label: null },
  { key: 'completed',   color: 'text-green-500',  icon: CheckCircle2, wipLimit: null, label: null },
  { key: 'failed',      color: 'text-red-500',    icon: XCircle,      wipLimit: null, label: null },
  { key: 'cancelled',   color: 'text-gray-400',   icon: AlertCircle,  wipLimit: null, label: null },
  { key: 'deleted',     color: 'text-red-400',    icon: Trash2,       wipLimit: null, label: null },
];

/** Trash retention window - mirrors backend `agent.task.deleted-retention.days` (drives the "purges in N days" badge). */
const DELETED_RETENTION_DAYS = 30;

/** failed/cancelled/deleted ship hidden by default (matches the historical board). */
const DEFAULT_HIDDEN = new Set(['failed', 'cancelled', 'deleted']);
const STORAGE_KEY = 'taskboard-hidden-columns';
/** Stable empty set for columns with no active selection (avoids re-render churn). */
const EMPTY_IDS: ReadonlySet<string> = new Set();

/** Destructive verbs that move tasks into Cancelled/Deleted (or purge) - gated by a confirmation modal. Restore is non-destructive and skips it. */
type DestructiveAction = Exclude<BulkTaskAction, 'restore'>;

/** i18n keys for the confirmation modal, keyed by destructive verb. Confirm/dismiss labels reuse the bulk-bar keys. */
const CONFIRM_COPY: Record<DestructiveAction, { title: string; message: string; confirm: string }> = {
  cancel: { title: 'bulk.confirmCancelTitle', message: 'bulk.confirmCancel', confirm: 'bulk.cancel' },
  delete: { title: 'bulk.confirmDeleteTitle', message: 'bulk.confirmDelete', confirm: 'bulk.delete' },
  purge:  { title: 'bulk.confirmPurgeTitle',  message: 'bulk.confirmPurge',  confirm: 'bulk.deletePermanently' },
};

function loadHiddenColumns(): Set<string> {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      const arr = JSON.parse(stored) as string[];
      if (Array.isArray(arr)) return new Set(arr);
    }
  } catch { /* ignore */ }
  return new Set(DEFAULT_HIDDEN);
}

export function TaskBoardPage() {
  const t = useTranslations('taskBoard');
  const board = useTaskBoard();
  const sidePanel = useSidePanelSafe();
  const [showCreateDialog, setShowCreateDialog] = useState(false);
  const [showColumnManager, setShowColumnManager] = useState(false);
  const [dragTaskId, setDragTaskId] = useState<string | null>(null);
  const [hiddenColumns, setHiddenColumns] = useState<Set<string>>(loadHiddenColumns);
  const [initialStagedStatusTaskId, setInitialStagedStatusTaskId] = useState<string | null>(null);
  // F6 client-side filters (the board already loads every card): label, "my tasks", "blocked".
  const [labelFilter, setLabelFilter] = useState<string | null>(null);
  const [mineOnly, setMineOnly] = useState(false);
  const [blockedOnly, setBlockedOnly] = useState(false);
  const activeFilterCount = (mineOnly ? 1 : 0) + (blockedOnly ? 1 : 0);
  // Multi-select is scoped to ONE column at a time (per the same-column rule): selecting
  // a card in a different column resets the selection to that column. columnKey === a
  // task status (COLUMNS keys === statuses).
  const [selection, setSelection] = useState<{ columnKey: string | null; ids: Set<string> }>(
    { columnKey: null, ids: new Set() },
  );
  const [bulkBusy, setBulkBusy] = useState(false);
  // Deferred destructive action awaiting confirmation in the modal. Works for both the
  // multi-select bar (count = selection size) and a single-card drag (count = 1); `run`
  // captures the exact work to perform on confirm, so each path keeps its own executor.
  const [pendingConfirm, setPendingConfirm] = useState<{ action: DestructiveAction; count: number; run: () => Promise<void> } | null>(null);
  const [showColumnPicker, setShowColumnPicker] = useState(false);
  const columnPickerRef = useRef<HTMLDivElement>(null);
  // "My tasks" + "Blocked" are consolidated into one Filters dropdown (instead of
  // separate toolbar toggle pills), mirroring the column picker.
  const [showFilterMenu, setShowFilterMenu] = useState(false);
  const filterMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!showColumnPicker) return;
    const handleClick = (e: MouseEvent) => {
      if (columnPickerRef.current && !columnPickerRef.current.contains(e.target as Node)) {
        setShowColumnPicker(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showColumnPicker]);

  useEffect(() => {
    if (!showFilterMenu) return;
    const handleClick = (e: MouseEvent) => {
      if (filterMenuRef.current && !filterMenuRef.current.contains(e.target as Node)) {
        setShowFilterMenu(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [showFilterMenu]);

  // Columns are server-driven (F4 / F3) with a fallback to the built-in defaults, so an
  // un-customized board (and the unit tests, which don't provide statuses) render exactly
  // as before. A built-in key keeps its i18n title; a custom column uses its stored label.
  const columnDefs = useMemo<ColumnDef[]>(() => {
    const cfg = board.statuses;
    if (!cfg || cfg.length === 0) return DEFAULT_COLUMNS;
    return cfg.map(s => ({
      key: s.key,
      color: s.color || COLOR_BY_CATEGORY[s.category] || 'text-gray-400',
      icon: ICON_BY_CATEGORY[s.category] || Pause,
      wipLimit: s.wipLimit ?? null,
      label: DEFAULT_STATUS_KEYS.has(s.key) ? null : s.label,
    }));
  }, [board.statuses]);

  const columnTitle = useCallback(
    (col: ColumnDef) => col.label ?? t(`status.${col.key}`),
    [t],
  );

  const toggleColumn = (key: string) => {
    setHiddenColumns(prev => {
      const next = new Set(prev);
      if (next.has(key)) {
        next.delete(key);
      } else {
        const visibleCount = columnDefs.length - next.size;
        if (visibleCount <= 1) return prev;
        next.add(key);
      }
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify([...next])); } catch { /* ignore */ }
      return next;
    });
  };

  const visibleColumns = useMemo(() => columnDefs.filter(c => !hiddenColumns.has(c.key)), [columnDefs, hiddenColumns]);

  const agentMap = useMemo(() => new Map((board.agents || []).map(a => [a.id, a])), [board.agents]);
  const taskMap = useMemo(() => new Map((board.tasks || []).map(t => [t.id, t])), [board.tasks]);
  // Current user's chosen displayName (never the Keycloak real name) for the
  // "you" placeholder on cards.
  const selfPerson = useMemo(() => (board.people || []).find(p => p.isSelf) || null, [board.people]);
  const selfName = selfPerson?.displayName || t('detail.you');
  // Fallback display source for a human assignee/reviewer when a WS task update
  // (published un-enriched) drops task.users - the teammate list is always loaded.
  const peopleById = useMemo(() => new Map((board.people || []).map(p => [p.userId, p])), [board.people]);

  const openAgentPanel = useCallback((agentId: string) => {
    if (!sidePanel) return;
    const agent = agentMap.get(agentId);
    if (!agent) return;
    sidePanel.openTab({
      id: `agent-${agent.id}`,
      label: agent.name,
      icon: <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-4 !h-4" />,
      content: <AgentPanelContent agentId={agent.id} initialTab={AGENT_CONFIGURATION_TAB} />,
      preferredWidth: 0.35,
    });
  }, [sidePanel, agentMap]);

  // Label catalog (F2), id → {name,color}, for resolving a card's labelIds to chips.
  const labelsById = useMemo(() => new Map((board.labels || []).map(l => [l.id, l] as const)), [board.labels]);

  // A blocker only keeps a task "blocked" while it is still ACTIVE (AgentTaskEntity.blockedByIds
  // doc: the board computes "blocked" while any blocker is still non-terminal). A blocker stops
  // blocking once it reaches a closed state (done / failed / cancelled) or is removed (deleted) -
  // i.e. any status whose category is one of those. Fall back to the built-in keys when the
  // status catalog isn't loaded (unit tests).
  const terminalStatusKeys = useMemo(() => {
    const cfg = board.statuses;
    if (cfg && cfg.length > 0) {
      return new Set(
        cfg
          .filter(s => s.category === 'done' || s.category === 'failed' || s.category === 'cancelled' || s.category === 'deleted')
          .map(s => s.key),
      );
    }
    return new Set(['completed', 'failed', 'cancelled', 'deleted']);
  }, [board.statuses]);

  // F6: apply the client-side filters to the loaded tasks before grouping.
  const filteredTasks = useMemo(() => {
    let ts = board.tasks;
    if (labelFilter) ts = ts.filter(tk => tk.labelIds.includes(labelFilter));
    if (mineOnly) {
      const me = selfPerson?.userId;
      ts = me ? ts.filter(tk => tk.assignedToUserId === me || tk.createdByUserId === me) : [];
    }
    // A task is "blocked" only while at least one of its blockers is still non-terminal.
    // (Was: blockedByIds.length > 0 - which kept a task "blocked" even after its blocker
    // completed, contradicting AgentTaskEntity.blockedByIds.)
    if (blockedOnly) {
      ts = ts.filter(tk => tk.blockedByIds.some(id => {
        const blocker = taskMap.get(id);
        // A resolvable blocker blocks only while non-terminal. An unresolvable one
        // (not loaded / cross-board) stays conservatively "blocking" (prior behaviour).
        return blocker == null || !terminalStatusKeys.has(blocker.status);
      }));
    }
    return ts;
  }, [board.tasks, labelFilter, mineOnly, blockedOnly, selfPerson, taskMap, terminalStatusKeys]);

  // Group tasks into columns (keyed by the live column set, custom columns included).
  const columns = useMemo(() => {
    const groups: Record<string, Task[]> = {};
    for (const c of columnDefs) groups[c.key] = [];
    for (const task of filteredTasks) {
      if (groups[task.status]) groups[task.status].push(task);
    }
    return groups;
  }, [filteredTasks, columnDefs]);

  // ── Multi-select (same-column) ──
  const clearSelection = useCallback(() => setSelection({ columnKey: null, ids: new Set() }), []);

  const toggleCardSelection = useCallback((columnKey: string, taskId: string) => {
    setSelection(prev => {
      if (prev.columnKey !== columnKey) {
        // Switching columns resets the selection to the newly-picked column.
        return { columnKey, ids: new Set([taskId]) };
      }
      const ids = new Set(prev.ids);
      if (ids.has(taskId)) ids.delete(taskId); else ids.add(taskId);
      return ids.size === 0 ? { columnKey: null, ids } : { columnKey, ids };
    });
  }, []);

  const toggleSelectAll = useCallback((columnKey: string, taskIds: string[]) => {
    setSelection(prev => {
      const allSelected = prev.columnKey === columnKey
        && prev.ids.size === taskIds.length
        && taskIds.every(id => prev.ids.has(id));
      if (allSelected || taskIds.length === 0) return { columnKey: null, ids: new Set() };
      return { columnKey, ids: new Set(taskIds) };
    });
  }, []);

  // Prune selected ids that left the column (status change via WS, refresh, etc.) so a
  // bulk action never targets stale ids. Drop the column entirely once nothing remains.
  useEffect(() => {
    setSelection(prev => {
      if (!prev.columnKey || prev.ids.size === 0) return prev;
      const valid = new Set<string>();
      for (const task of board.tasks) {
        if (task.status === prev.columnKey && prev.ids.has(task.id)) valid.add(task.id);
      }
      if (valid.size === prev.ids.size) return prev;
      return valid.size === 0 ? { columnKey: null, ids: new Set() } : { columnKey: prev.columnKey, ids: valid };
    });
  }, [board.tasks]);

  // Authoritative re-entrancy latch: a synchronous ref so a second call within the same tick
  // is rejected even before React flushes `bulkBusy` to disable the buttons.
  const bulkBusyRef = useRef(false);
  // Run a deferred action under the busy guard (disables the bar + modal buttons, blocks re-entry).
  // Each `run` closure already swallows its own errors; the trailing .catch is belt-and-suspenders
  // so the busy flag always clears even if a future closure rejects.
  const runGuarded = useCallback(async (run: () => Promise<void>) => {
    if (bulkBusyRef.current) return;
    bulkBusyRef.current = true;
    setBulkBusy(true);
    await run().catch(() => { /* run() handles its own errors; board refresh shows the truth */ });
    bulkBusyRef.current = false;
    setBulkBusy(false);
  }, []);

  // Multi-select bar verb. Destructive moves (cancel/delete/purge) open the confirmation
  // modal; restore is non-destructive and fires immediately.
  const requestBulkAction = useCallback((action: BulkTaskAction) => {
    const ids = [...selection.ids];
    if (ids.length === 0 || bulkBusy) return;
    const run = async () => {
      try { await taskService.bulkAction(ids, action); }
      catch { /* per-item errors are surfaced server-side; board refresh shows the truth */ }
      clearSelection();
      board.refresh();
    };
    if (action === 'restore') { void runGuarded(run); return; }
    setPendingConfirm({ action, count: ids.length, run });
  }, [selection.ids, bulkBusy, clearSelection, board, runGuarded]);

  // Confirm the pending destructive action (from the bar or a drag). Keeps the modal open
  // with disabled buttons until the work settles, then closes it.
  const confirmPending = useCallback(async () => {
    if (!pendingConfirm || bulkBusy) return;
    const { run } = pendingConfirm;
    await runGuarded(run);
    setPendingConfirm(null);
  }, [pendingConfirm, bulkBusy, runGuarded]);

  const handleDrop = useCallback((targetColumn: string, e: React.DragEvent) => {
    e.preventDefault();
    if (!dragTaskId) return;
    const taskId = dragTaskId;
    setDragTaskId(null);

    const task = board.tasks.find(t => t.id === taskId);
    if (!task) return;
    if (targetColumn === task.status) return;

    // Drop onto Deleted → soft-delete (trash), gated by the confirmation modal.
    if (targetColumn === 'deleted') {
      setPendingConfirm({
        action: 'delete',
        count: 1,
        run: async () => {
          try { await taskService.softDeleteTasks([taskId]); } catch { /* refresh shows the truth */ }
          board.refresh();
        },
      });
      return;
    }

    // Drop onto Cancelled → cancel (cascading), gated by the confirmation modal. moveToCancelled
    // also requalifies terminal tasks and clears soft-delete bookkeeping for a trashed source.
    if (targetColumn === 'cancelled') {
      setPendingConfirm({
        action: 'cancel',
        count: 1,
        run: async () => {
          try { await taskService.moveToCancelled(task); } catch { /* refresh shows the truth */ }
          board.refresh();
        },
      });
      return;
    }

    // Moving to in_progress → open modal with pre-staged status (user must confirm)
    if (targetColumn === 'in_progress') {
      setInitialStagedStatusTaskId(taskId);
      board.setSelectedTaskId(taskId);
      return;
    }

    // in_review requires an assignee (agent OR human) - block if missing
    if (targetColumn === 'in_review' && !task.assignedToAgentId && !task.assignedToUserId) {
      board.setSelectedTaskId(taskId);
      return;
    }

    // Any other move (incl. dragging OUT of Deleted to a normal column = restore-to-specific-column,
    // where updateTask clears the soft-delete bookkeeping server-side).
    void (async () => {
      try { await taskService.updateTask(taskId, { status: targetColumn as TaskStatus }); board.refresh(); }
      catch { board.refresh(); }
    })();
  }, [dragTaskId, board]);

  // Loading skeleton
  if (board.loading && board.tasks.length === 0) {
    return (
      <div className="flex flex-col flex-1 min-h-0 pt-4 px-2 sm:px-4 gap-4">
        <div className="flex flex-1 min-h-0 gap-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="flex-1 min-w-0 rounded-xl bg-theme-secondary/50 animate-pulse p-3 space-y-2">
              <div className="h-4 w-24 bg-theme-tertiary rounded" />
              <div className="h-16 bg-theme-tertiary rounded" />
              <div className="h-16 bg-theme-tertiary rounded" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="relative flex flex-col flex-1 min-h-0 pt-4 px-2 sm:px-4 gap-4">
      {/* ── Header ────────────────────────────────────────── */}
      <div className="flex-shrink-0 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
            <ClipboardList className="h-5 w-5 text-theme-primary" />
          </div>
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-theme-primary">{t('title')}</h3>
            <p className="text-sm text-theme-secondary truncate hidden sm:block">{t('empty.description')}</p>
          </div>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0 flex-wrap">
          <Select
            value={board.sortBy}
            onValueChange={(v) => board.setSortBy(v as TaskSortField)}
          >
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
              <ArrowUpDown className="h-3 w-3 mr-1 text-theme-muted" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="priority" className="text-xs rounded-md py-1.5">{t('sort.priority')}</SelectItem>
              <SelectItem value="updated_at" className="text-xs rounded-md py-1.5">{t('sort.updatedAt')}</SelectItem>
              <SelectItem value="created_at" className="text-xs rounded-md py-1.5">{t('sort.createdAt')}</SelectItem>
              <SelectItem value="due_by" className="text-xs rounded-md py-1.5">{t('sort.dueBy')}</SelectItem>
            </SelectContent>
          </Select>
          <Select
            value={board.agentFilter || '__all__'}
            onValueChange={(v) => board.setAgentFilter(v === '__all__' ? null : v)}
          >
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
              <SelectValue placeholder={t('filters.allAgents')} />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="__all__" className="text-xs rounded-md py-1.5">
                {t('filters.allAgents')}
              </SelectItem>
              {board.agents.map((a) => (
                <SelectItem key={a.id} value={a.id} className="text-xs rounded-md py-1.5">
                  <span className="flex items-center gap-2">
                    <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                    {a.name}
                  </span>
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          {(board.labels?.length ?? 0) > 0 && (
            <Select value={labelFilter || '__all__'} onValueChange={(v) => setLabelFilter(v === '__all__' ? null : v)}>
              <SelectTrigger className="min-h-0 h-7 w-auto min-w-[110px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
                <SelectValue placeholder={t('filters.allLabels')} />
              </SelectTrigger>
              <SelectContent className="rounded-lg">
                <SelectItem value="__all__" className="text-xs rounded-md py-1.5">{t('filters.allLabels')}</SelectItem>
                {board.labels.map((l) => (
                  <SelectItem key={l.id} value={l.id} className="text-xs rounded-md py-1.5">{l.name}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
          {/* My tasks + Blocked consolidated into one Filters dropdown (multi-select). */}
          <div className="relative" ref={filterMenuRef}>
            <button
              type="button"
              data-testid="task-filter-menu"
              aria-expanded={showFilterMenu}
              onClick={() => setShowFilterMenu((s) => !s)}
              className={`inline-flex items-center gap-1.5 h-7 px-2 rounded-md text-xs border transition-colors ${
                activeFilterCount > 0
                  ? 'border-[var(--accent-primary)] text-[var(--accent-primary)] bg-[var(--accent-primary)]/10'
                  : 'border-slate-200 dark:border-slate-700/50 text-theme-secondary hover:text-theme-primary hover:border-slate-300 dark:hover:border-slate-600'
              }`}
            >
              <SlidersHorizontal className="h-3 w-3" />
              {t('filters.filters')}
              {activeFilterCount > 0 && <span className="tabular-nums">({activeFilterCount})</span>}
            </button>
            {showFilterMenu && (
              <div className="absolute right-0 top-full mt-1 z-50 w-44 rounded-lg border border-slate-200 dark:border-slate-700/50 bg-[var(--bg-primary)] shadow-lg py-1">
                <button
                  type="button"
                  data-testid="task-filter-mine"
                  aria-pressed={mineOnly}
                  onClick={() => setMineOnly((m) => !m)}
                  className="w-full flex items-center gap-2 px-3 py-1.5 text-xs hover:bg-[var(--bg-secondary)] transition-colors"
                >
                  <span className={`flex items-center justify-center w-4 h-4 rounded border ${
                    mineOnly
                      ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]'
                      : 'border-slate-300 dark:border-slate-600'
                  }`}>
                    {mineOnly && <Check className="h-3 w-3" />}
                  </span>
                  <span className="flex-1 text-left text-theme-primary">{t('filters.myTasks')}</span>
                </button>
                <button
                  type="button"
                  data-testid="task-filter-blocked"
                  aria-pressed={blockedOnly}
                  onClick={() => setBlockedOnly((b) => !b)}
                  className="w-full flex items-center gap-2 px-3 py-1.5 text-xs hover:bg-[var(--bg-secondary)] transition-colors"
                >
                  <span className={`flex items-center justify-center w-4 h-4 rounded border ${
                    blockedOnly
                      ? 'bg-amber-500 border-amber-500 text-white'
                      : 'border-slate-300 dark:border-slate-600'
                  }`}>
                    {blockedOnly && <Check className="h-3 w-3" />}
                  </span>
                  <Lock className="h-3 w-3 text-theme-muted" />
                  <span className="flex-1 text-left text-theme-primary">{t('filters.blocked')}</span>
                </button>
              </div>
            )}
          </div>
          <div className="relative">
            <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-theme-muted" />
            <input
              type="text"
              value={board.searchQuery}
              onChange={(e) => board.setSearchQuery(e.target.value)}
              placeholder={t('filters.search')}
              className="h-7 rounded-md pl-7 pr-2 text-xs border border-slate-200 dark:border-slate-700/50 bg-transparent text-theme-primary placeholder:text-theme-muted w-40 focus:outline-none focus:ring-1 focus:ring-[var(--accent-primary)]"
            />
          </div>
          {/* Column visibility picker */}
          <div className="relative" ref={columnPickerRef}>
            <button
              type="button"
              onClick={() => setShowColumnPicker(p => !p)}
              className="inline-flex items-center gap-1.5 h-7 px-2 rounded-md text-xs border border-slate-200 dark:border-slate-700/50 text-theme-secondary hover:text-theme-primary hover:border-slate-300 dark:hover:border-slate-600 transition-colors"
            >
              <Columns3 className="h-3 w-3" />
              {t('filters.visibleColumns')}
              <span className="text-theme-muted tabular-nums">({visibleColumns.length})</span>
            </button>
            {showColumnPicker && (
              <div className="absolute right-0 top-full mt-1 z-50 w-48 rounded-lg border border-slate-200 dark:border-slate-700/50 bg-[var(--bg-primary)] shadow-lg py-1">
                {columnDefs.map(col => {
                  const Icon = col.icon;
                  const visible = !hiddenColumns.has(col.key);
                  const count = (columns[col.key] || []).length;
                  return (
                    <button
                      key={col.key}
                      type="button"
                      onClick={() => toggleColumn(col.key)}
                      className="w-full flex items-center gap-2 px-3 py-1.5 text-xs hover:bg-[var(--bg-secondary)] transition-colors"
                    >
                      <span className={`flex items-center justify-center w-4 h-4 rounded border ${
                        visible
                          ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]'
                          : 'border-slate-300 dark:border-slate-600'
                      }`}>
                        {visible && <Check className="h-3 w-3" />}
                      </span>
                      <Icon className={`h-3 w-3 ${col.color}`} />
                      <span className="flex-1 text-left text-theme-primary truncate">{columnTitle(col)}</span>
                      {count > 0 && <span className="text-theme-muted tabular-nums">{count}</span>}
                    </button>
                  );
                })}
                <div className="border-t border-theme mt-1 pt-1">
                  <button
                    type="button"
                    data-testid="open-column-manager"
                    onClick={() => { setShowColumnPicker(false); setShowColumnManager(true); }}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-xs text-theme-secondary hover:bg-[var(--bg-secondary)] hover:text-theme-primary transition-colors"
                  >
                    <Settings2 className="h-3 w-3" />
                    {t('manageColumns.open')}
                  </button>
                </div>
              </div>
            )}
          </div>
          <Button onClick={() => setShowCreateDialog(true)} size="sm" className="h-7 px-3 text-xs gap-1">
            <Plus className="h-3 w-3" />
            {t('actions.createTask')}
          </Button>
        </div>
      </div>

      {/* Truncation warning */}
      {board.total > board.tasks.length && (
        <div className="flex items-center gap-2 text-xs text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-lg px-3 py-2">
          <Info className="h-3.5 w-3.5 flex-shrink-0" />
          {t('truncated', { shown: board.tasks.length, total: board.total })}
        </div>
      )}

      {/* ── Kanban Board ─────────────────────────────────── */}
      {board.error ? (
        <div className="flex-shrink-0 text-sm text-red-500 bg-red-50 dark:bg-red-900/20 rounded-xl px-4 py-3">{board.error}</div>
      ) : (
        <div className="flex flex-1 min-h-0 gap-3 pb-2 overflow-x-auto overflow-y-hidden">
          {visibleColumns.map((col) => {
            const tasks = columns[col.key] || [];
            const Icon = col.icon;
            return (
              <KanbanColumn
                key={col.key}
                columnKey={col.key}
                icon={<Icon className={`h-3.5 w-3.5 ${col.color}`} />}
                title={columnTitle(col)}
                count={tasks.length}
                wipLimit={col.wipLimit}
                tasks={tasks}
                agentMap={agentMap}
                taskMap={taskMap}
                terminalStatusKeys={terminalStatusKeys}
                peopleById={peopleById}
                labelsById={labelsById}
                dragTaskId={dragTaskId}
                onDragStart={setDragTaskId}
                onDrop={handleDrop}
                onSelectTask={board.setSelectedTaskId}
                onOpenAgent={openAgentPanel}
                onAddTask={col.key === 'pending' ? () => setShowCreateDialog(true) : undefined}
                userId={selfPerson?.userId ?? null}
                userName={selfName}
                selectedIds={selection.columnKey === col.key ? selection.ids : EMPTY_IDS}
                onToggleSelect={(taskId) => toggleCardSelection(col.key, taskId)}
                onToggleSelectAll={() => toggleSelectAll(col.key, tasks.map(tk => tk.id))}
              />
            );
          })}
        </div>
      )}

      {/* Bulk action bar - appears when ≥1 card is selected (same-column) */}
      {selection.ids.size > 0 && (
        <BulkActionBar
          count={selection.ids.size}
          isDeletedColumn={selection.columnKey === 'deleted'}
          busy={bulkBusy}
          onCancel={() => requestBulkAction('cancel')}
          onDelete={() => requestBulkAction('delete')}
          onRestore={() => requestBulkAction('restore')}
          onPurge={() => requestBulkAction('purge')}
          onClear={clearSelection}
        />
      )}

      {/* Confirmation modal for any destructive move (bulk bar or single-card drag) to
          Cancelled / Deleted, plus permanent purge. Restore is non-destructive → no modal. */}
      <BulkDeleteModal
        isOpen={!!pendingConfirm}
        title={pendingConfirm ? t(CONFIRM_COPY[pendingConfirm.action].title) : ''}
        message={pendingConfirm ? t(CONFIRM_COPY[pendingConfirm.action].message, { count: pendingConfirm.count }) : ''}
        confirmLabel={pendingConfirm ? t(CONFIRM_COPY[pendingConfirm.action].confirm) : ''}
        cancelLabel={t('bulk.keep')}
        isConfirming={bulkBusy}
        icon={pendingConfirm?.action === 'cancel' ? <Ban className="w-8 h-8 text-red-500" /> : undefined}
        onCancel={() => { if (!bulkBusy) setPendingConfirm(null); }}
        onConfirm={confirmPending}
      />

      {/* Detail panel */}
      {board.selectedTaskId && (
        <TaskDetailPanel
          taskId={board.selectedTaskId}
          agents={board.agents}
          people={board.people}
          statuses={board.statuses}
          labels={board.labels}
          initialStagedStatus={initialStagedStatusTaskId === board.selectedTaskId ? 'in_progress' as const : undefined}
          onClose={() => { board.setSelectedTaskId(null); setInitialStagedStatusTaskId(null); }}
          onRefresh={board.refresh}
          onSelectTask={board.setSelectedTaskId}
        />
      )}

      {/* Create dialog */}
      {showCreateDialog && (
        <CreateTaskDialog
          agents={board.agents}
          people={board.people}
          onClose={() => setShowCreateDialog(false)}
          onCreated={() => { setShowCreateDialog(false); board.refresh(); }}
        />
      )}

      {showColumnManager && (
        <ColumnManagerDialog
          statuses={board.statuses}
          onClose={() => setShowColumnManager(false)}
          onChanged={() => board.refresh()}
        />
      )}

      {/* Agent activity WS subscriptions - one per visible or assigned agent */}
      <AgentActivitySubscribers tasks={board.tasks} agents={board.agents} />
    </div>
  );
}

// ─── Agent activity WS subscriptions ────────────────────────────

function AgentActivitySubscriberComponent({ agentId }: { agentId: string }) {
  useAgentActivitySubscriber(agentId);
  return null;
}

const AgentActivitySubscribers = React.memo(
  function AgentActivitySubscribers({ tasks, agents }: { tasks: Task[]; agents: Agent[] }) {
    const agentIds = useMemo(() => {
      return selectTaskActivityAgentIds(tasks, agents);
    }, [tasks, agents]);

    return (
      <>
        {agentIds.map(id => (
          <AgentActivitySubscriberComponent key={id} agentId={id} />
        ))}
      </>
    );
  },
  (prev, next) => {
    return selectTaskActivityAgentIds(prev.tasks, prev.agents).join(',')
      === selectTaskActivityAgentIds(next.tasks, next.agents).join(',');
  },
);

// ─── Bulk action bar ─────────────────────────────────────────────

function BulkActionBar({ count, isDeletedColumn, busy, onCancel, onDelete, onRestore, onPurge, onClear }: {
  count: number;
  isDeletedColumn: boolean;
  busy: boolean;
  onCancel: () => void;
  onDelete: () => void;
  onRestore: () => void;
  onPurge: () => void;
  onClear: () => void;
}) {
  const t = useTranslations('taskBoard');
  const btn = 'inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-xs transition-colors disabled:opacity-50';
  const neutral = 'text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary';
  const danger = 'text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20';
  return (
    <div
      data-testid="task-bulk-bar"
      className="absolute bottom-4 left-1/2 -translate-x-1/2 z-40 flex items-center gap-1.5 rounded-full border border-slate-200 dark:border-slate-700/50 bg-[var(--bg-primary)] shadow-lg px-3 py-2"
    >
      <span className="text-sm font-medium text-theme-primary tabular-nums px-1">
        {t('bulk.selected', { count })}
      </span>
      <div className="h-4 w-px bg-slate-200 dark:bg-slate-700/50" />
      {isDeletedColumn ? (
        <>
          <button type="button" data-testid="task-bulk-restore" disabled={busy} onClick={onRestore} className={`${btn} ${neutral}`}>
            <RotateCcw className="h-3.5 w-3.5" /> {t('bulk.restore')}
          </button>
          <button type="button" data-testid="task-bulk-purge" disabled={busy} onClick={onPurge} className={`${btn} ${danger}`}>
            <Trash2 className="h-3.5 w-3.5" /> {t('bulk.deletePermanently')}
          </button>
        </>
      ) : (
        <>
          <button type="button" data-testid="task-bulk-cancel" disabled={busy} onClick={onCancel} className={`${btn} ${neutral}`}>
            <Ban className="h-3.5 w-3.5" /> {t('bulk.cancel')}
          </button>
          <button type="button" data-testid="task-bulk-delete" disabled={busy} onClick={onDelete} className={`${btn} ${danger}`}>
            <Trash2 className="h-3.5 w-3.5" /> {t('bulk.delete')}
          </button>
        </>
      )}
      <div className="h-4 w-px bg-slate-200 dark:bg-slate-700/50" />
      <button
        type="button"
        data-testid="task-bulk-clear"
        onClick={onClear}
        title={t('bulk.clear')}
        className="inline-flex items-center justify-center h-7 w-7 rounded-md text-theme-muted hover:text-theme-primary hover:bg-theme-tertiary transition-colors"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}

// ─── Kanban Column ───────────────────────────────────────────────

interface KanbanColumnProps {
  columnKey: string;
  icon: React.ReactNode;
  title: string;
  count: number;
  /** WIP limit (F3); null = none. Header turns red when count exceeds it. */
  wipLimit: number | null;
  tasks: Task[];
  agentMap: Map<string, Agent>;
  taskMap: Map<string, Task>;
  terminalStatusKeys: ReadonlySet<string>;
  peopleById: Map<string, TaskPerson>;
  labelsById: Map<string, TaskLabel>;
  dragTaskId: string | null;
  onDragStart: (taskId: string) => void;
  onDrop: (column: string, e: React.DragEvent) => void;
  onSelectTask: (taskId: string) => void;
  onOpenAgent?: (agentId: string) => void;
  onAddTask?: () => void;
  userId?: string | null;
  userName?: string;
  selectedIds: ReadonlySet<string>;
  onToggleSelect: (taskId: string) => void;
  onToggleSelectAll: () => void;
}

function KanbanColumn({
  columnKey, icon, title, count, wipLimit, tasks, agentMap, taskMap, terminalStatusKeys, peopleById, labelsById,
  dragTaskId, onDragStart, onDrop, onSelectTask, onOpenAgent, onAddTask,
  userId, userName, selectedIds, onToggleSelect, onToggleSelectAll,
}: KanbanColumnProps) {
  const t = useTranslations('taskBoard');
  const [isDragOver, setIsDragOver] = useState(false);
  const selectedCount = selectedIds.size;
  const allSelected = tasks.length > 0 && selectedCount === tasks.length;
  const isDeletedColumn = columnKey === 'deleted';

  return (
    <div
      data-testid={`task-column-${columnKey}`}
      data-task-status={columnKey}
      className={`group/col flex flex-col rounded-xl border transition-all duration-200 overflow-hidden min-h-0 flex-1 min-w-[180px] ${
        isDragOver
          ? 'border-[var(--accent-primary)] bg-[var(--accent-primary)]/5 shadow-md'
          : 'border-slate-200 dark:border-slate-700/50 bg-theme-secondary/30'
      }`}
      onDragOver={(e) => { e.preventDefault(); setIsDragOver(true); }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={(e) => { setIsDragOver(false); onDrop(columnKey, e); }}
    >
      {/* Column header */}
      <div className="flex items-center gap-2 px-3 py-2 bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
        {/* Select-all checkbox - visible on hover or when this column has a selection */}
        {tasks.length > 0 && (
          <button
            type="button"
            data-testid={`task-column-selectall-${columnKey}`}
            onClick={onToggleSelectAll}
            title={t('bulk.selectAll')}
            className={`flex items-center justify-center w-4 h-4 rounded border transition-opacity ${
              selectedCount > 0 ? 'opacity-100' : 'opacity-0 group-hover/col:opacity-100'
            } ${allSelected
              ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]'
              : 'border-slate-300 dark:border-slate-600'}`}
          >
            {allSelected && <Check className="h-3 w-3" />}
            {!allSelected && selectedCount > 0 && <span className="h-0.5 w-2 rounded bg-[var(--accent-primary)]" />}
          </button>
        )}
        {icon}
        <span className="text-sm font-medium text-theme-primary truncate">{title}</span>
        <span
          data-testid={`task-column-count-${columnKey}`}
          title={wipLimit != null ? `${count} / ${wipLimit}` : undefined}
          className={`ml-auto text-xs font-semibold rounded-full px-2 py-0.5 min-w-[24px] text-center tabular-nums ${
            wipLimit != null && count > wipLimit
              ? 'bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400'
              : 'text-theme-muted bg-theme-tertiary'
          }`}
        >
          {count}{wipLimit != null ? ` / ${wipLimit}` : ''}
        </span>
      </div>

      {/* Cards */}
      <div className="flex-1 overflow-y-auto p-2 space-y-2">
        {tasks.map((task) => (
          <KanbanCard
            key={task.id}
            task={task}
            agentMap={agentMap}
            taskMap={taskMap}
            terminalStatusKeys={terminalStatusKeys}
            peopleById={peopleById}
            labelsById={labelsById}
            isDragging={dragTaskId === task.id}
            onDragStart={() => onDragStart(task.id)}
            onClick={() => onSelectTask(task.id)}
            onOpenAgent={onOpenAgent}
            userId={userId}
            userName={userName}
            isDeletedColumn={isDeletedColumn}
            selected={selectedIds.has(task.id)}
            selectionActive={selectedCount > 0}
            onToggleSelect={() => onToggleSelect(task.id)}
          />
        ))}
        {onAddTask && (
          <button
            type="button"
            onClick={onAddTask}
            className="w-full rounded-xl border border-dashed border-slate-300 dark:border-slate-600 py-3 flex items-center justify-center gap-1.5 text-xs text-theme-muted opacity-0 group-hover/col:opacity-100 transition-opacity hover:border-slate-400 dark:hover:border-slate-500 hover:text-theme-secondary hover:bg-theme-secondary/30"
          >
            <Plus className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

// ─── Kanban Card ─────────────────────────────────────────────────

function KanbanCard({ task, agentMap, taskMap, terminalStatusKeys, peopleById, labelsById, isDragging, onDragStart, onClick, onOpenAgent, userId, userName, isDeletedColumn, selected, selectionActive, onToggleSelect }: {
  task: Task;
  agentMap: Map<string, Agent>;
  taskMap: Map<string, Task>;
  terminalStatusKeys: ReadonlySet<string>;
  peopleById: Map<string, TaskPerson>;
  labelsById: Map<string, TaskLabel>;
  isDragging: boolean;
  onDragStart: () => void;
  onClick: () => void;
  onOpenAgent?: (agentId: string) => void;
  userId?: string | null;
  userName?: string;
  isDeletedColumn: boolean;
  selected: boolean;
  selectionActive: boolean;
  onToggleSelect: () => void;
}) {
  const t = useTranslations('taskBoard');
  // Resolve a human id, preferring the server-enriched task.users map and falling
  // back to the loaded teammate list (covers un-enriched WS task updates).
  const resolveCardUser = (id: string | null): { userId: string; displayName: string | null; avatarUrl: string | null } | null => {
    if (!id) return null;
    const ref = task.users?.[id];
    const person = peopleById.get(id);
    return {
      userId: id,
      displayName: ref?.displayName || person?.displayName || null,
      avatarUrl: ref?.avatarUrl ?? person?.avatarUrl ?? null,
    };
  };
  const assigneeAgent = task.assignedToAgentId ? agentMap.get(task.assignedToAgentId) : null;
  const assigneeUser = resolveCardUser(task.assignedToUserId);
  const reviewerAgent = task.reviewerAgentId ? agentMap.get(task.reviewerAgentId) : null;
  const reviewerUser = resolveCardUser(task.reviewerUserId);
  const refName = (r: { displayName: string | null } | null) => r?.displayName || t('detail.someone');
  const parentTask = task.parentTaskId ? taskMap.get(task.parentTaskId) : null;

  // Feature badges (F2/F5/F9/F10/F12). All conditional, so a bare task renders unchanged.
  const cardLabels = task.labelIds.map(id => labelsById.get(id)).filter((l): l is TaskLabel => !!l);
  const checklistTotal = task.checklist.length;
  const checklistDone = task.checklist.filter(i => i.done === true).length;
  // Count only blockers that are still ACTIVE (non-terminal) - a completed/cancelled blocker
  // no longer blocks, so the card's amber "blocked" badge must drop it (matches the board's
  // Blocked filter). Unresolvable blockers stay conservatively counted.
  const blockedCount = task.blockedByIds.filter(id => {
    const blocker = taskMap.get(id);
    return blocker == null || !terminalStatusKeys.has(blocker.status);
  }).length;
  const attachCount = task.attachments.length;
  const due = task.dueBy ? parseUtcAware(task.dueBy) : null;
  const overdue = !!due && due.getTime() < Date.now()
    && task.status !== 'completed' && task.status !== 'cancelled' && task.status !== 'deleted';

  const priorityLeftColor: Record<string, string> = {
    urgent: '#ef4444', high: '#fb923c', normal: '', low: '',
  };

  // Shimmer is scoped to the (agent, task) pair: an agent may be running for a different task
  // (e.g. handling another inbox item, direct chat, or a workflow node) - those executions
  // must NOT shimmer this card. Only shimmer when the activity stream reports the agent is
  // currently executing for THIS specific task id.
  //
  // Which agent to watch depends on the task's current status:
  //   - in_review → the reviewer agent is the one running (executeReviewerForTask)
  //   - everything else → the assignee is the one running (executeAgentForTask)
  const agentRunning = useAgentActivityStore(
    useCallback(
      (s) => {
        const relevantAgentId = task.status === 'in_review'
          ? task.reviewerAgentId
          : task.assignedToAgentId;
        if (!relevantAgentId) return false;
        const activity = s.agents[relevantAgentId];
        if (!activity?.isRunning) return false;
        return activity.currentTaskId === task.id;
      },
      [task.assignedToAgentId, task.reviewerAgentId, task.status, task.id],
    ),
  );

  // Status-based border color (same palette as workflow FlowNode)
  const statusBorderColor = agentRunning
    ? '#3b82f6'  // blue-500 - running
    : undefined; // column headers are enough - no colored borders per status

  return (
    <div
      data-testid={`task-card-${task.id}`}
      data-task-status={task.status}
      data-task-title={task.title}
      draggable
      onDragStart={(e) => { e.dataTransfer.effectAllowed = 'move'; onDragStart(); }}
      onClick={onClick}
      className={`group relative overflow-hidden bg-[var(--bg-primary)] rounded-xl border-2 border-l-[3px] cursor-pointer
        transition-colors duration-200
        ${!statusBorderColor ? 'border-slate-200 dark:border-slate-700/50' : ''}
        ${selected ? 'ring-2 ring-[var(--accent-primary)] ring-offset-1 ring-offset-[var(--bg-secondary)]' : ''}
        ${isDragging ? 'opacity-40 scale-95' : 'hover:bg-slate-50 dark:hover:bg-slate-800/60'}
      `}
      style={{
        borderColor: statusBorderColor || undefined,
        borderLeftColor: priorityLeftColor[task.priority] || statusBorderColor || undefined,
      }}
    >
      {/* Selection checkbox - visible on hover, or when this card / its column has a selection.
          Stops propagation so it never opens the detail panel or starts a drag. */}
      <button
        type="button"
        data-testid={`task-card-select-${task.id}`}
        aria-pressed={selected}
        draggable={false}
        onClick={(e) => { e.stopPropagation(); onToggleSelect(); }}
        className={`absolute top-1.5 left-1.5 z-20 flex items-center justify-center w-4 h-4 rounded border ring-2 ring-[var(--bg-primary)] transition-opacity ${
          selected || selectionActive ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
        } ${selected
          ? 'bg-[var(--accent-primary)] border-[var(--accent-primary)] text-[var(--accent-foreground)]'
          : 'bg-[var(--bg-primary)] border-slate-300 dark:border-slate-600'}`}
      >
        {selected && <Check className="h-3 w-3" />}
      </button>
      {/* Shimmer scan effect when agent is actively working - same as workflow nodes */}
      {agentRunning && (
        <div
          data-testid={`task-card-shimmer-${task.id}`}
          className="absolute inset-0 pointer-events-none rounded-[11px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, var(--shimmer-color) 25%, var(--shimmer-color-strong) 50%, var(--shimmer-color) 75%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      <div className={`px-3 py-2.5 relative transition-[padding] ${selectionActive ? 'pl-7' : ''}`}>
        {/* Subtask indicator */}
        {parentTask && (
          <p className="text-xs text-theme-muted truncate mb-0.5">↳ {parentTask.title}</p>
        )}

        {/* Title */}
        <p className="text-sm text-theme-primary leading-snug line-clamp-2">{task.title}</p>

        {/* Instructions preview */}
        {task.instructions && (
          <p className="text-xs text-theme-muted leading-relaxed line-clamp-2 mt-1">{task.instructions}</p>
        )}

        {/* Labels (F2) */}
        {cardLabels.length > 0 && (
          <div className="flex flex-wrap gap-1 mt-1.5" data-testid={`task-card-labels-${task.id}`}>
            {cardLabels.slice(0, 4).map(l => (
              <span
                key={l.id}
                className="inline-flex items-center gap-1 max-w-[120px] rounded px-1.5 py-0.5 text-[10px] font-medium bg-theme-tertiary text-theme-secondary"
              >
                {l.color && /^#[0-9a-fA-F]{3,8}$/.test(l.color) && (
                  <span className="h-1.5 w-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: l.color }} />
                )}
                <span className="truncate">{l.name}</span>
              </span>
            ))}
            {cardLabels.length > 4 && <span className="text-[10px] text-theme-muted self-center">+{cardLabels.length - 4}</span>}
          </div>
        )}

        {/* Meta badges: due/overdue (F5), estimate (F12), blocked (F9), checklist + attachments (F10) */}
        {(due || task.estimateMinutes != null || blockedCount > 0 || checklistTotal > 0 || attachCount > 0) && (
          <div className="flex flex-wrap items-center gap-2 mt-1.5 text-[10px] text-theme-muted" data-testid={`task-card-meta-${task.id}`}>
            {due && (
              <span className={`inline-flex items-center gap-0.5 ${overdue ? 'text-red-500 dark:text-red-400 font-medium' : ''}`} title={due.toLocaleString(getClientLocale())}>
                <CalendarClock className="h-3 w-3" /> {formatDueShort(due)}
              </span>
            )}
            {task.estimateMinutes != null && (
              <span className="inline-flex items-center gap-0.5">
                <Timer className="h-3 w-3" /> {formatMinutes(task.estimateMinutes)}
              </span>
            )}
            {blockedCount > 0 && (
              <span data-testid={`task-card-blocked-${task.id}`} className="inline-flex items-center gap-0.5 text-amber-600 dark:text-amber-400">
                <Lock className="h-3 w-3" /> {blockedCount}
              </span>
            )}
            {checklistTotal > 0 && (
              <span className={`inline-flex items-center gap-0.5 ${checklistDone === checklistTotal ? 'text-green-600 dark:text-green-400' : ''}`}>
                <ListChecks className="h-3 w-3" /> {checklistDone}/{checklistTotal}
              </span>
            )}
            {attachCount > 0 && (
              <span className="inline-flex items-center gap-0.5">
                <Paperclip className="h-3 w-3" /> {attachCount}
              </span>
            )}
          </div>
        )}

        {/* Footer: metadata left, avatars right */}
        <div className="flex items-center justify-between mt-2">
          {isDeletedColumn && task.deletedAt ? (
            <span
              data-testid={`task-card-purge-${task.id}`}
              className="text-xs text-red-500 dark:text-red-400 tabular-nums"
              title={t('deleted.purgesInTitle')}
            >
              {(() => {
                const days = purgeInDays(task.deletedAt);
                return days === 0 ? t('deleted.purgesToday') : t('deleted.purgesIn', { days });
              })()}
            </span>
          ) : (
            <span className="text-xs text-theme-muted tabular-nums">{formatAge(task.createdAt)}</span>
          )}

          {/* Avatar stack - reviewer (agent / teammate / you), then assignee (agent / teammate / unassigned) */}
          <div className="flex items-center -space-x-1.5">
            {reviewerAgent ? (
              <div title={`${t('actions.reviewer')}: ${reviewerAgent.name}`}
                className="relative z-0 rounded-full ring-2 ring-[var(--bg-primary)] cursor-pointer hover:ring-[var(--accent-primary)] transition-colors"
                onClick={(e) => { e.stopPropagation(); onOpenAgent?.(reviewerAgent.id); }}>
                <AvatarDisplay avatarUrl={reviewerAgent.avatarUrl} name={reviewerAgent.name} size="sm" className="!w-5 !h-5" />
              </div>
            ) : reviewerUser ? (
              <div title={`${t('actions.reviewer')}: ${refName(reviewerUser)}`}
                className="relative z-0 rounded-full ring-2 ring-[var(--bg-primary)]">
                <PublisherAvatar userId={reviewerUser.userId} name={refName(reviewerUser)} size={20} variant="neutral" />
              </div>
            ) : (
              <div title={`${t('actions.reviewer')}: ${userName || 'You'}`}
                className="relative z-0 rounded-full ring-2 ring-[var(--bg-primary)]">
                <PublisherAvatar userId={userId ?? null} name={userName || 'You'} size={20} variant="neutral" />
              </div>
            )}
            {assigneeAgent ? (
              <div title={assigneeAgent.name}
                className="relative z-10 rounded-full ring-2 ring-[var(--bg-primary)] cursor-pointer hover:ring-[var(--accent-primary)] transition-colors"
                onClick={(e) => { e.stopPropagation(); onOpenAgent?.(assigneeAgent.id); }}>
                <AvatarDisplay avatarUrl={assigneeAgent.avatarUrl} name={assigneeAgent.name} size="sm" className="!w-6 !h-6" />
              </div>
            ) : assigneeUser ? (
              <div title={`${t('detail.assignedTo')}: ${refName(assigneeUser)}`}
                className="relative z-10 rounded-full ring-2 ring-[var(--bg-primary)]">
                <PublisherAvatar userId={assigneeUser.userId} name={refName(assigneeUser)} size={24} variant="neutral" />
              </div>
            ) : (
              <div title={t('filters.unassigned')}
                className="relative z-10 w-6 h-6 rounded-full ring-2 ring-[var(--bg-primary)] bg-slate-100 dark:bg-slate-700 flex items-center justify-center">
                <span className="text-[10px] text-theme-muted">?</span>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function formatAge(isoDate: string): string {
  const ms = Date.now() - parseUtcAware(isoDate).getTime();
  const mins = Math.floor(ms / 60_000);
  if (mins < 1) return '<1m';
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

/** Whole days until a trashed task is auto-purged (deletedAt + retention window). Clamped at 0. */
function purgeInDays(deletedAt: string): number {
  const purgeAt = parseUtcAware(deletedAt).getTime() + DELETED_RETENTION_DAYS * 86_400_000;
  return Math.max(0, Math.ceil((purgeAt - Date.now()) / 86_400_000));
}

/** Compact minutes → "45m" / "2h" / "2h30" (F12 estimate/time badge). */
function formatMinutes(min: number): string {
  if (min < 60) return `${min}m`;
  const h = Math.floor(min / 60);
  const m = min % 60;
  return m === 0 ? `${h}h` : `${h}h${m}`;
}

