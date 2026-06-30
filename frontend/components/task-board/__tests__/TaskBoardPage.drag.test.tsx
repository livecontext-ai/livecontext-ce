// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskBoardPage } from '../TaskBoardPage';
import type { Task } from '@/lib/api/orchestrator/task.types';

const mocks = vi.hoisted(() => ({
  taskService: {
    updateTask: vi.fn(),
    cancelTask: vi.fn(),
    moveToCancelled: vi.fn(),
    softDeleteTasks: vi.fn(),
  },
  board: {
    tasks: [] as Task[],
    stats: null,
    agents: [],
    total: 0,
    loading: false,
    error: null,
    agentFilter: null,
    setAgentFilter: vi.fn(),
    searchQuery: '',
    setSearchQuery: vi.fn(),
    sortBy: 'priority',
    setSortBy: vi.fn(),
    refresh: vi.fn(),
    selectedTaskId: null,
    setSelectedTaskId: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { name: 'Test User', preferred_username: 'test-user' },
    avatarUrl: null,
  }),
}));

vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => null,
}));

vi.mock('@/components/app/AgentPanelContent', () => ({
  AGENT_CONFIGURATION_TAB: 'configuration',
  AgentPanelContent: () => null,
}));

vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <span>{name ?? 'Agent'}</span>,
}));

vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({
  useAgentActivitySubscriber: vi.fn(),
  useAgentActivityStore: () => false,
}));

vi.mock('../taskActivitySubscriptions', () => ({
  selectTaskActivityAgentIds: () => [],
}));

vi.mock('../useTaskBoard', () => ({
  useTaskBoard: () => mocks.board,
}));

vi.mock('../TaskDetailPanel', () => ({
  TaskDetailPanel: () => null,
}));

vi.mock('../CreateTaskDialog', () => ({
  CreateTaskDialog: () => null,
}));

vi.mock('@/lib/api/orchestrator/task.service', () => ({
  taskService: mocks.taskService,
}));

vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
  SelectValue: () => <span />,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    tenantId: 'tenant-1',
    parentTaskId: null,
    createdByAgentId: null,
    createdByUserId: 'tenant-1',
    assignedToAgentId: null,
    assignedToUserId: null,
    reviewerAgentId: null,
    reviewerUserId: null,
    recurrenceId: null,
    title: 'A task',
    instructions: 'Task instructions',
    taskContext: null,
    priority: 'normal',
    status: 'pending',
    result: null,
    errorMessage: null,
    depth: 0,
    dueBy: null,
    createdAt: '2026-05-26T10:00:00.000Z',
    updatedAt: '2026-05-26T10:00:00.000Z',
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
    ...overrides,
  };
}

function dragCardToColumn(taskId: string, columnStatus: string) {
  const card = screen.getByTestId(`task-card-${taskId}`);
  fireEvent.dragStart(card, { dataTransfer: { effectAllowed: 'move' } });
  fireEvent.drop(screen.getByTestId(`task-column-${columnStatus}`), { dataTransfer: {} });
}

/** Click the confirm button inside the confirmation modal (BulkDeleteModal → role="dialog"). */
function confirmModal(confirmLabelKey: string) {
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: confirmLabelKey }));
}

