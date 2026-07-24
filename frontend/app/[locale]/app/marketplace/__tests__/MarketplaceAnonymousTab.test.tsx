// @vitest-environment jsdom
/**
 * Anonymous visitors and the query-param-backed tab.
 *
 * My Publications / My Purchases both call authenticated endpoints and hold
 * tenant-scoped data, so a signed-out visitor must never reach them - including
 * via a deep link like ?tab=mine. The snap-back is resolved during RENDER (not
 * only in an effect) so the private tab never gets a mount frame in which to
 * fire those calls.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
const searchParamsState = vi.hoisted(() => ({ params: new URLSearchParams() }));
const routerMock = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParamsState.params,
  usePathname: () => '/app/marketplace',
  useRouter: () => routerMock,
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn().mockResolvedValue(undefined) }),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() },
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
// Signed OUT.
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: false, numericUserId: null }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: null, isLoading: false, isCloudLinked: false, isInstallCloudLinked: false }),
}));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
  searchPublications: vi.fn(),
  getMyPublications: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));

const publicationServiceMock = vi.hoisted(() => ({
  getAcquiredApplications: vi.fn(),
  getPurchases: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  searchRemotePublications: vi.fn(),
  acquirePublication: vi.fn(),
  acquireAgentPublication: vi.fn(),
  acquireResourcePublication: vi.fn(),
  acquireRemotePublication: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));
vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: () => <div data-testid="category-filter" />,
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: { publication: { title: string } }) => <div>{props.publication.title}</div>,
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

import MarketplacePage from '../page';

beforeEach(() => {
  vi.clearAllMocks();
  cleanup();
  searchParamsState.params = new URLSearchParams();
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
    publications: [{ id: 'pub-1', title: 'Public App', displayMode: 'APPLICATION', publisherId: '9' }],
  });
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });
});

describe('Marketplace - anonymous visitor cannot land on a private tab', () => {
  it('?tab=mine falls back to Explore and never fires the authenticated fetches', async () => {
    searchParamsState.params = new URLSearchParams('tab=mine');

    render(<MarketplacePage />);

    // Explore rendered instead.
    await screen.findByText('Public App');
    // Not one frame of MyPublicationsTab / the acquired-set fetches.
    expect(orchestratorApiMock.getMyPublications).not.toHaveBeenCalled();
    expect(publicationServiceMock.getAcquiredApplications).not.toHaveBeenCalled();
    expect(publicationServiceMock.getPurchases).not.toHaveBeenCalled();
  });

  it('?tab=purchases likewise resolves to Explore', async () => {
    searchParamsState.params = new URLSearchParams('tab=purchases');

    render(<MarketplacePage />);

    await screen.findByText('Public App');
    expect(publicationServiceMock.getPurchases).not.toHaveBeenCalled();
  });

  it('cleans the stale tab param out of the URL', async () => {
    searchParamsState.params = new URLSearchParams('tab=mine');

    render(<MarketplacePage />);
    await screen.findByText('Public App');

    await waitFor(() => {
      expect(routerMock.replace).toHaveBeenCalledWith('/app/marketplace', { scroll: false });
    });
  });

  it('the private tab buttons are not even offered', async () => {
    render(<MarketplacePage />);
    await screen.findByText('Public App');

    expect(screen.queryByText('tabMyPublications')).not.toBeInTheDocument();
    expect(screen.queryByText('tabMyPurchases')).not.toBeInTheDocument();
  });
});
