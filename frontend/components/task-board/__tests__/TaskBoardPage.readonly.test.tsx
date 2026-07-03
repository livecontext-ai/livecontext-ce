// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskBoardPage } from '../TaskBoardPage';
import type { Task } from '@/lib/api/orchestrator/task.types';

/**
 * VIEWER org-role gating (RBAC sweep parity with AgentTable/WorkflowTable):
 * when useCanMutateInCurrentOrg() is false the board is browse-only - no create,
 * no drag, no selection/bulk affordances, no column manager, and the detail
 * panel opens read-only. MEMBER+ (canMutate=true) keeps every affordance.
 */

const mocks = vi.hoisted(() => ({
  org: { canMutate: true },
  detailPanelProps: vi.fn(),
  taskService: {
    updateTask: vi.fn(),
    cancelTask: vi.fn(),
    moveToCancelled: vi.fn(),
    softDeleteTasks: vi.fn(),
    bulkAction: vi.fn(),
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
    selectedTaskId: null as string | null,
    setSelectedTaskId: vi.fn(),
  },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => mocks.org.canMutate,
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
  TaskDetailPanel: (props: Record<string, unknown>) => {
    mocks.detailPanelProps(props);
    return <div data-testid="detail-panel" />;
  },
}));

vi.mock('../CreateTaskDialog', () => ({
  CreateTaskDialog: () => null,
}));

vi.mock('../ColumnManagerDialog', () => ({
  ColumnManagerDialog: () => null,
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

describe('TaskBoardPage VIEWER read-only gating', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.setItem('taskboard-hidden-columns', '[]');
    mocks.board.tasks = [task()];
    mocks.board.selectedTaskId = null;
    mocks.org.canMutate = true;
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('VIEWER: the New Task button is not rendered', () => {
    mocks.org.canMutate = false;
    render(<TaskBoardPage />);
    expect(screen.queryByText('actions.createTask')).toBeNull();
  });

  it('VIEWER: cards are not draggable and dropping mutates nothing', () => {
    mocks.org.canMutate = false;
    render(<TaskBoardPage />);

    const card = screen.getByTestId('task-card-task-1');
    expect(card).toHaveAttribute('draggable', 'false');

    // Even if a drag event fires (jsdom does not honor the draggable attr),
    // the guarded handlers must not stage any mutation or confirmation modal.
    fireEvent.dragStart(card, { dataTransfer: { effectAllowed: 'move' } });
    fireEvent.drop(screen.getByTestId('task-column-cancelled'), { dataTransfer: {} });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
    expect(mocks.taskService.moveToCancelled).not.toHaveBeenCalled();
    expect(mocks.taskService.softDeleteTasks).not.toHaveBeenCalled();
  });

  it('VIEWER: selection checkboxes and the select-all control are hidden (no bulk bar path)', () => {
    mocks.org.canMutate = false;
    render(<TaskBoardPage />);
    expect(screen.queryByTestId('task-card-select-task-1')).toBeNull();
    expect(screen.queryByTestId('task-column-selectall-pending')).toBeNull();
    expect(screen.queryByTestId('task-bulk-bar')).toBeNull();
  });

  it('VIEWER: the column picker stays browsable but the column manager entry is hidden', () => {
    mocks.org.canMutate = false;
    render(<TaskBoardPage />);
    fireEvent.click(screen.getByText('filters.visibleColumns'));
    // Read-only browsing keeps the visibility toggles (per-user, non-mutating):
    // the column title now appears twice (column header + picker entry).
    expect(screen.getAllByText('status.pending').length).toBeGreaterThan(1);
    // ...but the manager (create/rename/delete columns) is gone.
    expect(screen.queryByTestId('open-column-manager')).toBeNull();
  });

  it('VIEWER: the detail panel opens with readOnly=true', () => {
    mocks.org.canMutate = false;
    mocks.board.selectedTaskId = 'task-1';
    render(<TaskBoardPage />);
    expect(screen.getByTestId('detail-panel')).toBeInTheDocument();
    expect(mocks.detailPanelProps).toHaveBeenCalledWith(
      expect.objectContaining({ readOnly: true }),
    );
  });

  it('MEMBER+: create, drag, selection and column manager affordances all stay', () => {
    mocks.org.canMutate = true;
    mocks.board.selectedTaskId = 'task-1';
    render(<TaskBoardPage />);

    expect(screen.getByText('actions.createTask')).toBeInTheDocument();
    expect(screen.getByTestId('task-card-task-1')).toHaveAttribute('draggable', 'true');
    expect(screen.getByTestId('task-card-select-task-1')).toBeInTheDocument();
    expect(screen.getByTestId('task-column-selectall-pending')).toBeInTheDocument();
    fireEvent.click(screen.getByText('filters.visibleColumns'));
    expect(screen.getByTestId('open-column-manager')).toBeInTheDocument();
    expect(mocks.detailPanelProps).toHaveBeenCalledWith(
      expect.objectContaining({ readOnly: false }),
    );
  });
});
