'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { conversationApi, Conversation } from '@/lib/api/conversationApi';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { queryKeys } from '@/lib/query-client';
import { useUnifiedApp } from '@/contexts/UnifiedAppContext';
import { deduplicateById } from '@/lib/utils/deduplication';
import { useErrorHandler } from '@/hooks/utils/useErrorHandler';
import { useCurrentOrgStore } from '@/lib/stores/current-org-store';
import { notifyResourceDeleted, useResourceDeleted, type ResourceDeletedDetail } from '@/lib/resources/resourceDeleted';

export interface UseConversationListOptions {
  autoLoad?: boolean;
  pageSize?: number;
}

export interface UseConversationListReturn {
  // State
  conversations: Conversation[];
  loading: boolean;
  error: string | null;
  hasMore: boolean;
  currentPage: number;
  isSearching: boolean;

  // Actions
  loadConversations: (page?: number, forceRefresh?: boolean) => Promise<void>;
  loadMoreConversations: () => Promise<void>;
  searchConversations: (searchTerm: string, searchType?: 'title' | 'content') => Promise<void>;
  clearSearch: () => Promise<void>;
  loadConversationById: (conversationId: string) => Promise<Conversation | null>;
  forceRefreshConversations: () => Promise<void>;
  refetchConversations: () => void;
  setConversations: React.Dispatch<React.SetStateAction<Conversation[]>>;
}

/**
 * Hook for managing conversation list with React Query
 * Handles pagination, search, and synchronization with shared context
 */
