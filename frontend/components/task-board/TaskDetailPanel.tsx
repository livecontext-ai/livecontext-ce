'use client';

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import {
  X, Send, ChevronDown, ChevronRight, Clock, User, Bot,
  FileText, ListChecks, MessageSquare, Activity, Braces,
  Cpu, ArrowLeft, Wrench, CheckCircle2, AlertCircle, XCircle, Plus, Play, Trash2,
  Save, RotateCcw, Square, Timer, Lock,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select, SelectTrigger, SelectValue, SelectContent, SelectItem, SelectGroup, SelectLabel,
} from '@/components/ui/select';
import { AvatarDisplay } from '@/components/agents';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { taskService } from '@/lib/api/orchestrator/task.service';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { useExecutionPagedResource } from '@/hooks/agent/useExecutionPagedResource';
import { LoadOlderSentinel } from '@/components/agent-fleet/LoadOlderSentinel';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentPanelContent, AGENT_CONVERSATION_TAB } from '@/components/app/AgentPanelContent';
import { StatusIcon, PriorityBadge } from './TaskBadges';
import { CreateTaskDialog } from './CreateTaskDialog';
import { TaskExtrasEditor } from './TaskExtrasEditor';
import { cn } from '@/lib/utils';
import type { Task, TaskEvent, TaskStatus, TaskPriority, UpdateTaskInput, TaskPerson, TaskStatusConfig, TaskLabel } from '@/lib/api/orchestrator/task.types';
import type { AgentExecutionRecord, AgentExecutionMessage, AgentExecutionToolCall } from '@/lib/api/orchestrator/agent-metrics.types';
import type { Agent } from '@/lib/api/orchestrator/types';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';

interface TaskDetailPanelProps {
  taskId: string;
  agents: Agent[];
  /** Teammates assignable as a human assignee / reviewer (Jira-style). */
  people?: TaskPerson[];
  /** Board columns (F4) so the status picker offers custom columns, not just the built-ins. */
  statuses?: TaskStatusConfig[];
  /** Label catalog (F2) so the header can resolve this task's label ids to chips. */
  labels?: TaskLabel[];
  /** Pre-stage a status transition (e.g. drag from pending → in_progress column) */
  initialStagedStatus?: TaskStatus;
  /** VIEWER org-role: browse-only panel - every mutating control is hidden or disabled. */
  readOnly?: boolean;
  onClose: () => void;
  onRefresh: () => void;
  onSelectTask?: (taskId: string) => void;
}

type Tab = 'overview' | 'notes' | 'executions' | 'activity' | 'context';

// ─── Staged edits ──────────────────────────────────────────────

interface StagedChanges {
  status?: TaskStatus;
  priority?: TaskPriority;
  agentId?: string;          // agent assignee
  assigneeUserId?: string;   // human assignee (Jira-style, never auto-executes)
  unassign?: boolean;
  reviewerAgentId?: string;
  reviewerUserId?: string;   // human reviewer
  removeReviewer?: boolean;
  maxReviewAttempts?: number;
}

const USER_VALUE_PREFIX = 'user:';

function useTaskStagedEdits(task: Task | null, initialStagedStatus?: TaskStatus) {
  const [stagedChanges, setStagedChanges] = useState<StagedChanges>(() => {
    if (task && initialStagedStatus && initialStagedStatus !== task.status) {
      return { status: initialStagedStatus };
    }
    return {};
  });

  const hasStagedChanges = Object.keys(stagedChanges).length > 0;

  // Effective values (staged wins over server) - safe defaults when task is null.
  // Assignee is an agent XOR a person: staging one clears the other.
  const effectiveStatus = (stagedChanges.status ?? task?.status ?? 'pending') as TaskStatus;
  const effectiveAssigneeAgentId = stagedChanges.unassign ? null
    : stagedChanges.agentId !== undefined ? stagedChanges.agentId
    : stagedChanges.assigneeUserId !== undefined ? null
    : (task?.assignedToAgentId ?? null);
  const effectiveAssigneeUserId = stagedChanges.unassign ? null
    : stagedChanges.assigneeUserId !== undefined ? stagedChanges.assigneeUserId
    : stagedChanges.agentId !== undefined ? null
    : (task?.assignedToUserId ?? null);
  const hasAssignee = !!effectiveAssigneeAgentId || !!effectiveAssigneeUserId;
  const assigneeIsAgent = !!effectiveAssigneeAgentId;
  const effectiveAssigneeValue = effectiveAssigneeAgentId
    ? effectiveAssigneeAgentId
    : effectiveAssigneeUserId ? USER_VALUE_PREFIX + effectiveAssigneeUserId : '__none__';

  const effectivePriority = (stagedChanges.priority ?? task?.priority ?? 'normal') as TaskPriority;

  const effectiveReviewerAgentId = stagedChanges.removeReviewer ? null
    : stagedChanges.reviewerAgentId !== undefined ? stagedChanges.reviewerAgentId
    : stagedChanges.reviewerUserId !== undefined ? null
    : (task?.reviewerAgentId ?? null);
  const effectiveReviewerUserId = stagedChanges.removeReviewer ? null
    : stagedChanges.reviewerUserId !== undefined ? stagedChanges.reviewerUserId
    : stagedChanges.reviewerAgentId !== undefined ? null
    : (task?.reviewerUserId ?? null);
  const reviewerIsAgent = !!effectiveReviewerAgentId;
  const effectiveReviewerValue = effectiveReviewerAgentId
    ? effectiveReviewerAgentId
    : effectiveReviewerUserId ? USER_VALUE_PREFIX + effectiveReviewerUserId : '__none__';

  const effectiveMaxReview = stagedChanges.maxReviewAttempts !== undefined
    ? stagedChanges.maxReviewAttempts : (task?.maxReviewAttempts ?? 3);

  const isCancelStaged = stagedChanges.status === 'cancelled';

  // Stage changes with mutual exclusion + no-op cleanup
  // Uses functional updater (prev =>) to avoid stale stagedChanges closure
  const stageChange = useCallback((fields: Partial<StagedChanges>) => {
    setStagedChanges(prev => {
      const next = { ...prev, ...fields };

      // Mutual exclusion: assignee group (agent XOR person XOR unassign)
      if ('agentId' in fields && fields.agentId !== undefined) { delete next.assigneeUserId; delete next.unassign; }
      if ('assigneeUserId' in fields && fields.assigneeUserId !== undefined) { delete next.agentId; delete next.unassign; }
      if ('unassign' in fields && fields.unassign) { delete next.agentId; delete next.assigneeUserId; }
      // Mutual exclusion: reviewer group (agent XOR person XOR remove)
      if ('reviewerAgentId' in fields && fields.reviewerAgentId !== undefined) { delete next.reviewerUserId; delete next.removeReviewer; }
      if ('reviewerUserId' in fields && fields.reviewerUserId !== undefined) { delete next.reviewerAgentId; delete next.removeReviewer; }
      if ('removeReviewer' in fields && fields.removeReviewer) { delete next.reviewerAgentId; delete next.reviewerUserId; }

      // Remove no-ops (staged === server)
      if (next.status === task?.status) delete next.status;
      if (next.priority === task?.priority) delete next.priority;
      if (next.agentId !== undefined && next.agentId === (task?.assignedToAgentId ?? undefined)) delete next.agentId;
      if (next.assigneeUserId !== undefined && next.assigneeUserId === (task?.assignedToUserId ?? undefined)) delete next.assigneeUserId;
      if (next.unassign && !task?.assignedToAgentId && !task?.assignedToUserId) delete next.unassign;
      if (next.reviewerAgentId !== undefined && next.reviewerAgentId === (task?.reviewerAgentId ?? undefined)) delete next.reviewerAgentId;
      if (next.reviewerUserId !== undefined && next.reviewerUserId === (task?.reviewerUserId ?? undefined)) delete next.reviewerUserId;
      if (next.removeReviewer && !task?.reviewerAgentId && !task?.reviewerUserId) delete next.removeReviewer;
      if (next.maxReviewAttempts === (task?.maxReviewAttempts ?? 3)) delete next.maxReviewAttempts;

      return next;
    });
  }, [task?.status, task?.priority, task?.assignedToAgentId, task?.assignedToUserId, task?.reviewerAgentId, task?.reviewerUserId, task?.maxReviewAttempts]);

  const discardChanges = useCallback(() => setStagedChanges({}), []);

  // Reset staged changes if task identity changes (component reuse)
  const taskIdRef = useRef(task?.id);
  useEffect(() => {
    const previousTaskId = taskIdRef.current;
    taskIdRef.current = task?.id;
    if (previousTaskId && task?.id && task.id !== previousTaskId) {
      setStagedChanges({});
    }
  }, [task?.id]);

  return {
    stagedChanges, hasStagedChanges, isCancelStaged,
    effectiveStatus, effectivePriority,
    effectiveAssigneeAgentId, effectiveAssigneeUserId, effectiveAssigneeValue, hasAssignee, assigneeIsAgent,
    effectiveReviewerAgentId, effectiveReviewerUserId, effectiveReviewerValue, reviewerIsAgent,
    effectiveMaxReview,
    stageChange, discardChanges,
  };
}

