/**
 * @vitest-environment jsdom
 *
 * Regression - pins how {@link ApplicationCard} routes its thumbnail preview.
 *
 * Bug ("404 au milieu de la carte"): an ACQUIRED app's run + interface belong to
 * the PUBLISHER, so previewing it through the authenticated per-run endpoint
 * ({@code /interfaces/{id}/render}) is cross-tenant → 404 painted inside the card.
 * The fix routes acquired cards through the PUBLIC, publication-scoped
 * showcase-render by passing {@code publicationId} to {@link ShowcasePreview}
 * (the same cross-tenant-safe path the marketplace card uses), while
 * owned/published cards keep the authenticated own-run render (no publicationId).
 *
 * ShowcasePreview is stubbed to a prop-capture mock - we assert only the prop the
 * routing decision hinges on, not its render output.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, cleanup, fireEvent } from '@testing-library/react';
import * as React from 'react';

const captured = vi.hoisted(() => ({
  /** Props of every ShowcasePreview render, newest last. */
  calls: [] as Array<Record<string, unknown>>,
}));

// The component under test - capture the props the card hands ShowcasePreview.
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: (props: Record<string, unknown>) => {
    captured.calls.push(props);
    return null;
  },
}));

// Lightweight stubs for the rest of the card's children + the page-module deps
// that execute at import time. None affect the preview-routing decision.
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/workflow', () => ({ ShareWorkflowModal: () => null }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isLoading: false }) }));
vi.mock('@/i18n/navigation', () => ({ useRouter: () => ({ push: () => undefined }) }));
vi.mock('@/lib/api', () => ({ orchestratorApi: {} }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: {} }));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({ workflowService: {} }));
vi.mock('@/lib/api/orchestrator/version.service', () => ({ versionService: {} }));
// The page imports the heavy `@/components/workflow` barrel (ShareWorkflowModal), which transitively
// evaluates the aggregated orchestrator API index against the empty service mocks above (import-time
// `.bind` of undefined). The card under test doesn't use it, so stub the barrel to a no-op.
vi.mock('@/components/workflow', () => ({ ShareWorkflowModal: () => null }));

import { ApplicationCard } from '@/components/applications/ApplicationCard';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';

// A publication that CAN preview: it carries a showcase run + interface so
// `canPreview` is satisfied and ShowcasePreview actually mounts.
const showcasedPublication = {
  id: 'pub-1',
  title: 'Demo App',
  description: 'desc',
  status: 'ACTIVE',
  publisherId: 'publisher-x',
  publisherName: 'Publisher X',
  showcaseRunId: 'showcase_abc',
  showcaseInterfaceId: 'iface-1',
  planVersion: 1,
  nodeIcons: [],
} as unknown as WorkflowPublication;

const noop = () => undefined;

describe('ApplicationCard - acquired preview avoids the cross-tenant 404', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  it('acquired card passes publicationId → public publication-scoped showcase-render', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    // The fix: routing the acquired preview through the public endpoint.
    expect(captured.calls[0].publicationId).toBe('pub-1');
    // And it tells ShowcasePreview to own the fallback on a no-snapshot miss.
    expect(captured.calls[0].suppressErrorUi).toBe(true);
    expect(typeof captured.calls[0].onError).toBe('function');
  });

  it('owned/published card omits publicationId → keeps the authenticated own-run render', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    // Pre-fix the page passed no publicationId for ANY source; the regression is
    // specifically that acquired (above) now does while published still does not.
    expect(captured.calls[0].publicationId).toBeUndefined();
    expect(captured.calls[0].suppressErrorUi).toBeFalsy();
  });
});

// Bug ("on voit l'application créée mais pas les data"): a CLOUD-acquired app's
// showcase clone lives on the cloud, so rendering its preview through the LOCAL
// publication-scoped showcase-render 404s and the card falls back to the empty
// cover tile. The fix threads the publication's `remote` flag into ShowcasePreview
// so the read routes through the cloud-parity remote by-id proxy.
describe('ApplicationCard - cloud-acquired preview routes through the remote proxy', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  it('acquired + remote → ShowcasePreview gets remote=true (cloud showcase-render)', () => {
    render(
      <ApplicationCard
        publication={{ ...showcasedPublication, remote: true } as unknown as WorkflowPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].publicationId).toBe('pub-1');
    expect(captured.calls[0].remote).toBe(true);
  });

  it('acquired + local (no remote flag) → ShowcasePreview keeps remote falsy (local render)', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].remote).toBeFalsy();
  });
});

