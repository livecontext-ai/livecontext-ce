// @vitest-environment jsdom
/**
 * The Files page keeps its whole view in the address, so a reload reopens the listing as it was:
 * search, page and page size, source / file-type / date filters, grid vs list, sort.
 *
 * Unlike the other FileBrowser suites this one runs the REAL {@code useStorageExplorer}: the
 * contract is "the address decides what is REQUESTED", and a mocked hook could only show that a
 * value was handed over, not that the first request already carries it. The trap it pins is the
 * "filter changed, back to page 1" rule firing on mount and wiping the page a reload asked for.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';
import { render, cleanup, fireEvent, waitFor, act } from '@testing-library/react';

vi.mock('@/i18n/navigation', () => ({
  useRouter: () => ({ push: () => undefined, replace: () => undefined, prefetch: () => undefined }),
  usePathname: () => '/app',
  Link: ({ children }: { children?: React.ReactNode }) => <a>{children}</a>,
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));
vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});

const getExplorerEntries = vi.fn();
const getFolderTrail = vi.fn();
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: {
    getExplorerEntries: (...a: unknown[]) => getExplorerEntries(...a),
    getFolderTrail: (...a: unknown[]) => getFolderTrail(...a),
    getAllFolders: vi.fn().mockResolvedValue([]),
  },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  MouseSensor: class {},
  TouchSensor: class {},
  pointerWithin: () => [],
  rectIntersection: () => [],
  useSensor: () => ({}),
  useSensors: () => [],
}));
vi.mock('../FilesExplorerBody', () => ({ FilesExplorerBody: () => <div /> }));
vi.mock('../FilesMoveToFolderDialog', () => ({ FilesMoveToFolderDialog: () => null }));
vi.mock('@/components/app/FileDetailView', () => ({ FileDetailView: () => <div /> }));
vi.mock('@/components/chat/GenerateEntryButton', () => ({ GenerateEntryButton: () => null }));
vi.mock('@/components/generation/GenerationHistoryList', () => ({ GenerationHistoryList: () => null }));

// The filter bar is stubbed down to what it is HANDED and one button per control, so the
// assertions are about what FileBrowser and the hook do with a change, not about the bar's markup.
vi.mock('../FileFilterBar', () => ({
  FileFilterBar: (p: {
    searchInput: string; onSearchChange: (v: string) => void;
    fileType: string; onFileTypeChange: (v: string) => void;
    sourceType: string; onSourceTypeChange: (v: string) => void;
    dateFrom: string; dateTo: string;
    onDateFromChange: (v: string) => void; onDateToChange: (v: string) => void;
    viewMode: string; onViewModeChange: (v: 'grid' | 'list') => void;
    sortKey: string; sortDirection: string;
    onSortKeyChange: (v: 'date' | 'name' | 'size' | 'type') => void;
  }) => (
    <div>
      <span data-testid="shown">
        {[p.searchInput, p.fileType, p.sourceType, p.dateFrom, p.dateTo, p.viewMode, p.sortKey, p.sortDirection].join('|')}
      </span>
      <button data-testid="search-invoice" onClick={() => p.onSearchChange('invoice')} />
      <button data-testid="type-pdf" onClick={() => p.onFileTypeChange('pdf')} />
      <button data-testid="source-step" onClick={() => p.onSourceTypeChange('STEP_OUTPUT')} />
      <button data-testid="from-jan" onClick={() => p.onDateFromChange('2026-01-10')} />
      <button data-testid="set-list" onClick={() => p.onViewModeChange('list')} />
      <button data-testid="sort-by-name" onClick={() => p.onSortKeyChange('name')} />
    </div>
  ),
}));
vi.mock('@/components/ui/PaginationBar', () => ({
  PaginationBar: (p: { page: number; pageSize: number; onPageChange: (n: number) => void; onPageSizeChange: (n: number) => void }) => (
    <div>
      <span data-testid="paging">{`${p.page}/${p.pageSize}`}</span>
      <button data-testid="go-page-4" onClick={() => p.onPageChange(3)} />
      <button data-testid="size-100" onClick={() => p.onPageSizeChange(100)} />
    </div>
  ),
}));
vi.mock('@/components/ui/BulkDeleteModal', () => ({ BulkDeleteModal: () => null }));
vi.mock('@/components/ToastContainer', () => ({ default: () => null }));
vi.mock('@/components/Toast', () => ({ useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }) }));
vi.mock('@/hooks/useAuthToken', () => ({ useAuthToken: () => 'token' }));
vi.mock('@/hooks/useGenerationModels', () => ({
  useGenerationModels: () => ({ models: [], isLoading: false, availability: 'absent' }),
}));
vi.mock('@/hooks/useDebouncedValue', () => ({ useDebouncedValue: (v: unknown) => v }));
// Captured so a test can play a workspace switch.
const orgReset = vi.hoisted(() => ({ fire: () => {} }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({
  useOrgScopedReset: (reset: () => void) => { orgReset.fire = reset; },
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  getActiveOrgHeaderForRequest: () => ({}),
  useCanMutateInCurrentOrg: () => true,
}));
vi.mock('@/lib/api/orchestrator/file.service', () => ({ fileService: { downloadAndSave: vi.fn(), uploadGeneric: vi.fn() } }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import { FileBrowser } from '../FileBrowser';
import { FILES_SORT_STORAGE_KEY, FILES_VIEW_MODE_STORAGE_KEY } from '@/lib/files/filesViewPreferences';
import { dayEdgeInstant } from '@/lib/utils/dateFormatters';

const PATH = '/en/app/files';

/** The params of every listing request so far, oldest first. */
const requests = (): Array<Record<string, unknown>> => getExplorerEntries.mock.calls.map((c) => c[0]);
const lastRequest = () => requests()[requests().length - 1];
/** The address as a map, so an assertion does not depend on the order params were written in. */
const address = () => Object.fromEntries(new URLSearchParams(fakeFolderRouter.search()));