describe('TaskBoardPage drag to the Cancelled column', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Make every column visible (failed/cancelled are hidden by default).
    localStorage.setItem('taskboard-hidden-columns', '[]');
    mocks.taskService.moveToCancelled.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  // Regression: the drop handler used to call the cascading cancel endpoint directly,
  // which silently no-ops (200, cancelled_count=0) on a completed task - the card
  // snapped back to Completed with no error. The drop must delegate to
  // moveToCancelled with the full task so the service picks a transitioning endpoint.
  // Drops on Cancelled are now confirmation-gated, so the modal must be confirmed first.
  it('routes a completed task dropped on Cancelled through moveToCancelled after confirmation', async () => {
    mocks.board.tasks = [task({ id: 'task-done', status: 'completed', title: 'Done task' })];

    render(<TaskBoardPage />);
    dragCardToColumn('task-done', 'cancelled');

    // Confirmation modal first - no API call until confirmed.
    expect(screen.getByText('bulk.confirmCancelTitle')).toBeInTheDocument();
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
    confirmModal('bulk.cancel');

    await waitFor(() => {
      expect(mocks.taskService.moveToCancelled).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'task-done', status: 'completed' }),
      );
    });
    expect(mocks.taskService.cancelTask).not.toHaveBeenCalled();
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
    expect(mocks.board.refresh).toHaveBeenCalled();
  });

  it('routes an active pending task dropped on Cancelled through moveToCancelled after confirmation', async () => {
    mocks.board.tasks = [task({ id: 'task-active', status: 'pending', title: 'Active task' })];

    render(<TaskBoardPage />);
    dragCardToColumn('task-active', 'cancelled');
    confirmModal('bulk.cancel');

    await waitFor(() => {
      expect(mocks.taskService.moveToCancelled).toHaveBeenCalledWith(
        expect.objectContaining({ id: 'task-active', status: 'pending' }),
      );
    });
    expect(mocks.board.refresh).toHaveBeenCalled();
  });

  it('dismissing the drag-to-Cancelled modal performs no cancellation', () => {
    mocks.board.tasks = [task({ id: 'task-active', status: 'pending', title: 'Active task' })];

    render(<TaskBoardPage />);
    dragCardToColumn('task-active', 'cancelled');
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'bulk.keep' }));

    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('keeps non-cancel column drops on the plain status update path (no confirmation)', async () => {
    mocks.board.tasks = [task({ id: 'task-done', status: 'completed', title: 'Done task' })];
    mocks.taskService.updateTask.mockResolvedValue(task({ id: 'task-done', status: 'pending' }));

    render(<TaskBoardPage />);
    dragCardToColumn('task-done', 'pending');

    expect(screen.queryByRole('dialog')).toBeNull();
    await waitFor(() => {
      expect(mocks.taskService.updateTask).toHaveBeenCalledWith('task-done', { status: 'pending' });
    });
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
  });

  it('drops a task onto Deleted -> soft-delete after confirmation (not cancel/PATCH)', async () => {
    mocks.taskService.softDeleteTasks.mockResolvedValue(undefined);
    mocks.board.tasks = [task({ id: 'task-del', status: 'pending', title: 'To trash' })];

    render(<TaskBoardPage />);
    dragCardToColumn('task-del', 'deleted');

    expect(screen.getByText('bulk.confirmDeleteTitle')).toBeInTheDocument();
    expect(mocks.taskService.softDeleteTasks).not.toHaveBeenCalled();
    confirmModal('bulk.delete');

    await waitFor(() => expect(mocks.taskService.softDeleteTasks).toHaveBeenCalledWith(['task-del']));
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
    expect(mocks.board.refresh).toHaveBeenCalled();
  });

  it('drops a task onto In Progress -> opens the detail pre-staged, no direct status write', () => {
    mocks.board.tasks = [task({ id: 'task-ip', status: 'pending', title: 'Stage me' })];

    render(<TaskBoardPage />);
    dragCardToColumn('task-ip', 'in_progress');

    // Pre-stage handoff: the panel opens (selectedTaskId) and the user confirms the
    // in_progress transition from the action bar - no direct status write fires here.
    expect(mocks.board.setSelectedTaskId).toHaveBeenCalledWith('task-ip');
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});

