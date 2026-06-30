'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowRight, Coins, Gift, Monitor, Network, Package, Star, Table, Workflow, Zap } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import type { WorkflowPublication, AcquiredApplication } from '@/lib/api/orchestrator/types';
import { favoriteService } from '@/lib/api/orchestrator/favorite.service';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { InterfacePreview, type InterfaceSnapshotLike } from '@/components/marketplace/InterfacePreview';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { AvatarDisplay } from '@/components/agents';
import { isCeMode } from '@/lib/format-cost';
import { IS_CE } from '@/lib/edition';
import { useCeCloudLinkStatus } from '@/hooks/useCeCloudLinkStatus';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * Highlighted-this-week row rendered in the chat welcome view.
 *
 * Fetches real marketplace publications from the public
 * `/api/publications/marketplace` endpoint (visible to both authenticated and
 * anonymous visitors). When the catalog is empty or the fetch fails the whole
 * section is hidden - we never synthesize fake "sample" tiles, because they
 * would mislead anonymous visitors into thinking these apps exist.
 */

interface DisplayPub {
  id: string;
  title: string;
  description?: string;
  publisherId?: string;
  publisherName?: string;
  creditsPerUse: number;
  displayMode: 'WORKFLOW' | 'INTERFACE' | 'APPLICATION' | 'AGENT' | 'TABLE' | 'SKILL';
  agentAvatarUrl?: string;
  real: WorkflowPublication;
}

