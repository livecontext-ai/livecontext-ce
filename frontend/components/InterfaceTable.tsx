'use client';

import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useRouter } from '@/i18n/navigation';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Search, Monitor, Plus, Trash2, Copy, Globe, Clock, ArrowUpDown, Eye } from 'lucide-react';
import { PublicationStatusIcon } from '@/components/publications/PublicationStatusIcon';
import { ResourceInfoPopover } from '@/components/resource-info/ResourceInfoPopover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { favoritesFirst, type ListSortKey, type VisibilityFilter } from '@/lib/utils/listSort';
import { useResourceFavorites } from '@/hooks/useResourceFavorites';
import { FavoriteStarButton } from '@/components/ui/FavoriteStarButton';
import LoadingSpinner from '@/components/LoadingSpinner';
import { resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';
import { CreateInterfaceModal } from '@/components/chat/CreateInterfaceModal';
import { orchestratorApi } from '@/lib/api';
import { interfaceService } from '@/lib/api/orchestrator/interface.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
import { useTranslations } from 'next-intl';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import { useSelectableItems } from '@/hooks/useSelectableItems';
import { FolderPlus } from 'lucide-react';
import { InterfaceFolderFace, INTERFACE_FACE_CELLS } from '@/components/folders/InterfaceFolderFace';
import { FolderBreadcrumb } from '@/components/folders/FolderBreadcrumb';
import { FolderTilesGrid } from '@/components/folders/FolderTilesGrid';
import { FolderDialogs } from '@/components/folders/FolderDialogs';
import { FolderDragContext } from '@/components/folders/FolderDragContext';
import { DraggableResourceCard } from '@/components/folders/DraggableResourceCard';
import { useListFolders } from '@/hooks/useListFolders';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { EmptyState } from '@/components/ui/EmptyState';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import PublishResourceModal from '@/components/marketplace/PublishResourceModal';
import { useResourceRowsDeleted } from '@/lib/resources/resourceDeleted';

/**
 * How many folder-preview pages one level of the list will render at most. A face draws
 * {@code INTERFACE_FACE_CELLS} pages, so this is four folders' worth: past that the tiles keep
 * their silhouettes rather than turning a folder-heavy page into dozens of template loads.
 */
const FOLDER_TEMPLATE_BUDGET = INTERFACE_FACE_CELLS * 4;

export interface InterfaceRow {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  htmlTemplate?: string;
  cssTemplate?: string;
  jsTemplate?: string;
  format?: string | null;
  isPublic: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
  sourceWorkflowId?: string;
  interfaceType?: string;
  data?: Record<string, unknown>;
}

// Skeleton card for loading state (Pinterest style - matches Applications page)
function InterfaceCardSkeleton() {
  return (
    <div className="space-y-3 animate-pulse mb-4 break-inside-avoid">
      <div className="bg-theme-tertiary rounded-xl overflow-hidden" style={{ aspectRatio: '16 / 10' }} />
      <div className="space-y-1">
        <div className="h-4 bg-theme-tertiary rounded w-3/4" />
        <div className="h-3 bg-theme-tertiary rounded w-full" />
      </div>
    </div>
  );
}

interface InterfaceCardProps {
  intf: InterfaceRow;
  isSelected: boolean;
  isShared: boolean;
  isPendingReview: boolean;
  isRejected: boolean;
  rejectionReason?: string | null;
  /** Show a Lock when not shared/pending/rejected (gated on the publication-status sweep). */
  showPrivate?: boolean;
  isFavorite: boolean;
  onToggleFavorite: (id: string) => void;
  onToggleSelect: (id: string) => void;
  onClick: () => void;
}

function InterfaceCard({ intf, isSelected, isShared, isPendingReview, isRejected, rejectionReason, showPrivate, isFavorite, onToggleFavorite, onToggleSelect, onClick }: InterfaceCardProps) {
  const t = useTranslations();
  const hasTemplate = !!intf.htmlTemplate;
  const isWebSearch = intf.interfaceType === 'web_search';
  // The interface's own shape. Kept on the light list payload precisely so the card can size
  // its thumbnail instead of assuming 1280x800.
  const formatViewport = resolveInterfaceFormat(intf.format);

  // Parse web search data for browser-chrome preview
  const webSearchPreview = useMemo(() => {
    if (!isWebSearch || !intf.data) return null;
    const results = (intf.data.results as Array<Record<string, unknown>>) || [];
    const firstResult = results[0];
    const query = (firstResult?.query as string) || '';

    const searchItems: Array<{ title: string; url: string }> = [];
    for (const r of results) {
      const items = r.results as Array<Record<string, unknown>> | undefined;
      if (items) {
        for (const item of items) {
          const url = item.url as string | undefined;
          const itemTitle = item.title as string | undefined;
          if (url && itemTitle) {
            searchItems.push({ title: itemTitle, url });
          }
        }
      }
    }

    const fetchedCount = results.reduce((count: number, r: Record<string, unknown>) => {
      const pages = r.pages as Array<Record<string, unknown>> | undefined;
      if (pages) return count + pages.length;
      if (r.action === 'fetch' && r.url) return count + 1;
      return count;
    }, 0);

    return { query, searchItems, fetchedCount };
  }, [isWebSearch, intf.data]);

  return (
    <div
      className="group space-y-3 mb-4 break-inside-avoid relative cursor-pointer"
      onClick={onClick}
      title={undefined}
    >
      {/* Selection checkbox - visible on hover OR when checked */}
      <div className={`absolute top-2 right-2 z-10 transition-opacity ${isSelected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onToggleSelect(intf.id)}
          onClick={(e) => e.stopPropagation()}
          className="rounded border-theme cursor-pointer h-4 w-4"
        />
      </div>

      {/* Favorite star - top-left (opposite the checkbox), floats this interface to the top. */}
      <FavoriteStarButton
        isFavorite={isFavorite}
        onToggle={() => onToggleFavorite(intf.id)}
        className="top-2 left-2 bottom-auto"
      />

      {/* Preview area - natural height (Pinterest style) */}
      {isWebSearch && webSearchPreview ? (
        <div className="overflow-hidden rounded-[12px] border border-theme bg-theme-primary">
          {/* Browser chrome - dots + search bar */}
          <div className="flex items-center gap-2 px-2.5 py-1.5 bg-theme-secondary border-b border-theme">
            <div className="flex items-center gap-1">
              <span className="w-2 h-2 rounded-full bg-red-400/70" />
              <span className="w-2 h-2 rounded-full bg-yellow-400/70" />
              <span className="w-2 h-2 rounded-full bg-green-400/70" />
            </div>
            <div className="flex-1 flex items-center gap-1 px-2 py-0.5 bg-theme-primary rounded border border-theme text-xs">
              <Search className="w-3 h-3 text-theme-muted shrink-0" />
              <span className="truncate text-theme-secondary">{webSearchPreview.query || intf.name}</span>
            </div>
          </div>

          {/* Search results preview - favicon + title */}
          <div className="px-3 py-2">
            {webSearchPreview.searchItems.length > 0 ? (
              <div className="space-y-1">
                {webSearchPreview.searchItems.slice(0, 4).map((item, idx) => (
                  <div key={idx} className="flex items-center gap-2">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      width={12}
                      height={12}
                      alt=""
                      className="rounded-full shrink-0"
                      src={`https://s2.googleusercontent.com/s2/favicons?domain=${item.url}&sz=64`}
                    />
                    <span className="text-xs text-theme-secondary truncate">{item.title}</span>
                  </div>
                ))}
                {webSearchPreview.searchItems.length > 4 && (
                  <span className="text-xs text-theme-muted">+{webSearchPreview.searchItems.length - 4} more</span>
                )}
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <Globe className="w-3.5 h-3.5 text-theme-muted shrink-0" />
                <span className="text-xs text-theme-secondary">{intf.name}</span>
              </div>
            )}
          </div>

          {/* Footer - result/fetch counts */}
          <div className="bg-theme-primary border-t border-theme px-3 py-1.5">
            <div className="flex items-center gap-2">
              {webSearchPreview.searchItems.length > 0 && (
                <span className="text-xs text-theme-muted flex items-center gap-1">
                  <Search className="h-2.5 w-2.5" />
                  {webSearchPreview.searchItems.length}
                </span>
              )}
              {webSearchPreview.fetchedCount > 0 && (
                <span className="text-xs text-theme-muted flex items-center gap-1">
                  <Globe className="h-2.5 w-2.5" />
                  {webSearchPreview.fetchedCount}
                </span>
              )}
            </div>
          </div>
        </div>
      ) : hasTemplate ? (
        // A declared format gives the card a real shape: render the interface at its own
        // viewport and letterbox it, instead of width-fitting a 1080x1920 page and cropping
        // everything past the first 400px.
        formatViewport ? (
          <div className="pointer-events-none rounded-xl overflow-hidden" style={{ height: 400 }}>
            <InterfaceThumbnail
              htmlTemplate={intf.htmlTemplate!}
              mode="edit"
              customCss={intf.cssTemplate || undefined}
              jsTemplate={intf.jsTemplate || undefined}
              fit="contain"
              viewport={formatViewport}
            />
          </div>
        ) : (
          <div className="pointer-events-none rounded-xl overflow-hidden">
            <InterfaceThumbnail
              htmlTemplate={intf.htmlTemplate!}
              mode="edit"
              customCss={intf.cssTemplate || undefined}
              jsTemplate={intf.jsTemplate || undefined}
              maxHeight={400}
            />
          </div>
        )
      ) : (
        <div className="rounded-xl overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900">
          <div className="relative flex items-center justify-center overflow-hidden" style={{ aspectRatio: '16 / 10' }}>
            <div
              className="absolute inset-0 dark:hidden"
              style={{
                backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)',
                backgroundSize: '16px 16px',
              }}
            />
            <div
              className="hidden dark:block absolute inset-0"
              style={{
                backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)',
                backgroundSize: '16px 16px',
              }}
            />
            <div className="relative z-10">
              <div className="w-12 h-12 bg-theme-secondary rounded-xl flex items-center justify-center">
                <Monitor className="w-6 h-6 text-theme-tertiary" />
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Card Content */}
      <div className="px-1 py-1 space-y-1">
        <div className="flex items-center gap-1.5">
          <span className="text-sm font-medium text-theme-primary truncate flex-1">{intf.name}</span>
          <PublicationStatusIcon
            isShared={isShared}
            isPending={!isShared && !isRejected && isPendingReview}
            isRejected={!isShared && isRejected}
            rejectionReason={rejectionReason}
            showPrivate={showPrivate}
          />
          {/* On the name row rather than a row of its own: this card is a picture over a
              name, and a dedicated meta row would add height to every tile in a masonry
              grid to hold a single button. */}
          <ResourceInfoPopover
            resourceName={intf.name}
            ownerId={intf.tenantId}
            createdAt={intf.createdAt}
            updatedAt={intf.updatedAt}
            className="shrink-0"
            data-testid={`interface-info-${intf.id}`}
          />
        </div>
        {intf.description && (
          <p className="text-xs text-theme-muted truncate cursor-help" title={intf.description}>{intf.description}</p>
        )}
      </div>
    </div>
  );
}

