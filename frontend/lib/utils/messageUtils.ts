/**
 * Message utilities for merging, deduplication, and sorting
 * Following DRY principle - single source of truth for message operations
 */

import { Message } from '@/lib/api/conversationApi';
import { parseUtcAware } from '@/lib/utils/dateFormatters';

// Local + backend messages must share the same time reference frame.
// Backend timestamps may arrive as TZ-less `LocalDateTime` (Jackson default);
// local timestamps are generated via `new Date().toISOString()` which always
// ends with 'Z'. Without parseUtcAware, a 5-second dedup window would
// misclassify identical messages whose backend timestamp was raw-parsed as
// browser-local and shifted by the user's offset.
const msgTime = (m: Message): number => parseUtcAware((m.timestamp || 0) as string).getTime();

// Server-assigned monotonic creation time, in epoch millis, or null when absent (an optimistic
// local message that has not been persisted yet). Every persisted row carries it, including the
// ones delivered live via the message_added WS event. This is the AUTHORITATIVE ordering key - it
// comes from a single (server) clock, whereas `timestamp` mixes the client clock (optimistic
// send) with the server clock (once persisted).
const createdAtMs = (m: Message): number | null => {
  const raw = m.createdAt;
  if (!raw) return null;
  const t = parseUtcAware(raw).getTime();
  return Number.isNaN(t) ? null : t;
};

/**
 * Check if two messages are duplicates based on content and timestamp
 */
export function areMessagesDuplicate(msg1: Message, msg2: Message, timeWindowMs: number = 5000): boolean {
  if (msg1.role !== msg2.role || msg1.content !== msg2.content) {
    return false;
  }

  const time1 = msgTime(msg1);
  const time2 = msgTime(msg2);

  return Math.abs(time1 - time2) < timeWindowMs;
}

/**
 * Sort messages into display (chronological, ascending) order.
 *
 * Ordering key precedence:
 *  1. `createdAt` (server-monotonic) when BOTH messages have it - the authoritative order,
 *     identical to the backend's `ORDER BY created_at`, immune to client/server clock skew.
 *  2. An optimistic local message (no `createdAt` yet - a just-sent user message not yet
 *     persisted) sorts AFTER any server-persisted message. It represents the current activity
 *     and must never jump ahead of prior turns because the CLIENT clock that stamped its
 *     `timestamp` happens to lag the server (the reported queue / stop / error relaunch reorder
 *     bug).
 *  3. `timestamp` only as the last resort - between two messages that BOTH lack `createdAt`
 *     (e.g. several optimistic messages), or to break an exact `createdAt` tie.
 */
export function sortMessagesByTime(messages: Message[]): Message[] {
  return [...messages].sort((a, b) => {
    const ca = createdAtMs(a);
    const cb = createdAtMs(b);
    if (ca !== null && cb !== null) {
      return ca !== cb ? ca - cb : msgTime(a) - msgTime(b);
    }
    if (ca !== null) return -1; // a persisted, b optimistic -> a first
    if (cb !== null) return 1;  // b persisted, a optimistic -> b first
    return msgTime(a) - msgTime(b);
  });
}

/**
 * Filter local temporary messages that have equivalents in backend messages
 */
function filterReplacedTemporaryMessages(
  localMessages: Message[],
  backendMessages: Message[]
): Message[] {
  return localMessages.filter(localMsg => {
    const msgId = localMsg.id || '';

    // For temp messages, check if there's an equivalent backend message
    if (msgId.startsWith('temp-')) {
      const hasEquivalent = backendMessages.some(backendMsg =>
        backendMsg.role === localMsg.role &&
        backendMsg.content === localMsg.content &&
        Math.abs(msgTime(backendMsg) - msgTime(localMsg)) < 10000 // 10 seconds window
      );
      return !hasEquivalent;
    }

    // Keep datasource and 'new' messages if not duplicated
    if (msgId.startsWith('datasource-') || msgId === 'new') {
      return !backendMessages.some(backendMsg => backendMsg.id === localMsg.id);
    }

    // For other local messages, keep if not in backend
    return !backendMessages.some(backendMsg => backendMsg.id === localMsg.id);
  });
}

/**
 * Merge backend messages with local messages, avoiding duplicates
 * This is the main function used when loading messages for a conversation
 */
export function mergeMessages(
  backendMessages: Message[],
  localMessages: Message[],
  isNewConversation: boolean = false
): Message[] {
  // Loading a DIFFERENT conversation discards the previous conversation's local messages - EXCEPT
  // when the backend returned nothing while we still hold local messages. A conversation that was
  // just created (e.g. send-from-Home) frequently has no rows persisted yet at fetch time, so
  // adopting an empty backend snapshot here would wipe the optimistic/streamed messages and render
  // the conversation empty until a manual reload (the "new conversation shows empty" clobber). Keep
  // local in that race; a later reload/refetch reconciles with the authoritative backend rows.
  if (isNewConversation) {
    if (backendMessages.length === 0 && localMessages.length > 0) {
      return sortMessagesByTime(localMessages);
    }
    return sortMessagesByTime(backendMessages);
  }

  // Filter local messages to keep only those not replaced by backend
  const filteredLocalMessages = filterReplacedTemporaryMessages(localMessages, backendMessages);

  // Start with backend messages
  const mergedMessages = [...backendMessages];

  // Add local messages that aren't duplicates
  filteredLocalMessages.forEach(localMsg => {
    // Skip if already exists by ID
    if (mergedMessages.some(msg => msg.id === localMsg.id)) {
      return;
    }

    // Skip if duplicate by content
    if (mergedMessages.some(msg => areMessagesDuplicate(msg, localMsg))) {
      return;
    }

    mergedMessages.push(localMsg);
  });

  return sortMessagesByTime(mergedMessages);
}

/**
 * Check if two message arrays are equivalent (same IDs and content)
 * Used to avoid unnecessary state updates
 */
export function areMessageArraysEqual(arr1: Message[], arr2: Message[]): boolean {
  if (arr1.length !== arr2.length) {
    return false;
  }

  return arr1.every((msg, idx) => {
    const other = arr2[idx];
    return msg.id === other?.id && msg.content === other?.content;
  });
}
