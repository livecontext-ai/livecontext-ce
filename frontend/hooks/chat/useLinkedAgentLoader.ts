import { useEffect, useRef } from 'react';
import { orchestratorApi } from '@/lib/api/orchestrator';
import type { Agent } from '@/lib/api/orchestrator';
import { fetchLinkedAgent, type LinkedAgentApi } from '@/lib/chat/linkedAgent';

export interface UseLinkedAgentLoaderOptions {
  /** The open conversation's id (from the route). Empty/undefined = new chat. */
  conversationId?: string | null;
  /** Forward-link agent id (conversations.agent_id), resolved by the caller. */
  linkedAgentId?: string | null;
  isAuthenticated: boolean;
  isReady: boolean;
  /** Called with the resolved agent, or null when there is none / on error. */
  onAgentResolved: (agent: Agent | null) => void;
  /** Toggles the loading flag around the fetch. */
  onLoadingChange: (loading: boolean) => void;
  /** Injectable for tests; defaults to the real orchestrator client. */
  api?: LinkedAgentApi;
}

/**
 * Loads the agent linked to the open conversation, PREFERRING its forward link
 * (`conversations.agent_id`, passed as `linkedAgentId`) over the reverse by-conversation
 * lookup (see {@link fetchLinkedAgent}).
 *
 * The load is keyed on the `(conversationId, linkedAgentId)` PAIR, not the conversation id
 * alone: an agent conversation whose forward link is only known AFTER the conversation /
 * sidebar list finishes loading transitions `linkedAgentId` from unknown to a real id, and
 * this re-fetches so the composer swaps the model selector for the agent avatar. Keying on
 * the conversation id alone (the old behavior) would latch the first (reverse, 404) result
 * and never recover. The ref is intentionally NOT reset on error, to avoid an infinite
 * retry loop on a transient failure (429/5xx); it resets naturally when the pair changes.
 */
export function useLinkedAgentLoader({
  conversationId,
  linkedAgentId,
  isAuthenticated,
  isReady,
  onAgentResolved,
  onLoadingChange,
  api = orchestratorApi,
}: UseLinkedAgentLoaderOptions): void {
  const loadedKeyRef = useRef<string | null>(null);

  useEffect(() => {
    // New chat (no conversation) - clear any previously resolved agent once.
    if (!conversationId) {
      if (loadedKeyRef.current !== null) {
        loadedKeyRef.current = null;
        onAgentResolved(null);
      }
      return;
    }

    // Wait until the token provider is ready before calling the API.
    if (!isAuthenticated || !isReady) return;

    const loadKey = `${conversationId}:${linkedAgentId || ''}`;
    if (loadedKeyRef.current === loadKey) return;
    loadedKeyRef.current = loadKey;

    onLoadingChange(true);
    fetchLinkedAgent(api, { linkedAgentId, conversationId })
      .then((agent) => onAgentResolved(agent ?? null))
      .catch((err: unknown) => {
        const e = err as { status?: number; message?: string };
        // 404 (no agent linked / not visible) is expected - show the model selector.
        if (e?.status !== 404 && !e?.message?.includes('404')) {
          console.error('Error loading linked agent:', err);
        }
        onAgentResolved(null);
      })
      .finally(() => onLoadingChange(false));
  }, [conversationId, linkedAgentId, isAuthenticated, isReady, onAgentResolved, onLoadingChange, api]);
}
