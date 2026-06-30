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
    bulkAction: vi.fn(),
    softDeleteTasks: vi.fn(),
  },
  board: {
    tasks: [] as Task[],
    stats: null,
    agents: [],
    people: [],
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
  // Echo "key:<count|days>" so we can assert interpolated values without locale strings.
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars && 'count' in vars) return `${key}:${vars.count}`;
    if (vars && 'days' in vars) return `${key}:${vars.days}`;
    return key;
  },
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ user: { name: 'Test User', preferred_username: 'test-user' }, avatarUrl: null }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AGENT_CONFIGURATION_TAB: 'configuration', AgentPanelContent: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: ({ name }: { name?: string }) => <span>{name ?? 'Agent'}</span> }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => <span /> }));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({
  useAgentActivitySubscriber: vi.fn(),
  useAgentActivityStore: () => false,
}));
vi.mock('../taskActivitySubscriptions', () => ({ selectTaskActivityAgentIds: () => [] }));
vi.mock('../useTaskBoard', () => ({ useTaskBoard: () => mocks.board }));
vi.mock('../TaskDetailPanel', () => ({ TaskDetailPanel: () => null }));
vi.mock('../CreateTaskDialog', () => ({ CreateTaskDialog: () => null }));
vi.mock('@/lib/api/orchestrator/task.service', () => ({ taskService: mocks.taskService }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
  SelectValue: () => <span />,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ui/button', () => ({
  Button: ({ children, ...p }: { children: React.ReactNode }) => <button type="button" {...p}>{children}</button>,
}));

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1', tenantId: 'tenant-1', parentTaskId: null,
    createdByAgentId: null, createdByUserId: 'tenant-1',
    assignedToAgentId: null, assignedToUserId: null,
    reviewerAgentId: null, reviewerUserId: null, recurrenceId: null,
    title: 'A task', instructions: 'Task instructions', taskContext: null,
    priority: 'normal', status: 'pending', result: null, errorMessage: null,
    depth: 0, dueBy: null,
    createdAt: '2026-05-26T10:00:00.000Z', updatedAt: '2026-05-26T10:00:00.000Z',
    startedAt: null, completedAt: null, notes: [],
    maxReviewAttempts: null, reviewAttemptCount: 0,
    assigneeExecutionId: null, reviewerExecutionId: null,
    deletedAt: null, previousStatus: null,
    boardRank: null, labelIds: [], estimateMinutes: null, timeSpentMinutes: null,
    blockedByIds: [], checklist: [], attachments: [],
    ...overrides,
  };
}

function selectCard(id: string) {
  fireEvent.click(screen.getByTestId(`task-card-select-${id}`));
}

/** The confirmation modal (BulkDeleteModal renders role="dialog"); null when closed. */
function confirmDialog(): HTMLElement | null {
  return screen.queryByRole('dialog');
}

/** Click the confirm (destructive) button inside the modal, scoped so the bulk-bar twin never collides. */
function confirmInModal(confirmLabelKey: string) {
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: confirmLabelKey }));
}

/** Click the dismiss button inside the modal (cancelLabel === 'bulk.keep'). */
function dismissModal() {
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'bulk.keep' }));
}

