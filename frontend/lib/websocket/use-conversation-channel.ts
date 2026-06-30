import { useCallback } from 'react';
import { useChannel } from './use-channel';
import type { ChannelEventPayload } from './ws-types';

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function isChannelEventPayload(value: unknown): value is ChannelEventPayload {
  if (!isRecord(value)) return false;
  return (
    typeof value.v === 'number' &&
    typeof value.type === 'string' &&
    typeof value.id === 'string' &&
    typeof value.ts === 'number' &&
    Object.prototype.hasOwnProperty.call(value, 'payload')
  );
}

export function normalizeConversationChannelEvent(payload: unknown): { eventType: string; data: unknown } {
  if (isChannelEventPayload(payload)) {
    const eventType = payload.type || 'unknown';
    const data = payload.payload;

    if (isRecord(data) && typeof data.type !== 'string') {
      return { eventType, data: { ...data, type: eventType } };
    }

    return { eventType, data };
  }

  if (!isRecord(payload)) {
    return { eventType: 'unknown', data: payload };
  }

  const eventType = typeof payload.type === 'string' ? payload.type : 'unknown';
  return { eventType, data: payload };
}

/**
 * React hook to subscribe to conversation events via WebSocket.
 *
 * This is the WebSocket replacement for the legacy streaming in StreamingContext.
 * During migration, both transports run in parallel. After Phase 7, the legacy transport will be removed.
 *
 * @param conversationId - The conversation ID to subscribe to (null = don't subscribe)
 * @param onEvent - Callback for incoming conversation events
 */
export function useConversationChannel(
  conversationId: string | null,
  onEvent: (eventType: string, data: unknown) => void
): void {
  const handler = useCallback(
    (payload: unknown) => {
      if (!payload) return;
      const { eventType, data } = normalizeConversationChannelEvent(payload);
      onEvent(eventType, data);
    },
    [onEvent]
  );

  const channel = conversationId ? `conversation:${conversationId}` : null;
  useChannel<unknown>(channel, handler, { requestSnapshot: true });
}
