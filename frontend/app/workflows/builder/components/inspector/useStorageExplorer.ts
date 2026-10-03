import { useState, useEffect, useCallback, useRef } from 'react';
import {
  storageApi,
  StorageExplorerEntry,
  StorageExplorerPage,
  StorageExplorerParams,
  type ExplorerSortKey,
  type ExplorerSortDirection,
} from '@/lib/api/storage-api';
import { FILE_TYPE_CATEGORIES } from '@/lib/files/fileTypes';
import { useEffectOnChange } from '@/hooks/useEffectOnChange';
import {
  urlEnum,
  urlInt,
  urlPageIndex,
  useUrlState,
  type UrlStateCodec,
} from '@/hooks/useUrlState';

const SORT_KEYS: readonly ExplorerSortKey[] = ['date', 'name', 'size', 'type'];
const SORT_DIRECTIONS: readonly ExplorerSortDirection[] = ['asc', 'desc'];
const FILE_TYPE_VALUES: readonly string[] = ['_all', ...FILE_TYPE_CATEGORIES];

/** A source / storage type as the server names them (`STEP_OUTPUT`): the address is user input. */
const urlTypeToken: UrlStateCodec<string> = {
  parse: (raw) => (/^[A-Z][A-Z0-9_]*$/.test(raw) ? raw : undefined),
  serialize: (value) => value,
};

interface UseStorageExplorerReturn {
  entries: StorageExplorerEntry[];
  totalElements: number;
  totalPages: number;
  currentPage: number;
  pageSize: number;
  loading: boolean;
  error: string | null;
  search: string;
  sourceTypeFilter: string;
  storageTypeFilter: string;
  /** ISO instant lower bound on createdAt ('' = unset). */
  dateFrom: string;
  /** ISO instant upper bound on createdAt ('' = unset). */
  dateTo: string;
  /** File-type category ('' / '_all' = unset). Server-side, narrows the FULL DB set. */
  fileType: string;
  /**
   * V313 folder-aware listing: null = root (top-level folders + loose files), or a
   * folder UUID = that folder's direct children. Undefined when the caller never
   * opts in (legacy flat listing - no parentFolderId sent to the server).
   */
  parentFolderId: string | null | undefined;
  /** Active ordering key - server-side, so it spans the whole result set, not the page. */
  sort: ExplorerSortKey;
  /** Active ordering direction. */
  direction: ExplorerSortDirection;
  setSort: (key: ExplorerSortKey, direction: ExplorerSortDirection) => void;
  setSearch: (value: string) => void;
  setSourceTypeFilter: (value: string) => void;
  setStorageTypeFilter: (value: string) => void;
  setDateFrom: (value: string) => void;
  setDateTo: (value: string) => void;
  setFileType: (value: string) => void;
  /**
   * V313: enter a folder (UUID) or return to root (null), resetting to page 0.
   * Only takes effect when the caller opted into folder mode
   * ({@code options.folderAware}); otherwise the legacy flat listing is kept.
   */
  navigateToFolder: (folderId: string | null) => void;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;
  refresh: () => void;
}

/**
 * Hook for fetching and managing Storage Explorer data.
 *
 * `options.pageSize` overrides the default 20 items per page (callers like the
 * side-panel Files tab want 50). `options.initialPage` seeds `currentPage` so
 * navigating back from {@link FileDetailView} can restore the page the user
 * left. `options.filesOnly` restricts the result to real files
 * ({@code file_name IS NOT NULL}) - the full-page Files browser sets it; the
 * side-panel explorer leaves it unset (legacy all-rows behaviour).
 * `options.s3Only` further restricts to real object-storage files
 * ({@code s3_key IS NOT NULL}) - the full-page Files browser sets it to hide
 * DB-resident pseudo-files (observability TEXT blobs, BINARY chat attachments).
 * `options.folderAware` (V313) opts into the folder-aware listing: the hook then
 * sends a {@code parentFolderId} ("root" or a folder UUID) and exposes
 * {@link UseStorageExplorerReturn.navigateToFolder}. Left unset, the legacy flat
 * listing is kept (side-panel explorer) and {@code parentFolderId} is undefined.
 * `options.virtualWorkflowFolders` (Phase 2b) additionally opts into the computed
 * VIRTUAL workflow folder tree (workflow → epoch → spawn → iteration) - the same
 * {@code parentFolderId} channel carries the virtual {@code "wf:…"} navigation keys.
 * `options.urlState` mirrors the listing's view in the page's query string (`page`, `size`,
 * `source`, `storage`, `type`, `sort`, `dir`) so a reload reopens it as it was. Only the
 * full-page Files browser turns it on: the inspector and the pickers are embedded in someone
 * else's page and must not own its address. The seeds then act as the defaults, spelled by
 * absence. Search and the date range stay with the caller, which holds the form they are typed in.
 */
