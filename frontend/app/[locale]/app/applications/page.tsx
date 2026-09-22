'use client';

import { useState, useEffect, useMemo, useCallback } from 'react';
import { useRouter } from '@/i18n/navigation';
import { AppWindow, Package, Search, Store, CheckCircle, EyeOff, Trash2, Share2, ArrowUpDown, Eye, Pencil } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { favoriteService } from '@/lib/api/orchestrator/favorite.service';
import type { WorkflowPublication, AcquiredApplication, WorkflowRelations } from '@/lib/api/orchestrator/types';
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
import {
  processApps,
  filterApps,
  filterByVisibility,
  nodeTypeFacets as computeNodeTypeFacets,
  type AppSortKey,
  type AppSourceFilter,
  type AppVisibilityFilter,
} from './applicationSort';
import { NodeTypeFilter } from '@/components/NodeTypeFilter';
import { deriveFavoritePubIds, favoriteTargetFor } from './applicationFavorites';
import { FolderPlus } from 'lucide-react';
import { ApplicationFolderFace, APPLICATION_FACE_CELLS } from '@/components/folders/ApplicationFolderFace';
import type { ShowcaseAppInput } from '@/lib/applications/showcasePreview';
import { FolderBreadcrumb } from '@/components/folders/FolderBreadcrumb';
import { FolderTilesGrid } from '@/components/folders/FolderTilesGrid';
import { FolderDialogs } from '@/components/folders/FolderDialogs';
import { FolderDragContext } from '@/components/folders/FolderDragContext';
import { DraggableResourceCard } from '@/components/folders/DraggableResourceCard';
import { useListFolders } from '@/hooks/useListFolders';
import { resourceFolderService, type ResourceFolder } from '@/lib/api/orchestrator/resource-folder.service';
import { buildFolderTiles, buildFolderTrail } from '@/lib/folders/buildFolderTiles';

import { ApplicationCard, PublicationCardSkeleton, type AppSource } from '@/components/applications/ApplicationCard';
import { track } from '@/lib/analytics/analytics';

// ============== Page Content ==============

/**
 * How many folder-preview applications one level will render LIVE at most: four whole faces'
 * worth. Past it a tile draws its apps' initials instead of their running pages.
 */
const FOLDER_SHOWCASE_BUDGET = APPLICATION_FACE_CELLS * 4;

