import { useState, useEffect, useCallback, useRef } from 'react';
import { storageApi, StorageExplorerEntry, StorageExplorerPage, StorageExplorerParams } from '@/lib/api/storage-api';

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
 */
export function useStorageExplorer(
  workflowId?: string,
  storageTypeDefault?: string,
  sourceTypeDefault?: string,
  options?: { pageSize?: number; initialPage?: number; initialSearch?: string; filesOnly?: boolean; s3Only?: boolean; folderAware?: boolean; virtualWorkflowFolders?: boolean },
): UseStorageExplorerReturn {
  const filesOnly = options?.filesOnly ?? false;
  const s3Only = options?.s3Only ?? false;
  const folderAware = options?.folderAware ?? false;
  const virtualWorkflowFolders = options?.virtualWorkflowFolders ?? false;
  const [entries, setEntries] = useState<StorageExplorerEntry[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [currentPage, setCurrentPage] = useState(options?.initialPage ?? 0);
  const [pageSize, setPageSize] = useState(options?.pageSize ?? 20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState(options?.initialSearch ?? '');
  const [sourceTypeFilter, setSourceTypeFilter] = useState(sourceTypeDefault ?? '');
  const [storageTypeFilter, setStorageTypeFilter] = useState(storageTypeDefault ?? '');
  const [dateFrom, setDateFrom] = useState('');
  const [dateTo, setDateTo] = useState('');
  const [fileType, setFileType] = useState('_all');
  // V313: null = root, UUID = a folder. Undefined when not folder-aware (the
  // legacy flat listing - never send the param). Seeded to root in folder mode.
  const [parentFolderId, setParentFolderId] = useState<string | null>(null);
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

      const result: StorageExplorerPage = await storageApi.getExplorerEntries(params);
      setEntries(Array.isArray(result.content) ? result.content : []);
      setTotalElements(result.totalElements ?? 0);
      setTotalPages(result.totalPages ?? 0);
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') return;
      setError(err instanceof Error ? err.message : 'Failed to load storage data');
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, search, sourceTypeFilter, storageTypeFilter, workflowId, dateFrom, dateTo, fileType, filesOnly, s3Only, folderAware, virtualWorkflowFolders, parentFolderId]);

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
  useEffect(() => {
    setCurrentPage(0);
  }, [search, sourceTypeFilter, storageTypeFilter, workflowId, dateFrom, dateTo, fileType, pageSize, parentFolderId]);

  const setPage = useCallback((page: number) => {
    setCurrentPage(page);
  }, []);

  // V313: enter a folder (or return to root). No-op effect on the param when the
  // caller isn't folder-aware (it just won't be sent), but we still track state so
  // the same hook instance can be re-used. Page reset is handled by the effect above.
  const navigateToFolder = useCallback((folderId: string | null) => {
    setParentFolderId(folderId);
  }, []);

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
