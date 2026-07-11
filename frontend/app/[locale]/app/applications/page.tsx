'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import { useRouter } from '@/i18n/navigation';
import { AppWindow, Package, Search, Store, CheckCircle, EyeOff, Trash2, Share2, ArrowUpDown, Eye, Pencil } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { favoriteService } from '@/lib/api/orchestrator/favorite.service';
import type { WorkflowPublication, AcquiredApplication } from '@/lib/api/orchestrator/types';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { useSelectableItems } from '@/hooks/useSelectableItems';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { EmptyState } from '@/components/ui/EmptyState';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { ShareLinkDialog } from '@/components/sharing/ShareLinkDialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ShareWorkflowModal } from '@/components/workflow';
import { processApps, type AppSortKey, type AppSourceFilter, type AppVisibilityFilter } from './applicationSort';
import { deriveFavoritePubIds, favoriteTargetFor } from './applicationFavorites';

import { ApplicationCard, PublicationCardSkeleton, type AppSource } from '@/components/applications/ApplicationCard';

// ============== Page Content ==============

function ApplicationsPageContent() {
  const t = useTranslations('applications');
  const tCommon = useTranslations('common');
  const router = useRouter();
  const { isLoading: isAuthLoading } = useAuth();
  // The applications page is the union (deduped by publication id) of two backend
  // streams: my-publications + acquired-applications. Each has its own totalCount
  // and pagination on the server, but the displayed list must dedup overlapping
  // entries (a user who acquired their own publication appears once). We can't
  // paginate server-side without a unified backend endpoint, so we load both
  // streams with a large size and paginate the merged set client-side.
  const FETCH_LIMIT = 100; // backend max
  const [allItems, setAllItems] = useState<{ pub: WorkflowPublication; source: AppSource; workflowId?: string; acquiredAt?: string; applicationRunId?: string; pinnedVersion?: number | null; lastExecutedAt?: string }[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearch = useDebouncedValue(searchQuery, 300);
  // Default ordering = most recently executed first; provenance filter = all.
  const [sortBy, setSortBy] = useState<AppSortKey>('execution');
  const [sourceFilter, setSourceFilter] = useState<AppSourceFilter>('all');
  // Visibility filter - narrows OWN published apps to Public / Private (all = no restriction).
  const [visibilityFilter, setVisibilityFilter] = useState<AppVisibilityFilter>('all');
  // Applications are workspace-scoped (owner = active org). Key the fetch on the active org so
  // switching workspace re-fetches - otherwise the previous workspace's list stays cached and its
  // cards 404 on open (the detail fetch IS org-scoped). Mirrors the quota/storage pages.
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);
  const { selectedIds: selectedItems, toggle: toggleSelection, clear: clearSelection } = useSelectableItems();
  const [showUnpublishModal, setShowUnpublishModal] = useState(false);
  const [isUnpublishing, setIsUnpublishing] = useState(false);
  const [shareOpen, setShareOpen] = useState(false);
  const [publicationToShare, setPublicationToShare] = useState<{
    publication: WorkflowPublication;
    resourceId?: string;
  } | null>(null);
  // Update (re-publish) wizard - the SAME flow as sharing a workflow, keyed by the
  // application's underlying workflowId so it loads the existing publication to edit.
  const [updateModalOpen, setUpdateModalOpen] = useState(false);
  const [appToUpdate, setAppToUpdate] = useState<{
    workflowId: string;
    title: string;
    description?: string;
  } | null>(null);
  const { toasts, addToast, removeToast } = useToast();
  // Personal favorites (workspace-scoped). TWO backing stores: PUBLISHED apps use
  // publication favorites (keyed by publication id); ACQUIRED apps favorite the local
  // CLONED workflow via native workflow favorites (keyed by the clone's workflowId) -
  // a cloud-acquired app's publication id is a remote id absent from the local
  // publications table (FK), so it can't be a publication favorite. The card star +
  // favorites-first sort read the DERIVED `favoritePubIds` computed from both stores.
  const [pubFavoriteIds, setPubFavoriteIds] = useState<Set<string>>(new Set());
  const [wfFavoriteIds, setWfFavoriteIds] = useState<Set<string>>(new Set());

  const fetchApplications = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      // Pull both streams with the backend max size + the active search term applied
      // server-side. After dedup we paginate client-side, which is the only way to
      // get a stable totalCount when the union has overlapping ids.
      // applicationOnly=true ensures standalone AGENT / TABLE / INTERFACE / SKILL
      // publications stay off this page - they belong to their own dedicated pages.
      const [acquiredRes, publishedRes] = await Promise.all([
        publicationService.getAcquiredApplicationsPage({ page: 0, size: FETCH_LIMIT, q: debouncedSearch }),
        publicationService.getMyPublicationsPage({ applicationOnly: true, page: 0, size: FETCH_LIMIT, q: debouncedSearch }),
      ]);

      const items: { pub: WorkflowPublication; source: AppSource; workflowId?: string; acquiredAt?: string }[] = [];
      const seenIds = new Set<string>();

      // When the user is BOTH publisher and acquirer of the same publication
      // (self-acquired their own published app), the dedup keeps the
      // 'published' entry but pinned_version actually lives on the acquired
      // clone (see WorkflowEntity rows: source pinned_version=NULL, clone
      // pinned_version=N). Build a lookup so the published path can use the
      // clone's workflowId for state-bearing queries (pin / version / run).
      const acquiredByPubId = new Map<string, string>();
      for (const app of acquiredRes.items || []) {
        if (app.publication?.id && app.workflowId) {
          acquiredByPubId.set(app.publication.id, app.workflowId);
        }
      }

      // Add my published apps first (backend already filters out AGENT publications)
      for (const pub of publishedRes.items || []) {
        // If the user has also acquired their own publication, prefer the
        // clone's workflowId for state queries (pinned/run).
        items.push({ pub, source: 'published', workflowId: acquiredByPubId.get(pub.id) });
        seenIds.add(pub.id);
      }

      // Add acquired apps (use the publication data, skip duplicates)
      for (const app of acquiredRes.items || []) {
        if (app.publication && !seenIds.has(app.publication.id)) {
          items.push({ pub: app.publication, source: 'acquired', workflowId: app.workflowId, acquiredAt: app.acquiredAt });
          seenIds.add(app.publication.id);
        }
      }

      // Prefer item.workflowId (acquired clone) when present so state-bearing queries (pin / version /
      // run) hit the user's own instance even on entries marked source='published'
      // (self-acquired-own-publication case - see acquiredByPubId enrichment above).
      const resolveWorkflowId = (item: typeof items[number]) =>
        item.workflowId ?? item.pub.workflowId;

      // Application run id (live preview) + last-executed (execution sort) + pinnedVersion (Live badge)
      // for EVERY card in ONE batched call keyed by workflowId, instead of two HTTP calls
      // (getApplicationRun + listVersions) PER item over the whole ~200-item union.
      const workflowIds = Array.from(new Set(
        items.map(resolveWorkflowId).filter((id): id is string => !!id),
      ));

      // Cloud-acquired apps (cloud-linked CE) carry only a MINIMAL synth publication (remote=true, no
      // showcase fields) because the source publication lives on the cloud, not locally. Enrich those
      // from the cloud-parity remote by-id proxy so the card can render the publisher's frozen showcase
      // (showcaseRunId/showcaseInterfaceId) and the real publisher/icons - otherwise canPreview is false
      // and the card shows the empty fallback tile. Local/published items skip the call. This per-item
      // remote enrichment stays (cloud-only) and runs in parallel with the run/version batch.
      const [runVersionMap, remotePubResults] = await Promise.all([
        workflowService.getApplicationRunVersionBatch(workflowIds),
        Promise.allSettled(
          items.map(item =>
            item.pub.remote
              ? publicationService.getPublicationByIdPublic(item.pub.id, /* remote */ true)
              : Promise.resolve(null),
          ),
        ),
      ]);

      const itemsWithMeta = items.map((item, i) => {
        // Merge the full cloud publication over the minimal synth for remote items, preserving
        // remote=true so the card routes its showcase read to the cloud proxy.
        const remotePubRes = remotePubResults[i];
        const pub = remotePubRes.status === 'fulfilled' && remotePubRes.value
          ? { ...item.pub, ...remotePubRes.value, remote: true }
          : item.pub;
        // Run / version come from the batch, keyed by workflowId. Absent (no run/pinned row, or the
        // batch failed) => undefined (hide the badge); present with null pinnedVersion => unpinned
        // (Inactive). Preserves the old per-item undefined-vs-null semantics.
        const wfId = resolveWorkflowId(item);
        const meta = wfId ? runVersionMap[wfId] : undefined;
        const applicationRunId = meta?.applicationRunId ?? undefined;
        const lastExecutedAt = meta?.lastExecutedAt ?? undefined;
        const pinnedVersion = meta ? (meta.pinnedVersion ?? null) : undefined;
        return { ...item, pub, applicationRunId, pinnedVersion, lastExecutedAt };
      });

      setAllItems(itemsWithMeta);
    } catch (err: any) {
      console.error('Error fetching applications:', err);
      setError(err.message || t('loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [t, debouncedSearch, currentOrgId]);

  useEffect(() => {
    if (!isAuthLoading) fetchApplications();
  }, [isAuthLoading, fetchApplications]);

  // Load the user's favorite ids (workspace-scoped). Refetch on workspace switch
  // so personal vs org favorites never bleed across workspaces. Non-fatal on error
  // (the stars just stay unfilled).
  useEffect(() => {
    if (isAuthLoading) return;
    let cancelled = false;
    Promise.all([
      publicationService.getFavoriteIds().catch(() => [] as string[]),
      favoriteService.getFavoriteIds('WORKFLOW').catch(() => [] as string[]),
    ])
      .then(([pubIds, wfIds]) => {
        if (cancelled) return;
        setPubFavoriteIds(new Set(pubIds));
        setWfFavoriteIds(new Set(wfIds));
      })
      .catch(() => { /* favorites are a non-critical enhancement */ });
    return () => { cancelled = true; };
  }, [isAuthLoading, currentOrgId]);

  // Reset to page 0 when the search term, provenance/visibility filter, or sort
  // changes - the visible set is different so the current page index may be out of range.
  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, sourceFilter, visibilityFilter, sortBy]);

  // Apply the provenance + visibility filters + sort over the deduped union, THEN
  // paginate. totalCount = size of the processed (filtered) set so pagination tracks it.
  // Pub ids considered favorited, unifying the two stores: a published app is
  // favorited when its publication id is starred; an acquired app is favorited when
  // its local cloned workflow is starred. Drives both the card star and the
  // favorites-first sort (which keys on publication id).
  const favoritePubIds = useMemo(
    () => deriveFavoritePubIds(allItems, pubFavoriteIds, wfFavoriteIds),
    [allItems, pubFavoriteIds, wfFavoriteIds],
  );

  const processed = useMemo(
    () => processApps(allItems, sourceFilter, visibilityFilter, sortBy, favoritePubIds),
    [allItems, sourceFilter, visibilityFilter, sortBy, favoritePubIds],
  );
  const totalCount = processed.length;
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const filtered = useMemo(() => {
    const from = safePage * pageSize;
    const to = from + pageSize;
    return processed.slice(from, to);
  }, [processed, safePage, pageSize]);

  // Snap back if the active page becomes out-of-range (e.g. after delete reduces totalCount).
  useEffect(() => {
    if (page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [page, totalPages]);

  // Determine if selection contains acquired apps (to show "Remove" instead of "Unpublish")
  const selectionHasAcquired = useMemo(() => {
    for (const cardId of selectedItems) {
      if (cardId.startsWith('acquired-')) return true;
    }
    return false;
  }, [selectedItems]);

  // Find selected published items for sharing (only published items can be shared)
  const selectedPublishedItems = useMemo(() => {
    return allItems.filter(({ pub, source }) => {
      const cardId = source === 'acquired' ? `acquired-${pub.id}` : `published-${pub.id}`;
      return selectedItems.has(cardId) && source === 'published';
    });
  }, [selectedItems, allItems]);

  const handleShareSelected = useCallback(async () => {
    if (selectedPublishedItems.length === 0) return;
    const selected = selectedPublishedItems[0];
    let resourceId: string | undefined;
    try {
      const appWorkflow = await publicationService.getApplicationWorkflow(selected.pub.id);
      resourceId = appWorkflow?.workflowId;
    } catch {
      resourceId = undefined;
    }
    setPublicationToShare({ publication: selected.pub, resourceId });
    setShareOpen(true);
  }, [selectedPublishedItems]);

  // Open the publish/UPDATE wizard (same as sharing a workflow) for the single
  // selected published app. Re-publishing edits the SOURCE workflow the
  // publication was created from, so the wizard must receive `pub.workflowId`:
  // it loads the existing publication via getPublicationByWorkflowId (keyed by
  // publication.workflow_id = this source workflow) and shows that workflow's
  // versions/runs. The publisher's runnable APPLICATION instance
  // (source_publication_id = pub.id) is a SEPARATE row that is NOT keyed to the
  // publication - resolving it here opened the wizard in publish-new mode and
  // hard-failed with "No workflow available" whenever that instance was absent
  // (the common case: it only exists once an app has been run/acquired).
  const handleUpdateSelected = useCallback(() => {
    if (selectedPublishedItems.length === 0) return;
    const selected = selectedPublishedItems[0];
    const workflowId = selected.pub.workflowId;
    if (!workflowId) {
      addToast({ type: 'error', title: t('loadFailed'), message: t('noWorkflow') });
      return;
    }
    setAppToUpdate({ workflowId, title: selected.pub.title, description: selected.pub.description });
    setUpdateModalOpen(true);
  }, [selectedPublishedItems, addToast, t]);

  const handleApplicationClick = useCallback((item: { pub: WorkflowPublication; source: AppSource; workflowId?: string }) => {
    // Navigate by publicationId - the application layout handles run creation from the snapshot
    router.push(`/app/applications/${item.pub.id}`);
  }, [router]);

  // Star / unstar an app. Optimistic: flip the local set immediately and revert on
  // failure so the UI stays responsive. Backend calls are idempotent. PUBLISHED apps
  // toggle the publication favorite (keyed by publication id); ACQUIRED apps toggle
  // the native workflow favorite on the local CLONE (keyed by workflowId) - the cloud
  // publication id is absent from the local publications table, so favoriting it would
  // 400/FK-fail (the original "Could not update favorites" bug for downloaded apps).
  const handleToggleFavorite = useCallback((item: { pub: WorkflowPublication; source: AppSource; workflowId?: string }) => {
    const fail = () => addToast({ type: 'error', title: t('favoriteErrorTitle'), message: t('favoriteErrorMessage') });
    const target = favoriteTargetFor(item);
    if (!target) { fail(); return; }
    if (target.kind === 'workflow') {
      const wfId = target.id;
      const was = wfFavoriteIds.has(wfId);
      setWfFavoriteIds(prev => { const n = new Set(prev); if (was) n.delete(wfId); else n.add(wfId); return n; });
      const op = was ? favoriteService.removeFavorite('WORKFLOW', wfId) : favoriteService.addFavorite('WORKFLOW', wfId);
      op.catch(() => {
        setWfFavoriteIds(prev => { const n = new Set(prev); if (was) n.add(wfId); else n.delete(wfId); return n; });
        fail();
      });
      return;
    }
    const pubId = target.id;
    const was = pubFavoriteIds.has(pubId);
    setPubFavoriteIds(prev => { const n = new Set(prev); if (was) n.delete(pubId); else n.add(pubId); return n; });
    const op = was ? publicationService.removeFavorite(pubId) : publicationService.addFavorite(pubId);
    op.catch(() => {
      setPubFavoriteIds(prev => { const n = new Set(prev); if (was) n.add(pubId); else n.delete(pubId); return n; });
      fail();
    });
  }, [wfFavoriteIds, pubFavoriteIds, addToast, t]);

  const confirmRemoveSelected = async () => {
    if (selectedItems.size === 0) return;
    setIsUnpublishing(true);
    try {
      const promises: Promise<any>[] = [];
      for (const cardId of selectedItems) {
        const item = allItems.find(({ pub, source }) => {
          const id = source === 'acquired' ? `acquired-${pub.id}` : `published-${pub.id}`;
          return id === cardId;
        });
        if (!item) continue;
        if (item.source === 'published') {
          promises.push(publicationService.unpublishWorkflow(item.pub.workflowId));
        } else if (item.source === 'acquired' && item.workflowId) {
          // Delete the cloned workflow (and its interfaces, datasources, runs)
          promises.push(workflowService.deleteWorkflow(item.workflowId));
        }
      }
      await Promise.all(promises);
      const count = selectedItems.size;
      setAllItems(prev => prev.filter(({ pub, source }) => {
        const id = source === 'acquired' ? `acquired-${pub.id}` : `published-${pub.id}`;
        return !selectedItems.has(id);
      }));
      clearSelection();
      setShowUnpublishModal(false);
      addToast({
        type: 'success',
        title: selectionHasAcquired ? t('removeSuccessTitle') : t('unpublishSuccessTitle'),
        message: selectionHasAcquired
          ? t('removeSuccessMessage', { count })
          : t('unpublishSuccessMessage', { count }),
      });
    } catch (err) {
      console.error('Error removing applications:', err);
      addToast({
        type: 'error',
        title: selectionHasAcquired ? t('removeErrorTitle') : t('unpublishErrorTitle'),
        message: selectionHasAcquired ? t('removeErrorMessage') : t('unpublishErrorMessage'),
      });
    } finally {
      setIsUnpublishing(false);
    }
  };

  return (
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          {/* Header */}
          <div>
            <h1 className="text-lg font-semibold text-theme-primary">
              {t('title')}
            </h1>
            <p className="text-sm text-theme-secondary mt-0.5">
              {t('subtitle')}
            </p>
          </div>

          {/* Search + provenance filter + visibility filter + sort. Gated on the RAW union size (not
              the filtered count) so the controls stay visible even when a filter
              currently yields zero apps - otherwise the user would be trapped. */}
          {(allItems.length > 0 || debouncedSearch.trim().length > 0) && (
            <div className="space-y-3">
              <div className="relative">
                <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
                <Input
                  type="text"
                  placeholder={t('searchPlaceholder')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full pl-11 rounded-xl bg-theme-primary border-theme text-sm"
                />
              </div>

              <div className="flex items-center justify-between gap-3 flex-wrap">
                {/* Provenance filter - All / Installed (acquired) / Published (own) */}
                <div className="flex items-center flex-wrap gap-2">
                  {(['all', 'installed', 'published'] as const).map((value) => {
                    const isActive = sourceFilter === value;
                    const label = value === 'all'
                      ? t('filterAll')
                      : value === 'installed'
                        ? t('filterInstalled')
                        : t('filterPublished');
                    const icon = value === 'installed'
                      ? <CheckCircle className="h-3.5 w-3.5" />
                      : value === 'published'
                        ? <Package className="h-3.5 w-3.5" />
                        : <AppWindow className="h-3.5 w-3.5" />;
                    return (
                      <button
                        key={value}
                        type="button"
                        onClick={() => setSourceFilter(value)}
                        className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium transition-all ${
                          isActive
                            ? 'bg-[var(--accent-primary)] text-[var(--bg-primary)]'
                            : 'bg-[var(--bg-tertiary)] text-theme-secondary hover:text-theme-primary'
                        }`}
                      >
                        {icon}
                        {label}
                      </button>
                    );
                  })}
                </div>

                {/* Visibility filter + Sort - both use the standard rounded-xl
                    bordered select shape (not a custom pill) so they conform to the
                    select used everywhere else in the app. */}
                <div className="flex items-center gap-2">
                  {/* Visibility - narrows OWN published apps to Public / Private */}
                  <Select value={visibilityFilter} onValueChange={(v) => setVisibilityFilter(v as AppVisibilityFilter)}>
                    <SelectTrigger className="w-auto gap-1.5" aria-label={t('filterByVisibility')}>
                      <Eye className="h-3.5 w-3.5 opacity-70" />
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">{t('visibilityAny')}</SelectItem>
                      <SelectItem value="public">{t('visibilityPublic')}</SelectItem>
                      <SelectItem value="private">{t('visibilityPrivate')}</SelectItem>
                    </SelectContent>
                  </Select>

                  {/* Sort - default "Last executed" */}
                  <Select value={sortBy} onValueChange={(v) => setSortBy(v as AppSortKey)}>
                    <SelectTrigger className="w-auto gap-1.5" aria-label={t('sortBy')}>
                      <ArrowUpDown className="h-3.5 w-3.5 opacity-70" />
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="execution">{t('sortExecution')}</SelectItem>
                      <SelectItem value="recent">{t('sortRecent')}</SelectItem>
                      <SelectItem value="name">{t('sortName')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
              </div>
            </div>
          )}

          {/* Selection actions - floating bottom-center bar (mirrors the task board). */}
          {selectedItems.size > 0 && (
            <SelectionActionBar count={selectedItems.size} onClear={clearSelection}>
              {selectedPublishedItems.length === 1 && (
                <>
                  <BulkBarButton onClick={handleUpdateSelected}>
                    <Pencil className="h-3.5 w-3.5" />
                    {t('update')}
                  </BulkBarButton>
                  <BulkBarButton onClick={handleShareSelected}>
                    <Share2 className="h-3.5 w-3.5" />
                    {t('shareLink')}
                  </BulkBarButton>
                </>
              )}
              <BulkBarButton variant="danger" onClick={() => setShowUnpublishModal(true)}>
                {selectionHasAcquired ? (
                  <Trash2 className="h-3.5 w-3.5" />
                ) : (
                  <EyeOff className="h-3.5 w-3.5" />
                )}
                {selectionHasAcquired
                  ? t('removeCount', { count: selectedItems.size })
                  : t('unpublishCount', { count: selectedItems.size })}
              </BulkBarButton>
            </SelectionActionBar>
          )}

          {/* Remove/Unpublish confirmation modal */}
          <BulkDeleteModal
            isOpen={showUnpublishModal}
            title={selectionHasAcquired ? t('removeTitle') : t('unpublishTitle')}
            message={selectionHasAcquired
              ? t('removeConfirmation', { count: selectedItems.size })
              : t('unpublishConfirmation', { count: selectedItems.size })}
            cancelLabel={tCommon('cancel')}
            confirmLabel={isUnpublishing
              ? (selectionHasAcquired ? t('removing') : t('unpublishing'))
              : (selectionHasAcquired ? t('removeCount', { count: selectedItems.size }) : t('unpublish'))}
            onCancel={() => setShowUnpublishModal(false)}
            onConfirm={confirmRemoveSelected}
            isConfirming={isUnpublishing}
          />

          {/* Error */}
          {error && (
            <div className="p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-600 dark:text-red-400">
              {error}
            </div>
          )}

          {/* Loading */}
          {isLoading && (
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              {/* Fill a full 4×4 grid (4 rows of 4 cols at lg) while loading. */}
              {Array.from({ length: 16 }, (_, i) => (
                <PublicationCardSkeleton key={i} />
              ))}
            </div>
          )}

          {/* Empty state */}
          {!isLoading && filtered.length === 0 && !error && (
            <EmptyState
              icon={<AppWindow className="h-7 w-7 text-theme-muted" />}
              title={debouncedSearch.trim().length > 0 ? t('noSearchResults') : t('empty')}
              subtitle={debouncedSearch.trim().length > 0 ? t('tryDifferentSearch') : t('emptyHint')}
              size="md"
              actions={debouncedSearch.trim().length === 0 ? (
                <Button
                  variant="contrast"
                  onClick={() => router.push('/app/marketplace')}
                >
                  <Store className="h-3.5 w-3.5" />
                  {t('browseMarketplace')}
                </Button>
              ) : undefined}
            />
          )}

          {/* Grid - Pinterest masonry like My Publications */}
          {!isLoading && filtered.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              {filtered.map(({ pub, source, workflowId, acquiredAt, applicationRunId, pinnedVersion }) => {
                const cardId = source === 'acquired' ? `acquired-${pub.id}` : `published-${pub.id}`;
                return (
                  <ApplicationCard
                    key={cardId}
                    publication={pub}
                    source={source}
                    isSelected={selectedItems.has(cardId)}
                    onToggleSelect={toggleSelection}
                    onCardClick={() => handleApplicationClick({ pub, source, workflowId })}
                    applicationRunId={applicationRunId}
                    acquiredAt={acquiredAt}
                    pinnedVersion={pinnedVersion}
                    isFavorite={favoritePubIds.has(pub.id)}
                    onToggleFavorite={() => handleToggleFavorite({ pub, source, workflowId })}
                  />
                );
              })}
            </div>
          )}

          {!isLoading && totalCount > pageSize && (
            <PaginationBar
              page={page}
              pageSize={pageSize}
              totalCount={totalCount}
              visibleCount={filtered.length}
              loading={isLoading}
              onPageChange={setPage}
              onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
            />
          )}
        </div>
      </div>
      <ShareLinkDialog
        open={shareOpen}
        onOpenChange={setShareOpen}
        resourceType="APPLICATION"
        resourceToken={publicationToShare?.publication.id || ''}
        resourceId={publicationToShare?.resourceId}
        resourceName={publicationToShare?.publication.title || ''}
      />
      {/* Update (re-publish) wizard - same flow as sharing a workflow. On close,
          refresh the list so any visibility/status edit reflects immediately. */}
      {appToUpdate && (
        <ShareWorkflowModal
          isOpen={updateModalOpen}
          onClose={() => {
            setUpdateModalOpen(false);
            setAppToUpdate(null);
            clearSelection();
            fetchApplications();
          }}
          workflowId={appToUpdate.workflowId}
          workflowName={appToUpdate.title}
          workflowDescription={appToUpdate.description}
        />
      )}
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}

export default function ApplicationsPage() {
  return <ApplicationsPageContent />;
}