export function TaskDetailPanel({ taskId, agents, people = [], statuses = [], labels = [], initialStagedStatus, readOnly = false, onClose, onRefresh, onSelectTask }: TaskDetailPanelProps) {
  const t = useTranslations('taskBoard');
  const { user, avatarUrl: userAvatarUrl } = useAuth();
  const [task, setTask] = useState<Task | null>(null);
  const [events, setEvents] = useState<TaskEvent[]>([]);
  const [children, setChildren] = useState<Task[]>([]);
  const [executions, setExecutions] = useState<AgentExecutionRecord[]>([]);
  const [parentTask, setParentTask] = useState<Task | null>(null);
  const [loading, setLoading] = useState(true);
  const [noteContent, setNoteContent] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const [selectedExecutionId, setSelectedExecutionId] = useState<string | null>(null);

  const sidePanel = useSidePanelSafe();
  const agentMap = useMemo(() => new Map(agents.map(a => [a.id, a])), [agents]);
  const peopleMap = useMemo(() => new Map(people.map(p => [p.userId, p])), [people]);
  const selfPerson = useMemo(() => people.find(p => p.isSelf) || null, [people]);
  // Current user's chosen displayName (NEVER the Keycloak real name) + avatar,
  // used for the "you" placeholders and the note composer.
  const selfName = selfPerson?.displayName || t('detail.you');
  const selfAvatar = selfPerson?.avatarUrl || userAvatarUrl || user?.picture || null;

  // Blockers carry only ids; to render an HONEST "blocked" badge (matching the board's
  // Blocked filter) we count only blockers that are still ACTIVE (non-terminal). The
  // blockers' live statuses come from the lightweight task list. Guarded so a partial
  // taskService mock can't crash the panel; an unresolved blocker stays conservatively
  // counted (incl. a blocker beyond the size:200 page → undefined → counted, never hidden).
  const [blockerStatusById, setBlockerStatusById] = useState<Map<string, string>>(new Map());
  useEffect(() => {
    const tp = taskService.listTasks?.({ size: 200 });
    if (tp) tp.then(r => setBlockerStatusById(new Map((r.tasks || []).map(x => [x.id, x.status])))).catch(() => {});
  }, []);
  // Same decision as TaskBoardPage: a LOADED catalog (length > 0) defines the terminal set
  // (even if it happens to have zero terminal columns); only an absent catalog falls back to
  // the built-in keys. Keeping the two surfaces identical avoids board↔panel disagreement.
  const terminalStatusKeys = useMemo(() => {
    if (statuses && statuses.length > 0) {
      return new Set(
        statuses
          .filter(s => s.category === 'done' || s.category === 'failed' || s.category === 'cancelled' || s.category === 'deleted')
          .map(s => s.key),
      );
    }
    return new Set(['completed', 'failed', 'cancelled', 'deleted']);
  }, [statuses]);
  const activeBlockerCount = useMemo(
    () => (task?.blockedByIds ?? []).filter(id => {
      const st = blockerStatusById.get(id);
      return st == null || !terminalStatusKeys.has(st);
    }).length,
    [task?.blockedByIds, blockerStatusById, terminalStatusKeys],
  );

  // Resolve a human id → display identity, preferring the server-enriched
  // task.users map (canonical displayName), then the loaded teammate list.
  // NEVER falls back to the viewer's own name for someone else's id.
  const resolveUser = useCallback((userId: string | null | undefined): { name: string; avatarUrl: string | null } => {
    if (!userId) return { name: t('detail.someone'), avatarUrl: null };
    if (selfPerson && userId === selfPerson.userId) return { name: selfName, avatarUrl: selfAvatar };
    const ref = task?.users?.[userId];
    const person = peopleMap.get(userId);
    return {
      name: ref?.displayName || person?.displayName || t('detail.someone'),
      avatarUrl: ref?.avatarUrl ?? person?.avatarUrl ?? null,
    };
  }, [task, peopleMap, selfPerson, selfName, selfAvatar, t]);

  const [applying, setApplying] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  // Advanced config (reviewer / max attempts / estimate / blockers / checklist) is
  // collapsed by default; auto-opened (per task) when the task already carries any.
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const loadData = useCallback(async () => {
    try {
      const [taskData, eventsPage, childrenData, execPage] = await Promise.all([
        taskService.getTask(taskId),
        taskService.getTaskEvents(taskId),
        taskService.getTaskChildren(taskId),
        taskService.getTaskExecutions(taskId).catch((err) => {
          console.warn('[TaskDetail] Failed to load executions:', err);
          return { content: [] as AgentExecutionRecord[] } as Awaited<ReturnType<typeof taskService.getTaskExecutions>>;
        }),
      ]);
      setTask(taskData);
      // Events page is DESC (newest first) - reverse to ASC for chronological display.
      setEvents([...eventsPage.content].reverse());
      setChildren(childrenData);
      // Executions stay DESC (newest first) - matches the existing UI ordering.
      setExecutions(execPage.content);
      if (taskData.parentTaskId) {
        taskService.getTask(taskData.parentTaskId).then(setParentTask).catch(() => {});
      } else {
        setParentTask(null);
      }
    } catch {
      // handled by empty state
    } finally {
      setLoading(false);
    }
  }, [taskId]);

  useEffect(() => { loadData(); }, [loadData]);

  // Auto-open the advanced section when a task already has advanced config, so it
  // is not hidden behind the collapse. Keyed on task id (re-eval per opened task).
  useEffect(() => {
    if (!task) return;
    setAdvancedOpen(
      task.estimateMinutes != null || task.timeSpentMinutes != null
      || task.blockedByIds.length > 0 || task.checklist.length > 0
      || !!task.reviewerAgentId || !!task.reviewerUserId,
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task?.id]);

  // ─── Staged edits hook (after task is loaded) ────────────────
  const staged = useTaskStagedEdits(task, initialStagedStatus);
  // Local draft for maxReviewAttempts (controlled input with onBlur commit)
  const [maxReviewDraft, setMaxReviewDraft] = useState<string>(String(staged.effectiveMaxReview ?? 3));
  useEffect(() => { setMaxReviewDraft(String(staged.effectiveMaxReview ?? 3)); }, [staged.effectiveMaxReview]);

  // ─── Action config: contextual button based on staged changes ─
  const isTerminal = task ? ['completed', 'failed', 'cancelled'].includes(task.status) : false;

  const actionConfig = useMemo(() => {
    if (!staged.hasStagedChanges) return null;

    if (staged.stagedChanges.status === 'cancelled') {
      return { label: t('actions.cancelTask'), icon: XCircle, variant: 'destructive' as const, className: '', disabled: false };
    }
    if (staged.stagedChanges.status === 'in_progress') {
      return {
        // An agent assignee executes; a human assignee just moves the card.
        label: staged.assigneeIsAgent ? t('actions.execute') : t('actions.startTask'),
        icon: Play, variant: 'default' as const,
        className: '',
        disabled: !staged.hasAssignee,
      };
    }
    if (staged.stagedChanges.status === 'in_review') {
      return { label: t('actions.sendToReview'), icon: Send, variant: 'default' as const, className: 'bg-orange-600 hover:bg-orange-700 text-white', disabled: !staged.hasAssignee };
    }
    if (staged.stagedChanges.status === 'completed') {
      return { label: t('actions.markComplete'), icon: CheckCircle2, variant: 'default' as const, className: 'bg-emerald-600 hover:bg-emerald-700 text-white', disabled: false };
    }
    if (staged.stagedChanges.status === 'failed') {
      return { label: t('actions.markFailed'), icon: XCircle, variant: 'destructive' as const, className: '', disabled: false };
    }
    if (staged.stagedChanges.status === 'pending') {
      return {
        label: isTerminal ? t('actions.reopenTask') : t('actions.saveChanges'),
        icon: isTerminal ? RotateCcw : Save, variant: 'default' as const,
        className: isTerminal ? 'bg-amber-500 hover:bg-amber-600 text-white' : '',
        disabled: false,
      };
    }
    // No status change - field-only edits
    return { label: t('actions.saveChanges'), icon: Save, variant: 'default' as const, className: '', disabled: false };
  }, [staged.stagedChanges, staged.hasStagedChanges, staged.hasAssignee, staged.assigneeIsAgent, isTerminal, task?.status, t]);

  // ─── Apply staged changes ──────────────────────────────────────
  const applyTaskChanges = useCallback(async (changes: StagedChanges) => {
    if (applying || !task) return;
    setApplying(true);
    setActionError(null);

    try {
      // Cancel path - cascading cancel for active tasks, status PATCH for terminal
      // ones (the cascade endpoint no-ops on completed/failed).
      if (changes.status === 'cancelled') {
        await taskService.moveToCancelled({ id: taskId, status: task.status });
        onRefresh();
        onClose();
        return;
      }

      // Normal path - single batched PATCH
      const patch = { ...changes };
      if (patch.status === 'in_progress' && task.status === 'in_progress') {
        delete patch.status;
      }
      const updated = Object.keys(patch).length > 0
        ? await taskService.updateTask(taskId, patch as UpdateTaskInput)
        : task;

      // If transitioning to in_progress, open agent conversation panel
      if (changes.status === 'in_progress' && updated.assignedToAgentId && sidePanel) {
        const agent = agentMap.get(updated.assignedToAgentId);
        if (agent) {
          sidePanel.openTab({
            id: `agent-${agent.id}`,
            label: agent.name,
            icon: <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-4 !h-4" />,
            content: <AgentPanelContent agentId={agent.id} initialTab={AGENT_CONVERSATION_TAB} />,
            preferredWidth: 0.35,
          });
        }
        onRefresh();
        onClose();
        return;
      }

      // Non-terminal transitions - stay on modal, clear staged, refresh
      staged.discardChanges();
      await loadData();
      onRefresh();
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || t('detail.updateFailed');
      setActionError(msg);
      // If cancel failed, lift the lockout so user can retry or change mind
      if (changes.status === 'cancelled') {
        staged.discardChanges();
      }
      loadData();
    } finally {
      setApplying(false);
    }
  }, [staged, applying, task, taskId, sidePanel, agentMap, onRefresh, onClose, loadData, t]);

  const applyChanges = useCallback(async () => {
    if (!staged.hasStagedChanges) return;
    await applyTaskChanges(staged.stagedChanges);
  }, [applyTaskChanges, staged.hasStagedChanges, staged.stagedChanges]);

  const handleDefaultExecute = useCallback(async () => {
    if (!task?.assignedToAgentId || !sidePanel) return;
    await applyTaskChanges({ status: 'in_progress' });
  }, [applyTaskChanges, task?.assignedToAgentId, sidePanel]);

  const handleDefaultCancel = useCallback(async () => {
    await applyTaskChanges({ status: 'cancelled' });
  }, [applyTaskChanges]);

  // ─── Guarded close (shared by Escape key + backdrop click) ───
  const guardedClose = useCallback(() => {
    if (staged.hasStagedChanges) {
      if (window.confirm(t('detail.discardConfirm'))) {
        staged.discardChanges();
        onClose();
      }
    } else {
      onClose();
    }
  }, [onClose, staged.hasStagedChanges, staged.discardChanges, t]);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') guardedClose();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [guardedClose]);

  // ─── Immediate handlers (title, instructions, notes, approve, reject, delete) ──
  const handleUpdate = async (fields: Record<string, unknown>) => {
    try {
      await taskService.updateTask(taskId, fields as Parameters<typeof taskService.updateTask>[1]);
      loadData();
      onRefresh();
    } catch {
      loadData();
    }
  };

  const handleAddNote = async () => {
    if (!noteContent.trim() || submitting) return;
    setSubmitting(true);
    try {
      await taskService.addNote(taskId, noteContent.trim());
      setNoteContent('');
      loadData();
      onRefresh();
    } catch {
      loadData();
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async () => {
    if (!task || deleting) return;
    if (!window.confirm(t('detail.confirmDelete'))) return;
    setDeleting(true);
    setActionError(null);
    try {
      await taskService.hardDeleteTask(taskId);
      onRefresh();
      onClose();
    } catch (err) {
      console.error('[TaskDetailPanel] Delete failed:', err);
      setActionError(t('detail.deleteFailed'));
      setDeleting(false);
    }
  };

  const handleApprove = async () => {
    if (!task) return;
    setActionError(null);
    try {
      await taskService.approveTask(taskId);
      onRefresh();
      onClose();
    } catch (err) {
      console.error('[TaskDetailPanel] Approve failed:', err);
      setActionError(t('detail.approveFailed'));
      loadData();
    }
  };

  const handleRejectReview = async () => {
    if (!task) return;
    setActionError(null);
    try {
      await taskService.rejectReviewTask(taskId);
      onRefresh();
      onClose();
    } catch (err) {
      console.error('[TaskDetailPanel] Reject review failed:', err);
      setActionError(t('detail.rejectFailed'));
      loadData();
    }
  };

  const handleStopAgent = async (role: 'assignee' | 'reviewer') => {
    if (!task || stopping) return;
    setStopping(true);
    setActionError(null);
    try {
      await taskService.stopAgentExecution(taskId, role);
      await loadData();
      onRefresh();
    } catch (err: any) {
      console.error('[TaskDetailPanel] Stop agent failed:', err);
      setActionError(err?.response?.data?.error || t('detail.stopFailed'));
      loadData();
    } finally {
      setStopping(false);
    }
  };

  if (loading) {
    return (
      <ModalShell onClose={onClose}>
        <div className="flex items-center justify-center py-20 text-theme-secondary text-sm">{t('detail.loading')}</div>
      </ModalShell>
    );
  }

  if (!task) {
    return (
      <ModalShell onClose={onClose}>
        <div className="flex items-center justify-center py-20 text-theme-secondary text-sm">{t('detail.notFound')}</div>
      </ModalShell>
    );
  }

  // Whether an agent execution is actively running
  const hasActiveAssigneeExecution = task.status === 'in_progress' && !!task.assigneeExecutionId;
  const hasActiveReviewerExecution = task.status === 'in_review' && !!task.reviewerExecutionId;

  // Whether ANY of the five contextual action zones renders, so the pinned action
  // bar (and its top border) only appears when there is actually a button to show
  // (e.g. a completed task with no staged edits has none). Mirrors each zone's guard.
  const hasActions =
    ((hasActiveAssigneeExecution || hasActiveReviewerExecution) && !staged.hasStagedChanges) // Zone 0
    || !!actionConfig                                                                          // Zone 1
    || (task.status === 'in_review' && !staged.stagedChanges.status)                           // Zone 2
    || (task.status === 'cancelled' && !staged.hasStagedChanges)                               // Zone 3
    || (!staged.hasStagedChanges && task.status !== 'in_review' && !isTerminal                 // Zone 4
        && !hasActiveAssigneeExecution && !hasActiveReviewerExecution);

  // Status dropdown disabled rules
  const getStatusDisabled = (s: TaskStatus): boolean => {
    if (s === 'in_review' && !staged.hasAssignee) return true;
    // Allow in_progress → pending and in_review → pending ONLY when no execution is active
    // (agent was stopped or never started). When active, user must use Stop button first.
    if (task.status === 'in_progress' && s === 'pending') return hasActiveAssigneeExecution;
    if (task.status === 'in_review' && s === 'pending') return hasActiveReviewerExecution;
    // Nonsensical: completed/failed → in_review (nothing to review)
    if ((task.status === 'completed' || task.status === 'failed') && s === 'in_review') return true;
    // Cancelled is fully terminal (cascade semantics - children also cancelled)
    if (task.status === 'cancelled') return s !== 'cancelled';
    return false;
  };

  const tabs: { key: Tab; label: string; icon: typeof FileText; count?: number }[] = [
    { key: 'overview', label: t('detail.instructions'), icon: FileText },
    { key: 'notes', label: t('detail.notes'), icon: MessageSquare, count: task.notes.length },
    { key: 'executions', label: t('detail.executions'), icon: Cpu, count: executions.length },
    { key: 'activity', label: t('detail.events'), icon: Activity, count: events.length },
    ...(task.taskContext && Object.keys(task.taskContext).length > 0
      ? [{ key: 'context' as Tab, label: t('detail.context'), icon: Braces }]
      : []),
  ];

  return (
    <ModalShell onClose={guardedClose}>
      {/* ── Header ─────────────────────────────────── */}
      <div className="flex-shrink-0 px-6 pt-5 pb-4 border-b border-theme">
        <div className="flex items-start gap-3">
          <StatusIcon status={task.status} className="mt-1" />
          <div className="flex-1 min-w-0">
            <EditableText
              value={task.title}
              disabled={readOnly}
              onSave={(v) => handleUpdate({ title: v })}
              className="text-base font-semibold text-theme-primary leading-snug"
            />
            {task.parentTaskId && (
              <div className="text-xs text-theme-secondary mt-1">
                ↳ {t('detail.parentTask')}: <span
                  className={`text-[var(--accent-primary)] font-medium ${onSelectTask ? 'cursor-pointer hover:underline' : ''}`}
                  onClick={() => onSelectTask?.(task.parentTaskId!)}
                >{parentTask?.title || task.parentTaskId.slice(0, 8) + '…'}</span>
              </div>
            )}
            {/* At-a-glance card info (labels / checklist / estimate / blockers),
                mirroring the priority badge - editing happens in the right rail. */}
            <HeaderMetaSummary task={task} labels={labels} activeBlockerCount={activeBlockerCount} t={t} />
          </div>
          <PriorityBadge priority={task.priority} />
          <Button variant="ghost" size="icon" onClick={guardedClose} className="h-8 w-8 rounded-full -mt-1 -mr-2 flex-shrink-0">
            <X className="h-4 w-4" />
          </Button>
        </div>
      </div>

      {/* Error banner - immediately visible */}
      {task.errorMessage && (
        <div className="flex-shrink-0 flex items-center gap-2 px-6 py-2 bg-red-50 dark:bg-red-900/20 border-b border-red-200 dark:border-red-800/30 text-sm text-red-600 dark:text-red-400">
          <AlertCircle className="h-3.5 w-3.5 flex-shrink-0" />
          <span className="truncate">{task.errorMessage}</span>
        </div>
      )}

      {/* ── Body: two-column on desktop, stacked on mobile ─────────────── */}
      <div className="flex flex-col md:flex-row flex-1 min-h-0">
        {/* Left: main content */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* Tabs */}
          <div className="flex-shrink-0 flex border-b border-theme bg-theme-secondary/30">
            {tabs.map(tab => {
              const Icon = tab.icon;
              return (
                <button
                  key={tab.key}
                  onClick={() => { setActiveTab(tab.key); setSelectedExecutionId(null); }}
                  className={cn(
                    'flex items-center gap-1.5 px-4 py-2.5 text-xs font-medium transition-colors border-b-2 -mb-px',
                    activeTab === tab.key
                      ? 'border-[var(--accent-primary)] text-[var(--accent-primary)]'
                      : 'border-transparent text-theme-muted hover:text-theme-primary'
                  )}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {tab.label}
                  {tab.count != null && tab.count > 0 && (
                    <span className="text-xs bg-theme-tertiary text-theme-muted rounded-full px-1.5 py-0.5 min-w-[18px] text-center tabular-nums">
                      {tab.count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>

          {/* Tab content */}
          <div className="flex-1 overflow-y-auto p-5">
            {activeTab === 'overview' && (
              <OverviewTab task={task} childTasks={children} agents={agents} people={people} agentMap={agentMap} isTerminal={isTerminal} readOnly={readOnly} onUpdate={handleUpdate} onRefresh={() => { loadData(); onRefresh(); }} onSelectTask={onSelectTask} t={t} />
            )}
            {activeTab === 'notes' && (
              <NotesTab
                task={task}
                agentMap={agentMap}
                resolveUser={resolveUser}
                selfName={selfName}
                noteContent={noteContent}
                setNoteContent={setNoteContent}
                submitting={submitting}
                readOnly={readOnly}
                onAddNote={handleAddNote}
                t={t}
              />
            )}
            {activeTab === 'executions' && (
              <ExecutionsTab
                executions={executions}
                agentMap={agentMap}
                selectedExecutionId={selectedExecutionId}
                onSelectExecution={setSelectedExecutionId}
                t={t}
              />
            )}
            {activeTab === 'activity' && (
              <ActivityTab events={events} agentMap={agentMap} resolveUser={resolveUser} t={t} />
            )}
            {activeTab === 'context' && task.taskContext && (
              <pre className="text-xs text-theme-secondary bg-theme-secondary rounded-xl p-4 overflow-x-auto">
                {JSON.stringify(task.taskContext, null, 2)}
              </pre>
            )}
          </div>
        </div>

        {/* Right: metadata + actions. Desktop = right sidebar; mobile = a full-width
            panel BELOW the content (capped + scrollable) so status / priority / assignee /
            reviewer and the apply/execute actions stay editable on phones. */}
        <div data-testid="task-meta-panel" className="flex-shrink-0 w-full md:w-56 max-h-[45vh] md:max-h-none border-t md:border-t-0 md:border-l border-theme bg-theme-secondary/20 flex flex-col">
          {/* Scrollable settings + card fields; the contextual action bar below stays pinned. */}
          <div data-testid="task-meta-scroll" className="flex-1 overflow-y-auto p-4 space-y-4 min-h-0">
          {/* Status */}
          <MetaField label={t('detail.status')} dirty={'status' in staged.stagedChanges}>
            <Select
              value={staged.effectiveStatus}
              onValueChange={(v) => staged.stageChange({ status: v as TaskStatus })}
            >
              <SelectTrigger disabled={readOnly} className="min-h-0 h-7 text-xs rounded-md w-full">
                <div className="flex items-center gap-1.5">
                  <StatusIcon status={staged.effectiveStatus} />
                  <span className="capitalize">
                    {statuses.find(x => x.key === staged.effectiveStatus && !x.isSystem)?.label
                      ?? t(`status.${staged.effectiveStatus}`)}
                  </span>
                </div>
              </SelectTrigger>
              <SelectContent>
                {/* F4: offer the board's columns (incl. custom) - not just the built-ins. Skip the
                    Deleted category (a card is trashed via the delete action, not the status picker). */}
                {(statuses.length
                  ? statuses.filter(s => s.category !== 'deleted').map(s => ({ key: s.key, isSystem: s.isSystem, label: s.label }))
                  : (['pending', 'in_progress', 'in_review', 'completed', 'failed', 'cancelled']).map(k => ({ key: k, isSystem: true, label: '' }))
                ).map(s => (
                  <SelectItem key={s.key} value={s.key} disabled={getStatusDisabled(s.key as TaskStatus)}>
                    <span className="flex items-center gap-1.5">
                      <StatusIcon status={s.key as TaskStatus} />
                      <span className="capitalize">{s.isSystem ? t(`status.${s.key}`) : s.label}</span>
                    </span>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </MetaField>

          {/* Priority */}
          <div className={cn(staged.isCancelStaged && 'opacity-50 pointer-events-none')}>
            <MetaField label={t('detail.priority')} dirty={'priority' in staged.stagedChanges}>
              <Select
                value={staged.effectivePriority}
                onValueChange={(v) => staged.stageChange({ priority: v as TaskPriority })}
              >
                <SelectTrigger disabled={readOnly} className="min-h-0 h-7 text-xs rounded-md w-full">
                  <div className="flex items-center gap-1.5">
                    <PriorityBadge priority={staged.effectivePriority} />
                  </div>
                </SelectTrigger>
                <SelectContent>
                  {(['urgent', 'high', 'normal', 'low'] as const).map(p => (
                    <SelectItem key={p} value={p}>
                      <PriorityBadge priority={p} />
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </MetaField>
          </div>

          {/* Assignee - an agent (auto-executes) or a teammate (Jira-style, manual) */}
          <div className={cn(staged.isCancelStaged && 'opacity-50 pointer-events-none')}>
            <MetaField label={t('detail.assignee')} dirty={'agentId' in staged.stagedChanges || 'assigneeUserId' in staged.stagedChanges || 'unassign' in staged.stagedChanges}>
              <div className={cn(
                'rounded-md transition-all',
                staged.stagedChanges.status === 'in_progress' && !staged.hasAssignee && 'ring-2 ring-amber-400 dark:ring-amber-500 animate-pulse',
              )}>
                <Select
                  value={staged.effectiveAssigneeValue}
                  onValueChange={(v) => {
                    if (v === '__none__') staged.stageChange({ unassign: true });
                    else if (v.startsWith(USER_VALUE_PREFIX)) staged.stageChange({ assigneeUserId: v.slice(USER_VALUE_PREFIX.length) });
                    else staged.stageChange({ agentId: v });
                  }}
                >
                  <SelectTrigger disabled={readOnly} className={cn(
                    'min-h-0 h-7 text-xs rounded-md w-full',
                    staged.stagedChanges.status === 'in_progress' && !staged.hasAssignee && 'border-amber-400 dark:border-amber-500 text-amber-600 dark:text-amber-400',
                  )}>
                    <SelectValue placeholder={t('filters.unassigned')} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__none__">{t('actions.unassign')}</SelectItem>
                    {agents.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.agents')}</SelectLabel>
                        {agents.map(a => (
                          <SelectItem key={a.id} value={a.id}>
                            <span className="flex items-center gap-1.5">
                              <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                              {a.name}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                    {people.length > 0 && (
                      <SelectGroup>
                        <SelectLabel>{t('assignGroups.people')}</SelectLabel>
                        {people.map(p => (
                          <SelectItem key={p.userId} value={USER_VALUE_PREFIX + p.userId}>
                            <span className="flex items-center gap-1.5">
                              <PublisherAvatar userId={p.userId} name={p.displayName} size={16} variant="neutral" />
                              {p.isSelf ? `${p.displayName} (${t('detail.you')})` : p.displayName}
                            </span>
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    )}
                  </SelectContent>
                </Select>
              </div>
              {staged.stagedChanges.status === 'in_progress' && !staged.hasAssignee && (
                <p className="text-xs text-amber-600 dark:text-amber-400 mt-1">{t('detail.assigneeRequired')}</p>
              )}
              {staged.effectiveAssigneeUserId && (
                <p className="text-xs text-theme-muted mt-1">{t('actions.humanAssigneeHint')}</p>
              )}
            </MetaField>
          </div>

          {/* Labels (core) - kept with the common settings above. Write-side editor, so a
              VIEWER does not get it (the header summary already shows labels read-only). */}
          {!readOnly && (
            <TaskExtrasEditor task={task} only="labels" onSaved={() => { loadData(); onRefresh(); }} />
          )}

          {/* Advanced config (reviewer / max attempts / estimate / blockers / checklist),
              collapsed by default unless the task already carries any of it. */}
          <button
            type="button"
            data-testid="task-advanced-toggle"
            onClick={() => setAdvancedOpen(o => !o)}
            aria-expanded={advancedOpen}
            className="flex items-center gap-1 w-full text-xs font-medium text-theme-muted uppercase tracking-wide hover:text-theme-primary transition-colors"
          >
            {advancedOpen ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            {t('detail.advanced')}
          </button>

          {advancedOpen && (
          <div className="space-y-4">
          {/* Reviewer - an agent or a teammate */}
          <div className={cn(staged.isCancelStaged && 'opacity-50 pointer-events-none')}>
            <MetaField label={t('actions.reviewer')} dirty={'reviewerAgentId' in staged.stagedChanges || 'reviewerUserId' in staged.stagedChanges || 'removeReviewer' in staged.stagedChanges}>
              <Select
                value={staged.effectiveReviewerValue}
                onValueChange={(v) => {
                  if (v === '__none__') staged.stageChange({ removeReviewer: true });
                  else if (v.startsWith(USER_VALUE_PREFIX)) staged.stageChange({ reviewerUserId: v.slice(USER_VALUE_PREFIX.length) });
                  else staged.stageChange({ reviewerAgentId: v });
                }}
              >
                <SelectTrigger disabled={readOnly} className="min-h-0 h-7 text-xs rounded-md w-full">
                  <SelectValue placeholder={t('actions.noReviewer')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="__none__">{t('actions.noReviewer')}</SelectItem>
                  {agents.filter(a => a.id !== staged.effectiveAssigneeAgentId).length > 0 && (
                    <SelectGroup>
                      <SelectLabel>{t('assignGroups.agents')}</SelectLabel>
                      {agents.filter(a => a.id !== staged.effectiveAssigneeAgentId).map(a => (
                        <SelectItem key={a.id} value={a.id}>
                          <span className="flex items-center gap-1.5">
                            <AvatarDisplay avatarUrl={a.avatarUrl} name={a.name} size="sm" className="!w-4 !h-4" />
                            {a.name}
                          </span>
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  )}
                  {people.filter(p => p.userId !== staged.effectiveAssigneeUserId).length > 0 && (
                    <SelectGroup>
                      <SelectLabel>{t('assignGroups.people')}</SelectLabel>
                      {people.filter(p => p.userId !== staged.effectiveAssigneeUserId).map(p => (
                        <SelectItem key={p.userId} value={USER_VALUE_PREFIX + p.userId}>
                          <span className="flex items-center gap-1.5">
                            <PublisherAvatar userId={p.userId} name={p.displayName} size={16} variant="neutral" />
                            {p.isSelf ? `${p.displayName} (${t('detail.you')})` : p.displayName}
                          </span>
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  )}
                </SelectContent>
              </Select>
            </MetaField>
          </div>

          {/* Max review attempts - only meaningful for an AGENT reviewer (no auto-retry for a person) */}
          {staged.reviewerIsAgent && (
            <div className={cn(staged.isCancelStaged && 'opacity-50 pointer-events-none')}>
              <MetaField label={t('review.maxAttempts')} dirty={'maxReviewAttempts' in staged.stagedChanges}>
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    min={1}
                    max={20}
                    step={1}
                    value={maxReviewDraft}
                    disabled={isTerminal || staged.isCancelStaged || readOnly}
                    onChange={(e) => setMaxReviewDraft(e.target.value)}
                    onBlur={() => {
                      const n = parseInt(maxReviewDraft, 10);
                      if (Number.isFinite(n) && n >= 1 && n <= 20) {
                        staged.stageChange({ maxReviewAttempts: n });
                      } else {
                        setMaxReviewDraft(String(staged.effectiveMaxReview ?? 3));
                      }
                    }}
                    className="h-7 text-xs w-20"
                  />
                  <span className="text-xs text-theme-muted tabular-nums">
                    {t('detail.attemptsUsed', { count: task.reviewAttemptCount ?? 0 })}
                  </span>
                </div>
              </MetaField>
            </div>
          )}

          {/* Card fields (estimate / blockers / checklist) - advanced. Write-side editor,
              hidden for a VIEWER (the header summary shows these read-only). */}
          {!readOnly && (
            <TaskExtrasEditor task={task} only="rest" onSaved={() => { loadData(); onRefresh(); }} />
          )}
          </div>
          )}

          {/* Created by - resolved displayName of the ACTUAL creator (never the viewer's name) */}
          <MetaField label={t('detail.createdBy')}>
            {task.createdByAgentId ? (
              <AgentBadge agent={agentMap.get(task.createdByAgentId)} />
            ) : (() => {
              const d = resolveUser(task.createdByUserId);
              return <UserBadge userId={task.createdByUserId} name={d.name} />;
            })()}
          </MetaField>

          {/* Assigned-to (human) - shown so a teammate assignee is visible at a glance */}
          {task.assignedToUserId && (
            <MetaField label={t('detail.assignedTo')}>
              {(() => { const d = resolveUser(task.assignedToUserId); return <UserBadge userId={task.assignedToUserId} name={d.name} />; })()}
            </MetaField>
          )}
          {task.reviewerUserId && (
            <MetaField label={t('actions.reviewer')}>
              {(() => { const d = resolveUser(task.reviewerUserId); return <UserBadge userId={task.reviewerUserId} name={d.name} />; })()}
            </MetaField>
          )}

          {/* Timestamps */}
          <MetaField label={t('detail.created')}>
            <span className="text-xs text-theme-secondary tabular-nums">{formatDate(task.createdAt)}</span>
          </MetaField>
          {task.startedAt && (
            <MetaField label={t('detail.started')}>
              <span className="text-xs text-theme-secondary tabular-nums">{formatDate(task.startedAt)}</span>
            </MetaField>
          )}
          {task.completedAt && (
            <MetaField label={t('detail.completed')}>
              <span className="text-xs text-theme-secondary tabular-nums">{formatDate(task.completedAt)}</span>
            </MetaField>
          )}
          {task.dueBy && (
            <MetaField label={t('detail.dueBy')}>
              <span className="text-xs text-theme-secondary tabular-nums">{formatDate(task.dueBy)}</span>
            </MetaField>
          )}
          </div>{/* end scrollable settings + card fields */}

          {/* ── Action Area - pinned to the bottom of the rail so the contextual
              buttons stay visible however far the settings scroll. Every zone mutates
              (stop/apply/approve/reject/delete/execute/cancel), so a VIEWER gets none. ── */}
          {hasActions && !readOnly && (
          <div data-testid="task-action-bar" className="flex-shrink-0 border-t border-theme p-3 space-y-3 bg-theme-secondary/40">

          {/* Zone 0: Stop agent - shown when an agent is actively running */}
          {(hasActiveAssigneeExecution || hasActiveReviewerExecution) && !staged.hasStagedChanges && (
            <div className="space-y-1.5">
              {actionError && <p className="text-xs text-red-500">{actionError}</p>}
              <Button
                variant="outline"
                size="sm"
                disabled={stopping}
                onClick={() => handleStopAgent(hasActiveAssigneeExecution ? 'assignee' : 'reviewer')}
                className="w-full text-xs h-7 border-red-300 dark:border-red-700 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20"
              >
                <Square className="h-3 w-3 mr-1" />
                {stopping
                  ? t('actions.stopping')
                  : hasActiveAssigneeExecution
                    ? t('actions.stopAgent')
                    : t('actions.stopReviewer')}
              </Button>
            </div>
          )}

          {/* Zone 1: Staged changes → contextual button + discard */}
          {actionConfig && (
            <div className="space-y-1.5">
              {actionError && <p className="text-xs text-red-500">{actionError}</p>}
              <p className="text-xs text-amber-600 dark:text-amber-400 flex items-center gap-1">
                <AlertCircle className="h-3 w-3" />
                {t('detail.unsavedChanges')}
              </p>
              <div className="relative overflow-hidden rounded-full">
                {/* Shimmer overlay for pending-execute flow (agent assignee only) */}
                {staged.stagedChanges.status === 'in_progress' && staged.assigneeIsAgent && !applying && (
                  <div
                    className="absolute inset-0 pointer-events-none z-10 rounded-full"
                    style={{
                      background: 'linear-gradient(90deg, transparent 0%, var(--shimmer-color) 25%, var(--shimmer-color-strong) 50%, var(--shimmer-color) 75%, transparent 100%)',
                      backgroundSize: '200% 100%',
                      animation: 'shimmer-scan 2s ease-in-out infinite',
                    }}
                  />
                )}
                <Button
                  size="sm"
                  variant={actionConfig.variant}
                  disabled={actionConfig.disabled || applying}
                  onClick={applyChanges}
                  className={cn('w-full text-xs h-7 relative', actionConfig.className)}
                >
                  {applying
                    ? <Cpu className="h-3 w-3 mr-1 animate-spin" />
                    : <actionConfig.icon className="h-3 w-3 mr-1" />}
                  {applying ? t('actions.executing') : actionConfig.label}
                </Button>
              </div>
              <button
                onClick={staged.discardChanges}
                disabled={applying}
                className="w-full text-xs text-theme-muted hover:text-theme-primary py-1 transition-colors disabled:opacity-50"
              >
                {t('actions.discardChanges')}
              </button>
            </div>
          )}

          {/* Zone 2: In review + no staged status → approve/reject (immediate) */}
          {task.status === 'in_review' && !staged.stagedChanges.status && (
            <div className="space-y-1.5">
              {actionError && <p className="text-xs text-red-500">{actionError}</p>}
              <Button size="sm" onClick={handleApprove} className="w-full text-xs h-7 bg-emerald-600 hover:bg-emerald-700 text-white">
                {t('actions.approve')}
              </Button>
              <Button variant="outline" size="sm" onClick={handleRejectReview} className="w-full text-xs h-7">
                {t('actions.rejectReview')}
              </Button>
            </div>
          )}

          {/* Zone 3: Cancelled + no staged changes → delete button */}
          {task.status === 'cancelled' && !staged.hasStagedChanges && (
            <div className="space-y-1.5">
              {actionError && <p className="text-xs text-red-500">{actionError}</p>}
              <Button
                variant="destructive"
                size="sm"
                onClick={handleDelete}
                disabled={deleting}
                className="w-full text-xs h-7"
              >
                <Trash2 className="h-3 w-3 mr-1" />
                {deleting ? t('actions.deleting') : t('actions.delete')}
              </Button>
            </div>
          )}

          {/* Zone 4: No staged changes + not in review + not terminal -> default execute/cancel actions */}
          {!staged.hasStagedChanges && task.status !== 'in_review' && !isTerminal && !hasActiveAssigneeExecution && !hasActiveReviewerExecution && (
            <div className="space-y-1.5">
              {actionError && <p className="text-xs text-red-500">{actionError}</p>}
              <div className="relative overflow-hidden rounded-full">
                {task.status === 'pending' && task.assignedToAgentId && !applying && (
                  <div
                    className="absolute inset-0 pointer-events-none z-10 rounded-full"
                    style={{
                      background: 'linear-gradient(90deg, transparent 0%, var(--shimmer-color) 25%, var(--shimmer-color-strong) 50%, var(--shimmer-color) 75%, transparent 100%)',
                      backgroundSize: '200% 100%',
                      animation: 'shimmer-scan 2s ease-in-out infinite',
                    }}
                  />
                )}
                <Button
                  size="sm"
                  disabled={!task.assignedToAgentId || !sidePanel || applying}
                  onClick={handleDefaultExecute}
                  className={cn(
                    'w-full text-xs h-7 relative',
                    task.status === 'pending' && task.assignedToAgentId && !applying && 'ring-2 ring-[var(--accent-primary)] shadow-md',
                  )}
                >
                  {applying ? <Cpu className="h-3 w-3 mr-1 animate-spin" /> : <Play className="h-3 w-3 mr-1" />}
                  {applying ? t('actions.executing') : task.assignedToAgentId ? t('actions.execute') : t('actions.startTask')}
                </Button>
              </div>
              <Button
                variant="destructive"
                size="sm"
                onClick={handleDefaultCancel}
                disabled={applying}
                className="w-full text-xs h-7"
              >
                {t('actions.cancel')}
              </Button>
            </div>
          )}
          </div>
          )}
        </div>
      </div>
    </ModalShell>
  );
}

// ─── Inline editable fields ────────────────────────────────────

function EditableText({ value, disabled, onSave, className }: {
  value: string;
  disabled: boolean;
  onSave: (v: string) => void;
  className?: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);

  useEffect(() => { setDraft(value); }, [value]);

  if (disabled || !editing) {
    return (
      <p
        className={cn(className, !disabled && 'cursor-text hover:bg-theme-secondary/40 rounded px-1 -mx-1 transition-colors')}
        onClick={() => !disabled && setEditing(true)}
      >
        {value}
      </p>
    );
  }

  const commit = () => {
    setEditing(false);
    const trimmed = draft.trim();
    if (trimmed && trimmed !== value) onSave(trimmed);
    else setDraft(value);
  };

  return (
    <input
      autoFocus
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => { if (e.key === 'Enter') commit(); if (e.key === 'Escape') { setDraft(value); setEditing(false); } }}
      className={cn(className, 'w-full bg-transparent outline-none ring-1 ring-[var(--accent-primary)] rounded px-1 -mx-1')}
    />
  );
}

function EditableTextarea({ value, disabled, onSave, placeholder, className }: {
  value: string;
  disabled: boolean;
  onSave: (v: string) => void;
  placeholder?: string;
  className?: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);

  useEffect(() => { setDraft(value); }, [value]);

  if (disabled || !editing) {
    return (
      <div
        className={cn(className, 'whitespace-pre-wrap min-h-[3rem]', !disabled && 'cursor-text hover:ring-1 hover:ring-slate-300 dark:hover:ring-slate-600 transition-all')}
        onClick={() => !disabled && setEditing(true)}
      >
        {value || <span className="text-theme-muted italic">{placeholder}</span>}
      </div>
    );
  }

  const commit = () => {
    setEditing(false);
    if (draft !== value) onSave(draft);
  };

  return (
    <textarea
      autoFocus
      value={draft}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => { if (e.key === 'Escape') { setDraft(value); setEditing(false); } }}
      rows={6}
      className={cn(className, 'w-full bg-transparent outline-none ring-1 ring-[var(--accent-primary)] resize-none max-h-48 overflow-y-auto')}
    />
  );
}

// ─── Modal shell ────────────────────────────────────────────────

function ModalShell({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  return (
    <>
      <div className="fixed inset-0 z-40 bg-black/30 backdrop-blur-sm" onClick={onClose} />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-3 sm:p-6 pointer-events-none">
        <div
          className="pointer-events-auto w-full max-w-4xl bg-[var(--bg-primary)] rounded-2xl shadow-2xl border border-theme flex flex-col animate-in fade-in zoom-in-95 duration-200"
          style={{ height: 'min(720px, calc(100vh - 24px))' }}
          onClick={(e) => e.stopPropagation()}
        >
          {children}
        </div>
      </div>
    </>
  );
}

// ─── Sidebar helpers ────────────────────────────────────────────

function MetaField({ label, dirty, children }: { label: string; dirty?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <div className="flex items-center gap-1.5">
        <span className="text-xs font-medium text-theme-muted uppercase tracking-wide">{label}</span>
        {dirty && (
          <span className="w-1.5 h-1.5 rounded-full bg-amber-500 flex-shrink-0" role="status" aria-label={`${label} modified`}>
            <span className="sr-only">{label} modified</span>
          </span>
        )}
      </div>
      <div className="mt-1">{children}</div>
    </div>
  );
}

/**
 * At-a-glance, read-only card info in the header (labels, checklist progress,
 * estimate/time, blocker count), in the same badge idiom as the priority badge.
 * Editing happens in the right settings rail; this is a summary only and renders
 * nothing when the card has none of these fields.
 */
function HeaderMetaSummary({ task, labels, activeBlockerCount, t }: {
  task: Task;
  labels: TaskLabel[];
  activeBlockerCount: number;
  t: ReturnType<typeof useTranslations>;
}) {
  const labelsById = useMemo(() => new Map(labels.map(l => [l.id, l])), [labels]);
  const taskLabels = task.labelIds.map(id => labelsById.get(id)).filter((l): l is TaskLabel => !!l);
  const checklistTotal = task.checklist.length;
  const checklistDone = task.checklist.filter(i => i.done).length;
  // Count only blockers that are still active (computed by the parent against live blocker
  // statuses) - a completed/removed blocker no longer blocks, matching the board badge + filter.
  const blockers = activeBlockerCount;
  const hasEstimate = task.estimateMinutes != null || task.timeSpentMinutes != null;

  if (taskLabels.length === 0 && checklistTotal === 0 && blockers === 0 && !hasEstimate) return null;

  const estLabel = task.estimateMinutes != null && task.timeSpentMinutes != null
    ? `${task.timeSpentMinutes}/${task.estimateMinutes}m`
    : task.estimateMinutes != null ? `${task.estimateMinutes}m`
    : `${task.timeSpentMinutes}m`;
  const pill = 'inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs bg-theme-tertiary text-theme-secondary';

  return (
    <div data-testid="task-header-summary" className="flex flex-wrap items-center gap-1.5 mt-2">
      {taskLabels.map(l => (
        <span key={l.id} className={pill}>
          {l.color && /^#[0-9a-fA-F]{3,8}$/.test(l.color) && (
            <span className="h-1.5 w-1.5 rounded-full" style={{ backgroundColor: l.color }} />
          )}
          {l.name}
        </span>
      ))}
      {checklistTotal > 0 && (
        <span className={pill} title={t('detail.checklist')}>
          <ListChecks className="h-3 w-3" /> <span className="tabular-nums">{checklistDone}/{checklistTotal}</span>
        </span>
      )}
      {hasEstimate && (
        <span className={pill} title={t('detail.estimate')}>
          <Timer className="h-3 w-3" /> <span className="tabular-nums">{estLabel}</span>
        </span>
      )}
      {blockers > 0 && (
        <span className={cn(pill, 'text-amber-600 dark:text-amber-400')} title={t('detail.blockedBy')}>
          <Lock className="h-3 w-3" /> <span className="tabular-nums">{blockers}</span>
        </span>
      )}
    </div>
  );
}

function AgentBadge({ agent }: { agent?: Agent }) {
  const t = useTranslations('taskBoard');
  if (!agent) return <span className="text-xs text-theme-muted">{t('detail.unknownAgent')}</span>;
  return (
    <span className="flex items-center gap-1.5 text-xs text-theme-primary">
      <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-4 !h-4" />
      {agent.name}
    </span>
  );
}

export function UserBadge({ userId, name }: { userId: string | null; name: string }) {
  return (
    <span className="flex items-center gap-1.5 text-xs text-theme-primary">
      <PublisherAvatar userId={userId} name={name} size={16} variant="neutral" />
      {name}
    </span>
  );
}

function ActorAvatar({ actorType, actorId, agentMap, resolveUser }: {
  actorType: string;
  actorId: string | null;
  agentMap: Map<string, Agent>;
  resolveUser: (userId: string | null | undefined) => { name: string; avatarUrl: string | null };
}) {
  if (actorType === 'agent' && actorId) {
    const agent = agentMap.get(actorId);
    if (agent?.avatarUrl) {
      return <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-5 !h-5" />;
    }
    return (
      <div className="w-5 h-5 rounded-full bg-theme-tertiary flex items-center justify-center flex-shrink-0">
        <Bot className="h-3 w-3 text-theme-muted" />
      </div>
    );
  }
  if (actorType === 'user') {
    const { name } = resolveUser(actorId);
    return <PublisherAvatar userId={actorId} name={name} size={20} variant="neutral" />;
  }
  return (
    <div className="w-5 h-5 rounded-full bg-theme-tertiary flex items-center justify-center flex-shrink-0">
      <Clock className="h-3 w-3 text-theme-muted" />
    </div>
  );
}

// ─── Tab: Overview ──────────────────────────────────────────────

function OverviewTab({
  task, childTasks, agents, people, agentMap, isTerminal, readOnly = false, onUpdate, onRefresh, onSelectTask, t,
}: {
  task: Task;
  childTasks: Task[];
  agents: Agent[];
  people: TaskPerson[];
  agentMap: Map<string, Agent>;
  isTerminal: boolean;
  readOnly?: boolean;
  onSelectTask?: (taskId: string) => void;
  onUpdate: (fields: Record<string, unknown>) => void;
  onRefresh: () => void;
  t: ReturnType<typeof useTranslations>;
}) {
  const [showCreateSubtask, setShowCreateSubtask] = useState(false);

  return (
    <div className="space-y-5">
      {/* Instructions */}
      <div>
        <h4 className="text-xs font-medium text-theme-muted uppercase tracking-wide mb-2">{t('detail.instructions')}</h4>
        <EditableTextarea
          value={task.instructions || ''}
          disabled={readOnly}
          onSave={(v) => onUpdate({ instructions: v })}
          placeholder={t('detail.instructions')}
          className="text-sm text-theme-primary leading-relaxed bg-theme-secondary/40 rounded-xl px-4 py-3"
        />
      </div>

      {/* Result */}
      {task.result && (
        <div>
          <h4 className="text-xs font-medium text-theme-muted uppercase tracking-wide mb-2">{t('detail.result')}</h4>
          <div className="text-sm text-theme-primary whitespace-pre-wrap leading-relaxed bg-theme-secondary/40 rounded-xl px-4 py-3">
            {task.result}
          </div>
        </div>
      )}

      {/* Error */}
      {task.errorMessage && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-xl px-4 py-3 border border-red-200 dark:border-red-800/30">
          {task.errorMessage}
        </div>
      )}

      {/* Sub-tasks */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <h4 className="text-xs font-medium text-theme-muted uppercase tracking-wide">
            {t('detail.children')} ({childTasks.length})
          </h4>
          {!readOnly && (
            <button
              type="button"
              onClick={() => setShowCreateSubtask(true)}
              className="flex items-center gap-1 text-xs text-theme-muted hover:text-theme-primary transition-colors"
            >
              <Plus className="h-3 w-3" />
              {t('actions.createSubtask')}
            </button>
          )}
        </div>
        {childTasks.length === 0 ? (
          <p className="text-xs text-theme-muted">{t('detail.noChildren')}</p>
        ) : (
          <div className="space-y-1.5">
            {childTasks.map(child => {
              // A sub-task assignee is an agent XOR a human (Jira-style). Show the
              // agent preset for an agent; for a human render the canonical
              // PublisherAvatar (real photo / server fallback, never a raw UUID img).
              const assigneeAgent = child.assignedToAgentId ? agentMap.get(child.assignedToAgentId) : null;
              const assigneeUser = !child.assignedToAgentId ? child.assignedToUserId : null;
              return (
                <div
                  key={child.id}
                  onClick={() => onSelectTask?.(child.id)}
                  className={`flex items-center gap-2 text-sm bg-theme-secondary/50 rounded-lg px-3 py-2 ${onSelectTask ? 'cursor-pointer hover:bg-theme-secondary/80 transition-colors' : ''}`}
                >
                  <StatusIcon status={child.status} />
                  <span className="truncate flex-1 text-theme-primary">{child.title}</span>
                  {assigneeAgent ? (
                    <AvatarDisplay avatarUrl={assigneeAgent.avatarUrl} name={assigneeAgent.name} size="sm" className="!w-4 !h-4" />
                  ) : assigneeUser ? (
                    <PublisherAvatar userId={assigneeUser} name={child.users?.[assigneeUser]?.displayName || undefined} size={16} variant="neutral" />
                  ) : null}
                  <PriorityBadge priority={child.priority} />
                </div>
              );
            })}
          </div>
        )}
      </div>

      {showCreateSubtask && (
        <CreateTaskDialog
          agents={agents}
          people={people}
          parentTaskId={task.id}
          parentTaskTitle={task.title}
          onClose={() => setShowCreateSubtask(false)}
          onCreated={() => { setShowCreateSubtask(false); onRefresh(); }}
        />
      )}
    </div>
  );
}

// ─── Tab: Notes ─────────────────────────────────────────────────

function NotesTab({
  task, agentMap, resolveUser, selfName, noteContent, setNoteContent, submitting, readOnly = false, onAddNote, t,
}: {
  task: Task;
  agentMap: Map<string, Agent>;
  resolveUser: (userId: string | null | undefined) => { name: string; avatarUrl: string | null };
  selfName: string;
  noteContent: string;
  setNoteContent: (s: string) => void;
  submitting: boolean;
  readOnly?: boolean;
  onAddNote: () => void;
  t: ReturnType<typeof useTranslations>;
}) {
  return (
    <div className="flex flex-col h-full">
      {/* Notes list */}
      <div className="flex-1 overflow-y-auto space-y-3">
        {task.notes.length === 0 ? (
          <p className="text-xs text-theme-muted text-center py-6">{t('detail.noNotes')}</p>
        ) : (
          task.notes.map(note => {
            const agent = note.authorAgentId ? agentMap.get(note.authorAgentId) : null;
            const isAgent = !!note.authorAgentId;
            // Each note author resolves to their own displayName (never the viewer's name).
            const author = isAgent ? null : resolveUser(note.authorUserId);
            const name = isAgent ? (agent?.name || t('detail.agent')) : (author?.name || selfName);

            return (
              <div key={note.id} className="flex gap-2.5">
                {isAgent ? (
                  agent?.avatarUrl ? (
                    <AvatarDisplay avatarUrl={agent.avatarUrl} name={name} size="sm" className="!w-6 !h-6 flex-shrink-0 mt-0.5" />
                  ) : (
                    <div className="w-6 h-6 rounded-full flex-shrink-0 mt-0.5 bg-theme-tertiary flex items-center justify-center">
                      <span className="text-xs font-medium text-theme-muted">{name.charAt(0).toUpperCase()}</span>
                    </div>
                  )
                ) : (
                  <div className="flex-shrink-0 mt-0.5">
                    <PublisherAvatar userId={note.authorUserId} name={name} size={24} variant="neutral" />
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5 text-xs text-theme-muted">
                    <span className="font-medium text-theme-primary">{name}</span>
                    <span className="tabular-nums">{formatDate(note.createdAt)}</span>
                  </div>
                  <p className="text-sm text-theme-primary mt-0.5">{note.content}</p>
                </div>
              </div>
            );
          })
        )}
      </div>

      {/* Add note input - fixed at bottom (a note is a write, so a VIEWER gets no composer) */}
      {!readOnly && (
      <div className="flex items-center gap-2 pt-3 mt-3 border-t border-theme flex-shrink-0">
        <Input
          type="text"
          value={noteContent}
          onChange={(e) => setNoteContent(e.target.value)}
          placeholder={t('detail.addNotePlaceholder')}
          className="h-8 rounded-lg text-sm flex-1"
          onKeyDown={(e) => e.key === 'Enter' && onAddNote()}
        />
        <Button
          variant="outline"
          size="sm"
          onClick={onAddNote}
          disabled={!noteContent.trim() || submitting}
          className="h-8 w-8 p-0 rounded-lg"
        >
          <Send className="h-3.5 w-3.5" />
        </Button>
      </div>
      )}
    </div>
  );
}

// ─── Tab: Executions ────────────────────────────────────────────

function ExecutionsTab({
  executions, agentMap, selectedExecutionId, onSelectExecution, t,
}: {
  executions: AgentExecutionRecord[];
  agentMap: Map<string, Agent>;
  selectedExecutionId: string | null;
  onSelectExecution: (id: string | null) => void;
  t: ReturnType<typeof useTranslations>;
}) {
  if (selectedExecutionId) {
    return (
      <ExecutionDetailView
        executionId={selectedExecutionId}
        onBack={() => onSelectExecution(null)}
        t={t}
      />
    );
  }

  if (executions.length === 0) {
    return <p className="text-xs text-theme-muted text-center py-6">{t('detail.noExecutions')}</p>;
  }

  return (
    <div className="space-y-2">
      {executions.map(exec => {
        const agent = exec.agentEntityId ? agentMap.get(exec.agentEntityId) : null;
        const isSuccess = exec.status === 'COMPLETED';
        const isFailed = exec.status === 'FAILED' || exec.status === 'ERROR';

        return (
          <button
            key={exec.id}
            data-testid={`task-execution-row-${exec.id}`}
            onClick={() => onSelectExecution(exec.id)}
            className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg border border-theme bg-theme-secondary/30 hover:bg-theme-secondary/60 transition-colors text-left"
          >
            {/* Status icon */}
            {isSuccess ? (
              <CheckCircle2 className="h-4 w-4 text-emerald-500 flex-shrink-0" />
            ) : isFailed ? (
              <XCircle className="h-4 w-4 text-red-500 flex-shrink-0" />
            ) : (
              <Clock className="h-4 w-4 text-blue-500 flex-shrink-0" />
            )}

            {/* Agent */}
            <div className="flex items-center gap-1.5 min-w-0 flex-1">
              {agent && <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-5 !h-5 flex-shrink-0" />}
              <span className="text-sm text-theme-primary truncate">{agent?.name || t('detail.agent')}</span>
            </div>

            {/* Stats */}
            <div className="flex items-center gap-3 text-xs text-theme-muted flex-shrink-0">
              {exec.iterationCount > 0 && (
                <span className="tabular-nums">{exec.iterationCount} iter</span>
              )}
              {exec.totalToolCalls > 0 && (
                <span className="flex items-center gap-1 tabular-nums">
                  <Wrench className="h-3 w-3" /> {exec.totalToolCalls}
                </span>
              )}
              {exec.durationMs != null && (
                <span className="flex items-center gap-1 tabular-nums">
                  <Clock className="h-3 w-3" /> {formatDuration(exec.durationMs)}
                </span>
              )}
            </div>

            {/* Model badge */}
            {exec.model && (
              <span className="text-xs bg-theme-tertiary text-theme-muted rounded px-1.5 py-0.5 flex-shrink-0">
                {exec.model}
              </span>
            )}

            {/* Time */}
            <span className="text-xs text-theme-muted tabular-nums flex-shrink-0">
              {formatDate(exec.startedAt)}
            </span>
          </button>
        );
      })}
    </div>
  );
}

// ─── Execution detail (conversation + tool calls) ───────────────

function ExecutionDetailView({
  executionId, onBack, t,
}: {
  executionId: string;
  onBack: () => void;
  t: ReturnType<typeof useTranslations>;
}) {
  const [activeSubTab, setActiveSubTab] = useState<'conversation' | 'toolCalls'>('conversation');

  // Lazy-load: page 0 = newest 30, scroll-up sentinel triggers older pages. Tool-call
  // payloads carry MB-sized request/response bodies - unpaginated load OOM'd the JVM.
  const messagesRes = useExecutionPagedResource<AgentExecutionMessage>(
    executionId,
    agentService.getExecutionConversationPaged.bind(agentService),
  );
  const toolCallsRes = useExecutionPagedResource<AgentExecutionToolCall>(
    executionId,
    agentService.getExecutionToolCallsPaged.bind(agentService),
  );

  const visibleMessages = messagesRes.items.filter(m => m.role !== 'SYSTEM');
  const toolCalls = toolCallsRes.items;
  const loading = messagesRes.loading && toolCallsRes.loading;
  const activeRes = activeSubTab === 'conversation' ? messagesRes : toolCallsRes;

  return (
    <div className="space-y-3" data-testid={`task-execution-detail-${executionId}`}>
      {/* Back + sub-tabs */}
      <div className="flex items-center gap-3">
        <button onClick={onBack} className="flex items-center gap-1 text-xs text-[var(--accent-primary)] hover:underline">
          <ArrowLeft className="h-3 w-3" />
          {t('detail.backToExecutions')}
        </button>
        <div className="flex-1" />
        <div className="flex rounded-lg border border-theme overflow-hidden bg-theme-secondary">
          <button
            onClick={() => setActiveSubTab('conversation')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors',
              activeSubTab === 'conversation'
                ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-sm'
                : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary'
            )}
          >
            <MessageSquare className="h-3 w-3" />
            {t('detail.conversation')} ({visibleMessages.length})
          </button>
          <button
            onClick={() => setActiveSubTab('toolCalls')}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors',
              activeSubTab === 'toolCalls'
                ? 'bg-[var(--accent-primary)] text-[var(--accent-foreground)] shadow-sm'
                : 'text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary'
            )}
          >
            <Wrench className="h-3 w-3" />
            {t('detail.toolCalls')} ({toolCalls.length})
          </button>
        </div>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-12">
          <div className="animate-spin h-5 w-5 border-2 border-slate-300 border-t-slate-600 rounded-full" />
        </div>
      ) : (
        <>
          <LoadOlderSentinel
            hasMore={activeRes.hasMore}
            loading={activeRes.loadingOlder}
            onLoadOlder={activeRes.loadOlder}
          />
          {activeSubTab === 'conversation' ? (
            <ConversationList messages={visibleMessages} />
          ) : (
            <ToolCallList toolCalls={toolCalls} />
          )}
        </>
      )}
    </div>
  );
}

// ─── Conversation messages ──────────────────────────────────────

const ROLE_COLORS: Record<string, string> = {
  USER: 'border-l-blue-400',
  ASSISTANT: 'border-l-emerald-400',
  TOOL: 'border-l-amber-400',
};

function ConversationList({ messages }: { messages: AgentExecutionMessage[] }) {
  const t = useTranslations('taskBoard');
  if (messages.length === 0) {
    return <p className="text-xs text-theme-muted text-center py-6">{t('detail.noConversation')}</p>;
  }
  return (
    <div className="space-y-2">
      {messages.map(msg => (
        <div key={msg.id} className={cn('border-l-2 pl-3 py-1.5', ROLE_COLORS[msg.role] || 'border-l-slate-300')}>
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs font-semibold uppercase text-theme-muted">{msg.role}</span>
            {msg.iterationNumber != null && (
              <span className="text-xs text-theme-muted">#{msg.iterationNumber}</span>
            )}
            {msg.toolName && (
              <span className="text-xs font-mono text-theme-muted">{msg.toolName}</span>
            )}
          </div>
          {msg.content && (
            <div className="text-sm text-theme-primary whitespace-pre-wrap break-words leading-relaxed overflow-y-auto" style={{ maxHeight: '12rem' }}>
              {msg.content}
            </div>
          )}
          {msg.toolCallsRequested && msg.toolCallsRequested.length > 0 && (
            <div className="mt-1 space-y-0.5">
              {msg.toolCallsRequested.map((tc, i) => (
                <div key={i} className="text-xs font-mono text-theme-secondary">
                  {tc.toolName}({Object.keys(tc.arguments || {}).join(', ')})
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

// ─── Tool calls list ────────────────────────────────────────────

function ToolCallList({ toolCalls }: { toolCalls: AgentExecutionToolCall[] }) {
  const t = useTranslations('taskBoard');
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  if (toolCalls.length === 0) {
    return <p className="text-xs text-theme-muted text-center py-6">{t('detail.noToolCalls')}</p>;
  }

  const toggle = (id: number) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  return (
    <div className="space-y-1.5">
      {toolCalls.map(tc => {
        const expanded = expandedIds.has(tc.id);
        return (
          <div key={tc.id} className="rounded-lg border border-theme overflow-hidden">
            <button
              data-testid={`task-execution-tool-call-${tc.id}`}
              onClick={() => toggle(tc.id)}
              className="w-full flex items-center gap-2 px-3 py-2 hover:bg-theme-secondary/50 transition-colors text-left"
            >
              {expanded
                ? <ChevronDown className="h-3 w-3 text-theme-muted flex-shrink-0" />
                : <ChevronRight className="h-3 w-3 text-theme-muted flex-shrink-0" />
              }
              {tc.success
                ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500 flex-shrink-0" />
                : <AlertCircle className="h-3.5 w-3.5 text-red-500 flex-shrink-0" />
              }
              <span className="text-sm font-medium text-theme-primary truncate flex-1">{tc.toolName}</span>
              {tc.isRepeat && (
                <span className="text-xs bg-amber-100 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 px-1.5 py-0.5 rounded-full">
                  x{tc.consecutiveCount}
                </span>
              )}
              {tc.durationMs != null && (
                <span className="text-xs text-theme-muted tabular-nums">{tc.durationMs}ms</span>
              )}
              <span className="text-xs text-theme-muted">#{tc.iterationNumber}</span>
            </button>
            {expanded && (
              <div className="px-3 pb-3 border-t border-theme space-y-2 pt-2">
                {tc.arguments && Object.keys(tc.arguments).length > 0 && (
                  <div>
                    <span className="text-xs font-semibold text-theme-muted uppercase">{t('detail.arguments')}</span>
                    <pre className="text-xs font-mono bg-theme-secondary rounded-lg p-2 mt-1 overflow-auto max-h-40">
                      {JSON.stringify(tc.arguments, null, 2)}
                    </pre>
                  </div>
                )}
                {tc.content && (
                  <div>
                    <span className="text-xs font-semibold text-theme-muted uppercase">{t('detail.output')}</span>
                    <pre className="text-xs font-mono bg-theme-secondary rounded-lg p-2 mt-1 overflow-auto max-h-40 whitespace-pre-wrap break-words">
                      {tc.content}
                    </pre>
                  </div>
                )}
                {tc.errorMessage && (
                  <div>
                    <span className="text-xs font-semibold text-red-500 uppercase">{t('detail.error')}</span>
                    <pre className="text-xs font-mono bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 rounded-lg p-2 mt-1">
                      {tc.errorMessage}
                    </pre>
                  </div>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ─── Tab: Activity ──────────────────────────────────────────────

function ActivityTab({
  events, agentMap, resolveUser, t,
}: {
  events: TaskEvent[];
  agentMap: Map<string, Agent>;
  resolveUser: (userId: string | null | undefined) => { name: string; avatarUrl: string | null };
  t: ReturnType<typeof useTranslations>;
}) {
  if (events.length === 0) {
    return <p className="text-xs text-theme-muted text-center py-6">{t('detail.noEvents')}</p>;
  }

  return (
    <div className="space-y-3">
      {events.map((evt) => {
        const agent = evt.actorId ? agentMap.get(evt.actorId) : null;
        const actorName = evt.actorType === 'agent'
          ? (agent?.name || t('detail.agent'))
          : evt.actorType === 'user' ? resolveUser(evt.actorId).name : t('detail.system');

        return (
          <div key={evt.id} className="flex gap-2.5">
            <ActorAvatar actorType={evt.actorType} actorId={evt.actorId} agentMap={agentMap} resolveUser={resolveUser} />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5 text-xs">
                <span className="font-medium text-theme-primary">{actorName}</span>
                <span className="text-theme-muted">{evt.eventType}</span>
              </div>
              <span className="text-xs text-theme-muted tabular-nums">{formatDate(evt.createdAt)}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ─── Helpers ────────────────────────────────────────────────────

function formatDate(iso: string): string {
  return formatUtcDateTime(iso);
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  const sec = ms / 1000;
  if (sec < 60) return `${sec.toFixed(1)}s`;
  const min = Math.floor(sec / 60);
  const remSec = Math.floor(sec % 60);
  return `${min}m${remSec}s`;
}
