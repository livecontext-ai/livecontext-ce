import { useEffect, useRef } from 'react';
import { wsClient } from './ws-client';

/**
 * Run `callback` every time the WebSocket session is re-established after a drop.
 *
 * Anything published while the tab had no session is lost (the gateway keeps no backlog),
 * so a component that renders live state re-reads it from REST here. Not called for the
 * page's first session: initial loads already fetch.
 */
export function useWsReconnected(callback: () => void): void {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => wsClient.onReconnected(() => callbackRef.current()), []);
}
