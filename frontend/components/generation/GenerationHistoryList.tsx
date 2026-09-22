'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { ChevronLeft, ChevronRight, Loader2, WandSparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { FileThumb } from '@/components/files/FileCard';
import { GenerationCard } from '@/components/generation/GenerationCard';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { formatIcon, FORMAT_ORDER } from '@/lib/generation/formats';
import { useGenerationModels } from '@/hooks/useGenerationModels';
import {
  GENERATION_HISTORY_PAGE_SIZE,
  useGenerationHistory,
} from '@/hooks/useGenerationHistory';
import type { GenerationHistoryEntry } from '@/lib/api/storage-api';

/**
 * What this workspace has generated, newest first.
 *
 * <p><b>Why this exists.</b> A generated asset used to disappear into Files the moment it was made:
 * one more row among uploads and step outputs, with nothing saying which model produced it or from
 * which words. The work that went into a prompt was lost as soon as the dialog closed, and the only
 * way to make a variant of something was to retype it from memory.
 *
 * <p>One component, shown in both places a person looks for it: inside the generation dialog (where
 * the previous attempt is the thing you want to start from) and in Files (where the assets live).
 * Written twice, the two would drift into two different ideas of what a past generation is.
 *
 * <p>The entries ARE files - each generated asset carries the recipe it was made from - so this
 * never lists something that has been deleted, and there is no second list to keep in step.
 */
export interface GenerationHistoryListProps {
  /**
   * Start a new generation from this entry's recipe. Omit to hide the control: a surface with no
   * way to open the form must not offer a button that leads nowhere.
   */
  onReuse?: (entry: GenerationHistoryEntry) => void;
  /** Open the asset itself. Omit and the tile is not clickable. */
  onOpen?: (entry: GenerationHistoryEntry) => void;
  /** Pass false while the surface is hidden, so a closed panel costs no request. */
  enabled?: boolean;
  /**
   * Title above the list, for a surface that shows it as one section among several.
   *
   * <p>Rendered here rather than by the caller so that it disappears WITH the list when
   * {@link hideWhenEmpty} applies: a heading left behind over nothing is the empty shelf that rule
   * exists to avoid.
   */
  heading?: string;
  /**
   * Render nothing at all when this workspace has generated nothing.
   *
   * <p>For a surface where the list is an addition rather than the point of the page. The studio
   * and the Home applications row already work this way: a section that can be empty announces
   * itself only once it has something to say. The dialog and the Files browser pass false, because
   * there the empty state IS the answer to what the reader opened.
   */
  hideWhenEmpty?: boolean;
  className?: string;
}

export function GenerationHistoryList({
  onReuse,
  onOpen,
  enabled = true,
  heading,
  hideWhenEmpty = false,
  className,
}: GenerationHistoryListProps) {
  const t = useTranslations('generationHistory');
  // The dialog's own words for a format, so "Image" reads the same on both screens.
  const tGeneration = useTranslations('generation');

  const [page, setPage] = React.useState(0);
  const [kind, setKind] = React.useState<string | null>(null);
  const { entries, hasMore, isLoading, isError } = useGenerationHistory(
    page, kind ?? undefined, enabled,
  );

  // Through the SAME cache the dialog reads: the two surfaces cost one request between them. Used
  // for two things only - what to call a model, and whether it still exists.
  const { models } = useGenerationModels(enabled);
  const modelIndex = React.useMemo(() => {
    const index = new Map<string, { label: string; iconSlug: string | null }>();
    models.forEach((m) => index.set(m.model, { label: m.label, iconSlug: m.iconSlug ?? null }));
    return index;
  }, [models]);

  // A format this install has no model for is not offered: an empty tab is a dead end. Read from
  // the catalogue rather than from the current page, which only knows about twelve assets.
  const availableKinds = React.useMemo(() => {
    const present = new Set(models.map((m) => m.kind));
    const known = FORMAT_ORDER.filter((k) => present.has(k));
    const rest = [...present].filter((k) => !FORMAT_ORDER.includes(k)).sort();
    return [...known, ...rest];
  }, [models]);

  const chooseKind = React.useCallback((next: string | null) => {
    setKind(next);
    // Page 3 of "everything" is not page 3 of "images", and landing past the end of the filtered
    // list shows an empty grid under a non-zero total, which reads as a failure.
    setPage(0);
  }, []);

  const formatLabel = React.useCallback(
    (k: string) => (FORMAT_ORDER.includes(k) ? tGeneration(`formats.${k}`) : k),
    [tGeneration],
  );

  // Nothing generated, on a surface that only wanted to show it if there was. Three things are
  // deliberately NOT this case, and each of them is a way of hiding something the reader still
  // needs. Loading: hiding while the answer is on its way makes the section appear late and jump
  // the page. An error: a failed request is the one thing that does not tell us the history is
  // empty. A chosen format, or a page past the first: the emptiness is then the reader's own
  // filtering, and taking the controls away with the grid strands them there - `page` survives, so
  // the Previous button they needed disappears until the component remounts.
  const emptiedByTheReader = kind !== null || page > 0;
  if (hideWhenEmpty && !isLoading && !isError && entries.length === 0 && !emptiedByTheReader) {
    return null;
  }

  return (
    <div className={className}>
      {heading && (
        <h2 className="mb-3 text-sm font-semibold tracking-tight text-theme-primary">{heading}</h2>
      )}
      {availableKinds.length > 1 && (
        <div className="flex flex-wrap items-center gap-1.5 mb-3">
          <FilterChip active={kind === null} onClick={() => chooseKind(null)} label={t('allFormats')} />
          {availableKinds.map((k) => {
            const Icon = formatIcon(k);
            return (
              <FilterChip
                key={k}
                active={kind === k}
                onClick={() => chooseKind(k)}
                label={formatLabel(k)}
                icon={<Icon className="h-3.5 w-3.5" />}
              />
            );
          })}
        </div>
      )}

      {isLoading && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="h-5 w-5 animate-spin text-theme-secondary" />
        </div>
      )}

      {/* A request that failed is NOT an empty history, and telling someone they have generated
          nothing while their assets exist invites them to start over for no reason. */}
      {!isLoading && isError && (
        <p className="text-sm text-theme-secondary text-center py-10" role="status">{t('error')}</p>
      )}

      {!isLoading && !isError && entries.length === 0 && (
        <div className="flex flex-col items-center gap-2 py-10 text-center">
          <WandSparkles className="h-6 w-6 text-theme-muted" />
          <p className="text-sm text-theme-secondary">
            {kind ? t('emptyForFormat', { format: formatLabel(kind) }) : t('empty')}
          </p>
        </div>
      )}

      {!isLoading && !isError && entries.length > 0 && (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {entries.map((entry) => (
            <GenerationHistoryCard
              key={entry.id}
              entry={entry}
              model={modelIndex.get(entry.provenance.model)}
              onReuse={onReuse}
              onOpen={onOpen}
            />
          ))}
        </ul>
      )}

      {/* Only once there is somewhere to go: a pager under a single screenful is furniture. */}
      {!isLoading && !isError && (hasMore || page > 0) && (
        <div className="flex items-center justify-between gap-2 pt-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
          >
            <ChevronLeft className="mr-1 h-4 w-4" />
            {t('previous')}
          </Button>
          {/* A range, not "N of M": there is no total, on purpose - counting the generated assets
              would cost a scan of every file the workspace owns on every page view. */}
          <span className="text-xs text-theme-muted">
            {t('range', {
              from: page * GENERATION_HISTORY_PAGE_SIZE + 1,
              to: page * GENERATION_HISTORY_PAGE_SIZE + entries.length,
            })}
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasMore}
          >
            {t('next')}
            <ChevronRight className="ml-1 h-4 w-4" />
          </Button>
        </div>
      )}
    </div>
  );
}

