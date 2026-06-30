// @vitest-environment jsdom
/**
 * BoardView is the single aggregated board with a Tasks/Applications/Workflows toggle.
 * These pin: (1) the selected resource is driven by ?resource= (deep-linked by the old
 * routes that now redirect here), (2) it falls back to 'task' and normalizes the URL when
 * no/invalid param is present, and (3) switching the toggle swaps the rendered board,
 * updates the URL, and persists the choice. Child boards are stubbed - this is about the
 * shell's routing, not the boards themselves.
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

let searchParams = new URLSearchParams();
const replace = vi.fn();
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParams,
  useRouter: () => ({ replace }),
  usePathname: () => '/app/board',
}));

import { BoardView } from '../BoardView';

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
  localStorage.clear();
});
afterEach(() => cleanup());

describe('BoardView', () => {
  it('defaults to the Tasks board and normalizes the URL when no resource param is present', () => {
    render(<BoardView />);
    expect(screen.getByTestId('task-board')).toBeTruthy();
    expect(replace).toHaveBeenCalledWith('/app/board?resource=task');
  });

  it('honors ?resource=application and renders the applications kanban without re-normalizing', () => {
    searchParams = new URLSearchParams('resource=application');
    render(<BoardView />);
    expect(screen.getByTestId('kanban-application')).toBeTruthy();
    expect(replace).not.toHaveBeenCalled();
  });

  it('falls back to the last-used resource from localStorage when no param is present', () => {
    localStorage.setItem('lc.boardResource', 'workflow');
    render(<BoardView />);
    expect(screen.getByTestId('kanban-workflow')).toBeTruthy();
    expect(replace).toHaveBeenCalledWith('/app/board?resource=workflow');
  });

  it('switching the toggle swaps the board, updates the URL and persists the choice', () => {
    render(<BoardView />);
    expect(screen.getByTestId('task-board')).toBeTruthy();

    fireEvent.click(screen.getByText('toggle.workflow'));

    expect(screen.getByTestId('kanban-workflow')).toBeTruthy();
    expect(replace).toHaveBeenLastCalledWith('/app/board?resource=workflow');
    expect(localStorage.getItem('lc.boardResource')).toBe('workflow');
  });
});
