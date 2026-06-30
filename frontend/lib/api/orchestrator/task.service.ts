/**
 * Task Service - API calls for the Task Board.
 * Routes through gateway → agent-service (/api/tasks/**).
 */

import { apiClient } from '../api-client';
import type {
  Task,
  TaskEvent,
  TaskStats,
  TaskListParams,
  TaskListResponse,
  CreateTaskInput,
  UpdateTaskInput,
  TaskStatusConfig,
  TaskStatusCategory,
  TaskLabel,
  TaskChecklistItem,
  TaskAttachment,
} from './task.types';
import type { AgentExecutionRecord, PagedResponse } from './agent-metrics.types';

function normalizePagedResponse<T>(value: unknown): PagedResponse<T> {
  if (Array.isArray(value)) {
    return {
      content: value as T[],
      totalElements: value.length,
      totalPages: 1,
      number: 0,
      size: value.length,
      first: true,
      last: true,
    };
  }

  const record = value && typeof value === 'object'
    ? value as Partial<PagedResponse<T>>
    : {};
  const content = Array.isArray(record.content) ? record.content : [];
  return {
    content,
    totalElements: record.totalElements ?? content.length,
    totalPages: record.totalPages ?? 1,
    number: record.number ?? 0,
    size: record.size ?? content.length,
    first: record.first ?? true,
    last: record.last ?? true,
  };
}

function normalizeTask(value: Task): Task {
  const task = value as Task & Partial<Pick<Task,
    'notes'
    | 'taskContext'
    | 'result'
    | 'errorMessage'
    | 'reviewAttemptCount'
    | 'maxReviewAttempts'
  >>;

  return {
    ...task,
    taskContext: task.taskContext ?? null,
    result: task.result ?? null,
    errorMessage: task.errorMessage ?? null,
    notes: Array.isArray(task.notes) ? task.notes : [],
    reviewAttemptCount: Number(task.reviewAttemptCount ?? 0),
    maxReviewAttempts: task.maxReviewAttempts ?? null,
    deletedAt: task.deletedAt ?? null,
    previousStatus: task.previousStatus ?? null,
    // F1/F2/F9/F10/F12 - default the new card fields so the UI can render safely.
    boardRank: task.boardRank ?? null,
    labelIds: Array.isArray(task.labelIds) ? task.labelIds : [],
    estimateMinutes: task.estimateMinutes ?? null,
    timeSpentMinutes: task.timeSpentMinutes ?? null,
    blockedByIds: Array.isArray(task.blockedByIds) ? task.blockedByIds : [],
    checklist: Array.isArray(task.checklist) ? task.checklist : [],
    attachments: Array.isArray(task.attachments) ? task.attachments : [],
  };
}

/** Bulk task-board action verbs (see TaskService.bulkAction). */
export type BulkTaskAction = 'cancel' | 'delete' | 'restore' | 'purge';

export interface BulkTaskResult {
  action: BulkTaskAction;
  requested: number;
  succeeded: number;
  failed: number;
  results: { task_id: string; ok: boolean; error?: string }[];
}

export class TaskService {

  /** List all tasks for the tenant (paginated, filterable). */
  async listTasks(params: TaskListParams = {}): Promise<TaskListResponse> {
    const query: Record<string, string> = {};
    if (params.status) query.status = params.status;
    if (params.assignedTo) query.assignedTo = params.assignedTo;
    if (params.createdBy) query.createdBy = params.createdBy;
    if (params.priority) query.priority = params.priority;
    if (params.search) query.search = params.search;
    if (params.parentTaskId) query.parentTaskId = params.parentTaskId;
    if (params.sort) query.sort = params.sort;
    if (params.page !== undefined) query.page = String(params.page);
    if (params.size !== undefined) query.size = String(params.size);

    const response = await apiClient.get<TaskListResponse>('/tasks', { params: query });
    return {
      ...response,
      tasks: Array.isArray(response.tasks) ? response.tasks.map(normalizeTask) : [],
    };
  }

