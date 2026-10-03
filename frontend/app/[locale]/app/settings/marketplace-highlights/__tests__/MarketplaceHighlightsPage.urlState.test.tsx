// @vitest-environment jsdom
/**
 * The highlights curation page keeps the bucket being curated and the candidate search in
 * the address, so a reload reopens the same list.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
const authState = vi.hoisted(() => ({ hasRole: (_: string) => true, isLoading: false }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => authState }));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));

const orchestratorApiMock = vi.hoisted(() => ({ getMarketplacePublications: vi.fn() }));
vi.mock('@/lib/api', () => ({ orchestratorApi: orchestratorApiMock }));
const publicationServiceMock = vi.hoisted(() => ({
  getAdminHighlights: vi.fn(),
  replaceHighlights: vi.fn(),
  getLandingSnapshot: vi.fn(),
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: publicationServiceMock }));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationPreview: () => null,
  StandardFallback: () => null,
}));

import MarketplaceHighlightsPage from '../page';

const PAGE = '/en/app/settings/marketplace-highlights';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

const searchBox = () => screen.getByPlaceholderText('candidates.searchPlaceholder') as HTMLInputElement;

beforeEach(() => {
  vi.clearAllMocks();
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getAdminHighlights.mockResolvedValue({ highlights: [] });
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
    publications: [
      { id: 'agent-x', title: 'An Agent', displayMode: 'AGENT', publisherName: 'Pub C' },
      { id: 'agent-y', title: 'Other Bot', displayMode: 'AGENT', publisherName: 'Pub D' },
    ],
  });
});
afterEach(cleanup);

describe('MarketplaceHighlightsPage - view kept in the address', () => {
  it('opens on the bucket and the candidate search the address carries', async () => {
    openAt('mode=AGENT&q=other');
    render(<MarketplaceHighlightsPage />);

    await waitFor(() => expect(publicationServiceMock.getAdminHighlights).toHaveBeenCalledWith('AGENT'));
    expect(publicationServiceMock.getAdminHighlights).not.toHaveBeenCalledWith('APPLICATION');
    expect(await screen.findByText('Other Bot')).toBeInTheDocument();
    expect(screen.queryByText('An Agent')).toBeNull();
    expect(searchBox().value).toBe('other');
  });

  it('falls back to the applications bucket on a mode it does not have', async () => {
    openAt('mode=NOPE');
    render(<MarketplaceHighlightsPage />);

    await waitFor(() => expect(publicationServiceMock.getAdminHighlights).toHaveBeenCalledWith('APPLICATION'));
  });

  it('writes the bucket and the search as they change', async () => {
    openAt();
    render(<MarketplaceHighlightsPage />);
    await waitFor(() => expect(publicationServiceMock.getAdminHighlights).toHaveBeenCalledWith('APPLICATION'));

    fireEvent.click(screen.getByRole('button', { name: 'modes.AGENT' }));
    expect(fakeFolderRouter.search()).toBe('mode=AGENT');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');

    fireEvent.change(await screen.findByPlaceholderText('candidates.searchPlaceholder'), { target: { value: 'bot' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('mode=AGENT&q=bot'));
  });
});
