'use client';

import { useState } from 'react';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Package, CheckCircle, Clock, XCircle, Sparkles, Star } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import { ShowcasePreview } from '@/components/marketplace/ShowcasePreview';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { VisibilityBadge } from '@/components/ui/VisibilityBadge';

// ============== Skeleton ==============
// Mirrors the live card layout: 16:10 thumbnail + footer rows below
// (matches marketplace + /app/interface card shape).

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

// ============== Fallback thumbnail (no live run) ==============
// Dot-grid background with workflow node icons or a generic package glyph.
// Mirrors the marketplace StandardFallback so cards look identical when there
// is no interface to preview.

function StandardFallback({ publication }: { publication: WorkflowPublication }) {
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

// ============== Application Card (same as PublicationCard in marketplace) ==============

export type AppSource = 'published' | 'acquired';

// A freshly-acquired app wears a "New" badge instead of "Installed" for this long
// after acquisition (data-driven off acquiredAt - no per-device state to persist).
const RECENTLY_ACQUIRED_MS = 24 * 60 * 60 * 1000; // 24h

interface ApplicationCardProps {
  publication: WorkflowPublication;
  source: AppSource;
  isSelected: boolean;
  onToggleSelect: (id: string) => void;
  onCardClick?: () => void;
  /** Run ID of the application-dedicated run (if it exists) */
  applicationRunId?: string;
  /** ISO timestamp the app was acquired - drives the transient "New" badge. */
  acquiredAt?: string;
  /**
   * Pinned version of the underlying workflow. {@code null} = unpinned (the
   * application is inactive - production triggers refused). A number = pinned
   * to that version (the application is active - webhook/schedule/datasource
   * triggers fire). {@code undefined} = not yet loaded.
   */
  pinnedVersion?: number | null;
  /** Whether this app is in the user's personal favorites. */
  isFavorite?: boolean;
  /**
   * Toggle the app's favorite state. When omitted, the star is not rendered
   * (e.g. anonymous / contexts without a favorites store).
   */
  onToggleFavorite?: (publicationId: string) => void;
}

export function ApplicationCard({ publication, source, isSelected, onToggleSelect, onCardClick, applicationRunId, acquiredAt, pinnedVersion, isFavorite, onToggleFavorite }: ApplicationCardProps) {
  const t = useTranslations('marketplace');
  const tApp = useTranslations('applications');
  const previewRunId = applicationRunId || publication.showcaseRunId;
  const cardId = source === 'acquired' ? `acquired-${publication.id}` : `published-${publication.id}`;
  const [rejectionDialogOpen, setRejectionDialogOpen] = useState(false);
  const isRejected = source === 'published' && publication.status === 'REJECTED';
  const isPending = source === 'published' && publication.status === 'PENDING_REVIEW';
  const isAcquired = source === 'acquired';
  // Just-downloaded apps read "New" instead of "Installed" for a short window.
  // Capture "now" once at mount (useState initializer) so render stays pure.
  const [nowAtMount] = useState(() => Date.now());
  const isNew = isAcquired && !!acquiredAt
    && nowAtMount - new Date(acquiredAt).getTime() < RECENTLY_ACQUIRED_MS;
  // An acquired app's run + interface belong to the PUBLISHER, so the authenticated
  // per-run render is cross-tenant → a 404 painted inside the card. We instead render
  // it through the public, publication-scoped showcase-render (the SAME frozen showcase
  // the marketplace card shows). If that publication has no captured snapshot the call
  // fails - fall back to the cover tile rather than surfacing the raw error.
  const [acquiredPreviewFailed, setAcquiredPreviewFailed] = useState(false);
  const canPreview = !!previewRunId && !!publication.showcaseInterfaceId
    && (!isAcquired || !acquiredPreviewFailed);

  return (
    <div className="group block cursor-pointer" onClick={onCardClick}>
      {/* Thumbnail - fixed 16:10 aspect, framed card (mirrors marketplace PublicationCard) */}
      <div
        className="relative overflow-hidden rounded-[20px] border border-theme bg-theme-tertiary"
        style={{ aspectRatio: '16 / 10' }}
      >
        <div className="absolute inset-0">
          {canPreview ? (
            <ShowcasePreview
              runId={previewRunId!}
              interfaceId={publication.showcaseInterfaceId!}
              // Acquired apps render the publisher's frozen, publication-scoped
              // showcase (cross-tenant-safe); owned/published apps keep the
              // authenticated live render of their own run. On an acquired miss
              // (no snapshot) swap to the cover fallback.
              publicationId={isAcquired ? publication.id : undefined}
              // Local (non-remote) acquired apps read the showcase through the
              // AUTHENTICATED endpoint so the receipt bypass keeps the preview
              // working even after the publisher unpublishes / privatises the
              // source publication (the anonymous /by-id path 403s then, which is
              // what dropped the card onto the node-icon cover tile).
              authenticated={isAcquired && !publication.remote}
              // Cloud-acquired app (cloud-linked CE): its showcase clone lives on the
              // cloud, so route the render through the remote by-id proxy - a local
              // showcase-render would 404 and fall back to the empty cover tile.
              remote={publication.remote}
              onError={isAcquired ? () => setAcquiredPreviewFailed(true) : undefined}
              suppressErrorUi={isAcquired}
              className="absolute inset-0 h-full w-full"
              hidePagination
            />
          ) : (
            <StandardFallback publication={publication} />
          )}
        </div>

        {/* Top-right: selection checkbox (hover) + the acquired/pending/rejected status -
            checkbox on the RIGHT, matching the other resource cards. */}
        <div className="absolute top-3 right-3 z-20 flex items-center gap-1.5">
          <div
            className={`transition-opacity ${isSelected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100'}`}
          >
            <input
              type="checkbox"
              checked={isSelected}
              onChange={() => onToggleSelect(cardId)}
              onClick={(e) => e.stopPropagation()}
              className="rounded border-theme cursor-pointer h-4 w-4 bg-white/90 dark:bg-black/60"
            />
          </div>
          {source === 'acquired' ? (
            isNew ? (
              <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-blue-500 text-white shadow-sm">
                <Sparkles className="h-3 w-3" />
                {tApp('new')}
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-emerald-500 text-white shadow-sm">
                <CheckCircle className="h-3 w-3" />
                {tApp('installed')}
              </span>
            )
          ) : isPending ? (
            <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-amber-500 text-white shadow-sm">
              <Clock className="h-3 w-3" />
              {t('pendingReview')}
            </span>
          ) : isRejected ? (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                if (publication.rejectionReason) setRejectionDialogOpen(true);
              }}
              title={publication.rejectionReason || ''}
              aria-label={t('viewRejectionReason')}
              className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-red-500 text-white shadow-sm hover:brightness-110"
            >
              <XCircle className="h-3 w-3" />
              {t('rejected')}
            </button>
          ) : null}
        </div>

        {/* Top-left (hover): the "Shared" chip (a published app) + the category -
            same corner + behavior as the marketplace card's Free + category. The
            selection checkbox now lives top-RIGHT, matching the other resource cards. */}
        <div className="absolute top-3 left-3 z-20 flex flex-col items-start gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity duration-200">
          {source === 'published' && !isPending && !isRejected && (
            <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-md text-[11px] font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10">
              <Package className="h-3 w-3" />
              {t('shared')}
            </span>
          )}
          {publication.category && (
            <span className="inline-flex items-center h-[22px] px-2 rounded-md text-[11px] font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10">
              {publication.category.name}
            </span>
          )}
        </div>

        {/* Bottom-right: "Live" badge - the underlying workflow is pinned
            (production triggers fire). For a PUBLISHED app it must ALSO be
            approved: while PENDING_REVIEW / REJECTED the shared app is not yet
            available on the marketplace, so a green "Live" here contradicts the
            amber "Pending review" / red "Rejected" status badge above-right.
            Acquired apps are the acquirer's own pinned instance → unaffected.
            Absence of the badge IS the off state (no opposite "Inactive"). */}
        {pinnedVersion != null && (isAcquired || (!isPending && !isRejected)) && (
          <span
            className="absolute bottom-3 right-3 z-20 inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium shadow-sm bg-emerald-500 text-white"
            aria-label={tApp('live')}
          >
            <span className="h-1.5 w-1.5 rounded-full bg-white animate-pulse" aria-hidden="true" />
            {tApp('live')}
          </span>
        )}

        {/* Bottom-left: favorite star. Always visible once favorited (so the user
            sees their picks at a glance); hover/focus-revealed otherwise. Stops
            propagation so it never triggers the card's navigation. */}
        {onToggleFavorite && (
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); onToggleFavorite(publication.id); }}
            aria-pressed={!!isFavorite}
            aria-label={isFavorite ? tApp('unfavorite') : tApp('favorite')}
            title={isFavorite ? tApp('unfavorite') : tApp('favorite')}
            className={`absolute bottom-3 left-3 z-20 inline-flex items-center justify-center h-7 w-7 rounded-full backdrop-blur bg-white/80 dark:bg-black/50 border border-white/40 dark:border-white/10 shadow-sm transition-opacity ${
              isFavorite
                ? 'opacity-100 text-amber-500'
                : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100 text-theme-secondary hover:text-theme-primary'
            }`}
          >
            <Star className={`h-3.5 w-3.5 ${isFavorite ? 'fill-current' : ''}`} />
          </button>
        )}
      </div>

      {/* Footer - always visible below the thumbnail (mirrors marketplace + /app/interface) */}
      <div className="px-1 pt-2 pb-1 space-y-1">
        <div className="flex items-center gap-1.5 min-w-0">
          <h3 className="text-sm font-medium text-theme-primary truncate">
            {publication.title}
          </h3>
          {/* Public / private indicator for the viewer's OWN published apps. Acquired apps carry
              the publisher's visibility, not the viewer's, so they get no marker. */}
          {source === 'published' && <VisibilityBadge visibility={publication.visibility} />}
        </div>

        {publication.description && (
          <p className="text-xs text-theme-muted line-clamp-2 leading-snug">
            {publication.description}
          </p>
        )}

        <div className="flex items-center gap-1.5 min-w-0 pt-0.5">
          <PublisherAvatar userId={publication.publisherId} name={publication.publisherName} remote={publication.remote} />
          <span className="text-xs text-theme-secondary truncate">
            {publication.publisherName || t('anonymous')}
          </span>
          {publication.nodeIcons && publication.nodeIcons.length > 0 && (
            <WorkflowNodeIcons
              nodeIcons={publication.nodeIcons}
              maxDisplay={3}
              prioritizeMcpAndTriggers
              size="inline"
              className="ml-auto shrink-0"
            />
          )}
          {publication.planVersion != null && (
            // No icons → vN pins right via ml-auto. Icons present → vN
            // sits just after them; the icons row already grabbed ml-auto.
            <span className={`text-xs text-theme-muted tabular-nums shrink-0 ${
              publication.nodeIcons && publication.nodeIcons.length > 0 ? '' : 'ml-auto'
            }`}>
              v{publication.planVersion}
            </span>
          )}
        </div>
      </div>

      {isRejected && (
        <Dialog open={rejectionDialogOpen} onOpenChange={setRejectionDialogOpen}>
          <DialogContent className="max-w-md" onClick={(e) => e.stopPropagation()}>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2 text-red-600 dark:text-red-400">
                <XCircle className="h-4 w-4" />
                {t('rejectionReasonTitle')}
              </DialogTitle>
            </DialogHeader>
            <div className="mt-2 text-sm text-theme-secondary whitespace-pre-wrap break-words">
              {publication.rejectionReason || t('noRejectionReason')}
            </div>
          </DialogContent>
        </Dialog>
      )}
    </div>
  );
}
