'use client';

import React, { useState, useCallback, useMemo, useEffect, useRef } from 'react';
// Locale-aware router (next-intl) - the post-create redirect into the builder
// must preserve the active locale, like WorkflowTable does.
import { useRouter } from '@/i18n/navigation';
import { useTranslations } from 'next-intl';
import { FileEdit, Play, AlertCircle, PauseCircle, X, Search, ArrowUpDown, ClipboardList, AppWindow, Calendar, Zap, Plus, Store, Eye } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { Select, SelectTrigger, SelectContent, SelectItem, SelectValue } from '@/components/ui/select';
import { matchesVisibilityFilter, type VisibilityFilter } from '@/lib/utils/visibility';
import { useCanMutateInCurrentOrg } from '@/lib/stores/current-org-store';
import { useWorkflowBoard, type WorkflowBoardSource } from './useWorkflowBoard';
import { WorkflowBoardCard } from './WorkflowBoardCard';
import { PinVersionModal } from './PinVersionModal';
import { CreateWorkflowModal } from '@/components/chat/CreateWorkflowModal';
import type { WorkflowBoardColumn, WorkflowBoardCard as CardType, NodeIconData } from '@/lib/api/orchestrator/types';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

type SortField = 'name' | 'lastExecutedAt' | 'runCount' | 'updatedAt';
type ModifiedFilter = 'all' | '24h' | '7d' | '30d';
type TriggerFilter = 'all' | 'manual' | 'webhook' | 'schedule' | 'datasource' | 'chat' | 'form' | 'workflow' | 'error';

// Maps backend nodeId (from WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID) → trigger type key
const TRIGGER_NODE_ID_TO_TYPE: Record<string, TriggerFilter> = {
  'manual-trigger': 'manual',
  'webhook-trigger': 'webhook',
  'schedule-trigger': 'schedule',
  'tables-trigger': 'datasource',
  'chat-trigger': 'chat',
  'form-trigger': 'form',
  'workflows-trigger': 'workflow',
  'error-trigger': 'error',
};

function extractTriggerTypes(nodeIcons: NodeIconData[] | undefined): Set<TriggerFilter> {
  const types = new Set<TriggerFilter>();
  if (!nodeIcons) return types;
  for (const icon of nodeIcons) {
    if (icon.nodeKind !== 'entry' || !icon.nodeId) continue;
    const t = TRIGGER_NODE_ID_TO_TYPE[icon.nodeId];
    if (t) types.add(t);
  }
  return types;
}

const COLUMNS: { key: WorkflowBoardColumn; color: string; icon: typeof FileEdit }[] = [
  { key: 'draft',       color: 'text-gray-400',   icon: FileEdit },
  { key: 'production',  color: 'text-green-500',  icon: Play },
  { key: 'needsReview', color: 'text-orange-500', icon: AlertCircle },
  { key: 'paused',      color: 'text-red-500',    icon: PauseCircle },
];

const MODIFIED_THRESHOLDS: Record<ModifiedFilter, number> = {
  all: 0,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
  '30d': 30 * 24 * 60 * 60 * 1000,
};

function sortCards(cards: CardType[], sortBy: SortField): CardType[] {
  return [...cards].sort((a, b) => {
    if (sortBy === 'name') return a.name.localeCompare(b.name);
    if (sortBy === 'runCount') return b.runCount - a.runCount;
    if (sortBy === 'updatedAt') {
      const aDate = a.updatedAt ?? '';
      const bDate = b.updatedAt ?? '';
      return bDate.localeCompare(aDate);
    }
    // lastExecutedAt - most recent first, nulls last
    const aDate = a.lastExecutedAt ?? '';
    const bDate = b.lastExecutedAt ?? '';
    return bDate.localeCompare(aDate);
  });
}

/**
 * Kanban board for workflows OR applications. Both render the same 4 columns
 * (draft/production/needsReview/paused), cards and drag-drop - only the data source
 * and the header title/subtitle/icon differ (see {@link WorkflowBoardSource}).
 */
