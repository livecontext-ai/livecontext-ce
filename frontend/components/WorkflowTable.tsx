'use client';

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useRouter } from '@/i18n/navigation';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Search, Plus, Trash2, Workflow as WorkflowIcon, Clock, Copy, Globe, Lock, AlertTriangle, ArrowUpDown, Eye, Pencil } from 'lucide-react';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { favoritesFirst, type ListSortKey, type VisibilityFilter } from '@/lib/utils/listSort';
import { useResourceFavorites } from '@/hooks/useResourceFavorites';
import { FavoriteStarButton } from '@/components/ui/FavoriteStarButton';
import { WorkflowNodeIcons } from './WorkflowNodeIcons';
import { NodeTypeFilter } from './NodeTypeFilter';
import type { NodeTypeFacet } from '@/lib/api/orchestrator/types';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
import { orchestratorApi, Workflow } from '@/lib/api';
import { CreateWorkflowModal } from './chat/CreateWorkflowModal';
import { EditMetadataModal } from '@/components/app/EditMetadataModal';
import { BudgetChip } from '@/components/budget/BudgetChip';
import { budgetChipHasContent } from '@/components/budget/budgetPeriod';
import { useTranslations } from 'next-intl';
import { useSelectableItems } from '@/hooks/useSelectableItems';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { SelectionActionBar, BulkBarButton } from '@/components/ui/SelectionActionBar';
import { EmptyState } from '@/components/ui/EmptyState';
import { CardSkeletonGrid } from '@/components/ui/CardSkeletonGrid';
import { PaginationBar } from '@/components/ui/PaginationBar';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { createEmptyWorkflowPlan } from '@/lib/workflows/defaultWorkflowPlan';
import { TemplateGallery } from '@/components/templates/TemplateGallery';
import { WorkflowRelationsMenu } from '@/components/workflow/relations/WorkflowRelationsMenu';
import { ResourceInfoPopover } from '@/components/resource-info/ResourceInfoPopover';
import { loadWorkflowEditors } from '@/lib/api/orchestrator/version.service';
import type { WorkflowRelations } from '@/lib/api/orchestrator/types';
import { FolderPlus } from 'lucide-react';
import { WorkflowFolderFace } from '@/components/folders/WorkflowFolderFace';
import { FolderBreadcrumb } from '@/components/folders/FolderBreadcrumb';
import { FolderTilesGrid } from '@/components/folders/FolderTilesGrid';
import { FolderDialogs } from '@/components/folders/FolderDialogs';
import { FolderDragContext } from '@/components/folders/FolderDragContext';
import { DraggableResourceCard } from '@/components/folders/DraggableResourceCard';
import { useListFolders } from '@/hooks/useListFolders';
import { useResourceRowsDeleted } from '@/lib/resources/resourceDeleted';



interface WorkflowTableProps {
  className?: string;
  onWorkflowClick?: (workflow: Workflow) => void;
  onAnalyzeClick?: () => void; // Callback to close modal before redirecting
  onAddAnalyzeBadges?: (ids: string[], type: 'data' | 'workflow') => void; // Callback to add badges directly
}

