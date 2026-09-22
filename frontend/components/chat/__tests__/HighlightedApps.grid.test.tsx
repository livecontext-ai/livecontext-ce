// @vitest-environment jsdom
/**
 * How the Home highlights row wires itself to the balanced column layout.
 *
 * The column ARITHMETIC is covered by highlightGridColumns.test.ts. What matters
 * here is the wiring the component owns: the grid must sit inside a `@container`
 * (a container query with no container silently pins the row to its narrowest
 * tier), it must ask for the columns its own card count needs, and the loading
 * skeletons must lay out exactly like the cards that replace them.
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
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => ({ isAuthenticated: true, isReady: true }) }));
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
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => <div data-testid="showcase" /> }));
vi.mock('@/components/marketplace/InterfacePreview', () => ({ InterfacePreview: () => <div /> }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => <span /> }));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span /> }));

import { HighlightedApps } from '../HighlightedApps';
import { highlightGridColumns } from '../highlightGridColumns';

const curated = (n: number) => Array.from({ length: n }, (_, i) => ({
  rank: i + 1,
  publication: {
    id: `hl-${i}`,
    title: `Highlight ${i}`,
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    showcaseRunId: `run-${i}`,
    showcaseInterfaceId: `iface-${i}`,
  },
}));

async function renderRow(count: number): Promise<HTMLElement> {
  publicationServiceMock.getHighlights.mockResolvedValue({ displayMode: 'APPLICATION', highlights: curated(count) });
  render(<HighlightedApps />);
  await waitFor(() => expect(screen.getAllByTestId('showcase')).toHaveLength(count));
  return screen.getByTestId('highlight-grid');
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  publicationServiceMock.getLandingSnapshot.mockResolvedValue({ landing: null });
  publicationServiceMock.getFavorites.mockResolvedValue({ favorites: [] });
  publicationServiceMock.getAcquiredApplicationsPage.mockResolvedValue({ items: [], totalCount: 0, page: 0, size: 25 });
  publicationServiceMock.getPublicationByIdPublic.mockResolvedValue({});
  favoriteServiceMock.getFavoriteIds.mockResolvedValue([]);
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [] });
});

afterEach(() => cleanup());

describe('HighlightedApps grid wiring', () => {
  it('measures the row itself, not the viewport, so a collapsed sidebar is accounted for', async () => {
    const grid = await renderRow(4);
    // Without this the @min-[...] queries have no container to resolve against
    // and the row silently collapses to its narrowest tier.
    expect(grid.parentElement).toHaveClass('@container');
  });

  it('asks for the columns its own card count needs, and drops the viewport breakpoints', async () => {
    const grid = await renderRow(4);
    expect(grid.className).toContain(highlightGridColumns(4));
    // The viewport breakpoints are what produced the 3 + 1 row next to an open sidebar.
    expect(grid.className).not.toContain('md:grid-cols-3');
    expect(grid.className).not.toContain('lg:grid-cols-4');
  });

  it('re-tiers for a row that does not hold 4 cards', async () => {
    // A second count is what separates reading items.length from hardcoding the
    // 4 of the default row: favorites go up to 8, and 5 cards drop the widest
    // tier to 3 so the last row is 3 + 2 instead of 4 + 1.
    const grid = await renderRow(5);
    expect(grid.className).toContain(highlightGridColumns(5));
    expect(grid.className).not.toContain(highlightGridColumns(4));
  });

  it('lays the loading skeletons out exactly like the cards that replace them', async () => {
    // A pending fetch keeps the row in its loading state.
    publicationServiceMock.getHighlights.mockReturnValue(new Promise(() => {}));
    render(<HighlightedApps />);
    const grid = await screen.findByTestId('highlight-grid');
    expect(grid.children).toHaveLength(4);
    expect(grid.className).toContain(highlightGridColumns(4));
  });
});