beforeEach(() => {
  fakeFolderRouter.reset(PATH);
  window.localStorage.clear();
  getExplorerEntries.mockReset();
  getExplorerEntries.mockResolvedValue({ content: [], totalElements: 400, totalPages: 8 });
  getFolderTrail.mockReset();
  getFolderTrail.mockResolvedValue([]);
});
afterEach(() => cleanup());

describe('FileBrowser - a reload reopens the listing the address describes', () => {
  it('requests exactly the view in the address, on the FIRST request and on every one after', async () => {
    fakeFolderRouter.navigate(
      `${PATH}?q=report&page=3&size=100&source=STEP_OUTPUT&type=images&from=2026-01-10&to=2026-01-20&view=list&sort=name&dir=desc`,
      'replace',
    );

    const { getByTestId } = render(<FileBrowser />);

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    expect(requests()[0]).toMatchObject({
      page: 2, // the address counts from 1
      size: 100,
      search: 'report',
      sourceType: 'STEP_OUTPUT',
      fileType: 'images',
      // The day typed, at the reader's midnight: the address holds the day, the server the instant.
      dateFrom: dayEdgeInstant('2026-01-10', 'start'),
      dateTo: dayEdgeInstant('2026-01-20', 'end'),
      sort: 'name',
      direction: 'desc',
    });
    await act(async () => {});
    // The mount must not count as "a filter changed": that rule goes back to page 1.
    expect(requests().every((r) => r.page === 2)).toBe(true);
    expect(getByTestId('shown').textContent).toBe('report|images|STEP_OUTPUT|2026-01-10|2026-01-20|list|name|desc');
    expect(getByTestId('paging').textContent).toBe('2/100');
    // Reading the address does not rewrite it.
    expect(fakeFolderRouter.navigations).toHaveLength(1);
  });

  it('ignores values it does not recognise instead of rendering a view it has no control for', async () => {
    fakeFolderRouter.navigate(`${PATH}?type=viruses&sort=colour&dir=sideways&view=3d&page=0&size=9999&from=yesterday`, 'replace');

    const { getByTestId } = render(<FileBrowser />);

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    expect(requests()[0]).toMatchObject({ page: 0, size: 50, sort: 'date', direction: 'desc' });
    expect(requests()[0]).not.toHaveProperty('fileType');
    expect(requests()[0]).not.toHaveProperty('dateFrom');
    expect(getByTestId('shown').textContent).toBe('|_all|||' + '|grid|date|desc');
  });

  it('lands on the last page when the address asks for one past the end', async () => {
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 80, totalPages: 2 });
    fakeFolderRouter.navigate(`${PATH}?page=9`, 'replace');

    render(<FileBrowser />);

    await waitFor(() => expect(address()).toEqual({ page: '2' }));
    await waitFor(() => expect(lastRequest()).toMatchObject({ page: 1 }));
  });
});

