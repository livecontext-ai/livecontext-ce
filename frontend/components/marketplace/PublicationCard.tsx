'use client';

import { memo, useState, useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Coins, Gift, Package, Download, CheckCircle, Network, Zap, Workflow, Monitor, Table, Star, ArrowUpRight } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { track } from '@/lib/analytics/analytics';
import type { WorkflowPublication } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { InterfacePreview, type InterfaceSnapshotLike } from '@/components/marketplace/InterfacePreview';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { UserActionMenu } from '@/components/profile/UserActionMenu';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { AvatarDisplay } from '@/components/agents';
import { VisibilityBadge } from '@/components/ui/VisibilityBadge';
import { isCeMode } from '@/lib/format-cost';

// ============================================================================
// Shared marketplace publication card.
//
// Extracted verbatim from app/[locale]/app/marketplace/page.tsx so the exact
// same card (thumbnail + footer) can be reused outside the marketplace grid
// (e.g. the onboarding "suggested apps" modal) WITHOUT duplicating any style.
// The marketplace page imports these back; do not fork the markup.
// ============================================================================

// ============== Skeleton ==============
// Mirrors the live card layout: 16:10 thumbnail + footer rows below
// (matches /app/interface card shape).

export function PublicationCardSkeleton() {
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

// ============== Price pill (top-left chip) ==============
// Small translucent pill that sits in the top-left corner of every card. Because the
// Explore tab is always scoped to a single resource type (the filter chips no longer
// expose an "All" option), the type chip that used to live here was redundant - the
// price is the more useful thing to surface at rest.

export function PricePill({ publication, isFree }: { publication: WorkflowPublication; isFree: boolean }) {
  const t = useTranslations('marketplace');
  const Icon = isFree ? Gift : Coins;
  const label = isFree
    ? t('free')
    : (isCeMode ? `$${publication.creditsPerUse}` : `${publication.creditsPerUse} ${t('credits')}`);
  return (
    <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-md text-[11px] font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10">
      <Icon className="h-3 w-3" />
      {label}
    </span>
  );
}

// ============== Preview dispatcher ==============
// Picks between ShowcasePreview (workflow runs - live render) and InterfacePreview
// (embedded landing snapshot - TABLE / INTERFACE / SKILL / AGENT) based on what the
// publication actually carries.

export function PublicationPreview({ publication, fallback, overlay, fill, ownerPreview, remote, acquired }: { publication: WorkflowPublication; fallback?: React.ReactNode; overlay?: React.ReactNode; fill?: boolean; ownerPreview?: boolean; remote?: boolean; acquired?: boolean }) {
  const hasRun = !!publication.showcaseRunId && !!publication.showcaseInterfaceId;
  const [landing, setLanding] = useState<InterfaceSnapshotLike | null>(null);
  const [loadingLanding, setLoadingLanding] = useState(!hasRun);

  useEffect(() => {
    if (hasRun) return;
    let cancelled = false;
    setLoadingLanding(true);
    publicationService.getLandingSnapshot(publication.id, remote)
      .then((res) => { if (!cancelled) setLanding(res.landing); })
      .catch(() => { if (!cancelled) setLanding(null); })
      .finally(() => { if (!cancelled) setLoadingLanding(false); });
    return () => { cancelled = true; };
  }, [publication.id, hasRun, remote]);

  // fill=true → the caller controls the outer shape (rounded/aspect) and we just paint the content.
  // fill=false (default) → legacy callers still get a self-sized rounded 16:10 tile.
  const wrapperClass = fill
    ? 'relative w-full h-full bg-white dark:bg-slate-900'
    : 'relative rounded-xl overflow-hidden bg-white dark:bg-slate-900';
  const wrapperStyle = fill ? undefined : { aspectRatio: '16 / 10' as const };

  if (hasRun) {
    return (
      <ShowcasePreview
        runId={publication.showcaseRunId!}
        interfaceId={publication.showcaseInterfaceId!}
        // Own publications (My Shared) render via the AUTHENTICATED per-run path - valid at ANY
        // visibility because the run is the caller's own - so a PRIVATE app previews instead of
        // erroring on the PUBLIC-only showcase endpoint. Foreign/marketplace cards keep the public,
        // publication-scoped showcase render (cross-tenant-safe), exactly like /app/applications.
        // localShowcase (A1) = a CLOUD purchase backed by a LOCAL clone: render the acquirer's OWN
        // clone via the authenticated per-run path (publicationId omitted -> runId+interfaceId are
        // local), immune to the cloud publisher deleting the source. Otherwise an owned (My Shared)
        // card uses the per-run path too; foreign/marketplace cards keep the publication-scoped render.
        publicationId={(ownerPreview || publication.localShowcase) ? undefined : publication.id}
        // An ACQUIRED card (My Purchases) routes the publication-scoped render through the
        // receipt-gated AUTH'D showcase endpoint, so the frozen interface still previews after
        // the publisher unpublishes/deletes the source (status INACTIVE -> the anonymous /by-id
        // path 403s "not publicly available"). Cloud purchases (remote) keep the by-id proxy.
        authenticated={acquired && !remote}
        remote={remote && !publication.localShowcase}
        className={fill ? 'absolute inset-0 h-full w-full' : ''}
      />
    );
  }
  if (loadingLanding) {
    return fill
      ? <div className="absolute inset-0 animate-pulse bg-slate-200 dark:bg-slate-700" />
      : <div className="w-full animate-pulse bg-slate-200 dark:bg-slate-700 rounded-xl" style={{ aspectRatio: '16 / 10' }} />;
  }
  // Overlay: caller-supplied foreground (e.g. agent avatar) layered on top of the
  // landing snapshot. Falls back gracefully when the landing is missing.
  if (overlay) {
    return (
      <div className={wrapperClass} style={wrapperStyle}>
        {landing ? (
          <InterfacePreview snapshot={landing} className="absolute inset-0 h-full w-full" />
        ) : (
          <div className="absolute inset-0 bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900" />
        )}
        {landing && (
          <div className="absolute inset-0 bg-gradient-to-b from-white/10 via-white/30 to-white/70 dark:from-slate-900/10 dark:via-slate-900/40 dark:to-slate-900/80" />
        )}
        <div className="absolute inset-0 z-10 flex items-center justify-center">{overlay}</div>
      </div>
    );
  }
  if (!landing) {
    return <>{fallback ?? null}</>;
  }
  return (
    <div className={wrapperClass} style={wrapperStyle}>
      <InterfacePreview snapshot={landing} className="absolute inset-0 h-full w-full" />
    </div>
  );
}

// ============== Fallback thumbnail (no landing snapshot) ==============
// Used by the new image-first card when the publication has no interface preview
// (bare workflows) or the landing snapshot fetch failed. Dot-grid background with
// either the workflow's node icons or a generic package glyph.

export function StandardFallback({ publication }: { publication: WorkflowPublication }) {
  return (
    <div className="relative w-full h-full bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 grid place-items-center">
      <div
        className="absolute inset-0 opacity-60 dark:hidden"
        style={{ backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)', backgroundSize: '16px 16px' }}
      />
      <div
        className="absolute inset-0 opacity-40 hidden dark:block"
        style={{ backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)', backgroundSize: '16px 16px' }}
      />
      <div className="relative z-10">
        {publication.nodeIcons && publication.nodeIcons.length > 0 ? (
          <WorkflowNodeIcons nodeIcons={publication.nodeIcons} />
        ) : (
          <div className="w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center">
            <Package className="w-7 h-7 text-theme-tertiary" />
          </div>
        )}
      </div>
    </div>
  );
}

// ============== Publication Card ==============

export interface PublicationCardProps {
  publication: WorkflowPublication;
  currentUserId?: string;
  /**
   * Server-computed org-aware "I already own this" - true when the publication is owned by the
   * caller's ACTIVE workspace (owner_type=ORG and owner_id == active org, or USER and owner_id ==
   * caller). When provided it supersedes the publisher-id check, so an app owned by your active
   * workspace shows "Installed" instead of "Acquire". Omitted by non-marketplace callers
   * (onboarding/landing/chat), which keep the legacy publisher-id fallback below.
   */
  ownedByMe?: boolean;
  onAcquire?: (publication: WorkflowPublication) => void;
  isAcquired?: boolean;
  showStats?: boolean;
  /**
   * The card represents one of the VIEWER'S OWN publications (the "My Shared" tab). Two effects:
   * (1) the thumbnail previews via the authenticated per-run path so a PRIVATE app renders instead
   * of erroring on the public showcase endpoint; (2) the footer shows a public / private indicator
   * from {@code publication.visibility}. Off for marketplace/foreign cards.
   */
  mine?: boolean;
  /**
   * CE-cloud parity: the card renders a CLOUD publication (cloud-linked CE
   * marketplace / highlights). Routes the thumbnail (landing-snapshot /
   * showcase-render) and the publisher avatar through the cloud proxy, since
   * the cloud ids are absent from the local DB. Off for local cards
   * (onboarding/profile/My Shared) and on non-CE/cloud.
   */
  remote?: boolean;
  /**
   * The card represents an app the viewer ACQUIRED (the "My Purchases" tab). Routes the
   * thumbnail's publication-scoped showcase render through the receipt-gated AUTH'D endpoint
   * so it still previews after the publisher unpublishes/deletes the source (status INACTIVE).
   * Off for marketplace/Explore cards, which keep the anonymous cross-tenant-safe render.
   */
  acquired?: boolean;
  /**
   * Live install progress (0-100) from the shared marketplace-install store, or null/undefined
   * when this publication is not installing. While set, the thumbnail preview is greyed out and
   * un-greys left-to-right as the gauge fills (same simulated 5-10s ramp the install modal used),
   * with the modal's progress bar + percentage rendered at the bottom of the thumbnail.
   */
  installProgress?: number | null;
  /**
   * Destination of the "Open" button that REPLACES the Install button once the publication is
   * installed (e.g. /app/applications/{publicationId}). Callers pass it only when the viewer
   * actually has the installed resource; omitted → no Open button (badge only).
   */
  openHref?: string;
}

// memo: during an install the shared store ticks progress every 50ms and the
// whole grid re-renders; memo confines that to the ONE card whose
// installProgress prop actually changes (iframe previews make a full-grid
// re-render at 20Hz genuinely expensive).
export const PublicationCard = memo(function PublicationCard({ publication, currentUserId, ownedByMe, onAcquire, isAcquired, showStats, mine, remote, acquired, installProgress, openHref }: PublicationCardProps) {
  const t = useTranslations('marketplace');
  const router = useRouter();
  const displayMode = publication.displayMode || 'WORKFLOW';
  const isAgent = displayMode === 'AGENT';
  const hasInterfacePreview = displayMode !== 'WORKFLOW';
  const isOwn = ownedByMe ?? (!!currentUserId && publication.publisherId === currentUserId);
  const isFree = !publication.creditsPerUse || publication.creditsPerUse === 0;
  const isInstalling = installProgress != null;
  const clampedProgress = isInstalling ? Math.min(100, Math.max(0, installProgress)) : 0;
  const canAcquire = !isOwn && !isAcquired && !!onAcquire && !isInstalling;
  const showOpen = !!openHref && !isInstalling;

  // Preview destination: agents open the dedicated agent-preview page; every other
  // publication type goes through the unified /preview route.
  const previewHref = isAgent
    ? `/app/marketplace/agents/${publication.id}`
    : `/app/marketplace/${publication.id}/preview`;

  // Agent count badges - derived from snapshot counts; shown inside the hover overlay
  // below the foot row (only the non-zero ones). Icons mirror the left sidebar.
  const countBadges = isAgent
    ? ([
        { icon: Network, count: publication.agentCount || 0, label: t('resourceType.AGENT') },
        { icon: Zap, count: publication.skillCount || 0, label: t('resourceType.SKILL') },
        { icon: Workflow, count: publication.workflowCount || 0, label: t('resourceType.WORKFLOW') },
        { icon: Monitor, count: publication.interfaceCount || 0, label: t('resourceType.INTERFACE') },
        { icon: Table, count: publication.datasourceCount || 0, label: t('resourceType.TABLE') },
      ].filter(b => b.count > 0))
    : [];

  return (
    <Link
      href={previewHref}
      className="group block cursor-pointer"
    >
      {/* Thumbnail - fixed 16:10 aspect, framed card */}
      <div
        className="relative overflow-hidden rounded-[20px] border border-theme bg-theme-tertiary"
        style={{ aspectRatio: '16 / 10' }}
      >
        <div className="absolute inset-0">
          {isAgent ? (
            <PublicationPreview
              publication={publication}
              fill
              ownerPreview={mine}
              remote={remote}
              acquired={acquired}
              overlay={<AvatarDisplay avatarUrl={publication.agentAvatarUrl} name={publication.title} size="xl" />}
              fallback={<StandardFallback publication={publication} />}
            />
          ) : hasInterfacePreview ? (
            <PublicationPreview publication={publication} fill ownerPreview={mine} remote={remote} acquired={acquired} fallback={<StandardFallback publication={publication} />} />
          ) : (
            <StandardFallback publication={publication} />
          )}
        </div>

        {/* Top-left (hover): price chip, then the category just below it. The
            category moved off the footer to here at the user's request. */}
        <div className="absolute top-3 left-3 z-20 flex flex-col items-start gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity duration-200">
          <PricePill publication={publication} isFree={isFree} />
          {publication.category && (
            <span className="inline-flex items-center h-[22px] px-2 rounded-md text-[11px] font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10">
              {publication.category.name}
            </span>
          )}
        </div>

        {/* Top-right: status badge - owner = publisher already has access, visually equivalent to installed */}
        {isAcquired || isOwn ? (
          <span className="absolute top-3 right-3 z-20 inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-emerald-500 text-white shadow-sm">
            <CheckCircle className="h-3 w-3" />
            {t('installed')}
          </span>
        ) : null}

        {/* Install-in-progress: the preview starts fully greyed out and un-greys
            left-to-right as the gauge fills - the veil's left edge tracks the
            progress, so the revealed strip shows the interface in full color.
            The bar + percentage at the bottom reuse the exact loading system of
            the (former in-modal) install progress screen. */}
        {isInstalling && (
          <div data-testid="publication-card-install-progress" className="absolute inset-0 z-20 pointer-events-none">
            <div
              className="absolute inset-y-0 right-0 bg-white/40 dark:bg-slate-900/50"
              style={{
                left: `${clampedProgress}%`,
                backdropFilter: 'grayscale(1)',
                WebkitBackdropFilter: 'grayscale(1)',
                // Smooth out the 50ms store tick so the reveal reads as a
                // continuous sweep rather than discrete jumps.
                transition: 'left 100ms linear',
              }}
            />
            <div className="absolute inset-x-4 bottom-3 flex flex-col items-center gap-1">
              <div
                role="progressbar"
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={Math.round(clampedProgress)}
                aria-label={t('acquiring')}
                className="w-full h-2.5 rounded-full bg-slate-200 dark:bg-slate-700 overflow-hidden ring-1 ring-slate-300/50 dark:ring-slate-600/60"
              >
                <div
                  className="h-full bg-[var(--accent-primary)] shadow-[0_0_8px_var(--accent-primary)]"
                  style={{ width: `${clampedProgress}%`, transition: 'width 100ms linear' }}
                />
              </div>
              <span className="text-[11px] font-medium text-theme-primary tabular-nums rounded px-1.5 backdrop-blur bg-white/80 dark:bg-black/50 border border-white/40 dark:border-white/10">
                {Math.round(clampedProgress)}%
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Footer - always visible below the thumbnail (mirrors /app/interface card layout) */}
      <div className="px-1 pt-2 pb-1 space-y-1">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-1.5 min-w-0 flex-1">
            <h3 className="text-sm font-medium text-theme-primary truncate">{publication.title}</h3>
            {/* My Shared: public / private indicator for the viewer's own publication. */}
            {mine && <VisibilityBadge visibility={publication.visibility} />}
            {/* Average rating sits right next to the name (social proof at a glance). */}
            {(publication.reviewCount ?? 0) > 0 && (
              <span className="inline-flex items-center gap-1 text-xs text-theme-muted shrink-0">
                <Star className="h-3 w-3 fill-amber-400 text-amber-400" />
                {(publication.averageRating ?? 0).toFixed(1)}
                <span className="text-theme-muted">({publication.reviewCount})</span>
              </span>
            )}
          </div>
          {showOpen ? (
            // Installed → the Install button's slot becomes an "Open" button
            // that jumps straight to the installed application. A nested <a>
            // inside the card's Link is invalid HTML, so navigate imperatively.
            <button
              type="button"
              data-testid="publication-card-open"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                track('app_post_install_opened', {
                  publication_id: publication.id,
                  publication_type: publication.publicationType ?? null,
                  acquired_id: null,
                });
                router.push(openHref!);
              }}
              className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-[var(--accent-primary)] text-[var(--bg-primary)] hover:brightness-110 active:scale-95 transition-[filter,transform] shrink-0"
            >
              <ArrowUpRight className="h-3 w-3" />
              {t('open')}
            </button>
          ) : canAcquire ? (
            <button
              type="button"
              onClick={(e) => { e.preventDefault(); e.stopPropagation(); onAcquire!(publication); }}
              className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-[var(--accent-primary)] text-[var(--bg-primary)] hover:brightness-110 active:scale-95 transition-[filter,transform] shrink-0"
            >
              <Download className="h-3 w-3" />
              {t('acquire')}
            </button>
          ) : null}
        </div>

        {(publication.description || (isAgent && publication.agentModelProvider && publication.agentModelName)) && (
          <p className="text-xs text-theme-muted line-clamp-2 leading-snug">
            {publication.description || `${publication.agentModelProvider}/${publication.agentModelName}`}
          </p>
        )}

        <div className="flex items-center gap-1.5 min-w-0 pt-0.5">
          {/* Publisher is clickable: view profile / send a DM (popover, no card click-through).
              A cloud-sourced (remote) publisher cannot receive a DM here, so the message
              action is hidden in remote mode - see UserActionMenu. */}
          <UserActionMenu userId={publication.publisherId} remote={remote}>
            <PublisherAvatar userId={publication.publisherId} name={publication.publisherName} remote={remote} />
            <span className="text-xs text-theme-secondary truncate">
              {publication.publisherName || t('anonymous')}
            </span>
          </UserActionMenu>
          {publication.nodeIcons && publication.nodeIcons.length > 0 && (
            <WorkflowNodeIcons
              nodeIcons={publication.nodeIcons}
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
              <span key={i} className="inline-flex items-center gap-0.5 text-xs text-theme-muted" title={b.label}>
                <b.icon className="h-3 w-3" />
                {b.count}
              </span>
            ))}
          </div>
        )}

        {showStats && (
          <div className="flex items-center gap-3 pt-0.5">
            {/* Uses + earnings. The average rating moved next to the title above. */}
            <span className="inline-flex items-center gap-1 text-xs text-theme-muted">
              <Download className="h-3 w-3" />
              {publication.useCount || 0} {t('uses')}
            </span>
            {(publication.totalCreditsEarned || 0) > 0 && (
              <span className="inline-flex items-center gap-1 text-xs text-theme-muted">
                <Coins className="h-3 w-3" />
                {isCeMode ? `$${publication.totalCreditsEarned}` : `${publication.totalCreditsEarned} ${t('credits')}`}
              </span>
            )}
          </div>
        )}
      </div>
    </Link>
  );
});