// Bug ("on affiche une vue workflow et non l'interface"): an acquired app whose
// publisher unpublished / privatised the source publication made the ANONYMOUS
// /by-id showcase-render 403, so the card dropped to the node-icon cover tile.
// Fix: a LOCAL (non-remote) acquired card reads the showcase through the
// AUTHENTICATED endpoint (authenticated=true) so the receipt bypass keeps the
// preview working. Remote (cloud-linked CE) keeps the anonymous by-id proxy.
describe('ApplicationCard - acquired preview uses the authenticated showcase-render', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  it('acquired + local → ShowcasePreview gets authenticated=true (receipt bypass)', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].publicationId).toBe('pub-1');
    expect(captured.calls[0].authenticated).toBe(true);
    expect(captured.calls[0].remote).toBeFalsy();
  });

  it('acquired + remote → authenticated falsy (cloud-linked CE keeps the by-id proxy)', () => {
    render(
      <ApplicationCard
        publication={{ ...showcasedPublication, remote: true } as unknown as WorkflowPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].remote).toBe(true);
    expect(captured.calls[0].authenticated).toBeFalsy();
  });

  it('owned/published → authenticated falsy (keeps the authenticated own-run render path)', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );

    expect(captured.calls.length).toBeGreaterThan(0);
    expect(captured.calls[0].authenticated).toBeFalsy();
  });
});

describe('ApplicationCard - "New" vs "Installed" badge', () => {
  // next-intl is mocked to echo the key, so the badge text is the i18n key:
  // tApp('new') -> 'new', tApp('installed') -> 'installed', else 'shared'.
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  it('a just-acquired app reads "New" (not "Installed")', () => {
    const { container } = render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        acquiredAt={new Date().toISOString()}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).toContain('new');
    expect(container.textContent).not.toContain('installed');
  });

  it('an app acquired long ago reads "Installed"', () => {
    const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString();
    const { container } = render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        acquiredAt={threeDaysAgo}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).toContain('installed');
    expect(container.textContent).not.toContain('new');
  });

  it('an acquired app with no acquiredAt falls back to "Installed" (never errors on a missing date)', () => {
    const { container } = render(
      <ApplicationCard
        publication={showcasedPublication}
        source="acquired"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).toContain('installed');
    expect(container.textContent).not.toContain('new');
  });

  it('owned/published apps show neither "New" nor "Installed"', () => {
    const { container } = render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        acquiredAt={new Date().toISOString()}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).not.toContain('installed');
    expect(container.textContent).not.toContain('new');
  });
});

// Bug ("ça se met live mais quand je clique c'est pas live"): a published app's
// underlying workflow can be pinned (green "Live") while its publication is
// still PENDING_REVIEW (amber "Pending review") - the card showed BOTH, which
// reads as a contradiction (live yet not yet available on the marketplace).
// Fix: a PUBLISHED app only shows "Live" once approved (status === 'ACTIVE');
// while pending/rejected the status badge is the sole truth. Acquired apps are
// the acquirer's own pinned instance → the pin always means live there.
// next-intl is mocked to echo keys: tApp('live') -> 'live', t('pendingReview')
// -> 'pendingReview' (no 'live' substring), t('rejected') -> 'rejected'.
describe('ApplicationCard - "Live" badge reflects publication review status', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  const pinned = (status: string) =>
    ({ ...showcasedPublication, status } as unknown as WorkflowPublication);

  it('published + pinned + ACTIVE → shows Live', () => {
    const { container } = render(
      <ApplicationCard
        publication={pinned('ACTIVE')}
        source="published"
        pinnedVersion={3}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).toContain('live');
  });

  it('published + pinned + PENDING_REVIEW → NO Live, only the pending badge', () => {
    const { container } = render(
      <ApplicationCard
        publication={pinned('PENDING_REVIEW')}
        source="published"
        pinnedVersion={3}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).not.toContain('live');
    expect(container.textContent).toContain('pendingReview');
  });

  it('published + pinned + REJECTED → NO Live', () => {
    const { container } = render(
      <ApplicationCard
        publication={pinned('REJECTED')}
        source="published"
        pinnedVersion={3}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).not.toContain('live');
  });

  it('published + ACTIVE but NOT pinned → NO Live (badge still needs a pin)', () => {
    const { container } = render(
      <ApplicationCard
        publication={pinned('ACTIVE')}
        source="published"
        pinnedVersion={null}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).not.toContain('live');
  });

  it('acquired + pinned → shows Live regardless of publication status', () => {
    const { container } = render(
      <ApplicationCard
        publication={pinned('PENDING_REVIEW')}
        source="acquired"
        pinnedVersion={3}
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(container.textContent).toContain('live');
  });
});

