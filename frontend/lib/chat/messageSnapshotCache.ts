'use client';

/**
 * "What this conversation looked like a moment ago."
 *
 * A chat page can be REMOUNTED while the user is looking at it, with no navigation they asked
 * for. The end-of-stream URL sync is the everyday case: a conversation started from /app/chat
 * gets its own /app/c/{id} route once the reply is persisted, which swaps the page component
 * for a fresh instance. That instance starts with an empty message list, so the thread blanks
 * to a skeleton and repaints a few hundred milliseconds later - on screen it reads as the page
 * refreshing itself right after the answer finished.
 *
 * The messages are already known at that point, so nothing justifies showing a loading state.
 * This keeps the last painted list per conversation in module scope (outliving any React tree)
 * so the remounted page paints the exact same content on its first frame and the authoritative
 * fetch reconciles behind it.
 *
 * Deliberately NOT a store: nothing subscribes, nothing renders from it. It is a hand-off
 * between two lives of the same component, read once at mount.
 *
 * TWO invariants keep it honest, and both are enforced here rather than trusted to callers:
 *
 *  1. ONLY COMPLETE THREADS. A partially paginated list would be seeded next to a page counter
 *     that no longer describes it, and scroll-up would then walk pages the reader already has.
 *     `rememberMessages` refuses anything the caller did not declare complete, so a seeded mount
 *     is always "page 0, nothing older" - the exact state a page-0 fetch will confirm.
 *  2. CLIENT ONLY. The module is evaluated in the server bundle too, and `recallMessages` runs
 *     during render. Writes are refused on the server so one request can never seed another
 *     request's transcript, and reads are refused so a future server-side writer cannot start
 *     leaking through this door either.
 */

import type { Message } from '@/lib/api/conversationApi';
import { onConversationMessagesCleared } from '@/lib/chat/conversationMessagesBus';

/** Enough for the conversation being read plus a few recently visited ones. */
const MAX_CONVERSATIONS = 5;

/**
 * Defensive ceiling. A complete thread is by definition one page (ten messages today), so this
 * is never reached in practice - it only bounds the damage if "complete" ever grows a new
 * meaning, and it REFUSES rather than truncates: half a thread is not a snapshot.
 */
const MAX_MESSAGES = 40;

// Map preserves insertion order, so the oldest key is the first one - LRU eviction is a delete.
const snapshots = new Map<string, Message[]>();

const isClient = (): boolean => typeof window !== 'undefined';

export interface RememberOptions {
  /**
   * True when `messages` is the WHOLE conversation - nothing older is left to page in.
   * A caller that has only paginated part of the thread must pass false: see invariant 1.
   */
  complete: boolean;
}

/**
 * Record the list currently on screen for `conversationId`.
 *
 * Refused for: an empty list (a conversation that has messages must never be re-seeded with
 * nothing - that is the blank this exists to avoid, and an empty conversation has nothing to
 * hide), an incomplete thread, an implausibly long one, or a server render.
 */
export function rememberMessages(
  conversationId: string | null | undefined,
  messages: Message[],
  options: RememberOptions,
): void {
  if (!isClient()) return;
  if (!conversationId || messages.length === 0) return;
  if (!options.complete || messages.length > MAX_MESSAGES) {
    // Keeping a stale-but-complete earlier snapshot would be worse than none: it would seed a
    // thread the reader has since grown past. Drop what we have and let the mount fetch paint.
    snapshots.delete(conversationId);
    return;
  }

  // Re-inserting moves the key to the most-recent position.
  snapshots.delete(conversationId);
  snapshots.set(conversationId, messages);

  while (snapshots.size > MAX_CONVERSATIONS) {
    const oldest = snapshots.keys().next().value;
    if (oldest === undefined) break;
    snapshots.delete(oldest);
  }
}

/**
 * The last complete list painted for `conversationId`, or undefined when nothing is known.
 * Callers use it as INITIAL state only - it is a stand-in for the authoritative fetch, never a
 * substitute, and the first fetch that lands REPLACES it rather than merging into it.
 */
export function recallMessages(conversationId: string | null | undefined): Message[] | undefined {
  if (!isClient()) return undefined;
  if (!conversationId) return undefined;
  return snapshots.get(conversationId);
}

/** Drop a conversation's snapshot (wiped or deleted - re-seeding it would resurrect ghosts). */
export function forgetMessages(conversationId: string | null | undefined): void {
  if (!isClient()) return;
  if (!conversationId) return;
  snapshots.delete(conversationId);
}

/** Test seam - no production caller. */
export function resetMessageSnapshots(): void {
  snapshots.clear();
}

// A wipe is exactly the case where a stale snapshot would be worse than no snapshot.
onConversationMessagesCleared(forgetMessages);