  /** Get aggregated task counts by status. */
  async getStats(): Promise<TaskStats> {
    return apiClient.get<TaskStats>('/tasks/stats');
  }

  /** Get task detail (tenant-scoped, no agent perspective needed). */
  async getTask(taskId: string): Promise<Task> {
    const task = await apiClient.get<Task>(`/tasks/${taskId}/detail`);
    return normalizeTask(task);
  }

  /**
   * Get audit trail events for a task (paginated, DESC).
   * <p>
   * Backend page 0 = newest batch. Default size 30. The current task panel renders
   * a single flat list - we pull page 0 and reverse to ASC for chronological display.
   * If a task exceeds 30 events the older ones are not rendered today; lazy-load
   * wiring can be added if needed.
   */
  async getTaskEvents(taskId: string, page = 0, size = 30): Promise<PagedResponse<TaskEvent>> {
    const response = await apiClient.get<PagedResponse<TaskEvent> | TaskEvent[]>(
      `/tasks/${taskId}/events`,
      { params: { page: String(page), size: String(size) } }
    );
    return normalizePagedResponse<TaskEvent>(response);
  }

  /** Get direct children of a task. */
  async getTaskChildren(taskId: string): Promise<Task[]> {
    const tasks = await apiClient.get<Task[]>(`/tasks/${taskId}/children`);
    return (Array.isArray(tasks) ? tasks : []).map(normalizeTask);
  }

  /** Create a new task (human-created, optionally assigned). */
  async createTask(input: CreateTaskInput): Promise<Task> {
    const task = await apiClient.post<Task>('/tasks', input);
    return normalizeTask(task);
  }

  /** Update task fields (reassign, change priority, etc.). */
  async updateTask(taskId: string, input: UpdateTaskInput): Promise<Task> {
    const task = await apiClient.patch<Task>(`/tasks/${taskId}`, input);
    return normalizeTask(task);
  }

  /** Cancel a task (cascading to children). */
  async cancelTask(taskId: string, reason?: string): Promise<{ cancelled_count: number }> {
    const query: Record<string, string> = {};
    if (reason) query.reason = reason;
    return apiClient.delete<{ cancelled_count: number }>(`/tasks/${taskId}`, undefined, { params: query });
  }

  /**
   * Move a task to cancelled, picking the endpoint that actually transitions it.
   * Active tasks (pending/in_progress/in_review) go through the cascading cancel
   * endpoint (children are cancelled too). Terminal tasks (completed/failed) are
   * requalified with a plain status PATCH - the cascade endpoint's WHERE clause
   * skips terminal statuses and would return 200 with cancelled_count=0 (no-op).
   *
   * When the client-known status is stale (the task reached a terminal state
   * since the board last refreshed), the cascade endpoint no-ops with
   * cancelled_count=0 - we then fall back to the PATCH so the move still lands.
   * Note: `reason` only travels on the cascade path (the PATCH requalification
   * has no reason field), and PATCH-cancelling a terminal parent does not
   * cascade to children that were still running when the parent ended.
   */
  async moveToCancelled(task: Pick<Task, 'id' | 'status'>, reason?: string): Promise<void> {
    if (task.status !== 'completed' && task.status !== 'failed') {
      const { cancelled_count } = await this.cancelTask(task.id, reason);
      if (cancelled_count > 0) return;
    }
    await this.updateTask(task.id, { status: 'cancelled' });
  }

  /** Hard-delete a terminal task and its related data (events, notes, terminal children). */
  async hardDeleteTask(taskId: string): Promise<{ deleted_count: number }> {
    return apiClient.delete<{ deleted_count: number }>(`/tasks/${taskId}`, undefined, { params: { hard: 'true' } });
  }

  /**
   * Apply one action to a set of tasks (board multi-select bar + single-card drag onto
   * the Deleted column). Per-item partial success - inspect {@link BulkTaskResult.results}
   * for any `ok: false` rows. Backs all four verbs:
   *  - `cancel`  → cascading cancel to 'cancelled'
   *  - `delete`  → soft-delete to the Deleted column (restorable, 30-day retention)
   *  - `restore` → Deleted → previous column
   *  - `purge`   → permanent hard-delete of a trashed task
   */
  async bulkAction(taskIds: string[], action: BulkTaskAction, reason?: string): Promise<BulkTaskResult> {
    return apiClient.post<BulkTaskResult>('/tasks/bulk', {
      taskIds,
      action,
      ...(reason ? { reason } : {}),
    });
  }

