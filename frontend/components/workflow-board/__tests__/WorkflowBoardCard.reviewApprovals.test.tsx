// @vitest-environment jsdom
/**
 * Needs-review board cards expose a "Review approvals" button that opens the
 * run's approval review modal IN PLACE (no navigation into the workflow), and
 * the production-run status badge renders the awaiting_signal state instead of
 * falling through to the raw enum string.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, cleanup, screen } from '@testing-library/react';

const push = vi.fn();
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
// The modal itself is covered by RunApprovalsDialog.test.tsx - here we only
// assert the card wires it with the production run id.
vi.mock('@/components/approvals/RunApprovalsDialog', () => ({
  RunApprovalsDialog: ({ runId, open }: { runId: string; open: boolean }) =>
    open ? <div data-testid="run-approvals-dialog" data-run-id={runId} /> : null,
}));

import { WorkflowBoardCard } from '../WorkflowBoardCard';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

function card(overrides: Partial<CardType>): CardType {
  return { workflowId: 'wf-1', name: 'X', runCount: 0, column: 'draft', ...overrides } as CardType;
}

beforeEach(() => push.mockClear());
afterEach(() => cleanup());

describe('WorkflowBoardCard - review approvals button', () => {
  it('shows the button on a needsReview card and opens the modal WITHOUT navigating', () => {
    render(
      <WorkflowBoardCard
        card={card({ column: 'needsReview', productionRunId: 'run-7' })}
        onDragStart={() => {}}
      />,
    );
    const btn = screen.getByTestId('board-card-review-approvals');
    fireEvent.click(btn);
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByTestId('run-approvals-dialog').getAttribute('data-run-id')).toBe('run-7');
  });

  it('renders NO button outside the needsReview column', () => {
    render(
      <WorkflowBoardCard
        card={card({ column: 'production', productionRunId: 'run-7' })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.queryByTestId('board-card-review-approvals')).toBeNull();
  });

  it('renders NO button when the card has no production run id', () => {
    render(
      <WorkflowBoardCard
        card={card({ column: 'needsReview', productionRunId: null })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.queryByTestId('board-card-review-approvals')).toBeNull();
  });

  it('also works for application cards (sourcePublicationId set) - review stays in place', () => {
    render(
      <WorkflowBoardCard
        card={card({ column: 'needsReview', productionRunId: 'run-9', sourcePublicationId: 'pub-1' })}
        onDragStart={() => {}}
      />,
    );
    fireEvent.click(screen.getByTestId('board-card-review-approvals'));
    expect(push).not.toHaveBeenCalled();
    expect(screen.getByTestId('run-approvals-dialog').getAttribute('data-run-id')).toBe('run-9');
  });
});

describe('WorkflowBoardCard - awaiting_signal status badge', () => {
  it('renders the needsApproval label instead of the raw enum string', () => {
    render(
      <WorkflowBoardCard
        card={card({ column: 'needsReview', productionRunId: 'run-7', productionRunStatus: 'AWAITING_SIGNAL' })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByText('status.needsApproval')).toBeTruthy();
    expect(screen.queryByText('AWAITING_SIGNAL')).toBeNull();
  });
});
