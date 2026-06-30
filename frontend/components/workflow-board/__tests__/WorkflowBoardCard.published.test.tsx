// @vitest-environment jsdom
/**
 * The workflows board must show the SAME "shared / in review / rejected" marker the /app/workflow
 * list shows, so a published workflow is recognizable on the board (previously it looked identical
 * to a private one). The marker is workflow-only: an application card already renders as an app and
 * must NOT also carry the workflow published marker. This pins both the per-status icon and that
 * application/workflow split.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
// Key-identity translator: title attributes come back as the bare i18n key, so we can assert them.
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));

import { WorkflowBoardCard } from '../WorkflowBoardCard';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

function card(overrides: Partial<CardType>): CardType {
  return { workflowId: 'wf-1', name: 'X', runCount: 0, column: 'draft', ...overrides } as CardType;
}

afterEach(() => cleanup());

describe('WorkflowBoardCard - published marker', () => {
  it('an ACTIVE published workflow shows the "shared" marker', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ isPublished: true, publicationStatus: 'ACTIVE' })} onDragStart={() => {}} />,
    );
    expect(container.querySelector('[title="shared"]')).not.toBeNull();
    expect(container.querySelector('[title="sharedInReview"]')).toBeNull();
    expect(container.querySelector('[title="sharedRejected"]')).toBeNull();
  });

  it('a PENDING_REVIEW workflow shows the "in review" marker (not the shared one)', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ isPublished: false, publicationStatus: 'PENDING_REVIEW' })} onDragStart={() => {}} />,
    );
    expect(container.querySelector('[title="sharedInReview"]')).not.toBeNull();
    expect(container.querySelector('[title="shared"]')).toBeNull();
  });

  it('a REJECTED workflow shows the "rejected" marker', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ isPublished: false, publicationStatus: 'REJECTED' })} onDragStart={() => {}} />,
    );
    expect(container.querySelector('[title="sharedRejected"]')).not.toBeNull();
    expect(container.querySelector('[title="shared"]')).toBeNull();
  });

  it('an unpublished workflow shows no marker', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({})} onDragStart={() => {}} />,
    );
    expect(container.querySelector('[title="shared"]')).toBeNull();
    expect(container.querySelector('[title="sharedInReview"]')).toBeNull();
    expect(container.querySelector('[title="sharedRejected"]')).toBeNull();
  });

  it('an application card does NOT carry the workflow published marker (it renders as an app)', () => {
    const { container } = render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'pub-9', isPublished: true, publicationStatus: 'ACTIVE' })}
        onDragStart={() => {}}
      />,
    );
    expect(container.querySelector('[title="shared"]')).toBeNull();
  });
});