export function useStorageExplorer(
  workflowId?: string,
  storageTypeDefault?: string,
  sourceTypeDefault?: string,
  options?: {
    pageSize?: number;
    initialPage?: number;
    initialSearch?: string;
    filesOnly?: boolean;
    s3Only?: boolean;
    folderAware?: boolean;
    virtualWorkflowFolders?: boolean;
    /** Folder to open on mount (folder-aware callers restoring a folder from the URL). */
    initialFolderId?: string | null;
    /** Ordering to start with - the Files page seeds it from the URL / the saved preference. */
    initialSort?: ExplorerSortKey;
    initialDirection?: ExplorerSortDirection;
    /** ISO instants to start the date range with (the caller restoring it from the URL). */
    initialDateFrom?: string;
    initialDateTo?: string;
    /** True to keep the view in the page's query string. See the hook's doc. */
    urlState?: boolean;
  },
): UseStorageExplorerReturn {
  const filesOnly = options?.filesOnly ?? false;
  const s3Only = options?.s3Only ?? false;
  const folderAware = options?.folderAware ?? false;
  const virtualWorkflowFolders = options?.virtualWorkflowFolders ?? false;
  const urlState = options?.urlState ?? false;
  const [entries, setEntries] = useState<StorageExplorerEntry[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [currentPage, setCurrentPage] = useUrlState('page', options?.initialPage ?? 0, {
    codec: urlPageIndex,
    enabled: urlState,
  });
  const [pageSize, setPageSize] = useUrlState('size', options?.pageSize ?? 20, {
    codec: urlInt(1, 100),
    enabled: urlState,
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState(options?.initialSearch ?? '');
  const [sourceTypeFilter, setSourceTypeFilter] = useUrlState('source', sourceTypeDefault ?? '', {
    codec: urlTypeToken,
    enabled: urlState,
  });
  const [storageTypeFilter, setStorageTypeFilter] = useUrlState('storage', storageTypeDefault ?? '', {
    codec: urlTypeToken,
    enabled: urlState,
  });
  const [dateFrom, setDateFrom] = useState(options?.initialDateFrom ?? '');
  const [dateTo, setDateTo] = useState(options?.initialDateTo ?? '');
  const [fileType, setFileType] = useUrlState<string>('type', '_all', {
    codec: urlEnum(FILE_TYPE_VALUES),
    enabled: urlState,
  });
  // V313: null = root, UUID = a folder. Undefined when not folder-aware (the
  // legacy flat listing - never send the param). Seeded to root in folder mode.
  const [parentFolderId, setParentFolderId] = useState<string | null>(options?.initialFolderId ?? null);
  const [sort, setSortKey] = useUrlState<ExplorerSortKey>('sort', options?.initialSort ?? 'date', {
    codec: urlEnum(SORT_KEYS),
    enabled: urlState,
  });
  const [direction, setDirection] = useUrlState<ExplorerSortDirection>('dir', options?.initialDirection ?? 'desc', {
    codec: urlEnum(SORT_DIRECTIONS),
    enabled: urlState,
  });
  const abortRef = useRef<AbortController | null>(null);

  const fetchData = useCallback(async () => {
    // Cancel previous request
    if (abortRef.current) {
      abortRef.current.abort();
    }
    abortRef.current = new AbortController();

    setLoading(true);
    setError(null);

    try {
      const params: StorageExplorerParams = {
        page: currentPage,
        size: pageSize,
      };

      if (search) params.search = search;
      if (sourceTypeFilter) params.sourceType = sourceTypeFilter;
      if (storageTypeFilter) params.storageType = storageTypeFilter;
      if (workflowId) params.workflowId = workflowId;
      if (dateFrom) params.dateFrom = dateFrom;
      if (dateTo) params.dateTo = dateTo;
      if (fileType && fileType !== '_all') params.fileType = fileType;
      if (filesOnly) params.filesOnly = true;
      if (s3Only) params.s3Only = true;
      // V313: only send parentFolderId when folder-aware (null → "root"). Omitting
      // it keeps the legacy flat listing for non-folder callers.
      if (folderAware) params.parentFolderId = parentFolderId ?? 'root';
      // Phase 2b: opt into the computed virtual workflow folder tree.
      if (virtualWorkflowFolders) params.virtualWorkflowFolders = true;
      // Ordering is server-side: it re-orders the FULL result set and re-paginates,
      // never just the rows already loaded.
      params.sort = sort;
      params.direction = direction;

      const result: StorageExplorerPage = await storageApi.getExplorerEntries(params);
      setEntries(Array.isArray(result.content) ? result.content : []);
      setTotalElements(result.totalElements ?? 0);
      setTotalPages(result.totalPages ?? 0);
      // A page past the end (a link kept from before files were deleted, the last file of the
      // last page removed) comes back empty: step back to the last page that exists. Decided on
      // the answer, never on the counts held in state, which are 0 until the first one arrives.
      const lastPage = (result.totalPages ?? 0) - 1;
      if (lastPage >= 0 && currentPage > lastPage) setCurrentPage(lastPage);
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') return;
      setError(err instanceof Error ? err.message : 'Failed to load storage data');
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, search, sourceTypeFilter, storageTypeFilter, workflowId, dateFrom, dateTo, fileType, filesOnly, s3Only, folderAware, virtualWorkflowFolders, parentFolderId, sort, direction, setCurrentPage]);

  useEffect(() => {
    fetchData();
    return () => {
      if (abortRef.current) {
        abortRef.current.abort();
      }
    };
  }, [fetchData]);

  // Reset page when filters or page size change - search/sort/type all re-query
  // the full DB set and re-paginate (never narrow the already-loaded page).
  // Entering/leaving a folder (parentFolderId) likewise re-paginates from page 0.
  // Not for the values the hook mounted with: that would wipe the page it was seeded with
  // (`initialPage`, or the one the address asked for).
  useEffectOnChange(() => {
    setCurrentPage(0);
  }, [search, sourceTypeFilter, storageTypeFilter, workflowId, dateFrom, dateTo, fileType, pageSize, parentFolderId, sort, direction]);

  const setPage = useCallback((page: number) => {
    setCurrentPage(page);
  }, [setCurrentPage]);

  // V313: enter a folder (or return to root). No-op effect on the param when the
  // caller isn't folder-aware (it just won't be sent), but we still track state so
  // the same hook instance can be re-used. Page reset is handled by the effect above.
  // Re-entering the folder already open is a NO-OP. Without this, a second click on a
  // folder card (easy while a slow listing is still loading and the old cards are still
  // on screen) would set the same id again - a new state object every time - and refetch.
  const navigateToFolder = useCallback((folderId: string | null) => {
    setParentFolderId((prev) => (prev === folderId ? prev : folderId));
  }, []);

  const setSort = useCallback((key: ExplorerSortKey, dir: ExplorerSortDirection) => {
    setSortKey(key);
    setDirection(dir);
  }, [setSortKey, setDirection]);

  return {
    entries,
    totalElements,
    totalPages,
    currentPage,
    pageSize,
    loading,
    error,
    search,
    sourceTypeFilter,
    storageTypeFilter,
    dateFrom,
    dateTo,
    fileType,
    parentFolderId: folderAware ? parentFolderId : undefined,
    sort,
    direction,
    setSort,
    setSearch,
    setSourceTypeFilter,
    setStorageTypeFilter,
    setDateFrom,
    setDateTo,
    setFileType,
    navigateToFolder,
    setPage,
    setPageSize,
    refresh: fetchData,
  };
}
