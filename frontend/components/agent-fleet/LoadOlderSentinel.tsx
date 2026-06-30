'use client';

import { useEffect, useRef } from 'react';
import { useTranslations } from 'next-intl';

interface LoadOlderSentinelProps {
  hasMore: boolean;
  loading: boolean;
  onLoadOlder: () => void;
  /**
   * 'top'    = sentinel sits above the list, expand top margin so it triggers slightly
   *            before scrolling fully to the top (classic chat-style "load older").
   * 'bottom' = sentinel sits below the list, expand bottom margin so it triggers slightly
   *            before scrolling fully to the bottom (infinite-scroll "load more").
   */
  placement?: 'top' | 'bottom';
  /**
   * IntersectionObserver root. Pass the scroll container when the sentinel lives inside a
   * nested overflow:auto element - otherwise the default viewport root won't fire as the
   * inner container scrolls.
   */
  scrollRoot?: Element | null;
  /** Override the default labels (`agentMetrics.loadingOlder` / `scrollToLoadOlder`). */
  loadingLabel?: string;
  idleLabel?: string;
}

/**
 * IntersectionObserver-based pagination trigger. Place it at the top (`placement="top"`)
 * or bottom (`placement="bottom"`) of a paginated list; when it enters the observer root,
 * `onLoadOlder` fires once. Works in any scroll container without requiring a scroll handler
 * on the parent - matches the visual UX of `MessageHistory` lazy-load (no explicit button)
 * while keeping the rendering component generic.
 */
export function LoadOlderSentinel({
  hasMore,
  loading,
  onLoadOlder,
  placement = 'top',
  scrollRoot,
  loadingLabel,
  idleLabel,
}: LoadOlderSentinelProps) {
  const t = useTranslations('agentMetrics');
  const sentinelRef = useRef<HTMLDivElement>(null);
  const onLoadOlderRef = useRef(onLoadOlder);
  onLoadOlderRef.current = onLoadOlder;

  useEffect(() => {
    if (!hasMore || loading) return;
    const node = sentinelRef.current;
    if (!node) return;

    const rootMargin = placement === 'top'
      ? '40px 0px 0px 0px'
      : '0px 0px 40px 0px';
    const observer = new IntersectionObserver(
      entries => {
        if (entries.some(e => e.isIntersecting)) {
          onLoadOlderRef.current();
        }
      },
      { root: scrollRoot ?? null, rootMargin, threshold: 0 },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [hasMore, loading, placement, scrollRoot]);

  if (!hasMore) return null;
  const resolvedLoading = loadingLabel ?? t('loadingOlder');
  const resolvedIdle = idleLabel ?? t('scrollToLoadOlder');
  return (
    <div ref={sentinelRef} className="flex items-center justify-center py-2 text-xs text-theme-muted">
      {loading ? (
        <span className="flex items-center gap-2">
          <span className="animate-spin h-3 w-3 border-2 border-slate-300 border-t-slate-600 rounded-full" />
          {resolvedLoading}
        </span>
      ) : (
        <span>{resolvedIdle}</span>
      )}
    </div>
  );
}
