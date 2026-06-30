'use client';

import { useCallback, useEffect, useState } from 'react';
import { useTranslations } from 'next-intl';
import { CheckCircle, Coins, Download, Gift, Loader2, Package } from 'lucide-react';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication } from '@/lib/api/orchestrator/types';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useAuth } from '@/lib/providers/smart-providers';
import { InterfacePreview, type InterfaceSnapshotLike } from '@/components/marketplace/InterfacePreview';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import AcquirePublicationModal from '@/components/marketplace/AcquirePublicationModal';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';

type ResourceType = 'TABLE' | 'INTERFACE' | 'SKILL' | 'WORKFLOW';

interface Props {
  type: ResourceType;
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  subtitle: string;
  emptyText: string;
}

function PricePill({ publication, isFree, t }: { publication: WorkflowPublication; isFree: boolean; t: (key: string) => string }) {
  const Icon = isFree ? Gift : Coins;
  const label = isFree ? t('free') : `${publication.creditsPerUse} ${t('credits')}`;
  return (
    <span className="inline-flex items-center gap-1 h-[22px] px-2 rounded-md text-[11px] font-medium backdrop-blur bg-white/80 dark:bg-black/50 text-theme-primary border border-white/40 dark:border-white/10">
      <Icon className="h-3 w-3" />
      {label}
    </span>
  );
}

function PublicationCard({
  publication,
  currentUserId,
  isAcquired,
  onAcquire,
}: {
  publication: WorkflowPublication;
  currentUserId?: string;
  isAcquired: boolean;
  onAcquire: (pub: WorkflowPublication) => void;
}) {
  const t = useTranslations('marketplace');
  const [landing, setLanding] = useState<InterfaceSnapshotLike | null>(null);
  const [loadingLanding, setLoadingLanding] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await publicationService.getLandingSnapshot(publication.id);
        if (!cancelled) setLanding(res.landing);
      } catch {
        if (!cancelled) setLanding(null);
      } finally {
        if (!cancelled) setLoadingLanding(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [publication.id]);

  // Org-aware ownership computed server-side (active workspace owns it); falls back to the
  // publisher-id check only if the backend didn't supply the flag.
  const isOwn = publication.ownedByMe ?? (!!currentUserId && publication.publisherId === currentUserId);
  const canAcquire = !isOwn && !isAcquired;
  const isFree = !publication.creditsPerUse || publication.creditsPerUse === 0;

  return (
    <div className="group block">
      {/* Thumbnail - fixed 16:10 aspect, framed card */}
      <div
        className="relative overflow-hidden rounded-[20px] border border-theme bg-theme-tertiary"
        style={{ aspectRatio: '16 / 10' }}
      >
        <div className="absolute inset-0 bg-white dark:bg-slate-900">
          {loadingLanding ? (
            <div className="absolute inset-0 flex items-center justify-center">
              <Loader2 className="h-4 w-4 animate-spin text-theme-muted" />
            </div>
          ) : landing ? (
            <InterfacePreview snapshot={landing} className="absolute inset-0 h-full w-full" emptyLabel={t('noLandingPreview')} />
          ) : (
            <div className="relative w-full h-full grid place-items-center bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900">
              <div
                className="absolute inset-0 opacity-60 dark:hidden"
                style={{ backgroundImage: 'radial-gradient(circle, #cbd5e1 1px, transparent 1px)', backgroundSize: '16px 16px' }}
              />
              <div
                className="absolute inset-0 opacity-40 hidden dark:block"
                style={{ backgroundImage: 'radial-gradient(circle, #475569 1px, transparent 1px)', backgroundSize: '16px 16px' }}
              />
              <div className="relative z-10 w-14 h-14 bg-theme-secondary rounded-full flex items-center justify-center">
                <Package className="w-7 h-7 text-theme-tertiary" />
              </div>
            </div>
          )}
        </div>

        {/* Top-left: price chip */}
        <span className="absolute top-3 left-3 z-20">
          <PricePill publication={publication} isFree={isFree} t={t} />
        </span>

        {/* Top-right: installed badge */}
        {isAcquired || isOwn ? (
          <span className="absolute top-3 right-3 z-20 inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-emerald-500 text-white shadow-sm">
            <CheckCircle className="h-3 w-3" />
            {t('installed')}
          </span>
        ) : null}
      </div>

      {/* Footer - always visible below the thumbnail */}
      <div className="px-1 pt-2 pb-1 space-y-1">
        <div className="flex items-center justify-between gap-2">
          <h3 className="text-sm font-medium text-theme-primary truncate flex-1">{publication.title}</h3>
          {canAcquire ? (
            <button
              type="button"
              onClick={() => onAcquire(publication)}
              className="inline-flex items-center gap-1 h-[22px] px-2 rounded-full text-[11px] font-medium bg-[var(--accent-primary)] text-[var(--bg-primary)] hover:brightness-110 active:scale-95 transition-[filter,transform] shrink-0"
            >
              <Download className="h-3 w-3" />
              {t('acquire')}
            </button>
          ) : null}
        </div>
        {publication.description && (
          <p className="text-xs text-theme-muted line-clamp-2 leading-snug">{publication.description}</p>
        )}
        <div className="flex items-center gap-1.5 min-w-0 pt-0.5">
          <PublisherAvatar userId={publication.publisherId} name={publication.publisherName} />
          <span className="text-xs text-theme-secondary truncate">
            {publication.publisherName || publication.publisherId}
          </span>
        </div>
      </div>
    </div>
  );
}

