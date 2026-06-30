import { describe, expect, it, vi } from 'vitest';
import type { Task } from '@/lib/api/orchestrator/task.types';

vi.mock('@/lib/websocket', () => ({
  useChannel: vi.fn(),
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ numericUserId: 42 }),
}));

import { applyStreamEvents } from '../useTaskBoardStream';

function task(id: string, status: Task['status'], assignedToAgentId: string | null = null): Task {
  return {
    id,
    tenantId: 'tenant-1',
    parentTaskId: null,
    createdByAgentId: null,
    createdByUserId: 'user-1',
    assignedToAgentId,
    assignedToUserId: null,
    reviewerAgentId: null,
    reviewerUserId: null,
    recurrenceId: null,
    title: `Task ${id}`,
    instructions: 'Do it',
    taskContext: null,
    priority: 'normal',
    status,
    result: null,
    errorMessage: null,
    depth: 0,
    dueBy: null,
    createdAt: '2026-05-22T10:00:00Z',
    updatedAt: '2026-05-22T10:00:00Z',
    startedAt: null,
    completedAt: null,
    notes: [],
    maxReviewAttempts: null,
    reviewAttemptCount: 0,
    assigneeExecutionId: null,
    reviewerExecutionId: null,
    deletedAt: null,
    previousStatus: null,
    boardRank: null,
    labelIds: [],
    estimateMinutes: null,
    timeSpentMinutes: null,
    blockedByIds: [],
    checklist: [],
    attachments: [],
  };
}

describe('applyStreamEvents', () => {
  it('preserves locally loaded notes when update payload contains no notes', () => {
    const original = task('task-1', 'in_progress', 'agent-1');
    original.notes = [{
      id: 'note-1',
      authorAgentId: 'agent-1',
      authorUserId: null,
      content: 'Reviewer context',
      createdAt: '2026-05-22T10:01:00Z',
    }];
    const updated = { ...original, status: 'in_review' as const, notes: [] };

    const result = applyStreamEvents([original], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T10:02:00Z',
      task: updated,
    }]);

    expect(result.changed).toBe(true);
    expect(result.tasks[0].status).toBe('in_review');
    expect(result.tasks[0].notes).toEqual(original.notes);
    expect(result.stats).toMatchObject({ inReview: 1, total: 1 });
  });

  it('inserts an updated task that was not in the current filtered list', () => {
    const incoming = task('task-2', 'pending');

    const result = applyStreamEvents([], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T10:03:00Z',
      task: incoming,
    }]);

    expect(result.changed).toBe(true);
    expect(result.tasks).toEqual([incoming]);
    expect(result.stats).toMatchObject({ pending: 1, backlog: 1, total: 1 });
  });

  it('does not insert an updated task that is outside the active filters', () => {
    const incoming = task('task-2', 'pending', 'agent-other');

    const result = applyStreamEvents([], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T10:03:00Z',
      task: incoming,
    }], { assignedTo: 'agent-visible' });

    expect(result.changed).toBe(false);
    expect(result.tasks).toEqual([]);
    expect(result.stats).toBeNull();
  });

  it('removes an existing task when an update no longer matches the active filters', () => {
    const existing = task('task-2', 'pending', 'agent-visible');
    const moved = { ...existing, assignedToAgentId: 'agent-other' };

    const result = applyStreamEvents([existing], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T10:03:00Z',
      task: moved,
    }], { assignedTo: 'agent-visible' });

    expect(result.changed).toBe(true);
    expect(result.tasks).toEqual([]);
    expect(result.stats).toMatchObject({ pending: 0, total: 0 });
  });

  it('re-buckets a task into the deleted column on a soft-delete (status→deleted) update', () => {
    // Soft-delete is published as task_updated (the row still exists), not task_deleted.
    const existing = task('task-9', 'in_progress', 'agent-1');
    const trashed = { ...existing, status: 'deleted' as const, deletedAt: '2026-05-22T11:00:00Z', previousStatus: 'in_progress' as const };

    const result = applyStreamEvents([existing], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T11:00:00Z',
      task: trashed,
    }]);

    expect(result.changed).toBe(true);
    expect(result.tasks[0].status).toBe('deleted');
    // computeStats counts the deleted bucket and drops it from in_progress.
    expect(result.stats).toMatchObject({ deleted: 1, inProgress: 0, total: 1 });
  });

  it('keeps a soft-deleted task under a matching agent filter and counts it as deleted', () => {
    // A trashed task keeps its assignedToAgentId, so it must survive a matching agent filter.
    const existing = task('task-f1', 'in_progress', 'agent-1');
    const trashed = { ...existing, status: 'deleted' as const, deletedAt: '2026-05-22T11:00:00Z', previousStatus: 'in_progress' as const };

    const result = applyStreamEvents([existing], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T11:00:00Z',
      task: trashed,
    }], { assignedTo: 'agent-1' });

    expect(result.changed).toBe(true);
    expect(result.tasks[0].status).toBe('deleted');
    expect(result.stats).toMatchObject({ deleted: 1, inProgress: 0 });
  });

  it('removes a soft-deleted task that no longer matches the active filter (unassigned)', () => {
    // The trashed task still has an agent → it does not match an "unassigned" filter → dropped.
    const existing = task('task-f2', 'in_progress', 'agent-1');
    const trashed = { ...existing, status: 'deleted' as const, deletedAt: '2026-05-22T11:00:00Z' };

    const result = applyStreamEvents([existing], [{
      event: 'task_updated',
      tenantId: 'tenant-1',
      organizationId: 'org-1',
      timestamp: '2026-05-22T11:00:00Z',
      task: trashed,
    }], { assignedTo: 'unassigned' });

    expect(result.changed).toBe(true);
    expect(result.tasks).toEqual([]);
    expect(result.stats).toMatchObject({ deleted: 0, total: 0 });
  });

  it('removes a trashed task from the board when the retention purge fires (task_deleted)', () => {
    const trashed = { ...task('task-10', 'deleted', 'agent-1'), deletedAt: '2026-04-01T00:00:00Z' };

    const purged = applyStreamEvents([trashed], [{
      event: 'task_deleted',
      tenantId: 'tenant-1',
      timestamp: '2026-05-22T11:01:00Z',
      taskId: 'task-10',
    }]);
    expect(purged.changed).toBe(true);
    expect(purged.tasks).toEqual([]);
    expect(purged.stats).toMatchObject({ deleted: 0, total: 0 });
  });

  it('deletes matching tasks and ignores unknown delete events without churn', () => {
    const existing = task('task-3', 'completed', 'agent-1');

    const deleted = applyStreamEvents([existing], [{
      event: 'task_deleted',
      tenantId: 'tenant-1',
      timestamp: '2026-05-22T10:04:00Z',
      taskId: 'task-3',
    }]);
    expect(deleted.changed).toBe(true);
    expect(deleted.tasks).toEqual([]);
    expect(deleted.stats).toMatchObject({ completed: 0, total: 0 });

    const ignored = applyStreamEvents([existing], [{
      event: 'task_deleted',
      tenantId: 'tenant-1',
      timestamp: '2026-05-22T10:05:00Z',
      taskId: 'missing',
    }]);
    expect(ignored.changed).toBe(false);
    expect(ignored.tasks).toEqual([existing]);
    expect(ignored.stats).toBeNull();
  });
});
