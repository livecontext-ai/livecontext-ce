'use client';

import { useEffect, useRef } from 'react';
import { useAuth } from '@/lib/providers/smart-providers';
import { wsClient } from './ws-client';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';

/**
 * WebSocket provider - owns the single shared gateway connection for the whole
 * app lifetime. Mounted ONCE near the root (smart-providers), so it must NOT churn
 * the socket on navigation or re-render:
 *  - connects once when auth is ready,
 *  - re-handshakes ONLY when the active workspace changes (new `?activeOrg`),
 *  - disconnects only on logout or real unmount.
 *
 * The token getter is read through a ref so its (unstable) identity never enters
 * the effect deps. Previously `getAccessToken` was a dependency: every parent
 * re-render gave it a new identity → the effect cleanup ran `wsClient.disconnect()`
 * and reconnected, churning the socket. That dropped real-time events (the client
 * latched off after the churn) and burst the gateway per-user rate limit.
 *
 * PR25 R1 - reconnect on `currentOrgId` change so the session's `organizationId`
 * reflects the active workspace (else ChannelAuthorizer denies
 * `org:<currentOrgId>:notifications` after a mid-session workspace switch).
 */
export function WebSocketProvider({ children }: { children: React.ReactNode }) {
  const { isAuthenticated, isLoading, getAccessToken } = useAuth();
  const currentOrgId = useCurrentOrgStore((s) => s.currentOrgId);

  // Always-latest token getter, WITHOUT making it an effect dependency. Updated in
  // an effect (not during render) so the connection lifecycle effect below never
  // re-runs just because `getAccessToken`'s identity changed.
  const getAccessTokenRef = useRef(getAccessToken);
  useEffect(() => { getAccessTokenRef.current = getAccessToken; }, [getAccessToken]);

  const startedRef = useRef(false);
  const lastOrgRef = useRef<string | null | undefined>(undefined);

  // Connection lifecycle: connect once · reconnect on workspace switch · disconnect on logout.
  useEffect(() => {
    if (isLoading) return;

    if (!isAuthenticated) {
      // Logged out - tear down and allow a clean reconnect on next login.
      if (startedRef.current) {
        wsClient.disconnect();
        startedRef.current = false;
        lastOrgRef.current = undefined;
      }
      return;
    }

    if (!startedRef.current) {
      const gatewayHttp = process.env.NEXT_PUBLIC_GATEWAY_WS_URL
        || `${window.location.protocol}//${window.location.host}`;
      const gatewayUrl = gatewayHttp.replace(/^https:/, 'wss:').replace(/^http:/, 'ws:');

      // Stable providers - read live values; never re-created per render.
      const tokenProvider = (): Promise<string> => getAccessTokenRef.current();
      const activeOrgProvider = (): string | null => useCurrentOrgStore.getState().currentOrgId;

      console.log(`[WS:provider] Connecting to gateway: ${gatewayUrl} (activeOrg=${currentOrgId ?? 'personal'})`);
      wsClient.connect(gatewayUrl, tokenProvider, activeOrgProvider);
      startedRef.current = true;
      lastOrgRef.current = currentOrgId;
    } else if (lastOrgRef.current !== currentOrgId) {
      // Workspace switched - re-handshake so the session's organizationId updates.
      console.log(`[WS:provider] Active workspace changed (${lastOrgRef.current ?? 'personal'} → ${currentOrgId ?? 'personal'}); reconnecting WS`);
      lastOrgRef.current = currentOrgId;
      wsClient.reconnect();
    }
  }, [isAuthenticated, isLoading, currentOrgId]);

  // Token refresh - independent of the connection lifecycle; reads the live getter.
  useEffect(() => {
    const refreshInterval = setInterval(
      () => { wsClient.refreshToken(); },
      12 * 60 * 60 * 1000, // every 12h, well ahead of the 14-day token expiry
    );
    return () => clearInterval(refreshInterval);
  }, []);

  // Disconnect ONLY on real unmount (app teardown) - never on navigation/re-render.
  useEffect(() => () => { wsClient.disconnect(); }, []);

  return <>{children}</>;
}