export function useConversationList({
  autoLoad = true,
  pageSize = 50
}: UseConversationListOptions = {}): UseConversationListReturn {
  const { isAuthenticated, user, isReady } = useAuthGuard();
  const queryClient = useQueryClient();
  const { addConversations, removeConversation: removeSharedConversation, setHasMore: setSyncHasMore, state: appState } = useUnifiedApp();
  const sharedHasMore = appState.hasMore;
  const sharedConversations = appState.conversations;

  // DRY: Use error handler hook
  const { error, setError, handleError, clearError } = useErrorHandler();

  // Local state
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [loading, setLoading] = useState(false);
  const [hasMore, setHasMore] = useState(sharedHasMore !== undefined ? sharedHasMore : false);
  const hasMoreRef = useRef(sharedHasMore !== undefined ? sharedHasMore : false);
  const [currentPage, setCurrentPage] = useState(0);
  const [isSearching, setIsSearching] = useState(false);
  const [searchTerm, setSearchTerm] = useState('');

  // Refs to avoid stale closures in callbacks
  /**
   * Conversation ids currently being announced as cascaded from an agent deletion.
   * Read by this hook's own deletion handler to recognise its own re-entry.
   */
  const cascadingConversationIdsRef = useRef<Set<string>>(new Set());

  const conversationsRef = useRef<Conversation[]>([]);
  useEffect(() => { conversationsRef.current = conversations; }, [conversations]);

  const currentPageRef = useRef(0);
  useEffect(() => { currentPageRef.current = currentPage; }, [currentPage]);

  // Track 500 errors to stop completely
  const has500ErrorRef = useRef(false);

  // Initialize currentPage based on already loaded conversations
  const hasInitializedPageRef = useRef(false);

  useEffect(() => {
    if (!hasInitializedPageRef.current && sharedConversations.length > 0 && !isSearching) {
      const estimatedPage = Math.floor(sharedConversations.length / pageSize);
      // Only fast-forward to RESUME a genuinely deeper cache (> 1 full page already loaded,
      // e.g. navigating back to chat after paginating). On a COLD mount the page-0 fetch
      // lands exactly `pageSize` items → estimatedPage === 1 → fast-forwarding here would
      // immediately fetch page 1 too (the eager double-load: "Adding 50" twice + a second
      // full re-render). Gate at > 1 so a single page stays put; scroll-driven
      // loadMoreConversations fetches further pages lazily when the user actually scrolls.
      if (estimatedPage > 1) {
        console.log(`⏩ [INIT] Fast-forwarding pagination to page ${estimatedPage} based on ${sharedConversations.length} cached items`);
        setCurrentPage(estimatedPage);
      }
      hasInitializedPageRef.current = true;
    }
  }, [sharedConversations.length, pageSize, isSearching]);

  // 2026-05-18 - drop local conversation cache on workspace switch. The
  // sidebar prefers sharedConversations (cleared by UnifiedAppContext) but
  // falls back to rawConversations from this hook when shared is empty, so
  // without resetting local state the OLD workspace's list would flash
  // through the fallback before the refetch lands. Also rewinds currentPage
  // so the next fetch starts from page 0 instead of whatever page the user
  // had paginated to in the previous workspace.
  useEffect(() => {
    return useCurrentOrgStore.subscribe(
      (s) => s.currentOrgId,
      () => {
        setConversations([]);
        setCurrentPage(0);
        setHasMore(true);
        hasMoreRef.current = true;
        hasInitializedPageRef.current = false;
        has500ErrorRef.current = false;
      },
    );
  }, []);

  // React Query for conversations - single call per page, no polling
  const {
    data: conversationsData,
    isLoading: conversationsLoading,
    error: conversationsError,
    refetch: refetchConversations
  } = useQuery({
    queryKey: queryKeys.conversations.page(currentPage, pageSize),
    queryFn: async () => {
      try {
        const response = await conversationApi.getConversations(currentPage, pageSize);
        has500ErrorRef.current = false;
        return response;
      } catch (error) {
        if (error instanceof Error) {
          const statusMatch = error.message.match(/HTTP (\d+)/);
          if (statusMatch && statusMatch[1] === '500') {
            console.error('❌ [CONVERSATIONS] Error 500 detected, stopping completely');
            has500ErrorRef.current = true;
            queryClient.cancelQueries({ queryKey: queryKeys.conversations.page(currentPage, pageSize) });
          }
        }
        throw error;
      }
    },
    enabled: !!(autoLoad && isAuthenticated && isReady && user?.sub && !has500ErrorRef.current),
    staleTime: Infinity,
    gcTime: 60 * 60 * 1000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    refetchInterval: false,
    retry: (failureCount, error) => {
      if (error instanceof Error) {
        const statusMatch = error.message.match(/HTTP (\d+)/);
        if (statusMatch && statusMatch[1] === '500') {
          has500ErrorRef.current = true;
          return false;
        }
      }
      return false;
    },
    placeholderData: (previousData) => previousData,
  });

  // Handle 500 errors
  useEffect(() => {
    if (conversationsError) {
      const errorMessage = conversationsError instanceof Error ? conversationsError.message : String(conversationsError);
      const statusMatch = errorMessage.match(/HTTP (\d+)/);
      if (statusMatch && statusMatch[1] === '500') {
        console.error('❌ [CONVERSATIONS] Error 500 detected, stopping completely');
        has500ErrorRef.current = true;
        setError('Server error (500). Conversation loading has been stopped.');
        queryClient.setQueryData(queryKeys.conversations.page(currentPage, pageSize), undefined);
        queryClient.cancelQueries({ queryKey: queryKeys.conversations.page(currentPage, pageSize) });
      }
    }
  }, [conversationsError, currentPage, pageSize, queryClient]);

  // Update conversations when React Query returns data
  useEffect(() => {
    if (conversationsData && typeof conversationsData === 'object') {
      const data = conversationsData as any;
      const content = data.content || [];
      const isLast = data.last !== undefined ? data.last : true;

      console.log(`📊 [DATA UPDATE] conversationsData received - page: ${currentPage}, content.length: ${content.length}, isLast: ${isLast}`);

      if (currentPage === 0) {
        setConversations(content);
      } else {
        setConversations(prev => {
          // DRY: Use deduplicateById utility
          const newConversations = deduplicateById(content, prev);

          if (newConversations.length > 0) {
            return [...prev, ...newConversations];
          }
          return prev;
        });
      }

      const shouldHaveMore = !isLast && content.length > 0;
      hasMoreRef.current = shouldHaveMore;
      setHasMore(shouldHaveMore);
      setSyncHasMore(shouldHaveMore);
    }
  }, [conversationsData, currentPage, setSyncHasMore]);

  /**
   * Drop conversations that no longer exist, from all three places that can show them.
   *
   * Two kinds matter here. A deleted CONVERSATION is the obvious one. A deleted
   * AGENT is the one that bit: the server deletes that agent's conversations with
   * it, and nothing told this list, so the rows sat in the sidebar until a hard
   * reload.
   *
   * All three copies have to be corrected or the row comes back:
   *  - the local array, which is what renders;
   *  - the shared context, because `useSidebarConversations` renders whichever of
   *    the two is LONGER, so filtering only the local one hands the render to the
   *    stale copy;
   *  - the React Query page cache, which is `staleTime: Infinity` with an hour of
   *    `gcTime` and `refetchOnMount: false`. Left alone it re-seeds the local array
   *    with the deleted row on the next mount of the sidebar, which is the
   *    "it came back" half of the bug.
   *
   * The invalidate that follows is the server truth: the local filter is what makes
   * the row go away NOW, the refetch is what refills the page and fixes the count.
   */
  const dropDeletedConversations = useCallback(({ kind, id }: ResourceDeletedDetail) => {
    if (kind !== 'conversation' && kind !== 'agent') return;
    // The agent branch below announces each of the agent's conversations, and that
    // dispatch is SYNCHRONOUS, so this handler re-enters while the work above is
    // still in flight. `conversationsRef` is synced in an effect and has not been
    // updated yet, so the re-entrant pass would recompute the same id and redo
    // everything: a second removeSharedConversation and a second invalidate per
    // conversation. The id sits in this set for exactly the duration of its own
    // dispatch, which is the only window in which re-entry can happen.
    if (kind === 'conversation' && cascadingConversationIdsRef.current.has(id)) return;
    const isGone = (conv: Conversation) => (
      kind === 'conversation' ? conv.id === id : conv.agentId === id
    );

    // Every cached page, not just the one on screen: `queryKeys.conversations.all`
    // is a prefix, so this reaches ['conversations', 0, 50], ['conversations', 1,
    // 50] and the search keys alike. Pages whose shape this does not recognise are
    // returned untouched. Doing this FIRST also collects the ids, which the
    // invalidate below would otherwise refetch away before anyone read them.
    const doomed = new Set<string>();
    queryClient.setQueriesData<unknown>(
      { queryKey: queryKeys.conversations.all },
      (cached) => {
        const page = cached as { content?: Conversation[] } | undefined;
        if (!page || !Array.isArray(page.content)) return cached;
        const content = page.content.filter(conv => {
          if (!isGone(conv)) return true;
          doomed.add(conv.id);
          return false;
        });
        return content.length === page.content.length ? cached : { ...page, content };
      },
    );
    conversationsRef.current.filter(isGone).forEach(conv => doomed.add(conv.id));

    // Nothing of ours was showing it: say nothing, touch nothing, and above all do
    // not refetch. A bulk delete of N resources fires N broadcasts, and a list that
    // reacted to each regardless would turn one user action into N page loads of a
    // list that held none of them.
    if (doomed.size === 0) return;

    // Called with one argument explicitly: a bare `forEach(removeSharedConversation)`
    // also hands it the index and the array, which is only harmless for as long as
    // the context's signature stays one-argument.
    doomed.forEach(convId => removeSharedConversation(convId));

    setConversations(prev => {
      const remaining = prev.filter(conv => !isGone(conv));
      return remaining.length === prev.length ? prev : remaining;
    });

    // The server truth: the local filter is what makes the row go away NOW, the
    // refetch is what refills the page and fixes the count.
    queryClient.invalidateQueries({ queryKey: queryKeys.conversations.all });

    // An agent takes its conversations with it server-side, so a `conversation-<id>`
    // side-panel tab for one of them is now showing something that does not exist.
    // The panel matches tabs by kind and id and cannot know that link, so say it
    // here, where the mapping is already in hand. Only the agent branch emits, and
    // the re-entrancy guard at the top of this handler stops the pass it causes.
    if (kind === 'agent') {
      doomed.forEach(convId => {
        cascadingConversationIdsRef.current.add(convId);
        try {
          // `cascadedFrom` states what this is: inferred from the agent's deletion,
          // not observed. The server's cascade is best-effort, so a consumer whose
          // reaction is expensive or irreversible can tell the two apart. Closing a
          // tab and dropping a row, the only consumers today, are neither.
          notifyResourceDeleted('conversation', convId, { kind: 'agent', id });
        } finally {
          cascadingConversationIdsRef.current.delete(convId);
        }
      });
    }
  }, [queryClient, removeSharedConversation]);
  useResourceDeleted(dropDeletedConversations);

  // Sync conversations with shared context
  const lastSyncedConversationsRef = useRef<string>('');

  useEffect(() => {
    if (conversations.length > 0) {
      const conversationsSignature = conversations.map(conv => `${conv.id}-${conv.title}`).join('|');

      if (conversationsSignature !== lastSyncedConversationsRef.current) {
        lastSyncedConversationsRef.current = conversationsSignature;

        const compactConversations = conversations.map(conv => ({
          id: conv.id,
          title: conv.title,
          workflowId: conv.workflowId,
          agentId: conv.agentId,
          // Forward the timestamps so UnifiedAppContext.sortByDate orders
          // correctly and the sidebar hover pill shows the real "Xm ago"
          // for each row - without these, the fallback in
          // ConversationSidebar substitutes `new Date()` and every entry
          // ends up reporting the same time.
          createdAt: conv.createdAt,
          updatedAt: conv.updatedAt,
        }));
        addConversations(compactConversations);
      }
    }
  }, [conversations, addConversations]);

  // Sync hasMore from shared context
  useEffect(() => {
    if (sharedHasMore !== undefined && sharedHasMore !== hasMore) {
      setHasMore(sharedHasMore);
    }
  }, [sharedHasMore, hasMore]);

  // Load conversations
  const loadConversations = useCallback(async (page = 0, forceRefresh = false) => {
    if (has500ErrorRef.current) {
      console.log(`⚠️ [LOAD CONVERSATIONS] Error 500 detected, stopping completely`);
      return;
    }

    if (page !== currentPageRef.current) {
      console.log(`📄 [LOAD CONVERSATIONS] Changing page from ${currentPageRef.current} to ${page}`);
      setCurrentPage(page);
      return;
    }

    if (forceRefresh) {
      console.log(`🔄 [LOAD CONVERSATIONS] Force refresh - refetching`);
      await refetchConversations();
    }
  }, [refetchConversations]);

  // Load more conversations (pagination)
  const loadMoreConversations = useCallback(async () => {
    const effectiveHasMore = sharedHasMore !== undefined ? sharedHasMore : hasMore;

    if (!effectiveHasMore || loading || isSearching || has500ErrorRef.current) {
      return;
    }

    const nextPage = currentPage + 1;
    console.log(`🔄 [LOAD MORE] Loading page ${nextPage}`);
    setCurrentPage(nextPage);
  }, [hasMore, sharedHasMore, loading, isSearching, currentPage]);

  // Load specific conversation by ID
  const loadConversationById = useCallback(async (conversationId: string) => {
    // First check if conversation is already loaded (use ref for stable reference)
    const existingConversation = conversationsRef.current.find(conv => conv.id === conversationId);
    if (existingConversation) {
      return existingConversation;
    }

    // Try to load from API directly (for fresh page loads or refreshes)
    try {
      console.log(`📥 [loadConversationById] Fetching conversation ${conversationId} from API`);
      const conversation = await conversationApi.getConversation(conversationId) as Conversation;

      console.log(`📥 [loadConversationById] Received conversation:`, {
        id: conversation?.id,
        hasPendingAction: !!conversation?.pendingAction,
        hasApprovedServices: !!conversation?.approvedServices,
        pendingAction: conversation?.pendingAction,
        approvedServices: conversation?.approvedServices
      });

      if (conversation) {
        // Add to local conversations list
        setConversations((prev: Conversation[]): Conversation[] => {
          const exists = prev.some((c: Conversation) => c.id === conversation.id);
          if (exists) {
            // Update existing
            console.log(`📥 [loadConversationById] Updating existing conversation in list`);
            return prev.map((c: Conversation) => c.id === conversation.id ? conversation : c);
          } else {
            // Add new
            console.log(`📥 [loadConversationById] Adding new conversation to list`);
            return [conversation, ...prev];
          }
        });

        console.log(`✅ [loadConversationById] Conversation loaded successfully`);
        return conversation;
      } else {
        console.warn(`⚠️ [loadConversationById] Conversation is null/undefined`);
      }
    } catch (error) {
      console.error(`❌ [loadConversationById] Failed to fetch conversation ${conversationId}:`, error);
    }

    return null;
  }, [setConversations]);

  // Force refresh conversations
  const forceRefreshConversations = useCallback(async () => {
    console.log('🔄 [FORCE REFRESH] Complete reset');
    has500ErrorRef.current = false;
    setConversations([]);
    setCurrentPage(0);
    setHasMore(true);
    setSyncHasMore(true);
    clearError();
    await refetchConversations();
  }, [refetchConversations, setSyncHasMore]);

  // Search conversations
  const searchConversations = useCallback(async (term: string, searchType: 'title' | 'content' = 'content') => {
    if (!term.trim()) return;

    setLoading(true);
    clearError();
    setIsSearching(true);
    setSearchTerm(term);

    try {
      const response = await conversationApi.searchConversations(term, searchType);

      if (!response || typeof response !== 'object') {
        throw new Error('Invalid response format from API');
      }

      const data = response as any;
      const content = data.content || [];
      const isLast = data.last !== undefined ? data.last : true;

      setConversations(content);
      setCurrentPage(0);
      setHasMore(!isLast);
      setSyncHasMore(!isLast);
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to search conversations', 'searchConversations');
    } finally {
      setLoading(false);
    }
  }, [setSyncHasMore, handleError]);

  // Clear search
  const clearSearch = useCallback(async () => {
    setIsSearching(false);
    setSearchTerm('');
    await loadConversations(0);
  }, [loadConversations]);

  return {
    conversations,
    loading: conversationsLoading || loading,
    error: conversationsError?.message || error,
    hasMore: sharedHasMore !== undefined ? sharedHasMore : hasMore,
    currentPage,
    isSearching,
    loadConversations,
    loadMoreConversations,
    searchConversations,
    clearSearch,
    loadConversationById,
    forceRefreshConversations,
    refetchConversations,
    setConversations,
  };
}
