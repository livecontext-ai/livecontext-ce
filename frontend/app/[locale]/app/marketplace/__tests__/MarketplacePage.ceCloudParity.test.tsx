// @vitest-environment jsdom
/**
 * CE marketplace cloud-parity gate (2026-06-10).
 *
 * A CE install whose cloud link is connected AND registered renders the SAME
 * marketplace UI as cloud (tabs + type chips), with the Explore reads served
 * by the CE backend's /publications/remote/* proxies and installs routed
 * through the CE remote acquire path (ceMode). An UNLINKED CE surfaces no
 * community apps at all - it shows a connect-to-cloud CTA that starts the
 * OAuth link flow with the marketplace as returnPath, and completes the link
 * when the browser returns here (?cloud_link_callback=1&state=...). While the
 * link status resolves, neither data source is hit (no flash of the wrong UI).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  // Key-echo translator: assertions match raw keys (tabMyPublications, ...).
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));

// CE gate completes the OAuth callback here and reads the URL for the
// ?cloud_link_callback=1&state=... params; the connect CTA starts the flow.
const searchParamsState = vi.hoisted(() => ({ params: new URLSearchParams() }));
vi.mock('next/navigation', () => ({
  useSearchParams: () => searchParamsState.params,
}));

const queryClientMock = vi.hoisted(() => ({
  invalidateQueries: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => queryClientMock,
}));

const cloudLinkServiceMock = vi.hoisted(() => ({
  getAuthUrl: vi.fn(),
  connect: vi.fn(),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: cloudLinkServiceMock,
}));

vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: () => {},
}));

// The connect callback must drop the module-level models cache (linking flips
// the executable catalog BYOK -> cloud; useModels' 5-min TTL would otherwise
// keep the pickers on the stale, possibly empty, list).
const clearModelsCacheMock = vi.hoisted(() => vi.fn());
vi.mock('@/hooks/useModels', () => ({
  clearModelsCache: clearModelsCacheMock,
}));

vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, numericUserId: 7 }),
}));

// The point of this file: the COMMUNITY edition (mutable so the non-remote
// cloud-edition regression test can flip it off).
const editionState = vi.hoisted(() => ({ IS_CE: true }));

vi.mock('@/lib/edition', () => editionState);

const linkState = vi.hoisted(() => ({
  status: null as Record<string, unknown> | null,
  isLoading: false,
  // isCloudLinked = PER-USER (link owner); isInstallCloudLinked = INSTALL-global (any user on an
  // admin-linked install). The gate keys VISIBILITY on isInstallCloudLinked so a member inherits it.
  isCloudLinked: false,
  isInstallCloudLinked: false,
}));

vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ ...linkState }),
}));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
  searchPublications: vi.fn(),
  getMyPublications: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: orchestratorApiMock,
}));

const publicationServiceMock = vi.hoisted(() => ({
  getAcquiredApplications: vi.fn(),
  getPurchases: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  searchRemotePublications: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));

vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: () => <div data-testid="category-filter" />,
}));

vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: ({ isOpen, ceMode }: { isOpen: boolean; ceMode?: boolean }) =>
    isOpen ? <div data-testid="acquire-modal" data-ce-mode={String(!!ceMode)} /> : null,
}));

vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: ({
    publication,
    currentUserId,
    onAcquire,
  }: {
    publication: { id: string; title: string; publisherId?: string };
    currentUserId?: string;
    onAcquire?: (publication: { id: string; title: string }) => void;
  }) => {
    // Mirrors the real PublicationCard's ownership gate
    // (isOwn = publisherId === currentUserId → canAcquire = false): a page
    // that wrongly passes the local CE user id in remote mode loses the
    // install button here exactly like in the real card.
    const isOwn = !!currentUserId && publication.publisherId === currentUserId;
    return (
      <div
        data-testid="publication-card"
        data-current-user-id={currentUserId ?? ''}
        data-is-own={String(isOwn)}
      >
        {publication.title}
        {onAcquire && !isOwn && (
          <button type="button" onClick={() => onAcquire(publication)}>
            install
          </button>
        )}
      </div>
    );
  },
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));

import MarketplacePage from '../page';

const REMOTE_PUB = {
  id: 'pub-cloud-1',
  title: 'Cloud Parity App',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  creditsPerUse: 0,
};

const LEGACY_PUB = {
  id: 'pub-legacy-1',
  title: 'Legacy Direct App',
  displayMode: 'APPLICATION',
  publicationType: 'WORKFLOW',
  creditsPerUse: 0,
};

const fetchMock = vi.fn();

// jsdom doesn't implement navigation; intercept window.location.href so the
// connect CTA test can assert the redirect target without a noisy log.
let assignedHref = '';

beforeEach(() => {
  vi.clearAllMocks();
  assignedHref = '';
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: {
      get href() { return assignedHref; },
      set href(v: string) { assignedHref = v; },
      pathname: '/en/app/marketplace',
    },
  });
  editionState.IS_CE = true;
  linkState.status = null;
  linkState.isLoading = false;
  linkState.isCloudLinked = false;
  linkState.isInstallCloudLinked = false;
  searchParamsState.params = new URLSearchParams();
  queryClientMock.invalidateQueries.mockResolvedValue(undefined);
  cloudLinkServiceMock.getAuthUrl.mockResolvedValue({ authUrl: 'https://kc.example/auth', state: 's1' });
  cloudLinkServiceMock.connect.mockResolvedValue({ linked: true, registered: true });
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getRemoteMarketplacePublications.mockResolvedValue({ publications: [REMOTE_PUB] });
  publicationServiceMock.searchRemotePublications.mockResolvedValue({ publications: [] });
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
  fetchMock.mockResolvedValue({
    ok: true,
    json: async () => ({ publications: [LEGACY_PUB] }),
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('MarketplacePage - CE cloud-parity gate', () => {
  it('linked CE renders the full cloud marketplace UI backed by the remote proxies', async () => {
    linkState.isCloudLinked = true;
    linkState.isInstallCloudLinked = true;
    linkState.status = { linked: true, registered: true, installLinked: true };

    render(<MarketplacePage />);

    // Cloud-only chrome: the My Publications / My Purchases tabs.
    expect(screen.getByText('tabMyPublications')).toBeInTheDocument();
    expect(screen.getByText('tabMyPurchases')).toBeInTheDocument();

    // Explore data comes from the CE backend remote proxy with the cloud's
    // default pagination - never from the local orchestrator endpoint and
    // never from a direct browser call to livecontext.ai.
    await waitFor(() => {
      expect(publicationServiceMock.getRemoteMarketplacePublications).toHaveBeenCalledWith(0, 50, undefined);
    });
    expect(await screen.findByText('Cloud Parity App')).toBeInTheDocument();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('inheriting member (per-user unlinked, install-linked) sees the cloud marketplace, not the connect CTA', async () => {
    // A non-owner member of an admin-linked install: the PER-USER link is false
    // (it cannot manage the link) but the INSTALL-global link is true, so it
    // inherits the admin's cloud marketplace VISIBILITY.
    linkState.isCloudLinked = false;
    linkState.isInstallCloudLinked = true;
    linkState.status = { linked: false, installLinked: true, installCloudPlanCode: 'TEAM' };

    render(<MarketplacePage />);

    // Full cloud marketplace, not the connect CTA.
    expect(screen.getByText('tabMyPublications')).toBeInTheDocument();
    expect(screen.queryByText('cloudConnect.title')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(publicationServiceMock.getRemoteMarketplacePublications).toHaveBeenCalledWith(0, 50, undefined);
    });
    expect(await screen.findByText('Cloud Parity App')).toBeInTheDocument();
  });

  it('linked CE routes installs through the CE remote acquire path (ceMode)', async () => {
    linkState.isCloudLinked = true;
    linkState.isInstallCloudLinked = true;
    linkState.status = { linked: true, registered: true, installLinked: true };

    render(<MarketplacePage />);

    fireEvent.click(await screen.findByRole('button', { name: 'install' }));

    expect(screen.getByTestId('acquire-modal')).toHaveAttribute('data-ce-mode', 'true');
  });

  it('unlinked CE shows the connect-to-cloud CTA, fetches no community apps, and starts OAuth with the marketplace return path', async () => {
    linkState.isCloudLinked = false;

    render(<MarketplacePage />);

    // Connect CTA instead of any publications or the cloud tabs.
    expect(await screen.findByText('cloudConnect.title')).toBeInTheDocument();
    expect(screen.queryByTestId('publication-card')).not.toBeInTheDocument();
    expect(screen.queryByText('tabMyPublications')).not.toBeInTheDocument();
    expect(screen.queryByText('tabMyPurchases')).not.toBeInTheDocument();

    // No community apps surfaced from ANY source - not the public cloud API
    // (legacy direct fetch is gone), the remote proxy, or the local endpoint.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(publicationServiceMock.getRemoteMarketplacePublications).not.toHaveBeenCalled();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();

    // The cloud button starts the link flow with the marketplace as returnPath
    // so the install lands back here (matches the backend returnPath allowlist).
    fireEvent.click(screen.getByRole('button', { name: 'cloudConnect.button' }));
    await waitFor(() => {
      expect(cloudLinkServiceMock.getAuthUrl).toHaveBeenCalledWith('/en/app/marketplace');
    });
    expect(assignedHref).toBe('https://kc.example/auth');
  });

  it('completes the cloud link and refreshes the cached status when returning from the OAuth callback', async () => {
    // Land on the marketplace with the connect callback params the backend appends.
    searchParamsState.params = new URLSearchParams('cloud_link_callback=1&state=abc123');
    linkState.isCloudLinked = false;

    render(<MarketplacePage />);

    await waitFor(() => {
      expect(cloudLinkServiceMock.connect).toHaveBeenCalledWith('abc123');
    });
    // Invalidating the shared status query flips the gate to the linked
    // marketplace once the refetch reports linked+registered.
    expect(queryClientMock.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['cloud-link', 'status'] });
    // Linking changes the executable model catalog: the stale (possibly empty)
    // models cache must be dropped so every picker refetches the cloud one.
    expect(clearModelsCacheMock).toHaveBeenCalledTimes(1);
    // No connect CTA flow was started - this is the return leg, not the start.
    expect(cloudLinkServiceMock.getAuthUrl).not.toHaveBeenCalled();
  });

  it('falls back to the connect CTA (fail-soft) when completing the link fails', async () => {
    // A stale/replayed state → the backend rejects the connect; the gate must
    // not crash, and the user lands back on the CTA to retry.
    searchParamsState.params = new URLSearchParams('cloud_link_callback=1&state=stale');
    linkState.isCloudLinked = false;
    cloudLinkServiceMock.connect.mockRejectedValue(new Error('state expired'));

    render(<MarketplacePage />);

    expect(await screen.findByText('cloudConnect.title')).toBeInTheDocument();
    expect(cloudLinkServiceMock.connect).toHaveBeenCalledWith('stale');
    expect(screen.queryByTestId('publication-card')).not.toBeInTheDocument();
    // The catalog did not change (link failed) - the models cache must survive.
    expect(clearModelsCacheMock).not.toHaveBeenCalled();
  });

  it('remote mode never passes the local CE user id - a cloud publisherId colliding with it keeps the install CTA', async () => {
    linkState.isCloudLinked = true;
    linkState.isInstallCloudLinked = true;
    linkState.status = { linked: true, registered: true, installLinked: true };
    // Cloud publication whose publisherId ('7') collides with the LOCAL CE
    // numericUserId (7) - the two ids live in DIFFERENT user-id namespaces, so
    // it must NOT be treated as "own" (which would suppress the install CTA).
    publicationServiceMock.getRemoteMarketplacePublications.mockResolvedValue({
      publications: [{ ...REMOTE_PUB, publisherId: '7' }],
    });

    render(<MarketplacePage />);

    const card = await screen.findByTestId('publication-card');
    expect(card).toHaveAttribute('data-current-user-id', '');
    expect(card).toHaveAttribute('data-is-own', 'false');
    expect(screen.getByRole('button', { name: 'install' })).toBeInTheDocument();
  });

  it('non-remote explore still passes the local user id so own publications keep the CTA suppressed (regression)', async () => {
    // Cloud edition (or any non-remote render): publisherId and the viewer id
    // share the same namespace - ownership detection must keep working.
    editionState.IS_CE = false;
    orchestratorApiMock.getMarketplacePublications.mockResolvedValue({
      publications: [{ ...REMOTE_PUB, id: 'pub-local-1', title: 'Local Own App', publisherId: '7' }],
    });

    render(<MarketplacePage />);

    const card = await screen.findByTestId('publication-card');
    expect(await screen.findByText('Local Own App')).toBeInTheDocument();
    expect(card).toHaveAttribute('data-current-user-id', '7');
    expect(card).toHaveAttribute('data-is-own', 'true');
    expect(screen.queryByRole('button', { name: 'install' })).not.toBeInTheDocument();
  });

  it('shows the skeleton and fires NO data fetch while the link status resolves', () => {
    linkState.isLoading = true;

    render(<MarketplacePage />);

    expect(screen.getAllByTestId('card-skeleton').length).toBeGreaterThan(0);
    expect(publicationServiceMock.getRemoteMarketplacePublications).not.toHaveBeenCalled();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
