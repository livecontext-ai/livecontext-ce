// @vitest-environment jsdom
/**
 * Personal "Favorites" view of the Home highlights row.
 *
 * The row shows the admin-curated Highlights by default; once the signed-in user
 * has favorited at least one app it leads with Favorites and exposes a hover
 * toggle to switch back. With zero favorites there is NO toggle (Highlights is
 * the only mode). Favorited cards open the app in the user's library, not the
 * marketplace preview.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isReady: true }),
}));

// Cloud edition (not CE) - the CE gating is covered by HighlightedApps.ce.test.tsx.
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));

vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ isLoading: false, isCloudLinked: false, isInstallCloudLinked: false }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string | null }) => unknown) => sel({ currentOrgId: 'org1' }),
}));

const orchestratorApiMock = vi.hoisted(() => ({ getMarketplacePublications: vi.fn() }));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

const publicationServiceMock = vi.hoisted(() => ({
  getHighlights: vi.fn(),
  getRemoteHighlights: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  getLandingSnapshot: vi.fn(),
  getFavorites: vi.fn(),
  getAcquiredApplicationsPage: vi.fn(),
  getPublicationByIdPublic: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: publicationServiceMock }));

// Native workflow favorites (acquired apps favorite their local clone, not the cloud pub).
const favoriteServiceMock = vi.hoisted(() => ({ getFavoriteIds: vi.fn() }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: favoriteServiceMock }));

vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => <span /> }));
// Expose the props the owner-aware preview fix toggles: the authenticated owner
// render omits publicationId; the public publication-scoped render sets it. The
// mock can also simulate the authenticated render failing (acquired favorite,
// cross-tenant) so the onError -> public-render fallback is assertable.
const showcaseState = vi.hoisted(() => ({ simulateAuthFailure: false }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: ({ publicationId, runId, onError, suppressErrorUi }: { publicationId?: string; runId?: string; onError?: (e: Error) => void; suppressErrorUi?: boolean }) => {
    React.useEffect(() => {
      if (showcaseState.simulateAuthFailure && publicationId === undefined && onError) {
        onError(new Error('auth render failed'));
      }
    }, [publicationId, onError]);
    return (
      <div
        data-testid="showcase"
        data-publication-id={publicationId ?? ''}
        data-run-id={runId ?? ''}
        data-has-onerror={onError ? 'y' : 'n'}
        data-suppress={suppressErrorUi ? 'y' : 'n'}
      />
    );
  },
}));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => <div /> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => <span /> }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span /> }));

import { HighlightedApps } from '../HighlightedApps';

const CURATED = { id: 'hl-1', title: 'Curated Highlight', displayMode: 'APPLICATION', creditsPerUse: 0, showcaseRunId: 'run-h', showcaseInterfaceId: 'iface-h' };
const FAVORITE = { id: 'fav-1', title: 'My Favorite App', displayMode: 'APPLICATION', creditsPerUse: 0, showcaseRunId: 'run-1', showcaseInterfaceId: 'iface-1' };

const MODE_KEY = 'lc.home.highlightMode';

beforeEach(() => {
  vi.clearAllMocks();
  // The toggle now persists the last pick to localStorage; clear it between tests
  // so a write in one case can never leak into the next.
  localStorage.clear();
  showcaseState.simulateAuthFailure = false;
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getHighlights.mockResolvedValue({
    displayMode: 'APPLICATION',
    highlights: [{ rank: 1, publication: CURATED }],
  });
  publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] });
  publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 25 });
  publicationServiceMock.getPublicationByIdPublic.mockResolvedValue({});
  favoriteServiceMock.getFavoriteIds.mockResolvedValue([]);
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
});

afterEach(() => cleanup());

describe('HighlightedApps - Favorites mode', () => {
  it('with NO favorites: shows Highlights and renders NO mode toggle', async () => {
    render(<HighlightedApps />);

    expect(await screen.findByText('Curated Highlight')).toBeInTheDocument();
    // The toggle is not rendered at all when there is nothing to switch to.
    expect(screen.queryByRole('button', { name: /favorites/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^title$/i })).not.toBeInTheDocument();
  });

  it('with favorites: defaults to Favorites, shows the toggle, and links favorited cards to the app page', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);

    // Favorites-first: the favorited app leads, the curated highlight is not shown.
    expect(await screen.findByText('My Favorite App')).toBeInTheDocument();
    expect(screen.queryByText('Curated Highlight')).not.toBeInTheDocument();

    // The toggle is present, with Favorites active.
    const favBtn = screen.getByRole('button', { name: /favorites/i });
    expect(favBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /^title$/i })).toHaveAttribute('aria-pressed', 'false');

    // A favorited card opens the app in the user's library (not the marketplace preview).
    const card = screen.getByText('My Favorite App').closest('a');
    expect(card).toHaveAttribute('href', '/app/applications/fav-1');
  });

  it('toggling to Highlights swaps the rendered set', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);
    await screen.findByText('My Favorite App');

    fireEvent.click(screen.getByRole('button', { name: /^title$/i }));

    await waitFor(() => expect(screen.getByText('Curated Highlight')).toBeInTheDocument());
    expect(screen.queryByText('My Favorite App')).not.toBeInTheDocument();
    // A highlight card opens the marketplace preview.
    expect(screen.getByText('Curated Highlight').closest('a')).toHaveAttribute('href', '/app/marketplace/hl-1/preview');
  });

  // Regression: a favorited app is the user's OWN library app, so its preview must
  // render through the authenticated owner path (publicationId omitted) - otherwise
  // a PRIVATE own app 403s the public render ("Publication is not publicly available")
  // and the card breaks. Pre-fix this passed publicationId={id} and failed.
  it('previews a favorited app via the authenticated owner render (no publicationId)', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);
    await screen.findByText('My Favorite App');

    const showcase = screen.getByTestId('showcase');
    // Owner view -> authenticated per-run path: publicationId omitted, runId is the showcase run.
    expect(showcase).toHaveAttribute('data-publication-id', '');
    expect(showcase).toHaveAttribute('data-run-id', 'run-1');
    // The acquired-fallback must be wired (onError + suppressErrorUi), so a private
    // owner render that fails can fall back to the public render without a flash.
    expect(showcase).toHaveAttribute('data-has-onerror', 'y');
    expect(showcase).toHaveAttribute('data-suppress', 'y');
  });

  // Regression: an ACQUIRED favorite's showcase run lives in the publisher's tenant,
  // so the authenticated owner render fails - the thumb must fall back to the public
  // publication-scoped render (publicationId set) rather than break. This pins the
  // onError state machine: drop onError and this stays at the (empty) owner path.
  it('falls back to the public render when the authenticated owner render fails', async () => {
    showcaseState.simulateAuthFailure = true;
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);
    await screen.findByText('My Favorite App');

    await waitFor(() => expect(screen.getByTestId('showcase')).toHaveAttribute('data-publication-id', 'fav-1'));
  });

  // Guard: marketplace highlights are public discovery tiles and must KEEP the
  // public publication-scoped render (publicationId set) - the owner-aware change
  // must not leak the authenticated path to anonymous-facing highlights.
  it('previews a marketplace highlight via the public render (publicationId set)', async () => {
    // No favorites -> Highlights mode.
    render(<HighlightedApps />);
    await screen.findByText('Curated Highlight');

    const showcase = screen.getByTestId('showcase');
    expect(showcase).toHaveAttribute('data-publication-id', 'hl-1');
    // Public path: no owner-aware fallback wiring.
    expect(showcase).toHaveAttribute('data-has-onerror', 'n');
  });

  // Persistence: picking a mode writes the choice to localStorage, so the Home row
  // can reopen on the last choice. Pre-fix the pick lived only in memory.
  it('persists the picked mode to localStorage on each toggle', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);
    await screen.findByText('My Favorite App');

    fireEvent.click(screen.getByRole('button', { name: /^title$/i }));
    await waitFor(() => expect(screen.getByText('Curated Highlight')).toBeInTheDocument());
    expect(localStorage.getItem(MODE_KEY)).toBe('HIGHLIGHTS');

    fireEvent.click(screen.getByRole('button', { name: /favorites/i }));
    await waitFor(() => expect(screen.getByText('My Favorite App')).toBeInTheDocument());
    expect(localStorage.getItem(MODE_KEY)).toBe('FAVORITES');
  });

  // Persistence: a stored HIGHLIGHTS pick is authoritative and overrides the
  // favorites-first auto-default even though the user HAS favorites. This is the
  // "keep the last choice" guarantee. Pre-fix this led with Favorites every visit.
  it('restores a stored HIGHLIGHTS pick over the favorites-first default', async () => {
    localStorage.setItem(MODE_KEY, 'HIGHLIGHTS');
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    render(<HighlightedApps />);

    // The toggle only renders once favorites have loaded, so finding it proves the
    // favorites-first path ran - yet the persisted HIGHLIGHTS pick still wins.
    const titleBtn = await screen.findByRole('button', { name: /^title$/i });
    expect(titleBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /favorites/i })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Curated Highlight')).toBeInTheDocument();
    expect(screen.queryByText('My Favorite App')).not.toBeInTheDocument();
  });

  // Persistence end-to-end: the choice survives a remount (revisiting the Home
  // page). Pick Highlights, unmount, remount -> Highlights is restored.
  it('reopens on the last choice after a remount', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });

    const first = render(<HighlightedApps />);
    await screen.findByText('My Favorite App');
    fireEvent.click(screen.getByRole('button', { name: /^title$/i }));
    await waitFor(() => expect(screen.getByText('Curated Highlight')).toBeInTheDocument());
    first.unmount();

    render(<HighlightedApps />);
    const titleBtn = await screen.findByRole('button', { name: /^title$/i });
    expect(titleBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Curated Highlight')).toBeInTheDocument();
    expect(screen.queryByText('My Favorite App')).not.toBeInTheDocument();
  });

  // Safety + recovery: a stored FAVORITES pick must never strand the user in an
  // empty favorites view when they currently have no favorites (the canToggle guard
  // wins, so the row falls back to Highlights with no toggle), AND the stored pick
  // must be left intact - not silently cleared - so that once favorites exist again
  // the row honors the FAVORITES pick. The intact-then-honored assertions are what
  // distinguish the persisted path from a naive implementation that wipes the pick
  // when favorites empty out.
  it('keeps a stored FAVORITES pick intact while favorites are empty, then honors it once they exist', async () => {
    localStorage.setItem(MODE_KEY, 'FAVORITES');
    // getFavorites returns [] (default beforeEach mock).

    const first = render(<HighlightedApps />);

    // No favorites yet: safety fallback to Highlights, no toggle, stored pick untouched.
    expect(await screen.findByText('Curated Highlight')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /favorites/i })).not.toBeInTheDocument();
    expect(localStorage.getItem(MODE_KEY)).toBe('FAVORITES');
    first.unmount();

    // Favorites now exist (user re-favorited): the preserved FAVORITES pick is honored.
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] });
    render(<HighlightedApps />);

    const favBtn = await screen.findByRole('button', { name: /favorites/i });
    expect(favBtn).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('My Favorite App')).toBeInTheDocument();
  });

  // Regression: the Home favorites row caps at 8 cards (the "see all" CTA links to
  // the full list). Pre-fix the row rendered every favorite unbounded.
  it('caps the Home favorites row at 8 even when more are favorited', async () => {
    const many = Array.from({ length: 12 }, (_, i) => ({
      id: `fav-${i + 1}`, title: `Favorite ${i + 1}`, displayMode: 'APPLICATION', creditsPerUse: 0,
    }));
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: many });

    const { container } = render(<HighlightedApps />);
    await screen.findByText('Favorite 1');

    // Card links point at /app/applications/<id>; the "see all" CTA is /app/applications (no id).
    const favLinks = container.querySelectorAll('a[href^="/app/applications/"]');
    expect(favLinks.length).toBe(8);
    expect(screen.queryByText('Favorite 9')).not.toBeInTheDocument();
  });

  // Regression (cloud parity): a cloud-acquired app favorited via the NATIVE workflow
  // store (its remote publication id can't be a publication-favorite) must still appear
  // in the Home Favorites row. Pre-fix the row read ONLY publication favorites, so a
  // favorited downloaded app was invisible in Home (unlike on cloud).
  it('surfaces a workflow-favorited acquired app (cloud) in the Home favorites row', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] }); // no publication favorites
    favoriteServiceMock.getFavoriteIds.mockResolvedValue(['clone-w1']);       // the local clone is starred
    publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{
        workflowId: 'clone-w1',
        publication: { id: 'acq-pub', title: 'Downloaded Fav', displayMode: 'APPLICATION', creditsPerUse: 0, remote: true },
      }],
      totalCount: 1, page: 0, size: 25,
    });
    // Remote synth -> enriched from the cloud proxy so the thumbnail can render.
    publicationServiceMock.getPublicationByIdPublic.mockResolvedValue({
      id: 'acq-pub', title: 'Downloaded Fav', displayMode: 'APPLICATION',
      showcaseRunId: 'r', showcaseInterfaceId: 'i',
    });

    render(<HighlightedApps />);

    expect(await screen.findByText('Downloaded Fav')).toBeInTheDocument();
    // Enriched via the cloud proxy (remote=true) and opens in the user's library.
    await waitFor(() => expect(publicationServiceMock.getPublicationByIdPublic).toHaveBeenCalledWith('acq-pub', true));
    expect(screen.getByText('Downloaded Fav').closest('a')).toHaveAttribute('href', '/app/applications/acq-pub');
  });

  // A workflow favorite already covered by a publication favorite must not double-count.
  it('does not duplicate an app that is both a publication favorite and workflow-favorited', async () => {
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [FAVORITE] }); // id fav-1
    favoriteServiceMock.getFavoriteIds.mockResolvedValue(['clone-x']);
    publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{ workflowId: 'clone-x', publication: { id: 'fav-1', title: 'My Favorite App', displayMode: 'APPLICATION', creditsPerUse: 0 } }],
      totalCount: 1, page: 0, size: 25,
    });

    const { container } = render(<HighlightedApps />);
    await screen.findByText('My Favorite App');

    expect(container.querySelectorAll('a[href="/app/applications/fav-1"]').length).toBe(1);
  });
});
