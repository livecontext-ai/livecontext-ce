'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { PagedResponse } from '@/lib/api/orchestrator/agent-metrics.types';

/**
 * Lazy-load a paginated execution-detail resource (conversation, tool calls, iterations).
 *
 * The backend returns DESC (page 0 = newest batch). Items are kept in ASC order client-side
 * so consumers can render chronologically without re-sorting. `loadOlder` fetches the next page
 * and prepends to the array.
 *
 * Same shape as {@link useMessages} but generic over any execution-scoped resource. We don't
 * thread WebSocket integration here - execution detail is a read-only observability view.
 *
 * @param executionId - null/undefined disables fetching (mirrors React Query's `enabled` flag)
 * @param fetchPage - function returning a paginated response for `(page, size)`
 * @param pageSize - first paint batch size; loadOlder reuses the same size
 */
export function useExecutionPagedResource<T>(
  executionId: string | null | undefined,
  fetchPage: (executionId: string, page: number, size: number) => Promise<PagedResponse<T>>,
  pageSize: number = 30,
) {
  const [items, setItems] = useState<T[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingOlder, setLoadingOlder] = useState<boolean>(false);
  const [hasMore, setHasMore] = useState<boolean>(false);
  const [totalElements, setTotalElements] = useState<number>(0);

  const pageRef = useRef<number>(0);
  const loadingOlderRef = useRef<boolean>(false);
  const hasMoreRef = useRef<boolean>(false);
  const currentExecutionRef = useRef<string | null>(null);

  // Indirect fetchPage through a ref so callers can pass `service.method.bind(service)` inline
  // without invalidating the `load` callback on every render (which would refire the initial
  // fetch on every parent rerender). Same pattern as `LoadOlderSentinel.onLoadOlderRef`.
  const fetchPageRef = useRef(fetchPage);
  fetchPageRef.current = fetchPage;

  const load = useCallback(async () => {
    if (!executionId) {
      setItems([]);
      setHasMore(false);
      setTotalElements(0);
      pageRef.current = 0;
      hasMoreRef.current = false;
      return;
    }
    currentExecutionRef.current = executionId;
    setLoading(true);
    try {
      const response = await fetchPageRef.current(executionId, 0, pageSize);
      // Drop stale response if the executionId changed while we were fetching.
      if (currentExecutionRef.current !== executionId) return;
      // Backend returns DESC - reverse to ASC for chronological display.
      setItems([...response.content].reverse());
      pageRef.current = 0;
      const total = response.totalElements ?? response.content.length;
      setTotalElements(total);
      const more =
        typeof (response as { hasNext?: boolean }).hasNext === 'boolean'
          ? !!(response as { hasNext?: boolean }).hasNext
          : pageSize < total;
      setHasMore(more);
      hasMoreRef.current = more;
    } finally {
      setLoading(false);
    }
  }, [executionId, pageSize]);

  const loadOlder = useCallback(async () => {
    if (!executionId || !hasMoreRef.current || loadingOlderRef.current) return;
    loadingOlderRef.current = true;
    setLoadingOlder(true);
    try {
      const nextPage = pageRef.current + 1;
      const response = await fetchPageRef.current(executionId, nextPage, pageSize);
      if (currentExecutionRef.current !== executionId) return;
      // The new (older) page is DESC: prepend its reverse-ASC to the current ASC list.
      const olderAsc = [...response.content].reverse();
      setItems(prev => [...olderAsc, ...prev]);
      pageRef.current = nextPage;
      const total = response.totalElements ?? totalElements;
      const more =
        typeof (response as { hasNext?: boolean }).hasNext === 'boolean'
          ? !!(response as { hasNext?: boolean }).hasNext
          : (nextPage + 1) * pageSize < total;
      setHasMore(more);
      hasMoreRef.current = more;
    } finally {
      setLoadingOlder(false);
      loadingOlderRef.current = false;
    }
  }, [executionId, pageSize, totalElements]);

  useEffect(() => {
    load();
  }, [load]);

  // Phase 6c (2026-05-19) - clear the paginated buffer on workspace switch.
  // The execution detail view (AgentExecutionInspectorDetail) can stay
  // mounted under the SidePanel/keepMounted shell while the user switches
  // workspace; without this reset the previous workspace's tool-call /
  // conversation rows linger until the executionId itself changes.
  useOrgScopedReset(() => {
    setItems([]);
    setHasMore(false);
    setTotalElements(0);
    pageRef.current = 0;
    hasMoreRef.current = false;
    currentExecutionRef.current = null;
  });

  return { items, loading, loadingOlder, hasMore, totalElements, loadOlder, reload: load };
}
