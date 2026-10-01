'use client';

import { useCallback, useMemo, useRef } from 'react';
import { useWsReconnected } from '@/lib/websocket';
import type { StreamError } from '@/contexts/StreamingContext';

/**
 * When a surface showing a conversation's saved messages must re-read them, in ONE place, so
 * every surface gets all three moments instead of whichever its author thought of:
 *
 *  - the WebSocket session came back: anything published while the tab had none is gone (the
 *    gateway keeps no backlog), and a reply can have finished entirely in that window, whether
 *    or not this surface was following a stream;
 *  - a live stream of the conversation completed (including one StreamingContext settles from
 *    REST after a reconnect);
 *  - a live stream of it reported an error: that does not prove nothing was saved (a fallback
 *    can finish the turn under the same stream, a `done` can be lost), so the thread is re-read
 *    instead of being taken for final.
 *
 * `reread` is the surface's own reload of its own message store, and must be SILENT: none of
 * these moments may raise a spinner, reset pagination or clear the list when the fetch fails.
 * The returned callbacks plug into the StreamingContext callbacks (`sendMessage`,
 * `checkAndReconnect`); a surface that follows its conversation channel itself only needs the
 * reconnect half. The returned object and both callbacks are stable, and always call the
 * latest `reread`.
 */
export function useConversationResync(
  conversationId: string | null | undefined,
  reread: (conversationId: string) => void,
): {
  onStreamComplete: (conversationId: string) => void;
  onError: (error: StreamError, conversationId?: string) => void;
} {
  const rereadRef = useRef(reread);
  rereadRef.current = reread;
  const conversationIdRef = useRef(conversationId);
  conversationIdRef.current = conversationId;

  useWsReconnected(() => {
    const id = conversationIdRef.current;
    if (id) rereadRef.current(id);
  });

  const onStreamComplete = useCallback((id: string) => {
    rereadRef.current(id);
  }, []);

  // No conversation id means the send itself was refused: nothing can have been saved.
  const onError = useCallback((_error: StreamError, id?: string) => {
    if (id) rereadRef.current(id);
  }, []);

  return useMemo(() => ({ onStreamComplete, onError }), [onStreamComplete, onError]);
}
