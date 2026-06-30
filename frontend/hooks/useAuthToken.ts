'use client';

import { useEffect, useRef, useState } from 'react';
import { apiClient } from '@/lib/api/api-client';

/**
 * Returns a "fresh enough" JWT for use as an {@code Authorization: Bearer} header on
 * fetches that a component issues directly (e.g. the bulk zip-download POSTs in
 * {@code FileBrowser} / {@code StorageExplorerTab}). The provider returned by
 * {@link apiClient.getTokenProvider} delegates to OIDC silent refresh under the hood, so
 * each invocation returns the current valid access token; this hook re-reads it on
 * visibility change + a slow timer so a long-open tab doesn't hold a stale value.
 *
 * <p><strong>Never put this token in a URL.</strong> File/media rendering does NOT use this
 * hook - it fetches with the header and renders from a blob:/data: URL via
 * {@code useAuthedObjectUrl} / {@code fetchAuthedBlobUrl}. The token is a long-lived,
 * full-scope bearer; a {@code ?token=} in any URL leaks the whole session (copy-paste,
 * CDN / proxy / analytics logs, browser history).
 *
 * Strategy:
 * - Capture token at mount.
 * - Re-fetch on tab visibility change (user backgrounded the tab past
 *   token expiry then returned). This is the common case - a long
 *   conversation left open overnight comes back with a stale token.
 * - Re-fetch on a slow periodic timer as a safety net (default 30 min;
 *   the realm is configured for a 14-day access-token lifespan in
 *   {@code deploy/scripts/configure-keycloak.sh:142}, so refreshes are
 *   rare; the timer is mostly a defense against missed visibility events
 *   and clock skew on suspended laptops).
 *
 * The component re-renders only when the token actually changes (string
 * equality), so a request issued on each render reads a fresh header value on
 * provider rotation without burning render cycles in between.
 */
export function useAuthToken(refreshIntervalMs: number = 30 * 60 * 1000): string | undefined {
  const [token, setToken] = useState<string | undefined>(undefined);
  const cancelledRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;

    const fetchToken = async () => {
      try {
        const provider = apiClient.getTokenProvider();
        if (!provider) return;
        const fresh = await provider();
        if (cancelledRef.current) return;
        const next = fresh || undefined;
        // Avoid a state update (and re-render) when the OIDC client returned
        // the same access token - most invocations will NOT have rotated.
        setToken((prev) => (prev === next ? prev : next));
      } catch {
        /* silent - anonymous fallback handled by callers (broken-icon, retry). */
      }
    };

    fetchToken();
    const interval = setInterval(fetchToken, refreshIntervalMs);

    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') fetchToken();
    };
    document.addEventListener('visibilitychange', onVisibilityChange);

    return () => {
      cancelledRef.current = true;
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [refreshIntervalMs]);

  return token;
}
