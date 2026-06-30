// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TaskDetailPanel } from '../TaskDetailPanel';
import type { Task } from '@/lib/api/orchestrator/task.types';
import type { Agent } from '@/lib/api/orchestrator/types';

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
  useTranslations: () => (key: string, values?: Record<string, unknown>) => {
    const messages: Record<string, string> = {
      'actions.approve': 'Approve',
      'actions.cancel': 'Cancel',
      'actions.cancelTask': 'Cancel task',
      'actions.delete': 'Delete task',
      'actions.deleting': 'Deleting',
      'actions.stopAgent': 'Stop agent',
      'actions.stopReviewer': 'Stop reviewer',
      'actions.stopping': 'Stopping',
      'detail.confirmDelete': 'Delete this task permanently?',
      'actions.discardChanges': 'Discard changes',
      'actions.execute': 'Execute Agent',
      'actions.executing': 'Executing',
      'actions.noReviewer': 'No reviewer',
      'actions.rejectReview': 'Reject review',
      'actions.reviewer': 'Reviewer',
      'actions.startTask': 'Start Task',
      'actions.unassign': 'Unassign',
      'actions.humanAssigneeHint': "A teammate won't run this automatically.",
      'assignGroups.agents': 'Agents',
      'assignGroups.people': 'People',
      'detail.activity': 'Activity',
      'detail.advanced': 'Advanced',
      'detail.assignee': 'Assignee',
      'detail.assignedTo': 'Assigned to',
      'detail.you': 'You',
      'detail.someone': 'Unknown',
      'detail.assigneeRequired': 'Assign an agent before starting.',
      'detail.attemptsUsed': `${values?.count ?? 0} used`,
      'detail.completed': 'Completed',
      'detail.context': 'Context',
      'detail.created': 'Created',
      'detail.createdBy': 'Created by',
      'detail.dueBy': 'Due by',
      'detail.events': 'Events',
      'detail.executions': 'Executions',
      'detail.notes': 'Notes',
      'detail.overview': 'Overview',
      'detail.priority': 'Priority',
      'detail.started': 'Started',
      'detail.status': 'Status',
      'detail.subTasks': 'Sub-tasks',
      'detail.unknownAgent': 'Unknown agent',
      'detail.unsavedChanges': 'Unsaved changes',
      'filters.unassigned': 'Unassigned',
      'priority.normal': 'Normal',
      'review.maxAttempts': 'Max review attempts',
      'status.cancelled': 'Cancelled',
      'status.completed': 'Completed',
      'status.failed': 'Failed',
      'status.in_progress': 'In Progress',
      'status.in_review': 'In Review',
      'status.pending': 'Pending',
    };
    return messages[key] ?? key;
  },
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
  AgentPanelContent: ({ agentId }: { agentId: string }) => <div>Agent panel {agentId}</div>,
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

vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const SelectContext = ReactModule.createContext<{
    value?: string;
    onValueChange?: (value: string) => void;
  }>({});

  return {
    Select: ({ value, onValueChange, children }: {
      value?: string;
      onValueChange?: (value: string) => void;
      children: React.ReactNode;
    }) => (
      <SelectContext.Provider value={{ value, onValueChange }}>
        <div>{children}</div>
      </SelectContext.Provider>
    ),
    SelectTrigger: ({ children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) => (
      <button type="button" {...props}>{children}</button>
    ),
    SelectValue: ({ placeholder }: { placeholder?: string }) => {
      const ctx = ReactModule.useContext(SelectContext);
      return <span>{ctx.value ?? placeholder}</span>;
    },
    SelectContent: ({ children }: { children: React.ReactNode }) => <div role="listbox">{children}</div>,
    SelectGroup: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectLabel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, disabled, children }: {
      value: string;
      disabled?: boolean;
      children: React.ReactNode;
    }) => {
      const ctx = ReactModule.useContext(SelectContext);
      return (
        <button
          type="button"
          role="option"
          aria-selected={ctx.value === value}
          disabled={disabled}
          onClick={() => {
            if (!disabled) ctx.onValueChange?.(value);
          }}
        >
          {children}
        </button>
      );
    },
  };
});