function PublicationThumb({ pub, remote, ownerView }: { pub: DisplayPub; remote?: boolean; ownerView?: boolean }) {
  const isAgent = pub.displayMode === 'AGENT';
  const hasLanding = pub.displayMode !== 'WORKFLOW';
  const [landing, setLanding] = useState<InterfaceSnapshotLike | null>(null);
  const [loaded, setLoaded] = useState(false);
  // Owner view (the Favorites row): a favorited app is in the user's OWN library,
  // so its showcase run belongs to them. Render it through the authenticated
  // per-run path (publicationId omitted) so a PRIVATE own app previews exactly
  // like /app/applications, instead of the public publication-scoped render which
  // rejects non-public apps ("Publication is not publicly available"). Fall back
  // to the public render only when the authenticated read fails (an ACQUIRED
  // favorite's showcase run lives in the publisher's tenant). Highlights
  // (marketplace discovery tiles) are public, so they keep the public path.
  const [ownerRenderFailed, setOwnerRenderFailed] = useState(false);
  const real = pub.real;

  useEffect(() => {
    if (!hasLanding) { setLoaded(true); return; }
    if (real.showcaseRunId && real.showcaseInterfaceId) { setLoaded(true); return; }
    let cancelled = false;
    // No-showcase-run apps fall back to the public landing snapshot even in owner
    // view. For a PRIVATE own app this read 403s, but it is swallowed below and
    // degrades to the cover tile (no visible error) - the showcase path above is
    // the owner-aware one; this branch only ever yields a generic tile for them.
    publicationService.getLandingSnapshot(real.id, remote)
      .then(res => { if (!cancelled) setLanding(res.landing); })
      .catch(() => { if (!cancelled) setLanding(null); })
      .finally(() => { if (!cancelled) setLoaded(true); });
    return () => { cancelled = true; };
  }, [real, hasLanding, remote]);

  if (real.showcaseRunId && real.showcaseInterfaceId) {
    const useAuthRender = !!ownerView && !ownerRenderFailed;
    return (
      <ShowcasePreview
        runId={real.showcaseRunId}
        interfaceId={real.showcaseInterfaceId}
        // Owner view: authenticated per-run render (no publicationId) so the
        // user's own private app previews; on failure fall back to the public
        // render (acquired favorite = publisher's run, cross-tenant).
        publicationId={useAuthRender ? undefined : real.id}
        remote={useAuthRender ? false : remote}
        onError={useAuthRender ? () => setOwnerRenderFailed(true) : undefined}
        suppressErrorUi={useAuthRender}
        className="absolute inset-0 h-full w-full"
      />
    );
  }

  if (!loaded) {
    return <div className="absolute inset-0 animate-pulse bg-slate-200 dark:bg-slate-700" />;
  }

  if (isAgent) {
    return (
      <div className="relative w-full h-full bg-white dark:bg-slate-900">
        {landing ? (
          <InterfacePreview snapshot={landing} className="absolute inset-0 h-full w-full" />
        ) : (
          <div className="absolute inset-0 bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900" />
        )}
        {landing && <div className="absolute inset-0 bg-gradient-to-b from-white/10 via-white/30 to-white/70 dark:from-slate-900/10 dark:via-slate-900/40 dark:to-slate-900/80" />}
        <div className="absolute inset-0 z-10 flex items-center justify-center">
          <AvatarDisplay avatarUrl={real.agentAvatarUrl} name={real.title} size="xl" />
        </div>
      </div>
    );
  }

  if (hasLanding && landing) {
    return (
      <div className="relative w-full h-full">
        <InterfacePreview snapshot={landing} className="absolute inset-0 h-full w-full" />
      </div>
    );
  }

  // workflow or missing landing
  return (
    <div className="relative w-full h-full bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 grid place-items-center">
      <div
        className="absolute inset-0 opacity-60 dark:opacity-30"
        style={{ backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)', backgroundSize: '16px 16px' }}
      />
      <div className="relative z-10">
        {real.nodeIcons && real.nodeIcons.length > 0 ? (
          <WorkflowNodeIcons nodeIcons={real.nodeIcons} />
        ) : (
          <div className="w-14 h-14 bg-white dark:bg-slate-800 rounded-full flex items-center justify-center shadow-sm">
            <Package className="w-7 h-7 text-theme-tertiary" />
          </div>
        )}
      </div>
    </div>
  );
}

function HighlightCard({ pub, remote, target = 'marketplace' }: { pub: DisplayPub; remote?: boolean; target?: 'marketplace' | 'application' }) {
  const t = useTranslations('marketplace');
  const tHl = useTranslations('chat.highlights');
  const isFree = !pub.creditsPerUse || pub.creditsPerUse === 0;
  const priceLabel = isFree
    ? t('free')
    : isCeMode ? `$${pub.creditsPerUse}` : `${pub.creditsPerUse} ${t('credits')}`;
  const PriceIcon = isFree ? Gift : Coins;

  const countBadges = useMemo(() => {
    const r = pub.real;
    if (r.displayMode !== 'AGENT') return [];
    return [
      { icon: Network, count: r.agentCount || 0 },
      { icon: Zap, count: r.skillCount || 0 },
      { icon: Workflow, count: r.workflowCount || 0 },
      { icon: Monitor, count: r.interfaceCount || 0 },
      { icon: Table, count: r.datasourceCount || 0 },
    ].filter(b => b.count > 0);
  }, [pub]);

  // Favorites are apps in the user's own library, so they open at the app page;
  // highlights are discovery tiles, so they open the marketplace preview.
  const href = target === 'application'
    ? `/app/applications/${pub.id}`
    : pub.displayMode === 'AGENT'
      ? `/app/marketplace/agents/${pub.id}`
      : `/app/marketplace/${pub.id}/preview`;

  return (
    <Link href={href} className="group block">
      {/* Thumbnail - fixed 16:10 aspect, framed card */}
      <div
        className="relative overflow-hidden rounded-[20px] border border-theme bg-theme-tertiary"
        style={{ aspectRatio: '16 / 10' }}
      >
        <div className="absolute inset-0">
          <PublicationThumb pub={pub} remote={remote} ownerView={target === 'application'} />
        </div>

        {/* price pill - hidden by default, revealed on hover/focus */}
        <span className="absolute top-3 left-3 z-20 inline-flex items-center gap-1 h-[22px] px-2 rounded-md text-xs font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity duration-200">
          <PriceIcon className="h-3 w-3" />
          {priceLabel}
        </span>
      </div>

      {/* Footer - always visible below the thumbnail (mirrors /app/interface card layout) */}
      <div className="px-1 pt-2 pb-1 space-y-1">
        <h3 className="text-sm font-medium text-theme-primary truncate">{pub.title}</h3>
        {pub.description && (
          <p className="text-xs text-theme-muted line-clamp-2 leading-snug">{pub.description}</p>
        )}
        <div className="flex items-center gap-1.5 min-w-0 pt-0.5">
          <PublisherAvatar userId={pub.publisherId} name={pub.publisherName} remote={remote} />
          <span className="text-xs text-theme-secondary truncate">
            {pub.publisherName || t('anonymous')}
          </span>
          {pub.real.nodeIcons && pub.real.nodeIcons.length > 0 && (
            <WorkflowNodeIcons
              nodeIcons={pub.real.nodeIcons}
              maxDisplay={3}
              prioritizeMcpAndTriggers
              size="inline"
              className="ml-auto shrink-0"
            />
          )}
        </div>
        {countBadges.length > 0 && (
          <div className="flex items-center gap-2 pt-0.5">
            {countBadges.map((b, i) => (
              <span key={i} className="inline-flex items-center gap-0.5 text-xs text-theme-muted">
                <b.icon className="h-3 w-3" />
                {b.count}
              </span>
            ))}
          </div>
        )}
      </div>
    </Link>
  );
}

function toDisplayPub(p: WorkflowPublication): DisplayPub {
  return {
    id: p.id,
    title: p.title,
    description: p.description,
    publisherId: p.publisherId,
    publisherName: p.publisherName,
    creditsPerUse: p.creditsPerUse || 0,
    displayMode: p.displayMode || 'WORKFLOW',
    agentAvatarUrl: p.agentAvatarUrl,
    real: p,
  };
}

// Skeleton mirrors the live card layout: 16:10 thumbnail + footer rows below.
function HighlightCardSkeleton() {
  return (
    <div className="block">
      <div
        className="relative overflow-hidden rounded-[20px] border border-theme bg-theme-tertiary animate-pulse"
        style={{ aspectRatio: '16 / 10' }}
      />
      <div className="px-1 pt-2 pb-1 space-y-1.5">
        <div className="h-4 w-2/3 bg-theme-tertiary rounded animate-pulse" />
        <div className="h-3 w-full bg-theme-tertiary rounded animate-pulse" />
        <div className="h-3 w-1/3 bg-theme-tertiary rounded animate-pulse" />
      </div>
    </div>
  );
}

function HighlightRow({
  heading,
  items,
  isLoading,
  href,
  ctaLabel,
  remote,
  target,
  toggle,
}: {
  heading: string;
  items: DisplayPub[];
  isLoading: boolean;
  href: string;
  ctaLabel: string;
  remote?: boolean;
  target?: 'marketplace' | 'application';
  toggle?: React.ReactNode;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between mb-4 gap-3 flex-wrap">
        <div className="flex items-center gap-3 min-w-0">
          <h2 className="text-sm font-semibold tracking-tight text-theme-primary">{heading}</h2>
          {toggle}
        </div>
        <Link
          href={href}
          className="text-xs text-theme-secondary hover:text-theme-primary inline-flex items-center gap-1 whitespace-nowrap"
        >
          {ctaLabel}
          <ArrowRight className="h-3 w-3" />
        </Link>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
        {isLoading
          ? Array.from({ length: 4 }, (_, i) => <HighlightCardSkeleton key={i} />)
          : items.map(p => <HighlightCard key={p.id} pub={p} remote={p.real.remote ?? remote} target={target} />)}
      </div>
    </div>
  );
}

type HighlightMode = 'HIGHLIGHTS' | 'FAVORITES';

// Persist the user's last explicit Highlights/Favorites pick so the Home row
// reopens on their last choice across reloads and navigations. Reads/writes are
// guarded for SSR (no window) and for browsers that throw on localStorage
// (private mode / disabled storage) so the toggle never breaks.
const HIGHLIGHT_MODE_STORAGE_KEY = 'lc.home.highlightMode';

function readStoredHighlightMode(): HighlightMode | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(HIGHLIGHT_MODE_STORAGE_KEY);
    return raw === 'FAVORITES' || raw === 'HIGHLIGHTS' ? raw : null;
  } catch {
    return null;
  }
}

