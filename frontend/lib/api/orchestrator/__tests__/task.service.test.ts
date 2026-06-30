/**
 * Tests for TaskService.moveToCancelled - endpoint selection by task status.
 *
 * Regression context: the board and the detail panel used to route every
 * move-to-cancelled through the cascading cancel endpoint (DELETE /tasks/{id}).
 * Its SQL only matches active statuses (pending/in_progress/in_review), so on a
 * completed/failed task it returned 200 with cancelled_count=0 - a silent no-op:
 * the card snapped back to its column with no error and nothing in the logs.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock('../../api-client', () => ({
  apiClient: mocks.apiClient,
}));

import { taskService } from '../task.service';
import type { TaskStatus } from '../task.types';

describe('TaskService.moveToCancelled', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiClient.patch.mockResolvedValue({ id: 'task-1', status: 'cancelled' });
    mocks.apiClient.delete.mockResolvedValue({ cancelled_count: 1 });
  });

  it('requalifies a completed task via a status PATCH (cascade cancel would no-op)', async () => {
    await taskService.moveToCancelled({ id: 'task-1', status: 'completed' });

    expect(mocks.apiClient.patch).toHaveBeenCalledWith('/tasks/task-1', { status: 'cancelled' });
    expect(mocks.apiClient.delete).not.toHaveBeenCalled();
  });

  it('requalifies a failed task via a status PATCH (cascade cancel would no-op)', async () => {
    await taskService.moveToCancelled({ id: 'task-1', status: 'failed' });

    expect(mocks.apiClient.patch).toHaveBeenCalledWith('/tasks/task-1', { status: 'cancelled' });
    expect(mocks.apiClient.delete).not.toHaveBeenCalled();
  });

  it.each<TaskStatus>(['pending', 'in_progress', 'in_review'])(
    'cancels an active %s task through the cascading cancel endpoint (children cancelled too)',
    async (status) => {
      await taskService.moveToCancelled({ id: 'task-1', status });

      expect(mocks.apiClient.delete).toHaveBeenCalledWith('/tasks/task-1', undefined, { params: {} });
      expect(mocks.apiClient.patch).not.toHaveBeenCalled();
    },
  );

  it('passes the cancel reason through to the cascading cancel endpoint', async () => {
    await taskService.moveToCancelled({ id: 'task-1', status: 'pending' }, 'duplicate');

    expect(mocks.apiClient.delete).toHaveBeenCalledWith(
      '/tasks/task-1', undefined, { params: { reason: 'duplicate' } },
    );
    expect(mocks.apiClient.patch).not.toHaveBeenCalled();
  });

  // Stale-status race: the panel/board knows the task as in_progress, but the agent
  // completed it server-side in the meantime. The cascade endpoint then no-ops with
  // cancelled_count=0 (HTTP 200) - without the fallback the move would silently fail,
  // reproducing the original bug through a race window.
  it('falls back to the status PATCH when the cascade no-ops on a stale active status', async () => {
    mocks.apiClient.delete.mockResolvedValue({ cancelled_count: 0 });

    await taskService.moveToCancelled({ id: 'task-1', status: 'in_progress' });

    expect(mocks.apiClient.delete).toHaveBeenCalledWith('/tasks/task-1', undefined, { params: {} });
    expect(mocks.apiClient.patch).toHaveBeenCalledWith('/tasks/task-1', { status: 'cancelled' });
  });

  it('stays idempotent on an already-cancelled task (cascade no-op, then a same-status PATCH)', async () => {
    mocks.apiClient.delete.mockResolvedValue({ cancelled_count: 0 });

    await taskService.moveToCancelled({ id: 'task-1', status: 'cancelled' });

    // Backend treats a PATCH to the current status as a no-op - no error surfaces.
    expect(mocks.apiClient.patch).toHaveBeenCalledWith('/tasks/task-1', { status: 'cancelled' });
  });
});

describe('TaskService bulk actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiClient.post.mockResolvedValue({
      action: 'delete', requested: 2, succeeded: 2, failed: 0, results: [],
    });
  });

  it('posts the action + ids to /tasks/bulk (camelCase body matching the REST DTO)', async () => {
    await taskService.bulkAction(['a', 'b'], 'delete');
    expect(mocks.apiClient.post).toHaveBeenCalledWith('/tasks/bulk', { taskIds: ['a', 'b'], action: 'delete' });
  });

  it('omits reason when not provided, includes it when given', async () => {
    await taskService.bulkAction(['a'], 'cancel');
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['a'], action: 'cancel' });

    await taskService.bulkAction(['a'], 'cancel', 'cleanup');
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['a'], action: 'cancel', reason: 'cleanup' });
  });

  it('softDeleteTasks → delete, restoreTasks → restore, purgeTasks → purge, cancelTasks → cancel', async () => {
    await taskService.softDeleteTasks(['x']);
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['x'], action: 'delete' });

    await taskService.restoreTasks(['x']);
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['x'], action: 'restore' });

    await taskService.purgeTasks(['x']);
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['x'], action: 'purge' });

    await taskService.cancelTasks(['x'], 'why');
    expect(mocks.apiClient.post).toHaveBeenLastCalledWith('/tasks/bulk', { taskIds: ['x'], action: 'cancel', reason: 'why' });
  });
});