export function WorkflowKanbanBoard({ source = 'workflow' }: { source?: WorkflowBoardSource } = {}) {
  const t = useTranslations('workflowBoard');
  const tApp = useTranslations('applicationBoard');
  const tCommon = useTranslations('common');
  const router = useRouter();
  const isApp = source === 'application';
  // Audit 2026-07-02 - VIEWER role in an org workspace is read-only: dragging a
  // card to another column persists a status change, so it never starts, and both
  // header CTAs (create workflow / install application) are hidden - install ends
  // in acquire endpoints the backend 403s for VIEWER. Browsing/filtering stays.
  const canMutate = useCanMutateInCurrentOrg();
  const {
    columns, totalCount, initialLoading, errorCode, dismissError, moveCard, canDrop,
    pinRequest, closePinRequest, confirmPin, loadMore,
  } = useWorkflowBoard(source);

  const [searchQuery, setSearchQuery] = useState('');
  const [sortBy, setSortBy] = useState<SortField>('lastExecutedAt');
  const [modifiedFilter, setModifiedFilter] = useState<ModifiedFilter>('all');
  const [triggerFilter, setTriggerFilter] = useState<TriggerFilter>('all');
  // Visibility filter - narrows to own published cards by marketplace visibility (Public / Private).
  // Cards with no publication (unpublished workflows, acquired apps) have no visibility, so any
  // narrowing drops them - same single-bucket split as /app/applications.
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');
  const [dragCard, setDragCard] = useState<CardType | null>(null);
  const [dragOverColumn, setDragOverColumn] = useState<WorkflowBoardColumn | null>(null);
  const [showCreateWorkflow, setShowCreateWorkflow] = useState(false);

  // Trigger types present in the loaded data - drives the dropdown options.
  // Note: this only reflects loaded pages. Filters apply to what's been loaded so far.
  const availableTriggerTypes = useMemo(() => {
    const present = new Set<TriggerFilter>();
    for (const col of COLUMNS) {
      for (const card of columns[col.key]?.items ?? []) {
        for (const tType of extractTriggerTypes(card.nodeIcons)) {
          present.add(tType);
        }
      }
    }
    return present;
  }, [columns]);

  // Filter + sort cards client-side, only across the already-loaded pages.
  // The DB pagination feeds the column raw; filters narrow what's displayed.
  const processedColumns = useMemo(() => {
    const term = searchQuery.trim().toLowerCase();
    const threshold = MODIFIED_THRESHOLDS[modifiedFilter];
    const cutoff = threshold > 0 ? Date.now() - threshold : 0;
    const result: Record<WorkflowBoardColumn, CardType[]> = {
      draft: [], production: [], needsReview: [], paused: [],
    };
    for (const col of COLUMNS) {
      let cards = columns[col.key]?.items ?? [];
      if (term.length > 0) {
        cards = cards.filter(c =>
          c.name.toLowerCase().includes(term) ||
          (c.description?.toLowerCase().includes(term))
        );
      }
      if (cutoff > 0) {
        cards = cards.filter(c => {
          const ts = c.updatedAt ? parseUtcAware(c.updatedAt).getTime() : 0;
          return ts >= cutoff;
        });
      }
      if (triggerFilter !== 'all') {
        cards = cards.filter(c => extractTriggerTypes(c.nodeIcons).has(triggerFilter));
      }
      if (visibilityFilter !== 'all') {
        cards = cards.filter(c => matchesVisibilityFilter(c.visibility, visibilityFilter));
      }
      result[col.key] = sortCards(cards, sortBy);
    }
    return result;
  }, [columns, searchQuery, sortBy, modifiedFilter, triggerFilter, visibilityFilter]);

  const handleDragStart = useCallback((card: CardType) => {
    if (!canMutate) return; // read-only VIEWER: never arm a drag
    setDragCard(card);
  }, [canMutate]);

  const handleDragOver = useCallback((e: React.DragEvent, columnKey: WorkflowBoardColumn) => {
    if (!dragCard) return;
    if (!canDrop(dragCard, columnKey)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDragOverColumn(columnKey);
  }, [dragCard, canDrop]);

  const handleDragLeave = useCallback(() => {
    setDragOverColumn(null);
  }, []);

  const handleDrop = useCallback(async (e: React.DragEvent, columnKey: WorkflowBoardColumn) => {
    e.preventDefault();
    setDragOverColumn(null);
    // Defensive mirror of handleDragStart: a VIEWER drop must never call moveCard.
    if (!canMutate) return;
    if (!dragCard) return;
    if (!canDrop(dragCard, columnKey)) return;
    await moveCard(dragCard, columnKey);
    setDragCard(null);
  }, [canMutate, dragCard, canDrop, moveCard]);

  const handleDragEnd = useCallback(() => {
    setDragCard(null);
    setDragOverColumn(null);
  }, []);

  if (initialLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner size="sm" />
      </div>
    );
  }

  // Fatal load error - no data to show
  if (errorCode === 'loadFailed' && totalCount === 0) {
    return (
      <div className="flex items-center justify-center py-20 text-red-500 text-sm">
        {t(`errors.${errorCode}`)}
      </div>
    );
  }

  // Always render the board (header + columns) - even with zero items - so the
  // create-workflow / install-application CTA stays reachable, like the task board.
  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* Header */}
      <div className="flex-shrink-0 flex flex-col sm:flex-row sm:items-center justify-between gap-2 sm:gap-3 px-4 pt-3 pb-1">
        <div className="flex items-center gap-3 min-w-0">
          <div className="w-10 h-10 bg-theme-tertiary rounded-full flex items-center justify-center flex-shrink-0">
            {isApp
              ? <AppWindow className="h-5 w-5 text-theme-primary" />
              : <ClipboardList className="h-5 w-5 text-theme-primary" />}
          </div>
          <div className="min-w-0">
            <h3 className="text-lg font-semibold text-theme-primary">{isApp ? tApp('title') : t('title')}</h3>
            <p className="text-sm text-theme-secondary truncate hidden sm:block">
              {isApp ? tApp('subtitle', { count: totalCount }) : t('subtitle', { count: totalCount })}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2 sm:justify-end">
          <Select value={triggerFilter} onValueChange={(v) => setTriggerFilter(v as TriggerFilter)}>
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[110px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
              <Zap className="h-3 w-3 mr-1 text-theme-muted" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="all" className="text-xs rounded-md py-1.5">{t('triggerFilter.all')}</SelectItem>
              {(['manual', 'webhook', 'schedule', 'datasource', 'chat', 'form', 'workflow', 'error'] as const)
                .filter(tt => availableTriggerTypes.has(tt))
                .map(tt => (
                  <SelectItem key={tt} value={tt} className="text-xs rounded-md py-1.5">
                    {t(`triggerFilter.${tt}`)}
                  </SelectItem>
                ))}
            </SelectContent>
          </Select>
          <Select value={visibilityFilter} onValueChange={(v) => setVisibilityFilter(v as VisibilityFilter)}>
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[100px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50" aria-label={tCommon('filterByVisibility')}>
              <Eye className="h-3 w-3 mr-1 text-theme-muted" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="all" className="text-xs rounded-md py-1.5">{tCommon('visibilityAny')}</SelectItem>
              <SelectItem value="public" className="text-xs rounded-md py-1.5">{tCommon('visibilityPublic')}</SelectItem>
              <SelectItem value="private" className="text-xs rounded-md py-1.5">{tCommon('visibilityPrivate')}</SelectItem>
            </SelectContent>
          </Select>
          <Select value={modifiedFilter} onValueChange={(v) => setModifiedFilter(v as ModifiedFilter)}>
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[100px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
              <Calendar className="h-3 w-3 mr-1 text-theme-muted" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="all" className="text-xs rounded-md py-1.5">{t('filter.modifiedAll')}</SelectItem>
              <SelectItem value="24h" className="text-xs rounded-md py-1.5">{t('filter.modified24h')}</SelectItem>
              <SelectItem value="7d" className="text-xs rounded-md py-1.5">{t('filter.modified7d')}</SelectItem>
              <SelectItem value="30d" className="text-xs rounded-md py-1.5">{t('filter.modified30d')}</SelectItem>
            </SelectContent>
          </Select>
          <Select value={sortBy} onValueChange={(v) => setSortBy(v as SortField)}>
            <SelectTrigger className="min-h-0 h-7 w-auto min-w-[120px] rounded-md px-2 py-1 text-xs border-slate-200 dark:border-slate-700/50">
              <ArrowUpDown className="h-3 w-3 mr-1 text-theme-muted" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent className="rounded-lg">
              <SelectItem value="lastExecutedAt" className="text-xs rounded-md py-1.5">{t('sort.lastExecuted')}</SelectItem>
              <SelectItem value="updatedAt" className="text-xs rounded-md py-1.5">{t('sort.lastModified')}</SelectItem>
              <SelectItem value="name" className="text-xs rounded-md py-1.5">{t('sort.name')}</SelectItem>
              <SelectItem value="runCount" className="text-xs rounded-md py-1.5">{t('sort.runCount')}</SelectItem>
            </SelectContent>
          </Select>
          <div className="relative flex-1 sm:flex-none min-w-[140px] sm:min-w-0">
            <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3 w-3 text-theme-muted" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('search.placeholder')}
              className="h-7 rounded-md pl-7 pr-2 text-xs border border-slate-200 dark:border-slate-700/50 bg-transparent text-theme-primary placeholder:text-theme-muted w-full sm:w-40 focus:outline-none focus:ring-1 focus:ring-[var(--accent-primary)]"
            />
          </div>
          {/* Both CTAs are VIEWER-gated: creating a workflow mutates directly, and
              installing an application ends in the acquire endpoints, which the
              backend rejects for VIEWER (403) - showing it would dead-end. */}
          {canMutate && (
            <Button
              onClick={isApp ? () => router.push('/app/marketplace') : () => setShowCreateWorkflow(true)}
              size="sm"
              className="h-7 px-3 text-xs gap-1 flex-shrink-0"
            >
              {isApp ? <Store className="h-3 w-3" /> : <Plus className="h-3 w-3" />}
              {isApp ? tApp('actions.installApplication') : t('actions.createWorkflow')}
            </Button>
          )}
        </div>
      </div>

      {/* Dismissible error banner for move failures */}
      {errorCode && (
        <div className="flex items-center gap-2 mx-4 mt-2 px-3 py-2 rounded-lg bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 text-sm">
          <span className="flex-1">{t(`errors.${errorCode}`)}</span>
          <button type="button" onClick={dismissError} className="shrink-0 p-0.5 hover:bg-red-100 dark:hover:bg-red-900/30 rounded">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      <div className="flex gap-4 p-4 overflow-x-auto flex-1 min-h-0" onDragEnd={handleDragEnd}>
        {COLUMNS.map(col => {
          const Icon = col.icon;
          const state = columns[col.key];
          const cards: CardType[] = processedColumns[col.key] ?? [];
          const isValidDrop = dragCard ? canDrop(dragCard, col.key) : false;
          const isOver = dragOverColumn === col.key;

          return (
            <KanbanColumn
              key={col.key}
              columnKey={col.key}
              icon={<Icon className={`h-4 w-4 ${col.color}`} />}
              title={t(`columns.${col.key}`)}
              count={state?.totalCount ?? 0}
              cards={cards}
              hasMore={state?.hasMore ?? false}
              loadingMore={state?.loading ?? false}
              onLoadMore={() => loadMore(col.key)}
              isDragging={!!dragCard}
              isValidDrop={isValidDrop}
              isDragOver={isOver}
              dragCardId={dragCard?.workflowId ?? null}
              onDragStart={handleDragStart}
              onDragOver={handleDragOver}
              onDragLeave={handleDragLeave}
              onDrop={handleDrop}
            />
          );
        })}
      </div>

      {pinRequest && (
        <PinVersionModal
          card={pinRequest}
          onCancel={closePinRequest}
          onConfirm={confirmPin}
        />
      )}

      {showCreateWorkflow && (
        <CreateWorkflowModal
          onClose={() => setShowCreateWorkflow(false)}
          onWorkflowCreated={(workflowId) => {
            setShowCreateWorkflow(false);
            // Same flow as the workflow list: jump straight into the new builder.
            router.push(`/app/workflow/${workflowId}`);
          }}
        />
      )}
    </div>
  );
}

interface KanbanColumnProps {
  columnKey: WorkflowBoardColumn;
  icon: React.ReactNode;
  title: string;
  count: number;
  cards: CardType[];
  hasMore: boolean;
  loadingMore: boolean;
  onLoadMore: () => void;
  isDragging: boolean;
  isValidDrop: boolean;
  isDragOver: boolean;
  dragCardId: string | null;
  onDragStart: (card: CardType) => void;
  onDragOver: (e: React.DragEvent, columnKey: WorkflowBoardColumn) => void;
  onDragLeave: () => void;
  onDrop: (e: React.DragEvent, columnKey: WorkflowBoardColumn) => void;
}

function KanbanColumn({
  columnKey, icon, title, count, cards,
  hasMore, loadingMore, onLoadMore,
  isDragging, isValidDrop, isDragOver, dragCardId,
  onDragStart, onDragOver, onDragLeave, onDrop,
}: KanbanColumnProps) {
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  // IntersectionObserver-driven lazy load: when the bottom sentinel scrolls into view
  // (within the column's own scroll container), ask the hook for the next page.
  useEffect(() => {
    const sentinel = sentinelRef.current;
    const root = scrollRef.current;
    if (!sentinel || !root || !hasMore) return;

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting && hasMore && !loadingMore) {
            onLoadMore();
          }
        }
      },
      { root, rootMargin: '100px', threshold: 0 }
    );
    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [hasMore, loadingMore, onLoadMore]);

  const dropZoneClasses = isDragging
    ? isValidDrop
      ? isDragOver
        ? 'ring-2 ring-blue-400 bg-blue-50/30 dark:bg-blue-900/10'
        : 'ring-1 ring-dashed ring-blue-300/50 dark:ring-blue-600/30'
      : 'opacity-60'
    : '';

  return (
    <div
      className={`flex flex-col rounded-xl border border-slate-200 dark:border-slate-700/50 bg-theme-secondary/30 flex-1 min-w-[220px] min-h-0 overflow-hidden transition-all ${dropZoneClasses}`}
      onDragOver={(e) => onDragOver(e, columnKey)}
      onDragLeave={onDragLeave}
      onDrop={(e) => onDrop(e, columnKey)}
    >
      {/* Column header */}
      <div className="flex items-center gap-2 px-3 py-2 bg-theme-secondary border-b border-slate-200 dark:border-slate-700/50">
        {icon}
        <span className="text-sm font-medium text-theme-primary">{title}</span>
        <span className="ml-auto text-xs font-semibold text-theme-muted bg-theme-tertiary rounded-full px-2 py-0.5 min-w-[24px] text-center tabular-nums">
          {count}
        </span>
      </div>

      {/* Cards - scrolls independently per column */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto p-2 space-y-2">
        {cards.map(card => (
          <WorkflowBoardCard
            key={card.workflowId}
            card={card}
            isDragging={dragCardId === card.workflowId}
            onDragStart={() => onDragStart(card)}
          />
        ))}

        {/* Lazy-load sentinel + spinner */}
        {hasMore && (
          <div ref={sentinelRef} className="flex items-center justify-center py-2">
            {loadingMore && <LoadingSpinner size="xs" />}
          </div>
        )}
      </div>
    </div>
  );
}
