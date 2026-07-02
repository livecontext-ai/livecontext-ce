// @vitest-environment jsdom
/**
 * RBAC hardening (2026-07-02): the VIEWER org role is read-only on the kanban
 * board - dragging a card to another column persists a status change
 * (moveCard), so a VIEWER's drag must never arm, and BOTH header CTAs are
 * hidden: create-workflow mutates directly, and install-application funnels
 * into the acquire endpoints, which the backend rejects for VIEWER (403) -
 * showing it would dead-end. MEMBER keeps everything.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

const push = vi.fn();
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

const orgMutationGate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => orgMutationGate.canMutate,
}));

const moveCard = vi.fn();
const card: CardType = {
  workflowId: 'wf-1',
  name: 'Daily report',
  description: null,
  runCount: 3,
  lastExecutedAt: null,
  updatedAt: '2026-06-01T00:00:00Z',
  nodeIcons: [],
} as unknown as CardType;

const emptyCol = { items: [], totalCount: 0, hasMore: false, loading: false };
vi.mock('../useWorkflowBoard', () => ({
  useWorkflowBoard: () => ({
    columns: {
      draft: { items: [card], totalCount: 1, hasMore: false, loading: false },
      production: emptyCol,
      needsReview: emptyCol,
      paused: emptyCol,
    },
    totalCount: 1,
    initialLoading: false,
    errorCode: null,
    dismissError: vi.fn(),
    moveCard,
    canDrop: () => true, // every move is board-legal; only the role gate may block
    pinRequest: null,
    closePinRequest: vi.fn(),
    confirmPin: vi.fn(),
    loadMore: vi.fn(),
  }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => null,
}));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({
  CreateWorkflowModal: () => <div data-testid="create-workflow-modal" />,
}));
vi.mock('../WorkflowBoardCard', () => ({
  WorkflowBoardCard: ({ card: c, onDragStart }: { card: CardType; onDragStart: () => void }) => (
    <div data-testid={`board-card-${c.workflowId}`} draggable onDragStart={onDragStart} />
  ),
}));

import { WorkflowKanbanBoard } from '../WorkflowKanbanBoard';

/** The KanbanColumn root div (drop target) is the grandparent of the column title span. */
function columnDropTarget(titleKey: string): HTMLElement {
  const title = screen.getByText(titleKey);
  return title.parentElement!.parentElement as HTMLElement;
}

function dragCardOntoProduction() {
  fireEvent.dragStart(screen.getByTestId('board-card-wf-1'));
  const target = columnDropTarget('columns.production');
  // jsdom drag events carry no DataTransfer - stub the field the handler writes.
  const dataTransfer = { dropEffect: '' };
  fireEvent.dragOver(target, { dataTransfer });
  fireEvent.drop(target, { dataTransfer });
}

beforeEach(() => {
  push.mockClear();
  moveCard.mockClear();
  orgMutationGate.canMutate = true;
});
afterEach(() => cleanup());

describe('WorkflowKanbanBoard - VIEWER read-only gating', () => {
  it('MEMBER: dropping a card on another column calls moveCard', () => {
    render(<WorkflowKanbanBoard source="workflow" />);
    dragCardOntoProduction();
    expect(moveCard).toHaveBeenCalledWith(card, 'production');
  });

  it('MEMBER: the create-workflow CTA is shown', () => {
    render(<WorkflowKanbanBoard source="workflow" />);
    expect(screen.getByRole('button', { name: 'actions.createWorkflow' })).toBeTruthy();
  });

  it('MEMBER: the Applications board shows the install-application CTA', () => {
    render(<WorkflowKanbanBoard source="application" />);
    expect(screen.getByRole('button', { name: 'actions.installApplication' })).toBeTruthy();
  });

  it('VIEWER: the drag never arms - dropping on another column does NOT call moveCard', () => {
    orgMutationGate.canMutate = false;
    render(<WorkflowKanbanBoard source="workflow" />);
    dragCardOntoProduction();
    expect(moveCard).not.toHaveBeenCalled();
  });

  it('VIEWER: the create-workflow CTA is hidden', () => {
    orgMutationGate.canMutate = false;
    render(<WorkflowKanbanBoard source="workflow" />);
    expect(screen.queryByRole('button', { name: 'actions.createWorkflow' })).toBeNull();
  });

  it('VIEWER: the install-application CTA is hidden too (acquire endpoints 403 for VIEWER)', () => {
    orgMutationGate.canMutate = false;
    render(<WorkflowKanbanBoard source="application" />);
    expect(screen.queryByRole('button', { name: 'actions.installApplication' })).toBeNull();
  });
});
