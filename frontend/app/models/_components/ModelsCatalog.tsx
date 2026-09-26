'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Brain, Eye, Globe, Layers, Search, Wrench, X } from 'lucide-react';
import { getProviderDisplayName, getProviderIconSrc } from '@/lib/ai-providers/providerIcons';
import { ServiceLogo } from '@/components/ui/service-logo';
import { CATALOG_MODELS, type CatalogModel, type ModelCapability } from './modelsData';
import { formatMonthKey, formatPrice, formatReleased, formatTokens, formatYear, groupDigits } from './modelsFormat';
import { providerHref } from './modelsQuery';

const CAPABILITY_META: Record<ModelCapability, { icon: typeof Brain; label: string; title: string }> = {
  reasoning: { icon: Brain, label: 'Reasoning', title: 'Extended reasoning / thinking' },
  vision: { icon: Eye, label: 'Vision', title: 'Reads images' },
  tools: { icon: Wrench, label: 'Tools', title: 'Calls tools and integrations' },
  caching: { icon: Layers, label: 'Cache', title: 'Prompt caching, so repeat context costs less' },
  web: { icon: Globe, label: 'Web', title: 'Built-in web search' },
};

const CAPABILITY_ORDER: ModelCapability[] = ['reasoning', 'vision', 'tools', 'caching', 'web'];

type SortKey = 'newest' | 'context' | 'price';

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'newest', label: 'Newest' },
  { key: 'context', label: 'Biggest context' },
  { key: 'price', label: 'Cheapest' },
];

const COLLAPSED_ROWS = 20;

function ProviderMark({ provider, size = 16 }: { provider: string; size?: number }) {
  const src = getProviderIconSrc(provider);
  if (!src) return null;
  return (
    <ServiceLogo
      src={src}
      alt=""
      aria-hidden="true"
      loading="lazy"
      width={size}
      height={size}
      className="shrink-0 logo-color"
      style={{ width: size, height: size }}
    />
  );
}

/**
 * The /models catalogue: a chronological strip of every model release we carry,
 * then a searchable list of the same models. Both read the one generated dataset,
 * so the strip and the list can never disagree, and both react to the same search
 * box and provider filter: picking "Anthropic" redraws the timeline as the Claude
 * release history.
 *
 * `initialProvider` is the `?provider=` filter the server already resolved, so the
 * filtered list is in the HTML rather than appearing after hydration: a visitor
 * arriving from the footer's "Grok" link sees Grok on first paint, and a crawler
 * sees it at all.
 */
