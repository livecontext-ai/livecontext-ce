// @vitest-environment jsdom
/**
 * "Marketplace Highlights" row in CE (cloud-parity, 2026-06-10).
 *
 * On cloud, HighlightedApps shows the admin-curated highlights row on the chat
 * welcome view. A cloud-linked CE reads the SAME curated row through the CE
 * backend's /publications/remote/highlights proxy (with the remote marketplace
 * as fallback). Community apps are gated behind an active cloud link: an
 * UNLINKED CE reads NO source (local or remote) and hides the row entirely.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
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

// The point of this file: the COMMUNITY edition.
vi.mock('@/lib/edition', () => ({
  IS_CE: true,
}));

vi.mock('@/lib/format-cost', () => ({
  isCeMode: true,
}));

const linkState = vi.hoisted(() => ({
  status: null as Record<string, unknown> | null,
  isLoading: false,
  isCloudLinked: false,
  // Install-global link: an invited member inherits this (true) without a per-user link
  // (isCloudLinked stays false). The highlights row gates on THIS flag, like the marketplace.
  isInstallCloudLinked: false,
}));

vi.mock('@/hooks/useCeCloudLinkStatus', () => ({
  useCeCloudLinkStatus: () => ({ ...linkState }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string | null }) => unknown) => sel({ currentOrgId: 'org1' }),
}));

const orchestratorApiMock = vi.hoisted(() => ({
  getMarketplacePublications: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
  orchestratorApi: orchestratorApiMock,
}));

const publicationServiceMock = vi.hoisted(() => ({
  getHighlights: vi.fn(),
  getRemoteHighlights: vi.fn(),
  getRemoteMarketplacePublications: vi.fn(),
  getLandingSnapshot: vi.fn(),
  getFavorites: vi.fn(),
  getAcquiredApplicationsPage: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));

// The favorites effect merges PUBLICATION favorites with native WORKFLOW favorites
// (acquired apps). Stub the workflow-favorites store so that effect resolves to empty.
const favoriteServiceMock = vi.hoisted(() => ({
  getFavoriteIds: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator/favorite.service', () => ({
  favoriteService: favoriteServiceMock,
}));

vi.mock('@/components/marketplace/PublisherAvatar', () => ({
  PublisherAvatar: () => <span data-testid="publisher-avatar" />,
}));

vi.mock('@/components/marketplace/ShowcasePreview', () => ({
  ShowcasePreview: () => <div data-testid="showcase-preview" />,
}));

vi.mock('@/components/marketplace/InterfacePreview', () => ({
  InterfacePreview: () => <div data-testid="interface-preview" />,
}));

vi.mock('@/components/WorkflowNodeIcons', () => ({
  WorkflowNodeIcons: () => <span data-testid="node-icons" />,
}));

vi.mock('@/components/agents', () => ({
  AvatarDisplay: () => <span data-testid="avatar-display" />,
}));

import { HighlightedApps } from '../HighlightedApps';

const CURATED_PUB = {
  id: 'pub-curated-1',
  title: 'Curated Cloud App',
  description: 'Admin-curated highlight from the cloud.',
  displayMode: 'APPLICATION',
  creditsPerUse: 0,
};

const MARKETPLACE_PUB = {
  id: 'pub-fallback-1',
  title: 'Fallback Marketplace App',
  displayMode: 'APPLICATION',
  creditsPerUse: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  linkState.status = null;
  linkState.isLoading = false;
  linkState.isCloudLinked = false;
  linkState.isInstallCloudLinked = false;
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getHighlights.mockResolvedValue({ highlights: [] });
  publicationServiceMock.getRemoteHighlights.mockResolvedValue({ highlights: [] });
  publicationServiceMock.getRemoteMarketplacePublications.mockResolvedValue({ publications: [] });
  publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] });
  publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({ items: [] });
  favoriteServiceMock.getFavoriteIds.mockResolvedValue([]);
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
});

afterEach(() => {
  cleanup();
});

describe('HighlightedApps - CE cloud-parity', () => {
  it('linked CE renders the cloud-curated highlights via the remote proxy', async () => {
    linkState.isCloudLinked = true;
    linkState.isInstallCloudLinked = true;
    publicationServiceMock.getRemoteHighlights.mockResolvedValue({
      displayMode: 'APPLICATION',
      highlights: [{ rank: 1, publication: CURATED_PUB }],
    });

    render(<HighlightedApps />);

    expect(await screen.findByText('Curated Cloud App')).toBeInTheDocument();
    expect(publicationServiceMock.getRemoteHighlights).toHaveBeenCalledWith('APPLICATION');
    // The LOCAL endpoints are never touched by a linked install.
    expect(publicationServiceMock.getHighlights).not.toHaveBeenCalled();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
  });

  it('MEMBER of an admin-linked install (no per-user link) still sees the highlights via the remote proxy', async () => {
    // The regression: an invited member has isCloudLinked=false but isInstallCloudLinked=true.
    // Gating on the per-user flag hid the Home highlights for every member; gating on the
    // install-global flag (the fix) shows them, matching the marketplace + sidebar badge.
    linkState.isCloudLinked = false;
    linkState.isInstallCloudLinked = true;
    publicationServiceMock.getRemoteHighlights.mockResolvedValue({
      displayMode: 'APPLICATION',
      highlights: [{ rank: 1, publication: CURATED_PUB }],
    });

    render(<HighlightedApps />);

    expect(await screen.findByText('Curated Cloud App')).toBeInTheDocument();
    expect(publicationServiceMock.getRemoteHighlights).toHaveBeenCalledWith('APPLICATION');
    expect(publicationServiceMock.getHighlights).not.toHaveBeenCalled();
  });

  it('linked CE falls back to the remote marketplace top when no curated highlights exist', async () => {
    linkState.isCloudLinked = true;
    linkState.isInstallCloudLinked = true;
    publicationServiceMock.getRemoteMarketplacePublications.mockResolvedValue({
      publications: [MARKETPLACE_PUB],
    });

    render(<HighlightedApps />);

    expect(await screen.findByText('Fallback Marketplace App')).toBeInTheDocument();
    expect(publicationServiceMock.getRemoteMarketplacePublications).toHaveBeenCalledWith(0, 24);
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
  });

  it('unlinked CE surfaces nothing and fires NO fetch (community apps gated behind the cloud link)', async () => {
    linkState.isCloudLinked = false;

    const { container } = render(<HighlightedApps />);

    // Gated: the row is hidden and NO source is read - neither local nor remote.
    await waitFor(() => {
      expect(container.firstChild).toBeNull();
    });
    expect(publicationServiceMock.getHighlights).not.toHaveBeenCalled();
    expect(publicationServiceMock.getRemoteHighlights).not.toHaveBeenCalled();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
    expect(publicationServiceMock.getRemoteMarketplacePublications).not.toHaveBeenCalled();
  });

  it('fires NO fetch while the CE link status is still resolving (no flash of the wrong source)', () => {
    linkState.isLoading = true;

    render(<HighlightedApps />);

    expect(publicationServiceMock.getHighlights).not.toHaveBeenCalled();
    expect(publicationServiceMock.getRemoteHighlights).not.toHaveBeenCalled();
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
    expect(publicationServiceMock.getRemoteMarketplacePublications).not.toHaveBeenCalled();
  });
});
