'use client';

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useSearchParams, useRouter, usePathname } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { Search, Package, ShoppingBag, Bot, Zap, Monitor, Table, AppWindow, Eye, Cloud } from 'lucide-react';
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
import { useMarketplaceInstallStore } from '@/lib/stores/marketplace-install-store';
import { IS_CE } from '@/lib/edition';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { cloudLinkService } from '@/lib/api/cloud-link.service';
import { clearModelsCache } from '@/hooks/useModels';
import { PublicationCard, PublicationCardSkeleton } from '@/components/marketplace/PublicationCard';

// Card + preview helpers extracted to a shared component so the onboarding
// "suggested apps" modal reuses the exact same markup (no style fork).

const DISPLAY_FILTERS = ['apps', 'agents', 'interfaces', 'tables', 'skills'] as const;
type DisplayFilter = (typeof DISPLAY_FILTERS)[number];

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
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const raw = searchParams.get(key);
  const value = (allowed as readonly string[]).includes(raw ?? '') ? (raw as T) : fallback;

  const setValue = useCallback((next: T) => {
    const params = new URLSearchParams(searchParams.toString());
    if (next === fallback) params.delete(key);
    else params.set(key, next);
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  }, [router, pathname, searchParams, key, fallback]);

  return [value, setValue];
}

// ============== Explore Tab ==============

