/**
 * Message utilities for merging, deduplication, and sorting
 * Following DRY principle - single source of truth for message operations
 */

import { Message, MessageAttachment } from '@/lib/api/conversationApi';
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
  adoptServerTruth: boolean = false
): Message[] {
  // `adoptServerTruth` discards the local messages in favour of the backend rows. Two callers
  // need it: loading a DIFFERENT conversation, and confirming a list that was seeded from a
  // previous mount and has never been checked against the server.
  //
  // EXCEPT when the backend returned nothing while we still hold local messages. A conversation
  // that was just created (e.g. send-from-Home) frequently has no rows persisted yet at fetch
  // time, so adopting an empty backend snapshot here would wipe the optimistic/streamed messages
  // and render the conversation empty until a manual reload (the "new conversation shows empty"
  // clobber). Keep local in that race; a later reload/refetch reconciles with the authoritative
  // backend rows.
  if (adoptServerTruth) {
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
 * A field whose values can be compared with `!==`. A structural one (an array, an object)
 * cannot: two fetches deserialise it into different instances, so `!==` would report every
 * message as changed on every refetch and repaint the whole thread - the exact bug this file
 * exists to fix, reintroduced silently by adding one field.
 *
 * It catches wholly structural types, not a union that merely MIGHT be one
 * (`string | { a: string }` reads as primitive here). Such a field still has to be compared by
 * value; the type cannot decide that for you.
 */
type PrimitiveFieldOf<T> = {
  [K in keyof T]-?: [NonNullable<T[K]>] extends [object] ? never : K;
}[keyof T];

/** Fields compared by VALUE instead, each by a dedicated comparator below. */
type ByValueComparedMessageField = 'attachments';

/** Every field of Message that the identity check must decide about, one way or the other. */
type ComparedMessageField = Exclude<keyof Message, ByValueComparedMessageField>;

/**
 * Fields that make two messages visually/behaviourally different. `attachments` is compared
 * separately (array).
 *
 * This list is EXHAUSTIVE over Message by construction, and the assertion below makes adding a
 * field to Message a compile error until it is listed here. That matters because a forgotten
 * field does not fail loudly: it makes the end-of-stream reconciliation silently decide the
 * message did not change, and the new field never reaches the screen.
 */
const MESSAGE_IDENTITY_FIELDS = [
  'id',
  'conversationId',
  'role',
  'content',
  'model',
  'timestamp',
  'createdAt',
  'toolCalls',
  'toolCallId',
  'toolName',
  'executionId',
  'agentId',
  'feedback',
  'pendingLocal',
] as const satisfies readonly PrimitiveFieldOf<Message>[];

/**
 * Compile error naming any Message field that is neither listed above nor compared by value.
 *
 * Two ways a new field trips this. A primitive one is simply missing from the list. A
 * STRUCTURAL one cannot be added to the list at all (PrimitiveFieldOf rejects it), which is
 * the point: it has to grow a by-value comparator like areAttachmentsEqual and be named in
 * ByValueComparedMessageField.
 */
type UnComparedMessageField = Exclude<ComparedMessageField, typeof MESSAGE_IDENTITY_FIELDS[number]>;
const _everyMessageFieldIsCompared: [UnComparedMessageField] extends [never]
  ? true
  : ['compare these Message fields, by reference or by value', UnComparedMessageField] = true;
void _everyMessageFieldIsCompared;

/**
 * The same idea as MESSAGE_IDENTITY_FIELDS, one notch stricter: there is no by-value escape
 * hatch here, so a structural field added to MessageAttachment has to grow its own comparator
 * and be handled explicitly rather than being declared out of scope.
 */
const ATTACHMENT_IDENTITY_FIELDS = [
  'storageId',
  'type',
  'fileName',
  'mimeType',
  'sizeBytes',
] as const satisfies readonly PrimitiveFieldOf<MessageAttachment>[];

/** Compile error naming any MessageAttachment field left out of the comparison above. */
type UnComparedAttachmentField = Exclude<
  keyof MessageAttachment,
  typeof ATTACHMENT_IDENTITY_FIELDS[number]
>;
const _everyAttachmentFieldIsCompared: [UnComparedAttachmentField] extends [never]
  ? true
  : ['compare these MessageAttachment fields', UnComparedAttachmentField] = true;
void _everyAttachmentFieldIsCompared;

function areAttachmentsEqual(a: Message['attachments'], b: Message['attachments']): boolean {
  if (a === b) return true;
  const left = a ?? [];
  const right = b ?? [];
  if (left.length !== right.length) return false;
  return left.every((att, i) => {
    const other = right[i];
    return ATTACHMENT_IDENTITY_FIELDS.every(field => att[field] === other[field]);
  });
}

/**
 * True when two messages carry exactly the same rendered payload.
 *
 * Deliberately stricter than an id+content check: the post-stream resync exists to pick up
 * server-only fields (toolCalls, executionId, feedback), so those must count as a real change
 * while everything else must not.
 */
export function areMessagesIdentical(a: Message, b: Message): boolean {
  if (a === b) return true;
  for (const field of MESSAGE_IDENTITY_FIELDS) {
    if (a[field] !== b[field]) return false;
  }
  return areAttachmentsEqual(a.attachments, b.attachments);
}

/**
 * Re-key a freshly fetched message list onto the objects already on screen.
 *
 * Every fetch returns brand-new objects for rows that did not change, so handing the raw
 * response to setState commits a new array and re-renders the transcript even when nothing
 * moved. Returning `prev` itself when nothing changed is what makes the end-of-stream
 * reconciliation free; keeping each unchanged message's reference on top of that is what lets
 * a memoized consumer skip the bubbles that did not move, whenever one exists.
 *
 * Ordering, membership and content always come from `next` - this only preserves identity.
 */
export function reconcileMessageIdentity(prev: Message[], next: Message[]): Message[] {
  // Two empty lists are the same list. Returning `next` here would commit a fresh [] and
  // state change, in the one code path whose entire purpose is to commit nothing.
  if (prev.length === 0) return next.length === 0 ? prev : next;

  const prevById = new Map<string, Message>();
  for (const msg of prev) {
    if (msg.id) prevById.set(msg.id, msg);
  }

  let unchanged = prev.length === next.length;
  const reconciled = next.map((msg, index) => {
    const previous = msg.id ? prevById.get(msg.id) : undefined;
    const kept = previous && areMessagesIdentical(previous, msg) ? previous : msg;
    if (unchanged && kept !== prev[index]) unchanged = false;
    return kept;
  });

  return unchanged ? prev : reconciled;
}