interface InterfaceTableProps {
  className?: string;
  /** Filter interfaces by type. undefined = show all (except web_search), 'web_search' = web search only */
  interfaceTypeFilter?: 'html' | 'web_search';
}

export function InterfaceTable({ className = '', interfaceTypeFilter }: InterfaceTableProps) {
  const router = useRouter();
  const t = useTranslations();
  const { toasts, addToast, removeToast } = useToast();
  // Audit 2026-05-17 round-6 - VIEWER gate on destructive actions.
  const canMutate = useCanMutateInCurrentOrg();
  // Personal favorites (workspace-scoped): float favorited interfaces to the top
  // and paint a star on each card. Refetched on workspace switch by the hook.
  const handleFavoriteError = useCallback(() => {
    addToast({ type: 'error', title: t('common.favoriteErrorTitle'), message: t('common.favoriteErrorMessage') });
  }, [addToast, t]);
  const { favoriteIds, toggleFavorite } = useResourceFavorites('INTERFACE', handleFavoriteError);
  const [interfaces, setInterfaces] = useState<InterfaceRow[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Bumped on workspace switch to force a reload; the load effect keys on it (and the search term /
  // type) rather than on the fetch callback identity, so an unstable callback can never re-fire it.
  const [reloadKey, setReloadKey] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearch = useDebouncedValue(searchQuery, 300);
  // Default order = most-recently-modified first; visibility filter = no restriction.
  const [sortBy, setSortBy] = useState<ListSortKey>('lastModified');
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');
  // Publication status now ships WITH each list page (publicationStatuses envelope), so it is always
  // resolved by the time a card renders - no separate sweep, no Lock-flash gate.
  const { selectedIds: selectedInterfaces, toggle: toggleInterfaceSelection, clear: clearInterfaceSelection } = useSelectableItems();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [publishingInterface, setPublishingInterface] = useState<InterfaceRow | null>(null);
  const [publishedInterfaceIds, setPublishedInterfaceIds] = useState<Set<string>>(new Set());
  const [pendingReviewInterfaceIds, setPendingReviewInterfaceIds] = useState<Set<string>>(new Set());
  const [rejectedInterfaceReasons, setRejectedInterfaceReasons] = useState<Map<string, string | null>>(new Map());
  const [unsharing, setUnsharing] = useState(false);
  const [unshareConfirmInterface, setUnshareConfirmInterface] = useState<string | null>(null);
  // Monotonic id so only the latest page request applies its result - guards against an out-of-order
  // resolve when page / sort / visibility / search change in quick succession (mirrors DataSourceTable).
  const requestIdRef = useRef(0);
  // Interfaces whose full html/css/js we've already pulled for the visible page (html view only) - so
  // each interface's template is fetched at most once, never re-fetched on page/sort/filter change.
  const fetchedTemplateIds = useRef<Set<string>>(new Set());
  // The lazily-loaded html/css/js for visible html-view interfaces (keyed by id), merged into the
  // card's intf at render. Kept OUT of `interfaces` state so a template merge does not re-trigger the
  // publication-status sweep (which keys on `interfaces`).
  const [templatesById, setTemplatesById] = useState<Map<string, { htmlTemplate?: string; cssTemplate?: string; jsTemplate?: string }>>(new Map());

  // FOLDERS (V450). The level being shown is a fetch parameter like the page number; the
  // tiles and the trail come back WITH the list. `reloadRef` breaks the loop between the
  // two: the hook needs a way to reload, and the fetch needs the hook's level.
  const reloadRef = useRef<() => void>(() => {});
  const searching = debouncedSearch.trim().length > 0;
  const folders = useListFolders({
    kind: 'interface',
    reload: useCallback(() => reloadRef.current(), []),
    busy: loading,
    selectedIds: selectedInterfaces,
    clearSelection: clearInterfaceSelection,
    searching,
    canMutate,
    labels: {
      actionFailed: t('folders.actionFailed'),
      createFailed: t('folders.createFailed'),
      renameFailed: t('folders.renameFailed'),
      deleteFailed: t('folders.deleteFailed'),
      moveFailed: t('folders.moveFailed'),
      moved: t('folders.moved'),
      movedToFolder: (count, name) => t('folders.movedToFolder', { count, name }),
      movedToTopLevel: (count) => t('folders.movedToTopLevel', { count }),
    },
    notify: addToast,
  });
  const folderCountLabel = useCallback(
    (count: number) => t('folders.interfaceCount', { count }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  // Load ONE server page. The backend applies search (`q`), type, sort and the visibility filter over
  // the whole set and returns only the requested slice already enriched with each row's publication
  // badge (`publicationStatuses`), so the browser never loads more than it shows and never sweeps all
  // publications. Mirrors the tables board (/data-sources/paged: server-paginated + server-enriched).
  const fetchInterfaces = useCallback(async () => {
    const reqId = ++requestIdRef.current;
    try {
      setLoading(true);
      setError(null);
      // A fresh page replaces the rows, so forget which templates we'd prefetched.
      fetchedTemplateIds.current = new Set();
      setTemplatesById(new Map());
      const result = await interfaceService.getInterfacesPage({
        page,
        size: pageSize,
        q: debouncedSearch,
        type: interfaceTypeFilter,
        sort: sortBy,
        visibility: visibilityFilter,
        // Heavy html/css/js templates are pulled lazily for the visible html-view cards (see the
        // template-prefetch effect below). The web_search / mixed views keep templates inline because
        // the list projection ALSO nulls `data`, which web_search cards need.
        includeTemplates: interfaceTypeFilter !== 'html',
        // `root` = the pages filed nowhere. A search overrides this server-side and looks
        // through every folder, so a name is always findable.
        folderId: folders.folderIdParam,
        includeFolders: true,
      });
      // A newer request superseded this one - drop its (now stale) result.
      if (reqId !== requestIdRef.current) return;

      setInterfaces((result.items || []) as InterfaceRow[]);
      setTotalCount(result.totalCount || 0);
      folders.applyListResponse(result);

      // Publication badge straight off the page envelope (no per-row sweep): ACTIVE = shared,
      // PENDING_REVIEW = in review, REJECTED = rejected (with reason); absent = private.
      const published = new Set<string>();
      const pending = new Set<string>();
      const rejected = new Map<string, string | null>();
      for (const [id, info] of Object.entries(result.publicationStatuses || {})) {
        if (info.status === 'ACTIVE') published.add(id);
        else if (info.status === 'PENDING_REVIEW') pending.add(id);
        else if (info.status === 'REJECTED') rejected.set(id, info.rejectionReason ?? null);
      }
      setPublishedInterfaceIds(published);
      setPendingReviewInterfaceIds(pending);
      setRejectedInterfaceReasons(rejected);
    } catch (err) {
      if (reqId !== requestIdRef.current) return;
      console.error('Error fetching interfaces:', err);
      setError('Failed to load interfaces');
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
  }, [page, pageSize, debouncedSearch, interfaceTypeFilter, sortBy, visibilityFilter, folders.folderIdParam]);

  // Reset to page 0 when the search term, type, sort, visibility filter or folder changes.
  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, interfaceTypeFilter, sortBy, visibilityFilter, folders.folderIdParam]);

  // The hook reloads through this ref, so it can be created before the fetch it triggers.
  reloadRef.current = fetchInterfaces;

  // An interface deleted anywhere else (its side-panel tab, the edit modal, a chat card)
  // drops out of this list at once, then the page is refetched so the total and the
  // page fill come from the server rather than from what was on screen.
  useResourceRowsDeleted('interface', interfaces, setInterfaces, fetchInterfaces);

  // Initial load + reload on page / size / search / type / sort / visibility / workspace switch. The
  // fetch callback already closes over those inputs, so keying on its identity fires exactly one page
  // load per change (the request-id guard discards any superseded in-flight result).
  useEffect(() => {
    fetchInterfaces();
  }, [fetchInterfaces, reloadKey]);

  // Phase 3 (2026-05-18) - clear local rows + published-status sets on
  // workspace switch.
  useOrgScopedReset(() => {
    setInterfaces([]);
    setTotalCount(0);
    setPublishedInterfaceIds(new Set());
    setPendingReviewInterfaceIds(new Set());
    fetchedTemplateIds.current = new Set();
    setTemplatesById(new Map());
    setError(null);
    setPage(0);
    setReloadKey((k) => k + 1);
  });

  const handleUnshareClick = useCallback((id: string) => {
    setUnshareConfirmInterface(id);
  }, []);

  const confirmUnshare = useCallback(async () => {
    if (!unshareConfirmInterface) return;
    setUnsharing(true);
    try {
      await publicationService.unpublishResource('INTERFACE', unshareConfirmInterface);
      setPublishedInterfaceIds(prev => {
        const next = new Set(prev);
        next.delete(unshareConfirmInterface);
        return next;
      });
      addToast({ type: 'success', title: t('marketplace.agents.unpublishSuccess', { type: 'INTERFACE' }), message: t('marketplace.agents.unpublishSuccess', { type: 'INTERFACE' }) });
    } catch (err) {
      console.error('Error unsharing interface:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    } finally {
      setUnsharing(false);
      setUnshareConfirmInterface(null);
    }
  }, [unshareConfirmInterface, addToast, t]);

  // The server returns the page already filtered (type + visibility), sorted and sliced, so render its
  // items verbatim - then float THIS page's favorites to the top (favorites-first within the page).
  const filteredInterfaces = useMemo(
    () => favoritesFirst(interfaces, (i) => i.id, favoriteIds),
    [interfaces, favoriteIds],
  );
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));

  // Snap back if the active page fell out of range (e.g. a last-page deletion narrowed the total).
  useEffect(() => {
    if (!loading && page > 0 && page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [loading, page, totalPages]);

  // Pull the full html/css/js of the given pages into `templatesById`, at most once each
  // (deduped via fetchedTemplateIds). The list payload deliberately omits those heavy fields,
  // and both surfaces that show a page draw the REAL page - the cards and the folder faces -
  // so both load through here.
  //
  // The ids are CLAIMED before the request, not when it lands. Both callers' inputs change
  // while a batch is in flight - the favourites arrive just after the list and rebuild the
  // row array - and a claim made only on arrival means each such change re-requests the very
  // templates already on the wire. A batch is never abandoned either: it is claimed, so
  // dropping it would leave those pages with no template at all, and a batch that lands after
  // the list moved on only writes keys nothing renders, which the next fetch clears anyway.
  const loadTemplates = useCallback((ids: string[]) => {
    const toFetch = ids.filter((id) => !fetchedTemplateIds.current.has(id));
    if (toFetch.length === 0) return;
    toFetch.forEach((id) => fetchedTemplateIds.current.add(id));
    void (async () => {
      const results = await Promise.all(
        toFetch.map(async (id) => ({ id, full: await interfaceService.getInterface(id).catch(() => null) })),
      );
      setTemplatesById((prev) => {
        const next = new Map(prev);
        for (const r of results) {
          if (r.full) {
            next.set(r.id, {
              htmlTemplate: r.full.htmlTemplate,
              cssTemplate: r.full.cssTemplate,
              jsTemplate: r.full.jsTemplate,
            });
          }
        }
        return next;
      });
    })();
  }, []);

  // Html view: the fetch-all loaded metadata only, so pull the full html/css/js for the VISIBLE page
  // (merged into the card at render). Non-visible interfaces never load their (large) templates.
  // The web_search / mixed views already carry templates inline (includeTemplates=true), so skip.
  useEffect(() => {
    if (interfaceTypeFilter !== 'html') return;
    loadTemplates(filteredInterfaces.filter((i) => !i.htmlTemplate).map((i) => i.id));
  }, [filteredInterfaces, interfaceTypeFilter, loadTemplates]);

  // A folder tile previews the pages it holds by RENDERING them, so the few pages its face
  // draws need their templates too - they are inside the folder, so they are never among the
  // cards. Bounded twice: the face draws at most INTERFACE_FACE_CELLS per folder, and one
  // level never loads more than FOLDER_TEMPLATE_BUDGET pages however many folders it shows.
  useEffect(() => {
    // Whole faces, never part of one: a face showing one rendered page beside three
    // silhouettes reads as three pages that failed to load. Once the budget cannot take a
    // whole face, that folder and the ones after it keep their silhouettes.
    const previewIds: string[] = [];
    for (const tile of folders.tiles) {
      const face = (tile.preview ?? []).slice(0, INTERFACE_FACE_CELLS);
      if (previewIds.length + face.length > FOLDER_TEMPLATE_BUDGET) break;
      previewIds.push(...face.map((item) => item.id));
    }
    loadTemplates(previewIds);
  }, [folders.tiles, loadTemplates]);

  const selectableInterfaces = filteredInterfaces;

  const deletableSelectedCount = selectedInterfaces.size;

  const cloneSelectedInterfaces = async () => {
    if (selectedInterfaces.size === 0) return;
    const idsToClone = Array.from(selectedInterfaces);
    try {
      for (const id of idsToClone) {
        await orchestratorApi.cloneInterface(id);
      }
      await fetchInterfaces();
      clearInterfaceSelection();
      addToast({
        type: 'success',
        title: t('common.cloneSuccess'),
        message: t('common.cloneSuccess')
      });
    } catch (err) {
      console.error('Error cloning interfaces:', err);
      addToast({
        type: 'error',
        title: t('common.cloneError'),
        message: t('common.cloneError')
      });
    }
  };

  const deleteSelectedInterfaces = () => {
    if (selectedInterfaces.size === 0) return;
    setShowDeleteModal(true);
  };

  const confirmDeleteInterfaces = async () => {
    if (selectedInterfaces.size === 0) return;

    const idsToDelete = Array.from(selectedInterfaces);
    if (idsToDelete.length === 0) return;

    try {
      const deletePromises = idsToDelete.map(id =>
        orchestratorApi.deleteInterface(id)
      );

      await Promise.all(deletePromises);

      const deletedSet = new Set(idsToDelete);
      setInterfaces(prev => prev.filter(i => !deletedSet.has(i.id)));
      clearInterfaceSelection();
      setShowDeleteModal(false);
    } catch (err) {
      console.error('Error deleting selected interfaces:', err);
      alert('Failed to delete selected interfaces');
    }
  };

  // `interfaceId` is set only when a page was CREATED (the same callback fires after an
  // edit): created while standing in a folder, it is filed there rather than landing back
  // at the top level.
  const handleInterfaceCreated = async (interfaceId?: string) => {
    if (interfaceId) await folders.fileNewResource(interfaceId);
    fetchInterfaces();
    setShowCreateModal(false);
  };

  return (
    // The drag surface covers the header too: the folder path in it is a drop target, so a
    // card can be dragged out of a folder onto the level it belongs to.
    <FolderDragContext folders={folders} nameOf={(id) => interfaces.find((i) => i.id === id)?.name}>
    <div className={`space-y-6 w-full ${className}`}>
      {/* Header - Applications-style: page title + description below, ALWAYS visible so the empty
          state shows the same layout as the Applications page. The create button shows only when
          there are items (when empty the EmptyState carries the create CTA) and never for web_search. */}
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        {/* Inside a folder the PATH is the page title, with the folder mark and an
            up-one-level arrow beside it - the same header the Files browser uses. */}
        {folders.trail.length > 0 ? (
          <FolderBreadcrumb
            trail={folders.trail}
            rootLabel={t('folders.allInterfaces')}
            backLabel={t('folders.upOneLevel')}
            subtitle={t('folders.interfaceCount', { count: totalCount })}
            onNavigate={folders.navigateToFolder}
            droppable={folders.canOrganize}
          />
        ) : (
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-theme-primary">{t('emptyState.interface.title')}</h1>
            <p className="text-sm text-theme-secondary mt-0.5">{t('emptyState.interface.subtitle')}</p>
          </div>
        )}
        {canMutate && !loading && interfaceTypeFilter !== 'web_search' && (
          <div className="flex flex-wrap items-center gap-2 md:shrink-0">
            {folders.foldersEnabled && (
              <Button variant="outline" size="sm" onClick={() => folders.setShowCreateDialog(true)}>
                <FolderPlus className="h-4 w-4 mr-1.5" />
                {t('folders.newFolder')}
              </Button>
            )}
            {(totalCount > 0 || debouncedSearch.trim().length > 0) && (
              <Button
                variant="default"
                size="sm"
                onClick={() => setShowCreateModal(true)}
              >
                <Plus className="h-4 w-4 mr-1.5" />
                {t('emptyState.interface.createButton')}
              </Button>
            )}
          </div>
        )}
      </div>

      {/* Search + visibility filter + sort - visible whenever there is data or an active query. */}
      {(totalCount > 0 || folders.tiles.length > 0 || debouncedSearch.trim().length > 0) && (
        <div className="flex flex-col gap-4 md:flex-row md:items-center">
          <div className="relative flex-1">
            <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
            <Input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('emptyState.interface.searchPlaceholder')}
              className="w-full pl-11 rounded-xl bg-theme-primary border-theme text-sm"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Select value={visibilityFilter} onValueChange={(v) => setVisibilityFilter(v as VisibilityFilter)}>
              <SelectTrigger className="w-auto gap-1.5" aria-label={t('common.filterByVisibility')}>
                <Eye className="h-3.5 w-3.5 opacity-70" />
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t('common.visibilityAny')}</SelectItem>
                <SelectItem value="public">{t('common.visibilityPublic')}</SelectItem>
                <SelectItem value="private">{t('common.visibilityPrivate')}</SelectItem>
              </SelectContent>
            </Select>
            <Select value={sortBy} onValueChange={(v) => setSortBy(v as ListSortKey)}>
              <SelectTrigger className="w-auto gap-1.5" aria-label={t('common.sortBy')}>
                <ArrowUpDown className="h-3.5 w-3.5 opacity-70" />
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="lastModified">{t('common.sortLastModified')}</SelectItem>
                <SelectItem value="name">{t('common.sortName')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      )}

      {/* Contextual actions for selection - floating bottom-center bar (mirrors the task board). */}
      {selectedInterfaces.size > 0 && (
        <SelectionActionBar count={selectedInterfaces.size} onClear={clearInterfaceSelection}>
          {/* Filing stays available while searching: you often find a page BECAUSE you were
              looking for where to put it. Only the drag targets need a folder view. */}
          {canMutate && (
            <BulkBarButton onClick={folders.openMoveDialog}>
              <FolderPlus className="h-3.5 w-3.5" />
              {t('folders.moveToFolder')}
            </BulkBarButton>
          )}
          {canMutate && (
            <BulkBarButton onClick={cloneSelectedInterfaces}>
              <Copy className="h-3.5 w-3.5" />
              {t('common.clone')} ({selectedInterfaces.size})
            </BulkBarButton>
          )}
          {canMutate && selectedInterfaces.size === 1 && (() => {
            const selectedId = Array.from(selectedInterfaces)[0];
            const isPublished = publishedInterfaceIds.has(selectedId);
            const isPendingReview = pendingReviewInterfaceIds.has(selectedId);
            return isPublished ? (
              <BulkBarButton variant="danger" disabled={unsharing} onClick={() => handleUnshareClick(selectedId)}>
                <Globe className="h-3.5 w-3.5" />
                {t('common.unshare')}
              </BulkBarButton>
            ) : isPendingReview ? (
              <BulkBarButton disabled className="text-amber-600 dark:text-amber-400">
                <Clock className="h-3.5 w-3.5" />
                {t('marketplace.pendingReview')}
              </BulkBarButton>
            ) : (
              <BulkBarButton onClick={() => {
                const intf = interfaces.find(i => selectedInterfaces.has(i.id));
                if (intf) setPublishingInterface(intf);
              }}>
                <Globe className="h-3.5 w-3.5" />
                {t('common.share')}
              </BulkBarButton>
            );
          })()}
          {canMutate && (
            <BulkBarButton variant="danger" onClick={deleteSelectedInterfaces} disabled={deletableSelectedCount === 0}>
              <Trash2 className="h-3.5 w-3.5" />
              {t('emptyState.interface.deleteCount', { count: deletableSelectedCount })}
            </BulkBarButton>
          )}
        </SelectionActionBar>
      )}

      {showCreateModal && (
        <CreateInterfaceModal
          onClose={() => setShowCreateModal(false)}
          onInterfaceCreated={handleInterfaceCreated}
        />
      )}

      {/* Delete confirmation modal */}
      <BulkDeleteModal
        isOpen={showDeleteModal}
        title={t('emptyState.interface.deleteTitle')}
        message={t('emptyState.interface.deleteConfirmation', { count: deletableSelectedCount })}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteModal(false)}
        onConfirm={confirmDeleteInterfaces}
      />

      {/* Content */}
      {loading ? (
        <div className="columns-1 md:columns-2 lg:columns-3 gap-4">
          {Array.from({ length: 6 }, (_, i) => (
            <InterfaceCardSkeleton key={i} />
          ))}
        </div>
      ) : (
        <>
          <FolderTilesGrid
            folders={folders}
            countLabel={folderCountLabel}
            renderFace={(folder) => (
              <InterfaceFolderFace preview={folder.preview ?? []} templates={templatesById} />
            )}
          />

          {filteredInterfaces.length === 0 && folders.tiles.length === 0 ? (
        <EmptyState
          icon={<Monitor className="h-7 w-7 text-theme-muted" />}
          size="md"
          title={folders.currentFolderId
            ? t('folders.emptyFolderTitle')
            : (interfaceTypeFilter === 'web_search'
              ? t('emptyState.interface.noWebSearchFound')
              : t('emptyState.interface.noInterfacesFound'))}
          subtitle={folders.currentFolderId
            ? t('folders.emptyFolderSubtitle')
            : totalCount === 0 && debouncedSearch.trim().length === 0
            ? (interfaceTypeFilter === 'web_search'
                ? t('emptyState.interface.webSearchDescription')
                : t('emptyState.interface.createFirstInterface'))
            : t('emptyState.interface.noMatchingInterfaces')}
          actions={canMutate && !folders.currentFolderId && totalCount === 0 && debouncedSearch.trim().length === 0 && interfaceTypeFilter !== 'web_search' ? (
            <Button
              variant="default"
              onClick={() => setShowCreateModal(true)}
              className="inline-flex items-center justify-center gap-2"
            >
              <Plus className="w-4 h-4" />
              {t('emptyState.interface.createButton')}
            </Button>
          ) : undefined}
        />
          ) : (
        <div className="columns-1 md:columns-2 lg:columns-3 gap-4">
          {filteredInterfaces.map((intf) => (
            <DraggableResourceCard key={intf.id} id={intf.id} disabled={!folders.canOrganize}>
            <InterfaceCard
              key={intf.id}
              // Merge the lazily-loaded template (html view) so the thumbnail renders; a no-op for the
              // web_search / mixed views where the template already rode in with the list row.
              intf={templatesById.has(intf.id) ? { ...intf, ...templatesById.get(intf.id) } : intf}
              isSelected={selectedInterfaces.has(intf.id)}
              isShared={publishedInterfaceIds.has(intf.id)}
              isPendingReview={pendingReviewInterfaceIds.has(intf.id)}
              isRejected={rejectedInterfaceReasons.has(intf.id)}
              rejectionReason={rejectedInterfaceReasons.get(intf.id)}
              showPrivate
              isFavorite={favoriteIds.has(intf.id)}
              onToggleFavorite={toggleFavorite}
              onToggleSelect={toggleInterfaceSelection}
              onClick={() => router.push(`/app/interface/${intf.id}`)}
            />
            </DraggableResourceCard>
          ))}
        </div>
          )}

        </>
      )}

      {!loading && totalCount > pageSize && (
        <PaginationBar
          page={page}
          pageSize={pageSize}
          totalCount={totalCount}
          visibleCount={filteredInterfaces.length}
          loading={loading}
          onPageChange={setPage}
          onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        />
      )}

      {publishingInterface && (
        <PublishResourceModal
          isOpen={!!publishingInterface}
          onClose={() => setPublishingInterface(null)}
          resourceType="INTERFACE"
          resourceId={publishingInterface.id}
          resourceName={publishingInterface.name}
          resourceDescription={publishingInterface.description}
          onSuccess={() => {
            setPendingReviewInterfaceIds(prev => new Set(prev).add(publishingInterface.id));
            addToast({
              type: 'success',
              title: t('marketplace.agents.publishSuccess', { type: 'INTERFACE' }),
              message: t('marketplace.agents.publishSuccess', { type: 'INTERFACE' }),
            });
          }}
        />
      )}

      {unshareConfirmInterface && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setUnshareConfirmInterface(null)}
        >
          <div
            className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme max-h-[90vh] overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-2xl flex items-center justify-center mx-auto mb-4">
                <Globe className="w-8 h-8 text-red-500" />
              </div>
              <h3 className="text-2xl font-semibold text-theme-primary">
                {t('marketplace.agents.unpublishConfirmTitle')}
              </h3>
            </div>
            <div className="space-y-3 mb-8">
              <p className="text-theme-secondary text-center">
                {t('marketplace.agents.unpublishConfirmMessage')}
              </p>
              <ul className="text-sm text-theme-secondary space-y-1 list-disc list-inside">
                <li>{t('marketplace.agents.unpublishLostVisibility')}</li>
                <li>{t('marketplace.agents.unpublishLostAcquisitions')}</li>
              </ul>
            </div>
            <div className="flex gap-3">
              <Button variant="outline" onClick={() => setUnshareConfirmInterface(null)} disabled={unsharing} className="flex-1">
                {t('common.cancel')}
              </Button>
              <Button variant="destructive" onClick={confirmUnshare} disabled={unsharing} className="flex-1">
                {unsharing && <LoadingSpinner size="xs" className="mr-1.5" />}
                {t('marketplace.agents.unpublishConfirmButton')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}

      <FolderDialogs folders={folders} selectedIds={selectedInterfaces} />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
    </FolderDragContext>
  );
}
