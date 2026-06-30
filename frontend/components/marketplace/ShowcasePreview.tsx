'use client';

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useTranslations } from 'next-intl';
import { Layout, ChevronLeft, ChevronRight, AlertCircle } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import clsx from 'clsx';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { InterfaceThumbnail } from '@/app/workflows/builder/components/interface/InterfaceThumbnail';
import { mergeTriggerDataIntoResolved } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import type { InterfaceRenderResult } from '@/lib/api/orchestrator/types';

interface ShowcasePreviewProps {
  /**
   * Run ID for the authenticated per-run render path. Required only when
   * {@code publicationId} is absent; omit it for the public showcase path
   * (the board passes only {@code publicationId}).
   */
  runId?: string;
  /**
   * Interface ID for the authenticated per-run render path. Required only when
   * {@code publicationId} is absent; omit it for the public showcase path.
   */
  interfaceId?: string;
  className?: string;
  /** Hide pagination controls (useful when preview is just a visual thumbnail). */
  hidePagination?: boolean;
  /**
   * When provided, render via the public marketplace endpoint
   * ({@code /publications/by-id/{id}/showcase-render}) instead of the
   * authenticated {@code /interfaces/{id}/render}. The public endpoint
   * only serves frozen {@code showcase_*} clones - safe for anonymous
   * visitors. When absent the component keeps the original auth'd path
   * (used by {@code /app/applications} where the run is the user's own).
   */
  publicationId?: string;
  /**
   * Pin the preview to a single captured epoch on the authenticated per-run
   * path. Forwarded to {@code renderInterface} so the publisher's wizard
   * preview shows exactly the epoch they pinned. Ignored on the public
   * {@code publicationId} path - the frozen showcase clone is already
   * epoch-scoped server-side.
   */
  epoch?: number;
  /**
   * Fired when the underlying render call fails (404 retention, 403 cross-tenant,
   * network). Lets the parent swap to a frozen showcase fallback instead of
   * showing the inline error UI. When set, {@code suppressErrorUi} is also
   * honored to avoid a brief error flash before the parent unmounts us.
   */
  onError?: (err: Error) => void;
  /**
   * When {@code true}, suppress the inline AlertCircle error block. Useful in
   * combination with {@code onError} when the parent owns the fallback UI.
   */
  suppressErrorUi?: boolean;
  /**
   * CE-cloud parity: route the public {@code showcase-render} read through the
   * CE backend's cloud proxy. Set by marketplace card thumbnails on a
   * cloud-linked CE (the publicationId is a CLOUD id, absent from the local DB).
   * Only meaningful on the {@code publicationId} path.
   */
  remote?: boolean;
  /**
   * Read the frozen showcase through the AUTHENTICATED endpoint
   * ({@code /publications/{id}/showcase-render}) instead of the anonymous
   * {@code /by-id/...} one. Set by the installed-application card so the
   * receipt-based acquirer bypass admits a publication the user installed even
   * after the publisher made it non-public (the anonymous path 403s then,
   * leaving the card on the node-icon cover tile). Only meaningful on the
   * {@code publicationId} path; ignored when {@code remote} is set (cloud-linked
   * CE keeps the by-id proxy).
   */
  authenticated?: boolean;
}

// Skeleton component for loading state
function SkeletonBox({ className, style }: { className?: string; style?: React.CSSProperties }) {
  return (
    <div className={`bg-slate-200 dark:bg-slate-700 rounded animate-pulse ${className || ''}`} style={style} />
  );
}

function ShowcaseSkeleton() {
  return (
    <SkeletonBox className="w-full rounded-xl" style={{ aspectRatio: '16 / 10' }} />
  );
}

