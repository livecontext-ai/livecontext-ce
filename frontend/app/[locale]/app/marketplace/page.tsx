'use client';

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { Search, Package, ShoppingBag, Bot, Zap, Monitor, Table, AppWindow, Eye, Cloud, ArrowUpDown, Star, CalendarDays, Coins, Clapperboard, X } from 'lucide-react';
import { useTranslations, useLocale } from 'next-intl';
import { orchestratorApi, WorkflowPublication } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import type { Purchase } from '@/lib/api/orchestrator/types';
import { useAuth } from '@/lib/providers/smart-providers';
import { CategoryFilter } from '@/components/marketplace/CategoryFilter';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { matchesVisibilityFilter, type VisibilityFilter } from '@/lib/utils/visibility';
import AcquirePublicationModal from '@/components/marketplace/AcquirePublicationModal';
import { InstallSummaryModal, type InstallSummaryTarget } from '@/components/marketplace/InstallSummaryModal';
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';
import { IS_CE } from '@/lib/edition';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { cloudLinkService } from '@/lib/api/cloud-link.service';
import { clearModelsCache } from '@/hooks/useModels';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';
import type { MarketplaceRefinements } from '@/lib/api/orchestrator/publication.service';
import { samePageUrl, showSamePageUrl } from '@/lib/navigation/showSamePageUrl';
import { track } from '@/lib/analytics/analytics';
import {
  CloudLinkExpiredNotice,
  useCloudLinkExpired,
} from '@/components/cloud-link/CloudLinkExpiredNotice';

// Card + preview helpers extracted to a shared component so the onboarding
// "suggested apps" modal reuses the exact same markup (no style fork).

const DISPLAY_FILTERS = ['apps', 'agents', 'interfaces', 'tables', 'skills'] as const;
type DisplayFilter = (typeof DISPLAY_FILTERS)[number];

// Which types the user can actually pick in the Explore type select. The other
// DISPLAY_FILTERS keep their logic (empty states, filtering) so surfacing one is
// a one-line addition here; the backend marketplace query already returns ALL
// ACTIVE+PUBLIC types.
const SELECTABLE_DISPLAY_FILTERS: readonly DisplayFilter[] = ['apps', 'agents'];

const DISPLAY_FILTER_ICONS: Record<DisplayFilter, typeof Bot> = {
  apps: AppWindow,
  agents: Bot,
  interfaces: Monitor,
  tables: Table,
  skills: Zap,
};

/** DisplayMode each type filter maps to in the marketplace query. */
const DISPLAY_FILTER_MODES: Record<DisplayFilter, string> = {
  apps: 'APPLICATION',
  agents: 'AGENT',
  interfaces: 'INTERFACE',
  tables: 'TABLE',
  skills: 'SKILL',
};

// ---------------------------------------------------------------------------
// Explore refinements - sort + rating / date / price filters
//
// All of them, the type filter included, are answered by the BACKEND: they go
// out as query params and come back as a page of exactly the publications that
// match.
//
// They used to be applied client-side, over a single popularity-ordered
// `page=0&size=50` fetch. That silently redefined each one as "...among the 50
// most popular publications": with 76 public publications, 26 could not be
// reached by any combination of clicks, "recent" could not surface anything
// newer than that window (a just-published app has no installs, favorites or
// reviews, so it sorts last and is exactly what falls off the end), and "last 7
// days" came back empty on the day something was published. Only the search box
// hit a different endpoint, which is why searching found apps the grid swore did
// not exist.
//
// 'popular' is the DEFAULT and is the backend's own ordering (favorites,
// installs, rating mass - see PublicationListQueryService.POPULARITY_ORDER_BY),
// which knows the favorite counts the client never receives.
// ---------------------------------------------------------------------------

const SORT_OPTIONS = ['popular', 'rating', 'recent', 'installs'] as const;
type SortOption = (typeof SORT_OPTIONS)[number];

const RATING_FILTERS = ['any', 'rated', 'rating4', 'rating3'] as const;
type RatingFilter = (typeof RATING_FILTERS)[number];

const DATE_FILTERS = ['any', 'd7', 'd30', 'd90', 'y1'] as const;
type DateFilter = (typeof DATE_FILTERS)[number];

const PRICE_FILTERS = ['any', 'free', 'paid'] as const;
type PriceFilter = (typeof PRICE_FILTERS)[number];

/** Backend `rating` param each rating filter maps to. */
const RATING_PARAMS: Record<RatingFilter, string> = {
  any: 'any',
  rated: 'rated',
  rating4: 'min_4',
  rating3: 'min_3',
};

/** Query params cleared together by the "reset filters" action. */
const REFINEMENT_PARAM_KEYS = ['rating', 'date', 'price'] as const;

/** Age window in days for the date filter. */
const DATE_WINDOW_DAYS: Record<DateFilter, number | null> = {
  any: null,
  d7: 7,
  d30: 30,
  d90: 90,
  y1: 365,
};

/**
 * One grid page. 24 is six full rows at the lg 4-column breakpoint, so "Load
 * more" always extends the grid by whole rows instead of leaving a ragged one.
 */
const PAGE_SIZE = 24;

/**
 * Enum-valued state that lives in the URL query string instead of component
 * state.
 *
 * The marketplace tab and the Explore type filter both use it so that leaving
 * the page and coming back - breadcrumb, browser Back, or a pasted link -
 * restores the grid the user was actually looking at. They used to be plain
 * `useState`, so every return trip remounted the page on Explore/Applications
 * and the agent you had just opened was no longer on screen.
 *
 * `replace`, not `push`: flipping a filter is not a step the Back button
 * should have to walk back through. An unknown or unparseable value resolves
 * to `fallback`, and selecting the fallback drops the param entirely so the
 * canonical URL stays clean.
 */
function useQueryParamState<T extends string>(
  key: string,
  allowed: readonly T[],
  fallback: T,
): [T, (next: T) => void] {
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const raw = searchParams.get(key);
  const value = (allowed as readonly string[]).includes(raw ?? '') ? (raw as T) : fallback;

  // A refinement is a change of ADDRESS on the page already on screen, not a change of page,
  // so it goes through the history API. Returning a select to its fallback removes the last
  // param, and a router push of the bare pathname is dropped when the page was loaded at it -
  // so on a page opened directly on `?type=agents`, clearing the filter did nothing at all.
  const setValue = useCallback((next: T) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === fallback) params.delete(key);
    else params.set(key, next);
    const qs = params.toString();
    showSamePageUrl(qs ? `${pathname}?${qs}` : pathname, samePageUrl(pathname, searchParams), 'replace');
  }, [pathname, searchParams, key, fallback]);

  return [value, setValue];
}

/**
 * Clear several query params in ONE navigation.
 *
 * Not a loop over {@link useQueryParamState} setters: each of those closes over
 * the `searchParams` of the current render, so calling three of them in the same
 * tick makes each rebuild the URL from the same stale snapshot and only the last
 * write survives - the other two params would silently stay in the URL.
 */
function useQueryParamReset(keys: readonly string[]): () => void {
  const pathname = usePathname();
  const searchParams = useSearchParams();

  return useCallback(() => {
    const params = new URLSearchParams(searchParams.toString());
    keys.forEach((key) => params.delete(key));
    const qs = params.toString();
    showSamePageUrl(qs ? `${pathname}?${qs}` : pathname, samePageUrl(pathname, searchParams), 'replace');
  }, [pathname, searchParams, keys]);
}

/**
 * One refinement dropdown (sort / rating / date / price). Same trigger shape as
 * the type select it sits next to, so the row reads as one control strip.
 */
