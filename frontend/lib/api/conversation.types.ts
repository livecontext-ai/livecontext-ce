/**
 * Conversation service module - Type definitions
 * The ConversationService class has been removed (dead code).
 * All conversation API calls go through unifiedApiService.
 */

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
  size: number;
  hasNext: boolean;
  hasPrevious: boolean;
}