export default function ModelsCatalog({ initialProvider = null }: { initialProvider?: string | null } = {}) {
  const [query, setQuery] = useState('');
  const [provider, setProvider] = useState<string | null>(initialProvider);
  const [sort, setSort] = useState<SortKey>('newest');
  const [showAll, setShowAll] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const router = useRouter();

  const providers = useMemo(() => {
    const counts = new Map<string, number>();
    for (const model of CATALOG_MODELS) counts.set(model.provider, (counts.get(model.provider) ?? 0) + 1);
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .map(([key, count]) => ({ key, count, label: getProviderDisplayName(key) }));
  }, []);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return CATALOG_MODELS.filter((model) => {
      if (provider && model.provider !== provider) return false;
      if (!needle) return true;
      return (
        model.name.toLowerCase().includes(needle) ||
        model.id.toLowerCase().includes(needle) ||
        getProviderDisplayName(model.provider).toLowerCase().includes(needle)
      );
    });
  }, [query, provider]);

  // CATALOG_MODELS is already newest-first, so 'newest' needs no re-sort; the two
  // other orders are derived from the same filtered set.
  const sorted = useMemo(() => {
    if (sort === 'newest') return filtered;
    const copy = [...filtered];
    copy.sort(sort === 'context' ? (a, b) => b.context - a.context : (a, b) => a.priceIn - b.priceIn);
    return copy;
  }, [filtered, sort]);

  const visible = showAll ? sorted : sorted.slice(0, COLLAPSED_ROWS);
  const isFiltering = query.trim().length > 0 || provider !== null;

  // Any filter change drops the selection: the selected row scrolls itself into
  // view when it mounts, so a selection surviving a filter would yank the page
  // back to that row the moment the filter is cleared.
  const changeQuery = (value: string) => {
    setQuery(value);
    setSelectedId(null);
  };

  // THE URL IS THE PROVIDER FILTER. `provider` below is a local copy that exists
  // only so a chip click paints before the navigation lands.
  //
  // Two things forced this. A chip click that left the URL behind meant a refresh
  // or a shared link restored a filter the visitor had just cleared. And the
  // footer's Models column is ON this page: clicking "Grok" from /models is a
  // client-side navigation, React keeps this component mounted, and `useState`
  // ignores the new `initialProvider` - so the URL changed and the list did not.
  // Syncing the URL by hand (history.replaceState) fixed the first and made the
  // second worse: the router then believed it was somewhere the URL was not, and
  // a link back to that stale URL navigated nowhere at all.
  useEffect(() => {
    setProvider(initialProvider);
    setSelectedId(null);
  }, [initialProvider]);

  // `replace`, not `push`: filtering is not a navigation, and twelve chips would
  // otherwise bury the back button. `scroll: false` keeps the visitor where they
  // are rather than jumping to the top of the page they are already reading.
  const goToProvider = (key: string | null) => {
    router.replace(key ? providerHref(key) : '/models', { scroll: false });
  };

  const changeProvider = (key: string | null) => {
    setProvider(key);
    setSelectedId(null);
    goToProvider(key);
  };

  const clearFilters = () => {
    setQuery('');
    setProvider(null);
    setSelectedId(null);
    goToProvider(null);
  };

  // The tiles describe WHAT IS LISTED, so they follow the filter. They used to be
  // computed once, on the page, over the whole catalogue: with a provider filter
  // that would put "91 models on this page" above a list of 11, which is the exact
  // tile-versus-list disagreement this page was already corrected for once.
  // Derived from `filtered`, never `sorted`: sorting reorders the same set.
  const stats = useMemo(() => {
    if (filtered.length === 0) return null;
    const widest = filtered.reduce((max, model) => Math.max(max, model.context), 0);
    const cheapest = filtered.reduce((min, model) => Math.min(min, model.priceIn), Number.POSITIVE_INFINITY);
    // Oldest by value, not by position: `filtered` happens to be newest-first, but
    // reading the last element would quietly start describing another model the day
    // that stops being true.
    const oldest = filtered.reduce((min, model) => (model.released < min ? model.released : min), filtered[0].released);
    const providerCount = new Set(filtered.map((model) => model.provider)).size;

    return [
      { value: String(filtered.length), label: 'models on this page' },
      // A provider filter makes this tile say "1", which tells nobody anything, so
      // it is dropped while one provider is selected rather than padded out.
      ...(provider ? [] : [{ value: String(providerCount), label: 'providers represented' }]),
      { value: formatTokens(widest), label: 'widest context window' },
      { value: formatPrice(cheapest), label: 'cheapest input, per 1M' },
      { value: formatYear(oldest), label: 'oldest release listed' },
    ];
  }, [filtered, provider]);

  // A timeline chip click reveals the model in the list below and marks the row,
  // so the strip stays a navigation device rather than a decoration.
  const handleSelect = (model: CatalogModel) => {
    const next = selectedId === model.id ? null : model.id;
    setSelectedId(next);
    if (next) setShowAll(true);
  };

  return (
    <div>
      {stats && (
        <div className={`grid grid-cols-2 ${provider ? 'md:grid-cols-4' : 'md:grid-cols-5'} gap-3 mb-14`}>
          {stats.map((stat) => (
            <div
              key={stat.label}
              className="rounded-2xl px-4 py-4"
              style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}
            >
              <div
                className="text-2xl font-bold tracking-tight"
                style={{ color: 'var(--text-primary)', fontFamily: 'var(--font-outfit), Outfit, sans-serif' }}
              >
                {stat.value}
              </div>
              <div className="mt-1 text-xs leading-snug" style={{ color: 'var(--text-muted)' }}>
                {stat.label}
              </div>
            </div>
          ))}
        </div>
      )}

      <ModelsTimeline models={filtered} selectedId={selectedId} onSelect={handleSelect} />

      <div className="mt-10 flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div className="relative w-full lg:max-w-sm">
          <Search
            className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none"
            style={{ color: 'var(--text-muted)' }}
            aria-hidden="true"
          />
          <input
            type="search"
            value={query}
            onChange={(event) => changeQuery(event.target.value)}
            placeholder="Search a model, an id, a provider"
            aria-label="Search models"
            className="models-search w-full h-9 pl-9 pr-3 rounded-xl text-sm transition-colors"
            style={{
              background: 'var(--bg-primary)',
              border: '1px solid var(--border-color)',
              color: 'var(--text-primary)',
            }}
          />
        </div>

        <div className="flex items-center gap-2" role="group" aria-label="Sort models">
          {SORTS.map((option) => {
            const active = sort === option.key;
            return (
              <button
                key={option.key}
                type="button"
                onClick={() => setSort(option.key)}
                aria-pressed={active}
                className="h-8 px-3 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                style={{
                  background: active ? 'var(--accent-primary)' : 'var(--bg-primary)',
                  color: active ? 'var(--accent-foreground)' : 'var(--text-secondary)',
                  border: `1px solid ${active ? 'var(--accent-primary)' : 'var(--border-color)'}`,
                }}
              >
                {option.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-2">
        <FilterChip active={provider === null} onClick={() => changeProvider(null)}>
          All {CATALOG_MODELS.length}
        </FilterChip>
        {providers.map((entry) => (
          <FilterChip
            key={entry.key}
            active={provider === entry.key}
            onClick={() => changeProvider(provider === entry.key ? null : entry.key)}
          >
            <ProviderMark provider={entry.key} size={14} />
            {entry.label}
            <span style={{ color: 'var(--text-muted)' }}>{entry.count}</span>
          </FilterChip>
        ))}
      </div>

      <div className="mt-6">
        {sorted.length === 0 ? (
          <div
            className="rounded-2xl px-6 py-12 text-center text-sm"
            style={{ border: '1px dashed var(--border-color)', color: 'var(--text-secondary)' }}
          >
            <p>
              No model matches{' '}
              {query.trim() && <span style={{ color: 'var(--text-primary)' }}>{query.trim()}</span>}
              {query.trim() && provider ? ' from ' : ''}
              {provider && <span style={{ color: 'var(--text-primary)' }}>{getProviderDisplayName(provider)}</span>}.
            </p>
            <button
              type="button"
              onClick={clearFilters}
              className="mt-3 inline-flex items-center gap-1.5 h-8 px-3 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              style={{ border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
            >
              <X className="w-3.5 h-3.5" aria-hidden="true" /> Clear filters
            </button>
          </div>
        ) : (
          <ModelList models={visible} selectedId={selectedId} />
        )}
      </div>

      {/* Desktop rows show the capability icons bare, so the legend is what makes
          them readable. Below md the badges carry their own label and it is noise. */}
      <div
        className="mt-4 hidden md:flex flex-wrap items-center gap-x-5 gap-y-2 text-xs"
        style={{ color: 'var(--text-muted)' }}
      >
        {CAPABILITY_ORDER.map((capability) => {
          const meta = CAPABILITY_META[capability];
          const Icon = meta.icon;
          return (
            <span key={capability} className="inline-flex items-center gap-1.5">
              <Icon className="w-3 h-3" aria-hidden="true" />
              {meta.title}
            </span>
          );
        })}
      </div>

      <div className="mt-5 flex flex-wrap items-center justify-between gap-3 text-xs" style={{ color: 'var(--text-muted)' }}>
        <p className="max-w-3xl leading-relaxed">
          {isFiltering
            ? `${sorted.length} of ${CATALOG_MODELS.length} models listed here. `
            : `${CATALOG_MODELS.length} models listed here. `}
          One row per model family: moving aliases, dated snapshots of a model already listed, and rows whose
          provider published no release date are left out. Context windows, list prices and capabilities come
          from the live catalog the app itself reads; release dates come from the providers&apos; own announcements.
        </p>
        {sorted.length > COLLAPSED_ROWS && (
          <button
            type="button"
            onClick={() => setShowAll((value) => !value)}
            className="h-8 px-3 rounded-lg text-xs font-medium transition-colors cursor-pointer"
            style={{ border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}
          >
            {showAll ? 'Show less' : `Show all ${sorted.length}`}
          </button>
        )}
      </div>
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className="inline-flex items-center gap-1.5 h-8 px-3 rounded-full text-xs font-medium transition-colors cursor-pointer"
      style={{
        background: active ? 'var(--bg-tertiary)' : 'var(--bg-primary)',
        color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
        border: `1px solid ${active ? 'var(--text-muted)' : 'var(--border-color)'}`,
      }}
    >
      {children}
    </button>
  );
}

/**
 * The chronological strip. One column per month that actually shipped something,
 * oldest on the left, and the scroller opens pinned to the right so a visitor
 * lands on the current month and scrolls back through the history.
 */
function ModelsTimeline({
  models,
  selectedId,
  onSelect,
}: {
  models: CatalogModel[];
  selectedId: string | null;
  onSelect: (model: CatalogModel) => void;
}) {
  const scrollerRef = useRef<HTMLDivElement>(null);

  const columns = useMemo(() => {
    const byMonth = new Map<string, CatalogModel[]>();
    for (const model of models) {
      const key = model.released.slice(0, 7);
      const bucket = byMonth.get(key);
      if (bucket) bucket.push(model);
      else byMonth.set(key, [model]);
    }
    return [...byMonth.entries()]
      .sort((a, b) => a[0].localeCompare(b[0]))
      .map(([month, entries]) => ({ month, models: entries }));
  }, [models]);

  // Pin to "now" on mount and whenever the strip's CONTENT changes, keyed by the
  // months it actually draws. Depending on the `columns` array itself would re-pin
  // on every keystroke, yanking a visitor who had scrolled back into the history.
  const columnsKey = columns.map((column) => column.month).join('|');
  useEffect(() => {
    const node = scrollerRef.current;
    if (node) node.scrollLeft = node.scrollWidth;
  }, [columnsKey]);

  if (columns.length === 0) return null;

  const tallest = columns.reduce((max, column) => Math.max(max, column.models.length), 0);

  return (
    <div className="models-timeline relative">
      <div
        ref={scrollerRef}
        className="models-timeline-scroller overflow-x-auto overflow-y-hidden pb-2"
        role="group"
        aria-label="Model releases over time"
      >
        <div className="flex items-end gap-3 min-w-max px-1">
          {columns.map((column, index) => {
            const year = column.month.slice(0, 4);
            const startsYear = index === 0 || columns[index - 1].month.slice(0, 4) !== year;
            return (
              <div key={column.month} className="flex flex-col justify-end" style={{ minWidth: 132 }}>
                <div
                  className="flex flex-col gap-1.5 justify-end"
                  style={{ minHeight: Math.min(tallest, 9) * 30 }}
                >
                  {column.models.map((model) => {
                    const active = selectedId === model.id;
                    return (
                      <button
                        key={model.id}
                        type="button"
                        onClick={() => onSelect(model)}
                        aria-pressed={active}
                        title={`${model.name} - ${getProviderDisplayName(model.provider)} - ${formatReleased(model.released)} - ${formatTokens(model.context)} context - ${formatPrice(model.priceIn)}/${formatPrice(model.priceOut)} per 1M`}
                        className="models-chip inline-flex items-center gap-1.5 h-7 px-2 rounded-lg text-xs text-left transition-colors cursor-pointer"
                        style={{
                          background: active ? 'var(--bg-tertiary)' : 'var(--bg-primary)',
                          border: `1px solid ${active ? 'var(--text-muted)' : 'var(--border-color)'}`,
                          color: 'var(--text-primary)',
                        }}
                      >
                        <ProviderMark provider={model.provider} size={13} />
                        <span className="truncate">{model.name}</span>
                      </button>
                    );
                  })}
                </div>
                <div className="mt-2 pt-2" style={{ borderTop: '1px solid var(--border-color)' }}>
                  <span
                    className="text-xs"
                    style={{ color: startsYear ? 'var(--text-primary)' : 'var(--text-muted)' }}
                  >
                    {formatMonthKey(column.month)}
                  </span>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function ModelList({ models, selectedId }: { models: CatalogModel[]; selectedId: string | null }) {
  return (
    <div className="rounded-2xl overflow-hidden" style={{ border: '1px solid var(--border-color)' }}>
      {/* `models-head` carries its own display rule (none, grid at md): a Tailwind
          `hidden` would lose to the scoped `.landing-root .models-row` selector. */}
      <div
        className="models-head px-4 py-2.5 text-xs font-medium"
        style={{ background: 'var(--bg-tertiary)', color: 'var(--text-muted)' }}
      >
        <span>Model</span>
        <span>Released</span>
        <span className="text-right">Context</span>
        <span className="text-right">Input /1M</span>
        <span className="text-right">Output /1M</span>
        <span>Caps</span>
      </div>
      {/* Named so it can be addressed on its own: the public chrome's footer is a
          pile of <li> links, and an unscoped "list item" query sweeps those in. */}
      <ul aria-label="Models">
        {models.map((model, index) => (
          <ModelRow key={model.id} model={model} selected={selectedId === model.id} first={index === 0} />
        ))}
      </ul>
    </div>
  );
}

function ModelRow({ model, selected, first }: { model: CatalogModel; selected: boolean; first: boolean }) {
  const ref = useRef<HTMLLIElement>(null);

  useEffect(() => {
    if (selected) ref.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [selected]);

  return (
    <li
      ref={ref}
      className="models-row px-4 py-3 text-sm"
      style={{
        borderTop: first ? undefined : '1px solid var(--border-color)',
        background: selected ? 'var(--landing-highlight-row)' : 'var(--bg-primary)',
      }}
    >
      <div className="flex items-center gap-2.5 min-w-0">
        <ProviderMark provider={model.provider} size={18} />
        <div className="min-w-0">
          <div className="truncate" style={{ color: 'var(--text-primary)' }}>
            {model.name}
          </div>
          <div className="truncate text-xs" style={{ color: 'var(--text-muted)' }} title={model.note ?? model.id}>
            <span className="font-mono">{model.id}</span>
            {model.note && <span> · {model.note}</span>}
          </div>
        </div>
      </div>

      <div className="models-cell" data-label="Released" style={{ color: 'var(--text-secondary)' }}>
        {formatReleased(model.released)}
      </div>
      <div
        className="models-cell md:text-right"
        data-label="Context"
        title={
          model.maxOutput
            ? `${groupDigits(model.context)} tokens in, up to ${groupDigits(model.maxOutput)} out`
            : `${groupDigits(model.context)} tokens in`
        }
        style={{ color: 'var(--text-secondary)' }}
      >
        {formatTokens(model.context)}
      </div>
      <div className="models-cell md:text-right" data-label="Input /1M" style={{ color: 'var(--text-secondary)' }}>
        {formatPrice(model.priceIn)}
      </div>
      <div className="models-cell md:text-right" data-label="Output /1M" style={{ color: 'var(--text-secondary)' }}>
        {formatPrice(model.priceOut)}
      </div>

      <div className="flex flex-wrap items-center gap-1.5">
        {CAPABILITY_ORDER.filter((capability) => model.caps.includes(capability)).map((capability) => {
          const meta = CAPABILITY_META[capability];
          const Icon = meta.icon;
          return (
            <span
              key={capability}
              title={meta.title}
              className="inline-flex items-center gap-1 h-6 px-1.5 rounded-md text-xs"
              style={{ background: 'var(--bg-secondary)', color: 'var(--text-muted)' }}
            >
              <Icon className="w-3 h-3" aria-hidden="true" />
              <span className="not-sr-only md:sr-only">{meta.label}</span>
            </span>
          );
        })}
      </div>
    </li>
  );
}
