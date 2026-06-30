'use client';

import { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { createPortal } from 'react-dom';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Search, Bot, Plus, Trash2, Copy, Loader2, Globe, Clock, Webhook, CalendarClock, ArrowUpDown, Eye } from 'lucide-react';
import { PublicationStatusIcon } from '@/components/publications/PublicationStatusIcon';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { favoritesFirst, type ListSortKey, type VisibilityFilter } from '@/lib/utils/listSort';
import { useResourceFavorites } from '@/hooks/useResourceFavorites';
import { FavoriteStarButton } from '@/components/ui/FavoriteStarButton';
import { CreateAgentModal } from '@/components/chat/CreateAgentModal';
import { orchestratorApi } from '@/lib/api';
import { agentService } from '@/lib/api/orchestrator/agent.service';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { AvatarDisplay } from '@/components/agents';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
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
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import PublishAgentModal from '@/components/marketplace/PublishAgentModal';

export interface AgentRow {
  id: string;
  tenantId: string;
  name: string;
  description?: string;
  systemPrompt?: string;
  modelProvider?: string;
  modelName?: string;
  temperature?: number;
  maxTokens?: number;
  conversationId?: string | null;
  avatarUrl?: string;
  isPublic: boolean;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
  config?: Record<string, unknown>;
}

interface AgentTableProps {
  className?: string;
}

