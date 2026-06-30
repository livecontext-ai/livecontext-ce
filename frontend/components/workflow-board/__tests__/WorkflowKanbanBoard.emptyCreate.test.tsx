// @vitest-environment jsdom
/**
 * Empty boards must still render the columns + a CTA (like the task board), instead of the
 * old "No workflows yet" replacement screen. The Workflows tab offers "Create workflow"
 * (→ modal → redirect into the new builder); the Applications tab offers "Install application"
 * (→ marketplace). This pins all three behaviors against the empty-state regression.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';

const push = vi.fn();
// The board uses the locale-aware router so the post-create redirect keeps the locale.
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));

const emptyCol = { items: [], totalCount: 0, hasMore: false, loading: false };
vi.mock('../useWorkflowBoard', () => ({
  useWorkflowBoard: () => ({
    columns: { draft: emptyCol, production: emptyCol, needsReview: emptyCol, paused: emptyCol },
    totalCount: 0,
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
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: () => null,
}));
vi.mock('@/components/chat/CreateWorkflowModal', () => ({
  CreateWorkflowModal: ({ onWorkflowCreated }: { onWorkflowCreated: (id: string) => void }) => (
    <button onClick={() => onWorkflowCreated('new-wf-id')}>confirm-create</button>
  ),
}));

import { WorkflowKanbanBoard } from '../WorkflowKanbanBoard';

beforeEach(() => push.mockClear());
afterEach(() => cleanup());

describe('WorkflowKanbanBoard - empty board still shows columns + CTA', () => {
  it('renders the kanban columns (not the old empty screen) when there are zero workflows', () => {
    render(<WorkflowKanbanBoard source="workflow" />);
    // Columns are present → the board is shown even when empty.
    expect(screen.getByText('columns.draft')).toBeTruthy();
    expect(screen.getByText('columns.production')).toBeTruthy();
    // ...and the old "No workflows yet" replacement screen is gone.
    expect(screen.queryByText('empty.title')).toBeNull();
    expect(screen.getByRole('button', { name: 'actions.createWorkflow' })).toBeTruthy();
  });

  it('Workflows tab: Create workflow → modal → redirects into the new builder', () => {
    render(<WorkflowKanbanBoard source="workflow" />);
    fireEvent.click(screen.getByRole('button', { name: 'actions.createWorkflow' }));
    fireEvent.click(screen.getByRole('button', { name: 'confirm-create' }));
    expect(push).toHaveBeenCalledWith('/app/workflow/new-wf-id');
  });

  it('Applications tab: Install application redirects to the marketplace', () => {
    render(<WorkflowKanbanBoard source="application" />);
    fireEvent.click(screen.getByRole('button', { name: 'actions.installApplication' }));
    expect(push).toHaveBeenCalledWith('/app/marketplace');
  });

  it('Workflows tab does NOT show the install-application CTA', () => {
    render(<WorkflowKanbanBoard source="workflow" />);
    expect(screen.queryByRole('button', { name: 'actions.installApplication' })).toBeNull();
  });
});
