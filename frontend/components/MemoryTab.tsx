'use client';

import { useCallback, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Brain, Plus, Pin, Trash2, Pencil, Bot, Users, Eye, EyeOff, AlertTriangle } from 'lucide-react';
import { BulkDeleteModal } from '@/components/ui/BulkDeleteModal';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { SearchField } from '@/components/ui/search-field';
import LoadingSpinner from '@/components/LoadingSpinner';
import { EmptyState } from '@/components/ui/EmptyState';
import { useOrgScopedQuery } from '@/lib/hooks/useOrgScopedQuery';
import { useCanMutateInCurrentOrg, useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { useToast } from '@/components/Toast';
import Toast from '@/components/Toast';
import { memoryService, type Memory, type MemoryRow, type MemoryType, type MemoryWriteRequest }
  from '@/lib/api/orchestrator/memory.service';
import { MemoryEditorModal } from '@/components/memory/MemoryEditorModal';
import { formatUtcDate } from '@/lib/utils/dateFormatters';

const TYPE_FILTERS: Array<MemoryType | 'all'> = ['all', 'user', 'feedback', 'project', 'reference'];

/**
 * The Memory tab: what the agents in this workspace have learned, in one list a
 * person can read, correct and delete.
 *
 * The list is fetched with {@link useOrgScopedQuery}, so its cache is keyed by
 * the active workspace. Switching workspace therefore swaps the whole list
 * rather than showing the previous workspace's memories until a refetch lands,
 * which for this feature would be a privacy bug, not a stale-data annoyance.
 */
export function MemoryTab({ className = '' }: { className?: string }) {
  const t = useTranslations('memory');
  // Two translators: the feature namespace, plus the shared one for the generic
  // error title. next-intl scopes a translator to one namespace, so a single
  // t() cannot reach both.
  const tCommon = useTranslations('common');
  const queryClient = useQueryClient();
  const { toasts, addToast, removeToast } = useToast();
  const canMutate = useCanMutateInCurrentOrg();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);

  const [search, setSearch] = useState('');
  const [typeFilter, setTypeFilter] = useState<MemoryType | 'all'>('all');
  const [editing, setEditing] = useState<Memory | null>(null);
  const [opening, setOpening] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const { data: memories, isLoading, isError, refetch: refetchList } = useOrgScopedQuery<MemoryRow[]>({
    queryKey: ['memories', 'list'],
    queryFn: () => memoryService.list(),
  });

  // A typed query goes to the server, which searches the BODY as well as the
  // title and summary. Filtering the loaded rows in the browser cannot: the body
  // is where the detail, the exceptions and the reasoning live, so an entry whose
  // summary does not happen to repeat the word was unfindable here while an agent
  // could find it perfectly well. Two characters is the floor because a one-letter
  // query matches almost everything and costs a round trip to say so.
  // Debounced like every other search on the platform (AgentTable,
  // DataSourceTable, InterfaceTable all use this hook at 300ms). Without it,
  // typing "postmortem" fired nine full-text queries, eight of which nobody
  // would ever read.
  const needle = useDebouncedValue(search.trim(), 300);
  const searching = needle.length >= 2;
  const {
    data: matches,
    isError: searchFailed,
    isFetching: searchInFlight,
    refetch: refetchSearch,
  } = useOrgScopedQuery<MemoryRow[]>({
    queryKey: ['memories', 'search', needle],
    queryFn: () => memoryService.search(needle),
    enabled: searching,
  });

  const refresh = useCallback(() => {
    // Invalidate, and only invalidate. It already refetches THIS list (the active
    // query matches the key) and every other mounted view of the same workspace's
    // memory; the extra refetch() that used to follow just fired the same request
    // a second time on every pin toggle.
    //
    // Scoped to this workspace's memory rather than ['org']: that prefix would
    // refetch workflows, agents and conversations too, which is a whole-app
    // refresh disguised as a checkbox.
    queryClient.invalidateQueries({ queryKey: ['org', currentOrgId ?? '__personal__', 'memories'] });
  }, [queryClient, currentOrgId]);

  const visible = useMemo(() => {
    // While a server search is in flight there is nothing to show yet for that
    // query; falling back to the full list would flash every entry between
    // keystrokes.
    const all = searching ? (matches ?? []) : (memories ?? []);
    const lowered = needle.toLowerCase();
    return all.filter((m) => {
      if (typeFilter !== 'all' && m.type !== typeFilter) return false;
      if (!lowered || searching) return true;
      // Only reached for a one-character query, which never leaves the browser.
      return m.title.toLowerCase().includes(lowered)
        || m.summary.toLowerCase().includes(lowered)
        || m.slug.toLowerCase().includes(lowered);
    });
  }, [memories, matches, needle, searching, typeFilter]);

  // A listed row carries no body (the list endpoint leaves it out), so the
  // editor has to fetch the entry it is about to open. Opening it from the row
  // instead would show an empty body and then SAVE that emptiness over the real
  // one - the failure is silent and destroys exactly the field the person came
  // to read.
  const openEditor = useCallback(async (row: MemoryRow) => {
    setOpening(row.id);
    try {
      const full = await memoryService.get(row.id);
      setCreating(false);
      setEditing(full);
    } catch (e) {
      addToast({ type: 'error', title: tCommon('error'), message: e instanceof Error ? e.message : String(e) });
    } finally {
      setOpening(null);
    }
  }, [addToast, tCommon]);

  const handleSave = useCallback(async (payload: MemoryWriteRequest) => {
    if (editing) {
      await memoryService.update(editing.id, payload);
      addToast({ type: 'success', title: t('toastUpdated'), message: '' });
    } else {
      await memoryService.create(payload);
      addToast({ type: 'success', title: t('toastCreated'), message: '' });
    }
    refresh();
  }, [editing, addToast, t, refresh]);

  // Deleting a memory is not undoable and the row may hold something a person
  // wrote by hand, so the single click that used to destroy it has to be
  // confirmed. Anyone who only wants the agents to stop using a fact should
  // deactivate it instead, which is the button next to this one.
  //
  // BulkDeleteModal rather than window.confirm: it is what SkillTab and the rest
  // of the platform use, it is themed, and it does not block the event loop.
  const [confirmingDelete, setConfirmingDelete] = useState<MemoryRow | null>(null);

  const handleDelete = useCallback(async () => {
    const memory = confirmingDelete;
    if (!memory) return;
    setConfirmingDelete(null);
    try {
      await memoryService.remove(memory.id);
      addToast({ type: 'success', title: t('toastDeleted'), message: '' });
      refresh();
    } catch (e) {
      addToast({ type: 'error', title: tCommon('error'), message: e instanceof Error ? e.message : String(e) });
    }
  }, [confirmingDelete, addToast, t, tCommon, refresh]);

  const handleToggleActive = useCallback(async (memory: MemoryRow) => {
    try {
      // Deactivating keeps the row, its text and its history, and takes it out of
      // every agent-facing read: no injection, no recall, no search, not addressable.
      // It is the reversible half of "this fact is wrong", where delete is the other.
      await memoryService.update(memory.id, { isActive: !memory.isActive });
      refresh();
    } catch (e) {
      addToast({ type: 'error', title: tCommon('error'), message: e instanceof Error ? e.message : String(e) });
    }
  }, [addToast, tCommon, refresh]);

  const handleTogglePin = useCallback(async (memory: MemoryRow) => {
    try {
      await memoryService.update(memory.id, { pinned: !memory.pinned });
      refresh();
    } catch (e) {
      addToast({ type: 'error', title: tCommon('error'), message: e instanceof Error ? e.message : String(e) });
    }
  }, [addToast, tCommon, refresh]);

  // The list failing hides everything; a failed SEARCH only hides the matches,
  // and only while a query is active. Either way the screen must say so rather
  // than draw an empty list.
  const failed = isError || (searching && searchFailed);
  // Either the debounce has not fired yet (the needle still trails the box) or
  // the request is out. Both mean the same thing to the reader: no answer yet.
  const awaitingSearch = search.trim().length >= 2
    && !failed
    && (needle !== search.trim() || searchInFlight || (searching && matches === undefined));

  if (isLoading) return <LoadingSpinner />;

  return (
    <div className={className}>
      {/* The platform's search surface, not a hand-placed icon over an Input:
          same height, same radius and the same clear button as every other
          search field in the app. */}
      <div className="mb-4 flex flex-col gap-3 md:flex-row md:items-center">
        <SearchField
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onClear={() => setSearch('')}
          clearLabel={t('clearSearch')}
          placeholder={t('searchPlaceholder')}
          containerClassName="min-w-[220px] flex-1"
        />

        <div className="flex flex-wrap items-center gap-2">
          {/* A filter row is built from Buttons - `default` for the chosen
              option, `outline` for the rest - and never from a private pill
              class. That is the rule in components/ui/README.md, and it is what
              the agenda's view switch does; the home-made pills this replaces
              were a second button dialect sitting next to the real one. */}
          <div role="group" aria-label={t('filterByType')} className="flex flex-wrap items-center gap-1">
            {TYPE_FILTERS.map((value) => (
              <Button
                key={value}
                type="button"
                size="sm"
                variant={typeFilter === value ? 'default' : 'outline'}
                aria-pressed={typeFilter === value}
                onClick={() => setTypeFilter(value)}
              >
                {value === 'all' ? t('filterAll') : t(`type.${value}`)}
              </Button>
            ))}
          </div>

          {canMutate && (
            <Button size="sm" onClick={() => { setEditing(null); setCreating(true); }}>
              <Plus className="h-3.5 w-3.5" />
              {t('createButton')}
            </Button>
          )}
        </div>
      </div>

      <p className="mb-4 text-sm text-theme-muted">{t('explainer')}</p>

      {/* A failed fetch is NOT an empty workspace. Rendering the empty state on
          an error told a person auditing what the agents know that there was
          nothing to see, on the one screen whose whole purpose is to show them
          everything - and a new feature's list is legitimately empty, so the
          two are indistinguishable to the reader. The retry is here because
          the usual cause is transient. */}
      {failed ? (
        <EmptyState
          icon={<AlertTriangle className="h-6 w-6 text-amber-500" />}
          title={t('loadFailedTitle')}
          subtitle={t('loadFailedSubtitle')}
          // Retries the query that FAILED. Wired to the list either way, the
          // button silently did nothing when it was the search that broke: it
          // refetched rows that were already there and left the error on screen.
          actions={(
            <Button
              variant="secondary"
              onClick={() => (searching && searchFailed ? refetchSearch() : refetchList())}
            >
              {tCommon('retry')}
            </Button>
          )}
        />
      ) : awaitingSearch ? (
        /* "No memory matches" is a claim about what the workspace CONTAINS.
           Made while the query is still in flight it is a claim nobody has
           checked yet, and the person reads it as an answer: the tab spends the
           300ms debounce plus a round trip asserting a fact it does not have. */
        <LoadingSpinner />
      ) : visible.length === 0 ? (
        <EmptyState
          icon={<Brain className="h-6 w-6 text-theme-muted" />}
          title={memories?.length ? t('noMatches') : t('emptyTitle')}
          subtitle={memories?.length ? t('noMatchesSubtitle') : t('emptySubtitle')}
        />
      ) : (
        <div className="space-y-2">
          {visible.map((memory) => (
            <div
              key={memory.id}
              // A deactivated entry is dimmed rather than hidden: it is still the
              // person's row to correct or revive, and this screen is the only place
              // it can be switched back on.
              // `rounded-xl`: the radius ladder in components/ui/README.md puts a
              // card on the same rung as a Button, and `rounded-md` is reserved
              // for the small non-interactive labels inside it.
              className={`rounded-xl border border-theme bg-theme-secondary px-4 py-3${
                memory.isActive ? '' : ' opacity-60'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-theme-primary">{memory.title}</span>
                    <span className="rounded-md bg-theme-tertiary px-1.5 py-0.5 text-xs text-theme-muted">
                      {t(`type.${memory.type}`)}
                    </span>
                    <span className="inline-flex items-center gap-1 text-xs text-theme-muted">
                      {memory.scope === 'agent'
                        ? <><Bot className="h-3 w-3" />{t('scopeAgent')}</>
                        : <><Users className="h-3 w-3" />{t('scopeWorkspace')}</>}
                    </span>
                    <span className="text-xs text-theme-muted">
                      {memory.source === 'user' ? t('sourceUser') : t('sourceAgent')}
                    </span>
                    {memory.pinned && (
                      <span className="inline-flex items-center gap-1 text-xs text-[var(--accent-primary)]">
                        <Pin className="h-3 w-3" />{t('pinnedBadge')}
                      </span>
                    )}
                    {!memory.isActive && (
                      <span className="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400">
                        <EyeOff className="h-3 w-3" />{t('inactiveBadge')}
                      </span>
                    )}
                  </div>
                  <p className="mt-1 text-sm text-theme-primary">{memory.summary}</p>
                  <p className="mt-1 text-xs text-theme-muted">
                    {t('metaLine', {
                      slug: memory.slug,
                      recalls: memory.recallCount,
                      updated: formatUtcDate(memory.updatedAt),
                    })}
                  </p>
                </div>

                {canMutate && (
                  /* Row actions are Buttons, `ghost`/`icon` at h-8 w-8 rounded-lg,
                     which is the shape the dialog's own close button and the task
                     board's row actions already use. Written by hand they had no
                     focus ring and no disabled handling, and their radius was one
                     rung off the card they sit on.

                     No `aria-pressed` on the two toggles here: both flip their
                     LABEL with the state ("Deactivate" becomes "Reactivate"),
                     which is the alternative to a pressed state rather than a
                     companion to it - together they announce "Reactivate,
                     pressed". The type filters above are the opposite case: one
                     fixed label each, so their state has to be carried by
                     `aria-pressed`. */
                  <div className="flex flex-shrink-0 items-center gap-0.5">
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      onClick={() => handleTogglePin(memory)}
                      title={memory.pinned ? t('unpinAction') : t('pinAction')}
                      aria-label={memory.pinned ? t('unpinAction') : t('pinAction')}
                      className={`h-8 w-8 rounded-lg ${
                        memory.pinned ? 'text-[var(--accent-primary)]' : 'text-[var(--text-muted)]'
                      }`}
                    >
                      <Pin className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      onClick={() => handleToggleActive(memory)}
                      title={memory.isActive ? t('deactivateAction') : t('activateAction')}
                      aria-label={memory.isActive ? t('deactivateAction') : t('activateAction')}
                      className={`h-8 w-8 rounded-lg ${
                        memory.isActive ? 'text-[var(--text-muted)]' : 'text-amber-600 dark:text-amber-400'
                      }`}
                    >
                      {memory.isActive
                        ? <Eye className="h-3.5 w-3.5" />
                        : <EyeOff className="h-3.5 w-3.5" />}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      onClick={() => openEditor(memory)}
                      disabled={opening === memory.id}
                      title={t('editAction')}
                      aria-label={t('editAction')}
                      className="h-8 w-8 rounded-lg text-[var(--text-muted)]"
                    >
                      <Pencil className="h-3.5 w-3.5" />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon"
                      onClick={() => setConfirmingDelete(memory)}
                      title={t('deleteAction')}
                      aria-label={t('deleteAction')}
                      className="h-8 w-8 rounded-lg text-[var(--text-muted)] hover:text-red-500"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </Button>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <BulkDeleteModal
        isOpen={confirmingDelete !== null}
        title={t('deleteTitle')}
        message={t('deleteConfirm', { title: confirmingDelete?.title ?? '' })}
        cancelLabel={tCommon('cancel')}
        confirmLabel={tCommon('delete')}
        onCancel={() => setConfirmingDelete(null)}
        onConfirm={handleDelete}
      />

      {(editing || creating) && (
        <MemoryEditorModal
          // Remount per entry: the form reads its initial values from the prop
          // once rather than syncing them through an effect, so the key is what
          // makes "edit a different entry" show that entry's values.
          key={editing?.id ?? 'new'}
          memory={editing}
          onClose={() => { setEditing(null); setCreating(false); }}
          onSave={handleSave}
        />
      )}

      <div className="fixed top-4 right-4 z-[10000] space-y-2">
        {toasts.map((toast) => (
          <Toast
            key={toast.id}
            id={toast.id}
            type={toast.type}
            title={toast.title}
            message={toast.message}
            duration={toast.duration}
            onClose={removeToast}
          />
        ))}
      </div>
    </div>
  );
}