  /** Move tasks to the Deleted column (soft-delete; restorable for 30 days). */
  async softDeleteTasks(taskIds: string[]): Promise<BulkTaskResult> {
    return this.bulkAction(taskIds, 'delete');
  }

  /** Restore trashed tasks to their previous column. */
  async restoreTasks(taskIds: string[]): Promise<BulkTaskResult> {
    return this.bulkAction(taskIds, 'restore');
  }

  /** Bulk-cancel tasks (cascading to children). */
  async cancelTasks(taskIds: string[], reason?: string): Promise<BulkTaskResult> {
    return this.bulkAction(taskIds, 'cancel', reason);
  }

  /** Permanently delete trashed tasks (no undo). */
  async purgeTasks(taskIds: string[]): Promise<BulkTaskResult> {
    return this.bulkAction(taskIds, 'purge');
  }

  /** Add a note to a task, optionally @-mentioning teammates (F11). */
  async addNote(taskId: string, content: string, mentionedUserIds?: string[]): Promise<{ note_id: string; task_id: string }> {
    return apiClient.post<{ note_id: string; task_id: string }>(`/tasks/${taskId}/notes`, {
      content,
      ...(mentionedUserIds && mentionedUserIds.length ? { mentionedUserIds } : {}),
    });
  }

  /** Claim a backlog task for an agent. */
  async claimTask(taskId: string, agentId: string): Promise<{ claimed: boolean; task?: Task }> {
    const response = await apiClient.post<{ claimed: boolean; task?: Task }>(`/tasks/${taskId}/claim`, { as_agent_id: agentId });
    return response.task ? { ...response, task: normalizeTask(response.task) } : response;
  }

  /**
   * Get executions linked to a task (paginated, DESC).
   * <p>
   * Backend page 0 = newest run. Default size 20. Multi-round agent loops can attach
   * hundreds of executions to a single task; unpaginated load risked OOM.
   */
  async getTaskExecutions(taskId: string, page = 0, size = 20): Promise<PagedResponse<AgentExecutionRecord>> {
    const response = await apiClient.get<PagedResponse<AgentExecutionRecord> | AgentExecutionRecord[]>(
      `/tasks/${taskId}/executions`,
      { params: { page: String(page), size: String(size) } }
    );
    return normalizePagedResponse<AgentExecutionRecord>(response);
  }

  /** Approve a task in review (in_review -> completed) as the current user. */
  async approveTask(taskId: string): Promise<Task> {
    const task = await apiClient.post<Task>(`/tasks/${taskId}/approve`, {});
    return normalizeTask(task);
  }

  /** Stop a running agent on a task (sets Redis cancel key + clears execution lock). */
  async stopAgentExecution(taskId: string, role: 'assignee' | 'reviewer'): Promise<Task> {
    const task = await apiClient.post<Task>(`/tasks/${taskId}/stop-agent`, { role });
    return normalizeTask(task);
  }

  /** Reject a task in review (in_review -> in_progress) as the current user. */
  async rejectReviewTask(taskId: string, reason?: string): Promise<Task> {
    const task = await apiClient.post<Task>(`/tasks/${taskId}/reject-review`, {
      ...(reason ? { reason } : {}),
    });
    return normalizeTask(task);
  }

  // ── Board columns / statuses (F4 / F3) ──────────────────────────────────

  /** List the board's configurable columns in display order (seeds defaults on first call). */
  async listStatuses(): Promise<TaskStatusConfig[]> {
    const res = await apiClient.get<{ statuses: TaskStatusConfig[] }>('/tasks/statuses');
    return Array.isArray(res.statuses) ? res.statuses : [];
  }

