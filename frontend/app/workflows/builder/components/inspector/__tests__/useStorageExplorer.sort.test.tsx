// @vitest-environment jsdom
/**
 * Tests for the parts of {@link useStorageExplorer} the Files browser now depends on and that
 * every FileBrowser test mocks away: the seeds it starts from, the sort it sends, and the
 * re-navigation guard.
 *
 * These cover the READ-BACK half of "the sort preference is remembered". The write half is
 * asserted in FileBrowser.navigation.test.tsx; without these, dropping the seed would silently
 * stop restoring the preference and every other test would stay green.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor, cleanup } from '@testing-library/react';

const getExplorerEntries = vi.fn();
vi.mock('@/lib/api/storage-api', () => ({
  storageApi: { getExplorerEntries: (...a: unknown[]) => getExplorerEntries(...a) },
  S3_FILES_FILTER: { filesOnly: true, s3Only: true },
}));

import { useStorageExplorer } from '../useStorageExplorer';

/** The params of the most recent listing request. */
function lastParams(): Record<string, unknown> {
  return getExplorerEntries.mock.calls[getExplorerEntries.mock.calls.length - 1][0];
}

const FOLDER_AWARE = { folderAware: true, virtualWorkflowFolders: true } as const;

beforeEach(() => {
  getExplorerEntries.mockReset();
  getExplorerEntries.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
});
afterEach(() => cleanup());

describe('useStorageExplorer - ordering', () => {
  it('defaults to newest-first, the ordering every surface had before sorting existed', async () => {
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, FOLDER_AWARE));

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    expect(result.current.sort).toBe('date');
    expect(result.current.direction).toBe('desc');
    expect(lastParams()).toMatchObject({ sort: 'date', direction: 'desc' });
  });

  it('starts from the seeded preference, which is how a remembered sort is restored', async () => {
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialSort: 'size', initialDirection: 'asc',
    }));

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    // The FIRST request already carries it - not a later one after a correction round-trip.
    expect(getExplorerEntries.mock.calls[0][0]).toMatchObject({ sort: 'size', direction: 'asc' });
    expect(result.current.sort).toBe('size');
  });

  it('re-queries with the new ordering when the user changes it', async () => {
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, FOLDER_AWARE));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalledTimes(1));

    act(() => result.current.setSort('name', 'asc'));

    await waitFor(() => expect(lastParams()).toMatchObject({ sort: 'name', direction: 'asc' }));
  });

  it('re-paginates from the first page when the ordering changes', async () => {
    // The sort re-orders the FULL result set, so staying on page 3 would show an arbitrary
    // window of a different list.
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, FOLDER_AWARE));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    act(() => result.current.setPage(3));
    await waitFor(() => expect(lastParams()).toMatchObject({ page: 3 }));

    act(() => result.current.setSort('size', 'desc'));

    await waitFor(() => expect(result.current.currentPage).toBe(0));
    await waitFor(() => expect(lastParams()).toMatchObject({ page: 0, sort: 'size' }));
  });
});

describe('useStorageExplorer - folder navigation', () => {
  it('opens the seeded folder on the FIRST request, so a refreshed URL lands in it directly', async () => {
    renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialFolderId: 'wf:wf1/r12',
    }));

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    // Not "root then the folder": one request, already scoped - otherwise a deep link
    // flashes the root listing first.
    expect(getExplorerEntries).toHaveBeenCalledTimes(1);
    expect(getExplorerEntries.mock.calls[0][0]).toMatchObject({ parentFolderId: 'wf:wf1/r12' });
  });

  it('re-navigating to the folder already open does NOT re-query', async () => {
    // The guard behind the repeated-click fix: without it, each extra click set the same id
    // as a new state value and refetched the listing.
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialFolderId: 'wf:wf1/r12',
    }));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalledTimes(1));

    act(() => result.current.navigateToFolder('wf:wf1/r12'));
    act(() => result.current.navigateToFolder('wf:wf1/r12'));

    await new Promise((r) => setTimeout(r, 20));
    expect(getExplorerEntries).toHaveBeenCalledTimes(1);
  });

  it('navigating to a DIFFERENT folder does re-query', async () => {
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialFolderId: 'wf:wf1/r12',
    }));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalledTimes(1));

    act(() => result.current.navigateToFolder('wf:wf1/r13'));

    await waitFor(() => expect(lastParams()).toMatchObject({ parentFolderId: 'wf:wf1/r13' }));
  });

  it('returning to the root sends the root sentinel, not a missing param', async () => {
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialFolderId: 'wf:wf1',
    }));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalledTimes(1));

    act(() => result.current.navigateToFolder(null));

    await waitFor(() => expect(lastParams()).toMatchObject({ parentFolderId: 'root' }));
  });
});

describe('useStorageExplorer - embedded copies do not own the address', () => {
  // The inspector and the pickers run this hook inside someone else's page. Only the full-page
  // Files browser passes `urlState`; without it nothing may be read from or written to the URL.
  it('writes nothing to the address when the view changes', async () => {
    const replaceState = vi.spyOn(window.history, 'replaceState');
    const pushState = vi.spyOn(window.history, 'pushState');
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, FOLDER_AWARE));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalledTimes(1));

    act(() => result.current.setPage(2));
    act(() => result.current.setSort('name', 'asc'));
    act(() => result.current.setFileType('pdf'));
    act(() => result.current.setSourceTypeFilter('STEP_OUTPUT'));
    act(() => result.current.setPageSize(100));

    await waitFor(() => expect(lastParams()).toMatchObject({ sort: 'name', fileType: 'pdf', size: 100 }));
    expect(replaceState).not.toHaveBeenCalled();
    expect(pushState).not.toHaveBeenCalled();
    replaceState.mockRestore();
    pushState.mockRestore();
  });

  it('starts on the seeded page instead of resetting it on mount', async () => {
    // `initialPage` exists so coming back from a file reopens the page the user left. The
    // "filter changed, back to the first page" rule used to fire on mount too and wipe it.
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 100, totalPages: 5 });
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialPage: 3,
    }));

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    await act(async () => {});
    expect(getExplorerEntries.mock.calls.every((c) => c[0].page === 3)).toBe(true);
    expect(result.current.currentPage).toBe(3);
  });

  it('still goes back to the first page when a filter changes after mount', async () => {
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 100, totalPages: 5 });
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialPage: 3,
    }));
    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());

    act(() => result.current.setSearch('report'));

    await waitFor(() => expect(lastParams()).toMatchObject({ page: 0, search: 'report' }));
  });

  it('steps back to the last page when the one asked for no longer exists', async () => {
    getExplorerEntries.mockResolvedValue({ content: [], totalElements: 40, totalPages: 2 });
    const { result } = renderHook(() => useStorageExplorer(undefined, undefined, undefined, {
      ...FOLDER_AWARE, initialPage: 7,
    }));

    await waitFor(() => expect(result.current.currentPage).toBe(1));
    await waitFor(() => expect(lastParams()).toMatchObject({ page: 1 }));
  });
});