/**
 * One format filter, drawn as the app's own control rather than as a pill.
 *
 * <p>It IS a {@link Button}: the design system is a flat one with a soft radius and hairline
 * borders, and its docblock says in as many words that it replaced the older pill shape. A
 * hand-rolled {@code rounded-full} chip was that older look surviving in one corner, so it read as
 * borrowed from somewhere else - and it would not have followed the system the next time the radius
 * or the accent moved.
 *
 * <p>Chosen / not chosen is the same pairing the Files page already uses for its Generated toggle
 * (solid accent versus outline), so the two controls that do the same kind of job look the same.
 *
 * <p>The size is kept deliberately smaller than a standard control: these sit above a grid as a
 * filter row, not as actions, and the height override is the only thing this adds to the variant.
 */
function FilterChip({
  active, onClick, label, icon,
}: { active: boolean; onClick: () => void; label: string; icon?: React.ReactNode }) {
  return (
    <Button
      type="button"
      variant={active ? 'default' : 'outline'}
      onClick={onClick}
      aria-pressed={active}
      className="h-7 gap-1.5 px-2.5 text-xs"
    >
      {icon}
      {label}
    </Button>
  );
}

/**
 * One past generation, through the card every other generation surface draws.
 *
 * <p>What this adds to it is the reading of the entry: which model made it (and whether that model
 * still exists), and what it cost.
 */
function GenerationHistoryCard({
  entry, model, onReuse, onOpen,
}: {
  entry: GenerationHistoryEntry;
  model?: { label: string; iconSlug: string | null };
  onReuse?: (entry: GenerationHistoryEntry) => void;
  onOpen?: (entry: GenerationHistoryEntry) => void;
}) {
  const t = useTranslations('generationHistory');
  const { provenance } = entry;
  const title = provenance.prompt?.trim() || entry.fileName || t('untitled');
  // A model that has left the catalogue cannot be re-run: the form has no row to select, and
  // sending its id would be refused after the reader had filled everything in again. Say so on the
  // control instead, and leave the entry readable.
  const reusable = Boolean(model);

  return (
    <GenerationCard
      as="li"
      thumb={<FileThumb entry={entry} />}
      title={title}
      modelLine={model?.label ?? provenance.model}
      iconSlug={model?.iconSlug}
      kind={provenance.kind}
      date={formatUtcDate(entry.createdAt)}
      billedCredits={provenance.billedCredits}
      onOpen={onOpen ? () => onOpen(entry) : undefined}
      openLabel={t('open', { name: title })}
      onModify={onReuse ? () => onReuse(entry) : undefined}
      modifyDisabled={!reusable}
      modifyTitle={reusable ? undefined : t('reuseUnavailable')}
    />
  );
}

export default GenerationHistoryList;