export function ShowcasePreview({ runId, interfaceId, className = '', hidePagination = false, publicationId, epoch, onError, suppressErrorUi = false, remote = false, authenticated = false }: ShowcasePreviewProps) {
  const t = useTranslations('showcase');
  const { isLoading: isAuthLoading } = useAuthGuard();

  const [renderResult, setRenderResult] = useState<InterfaceRenderResult | null>(null);
  const [triggerData, setTriggerData] = useState<Record<string, Record<string, unknown>> | undefined>(undefined);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [currentPage, setCurrentPage] = useState(0);

  // Lazy loading: only fetch when the component scrolls into the viewport
  const containerRef = useRef<HTMLDivElement>(null);
  const [isVisible, setIsVisible] = useState(false);

  // Stash onError in a ref so loadShowcase's identity stays stable across
  // parent re-renders (deps array intentionally excludes onError).
  const onErrorRef = useRef(onError);
  useEffect(() => { onErrorRef.current = onError; }, [onError]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setIsVisible(true);
          observer.disconnect();
        }
      },
      { rootMargin: '200px' } // start loading 200px before visible
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Fetch showcase data. Marketplace (publicationId present) uses the public
  // endpoint so anonymous visitors can preview cards; acquired-application
  // pages (no publicationId) keep the authenticated per-run render.
  const loadShowcase = useCallback(async (page: number = 0) => {
    setIsLoading(true);
    setError(null);

    try {
      let result: InterfaceRenderResult;
      if (publicationId) {
        result = await publicationService.getShowcaseRender(publicationId, { page, size: 1, authenticated }, remote);
      } else if (runId && interfaceId) {
        result = await orchestratorApi.renderInterface(interfaceId, runId, { page, size: 1, epoch });
      } else {
        setError('Missing runId or interfaceId');
        setIsLoading(false);
        return;
      }
      // Capture triggerData from first item for template variable resolution
      const firstItem = (result.items || [])[0];
      setTriggerData(firstItem?.triggerData as Record<string, Record<string, unknown>> | undefined);
      setRenderResult(result);
      setCurrentPage(page);
    } catch (err: any) {
      console.error('Error loading showcase:', err);
      setError(err.message || t('loadError'));
      onErrorRef.current?.(err instanceof Error ? err : new Error(String(err?.message ?? err)));
    } finally {
      setIsLoading(false);
    }
  }, [runId, interfaceId, publicationId, epoch, remote, authenticated, t]);

  // Load showcase once visible. The anonymous public marketplace path
  // (publicationId set, not authenticated) needs no token, so it skips the auth
  // gate - otherwise anonymous visitors would wait forever for auth to resolve.
  // The authenticated acquirer path DOES need a token, so it waits like the
  // per-run path.
  useEffect(() => {
    if (!isVisible) return;
    if ((!publicationId || authenticated) && isAuthLoading) return;
    loadShowcase(0);
  }, [isAuthLoading, isVisible, loadShowcase, publicationId, authenticated]);

  // Get the resolved HTML or data for the current item
  const { effectiveHtml, resolvedData } = useMemo(() => {
    if (!renderResult?.items?.length) return { effectiveHtml: undefined, resolvedData: undefined };
    const currentItem = renderResult.items[0];
    const itemData = currentItem.data || {};
    // If backend returned fully resolved HTML, use it directly
    if (itemData._resolvedHtml) {
      const html = itemData._resolvedHtml as string;
      return { effectiveHtml: html, resolvedData: undefined };
    }
    // Merge triggerData into resolvedData for template variables like {{trigger:name.output.field}}
    const merged = mergeTriggerDataIntoResolved(itemData, triggerData);
    return { effectiveHtml: undefined, resolvedData: merged };
  }, [renderResult, triggerData]);

  // Epoch nav: page 0 = newest. handleNewer decrements, handleOlder increments.
  const handleNewer = () => {
    if (currentPage > 0) {
      loadShowcase(currentPage - 1);
    }
  };

  const handleOlder = () => {
    const total = renderResult?.pagination?.totalPages || 0;
    if (currentPage < total - 1) {
      loadShowcase(currentPage + 1);
    }
  };

  const totalPages = renderResult?.pagination?.totalPages || 0;

  const showSkeleton = !isVisible || (isLoading && !renderResult);
  const hasContent = !showSkeleton && renderResult?.htmlTemplate;

  return (
    <div ref={containerRef} className={`group/showcase overflow-hidden rounded-xl relative ${className}`}>
      {showSkeleton ? (
        /* Skeleton: same 16:10 aspect ratio as InterfaceThumbnail */
        <div className="w-full animate-pulse bg-slate-200 dark:bg-slate-700 rounded-xl" style={{ aspectRatio: '16 / 10' }} />
      ) : !hasContent ? (
        /* Error or no data - stable placeholder with same ratio. When
           suppressErrorUi is set the parent owns fallback rendering, so we
           render an empty box of the same aspect ratio while it swaps us out. */
        <div className="flex flex-col items-center justify-center text-center" style={{ aspectRatio: '16 / 10' }}>
          {error && !suppressErrorUi ? (
            <>
              <AlertCircle className="h-8 w-8 text-red-500" />
              <p className="text-sm text-red-600 dark:text-red-400 mt-3">{error}</p>
            </>
          ) : !error ? (
            <>
              <Layout className="h-8 w-8 text-slate-400" />
              <p className="text-sm text-slate-500 mt-3">{t('noData')}</p>
            </>
          ) : null}
        </div>
      ) : (
        /* Live thumbnail - viewport-virtual scaled into the 16:10 box. */
        <div className="relative" style={{ aspectRatio: '16 / 10' }}>
          <div className="w-full h-full" style={{ opacity: isLoading ? 0.5 : 1, transition: 'opacity 200ms ease-in-out' }}>
            <InterfaceThumbnail
              htmlTemplate={effectiveHtml || renderResult!.htmlTemplate}
              customCss={renderResult!.cssTemplate || undefined}
              jsTemplate={renderResult!.jsTemplate || undefined}
              resolvedData={
                effectiveHtml || !resolvedData || Object.keys(resolvedData).length === 0
                  ? undefined
                  : resolvedData
              }
              // Pre-resolved HTML must render in run mode so {{var|default}} stays untouched.
              // Otherwise edit mode rewrites placeholders to [var] and breaks the snapshot.
              mode={
                effectiveHtml || (resolvedData && Object.keys(resolvedData).length > 0)
                  ? 'run'
                  : 'edit'
              }
              fit="contain"
              // Publisher JS runs in marketplace previews too - isolation is enforced
              // by the iframe sandbox (`allow-scripts`, no `allow-same-origin`), so the
              // script can drive its own DOM but cannot reach the parent's storage.
              // Forward bridge inputs so the iframe's prefillForms() populates the
              // form fields (textarea + selects) with the publisher's previously
              // submitted trigger values - without these the marketplace card
              // shows a blank form even when triggerData is on the snapshot.
              actionMapping={renderResult!.actionMappings || undefined}
              triggerData={triggerData}
              emptyLabel={t('noData')}
            />
          </div>
          {/* Clickable overlay - captures clicks above the iframe so the parent Link navigates */}
          <div className="absolute inset-0 z-10 cursor-pointer" />
          <div
            className="absolute inset-0 flex items-center justify-center pointer-events-none"
            style={{ opacity: isLoading ? 1 : 0, transition: 'opacity 200ms ease-in-out' }}
          >
            <LoadingSpinner size="sm" />
          </div>

          {/* Pagination overlay - hidden at rest, revealed on hover/focus to keep the
              thumbnail uncluttered while browsing the grid. */}
          {totalPages > 1 && !hidePagination && (
            <div
              className="absolute bottom-2 left-1/2 -translate-x-1/2 z-20 flex items-center gap-1 px-1.5 py-1 rounded-full bg-black/60 backdrop-blur-sm opacity-0 group-hover/showcase:opacity-100 focus-within:opacity-100 transition-opacity duration-200"
              onClick={(e) => { e.preventDefault(); e.stopPropagation(); }}
            >
              <button
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); handleNewer(); }}
                disabled={currentPage === 0 || isLoading}
                className={clsx(
                  'w-6 h-6 p-0 rounded-full transition-colors inline-flex items-center justify-center text-white',
                  currentPage === 0 || isLoading
                    ? 'opacity-40 cursor-not-allowed'
                    : 'hover:bg-white/20'
                )}
                aria-label="Newer"
              >
                <ChevronLeft className="h-3.5 w-3.5" />
              </button>
              <span className="text-[11px] text-white font-medium min-w-[38px] text-center tabular-nums">
                {currentPage + 1} / {totalPages}
              </span>
              <button
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); handleOlder(); }}
                disabled={currentPage >= totalPages - 1 || isLoading}
                className={clsx(
                  'w-6 h-6 p-0 rounded-full transition-colors inline-flex items-center justify-center text-white',
                  currentPage >= totalPages - 1 || isLoading
                    ? 'opacity-40 cursor-not-allowed'
                    : 'hover:bg-white/20'
                )}
                aria-label="Older"
              >
                <ChevronRight className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default ShowcasePreview;