export function ResourceMarketplaceGrid({ type, icon: Icon, title, subtitle, emptyText }: Props) {
  const t = useTranslations('marketplace');
  const { numericUserId } = useAuth();
  const currentUserId = numericUserId != null ? String(numericUserId) : undefined;

  const [loading, setLoading] = useState(true);
  const [publications, setPublications] = useState<WorkflowPublication[]>([]);
  const [acquiredIds, setAcquiredIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  // Modal-based acquire flow - shared with the workflow/agent grid so every
  // install path shows the 5-10s download progress bar. The previous inline
  // flow used a bottom-right toast and skipped the bar entirely.
  const [acquireTarget, setAcquireTarget] = useState<WorkflowPublication | null>(null);

  const load = useCallback(async () => {
    try {
      setLoading(true);
      const res = await publicationService.getMarketplaceByType(type, 0, 50);
      setPublications(res.publications);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [type]);

  useEffect(() => {
    load();
  }, [load]);

  // Phase 6c post-audit (2026-05-19) - clear acquisitions + publications
  // on workspace switch. Acquisitions are per-org (a publication acquired
  // in OrgA is not acquired in OrgB); without this reset the "isAcquired"
  // flag stuck across switches, hiding the acquire button in workspaces
  // that had never acquired the publication. Mirrors the reset already
  // present in `app/marketplace/page.tsx` for the Explore + MyPublications
  // tabs (Phase 6b).
  useOrgScopedReset(() => {
    setPublications([]);
    setAcquiredIds(new Set());
    setError(null);
    setAcquireTarget(null);
    setLoading(true);
  });

  const handleAcquire = useCallback((pub: WorkflowPublication) => {
    setAcquireTarget(pub);
  }, []);

  const handleAcquireSuccess = useCallback(() => {
    if (!acquireTarget) return;
    setAcquiredIds((prev) => new Set(prev).add(acquireTarget.id));
  }, [acquireTarget]);

  return (
    <div className="flex-1 overflow-y-auto min-h-0">
      <div className="min-h-full w-full p-6 pb-12">
        <div className="max-w-6xl mx-auto space-y-6 w-full">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-theme-secondary rounded-full flex items-center justify-center">
              <Icon className="w-5 h-5 text-theme-primary" />
            </div>
            <div>
              <h1 className="text-lg font-semibold text-theme-primary">{title}</h1>
              <p className="text-sm text-theme-secondary">{subtitle}</p>
            </div>
          </div>

          {error && (
            <div className="rounded-md border border-rose-300 dark:border-rose-700 bg-rose-50 dark:bg-rose-950 p-3 text-sm text-rose-700 dark:text-rose-200">
              {error}
            </div>
          )}

          {loading ? (
            <div className="flex items-center justify-center py-16">
              <LoadingSpinner />
            </div>
          ) : publications.length === 0 ? (
            <div className="p-12 rounded-xl bg-theme-secondary text-center">
              <div className="w-12 h-12 bg-theme-tertiary rounded-full flex items-center justify-center mx-auto mb-3">
                <Icon className="w-6 h-6 text-theme-secondary" />
              </div>
              <p className="text-sm text-theme-secondary">{emptyText}</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
              {publications.map((pub) => (
                <PublicationCard
                  key={pub.id}
                  publication={pub}
                  currentUserId={currentUserId}
                  isAcquired={acquiredIds.has(pub.id)}
                  onAcquire={handleAcquire}
                />
              ))}
            </div>
          )}

          {acquireTarget && (
            <AcquirePublicationModal
              isOpen={!!acquireTarget}
              onClose={() => setAcquireTarget(null)}
              publication={acquireTarget}
              onSuccess={handleAcquireSuccess}
            />
          )}
        </div>
      </div>
    </div>
  );
}
