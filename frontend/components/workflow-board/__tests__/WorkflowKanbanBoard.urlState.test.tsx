// @vitest-environment jsdom
/**
 * The kanban's view (search, sort, modified / trigger / visibility filters) lives in the
 * address, so a reload reopens the board as it was. Pinned both ways: an address carrying the
 * view filters the cards on the first paint, and a change writes the address.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import React from 'react';

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('@/lib/stores/current-org-store', () => ({ useCanMutateInCurrentOrg: () => true }));

const card = (id: string, name: string, visibility: string | null) => ({
  workflowId: id, name, visibility, nodeIcons: [], runCount: 0,
  lastExecutedAt: null, updatedAt: '2026-09-01T00:00:00Z',
});
const emptyCol = { items: [], totalCount: 0, hasMore: false, loading: false };
vi.mock('../useWorkflowBoard', () => ({
  useWorkflowBoard: () => ({
    columns: {
      draft: {
        items: [card('w1', 'Invoice sync', 'PUBLIC'), card('w2', 'Lead triage', 'PRIVATE')],
        totalCount: 2, hasMore: false, loading: false,
      },
      production: emptyCol, needsReview: emptyCol, paused: emptyCol,
    },
    totalCount: 2,
    initialLoading: false,
    errorCode: null,
    dismissError: vi.fn(),
    moveCard: vi.fn(),
    canDrop: () => false,
    pinRequest: null,
    closePinRequest: vi.fn(),
    confirmPin: vi.fn(),
    loadMore: vi.fn(),
  }),
}));
// A select that can be driven: each one renders its value and a button per option.
vi.mock('@/components/ui/select', () => {
  const Ctx = React.createContext<(v: string) => void>(() => {});
  return {
    Select: ({ value, onValueChange, children }: {
      value: string; onValueChange: (v: string) => void; children: React.ReactNode;
    }) => (
      <Ctx.Provider value={onValueChange}>
        <div data-testid="select" data-value={value}>{children}</div>
      </Ctx.Provider>
    ),
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value }: { value: string }) => {
      const pick = React.useContext(Ctx);
      return <button type="button" data-testid={`option-${value}`} onClick={() => pick(value)} />;
    },
  };
});
vi.mock('@/components/chat/CreateWorkflowModal', () => ({ CreateWorkflowModal: () => null }));
vi.mock('../WorkflowBoardCard', () => ({
  WorkflowBoardCard: ({ card: c }: { card: { workflowId: string } }) => (
    <div data-testid={`card-${c.workflowId}`} />
  ),
}));
vi.mock('../PinVersionModal', () => ({ PinVersionModal: () => null }));

import { WorkflowKanbanBoard } from '../WorkflowKanbanBoard';

beforeEach(() => fakeFolderRouter.reset('/en/app/board'));
afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

const selectValues = () => screen.getAllByTestId('select').map((el) => el.getAttribute('data-value'));

describe('WorkflowKanbanBoard - the view lives in the address', () => {
  it('opens in the view the address carries, cards already filtered', () => {
    fakeFolderRouter.navigate(
      '/en/app/board?resource=workflow&q=invoice&sort=name&modified=7d&trigger=webhook&visibility=public',
    );
    render(<WorkflowKanbanBoard source="workflow" />);

    expect(selectValues()).toEqual(expect.arrayContaining(['name', '7d', 'webhook', 'public']));
    // Filtered out by the restored trigger filter (no card has a webhook trigger).
    expect(screen.queryByTestId('card-w1')).toBeNull();
    expect(screen.queryByTestId('card-w2')).toBeNull();
  });

  it('restores the search alone as a filter on the cards', () => {
    fakeFolderRouter.navigate('/en/app/board?resource=workflow&q=invoice');
    render(<WorkflowKanbanBoard source="workflow" />);

    expect(screen.getByTestId('card-w1')).toBeTruthy();
    expect(screen.queryByTestId('card-w2')).toBeNull();
  });

  it('ignores a sort this board does not have', () => {
    // `due_by` is the task board's: left behind by a hand-edited link, it must not reach the sort.
    fakeFolderRouter.navigate('/en/app/board?resource=workflow&sort=due_by');
    render(<WorkflowKanbanBoard source="workflow" />);

    expect(selectValues()).toContain('lastExecutedAt');
    expect(screen.getByTestId('card-w1')).toBeTruthy();
  });

  it('writes a changed sort and filter, keeping the other parameters', () => {
    fakeFolderRouter.navigate('/en/app/board?resource=workflow');
    render(<WorkflowKanbanBoard source="workflow" />);

    fireEvent.click(screen.getAllByTestId('option-name')[0]);
    fireEvent.click(screen.getAllByTestId('option-30d')[0]);

    expect(fakeFolderRouter.search()).toBe('resource=workflow&sort=name&modified=30d');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
  });

  it('writes the search once the typing pauses', () => {
    vi.useFakeTimers();
    fakeFolderRouter.navigate('/en/app/board?resource=workflow');
    render(<WorkflowKanbanBoard source="workflow" />);

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'lead' } });
    expect(fakeFolderRouter.search()).toBe('resource=workflow');
    act(() => { vi.advanceTimersByTime(350); });

    expect(fakeFolderRouter.search()).toBe('resource=workflow&q=lead');
  });
});
