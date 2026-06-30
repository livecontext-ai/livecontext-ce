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
import { cleanup, render, screen, waitFor } from '@testing-library/react';
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

import MarketplaceHighlightsPage from '../page';

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
});
