'use client';

import { useState, useCallback, useRef, useEffect } from 'react';
import { conversationApi, Message } from '@/lib/api/conversationApi';
import { toWireMessage } from '@/lib/api/messageWire';
import { mergeMessages, reconcileMessageIdentity, sortMessagesByTime, areMessagesDuplicate } from '@/lib/utils/messageUtils';
import { deduplicateById } from '@/lib/utils/deduplication';
import { rememberMessages, recallMessages } from '@/lib/chat/messageSnapshotCache';
import { useErrorHandler } from '@/hooks/utils/useErrorHandler';
import { useAbortController } from '@/hooks/utils/useAbortController';
import { useTimeoutWarning } from '@/hooks/utils/useTimeoutWarning';

/**
 * Rows a page-0 fetch returns. Named because two separate decisions depend on it being the
 * SAME number: the first paint of a conversation, and whether a list can still be handed to the
 * next mount as "the whole thread".
 */
const DEFAULT_PAGE_SIZE = 10;

export interface UseMessagesOptions {
  /**
   * If set, scopes message fetches to a single execution. Use "latest" to resolve to
   * the most recent execution of the conversation server-side.
   */
  executionId?: string;
  /**
   * Conversation this hook is opening. Read once, to paint the FIRST frame from the snapshot
   * the previous mount left behind, so a remount of the chat page (the end-of-stream URL sync
   * is the everyday one) shows the thread it was already showing instead of a skeleton. The
   * authoritative fetch still runs and REPLACES it.
   * Only consulted when {@link UseMessagesOptions.retainAcrossRemount} is set.
   */
  conversationId?: string;
  /**
   * Declares this instance the OWNER of the conversation's cross-remount snapshot: the one
   * whose list is handed to the next mount. Off by default, and it must stay off for every
   * secondary view of the same conversation.
   *
   * There is exactly one owner per conversation, and ownership is opt-in rather than inferred,
   * because the snapshot is keyed by conversation id and shared across React trees: a second
   * instance holding a PARTIAL view (a side panel that fetched one page, or one execution)
   * would otherwise overwrite the main thread's snapshot with its slice, and the next remount
   * of the main chat would silently paint a truncated conversation.
   */
  retainAcrossRemount?: boolean;
}

/**
 * Options for a single loadMessages call.
 */
export interface LoadMessagesOptions {
  /**
   * Background reconciliation of a thread the reader is already looking at.
   *
   * What it never does is INTERRUPT: no loading flag, no slow-load warning, no pagination
   * rewind, no error banner, and above all no clearing of the list when the fetch fails.
   * What it still does is CORRECT, which is the whole point of running it - so it applies
   * server truth to the "load older" affordance and retires a stale error banner on success.
   * Both are documented at their call sites below.
   *
   * Silent is about the screen, not about the caller: a failed silent load still REJECTS, so
   * whoever asked for it can retry or give up deliberately. Swallowing the error here would
   * leave no one able to notice that the persisted reply never landed.
   */
  silent?: boolean;
}

export interface UseMessagesReturn {
  // State
  messages: Message[];
  hasMoreMessages: boolean;
  loadingOlderMessages: boolean;
  messagesLoading: boolean;
  sendingMessage: boolean;
  loadingTimeout: boolean;
  error: string | null;

  // Actions
  loadMessages: (conversationId: string, limit?: number, options?: LoadMessagesOptions) => Promise<void>;
  loadOlderMessages: (conversationId: string, limit?: number) => Promise<void>;
  addMessageLocal: (conversationId: string, message: Omit<Message, 'id' | 'conversationId'>, messageId?: string) => void;
  updateMessageLocal: (messageId: string, updates: Partial<Message>) => void;
  removeMessageLocal: (messageId: string) => void;
  sendMessage: (conversationId: string, message: Omit<Message, 'id' | 'conversationId' | 'createdAt'>) => Promise<void>;
  clearMessages: () => void;
  clearError: () => void;
  clearLoadingTimeout: () => void;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
}

/**
 * Hook for managing messages within a conversation
 * Handles loading, pagination, local operations, and sending
 */
