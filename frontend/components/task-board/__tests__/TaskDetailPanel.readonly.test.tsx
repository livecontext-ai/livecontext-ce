// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskDetailPanel } from '../TaskDetailPanel';
import type { Task } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';

/**
 * VIEWER org-role gating of the detail panel: with readOnly=true (threaded from
 * TaskBoardPage's useCanMutateInCurrentOrg gate) the panel is browse-only - no
 * pinned action bar (execute/cancel/approve/delete/stop), no note composer, no
 * extras editors, no inline title/instructions editing. readOnly=false keeps
 * every affordance (regression control).
 */

const mocks = vi.hoisted(() => ({
  taskService: {
    getTask: vi.fn(),
    getTaskEvents: vi.fn(),
    getTaskChildren: vi.fn(),
    getTaskExecutions: vi.fn(),
    updateTask: vi.fn(),
    cancelTask: vi.fn(),
    moveToCancelled: vi.fn(),
    addNote: vi.fn(),
    hardDeleteTask: vi.fn(),
    approveTask: vi.fn(),
    rejectReviewTask: vi.fn(),
    stopAgentExecution: vi.fn(),
    listTasks: vi.fn(),
  },
  sidePanel: {
    openTab: vi.fn(),
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
  useSidePanelSafe: () => mocks.sidePanel,
}));

vi.mock('@/components/app/AgentPanelContent', () => ({
  AGENT_CONVERSATION_TAB: 'conversation',
  AgentPanelContent: () => null,
}));

vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <span>{name ?? 'Agent'}</span>,
}));

vi.mock('@/components/agent-fleet/LoadOlderSentinel', () => ({
  LoadOlderSentinel: () => null,
}));

vi.mock('@/hooks/agent/useExecutionPagedResource', () => ({
  useExecutionPagedResource: () => ({
    items: [],
    isLoading: false,
    isLoadingMore: false,
    hasMore: false,
    loadMore: vi.fn(),
    refresh: vi.fn(),
  }),
}));

vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: {
    getExecutionConversationPaged: vi.fn(),
    getExecutionToolCallsPaged: vi.fn(),
  },
}));

vi.mock('@/lib/api/orchestrator/task.service', () => ({
  taskService: mocks.taskService,
}));

// Marker stub so absence/presence of the write-side editors is directly assertable.
vi.mock('../TaskExtrasEditor', () => ({
  TaskExtrasEditor: () => <div data-testid="extras-editor" />,
}));

vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) => (
    <button type="button" data-testid="select-trigger" {...props}>{children}</button>
  ),
  SelectValue: ({ placeholder }: { placeholder?: string }) => <span>{placeholder}</span>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectGroup: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectLabel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

const agent: Agent = {
  id: 'agent-1',
  name: 'Worker Agent',
  tenantId: 'tenant-1',
  description: 'Worker used by deterministic read-only tests.',
  avatarUrl: '',
};

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    tenantId: 'tenant-1',
    parentTaskId: null,
    createdByAgentId: null,
    createdByUserId: 'tenant-1',
    assignedToAgentId: 'agent-1',
    assignedToUserId: null,
    reviewerAgentId: null,
    reviewerUserId: null,
    recurrenceId: null,
    title: 'Pending task',
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

function renderPanel(taskData: Task, extraProps: Partial<React.ComponentProps<typeof TaskDetailPanel>> = {}) {
  mocks.taskService.getTask.mockResolvedValue(taskData);
  mocks.taskService.getTaskEvents.mockResolvedValue({ content: [] });
  mocks.taskService.getTaskChildren.mockResolvedValue([]);
  mocks.taskService.getTaskExecutions.mockResolvedValue({ content: [] });
  mocks.taskService.listTasks.mockResolvedValue({ tasks: [] });

  render(
    <TaskDetailPanel
      taskId={taskData.id}
      agents={[agent]}
      onClose={vi.fn()}
      onRefresh={vi.fn()}
      {...extraProps}
    />,
  );
}

describe('TaskDetailPanel readOnly (VIEWER) gating', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('readOnly: no pinned action bar, no extras editors, disabled meta selects', async () => {
    renderPanel(task(), { readOnly: true });
    await screen.findByText('Pending task');

    // Zone 4 (execute/cancel) would render for a pending task - gated away entirely.
    expect(screen.queryByTestId('task-action-bar')).toBeNull();
    // Write-side label/estimate/blocker/checklist editors are not rendered.
    expect(screen.queryByTestId('extras-editor')).toBeNull();
    // Status / priority / assignee selects are disabled.
    for (const trigger of screen.getAllByTestId('select-trigger')) {
      expect(trigger).toBeDisabled();
    }
  });

  it('readOnly: the note composer is hidden and the subtask creator is gone', async () => {
    renderPanel(task(), { readOnly: true });
    await screen.findByText('Pending task');

    expect(screen.queryByText('actions.createSubtask')).toBeNull();

    fireEvent.click(screen.getByText('detail.notes'));
    expect(screen.queryByPlaceholderText('detail.addNotePlaceholder')).toBeNull();
  });

  it('readOnly: the title is not inline-editable', async () => {
    renderPanel(task(), { readOnly: true });
    const title = await screen.findByText('Pending task');
    fireEvent.click(title);
    expect(screen.queryByDisplayValue('Pending task')).toBeNull();
  });

  it('default (writable): action bar, extras editors, composer and enabled selects all stay', async () => {
    renderPanel(task());
    await screen.findByText('Pending task');

    expect(screen.getByTestId('task-action-bar')).toBeInTheDocument();
    expect(screen.getAllByTestId('extras-editor').length).toBeGreaterThan(0);
    for (const trigger of screen.getAllByTestId('select-trigger')) {
      expect(trigger).not.toBeDisabled();
    }
    expect(screen.getByText('actions.createSubtask')).toBeInTheDocument();

    fireEvent.click(screen.getByText('detail.notes'));
    expect(screen.getByPlaceholderText('detail.addNotePlaceholder')).toBeInTheDocument();
  });
});
