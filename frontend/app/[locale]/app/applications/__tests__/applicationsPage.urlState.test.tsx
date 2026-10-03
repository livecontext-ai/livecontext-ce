// @vitest-environment jsdom
/**
 * The applications list keeps its view (search, sort, provenance, visibility, node types,
 * page, page size) in the address, so a reload reopens it as it was.
 *
 * The page number is the fragile one: the list is loaded asynchronously, so for a moment the
 * count is 0 and "the page is out of range" is true of every restored page. The tests below
 * hold the load open to prove the page survives that moment.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const mocks = vi.hoisted(() => ({
  getAcquiredApplicationsPage: vi.fn(),
  getMyPublicationsPage: vi.fn(),
  getApplicationRunVersionBatch: vi.fn(),
  // One object for the whole file: the selection feeds effect deps of the folder hook, and a
  // fresh one per render would make the page loop.
  selection: {
    selectedIds: new Set<string>(),
    toggle: () => {},
    clear: () => {},
    selectAll: () => {},
  },
  router: { push: () => {} },
}));

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
vi.mock('@/i18n/navigation', () => ({ useRouter: () => mocks.router }));
vi.mock('@/lib/providers/smart-providers', () => ({ useAuth: () => ({ isLoading: false }) }));
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getAcquiredApplicationsPage: mocks.getAcquiredApplicationsPage,
    getMyPublicationsPage: mocks.getMyPublicationsPage,
    getFavoriteIds: () => Promise.resolve([]),
    addFavorite: () => Promise.resolve(),
    removeFavorite: () => Promise.resolve(),
  },
}));
vi.mock('@/lib/api/orchestrator/workflow.service', () => ({
  workflowService: {
    getApplicationRunVersionBatch: mocks.getApplicationRunVersionBatch,
    getWorkflowRelationsBatch: () => Promise.resolve({}),
    deleteWorkflow: () => Promise.resolve(),
  },
}));
vi.mock('@/lib/api/orchestrator/favorite.service', () => ({
  favoriteService: {
    getFavoriteIds: () => Promise.resolve([]),
    addFavorite: () => Promise.resolve(),
    removeFavorite: () => Promise.resolve(),
  },
}));
vi.mock('@/lib/api/orchestrator/resource-folder.service', () => ({
  resourceFolderService: {
    list: () => Promise.resolve([]),
    memberships: () => Promise.resolve(new Map()),
  },
}));
vi.mock('@/components/marketplace/ShowcasePreview', () => ({ ShowcasePreview: () => null }));
vi.mock('@/components/WorkflowNodeIcons', () => ({ WorkflowNodeIcons: () => null }));
vi.mock('@/components/marketplace/PublisherAvatar', () => ({ PublisherAvatar: () => null }));
vi.mock('@/components/sharing/ShareLinkDialog', () => ({ ShareLinkDialog: () => null }));
vi.mock('@/components/workflow', () => ({ ShareWorkflowModal: () => null }));
vi.mock('@/components/ui/EmptyState', () => ({ EmptyState: () => null }));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (sel: (s: { currentOrgId: string }) => unknown) => sel({ currentOrgId: 'org1' }),
}));
vi.mock('@/hooks/useSelectableItems', () => ({ useSelectableItems: () => mocks.selection }));

// The node-type picker and the pager, reduced to what they are given and one way to drive them.
vi.mock('@/components/NodeTypeFilter', () => ({
  NodeTypeFilter: ({ value, onChange }: { value: string[]; onChange: (next: string[]) => void }) => (
    <div data-testid="node-types" data-value={value.join('|')}>
      <button type="button" onClick={() => onChange(['mcp:gmail'])}>pick-gmail</button>
    </div>
  ),
}));
vi.mock('@/components/ui/PaginationBar', () => ({
  PaginationBar: ({ page, pageSize, totalCount }: { page: number; pageSize: number; totalCount: number }) => (
    <div data-testid="pager" data-page={page} data-page-size={pageSize} data-total={totalCount} />
  ),
}));
// A select jsdom can drive: every option is a button that reports its value.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<{ onValueChange?: (v: string) => void }>({});
  return {
    Select: ({ value, onValueChange, children }: {
      value?: string; onValueChange?: (v: string) => void; children: React.ReactNode;
    }) => <Ctx.Provider value={{ onValueChange }}><div data-select-value={value}>{children}</div></Ctx.Provider>,
    SelectTrigger: ({ children, 'aria-label': label }: { children: React.ReactNode; 'aria-label'?: string }) => (
      <div aria-label={label}>{children}</div>
    ),
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const ctx = ReactModule.useContext(Ctx);
      return <button type="button" data-option={value} onClick={() => ctx.onValueChange?.(value)}>{children}</button>;
    },
  };
});

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import ApplicationsPage from '../page';

const PAGE = '/en/app/applications';
const labels = enMessages.applications;

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages as Record<string, unknown>}>
      <ApplicationsPage />
    </NextIntlClientProvider>,
  );
}

const two = (n: number) => String(n).padStart(2, '0');

/**
 * Fifteen of the user's own PRIVATE apps built with Gmail ("App 01" .. "App 15"), handed over
 * in REVERSE order so the name sort is visible, plus one decoy per filter: a public one, one
 * without Gmail, and an acquired one.
 */
