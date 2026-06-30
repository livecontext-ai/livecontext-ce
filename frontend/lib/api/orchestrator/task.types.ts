/**
 * Task Board types - mirrors backend AgentTaskEntity / TaskResponse / AgentTaskEventEntity.
 */

export interface TaskNote {
  id: string;
  authorAgentId: string | null;
  authorUserId: string | null;
  content: string;
  createdAt: string;
}

/** Resolved display identity for a human id referenced by a task. Never the raw Keycloak name. */
export interface TaskUserRef {
  displayName: string | null;
  avatarUrl: string | null;
}

/** A teammate that can be assigned a task / set as reviewer (Jira-style). */
export interface TaskPerson {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  email: string | null;
  /** True for the current user (so the picker can label "(You)" and sort it first). */
  isSelf?: boolean;
}

export interface Task {
  id: string;
  tenantId: string;
  parentTaskId: string | null;
  createdByAgentId: string | null;
  createdByUserId: string | null;
  assignedToAgentId: string | null;
  /** Human assignee (auth user id). Mutually exclusive with assignedToAgentId. Never auto-executes. */
  assignedToUserId: string | null;
  reviewerAgentId: string | null;
  /** Human reviewer (auth user id). Mutually exclusive with reviewerAgentId. */
  reviewerUserId: string | null;
  recurrenceId: string | null;
  title: string;
  instructions: string | null;
  taskContext: Record<string, unknown> | null;
  priority: TaskPriority;
  status: TaskStatus;
  result: string | null;
  errorMessage: string | null;
  depth: number;
  dueBy: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  completedAt: string | null;
  notes: TaskNote[];
  /** When the task was moved to the board's trash (status='deleted'); null while live. */
  deletedAt: string | null;
  /** Status held before being trashed, so Restore returns it to its origin column; null while live. */
  previousStatus: TaskStatus | null;
  /** Reviewer reject cap override. Null = use service default. Meaningful only when reviewerAgentId is set. */
  maxReviewAttempts: number | null;
  /** Number of reviewer rejections so far. Reaches maxReviewAttempts ⇒ task auto-fails. */
  reviewAttemptCount: number;
  /** Non-null when a worker agent execution is actively running on this task. */
  assigneeExecutionId: string | null;
  /** Non-null when a reviewer agent execution is actively running on this task. */
  reviewerExecutionId: string | null;
  /** Manual ordering rank within a board column (F1); null = unranked. */
  boardRank: number | null;
  /** Label ids on this task (F2); resolve to {@link TaskLabel} via the board catalog. */
  labelIds: string[];
  /** Estimated effort in minutes (F12); null = not set. */
  estimateMinutes: number | null;
  /** Logged time spent in minutes (F12); null = not set. */
  timeSpentMinutes: number | null;
  /** Ids of tasks blocking this one (F9); blocked while any blocker is non-terminal. */
  blockedByIds: string[];
  /** Checklist items (F10). */
  checklist: TaskChecklistItem[];
  /** File attachments (F10). */
  attachments: TaskAttachment[];
  /**
   * Resolved display identities for the human ids on this task (creator, assignee,
   * reviewer, note authors): userId → {displayName, avatarUrl}. Server-enriched so
   * the UI never falls back to the viewer's own (Keycloak real) name. May be absent.
   */
  users?: Record<string, TaskUserRef>;
}

/**
 * A task's status key. The seven built-ins keep literal autocomplete; the
 * {@code (string & {})} arm allows any custom board-column key (F4) without
 * losing the literals.
 */
export type TaskStatus =
  | 'pending' | 'in_progress' | 'in_review' | 'completed' | 'failed' | 'cancelled' | 'deleted'
  | (string & {});
export type TaskPriority = 'low' | 'normal' | 'high' | 'urgent';

/** Canonical lifecycle role a board status maps to (F4). */
export type TaskStatusCategory =
  'pending' | 'in_progress' | 'in_review' | 'done' | 'failed' | 'cancelled' | 'deleted';

/** A configurable board column (F4 / F3). */
export interface TaskStatusConfig {
  id: string;
  key: string;
  label: string;
  category: TaskStatusCategory;
  position: number;
  color: string | null;
  /** Work-in-progress limit (F3); null = no limit. */
  wipLimit: number | null;
  /** True for the seven built-ins: renamable/reorderable but not deletable. */
  isSystem: boolean;
  hidden: boolean;
}

/** A board label / tag (F2). */
export interface TaskLabel {
  id: string;
  name: string;
  color: string | null;
}

/** A checklist item (F10). */
export interface TaskChecklistItem {
  id: string;
  text: string;
  done: boolean;
}

/** A file attachment (F10); links a file already uploaded to storage by key. */
export interface TaskAttachment {
  id: string;
  fileName: string;
  storageKey: string;
  mimeType?: string | null;
  sizeBytes?: number | null;
}

export interface TaskEvent {
  id: number;
  taskId: string;
  eventType: string;
  actorType: 'agent' | 'user' | 'system';
  actorId: string | null;
  oldValue: Record<string, unknown> | null;
  newValue: Record<string, unknown> | null;
  createdAt: string;
}

export interface TaskStats {
  pending: number;
  inProgress: number;
  inReview: number;
  completed: number;
  failed: number;
  cancelled: number;
  deleted: number;
  backlog: number;
  total: number;
}

export interface TaskListParams {
  status?: string;
  assignedTo?: string;
  createdBy?: string;
  priority?: string;
  search?: string;
  parentTaskId?: string;
  sort?: 'created_at' | 'priority' | 'due_by' | 'updated_at' | 'manual';
  page?: number;
  size?: number;
}

export interface TaskListResponse {
  tasks: Task[];
  total: number;
  page: number;
  size: number;
}

export interface CreateTaskInput {
  agentId?: string | null;
  reviewerAgentId?: string | null;
  /** Human assignee (auth user id). Mutually exclusive with agentId. Never auto-executes (Jira-style). */
  assigneeUserId?: string | null;
  /** Human reviewer (auth user id). Mutually exclusive with reviewerAgentId. */
  reviewerUserId?: string | null;
  title: string;
  instructions?: string;
  priority?: TaskPriority;
  taskContext?: Record<string, unknown>;
  dueBy?: string;
  parentTaskId?: string | null;
  /**
   * Cap on reviewer reject attempts before the task is auto-failed (status='failed'
   * with the last reviewer feedback). Range [1, 20]. Default: 3 when omitted.
   * Only meaningful when reviewerAgentId is set.
   */
  maxReviewAttempts?: number | null;
}

export interface UpdateTaskInput {
  agentId?: string | null;
  /** Reassign to a human (auth user id). Mutually exclusive with agentId. unassign clears either. */
  assigneeUserId?: string | null;
  title?: string;
  instructions?: string;
  priority?: TaskPriority;
  unassign?: boolean;
  reviewerAgentId?: string | null;
  /** Set a human reviewer (auth user id). Mutually exclusive with reviewerAgentId. removeReviewer clears either. */
  reviewerUserId?: string | null;
  removeReviewer?: boolean;
  status?: TaskStatus;
  /**
   * Per-task override for the reviewer reject-loop cap. Range [1, 20].
   * Null/undefined means "no change". Only meaningful when reviewerAgentId is set.
   */
  maxReviewAttempts?: number | null;
}