// The footer carries a public / private indicator for the viewer's OWN published apps so they can
// tell a private app from a public one at a glance. Acquired apps carry the PUBLISHER's visibility,
// not the viewer's, so they get no marker. next-intl is mocked to echo keys, so the badge's title
// is the i18n key ('visibilityPublic' / 'visibilityPrivate').
describe('ApplicationCard - visibility marker (own published apps only)', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  const withVis = (visibility: string) =>
    ({ ...showcasedPublication, visibility } as unknown as WorkflowPublication);

  it('published + PUBLIC → public indicator', () => {
    const { container } = render(
      <ApplicationCard publication={withVis('PUBLIC')} source="published" isSelected={false} onToggleSelect={noop} />,
    );
    expect(container.querySelector('[title="visibilityPublic"]')).toBeTruthy();
    expect(container.querySelector('[title="visibilityPrivate"]')).toBeNull();
  });

  it('published + PRIVATE → private indicator', () => {
    const { container } = render(
      <ApplicationCard publication={withVis('PRIVATE')} source="published" isSelected={false} onToggleSelect={noop} />,
    );
    expect(container.querySelector('[title="visibilityPrivate"]')).toBeTruthy();
    expect(container.querySelector('[title="visibilityPublic"]')).toBeNull();
  });

  it('acquired → no visibility marker (the marker reflects the viewer\'s own setting only)', () => {
    const { container } = render(
      <ApplicationCard publication={withVis('PUBLIC')} source="acquired" isSelected={false} onToggleSelect={noop} />,
    );
    expect(container.querySelector('[title="visibilityPublic"]')).toBeNull();
    expect(container.querySelector('[title="visibilityPrivate"]')).toBeNull();
  });
});

// The favorite star lets a user star/unstar an app from the listing. next-intl is
// mocked to echo keys, so the toggle title/aria-label is tApp('favorite') ->
// 'favorite' (not yet favorited) or tApp('unfavorite') -> 'unfavorite' (favorited).
describe('ApplicationCard - favorite star', () => {
  beforeEach(() => {
    captured.calls = [];
    cleanup();
  });

  it('renders NO star when onToggleFavorite is not provided', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
      />,
    );
    expect(document.querySelector('[title="favorite"]')).toBeNull();
    expect(document.querySelector('[title="unfavorite"]')).toBeNull();
  });

  it('not-favorited → shows the "favorite" (add) action', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
        isFavorite={false}
        onToggleFavorite={noop}
      />,
    );
    expect(document.querySelector('[title="favorite"]')).toBeTruthy();
    expect(document.querySelector('[title="unfavorite"]')).toBeNull();
  });

  it('favorited → shows the "unfavorite" (remove) action with a filled star', () => {
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
        isFavorite
        onToggleFavorite={noop}
      />,
    );
    const btn = document.querySelector('[title="unfavorite"]');
    expect(btn).toBeTruthy();
    expect(btn?.getAttribute('aria-pressed')).toBe('true');
    // The star fills when favorited (visual affordance).
    expect(btn?.querySelector('svg')?.getAttribute('class')).toContain('fill-current');
  });

  it('clicking the star toggles favorite with the publication id and does NOT trigger the card click', () => {
    const onToggleFavorite = vi.fn();
    const onCardClick = vi.fn();
    render(
      <ApplicationCard
        publication={showcasedPublication}
        source="published"
        isSelected={false}
        onToggleSelect={noop}
        onCardClick={onCardClick}
        isFavorite={false}
        onToggleFavorite={onToggleFavorite}
      />,
    );

    fireEvent.click(document.querySelector('[title="favorite"]')!);

    expect(onToggleFavorite).toHaveBeenCalledWith('pub-1');
    // stopPropagation keeps the card from navigating when starring.
    expect(onCardClick).not.toHaveBeenCalled();
  });
});
