'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Folder, FolderOpen, FolderPlus, FolderInput, Upload, Download, Trash2, Pencil, ArrowLeft, ChevronRight } from 'lucide-react';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { Button } from '@/components/ui/button';
import { useStorageExplorer } from '@/app/workflows/builder/components/inspector/useStorageExplorer';
import { storageApi, S3_FILES_FILTER, type StorageExplorerEntry } from '@/lib/api/storage-api';
import { useAuthToken } from '@/hooks/useAuthToken';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { fileService } from '@/lib/api/orchestrator/file.service';
import { FileDetailView } from '@/components/app/FileDetailView';
import { FilesExplorerBody } from './FilesExplorerBody';
import { FilesMoveToFolderDialog } from './FilesMoveToFolderDialog';
import { DragPreviewCard } from './DragPreviewCard';
import type { MoveFolderRow } from '@/lib/files/moveFolderTree';
import { FileFilterBar } from './FileFilterBar';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
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
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import LoadingSpinner from '@/components/LoadingSpinner';

/** Default page size - must be one of the offered options (50 | 100). */
const PAGE_SIZE = 50;

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
  } = useStorageExplorer(undefined, undefined, undefined, { pageSize: PAGE_SIZE, ...S3_FILES_FILTER, folderAware: true, virtualWorkflowFolders: true });

  // V313: the manual-folder breadcrumb trail the user has navigated into
  // (root → … → current). Drives the header back-up-one-folder + the breadcrumb;
  // the current folder is its last entry (empty = root). The hook owns the actual
  // parentFolderId query param; this trail is the display/navigation history.
  const [folderTrail, setFolderTrail] = React.useState<FilesFolderCrumb[]>([]);

  // Enter a folder: push it onto the trail and re-query its children (page 0). The
  // nav key is the virtualId for a computed workflow folder, else the real id;
  // the crumb id IS that nav key (FilesFolderCrumb.id is a string), so the
  // breadcrumb/back navigation re-uses it directly.
  const enterFolder = React.useCallback((entry: StorageExplorerEntry) => {
    const navKey = folderNavKey(entry);
    if (!navKey) return; // malformed row (no id and no virtualId) - no-op.
    setFolderTrail((prev) => [...prev, { id: navKey, name: folderLabel(entry, t) }]);
    navigateToFolder(navKey);
  }, [navigateToFolder, t]);

  // Navigate to a specific folder id in the trail (or null = root). Truncates the
  // trail at that folder. Used by the breadcrumb crumbs + the header back button.
  const goToFolder = React.useCallback((folderId: string | null) => {
    setFolderTrail((prev) => {
      if (folderId === null) return [];
      const idx = prev.findIndex((c) => c.id === folderId);
      return idx >= 0 ? prev.slice(0, idx + 1) : prev;
    });
    navigateToFolder(folderId);
  }, [navigateToFolder]);

  const [viewMode, setViewMode] = React.useState<'grid' | 'list'>('grid');

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
  // VIRTUAL workflow folders (Phase 2b) have no real id - they can't be moved,
  // deleted or renamed, so they are NEVER selectable. Every selection structure
  // below operates on the real (non-virtual) rows only.
  const [selected, setSelected] = React.useState<Map<string, StorageExplorerEntry>>(new Map());
  const selectedIds = React.useMemo(() => new Set(selected.keys()), [selected]);
  const selectableEntries = React.useMemo(() => entries.filter((e) => !isVirtualEntry(e)), [entries]);
  const entryById = React.useMemo(() => {
    const m = new Map<string, StorageExplorerEntry>();
    for (const e of selectableEntries) m.set(e.id, e);
    return m;
  }, [selectableEntries]);

  const toggleSelection = React.useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Map(prev);
      if (next.has(id)) next.delete(id);
      else {
        const e = entryById.get(id);
        if (e) next.set(id, e);
      }
      return next;
    });
  }, [entryById]);

  const visibleSelectedCount = selectableEntries.reduce((n, e) => n + (selected.has(e.id) ? 1 : 0), 0);
  const allVisibleSelected = selectableEntries.length > 0 && visibleSelectedCount === selectableEntries.length;
  const toggleSelectAll = React.useCallback(() => {
    setSelected((prev) => {
      const next = new Map(prev);
      const all = selectableEntries.length > 0 && selectableEntries.every((e) => next.has(e.id));
      if (all) for (const e of selectableEntries) next.delete(e.id);
      else for (const e of selectableEntries) next.set(e.id, e);
      return next;
    });
  }, [selectableEntries]);
  const clearSelection = React.useCallback(() => setSelected(new Map()), []);

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
    navigateToFolder(null);
    setPage(0);
    refresh();
  });

  // ---- Bulk download (single ZIP, same endpoint as the side-panel) ----
  const [downloading, setDownloading] = React.useState(false);
  const handleBulkDownload = React.useCallback(async () => {
    if (selected.size === 0) return;
    setDownloading(true);
    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json', ...getActiveOrgHeaderForRequest() };
      if (token) headers['Authorization'] = `Bearer ${token}`;
      const res = await fetch('/api/proxy/storage/explorer/download-zip', {
        method: 'POST',
        headers,
        body: JSON.stringify({ ids: Array.from(selected.keys()) }),
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
    } catch (err) {
      console.error('Bulk download failed:', err);
      addToast({ type: 'error', title: t('downloadFailedTitle'), message: t('downloadFailedMessage') });
    } finally {
      setDownloading(false);
    }
  }, [selected, token, addToast, t]);

  const handleDownloadOne = React.useCallback(async (entry: StorageExplorerEntry) => {
    try {
      await fileService.downloadAndSave(
        { id: entry.id, path: entry.s3Key ?? undefined, name: entry.fileName ?? undefined },
        entry.fileName ?? undefined,
      );
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
      // Report the server's actual deletedCount (a cross-org / already-gone id is
      // skipped server-side, so this can be < the number selected).
      const { deletedCount } = await storageApi.deleteEntries(Array.from(selected.keys()));
      clearSelection();
      setShowDeleteModal(false);
      refresh();
      addToast({ type: 'success', title: t('deletedTitle'), message: t('deletedMessage', { count: deletedCount }) });
    } catch (err) {
      console.error('Delete failed:', err);
      addToast({ type: 'error', title: t('deleteFailedTitle'), message: t('deleteFailedMessage') });
    } finally {
      setDeleting(false);
    }
  }, [selected, clearSelection, refresh, addToast, t]);

  // ---- Folders (V313): drag-to-move, create, rename ----
  // dnd-kit: require a small drag distance before a pointer-drag starts so a plain
  // click still opens/selects a card (doesn't begin a move).
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));

  // The card currently under the pointer during a dnd-kit drag - drives the
  // floating DragOverlay thumbnail. Set on drag start, cleared on end/cancel.
  const [activeDragEntry, setActiveDragEntry] = React.useState<StorageExplorerEntry | null>(null);
  const handleDragStart = React.useCallback((event: DragStartEvent) => {
    setActiveDragEntry(entryById.get(String(event.active.id)) ?? null);
  }, [entryById]);

  // Move the dragged card(s) into a folder. When the dragged card is part of the
  // current multi-selection, the WHOLE selection moves; otherwise just that card.
  // A folder can't be dropped onto itself.
  const handleDragEnd = React.useCallback(async (event: DragEndEvent) => {
    const overId = event.over?.id ? String(event.over.id) : null;
    const activeId = event.active?.id ? String(event.active.id) : null;
    try {
      if (!overId || !activeId || overId === activeId) return;

      const ids = selected.has(activeId) ? Array.from(selected.keys()) : [activeId];
      // Never move the target folder into itself (defensive - the backend also blocks cycles).
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
  }, [selected, clearSelection, refresh, addToast, t]);

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
    for (const e of selected.values()) if (e.isFolder) ids.add(e.id);
    return ids;
  }, [selected]);

  // Commit the chosen destination (a folder id, or null for the top level).
  const handleMoveTo = React.useCallback(async (target: string | null) => {
    const moveIds = Array.from(selected.keys());
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
  }, [selected, clearSelection, refresh, addToast, t]);

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
  const canRenameFolder = !!singleSelected?.isFolder;
  const [renamingFolder, setRenamingFolder] = React.useState(false);
  const [renameValue, setRenameValue] = React.useState('');
  const [savingRename, setSavingRename] = React.useState(false);
  const startRenameFolder = React.useCallback(() => {
    if (!singleSelected?.isFolder) return;
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
  const [isDragging, setIsDragging] = React.useState(false);
  const handleFiles = React.useCallback(async (files: FileList | File[]) => {
    const arr = Array.from(files);
    if (arr.length === 0) return;
    setUploading(true);
    let ok = 0;
    let failed = 0;
    for (const f of arr) {
      try {
        // V313: land the upload in the current manual folder (null = root; never a
        // virtual workflow folder, which can't hold uploads).
        await fileService.uploadGeneric(f, 'files', currentManualFolderId);
        ok++;
      } catch (err) {
        console.error('Upload failed:', err);
        failed++;
      }
    }
    setUploading(false);
    if (ok > 0) {
      refresh();
      addToast({ type: 'success', title: t('uploadedTitle'), message: t('uploadedMessage', { count: ok }) });
    }
    if (failed > 0) {
      addToast({ type: 'error', title: t('uploadFailedTitle'), message: t('uploadFailedMessage', { count: failed }) });
    }
  }, [refresh, addToast, t, currentManualFolderId]);

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files?.length) handleFiles(e.dataTransfer.files);
  };
  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
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
              <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center flex-shrink-0">
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
                  <p className="text-sm text-theme-secondary">{t('count', { count: totalElements })}</p>
                )}
              </div>
            </div>
            <div className="flex items-center gap-2">
              {/* New folder - inline name input (V313). Opens an input that creates
                  the folder in the current location on Enter / blur. Hidden inside a
                  computed VIRTUAL workflow folder (no real parent to attach to). */}
              {!insideVirtual && (
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
                    disabled={savingFolder}
                    className="text-sm h-9 px-2.5 rounded-lg border border-theme bg-theme-secondary text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
                  />
                ) : (
                  <Button variant="outline" size="default" onClick={() => setCreatingFolder(true)}>
                    <FolderPlus className="h-4 w-4 mr-1.5" />
                    {t('newFolder')}
                  </Button>
                )
              )}
              <Button variant="default" size="default" onClick={() => fileInputRef.current?.click()} disabled={uploading}>
                {uploading ? <LoadingSpinner size="xs" className="mr-1.5" /> : <Upload className="h-4 w-4 mr-1.5" />}
                {t('upload')}
              </Button>
            </div>
          </div>

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
              onViewModeChange={setViewMode}
              onRefresh={refresh}
              loading={loading}
            />
          </div>

          {/* Selection toolbar */}
          {entries.length > 0 && (
            <div className="flex-shrink-0 flex items-center justify-between gap-2 mb-2">
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
              {selected.size > 0 && (
                <div className="flex items-center gap-1">
                  {/* Rename - only when exactly one FOLDER is selected (V313). An
                      inline input replaces the button while editing. */}
                  {canRenameFolder && (
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
                        className="text-sm h-9 px-2.5 rounded-lg border border-theme bg-theme-secondary text-theme-primary focus:outline-none focus:ring-2 focus:ring-[var(--accent-primary)]"
                      />
                    ) : (
                      <Button variant="outline" size="default" onClick={startRenameFolder} disabled={deleting || downloading}>
                        <Pencil className="h-3.5 w-3.5 mr-1.5" />
                        {t('renameFolder')}
                      </Button>
                    )
                  )}
                  <Button variant="outline" size="default" onClick={handleBulkDownload} disabled={downloading || deleting}>
                    {downloading ? <LoadingSpinner size="xs" className="mr-1.5" /> : <Download className="h-3.5 w-3.5 mr-1.5" />}
                    {tExp('downloadSelected')}
                  </Button>
                  {/* Move to… - folder tree picker (complements drag-and-drop). */}
                  <Button variant="outline" size="default" onClick={() => void openMoveModal()} disabled={deleting || downloading}>
                    <FolderInput className="h-3.5 w-3.5 mr-1.5" />
                    {t('moveTo')}
                  </Button>
                  <Button variant="destructive" size="default" onClick={() => setShowDeleteModal(true)} disabled={deleting || downloading}>
                    <Trash2 className="h-3.5 w-3.5 mr-1.5" />
                    {tExp('deleteSelected')}
                  </Button>
                  <Button variant="ghost" size="default" onClick={clearSelection}>
                    {tCommon('clearSelection')}
                  </Button>
                </div>
              )}
            </div>
          )}

          {/* Content - flows with the page (the AuthenticatedView container owns
              the scroll), so there is no inner scrollbar boxed to the grid. Grows
              (flex-1) so the pagination bar stays docked at the bottom of the page
              even when the list is empty or shorter than the viewport. */}
          <div className="flex-1">
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
                  {filtersActive
                    ? t('noMatches')
                    : folderTrail.length > 0
                      ? t('emptyFolder')
                      : t('empty')}
                </p>
                {!filtersActive && folderTrail.length === 0 && (
                  <>
                    <p className="text-xs mt-1 text-theme-muted">{t('emptyHint')}</p>
                    <Button variant="default" size="sm" className="mt-4" onClick={() => fileInputRef.current?.click()}>
                      <Upload className="h-4 w-4 mr-1.5" />
                      {t('upload')}
                    </Button>
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
                onDragStart={handleDragStart}
                onDragEnd={handleDragEnd}
                onDragCancel={() => setActiveDragEntry(null)}
              >
                {/* The shared FilesExplorerBody (grid density): folders first (sorted by
                    last activity), then files grouped into collapsible per-day sections -
                    the SAME body the side-panel explorer + project Files tab render. The
                    tiles stay dnd-kit draggable/droppable inside this DndContext, so the
                    drag-to-move behaviour is unchanged. */}
                <FilesExplorerBody
                  variant="grid"
                  entries={entries}
                  enableFolders
                  tFiles={t}
                  onOpenFolder={enterFolder}
                  onOpenFile={setDetailEntry}
                  onDownloadFile={handleDownloadOne}
                  downloadLabel={tExp('download')}
                  selectable
                  selectedIds={selectedIds}
                  onToggleSelect={toggleSelection}
                  gridDraggable
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
                      const virtual = isVirtualEntry(entry);
                      return (
                        <tr
                          key={entryKey(entry)}
                          className="group cursor-pointer transition-colors hover:bg-[var(--bg-tertiary)]/50"
                          onClick={() => (entry.isFolder ? enterFolder(entry) : setDetailEntry(entry))}
                        >
                          <td className="px-3 py-2 text-center" onClick={(e) => e.stopPropagation()}>
                            {/* Virtual workflow folders aren't selectable - empty cell. */}
                            {!virtual && (
                              <input
                                type="checkbox"
                                checked={selectedIds.has(entry.id)}
                                onChange={() => toggleSelection(entry.id)}
                                className="rounded border-slate-300 dark:border-slate-600"
                              />
                            )}
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
                              <span className={`text-[10px] leading-tight px-1.5 py-0.5 rounded-full font-medium ${STORAGE_SOURCE_STYLES[entry.sourceType] ?? 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400'}`}>
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

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}
