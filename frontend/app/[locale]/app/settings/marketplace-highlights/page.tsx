'use client';

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical, Plus, Save, Trash2, AlertCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useAuth } from '@/lib/providers/smart-providers';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type {
  HighlightDisplayMode,
  HighlightedPublicationItem,
} from '@/lib/api/orchestrator/publication.service';
import { orchestratorApi } from '@/lib/api';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { Button } from '@/components/ui/button';
import { PublicationPreview, StandardFallback } from '@/components/marketplace/PublicationCard';
import LoadingSpinner from '@/components/LoadingSpinner';
import { cn } from '@/lib/utils';
import { IS_CE } from '@/lib/edition';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

const DISPLAY_MODES: HighlightDisplayMode[] = [
  // LANDING first: it's the public-facing surface (homepage showcase). Its bucket
  // is curated independently from APPLICATION (which drives the in-chat row) but
  // holds APPLICATION-type publications - see candidatePublicationMode.
  'LANDING',
  'APPLICATION',
  'INTERFACE',
  'AGENT',
  'WORKFLOW',
  'TABLE',
  'SKILL',
];

// The publication displayMode whose candidates belong in a given highlight bucket.
// Every bucket is 1:1 with its type except LANDING, which holds APPLICATION apps.
function candidatePublicationMode(mode: HighlightDisplayMode): HighlightDisplayMode {
  return mode === 'LANDING' ? 'APPLICATION' : mode;
}

// Compact 16:10 app thumbnail for a curation row - reuses the EXACT marketplace
// preview (ShowcasePreview for showcased runs, landing-snapshot otherwise) so the
// admin sees the same visualization as the public card. Each row fetches its own
// preview, same as a marketplace card; the candidates list can be long (up to the
// page size of getMarketplacePublications), so this is a per-row fetch - acceptable
// on this admin-only, cloud-only page (low traffic, scrolled max-h container).
function RowThumbnail({ publication }: { publication: WorkflowPublication }) {
  return (
    <div
      className="relative shrink-0 w-20 overflow-hidden rounded-md border border-theme bg-theme-tertiary"
      style={{ aspectRatio: '16 / 10' }}
    >
      <PublicationPreview
        publication={publication}
        fill
        fallback={<StandardFallback publication={publication} />}
      />
    </div>
  );
}

function SortableRow({
  id,
  index,
  publication,
  onRemove,
}: {
  id: string;
  index: number;
  publication: WorkflowPublication | null;
  onRemove: () => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };
  const stale = !publication;
  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        'flex items-center gap-3 rounded-lg border px-3 py-2',
        stale
          ? 'border-amber-300 bg-amber-50 dark:border-amber-700 dark:bg-amber-900/10'
          : 'border-theme bg-theme-secondary'
      )}
    >
      <button
        type="button"
        className="cursor-grab active:cursor-grabbing text-theme-tertiary hover:text-theme-primary"
        {...attributes}
        {...listeners}
        aria-label="Drag to reorder"
      >
        <GripVertical className="h-4 w-4" />
      </button>
      <span className="text-xs text-theme-tertiary w-6 tabular-nums">{index + 1}</span>
      {publication ? (
        <RowThumbnail publication={publication} />
      ) : (
        <div
          className="relative shrink-0 w-20 rounded-md border border-amber-300 bg-amber-50 dark:border-amber-700 dark:bg-amber-900/10"
          style={{ aspectRatio: '16 / 10' }}
        />
      )}
      <div className="flex-1 min-w-0">
        {publication ? (
          <>
            <div className="text-sm font-medium text-theme-primary truncate">
              {publication.title}
            </div>
            {publication.publisherName && (
              <div className="text-xs text-theme-tertiary truncate">
                {publication.publisherName}
              </div>
            )}
          </>
        ) : (
          <div className="flex items-center gap-1 text-sm text-amber-700 dark:text-amber-400">
            <AlertCircle className="h-3.5 w-3.5" />
            Stale (publication deleted or not public) - remove
          </div>
        )}
      </div>
      <button
        type="button"
        onClick={onRemove}
        className="text-theme-tertiary hover:text-red-500 transition-colors"
        aria-label="Remove from highlights"
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </div>
  );
}

interface HighlightedRow {
  id: string;
  publication: WorkflowPublication | null;
}

