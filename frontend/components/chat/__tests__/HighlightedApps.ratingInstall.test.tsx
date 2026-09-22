// @vitest-environment jsdom
/**
 * The Home highlights row is the most-seen app surface (chat welcome view), but
 * it used to be the only one showing neither the rating nor a way to install:
 * a visitor had to open the marketplace preview to learn an app was rated 4.8
 * and to get an Install button. Both now live on the highlight card.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string; children: React.ReactNode }) => (
    <a href={href}>{children}</a>
  ),
}));

// The acquire modal is exercised by its own suite; here we only need to know
// that the card handed it a publication to install.
const modalState = vi.hoisted(() => ({ openedFor: null as string | null }));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({
  default: ({ publication }: { publication: { id: string } }) => {
    modalState.openedFor = publication.id;
    return <div data-testid="acquire-modal" />;
  },
}));

const authState = vi.hoisted(() => ({ isAuthenticated: true, numericUserId: 7 as number | null }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ ...authState, isReady: true }),
}));

// IS_MANAGED_CLOUD is read by the verified badge on each card's publisher row.
vi.mock('@/lib/edition', () => ({ IS_CE: false, IS_MANAGED_CLOUD: true }));
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

const favoriteServiceMock = vi.hoisted(() => ({ getFavoriteIds: vi.fn() }));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({ favoriteService: favoriteServiceMock }));

vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => <span /> }));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => <div /> }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => <div /> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => <span /> }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span /> }));

import { HighlightedApps } from '../HighlightedApps';

const RATED = {
  id: 'hl-rated',
  title: 'Prospect Scout',
  displayMode: 'APPLICATION',
  creditsPerUse: 0,
  publisherId: '9',
  averageRating: 4.8,
  reviewCount: 5,
};

const UNRATED = {
  id: 'hl-unrated',
  title: 'Fresh App',
  displayMode: 'APPLICATION',
  creditsPerUse: 0,
  publisherId: '9',
  averageRating: 0,
  reviewCount: 0,
};

function highlight(...pubs: unknown[]) {
  return { displayMode: 'APPLICATION', highlights: pubs.map((p, i) => ({ rank: i + 1, publication: p })) };
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  modalState.openedFor = null;
  authState.isAuthenticated = true;
  authState.numericUserId = 7;
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] });
  publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 25 });
  favoriteServiceMock.getFavoriteIds.mockResolvedValue([]);
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
});

afterEach(() => cleanup());

describe('HighlightedApps - rating and install on the highlight card', () => {
  it('shows the average rating and the vote count next to the title', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(RATED));

    render(<HighlightedApps />);

    expect(await screen.findByText('Prospect Scout')).toBeInTheDocument();
    expect(screen.getByText('4.8')).toBeInTheDocument();
    expect(screen.getByText('(5)')).toBeInTheDocument();
  });

  it('shows no rating at all for an app nobody has rated, rather than 0.0', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(UNRATED));

    render(<HighlightedApps />);

    expect(await screen.findByText('Fresh App')).toBeInTheDocument();
    expect(screen.queryByText('0.0')).not.toBeInTheDocument();
    expect(screen.queryByText('(0)')).not.toBeInTheDocument();
  });

  it('offers Install on a highlight and opens the acquire modal for that publication', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(RATED));

    render(<HighlightedApps />);

    const install = await screen.findByTestId('highlight-card-acquire');
    fireEvent.click(install);

    await waitFor(() => expect(screen.getByTestId('acquire-modal')).toBeInTheDocument());
    expect(modalState.openedFor).toBe('hl-rated');
  });

  it('shapes Install as a rounded square, not a pill, like every other card CTA', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(RATED));

    render(<HighlightedApps />);

    const install = await screen.findByTestId('highlight-card-acquire');
    expect(install.className).toContain('rounded-lg');
    expect(install.className).not.toContain('rounded-full');
  });

  it('offers Open instead of Install once the app is already installed', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(RATED));
    publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({
      items: [{ workflowId: 'wf-1', sourcePublicationId: 'hl-rated' }],
      totalCount: 1, page: 0, size: 25,
    });

    render(<HighlightedApps />);

    expect(await screen.findByTestId('highlight-card-open')).toBeInTheDocument();
    expect(screen.queryByTestId('highlight-card-acquire')).not.toBeInTheDocument();
  });

  it('never offers to install your own publication', async () => {
    publicationServiceMock.getHighlights.mockResolvedValue(highlight({ ...RATED, publisherId: '7' }));

    render(<HighlightedApps />);

    expect(await screen.findByText('Prospect Scout')).toBeInTheDocument();
    expect(screen.queryByTestId('highlight-card-acquire')).not.toBeInTheDocument();
  });

  it('hides the install button from anonymous visitors, who can still see the rating', async () => {
    authState.isAuthenticated = false;
    authState.numericUserId = null;
    publicationServiceMock.getHighlights.mockResolvedValue(highlight(RATED));

    render(<HighlightedApps />);

    expect(await screen.findByText('4.8')).toBeInTheDocument();
    expect(screen.queryByTestId('highlight-card-acquire')).not.toBeInTheDocument();
  });
});
