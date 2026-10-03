// @vitest-environment jsdom
/**
 * The marketplace view that a reload must reopen: the Explore search text and category, and
 * the visibility filter of My Publications.
 *
 * The type / sort / rating / date / price refinements already lived in the address (see
 * MarketplaceRefinements.test.tsx). The search box and the category did not, so a visitor who
 * opened a publication from a searched, categorised grid came back to the unfiltered one.
 *
 * The router here is backed by a real in-memory URL: a write has to be SEEN by the page, or a
 * test would pass on a param that is written and never read back.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent, act } from '@testing-library/react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn().mockResolvedValue(undefined) }),
}));
vi.mock('@/lib/api/cloud-link.service', () => ({
  cloudLinkService: { getAuthUrl: vi.fn(), connect: vi.fn() },
}));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: () => {} }));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
const auth = vi.hoisted(() => ({ isLoading: false, isAuthenticated: true, numericUserId: 7 }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => auth }));
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
}));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: publicationServiceMock,
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({ workflowService: {} }));
// The real filter is a Radix select fed by a categories request; what is under test is what
// the page does with the choice, so the stand-in shows the selection and offers one.
vi.mock('@/components/marketplace/CategoryFilter', () => ({
  CategoryFilter: (props: { selectedCategory?: string; onCategoryChange: (slug?: string) => void }) => (
    <div>
      <span data-testid="category-value">{props.selectedCategory ?? 'all'}</span>
      <button type="button" onClick={() => props.onCategoryChange('sales')}>pick-sales</button>
      <button type="button" onClick={() => props.onCategoryChange(undefined)}>pick-all</button>
    </div>
  ),
}));
vi.mock('@/components/marketplace/AcquirePublicationModal', () => ({ default: () => null }));
vi.mock('@/components/marketplace/InstallSummaryModal', () => ({ InstallSummaryModal: () => null }));
vi.mock('@/components/marketplace/PublicationCard', () => ({
  PublicationCard: (props: { publication: { title: string } }) => (
    <div data-testid="pub-card">{props.publication.title}</div>
  ),
  PublicationCardSkeleton: () => <div data-testid="card-skeleton" />,
}));
// Radix selects do not open under jsdom; a native one drives the same onValueChange.
vi.mock('@/components/ui/select', () => ({
  Select: (props: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) => (
    <select data-testid="select" value={props.value} onChange={(e) => props.onValueChange(e.target.value)}>
      {props.children}
    </select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: (props: { children: React.ReactNode }) => <>{props.children}</>,
  SelectItem: (props: { value: string }) => <option value={props.value}>{props.value}</option>,
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import MarketplacePage from '../page';

const PATH = '/en/app/marketplace';

beforeEach(() => {
  fakeFolderRouter.reset(PATH);
  auth.isAuthenticated = true;
  orchestratorApiMock.getMarketplacePublications.mockResolvedValue({ publications: [], count: 0 });
  orchestratorApiMock.searchPublications.mockResolvedValue({ publications: [] });
  orchestratorApiMock.getMyPublications.mockResolvedValue({
    publications: [
      { id: 'a', title: 'Public App', displayMode: 'APPLICATION', visibility: 'PUBLIC' },
      { id: 'b', title: 'Private App', displayMode: 'APPLICATION', visibility: 'PRIVATE' },
    ],
  });
  publicationServiceMock.getAcquiredApplications.mockResolvedValue({ applications: [] });
  publicationServiceMock.getPurchases.mockResolvedValue({ purchases: [] });
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Explore: search and category survive a reload', () => {
  it('opened on ?q= and ?category=, searches that text inside that category', async () => {
    fakeFolderRouter.navigate(`${PATH}?q=invoice&category=sales`, 'replace');

    render(<MarketplacePage />);

    await waitFor(() => expect(orchestratorApiMock.searchPublications).toHaveBeenCalled());
    const [query, category] = orchestratorApiMock.searchPublications.mock.calls[0];
    expect(query).toBe('invoice');
    expect(category).toBe('sales');
    expect(screen.getByPlaceholderText('searchPlaceholder')).toHaveValue('invoice');
    expect(screen.getByTestId('category-value')).toHaveTextContent('sales');
    // The unfiltered grid was never asked for on the way.
    expect(orchestratorApiMock.getMarketplacePublications).not.toHaveBeenCalled();
  });

  it('opened on ?category= alone, browses the grid of that category', async () => {
    fakeFolderRouter.navigate(`${PATH}?category=sales`, 'replace');

    render(<MarketplacePage />);

    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalled());
    expect(orchestratorApiMock.getMarketplacePublications.mock.calls[0][2]).toBe('sales');
  });

  it('writes the typed text to ?q= and keeps the other params', async () => {
    fakeFolderRouter.navigate(`${PATH}?sort=recent`, 'replace');
    render(<MarketplacePage />);
    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalled());

    fireEvent.change(screen.getByPlaceholderText('searchPlaceholder'), { target: { value: 'crm' } });

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('sort=recent&q=crm'));
    // A refinement, not a step: Back must leave the marketplace, not walk the keystrokes.
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('replace');
  });

  it('writes the chosen category to ?category= and drops it on "all"', async () => {
    render(<MarketplacePage />);
    await waitFor(() => expect(orchestratorApiMock.getMarketplacePublications).toHaveBeenCalled());

    fireEvent.click(screen.getByText('pick-sales'));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('category=sales'));

    fireEvent.click(screen.getByText('pick-all'));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('resetting the refinements leaves the search and the category in place', async () => {
    fakeFolderRouter.navigate(`${PATH}?q=crm&category=sales&price=free&rating=rated`, 'replace');
    render(<MarketplacePage />);
    await waitFor(() => expect(orchestratorApiMock.searchPublications).toHaveBeenCalled());

    await act(async () => {
      fireEvent.click(screen.getByTestId('marketplace-reset-filters'));
    });

    expect(fakeFolderRouter.search()).toBe('q=crm&category=sales');
    expect(screen.getByPlaceholderText('searchPlaceholder')).toHaveValue('crm');
    expect(screen.getByTestId('category-value')).toHaveTextContent('sales');
  });
});

describe('My Publications: the visibility filter survives a reload', () => {
  it('opened on ?visibility=private, shows only the private publications', async () => {
    fakeFolderRouter.navigate(`${PATH}?tab=mine&visibility=private`, 'replace');

    render(<MarketplacePage />);

    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(1));
    expect(screen.getByTestId('pub-card')).toHaveTextContent('Private App');
  });

  it('falls back to every publication on a value it does not know', async () => {
    fakeFolderRouter.navigate(`${PATH}?tab=mine&visibility=secret`, 'replace');

    render(<MarketplacePage />);

    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(2));
  });

  it('writes the chosen visibility to ?visibility=', async () => {
    fakeFolderRouter.navigate(`${PATH}?tab=mine`, 'replace');
    render(<MarketplacePage />);
    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(2));

    fireEvent.change(screen.getByTestId('select'), { target: { value: 'public' } });

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=mine&visibility=public'));
    expect(screen.getByTestId('pub-card')).toHaveTextContent('Public App');
  });
});

/**
 * A tab is a step, like on every other tabbed page: it is pushed, so Back returns to the tab
 * before it, and it takes with it the search, category and visibility filter of the tab being
 * left. Carried over, they would sit in the address filtering a tab that does not show them.
 */