function RefinementSelect<T extends string>({
  value,
  onChange,
  options,
  optionLabel,
  ariaLabel,
  icon: Icon,
  testId,
}: {
  value: T;
  onChange: (next: T) => void;
  options: readonly T[];
  optionLabel: (option: T) => string;
  ariaLabel: string;
  icon: typeof Bot;
  testId: string;
}) {
  return (
    <Select value={value} onValueChange={(v) => onChange(v as T)}>
      <SelectTrigger
        className="h-9 w-auto min-w-[9rem] gap-1.5 rounded-xl bg-theme-primary border-theme"
        aria-label={ariaLabel}
        data-testid={testId}
      >
        <SelectValue>
          <div className="flex items-center gap-2">
            <Icon className="h-3.5 w-3.5 flex-shrink-0" />
            <span className="text-sm">{optionLabel(value)}</span>
          </div>
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {options.map((option) => (
          <SelectItem key={option} value={option}>
            {optionLabel(option)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

// ============== Explore Tab ==============

// `remote` (CE cloud-parity, 2026-06-10): a cloud-linked CE renders this exact
// tab, but the explore reads come from the CE backend's /publications/remote/*
// proxies of the cloud public API (single cloud origin = marketplace.cloud-api-url)
// and installs go through the CE remote acquire path (ceMode).
function ExploreTab({ remote = false }: { remote?: boolean }) {
  const t = useTranslations('marketplace');
  // Navigates to the editable copy the install-time opt-in may have created.
  const router = useRouter();
  const { isLoading: isAuthLoading, isAuthenticated, numericUserId } = useAuth();
  const [publications, setPublications] = useState<WorkflowPublication[]>([]);
  // How many publications match the current query server-side, which is what
  // decides whether there is a next page to offer - `publications` only holds
  // the pages fetched so far.
  const [totalCount, setTotalCount] = useState(0);
  const [page, setPage] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | undefined>();
  // The studio shelf, a SECOND AXIS read straight off the URL rather than held in state: it is how
  // the studio surface links here, and it composes with whatever category is also selected. Kept out
  // of state so a shared link opens the same shelf the sender was looking at.
  const marketplaceSearchParams = useSearchParams();
  const studioOnly = marketplaceSearchParams.get('studio') === 'true';
  const [error, setError] = useState<string | null>(null);
  const [acquireTarget, setAcquireTarget] = useState<WorkflowPublication | null>(null);
  const [acquiredIds, setAcquiredIds] = useState<Set<string>>(new Set());
  // Publications installed during THIS visit: flips the card to installed/"Open"
  // immediately and keeps it there even if the acquiredIds refetch fails.
  const [justInstalledIds, setJustInstalledIds] = useState<Set<string>>(new Set());
  // Shared install state machine (progress lives on the CARD, not in the modal).
  // Only INLINE installs (started from the marketplace grid / preview header)
  // are rendered and consumed here - a full-modal install (ChatCore) owns its
  // own lifecycle and must not be stolen or double-surfaced by this tab.
  const rawActiveInstall = useMarketplaceInstallStore((s) => s.active);
  const activeInstall = rawActiveInstall?.inline ? rawActiveInstall : null;
  const clearInstall = useMarketplaceInstallStore((s) => s.clear);
  const consumeInstallSuccess = useMarketplaceInstallStore((s) => s.consumeSuccess);
  // Post-install summary of what landed in the workspace. Held locally (not read from
  // the store) so it survives consuming the success, and so a NEXT install can start
  // while the user is still reading it.
  const [installSummary, setInstallSummary] = useState<InstallSummaryTarget | null>(null);
  // Which install has already been summarised, so closing the modal is final (see the
  // success effect). Not state: it must not trigger a render of its own.
  const summarisedInstallRef = useRef<string | null>(null);
  const [displayFilter, setDisplayFilter] = useQueryParamState<DisplayFilter>('type', DISPLAY_FILTERS, 'apps');
  // Refinements live in the URL like `type` does, so a filtered grid survives
  // a Back/forward, a breadcrumb return, or a pasted link.
  const [sortOption, setSortOption] = useQueryParamState<SortOption>('sort', SORT_OPTIONS, 'popular');
  const [ratingFilter, setRatingFilter] = useQueryParamState<RatingFilter>('rating', RATING_FILTERS, 'any');
  const [dateFilter, setDateFilter] = useQueryParamState<DateFilter>('date', DATE_FILTERS, 'any');
  const [priceFilter, setPriceFilter] = useQueryParamState<PriceFilter>('price', PRICE_FILTERS, 'any');

  const hasActiveRefinement =
    ratingFilter !== 'any' || dateFilter !== 'any' || priceFilter !== 'any';

  const resetRefinements = useQueryParamReset(REFINEMENT_PARAM_KEYS);

  const displayFilterLabel = useCallback((filter: DisplayFilter) => (
    filter === 'apps' ? t('filterApplications')
    : filter === 'agents' ? t('filterAgents')
    : filter === 'interfaces' ? t('filterInterfaces')
    : filter === 'tables' ? t('filterTables')
    : t('filterSkills')
  ), [t]);

  const sortLabel = useCallback((option: SortOption) => (
    option === 'popular' ? t('sortPopular')
    : option === 'rating' ? t('sortRating')
    : option === 'recent' ? t('sortRecent')
    : t('sortInstalls')
  ), [t]);

  const ratingLabel = useCallback((filter: RatingFilter) => (
    filter === 'any' ? t('ratingAny')
    : filter === 'rated' ? t('ratingRated')
    : filter === 'rating4' ? t('rating4Plus')
    : t('rating3Plus')
  ), [t]);

  const dateLabel = useCallback((filter: DateFilter) => (
    filter === 'any' ? t('dateAny')
    : filter === 'd7' ? t('date7Days')
    : filter === 'd30' ? t('date30Days')
    : filter === 'd90' ? t('date90Days')
    : t('dateYear')
  ), [t]);

  const priceLabel = useCallback((filter: PriceFilter) => (
    filter === 'any' ? t('priceAny')
    : filter === 'free' ? t('priceFree')
    : t('pricePaid')
  ), [t]);

  // Phase 6 (2026-05-18) - `acquiredIds` is workspace-bound (each workspace
  // owns its own publication acquisitions); reset on switch so explore
  // doesn't hide rows acquired in another workspace.
  useOrgScopedReset(() => {
    setPublications([]);
    setTotalCount(0);
    setPage(0);
    setAcquiredIds(new Set());
    setJustInstalledIds(new Set());
    setError(null);
    // The summary describes what landed in the PREVIOUS workspace - drop it.
    setInstallSummary(null);
    // An install finishing after the switch belongs to the PREVIOUS workspace:
    // kill it so its success can't mark a card installed under the new one.
    clearInstall();
  });

  // Fetch acquired publication IDs to hide them from explore. Skipped when
  // anonymous - the endpoint requires auth and would log "No authentication
  // token available", polluting the console of public browsing visitors.
  const fetchAcquiredIds = useCallback(async () => {
    if (!isAuthenticated) {
      setAcquiredIds(new Set());
      return;
    }
    try {
      // Two sources, deliberately NOT one.
      //
      // Applications keep coming from /publications/acquired: it is derived from
      // the live cloned workflow, so deleting the clone correctly flips the card
      // back to "Install", and it applies the org per-member restriction
      // deny-list. Receipts have neither property (they are permanent and
      // unfiltered), so keying applications on them would show a phantom
      // "Installed" with an Open link pointing at a clone that no longer exists,
      // and would show restricted apps as installed.
      //
      // Every OTHER publication type comes from the receipts: an acquired AGENT
      // produces no APPLICATION workflow clone, so it never appeared in
      // /acquired and its card kept offering "Install" after a successful
      // install. recordAcquisition writes a receipt for every type and for free
      // publications too, which makes it the only type-agnostic install signal.
      // allSettled, not all: the two signals are independent, so one endpoint
      // failing must not blank the badges the other one still knows about.
      const [acquiredRes, purchasesRes] = await Promise.allSettled([
        publicationService.getAcquiredApplications(),
        publicationService.getPurchases(),
      ]);
      const ids = new Set<string>();
      if (acquiredRes.status === 'fulfilled') {
        for (const app of acquiredRes.value.applications || []) {
          if (app.sourcePublicationId) ids.add(app.sourcePublicationId);
        }
      }
      if (purchasesRes.status === 'fulfilled') {
        for (const purchase of purchasesRes.value.purchases || []) {
          if (!purchase.publicationId) continue;
          // Skip everything CLONE-BACKED: /acquired lists every cloned
          // workflow, whichever display mode it wears, and a clone can be
          // deleted. Those types are owned by the branch above so a permanent
          // receipt cannot resurrect a clone that is gone. Agents (and any
          // future non-workflow type) have no clone to check, so the receipt
          // is the only signal there is.
          const mode = purchase.publication?.displayMode || 'WORKFLOW';
          if (mode === 'APPLICATION' || mode === 'WORKFLOW') continue;
          ids.add(purchase.publicationId);
        }
      }
      setAcquiredIds(ids);
    } catch {
      // Silently ignore - worst case we show already acquired
    }
  }, [isAuthenticated]);

  /** The active refinements, in the shape the marketplace endpoints take. */
  const refinements = useMemo<MarketplaceRefinements>(() => ({
    displayMode: DISPLAY_FILTER_MODES[displayFilter],
    sort: sortOption,
    rating: RATING_PARAMS[ratingFilter],
    days: DATE_WINDOW_DAYS[dateFilter] ?? undefined,
    price: priceFilter,
  }), [displayFilter, sortOption, ratingFilter, dateFilter, priceFilter]);

  /**
   * Load one page of the grid.
   *
   * `append` separates "Load more" (keep what is on screen and add the next
   * page) from every other trigger - a new query, category or refinement
   * replaces the grid and starts again at page 0.
   *
   * Search stays ONE unpaginated call because the endpoint answers with the
   * whole match set, so it reports its own length as the total and never offers
   * a next page. It carries the same refinements as the grid: the search box
   * sits inside the filtered view, so typing must narrow what is on screen
   * rather than reset it to every publication.
   */
  const loadPage = useCallback(async (targetPage: number, append: boolean) => {
    // Only the newest request may write. Typing while a "Load more" is in flight,
    // or flipping a filter, leaves an older fetch resolving afterwards: without
    // this it would append rows the visitor no longer asked for, or replace the
    // grid with a set matching a query they have since changed.
    const requestId = ++latestRequestRef.current;
    if (append) setIsLoadingMore(true);
    else setIsLoading(true);
    setError(null);
    const query = searchQuery.trim();
    try {
      if (query) {
        const results = remote
          // The axis travels with the query: searching inside the Studio shelf must narrow it, not
          // reset it to the whole marketplace.
          ? await publicationService.searchRemotePublications(query, selectedCategory, refinements, studioOnly)
          : await orchestratorApi.searchPublications(query, selectedCategory, refinements, studioOnly);
        if (requestId !== latestRequestRef.current) return;
        const found = results?.publications || [];
        setPublications(found);
        setTotalCount(found.length);
        setPage(0);
        // A search is one unpaginated call, so this is one event per query
        // (never per scroll page). Length only, never the text.
        track('marketplace_searched', {
          query_length: query.length,
          result_count: found.length,
          category_slug: selectedCategory ?? null,
          display_filter: displayFilter,
        });
        return;
      }
      const response = remote
        // The axis is passed ONLY on the studio shelf. Passing `undefined` explicitly would still be
        // a fifth argument, and the ordinary marketplace call is meant to be the call it always was.
        ? (studioOnly
            ? await publicationService.getRemoteMarketplacePublications(
                targetPage, PAGE_SIZE, selectedCategory, refinements, true)
            : await publicationService.getRemoteMarketplacePublications(
                targetPage, PAGE_SIZE, selectedCategory, refinements))
        : (studioOnly
            ? await orchestratorApi.getMarketplacePublications(
                targetPage, PAGE_SIZE, selectedCategory, refinements, true)
            : await orchestratorApi.getMarketplacePublications(
                targetPage, PAGE_SIZE, selectedCategory, refinements));
      if (requestId !== latestRequestRef.current) return;
      const batch = response.publications || [];
      // Functional update: a "Load more" resolving after another state change
      // must extend whatever is on screen now, not the array this closure saw.
      setPublications((prev) => (append ? [...prev, ...batch] : batch));
      setTotalCount(typeof response.count === 'number' ? response.count : batch.length);
      setPage(targetPage);
    } catch (err: any) {
      if (requestId !== latestRequestRef.current) return;
      console.error('Error loading marketplace publications:', err);
      setError(err.message || (query ? t('searchError') : t('loadError')));
    } finally {
      // A superseded request must not clear the spinner the CURRENT one raised.
      if (requestId === latestRequestRef.current) {
        setIsLoading(false);
        setIsLoadingMore(false);
      }
    }
  }, [remote, searchQuery, selectedCategory, refinements, t, displayFilter, studioOnly]);

  const handleCategoryChange = useCallback((categorySlug?: string) => {
    setSelectedCategory(categorySlug);
    setSearchQuery('');
    track('marketplace_filtered', { filter: 'category', value: categorySlug ?? null });
  }, []);

  // One tracked setter per refinement control: the value is a bounded option key.
  const trackFilter = useCallback((filter: string, value: string | null) => {
    track('marketplace_filtered', { filter, value });
  }, []);
  const handleDisplayFilterChange = useCallback((next: DisplayFilter) => {
    setDisplayFilter(next);
    trackFilter('type', next);
  }, [setDisplayFilter, trackFilter]);
  const handleSortChange = useCallback((next: SortOption) => {
    setSortOption(next);
    trackFilter('sort', next);
  }, [setSortOption, trackFilter]);
  const handleRatingChange = useCallback((next: RatingFilter) => {
    setRatingFilter(next);
    trackFilter('rating', next);
  }, [setRatingFilter, trackFilter]);
  const handleDateChange = useCallback((next: DateFilter) => {
    setDateFilter(next);
    trackFilter('date', next);
  }, [setDateFilter, trackFilter]);
  const handlePriceChange = useCallback((next: PriceFilter) => {
    setPriceFilter(next);
    trackFilter('price', next);
  }, [setPriceFilter, trackFilter]);
  const handleResetRefinements = useCallback(() => {
    resetRefinements();
    trackFilter('reset', null);
  }, [resetRefinements, trackFilter]);

  const initialLoadDone = useRef(false);
  // Sequence number of the most recent grid request (see loadPage).
  const latestRequestRef = useRef(0);

  useEffect(() => {
    if (!isAuthLoading) fetchAcquiredIds();
  }, [isAuthLoading, fetchAcquiredIds]);

  // One view event per mount, once auth has resolved so is_authenticated is real.
  const viewTrackedRef = useRef(false);
  useEffect(() => {
    if (isAuthLoading || viewTrackedRef.current) return;
    viewTrackedRef.current = true;
    track('marketplace_viewed', {
      display_filter: displayFilter,
      sort: sortOption,
      is_authenticated: isAuthenticated,
      remote,
    });
  }, [isAuthLoading, displayFilter, sortOption, isAuthenticated, remote]);

  // Any change to what is being asked for - query, category, or a refinement -
  // reloads from page 0, since `loadPage` closes over all three.
  useEffect(() => {
    if (isAuthLoading) return;
    const timer = setTimeout(() => {
      loadPage(0, false);
      initialLoadDone.current = true;
    }, initialLoadDone.current ? 300 : 0);
    return () => clearTimeout(timer);
  }, [isAuthLoading, loadPage]);

  // A search returns its whole match set at once, so only the browse grid has a
  // next page. `totalCount` is the server's count of everything that matches,
  // not just what has been fetched.
  const hasMore = !searchQuery.trim() && publications.length < totalCount;

  // Install completed (the machine runs in the shared store, the modal is
  // already closed): flip the card to installed/"Open" right away, refresh the
  // server-side acquired set, then CONSUME the success so the progress overlay
  // (parked at 100%) disappears. consumeSuccess (not clear) because the finally
  // runs after an async refetch: by then the user may have started installing
  // another app, and clear() would kill that machine.
  //
  // The summary is captured into LOCAL state first: consuming the success wipes
  // the store entry, and the user must still be able to read what was installed
  // (and start another install) while the summary is on screen.
  useEffect(() => {
    if (activeInstall?.status !== 'success') return;
    const pubId = activeInstall.publication.id;
    // Dismissed stays dismissed: `installSummary` is null once closed, so without this
    // the effect would happily build a fresh summary and re-open the modal on its next
    // run (it re-runs while the store still holds the success).
    if (summarisedInstallRef.current !== pubId) {
      summarisedInstallRef.current = pubId;
      // Functional + identity-preserving: if this success is already summarised, keep the
      // SAME object so React bails out of the re-render. A fresh object here would make
      // every re-render that re-runs this effect schedule another one.
      setInstallSummary((prev) => (prev?.publication.id === pubId
        ? prev
        : {
            publication: activeInstall.publication,
            resources: activeInstall.resources,
            // Only when the user asked for it: an absent block means "no copy was
            // requested", which the summary renders as nothing at all.
            editableCopy: activeInstall.withEditableCopy
              ? {
                  workflowId: activeInstall.editableCopyWorkflowId,
                  failed: activeInstall.editableCopyFailed,
                }
              : undefined,
          }));
    }
    // A demo install acquired nothing, so it must not enter the optimistic
    // "just installed" set: the set survives the demo mode being switched off,
    // and the card would then claim an installation that never happened.
    if (!activeInstall.demo) {
      setJustInstalledIds((prev) => {
        const next = new Set(prev);
        next.add(pubId);
        return next;
      });
    }
    void fetchAcquiredIds().finally(() => consumeInstallSuccess(pubId));
    // `resources` is READ here but deliberately NOT a dependency: it is an object the
    // store writes in the same transition that sets status='success', so the status +
    // publication-id keys already cover it - while adding it would re-run this effect on
    // every render that hands back a fresh object, and each run sets state (an infinite
    // render loop; it hangs the page silently).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeInstall?.status, activeInstall?.publication.id, fetchAcquiredIds, consumeInstallSuccess]);

  // Terminal install errors surface through the SAME modal (error /
  // link-required / insufficient-credits screens): re-mount it while the store
  // holds one - also catches installs started from the preview page, whose
  // modal unmounted on navigation back to this page.
  const installErrorPublication =
    activeInstall && activeInstall.status !== 'installing' && activeInstall.status !== 'success'
      ? activeInstall.publication
      : null;



  const SelectedTypeIcon = DISPLAY_FILTER_ICONS[displayFilter];

  return (
    <div className="space-y-5">
      {/* Search + category + type + refinements. Wraps: seven controls do not
          fit one line on a laptop, and the search field keeps the first row. */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-[16rem]">
          <Search className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 h-4 w-4 text-theme-secondary" />
          <Input
            type="text"
            placeholder={t('searchPlaceholder')}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-11 rounded-xl bg-theme-primary border-theme text-sm"
          />
        </div>
        <CategoryFilter
          selectedCategory={selectedCategory}
          onCategoryChange={handleCategoryChange}
        />
        {/* Resource type - the grid is always scoped to a single type. A select
            rather than chips so it reads as the sibling filter of the category
            one it sits next to (icons mirror the left sidebar). */}
        <Select value={displayFilter} onValueChange={(v) => handleDisplayFilterChange(v as DisplayFilter)}>
          <SelectTrigger
            className="h-9 w-40 rounded-xl bg-theme-primary border-theme"
            aria-label={t('filterByType')}
          >
            <SelectValue>
              <div className="flex items-center gap-2">
                <SelectedTypeIcon className="h-3.5 w-3.5 flex-shrink-0" />
                <span className="text-sm">{displayFilterLabel(displayFilter)}</span>
              </div>
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            {SELECTABLE_DISPLAY_FILTERS.map((filter) => {
              const Icon = DISPLAY_FILTER_ICONS[filter];
              return (
                <SelectItem key={filter} value={filter}>
                  <div className="flex items-center gap-2">
                    <Icon className="h-3.5 w-3.5 flex-shrink-0" />
                    <span>{displayFilterLabel(filter)}</span>
                  </div>
                </SelectItem>
              );
            })}
          </SelectContent>
        </Select>

        {/* Refinements, in the order a marketplace visitor actually reasons:
            how to rank, then how good, then how fresh, then how much. */}
        <RefinementSelect
          value={sortOption}
          onChange={handleSortChange}
          options={SORT_OPTIONS}
          optionLabel={sortLabel}
          ariaLabel={t('sortBy')}
          icon={ArrowUpDown}
          testId="marketplace-sort-select"
        />
        <RefinementSelect
          value={ratingFilter}
          onChange={handleRatingChange}
          options={RATING_FILTERS}
          optionLabel={ratingLabel}
          ariaLabel={t('filterByRating')}
          icon={Star}
          testId="marketplace-rating-select"
        />
        <RefinementSelect
          value={dateFilter}
          onChange={handleDateChange}
          options={DATE_FILTERS}
          optionLabel={dateLabel}
          ariaLabel={t('filterByDate')}
          icon={CalendarDays}
          testId="marketplace-date-select"
        />
        <RefinementSelect
          value={priceFilter}
          onChange={handlePriceChange}
          options={PRICE_FILTERS}
          optionLabel={priceLabel}
          ariaLabel={t('filterByPrice')}
          icon={Coins}
          testId="marketplace-price-select"
        />
        {hasActiveRefinement && (
          <button
            type="button"
            onClick={handleResetRefinements}
            data-testid="marketplace-reset-filters"
            className="h-9 px-3 rounded-xl text-sm text-theme-secondary hover:text-theme-primary hover:bg-theme-tertiary transition-colors"
          >
            {t('resetFilters')}
          </button>
        )}
      </div>

      {error && (
        <div className="p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-600 dark:text-red-400">
          {error}
        </div>
      )}

      {isLoading && (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {/* Fill a full 4×4 grid (4 rows of 4 cols at lg) while loading. */}
          {Array.from({ length: 16 }, (_, i) => (
            <PublicationCardSkeleton key={i} />
          ))}
        </div>
      )}

      {!isLoading && publications.length === 0 && (() => {
        const emptyIcon =
          displayFilter === 'agents' ? <Bot className="h-7 w-7 text-theme-muted" />
          : displayFilter === 'apps' ? <AppWindow className="h-7 w-7 text-theme-muted" />
          : displayFilter === 'interfaces' ? <Monitor className="h-7 w-7 text-theme-muted" />
          : displayFilter === 'tables' ? <Table className="h-7 w-7 text-theme-muted" />
          : displayFilter === 'skills' ? <Zap className="h-7 w-7 text-theme-muted" />
          : <Package className="h-7 w-7 text-theme-muted" />;
        const emptyTitle =
          displayFilter === 'agents' ? t('emptyAgents')
          : displayFilter === 'apps' ? t('emptyApplications')
          : displayFilter === 'interfaces' ? t('emptyInterfaces')
          : displayFilter === 'tables' ? t('emptyTables')
          : displayFilter === 'skills' ? t('emptySkills')
          : t('noPublications');
        const emptyHint =
          displayFilter === 'agents' ? t('emptyAgentsHint')
          : displayFilter === 'apps' ? t('emptyApplicationsHint')
          : displayFilter === 'interfaces' ? t('emptyInterfacesHint')
          : displayFilter === 'tables' ? t('emptyTablesHint')
          : displayFilter === 'skills' ? t('emptySkillsHint')
          : t('noPublicationsHint');
        // A refinement that excludes everything is NOT "nothing was ever
        // published here": saying so would send the visitor away when one click
        // brings the grid back. The filter message wins over the type-specific
        // one, and carries the way out.
        if (hasActiveRefinement) {
          return (
            <div className="flex flex-col items-center justify-center py-16 text-center">
              <div className="w-14 h-14 bg-theme-tertiary rounded-xl flex items-center justify-center mb-4">
                <Search className="h-7 w-7 text-theme-muted" />
              </div>
              <h3 className="text-sm font-medium text-theme-primary mb-1">{t('noFilterResults')}</h3>
              <p className="text-sm text-theme-secondary max-w-sm mb-4">{t('noFilterResultsHint')}</p>
              <button
                type="button"
                onClick={handleResetRefinements}
                data-testid="marketplace-reset-filters-empty"
                className="h-9 px-4 rounded-xl text-sm font-medium bg-theme-tertiary text-theme-primary hover:bg-theme-secondary transition-colors"
              >
                {t('resetFilters')}
              </button>
            </div>
          );
        }
        return (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-14 h-14 bg-theme-tertiary rounded-xl flex items-center justify-center mb-4">
              {emptyIcon}
            </div>
            <h3 className="text-sm font-medium text-theme-primary mb-1">
              {searchQuery ? t('noSearchResults') : emptyTitle}
            </h3>
            <p className="text-sm text-theme-secondary max-w-sm">
              {searchQuery ? t('tryDifferentSearch') : emptyHint}
            </p>
          </div>
        );
      })()}

      {!isLoading && publications.length > 0 && (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {publications.map((publication) => {
            const isInstalled = acquiredIds.has(publication.id) || justInstalledIds.has(publication.id);
            const isThisInstalling =
              activeInstall?.publication.id === publication.id &&
              (activeInstall.status === 'installing' || activeInstall.status === 'success');
            // Single-flight: while ANY install runs (including one started from a modal
            // elsewhere - hence the RAW store entry), every other Install is refused.
            const otherInstallRunning =
              rawActiveInstall?.status === 'installing' && rawActiveInstall.publication.id !== publication.id;
            return (
            <PublicationCard
              key={publication.id}
              publication={publication}
              // In remote (linked-CE) mode the publications come from the CLOUD,
              // so publisherId lives in the cloud's user-id namespace - comparing
              // it against the LOCAL CE user id would mark foreign publications
              // as "own" on id collisions (e.g. both sides have user "1") and
              // suppress the install CTA. Ownership is only meaningful locally.
              currentUserId={remote || numericUserId == null ? undefined : String(numericUserId)}
              // Remote (cloud) publication → route the thumbnail + publisher
              // avatar reads through the cloud proxy (cloud ids aren't local).
              remote={remote}
              // Owned by the caller's ACTIVE workspace → Installed, not Acquire. Computed server-side:
              // this call sends the JWT (optionalAuth) and the gateway injects X-Organization-ID on the
              // public marketplace route, so the server compares owner_id to the active workspace.
              ownedByMe={publication.ownedByMe ?? false}
              // Install is an authenticated action - anonymous visitors can
              // browse but not acquire. Omitting the handler hides the CTA
              // button inside the hover overlay of PublicationCard.
              onAcquire={isAuthenticated ? setAcquireTarget : undefined}
              isAcquired={isInstalled}
              // Live install → the card's preview un-greys as the gauge fills
              // (kept through the brief 'success' window at 100% so the card
              // never flashes back to an Install button before the flip).
              installProgress={isThisInstalling ? activeInstall.progress : null}
              installBlocked={otherInstallRunning}
              // Installed application → the Install slot becomes "Open". The
              // applications route resolves the acquired clone from the
              // publication id, so no clone id is needed here.
              openHref={
                isInstalled && (publication.displayMode || 'WORKFLOW') === 'APPLICATION'
                  ? `/app/applications/${publication.id}`
                  : undefined
              }
            />
            );
          })}
        </div>
      )}

      {/* Load more. The grid is server-paged, so this is the only way to reach
          past the first page. The button's PRESENCE is the whole signal that
          there is more: the catalogue size is deliberately not published - how
          many apps exist is the marketplace's business, not a number to put
          under every grid. `totalCount` therefore only ever decides whether this
          renders, and is never shown. */}
      {!isLoading && hasMore && (
        <div className="flex justify-center pt-2">
          <Button
            variant="outline"
            onClick={() => loadPage(page + 1, true)}
            disabled={isLoadingMore}
            data-testid="marketplace-load-more"
            className="rounded-xl"
          >
            {isLoadingMore ? t('loadingMore') : t('loadMore')}
          </Button>
        </div>
      )}

      {/* Install modal - remote (linked CE) installs go through the CE remote
          acquire path (/publications/remote/{id}/acquire) via ceMode.
          inlineProgress: confirming closes the modal and the card renders the
          progress; the modal re-mounts by itself (installErrorPublication) to
          show terminal error screens. */}
      {(acquireTarget || installErrorPublication) && (
        <AcquirePublicationModal
          demoEligible
          isOpen
          inlineProgress
          onClose={() => setAcquireTarget(null)}
          publication={acquireTarget ?? installErrorPublication!}
          ceMode={remote}
        />
      )}

      {/* Post-install summary: names every resource the install created, so the
          workspace never gains interfaces / tables / agents silently. */}
      {installSummary && (
        <InstallSummaryModal
          publication={installSummary.publication}
          resources={installSummary.resources}
          editableCopy={installSummary.editableCopy}
          onOpenEditableCopy={installSummary.editableCopy?.workflowId
            ? () => {
                const copyId = installSummary.editableCopy!.workflowId!;
                setInstallSummary(null);
                router.push(`/app/workflow/${copyId}`);
              }
            : undefined}
          onClose={() => setInstallSummary(null)}
        />
      )}
    </div>
  );
}

// ============== My Publications Tab ==============

function MyPublicationsTab() {
  const t = useTranslations('marketplace');
  const tc = useTranslations('common');
  const { isLoading: isAuthLoading } = useAuth();
  const [publications, setPublications] = useState<WorkflowPublication[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Visibility filter - these are all the viewer's OWN publications, so Public / Private narrows by
  // marketplace visibility, mirroring /app/applications. `private` = everything not PUBLIC.
  const [visibilityFilter, setVisibilityFilter] = useState<VisibilityFilter>('all');

  const fetchMyPublications = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const response = await orchestratorApi.getMyPublications();
      setPublications(response.publications || []);
    } catch (err: any) {
      console.error('Error fetching my publications:', err);
      setError(err.message || t('loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (!isAuthLoading) fetchMyPublications();
  }, [isAuthLoading, fetchMyPublications]);

  // Phase 6 (2026-05-18) - clear on workspace switch and refetch.
  useOrgScopedReset(() => {
    setPublications([]);
    setError(null);
    if (!isAuthLoading) fetchMyPublications();
  });

  // Show every publishable type (APPLICATION, AGENT, INTERFACE, TABLE, SKILL).
  // Bare WORKFLOW publications live on the dedicated /marketplace/workflows page.
  const appPublications = useMemo(() => {
    return publications.filter((p) => {
      const mode = p.displayMode || 'WORKFLOW';
      return mode === 'APPLICATION' || mode === 'AGENT'
          || mode === 'INTERFACE' || mode === 'TABLE' || mode === 'SKILL';
    });
  }, [publications]);

  // Narrow by marketplace visibility via the shared helper (same bucketing the boards use).
  // These are all the viewer's OWN publications, so each carries a visibility → falls in exactly
  // one bucket; `private` = everything not PUBLIC (PRIVATE + legacy UNLISTED).
  const visiblePublications = useMemo(
    () => appPublications.filter((p) => matchesVisibilityFilter(p.visibility, visibilityFilter)),
    [appPublications, visibilityFilter],
  );

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
        {Array.from({ length: 3 }, (_, i) => (
          <PublicationCardSkeleton key={i} />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-600 dark:text-red-400">
        {error}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Visibility filter - gated on the RAW count (not the filtered one) so a filter that yields
          zero apps keeps the control visible and the user is never trapped. */}
      {appPublications.length > 0 && (
        <div className="flex items-center justify-end">
          <Select value={visibilityFilter} onValueChange={(v) => setVisibilityFilter(v as VisibilityFilter)}>
            <SelectTrigger className="w-auto gap-1.5" aria-label={tc('filterByVisibility')}>
              <Eye className="h-3.5 w-3.5 opacity-70" />
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{tc('visibilityAny')}</SelectItem>
              <SelectItem value="public">{tc('visibilityPublic')}</SelectItem>
              <SelectItem value="private">{tc('visibilityPrivate')}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      )}

      {visiblePublications.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <div className="w-14 h-14 bg-theme-tertiary rounded-xl flex items-center justify-center mb-4">
            <Package className="h-7 w-7 text-theme-muted" />
          </div>
          <h3 className="text-sm font-medium text-theme-primary mb-1">
            {appPublications.length === 0 ? t('noMyPublications') : t('noSearchResults')}
          </h3>
          <p className="text-sm text-theme-secondary max-w-sm">
            {appPublications.length === 0 ? t('noMyPublicationsHint') : t('tryDifferentSearch')}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {visiblePublications.map((publication) => (
            <PublicationCard key={publication.id} publication={publication} showStats mine />
          ))}
        </div>
      )}
    </div>
  );
}

// ============== My Purchases Tab ==============

// Exported for unit testing the cloud-acquired-purchase enrichment in isolation.
export function MyPurchasesTab({ remote = false }: { remote?: boolean }) {
  const t = useTranslations('marketplace');
  // Navigates to the editable copy the install-time opt-in may have created.
  const router = useRouter();
  const { isLoading: isAuthLoading } = useAuth();
  const [purchases, setPurchases] = useState<Purchase[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Re-install routes through AcquirePublicationModal so the user sees the
  // same 5-10s download progress bar as a fresh install (inlineProgress: the
  // bar renders on the purchase CARD, exactly like the Explore tab).
  const [reinstallTarget, setReinstallTarget] = useState<WorkflowPublication | null>(null);
  // Same inline-only gating as ExploreTab: this tab renders/consumes only the
  // installs it (or the marketplace preview header) started.
  const rawActiveInstall = useMarketplaceInstallStore((s) => s.active);
  const activeInstall = rawActiveInstall?.inline ? rawActiveInstall : null;
  const clearInstall = useMarketplaceInstallStore((s) => s.clear);
  const consumeInstallSuccess = useMarketplaceInstallStore((s) => s.consumeSuccess);
  // Same post-install summary as Explore: a re-install re-creates the whole resource
  // set, so the user is told what it put back.
  const [installSummary, setInstallSummary] = useState<InstallSummaryTarget | null>(null);
  const summarisedInstallRef = useRef<string | null>(null);

  // A workspace switch orphans any in-flight install started here.
  useOrgScopedReset(() => {
    clearInstall();
    setInstallSummary(null);
  });

  const fetchPurchases = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const response = await publicationService.getPurchases();
      const raw = response.purchases || [];

      // Cloud purchases: try the cloud by-id enrichment FIRST so an ACTIVE source keeps the
      // publisher's populated frozen showcase + full meta (avatar / description / node icons).
      // Only when the cloud source is unpublished/deleted (the fetch fails) do we fall back to A1:
      // render the acquirer's OWN local clone via the per-run path (immune to the deletion). Local
      // (own/deleted publisher) purchases need no enrichment.
      const afterCloud = await Promise.all(raw.map(async (purchase) => {
        const pub = purchase.publication;
        if (!pub?.remote) return { purchase, cloudOk: true };
        try {
          const full = await publicationService.getPublicationByIdPublic(pub.id, /* remote */ true);
          // Source still live → keep the cloud showcase; disable the local-clone fallback.
          return { purchase: { ...purchase, publication: { ...pub, ...full, remote: true, localShowcase: false } }, cloudOk: true };
        } catch {
          return { purchase, cloudOk: false }; // cloud source gone → A1 local-clone fallback next
        }
      }));

      // Resolve the clone's preview run ONLY for cloud-unavailable purchases that have a local
      // clone (one batched call). A clone with no run yet falls back to the cover tile.
      const fallbackIds = Array.from(new Set(
        afterCloud
          .filter(x => !x.cloudOk && x.purchase.publication?.localShowcase && x.purchase.publication.acquiredWorkflowId)
          .map(x => x.purchase.publication!.acquiredWorkflowId!),
      ));
      let runByWorkflow: Record<string, string | undefined> = {};
      if (fallbackIds.length) {
        try {
          const batch = await workflowService.getApplicationRunVersionBatch(fallbackIds);
          runByWorkflow = Object.fromEntries(
            Object.entries(batch).map(([wf, meta]) => [wf, meta?.applicationRunId ?? undefined]));
        } catch { /* leave empty → cover tile */ }
      }

      const enriched = afterCloud.map(({ purchase, cloudOk }) => {
        const pub = purchase.publication;
        if (cloudOk || !pub?.localShowcase || !pub.acquiredWorkflowId) return purchase;
        const runId = runByWorkflow[pub.acquiredWorkflowId];
        // showcaseInterfaceId is already the LOCAL clone's entry interface (from the backend).
        return runId ? { ...purchase, publication: { ...pub, showcaseRunId: runId } } : purchase;
      });
      setPurchases(enriched);
    } catch (err: any) {
      console.error('Error fetching purchases:', err);
      setError(err.message || t('loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [t]);

  useEffect(() => {
    if (!isAuthLoading) fetchPurchases();
  }, [isAuthLoading, fetchPurchases]);

  const handleReinstall = useCallback((publication: WorkflowPublication) => {
    setReinstallTarget(publication);
  }, []);

  // Reinstall completed (machine in the shared store, modal closed at confirm):
  // refresh the purchases so hasActiveWorkflow flips the card to installed,
  // THEN consume the success. (fetchPurchases swaps the grid for its loading
  // skeletons during the refetch; the refreshed list comes back already
  // "Installed", so no intermediate Install button is ever shown.) consumeSuccess
  // rather than clear(): the finally is async and must never kill an install
  // the user started in the meantime.
  useEffect(() => {
    if (activeInstall?.status !== 'success') return;
    const pubId = activeInstall.publication.id;
    // Dismissed stays dismissed (see the same guard in ExploreTab).
    if (summarisedInstallRef.current !== pubId) {
      summarisedInstallRef.current = pubId;
      setInstallSummary((prev) => (prev?.publication.id === pubId
        ? prev
        : {
            publication: activeInstall.publication,
            resources: activeInstall.resources,
            // Only when the user asked for it: an absent block means "no copy was
            // requested", which the summary renders as nothing at all.
            editableCopy: activeInstall.withEditableCopy
              ? {
                  workflowId: activeInstall.editableCopyWorkflowId,
                  failed: activeInstall.editableCopyFailed,
                }
              : undefined,
          }));
    }
    setReinstallTarget(null);
    void fetchPurchases().finally(() => consumeInstallSuccess(pubId));
    // `resources` read but NOT a dependency - see the same note in ExploreTab: an object
    // dependency re-runs this state-setting effect on every render and hangs the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeInstall?.status, activeInstall?.publication.id, fetchPurchases, consumeInstallSuccess]);

  // Same error-surfacing contract as the Explore tab: terminal install errors
  // re-mount the acquire modal on their dedicated screens.
  const installErrorPublication =
    activeInstall && activeInstall.status !== 'installing' && activeInstall.status !== 'success'
      ? activeInstall.publication
      : null;

  if (isLoading) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
        {Array.from({ length: 3 }, (_, i) => (
          <PublicationCardSkeleton key={i} />
        ))}
      </div>
    );
  }

  if (error) {
    return (
      <div className="p-4 rounded-xl bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 text-sm text-red-600 dark:text-red-400">
        {error}
      </div>
    );
  }

  if (purchases.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <div className="w-14 h-14 bg-theme-tertiary rounded-xl flex items-center justify-center mb-4">
          <ShoppingBag className="h-7 w-7 text-theme-muted" />
        </div>
        <h3 className="text-sm font-medium text-theme-primary mb-1">
          {t('noPurchases')}
        </h3>
        <p className="text-sm text-theme-secondary max-w-sm">
          {t('noPurchasesHint')}
        </p>
      </div>
    );
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
      {purchases.map((purchase) => {
        const pub = purchase.publication;
        // No publication snapshot → nothing to render (publisher removed it).
        if (!pub) return null;
        // Already-installed purchases show the "installed" badge (no button);
        // re-installable ones get the SAME persistent footer + Install button as
        // the Explore tab, routed through AcquirePublicationModal (free re-acquire).
        // Every My-Purchases row is a receipt holder, so reinstall mirrors the
        // backend's receipt-holder re-acquire rule: allowed for ANY status except
        // REJECTED. In particular a publisher-deleted (INACTIVE) or unpublished app
        // the user no longer has installed stays reinstallable from the frozen
        // snapshot - gating on `=== 'ACTIVE'` wrongly hid the button after delete.
        const canReinstall = pub.status !== 'REJECTED' && !purchase.hasActiveWorkflow;
        const isThisInstalling =
          activeInstall?.publication.id === pub.id &&
          (activeInstall.status === 'installing' || activeInstall.status === 'success');
        // Single-flight, same rule as Explore (RAW store: an install started anywhere counts).
        const otherInstallRunning =
          rawActiveInstall?.status === 'installing' && rawActiveInstall.publication.id !== pub.id;
        return (
          <PublicationCard
            key={purchase.publicationId}
            publication={pub}
            isAcquired={purchase.hasActiveWorkflow}
            // Every My-Purchases card is a receipt-holder: render its showcase via the
            // receipt-gated AUTH'D endpoint so the interface still previews after the
            // publisher unpublishes/deletes the source (INACTIVE). Without this the card hits
            // the anonymous /by-id render and 403s "Publication is not publicly available".
            acquired
            onAcquire={canReinstall ? handleReinstall : undefined}
            // Cloud purchase: route the card's showcase render + publisher avatar through
            // the cloud proxy (the synth carries remote=true; local purchases omit it).
            remote={pub.remote}
            // Reinstall in progress → same un-greying preview + gauge as Explore.
            installProgress={isThisInstalling ? activeInstall.progress : null}
            installBlocked={otherInstallRunning}
            // Installed application purchase → jump straight to the app.
            openHref={
              purchase.hasActiveWorkflow && (pub.displayMode || 'WORKFLOW') === 'APPLICATION'
                ? `/app/applications/${pub.id}`
                : undefined
            }
          />
        );
      })}
      {(reinstallTarget || installErrorPublication) && (
        <AcquirePublicationModal
          demoEligible
          isOpen
          inlineProgress
          onClose={() => setReinstallTarget(null)}
          publication={reinstallTarget ?? installErrorPublication!}
          ceMode={remote}
        />
      )}
      {installSummary && (
        <InstallSummaryModal
          publication={installSummary.publication}
          resources={installSummary.resources}
          editableCopy={installSummary.editableCopy}
          onOpenEditableCopy={installSummary.editableCopy?.workflowId
            ? () => {
                const copyId = installSummary.editableCopy!.workflowId!;
                setInstallSummary(null);
                router.push(`/app/workflow/${copyId}`);
              }
            : undefined}
          onClose={() => setInstallSummary(null)}
        />
      )}
    </div>
  );
}

// ============== Main Page ==============

const MARKETPLACE_TABS = ['explore', 'mine', 'purchases'] as const;
type MarketplaceTab = (typeof MARKETPLACE_TABS)[number];

// `remote` - CE cloud-parity mode (see ExploreTab). Only the Explore reads and
// the install path differ; My Publications / My Purchases stay on the local
// CE endpoints (they are local tenant data in both editions).
function MarketplacePageContent({ remote = false }: { remote?: boolean }) {
  const t = useTranslations('marketplace');
  const { isAuthenticated } = useAuth();
  // Read here as well as in ExploreTab: the grid needs the axis to FETCH with it, and the header
  // needs it to SAY so. Both read the URL rather than sharing state, so a shared link opens the
  // same shelf the sender was looking at.
  const marketplaceSearchParams = useSearchParams();
  const studioOnly = marketplaceSearchParams.get('studio') === 'true';
  const marketplacePathname = usePathname();
  const router = useRouter();
  const [requestedTab, setActiveTab] = useQueryParamState<MarketplaceTab>('tab', MARKETPLACE_TABS, 'explore');

  // Defensive: if the user signs out while on a private tab, or deep-links to
  // ?tab=mine without a session, snap back to Explore so we don't fire auth'd
  // API calls from MyPublicationsTab / MyPurchasesTab. Resolved during render
  // (not only in the effect) so the private tab never gets one frame to mount.
  const activeTab: MarketplaceTab = !isAuthenticated && requestedTab !== 'explore' ? 'explore' : requestedTab;

  useEffect(() => {
    if (!isAuthenticated && requestedTab !== 'explore') {
      setActiveTab('explore');
    }
  }, [isAuthenticated, requestedTab, setActiveTab]);

  return (
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          {/* Header + Tabs */}
          <div className="space-y-4">
            <div className="min-w-0">
              <h1 className="text-lg font-semibold text-theme-primary">
                {t('title')}
              </h1>
              <p className="text-sm text-theme-secondary mt-0.5">
                {studioOnly ? t('studioSubtitle') : t('subtitle')}
              </p>
              {/* The narrowing, said out loud. Without this the grid is a marketplace missing most
                  of itself: the category chips still read "All", nothing names the axis, and a
                  visitor who arrived on a shared link has no way to know what they are not seeing,
                  nor how to see it. The chip is therefore also the way OUT of the shelf. */}
              {studioOnly && (
                <button
                  type="button"
                  onClick={() => {
                    const next = new URLSearchParams(marketplaceSearchParams.toString());
                    next.delete('studio');
                    const qs = next.toString();
                    router.replace(qs ? `${marketplacePathname}?${qs}` : marketplacePathname);
                  }}
                  className="mt-2 inline-flex items-center gap-1.5 rounded-full border border-[var(--accent-primary)] bg-[var(--accent-primary)]/10 px-3 py-1 text-sm text-[var(--accent-primary)] hover:bg-[var(--accent-primary)]/20 transition-colors"
                >
                  <Clapperboard className="h-3.5 w-3.5" />
                  {t('studioFilterChip')}
                  <X className="h-3.5 w-3.5" />
                  <span className="sr-only">{t('studioFilterClear')}</span>
                </button>
              )}
            </div>

            {/* Tab bar - My Publications / My Purchases are hidden for anonymous
                visitors because they both call authenticated endpoints and hold
                tenant-scoped data. Anonymous users only see Explore. */}
            <div className="flex items-center gap-1 border-b border-theme">
              <button
                type="button"
                onClick={() => setActiveTab('explore')}
                className={`px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-px ${
                  activeTab === 'explore'
                    ? 'border-[var(--accent-primary)] text-theme-primary'
                    : 'border-transparent text-theme-muted hover:text-theme-primary'
                }`}
              >
                {t('tabExplore')}
              </button>
              {isAuthenticated && (
                <>
                  <button
                    type="button"
                    onClick={() => setActiveTab('mine')}
                    className={`px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-px ${
                      activeTab === 'mine'
                        ? 'border-[var(--accent-primary)] text-theme-primary'
                        : 'border-transparent text-theme-muted hover:text-theme-primary'
                    }`}
                  >
                    {t('tabMyPublications')}
                  </button>
                  <button
                    type="button"
                    onClick={() => setActiveTab('purchases')}
                    className={`px-4 py-2.5 text-sm font-medium transition-all border-b-2 -mb-px ${
                      activeTab === 'purchases'
                        ? 'border-[var(--accent-primary)] text-theme-primary'
                        : 'border-transparent text-theme-muted hover:text-theme-primary'
                    }`}
                  >
                    {t('tabMyPurchases')}
                  </button>
                </>
              )}
            </div>
          </div>

          {/* Tab content */}
          {activeTab === 'explore' && <ExploreTab remote={remote} />}
          {activeTab === 'mine' && <MyPublicationsTab />}
          {activeTab === 'purchases' && <MyPurchasesTab remote={remote} />}
        </div>
      </div>
    </div>
  );
}

export default function MarketplacePage() {
  if (IS_CE) {
    return <CeMarketplaceGate />;
  }
  return <MarketplacePageContent />;
}

// ============== CE gate (cloud parity, 2026-06-10) ==============
//
// A CE install whose cloud link is connected AND registered renders the SAME
// marketplace UI as cloud (tabs + type chips + full presentation), backed by
// the CE backend's /publications/remote/* proxies of the cloud public API.
// Community apps are gated behind an active cloud link: an UNLINKED install
// shows a connect-to-cloud CTA (CeMarketplaceCloudConnect) instead of any
// publications - nothing community is surfaced until the install is linked.

function CeMarketplaceGate() {
  // Gate VISIBILITY on the INSTALL-global link (isInstallCloudLinked): a non-owner
  // member of an admin-linked install inherits the cloud marketplace even though it
  // is not the link owner (isCloudLinked would be false for that member). The
  // connect-to-cloud CTA still shows when the install has no link at all.
  const { isLoading, isInstallCloudLinked } = useCeCloudLinkStatus();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const [completingLink, setCompletingLink] = useState(false);
  // The OAuth state is single-use (the backend consumes it on connect). Guard
  // against completing the same state twice - React 18 StrictMode double-invokes
  // effects in dev, and a re-run would otherwise fire a second, failing connect.
  const completedStateRef = useRef<string | null>(null);
  // ?cloud_link_error=expired: the backend no longer knew the OAuth state of the callback.
  // Read here (not in the CTA) so the parameter is cleaned whichever branch renders.
  const cloudLinkExpired = useCloudLinkExpired();

  // Returning from the cloud connect flow lands here as
  // ?cloud_link_callback=1&state=... (the backend allows the marketplace as a
  // returnPath). Complete the link, refresh the cached status so the gate flips
  // to the linked marketplace, then strip the params from the URL.
  useEffect(() => {
    const state = searchParams.get('state');
    const isCallback = searchParams.get('cloud_link_callback') === '1';
    if (!isCallback || !state || completedStateRef.current === state) return;
    completedStateRef.current = state;
    let cancelled = false;
    setCompletingLink(true);
    (async () => {
      try {
        await cloudLinkService.connect(state);
        // Linking changes the executable model catalog (BYOK -> cloud): drop the
        // cached (possibly empty) list so every picker refetches the cloud one.
        clearModelsCache();
        await queryClient.invalidateQueries({ queryKey: ['cloud-link', 'status'] });
      } catch {
        // fail-soft: fall back to the connect CTA so the user can retry
      } finally {
        if (!cancelled) {
          window.history.replaceState({}, '', window.location.pathname);
          setCompletingLink(false);
        }
      }
    })();
    return () => { cancelled = true; };
  }, [searchParams, queryClient]);

  if (isLoading || completingLink) {
    return <CeMarketplaceGateSkeleton />;
  }
  if (isInstallCloudLinked) {
    return <MarketplacePageContent remote />;
  }
  return <CeMarketplaceCloudConnect expired={cloudLinkExpired} />;
}

// Same chrome as both branches (header + 4-col card grid) so resolving the
// link status doesn't visibly reflow the page.
function CeMarketplaceGateSkeleton() {
  const t = useTranslations('marketplace');
  return (
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-theme-primary">{t('title')}</h1>
            <p className="text-sm text-theme-secondary mt-0.5">{t('subtitle')}</p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
            {Array.from({ length: 16 }, (_, i) => (
              <PublicationCardSkeleton key={i} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// ============== CE connect-to-cloud CTA (unlinked install) ==============
//
// An unlinked CE has no access to the community marketplace. Instead of an
// error or any (local/public) publications, we show a single connect-to-cloud
// call to action. The button starts the OAuth link flow with the marketplace
// as the returnPath, so the install returns here once linked and the gate
// flips to the full cloud marketplace (CeMarketplaceGate handles the callback).
function CeMarketplaceCloudConnect({ expired = false }: { expired?: boolean }) {
  const t = useTranslations('marketplace');
  const locale = useLocale();
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleConnect = useCallback(async () => {
    setConnecting(true);
    setError(null);
    try {
      window.location.href = await cloudLinkService.getConnectUrl(`/${locale}/app/marketplace`);
    } catch {
      setError(t('cloudConnect.error'));
      setConnecting(false);
    }
  }, [locale, t]);

  return (
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          {/* Header - same chrome as the linked marketplace. */}
          <div className="min-w-0">
            <h1 className="text-lg font-semibold text-theme-primary">{t('title')}</h1>
            <p className="text-sm text-theme-secondary mt-0.5">{t('subtitle')}</p>
          </div>

          {/* Connect-to-cloud CTA - replaces every community publication until linked. */}
          <div className="flex flex-col items-center justify-center text-center py-20 px-6">
            <div className="w-16 h-16 rounded-2xl bg-[var(--accent-primary)]/10 flex items-center justify-center mb-5">
              <Cloud className="h-8 w-8 text-[var(--accent-primary)]" />
            </div>
            <h2 className="text-base font-semibold text-theme-primary mb-2">
              {t('cloudConnect.title')}
            </h2>
            <p className="text-sm text-theme-secondary max-w-md mb-6">
              {t('cloudConnect.body')}
            </p>
            {error && (
              <p className="text-sm text-red-500 mb-4">{error}</p>
            )}
            {!error && expired && <CloudLinkExpiredNotice className="mb-4 max-w-md" />}
            <Button onClick={handleConnect} disabled={connecting}>
              {connecting ? (
                <LoadingSpinner size="xs" className="mr-2" />
              ) : (
                <Cloud className="h-4 w-4 mr-2" />
              )}
              {t('cloudConnect.button')}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
