// @vitest-environment jsdom
/**
 * A table's own page keeps its view in the address, so a reload (or a dropped connection
 * followed by one) reopens the table where it was instead of on page 1 with nothing filtered.
 *
 * What these pin, each of which was a way for the restore to be silently undone:
 *   - the initial load fetches the page and sort the address carries, not page 1 unsorted;
 *   - the page the RESPONSE reports is what the address records afterwards;
 *   - search, sort and column filters come back, and the filter panel opens on restored filters;
 *   - an embedded table (no `urlState`) neither reads nor writes the address.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { Dispatch, SetStateAction } from 'react';
import type { PaginationState } from '../types';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});

const fetchDataMock = vi.hoisted(() => vi.fn());
let setPaginationFromFetch: Dispatch<SetStateAction<PaginationState>> | null = null;

vi.mock('@/components/data-table/hooks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/data-table/hooks')>();
  // One object for the whole file: the controller keys effects on these functions, and a fresh
  // set per render would loop.
  const fetching = {
    rows: [], columns: [], tableLoading: false, loadingColumns: false, error: null,
    backendColumns: null, nodeType: null,
    setRows: vi.fn(), setColumns: vi.fn(), setError: vi.fn(),
    fetchColumns: vi.fn(),
    fetchData: fetchDataMock,
  };
  return {
    ...actual,
    // The only hook that talks to the network.
    useDataFetching: (params: { setPagination: Dispatch<SetStateAction<PaginationState>> }) => {
      setPaginationFromFetch = params.setPagination;
      return fetching;
    },
  };
});

// Stable identities: the hooks under test key effects and callbacks on these, and a fresh
// function per render would loop.
vi.mock('next-intl', () => {
  const t = (key: string) => key;
  return { useTranslations: () => t, useLocale: () => 'en' };
});

vi.mock('@/components/Toast', () => {
  const toast = { toasts: [], addToast: vi.fn(), removeToast: vi.fn() };
  return { useToast: () => toast };
});

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ getAccessTokenSilently: async () => '', isLoading: false }),
}));

import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';
import { useDataTableController } from '../useDataTableController';

const PAGE = '/en/app/tables/12';

function openAt(query: string): void {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
  fakeFolderRouter.navigations.length = 0;
}

describe('useDataTableController - view kept in the address', () => {
  beforeEach(() => {
    setPaginationFromFetch = null;
    fetchDataMock.mockReset();
    fetchDataMock.mockImplementation(async (page: number) => {
      act(() => setPaginationFromFetch?.((prev) => ({ ...prev, currentPage: page, totalPages: 9 })));
    });
  });

  it('loads the page, page size and sort the address carries', async () => {
    openAt('page=3&size=50&sort=data.name:asc');
    renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));

    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    const [page, pageSize, sort] = fetchDataMock.mock.calls[0];
    expect(page).toBe(3);
    expect(pageSize).toBe(50);
    expect(sort).toEqual({ key: 'data.name', direction: 'asc' });
    // Restoring is not a change: the address is left exactly as it was.
    expect(fakeFolderRouter.search()).toBe('page=3&size=50&sort=data.name:asc');
  });

  it('restores the search and the column filters, with the filter panel open', () => {
    openAt(`q=acme&filters=${encodeURIComponent(JSON.stringify({ 'data.status': 'paid' }))}`);
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));

    expect(result.current.searchQuery).toBe('acme');
    expect(result.current.columnFilters).toEqual({ 'data.status': 'paid' });
    expect(result.current.showColumnFilters).toBe(true);
  });

  it('records the page and page size in the address when they change', async () => {
    openAt('');
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    expect(fakeFolderRouter.search()).toBe('');

    await act(async () => { result.current.handlePageChange(4); });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('page=4'));

    await act(async () => { result.current.handlePageSizeChange(50); });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('size=50'));
  });

  it('records a sort, and drops it again when the header cycles back to the default order', async () => {
    openAt('page=4');
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());

    await act(async () => { result.current.handleSort('data.name'); });
    // A new order starts from the first page.
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('sort=data.name%3Aasc'));

    await act(async () => { result.current.handleSort('data.name'); });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('sort=data.name%3Adesc'));

    await act(async () => { result.current.handleSort('data.name'); });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe(''));
  });

  it('ignores a sort, page or filter set it cannot read', async () => {
    openAt('page=0&size=9999&sort=name&filters=%5B1%5D');
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());

    const [page, pageSize, sort] = fetchDataMock.mock.calls[0];
    expect(page).toBe(1);
    expect(pageSize).toBe(20);
    expect(sort).toBeNull();
    expect(result.current.columnFilters).toEqual({});
  });

  it('steps back to the last page when the address asks for one past the end', async () => {
    openAt('page=40');
    // Answers after a tick, like a request: the step back is asked for from inside an effect.
    fetchDataMock.mockImplementation(async (page: number) => {
      await Promise.resolve();
      act(() => setPaginationFromFetch?.((prev) => ({ ...prev, currentPage: page, totalPages: 3 })));
    });
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12, urlState: true }));

    await waitFor(() => expect(fetchDataMock.mock.calls.map(([page]) => page)).toEqual([40, 3]));
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('page=3'));
    expect(result.current.pagination.currentPage).toBe(3);
  });

  it('an embedded table starts another run on its first page, not on the page it had scrolled to', async () => {
    openAt('');
    const { result, rerender } = renderHook(
      ({ runId }: { runId: string }) =>
        useDataTableController({
          dataSourceId: 0,
          workflowContext: { workflowId: 'w1', runId, stepAlias: 'a1' },
        }),
      { initialProps: { runId: 'r1' } },
    );
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    await act(async () => { result.current.handlePageChange(3); });
    fetchDataMock.mockClear();

    rerender({ runId: 'r2' });

    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    expect(fetchDataMock.mock.calls[0][0]).toBe(1);
  });

  it('neither reads nor writes the address when embedded', async () => {
    openAt('page=3&q=acme&sort=data.name:asc');
    const { result } = renderHook(() => useDataTableController({ dataSourceId: 12 }));
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());

    expect(fetchDataMock.mock.calls[0][0]).toBe(1);
    expect(fetchDataMock.mock.calls[0][2]).toBeNull();
    expect(result.current.searchQuery).toBe('');

    await act(async () => { result.current.handlePageChange(2); });
    act(() => result.current.setSearchQuery('other'));
    expect(fakeFolderRouter.navigations).toHaveLength(0);
  });
});