function writeStoredHighlightMode(mode: HighlightMode): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(HIGHLIGHT_MODE_STORAGE_KEY, mode);
  } catch {
    // Quota / private-mode errors must not break the toggle.
  }
}

export function HighlightedApps() {
  const tHl = useTranslations('chat.highlights');
  const { isAuthenticated, isReady } = useAuthGuard();
  // CE cloud-parity (2026-06-10): a cloud-linked CE shows the SAME curated
  // highlights row as cloud, read through the CE backend's
  // /publications/remote/* proxies of the cloud public API. Community apps are
  // gated behind an active cloud link - an UNLINKED CE surfaces nothing here
  // (the marketplace page shows a connect-to-cloud CTA instead).
  // Gate on the INSTALL-global link (isInstallCloudLinked), NOT the per-user link: a non-owner
  // member of an admin-linked install inherits cloud visibility (linked=false but
  // installLinked=true), so the Home highlights row must show for them too - the SAME rule the
  // marketplace gate (CeMarketplaceGate) and the sidebar plan badge already use. Using the
  // per-user isCloudLinked hid the row for every invited member.
  const { isLoading: isLinkLoading, isInstallCloudLinked } = useCeCloudLinkStatus();
  const ceUnlinked = IS_CE && !isLinkLoading && !isInstallCloudLinked;
  const useRemoteSource = IS_CE && isInstallCloudLinked;
  // Favorites are workspace-scoped, so re-fetch them when the active workspace
  // changes (otherwise the row keeps the prior workspace's favorites until remount).
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);

  // Admin-curated highlights (shown to everyone, anonymous included).
  const [highlights, setHighlights] = useState<DisplayPub[]>([]);
  const [highlightsLoading, setHighlightsLoading] = useState(true);
  // The signed-in user's personal favorites (always the LOCAL list, even on a
  // cloud-linked CE - a user favorites apps in their own library).
  const [favorites, setFavorites] = useState<DisplayPub[]>([]);
  const [favoritesLoading, setFavoritesLoading] = useState(false);

  const [mode, setMode] = useState<HighlightMode>('HIGHLIGHTS');
  // An explicit toggle by the user wins over the favorites-first auto-default,
  // until favorites empty out (then we reset, so re-favoriting leads with FAVORITES again).
  const userPickedRef = useRef(false);
  // The user's persisted pick (localStorage). Once set it is authoritative: it
  // wins over both the HIGHLIGHTS default and the favorites-first auto-default,
  // so the row reopens on the last choice. null = the user never toggled (keep
  // the original auto-default behaviour).
  const storedPrefRef = useRef<HighlightMode | null>(null);

  const hasFavorites = favorites.length > 0;
  const canToggle = isAuthenticated && hasFavorites;

  useEffect(() => {
    // CE: wait until the cloud-link status resolves before picking a data
    // source, so a linked install never flashes the (empty) local result.
    if (IS_CE && isLinkLoading) return;
    // CE not cloud-linked → never fetch community apps; the row stays hidden.
    if (ceUnlinked) {
      setHighlights([]);
      setHighlightsLoading(false);
      return;
    }
    let cancelled = false;
    setHighlightsLoading(true);
    // Admin-curated highlights row. The endpoint is public (anonymous-accessible)
    // and cached server-side (Caffeine, 60s). Server filters to ACTIVE+PUBLIC.
    // Fail-closed: any error, hide the section. No synthetic samples - they
    // misled anonymous visitors into thinking the apps existed.
    (async () => {
      try {
        // 1. Try the admin-curated highlights row first (PublicHighlight DTO).
        const curated = useRemoteSource
          ? await publicationService.getRemoteHighlights('APPLICATION')
          : await publicationService.getHighlights('APPLICATION');
        const curatedApps = (curated.highlights || [])
          .map(h => h.publication as WorkflowPublication | null)
          .filter((p): p is WorkflowPublication => !!p)
          .map(toDisplayPub);

        if (curatedApps.length > 0) {
          if (cancelled) return;
          setHighlights(curatedApps);
          return;
        }

        // 2. Fallback to the top of the public marketplace (legacy behaviour).
        // `/api/publications/marketplace` is a public gateway endpoint, so this
        // works for authenticated and anonymous visitors alike.
        const res = useRemoteSource
          ? await publicationService.getRemoteMarketplacePublications(0, 24)
          : await orchestratorApi.getMarketplacePublications(0, 24);
        const all = (res.publications || []).map(toDisplayPub);
        const apps = all
          .filter(p => p.displayMode === 'APPLICATION'
                   || p.displayMode === 'INTERFACE' || p.displayMode === 'TABLE' || p.displayMode === 'SKILL')
          .slice(0, 4);
        if (cancelled) return;
        setHighlights(apps);
      } catch {
        if (cancelled) return;
        setHighlights([]); // fail-closed: hide the section on error
      } finally {
        if (!cancelled) setHighlightsLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [isAuthenticated, isReady, isLinkLoading, useRemoteSource, ceUnlinked]);

  // Personal favorites - authenticated only. Hydrated server-side (deleted /
  // deactivated apps already dropped) so it renders directly. Fail-closed to empty.
  useEffect(() => {
    if (!isReady || !isAuthenticated) {
      setFavorites([]);
      setFavoritesLoading(false);
      return;
    }
    let cancelled = false;
    setFavoritesLoading(true);
    // Favorites come from TWO stores (mirrors /app/applications): PUBLICATION favorites
    // (published / local apps) AND native WORKFLOW favorites (acquired apps - a cloud-
    // acquired app's publication id is remote/absent-locally so it's favorited by its
    // local clone). Merge both so a favorited downloaded app shows in Home like on cloud.
    Promise.all([
      publicationService.getFavorites().then(r => r.favorites || []).catch(() => [] as unknown[]),
      favoriteService.getFavoriteIds('WORKFLOW').catch(() => [] as string[]),
      publicationService.getAcquiredApplicationsPage({ page: 0, size: 100 })
        .then(r => r.items || []).catch(() => [] as AcquiredApplication[]),
    ])
      .then(async ([pubFavs, wfFavIds, acquired]) => {
        if (cancelled) return;
        const wfSet = new Set(wfFavIds);
        const pubCards = pubFavs.map(p => toDisplayPub(p as unknown as WorkflowPublication));
        const seen = new Set(pubCards.map(c => c.id));
        // Acquired apps whose local CLONE is workflow-favorited (and not already covered
        // by a publication favorite). Enrich cloud (remote) ones via the proxy so the Home
        // thumbnail renders the interface instead of the cover tile.
        const favAcquired = acquired.filter(a =>
          a.workflowId && wfSet.has(a.workflowId) && a.publication && !seen.has(a.publication.id));
        const acquiredCards = await Promise.all(favAcquired.map(async a => {
          let p = a.publication as WorkflowPublication;
          if (p.remote) {
            try {
              const full = await publicationService.getPublicationByIdPublic(p.id, true);
              p = { ...p, ...full, remote: true };
            } catch { /* cloud miss -> keep the synth (cover tile) */ }
          }
          return toDisplayPub(p);
        }));
        if (cancelled) return;
        // Cap the Home row at 8 favorites; the "see all" CTA links to the full list.
        setFavorites([...pubCards, ...acquiredCards].slice(0, 8));
      })
      .catch(() => { if (!cancelled) setFavorites([]); })
      .finally(() => { if (!cancelled) setFavoritesLoading(false); });
    return () => { cancelled = true; };
  }, [isAuthenticated, isReady, currentOrgId]);

  // Restore the persisted pick once on mount (client only). Declared BEFORE the
  // favorites-first effect so storedPrefRef is populated before that effect reads it.
  useEffect(() => {
    const stored = readStoredHighlightMode();
    storedPrefRef.current = stored;
    if (stored) {
      userPickedRef.current = true;
      setMode(stored);
    }
  }, []);

  // Favorites-first default, applied ONLY when the user has no persisted pick:
  // once the user has favorites and hasn't explicitly chosen, lead with Favorites;
  // when favorites empty out, fall back to Highlights and reset the manual flag so
  // a later re-favorite defaults to Favorites again. A persisted explicit pick is
  // authoritative and short-circuits this whole effect (the stored value is left
  // intact); the display still guards on canToggle, so an empty favorites list
  // shows Highlights regardless of a stored FAVORITES pick.
  useEffect(() => {
    if (storedPrefRef.current) return;
    if (!hasFavorites) {
      userPickedRef.current = false;
      setMode('HIGHLIGHTS');
    } else if (!userPickedRef.current) {
      setMode('FAVORITES');
    }
  }, [hasFavorites]);

  const pickMode = (m: HighlightMode) => {
    userPickedRef.current = true;
    storedPrefRef.current = m;
    writeStoredHighlightMode(m);
    setMode(m);
  };

  // CE not cloud-linked → never surface community apps here.
  if (ceUnlinked) {
    return null;
  }

  const showFavorites = mode === 'FAVORITES' && canToggle;
  const items = showFavorites ? favorites : highlights;
  const isLoading = showFavorites ? favoritesLoading : highlightsLoading;

  // Hide the whole section only when neither source has anything to show.
  if (!highlightsLoading && !favoritesLoading && highlights.length === 0 && favorites.length === 0) {
    return null;
  }

  // The mode toggle is hidden until the row is hovered/focused, and is only
  // rendered at all when the user actually has favorites to switch to.
  const toggle = canToggle ? (
    <div className="opacity-0 group-hover/hl:opacity-100 focus-within:opacity-100 transition-opacity duration-200 inline-flex items-center gap-0.5 rounded-lg border border-theme bg-theme-tertiary p-0.5">
      <button
        type="button"
        onClick={() => pickMode('FAVORITES')}
        aria-pressed={showFavorites}
        className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium transition-colors ${showFavorites ? 'bg-theme-primary text-theme-primary shadow-sm' : 'text-theme-secondary hover:text-theme-primary'}`}
      >
        <Star className={`h-3 w-3 ${showFavorites ? 'fill-current' : ''}`} />
        {tHl('favorites')}
      </button>
      <button
        type="button"
        onClick={() => pickMode('HIGHLIGHTS')}
        aria-pressed={!showFavorites}
        className={`rounded-md px-2 py-0.5 text-xs font-medium transition-colors ${!showFavorites ? 'bg-theme-primary text-theme-primary shadow-sm' : 'text-theme-secondary hover:text-theme-primary'}`}
      >
        {tHl('title')}
      </button>
    </div>
  ) : null;

  return (
    <section className="group/hl w-full max-w-6xl mx-auto px-6 mt-8 md:mt-10 space-y-10">
      <HighlightRow
        heading={showFavorites ? tHl('favorites') : tHl('title')}
        items={items}
        isLoading={isLoading}
        href={showFavorites ? '/app/applications' : '/app/marketplace'}
        ctaLabel={showFavorites ? tHl('allFavorites') : tHl('all')}
        remote={showFavorites ? false : useRemoteSource}
        target={showFavorites ? 'application' : 'marketplace'}
        toggle={toggle}
      />
    </section>
  );
}

export default HighlightedApps;