export default function MarketplaceHighlightsPage() {
  const t = useTranslations('settings.marketplaceHighlights');
  const { hasRole, isLoading: authLoading } = useAuth();

  const [activeMode, setActiveMode] = useState<HighlightDisplayMode>('APPLICATION');
  const [highlighted, setHighlighted] = useState<HighlightedRow[]>([]);
  const [candidates, setCandidates] = useState<WorkflowPublication[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const loadAll = useCallback(async (mode: HighlightDisplayMode) => {
    setLoading(true);
    setError(null);
    try {
      const [adminRes, marketplaceRes] = await Promise.all([
        publicationService.getAdminHighlights(mode),
        orchestratorApi.getMarketplacePublications(0, 100),
      ]);
      const rows: HighlightedRow[] = (adminRes.highlights ?? []).map(
        (h: HighlightedPublicationItem) => ({
          id: h.publication?.id ?? `stale-${h.rank}`,
          publication: h.publication as WorkflowPublication | null,
        })
      );
      setHighlighted(rows);
      const highlightedIds = new Set(rows.map(r => r.id));
      const candMode = candidatePublicationMode(mode);
      const cand = (marketplaceRes.publications ?? [])
        .filter(p => p.displayMode === candMode && !highlightedIds.has(p.id));
      setCandidates(cand);
      setDirty(false);
    } catch (e: any) {
      setError(e?.message ?? 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (authLoading) return;
    if (!hasRole('ADMIN')) return;
    if (IS_CE) return;
    loadAll(activeMode);
  }, [activeMode, authLoading, hasRole, loadAll]);

  // Phase 6c (2026-05-19) - drop highlights, candidates, search, and
  // dirty state on workspace switch. Admin role is per-workspace, so a
  // user can lose ADMIN rights by switching; without this reset the
  // previous workspace's curated list stays in the DOM during the
  // re-render window before the role check rejects the page.
  useOrgScopedReset(() => {
    setHighlighted([]);
    setCandidates([]);
    setSearch('');
    setDirty(false);
    setError(null);
    setLoading(true);
    setActiveMode('APPLICATION');
  });

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    setHighlighted(prev => {
      const oldIndex = prev.findIndex(r => r.id === active.id);
      const newIndex = prev.findIndex(r => r.id === over.id);
      if (oldIndex < 0 || newIndex < 0) return prev;
      return arrayMove(prev, oldIndex, newIndex);
    });
    setDirty(true);
  };

  const addCandidate = (pub: WorkflowPublication) => {
    setHighlighted(prev => [...prev, { id: pub.id, publication: pub }]);
    setCandidates(prev => prev.filter(c => c.id !== pub.id));
    setDirty(true);
  };

  const removeRow = (id: string) => {
    setHighlighted(prev => {
      const removed = prev.find(r => r.id === id);
      if (removed?.publication
          && removed.publication.displayMode === candidatePublicationMode(activeMode)) {
        setCandidates(c => [removed.publication!, ...c]);
      }
      return prev.filter(r => r.id !== id);
    });
    setDirty(true);
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const orderedIds = highlighted
        .map(r => r.publication?.id)
        .filter((id): id is string => !!id);
      await publicationService.replaceHighlights(activeMode, orderedIds);
      setDirty(false);
      await loadAll(activeMode);
    } catch (e: any) {
      setError(e?.details?.error ?? e?.message ?? 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const filteredCandidates = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return candidates;
    return candidates.filter(
      c =>
        c.title.toLowerCase().includes(q) ||
        (c.publisherName ?? '').toLowerCase().includes(q)
    );
  }, [candidates, search]);

  // Guards: auth loading -> spinner; CE-mode admins see "feature unavailable"
  // BEFORE the role check so a CE deployment serves a relevant message rather
  // than 403 (the nav item is also hiddenInCE so this branch is defensive).
  if (authLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <LoadingSpinner />
      </div>
    );
  }
  if (IS_CE) {
    return (
      <div className="max-w-2xl mx-auto py-20 text-center">
        <AlertCircle className="mx-auto h-10 w-10 text-amber-500" />
        <h1 className="mt-4 text-lg font-semibold text-theme-primary">
          {t('ceUnavailable.title')}
        </h1>
        <p className="mt-2 text-sm text-theme-secondary">{t('ceUnavailable.body')}</p>
      </div>
    );
  }
  if (!hasRole('ADMIN')) {
    return (
      <div className="max-w-2xl mx-auto py-20 text-center">
        <AlertCircle className="mx-auto h-10 w-10 text-amber-500" />
        <h1 className="mt-4 text-lg font-semibold text-theme-primary">
          {t('forbidden.title')}
        </h1>
        <p className="mt-2 text-sm text-theme-secondary">{t('forbidden.body')}</p>
      </div>
    );
  }

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      <div>
        <h1 className="text-xl font-semibold text-theme-primary">{t('title')}</h1>
        <p className="mt-1 text-sm text-theme-secondary">{t('subtitle')}</p>
      </div>

      {/* DisplayMode tabs */}
      <div className="flex flex-wrap gap-1 border-b border-theme">
        {DISPLAY_MODES.map(mode => (
          <button
            key={mode}
            type="button"
            onClick={() => {
              if (dirty && !window.confirm(t('confirmDiscard'))) return;
              setActiveMode(mode);
            }}
            className={cn(
              'px-3 py-2 text-sm border-b-2 -mb-px transition-colors',
              activeMode === mode
                ? 'border-blue-500 text-theme-primary'
                : 'border-transparent text-theme-tertiary hover:text-theme-primary'
            )}
          >
            {t(`modes.${mode}`)}
          </button>
        ))}
      </div>

      {error && (
        <div className="rounded-md border border-red-300 bg-red-50 dark:border-red-700 dark:bg-red-900/20 p-3 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <LoadingSpinner />
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Curated column */}
          <section className="space-y-2">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-semibold text-theme-primary">
                {t('curated.title', { count: highlighted.length })}
              </h2>
              <Button
                size="sm"
                onClick={save}
                disabled={!dirty || saving}
                className="h-7 px-3 text-xs"
              >
                <Save className="mr-1 h-3 w-3" />
                {saving ? t('saving') : t('save')}
              </Button>
            </div>
            <p className="text-xs text-theme-tertiary">{t('curated.hint')}</p>
            {highlighted.length === 0 ? (
              <p className="text-sm text-theme-tertiary py-6 text-center border border-dashed border-theme rounded-lg">
                {t('curated.empty')}
              </p>
            ) : (
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={handleDragEnd}
              >
                <SortableContext
                  items={highlighted.map(r => r.id)}
                  strategy={verticalListSortingStrategy}
                >
                  <div className="space-y-1.5">
                    {highlighted.map((row, idx) => (
                      <SortableRow
                        key={row.id}
                        id={row.id}
                        index={idx}
                        publication={row.publication}
                        onRemove={() => removeRow(row.id)}
                      />
                    ))}
                  </div>
                </SortableContext>
              </DndContext>
            )}
          </section>

          {/* Candidates column */}
          <section className="space-y-2">
            <h2 className="text-sm font-semibold text-theme-primary">
              {t('candidates.title')}
            </h2>
            <input
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder={t('candidates.searchPlaceholder')}
              className="w-full rounded-md border border-theme bg-theme-secondary px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
            {filteredCandidates.length === 0 ? (
              <p className="text-sm text-theme-tertiary py-6 text-center border border-dashed border-theme rounded-lg">
                {t('candidates.empty')}
              </p>
            ) : (
              <div className="space-y-1.5 max-h-[60vh] overflow-y-auto pr-1">
                {filteredCandidates.map(pub => (
                  <div
                    key={pub.id}
                    className="flex items-center gap-3 rounded-lg border border-theme bg-theme-secondary px-3 py-2"
                  >
                    <RowThumbnail publication={pub} />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-theme-primary truncate">
                        {pub.title}
                      </div>
                      {pub.publisherName && (
                        <div className="text-xs text-theme-tertiary truncate">
                          {pub.publisherName}
                        </div>
                      )}
                    </div>
                    <button
                      type="button"
                      onClick={() => addCandidate(pub)}
                      className="inline-flex items-center gap-1 rounded-md border border-theme px-2 py-1 text-xs text-theme-primary hover:bg-theme-tertiary"
                    >
                      <Plus className="h-3 w-3" />
                      {t('candidates.add')}
                    </button>
                  </div>
                ))}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
