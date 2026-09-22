/**
 * Conversation service module - Type definitions
 * The ConversationService class has been removed (dead code).
 * All conversation API calls go through unifiedApiService.
 */

import type { ToolAuthorizationSubject } from '@/contexts/StreamingContext';

/** One persisted approval/authorization card awaiting the user (see Conversation.pendingActions). */
export interface PendingActionEntry {
  tool_call?: any;
  waiting_for?: string;
  services?: Array<{
    serviceType: string;
    serviceName: string;
    iconSlug?: string;
    toolName?: string;
    description?: string;
  }>;
  reason?: string;
  needs_attention?: boolean;
  original_request?: string;
  context_summary?: string;
  created_at?: string;
  expires_at?: string;
  // Tool authorization (waiting_for === 'tool_authorization')
  rule?: string;
  tool_name?: string;
  action?: string;
  tool_call_id?: string;
  args_summary?: string;
  application_id?: string; // publication id for application:acquire (reopen install modal on reload)
  /** What the card is about (workflow + version, or the cron being armed), so a reload still names it. */
  subject?: ToolAuthorizationSubject;
  // Question card (waiting_for === 'user_question'): the questions the ask_user call showed
  questions?: Array<{
    header: string;
    question: string;
    options: Array<{ label: string; description?: string }>;
    multiSelect?: boolean;
  }>;
}

/**
 * The kinds of conversation the platform holds. Mirrors the backend {@code ConversationKind}
 * enum; the wire values are these exact lowercase strings.
 */
export type ConversationKind = 'chat' | 'studio';

/**
 * What a conversation is, with the one fallback rule applied: no kind means chat.
 *
 * <p>Stated once, here, rather than at each `conv.kind === 'studio'` call site. The call sites that
 * matter are negative ones ("is this an ordinary chat"), and each would have to remember to treat
 * undefined as chat; the one that forgets routes every legacy conversation into the studio.
 */
export function conversationKind(conversation: Pick<Conversation, 'kind'> | null | undefined): ConversationKind {
  return conversation?.kind === 'studio' ? 'studio' : 'chat';
}

/**
 * Where a conversation LIVES: the route that can actually serve it.
 *
 * <p>Stated once because three surfaces navigate to a conversation - the sidebar, the global
 * search, and the chat page's own redirect for a studio thread reached by an old link - and the
 * rule is the same at all three. Duplicated, it is three places to forget, and forgetting is
 * silent: a studio conversation opened at /app/c renders a blank thread (the chat renderer
 * suppresses generation envelopes) and its composer sends the next message to a CHAT model with
 * those envelopes as prior context, which is the one state the immutable kind exists to prevent.
 *
 * <p>Takes the conversation rather than the kind so the "no kind means chat" fallback cannot be
 * skipped on the way in: every conversation stored before the column existed has none.
 */
export function conversationRoute(conversation: Pick<Conversation, 'id' | 'kind'>): string {
  return conversationKind(conversation) === 'studio'
    ? `/app/studio/${conversation.id}`
    : `/app/c/${conversation.id}`;
}

export interface Conversation {
  id: string;
  userId: string;
  title: string;
  model: string;
  provider: string;
  createdAt: string;
  updatedAt: string;
  messageCount: number;
  workflowId?: string;
  agentId?: string;
  /**
   * What the conversation IS: a chat with a model or an agent, or a studio thread of pure
   * generations.
   *
   * <p>Optional on this type because a response from a server that predates the field carries no
   * kind, and a conversation with no kind is a chat - which is what every conversation was. Read it
   * through {@link conversationKind} rather than directly, so that fallback is stated once.
   *
   * <p>Decided when the conversation is created and immutable afterwards: the server refuses a
   * change with a 400 rather than ignoring it.
   */
  kind?: ConversationKind;
  firstMessagePreview?: string;
  /** Legacy single pending action (kept in sync with pendingActions[0] for back-compat). */
  pendingAction?: PendingActionEntry;
  /**
   * Parallel pending actions (approval/authorization cards) awaiting the user. The agent
   * raises cards asynchronously without pausing the run, so several can be pending at once.
   * The chat renders one card per entry. Falls back to [pendingAction] when absent.
   */
  pendingActions?: PendingActionEntry[];
  approvedServices?: string[];
  chatConfig?: {
    temperature?: number;
    maxTokens?: number;
    maxIterations?: number;
    executionTimeout?: number;
    inactivityTimeout?: number;
    systemPrompt?: string;
    toolsMode?: 'all' | 'none';
    webSearch?: boolean;
    autoAuthorizeTools?: boolean;
    defaultSkillIds?: string[];
  };
  compactionMarker?: CompactionMarker | null;
}

/**
 * Lightweight projection of the backend's cold-zone summary. Surfaced so the
 * chat UI can render a persistent "prior context summarised" divider between
 * the last covered turn and the HOT+WARM window.
 *
 * - `turnsCovered` lists the message indices that have been folded into the
 *   summary. The divider is placed after `max(turnsCovered)`.
 * - `generatedAt` / `model` are informational (tooltip/debug). Null-tolerant:
 *   pre-v1 envelopes or partial model outputs may omit them.
 * - `status` is the recall trust level: 'active' (or null/absent for rows
 *   predating the field) when fresh, 'stale' once the backend flagged the
 *   summary unreliable (history shrank under it, or the user corrected course
 *   and no regeneration has landed yet).
 */
export interface CompactionMarker {
  turnsCovered: number[];
  generatedAt: string | null;
  model: string | null;
  status?: 'active' | 'stale' | null;
}

export interface MessageAttachment {
  storageId: string;
  type: 'IMAGE' | 'PDF' | 'TEXT' | 'OTHER';
  fileName: string;
  mimeType: string;
  sizeBytes?: number;
}

export interface Message {
  id: string;
  conversationId: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  model: string;
  timestamp: string;
  /**
   * Server-assigned monotonic creation time (@CreationTimestamp). This is the AUTHORITATIVE
   * ordering key: the backend fetch is `ORDER BY created_at`, and it comes from a single
   * (server) clock, unlike `timestamp` which is the CLIENT wall-clock for an optimistic
   * local message and the SERVER clock once persisted. Sorting prefers this (see
   * messageUtils.sortMessagesByTime) so a just-sent message can never reorder ahead of the
   * prior reply under client/server clock skew. Absent only on optimistic local messages until
   * they are persisted; every persisted row carries it, including the live `message_added` WS event.
   */
  createdAt?: string;
  toolCalls?: string;
  toolCallId?: string;
  toolName?: string;
  /**
   * Server-assigned id of the agent execution (one chat turn) this message belongs to. Minted at
   * dispatch and persisted on every message of the turn (backend Message.executionId / MessageDto).
   * Used to aggregate the Conversation Activity card by execution and to fetch that execution's
   * observability metrics. Absent on optimistic local messages until they are persisted.
   */
  executionId?: string;
  agentId?: string;
  feedback?: number | null;
  attachments?: MessageAttachment[];
  /**
   * Frontend-only optimistic flag set on a message added by the local user via the composer.
   * Drives "always scroll to bottom on user-send" without sniffing id prefixes.
   * Never serialized to the backend - toWireMessage() strips it before any HTTP call.
   */
  pendingLocal?: boolean;
}

export interface ConversationResponse {
  content: Conversation[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
  first: boolean;
  last: boolean;
  hasNext: boolean;
  hasPrevious: boolean;
  numberOfElements: number;
}
