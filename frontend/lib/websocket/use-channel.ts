import { useEffect, useRef } from 'react';
import { wsClient } from './ws-client';
import type { ChannelHandler } from './ws-types';

/**
 * React hook to subscribe to a WebSocket channel.
 *
 * - Subscribes on mount / when channel changes
 * - Unsubscribes on unmount / when channel changes
 * - Uses stable handler ref to avoid re-subscriptions on handler change
 *
 * @param channel The channel to subscribe to (null = don't subscribe)
 * @param handler Callback for incoming messages
 * @param options Options like requestSnapshot
 */
export function useChannel<T = unknown>(
  channel: string | null,
  handler: (data: T) => void,
  options?: { requestSnapshot?: boolean }
): void {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  const requestSnapshot = options?.requestSnapshot ?? false;

  useEffect(() => {
    if (!channel) return;

    const wrappedHandler: ChannelHandler = (data) => {
      handlerRef.current(data as T);
    };

    const unsubscribe = wsClient.subscribe(channel, wrappedHandler, requestSnapshot);

    return () => {
      unsubscribe();
    };
  }, [channel, requestSnapshot]);
}
