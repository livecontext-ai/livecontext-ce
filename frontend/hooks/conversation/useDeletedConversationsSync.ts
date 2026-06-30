'use client';

import { useEffect, useRef } from 'react';

/**
 * Reconcile a local "list" with a "shared" store when conversations are deleted
 * *outside* the normal mutation flow (another tab, direct `removeConversation`
 * on the unified context, etc.).
 *
 * Why this exists as its own hook:
 *
 *   The local list and the shared context are two parallel stores of the same
 *   data. After `loadConversationById` prepends a not-yet-cached conversation
 *   to the local list, the shared context catches up *one render later* via
 *   the local→shared sync effect. The previous heuristic
 *   (`shared.length < list.length` ⇒ deletion) misclassified that one-render
 *   lag as a deletion and called `clearMessages`, which aborts the in-flight
 *   `/messages/page` fetch via `AbortController.abort()` - the user saw
 *   skeleton → blank.
 *
 *   The correct primitive is *IDs that disappeared from shared between
 *   renders* - compare current shared IDs with the set we observed last
 *   render. That signal only fires for genuine external deletions and is
 *   immune to the list-grew-first lag.
 *
 *   Effect deps are the *array references* of shared/list, not their
 *   `.length`. Length-stable mutations (delete A + add B in the same tick)
 *   would otherwise let `previousSharedIdsRef` go stale and fabricate a
 *   disappearance for an already-replaced ID on the next length-changing tick.
 */
export interface UseDeletedConversationsSyncOptions {
  /** Conversations as they appear in the shared (UnifiedApp) store. */
  sharedConversations: { id: string }[];
  /** Conversations as they appear in the local list. */
  listConversations: { id: string }[];
  /** Drop the given IDs from the local list. Called only on genuine disappearance. */
  removeFromList: (disappearedIds: Set<string>) => void;
  /** Currently-selected conversation ID, or null. */
  currentConversationId: string | null;
  /** Called when the currently-selected conversation is among the disappeared. */
  onCurrentDeleted: () => void;
}

export function useDeletedConversationsSync({
  sharedConversations,
  listConversations,
  removeFromList,
  currentConversationId,
  onCurrentDeleted,
}: UseDeletedConversationsSyncOptions): void {
  // Initialised once shared *and* list both have data. Until then we have no
  // meaningful "previous" baseline - seeding from an empty set would let the
  // first non-empty render be interpreted as N IDs disappearing.
  const initialisedRef = useRef(false);
  const previousSharedIdsRef = useRef<Set<string>>(new Set());

  // Pull mutable inputs into refs so the effect deps stay focused on data
  // changes, not on caller identity churn.
  const removeFromListRef = useRef(removeFromList);
  removeFromListRef.current = removeFromList;
  const onCurrentDeletedRef = useRef(onCurrentDeleted);
  onCurrentDeletedRef.current = onCurrentDeleted;
  const currentConversationIdRef = useRef(currentConversationId);
  currentConversationIdRef.current = currentConversationId;

  useEffect(() => {
    const currentSharedIds = new Set(sharedConversations.map(c => c.id));

    if (!initialisedRef.current) {
      if (sharedConversations.length > 0 && listConversations.length > 0) {
        initialisedRef.current = true;
        previousSharedIdsRef.current = currentSharedIds;
      }
      return;
    }

    const disappeared = new Set<string>();
    previousSharedIdsRef.current.forEach(id => {
      if (!currentSharedIds.has(id)) disappeared.add(id);
    });
    previousSharedIdsRef.current = currentSharedIds;

    if (disappeared.size === 0) return;

    removeFromListRef.current(disappeared);
    const currentId = currentConversationIdRef.current;
    if (currentId && disappeared.has(currentId)) {
      onCurrentDeletedRef.current();
    }
    // Depend on array references, not `.length`. See header comment.
  }, [sharedConversations, listConversations]);
}