describe('TaskBoardPage multi-select + bulk bar', () => {
  let confirmSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('taskboard-hidden-columns', '[]'); // reveal all columns incl. deleted
    mocks.taskService.bulkAction.mockResolvedValue({ action: 'delete', requested: 1, succeeded: 1, failed: 0, results: [] });
    mocks.taskService.softDeleteTasks.mockResolvedValue({ action: 'delete', requested: 1, succeeded: 1, failed: 0, results: [] });
    // The board moved off window.confirm to a real modal; this spy proves it is never used.
    confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    confirmSpy.mockRestore();
  });

  it('shows the bulk bar with a live count once a card is selected', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' }), task({ id: 'p2', status: 'pending' })];
    render(<TaskBoardPage />);

    expect(screen.queryByTestId('task-bulk-bar')).toBeNull();

    selectCard('p1');
    expect(screen.getByTestId('task-bulk-bar')).toBeInTheDocument();
    expect(screen.getByText('bulk.selected:1')).toBeInTheDocument();

    selectCard('p2');
    expect(screen.getByText('bulk.selected:2')).toBeInTheDocument();
    // Non-deleted column → cancel + delete; never restore/purge.
    expect(screen.getByTestId('task-bulk-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('task-bulk-delete')).toBeInTheDocument();
    expect(screen.queryByTestId('task-bulk-restore')).toBeNull();
    expect(screen.queryByTestId('task-bulk-purge')).toBeNull();
  });

  it('toggling the same card off empties the selection and hides the bar', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    expect(screen.getByTestId('task-bulk-bar')).toBeInTheDocument();

    selectCard('p1'); // deselect → size 0 → columnKey null
    expect(screen.queryByTestId('task-bulk-bar')).toBeNull();
  });

  it('select-all promotes a partial selection to the full column', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' }), task({ id: 'p2', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1'); // partial (1 of 2)
    expect(screen.getByText('bulk.selected:1')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('task-column-selectall-pending'));
    expect(screen.getByText('bulk.selected:2')).toBeInTheDocument();
  });

  it('prunes selected ids that leave their column (e.g. a WS status change) and collapses when empty', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' }), task({ id: 'p2', status: 'pending' })];
    const { rerender } = render(<TaskBoardPage />);

    selectCard('p1');
    selectCard('p2');
    expect(screen.getByText('bulk.selected:2')).toBeInTheDocument();

    // p1 moves out of pending (live update) → pruned from the selection.
    mocks.board.tasks = [task({ id: 'p1', status: 'in_progress' }), task({ id: 'p2', status: 'pending' })];
    rerender(<TaskBoardPage />);
    expect(screen.getByText('bulk.selected:1')).toBeInTheDocument();

    // both leave pending → selection empties, bar disappears.
    mocks.board.tasks = [task({ id: 'p1', status: 'in_progress' }), task({ id: 'p2', status: 'in_progress' })];
    rerender(<TaskBoardPage />);
    expect(screen.queryByTestId('task-bulk-bar')).toBeNull();
  });

  it('enforces the same-column rule - selecting in another column resets the selection', () => {
    mocks.board.tasks = [
      task({ id: 'p1', status: 'pending' }),
      task({ id: 'p2', status: 'pending' }),
      task({ id: 'c1', status: 'completed' }),
    ];
    render(<TaskBoardPage />);

    selectCard('p1');
    selectCard('p2');
    expect(screen.getByText('bulk.selected:2')).toBeInTheDocument();

    // Selecting a card in the completed column drops the pending selection.
    selectCard('c1');
    expect(screen.getByText('bulk.selected:1')).toBeInTheDocument();
  });

  it('bulk Delete opens the modal (not window.confirm); confirming calls bulkAction(delete)', async () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' }), task({ id: 'p2', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    selectCard('p2');
    fireEvent.click(screen.getByTestId('task-bulk-delete'));

    // Modal shows the delete copy with the live count; no API call and no native confirm yet.
    expect(confirmDialog()).toBeInTheDocument();
    expect(screen.getByText('bulk.confirmDeleteTitle')).toBeInTheDocument();
    expect(screen.getByText('bulk.confirmDelete:2')).toBeInTheDocument();
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(mocks.taskService.bulkAction).not.toHaveBeenCalled();

    confirmInModal('bulk.delete');

    await waitFor(() => {
      expect(mocks.taskService.bulkAction).toHaveBeenCalledWith(['p1', 'p2'], 'delete');
    });
    expect(mocks.board.refresh).toHaveBeenCalled();
    // Selection cleared + modal closed.
    await waitFor(() => expect(screen.queryByTestId('task-bulk-bar')).toBeNull());
    expect(confirmDialog()).toBeNull();
    expect(confirmSpy).not.toHaveBeenCalled();
  });

  it('still clears the selection and refreshes the board when the bulk action rejects', async () => {
    mocks.taskService.bulkAction.mockRejectedValueOnce(new Error('network'));
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    fireEvent.click(screen.getByTestId('task-bulk-delete'));
    confirmInModal('bulk.delete');

    // Error is swallowed; the bar + modal still clear and the board refreshes (resilience).
    await waitFor(() => expect(screen.queryByTestId('task-bulk-bar')).toBeNull());
    expect(confirmDialog()).toBeNull();
    expect(mocks.board.refresh).toHaveBeenCalled();
  });

  it('ignores a second confirm click while the first bulk action is still in flight', () => {
    let resolve: (v: unknown) => void = () => {};
    mocks.taskService.bulkAction.mockReturnValue(new Promise((r) => { resolve = r; }));
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    fireEvent.click(screen.getByTestId('task-bulk-delete')); // opens modal
    const confirmBtn = within(screen.getByRole('dialog')).getByRole('button', { name: 'bulk.delete' });
    fireEvent.click(confirmBtn); // first → in flight (busy guard + disabled)
    fireEvent.click(confirmBtn); // second → ignored

    expect(mocks.taskService.bulkAction).toHaveBeenCalledTimes(1);
    resolve({ action: 'delete', requested: 1, succeeded: 1, failed: 0, results: [] });
  });

  it('does NOT act when the Delete modal is dismissed', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    fireEvent.click(screen.getByTestId('task-bulk-delete'));
    expect(confirmDialog()).toBeInTheDocument();

    dismissModal();

    expect(mocks.taskService.bulkAction).not.toHaveBeenCalled();
    expect(confirmDialog()).toBeNull();
    // Selection survives a dismiss so the user can retry.
    expect(screen.getByTestId('task-bulk-bar')).toBeInTheDocument();
  });

  it('Cancel is also modal-gated - dismissing it does nothing', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    fireEvent.click(screen.getByTestId('task-bulk-cancel'));

    expect(screen.getByText('bulk.confirmCancelTitle')).toBeInTheDocument();
    dismissModal();
    expect(mocks.taskService.bulkAction).not.toHaveBeenCalled();
  });

  it('Delete-permanently is modal-gated - dismissing it does nothing', () => {
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: '2026-05-26T09:00:00.000Z', previousStatus: 'completed' })];
    render(<TaskBoardPage />);

    selectCard('d1');
    fireEvent.click(screen.getByTestId('task-bulk-purge'));

    expect(screen.getByText('bulk.confirmPurgeTitle')).toBeInTheDocument();
    dismissModal();
    expect(mocks.taskService.bulkAction).not.toHaveBeenCalled();
  });

  it('selecting a card does NOT open the detail panel (checkbox stops propagation)', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');

    expect(mocks.board.setSelectedTaskId).not.toHaveBeenCalled();
  });

  it('Restore is NOT modal-gated (non-destructive) - fires immediately with no dialog', async () => {
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: '2026-05-26T09:00:00.000Z', previousStatus: 'completed' })];
    render(<TaskBoardPage />);

    selectCard('d1');
    fireEvent.click(screen.getByTestId('task-bulk-restore'));

    expect(confirmDialog()).toBeNull();
    expect(confirmSpy).not.toHaveBeenCalled();
    await waitFor(() => expect(mocks.taskService.bulkAction).toHaveBeenCalledWith(['d1'], 'restore'));
  });

  it('bulk Cancel routes to bulkAction(cancel) after confirmation', async () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    selectCard('p1');
    fireEvent.click(screen.getByTestId('task-bulk-cancel'));
    confirmInModal('bulk.cancel');

    await waitFor(() => expect(mocks.taskService.bulkAction).toHaveBeenCalledWith(['p1'], 'cancel'));
  });

  it('select-all toggles every card in the column, and Clear empties the selection', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' }), task({ id: 'p2', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.click(screen.getByTestId('task-column-selectall-pending'));
    expect(screen.getByText('bulk.selected:2')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('task-bulk-clear'));
    expect(screen.queryByTestId('task-bulk-bar')).toBeNull();
  });

  it('the Deleted column offers Restore + Delete-permanently (no Cancel/Delete) and routes them', async () => {
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: '2026-05-26T09:00:00.000Z', previousStatus: 'completed' })];
    render(<TaskBoardPage />);

    selectCard('d1');
    expect(screen.getByTestId('task-bulk-restore')).toBeInTheDocument();
    expect(screen.getByTestId('task-bulk-purge')).toBeInTheDocument();
    expect(screen.queryByTestId('task-bulk-cancel')).toBeNull();
    expect(screen.queryByTestId('task-bulk-delete')).toBeNull();

    fireEvent.click(screen.getByTestId('task-bulk-restore'));
    await waitFor(() => expect(mocks.taskService.bulkAction).toHaveBeenCalledWith(['d1'], 'restore'));
  });

  it('Purge confirmation calls bulkAction(purge)', async () => {
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: '2026-05-26T09:00:00.000Z', previousStatus: 'completed' })];
    render(<TaskBoardPage />);

    selectCard('d1');
    fireEvent.click(screen.getByTestId('task-bulk-purge'));
    confirmInModal('bulk.deletePermanently');

    await waitFor(() => expect(mocks.taskService.bulkAction).toHaveBeenCalledWith(['d1'], 'purge'));
  });

  it('shows the remaining-days count on a trashed card (deletedAt + 30d retention)', () => {
    const fiveDaysAgo = new Date(Date.now() - 5 * 86_400_000).toISOString();
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: fiveDaysAgo, previousStatus: 'completed' })];
    render(<TaskBoardPage />);
    // 30-day window minus 5 days elapsed → purges in 25 days.
    expect(screen.getByTestId('task-card-purge-d1')).toHaveTextContent('deleted.purgesIn:25');
  });

  it('shows "purges today" once a trashed card is past the retention window', () => {
    const longAgo = new Date(Date.now() - 40 * 86_400_000).toISOString();
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: longAgo, previousStatus: 'completed' })];
    render(<TaskBoardPage />);
    expect(screen.getByTestId('task-card-purge-d1')).toHaveTextContent('deleted.purgesToday');
  });
});

