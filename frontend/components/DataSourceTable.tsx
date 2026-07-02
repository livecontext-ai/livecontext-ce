'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';

import { useRouter } from '@/i18n/navigation';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Search, Plus, Trash2, Table, Copy, Globe, Clock, ArrowUpDown, Eye } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { favoritesFirst, type ListSortKey, type VisibilityFilter } from '@/lib/utils/listSort';
import { useResourceFavorites } from '@/hooks/useResourceFavorites';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
import { orchestratorApi, DataSource } from '@/lib/api';
import { dataSourceService } from '@/lib/api/orchestrator/datasource.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { CreateDataSourceModal } from './chat/CreateDataSourceModal';
import { useTranslations } from 'next-intl';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { useSelectableItems } from '@/hooks/useSelectableItems';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { EmptyState } from '@/components/ui/EmptyState';
import { CardSkeletonGrid } from '@/components/ui/CardSkeletonGrid';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import PublishResourceModal from '@/components/marketplace/PublishResourceModal';
import { DataSourceCard } from '@/components/data-table/DataSourceCard';

interface DataSourceTableProps {
  className?: string;
  onDataSourceClick?: (dataSource: DataSource) => void;
  onAnalyzeClick?: () => void; // Callback to close modal before redirecting
  onAddAnalyzeBadges?: (ids: string[], type: 'data' | 'workflow') => void; // Callback to add badges directly
  onDataSourceCreated?: (dataSource: DataSource) => void; // Callback when a new datasource is created
}

