// @vitest-environment jsdom
/**
 * Application board cards must preview the application's live INTERFACE (the same showcase
 * thumbnail the marketplace renders) instead of the workflow-style node icons - while the
 * footer (name / version / run metadata) stays exactly as the workflow card. Regular workflow
 * cards keep the node icons. If the showcase render fails the card falls back to node icons so
 * it never renders empty. This pins all three branches.
 */
import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';

const h = vi.hoisted(() => ({ showcaseSpy: vi.fn(), triggerError: false }));

vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next-intl', () => ({
  useTranslations: () => (k: string, opts?: Record<string, unknown>) => (opts ? `${k}:${JSON.stringify(opts)}` : k),
}));
vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: () => <div data-testid="node-icons" />,
}));
// Stub the showcase thumbnail: record its props, and optionally fire onError to drive
// the fallback branch - done in an effect so we don't setState during the parent's render.
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: { publicationId?: string; runId?: string; interfaceId?: string; onError?: (e: Error) => void }) => {
    h.showcaseSpy(props);
    React.useEffect(() => {
      if (h.triggerError) props.onError?.(new Error('showcase render failed'));
    }, []);
    return <div data-testid="showcase-preview" />;
  },
}));

import { WorkflowBoardCard } from '../WorkflowBoardCard';
import type { WorkflowBoardCard as CardType } from '@/lib/api/orchestrator/types';

function card(overrides: Partial<CardType>): CardType {
  return { workflowId: 'wf-1', name: 'My App', runCount: 0, column: 'draft', ...overrides } as CardType;
}

afterEach(() => {
  cleanup();
  h.showcaseSpy.mockClear();
  h.triggerError = false;
});

describe('WorkflowBoardCard - interface preview', () => {
  it('a LOCAL ACQUIRED application card (no showcase run) previews via the AUTHENTICATED publication-scoped showcase (receipt-gated), not the per-run path', () => {
    // No showcaseRunId/showcaseInterfaceId and not remote → a LOCAL acquired app. It renders through
    // the publication-scoped showcase by publicationId (its run belongs to the publisher → the
    // per-run path would 403 cross-tenant), but via the AUTHENTICATED endpoint so the acquirer's
    // receipt admits it even when the publisher's source publication is private / in-review.
    render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'pub-9', name: 'My App', runCount: 3 })}
        onDragStart={() => {}}
      />,
    );
    // Interface preview shown, node icons NOT shown.
    expect(screen.getByTestId('showcase-preview')).toBeTruthy();
    expect(screen.queryByTestId('node-icons')).toBeNull();
    // Wired to the publication behind the card, authenticated (local) + NOT remote, and to the
    // board-card preview chrome: pagination hidden + inline error UI suppressed (we own the
    // fallback). Pinning these props guards against a silent regression of the showcase routing.
    expect(h.showcaseSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        publicationId: 'pub-9',
        // No per-run ids → publication-scoped render; authenticated ON (local), remote OFF.
        runId: undefined,
        interfaceId: undefined,
        authenticated: true,
        remote: false,
        hidePagination: true,
        suppressErrorUi: true,
        // onError must be wired or the failure fallback below could never fire.
        onError: expect.any(Function),
      }),
    );
    // Footer is unchanged - still shows the name and run count.
    expect(screen.getByText('My App')).toBeTruthy();
    expect(screen.getByText('card.runs:{"count":3}')).toBeTruthy();
  });

  it('a REMOTE (cloud-acquired) application card previews via the cloud proxy (remote=true), not a local render', () => {
    // remote=true → the source publication is a CLOUD id absent from the local catalog (cloud-linked
    // CE acquisition). The card MUST route the showcase read through the cloud proxy; a local render
    // would 404 on the cloud-only pub id and drop the card onto the node-icon tile - the CE-vs-Cloud
    // gap this fixes. authenticated is OFF for the remote branch (the proxy is the cloud's read).
    render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'cloud-pub-1', name: 'Cloud App', remote: true })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByTestId('showcase-preview')).toBeTruthy();
    expect(screen.queryByTestId('node-icons')).toBeNull();
    expect(h.showcaseSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        publicationId: 'cloud-pub-1',
        runId: undefined,
        interfaceId: undefined,
        remote: true,
        authenticated: false,
        hidePagination: true,
        suppressErrorUi: true,
        onError: expect.any(Function),
      }),
    );
  });

  it('an OWN published app (showcase run + interface present) previews via the AUTHENTICATED per-run path, not the public showcase', () => {
    render(
      <WorkflowBoardCard
        card={card({
          sourcePublicationId: 'pub-9',
          showcaseRunId: 'run_show_1',
          showcaseInterfaceId: 'iface-1',
          name: 'My Own App',
        })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByTestId('showcase-preview')).toBeTruthy();
    // Authenticated render: runId + interfaceId are passed and publicationId is OMITTED, so the
    // preview shows even when the publication is private / in-review / rejected (the run is the
    // caller's own) - exactly like /app/applications for an own app.
    expect(h.showcaseSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        runId: 'run_show_1',
        interfaceId: 'iface-1',
        publicationId: undefined,
        hidePagination: true,
        suppressErrorUi: true,
        onError: expect.any(Function),
      }),
    );
  });

  it('a regular workflow card (no publication) keeps the node icons, no interface preview', () => {
    render(
      <WorkflowBoardCard
        card={card({ nodeIcons: [{ type: 'mcp:x' }] as unknown as CardType['nodeIcons'] })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByTestId('node-icons')).toBeTruthy();
    expect(screen.queryByTestId('showcase-preview')).toBeNull();
    expect(h.showcaseSpy).not.toHaveBeenCalled();
  });

  it('falls back to node icons when the showcase render fails, footer preserved', async () => {
    h.triggerError = true;
    render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'pub-9', name: 'My App', nodeIcons: [{ type: 'mcp:x' }] as unknown as CardType['nodeIcons'] })}
        onDragStart={() => {}}
      />,
    );
    // The showcase branch DID mount first (spy called) - proving the fallback is driven by
    // onError, not by node-icons rendering unconditionally.
    expect(h.showcaseSpy).toHaveBeenCalledWith(expect.objectContaining({ publicationId: 'pub-9' }));
    // onError then flips the card to the node-icon fallback so it never renders empty.
    await waitFor(() => expect(screen.getByTestId('node-icons')).toBeTruthy());
    expect(screen.queryByTestId('showcase-preview')).toBeNull();
    // Footer survives the fallback too.
    expect(screen.getByText('My App')).toBeTruthy();
  });

  it('falls back to the default workflow glyph when the showcase fails and there are no node icons', async () => {
    h.triggerError = true;
    const { container } = render(
      <WorkflowBoardCard card={card({ sourcePublicationId: 'pub-9' })} onDragStart={() => {}} />,
    );
    // The showcase branch mounted first, then failed.
    expect(h.showcaseSpy).toHaveBeenCalledWith(expect.objectContaining({ publicationId: 'pub-9' }));
    // No showcase, no WorkflowNodeIcons (none on the card) - the generic glyph renders inside
    // its themed circle so the card is never blank.
    await waitFor(() => expect(screen.queryByTestId('showcase-preview')).toBeNull());
    expect(screen.queryByTestId('node-icons')).toBeNull();
    const glyph = container.querySelector('.bg-theme-secondary');
    expect(glyph).toBeTruthy();
    expect(glyph!.querySelector('svg')).toBeTruthy();
  });
});