const PUBLISHED = [
  ...Array.from({ length: 15 }, (_, i) => ({
    id: `p${two(15 - i)}`, title: `App ${two(15 - i)}`, workflowId: `wf${two(15 - i)}`,
    status: 'ACTIVE', visibility: 'PRIVATE', nodeTypes: ['mcp:gmail'],
  })),
  { id: 'p-public', title: 'App public', workflowId: 'wf-public', status: 'ACTIVE', visibility: 'PUBLIC', nodeTypes: ['mcp:gmail'] },
  { id: 'p-slack', title: 'App slack', workflowId: 'wf-slack', status: 'ACTIVE', visibility: 'PRIVATE', nodeTypes: ['mcp:slack'] },
];
const ACQUIRED = [{
  publication: { id: 'a1', title: 'App acquired', workflowId: 'wf-src', status: 'ACTIVE', visibility: 'PUBLIC', nodeTypes: ['mcp:gmail'] },
  workflowId: 'wf-clone',
  acquiredAt: '2026-09-01T00:00:00Z',
}];

/** The card titles on screen, in order. A title may be drawn more than once per card. */
function shownTitles(): string[] {
  const titles = screen.queryAllByText(/^App /).map((el) => el.textContent ?? '');
  return titles.filter((title, i) => titles.indexOf(title) === i);
}

const query = () => new URLSearchParams(fakeFolderRouter.search());
const selectValue = (label: string) =>
  screen.getByLabelText(label).closest('[data-select-value]')?.getAttribute('data-select-value');
const selectOption = (label: string, value: string) =>
  screen.getByLabelText(label).closest('[data-select-value]')!.querySelector(`[data-option="${value}"]`)!;

/** A load the test finishes by hand, to look at the page while nothing has come back yet. */
function holdPublishedLoad() {
  let settle: { resolve: (v: unknown) => void; reject: (e: unknown) => void } | undefined;
  mocks.getMyPublicationsPage.mockImplementation(
    () => new Promise((resolve, reject) => { settle = { resolve, reject }; }),
  );
  return {
    finish: () => settle!.resolve({ items: PUBLISHED, totalCount: PUBLISHED.length }),
    fail: () => settle!.reject(new Error('network down')),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.getMyPublicationsPage.mockResolvedValue({ items: PUBLISHED, totalCount: PUBLISHED.length });
  mocks.getAcquiredApplicationsPage.mockResolvedValue({ items: ACQUIRED, totalCount: ACQUIRED.length });
  mocks.getApplicationRunVersionBatch.mockResolvedValue({});
});

afterEach(() => cleanup());

