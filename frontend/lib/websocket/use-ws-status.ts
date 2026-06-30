import { useSyncExternalStore } from 'react';
import { wsClient } from './ws-client';
import type { WsConnectionStatus } from './ws-types';

/**
 * React hook to get the current WebSocket connection status.
 * Uses useSyncExternalStore for tear-safe reads.
 */
export function useWebSocketStatus(): WsConnectionStatus {
  return useSyncExternalStore(
    wsClient.subscribeStatus.bind(wsClient),
    wsClient.getStatusSnapshot,
    // Server snapshot (SSR) - always disconnected
    () => 'disconnected' as WsConnectionStatus
  );
}