export default function DataSourceTable({
  className = '',
  onDataSourceClick,
  onAnalyzeClick,
  onAddAnalyzeBadges,
  onDataSourceCreated
}: DataSourceTableProps) {
  const router = useRouter();
  const { toasts, addToast, removeToast } = useToast();
  const t = useTranslations();
  // Audit 2026-05-17 round-6 - VIEWER gate on destructive actions.
  const canMutate = useCanMutateInCurrentOrg();
  // Personal favorites (workspace-scoped): float favorited tables to the top of
  // the current page and paint a star on each card. Refetched on workspace switch
  // by the hook. The list is server-paginated, so favorites float within the
  // loaded page (matching "display favorites first" without a cross-page fetch).
  const handleFavoriteError = useCallback(() => {
    addToast({ type: 'error', title: t('common.favoriteErrorTitle'), message: t('common.favoriteErrorMessage') });
  }, [addToast, t]);
  const { favoriteIds, toggleFavorite } = useResourceFavorites('TABLE', handleFavoriteError);

  const [dataSources, setDataSources] = useState<DataSource[]>([]);
  // Per-datasource row counts for the current page (id -> count), from the paged endpoint.
  const [rowCounts, setRowCounts] = useState<Record<string, number>>({});
  // Per-datasource first-N row sample (id -> [row data…]) for each card's mini-table preview.
  const [sampleRows, setSampleRows] = useState<Record<string, Array<Record<string, unknown>>>>({});
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Bumped on workspace switch to force a reload; the load effect keys on it (and the search term)
  // rather than on the fetch callback identity, so an unstable callback can never re-fire the load.
  const [reloadKey, setReloadKey] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearch = useDebouncedValue(searchQuery, 300);
  // Default order = most-recently-modified first; visibility filter = no restriction.
  const [sortBy, setSortBy] = useState<ListSortKey>('lastModified');
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');
  // Monotonic id so only the latest page request applies its result - guards against an
  // out-of-order resolve when page / sort / visibility / search change in quick succession.
  const requestIdRef = useRef(0);
  const { selectedIds: selectedDataSources, toggle: toggleDataSourceSelection, clear: clearDataSourceSelection } = useSelectableItems();
  const [showAddDataSourceModal, setShowAddDataSourceModal] = useState(false);
  const [addDataSourceName, setAddDataSourceName] = useState('');
  const [addDataSourceDescription, setAddDataSourceDescription] = useState('');
  const [isAddingDataSource, setIsAddingDataSource] = useState(false);
  const [showDeleteDataSourcesModal, setShowDeleteDataSourcesModal] = useState(false);
  const [isAddingDataSourceInline, setIsAddingDataSourceInline] = useState(false);
  const [newDataSourceName, setNewDataSourceName] = useState('');
  const [newDataSourceDescription, setNewDataSourceDescription] = useState('');
  const [showCreateDataModal, setShowCreateDataModal] = useState(false);
  const [publishingDataSource, setPublishingDataSource] = useState<DataSource | null>(null);
  const [publishedDataSourceIds, setPublishedDataSourceIds] = useState<Set<string>>(new Set());
  const [pendingReviewDataSourceIds, setPendingReviewDataSourceIds] = useState<Set<string>>(new Set());
  const [rejectedDataSourceReasons, setRejectedDataSourceReasons] = useState<Map<string, string | null>>(new Map());
  const [unsharing, setUnsharing] = useState(false);
  const [unshareConfirmDataSource, setUnshareConfirmDataSource] = useState<string | null>(null);

  // Load ONE server page. Server applies search (`q`), sort and the visibility filter over the whole
  // set and returns only the requested slice already enriched with each row's publication badge
  // (`publicationStatuses`), so the browser never loads more than it shows and never fans out a
  // per-row status call. Mirrors the workflow board (server-paginated + server-enriched).
  const fetchDataSources = useCallback(async () => {
    const reqId = ++requestIdRef.current;
    try {
      setLoading(true);
      setError(null);
      const result = await dataSourceService.getDataSourcesPage({
        page,
        size: pageSize,
        q: debouncedSearch,
        sort: sortBy,
        visibility: visibilityFilter,
      });
      // A newer request superseded this one - drop its (now stale) result.
      if (reqId !== requestIdRef.current) return;

      setDataSources((result.items || []) as DataSource[]);
      setRowCounts(result.rowCounts || {});
      setSampleRows(result.sampleRows || {});
      setTotalCount(result.totalCount || 0);

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
      setPublishedDataSourceIds(published);
      setPendingReviewDataSourceIds(pending);
      setRejectedDataSourceReasons(rejected);
    } catch (err) {
      if (reqId !== requestIdRef.current) return;
      console.error('Error fetching data sources:', err);
      setError(t('data.failedToLoad'));
      addToast({
        type: 'error',
        title: t('data.errorLoadingDataSources'),
        message: t('data.failedToLoad')
      });
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
  }, [page, pageSize, debouncedSearch, sortBy, visibilityFilter, addToast, t]);

  // Reset to page 0 when the search term, sort, or visibility filter changes (a new page 0 fetch
  // then fires via the load effect below).
  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, sortBy, visibilityFilter]);

  // 🇫🇷 Démarrer l'ajout inline de datasource
  const startAddingDataSourceInline = () => {
    setIsAddingDataSourceInline(true);
    setNewDataSourceName('');
    setNewDataSourceDescription('');
  };

  // 🇫🇷 Annuler l'ajout inline de datasource
  const cancelAddingDataSourceInline = () => {
    setIsAddingDataSourceInline(false);
    setNewDataSourceName('');
    setNewDataSourceDescription('');
  };

  // 🇫🇷 Création d'un nouveau DataSource
  const createNewDataSource = async () => {
    const nameToUse = isAddingDataSourceInline ? newDataSourceName : addDataSourceName;
    const descriptionToUse = isAddingDataSourceInline ? newDataSourceDescription : addDataSourceDescription;

    if (!nameToUse.trim()) return;

    try {
      setIsAddingDataSource(true);

      const dataSourceConfig = {
        name: nameToUse,
        description: descriptionToUse,
        sourceConfig: {},
        data: [],
        createdBy: 'user',
        mappingSpec: {}
      };

      const newDataSource = await orchestratorApi.createDataSource(dataSourceConfig);

      // Call callback if provided
      if (onDataSourceCreated) {
        onDataSourceCreated(newDataSource as unknown as DataSource);
      }

      // Reinitialiser le formulaire
      if (isAddingDataSourceInline) {
        setNewDataSourceName('');
        setNewDataSourceDescription('');
        setIsAddingDataSourceInline(false);
      } else {
        setAddDataSourceName('');
        setAddDataSourceDescription('');
        setShowAddDataSourceModal(false);
      }

      // Recharger la liste des DataSources
      await fetchDataSources();

      addToast({
        type: 'success',
        title: t('data.dataSourceCreated'),
        message: t('data.dataSourceCreatedMessage', { name: nameToUse })
      });
    } catch (err) {
      console.error('Error creating new data source:', err);
      addToast({
        type: 'error',
        title: t('data.errorCreatingDataSource'),
        message: t('data.failedToCreate')
      });
    } finally {
      setIsAddingDataSource(false);
    }
  };

  // Clone selected data sources
  const cloneSelectedDataSources = async () => {
    if (selectedDataSources.size === 0) return;
    const idsToClone = Array.from(selectedDataSources);
    try {
      for (const id of idsToClone) {
        await orchestratorApi.cloneDataSource(String(id));
      }
      await fetchDataSources();
      clearDataSourceSelection();
      addToast({
        type: 'success',
        title: t('common.cloneSuccess'),
        message: t('common.cloneSuccess')
      });
    } catch (err) {
      console.error('Error cloning data sources:', err);
      addToast({
        type: 'error',
        title: t('common.cloneError'),
        message: t('common.cloneError')
      });
    }
  };

  // 🇫🇷 Suppression multiple de DataSources - ouvre la modale
  const deleteSelectedDataSources = () => {
    if (selectedDataSources.size === 0) return;
    setShowDeleteDataSourcesModal(true);
  };

  // Confirmation de suppression des DataSources
  const confirmDeleteDataSources = async () => {
    if (selectedDataSources.size === 0) return;

    const idsToDelete = Array.from(selectedDataSources).filter(id => {
      const ds = dataSources.find(d => d.id === id);
      return !!ds;
    });

    if (idsToDelete.length === 0) return;

    const count = idsToDelete.length;

    try {
      const deletePromises = idsToDelete.map(id =>
        orchestratorApi.deleteDataSource(String(id))
      );

      await Promise.all(deletePromises);

      clearDataSourceSelection();
      setShowDeleteDataSourcesModal(false);
      // Reload the current page so the server recomputes totalCount + refills the page (the
      // snap-back effect handles the case where the last page is now empty).
      await fetchDataSources();

      addToast({
        type: 'success',
        title: t('data.dataSourcesDeleted'),
        message: t('data.dataSourcesDeletedMessage', { count })
      });
    } catch (err) {
      console.error('Error deleting selected data sources:', err);
      addToast({
        type: 'error',
        title: t('data.errorDeletingDataSources'),
        message: t('data.failedToDelete')
      });
    }
  };

  const deletableSelectedCount = selectedDataSources.size;

  // Handle datasource click - navigate using native Next.js routing
  const handleDataSourceClick = (dataSource: DataSource) => {
    if (onDataSourceClick) {
      onDataSourceClick(dataSource);
    } else {
      router.push(`/app/tables/${dataSource.id}`);
    }
  };

  // The server returns the page already filtered (visibility), sorted and sliced, so render its
  // items verbatim - then float this page's favorites to the top (favorites-first within the page).
  const filteredDataSources = favoritesFirst(dataSources, (d) => String(d.id), favoriteIds);
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));

  // Snap back if the active page fell out of range (e.g. a last-page deletion narrowed the total).
  useEffect(() => {
    if (!loading && page > 0 && page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [loading, page, totalPages]);

  // Initial load + reload on page / size / search / sort / visibility / workspace switch. The fetch
  // callback already closes over those inputs, so keying on its identity fires exactly one page load
  // per change (the request-id guard discards any superseded in-flight result).
  useEffect(() => {
    fetchDataSources();
  }, [fetchDataSources, reloadKey]);

  // Phase 3 (2026-05-18) - clear local rows on workspace switch.
  useOrgScopedReset(() => {
    setDataSources([]);
    setRowCounts({});
    setSampleRows({});
    setTotalCount(0);
    setPublishedDataSourceIds(new Set());
    setPendingReviewDataSourceIds(new Set());
    setRejectedDataSourceReasons(new Map());
    setError(null);
    setPage(0);
    setReloadKey((k) => k + 1);
  });

  const handleUnshareClick = useCallback((id: string) => {
    setUnshareConfirmDataSource(id);
  }, []);

  const confirmUnshare = useCallback(async () => {
    if (!unshareConfirmDataSource) return;
    setUnsharing(true);
    try {
      await publicationService.unpublishResource('TABLE', unshareConfirmDataSource);
      setPublishedDataSourceIds(prev => {
        const next = new Set(prev);
        next.delete(unshareConfirmDataSource);
        return next;
      });
      addToast({ type: 'success', title: t('marketplace.agents.unpublishSuccess', { type: 'TABLE' }), message: t('marketplace.agents.unpublishSuccess', { type: 'TABLE' }) });
    } catch (err) {
      console.error('Error unsharing data source:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    } finally {
      setUnsharing(false);
      setUnshareConfirmDataSource(null);
    }
  }, [unshareConfirmDataSource, addToast, t]);

  return (
    <div className={`space-y-4 w-full overflow-visible ${className}`}>
      {/* Header - Applications-style: page title + description below, ALWAYS visible so the empty
          state shows the same layout as the Applications page. The create button shows only when
          there are items; when empty the EmptyState carries the create CTA. */}
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div>
          <h1 className="text-lg font-semibold text-theme-primary">{t('data.title')}</h1>
          <p className="text-sm text-theme-secondary mt-0.5">{t('data.subtitle')}</p>
        </div>
        {canMutate && !loading && (totalCount > 0 || debouncedSearch.trim().length > 0) && (
          <Button
            variant="default"
            size="sm"
            onClick={() => setShowCreateDataModal(true)}
          >
            <Plus className="h-4 w-4 mr-1.5" />
            {t('data.createTable')}
          </Button>
        )}
      </div>

      {/* Search + visibility filter + sort - hidden when there is no data and no active search. */}
      {(totalCount > 0 || debouncedSearch.trim().length > 0) && (
        <div className="flex flex-col gap-4 md:flex-row md:items-center">
          <div className="relative flex-1 overflow-visible">
            <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('data.searchPlaceholder')}
              className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 py-3 text-sm text-[var(--text-primary)] ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 disabled:cursor-not-allowed disabled:opacity-50 pl-11"
            />
          </div>
          <div className="flex items-center gap-2">
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

      {/* Actions contextuelles - floating bottom-center bar (mirrors the task board). */}
      {selectedDataSources.size > 0 && (
        <SelectionActionBar count={selectedDataSources.size} onClear={clearDataSourceSelection}>
          {canMutate && (
            <BulkBarButton onClick={cloneSelectedDataSources}>
              <Copy className="h-3.5 w-3.5" />
              {t('common.clone')} ({selectedDataSources.size})
            </BulkBarButton>
          )}
          {canMutate && selectedDataSources.size === 1 && (() => {
            const selectedId = String(Array.from(selectedDataSources)[0]);
            const isPublished = publishedDataSourceIds.has(selectedId);
            const isPendingReview = pendingReviewDataSourceIds.has(selectedId);
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
                const ds = dataSources.find(d => String(d.id) === selectedId);
                if (ds) setPublishingDataSource(ds);
              }}>
                <Globe className="h-3.5 w-3.5" />
                {t('common.share')}
              </BulkBarButton>
            );
          })()}
          {canMutate && (
            <BulkBarButton variant="danger" onClick={deleteSelectedDataSources} disabled={deletableSelectedCount === 0}>
              <Trash2 className="h-3.5 w-3.5" />
              {t('data.deleteCount', { count: deletableSelectedCount })}
            </BulkBarButton>
          )}
        </SelectionActionBar>
      )}

      {/* Table cards grid */}
      <div className="space-y-4 w-full overflow-visible">
        {loading ? (
          <CardSkeletonGrid />
        ) : filteredDataSources.length === 0 && !isAddingDataSourceInline ? (
          <EmptyState
            icon={<Table className="h-7 w-7 text-theme-muted" />}
            size="md"
            title={t('data.noTablesFound')}
            subtitle={totalCount === 0 && debouncedSearch.trim().length === 0
              ? t('data.createFirstTable')
              : t('data.noMatchingTables')}
            actions={canMutate && totalCount === 0 && debouncedSearch.trim().length === 0 ? (
              <Button
                variant="default"
                onClick={() => setShowCreateDataModal(true)}
                className="inline-flex items-center justify-center gap-2"
              >
                <Plus className="w-4 h-4" />
                {t('data.createTable')}
              </Button>
            ) : undefined}
          />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {filteredDataSources.map((ds) => (
              <DataSourceCard
                key={ds.id}
                ds={ds}
                rowCount={rowCounts[String(ds.id)] ?? 0}
                sampleRows={sampleRows[String(ds.id)] ?? []}
                onClick={() => handleDataSourceClick(ds)}
                selected={selectedDataSources.has(ds.id)}
                onToggleSelect={() => toggleDataSourceSelection(ds.id)}
                isFavorite={favoriteIds.has(String(ds.id))}
                onToggleFavorite={() => toggleFavorite(String(ds.id))}
                publication={{
                  isShared: publishedDataSourceIds.has(String(ds.id)),
                  isPending: !publishedDataSourceIds.has(String(ds.id)) && !rejectedDataSourceReasons.has(String(ds.id)) && pendingReviewDataSourceIds.has(String(ds.id)),
                  isRejected: !publishedDataSourceIds.has(String(ds.id)) && rejectedDataSourceReasons.has(String(ds.id)),
                  rejectionReason: rejectedDataSourceReasons.get(String(ds.id)),
                  // Status ships WITH the page now (no separate async sweep), so it is always
                  // resolved by the time a card renders - the private Lock can show immediately.
                  showPrivate: true,
                }}
              />
            ))}
          </div>
        )}

      </div>

      {!loading && totalCount > pageSize && (
        <PaginationBar
          page={page}
          pageSize={pageSize}
          totalCount={totalCount}
          visibleCount={filteredDataSources.length}
          loading={loading}
          onPageChange={setPage}
          onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        />
      )}

      {/* Modale de création de DataSource */}
      {showAddDataSourceModal && (
        <div className="fixed inset-0 bg-black/20 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 text-center animate-in fade-in-0 zoom-in-95 duration-300 border border-theme">
            {/* Icon */}
            <div className="w-16 h-16 bg-theme-secondary rounded-full flex items-center justify-center mx-auto mb-4">
              <Table className="w-8 h-8 text-theme-primary" />
            </div>

            <h3 className="text-2xl font-semibold text-theme-primary mb-4">
              {t('data.createNewDataSource')}
            </h3>

            <div className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">
                  {t('common.name')} *
                </label>
                <Input
                  type="text"
                  value={addDataSourceName}
                  onChange={(e) => setAddDataSourceName(e.target.value)}
                  placeholder={t('data.enterName')}
                  className="w-full"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-theme-primary mb-1">
                  {t('common.description')}
                </label>
                <Input
                  type="text"
                  value={addDataSourceDescription}
                  onChange={(e) => setAddDataSourceDescription(e.target.value)}
                  placeholder={t('data.enterDescription')}
                  className="w-full"
                />
              </div>
            </div>

            <div className="flex gap-3 mt-6">
              <Button
                onClick={() => {
                  setShowAddDataSourceModal(false);
                  setAddDataSourceName('');
                  setAddDataSourceDescription('');
                }}
                disabled={isAddingDataSource}
                variant="outline"
                className="flex-1"
              >
                {t('common.cancel')}
              </Button>
              <Button
                onClick={createNewDataSource}
                disabled={!addDataSourceName.trim() || isAddingDataSource}
                variant="default"
                className="flex-1"
              >
                {isAddingDataSource ? t('data.creating') : t('data.createDataSource')}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Modale de confirmation de suppression */}
      <BulkDeleteModal
        isOpen={showDeleteDataSourcesModal}
        title={t('common.confirmDeletion')}
        message={t('data.deleteConfirmation', { count: deletableSelectedCount })}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteDataSourcesModal(false)}
        onConfirm={confirmDeleteDataSources}
      />

      {/* Modal de création de data source */}
      {showCreateDataModal && (
        <CreateDataSourceModal
          onClose={() => setShowCreateDataModal(false)}
          onDataSourceCreated={(dataSourceId) => {
            setShowCreateDataModal(false);
            fetchDataSources();
          }}
        />
      )}

      {publishingDataSource && (
        <PublishResourceModal
          isOpen={!!publishingDataSource}
          onClose={() => setPublishingDataSource(null)}
          resourceType="TABLE"
          resourceId={String(publishingDataSource.id)}
          resourceName={publishingDataSource.name}
          resourceDescription={publishingDataSource.description}
          onSuccess={() => {
            setPendingReviewDataSourceIds(prev => new Set(prev).add(String(publishingDataSource.id)));
            addToast({
              type: 'success',
              title: t('marketplace.agents.publishSuccess', { type: 'TABLE' }),
              message: t('marketplace.agents.publishSuccess', { type: 'TABLE' }),
            });
          }}
        />
      )}

      {unshareConfirmDataSource && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setUnshareConfirmDataSource(null)}
        >
          <div
            className="max-w-md w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-16 h-16 bg-red-100 dark:bg-red-950/40 rounded-full flex items-center justify-center mx-auto mb-4">
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
              <Button variant="outline" onClick={() => setUnshareConfirmDataSource(null)} disabled={unsharing} className="flex-1">
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

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}
