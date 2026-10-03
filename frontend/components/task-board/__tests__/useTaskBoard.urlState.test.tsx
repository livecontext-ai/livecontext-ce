// @vitest-environment jsdom
/**
 * The task board's view (agent filter, search, sort, open task) lives in the address, so a
 * reload reopens the board as it was. These pin both directions: a board opened on an address
 * carrying the view starts in it and asks the server for exactly that, and a change writes the
 * address.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor, cleanup } from '@testing-library/react';

const mocks = vi.hoisted(() => ({
  listTasks: vi.fn(),
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('@/lib/api/orchestrator/task.service', () => ({
  taskService: {
    listTasks: mocks.listTasks,
    getStats: vi.fn().mockResolvedValue(null),
    listStatuses: vi.fn().mockResolvedValue([]),
    listLabels: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgents: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../useTaskPeople', () => ({ useTaskPeople: () => [] }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('../useTaskBoardStream', () => ({
  useTaskBoardStreamStore: (selector: (s: { seq: number; flush: () => unknown[] }) => unknown) =>
    selector({ seq: 0, flush: () => [] }),
  useTaskBoardSubscription: () => {},
  applyStreamEvents: () => ({ tasks: [], stats: null, changed: false }),
}));

import { useTaskBoard } from '../useTaskBoard';

beforeEach(() => {
  fakeFolderRouter.reset('/en/app/board');
  mocks.listTasks.mockReset();
  mocks.listTasks.mockResolvedValue({ tasks: [], total: 0 });
});
afterEach(() => cleanup());

describe('useTaskBoard - the view lives in the address', () => {
  it('opens in the view the address carries and requests exactly that', async () => {
    fakeFolderRouter.navigate('/en/app/board?resource=task&agent=a1&q=invoice&sort=due_by&task=t9');

    const { result } = renderHook(() => useTaskBoard());

    expect(result.current.agentFilter).toBe('a1');
    expect(result.current.searchQuery).toBe('invoice');
    expect(result.current.sortBy).toBe('due_by');
    expect(result.current.selectedTaskId).toBe('t9');
    // The FIRST request already carries the restored search: seeding the debounced copy empty
    // would load the unfiltered board and then load it again 300 ms later.
    await waitFor(() => expect(mocks.listTasks).toHaveBeenCalled());
    expect(mocks.listTasks.mock.calls[0][0]).toMatchObject({
      assignedTo: 'a1', search: 'invoice', sort: 'due_by',
    });
  });

  it('falls back to the default sort on one the board does not have', () => {
    fakeFolderRouter.navigate('/en/app/board?sort=lastExecutedAt');
    const { result } = renderHook(() => useTaskBoard());
    expect(result.current.sortBy).toBe('priority');
  });

  it('writes the agent filter, the sort and the open task, keeping the other parameters', () => {
    fakeFolderRouter.navigate('/en/app/board?resource=task');
    const { result } = renderHook(() => useTaskBoard());

    act(() => result.current.setAgentFilter('a2'));
    act(() => result.current.setSortBy('created_at'));
    act(() => result.current.setSelectedTaskId('t3'));

    expect(fakeFolderRouter.search()).toBe('resource=task&agent=a2&sort=created_at&task=t3');
    // A refinement of the view, not a step: Back leaves the board.
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
  });

  it('removes a parameter when its value goes back to the default', () => {
    fakeFolderRouter.navigate('/en/app/board?agent=a1&task=t9');
    const { result } = renderHook(() => useTaskBoard());

    act(() => result.current.setAgentFilter(null));
    act(() => result.current.setSelectedTaskId(null));

    expect(fakeFolderRouter.search()).toBe('');
  });
});
