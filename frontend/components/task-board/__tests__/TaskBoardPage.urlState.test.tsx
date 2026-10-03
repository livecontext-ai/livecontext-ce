// @vitest-environment jsdom
/**
 * The board's client-side filters (label, "my tasks", "blocked") live in the address, so a
 * reload keeps them. Pinned both ways: an address carrying them filters the cards on the first
 * paint, and toggling one writes it.
 */
import '@testing-library/jest-dom/vitest';

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { Task } from '@/lib/api/orchestrator/task.types';

const mocks = vi.hoisted(() => ({
  board: {
    tasks: [] as Task[],
    stats: null,
    agents: [],
    people: [{ userId: 'me', displayName: 'Me', avatarUrl: null, isSelf: true }],
    statuses: [],
    labels: [{ id: 'l1', name: 'Billing', color: null }],
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

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ user: { name: 'Test User' }, avatarUrl: null }),
}));
vi.mock('@/contexts/SidePanelContext', () => ({ useSidePanelSafe: () => null }));
vi.mock('@/components/app/AgentPanelContent', () => ({ AGENT_CONFIGURATION_TAB: 'configuration', AgentPanelContent: () => null }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span /> }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => <span /> }));
vi.mock('@/components/agent-fleet/hooks/useAgentActivityStream', () => ({
  useAgentActivitySubscriber: vi.fn(),
  useAgentActivityStore: () => false,
}));
vi.mock('../taskActivitySubscriptions', () => ({ selectTaskActivityAgentIds: () => [] }));
vi.mock('../useTaskBoard', () => ({ useTaskBoard: () => mocks.board }));
vi.mock('../TaskDetailPanel', () => ({ TaskDetailPanel: () => null }));
vi.mock('../CreateTaskDialog', () => ({ CreateTaskDialog: () => null }));
vi.mock('../ColumnManagerDialog', () => ({ ColumnManagerDialog: () => null }));
vi.mock('@/lib/api/orchestrator/task.service', () => ({ taskService: {} }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <button type="button">{children}</button>,
  SelectValue: () => <span />,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { TaskBoardPage } from '../TaskBoardPage';

function task(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1', tenantId: 'tenant-1', parentTaskId: null,
    createdByAgentId: null, createdByUserId: 'someone',
    assignedToAgentId: null, assignedToUserId: null,
    reviewerAgentId: null, reviewerUserId: null, recurrenceId: null,
    title: 'A task', instructions: '', taskContext: null,
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
  } as Task;
}

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/board');
  mocks.board.tasks = [
    task({ id: 'mine-labelled', assignedToUserId: 'me', labelIds: ['l1'] }),
    task({ id: 'mine-plain', assignedToUserId: 'me' }),
    task({ id: 'theirs-labelled', labelIds: ['l1'] }),
    task({ id: 'blocked', blockedByIds: ['mine-plain'] }),
  ];
});
afterEach(() => cleanup());

const shown = (id: string) => screen.queryByTestId(`task-card-${id}`) !== null;

describe('TaskBoardPage - the filters live in the address', () => {
  it('opens already filtered by the label and "my tasks" the address carries', () => {
    fakeFolderRouter.navigate('/en/app/board?resource=task&label=l1&mine=1');
    render(<TaskBoardPage />);

    expect(shown('mine-labelled')).toBe(true);
    expect(shown('mine-plain')).toBe(false);
    expect(shown('theirs-labelled')).toBe(false);

    fireEvent.click(screen.getByTestId('task-filter-menu'));
    expect(screen.getByTestId('task-filter-mine')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('task-filter-blocked')).toHaveAttribute('aria-pressed', 'false');
  });

  it('opens on the blocked tasks when the address says so', () => {
    fakeFolderRouter.navigate('/en/app/board?blocked=1');
    render(<TaskBoardPage />);

    expect(shown('blocked')).toBe(true);
    expect(shown('mine-plain')).toBe(false);
  });

  it('writes a toggled filter, and removes it when it is switched back off', () => {
    fakeFolderRouter.navigate('/en/app/board?resource=task');
    render(<TaskBoardPage />);

    fireEvent.click(screen.getByTestId('task-filter-menu'));
    fireEvent.click(screen.getByTestId('task-filter-mine'));
    fireEvent.click(screen.getByTestId('task-filter-blocked'));
    expect(fakeFolderRouter.search()).toBe('resource=task&mine=1&blocked=1');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');

    fireEvent.click(screen.getByTestId('task-filter-mine'));
    expect(fakeFolderRouter.search()).toBe('resource=task&blocked=1');
  });
});
