'use client';

import { useStreamingSafe } from '@/contexts/StreamingContext';

/**
 * Hook to check if a specific conversation is currently streaming.
 * Uses StreamingContext as the single source of truth for streaming state.
 *
 * @param conversationId - The conversation ID to check
 * @returns boolean - Whether the conversation is streaming
 */
export function useIsStreaming(conversationId: string): boolean {
  const streaming = useStreamingSafe();

  if (!streaming) {
    return false;
  }

  return streaming.isStreamingConversation(conversationId);
}

/**
 * Hook to check if any conversation is currently streaming.
 *
 * @returns boolean - Whether any stream is active
 */
export function useIsAnyStreaming(): boolean {
  const streaming = useStreamingSafe();
  return streaming?.isStreaming ?? false;
}

/**
 * Hook to get the currently streaming conversation ID.
 *
 * @returns string | null - The conversation ID currently streaming, or null
 */
export function useStreamingConversationId(): string | null {
  const streaming = useStreamingSafe();
  return streaming?.state.conversationId ?? null;
}
