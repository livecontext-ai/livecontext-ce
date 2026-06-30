// @vitest-environment jsdom
/**
 * The board card is shared by the workflow board and the applications board. An APPLICATION
 * row must open the application surface (/app/applications/{sourcePublicationId}) - NOT the
 * workflow builder - while regular workflows keep their builder / live-run routing. This pins
 * that per-card branch (the apps board would otherwise drop users into the workflow editor).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';

const push = vi.fn();
// The card uses the locale-aware router so navigation keeps the active locale.
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
// Application cards render the live showcase thumbnail; stub it so this test stays
// focused on click routing (and avoids the real component's network/IntersectionObserver).
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));

import { WorkflowBoardCard } from '../WorkflowBoardCard';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

function card(overrides: Partial<CardType>): CardType {
  return { workflowId: 'wf-1', name: 'X', runCount: 0, column: 'draft', ...overrides } as CardType;
}

beforeEach(() => push.mockClear());
afterEach(() => cleanup());

describe('WorkflowBoardCard - click routing', () => {
  it('an application card (sourcePublicationId) opens the application surface', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ sourcePublicationId: 'pub-9', column: 'production', productionRunId: 'run-1' })} onDragStart={() => {}} />,
    );
    fireEvent.click(container.firstChild as Element);
    expect(push).toHaveBeenCalledWith('/app/applications/pub-9');
  });

  it('a production workflow card (no publication) opens the live run', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ column: 'production', productionRunId: 'run-1' })} onDragStart={() => {}} />,
    );
    fireEvent.click(container.firstChild as Element);
    expect(push).toHaveBeenCalledWith('/app/workflow/wf-1/run/run-1');
  });

  it('a draft workflow card opens the builder', () => {
    const { container } = render(
      <WorkflowBoardCard card={card({ column: 'draft' })} onDragStart={() => {}} />,
    );
    fireEvent.click(container.firstChild as Element);
    expect(push).toHaveBeenCalledWith('/app/workflow/wf-1');
  });
});