describe('TaskBoardPage drag onto destructive columns is modal-gated', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('taskboard-hidden-columns', '[]');
    mocks.taskService.softDeleteTasks.mockResolvedValue({ action: 'delete', requested: 1, succeeded: 1, failed: 0, results: [] });
  });
  afterEach(() => { cleanup(); localStorage.clear(); });

  it('drag onto Deleted opens the delete modal; confirming soft-deletes the single card', async () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-p1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-deleted'), { dataTransfer: {} });

    // Modal first - single-card count, no API call yet.
    expect(screen.getByText('bulk.confirmDeleteTitle')).toBeInTheDocument();
    expect(screen.getByText('bulk.confirmDelete:1')).toBeInTheDocument();
    expect(mocks.taskService.softDeleteTasks).not.toHaveBeenCalled();

    confirmInModal('bulk.delete');

    await waitFor(() => expect(mocks.taskService.softDeleteTasks).toHaveBeenCalledWith(['p1']));
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
    expect(confirmDialog()).toBeNull();
  });

  it('dismissing the drag-to-Deleted modal performs no soft-delete', () => {
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-p1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-deleted'), { dataTransfer: {} });
    dismissModal();

    expect(mocks.taskService.softDeleteTasks).not.toHaveBeenCalled();
    expect(confirmDialog()).toBeNull();
  });

  it('ignores a second confirm click while a drag soft-delete is still in flight', () => {
    let resolve: (v: unknown) => void = () => {};
    mocks.taskService.softDeleteTasks.mockReturnValue(new Promise((r) => { resolve = r; }));
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-p1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-deleted'), { dataTransfer: {} });
    const confirmBtn = within(screen.getByRole('dialog')).getByRole('button', { name: 'bulk.delete' });
    fireEvent.click(confirmBtn); // first → in flight (busy latch + disabled)
    fireEvent.click(confirmBtn); // second → ignored

    expect(mocks.taskService.softDeleteTasks).toHaveBeenCalledTimes(1);
    resolve({ action: 'delete', requested: 1, succeeded: 1, failed: 0, results: [] });
  });

  it('still closes the modal and refreshes when a drag soft-delete rejects', async () => {
    mocks.taskService.softDeleteTasks.mockRejectedValueOnce(new Error('network'));
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-p1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-deleted'), { dataTransfer: {} });
    confirmInModal('bulk.delete');

    // The drag closure swallows the rejection; the modal still closes and the board refreshes.
    await waitFor(() => expect(confirmDialog()).toBeNull());
    expect(mocks.board.refresh).toHaveBeenCalled();
  });

  it('drag onto Cancelled opens the cancel modal; confirming routes through moveToCancelled', async () => {
    mocks.taskService.moveToCancelled.mockResolvedValue(undefined);
    mocks.board.tasks = [task({ id: 'p1', status: 'pending' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-p1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-cancelled'), { dataTransfer: {} });

    expect(screen.getByText('bulk.confirmCancelTitle')).toBeInTheDocument();
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();

    confirmInModal('bulk.cancel');

    await waitFor(() => expect(mocks.taskService.moveToCancelled).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'p1', status: 'pending' }),
    ));
  });

  it('restores a card dragged OUT of Deleted onto a normal column via updateTask - no modal', async () => {
    mocks.taskService.updateTask.mockResolvedValue(task({ id: 'd1', status: 'pending' }));
    mocks.board.tasks = [task({ id: 'd1', status: 'deleted', deletedAt: '2026-05-26T09:00:00.000Z', previousStatus: 'completed' })];
    render(<TaskBoardPage />);

    fireEvent.dragStart(screen.getByTestId('task-card-d1'), { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-pending'), { dataTransfer: {} });

    // Restore is non-destructive → fires immediately, no confirmation.
    expect(confirmDialog()).toBeNull();
    await waitFor(() => expect(mocks.taskService.updateTask).toHaveBeenCalledWith('d1', { status: 'pending' }));
    expect(mocks.taskService.softDeleteTasks).not.toHaveBeenCalled();
  });
});
