// @vitest-environment jsdom
/**
 * BoardView is the single aggregated board with a Tasks/Applications/Workflows toggle.
 * These pin: (1) the selected resource is driven by ?resource= (deep-linked by the old
 * routes that now redirect here), (2) with no or an invalid param it falls back to the
 * last-used choice, else 'task', and (3) switching the toggle swaps the rendered board,
 * writes the address as a step Back can undo, drops the parameters of the board being left
 * and persists the choice. Child boards are stubbed - this is about the shell's routing, not
 * the boards themselves.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

vi.mock('@/components/task-board/TaskBoardPage', () => ({ TaskBoardPage: () => <div data-testid="task-board" /> }));
vi.mock('@/components/workflow-board/WorkflowKanbanBoard', () => ({
  WorkflowKanbanBoard: ({ source }: { source?: string }) => <div data-testid={`kanban-${source ?? 'workflow'}`} />,
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

import { BoardView } from '../BoardView';

beforeEach(() => {
  fakeFolderRouter.reset('/app/board');
  localStorage.clear();
});
afterEach(() => cleanup());

describe('BoardView', () => {
  it('defaults to the Tasks board and names it in the address, so the link says what it shows', () => {
    render(<BoardView />);
    expect(screen.getByTestId('task-board')).toBeTruthy();
    // `replace`: the address is being completed, not left, so Back does not return to it.
    expect(fakeFolderRouter.navigations).toEqual([{ url: '/app/board?resource=task', method: 'replace' }]);
  });

  it('honors ?resource=application and renders the applications kanban', () => {
    fakeFolderRouter.navigate('/app/board?resource=application');
    render(<BoardView />);
    expect(screen.getByTestId('kanban-application')).toBeTruthy();
  });

  it('keeps the redirect target of the old Tasks route on the Tasks board', () => {
    // /app/tasks redirects to /app/board?resource=task, whatever was used last.
    localStorage.setItem('lc.boardResource', 'workflow');
    fakeFolderRouter.navigate('/app/board?resource=task');
    render(<BoardView />);
    expect(screen.getByTestId('task-board')).toBeTruthy();
  });

  it('falls back to the last-used resource from localStorage when no param is present', () => {
    localStorage.setItem('lc.boardResource', 'workflow');
    render(<BoardView />);
    expect(screen.getByTestId('kanban-workflow')).toBeTruthy();
    // And says so in the address: copied as it stands, the link opens this same board for
    // someone whose own last-used board is another one.
    expect(fakeFolderRouter.search()).toBe('resource=workflow');
  });

  it('falls back to the last-used resource on a resource the board does not know', () => {
    localStorage.setItem('lc.boardResource', 'application');
    fakeFolderRouter.navigate('/app/board?resource=nope');
    render(<BoardView />);
    expect(screen.getByTestId('kanban-application')).toBeTruthy();
  });

  it('switching the toggle swaps the board, writes the address and persists the choice', () => {
    render(<BoardView />);
    expect(screen.getByTestId('task-board')).toBeTruthy();

    fireEvent.click(screen.getByText('toggle.workflow'));

    expect(screen.getByTestId('kanban-workflow')).toBeTruthy();
    // A push, so Back returns to the board the user came from.
    expect(fakeFolderRouter.navigations.at(-1)).toEqual({
      url: '/app/board?resource=workflow',
      method: 'push',
    });
    expect(localStorage.getItem('lc.boardResource')).toBe('workflow');
  });

  it('drops the view of the board being left, and keeps what is not a board parameter', () => {
    // Carried over, the task board's search would filter the workflows by a text typed
    // for tasks, and `sort=due_by` is not even a sort the kanban has.
    fakeFolderRouter.navigate(
      '/app/board?resource=task&q=invoice&sort=due_by&agent=a1&task=t1&label=l1&mine=1&blocked=1&other=kept',
    );
    render(<BoardView />);

    fireEvent.click(screen.getByText('toggle.workflow'));

    expect(fakeFolderRouter.search()).toBe('resource=workflow&other=kept');
  });

  it('does not touch the address when the board already on screen is clicked again', () => {
    fakeFolderRouter.navigate('/app/board?q=invoice');
    render(<BoardView />);
    fakeFolderRouter.navigations.length = 0;

    fireEvent.click(screen.getByText('toggle.task'));

    expect(fakeFolderRouter.navigations).toEqual([]);
    // The filter is still there: only a SWITCH drops the view of the board being left.
    expect(fakeFolderRouter.search()).toBe('q=invoice&resource=task');
  });
});
