import { conversationKind, type Conversation } from '@/lib/api/conversation.types';

/**
 * Whether the chat page is talking to Orbi, the general chat, rather than to an agent.
 *
 * <p>Orbi is the chat with no agent. An agent conversation has its own avatar in the composer,
 * a studio conversation belongs to another surface, and a conversation that has not loaded yet
 * is unknown: answering "Orbi" for it would flash the mascot on an agent thread for the
 * second it takes to load, so it answers no until the conversation is known. The one exception
 * is a conversation the page itself just started from Orbi: it is known to be Orbi before it
 * loads (it only loads once the first reply is done), so Orbi stays through the first turn.
 */
export function isOrbiChat({
  conversationId,
  conversation,
  agentId,
  startedFromOrbi = false,
}: {
  /** The conversation in the URL, null on the home page. */
  conversationId: string | null | undefined;
  /** The loaded conversation, if any. */
  conversation: Pick<Conversation, 'id' | 'kind' | 'agentId'> | null | undefined;
  /** Any agent already resolved for this page (URL `agentId`, forward link, sidebar list). */
  agentId: string | null | undefined;
  /** The page started this conversation itself, from Orbi (its home with no agent). */
  startedFromOrbi?: boolean;
}): boolean {
  if (agentId) return false;
  if (!conversationId) return true;
  if (!conversation || conversation.id !== conversationId) return startedFromOrbi;
  if (conversation.agentId) return false;
  return conversationKind(conversation) === 'chat';
}