describe('Marketplace tabs: a switch is a step, and leaves the filters of the previous tab behind', () => {
  it('pushes the tab and drops the search and category, keeping the refinements', async () => {
    fakeFolderRouter.navigate(`${PATH}?q=crm&category=sales&type=agents`, 'replace');
    render(<MarketplacePage />);
    await screen.findByText('tabMyPublications');
    fakeFolderRouter.navigations.length = 0;

    fireEvent.click(screen.getByText('tabMyPublications'));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('type=agents&tab=mine'));
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');
  });

  it('drops the visibility filter when leaving My Publications', async () => {
    fakeFolderRouter.navigate(`${PATH}?tab=mine&visibility=private`, 'replace');
    render(<MarketplacePage />);
    await waitFor(() => expect(screen.getAllByTestId('pub-card')).toHaveLength(1));

    fireEvent.click(screen.getByText('tabExplore'));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('puts a signed-out visitor back on Explore with a replace, not a step to come Back to', async () => {
    auth.isAuthenticated = false;
    fakeFolderRouter.navigate(`${PATH}?tab=mine`, 'replace');
    fakeFolderRouter.navigations.length = 0;

    render(<MarketplacePage />);

    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
    expect(fakeFolderRouter.navigations.every((n) => n.method === 'replace')).toBe(true);
  });
});