describe('FileBrowser - every change of view is written to the address', () => {
  it('writes the page and the page size', async () => {
    const { getByTestId } = render(<FileBrowser />);
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());

    act(() => fireEvent.click(getByTestId('go-page-4')));
    expect(address()).toEqual({ page: '4' });
    await waitFor(() => expect(lastRequest()).toMatchObject({ page: 3 }));

    // A new page size re-paginates from the first page, and the address says so too.
    act(() => fireEvent.click(getByTestId('size-100')));
    await waitFor(() => expect(address()).toEqual({ size: '100' }));
    await waitFor(() => expect(lastRequest()).toMatchObject({ page: 0, size: 100 }));
  });

  it('writes a filter, goes back to the first page, and keeps the other params', async () => {
    fakeFolderRouter.navigate(`${PATH}?page=3&sort=size`, 'replace');
    const { getByTestId } = render(<FileBrowser />);
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());

    act(() => fireEvent.click(getByTestId('type-pdf')));
    await waitFor(() => expect(address()).toEqual({ sort: 'size', type: 'pdf' }));
    await waitFor(() => expect(lastRequest()).toMatchObject({ page: 0, fileType: 'pdf', sort: 'size' }));

    act(() => fireEvent.click(getByTestId('source-step')));
    await waitFor(() => expect(address()).toEqual({ sort: 'size', type: 'pdf', source: 'STEP_OUTPUT' }));

    act(() => fireEvent.click(getByTestId('from-jan')));
    await waitFor(() => expect(address()).toMatchObject({ from: '2026-01-10' }));
    await waitFor(() => expect(lastRequest()).toMatchObject({ dateFrom: dayEdgeInstant('2026-01-10', 'start') }));
    // Refinements of the view, not steps: Back must leave the page, not walk the filters.
    expect(fakeFolderRouter.navigations.every((n) => n.method === 'replace')).toBe(true);
  });

  it('writes the search text once the typing pauses', async () => {
    const { getByTestId } = render(<FileBrowser />);
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());

    act(() => fireEvent.click(getByTestId('search-invoice')));

    await waitFor(() => expect(lastRequest()).toMatchObject({ search: 'invoice' }));
    await waitFor(() => expect(address()).toEqual({ q: 'invoice' }));
  });
});

describe('FileBrowser - the remembered preference is the default, the address wins over it', () => {
  it('starts from the stored sort and view with a clean address', async () => {
    window.localStorage.setItem(FILES_SORT_STORAGE_KEY, JSON.stringify({ key: 'size', direction: 'asc' }));
    window.localStorage.setItem(FILES_VIEW_MODE_STORAGE_KEY, 'list');

    const { getByTestId } = render(<FileBrowser />);

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    expect(requests()[0]).toMatchObject({ sort: 'size', direction: 'asc' });
    expect(getByTestId('shown').textContent).toContain('|list|size|asc');
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('lets the address override the stored sort and view', async () => {
    window.localStorage.setItem(FILES_SORT_STORAGE_KEY, JSON.stringify({ key: 'size', direction: 'asc' }));
    window.localStorage.setItem(FILES_VIEW_MODE_STORAGE_KEY, 'list');
    fakeFolderRouter.navigate(`${PATH}?sort=name&dir=desc&view=grid`, 'replace');

    const { getByTestId } = render(<FileBrowser />);

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    expect(requests()[0]).toMatchObject({ sort: 'name', direction: 'desc' });
    expect(getByTestId('shown').textContent).toContain('|grid|name|desc');
  });

  it('writes a new sort and view to the address AND to the stored preference', async () => {
    const { getByTestId } = render(<FileBrowser />);
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());

    act(() => fireEvent.click(getByTestId('sort-by-name')));
    // Name sorts A to Z, which is not the direction the page opened with.
    await waitFor(() => expect(address()).toEqual({ sort: 'name', dir: 'asc' }));
    expect(JSON.parse(window.localStorage.getItem(FILES_SORT_STORAGE_KEY)!)).toEqual({ key: 'name', direction: 'asc' });

    act(() => fireEvent.click(getByTestId('set-list')));
    await waitFor(() => expect(address()).toEqual({ sort: 'name', dir: 'asc', view: 'list' }));
    expect(window.localStorage.getItem(FILES_VIEW_MODE_STORAGE_KEY)).toBe('list');
  });
});

describe('FileBrowser - workspace switch', () => {
  it('drops the folder and the page in ONE navigation, and keeps the filters', async () => {
    fakeFolderRouter.navigate(`${PATH}?folder=abc&page=3&q=report`, 'replace');
    render(<FileBrowser />);
    await waitFor(() => expect(lastRequest()).toMatchObject({ parentFolderId: 'abc', page: 2 }));
    const before = fakeFolderRouter.navigations.length;

    act(() => orgReset.fire());

    // A second write for the page would be built from the query of the last render and put
    // the folder of the workspace just left straight back into the address.
    await waitFor(() => expect(lastRequest()).toMatchObject({ parentFolderId: 'root', page: 0, search: 'report' }));
    expect(address()).toEqual({ q: 'report' });
    expect(fakeFolderRouter.navigations.length).toBe(before + 1);
  });
});