export default function WorkflowTable({
  className = '',
  onWorkflowClick,
  onAnalyzeClick,
  onAddAnalyzeBadges
}: WorkflowTableProps) {
  const router = useRouter();
  const { toasts, addToast, removeToast } = useToast();
  const t = useTranslations();
  // Audit 2026-05-17 round-4 - gate destructive actions in org workspaces
  // where the caller is VIEWER. Personal workspace + non-VIEWER org roles
  // both return true.
  const canMutate = useCanMutateInCurrentOrg();

  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Bumped on workspace switch to force a reload; the load effect keys on it (and the search term)
  // rather than on the fetch callback identity, so an unstable callback can never re-fire the load.
  const [reloadKey, setReloadKey] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearch = useDebouncedValue(searchQuery, 300);
  // Default order = most-recently-modified first (preserves the list's long-standing ordering);
  // visibility filter = no restriction. Both are applied SERVER-SIDE over the whole tenant set,
  // so the browser loads only the page it shows (no fetch-all).
  const [sortBy, setSortBy] = useState<ListSortKey>('lastModified');
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');
  /**
   * Node-type filter (server-side, ANY-of) and the options offered for it. The
   * facets arrive with each page rather than from a call of their own, so the
   * counts can never describe a different set than the rows on screen.
   */
  const [nodeTypeFilter, setNodeTypeFilter] = useState<string[]>([]);
  const [nodeTypeFacets, setNodeTypeFacets] = useState<NodeTypeFacet[]>([]);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [totalCount, setTotalCount] = useState(0);
  // Monotonic id so only the latest page request applies its result (out-of-order guard).
  const requestIdRef = useRef(0);
  const { selectedIds: selectedWorkflows, toggle: toggleWorkflowSelection, clear: clearWorkflowSelection, selectAll: selectAllWorkflowIds } = useSelectableItems();
  const [showDeleteWorkflowsModal, setShowDeleteWorkflowsModal] = useState(false);
  const [isAddingWorkflowInline, setIsAddingWorkflowInline] = useState(false);
  const [isAddingWorkflow, setIsAddingWorkflow] = useState(false);
  const [newWorkflowName, setNewWorkflowName] = useState('');
  const [newWorkflowDescription, setNewWorkflowDescription] = useState('');
  const [showCreateWorkflowModal, setShowCreateWorkflowModal] = useState(false);
  // The workflow the bulk bar's Update button is editing, or null. Holds the
  // ROW, not just its id, so the modal opens on the values the list already has
  // (name, description, cap) instead of flashing empty while a fetch lands.
  const [editingWorkflow, setEditingWorkflow] = useState<Workflow | null>(null);
  const [isSavingWorkflowMetadata, setIsSavingWorkflowMetadata] = useState(false);
  // Sub-workflow neighbourhood per card, resolved for the WHOLE page in one request rather than
  // one per card. A workflow absent from the map has no relation and shows no indicator.
  const [relationsByWorkflow, setRelationsByWorkflow] = useState<Record<string, WorkflowRelations>>({});

  // FOLDERS. The level being shown is a fetch parameter like the page number; the tiles and
  // the trail come back WITH the list, since that response already holds the workflows they
  // are computed from. `reloadRef` breaks the loop between the two: the hook needs a way to
  // reload, and the fetch needs the hook's level.
  const reloadRef = useRef<() => void>(() => {});
  const searching = debouncedSearch.trim().length > 0;
  const folders = useListFolders({
    kind: 'workflow',
    reload: useCallback(() => reloadRef.current(), []),
    selectedIds: selectedWorkflows,
    clearSelection: clearWorkflowSelection,
    searching,
    busy: loading,
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
    (count: number) => t('folders.workflowCount', { count }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  // Personal favorites (workspace-scoped): float favorited workflows to the top
  // and paint a star on each card. Refetched on workspace switch by the hook.
  const handleFavoriteError = useCallback(() => {
    addToast({ type: 'error', title: t('common.favoriteErrorTitle'), message: t('common.favoriteErrorMessage') });
  }, [addToast, t]);
  const { favoriteIds, toggleFavorite } = useResourceFavorites('WORKFLOW', handleFavoriteError);

  // Chargement des workflows
  // Note: tenantId is NOT used - Gateway injects X-User-ID from JWT automatically
  const fetchWorkflows = useCallback(async () => {
    const reqId = ++requestIdRef.current;
    try {
      setLoading(true);
      setError(null);
      // Load ONE server page: the backend applies search (`q`), the visibility filter and the sort
      // over the whole tenant set and returns only the requested slice, already enriched with each
      // row's publication badge. The browser never loads more than it shows. Mirrors the
      // Agent / Interface / DataSource paged lists.
      const result = await orchestratorApi.getWorkflowsPage({
        page,
        size: pageSize,
        q: debouncedSearch,
        sort: sortBy,
        visibility: visibilityFilter,
        // `root` = the workflows filed nowhere. A search overrides this server-side and
        // looks through every folder, so a name is always findable.
        folderId: folders.folderIdParam,
        includeFolders: true,
        nodeTypes: nodeTypeFilter,
        includeNodeTypeFacets: true,
      });
      // A newer request superseded this one - drop its (now stale) result.
      if (reqId !== requestIdRef.current) return;
      setWorkflows(result.workflows ?? []);
      setTotalCount(result.totalCount ?? 0);
      setNodeTypeFacets(result.nodeTypeFacets ?? []);
      folders.applyListResponse(result);
      // Sub-workflow relations for the whole page in ONE request. Deliberately NOT awaited with
      // the list: a card reads perfectly without its relations indicator, so resolving them must
      // never hold the grid back. Same request-id guard as the page itself, so a superseded page
      // cannot paint its relations over the current one.
      const pageIds = (result.workflows ?? []).map((w) => w.id).filter(Boolean);
      if (pageIds.length === 0) {
        setRelationsByWorkflow({});
      } else {
        orchestratorApi.getWorkflowRelationsBatch(pageIds)
          .then((map) => { if (reqId === requestIdRef.current) setRelationsByWorkflow(map); })
          .catch(() => { /* best-effort: the cards simply show no relations indicator */ });
      }
    } catch (err) {
      if (reqId !== requestIdRef.current) return;
      console.error('Error fetching workflows:', err);
      setError(t('workflow.failedToLoad'));
      setWorkflows([]);
      setTotalCount(0);
      setRelationsByWorkflow({});
      addToast({
        type: 'error',
        title: t('workflow.errorLoadingWorkflows'),
        message: t('workflow.failedToLoad')
      });
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
    // addToast / t are intentionally NOT deps: they are stable in production but recreated every
    // render under test mocks, and the load effect keys on this callback's identity - listing them
    // would refire the fetch on every render. Mirrors AgentTable.fetchAgents.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize, debouncedSearch, sortBy, visibilityFilter, nodeTypeFilter, folders.folderIdParam]);

  // Reset to page 0 when the search term, sort, or a filter changes - the visible set
  // differs so the current page index may be out of range.
  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, sortBy, visibilityFilter, nodeTypeFilter, folders.folderIdParam]);

  // The hook reloads through this ref, so it can be created before the fetch it triggers.
  reloadRef.current = fetchWorkflows;

  // A workflow deleted anywhere else (its side-panel tab, the edit modal, a chat card)
  // drops out of this list at once, then the page is refetched so the total and the
  // page fill come from the server rather than from what was on screen.
  useResourceRowsDeleted('workflow', workflows, setWorkflows, fetchWorkflows);

  /**
   * Save the bulk bar's Update. Same call the builder's breadcrumb makes, so a
   * workflow renamed or capped from the list and from inside it goes through
   * one endpoint.
   */
  const handleSaveWorkflowMetadata = useCallback(async (values: {
    name: string;
    description: string;
    budgetCredits?: number | null;
    budgetPeriodMode?: string | null;
  }) => {
    if (!editingWorkflow) return;
    setIsSavingWorkflowMetadata(true);
    try {
      await orchestratorApi.updateWorkflow(editingWorkflow.id, {
        name: values.name,
        description: values.description,
        budgetCredits: values.budgetCredits,
        budgetPeriodMode: values.budgetPeriodMode,
      });
      setEditingWorkflow(null);
      // The card shows every field this modal edits, so the list has to be told.
      fetchWorkflows();
      addToast({
        type: 'success',
        title: t('modals.editMetadata.successTitle'),
        message: t('modals.editMetadata.successMessage', { name: values.name }),
      });
    } catch (err) {
      console.error('Failed to update workflow:', err);
      addToast({
        type: 'error',
        title: t('modals.editMetadata.errorTitle'),
        message: t('modals.editMetadata.errorMessage'),
      });
    } finally {
      setIsSavingWorkflowMetadata(false);
    }
  }, [editingWorkflow, fetchWorkflows, addToast, t]);

  // Clone selected workflows
  const cloneSelectedWorkflows = async () => {
    if (selectedWorkflows.size === 0) return;
    const idsToClone = Array.from(selectedWorkflows);
    try {
      for (const id of idsToClone) {
        await orchestratorApi.cloneWorkflow(id);
      }
      await fetchWorkflows();
      clearWorkflowSelection();
      addToast({
        type: 'success',
        title: t('common.cloneSuccess'),
        message: t('common.cloneSuccess')
      });
    } catch (err) {
      console.error('Error cloning workflows:', err);
      addToast({
        type: 'error',
        title: t('common.cloneError'),
        message: t('common.cloneError')
      });
    }
  };

  // Suppression multiple de workflows
  const deleteSelectedWorkflows = () => {
    if (selectedWorkflows.size === 0) return;
    setShowDeleteWorkflowsModal(true);
  };

  // Confirmation de suppression des workflows
  const confirmDeleteWorkflows = async () => {
    if (selectedWorkflows.size === 0) return;

    const idsToDelete = Array.from(selectedWorkflows);
    if (idsToDelete.length === 0) return;

    const count = idsToDelete.length;

    try {
      const deletePromises = idsToDelete.map(id =>
        orchestratorApi.deleteWorkflow(id)
      );

      await Promise.all(deletePromises);

      // Fermer la modal d'abord
      setShowDeleteWorkflowsModal(false);

      // Mettre à jour les workflows et la sélection
      const deletedSet = new Set(idsToDelete);
      setWorkflows(prev => prev.filter(w => !deletedSet.has(w.id)));
      clearWorkflowSelection();

      // Rafraîchir la liste des workflows
      await fetchWorkflows();

      addToast({
        type: 'success',
        title: t('workflow.workflowsDeleted'),
        message: t('workflow.workflowsDeletedMessage', { count })
      });
    } catch (err) {
      console.error('Error deleting selected workflows:', err);
      setShowDeleteWorkflowsModal(false);
      addToast({
        type: 'error',
        title: t('workflow.errorDeletingWorkflows'),
        message: t('workflow.failedToDelete')
      });
    }
  };

  const selectAllWorkflows = () => {
    selectAllWorkflowIds(filteredWorkflows.map(w => w.id));
  };

  // Démarrer l'ajout inline de workflow
  const startAddingWorkflowInline = () => {
    setIsAddingWorkflowInline(true);
    setNewWorkflowName('');
    setNewWorkflowDescription('');
  };

  // 🇫🇷 Annuler l'ajout inline de workflow
  const cancelAddingWorkflowInline = () => {
    setIsAddingWorkflowInline(false);
    setNewWorkflowName('');
    setNewWorkflowDescription('');
  };

  // 🇫🇷 Création d'un nouveau Workflow
  const createNewWorkflow = async () => {
    if (!newWorkflowName.trim()) return;

    try {
      setIsAddingWorkflow(true);

      // Generate a UUID for the workflow
      const workflowId = crypto.randomUUID();

      const workflowPlan = createEmptyWorkflowPlan({
        id: workflowId,
        name: newWorkflowName,
        description: newWorkflowDescription || undefined,
      });

      // Prepare the request body in the format expected by the new endpoint.
      // workflowId must be a top-level field - the backend ignores plan.id
      // (WorkflowPlanParser) and would otherwise create the row under a
      // server-generated UUID.
      const requestBody = {
        planJson: JSON.stringify(workflowPlan),
        dataInputs: {},
        workflowId,
      };

      // Use orchestratorApi which goes through the gateway
      const result = await orchestratorApi.saveWorkflowPlan(requestBody);

      // Reinitialiser le formulaire
      setNewWorkflowName('');
      setNewWorkflowDescription('');
      setIsAddingWorkflowInline(false);

      // Recharger la liste des Workflows
      await fetchWorkflows();

      addToast({
        type: 'success',
        title: t('workflow.workflowCreated'),
        message: t('workflow.workflowCreatedMessage', { name: newWorkflowName })
      });
    } catch (err) {
      console.error('Error creating new workflow:', err);
      const errorMessage = err instanceof Error ? err.message : t('workflow.failedToCreate');
      addToast({
        type: 'error',
        title: t('workflow.errorCreatingWorkflow'),
        message: errorMessage
      });
    } finally {
      setIsAddingWorkflow(false);
    }
  };

  const deletableSelectedCount = selectedWorkflows.size;

  // Gestion du clic sur un workflow
  const handleWorkflowClick = (workflow: Workflow) => {
    if (onWorkflowClick) {
      onWorkflowClick(workflow);
    } else {
      router.push(`/app/workflow/${workflow.id}`);
    }
  };

  // The server returns the page already filtered (visibility), sorted and sliced, so render its
  // items verbatim - then float THIS page's favorites to the top (favorites-first within the page).
  const filteredWorkflows = useMemo(
    () => favoritesFirst(Array.isArray(workflows) ? workflows : [], (w) => w.id, favoriteIds),
    [workflows, favoriteIds],
  );
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));

  // Snap back if the active page fell out of range (e.g. a last-page deletion narrowed the total).
  useEffect(() => {
    if (!loading && page > 0 && page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [loading, page, totalPages]);

  // Chargement initial + reload on search term / workspace switch. Keyed on the inputs (not the
  // fetch callback) so an unstable `fetchWorkflows` identity never triggers a render→fetch loop.
  // Note: No tenantId check needed - Gateway injects X-User-ID from JWT
  useEffect(() => {
    fetchWorkflows();
  }, [fetchWorkflows, reloadKey]);

  // Phase 3 (2026-05-18) - clear local rows on workspace switch, then force a reload.
  useOrgScopedReset(() => {
    setWorkflows([]);
    setTotalCount(0);
    setError(null);
    setPage(0);
    setReloadKey((k) => k + 1);
  });

  return (
    // The drag surface covers the header too: the folder path in it is a drop target, so a
    // card can be dragged out of a folder onto the level it belongs to.
    <FolderDragContext folders={folders} nameOf={(id) => workflows.find((w) => w.id === id)?.name}>
    <div className={`space-y-4 w-full overflow-visible ${className}`}>
      {/* Header - Applications-style: page title + description below, ALWAYS visible so the empty
          state shows the same layout as the Applications page (title + subtitle + centered empty
          state). The create button lives in the header only when there are items; when empty the
          EmptyState carries the create CTA (so we don't show two create buttons). */}
      <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        {/* Inside a folder the PATH is the page title, with the folder mark and an
            up-one-level arrow beside it - the same header the Files browser uses. */}
        {folders.trail.length > 0 ? (
          <FolderBreadcrumb
            trail={folders.trail}
            rootLabel={t('folders.allWorkflows')}
            backLabel={t('folders.upOneLevel')}
            subtitle={t('folders.workflowCount', { count: totalCount })}
            onNavigate={folders.navigateToFolder}
            droppable={folders.canOrganize}
          />
        ) : (
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-theme-primary">{t('workflow.title')}</h1>
            <p className="text-sm text-theme-secondary mt-0.5">{t('workflow.subtitle')}</p>
          </div>
        )}
        {canMutate && !loading && (
          <div className="flex flex-wrap items-center gap-2 md:shrink-0">
            {/* Templates sit behind this button rather than in a permanent banner:
                a starting point should not cost scroll on every visit. Shown even
                when the list is empty, which is when it helps most. */}
            <TemplateGallery
              kind="workflow"
              canMutate={canMutate}
              existingNames={workflows.map((w) => w.name).filter(Boolean) as string[]}
              onWorkflowCreated={async (id) => {
                // Created while standing in a folder: file it there before leaving for
                // the builder, or it would land back at the top level.
                await folders.fileNewResource(id);
                router.push(`/app/workflow/${id}`);
              }}
              onError={(message) =>
                addToast({ type: 'error', title: t('workflow.errorCreatingWorkflow'), message })
              }
            />
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
                onClick={() => setShowCreateWorkflowModal(true)}
              >
                <Plus className="h-4 w-4 mr-1.5" />
                {t('workflow.createWorkflow')}
              </Button>
            )}
          </div>
        )}
      </div>

      {/* Search + node-type filter + visibility filter + sort - visible whenever there is data or
          an active refinement. The node-type filter has to keep the bar open on its own: it can
          legitimately return nothing, and hiding the bar would leave no way to undo it.
          Both selects use the standard Applications-page select shape. */}
      {(totalCount > 0
        || folders.tiles.length > 0
        || debouncedSearch.trim().length > 0
        || nodeTypeFilter.length > 0) && (
        <div className="flex flex-col gap-4 md:flex-row md:items-center">
          <div className="relative flex-1 overflow-visible">
            <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('workflow.searchPlaceholder')}
              className="flex w-full rounded-xl border border-theme bg-[var(--bg-primary)] px-4 text-sm text-[var(--text-primary)] ring-offset-background file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--text-secondary)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)] focus-visible:ring-offset-0 disabled:cursor-not-allowed disabled:opacity-50 pl-11"
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <NodeTypeFilter
              facets={nodeTypeFacets}
              value={nodeTypeFilter}
              onChange={setNodeTypeFilter}
            />
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
                <SelectItem value="lastExecuted">{t('common.sortLastExecuted')}</SelectItem>
                <SelectItem value="name">{t('common.sortName')}</SelectItem>
                <SelectItem value="runCount">{t('common.sortRunCount')}</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      )}

      {/* Actions contextuelles - floating bottom-center bar (mirrors the task board). */}
      {selectedWorkflows.size > 0 && (
        <SelectionActionBar count={selectedWorkflows.size} onClear={clearWorkflowSelection}>
          {/* Filing stays available while searching: you often find a workflow BECAUSE you
              were looking for where to put it. Only the drag targets need a folder view. */}
          {canMutate && (
            <BulkBarButton onClick={folders.openMoveDialog}>
              <FolderPlus className="h-3.5 w-3.5" />
              {t('folders.moveToFolder')}
            </BulkBarButton>
          )}
          {/* Update (edit) - single selection only, mirrors the agent list and
              the applications board. Editing "the selection" makes no sense for
              more than one row: the modal edits a name. */}
          {canMutate && selectedWorkflows.size === 1 && (
            <BulkBarButton onClick={() => {
              const workflow = workflows.find((w) => selectedWorkflows.has(w.id));
              if (workflow) setEditingWorkflow(workflow);
            }}>
              <Pencil className="h-3.5 w-3.5" />
              {t('common.update')}
            </BulkBarButton>
          )}
          {canMutate && (
            <BulkBarButton onClick={cloneSelectedWorkflows}>
              <Copy className="h-3.5 w-3.5" />
              {t('common.clone')} ({selectedWorkflows.size})
            </BulkBarButton>
          )}
          {canMutate && (
            <BulkBarButton variant="danger" onClick={deleteSelectedWorkflows} disabled={deletableSelectedCount === 0}>
              <Trash2 className="h-3.5 w-3.5" />
              {t('workflow.deleteCount', { count: deletableSelectedCount })}
            </BulkBarButton>
          )}
        </SelectionActionBar>
      )}

      {/* Workflow cards grid */}
      <div className="space-y-4 w-full overflow-visible">
        {loading ? (
          <CardSkeletonGrid />
        ) : (
          <>
            <FolderTilesGrid
              folders={folders}
              countLabel={folderCountLabel}
              renderFace={(folder) => <WorkflowFolderFace preview={folder.preview ?? []} />}
            />

            {filteredWorkflows.length === 0 && folders.tiles.length === 0 && !isAddingWorkflowInline ? (
          <EmptyState
            icon={<WorkflowIcon className="h-7 w-7 text-theme-muted" />}
            size="md"
            title={folders.currentFolderId ? t('folders.emptyFolderTitle') : t('workflow.noWorkflowsFound')}
            subtitle={folders.currentFolderId
              ? t('folders.emptyFolderSubtitle')
              // A node-type filter empties the list just as a search does, so it must
              // read the same way: 'nothing matched', not 'you have no workflows' with a
              // create button under it.
              : (totalCount === 0 && debouncedSearch.trim().length === 0 && nodeTypeFilter.length === 0
                ? t('workflow.createFirstWorkflow')
                : t('workflow.noMatchingWorkflows'))}
            actions={canMutate && !folders.currentFolderId && totalCount === 0 && debouncedSearch.trim().length === 0 && nodeTypeFilter.length === 0 ? (
              <Button
                variant="default"
                onClick={() => setShowCreateWorkflowModal(true)}
                className="inline-flex items-center justify-center gap-2"
              >
                <Plus className="w-4 h-4" />
                {t('workflow.createWorkflow')}
              </Button>
            ) : undefined}
          />
            ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {filteredWorkflows.map((w) => {
              // Count all nodes from plan
              let nodeCount = 0;
              if (w.plan) {
                nodeCount = (w.plan.triggers?.length || 0)
                  + (w.plan.mcps?.length || 0)
                  + (w.plan.tables?.length || 0)
                  + (w.plan.cores?.length || 0)
                  + (w.plan.agents?.length || 0)
                  + (w.plan.interfaces?.length || 0);
              }

              return (
                <DraggableResourceCard key={w.id} id={w.id} disabled={!folders.canOrganize}>
                <div
                  className="group rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow cursor-pointer"
                  onClick={() => handleWorkflowClick(w)}
                >
                  {/* Background with ReactFlow-style dots pattern */}
                  <div className="relative h-[120px] flex items-center justify-center overflow-hidden bg-slate-50 dark:bg-slate-900">
                    {/* Dots pattern - light mode */}
                    <div
                      className="absolute inset-0 dark:hidden"
                      style={{
                        backgroundImage: `radial-gradient(circle, #cbd5e1 1px, transparent 1px)`,
                        backgroundSize: '16px 16px',
                      }}
                    />
                    {/* Dots pattern - dark mode */}
                    <div
                      className="hidden dark:block absolute inset-0"
                      style={{
                        backgroundImage: `radial-gradient(circle, #475569 1px, transparent 1px)`,
                        backgroundSize: '16px 16px',
                      }}
                    />

                    {/* Node icons or fallback workflow icon */}
                    <div className="relative z-10">
                      {w.nodeIcons && w.nodeIcons.length > 0 ? (
                        <WorkflowNodeIcons nodeIcons={w.nodeIcons} totalNodeCount={nodeCount} />
                      ) : (
                        <div className="w-12 h-12 bg-theme-secondary rounded-xl flex items-center justify-center">
                          <WorkflowIcon className="w-6 h-6 text-theme-primary" />
                        </div>
                      )}
                    </div>

                    {/* Selection checkbox - visible on hover OR when checked */}
                    <div className={`absolute top-2 right-2 transition-opacity z-10 ${selectedWorkflows.has(w.id) ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
                      <input
                        type="checkbox"
                        checked={selectedWorkflows.has(w.id)}
                        onChange={() => toggleWorkflowSelection(w.id)}
                        onClick={(e) => e.stopPropagation()}
                        className="rounded border-theme cursor-pointer h-4 w-4"
                      />
                    </div>

                    {/* Favorite star - bottom-left, floats this workflow to the top of the list. */}
                    <FavoriteStarButton
                      isFavorite={favoriteIds.has(w.id)}
                      onToggle={() => toggleFavorite(w.id)}
                    />
                  </div>

                  {/* Footer */}
                  <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-theme-primary truncate">{w.name}</span>
                        {nodeCount > 0 && (
                          <span className="text-xs text-theme-muted shrink-0">
                            {nodeCount} node{nodeCount !== 1 ? 's' : ''}
                          </span>
                        )}
                      </div>
                      {w.description && (
                        <p className="text-xs text-theme-muted truncate mt-0.5">{w.description}</p>
                      )}
                      {/* ONE line, always.
                          This row accumulates: a modified date, a run count, a
                          spending figure, a live dot, a review or rejected
                          badge, a shared globe, and the relations button. What
                          made it wrap was never the flex track (`display:flex`
                          is `nowrap` already) but the TEXT inside each segment:
                          on a narrow card the date alone took two lines and
                          pushed the rest of the row down with it.

                          So `truncate` on the date is the whole mechanism: it
                          stops that text wrapping AND lets the segment's
                          automatic minimum size resolve to 0, which is what
                          allows it to give up width. Every OTHER child, the
                          relations button at the end included, is `shrink-0`,
                          so the date is the only thing that gives. That is the
                          right thing to sacrifice: it is the only segment still
                          readable when cut ("Modified 2 days...") and the only
                          one whose absence changes nothing about what the card
                          says.

                          Deliberately NOT `overflow-hidden`, for ONE reason:
                          clipping here would cut the BudgetChip's focus ring,
                          which is drawn 3px outside its box and is that
                          control's only keyboard affordance. The card root
                          clips anyway, so this buys no protection against a row
                          that genuinely cannot fit - it only moves the clip out
                          to where the ring survives it. */}
                      <div className="flex items-center gap-1 mt-1 text-xs text-theme-muted">
                        <Clock className="h-3 w-3 shrink-0" />
                        <span className="truncate">{t('workflow.modified')} {formatRelativeDate(w.updatedAt)}</span>
                        {w.runCount != null && w.runCount > 0 && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span className="shrink-0 whitespace-nowrap">{t('workflow.runCount', { count: w.runCount })}</span>
                          </>
                        )}
                        {/* What this automation is costing, where people already
                            look. Renders nothing until a production fire has
                            actually spent something. */}
                        {budgetChipHasContent(w.budgetPeriodSpent, w.budgetCredits) && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <BudgetChip
                              spent={w.budgetPeriodSpent}
                              cap={w.budgetCredits}
                              periodMode={w.budgetPeriodMode}
                              resetsAt={w.budgetPeriodResetsAt}
                            />
                          </>
                        )}
                        {w.hasActiveRun && w.pinnedVersion != null && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-emerald-600 dark:text-emerald-400">
                              <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500 animate-pulse" />
                              {t('workflow.live')}
                            </span>
                          </>
                        )}
                        {/* Publication moderation state. A shared workflow
                            awaiting admin approval shows an orange "in review"
                            chip; rejected shows red; an approved (ACTIVE)
                            publication keeps the plain "shared" Globe. Mutually
                            exclusive - publicationStatus drives the first two,
                            isPublished (= ACTIVE-only) drives the last. */}
                        {w.publicationStatus === 'PENDING_REVIEW' && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span
                              className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-amber-600 dark:text-amber-400"
                              title={t('workflow.sharedInReview')}
                            >
                              <Clock className="h-3 w-3 shrink-0" />
                              {t('workflow.inReview')}
                            </span>
                          </>
                        )}
                        {w.publicationStatus === 'REJECTED' && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span
                              className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-red-600 dark:text-red-400"
                              title={t('workflow.sharedRejected')}
                            >
                              <AlertTriangle className="h-3 w-3 shrink-0" />
                              {t('workflow.rejected')}
                            </span>
                          </>
                        )}
                        {w.isPublished && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span className="shrink-0" title={t('workflow.shared')}><Globe className="h-3 w-3" /></span>
                          </>
                        )}
                        {/* Private marker - a Lock for any workflow that is NOT publicly shared
                            and has no in-review / rejected publication (those keep their own
                            chips above). The public counterpart to the "shared" Globe. */}
                        {!w.isPublished && w.publicationStatus !== 'PENDING_REVIEW' && w.publicationStatus !== 'REJECTED' && (
                          <>
                            <span className="shrink-0 text-slate-300 dark:text-slate-600">·</span>
                            <span className="shrink-0" title={t('common.visibilityPrivate')} aria-label={t('common.visibilityPrivate')}><Lock className="h-3 w-3" /></span>
                          </>
                        )}
                        {/* Sub-workflow neighbourhood. Renders nothing unless this workflow calls
                            another or is called by one, so its presence IS the mention. */}
                        {/* The two controls of this row, pushed together to the right edge.
                            `ml-auto` belongs to the GROUP, not to either button: on a card
                            with no relations the info button would otherwise be the one
                            carrying it on some cards and not others, and the row's right
                            edge would jitter between cards in the same grid. */}
                        <div className="ml-auto flex shrink-0 items-center gap-0.5">
                          <WorkflowRelationsMenu
                            relations={relationsByWorkflow[w.id]}
                            // `shrink-0` with the rest of the row: without it this
                            // 28px button shares the shrinking with the date and
                            // is squashed out of square before the date has
                            // finished truncating.
                            className="shrink-0"
                            data-testid={`workflow-relations-${w.id}`}
                          />
                          <ResourceInfoPopover
                            // The grid keys its cards by workflow id, so this instance is
                            // never reused for another workflow. Passed anyway: the card's
                            // correctness should not rest on a key set three components up.
                            resourceKey={`workflow:${w.id}`}
                            resourceName={w.name}
                            ownerId={w.tenantId}
                            createdAt={w.createdAt}
                            updatedAt={w.updatedAt}
                            // Resolved only when this card's popover is opened - a page of
                            // 24 cards issues nothing until one is actually consulted.
                            loadEditors={() => loadWorkflowEditors(w.id)}
                            className="shrink-0"
                            data-testid={`workflow-info-${w.id}`}
                          />
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                </DraggableResourceCard>
              );
            })}
          </div>
            )}

          </>
        )}

      </div>

      {/* Pagination controls - only when the server total spans more than one page */}
      {!loading && totalCount > pageSize && (
        <PaginationBar
          page={page}
          pageSize={pageSize}
          totalCount={totalCount}
          visibleCount={filteredWorkflows.length}
          loading={loading}
          onPageChange={setPage}
          onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        />
      )}

      {/* Modale de confirmation de suppression */}
      <BulkDeleteModal
        isOpen={showDeleteWorkflowsModal}
        title={t('common.confirmDeletion')}
        message={t('workflow.deleteConfirmation', { count: deletableSelectedCount })}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteWorkflowsModal(false)}
        onConfirm={confirmDeleteWorkflows}
      />

      {/* Modal de création de workflow */}
      {editingWorkflow && (
        <EditMetadataModal
          resourceType="workflow"
          initialName={editingWorkflow.name}
          initialDescription={editingWorkflow.description || ''}
          initialBudgetCredits={editingWorkflow.budgetCredits ?? null}
          initialBudgetPeriodMode={editingWorkflow.budgetPeriodMode ?? null}
          isSaving={isSavingWorkflowMetadata}
          onClose={() => setEditingWorkflow(null)}
          onSave={handleSaveWorkflowMetadata}
        />
      )}

      {showCreateWorkflowModal && (
        <CreateWorkflowModal
          onClose={() => setShowCreateWorkflowModal(false)}
          onWorkflowCreated={async (workflowId) => {
            setShowCreateWorkflowModal(false);
            await folders.fileNewResource(workflowId);
            // Jump straight into the new workflow's builder instead of staying on the list.
            router.push(`/app/workflow/${workflowId}`);
          }}
        />
      )}

      <FolderDialogs folders={folders} selectedIds={selectedWorkflows} />

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
    </FolderDragContext>
  );
}