export function AgentTable({ className = '' }: AgentTableProps) {
  const t = useTranslations();
  const sidePanel = useSidePanelSafe();
  // Audit 2026-05-17 round-6 - VIEWER gate on destructive actions.
  const canMutate = useCanMutateInCurrentOrg();
  const { toasts, addToast, removeToast } = useToast();
  // Personal favorites (workspace-scoped): float favorited agents to the top and
  // paint a star on each card. Refetched on workspace switch by the hook.
  const handleFavoriteError = useCallback(() => {
    addToast({ type: 'error', title: t('common.favoriteErrorTitle'), message: t('common.favoriteErrorMessage') });
  }, [addToast, t]);
  const { favoriteIds, toggleFavorite } = useResourceFavorites('AGENT', handleFavoriteError);
  const [agents, setAgents] = useState<AgentRow[]>([]);
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(25);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Bumped on workspace switch to force a reload; the load effect keys on it (and the search term)
  // rather than on the fetch callback identity, so an unstable callback can never re-fire the load.
  const [reloadKey, setReloadKey] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const debouncedSearch = useDebouncedValue(searchQuery, 300);
  // Default order = most-recently-modified first; visibility filter = no restriction. Both are
  // applied SERVER-SIDE over the whole tenant set, so the browser loads only the page it shows.
  const [sortBy, setSortBy] = useState<ListSortKey>('lastModified');
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');
  // Publication status now ships WITH each list page (publicationStatuses envelope), so it is always
  // resolved by the time a card renders - no separate sweep, no Lock-flash gate.
  // Monotonic id so only the latest page request applies its result (out-of-order guard).
  const requestIdRef = useRef(0);
  const { selectedIds: selectedAgents, toggle: toggleAgentSelectionById, clear: clearAgentSelection } = useSelectableItems();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDeleteModal, setShowDeleteModal] = useState(false);
  const [editingAgent, setEditingAgent] = useState<AgentRow | null>(null);
  const [publishingAgent, setPublishingAgent] = useState<AgentRow | null>(null);
  const [publishedAgentIds, setPublishedAgentIds] = useState<Set<string>>(new Set());
  const [pendingReviewAgentIds, setPendingReviewAgentIds] = useState<Set<string>>(new Set());
  const [rejectedAgentReasons, setRejectedAgentReasons] = useState<Map<string, string | null>>(new Map());
  const [unsharing, setUnsharing] = useState(false);
  const [unshareConfirmAgent, setUnshareConfirmAgent] = useState<string | null>(null);
  // Trigger badges in the card footer: which agents have an active webhook and/or active schedule.
  // Resolved from a SINGLE /agents/triggers (FleetTrigger) batch for the whole workspace instead of a
  // getWebhook + getSchedule pair per VISIBLE agent (the old fan-out fired 2 calls per card, nearly
  // all 404). Same data shape as useAgentFleetState.triggersByAgent.
  const [triggersByAgent, setTriggersByAgent] = useState<Map<string, { hasWebhook: boolean; hasSchedule: boolean; cronExpression?: string; timezone?: string }>>(new Map());

  // Load ONE server page. The backend applies search (`q`), sort and the visibility filter over the
  // whole set and returns only the requested slice already enriched with each row's publication badge
  // (`publicationStatuses`), so the browser never loads more than it shows and never sweeps all
  // publications. Mirrors the tables board (/data-sources/paged: server-paginated + server-enriched).
  const fetchAgents = useCallback(async () => {
    const reqId = ++requestIdRef.current;
    try {
      setLoading(true);
      setError(null);
      const result = await agentService.getAgentsPage({
        page,
        size: pageSize,
        q: debouncedSearch,
        sort: sortBy,
        visibility: visibilityFilter,
      });
      // A newer request superseded this one - drop its (now stale) result.
      if (reqId !== requestIdRef.current) return;

      setAgents((result.items || []) as AgentRow[]);
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
      setPublishedAgentIds(published);
      setPendingReviewAgentIds(pending);
      setRejectedAgentReasons(rejected);
    } catch (err) {
      if (reqId !== requestIdRef.current) return;
      console.error('Error fetching agents:', err);
      setError('Failed to load agents');
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
  }, [page, pageSize, debouncedSearch, sortBy, visibilityFilter]);

  // Reset to page 0 when the search term, sort, or visibility filter changes.
  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, sortBy, visibilityFilter]);

  // Initial load + reload on page / size / search / sort / visibility / workspace switch. The fetch
  // callback already closes over those inputs, so keying on its identity fires exactly one page load
  // per change (the request-id guard discards any superseded in-flight result).
  useEffect(() => {
    fetchAgents();
  }, [fetchAgents, reloadKey]);

  // Phase 3 (2026-05-18) - clear local rows + acquired-status sets on
  // workspace switch. Pre-reset, the table kept showing the previous
  // workspace's agents (and "Acquired" badges) until the user changed
  // page or search. Resetting to page 0 triggers fetchAgents via the
  // page-change effect.
  useOrgScopedReset(() => {
    setAgents([]);
    setTotalCount(0);
    setPublishedAgentIds(new Set());
    setPendingReviewAgentIds(new Set());
    setRejectedAgentReasons(new Map());
    setTriggersByAgent(new Map());
    clearAgentSelection();
    setError(null);
    setPage(0);
    setReloadKey((k) => k + 1);
  });

  const handleUnshareClick = useCallback((agentId: string) => {
    setUnshareConfirmAgent(agentId);
  }, []);

  const confirmUnshare = useCallback(async () => {
    if (!unshareConfirmAgent) return;
    setUnsharing(true);
    try {
      await publicationService.unpublishAgent(unshareConfirmAgent);
      setPublishedAgentIds(prev => {
        const next = new Set(prev);
        next.delete(unshareConfirmAgent);
        return next;
      });
      addToast({ type: 'success', title: t('marketplace.agents.unpublishSuccess'), message: t('marketplace.agents.unpublishSuccess') });
    } catch (err) {
      console.error('Error unsharing agent:', err);
      addToast({ type: 'error', title: t('common.error'), message: t('common.error') });
    } finally {
      setUnsharing(false);
      setUnshareConfirmAgent(null);
    }
  }, [unshareConfirmAgent, addToast, t]);

  // The server returns the page already filtered (visibility), sorted and sliced, so render its
  // items verbatim - then float THIS page's favorites to the top (favorites-first within the page).
  const filteredAgents = useMemo(
    () => favoritesFirst(agents, (a) => a.id, favoriteIds),
    [agents, favoriteIds],
  );
  const totalPages = Math.max(1, Math.ceil(totalCount / pageSize));

  // Snap back if the active page fell out of range (e.g. a last-page deletion narrowed the total).
  useEffect(() => {
    if (!loading && page > 0 && page > totalPages - 1) setPage(Math.max(0, totalPages - 1));
  }, [loading, page, totalPages]);

  // Trigger badges (webhook / schedule) for the WHOLE workspace in ONE /agents/triggers (FleetTrigger)
  // batch, instead of a getWebhook + getSchedule pair per visible agent. The endpoint returns only
  // agents that HAVE an active webhook or enabled schedule, so any agent absent from the result simply
  // shows no badge. Best-effort; a failure leaves the footer iconless.
  const loadFleetTriggers = useCallback(async () => {
    try {
      const triggers = await agentService.getFleetTriggers();
      const next = new Map<string, { hasWebhook: boolean; hasSchedule: boolean; cronExpression?: string; timezone?: string }>();
      for (const tr of triggers) {
        if (tr.hasWebhook || tr.hasSchedule) {
          next.set(tr.agentId, {
            hasWebhook: tr.hasWebhook,
            hasSchedule: tr.hasSchedule,
            cronExpression: tr.cronExpression || undefined,
            timezone: tr.timezone || undefined,
          });
        }
      }
      setTriggersByAgent(next);
    } catch {
      // ignore - no trigger badges
    }
  }, []);

  useEffect(() => {
    if (agents.length > 0) loadFleetTriggers();
  }, [agents, loadFleetTriggers]);

  const toggleAgentSelection = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    toggleAgentSelectionById(id);
  };

  // Clone selected agents
  const cloneSelectedAgents = async () => {
    if (selectedAgents.size === 0) return;
    const idsToClone = Array.from(selectedAgents);
    try {
      for (const id of idsToClone) {
        await orchestratorApi.cloneAgent(id);
      }
      addToast({
        type: 'success',
        title: t('common.cloneSuccess'),
        message: t('common.cloneSuccess')
      });
    } catch (err) {
      console.error('Error cloning agents:', err);
      addToast({
        type: 'error',
        title: t('common.cloneError'),
        message: t('common.cloneError')
      });
    } finally {
      await fetchAgents();
      clearAgentSelection();
    }
  };

  // Delete selected agents
  const deleteSelectedAgents = () => {
    if (selectedAgents.size === 0) return;
    setShowDeleteModal(true);
  };

  const confirmDeleteAgents = async () => {
    if (selectedAgents.size === 0) return;

    const idsToDelete = Array.from(selectedAgents);

    try {
      const deletePromises = idsToDelete.map(id =>
        orchestratorApi.deleteAgent(id)
      );

      await Promise.all(deletePromises);

      setAgents(prev => prev.filter(a => !selectedAgents.has(a.id)));
      clearAgentSelection();
      setShowDeleteModal(false);
    } catch (err) {
      console.error('Error deleting selected agents:', err);
      addToast({
        type: 'error',
        title: t('common.error'),
        message: t('common.error'),
      });
    }
  };

  const handleAgentCreated = () => {
    fetchAgents();
    setShowCreateModal(false);
    setEditingAgent(null);
  };

  // Open edit modal for an agent
  const openEditModal = (agent: AgentRow) => {
    setEditingAgent(agent);
  };

  // Open agent in side panel - scoped to /app/agent so the tab disappears when the
  // user navigates to another section (matches the workflow-panel / application-panel
  // scope pattern).
  const openAgentPanel = useCallback((agent: AgentRow) => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: `agent-${agent.id}`,
      label: agent.name,
      icon: <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="sm" className="!w-4 !h-4" />,
      content: <AgentPanelContent agentId={agent.id} initialTab={AGENT_CONFIGURATION_TAB} />,
      preferredWidth: 0.35,
      pinned: true,
      scope: ['/app/agent'],
    });
  }, [sidePanel]);

  // Deep-link entry point: `/app/agent?openAgent=<id>` opens the right-side
  // panel for the target agent once the list has loaded. Used by the
  // NotificationBell rows (TRIGGER + recent-activity AGENT subjects) because
  // no per-agent page exists - the legacy /app/agent/<id> route 404s.
  // One-shot: the param is stripped via router.replace after the first match
  // so a manual reload doesn't re-pop the panel.
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const openAgentParam = searchParams.get('openAgent');
  const handledOpenAgentRef = useRef<string | null>(null);
  useEffect(() => {
    if (!openAgentParam) return;
    if (handledOpenAgentRef.current === openAgentParam) return;
    if (loading || agents.length === 0) return;
    const target = agents.find((a) => a.id === openAgentParam);
    if (!target) return;
    handledOpenAgentRef.current = openAgentParam;
    openAgentPanel(target);
    // Strip the query param so a reload doesn't re-open the panel forever.
    const next = new URLSearchParams(searchParams.toString());
    next.delete('openAgent');
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname);
  }, [openAgentParam, loading, agents, openAgentPanel, router, pathname, searchParams]);


  return (
    <div className={`space-y-4 w-full overflow-visible ${className}`}>
      {/* Header actions */}
      {(loading || totalCount > 0 || debouncedSearch.trim().length > 0) && (
        <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <Bot className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-theme-primary">{t('emptyState.agent.title')}</h2>
              {loading ? (
                <div className="h-4 w-20 bg-theme-tertiary rounded animate-pulse mt-1"></div>
              ) : (
                <p className="text-sm text-theme-secondary">{totalCount} agent{totalCount !== 1 ? 's' : ''}</p>
              )}
            </div>
          </div>
          {loading ? (
            <div className="h-8 w-28 bg-theme-tertiary rounded animate-pulse"></div>
          ) : (
            <Button
              variant="default"
              size="sm"
              onClick={() => setShowCreateModal(true)}
            >
              <Plus className="h-4 w-4 mr-1.5" />
              {t('emptyState.agent.createButton')}
            </Button>
          )}
        </div>
      )}

      {/* Search + visibility filter + sort */}
      {(totalCount > 0 || debouncedSearch.trim().length > 0) && (
        <div className="flex flex-col gap-4 md:flex-row md:items-center">
          <div className="relative flex-1 overflow-visible">
            <Search className="pointer-events-none absolute left-4 top-3.5 h-4 w-4 text-theme-secondary" />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('emptyState.agent.searchPlaceholder')}
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

      {/* Contextual actions - floating bottom-center bar (mirrors the task board). */}
      {selectedAgents.size > 0 && (
        <SelectionActionBar count={selectedAgents.size} onClear={clearAgentSelection}>
          <BulkBarButton onClick={cloneSelectedAgents}>
            <Copy className="h-3.5 w-3.5" />
            {t('common.clone')} ({selectedAgents.size})
          </BulkBarButton>
          {selectedAgents.size === 1 && (() => {
            const selectedId = Array.from(selectedAgents)[0];
            const isPublished = publishedAgentIds.has(selectedId);
            const isPendingReview = pendingReviewAgentIds.has(selectedId);
            return isPublished ? (
              <BulkBarButton variant="danger" disabled={unsharing} onClick={() => handleUnshareClick(selectedId)}>
                <Globe className="h-3.5 w-3.5" />
                {t('common.unshare')}
              </BulkBarButton>
            ) : isPendingReview ? (
              <BulkBarButton disabled className="text-amber-400 dark:text-amber-600">
                <Clock className="h-3.5 w-3.5" />
                {t('marketplace.pendingReview')}
              </BulkBarButton>
            ) : (
              <BulkBarButton onClick={() => {
                const agent = agents.find(a => selectedAgents.has(a.id));
                if (agent) setPublishingAgent(agent);
              }}>
                <Globe className="h-3.5 w-3.5" />
                {t('common.share')}
              </BulkBarButton>
            );
          })()}
          {canMutate && (
            <BulkBarButton variant="danger" onClick={deleteSelectedAgents}>
              <Trash2 className="h-3.5 w-3.5" />
              {t('emptyState.agent.deleteCount', { count: selectedAgents.size })}
            </BulkBarButton>
          )}
        </SelectionActionBar>
      )}

      {(showCreateModal || editingAgent) && (
        <CreateAgentModal
          onClose={() => {
            setShowCreateModal(false);
            setEditingAgent(null);
          }}
          onAgentCreated={handleAgentCreated}
          agent={editingAgent || undefined}
        />
      )}

      {/* Delete confirmation modal */}
      <BulkDeleteModal
        isOpen={showDeleteModal}
        title={t('emptyState.agent.deleteTitle')}
        message={t('emptyState.agent.deleteConfirmation', { count: selectedAgents.size })}
        cancelLabel={t('common.cancel')}
        confirmLabel={t('common.delete')}
        onCancel={() => setShowDeleteModal(false)}
        onConfirm={confirmDeleteAgents}
      />

      {/* Agent Cards Grid */}
      <div className="space-y-4 w-full overflow-visible">
        {loading ? (
          <CardSkeletonGrid />
        ) : filteredAgents.length === 0 ? (
          <EmptyState
            icon={<Bot className="h-8 w-8 text-theme-tertiary" />}
            title={t('emptyState.agent.noAgentsFound')}
            subtitle={totalCount === 0 && debouncedSearch.trim().length === 0
              ? t('emptyState.agent.createFirstAgent')
              : t('emptyState.agent.noMatchingAgents')}
            actions={totalCount === 0 && debouncedSearch.trim().length === 0 ? (
              <Button variant="default" onClick={() => setShowCreateModal(true)}>
                <Plus className="w-4 h-4 mr-1.5" />
                {t('emptyState.agent.createButton')}
              </Button>
            ) : undefined}
          />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {filteredAgents.map((agent) => {
              const isSelected = selectedAgents.has(agent.id);

              return (
                <div
                  key={agent.id}
                  className={`group cursor-pointer rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow ${
                    isSelected ? 'ring-2 ring-[var(--accent-primary)]' : ''
                  }`}
                  onClick={() => openAgentPanel(agent)}
                >
                  {/* Icon preview area */}
                  <div className="relative h-[120px] flex items-center justify-center overflow-hidden bg-white dark:bg-slate-900">

                    {/* Agent avatar - central icon */}
                    <div className="relative z-10">
                      <AvatarDisplay avatarUrl={agent.avatarUrl} name={agent.name} size="xl" />
                    </div>

                    {/* Selection checkbox */}
                    <div className={`absolute top-2 right-2 transition-opacity z-10 ${
                      isSelected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                    }`}>
                      <input
                        type="checkbox"
                        checked={isSelected}
                        onChange={() => {}}
                        onClick={(e) => toggleAgentSelection(agent.id, e)}
                        className="rounded border-theme cursor-pointer w-4 h-4"
                      />
                    </div>

                    {/* Favorite star - bottom-left, floats this agent to the top of the list. */}
                    <FavoriteStarButton
                      isFavorite={favoriteIds.has(agent.id)}
                      onToggle={() => toggleFavorite(agent.id)}
                    />
                  </div>

                  {/* Footer */}
                  <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex-1 min-w-0">
                        <span className="text-sm font-medium text-theme-primary truncate block">{agent.name}</span>
                        {agent.description && (
                          <p className="text-xs text-theme-muted truncate mt-0.5">{agent.description}</p>
                        )}
                        <div className="flex items-center gap-1.5 mt-0.5 text-xs text-theme-muted">
                          {agent.modelProvider && agent.modelName && (
                            <span>{agent.modelProvider}/{agent.modelName}</span>
                          )}
                          {/* Globe (shared) / Clock (in review) / X (rejected) / Lock (private).
                              Status ships with the page envelope, so it is always resolved here. */}
                          <>
                            {agent.modelProvider && agent.modelName && (
                              <span className="text-slate-300 dark:text-slate-600">·</span>
                            )}
                            <PublicationStatusIcon
                              isShared={publishedAgentIds.has(agent.id)}
                              isPending={!publishedAgentIds.has(agent.id) && !rejectedAgentReasons.has(agent.id) && pendingReviewAgentIds.has(agent.id)}
                              isRejected={!publishedAgentIds.has(agent.id) && rejectedAgentReasons.has(agent.id)}
                              rejectionReason={rejectedAgentReasons.get(agent.id)}
                              showPrivate
                            />
                          </>
                          {/* Trigger badges - small webhook / schedule icons so the user
                              can see at a glance which agents are auto-fired without
                              opening the side panel. Click is a no-op (the parent card
                              click opens the panel anyway). */}
                          {(() => {
                            const trg = triggersByAgent.get(agent.id);
                            if (!trg) return null;
                            return (
                              <>
                                <span className="text-slate-300 dark:text-slate-600">·</span>
                                {trg.hasWebhook && (
                                  <span title="Webhook trigger active" aria-label="Webhook">
                                    <Webhook className="h-3 w-3" />
                                  </span>
                                )}
                                {trg.hasSchedule && (
                                  <span
                                    title={trg.cronExpression
                                      ? `Schedule: ${trg.cronExpression}${trg.timezone ? ' (' + trg.timezone + ')' : ''}`
                                      : 'Schedule trigger active'}
                                    aria-label="Schedule"
                                  >
                                    <CalendarClock className="h-3 w-3" />
                                  </span>
                                )}
                              </>
                            );
                          })()}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {!loading && totalCount > pageSize && (
        <PaginationBar
          page={page}
          pageSize={pageSize}
          totalCount={totalCount}
          visibleCount={filteredAgents.length}
          loading={loading}
          onPageChange={setPage}
          onPageSizeChange={(s) => { setPageSize(s); setPage(0); }}
        />
      )}

      {publishingAgent && (
        <PublishAgentModal
          isOpen={!!publishingAgent}
          onClose={() => setPublishingAgent(null)}
          agentId={publishingAgent.id}
          agentName={publishingAgent.name}
          agentDescription={publishingAgent.description}
          agentAvatarUrl={publishingAgent.avatarUrl}
          onSuccess={() => {
            setPublishedAgentIds(prev => new Set(prev).add(publishingAgent.id));
            addToast({
              type: 'success',
              title: t('marketplace.agents.publishSuccess'),
              message: t('marketplace.agents.publishSuccess'),
            });
          }}
        />
      )}

      {/* Unshare confirmation modal */}
      {unshareConfirmAgent && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setUnshareConfirmAgent(null)}
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
              <Button variant="outline" onClick={() => setUnshareConfirmAgent(null)} disabled={unsharing} className="flex-1">
                {t('common.cancel')}
              </Button>
              <Button variant="destructive" onClick={confirmUnshare} disabled={unsharing} className="flex-1">
                {unsharing && <Loader2 className="h-4 w-4 animate-spin mr-1.5" />}
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