describe('Applications page - view kept in the address', () => {
  const RESTORED = 'q=app&sort=name&source=published&visibility=private&types=mcp%3Agmail&page=2&size=10';

  it('opens in the state the address carries: every control shows it and the grid is page 2', async () => {
    openAt(RESTORED);
    renderPage();

    // 15 apps pass the three filters; sorted by name at 10 a page, page 2 is the last five.
    await screen.findByText('App 11');
    expect(shownTitles()).toEqual(['App 11', 'App 12', 'App 13', 'App 14', 'App 15']);

    expect((screen.getByPlaceholderText(labels.searchPlaceholder) as HTMLInputElement).value).toBe('app');
    expect(selectValue(labels.sortBy)).toBe('name');
    expect(selectValue(labels.filterByVisibility)).toBe('private');
    expect(screen.getByRole('button', { name: labels.filterPublished })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: labels.filterAll })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('node-types')).toHaveAttribute('data-value', 'mcp:gmail');
    expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '1');
    expect(screen.getByTestId('pager')).toHaveAttribute('data-page-size', '10');
    expect(screen.getByTestId('pager')).toHaveAttribute('data-total', '15');

    // The search is the one refinement the server applies: it must go out with the first load.
    expect(mocks.getMyPublicationsPage).toHaveBeenCalledWith(expect.objectContaining({ q: 'app' }));
    expect(mocks.getMyPublicationsPage).toHaveBeenCalledTimes(1);
    // Nothing was rewritten on the way in.
    expect(fakeFolderRouter.search()).toBe(RESTORED);
  });

  it('a restored page is not thrown away while the apps are still loading and the count is 0', async () => {
    const load = holdPublishedLoad();
    openAt(RESTORED);
    renderPage();

    // Nothing has come back: 0 apps, so 1 page, and page 2 is "out of range". Let every effect
    // of that state run before looking.
    await act(async () => { await Promise.resolve(); });
    expect(mocks.getMyPublicationsPage).toHaveBeenCalledTimes(1);
    expect(shownTitles()).toEqual([]);
    expect(query().get('page')).toBe('2');

    await act(async () => { load.finish(); });

    // Had the page been snapped to the first one during the load, the grid would open on App 01.
    await screen.findByText('App 11');
    expect(shownTitles()).toEqual(['App 11', 'App 12', 'App 13', 'App 14', 'App 15']);
    expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '1');
    expect(fakeFolderRouter.search()).toBe(RESTORED);
  });

  it('a restored page survives a FAILED load, whose count of 0 says nothing about the list', async () => {
    const load = holdPublishedLoad();
    openAt('page=2&size=10');
    renderPage();
    await act(async () => { await Promise.resolve(); });

    await act(async () => { load.fail(); });

    expect(await screen.findByText('network down')).toBeInTheDocument();
    // A reload after the network is back must reopen page 2, not page 1.
    expect(fakeFolderRouter.search()).toBe('page=2&size=10');
  });

  it('a restored page that is past the end once the apps HAVE loaded steps back to the last one', async () => {
    openAt('page=9&size=10');
    renderPage();

    // 18 apps at 10 a page: page 2 is the last, and holds the remaining eight.
    await waitFor(() => expect(query().get('page')).toBe('2'));
    expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '1');
    expect(shownTitles()).toHaveLength(8);
    expect(query().get('size')).toBe('10');
  });

  it('restored filters that match nothing leave the first page, and keep the filters', async () => {
    // Acquired apps carry no visibility of their own, so "installed" and "private" exclude
    // each other: the load comes back with apps and none of them matches.
    openAt('source=installed&visibility=private&page=2&size=10');
    renderPage();

    await waitFor(() => expect(query().has('page')).toBe(false));
    expect(shownTitles()).toEqual([]);
    expect(screen.getByRole('button', { name: labels.filterInstalled })).toHaveAttribute('aria-pressed', 'true');
    expect(selectValue(labels.filterByVisibility)).toBe('private');
    expect(fakeFolderRouter.search()).toBe('source=installed&visibility=private&size=10');
  });

  it('ignores a sort, a provenance, a visibility, a page and a size it does not have', async () => {
    openAt('sort=nope&source=nope&visibility=nope&page=0&size=5000');
    renderPage();

    await screen.findByText('App 01');
    expect(selectValue(labels.sortBy)).toBe('execution');
    expect(selectValue(labels.filterByVisibility)).toBe('all');
    expect(screen.getByRole('button', { name: labels.filterAll })).toHaveAttribute('aria-pressed', 'true');
    // All 18 on one page of the default size: no pager.
    expect(shownTitles()).toHaveLength(18);
    expect(screen.queryByTestId('pager')).not.toBeInTheDocument();
  });

  // Every filter below still leaves more than one page of apps, so the page is not dropped
  // because it fell out of range: it is dropped because the set it indexed is another one.
  it.each([
    ['the sort', 'sort', 'name', () => selectOption(labels.sortBy, 'name')],
    ['the visibility', 'visibility', 'private', () => selectOption(labels.filterByVisibility, 'private')],
    ['the provenance', 'source', 'published', () => screen.getByRole('button', { name: labels.filterPublished })],
    ['the node types', 'types', 'mcp:gmail', () => screen.getByText('pick-gmail')],
  ])('changing %s writes it to the address and goes back to the first page', async (_name, key, value, control) => {
    openAt('page=2&size=10');
    renderPage();
    await waitFor(() => expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '1'));

    fireEvent.click(control());

    await waitFor(() => expect(query().has('page')).toBe(false));
    expect(query().get(key)).toBe(value);
    expect(query().get('size')).toBe('10');
    expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '0');
    expect(Number(screen.getByTestId('pager').getAttribute('data-total'))).toBeGreaterThan(10);
  });

  it('typing a search writes it to the address and goes back to the first page', async () => {
    openAt('page=2&size=10');
    renderPage();
    await waitFor(() => expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '1'));

    fireEvent.change(screen.getByPlaceholderText(labels.searchPlaceholder), { target: { value: 'app' } });

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('size=10&q=app'));
    await waitFor(() => expect(screen.getByTestId('pager')).toHaveAttribute('data-page', '0'));
    expect(mocks.getMyPublicationsPage).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'app' }));
  });

  it('picking the default value again removes the param instead of spelling it', async () => {
    openAt('sort=name&source=published');
    renderPage();
    await screen.findByText('App 01');

    fireEvent.click(selectOption(labels.sortBy, 'execution'));
    fireEvent.click(screen.getByRole('button', { name: labels.filterAll }));

    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });
});
