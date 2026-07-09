import type { Agent } from '@/lib/api/orchestrator';

/** The two orchestrator calls used to resolve a conversation's agent. */
export interface LinkedAgentApi {
  getAgent: (id: string) => Promise<Agent>;
  getAgentByConversationId: (conversationId: string) => Promise<Agent | null>;
}

/**
 * Resolve the agent linked to a conversation, PREFERRING the conversation's forward link
 * (`conversations.agent_id`, passed as `linkedAgentId`) over the reverse by-conversation
 * lookup.
 *
 * The reverse `getAgentByConversationId` reads the single-valued `agents.conversation_id`
 * column, which is null for an agent owning several conversations - so it must only be a
 * FALLBACK. Preferring the forward link keeps the composer avatar and the agent-scoped
 * skills/options working for every conversation an agent owns, not just the one (if any)
 * its reverse column happens to point at.
 *
 * Returns `null` (resolved) when neither identifier is known - the caller then shows the
 * model selector / per-conversation prefs.
 */
export function fetchLinkedAgent(
  api: LinkedAgentApi,
  { linkedAgentId, conversationId }: { linkedAgentId?: string | null; conversationId?: string | null },
): Promise<Agent | null> {
  if (linkedAgentId) return api.getAgent(linkedAgentId);
  if (conversationId) return api.getAgentByConversationId(conversationId);
  return Promise.resolve(null);
}

/** Whether a linked-agent lookup is worth running (forward link OR conversation known). */
export function canFetchLinkedAgent(
  { linkedAgentId, conversationId }: { linkedAgentId?: string | null; conversationId?: string | null },
): boolean {
  return !!linkedAgentId || !!conversationId;
}

/** Minimal shape needed to read a conversation's forward agent link. */
export interface ConversationAgentLink {
  id: string;
  agentId?: string | null;
}

/**
 * Resolve the forward-link agent id (`conversations.agent_id`) for the open conversation,
 * preferring the freshly loaded conversation object, then a cached list entry (e.g. the
 * sidebar list) so the agent resolves as soon as either source knows it - without waiting
 * on the full conversation load.
 *
 * Guards on the id so a not-yet-switched `currentConversation` never leaks its agentId
 * onto a different conversationId during navigation. Returns `null` when unknown.
 */
export function resolveConversationAgentId({
  conversationId,
  currentConversation,
  conversations,
}: {
  conversationId?: string | null;
  currentConversation?: ConversationAgentLink | null;
  conversations?: ReadonlyArray<ConversationAgentLink> | null;
}): string | null {
  if (!conversationId) return null;
  const fromCurrent = currentConversation?.id === conversationId
    ? (currentConversation?.agentId || null)
    : null;
  if (fromCurrent) return fromCurrent;
  return conversations?.find((c) => c.id === conversationId)?.agentId || null;
}