export function useMessages(options?: UseMessagesOptions): UseMessagesReturn {
  const executionId = options?.executionId;
  // Utility hooks (DRY - eliminates duplication)
  const { error, handleError, clearError } = useErrorHandler();
  const abortController = useAbortController();
  const loadingTimeout = useTimeoutWarning(
    60000,
    () => setLoadingTimeout(true),
    '⚠️ Loading messages timeout reached'
  );
  const sendingTimeout = useTimeoutWarning(
    60000,
    () => setLoadingTimeout(true),
    '⚠️ Sending message timeout reached'
  );

  // Opt-in, never inferred: see UseMessagesOptions.retainAcrossRemount. An execution-scoped
  // instance holds one slice of a conversation and can therefore never be the owner.
  const ownsSnapshot = options?.retainAcrossRemount === true && !executionId;

  // State. Seeded from the snapshot of the previous mount when there is one: a remount of an
  // open conversation must not blank the transcript on its way back in.
  const [messages, setMessages] = useState<Message[]>(
    () => (ownsSnapshot ? recallMessages(options?.conversationId) : undefined) ?? [],
  );
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [sendingMessage, setSendingMessage] = useState(false);
  const [loadingTimeoutState, setLoadingTimeout] = useState(false);

  // Message pagination state
  const [messagePage, setMessagePage] = useState(0);
  const [hasMoreMessages, setHasMoreMessages] = useState(false);
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [conversationHasMoreMessages, setConversationHasMoreMessages] = useState<Record<string, boolean>>({});

  // Refs for stable access
  const conversationHasMoreMessagesRef = useRef<Record<string, boolean>>({});
  const messagePageRef = useRef(0);
  const hasMoreMessagesRef = useRef(false);
  const loadingOlderMessagesRef = useRef(false);
  const currentLoadingConversationRef = useRef<string | null>(null);
  const lastLoadedConversationRef = useRef<string | null>(null);
  // Conversation the current `messages` belong to - the key the snapshot is written under.
  // Seeded state already belongs to options.conversationId, so it starts there.
  const heldConversationIdRef = useRef<string | null>(ownsSnapshot ? options?.conversationId ?? null : null);
  // Set while the state came from a SEED and no fetch has confirmed it yet.
  // The first fetch to land must REPLACE rather than merge, whichever conversation it is for:
  // a seed can carry an optimistic row the server never persisted (a send that errored out),
  // and the keep-local merge branch would then re-adopt that phantom bubble on every single
  // mount, forever. Not keyed by conversation on purpose - if the reader moves on before the
  // seeded conversation's own fetch lands, the seed must still be dropped rather than blended
  // into whichever conversation is now on screen.
  // Bumped by anything that invalidates work in flight: a new explicit load, or a clear. A
  // silent load captures it and stands down if it has moved on by the time its rows arrive.
  const loadGenerationRef = useRef(0);
  const unconfirmedSeedRef = useRef<string | null>(
    ownsSnapshot && messages.length > 0 ? options?.conversationId ?? null : null,
  );

  // How many messages a page-0 fetch would return, as of the last one that ran. A snapshot is
  // only safe to hand to the next mount when the list HELD is no bigger than that, because the
  // seeded list has to survive the fetch that confirms it (a seeded mount adopts server truth).
  //
  // "Fits in the first page" rather than "nothing left to page in": scroll to the top of a
  // 25-message thread and there is nothing older left to fetch, yet page 0 still returns 10, so
  // seeding 25 would show the transcript collapse on the next remount.
  //
  // A COUNT rather than a boolean, because the two sides can disagree. The thread can shrink
  // under a list that is still held: compaction or a deletion takes the server to one page while
  // the merge keeps the older rows already on screen. A boolean derived from the server would
  // flip back to "complete" over 20 stale rows and hand over a snapshot its own confirming fetch
  // then truncates to 10 - the same collapse, through the other door.
  //
  // Starts at the page size, because a conversation that has only ever had messages added
  // locally (send-from-Home) is one page by construction - up to that many. A thread that needs
  // more than one page records -1, which no list can be small enough to satisfy.
  //
  // State rather than a ref because the snapshot effect has to RE-RUN when it changes: a fetch
  // can report a different thread while returning a list that reconciles to the same objects,
  // and a ref would leave the effect asleep on a snapshot that is no longer safe to hand over.
  // React bails out when the value is unchanged, so the common case costs no extra render.
  const [firstPageThreadSize, setFirstPageThreadSize] = useState(DEFAULT_PAGE_SIZE);

  // CRITICAL: Keep a ref to setMessages to ensure callbacks always use the latest setter
  // This fixes the issue where stale closures in streaming callbacks would not update the correct state
  const setMessagesRef = useRef(setMessages);
  setMessagesRef.current = setMessages;

  // Also keep a ref to current messages for duplicate detection
  const messagesRef = useRef(messages);
  messagesRef.current = messages;

  // Hand the painted list to the next mount of this conversation, so a remount of the chat
  // page (the end-of-stream /app/c/{id} URL sync swaps the page component) paints the same
  // thread on its first frame instead of blanking to a skeleton.
  //
  // `complete` is what keeps the seed coherent with the pagination state it is seeded next to:
  // only a thread that FITS IN THE FIRST PAGE is recorded (the comparison against
  // firstPageThreadSize below - "nothing left to page in" is a different and wrong question),
  // so a seeded mount is always "page 0, nothing above", which is exactly what its page-0 fetch
  // will confirm.
  useEffect(() => {
    if (!ownsSnapshot) return;
    rememberMessages(heldConversationIdRef.current, messages, {
      complete: messages.length <= firstPageThreadSize,
    });
  }, [messages, firstPageThreadSize, ownsSnapshot]);

  // Sync refs with state
  conversationHasMoreMessagesRef.current = conversationHasMoreMessages;
  messagePageRef.current = messagePage;
  hasMoreMessagesRef.current = hasMoreMessages;
  loadingOlderMessagesRef.current = loadingOlderMessages;

  // Load messages for a conversation.
  // Default page size = first paint of the chat history; lazy-load (loadOlderMessages)
  // fetches earlier batches when the user scrolls to the top of the panel.
  const loadMessages = useCallback(async (
    conversationId: string,
    limit: number = DEFAULT_PAGE_SIZE,
    loadOptions?: LoadMessagesOptions,
  ) => {
    // A silent load is a background reconciliation of a list the user is already reading. It
    // must never INTERRUPT it: no loading flag, no slow-load warning, no pagination rewind, no
    // error banner, and above all no clearing on failure. It does still CORRECT it on the way
    // out - server truth for the "load older" affordance, and retiring a stale banner - which
    // is the entire reason for running it.
    const silent = loadOptions?.silent === true;

    // An explicit load OWNS the hook: it invalidates whatever was in flight and records which
    // conversation is being loaded. A silent one does neither. Renewing the controller here
    // would invalidate a cold mount's own fetch (its rows would be thrown away and the reader
    // would watch an empty list until the reconciliation lands), and claiming the conversation
    // ref would redirect that load's staleness guard at it.
    //
    // So a silent load carries a GENERATION instead. Anything that invalidates work in flight
    // bumps it - another load, or a clear - and a silent result whose generation is no longer
    // current is dropped. That covers the three cases a single ref cannot: no explicit load has
    // ever run (send-from-Home: apply), one is running for another conversation (drop), and the
    // list was cleared underneath (drop, or the wipe is undone by its own reconciliation).
    const generation = silent
      ? loadGenerationRef.current
      : (loadGenerationRef.current += 1);
    const controller = silent ? null : abortController.renew();
    if (!silent) {
      loadingTimeout.start();
      setMessagesLoading(true);
    }
    console.log('📥 [loadMessages] START silent=' + silent + ' for:', conversationId);

    if (!silent) {
      currentLoadingConversationRef.current = conversationId;
      clearError();
      setMessagePage(0);
      setLoadingTimeout(false);

      // Resetting pagination collapses the "load older" affordance for a frame, so it is part
      // of the visible reset an explicit load owns and a silent reconciliation must skip.
      const existingHasMore = conversationHasMoreMessagesRef.current[conversationId];
      if (existingHasMore !== undefined) {
        setHasMoreMessages(existingHasMore);
      } else {
        setHasMoreMessages(false);
      }
    }

    try {
      console.log('📥 [loadMessages] Fetching messages for conversation:', conversationId);
      const response = await conversationApi.getPaginatedMessages(conversationId, 0, limit, { executionId });

      // A silent result is applied only while it is still the current generation.
      if (silent && generation !== loadGenerationRef.current) {
        console.log('⏭️ [loadMessages] Ignoring superseded silent result for:', conversationId);
        return;
      }

      // Check if this request was aborted (DRY: use isAborted from hook)
      if (controller?.signal.aborted) {
        console.log('⏭️ [loadMessages] Request was aborted for:', conversationId);
        if (!silent) setMessagesLoading(false);
        return;
      }

      if (!silent && currentLoadingConversationRef.current !== conversationId) {
        console.log('⏭️ [loadMessages] Ignoring stale result for conversation:', conversationId);
        setMessagesLoading(false);
        return;
      }

      const loadedMessages = (response as any).content || response;
      // A paginated response carries the count; the defensive bare-array branch above does not,
      // and reading it as 0 would declare every thread too big to hand over.
      const reportedTotal = (response as any).totalElements;
      const totalElements = typeof reportedTotal === 'number'
        ? reportedTotal
        : (Array.isArray(loadedMessages) ? loadedMessages.length : 0);
      const totalPages = Math.ceil(totalElements / limit);

      // Sort messages by timestamp (ascending for chat display)
      const sortedMessages = sortMessagesByTime(loadedMessages as Message[]);

      // Check if we're loading a different conversation
      const isSwitchingConversation = lastLoadedConversationRef.current !== null &&
        lastLoadedConversationRef.current !== conversationId;

      // A seeded list has never been checked against the server. Adopt the server rows
      // wholesale on the first fetch that confirms it, the same way a conversation switch
      // does - mergeMessages still protects the "backend has nothing yet" race either way.
      const seededConversationId = unconfirmedSeedRef.current;
      unconfirmedSeedRef.current = null;
      const isUnconfirmedSeed = seededConversationId !== null;
      const adoptServerTruth = isSwitchingConversation || isUnconfirmedSeed;

      // A seed for a DIFFERENT conversation is not a starting point, it is someone else's
      // transcript. Adopting server truth is not enough to drop it: mergeMessages keeps local
      // rows when the backend returns nothing (the just-created-conversation race), which would
      // leave conversation A's messages rendered under conversation B, permanently.
      //
      // Only the foreign rows go. A message optimistically sent into THIS conversation while
      // the fetch was in flight is exactly what that same race protects, so it stays.
      const seedBelongsElsewhere = isUnconfirmedSeed && seededConversationId !== conversationId;

      // Update the last loaded conversation ref
      lastLoadedConversationRef.current = conversationId;
      heldConversationIdRef.current = conversationId;

      // Merge with local messages, avoiding duplicates
      // IMPORTANT: If loading a new conversation, start fresh (don't merge with prev)
      setMessages(prev => {
        if (isSwitchingConversation) {
          console.log('🔄 [loadMessages] Switching conversation - clearing old messages');
        }

        // Use utility function to merge messages (DRY principle)
        const merged = mergeMessages(
          sortedMessages,
          seedBelongsElsewhere ? prev.filter(m => m.conversationId === conversationId) : prev,
          adoptServerTruth,
        );

        // Re-key onto the objects already on screen. What this buys TODAY is the whole-array
        // result: an unchanged thread returns `prev`, so the reconciliation COMMITS NOTHING -
        // same array, same flags, and the tree reconciles to itself without touching the DOM.
        // Preserving each unchanged message's reference on top of that is for memoized consumers;
        // the transcript is currently rendered inline with no React.memo, so it is correctness
        // kept ready rather than a saving already banked.
        //
        // The bail-out itself is not new (areMessageArraysEqual did it), but it compared only
        // id and content, so a reconciliation whose ONLY change was a persisted toolCalls /
        // executionId / feedback was silently discarded. That is the second bug this fixes.
        return reconcileMessageIdentity(prev, merged);
      });

      // Recorded on EVERY fetch, silent included: this is server truth about the thread's
      // shape, and the snapshot's correctness depends on it even when nothing visible moves.
      // Measured against the DEFAULT page size, not this call's `limit`: the fetch that will
      // confirm a seeded mount is always a default-size page 0, so a thread counted as "one
      // page" under a larger limit would be truncated by its own confirmation.
      setFirstPageThreadSize(totalElements <= DEFAULT_PAGE_SIZE ? totalElements : -1);

      // "Are there older messages not yet loaded?" is page-relative, and only an EXPLICIT load
      // rewinds to page 0. A silent reconcile keeps whatever page the reader had scrolled back
      // to, so it has to ask the question from there.
      //
      // Skipping this in silent mode was a real regression: a turn that grows a one-page
      // conversation past the page size left hasMoreMessages false, and the scroll-up
      // affordance never appeared - older messages were unreachable, with no spinner and no
      // error, until the reader navigated away and back. Resetting the flag on the way IN is
      // the visible flicker silent must avoid; applying server truth on the way OUT is not.
      const pageAfterThisLoad = silent ? messagePageRef.current : 0;
      setHasMoreMessages(pageAfterThisLoad + 1 < totalPages);
      if (!silent) {
        setMessagePage(0);
      }
      // The map is only the PROVISIONAL value a later explicit load of this conversation starts
      // from, before its own fetch answers. This writer records the page-0 answer because an
      // explicit load begins at page 0; loadOlderMessages writes its own page-relative answer
      // to the same key, so the map holds whichever ran last. That is pre-existing, and
      // harmless precisely because every load overwrites it from server truth.
      const hasMoreFromFirstPage = totalPages > 1;
      setConversationHasMoreMessages(prev => (
        prev[conversationId] === hasMoreFromFirstPage
          ? prev
          : { ...prev, [conversationId]: hasMoreFromFirstPage }
      ));

      if (!silent) {
        loadingTimeout.clear(); // DRY: Use utility hook
        setMessagesLoading(false);
      } else {
        // The explicit path clears the error on the way IN; a silent load cannot do that (it
        // would blank a real banner before knowing whether it is about to fail), so it retires
        // it on the way out instead. Without this, a banner raised by an earlier failure
        // survived every later SILENT success, until something explicit happened to reload.
        clearError();
      }
      console.log('✅ [loadMessages] DONE silent=' + silent + ', messages:', sortedMessages.length, 'for:', conversationId);
    } catch (err) {
      // Ignore AbortError (request was intentionally cancelled)
      if (err instanceof Error && err.name === 'AbortError') {
        console.log('📭 [loadMessages] Request aborted (expected behavior)');
        if (!silent) setMessagesLoading(false);
        return;
      }

      // A background reconciliation that fails changes NOTHING on screen: no error banner and,
      // above all, no wipe of the list the user is reading (the setMessages([]) below would
      // blank a perfectly good conversation on a transient 5xx). The caller still finds out.
      if (silent) {
        console.warn('[loadMessages] silent reconciliation failed, keeping current messages', err);
        throw err;
      }

      // DRY: Use error handler hook
      handleError(err, 'Failed to load messages', 'loadMessages');
      setMessages([]);
      setHasMoreMessages(false);
      loadingTimeout.clear();
      setMessagesLoading(false);
    }
  }, [abortController, loadingTimeout, clearError, handleError, executionId]);

  // Load older messages (for lazy loading)
  const loadOlderMessages = useCallback(async (conversationId: string, limit: number = 10) => {
    if (!hasMoreMessagesRef.current || loadingOlderMessagesRef.current) return;

    setLoadingOlderMessages(true);
    clearError();

    try {
      const nextPage = messagePageRef.current + 1;
      const response = await conversationApi.getPaginatedMessages(conversationId, nextPage, limit, { executionId });
      const newMessages = (response as any).content || response;

      if (newMessages && Array.isArray(newMessages) && newMessages.length > 0) {
        // Older rows actually came in, so the thread is by definition more than one page: the
        // list held from here on can no longer be confirmed by a page-0 fetch, and it stops
        // being snapshot material until a fresh load says otherwise. An EMPTY older page says
        // nothing about the thread's shape, so it must not revoke the snapshot.
        setFirstPageThreadSize(-1);

        const sortedNewMessages = sortMessagesByTime(newMessages);

        setMessages(prev => {
          // DRY: Use deduplicateById utility
          const uniqueNewMessages = deduplicateById(sortedNewMessages, prev);
          return sortMessagesByTime([...uniqueNewMessages, ...prev]);
        });

        setMessagePage(nextPage);

        // Use server truth (totalPages or hasNext) instead of length === limit. The legacy heuristic
        // misfires when the next page returns *exactly* `limit` rows - caller keeps thinking more
        // exists and fires empty fetches forever on scroll-up.
        const totalElements: number | undefined = (response as any).totalElements;
        const hasMoreSignal: boolean | undefined = (response as any).hasNext;
        const hasMore = typeof hasMoreSignal === 'boolean'
          ? hasMoreSignal
          : (typeof totalElements === 'number'
              ? (nextPage + 1) * limit < totalElements
              : newMessages.length === limit);
        setHasMoreMessages(hasMore);
        setConversationHasMoreMessages(prev => ({
          ...prev,
          [conversationId]: hasMore
        }));
      } else {
        setHasMoreMessages(false);
        setConversationHasMoreMessages(prev => ({
          ...prev,
          [conversationId]: false
        }));
      }
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to load older messages', 'loadOlderMessages');
    } finally {
      setLoadingOlderMessages(false);
    }
  }, [clearError, handleError, executionId]);

  // Add a message locally without API call
  // CRITICAL: Uses setMessagesRef.current to always get the latest setter
  // This prevents stale closure issues when called from streaming callbacks
  const addMessageLocal = useCallback((
    conversationId: string,
    message: Omit<Message, 'id' | 'conversationId'>,
    messageId?: string
  ) => {
    const localMessage: Message = {
      id: messageId || `temp-${Date.now()}-${Math.random()}`,
      conversationId,
      ...message
    };
    heldConversationIdRef.current = conversationId;

    // Use the ref to get the latest setMessages function
    // This is critical for streaming callbacks that may have stale closures
    const currentSetMessages = setMessagesRef.current;

    currentSetMessages(prev => {
      if (messageId) {
        const existingIndex = prev.findIndex(msg => msg.id === messageId);
        if (existingIndex >= 0) {
          const updated = [...prev];
          updated[existingIndex] = localMessage;
          return sortMessagesByTime(updated);
        }
      }

      // Check for duplicate (use utility function - DRY)
      if (prev.some(existingMsg => areMessagesDuplicate(existingMsg, localMessage, 5000))) {
        return prev;
      }

      return sortMessagesByTime([...prev, localMessage]);
    });
  }, []);

  // Update a message locally
  const updateMessageLocal = useCallback((messageId: string, updates: Partial<Message>) => {
    setMessages(prev => {
      const updated = prev.map(msg => msg.id === messageId ? { ...msg, ...updates } : msg);
      return sortMessagesByTime(updated);
    });
  }, []);

  // Remove a message locally
  const removeMessageLocal = useCallback((messageId: string) => {
    setMessages(prev => prev.filter(msg => msg.id !== messageId));
  }, []);

  // Send message with thinking indicator
  const sendMessage = useCallback(async (
    conversationId: string,
    message: Omit<Message, 'id' | 'conversationId' | 'createdAt'>
  ) => {
    setSendingMessage(true);
    clearError();
    setLoadingTimeout(false);

    // DRY: Use timeout warning hook
    sendingTimeout.start();

    try {
      const newMessage = await conversationApi.addMessage(conversationId, toWireMessage(message));
      heldConversationIdRef.current = conversationId;
      setMessages(prev => [...prev, newMessage as Message]);
      sendingTimeout.clear();
    } catch (err) {
      // DRY: Use error handler hook
      handleError(err, 'Failed to send message', 'sendMessage');
      sendingTimeout.clear();
    } finally {
      setSendingMessage(false);
    }
  }, [clearError, handleError, sendingTimeout]);

  // Clear messages
  const clearMessages = useCallback(() => {
    console.log('🔄 Clearing messages');

    // DRY: Use abort controller hook
    abortController.abort();

    setMessages([]);
    // Anything already in flight is now about a list that no longer exists on screen. Without
    // this, a silent reconciliation landing after the wipe re-fills it with the rows the server
    // was just told to forget: the wipe undone by its own request.
    loadGenerationRef.current += 1;
    currentLoadingConversationRef.current = null;
    lastLoadedConversationRef.current = null;
    // The empty list belongs to no conversation - keep the previous snapshot for whoever
    // reopens that conversation, but stop attributing what happens next to it.
    heldConversationIdRef.current = null;
    unconfirmedSeedRef.current = null;
    setFirstPageThreadSize(DEFAULT_PAGE_SIZE);
  }, [abortController]);

  // Clear loading timeout (kept for backwards compatibility)
  const clearLoadingTimeout = useCallback(() => {
    loadingTimeout.clear();
    sendingTimeout.clear();
  }, [loadingTimeout, sendingTimeout]);

  return {
    messages,
    hasMoreMessages,
    loadingOlderMessages,
    messagesLoading,
    sendingMessage,
    loadingTimeout: loadingTimeoutState,
    error,
    loadMessages,
    loadOlderMessages,
    addMessageLocal,
    updateMessageLocal,
    removeMessageLocal,
    sendMessage,
    clearMessages,
    clearError,
    clearLoadingTimeout,
    setMessages,
  };
}
