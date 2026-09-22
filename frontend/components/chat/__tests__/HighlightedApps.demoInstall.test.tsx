// @vitest-environment jsdom
/**
 * Admin demo-install mode on the chat "Marketplace Highlights" row.
 *
 * This surface hides its Install CTA behind THREE gates, not one: the green
 * badge, an acquire handler that is withheld for an own/installed card, and an
 * Open link that outranks the Install slot. Demo mode has to lift all three, and
 * lift them together: dropping Open on a row that has no acquire handler would
 * leave the card with no button at all.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));

const authState = vi.hoisted(() => ({ isAuthenticated: true }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ ...authState, isReady: true, numericUserId: 5 }),
}));

// IS_MANAGED_CLOUD is read by the verified badge on each card's publisher row.
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
vi.mock('@/lib/format-cost', () => ({ isCeMode: false }));
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ status: null, isLoading: false, isCloudLinked: false, isInstallCloudLinked: false }),
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
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({ publicationService: publicationServiceMock }));

const favoriteServiceMock = vi.hoisted(() => ({ getFavoriteIds: vi.fn() }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: favoriteServiceMock }));

vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => <span /> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => <div /> }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => <div /> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => <span /> }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => null }));

const demoMode = vi.hoisted(() => ({ on: false }));
vi.mock('@/lib/marketplace/demoInstallMode', () => ({
  useMarketplaceDemoInstall: () => demoMode.on,
}));

import { HighlightedApps } from '../HighlightedApps';

const APP = {
  id: 'pub-1',
  title: 'Invoice to Client',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  creditsPerUse: 0,
  publisherId: '999',
};

describe('HighlightedApps - admin demo-install mode', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    demoMode.on = false;
    authState.isAuthenticated = true;
    publicationServiceMock.getHighlights.mockResolvedValue({
      displayMode: 'APPLICATION',
      highlights: [{ rank: 1, publication: APP }],
    });
    publicationServiceMock.getRemoteHighlights.mockResolvedValue({ highlights: [] });
    publicationServiceMock.getRemoteMarketplacePublications.mockResolvedValue({ publications: [] });
    publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] });
    favoriteServiceMock.getFavoriteIds.mockResolvedValue([]);
    // The row treats this publication as already installed by the viewer.
    publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{ sourcePublicationId: 'pub-1' }],
    });
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
    publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  });

  afterEach(() => cleanup());

  it('off: an installed application shows Open, not Install', async () => {
    render(<HighlightedApps />);
    await waitFor(() => expect(screen.getByTestId('highlight-card-open')).toBeInTheDocument());
    expect(screen.queryByTestId('highlight-card-acquire')).toBeNull();
  });

  it('on: the same card swaps Open for Install', async () => {
    demoMode.on = true;
    render(<HighlightedApps />);
    await waitFor(() => expect(screen.getByTestId('highlight-card-acquire')).toBeInTheDocument());
    expect(screen.queryByTestId('highlight-card-open')).toBeNull();
  });

  it('on: an anonymous visitor is offered no Install, since installing needs an account', async () => {
    // The row passes no acquire handler when nobody is signed in, and demo mode
    // must not invent one: `demoCta` is gated on the handler existing.
    demoMode.on = true;
    authState.isAuthenticated = false;
    render(<HighlightedApps />);
    await waitFor(() => expect(screen.getByText('Invoice to Client')).toBeInTheDocument());
    expect(screen.queryByTestId('highlight-card-acquire')).toBeNull();
  });
});