const agent: Agent = {
  id: 'agent-1',
  name: 'Worker Agent',
  tenantId: 'tenant-1',
  description: 'Worker used by deterministic task detail tests.',
  avatarUrl: '',
};

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
  mocks.taskService.updateTask.mockImplementation(async (_taskId: string, patch: Partial<Task>) => ({
    ...taskData,
    ...patch,
  }));
  mocks.taskService.approveTask.mockResolvedValue({
    ...taskData,
    status: 'completed',
  });
  mocks.taskService.rejectReviewTask.mockResolvedValue({
    ...taskData,
    status: 'in_progress',
  });

  const onClose = vi.fn();
  const onRefresh = vi.fn();

  render(
    <TaskDetailPanel
      taskId={taskData.id}
      agents={[agent]}
      onClose={onClose}
      onRefresh={onRefresh}
      {...extraProps}
    />,
  );

  return { onClose, onRefresh };
}

describe('TaskDetailPanel contextual task actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  // Regression (live-test 2026-06-24): the header "blocked" badge used task.blockedByIds.length
  // (mere presence), so it stayed lit after a blocker completed - inconsistent with the board,
  // which now counts only non-terminal blockers. The panel resolves blocker statuses via
  // listTasks and shows only the ACTIVE count.
  it('header blocked badge counts only still-active blockers (a completed blocker is dropped)', async () => {
    mocks.taskService.listTasks.mockResolvedValue({ tasks: [
      task({ id: 'blk-done', status: 'completed' }),
      task({ id: 'blk-pending', status: 'pending' }),
    ] });
    renderPanel(task({ id: 'dep', title: 'Dependent task', blockedByIds: ['blk-done', 'blk-pending'] }), {
      statuses: [
        { id: 's1', key: 'pending', label: 'Pending', category: 'pending', color: '', wipLimit: null, isSystem: true, hidden: false, position: 0 },
        { id: 's2', key: 'completed', label: 'Completed', category: 'done', color: '', wipLimit: null, isSystem: true, hidden: false, position: 1 },
      ],
    });
    await screen.findByText('Dependent task');
    // Only the still-pending blocker is active: badge shows 1, not the raw 2 (pre-fix it showed 2).
    await waitFor(() => {
      const summary = screen.getByTestId('task-header-summary');
      expect(summary).toHaveTextContent('1');
      expect(summary).not.toHaveTextContent('2');
    });
  });

  it('header blocked badge disappears once every blocker is terminal', async () => {
    mocks.taskService.listTasks.mockResolvedValue({ tasks: [
      task({ id: 'blk-done', status: 'completed' }),
    ] });
    renderPanel(task({ id: 'dep2', title: 'Fully unblocked task', blockedByIds: ['blk-done'] }), {
      statuses: [
        { id: 's2', key: 'completed', label: 'Completed', category: 'done', color: '', wipLimit: null, isSystem: true, hidden: false, position: 1 },
      ],
    });
    await screen.findByText('Fully unblocked task');
    await waitFor(() => expect(mocks.taskService.listTasks).toHaveBeenCalled());
    // No labels/checklist/estimate and zero ACTIVE blockers -> the summary renders nothing.
    await waitFor(() => expect(screen.queryByTestId('task-header-summary')).toBeNull());
  });

  it('shows a disabled default start action and allows staging in_progress without an assignee', async () => {
    renderPanel(task({ title: 'Unassigned pending task' }));

    await screen.findByText('Unassigned pending task');

    expect(screen.getByRole('button', { name: /^Start Task$/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^Cancel$/i })).toBeVisible();

    const inProgressOption = screen.getByRole('option', { name: /In Progress/i });
    expect(inProgressOption).not.toBeDisabled();
    fireEvent.click(inProgressOption);

    expect(await screen.findByText('Unsaved changes')).toBeVisible();
    expect(screen.getByRole('button', { name: /^Start Task$/i })).toBeDisabled();
    expect(screen.getByText('Assign an agent before starting.')).toBeVisible();
    expect(mocks.taskService.updateTask).not.toHaveBeenCalled();
  });

  it('executes an assigned pending task from the default action', async () => {
    const { onClose, onRefresh } = renderPanel(task({
      assignedToAgentId: agent.id,
      title: 'Assigned pending task',
    }));

    await screen.findByText('Assigned pending task');
    fireEvent.click(screen.getByRole('button', { name: /^Execute Agent$/i }));

    await waitFor(() => {
      expect(mocks.taskService.updateTask).toHaveBeenCalledWith('task-1', { status: 'in_progress' });
    });
    expect(mocks.sidePanel.openTab).toHaveBeenCalledWith(expect.objectContaining({
      id: `agent-${agent.id}`,
      label: agent.name,
    }));
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it('executes an assigned pending task from a staged in_progress status change', async () => {
    const { onClose, onRefresh } = renderPanel(task({
      assignedToAgentId: agent.id,
      title: 'Staged pending task',
    }));

    await screen.findByText('Staged pending task');
    fireEvent.click(screen.getByRole('option', { name: /In Progress/i }));
    expect(await screen.findByText('Unsaved changes')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /^Execute Agent$/i }));

    await waitFor(() => {
      expect(mocks.taskService.updateTask).toHaveBeenCalledWith('task-1', { status: 'in_progress' });
    });
    expect(mocks.sidePanel.openTab).toHaveBeenCalledWith(expect.objectContaining({
      id: `agent-${agent.id}`,
      label: agent.name,
    }));
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it('approves in-review tasks as the current user without impersonating the reviewer agent', async () => {
    const reviewerAgentId = 'reviewer-agent-1';
    const { onClose, onRefresh } = renderPanel(task({
      assignedToAgentId: agent.id,
      reviewerAgentId,
      status: 'in_review',
      title: 'Reviewer-backed approval task',
      result: 'Ready for approval',
    }));

    await screen.findByText('Reviewer-backed approval task');
    fireEvent.click(screen.getByRole('button', { name: /^Approve$/i }));

    await waitFor(() => {
      expect(mocks.taskService.approveTask).toHaveBeenCalled();
    });
    expect(mocks.taskService.approveTask.mock.calls[0]).toEqual(['task-1']);
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it('requests changes on in-review tasks as the current user without impersonating the reviewer agent', async () => {
    const reviewerAgentId = 'reviewer-agent-1';
    const { onClose, onRefresh } = renderPanel(task({
      assignedToAgentId: agent.id,
      reviewerAgentId,
      status: 'in_review',
      title: 'Reviewer-backed request changes task',
      result: 'Needs review',
    }));

    await screen.findByText('Reviewer-backed request changes task');
    fireEvent.click(screen.getByRole('button', { name: /^Reject review$/i }));

    await waitFor(() => {
      expect(mocks.taskService.rejectReviewTask).toHaveBeenCalled();
    });
    expect(mocks.taskService.rejectReviewTask.mock.calls[0]).toEqual(['task-1']);
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  // Regression: cancelling from the panel always hit the cascading cancel endpoint,
  // which no-ops (200, cancelled_count=0) on terminal tasks - staging "Cancelled" on a
  // completed task closed the panel without changing anything. The cancel path must now
  // delegate to moveToCancelled with the task's current status so the service can pick
  // the endpoint that actually transitions it.
  it('cancels a completed task through moveToCancelled with its terminal status', async () => {
    mocks.taskService.moveToCancelled.mockResolvedValue(undefined);
    const { onClose, onRefresh } = renderPanel(task({
      status: 'completed',
      title: 'Completed task to requalify',
      result: 'Done',
      completedAt: '2026-05-26T11:00:00.000Z',
    }));

    await screen.findByText('Completed task to requalify');
    fireEvent.click(screen.getByRole('option', { name: /^Cancelled$/i }));
    expect(await screen.findByText('Unsaved changes')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /^Cancel task$/i }));

    await waitFor(() => {
      expect(mocks.taskService.moveToCancelled).toHaveBeenCalledWith(
        { id: 'task-1', status: 'completed' },
      );
    });
    expect(mocks.taskService.cancelTask).not.toHaveBeenCalled();
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  it('surfaces the error and keeps the panel open when the cancel call fails', async () => {
    mocks.taskService.moveToCancelled.mockRejectedValue(new Error('cancel exploded'));
    const { onClose } = renderPanel(task({ title: 'Task whose cancel fails' }));

    await screen.findByText('Task whose cancel fails');
    fireEvent.click(screen.getByRole('button', { name: /^Cancel$/i }));

    expect(await screen.findByText('cancel exploded')).toBeVisible();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('cancels an active pending task through moveToCancelled from the default cancel action', async () => {
    mocks.taskService.moveToCancelled.mockResolvedValue(undefined);
    const { onClose, onRefresh } = renderPanel(task({ title: 'Pending task to cancel' }));

    await screen.findByText('Pending task to cancel');
    fireEvent.click(screen.getByRole('button', { name: /^Cancel$/i }));

    await waitFor(() => {
      expect(mocks.taskService.moveToCancelled).toHaveBeenCalledWith(
        { id: 'task-1', status: 'pending' },
      );
    });
    expect(onRefresh).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  // Regression: the metadata panel (status / priority / assignee / reviewer + the apply
  // action) used to be `hidden md:block`, so on mobile (<768px) none of it rendered and a
  // phone user could not edit those fields. It must now render at every width (full-width
  // panel on mobile, right sidebar on desktop) - never `hidden`.
  it('renders the status/priority/assignee panel at all viewport widths (not hidden on mobile)', async () => {
    renderPanel(task({ title: 'Mobile-editable task' }));
    await screen.findByText('Mobile-editable task');

    const meta = screen.getByTestId('task-meta-panel');
    // The bug was a `hidden` class that display:none'd the whole rail below md.
    expect(meta).not.toHaveClass('hidden');
    // Full-width panel on mobile, fixed sidebar on desktop.
    expect(meta).toHaveClass('w-full');
    expect(meta).toHaveClass('md:w-56');

    // The three controls the user reported (status, priority, assignee) live inside it.
    expect(meta).toContainElement(screen.getByText('Status'));
    expect(meta).toContainElement(screen.getByText('Priority'));
    expect(meta).toContainElement(screen.getByText('Assignee'));
  });

  // Bug: "Created by" showed the viewer's real identity-provider display name.
  // instead of the actual creator's chosen displayName. It must resolve the creator
  // from the server-enriched task.users map, never the logged-in user.
  it('shows the creator displayName from task.users, never the viewer\'s real name', async () => {
    renderPanel(task({
      createdByAgentId: null,
      createdByUserId: '99',
      users: { '99': { displayName: 'Dana Example', avatarUrl: null } },
      title: 'Human-created task',
    }));

    await screen.findByText('Human-created task');
    expect(screen.getByText('Dana Example')).toBeVisible();
    // The mocked logged-in user is "Test User" - it must NOT leak into the creator slot.
    expect(screen.queryByText('Test User')).toBeNull();
  });

  // Layout: the card-field editors moved OUT of the centre overview tab INTO the
  // right settings rail. Labels stay in the always-visible core; estimate/blockers/
  // checklist live under the collapsible "Advanced" section.
  it('renders labels in the core rail and the rest under the Advanced collapse', async () => {
    renderPanel(task({ title: 'Rail-hosted extras' }));
    await screen.findByText('Rail-hosted extras');

    const scroll = screen.getByTestId('task-meta-scroll');
    // Labels editor is always present in the core section.
    expect(scroll).toContainElement(screen.getByTestId('task-labels-editor'));
    // A task with no advanced data starts collapsed -> the rest is not mounted.
    expect(screen.queryByTestId('task-extras-editor')).toBeNull();

    // Expanding "Advanced" reveals estimate / blockers / checklist in the rail.
    fireEvent.click(screen.getByRole('button', { name: /^Advanced$/i }));
    expect(scroll).toContainElement(screen.getByTestId('task-extras-editor'));
  });

  // A task that already carries advanced config (here: an estimate) auto-opens the
  // Advanced section so it is not hidden behind the collapse - no click needed.
  it('auto-opens the Advanced section when the task already has advanced data', async () => {
    renderPanel(task({ title: 'Has an estimate', estimateMinutes: 45 }));
    await screen.findByText('Has an estimate');
    // The auto-open effect commits after the first render, so await the editor.
    const extras = await screen.findByTestId('task-extras-editor');
    expect(screen.getByTestId('task-meta-scroll')).toContainElement(extras);
  });

  // The auto-open effect keys on several fields, not just estimate - a blocker also opens it.
  it('auto-opens the Advanced section when the task already has blockers', async () => {
    renderPanel(task({ title: 'Has a blocker', blockedByIds: ['x1'] }));
    await screen.findByText('Has a blocker');
    const extras = await screen.findByTestId('task-extras-editor');
    expect(screen.getByTestId('task-meta-scroll')).toContainElement(extras);
  });

  // Zone 0: an actively-running assignee execution surfaces a Stop button in the pinned bar.
  it('shows a Stop agent button in the action bar while an assignee execution is running', async () => {
    renderPanel(task({ title: 'Running task', status: 'in_progress', assignedToAgentId: agent.id, assigneeExecutionId: 'exec-1' }));
    await screen.findByText('Running task');
    const bar = screen.getByTestId('task-action-bar');
    const stop = within(bar).getByRole('button', { name: /^Stop agent$/ });
    fireEvent.click(stop);
    await waitFor(() => expect(mocks.taskService.stopAgentExecution).toHaveBeenCalledWith('task-1', 'assignee'));
  });

  // Zone 3: a cancelled task surfaces a Delete button that hard-deletes after confirm.
  it('hard-deletes a cancelled task from the action bar after confirmation', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    mocks.taskService.hardDeleteTask.mockResolvedValue(undefined);
    const { onClose } = renderPanel(task({ title: 'Trashed task', status: 'cancelled' }));
    await screen.findByText('Trashed task');
    const bar = screen.getByTestId('task-action-bar');
    fireEvent.click(within(bar).getByRole('button', { name: /^Delete task$/ }));
    await waitFor(() => expect(mocks.taskService.hardDeleteTask).toHaveBeenCalledWith('task-1'));
    expect(onClose).toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  // Header summary: at-a-glance card info (labels / checklist / estimate / blockers)
  // mirrors the priority badge. Labels resolve via the `labels` catalog prop.
  it('renders the header card-info summary from the task fields and label catalog', async () => {
    renderPanel(
      task({
        title: 'Rich card',
        labelIds: ['l1'],
        checklist: [
          { id: 'c1', text: 'a', done: true },
          { id: 'c2', text: 'b', done: false },
        ],
        estimateMinutes: 30,
        timeSpentMinutes: 5,
        blockedByIds: ['x1', 'x2'],
      }),
      { labels: [{ id: 'l1', name: 'urgent', color: '#ef4444' }] },
    );
    await screen.findByText('Rich card');

    // Scope to the title's container (the header's flex-1 column) so the checklist
    // fraction here is the header summary's, not the rail editor's identical "1/2".
    const header = screen.getByText('Rich card').closest('div')!;
    expect(within(header).getByTestId('task-header-summary')).toBeInTheDocument();
    expect(within(header).getByText('urgent')).toBeVisible(); // label chip
    expect(within(header).getByText('1/2')).toBeVisible();     // checklist progress
    expect(within(header).getByText('5/30m')).toBeVisible();   // time / estimate
  });

  // The header summary renders NOTHING when the card has no labels/checklist/estimate/
  // blockers (HeaderMetaSummary early-returns null) - no empty chip row.
  it('renders no header summary for a card with no labels/checklist/estimate/blockers', async () => {
    renderPanel(task({ title: 'Bare card' }));
    await screen.findByText('Bare card');
    expect(screen.queryByTestId('task-header-summary')).toBeNull();
  });

  // A single field present shows only that pill (here: blockers, no checklist/estimate).
  it('shows only the blocker pill in the header when only blockers are present', async () => {
    renderPanel(task({ title: 'Blocked-only card', blockedByIds: ['x1', 'x2', 'x3'] }));
    await screen.findByText('Blocked-only card');
    const summary = screen.getByTestId('task-header-summary');
    expect(within(summary).getByText('3')).toBeVisible();        // blocker count
    expect(within(summary).queryByText(/\//)).toBeNull();         // no checklist/estimate fraction
  });

  // Terminal tasks with no staged edits have no contextual action, so the pinned
  // action bar (and its top border) must not render at all.
  it('hides the pinned action bar for a completed task with no staged edits', async () => {
    renderPanel(task({ status: 'completed', title: 'Finished task', result: 'done', completedAt: '2026-05-26T11:00:00.000Z' }));
    await screen.findByText('Finished task');
    expect(screen.queryByTestId('task-action-bar')).toBeNull();
  });

  // The contextual buttons must stay visible however far the settings scroll, so the
  // action bar is a sibling of the scroll region (pinned), never inside it.
  it('keeps the contextual action bar pinned outside the scrollable settings', async () => {
    renderPanel(task({ title: 'Pinned actions task' }));
    await screen.findByText('Pinned actions task');

    const bar = screen.getByTestId('task-action-bar');
    const scroll = screen.getByTestId('task-meta-scroll');
    expect(scroll).not.toContainElement(bar);
    expect(screen.getByTestId('task-meta-panel')).toContainElement(bar);
    expect(screen.getByRole('button', { name: /^Start Task$/i })).toBeVisible();
  });

  // Jira-style: a HUMAN assignee can move across columns but never auto-dispatches an agent.
  it('lets a human assignee move to in_progress without dispatching an agent', async () => {
    renderPanel(task({
      assignedToAgentId: null,
      assignedToUserId: '42',
      users: { '42': { displayName: 'Alice', avatarUrl: null } },
      title: 'Human-assigned task',
    }));

    await screen.findByText('Human-assigned task');
    // The teammate is visible as the assignee (not the viewer, not "Unassigned").
    expect(screen.getAllByText('Alice').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('option', { name: /In Progress/i }));
    const startBtn = await screen.findByRole('button', { name: /^Start Task$/i });
    expect(startBtn).not.toBeDisabled(); // hasAssignee (human) → allowed
    fireEvent.click(startBtn);

    await waitFor(() => expect(mocks.taskService.updateTask).toHaveBeenCalled());
    // No agent conversation panel opens - a person does the work manually.
    expect(mocks.sidePanel.openTab).not.toHaveBeenCalled();
  });
});
