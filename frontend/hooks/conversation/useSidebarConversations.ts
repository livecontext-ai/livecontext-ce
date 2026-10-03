'use client';

import { useCallback, useMemo } from 'react';
import { conversationApi, type Conversation } from '@/lib/api/conversationApi';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { sortByRecency } from '@/lib/utils/conversationRecency';
import { emitConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';
import { useConversationList } from './useConversationList';
import { useConversationMutations } from './useConversationMutations';
import { useDeletedConversationsSync } from './useDeletedConversationsSync';

export interface UseSidebarConversationsOptions {
  /**
   * Whether the surface being shown lists conversations at all. False keeps the
   * query dormant instead of fetching a list nothing will draw.
   */
  autoLoad: boolean;
  /** The conversation the main panel is on, if any. */
  currentConversationId?: string | null;
}

export interface UseSidebarConversations {
  /** Ready to render: merged with the shared cache, de-duplicated, most recent first. */
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  loadMore: () => Promise<void>;
  /** Fetch one conversation the list has not cached yet (a deep link, a fresh row). */
  loadConversationById: (conversationId: string) => Promise<Conversation | null>;
  /** Delete it, everywhere: server, shared cache, and this list. */
  deleteConversation: (conversationId: string) => Promise<void>;
  /** Wipe an agent conversation's history, and tell the surface showing it. */
  clearMessages: (conversationId: string) => Promise<void>;
  /**
   * Rewind to page 0 and re-read from the server. It empties this hook's own
   * copy first; the shared cache backstops the rows meanwhile, so the list does
   * not blank while the request is in flight.
   */
  refresh: () => Promise<void>;
}

/**
 * The conversation list, for the sidebar and nothing else.
 *
 * The sidebar used to call `useConversationHistory`, the CHAT PAGE's hook. That
 * hook is three hooks: the list, the message store, and the mutations. The
 * sidebar draws titles - it has never drawn a message - so two thirds of it was
 * dead weight the sidebar nonetheless paid for on every render: a second
 * `useMessages` instance with its own message array, its own abort controller
 * and two 60-second timeout warnings, none of it reachable by any pixel.
 *
 * Dead weight is the cheap half of the problem. The expensive half is that dead
 * weight LOOKS live: "clear this conversation's messages" called that private
 * store's `clearMessages`, which emptied an array nothing rendered while the
 * transcript stayed on screen. A store that no one reads cannot fail visibly,
 * so the bug sat there. The wipe now goes out as an event
 * ({@link emitConversationMessagesCleared}) that the surface actually showing
 * the messages listens for.
 *
 * What IS kept is everything list-shaped, deletion sync included: without it a
 * conversation deleted from another surface disappears from the shared cache
 * and then reappears in the sidebar from this hook's own copy.
 */
export function useSidebarConversations({
  autoLoad,
  currentConversationId,
}: UseSidebarConversationsOptions): UseSidebarConversations {
  const { state: appState } = useUnifiedApp();
  const sharedConversations = appState.conversations;
  const sharedHasMore = appState.hasMore;

  const {
    conversations: rawConversations,
    loading,
    error,
    hasMore,
    loadMoreConversations,
    loadConversationById,
    forceRefreshConversations,
    setConversations,
  } = useConversationList({ autoLoad });

  const mutations = useConversationMutations({
    onConversationDeleted: (deletedId) => {
      setConversations((prev) => prev.filter((conv) => conv.id !== deletedId));
    },
  });

  // Deletions that happen somewhere else (the chat page, another tab) reach us
  // as an id vanishing from the shared cache. Without this the row would come
  // back, because the merge below falls back to this hook's own copy whenever
  // the shared cache is the shorter of the two - which is exactly what a
  // deletion makes it.
  useDeletedConversationsSync({
    sharedConversations,
    listConversations: rawConversations,
    removeFromList: (disappearedIds) => {
      setConversations((prev) => prev.filter((conv) => !disappearedIds.has(conv.id)));
    },
    currentConversationId: currentConversationId ?? null,
    onCurrentDeleted: () => {},
  });

  /**
   * One list out of the two stores. Moved here from the component unchanged.
   *
   * The shared cache carries a COMPACT projection (id, title, timestamps) and is
   * what live chat updates write to; this hook's copy carries the full server
   * DTO. Membership comes from whichever store has more rows, so a conversation
   * present in only one of them is still listed. The row's BODY comes from the
   * full DTO whenever there is one, because the compact projection has no
   * provider or message count and its missing timestamps would make the hover
   * pill claim every row is the same age.
   *
   * Preferring the DTO wholesale is deliberate, and was tried the other way
   * round: overlaying the cache's `title`/`updatedAt` on top looks like it would
   * carry a live rename through, but `addConversations` only ever INSERTS
   * (UnifiedAppContext) and no caller sends `updatedAt` to `updateConversation`,
   * so the cache's timestamp is frozen at first sight. Overlaying it pins the
   * order to that frozen value and defeats the refetch below, whose whole job is
   * to heal order after a chat exit. The consequence of leaving it as it is: a
   * title synthesised while its conversation is ALREADY in the fetched page
   * shows late, at the next refetch.
   */
  const conversations = useMemo(() => {
    const source = sharedConversations.length >= rawConversations.length ? sharedConversations : rawConversations;
    const fullById = new Map<string, Conversation>();
    for (const conv of rawConversations) fullById.set(conv.id, conv);

    const seen = new Map<string, Conversation>();
    for (const conv of source) {
      if (seen.has(conv.id)) continue;
      const full = fullById.get(conv.id);
      if (full) {
        seen.set(conv.id, full);
        continue;
      }
      if ('id' in conv && 'title' in conv && !('userId' in conv)) {
        seen.set(conv.id, {
          id: conv.id,
          title: conv.title,
          userId: '',
          model: '',
          provider: '',
          createdAt: conv.createdAt ?? new Date().toISOString(),
          updatedAt: conv.updatedAt ?? new Date().toISOString(),
          messageCount: 0,
          workflowId: conv.workflowId,
          agentId: conv.agentId,
          taskId: conv.taskId,
        } as Conversation);
      } else {
        seen.set(conv.id, conv as Conversation);
      }
    }

    // Most recent activity first, on the authoritative timestamps the merge
    // above preferred - so a conversation that just answered climbs back to the
    // top instead of keeping the cache's insertion order.
    return sortByRecency(Array.from(seen.values()));
  }, [sharedConversations, rawConversations]);

  const deleteConversation = useCallback(
    async (conversationId: string) => {
      await mutations.deleteConversation(conversationId, rawConversations, currentConversationId ?? null);
    },
    [mutations, rawConversations, currentConversationId],
  );

  const clearMessages = useCallback(async (conversationId: string) => {
    await conversationApi.clearConversationMessages(conversationId);
    // Only after the server said yes: announcing a wipe that did not happen
    // would empty the visible transcript and leave it empty until a reload.
    emitConversationMessagesCleared(conversationId);
  }, []);

  return {
    conversations,
    loading,
    // BOTH sources, as the hook this replaced did. `useConversationMutations`
    // swallows a failed delete into its own error state rather than throwing, so
    // reading only the list's error would close the confirmation modal on a
    // server refusal, leave the row where it was, and say nothing at all.
    error: error || mutations.error,
    hasMore: sharedHasMore !== undefined ? sharedHasMore : hasMore,
    loadMore: loadMoreConversations,
    loadConversationById,
    deleteConversation,
    clearMessages,
    refresh: forceRefreshConversations,
  };
}
