// @vitest-environment jsdom
/**
 * Marketplace Highlights curation page - app thumbnails (2026-06-18).
 *
 * Each curated and candidate row shows the SAME visual preview as the public
 * marketplace card (via PublicationPreview), so an admin curating the landing /
 * highlights rows gets a visualization, not just a title. Stale curated rows
 * (publication deleted/unpublished) show no thumbnail.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

const authState = vi.hoisted(() => ({ hasRole: (_: string) => true, isLoading: false }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => authState,
}));

vi.mock('@/lib/edition', () => ({ IS_CE: false }));

vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: () => {},
}));

const orchestratorApiMock = vi.hoisted(() => ({ getMarketplacePublications: vi.fn() }));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

const publicationServiceMock = vi.hoisted(() => ({
  getAdminHighlights: vi.fn(),
  replaceHighlights: vi.fn(),
  getLandingSnapshot: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));

// The point of this test: assert the marketplace preview is rendered per row.
// Stub it to an identifiable node carrying the publication id.
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationPreview: ({ publication }: { publication: { id: string } }) => (
    <div data-testid="row-thumb" data-pub={publication.id} />
  ),
  StandardFallback: () => <div data-testid="std-fallback" />,
}));

import MarketplaceHighlightsPage, { PUBLIC_PAGE_MODES } from '../page';
import { PERSONA_KEYS } from '@/components/landing/personas/personas';

const APP_CURATED = { id: 'app-curated', title: 'Curated App', displayMode: 'APPLICATION', publisherName: 'Pub A' };
const APP_CANDIDATE = { id: 'app-candidate', title: 'Candidate App', displayMode: 'APPLICATION', publisherName: 'Pub B' };
const AGENT_CANDIDATE = { id: 'agent-x', title: 'An Agent', displayMode: 'AGENT', publisherName: 'Pub C' };

beforeEach(() => {
  vi.clearAllMocks();
  authState.hasRole = () => true;
  authState.isLoading = false;
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getAdminHighlights.mockResolvedValue({
    highlights: [
      { rank: 0, publication: APP_CURATED },
      { rank: 1, publication: null }, // stale row - publication deleted/unpublished
    ],
  });
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
    publications: [APP_CANDIDATE, AGENT_CANDIDATE],
  });
});

afterEach(() => cleanup());

describe('MarketplaceHighlightsPage - row thumbnails', () => {
  it('renders a marketplace preview thumbnail for each real curated and candidate row', async () => {
    render(<MarketplaceHighlightsPage />);

    // The curated real app + the APPLICATION candidate each get a thumbnail.
    await waitFor(() => {
      const thumbs = screen.getAllByTestId('row-thumb');
      expect(thumbs.length).toBe(2);
    });

    const pubIds = screen.getAllByTestId('row-thumb').map(el => el.getAttribute('data-pub'));
    expect(pubIds).toContain('app-curated');   // curated row
    expect(pubIds).toContain('app-candidate'); // candidate row (APPLICATION, default tab)
    // The AGENT candidate is filtered out of the APPLICATION tab → no thumbnail.
    expect(pubIds).not.toContain('agent-x');
  });

  it('renders the stale curated row as a placeholder, not a thumbnail', async () => {
    render(<MarketplaceHighlightsPage />);

    // The stale row IS rendered (its "stale" message shows so the admin can remove it)...
    await waitFor(() => {
      expect(screen.getByText(/Stale \(publication deleted/i)).toBeInTheDocument();
    });
    // ...but it gets the placeholder, NOT a PublicationPreview - so exactly 2 thumbs
    // (curated app + candidate), never 3. Guards against passing null to RowThumbnail.
    expect(screen.getAllByTestId('row-thumb')).toHaveLength(2);
  });

  /**
   * The six persona rows are what an admin curates to give each /for/<persona> page its
   * own apps. They are bucket keys, not publication types: their candidate list is
   * APPLICATION, like the home page's row, or the tab would open with nothing to pick.
   * (useTranslations is mocked to echo the key, so the tabs read "modes.LANDING_OPS".)
   */
  it('offers one tab per public page, persona rows included, before the resource rows', async () => {
    render(<MarketplaceHighlightsPage />);
    for (const key of ['LANDING', 'LANDING_OPS', 'LANDING_CREATOR', 'LANDING_SUPPORT',
      'LANDING_SALES', 'LANDING_MARKETING', 'LANDING_RECRUITING']) {
      expect(await screen.findByRole('button', { name: `modes.${key}` })).toBeInTheDocument();
    }
    const tabs = screen.getAllByRole('button').map((b) => b.textContent);
    expect(tabs.indexOf('modes.LANDING_RECRUITING')).toBeLessThan(tabs.indexOf('modes.APPLICATION'));
  });

  it('offers exactly the buckets the persona pages ask for, in PERSONA_KEYS order', () => {
    // MarketplacePreview builds its bucket from the persona key at runtime; this list, the
    // Java enum and V490 each spell the six out. Adding a seventh persona without touching
    // them gives a page that requests LANDING_<NEW>, gets a 400 on the unknown enum value,
    // silently falls back to the home page's row, and has no tab here to curate. This is
    // the cheap half of that guard; the Java test names the same six on its side.
    expect(PUBLIC_PAGE_MODES).toEqual(['LANDING', ...PERSONA_KEYS.map((p) => `LANDING_${p.toUpperCase()}`)]);
  });

  it('fills a persona tab with APPLICATION candidates, since no publication is of type LANDING_OPS', async () => {
    // What the test above only claimed. A persona bucket asks the API for its own row
    // (LANDING_OPS) but offers APPLICATION apps to put in it: filtering candidates on the
    // bucket key would match nothing published, and the tab would open empty with no error
    // to explain why. That is candidatePublicationMode, and this is where it is exercised.
    render(<MarketplaceHighlightsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'modes.LANDING_OPS' }));

    await waitFor(() => {
      expect(publicationServiceMock.getAdminHighlights).toHaveBeenCalledWith('LANDING_OPS');
    });
    await waitFor(() => {
      const pubIds = screen.getAllByTestId('row-thumb').map(el => el.getAttribute('data-pub'));
      expect(pubIds).toContain('app-candidate');
      expect(pubIds).not.toContain('agent-x');
    });
  });
});