// `remote` (CE cloud-parity, 2026-06-10): a cloud-linked CE renders this exact
// tab, but the explore reads come from the CE backend's /publications/remote/*
// proxies of the cloud public API (single cloud origin = marketplace.cloud-api-url)
// and installs go through the CE remote acquire path (ceMode).
function ExploreTab({ remote = false }: { remote?: boolean }) {
  const t = useTranslations('marketplace');
  const { isLoading: isAuthLoading, isAuthenticated, numericUserId } = useAuth();
  const [publications, setPublications] = useState<WorkflowPublication[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | undefined>();
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
  const [displayFilter, setDisplayFilter] = useQueryParamState<DisplayFilter>('type', DISPLAY_FILTERS, 'apps');
  // Applications and Agents are surfaced in the marketplace via the type-filter chips below.
  // Interfaces / Tables / Skills stay hidden for now (their logic is intact - add them to the
  // chip list below to surface them once ready). The backend marketplace query returns ALL
  // ACTIVE+PUBLIC types, so this is purely which chips the user can pick.
  const SHOW_DISPLAY_FILTERS = true;

  // Phase 6 (2026-05-18) - `acquiredIds` is workspace-bound (each workspace
  // owns its own publication acquisitions); reset on switch so explore
  // doesn't hide rows acquired in another workspace.
  useOrgScopedReset(() => {
    setPublications([]);
    setAcquiredIds(new Set());
    setJustInstalledIds(new Set());
    setError(null);
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

  const fetchPublications = useCallback(async (categorySlug?: string) => {
    setIsLoading(true);
    setError(null);
    try {
      const response = remote
        ? await publicationService.getRemoteMarketplacePublications(0, 50, categorySlug)
        : await orchestratorApi.getMarketplacePublications(0, 50, categorySlug);
      setPublications(response.publications || []);
    } catch (err: any) {
      console.error('Error fetching marketplace publications:', err);
      setError(err.message || t('loadError'));
    } finally {
      setIsLoading(false);
    }
  }, [remote, t]);

  const searchPublications = useCallback(async (query: string) => {
    if (!query.trim()) {
      fetchPublications(selectedCategory);
      return;
    }
    setIsLoading(true);
    setError(null);
    try {
      // Forward the active category so typing into the search bar doesn't
      // silently drop a pre-selected category filter (D-3 bug fix).
      const results = remote
        ? await publicationService.searchRemotePublications(query, selectedCategory)
        : await orchestratorApi.searchPublications(query, selectedCategory);
      setPublications(results?.publications || []);
    } catch (err: any) {
      console.error('Error searching publications:', err);
      setError(err.message || t('searchError'));
    } finally {
      setIsLoading(false);
    }
  }, [fetchPublications, remote, selectedCategory, t]);

  const handleCategoryChange = useCallback((categorySlug?: string) => {
    setSelectedCategory(categorySlug);
    setSearchQuery('');
  }, []);

  const initialLoadDone = useRef(false);

  useEffect(() => {
    if (!isAuthLoading) fetchAcquiredIds();
  }, [isAuthLoading, fetchAcquiredIds]);

  useEffect(() => {
    if (isAuthLoading) return;
    const timer = setTimeout(() => {
      if (searchQuery) {
        searchPublications(searchQuery);
      } else {
        fetchPublications(selectedCategory);
      }
      initialLoadDone.current = true;
    }, initialLoadDone.current ? 300 : 0);
    return () => clearTimeout(timer);
  }, [isAuthLoading, searchQuery, searchPublications, fetchPublications, selectedCategory]);

  // Install completed (the machine runs in the shared store, the modal is
  // already closed): flip the card to installed/"Open" right away, refresh the
  // server-side acquired set, then CONSUME the success so the progress overlay
  // (parked at 100%) disappears. consumeSuccess (not clear) because the finally
  // runs after an async refetch: by then the user may have started installing
  // another app, and clear() would kill that machine.
  useEffect(() => {
    if (activeInstall?.status !== 'success') return;
    const pubId = activeInstall.publication.id;
    setJustInstalledIds((prev) => {
      const next = new Set(prev);
      next.add(pubId);
      return next;
    });
    void fetchAcquiredIds().finally(() => consumeInstallSuccess(pubId));
  }, [activeInstall?.status, activeInstall?.publication.id, fetchAcquiredIds, consumeInstallSuccess]);

  // Terminal install errors surface through the SAME modal (error /
  // link-required / insufficient-credits screens): re-mount it while the store
  // holds one - also catches installs started from the preview page, whose
  // modal unmounted on navigation back to this page.
  const installErrorPublication =
    activeInstall && activeInstall.status !== 'installing' && activeInstall.status !== 'success'
      ? activeInstall.publication
      : null;


  const filteredPublications = useMemo(() => {
    return publications.filter((p) => {
      const mode = p.displayMode || 'WORKFLOW';
      if (displayFilter === 'agents') return mode === 'AGENT';
      if (displayFilter === 'apps') return mode === 'APPLICATION';
      if (displayFilter === 'interfaces') return mode === 'INTERFACE';
      if (displayFilter === 'tables') return mode === 'TABLE';
      return mode === 'SKILL';
    });
  }, [publications, displayFilter]);

  return (
    <div className="space-y-5">
      {/* Search + Category filter */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1">
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
      </div>

      {/* Display filter chips - always scoped to a single resource type (icons mirror the left sidebar). */}
      {/* Applications + Agents surfaced for now; Interfaces/Tables/Skills kept in the logic but off the list. */}
      {SHOW_DISPLAY_FILTERS && (
      <div className="flex items-center flex-wrap gap-2">
        {DISPLAY_FILTERS
          .filter((filter) => filter === 'apps' || filter === 'agents')
          .map((filter) => {
          const isActive = displayFilter === filter;
          const label =
            filter === 'apps' ? t('filterApplications')
            : filter === 'agents' ? t('filterAgents')
            : filter === 'interfaces' ? t('filterInterfaces')
            : filter === 'tables' ? t('filterTables')
            : t('filterSkills');
          const icon =
            filter === 'agents' ? <Bot className="h-3.5 w-3.5" />
            : filter === 'apps' ? <AppWindow className="h-3.5 w-3.5" />
            : filter === 'interfaces' ? <Monitor className="h-3.5 w-3.5" />
            : filter === 'tables' ? <Table className="h-3.5 w-3.5" />
            : <Zap className="h-3.5 w-3.5" />;
          return (
            <button
              key={filter}
              type="button"
              onClick={() => setDisplayFilter(filter)}
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
      )}

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

      {!isLoading && filteredPublications.length === 0 && (() => {
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
        return (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-14 h-14 bg-theme-tertiary rounded-full flex items-center justify-center mb-4">
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

      {!isLoading && filteredPublications.length > 0 && (
        <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
          {filteredPublications.map((publication) => {
            const isInstalled = acquiredIds.has(publication.id) || justInstalledIds.has(publication.id);
            const isThisInstalling =
              activeInstall?.publication.id === publication.id &&
              (activeInstall.status === 'installing' || activeInstall.status === 'success');
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

      {/* Install modal - remote (linked CE) installs go through the CE remote
          acquire path (/publications/remote/{id}/acquire) via ceMode.
          inlineProgress: confirming closes the modal and the card renders the
          progress; the modal re-mounts by itself (installErrorPublication) to
          show terminal error screens. */}
      {(acquireTarget || installErrorPublication) && (
        <AcquirePublicationModal
          isOpen
          inlineProgress
          onClose={() => setAcquireTarget(null)}
          publication={acquireTarget ?? installErrorPublication!}
          ceMode={remote}
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
          <div className="w-14 h-14 bg-theme-tertiary rounded-full flex items-center justify-center mb-4">
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

  // A workspace switch orphans any in-flight install started here.
  useOrgScopedReset(() => {
    clearInstall();
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
    setReinstallTarget(null);
    void fetchPurchases().finally(() => consumeInstallSuccess(pubId));
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
        <div className="w-14 h-14 bg-theme-tertiary rounded-full flex items-center justify-center mb-4">
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
          isOpen
          inlineProgress
          onClose={() => setReinstallTarget(null)}
          publication={reinstallTarget ?? installErrorPublication!}
          ceMode={remote}
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
                {t('subtitle')}
              </p>
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
  return <CeMarketplaceCloudConnect />;
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
function CeMarketplaceCloudConnect() {
  const t = useTranslations('marketplace');
  const locale = useLocale();
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleConnect = useCallback(async () => {
    setConnecting(true);
    setError(null);
    try {
      const { authUrl } = await cloudLinkService.getAuthUrl(`/${locale}/app/marketplace`);
      window.location.href = authUrl;
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
            <div className="w-16 h-16 rounded-full bg-[var(--accent-primary)]/10 flex items-center justify-center mb-5">
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