// The footer carries a public / private indicator (Globe = public, Lock = private) for the card's
// OWN publication, on BOTH boards. next-intl is mocked to echo keys, so the badge's title is the
// i18n key ('visibilityPublic' / 'visibilityPrivate'); the generic "shared" Globe keeps title 'shared'.
describe('WorkflowBoardCard - public / private visibility marker', () => {
  it('an own application card (PRIVATE) shows the private indicator', () => {
    render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'pub-9', showcaseRunId: 'r', showcaseInterfaceId: 'i', visibility: 'PRIVATE' })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByTitle('visibilityPrivate')).toBeTruthy();
    expect(screen.queryByTitle('visibilityPublic')).toBeNull();
  });

  it('an own application card (PUBLIC) shows the public indicator', () => {
    render(
      <WorkflowBoardCard
        card={card({ sourcePublicationId: 'pub-9', showcaseRunId: 'r', showcaseInterfaceId: 'i', visibility: 'PUBLIC' })}
        onDragStart={() => {}}
      />,
    );
    expect(screen.getByTitle('visibilityPublic')).toBeTruthy();
  });

  it('an acquired application card (no visibility) shows no visibility marker', () => {
    render(
      <WorkflowBoardCard card={card({ sourcePublicationId: 'pub-9' })} onDragStart={() => {}} />,
    );
    expect(screen.queryByTitle('visibilityPublic')).toBeNull();
    expect(screen.queryByTitle('visibilityPrivate')).toBeNull();
    expect(screen.queryByTitle('shared')).toBeNull();
  });

  it('a shared workflow card (PUBLIC) shows the visibility-aware indicator, NOT the generic "shared" globe', () => {
    render(
      <WorkflowBoardCard card={card({ isPublished: true, visibility: 'PUBLIC' })} onDragStart={() => {}} />,
    );
    expect(screen.getByTitle('visibilityPublic')).toBeTruthy();
    expect(screen.queryByTitle('shared')).toBeNull();
  });

  it('an in-review workflow card keeps the review marker; visibility never overrides moderation state', () => {
    render(
      <WorkflowBoardCard card={card({ publicationStatus: 'PENDING_REVIEW', visibility: 'PUBLIC' })} onDragStart={() => {}} />,
    );
    expect(screen.getByTitle('sharedInReview')).toBeTruthy();
    expect(screen.queryByTitle('visibilityPublic')).toBeNull();
  });

  it('a shared workflow with unknown visibility falls back to the generic "shared" globe', () => {
    render(
      <WorkflowBoardCard card={card({ isPublished: true })} onDragStart={() => {}} />,
    );
    expect(screen.getByTitle('shared')).toBeTruthy();
    expect(screen.queryByTitle('visibilityPublic')).toBeNull();
    expect(screen.queryByTitle('visibilityPrivate')).toBeNull();
  });
});
