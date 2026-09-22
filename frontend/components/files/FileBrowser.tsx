'use client';

import * as React from 'react';
import { useRouter, usePathname, useSearchParams } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Folder, FolderOpen, FolderPlus, FolderInput, History, Upload, Download, Trash2, Pencil, ArrowLeft, ChevronRight } from 'lucide-react';

// Generating from the FILES page happens in place: the reader is already looking at the list the
// asset will land in, and sending them to the studio for it would swap that list for a thread and
// leave them to navigate back for the one thing they came to see.
import { GenerateEntryButton } from '@/components/chat/GenerateEntryButton';
// What this workspace has generated, and the recipe behind each asset. The SAME list the generation
// dialog shows, so a past generation is one thing wherever it is looked at.
import { GenerationHistoryList } from '@/components/generation/GenerationHistoryList';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import type { GenerationHistoryEntry, GenerationProvenance } from '@/lib/api/storage-api';
import {
  DndContext,
  DragOverlay,
  pointerWithin,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useDragSensors } from '@/lib/dnd/useDragSensors';
import { Button } from '@/components/ui/button';
import { useStorageExplorer } from '@/app/workflows/builder/components/inspector/useStorageExplorer';
import { storageApi, S3_FILES_FILTER, type StorageExplorerEntry } from '@/lib/api/storage-api';
import { useAuthToken } from '@/hooks/useAuthToken';
import { getActiveOrgHeaderForRequest, useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { fileService } from '@/lib/api/orchestrator/file.service';
import { is413StorageError } from '@/lib/api/error-utils';
import { showInsufficientStorageModal } from '@/components/billing/InsufficientStorageModal';
import { FileDetailView } from '@/components/app/FileDetailView';
import { FilesExplorerBody } from './FilesExplorerBody';
import { FilesMoveToFolderDialog } from './FilesMoveToFolderDialog';
import { DragPreviewCard } from './DragPreviewCard';
import type { MoveFolderRow } from '@/lib/files/moveFolderTree';
import { FileFilterBar } from './FileFilterBar';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import {
  emitFilesDetailState,
  onFilesDetailCommand,
  onFilesFolderNavigate,
  type FilesFolderCrumb,
  FILES_DETAIL_BACK,
  FILES_DETAIL_PREV,
  FILES_DETAIL_NEXT,
  FILES_DETAIL_DOWNLOAD,
} from '@/lib/files/filesHeaderBus';
import {
  type FileTypeCategory,
  getFileTypeIcon,
  STORAGE_SOURCE_STYLES,
  STORAGE_SOURCE_LABELS,
} from '@/lib/files/fileTypes';
import {
  isVirtualEntry,
  folderNavKey,
  entryKey,
  folderLabel,
} from '@/lib/files/virtualFolders';
import { usePersistentState } from '@/hooks/usePersistentState';
import {
  FILES_FOLDER_PARAM,
  FILES_SORT_STORAGE_KEY,
  FILES_VIEW_MODE_STORAGE_KEY,
  DEFAULT_SORT_PREFERENCE,
  folderQueryString,
  isSameFolder,
  naturalDirectionFor,
  normalizeSortPreference,
  normalizeViewMode,
  type FilesSortPreference,
  type FilesViewMode,
} from '@/lib/files/filesViewPreferences';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import LoadingSpinner from '@/components/LoadingSpinner';
import { track } from '@/lib/analytics/analytics';

/** Default page size - must be one of the offered options (50 | 100). */
const PAGE_SIZE = 50;

/**
 * The generation dialog, kept out of this page's first load.
 *
 * <p>It is a large component with a catalogue, a form per model and a file picker, and most visits
 * to Files never open it, so its chunk is fetched on the click that needs it rather than by every
 * reader who came to look at a list.
 */
const CreateGenerationModal = React.lazy(() =>
  import('@/components/chat/CreateGenerationModal').then((m) => ({ default: m.CreateGenerationModal })));

/**
 * Full-page file browser. Lists every real file in the active workspace
 * ({@code filesOnly}) with rich filtering (search, file type, source, date
 * range), grid + list views, multi-select bulk download/delete, drag-and-drop
 * upload, an inline detail/preview view, and pagination. Built entirely on the
 * existing {@code /storage/explorer} API + {@link FileDetailView}; the side-panel
 * Storage Explorer shares the same helpers and endpoints.
 */
export function FileBrowser() {
  const t = useTranslations('files');
  const tExp = useTranslations('storageExplorer');
  const tCommon = useTranslations('common');
  const token = useAuthToken();
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: hide every
  // write affordance (upload, new folder, rename, move, delete). Browsing,
  // filtering and downloads stay available. Same gating as the tables page.
  const canMutate = useCanMutateInCurrentOrg();

  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // ---- What the browser remembers, and where ----
  // The OPEN FOLDER lives in the URL: it describes what is on screen, so a refresh, a
  // browser Back, or a pasted link must land on the same folder instead of snapping back
  // to the root. HOW the user looks at files (grid vs list, the sort criterion) lives in
  // localStorage instead: it should follow them across folders and sessions rather than
  // ride along in a link they share.
  const urlFolder = searchParams.get(FILES_FOLDER_PARAM);
  const [storedViewMode, setStoredViewMode] = usePersistentState<FilesViewMode>(FILES_VIEW_MODE_STORAGE_KEY, 'grid');
  const [storedSort, setStoredSort] = usePersistentState<FilesSortPreference>(
    FILES_SORT_STORAGE_KEY,
    DEFAULT_SORT_PREFERENCE,
  );
  // Re-validated on read: a preference written by an older build (or hand-edited) must
  // degrade to the default, never render an unsortable listing or a blank view.
  const viewMode = normalizeViewMode(storedViewMode);
  const savedSort = React.useMemo(() => normalizeSortPreference(storedSort), [storedSort]);
  // Seeds for the data hook's own state, read ONCE: after mount the hook is driven by
  // setSort / navigateToFolder, so these refs never need to stay in sync.
  const seedRef = React.useRef({ folder: urlFolder, sort: savedSort });

  const {
    entries,
    totalElements,
    currentPage,
    pageSize,
    loading,
    error,
    search,
    sourceTypeFilter,
    setSearch,
    setSourceTypeFilter,
    dateFrom,
    dateTo,
    fileType,
    sort,
    direction,
    setSort,
    setDateFrom,
    setDateTo,
    setFileType,
    setPage,
    setPageSize,
    refresh,
    navigateToFolder,
    parentFolderId,
    // s3Only: the Files page shows ONLY real object-storage files. This hides
    // DB-resident pseudo-files - agent observability TEXT blobs (tool_call_result.txt,
    // agent_message.txt), BINARY chat attachments and avatars - which carry a
    // file_name and would otherwise surface here.
    // folderAware (V313): switch to the folder-scoped listing (root → top-level
    // folders + loose files; a folder UUID → that folder's children).
    // virtualWorkflowFolders (Phase 2b): also surface the computed workflow folder
    // tree (workflow → epoch → spawn → iteration) at root and navigate into it.
  } = useStorageExplorer(undefined, undefined, undefined, {
    pageSize: PAGE_SIZE,
    ...S3_FILES_FILTER,
    folderAware: true,
    virtualWorkflowFolders: true,
    initialFolderId: seedRef.current.folder,
    initialSort: seedRef.current.sort.key,
    initialDirection: seedRef.current.sort.direction,
  });

  // V313: the breadcrumb trail the user has navigated into (root → … → current).
  // Drives the header back-up-one-folder + the breadcrumb; the current folder is its
  // last entry (empty = root). The URL owns WHICH folder is open; this trail is the
  // path that led there, kept in sync with the URL by the effect below.
  const [folderTrail, setFolderTrail] = React.useState<FilesFolderCrumb[]>([]);

  /** Open a folder by pushing it into the URL - so Back returns to the folder above. */
  const pushFolder = React.useCallback((folderId: string | null) => {
    const query = folderQueryString(searchParams, folderId);
    router.push(query ? `${pathname}?${query}` : pathname);
  }, [router, pathname, searchParams]);

  // True while the cards on screen do not belong to the folder the URL names: either a
  // navigation has landed in the URL but the new listing has not arrived yet
  // (parentFolderId still trails urlFolder), or that listing is in flight. Acting on those
  // cards would file a crumb under the wrong parent.
  const listingStale = !isSameFolder(parentFolderId ?? null, urlFolder) || loading;

  // Enter a folder. The nav key is the virtualId for a computed workflow folder, else
  // the real id.
  //
  // THREE guards, all for the same production symptom: a slow listing keeps the PREVIOUS
  // folder's cards on screen, the user clicks again, and each extra click used to push
  // another crumb - the breadcrumb read "Run 12 / Run 12 / Run 12" and it took as many
  // Backs to get out.
  //  1. Already there → nothing to do (the click that repeats the current folder).
  //  2. The listing on screen belongs to another folder → ignore; a click on a SIBLING
  //     card in that window would otherwise append a crumb that is not a child of the
  //     current folder and desync the trail from the URL.
  //  3. Belt and braces on the trail itself: never push a crumb already on the path, so
  //     even an unforeseen route into this function cannot stack a duplicate.
  const enterFolder = React.useCallback((entry: StorageExplorerEntry) => {
    const navKey = folderNavKey(entry);
    if (!navKey) return; // malformed row (no id and no virtualId) - no-op.
    if (isSameFolder(navKey, urlFolder)) return;
    if (listingStale) return;
    setFolderTrail((prev) => (
      prev.some((c) => c.id === navKey) ? prev : [...prev, { id: navKey, name: folderLabel(entry, t) }]
    ));
    pushFolder(navKey);
  }, [pushFolder, urlFolder, listingStale, t]);

  // Navigate to a specific folder id in the trail (or null = root) - the breadcrumb
  // crumbs and the header back button. Re-selecting the folder already open is a no-op
  // (same guard as above): the crumb of the folder you are IN must not push history.
  const goToFolder = React.useCallback((folderId: string | null) => {
    if (isSameFolder(folderId, urlFolder)) return;
    pushFolder(folderId);
  }, [pushFolder, urlFolder]);

  // ---- URL → state ----
  // The single place the open folder is applied. It runs for every way the URL can
  // change: an in-app navigation, browser Back/Forward, a refresh, a pasted link.
  //
  // The trail is reconciled rather than rebuilt: it is already correct when we pushed the
  // crumb ourselves, truncatable when the user walked back up, and only fetched from the
  // server in the one case where the path is genuinely unknown - arriving cold on a deep
  // link. `trailRequestRef` drops a response that lost the race with a newer navigation.
  const trailRequestRef = React.useRef<string | null>(null);
  // Mirrors folderTrail for the effect below to read WITHOUT depending on it (depending on
  // it would re-run the effect on every trail change and re-enter the reconciliation).
  // Declared before that effect so it is already refreshed when the effect runs.
  const folderTrailRef = React.useRef<FilesFolderCrumb[]>(folderTrail);
  React.useEffect(() => {
    folderTrailRef.current = folderTrail;
  }, [folderTrail]);
  // next-intl's translator is NOT referentially stable across renders, so it must not be a
  // dependency of the effect below: the effect would re-run on every render, re-issuing the
  // very trail request whose response caused that render - an endless fetch loop. Read it
  // through a ref instead, which is always the current translator anyway.
  const tRef = React.useRef(t);
  tRef.current = t;

  React.useEffect(() => {
    navigateToFolder(urlFolder);

    if (!urlFolder) {
      // Keep the SAME array when it is already empty. Handing React a fresh [] would
      // re-render, and any re-render that changes one of this effect's dependencies
      // re-enters it - a self-feeding loop. Idempotent state writes are what stop that.
      setFolderTrail((prev) => (prev.length === 0 ? prev : []));
      trailRequestRef.current = null;
      return;
    }

    const trail = folderTrailRef.current;
    const idx = trail.findIndex((c) => c.id === urlFolder);
    if (idx >= 0) {
      // Already on the path: either the crumb we just pushed ourselves (last), or an
      // ancestor the user walked back up to (truncate). No request needed either way.
      if (idx !== trail.length - 1) setFolderTrail(trail.slice(0, idx + 1));
      trailRequestRef.current = null;
      return;
    }

    // The one case the path is genuinely unknown: arriving cold on this folder (refresh,
    // pasted link, Forward past a truncation). Ask the server to rebuild it.
    trailRequestRef.current = urlFolder;
    storageApi.getFolderTrail(urlFolder, S3_FILES_FILTER)
      .then((crumbs) => {
        if (trailRequestRef.current !== urlFolder) return; // a newer navigation won
        setFolderTrail(crumbs.map((c) => ({ id: folderNavKey(c) ?? c.id, name: folderLabel(c, tRef.current) })));
      })
      .catch((err) => {
        // A trail we cannot rebuild costs the breadcrumb, not the listing: the files
        // themselves are already loading from the URL's folder id.
        console.error('Failed to load folder trail:', err);
      });
  }, [urlFolder, navigateToFolder]);

  // ---- View + sort preferences ----
  const changeViewMode = React.useCallback((mode: FilesViewMode) => {
    setStoredViewMode(normalizeViewMode(mode));
  }, [setStoredViewMode]);

  // Picking a criterion adopts its natural direction (A→Z for text, newest/biggest for
  // date and size); the toggle then flips it. Both are remembered for the next visit.
  const changeSortKey = React.useCallback((key: FilesSortPreference['key']) => {
    const next = { key, direction: naturalDirectionFor(key) };
    setSort(next.key, next.direction);
    setStoredSort(next);
  }, [setSort, setStoredSort]);

  const toggleSortDirection = React.useCallback(() => {
    const next: FilesSortPreference = { key: sort, direction: direction === 'asc' ? 'desc' : 'asc' };
    setSort(next.key, next.direction);
    setStoredSort(next);
  }, [sort, direction, setSort, setStoredSort]);

  // Search - debounced into the hook's server-side filter.
  const [searchInput, setSearchInput] = React.useState('');
  const debouncedSearch = useDebouncedValue(searchInput, 300);
  React.useEffect(() => {
    setSearch(debouncedSearch);
  }, [debouncedSearch, setSearch]);

  // Every filter - search, source, date AND file-type - is server-side: changing
  // any of them re-queries the full DB set and re-paginates from page 0 (the hook
  // resets currentPage). Nothing is narrowed over the already-loaded page.
  // Date inputs are yyyy-mm-dd; converted to ISO instants.
  const [dateFromInput, setDateFromInput] = React.useState('');
  const [dateToInput, setDateToInput] = React.useState('');
  // Boundaries are UTC to match the UTC dates shown on every row (formatUtcDate);
  // the backend filters createdAt against these ISO instants.
  const handleDateFrom = React.useCallback((v: string) => {
    setDateFromInput(v);
    setDateFrom(v ? `${v}T00:00:00.000Z` : '');
  }, [setDateFrom]);
  const handleDateTo = React.useCallback((v: string) => {
    setDateToInput(v);
    setDateTo(v ? `${v}T23:59:59.999Z` : '');
  }, [setDateTo]);

  const filtersActive = !!search || !!sourceTypeFilter || !!dateFrom || !!dateTo || fileType !== '_all';

  // ---- Selection (Map survives pagination → bulk actions span pages) ----
  // Keyed by entryKey(): real rows by id, VIRTUAL workflow folders (Phase 2b) by
  // their virtualId - so a workflow folder gets the SAME checkbox + bulk-delete
  // flow as any file. Virtual folders still can't be downloaded / moved / renamed
  // (no real row); those actions disable while one is selected.
  const [selected, setSelected] = React.useState<Map<string, StorageExplorerEntry>>(new Map());
  const selectedIds = React.useMemo(() => new Set(selected.keys()), [selected]);
  const selectableEntries = entries;
  const entryByKey = React.useMemo(() => {
    const m = new Map<string, StorageExplorerEntry>();
    for (const e of selectableEntries) m.set(entryKey(e), e);
    return m;
  }, [selectableEntries]);

  const toggleSelection = React.useCallback((key: string) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(key)) next.delete(key);
      else {
        const e = entryByKey.get(key);
        if (e) next.set(key, e);
      }
      return next;
    });
  }, [entryByKey]);

  const visibleSelectedCount = selectableEntries.reduce((n, e) => n + (selected.has(entryKey(e)) ? 1 : 0), 0);
  const allVisibleSelected = selectableEntries.length > 0 && visibleSelectedCount === selectableEntries.length;
  const toggleSelectAll = React.useCallback(() => {
    setSelected((prev) => {
      const next = new Map(prev);
      const all = selectableEntries.length > 0 && selectableEntries.every((e) => next.has(entryKey(e)));
      if (all) for (const e of selectableEntries) next.delete(entryKey(e));
      else for (const e of selectableEntries) next.set(entryKey(e), e);
      return next;
    });
  }, [selectableEntries]);
  const clearSelection = React.useCallback(() => setSelected(new Map()), []);

  // Split of the selection: real rows (files + manual folders, by id) vs virtual
  // workflow folders (by virtualId ref). Download/move/rename apply to real rows
  // only; delete handles both.
  const selectedVirtual = React.useMemo(
    () => Array.from(selected.values()).filter(isVirtualEntry),
    [selected],
  );
  const selectedRealIds = React.useMemo(
    () => Array.from(selected.values()).filter((e) => !isVirtualEntry(e)).map((e) => e.id),
    [selected],
  );
  const hasVirtualSelected = selectedVirtual.length > 0;

  // ---- Focused single-file viewer state ----
  // Declared up here (before the workspace-reset hook) so a workspace switch can
  // close the viewer - its entry belongs to the old org and would 403.
  const [detailEntry, setDetailEntry] = React.useState<StorageExplorerEntry | null>(null);
  const [detailDownloading, setDetailDownloading] = React.useState(false);

  // Reset selection when any filter changes OR when entering/leaving a folder
  // (selections only make sense within a single listing).
  React.useEffect(() => {
    setSelected(new Map());
  }, [search, sourceTypeFilter, dateFrom, dateTo, fileType, parentFolderId]);

  const { toasts, addToast, removeToast } = useToast();

  // Workspace switch → close the viewer, drop selection + page, return to the
  // folder root (its folders belong to the old org), refetch in the new scope.
  useOrgScopedReset(() => {
    setDetailEntry(null);
    setSelected(new Map());
    setFolderTrail([]);
    // Drop the folder from the URL too, or a refresh would re-open a folder that
    // belongs to the workspace we just left. Through the same helper as every other
    // navigation, so any OTHER query param survives. `replace`, not `push`: leaving a
    // workspace is not a navigation step the user should be able to Back into.
    const query = folderQueryString(searchParams, null);
    router.replace(query ? `${pathname}?${query}` : pathname);
    navigateToFolder(null);
    setPage(0);
    refresh();
  });

  // ---- Bulk download (single ZIP, same endpoint as the side-panel) ----
  const [downloading, setDownloading] = React.useState(false);
  const handleBulkDownload = React.useCallback(async () => {
    if (selectedRealIds.length === 0) return;
    setDownloading(true);
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json', ...getActiveOrgHeaderForRequest() };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch('/api/proxy/storage/explorer/download-zip', {
        method: 'POST',
        headers,
        body: JSON.stringify({ ids: selectedRealIds }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `files-${new Date().toISOString().slice(0, 10)}.zip`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      track('file_downloaded', { file_count: selectedRealIds.length, method: 'zip' });
    } catch (err) {
      console.error('Bulk download failed:', err);
      addToast({ type: 'error', title: t('downloadFailedTitle'), message: t('downloadFailedMessage') });
    } finally {
      setDownloading(false);
    }
  }, [selectedRealIds, token, addToast, t]);

  const handleDownloadOne = React.useCallback(async (entry: StorageExplorerEntry) => {
    try {
      await fileService.downloadAndSave(
        { id: entry.id, path: entry.s3Key ?? undefined, name: entry.fileName ?? undefined },
        entry.fileName ?? undefined,
      );
      track('file_downloaded', { file_id: entry.id, mime_type: entry.mimeType ?? null, method: 'single' });
    } catch (err) {
      console.error('Download failed:', err);
      addToast({ type: 'error', title: t('downloadFailedTitle'), message: t('downloadFailedMessage') });
    }
  }, [addToast, t]);

  // ---- Bulk delete ----
  const [showDeleteModal, setShowDeleteModal] = React.useState(false);
  const [deleting, setDeleting] = React.useState(false);
  const handleConfirmDelete = React.useCallback(async () => {
    if (selected.size === 0) return;
    setDeleting(true);
    try {
      // One unified delete for the whole selection: real rows (files + manual
      // folders) go through the bulk endpoint; each selected VIRTUAL workflow
      // folder deletes every file it groups via its per-ref endpoint. Report the
      // server's actual combined deletedCount (a cross-org / already-gone id is
      // skipped server-side, so this can be < the number selected).
      let totalDeleted = 0;
      if (selectedRealIds.length > 0) {
        const { deletedCount } = await storageApi.deleteEntries(selectedRealIds);
        totalDeleted += deletedCount;
      }
      for (const folder of selectedVirtual) {
        if (!folder.virtualId) continue;
        const { deletedCount } = await storageApi.deleteVirtualFolder(folder.virtualId);
        totalDeleted += deletedCount;
      }
      clearSelection();
      setShowDeleteModal(false);
      refresh();
      addToast({ type: 'success', title: t('deletedTitle'), message: t('deletedMessage', { count: totalDeleted }) });
    } catch (err) {
      console.error('Delete failed:', err);
      addToast({ type: 'error', title: t('deleteFailedTitle'), message: t('deleteFailedMessage') });
    } finally {
      setDeleting(false);
    }
  }, [selected, selectedRealIds, selectedVirtual, clearSelection, refresh, addToast, t]);

  // ---- Folders (V313): drag-to-move, create, rename ----
  // The same idea of what a drag is that the resource lists use - see useDragSensors.
  const sensors = useDragSensors();

  // The card currently under the pointer during a dnd-kit drag - drives the
  // floating DragOverlay thumbnail. Set on drag start, cleared on end/cancel.
  const [activeDragEntry, setActiveDragEntry] = React.useState<StorageExplorerEntry | null>(null);
  const handleDragStart = React.useCallback((event: DragStartEvent) => {
    // Only real rows are draggable, and a real row's entryKey IS its id.
    setActiveDragEntry(entryByKey.get(String(event.active.id)) ?? null);
  }, [entryByKey]);

  // Move the dragged card(s) into a folder. When the dragged card is part of the
  // current multi-selection, the WHOLE selection moves; otherwise just that card.
  // A folder can't be dropped onto itself.
  const handleDragEnd = React.useCallback(async (event: DragEndEvent) => {
    const overId = event.over?.id ? String(event.over.id) : null;
    const activeId = event.active?.id ? String(event.active.id) : null;
    try {
      if (!overId || !activeId || overId === activeId) return;

      // A multi-selection drag moves the REAL selected rows only (virtual workflow
      // folders have no real row to move). Never move the target folder into
      // itself (defensive - the backend also blocks cycles).
      const ids = selected.has(activeId) ? selectedRealIds : [activeId];
      const moveIds = ids.filter((id) => id !== overId);
      if (moveIds.length === 0) return;

      try {
        const { movedCount, failed } = await storageApi.moveEntries(moveIds, overId);
        clearSelection();
        refresh();
        if (failed.length > 0) {
          addToast({ type: 'error', title: t('moveFailedTitle'), message: t('moveFailedMessage', { count: failed.length }) });
        } else if (movedCount > 0) {
          addToast({ type: 'success', title: t('movedTitle'), message: t('movedMessage', { count: movedCount }) });
        }
      } catch (err) {
        console.error('Move failed:', err);
        addToast({ type: 'error', title: t('moveFailedTitle'), message: t('moveFailedMessage', { count: moveIds.length }) });
      }
    } finally {
      // Always drop the overlay thumbnail once the drag resolves (success or no-op).
      setActiveDragEntry(null);
    }
  }, [selected, selectedRealIds, clearSelection, refresh, addToast, t]);

  // ---- "Move to…" folder tree picker (complements drag-and-drop) ----
  // Opened from the selection toolbar; loads the full manual-folder tree on open.
  const [showMoveModal, setShowMoveModal] = React.useState(false);
  const [allFolders, setAllFolders] = React.useState<MoveFolderRow[]>([]);
  const [loadingFolders, setLoadingFolders] = React.useState(false);
  const openMoveModal = React.useCallback(async () => {
    setShowMoveModal(true);
    setLoadingFolders(true);
    try {
      setAllFolders(await storageApi.getAllFolders());
    } catch (err) {
      console.error('Load folders failed:', err);
      setAllFolders([]);
    } finally {
      setLoadingFolders(false);
    }
  }, []);

  // The selected FOLDER ids - the picker (and its subtree walk) must never offer
  // these as a destination. Files contribute nothing here (a file has no subtree).
  const selectedFolderIds = React.useMemo(() => {
    const ids = new Set<string>();
    for (const e of selected.values()) if (e.isFolder && !isVirtualEntry(e)) ids.add(e.id);
    return ids;
  }, [selected]);

  // Commit the chosen destination (a folder id, or null for the top level).
  const handleMoveTo = React.useCallback(async (target: string | null) => {
    const moveIds = selectedRealIds;
    if (moveIds.length === 0) return;
    try {
      const { movedCount, failed } = await storageApi.moveEntries(moveIds, target);
      clearSelection();
      refresh();
      setShowMoveModal(false);
      if (failed.length > 0) {
        addToast({ type: 'error', title: t('moveFailedTitle'), message: t('moveFailedMessage', { count: failed.length }) });
      } else if (movedCount > 0) {
        addToast({ type: 'success', title: t('movedTitle'), message: t('movedMessage', { count: movedCount }) });
      }
    } catch (err) {
      console.error('Move failed:', err);
      addToast({ type: 'error', title: t('moveFailedTitle'), message: t('moveFailedMessage', { count: moveIds.length }) });
    }
  }, [selectedRealIds, clearSelection, refresh, addToast, t]);

  // Create a folder in the current location (root or the open MANUAL folder).
  // A manual folder can only live at root or under another manual folder - never
  // inside a computed VIRTUAL workflow grouping (its key starts with 'wf:'), where
  // there's no real parent to attach to. Inside a virtual folder we hide the New
  // folder button and never send a virtual key as the parent.
  const [creatingFolder, setCreatingFolder] = React.useState(false);
  const [newFolderName, setNewFolderName] = React.useState('');
  const [savingFolder, setSavingFolder] = React.useState(false);
  const insideVirtual = !!parentFolderId && parentFolderId.startsWith('wf:');
  const currentManualFolderId = (parentFolderId && !insideVirtual) ? parentFolderId : null;
  const handleCreateFolder = React.useCallback(async () => {
    const name = newFolderName.trim();
    if (!name) { setCreatingFolder(false); setNewFolderName(''); return; }
    setSavingFolder(true);
    try {
      await storageApi.createFolder(name, currentManualFolderId);
      setCreatingFolder(false);
      setNewFolderName('');
      refresh();
    } catch (err) {
      console.error('Create folder failed:', err);
      addToast({ type: 'error', title: t('createFolderFailedTitle'), message: t('createFolderFailedMessage') });
    } finally {
      setSavingFolder(false);
    }
  }, [newFolderName, currentManualFolderId, refresh, addToast, t]);

  // Rename: enabled only when exactly one FOLDER is selected (reuses renameEntry -
  // backend renames file_name, which is the folder's name).
  const singleSelected = selected.size === 1 ? Array.from(selected.values())[0] : null;
  // Rename applies to MANUAL folders only (a virtual workflow folder has no row to rename).
  const canRenameFolder = !!singleSelected?.isFolder && !isVirtualEntry(singleSelected);
  const [renamingFolder, setRenamingFolder] = React.useState(false);
  const [renameValue, setRenameValue] = React.useState('');
  const [savingRename, setSavingRename] = React.useState(false);
  const startRenameFolder = React.useCallback(() => {
    if (!singleSelected?.isFolder || isVirtualEntry(singleSelected)) return;
    setRenameValue(singleSelected.fileName ?? '');
    setRenamingFolder(true);
  }, [singleSelected]);
  const handleRenameFolder = React.useCallback(async () => {
    if (!singleSelected) { setRenamingFolder(false); return; }
    const name = renameValue.trim();
    if (!name || name === singleSelected.fileName) { setRenamingFolder(false); return; }
    setSavingRename(true);
    try {
      await storageApi.renameEntry(singleSelected.id, name);
      setRenamingFolder(false);
      clearSelection();
      refresh();
    } catch (err) {
      console.error('Rename folder failed:', err);
      addToast({ type: 'error', title: t('renameFolderFailedTitle'), message: t('renameFolderFailedMessage') });
    } finally {
      setSavingRename(false);
    }
  }, [singleSelected, renameValue, clearSelection, refresh, addToast, t]);

  // ---- Upload (button + drag-and-drop) ----
  const fileInputRef = React.useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = React.useState(false);
  const [generationOpen, setGenerationOpen] = React.useState(false);
  /**
   * The recipe the dialog should open with, when it was opened to RE-run something.
   *
   * <p>Held here rather than passed at the call site because the dialog is mounted once for both
   * ways in: "make something new" (no recipe) and "make this again, with one thing changed" (the
   * recipe of the asset being looked at).
   */
  const [regenerateRecipe, setRegenerateRecipe] = React.useState<GenerationProvenance | null>(null);
  /** True while the generated assets are shown INSTEAD of the file grid. */
  const [showGenerations, setShowGenerations] = React.useState(false);

  // Through the SAME cache the Generate button and the dialog read, so the three cost one request
  // between them. Only used here to decide whether a history is worth offering at all: an install
  // that does not serve generation has nothing to look back at.
  const { availability: generationAvailability } = useGenerationModels(canMutate);
  const canBrowseGenerations = canMutate && generationAvailability !== 'absent';

  /**
   * Open the generation dialog on an asset's own recipe.
   *
   * <p>This is the whole point of recording one: the reader is looking at something they made,
   * changes a word, and runs it again - instead of retyping from memory and hoping.
   */
  const regenerate = React.useCallback((provenance: GenerationProvenance) => {
    // Handed straight to the dialog, with no storage hop and no navigation: the recipe never leaves
    // the page it was read on, so nothing can be truncated, stale or picked up by a later mount.
    setRegenerateRecipe(provenance);
    setGenerationOpen(true);
  }, []);

  /**
   * Open a generated asset in the file viewer.
   *
   * <p>A history entry is a file, so it is shown by the ONE viewer every other file goes through.
   * The prev/next arrows walk the file GRID, which this entry is usually not part of - the viewer
   * resolves them by looking the open file up in the loaded page, so they simply go quiet here, and
   * light up only for a generated asset that happens to be on that page too.
   */
  const openGeneratedAsset = React.useCallback((entry: GenerationHistoryEntry) => {
    setDetailEntry({
      id: entry.id,
      storageType: 'S3_FILE',
      sourceType: 'S3_FILE',
      fileName: entry.fileName,
      mimeType: entry.mimeType,
      sizeBytes: entry.sizeBytes,
      formattedSize: entry.formattedSize,
      createdAt: entry.createdAt,
      workflowId: null,
      workflowName: null,
      projectId: null,
      runId: null,
      stepKey: null,
      epoch: null,
      s3Key: entry.s3Key,
      contentType: entry.mimeType,
    });
  }, []);
  const [isDragging, setIsDragging] = React.useState(false);
  const handleFiles = React.useCallback(async (files: FileList | File[]) => {
    // VIEWER (org workspace) cannot upload - also blocks OS drag-and-drop, which
    // bypasses the (hidden) upload button.
    if (!canMutate) return;
    const arr = Array.from(files);
    if (arr.length === 0) return;
    setUploading(true);
    let ok = 0;
    let failedQuota = 0;
    let failedOther = 0;
    for (const f of arr) {
      try {
        // V313: land the upload in the current manual folder (null = root; never a
        // virtual workflow folder, which can't hold uploads).
        await fileService.uploadGeneric(f, 'files', currentManualFolderId);
        ok++;
      } catch (err) {
        console.error('Upload failed:', err);
        // A quota refusal is counted apart so it gets the storage modal instead of the
        // generic toast, which says only "upload failed" and leaves the user with no idea
        // that they are full or what to do about it. Not breaking out of the loop: the
        // backend refuses per file against the remaining room, so a smaller file can still
        // fit after a large one was rejected.
        //
        // Detection runs on the MESSAGE, and it must stay that way. uploadGeneric throws a
        // plain Error carrying the response body and no status, which is what makes this
        // correct: the upload endpoint answers 413 for TWO unrelated reasons, quota
        // ("Storage quota exceeded") and a single oversized file ("File too large. Maximum
        // size: N MB"). is413StorageError short-circuits on a `status` property when one is
        // present, so attaching the status here would look like a robustness improvement
        // and would in fact pop "you are out of space, here are the plans" at someone who
        // has room to spare and simply picked too big a file. Only the quota body matches.
        if (is413StorageError(err)) failedQuota++;
        else failedOther++;
      }
    }
    setUploading(false);
    track('file_uploaded', {
      file_count: arr.length,
      success_count: ok,
      failed_count: failedQuota + failedOther,
      quota_failed_count: failedQuota,
      in_folder: Boolean(currentManualFolderId),
      total_bytes: arr.reduce((sum, f) => sum + f.size, 0),
    });
    if (ok > 0) {
      refresh();
      addToast({ type: 'success', title: t('uploadedTitle'), message: t('uploadedMessage', { count: ok }) });
    }
    // Only the failures the modal does NOT explain get the generic toast, so a purely
    // quota-driven failure shows one clear message rather than a modal plus a vague toast.
    if (failedOther > 0) {
      addToast({ type: 'error', title: t('uploadFailedTitle'), message: t('uploadFailedMessage', { count: failedOther }) });
    }
    if (failedQuota > 0) {
      showInsufficientStorageModal();
    }
  }, [canMutate, refresh, addToast, t, currentManualFolderId]);

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files?.length) handleFiles(e.dataTransfer.files);
  };
  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    if (!canMutate) return; // read-only: no drop-to-upload overlay
    if (!isDragging) setIsDragging(true);
  };
  const onDragLeave = (e: React.DragEvent) => {
    // Only clear when the pointer actually leaves the root (not a child).
    if (e.currentTarget === e.target) setIsDragging(false);
  };

  // ---- Inline detail (replaces the grid; prev/next walk the visible page) ----
  // The detail's chrome (back / prev-next / info / download) lives in the app
  // header, not in this view, so the media renders full-bleed. We drive the
  // header over the shared bus: broadcast the current detail state, listen for
  // the header's commands. (State is declared above, near the selection state.)
  const detailIdx = detailEntry ? entries.findIndex((e) => e.id === detailEntry.id) : -1;
  const prevEntry = detailIdx > 0 ? entries[detailIdx - 1] : null;
  const nextEntry = detailIdx >= 0 && detailIdx < entries.length - 1 ? entries[detailIdx + 1] : null;
  const goPrev = prevEntry ? () => setDetailEntry(prevEntry) : undefined;
  const goNext = nextEntry ? () => setDetailEntry(nextEntry) : undefined;

  // Download the currently open file (header download button), tracking a spinner.
  const handleDownloadDetail = React.useCallback(async () => {
    if (!detailEntry) return;
    setDetailDownloading(true);
    try {
      await fileService.downloadAndSave(
        { id: detailEntry.id, path: detailEntry.s3Key ?? undefined, name: detailEntry.fileName ?? undefined },
        detailEntry.fileName ?? undefined,
      );
      track('file_downloaded', { file_id: detailEntry.id, mime_type: detailEntry.mimeType ?? null, method: 'detail' });
    } catch (err) {
      console.error('Download failed:', err);
      addToast({ type: 'error', title: t('downloadFailedTitle'), message: t('downloadFailedMessage') });
    } finally {
      setDetailDownloading(false);
    }
  }, [detailEntry, addToast, t]);

  // Broadcast the focused-viewer state to the app header (breadcrumb tail +
  // back/prev/next/download buttons).
  React.useEffect(() => {
    // Mirror FileDetailView's display-name fallbacks (fileName, then the s3 key
    // basename) so the breadcrumb tail and the viewer don't label the same file
    // differently. A nameless, keyless file just gets no tail (the viewer shows
    // its localized "File" placeholder) - never a raw MIME type as a name.
    const detailName = detailEntry
      ? (detailEntry.fileName ?? detailEntry.s3Key?.split('/').pop() ?? undefined)
      : undefined;
    emitFilesDetailState({
      open: !!detailEntry,
      fileId: detailEntry?.id,
      fileName: detailName,
      canPrev: !!prevEntry,
      canNext: !!nextEntry,
      downloading: detailDownloading,
      // V313: ship the manual-folder trail so the breadcrumb shows Files / A / B
      // and the header back-button can step up one folder.
      folderTrail,
    });
  }, [detailEntry, prevEntry, nextEntry, detailDownloading, folderTrail]);

  // Tell the header the viewer is gone when this page unmounts (navigation away).
  React.useEffect(() => () => emitFilesDetailState({ open: false }), []);

  // A rename committed from the header's edit modal (AppHeader dispatches
  // `metadataEditSaved` with resourceType 'file'). Reflect the new name in the
  // open viewer/breadcrumb immediately, then refresh the list so the card updates.
  React.useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent).detail as { resourceType?: string; id?: string; name?: string };
      if (detail?.resourceType !== 'file' || !detail.id) return;
      setDetailEntry((prev) => (prev && prev.id === detail.id ? { ...prev, fileName: detail.name ?? prev.fileName } : prev));
      refresh();
    };
    window.addEventListener('metadataEditSaved', handler as EventListener);
    return () => window.removeEventListener('metadataEditSaved', handler as EventListener);
  }, [refresh]);

  // V313: the breadcrumb folder crumbs + the header back-button request folder
  // navigation over the bus. Close the viewer first (navigating folders while a
  // file is open should land back in the list), then move to the target folder.
  React.useEffect(() => {
    return onFilesFolderNavigate((folderId) => {
      setDetailEntry(null);
      goToFolder(folderId);
    });
  }, [goToFolder]);

  // React to the header's commands. Re-subscribed when prev/next/download change
  // so the handlers never close over a stale sibling.
  React.useEffect(() => {
    const unsubs = [
      onFilesDetailCommand(FILES_DETAIL_BACK, () => setDetailEntry(null)),
      onFilesDetailCommand(FILES_DETAIL_PREV, () => { if (prevEntry) setDetailEntry(prevEntry); }),
      onFilesDetailCommand(FILES_DETAIL_NEXT, () => { if (nextEntry) setDetailEntry(nextEntry); }),
      onFilesDetailCommand(FILES_DETAIL_DOWNLOAD, () => { void handleDownloadDetail(); }),
    ];
    return () => unsubs.forEach((u) => u());
  }, [prevEntry, nextEntry, handleDownloadDetail]);

  return (
    <>
    <div
      className="relative flex flex-col min-h-[calc(100vh-8rem)]"
      onDragOver={onDragOver}
      onDragLeave={onDragLeave}
      onDrop={onDrop}
    >
      {/* Drag-to-upload overlay - sticky so it stays centred in the viewport while
          the page scrolls (the browser is now page-scrolled, not an inner box). */}
      {isDragging && (
        <div className="absolute inset-0 z-30 pointer-events-none">
          <div className="sticky top-0 h-[70vh] flex items-center justify-center bg-[var(--bg-primary)]/80 backdrop-blur-sm border-2 border-dashed border-[var(--accent-primary)] rounded-xl">
            <div className="flex flex-col items-center gap-2 text-[var(--accent-primary)]">
              <Upload className="h-10 w-10" />
              <span className="text-sm font-medium">{t('dropHint')}</span>
            </div>
          </div>
        </div>
      )}

      {detailEntry ? (
        // Single-file viewer: chromeless + full-bleed. Back / prev-next / download
        // live in the app header (this view broadcasts its state over the bus), so
        // the media itself shows large with no bordered container. The media is
        // sized to fit the viewport so the size / type / created / path metadata
        // stays visible right under it without scrolling.
        <FileDetailView
          chromeless
          entryId={detailEntry.id}
          s3Key={detailEntry.s3Key ?? undefined}
          fileName={detailEntry.fileName ?? undefined}
          mimeType={detailEntry.mimeType ?? undefined}
          sizeBytes={detailEntry.sizeBytes ?? undefined}
          createdAt={detailEntry.createdAt}
          onBack={() => setDetailEntry(null)}
          onPrev={goPrev}
          onNext={goNext}
          // Only where a generation can actually be started. Passing it unconditionally would have
          // the viewer ask for a recipe on every file opened by a reader who could not act on it,
          // and offer a button that leads to a dialog they may not open.
          onRegenerate={canBrowseGenerations ? regenerate : undefined}
        />
      ) : (
        <>
          {/* Header - in-page folder breadcrumb (Files / A / B) + back, so the user
              sees the current folder right here, not only in the app-header. (V313) */}
          <div className="flex-shrink-0 flex items-center justify-between gap-3 mb-3">
            <div className="flex items-center gap-2 min-w-0">
              {folderTrail.length > 0 && (
                <button
                  type="button"
                  onClick={() => goToFolder(folderTrail.length >= 2 ? folderTrail[folderTrail.length - 2].id : null)}
                  aria-label={t('backToParent')}
                  title={t('backToParent')}
                  className="p-1.5 rounded-lg hover:bg-theme-secondary text-theme-secondary flex-shrink-0"
                >
                  <ArrowLeft className="h-4 w-4" />
                </button>
              )}
              <div className="w-10 h-10 bg-theme-secondary rounded-xl flex items-center justify-center flex-shrink-0">
                <Folder className="w-5 h-5 text-theme-primary" />
              </div>
              <div className="min-w-0">
                <h1 className="flex items-center gap-1 text-lg font-semibold text-theme-primary min-w-0">
                  <button
                    type="button"
                    onClick={() => goToFolder(null)}
                    disabled={folderTrail.length === 0}
                    className={`truncate ${folderTrail.length > 0 ? 'text-theme-secondary hover:underline' : ''}`}
                  >
                    {t('title')}
                  </button>
                  {folderTrail.map((crumb, i) => {
                    const isLast = i === folderTrail.length - 1;
                    return (
                      <span key={crumb.id} className="flex items-center gap-1 min-w-0">
                        <ChevronRight className="h-4 w-4 flex-shrink-0 text-theme-muted" />
                        <button
                          type="button"
                          onClick={() => goToFolder(crumb.id)}
                          disabled={isLast}
                          className={`truncate ${isLast ? '' : 'text-theme-secondary hover:underline'}`}
                        >
                          {crumb.name}
                        </button>
                      </span>
                    );
                  })}
                </h1>
                {loading ? (
                  <div className="h-4 w-16 bg-theme-tertiary rounded animate-pulse mt-1" />
                ) : (
                  <p className="truncate text-sm text-theme-secondary">{t('count', { count: totalElements })}</p>
                )}
              </div>
            </div>
            {/* The three ways of getting a file in here. Their labels are
                `whitespace-nowrap`, so side by side they are wider than a phone:
                below `sm` each one keeps its icon and drops its label, and the
                word survives as the accessible name and the tooltip rather than
                being lost. `flex-shrink-0` keeps the group at its natural width
                so it is the TITLE that gives ground (it truncates), never the
                actions overflowing the page. */}
            <div className="flex flex-shrink-0 items-center gap-1.5 sm:gap-2">
              {/* Generating a file belongs where files are, beside the other way
                  of getting one in here - but only where a generation can
                  actually be started. */}
              <GenerateEntryButton
                variant="toolbar"
                label={t('generate')}
                onOpen={() => setGenerationOpen(true)}
              />
              {/* What has already been generated, beside the control that generates. A generated
                  asset is a file like any other once it lands here, so without this the model and
                  the words that produced it were only ever visible by opening the file one by one.
                  Offered only where a generation could be started: an install that does not serve
                  generation has nothing to look back at. */}
              {canBrowseGenerations && (
                <Button
                  variant={showGenerations ? 'default' : 'outline'}
                  className="px-2.5 sm:px-4"
                  aria-pressed={showGenerations}
                  title={t('generatedAssets')}
                  onClick={() => {
                    const next = !showGenerations;
                    setShowGenerations(next);
                    // A selection made in the grid means nothing over a list the bulk actions
                    // cannot address, and the floating bar would still offer to delete it.
                    if (next) clearSelection();
                  }}
                >
                  <History className="h-4 w-4 sm:mr-1.5" />
                  <span className="hidden sm:inline">{t('generatedAssets')}</span>
                </Button>
              )}
              {/* New folder - inline name input (V313). Opens an input that creates
                  the folder in the current location on Enter / blur. Hidden inside a
                  computed VIRTUAL workflow folder (no real parent to attach to) and
                  for read-only VIEWERs. */}
              {canMutate && !insideVirtual && (
                creatingFolder ? (
                  <input
                    autoFocus
                    type="text"
                    value={newFolderName}
                    placeholder={t('folderNamePlaceholder')}
                    onChange={(e) => setNewFolderName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void handleCreateFolder();
                      if (e.key === 'Escape') { setCreatingFolder(false); setNewFolderName(''); }
                    }}
                    onBlur={() => void handleCreateFolder()}
                    // Sized in rem rather than left to the browser: an unsized
                    // text input is ~20 characters wide, which on a phone is
                    // wider than what the two remaining buttons leave it.
                    className="w-32 sm:w-48 text-sm h-9 px-2.5 rounded-lg border border-theme bg-theme-secondary text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
                  />
                ) : (
                  <Button
                    variant="outline"
                    size="default"
                    className="px-2.5 sm:px-4"
                    title={t('newFolder')}
                    aria-label={t('newFolder')}
                    onClick={() => setCreatingFolder(true)}
                  >
                    <FolderPlus className="h-4 w-4 sm:mr-1.5" />
                    <span className="hidden sm:inline">{t('newFolder')}</span>
                  </Button>
                )
              )}
              {canMutate && (
                <Button
                  variant="default"
                  size="default"
                  className="px-2.5 sm:px-4"
                  title={t('upload')}
                  aria-label={t('upload')}
                  onClick={() => fileInputRef.current?.click()}
                  disabled={uploading}
                >
                  {uploading ? <LoadingSpinner size="xs" className="sm:mr-1.5" /> : <Upload className="h-4 w-4 sm:mr-1.5" />}
                  <span className="hidden sm:inline">{t('upload')}</span>
                </Button>
              )}
            </div>
          </div>

          {/* The generated assets, in place of the file grid. Same list the generation dialog
              shows; opening one lands in the ordinary file viewer, reusing one opens the form
              filled in with what produced it. */}
          {showGenerations && (
            <GenerationHistoryList
              className="flex-shrink-0"
              onOpen={openGeneratedAsset}
              onReuse={(entry) => regenerate(entry.provenance)}
            />
          )}

          {!showGenerations && (
          <>
          {/* Filters + view toggle */}
          <div className="flex-shrink-0 mb-3">
            <FileFilterBar
              searchInput={searchInput}
              onSearchChange={setSearchInput}
              fileType={fileType as FileTypeCategory}
              onFileTypeChange={setFileType}
              sourceType={sourceTypeFilter}
              onSourceTypeChange={setSourceTypeFilter}
              dateFrom={dateFromInput}
              dateTo={dateToInput}
              onDateFromChange={handleDateFrom}
              onDateToChange={handleDateTo}
              viewMode={viewMode}
              onViewModeChange={changeViewMode}
              sortKey={sort}
              sortDirection={direction}
              onSortKeyChange={changeSortKey}
              onSortDirectionToggle={toggleSortDirection}
              onRefresh={refresh}
              loading={loading}
            />
          </div>

          {/* Select-all row - always rendered while there are entries, so starting a
              selection never shifts the layout. The selection ACTIONS live in the
              floating SelectionActionBar pill at the bottom (same as every table). */}
          {entries.length > 0 && (
            <div className="flex-shrink-0 flex items-center gap-2 mb-2">
              <label className="flex items-center gap-2 text-xs text-theme-secondary cursor-pointer">
                <input
                  type="checkbox"
                  checked={allVisibleSelected}
                  ref={(el) => {
                    if (el) el.indeterminate = visibleSelectedCount > 0 && visibleSelectedCount < selectableEntries.length;
                  }}
                  onChange={toggleSelectAll}
                  className="rounded border-slate-300 dark:border-slate-600"
                />
                {selected.size > 0 ? tExp('selectedCount', { count: selected.size }) : tExp('selectAll')}
              </label>
            </div>
          )}

          {/* Content - flows with the page (the AuthenticatedView container owns
              the scroll), so there is no inner scrollbar boxed to the grid. Grows
              (flex-1) so the pagination bar stays docked at the bottom of the page
              even when the list is empty or shorter than the viewport. */}
          {/* While the cards on screen still belong to the folder being left, clicks on
              them are ignored (they would file a crumb under the wrong parent). Dim and
              mute them so that is visible: a card that looks live but does nothing reads
              as a broken app, and is exactly what made users click a folder three times. */}
          <div className={`flex-1 ${listingStale && entries.length > 0 ? 'opacity-60 pointer-events-none' : ''}`}>
            {error && <div className="p-3 text-sm text-red-500">{error}</div>}

            {loading && entries.length === 0 && (
              <div className="flex items-center justify-center py-16">
                <LoadingSpinner size="sm" />
              </div>
            )}

            {!loading && entries.length === 0 && !error && (
              <div className="flex flex-col items-center justify-center py-16 text-center text-theme-secondary">
                <FolderOpen className="h-12 w-12 mb-3 text-theme-muted" />
                <p className="text-sm">
                  {/* Keyed off the URL, not the trail: on a cold deep link into an empty
                      folder the trail is still being fetched, and keying off it showed the
                      workspace-level "no files at all" copy for a folder that simply has none. */}
                  {filtersActive
                    ? t('noMatches')
                    : urlFolder
                      ? t('emptyFolder')
                      : t('empty')}
                </p>
                {!filtersActive && !urlFolder && (
                  <>
                    <p className="text-xs mt-1 text-theme-muted">{t('emptyHint')}</p>
                    {canMutate && (
                      <Button variant="default" size="sm" className="mt-4" onClick={() => fileInputRef.current?.click()}>
                        <Upload className="h-4 w-4 mr-1.5" />
                        {t('upload')}
                      </Button>
                    )}
                  </>
                )}
              </div>
            )}

            {/* Grid: folders first (iOS-style tiles, drop targets), then files.
                Wrapped in a DndContext so files/folders can be dragged onto a
                FolderCard to move them. The native OS-file drag-to-UPLOAD is a
                separate, dataTransfer-based path on the root container - dnd-kit
                only handles the internal pointer drag, so the two don't collide. */}
            {entries.length > 0 && viewMode === 'grid' && (
              <DndContext
                sensors={sensors}
                // The pointer decides which card a drop lands on, not the floating preview,
                // which is anchored where the card was picked up plus the travel.
                collisionDetection={pointerWithin}
                onDragStart={handleDragStart}
                onDragEnd={handleDragEnd}
                onDragCancel={() => setActiveDragEntry(null)}
              >
                {/* The shared FilesExplorerBody (grid density): folders first (sorted by
                    last activity), then files grouped into collapsible per-day sections -
                    the SAME body the side-panel explorer + project Files tab render. The
                    tiles stay dnd-kit draggable/droppable inside this DndContext, so the
                    drag-to-move behaviour is unchanged.
                    groupByDay only while sorting by date: the per-day sections ARE a date
                    ordering, so keeping them under a name/size/type sort would chop that
                    order into date buckets and show neither. */}
                <FilesExplorerBody
                  variant="grid"
                  entries={entries}
                  enableFolders
                  groupByDay={sort === 'date'}
                  tFiles={t}
                  onOpenFolder={enterFolder}
                  onOpenFile={setDetailEntry}
                  onDownloadFile={handleDownloadOne}
                  downloadLabel={tExp('download')}
                  selectable
                  selectedIds={selectedIds}
                  onToggleSelect={toggleSelection}
                  gridDraggable={canMutate}
                />
                {/* Floating thumbnail of the dragged card (instead of nothing). For a
                    multi-selection drag, badge the count being moved. */}
                <DragOverlay>
                  {activeDragEntry && (
                    <DragPreviewCard
                      entry={activeDragEntry}
                      label={folderLabel(activeDragEntry, t)}
                      multi={selected.has(activeDragEntry.id) ? selected.size : 1}
                    />
                  )}
                </DragOverlay>
              </DndContext>
            )}

            {entries.length > 0 && viewMode === 'list' && (
              <div className="border border-theme rounded-xl overflow-hidden">
                <table className="w-full text-sm">
                  <thead className="bg-theme-secondary sticky top-0 z-10">
                    <tr>
                      <th className="w-10 px-3 py-2.5" />
                      <th className="px-3 py-2.5 text-left font-medium text-theme-secondary min-w-[200px]">{tCommon('name')}</th>
                      <th className="px-3 py-2.5 text-left font-medium text-theme-secondary hidden md:table-cell">{t('typeColumn')}</th>
                      <th className="px-3 py-2.5 text-left font-medium text-theme-secondary w-24">{t('sizeColumn')}</th>
                      <th className="px-3 py-2.5 text-left font-medium text-theme-secondary hidden sm:table-cell w-28">{t('sourceColumn')}</th>
                      <th className="px-3 py-2.5 text-left font-medium text-theme-secondary hidden lg:table-cell w-40">{tCommon('created')}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-theme">
                    {entries.map((entry) => {
                      // Folders show their label (manual = name, virtual = grouping);
                      // files keep the filename/content-type fallback.
                      const name = entry.isFolder
                        ? folderLabel(entry, t)
                        : (entry.fileName || entry.contentType || 'Unnamed');
                      return (
                        <tr
                          key={entryKey(entry)}
                          className="group cursor-pointer transition-colors hover:bg-[var(--bg-tertiary)]/50"
                          onClick={() => (entry.isFolder ? enterFolder(entry) : setDetailEntry(entry))}
                        >
                          <td className="px-3 py-2 text-center" onClick={(e) => e.stopPropagation()}>
                            {/* Every row is selectable - virtual workflow folders are keyed
                                by their virtualId and join the same bulk selection. */}
                            <input
                              type="checkbox"
                              checked={selectedIds.has(entryKey(entry))}
                              onChange={() => toggleSelection(entryKey(entry))}
                              className="rounded border-slate-300 dark:border-slate-600"
                            />
                          </td>
                          <td className="px-3 py-2 min-w-[200px] max-w-[420px]">
                            <div className="flex items-center gap-2 min-w-0">
                              {entry.isFolder
                                ? <Folder className="h-4 w-4 text-[var(--accent-primary)] flex-shrink-0" />
                                : getFileTypeIcon(entry, 'h-4 w-4')}
                              <span className="text-theme-primary truncate" title={name}>{name}</span>
                            </div>
                          </td>
                          <td className="px-3 py-2 text-theme-secondary hidden md:table-cell truncate max-w-[160px]" title={entry.mimeType ?? ''}>
                            {entry.isFolder ? '-' : (entry.mimeType ?? '-')}
                          </td>
                          <td className="px-3 py-2 text-theme-secondary whitespace-nowrap">
                            {entry.isFolder ? t('itemCount', { count: entry.childCount ?? 0 }) : entry.formattedSize}
                          </td>
                          <td className="px-3 py-2 hidden sm:table-cell">
                            {entry.sourceType && (
                              <span className={`text-[10px] leading-tight px-1.5 py-0.5 rounded-md font-medium ${STORAGE_SOURCE_STYLES[entry.sourceType] ?? 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400'}`}>
                                {STORAGE_SOURCE_LABELS[entry.sourceType] ?? entry.sourceType}
                              </span>
                            )}
                          </td>
                          <td className="px-3 py-2 text-theme-secondary hidden lg:table-cell whitespace-nowrap">
                            {entry.createdAt ? formatUtcDate(entry.createdAt) : '-'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          {/* Pagination - docked at the bottom of the page scrollport (sticky) so
              the controls stay visible while the list scrolls. totalCount is the
              server total for the ACTIVE filter set (file-type included
              server-side), so the "1-N of M" range is exact and the file-type
              filter spans the whole DB, not just the loaded page. */}
          <PaginationBar
            page={currentPage}
            pageSize={pageSize}
            totalCount={totalElements}
            visibleCount={entries.length}
            loading={loading}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            pageSizeOptions={[50, 100]}
            sticky
          />
          </>
          )}
        </>
      )}

      <FilesMoveToFolderDialog
        isOpen={showMoveModal}
        allFolders={allFolders}
        excludeFolderIds={selectedFolderIds}
        loading={loadingFolders}
        itemCount={selected.size}
        onClose={() => setShowMoveModal(false)}
        onMove={handleMoveTo}
      />

      <BulkDeleteModal
        isOpen={showDeleteModal}
        title={tExp('deleteConfirmTitle')}
        message={tExp('deleteConfirmMessage', { count: selected.size })}
        cancelLabel={tExp('cancel')}
        confirmLabel={tExp('deleteSelected')}
        onCancel={() => setShowDeleteModal(false)}
        onConfirm={handleConfirmDelete}
        isConfirming={deleting}
      />

      <input
        ref={fileInputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => {
          if (e.target.files) handleFiles(e.target.files);
          e.target.value = '';
        }}
      />

      {/* Mounted only while open: the dialog reads its translations at the top of its body, before
          it can decide it is closed. */}
      {generationOpen && (
        <React.Suspense fallback={null}>
          <CreateGenerationModal
            isOpen
            onClose={() => {
              setGenerationOpen(false);
              // Cleared on the way out, or the NEXT "Generate" would open on the last asset's
              // recipe instead of an empty form.
              setRegenerateRecipe(null);
            }}
            // Set only when the dialog was opened to run something again: the form then opens
            // filled in with what produced the asset being looked at.
            initialRecipe={regenerateRecipe}
            // The asset lands in this workspace, so the list it landed in refreshes rather than
            // making the reader wonder where it went.
            onGenerated={() => refresh()}
          />
        </React.Suspense>
      )}

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>

    {/* Floating bulk-selection pill - same bar as every table page. Rendered OUTSIDE
        the `relative` root above: that root spans the whole page-scrolled list
        (min-h + grows with content), so an absolute bar inside it lands at the bottom
        of the CONTENT - visible only after scrolling to the very end, at a position
        that varies with the list height. Out here its nearest positioned ancestor is
        the app <main> (viewport-sized, overflow-hidden), pinning the pill to the
        bottom of the visible area exactly like the table pages. */}
    {selected.size > 0 && (
      /* Non-positioned wrapper: keeps the pill's containing block on <main> while
         re-attaching the OS drag-to-upload handlers the pill lost by moving out of
         the root - a drop released on the pill must upload, not navigate away. */
      <div onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop}>
      <SelectionActionBar count={selected.size} onClear={clearSelection}>
        {canMutate && canRenameFolder && (
          renamingFolder ? (
            <input
              autoFocus
              type="text"
              value={renameValue}
              placeholder={t('renameFolder')}
              onChange={(e) => setRenameValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void handleRenameFolder();
                if (e.key === 'Escape') setRenamingFolder(false);
              }}
              onBlur={() => void handleRenameFolder()}
              disabled={savingRename}
              className="h-7 w-44 px-2 rounded-md bg-white/10 dark:bg-black/10 text-sm text-white dark:text-black placeholder:text-white/50 dark:placeholder:text-black/50 focus:outline-none focus:ring-1 focus:ring-white/40 dark:focus:ring-black/40"
            />
          ) : (
            <BulkBarButton onClick={startRenameFolder} disabled={deleting || downloading}>
              <Pencil className="h-3.5 w-3.5" />
              {t('renameFolder')}
            </BulkBarButton>
          )
        )}
        <BulkBarButton
          onClick={handleBulkDownload}
          disabled={downloading || deleting || hasVirtualSelected || selectedRealIds.length === 0}
          title={hasVirtualSelected ? t('virtualBulkExcluded') : undefined}
        >
          {downloading ? <LoadingSpinner size="xs" /> : <Download className="h-3.5 w-3.5" />}
          {tExp('downloadSelected')}
        </BulkBarButton>
        {canMutate && (
          <BulkBarButton
            onClick={() => void openMoveModal()}
            disabled={deleting || downloading || hasVirtualSelected || selectedRealIds.length === 0}
            title={hasVirtualSelected ? t('virtualBulkExcluded') : undefined}
          >
            <FolderInput className="h-3.5 w-3.5" />
            {t('moveTo')}
          </BulkBarButton>
        )}
        {canMutate && (
          <BulkBarButton variant="danger" onClick={() => setShowDeleteModal(true)} disabled={deleting || downloading}>
            <Trash2 className="h-3.5 w-3.5" />
            {tExp('deleteSelected')}
          </BulkBarButton>
        )}
      </SelectionActionBar>
      </div>
    )}
    </>
  );
}