function ApplicationsPageContent() {
  const t = useTranslations('applications');
  const tCommon = useTranslations('common');
  // The folder wording is shared by the five lists that have folders.
  const tFolders = useTranslations('folders');
  const router = useRouter();
  const { isLoading: isAuthLoading } = useAuth();
  // The applications page is the union (deduped by publication id) of two backend
  // streams: my-publications + acquired-applications. Each has its own totalCount
  // and pagination on the server, but the displayed list must dedup overlapping
  // entries (a user who acquired their own publication appears once). We can't
  // paginate server-side without a unified backend endpoint, so we load both
  // streams with a large size and paginate the merged set client-side.
  const FETCH_LIMIT = 100; // backend max
  const [allItems, setAllItems] = useState<{ pub: WorkflowPublication; source: AppSource; workflowId?: string; acquiredAt?: string; applicationRunId?: string; pinnedVersion?: number | null; lastExecutedAt?: string;
    budgetCredits?: number | null; budgetPeriodMode?: string | null; budgetPeriodSpent?: number | null;
    budgetPeriodResetsAt?: string | null }[]>([]);
  // Sub-workflow neighbourhood per application workflow, resolved for the whole grid in one
  // request (see fetchApplications). Absent id = no relation, and the card shows no indicator.
  const [relationsByWorkflow, setRelationsByWorkflow] = useState<Record<string, WorkflowRelations>>({});
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
  // Node-type filter. Client-side like every other refinement on this page: the
  // union of published + acquired apps is already fully loaded here, so both the
  // filter and its option counts are exact without another round-trip.
  const [nodeTypeFilter, setNodeTypeFilter] = useState<string[]>([]);
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

      // Sub-workflow neighbourhood per card, keyed by the same resolved workflowId. One request
      // for the whole grid, and deliberately NOT part of the await below: a card reads perfectly
      // without its relations indicator, so this must be unable to delay the grid or fail it.
      workflowService.getWorkflowRelationsBatch(workflowIds)
        .then(setRelationsByWorkflow)
        .catch(() => { /* best-effort: the cards simply show no relations indicator */ });

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
        return {
          ...item, pub, applicationRunId, pinnedVersion, lastExecutedAt,
          // Rides the same batch: no extra request for what an app is costing.
          budgetCredits: meta?.budgetCredits ?? null,
          budgetPeriodMode: meta?.budgetPeriodMode ?? null,
          budgetPeriodSpent: meta?.budgetPeriodSpent ?? null,
          budgetPeriodResetsAt: meta?.budgetPeriodResetsAt ?? null,
        };
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
  }, [debouncedSearch, sourceFilter, visibilityFilter, sortBy, nodeTypeFilter]);

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

  // FOLDERS (V452). This page is the one that builds its own tiles: it merges its published
  // apps with the ones it acquired and enriches them before showing anything, so the whole
  // set only exists here - and a tile computed from a slice of it would lie about what a
  // folder holds. The folders and the filing map come from the server; the counting,
  // previewing and ordering are the shared client twin of the server's builder.
  const [folderRows, setFolderRows] = useState<ResourceFolder[]>([]);
  const [memberships, setMemberships] = useState<Map<string, string>>(new Map());
  const searching = debouncedSearch.trim().length > 0;
  // The user narrowed the list themselves, by any means. Drives the empty state:
  // 'nothing matched' rather than 'you have nothing here'.
  const refining = searching || nodeTypeFilter.length > 0;

  const loadFolderState = useCallback(async () => {
    try {
      const [rows, map] = await Promise.all([
        resourceFolderService.list('application'),
        resourceFolderService.memberships('application'),
      ]);
      setFolderRows(rows);
      setMemberships(map);
    } catch (err) {
      console.error('Error loading application folders:', err);
    }
  }, []);

  const folders = useListFolders({
    kind: 'application',
    reload: loadFolderState,
    selectedIds: selectedItems,
    clearSelection,
    searching,
    canMutate: true,
    labels: {
      actionFailed: tFolders('actionFailed'),
      createFailed: tFolders('createFailed'),
      renameFailed: tFolders('renameFailed'),
      deleteFailed: tFolders('deleteFailed'),
      moveFailed: tFolders('moveFailed'),
      moved: tFolders('moved'),
      movedToFolder: (count, name) => tFolders('movedToFolder', { count, name }),
      movedToTopLevel: (count) => tFolders('movedToTopLevel', { count }),
    },
    notify: addToast,
    // Card ids carry their provenance ("acquired-<id>"); the filing is by publication id.
    toResourceId: (cardId) => cardId.replace(/^(acquired|published)-/, ''),
  });

  useEffect(() => {
    loadFolderState();
  }, [loadFolderState, currentOrgId]);

  const processed = useMemo(
    () => processApps(allItems, sourceFilter, visibilityFilter, sortBy, favoritePubIds, nodeTypeFilter),
    [allItems, sourceFilter, visibilityFilter, sortBy, favoritePubIds, nodeTypeFilter],
  );

  // Counted over the set the node-type filter applies to, with the filter itself
  // left out - so an option keeps its count while it is ticked instead of every
  // other option dropping to zero the moment you pick one.
  //
  // The folder level counts too, unless a search is active (which looks
  // everywhere): inside a folder, an option whose apps all live elsewhere would
  // otherwise advertise a count and then produce an empty grid - the one thing
  // the picker promises cannot happen.
  //
  // The provenance/visibility filters only, not processApps: counting does not
  // care about order, so sorting and floating favorites here would be work
  // thrown away on every render.
  const nodeTypeFacets = useMemo(() => {
    const scoped = filterByVisibility(filterApps(allItems, sourceFilter), visibilityFilter);
    const atThisLevel = searching
      ? scoped
      : scoped.filter((app) => (memberships.get(app.pub.id) ?? null) === folders.currentFolderId);
    return computeNodeTypeFacets(atThisLevel);
  }, [allItems, sourceFilter, visibilityFilter, searching, memberships, folders.currentFolderId]);

  // A search looks through every folder; otherwise the level narrows the list.
  const atLevel = useMemo(() => {
    if (searching) return processed;
    return processed.filter(
      (app) => (memberships.get(app.pub.id) ?? null) === folders.currentFolderId,
    );
  }, [processed, searching, memberships, folders.currentFolderId]);

  // Tiles + trail for the level being shown, fed to the shared hook so the tile grid, the
  // breadcrumb and the dialogs work exactly as they do on the four server-fed lists.
  useEffect(() => {
    folders.applyListResponse({
      folders: searching ? [] : buildFolderTiles({
        folders: folderRows,
        parentFolderId: folders.currentFolderId,
        items: processed.map((app) => ({
          id: app.pub.id,
          name: app.pub.title,
          lastModifiedAt: app.pub.updatedAt ?? app.pub.publishedAt ?? null,
          lastActivityAt: app.lastExecutedAt ?? null,
        })),
        memberships,
        sort: sortBy === 'name' ? 'name' : sortBy === 'execution' ? 'lastActivity' : 'lastModified',
      }),
      folderTrail: buildFolderTrail(folderRows, folders.currentFolderId),
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folderRows, memberships, processed, searching, sortBy, folders.currentFolderId]);

  const folderCountLabel = useCallback(
    (count: number) => tFolders('applicationCount', { count }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  // A folder tile previews the apps it holds by RENDERING them, the way their cards do, so
  // it needs the same app rows the cards get - the tile itself only carries ids and names.
  const appsById = useMemo(() => {
    const byId = new Map<string, ShowcaseAppInput>();
    for (const item of allItems) {
      byId.set(item.pub.id, {
        publication: item.pub,
        source: item.source,
        applicationRunId: item.applicationRunId,
      });
    }
    return byId;
  }, [allItems]);
  /**
   * The apps whose folder tile is allowed to run a live showcase, bounded for the whole level.
   *
   * <p>Each preview is a sandboxed iframe running a real page, and a level can show a lot of
   * folders: without a ceiling, a folder-heavy page would mount dozens at once on top of the
   * ones the cards already run. The pages list bounds itself the same way.
   *
   * <p>Whole tiles, never part of one: a face showing two running apps beside two initials
   * would read as two apps that failed to load. Once the budget cannot take a whole face, that
   * folder and the ones after it draw their apps' initials instead - the footer still says how
   * many each holds. A publication carries no cover image, so the initial is all there is to
   * fall back to, which is the reason to spend the budget on the first folders rather than
   * spreading it thin.
   */
  const liveShowcaseIds = useMemo(() => {
    const allowed = new Set<string>();
    for (const tile of folders.tiles) {
      const face = (tile.preview ?? []).slice(0, APPLICATION_FACE_CELLS);
      if (allowed.size + face.length > FOLDER_SHOWCASE_BUDGET) break;
      for (const item of face) allowed.add(item.id);
    }
    return allowed;
  }, [folders.tiles]);

  const resolveFolderApp = useCallback(
    (id: string) => (liveShowcaseIds.has(id) ? appsById.get(id) : undefined),
    [appsById, liveShowcaseIds],
  );

  const totalCount = atLevel.length;
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));
  const safePage = Math.min(page, totalPages - 1);
  const filtered = useMemo(() => {
    const from = safePage * pageSize;
    const to = from + pageSize;
    return atLevel.slice(from, to);
  }, [atLevel, safePage, pageSize]);

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
    track('application_opened', {
      publication_id: item.pub.id,
      source: item.source,
      publication_type: item.pub.publicationType ?? null,
      display_mode: item.pub.displayMode ?? null,
      has_workflow: Boolean(item.workflowId),
    });
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
    // The drag surface covers the header too: the folder path in it is a drop target, so a
    // card can be dragged out of a folder onto the level it belongs to.
    <FolderDragContext
      folders={folders}
      nameOf={(id) => allItems.find((a) => id.endsWith(a.pub.id))?.pub.title}
    >
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          {/* Header */}
          <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
            {/* Inside a folder the PATH is the page title, with the folder mark and an
                up-one-level arrow beside it - the same header the Files browser uses. */}
            {folders.trail.length > 0 ? (
              <FolderBreadcrumb
                trail={folders.trail}
                rootLabel={tFolders('allApplications')}
                backLabel={tFolders('upOneLevel')}
                subtitle={tFolders('applicationCount', { count: atLevel.length })}
                onNavigate={folders.navigateToFolder}
                droppable={folders.canOrganize}
              />
            ) : (
              <div className="min-w-0">
                <h1 className="text-lg font-semibold text-theme-primary">
                  {t('title')}
                </h1>
                <p className="text-sm text-theme-secondary mt-0.5">
                  {t('subtitle')}
                </p>
              </div>
            )}
            {folders.foldersEnabled && (
              <Button
                variant="outline"
                size="sm"
                className="shrink-0"
                onClick={() => folders.setShowCreateDialog(true)}
              >
                <FolderPlus className="h-4 w-4 mr-1.5" />
                {tFolders('newFolder')}
              </Button>
            )}
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
                {/* Provenance filter - All / Installed (acquired) / Published (own).
                    Drawn as the app's own Button, chosen = solid accent / not chosen =
                    outline, the same pairing the generation history filter row uses. A
                    hand-rolled pill (`rounded-md`, its own padding, `--bg-primary` as the
                    text colour on the accent instead of `--accent-foreground`) was a
                    second button shape on a row that already carries two selects at the
                    standard control height. */}
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
                      <Button
                        key={value}
                        type="button"
                        variant={isActive ? 'default' : 'outline'}
                        size="sm"
                        aria-pressed={isActive}
                        onClick={() => setSourceFilter(value)}
                      >
                        {icon}
                        {label}
                      </Button>
                    );
                  })}
                </div>

                {/* Visibility filter + Sort - both use the standard rounded-xl
                    bordered select shape (not a custom pill) so they conform to the
                    select used everywhere else in the app. */}
                <div className="flex items-center gap-2">
                  {/* Node types - narrows to the apps built with a given integration or node */}
                  <NodeTypeFilter
                    facets={nodeTypeFacets}
                    value={nodeTypeFilter}
                    onChange={setNodeTypeFilter}
                  />

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
              {/* Filing stays available while searching: you often find an app BECAUSE you
                  were looking for where to put it. Only the drag targets need a folder view. */}
              <BulkBarButton onClick={folders.openMoveDialog}>
                <FolderPlus className="h-3.5 w-3.5" />
                {tFolders('moveToFolder')}
              </BulkBarButton>
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
          {/* A level holding folders is not empty - the other four lists already gate on their
              tiles, and without it this page tells you it has no applications directly above
              the folder holding them. */}
          {!isLoading && filtered.length === 0 && folders.tiles.length === 0 && !error && (
            /* `refining`, not just the search: a node-type filter empties the list the
               same way, so it must read the same way - 'nothing matched', not 'you have
               no applications' with a marketplace button under it. */
            <EmptyState
              icon={<AppWindow className="h-7 w-7 text-theme-muted" />}
              title={refining ? t('noSearchResults') : t('empty')}
              subtitle={refining ? t('tryDifferentSearch') : t('emptyHint')}
              size="md"
              actions={!refining ? (
                <Button
                  variant="default"
                  onClick={() => router.push('/app/marketplace')}
                >
                  <Store className="h-3.5 w-3.5" />
                  {t('browseMarketplace')}
                </Button>
              ) : undefined}
            />
          )}

          {/* Folders + grid. Both are drop targets of the surrounding drag context: a card
              dropped on a tile is filed there, a tile dropped on another tile is nested. */}
          {!isLoading && (
            <>
              <FolderTilesGrid
                folders={folders}
                countLabel={folderCountLabel}
                gridClassName="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4 mb-4"
                renderFace={(folder) => (
                  <ApplicationFolderFace preview={folder.preview ?? []} resolveApp={resolveFolderApp} />
                )}
              />

              {filtered.length > 0 && (
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              {filtered.map(({ pub, source, workflowId, acquiredAt, applicationRunId, pinnedVersion,
                              budgetCredits, budgetPeriodMode, budgetPeriodSpent,
                              budgetPeriodResetsAt }) => {
                const cardId = source === 'acquired' ? `acquired-${pub.id}` : `published-${pub.id}`;
                return (
                  <DraggableResourceCard key={cardId} id={cardId} disabled={!folders.canOrganize}>
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
                    budgetCredits={budgetCredits}
                    budgetPeriodMode={budgetPeriodMode}
                    budgetPeriodSpent={budgetPeriodSpent}
                    budgetPeriodResetsAt={budgetPeriodResetsAt}
                    isFavorite={favoritePubIds.has(pub.id)}
                    onToggleFavorite={() => handleToggleFavorite({ pub, source, workflowId })}
                    /* Same resolution the run/version batch used: the acquired clone when there is
                       one, else the published workflow. A remote (cloud-hosted) app has no local
                       workflow, so it gets no relations - and never a row of dead entries. */
                    workflowId={pub.remote ? undefined : (workflowId ?? pub.workflowId)}
                    relations={relationsByWorkflow[workflowId ?? pub.workflowId ?? '']}
                  />
                  </DraggableResourceCard>
                );
              })}
            </div>
              )}

            </>
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
      <FolderDialogs folders={folders} selectedIds={selectedItems} />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
    </FolderDragContext>
  );
}

export default function ApplicationsPage() {
  return <ApplicationsPageContent />;
}