describe('TaskBoardPage Filters dropdown', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('taskboard-hidden-columns', '[]');
    (mocks.board as { statuses?: unknown }).statuses = undefined; // default: no catalog -> fallback terminal keys
  });
  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  // The standalone "My tasks" / "Blocked" toggle pills are consolidated into one
  // Filters dropdown; toggling Blocked narrows the board and bumps the count badge.
  it('consolidates the toggles into the Filters dropdown and narrows by Blocked', () => {
    mocks.board.tasks = [
      task({ id: 't-free', status: 'pending', title: 'Free card' }),
      task({ id: 't-blk', status: 'pending', title: 'Blocked card', blockedByIds: ['x'] }),
    ];

    render(<TaskBoardPage />);
    // Toggles are NOT standalone toolbar pills - they live inside the dropdown.
    expect(screen.queryByTestId('task-filter-blocked')).toBeNull();
    expect(screen.getByText('Free card')).toBeInTheDocument();
    expect(screen.getByText('Blocked card')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('task-filter-menu'));
    fireEvent.click(screen.getByTestId('task-filter-blocked'));

    expect(screen.queryByText('Free card')).toBeNull();
    expect(screen.getByText('Blocked card')).toBeInTheDocument();
    expect(screen.getByTestId('task-filter-menu')).toHaveTextContent('(1)');
  });

  // Regression (live-test 2026-06-24): the Blocked filter + card badge used blockedByIds.length
  // (mere presence), so a task stayed "blocked" even after its blocker completed. They must instead
  // count only blockers that are still NON-terminal (AgentTaskEntity.blockedByIds semantics:
  // "the board computes blocked while any blocker is still non-terminal").
  it('Blocked filter keeps a task only while >=1 blocker is still non-terminal', () => {
    mocks.board.tasks = [
      task({ id: 'blk-pending', status: 'pending', title: 'Pending blocker' }),
      task({ id: 'blk-done', status: 'completed', title: 'Done blocker' }),
      task({ id: 'dep-active', status: 'pending', title: 'Active blocked', blockedByIds: ['blk-pending'] }),
      task({ id: 'dep-resolved', status: 'pending', title: 'Resolved blocked', blockedByIds: ['blk-done'] }),
    ];
    render(<TaskBoardPage />);
    fireEvent.click(screen.getByTestId('task-filter-menu'));
    fireEvent.click(screen.getByTestId('task-filter-blocked'));
    // blocked by a still-pending task -> stays in the Blocked filter
    expect(screen.getByText('Active blocked')).toBeInTheDocument();
    // blocked ONLY by a completed task -> no longer blocked -> dropped from the filter (the fix)
    expect(screen.queryByText('Resolved blocked')).toBeNull();
    // the blockers themselves have no blockers -> excluded from the Blocked filter
    expect(screen.queryByText('Pending blocker')).toBeNull();
    expect(screen.queryByText('Done blocker')).toBeNull();
  });

  it("card 'blocked' badge counts only still-active (non-terminal) blockers", () => {
    mocks.board.tasks = [
      task({ id: 'blk-pending', status: 'pending', title: 'Pending blocker' }),
      task({ id: 'blk-done', status: 'completed', title: 'Done blocker' }),
      task({ id: 'dep-active', status: 'pending', title: 'Active dep', blockedByIds: ['blk-pending'] }),
      task({ id: 'dep-resolved', status: 'pending', title: 'Resolved dep', blockedByIds: ['blk-done'] }),
    ];
    render(<TaskBoardPage />);
    // a still-pending blocker -> amber "blocked" badge showing 1 active blocker
    expect(screen.getByTestId('task-card-blocked-dep-active')).toHaveTextContent('1');
    // a completed blocker -> no "blocked" badge at all (was shown pre-fix)
    expect(screen.queryByTestId('task-card-blocked-dep-resolved')).toBeNull();
  });

  // Exercises the LOADED-catalog branch of terminalStatusKeys (not the fallback): a custom
  // column whose category is done/deleted must count as terminal, so a blocker in it no
  // longer blocks even though its key isn't one of the built-in terminal keys.
  it('treats custom done-category AND deleted-category blockers as non-blocking (loaded catalog)', () => {
    (mocks.board as { statuses?: unknown }).statuses = [
      { id: 'st-p', key: 'pending', label: 'Pending', category: 'pending', color: '', wipLimit: null, isSystem: true, hidden: false, position: 0 },
      { id: 'st-s', key: 'shipped', label: 'Shipped', category: 'done', color: '', wipLimit: null, isSystem: false, hidden: false, position: 1 },
      { id: 'st-a', key: 'archived', label: 'Archived', category: 'deleted', color: '', wipLimit: null, isSystem: false, hidden: false, position: 2 },
    ];
    mocks.board.tasks = [
      task({ id: 'b-ship', status: 'shipped', title: 'Shipped blocker' }),
      task({ id: 'b-arch', status: 'archived', title: 'Archived blocker' }),
      task({ id: 'd-ship', status: 'pending', title: 'Dep on shipped', blockedByIds: ['b-ship'] }),
      task({ id: 'd-arch', status: 'pending', title: 'Dep on archived', blockedByIds: ['b-arch'] }),
    ];
    render(<TaskBoardPage />);
    fireEvent.click(screen.getByTestId('task-filter-menu'));
    fireEvent.click(screen.getByTestId('task-filter-blocked'));
    expect(screen.queryByText('Dep on shipped')).toBeNull();
    expect(screen.queryByText('Dep on archived')).toBeNull();
  });

  it('conservatively keeps a task whose blocker id is unresolvable (not loaded)', () => {
    mocks.board.tasks = [
      task({ id: 'd-ghost', status: 'pending', title: 'Dep on ghost', blockedByIds: ['ghost-id-not-loaded'] }),
    ];
    render(<TaskBoardPage />);
    fireEvent.click(screen.getByTestId('task-filter-menu'));
    fireEvent.click(screen.getByTestId('task-filter-blocked'));
    expect(screen.getByText('Dep on ghost')).toBeInTheDocument();
  });
});
