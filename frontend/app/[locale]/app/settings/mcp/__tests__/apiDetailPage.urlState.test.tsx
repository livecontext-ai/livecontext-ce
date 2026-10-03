// @vitest-environment jsdom
/**
 * An API's detail page opens on a tab chosen in this order: the one the address names, else
 * the one last opened for THIS api (kept in localStorage), else Overview. Clicking a tab
 * writes it to the address, so a reload or a shared link reopens the same tab.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// One object per collaborator for the whole file: several of them feed effect deps.
const stable = vi.hoisted(() => ({
  params: { apiSlug: 'api-1' },
  auth: { user: { id: 'u1' }, isAuthenticated: true, isAuthChecking: false },
  userApis: { apis: [], isLoading: false, error: null, getApiById: () => undefined },
  userData: { status: null, profile: null, monetization: null, isLoading: false },
  api: {
    api: { id: 'api-1', apiName: 'Weather API', isActive: true, status: 'PUBLISHED' },
    isLoading: false,
    error: null,
    refetch: () => {},
  },
  tools: { tools: [], isLoading: false, error: null },
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return { ...mod.fakeFolderRouter.nextNavigationModule(), useParams: () => stable.params };
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/hooks/useAuthGuard', () => ({ useAuthGuard: () => stable.auth }));
vi.mock('@/hooks/useUserApis', () => ({ useUserApis: () => stable.userApis }));
vi.mock('@/hooks/useUnifiedUserData', () => ({ useUnifiedUserData: () => stable.userData }));
vi.mock('@/hooks/useApiById', () => ({ useApiById: () => stable.api }));
vi.mock('@/hooks/useApiDetails', () => ({
  useApiDetails: () => stable.api,
  useApiTools: () => stable.tools,
}));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: {} }));
vi.mock('@/components/NavigationLoader', () => ({ default: () => null }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => null }));

// The page's own pieces, reduced to "which tab is drawn" and "a way to pick another".
vi.mock('../[apiSlug]/components/ApiHeader', () => ({ default: () => null }));
vi.mock('../[apiSlug]/components/TabNavigation', () => ({
  default: ({ activeTab, onTabChange }: { activeTab: string; onTabChange: (id: string) => void }) => (
    <nav data-testid="tabs" data-active={activeTab}>
      {['overview', 'tools', 'monetize'].map((id) => (
        <button key={id} type="button" onClick={() => onTabChange(id)}>{`go-${id}`}</button>
      ))}
    </nav>
  ),
}));
vi.mock('../[apiSlug]/components/tabs/OverviewTab', () => ({ default: () => <div data-testid="tab-overview" /> }));
vi.mock('../[apiSlug]/components/tabs/ToolsTab', () => ({ default: () => <div data-testid="tab-tools" /> }));
vi.mock('../[apiSlug]/components/tabs/MonetizeTab', () => ({ default: () => <div data-testid="tab-monetize" /> }));

import ApiDetailPage from '../[apiSlug]/page';

const PAGE = '/en/app/settings/mcp/api-1';
const STORED_TAB_KEY = 'api-tab-api-1';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

/** The tab the page draws: the highlighted one AND the content under it, which must agree. */
function shownTab(): string | null {
  const active = screen.getByTestId('tabs').getAttribute('data-active');
  const drawn = ['overview', 'tools', 'monetize'].filter((id) => screen.queryByTestId(`tab-${id}`));
  expect(drawn).toEqual([active]);
  return active;
}

beforeEach(() => localStorage.clear());
afterEach(() => { cleanup(); localStorage.clear(); });

describe('ApiDetailPage - which tab opens', () => {
  it('the tab the address names wins over the one remembered for this api', () => {
    localStorage.setItem(STORED_TAB_KEY, 'monetize');
    openAt('tab=tools');
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('tools');
    // Reading the address is not a visit: the remembered tab is only moved by a click.
    expect(localStorage.getItem(STORED_TAB_KEY)).toBe('monetize');
  });

  it('with no tab in the address, the tab remembered for this api opens, and the address stays clean', () => {
    localStorage.setItem(STORED_TAB_KEY, 'monetize');
    openAt();
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('monetize');
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('with neither, the page opens on Overview', () => {
    openAt();
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('overview');
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('a tab remembered for ANOTHER api does not apply here', () => {
    localStorage.setItem('api-tab-api-2', 'monetize');
    openAt();
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('overview');
  });

  it('a tab the address names that does not exist falls back to the remembered one', () => {
    localStorage.setItem(STORED_TAB_KEY, 'tools');
    openAt('tab=analytics');
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('tools');
  });

  it('a remembered tab that does not exist falls back to Overview', () => {
    localStorage.setItem(STORED_TAB_KEY, 'analytics');
    openAt();
    render(<ApiDetailPage />);

    expect(shownTab()).toBe('overview');
  });
});

describe('ApiDetailPage - picking a tab', () => {
  it('writes the tab to the address as a step Back can undo, and remembers it for this api', () => {
    openAt();
    render(<ApiDetailPage />);

    fireEvent.click(screen.getByText('go-tools'));

    expect(shownTab()).toBe('tools');
    expect(fakeFolderRouter.search()).toBe('tab=tools');
    expect(fakeFolderRouter.navigations.at(-1)?.method).toBe('push');
    expect(localStorage.getItem(STORED_TAB_KEY)).toBe('tools');
  });

  it('drops the search and the sub-view of the tab being left, and keeps the other params', () => {
    openAt('tab=tools&q=forecast&view=grid&keep=1');
    render(<ApiDetailPage />);

    fireEvent.click(screen.getByText('go-monetize'));

    expect(shownTab()).toBe('monetize');
    expect(fakeFolderRouter.search()).toBe('tab=monetize&keep=1');
  });

  it('picking Overview is spelled in the address too: absence means "the remembered tab", not Overview', () => {
    localStorage.setItem(STORED_TAB_KEY, 'monetize');
    openAt();
    render(<ApiDetailPage />);
    expect(shownTab()).toBe('monetize');

    fireEvent.click(screen.getByText('go-overview'));

    expect(shownTab()).toBe('overview');
    expect(fakeFolderRouter.search()).toBe('tab=overview');
  });

  it('Back to the address without a tab reopens the tab that was last picked', () => {
    openAt();
    render(<ApiDetailPage />);
    fireEvent.click(screen.getByText('go-tools'));
    expect(shownTab()).toBe('tools');

    // What Back does: the address returns to the entry before the click.
    fireEvent.click(screen.getByText('go-monetize'));
    React.act(() => fakeFolderRouter.navigate(PAGE, 'replace'));

    // No tab in the address: the remembered one applies, and that is the last one picked.
    expect(shownTab()).toBe('monetize');
  });
});