  async createStatus(input: { label: string; category: TaskStatusCategory; color?: string | null; wipLimit?: number | null }): Promise<TaskStatusConfig> {
    return apiClient.post<TaskStatusConfig>('/tasks/statuses', input);
  }

  async updateStatus(id: string, input: { label?: string; category?: TaskStatusCategory; color?: string | null; wipLimit?: number | null; clearWipLimit?: boolean; hidden?: boolean }): Promise<TaskStatusConfig> {
    return apiClient.patch<TaskStatusConfig>(`/tasks/statuses/${id}`, input);
  }

  async deleteStatus(id: string): Promise<{ deleted: boolean; moved_tasks: number; fallback_status: string }> {
    return apiClient.delete<{ deleted: boolean; moved_tasks: number; fallback_status: string }>(`/tasks/statuses/${id}`);
  }

  /** Persist a new column order (ids top-to-bottom). */
  async reorderStatuses(orderedIds: string[]): Promise<TaskStatusConfig[]> {
    const res = await apiClient.put<{ statuses: TaskStatusConfig[] }>('/tasks/statuses/order', { orderedIds });
    return Array.isArray(res.statuses) ? res.statuses : [];
  }

  // ── Labels (F2) ─────────────────────────────────────────────────────────

  async listLabels(): Promise<TaskLabel[]> {
    const res = await apiClient.get<{ labels: TaskLabel[] }>('/tasks/labels');
    return Array.isArray(res.labels) ? res.labels : [];
  }

  async createLabel(input: { name: string; color?: string | null }): Promise<TaskLabel> {
    return apiClient.post<TaskLabel>('/tasks/labels', input);
  }

  async updateLabel(id: string, input: { name?: string; color?: string | null }): Promise<TaskLabel> {
    return apiClient.patch<TaskLabel>(`/tasks/labels/${id}`, input);
  }

  async deleteLabel(id: string): Promise<{ deleted: boolean }> {
    return apiClient.delete<{ deleted: boolean }>(`/tasks/labels/${id}`);
  }

  /** Replace a task's label set (F2). */
  async setTaskLabels(taskId: string, labelIds: string[]): Promise<Task> {
    const task = await apiClient.put<Task>(`/tasks/${taskId}/labels`, { labelIds });
    return normalizeTask(task);
  }

  // ── Manual ordering (F1) ─────────────────────────────────────────────────

  /** Persist a column's manual card order after a drag (ids top-to-bottom). */
  async reorderTasks(orderedTaskIds: string[]): Promise<Task[]> {
    const res = await apiClient.put<{ tasks: Task[] }>('/tasks/rank', { orderedTaskIds });
    return (Array.isArray(res.tasks) ? res.tasks : []).map(normalizeTask);
  }

  // ── Estimation / time (F12) ──────────────────────────────────────────────

  async setEstimate(taskId: string, input: { estimateMinutes?: number | null; clearEstimate?: boolean; timeSpentMinutes?: number | null; clearTimeSpent?: boolean }): Promise<Task> {
    const task = await apiClient.put<Task>(`/tasks/${taskId}/estimate`, input);
    return normalizeTask(task);
  }

  // ── Dependencies (F9) ─────────────────────────────────────────────────────

  /** Replace a task's blocker set (the tasks that must finish first). */
  async setBlockers(taskId: string, blockedByIds: string[]): Promise<Task> {
    const task = await apiClient.put<Task>(`/tasks/${taskId}/blockers`, { blockedByIds });
    return normalizeTask(task);
  }

  // ── Checklist + attachments (F10) ─────────────────────────────────────────

  async setChecklist(taskId: string, items: TaskChecklistItem[]): Promise<Task> {
    const task = await apiClient.put<Task>(`/tasks/${taskId}/checklist`, { items });
    return normalizeTask(task);
  }

  async setAttachments(taskId: string, attachments: TaskAttachment[]): Promise<Task> {
    const task = await apiClient.put<Task>(`/tasks/${taskId}/attachments`, { attachments });
    return normalizeTask(task);
  }
}

export const taskService = new TaskService();
