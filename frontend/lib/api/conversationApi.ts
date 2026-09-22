/**
 * Conversation API service
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from './api-client';
import { notifyResourceDeleted } from '@/lib/resources/resourceDeleted';
import type { ConversationKind, ConversationResponse, Message } from './conversation.types';

// Re-export types from conversation.types for convenience
export type { Conversation, ConversationKind, Message, MessageAttachment, ConversationResponse, CompactionMarker } from './conversation.types';
export { conversationKind, conversationRoute } from './conversation.types';

/**
 * API service for conversation management
 * Uses apiClient for all HTTP requests
 */
export class ConversationApiService {

  /**
   * Get conversations for a user with pagination
   * User ID is automatically added by the gateway via X-User-ID header
   *
   * Short-circuits to an empty page when no auth token is configured
   * (anonymous marketplace visitors): every caller already treats an empty
   * response as "no conversations", and the alternative is a cascade of
   * console-spamming 401s from the shell-level ConversationSidebar / picker
   * fetches that fire on any /app/* route.
   */
  async getConversations(
    page = 0,
    size = 10,
    /**
     * Narrow the listing to one kind. The server filters in the QUERY, which is the only place it
     * can be done correctly: a page is chosen by recency, so narrowing the returned page answers
     * "the studio conversations among the 20 most recent" and shows nothing to anyone whose recent
     * activity is chat.
     */
    kind?: ConversationKind
  ): Promise<ConversationResponse> {
    try {
      // getAuthToken: returning an empty list because the provider was not installed YET made a
      // signed-in user's conversation list render empty on a cold load, with nothing to correct it.
      // The other side of that trade: an anonymous visitor now pays the wait before the empty-page
      // short-circuit below. Accepted rather than avoided, because the two are indistinguishable
      // from here - "no provider" is exactly what a booting session and a signed-out one look
      // like - and this list only renders under /app/*, where an anonymous visitor is on their way
      // to the login redirect anyway.
      const token = await apiClient.getAuthToken();
      if (!token) {
        return {
          content: [],
          totalElements: 0,
          totalPages: 0,
          page,
          size,
          first: true,
          last: true,
          hasNext: false,
          hasPrevious: false,
          numberOfElements: 0,
        };
      }
      console.log(`🌐 [API CALL] GET /conversations?page=${page}&size=${size}${kind ? `&kind=${kind}` : ''} - timestamp: ${new Date().toISOString()}`);
      const params: Record<string, string> = { page: page.toString(), size: size.toString() };
      if (kind) params.kind = kind;
      const response = await apiClient.get<ConversationResponse>(`/conversations`, { params });
      return response;
    } catch (error) {
      console.error(`❌ [API ERROR] GET /conversations?page=${page}&size=${size} - error:`, error);
      throw new Error('Failed to fetch conversations');
    }
  }

  /**
   * Search conversations by title or content
   * User ID is automatically added by the gateway via X-User-ID header
   */
  async searchConversations(
    searchTerm: string,
    searchType: 'title' | 'content' = 'content',
    page = 0,
    size = 10,
  ): Promise<ConversationResponse> {
    try {
      const endpoint = searchType === 'title' ? '/conversations/search/title' : '/conversations/search/content';
      const response = await apiClient.get<ConversationResponse>(
        endpoint, { params: { searchTerm, page: String(page), size: String(size) } }
      );
      return response;
    } catch (error) {
      console.error('Error searching conversations:', error);
      throw new Error('Failed to search conversations');
    }
  }

  /**
   * Get conversation by ID
   */
  async getConversation(conversationId: string) {
    try {
      console.log(`🌐 [API CALL] GET /conversations/${conversationId}`);
      const response = await apiClient.get(`/conversations/${conversationId}`);
      console.log(`✅ [API SUCCESS] GET /conversations/${conversationId}`, response);
      return response;
    } catch (error) {
      console.error(`❌ [API ERROR] GET /conversations/${conversationId}:`, error);
      throw new Error('Failed to fetch conversation');
    }
  }

  /**
   * Get the most recent messages for a conversation, sorted chronologically (ASC).
   *
   * Wraps the paginated `/messages/page` endpoint: fetches page 0 (DESC = newest), reverses
   * the array client-side. For chats that need scroll-up lazy-load, use the `useMessages`
   * hook directly instead of this helper.
   *
   * Replaces the legacy un-paginated `getMessages` which loaded entire conversation
   * payloads (up to 200 rows with MB-sized tool_calls) and stalled the panel for seconds.
   *
   * Default size is 50 - enough for typical builder-chat test sessions but well below the
   * old 200-row ceiling that triggered OOMs on heavy tool-call payloads. Callers displaying
   * long historical chats (workflow/agent builder, trigger panel) should migrate to the
   * `useMessages` hook with scroll-up `loadOlderMessages` for true lazy-load - current
   * surfaces accept the truncation as a known follow-up.
   */
  async getRecentMessagesAsc(conversationId: string, size = 50): Promise<Message[]> {
    try {
      const response = await this.getPaginatedMessages(conversationId, 0, size);
      const content = (response as { content?: Message[] } | Message[])?.['content'] ?? response;
      if (!Array.isArray(content)) return [];
      // Backend returns DESC (newest first). Reverse for chronological ASC display.
      return [...content].reverse();
    } catch (error) {
      console.error('Error fetching recent messages:', error);
      throw new Error('Failed to fetch messages');
    }
  }

  async getPaginatedMessages(
    conversationId: string,
    page = 0,
    size = 10,
    options?: { executionId?: string }
  ) {
    try {
      const params: Record<string, string> = {
        page: page.toString(),
        size: size.toString(),
      };
      if (options?.executionId) params.executionId = options.executionId;
      const response = await apiClient.get(
        `/conversations/${conversationId}/messages/page`,
        { params }
      );
      return response;
    } catch (error) {
      console.error('Error fetching paginated messages:', error);
      throw new Error('Failed to fetch paginated messages');
    }
  }

  /**
   * Create a new conversation.
   *
   * Optional `chatConfig` - seeded from the draft settings the user edited before any
   * conversation existed. Shape matches what PUT /conversations/{id} accepts (top-level
   * flat keys + optional `turnLimits` sub-object). Backend validates it with the same
   * {@code GuardOverrides} rules as the update path.
   */
  async createConversation(conversationData: {
    title: string;
    model: string;
    provider: string;
    workflowId?: string;
    chatConfig?: Record<string, unknown>;
    /**
     * What to create. Omit for an ordinary chat, which is what every caller before the studio
     * wanted. The server refuses an unknown value with a 400 rather than quietly making a chat.
     */
    kind?: ConversationKind;
  }) {
    try {
      const response = await apiClient.post('/conversations', conversationData);
      return response;
    } catch (error) {
      console.error('Error creating conversation:', error);
      throw new Error('Failed to create conversation');
    }
  }

  /**
   * Concurrent `findWorkflowConversation` calls for the same workflow, so a burst costs one HTTP.
   * Same shape as {@code ExecutionService.inFlightStateGets} and
   * {@code PublicationService}: private field, unconditional delete on settle.
   */
  private inFlightWorkflowConversationFinds = new Map<
    string,
    Promise<{ id: string; title?: string; workflowId?: string } | null>
  >();

  /**
   * Find an existing conversation for a specific workflow (does NOT create)
   * Returns null if no conversation exists
   *
   * <p>Concurrent callers for the same workflow share one request. Opening a workflow asks this
   * from two places, {@code useWorkflowChat} and the trigger panel, each behind its own
   * already-loaded guard, and `WorkflowPanelContent` mounts from more than one surface. Measured in
   * prod 2026-08-26: 146 calls a day answered 404 on `route=conversation`, of which 145 were this
   * endpoint; 31 of 56 gaps under 2 seconds, one workflow
   * asked 57 times, arriving in bursts at ~0.05 s apart. The answer is a 404 in the normal case
   * (the conversation is created on the first message), so a page load put several identical
   * failed requests in the network log for a state that is not an error at all.
   *
   * <p><strong>Why not {@code useResourceQuery}</strong>, which AGENTS.md points at for fetches
   * and which de-duplicates by {@code queryKey} across components. Two reasons, and the second is
   * the one that settles it. Both callers are imperative sequences - find, then load messages,
   * then reconnect the stream - and `loadConversation` is additionally exposed as a callable that
   * is re-invoked after a trigger changes, so converting them means restructuring two stateful
   * components. More importantly it CACHES ({@code staleTime}), and a cached negative is precisely
   * what must not happen here (see below). So this is not "the cheap fix instead of the right
   * layer": that layer would have to be used with caching disabled for this key, which is what
   * sharing the in-flight request already achieves.
   *
   * <p>Only the IN-FLIGHT request is shared; nothing is cached. A negative answer stops being true
   * the moment someone sends a message, and a cached null would hide the conversation that message
   * created. Two callers therefore become advisory, and they cost differently:
   *   - The SEND path reads this to decide whether to create. A stale null costs nothing:
   *     `ConversationCommandService.createWorkflowConversation` is find-or-create, so the POST
   *     returns the existing row rather than a duplicate.
   *   - The REFRESH path (`loadConversation(force)`, re-invoked after a trigger changes) reads it
   *     to DISPLAY. A stale null there means the panel shows no messages, and it does not retry on
   *     its own because its already-loaded ref was set before the request went out - the next
   *     run-status tick is what recovers it. Accepted rather than solved: giving this method a
   *     `force` that bypasses sharing would drag back the identity guard that `useModels` needs and
   *     that is dead weight here, to protect a window that in practice is closed by the create
   *     landing hundreds of ms before the reload.
   *
   * <p>Note what this does NOT do: the structural cause is three call sites each behind their own
   * per-mount guard, and it is untouched. Sharing only collapses callers that overlap in time, so
   * it helps exactly while a request is open - which covers the measured bursts (~0.05 s apart,
   * far inside one RTT) and nothing slower.
   *
   * <p>The twin {@code findAgentConversation} below is deliberately left alone: it has one mount
   * point today, so its calls do not overlap. That is an observation about the current tree, not a
   * property of the method - a second mount point would give it the same burst.
   */
  async findWorkflowConversation(
    workflowId: string
  ): Promise<{ id: string; title?: string; workflowId?: string } | null> {
    const existing = this.inFlightWorkflowConversationFinds.get(workflowId);
    if (existing) {
      return existing;
    }

    const request = (async () => {
      try {
        return await apiClient.get<{ id: string; title?: string; workflowId?: string }>(
          `/conversations/workflow/${workflowId}`
        );
      } catch (error: any) {
        // 404 means no conversation exists - this is expected and normal
        const errorMessage = error?.message?.toLowerCase() || '';
        if (errorMessage.includes('not found') || errorMessage.includes('404') || error?.status === 404) {
          return null;
        }
        console.error('Error finding workflow conversation:', error);
        throw new Error('Failed to find workflow conversation');
      }
    })().finally(() => {
      // Unconditional: nothing can overwrite the entry while a request is open. The map has
      // exactly three references - the get above, the set below, and this delete - and there is no
      // await between the get's miss and the set, so no caller can interleave. The guarded
      // form in `useModels` exists because its `force` parameter bypasses the early return; there
      // is no force here, and copying the guard would ship a branch no test can reach.
      this.inFlightWorkflowConversationFinds.delete(workflowId);
    });

    this.inFlightWorkflowConversationFinds.set(workflowId, request);
    return request;
  }

  /**
   * Create a new conversation for a specific workflow
   */
  async createWorkflowConversation(
    workflowId: string,
    model?: string,
    provider?: string,
    title?: string
  ): Promise<{ id: string; title?: string; workflowId?: string }> {
    try {
      const params: Record<string, string> = {};
      if (model) params.model = model;
      if (provider) params.provider = provider;

      const response = await apiClient.post<{ id: string; title?: string; workflowId?: string }>(
        `/conversations/workflow/${workflowId}`,
        { title },
        { params }
      );
      return response;
    } catch (error) {
      console.error('Error creating workflow conversation:', error);
      throw new Error('Failed to create workflow conversation');
    }
  }

  // ==================== AGENT CONVERSATIONS ====================

  /**
   * Find an existing conversation for a specific agent (does NOT create)
   * Returns null if no conversation exists
   */
  async findAgentConversation(
    agentId: string
  ): Promise<{ id: string; title?: string; agentId?: string } | null> {
    try {
      const response = await apiClient.get<{ id: string; title?: string; agentId?: string }>(
        `/conversations/agent/${agentId}`
      );
      return response;
    } catch (error: any) {
      // 404 means no conversation exists - this is expected and normal
      const errorMessage = error?.message?.toLowerCase() || '';
      if (errorMessage.includes('not found') || errorMessage.includes('404') || error?.status === 404) {
        return null;
      }
      console.error('Error finding agent conversation:', error);
      throw new Error('Failed to find agent conversation');
    }
  }

  /**
   * Create a new conversation for a specific agent
   */
  async createAgentConversation(
    agentId: string,
    model?: string,
    provider?: string,
    title?: string
  ): Promise<{ id: string; title?: string; agentId?: string }> {
    try {
      const params: Record<string, string> = {};
      if (model) params.model = model;
      if (provider) params.provider = provider;

      const response = await apiClient.post<{ id: string; title?: string; agentId?: string }>(
        `/conversations/agent/${agentId}`,
        { title },
        { params }
      );
      return response;
    } catch (error) {
      console.error('Error creating agent conversation:', error);
      throw new Error('Failed to create agent conversation');
    }
  }

  /**
   * Update a conversation
   */
  async updateConversation(
    conversationId: string,
    conversationData: any
  ) {
    try {
      const response = await apiClient.put(`/conversations/${conversationId}`, conversationData);
      return response;
    } catch (error) {
      console.error('Error updating conversation:', error);
      throw new Error('Failed to update conversation');
    }
  }

  /**
   * Read the caller's default chat options for the active workspace (V312).
   * Scoped server-side by X-User-ID + X-Organization-ID. Returns {} when none set.
   */
  async getUserChatDefaults(): Promise<Record<string, unknown>> {
    try {
      return (await apiClient.get<Record<string, unknown>>('/v3/chat/defaults')) ?? {};
    } catch (error) {
      console.error('Error fetching user chat defaults:', error);
      return {};
    }
  }

  /**
   * Upsert the caller's default chat options for the active workspace (V312).
   * Unknown keys are dropped server-side.
   */
  async updateUserChatDefaults(config: Record<string, unknown>): Promise<Record<string, unknown>> {
    return (await apiClient.put<Record<string, unknown>>('/v3/chat/defaults', config)) ?? {};
  }

  /**
   * Delete a conversation (soft delete)
   */
  async deleteConversation(conversationId: string) {
    try {
      const result = await apiClient.delete(`/conversations/${conversationId}`);
      // La suppression peut retourner null (code 204) ou une reponse vide, c'est normal
      notifyResourceDeleted('conversation', conversationId);
      return result;
    } catch (error) {
      console.error('Error deleting conversation:', error);

      // Si l'erreur contient "Invalid response status code 204",
      // c'est probablement que la suppression a reussi côte serveur
      if (error instanceof Error && error.message.includes('Invalid response status code 204')) {
        console.log('Server returned 204 (success) but frontend had parsing issue, considering deletion successful');
        // Same broadcast as the happy path: this branch IS a success, so a surface
        // still showing the conversation must be told, or it only heals on the
        // response shape the server happens to send.
        notifyResourceDeleted('conversation', conversationId);
        return null; // On considere que la suppression a reussi
      }

      throw new Error('Failed to delete conversation');
    }
  }

  /**
   * Clear all messages from a conversation without deleting it.
   * Used for agent conversations.
   */
  async clearConversationMessages(conversationId: string) {
    try {
      await apiClient.delete(`/conversations/${conversationId}/messages`);
    } catch (error) {
      console.error('Error clearing conversation messages:', error);

      if (error instanceof Error && error.message.includes('Invalid response status code 204')) {
        return null;
      }

      throw new Error('Failed to clear conversation messages');
    }
  }

  /**
   * Permanently delete a conversation
   */
  async permanentlyDeleteConversation(conversationId: string) {
    try {
      await apiClient.delete(`/conversations/${conversationId}/permanent`);
      // Its soft-delete sibling announces, and the rule is "a delete method
      // announces", not "the reachable ones do". No UI calls this today, so the
      // line changes nothing now and saves whoever wires a button to it later.
      notifyResourceDeleted('conversation', conversationId);
    } catch (error) {
      console.error('Error permanently deleting conversation:', error);
      throw new Error('Failed to permanently delete conversation');
    }
  }

  /**
   * Add a message to a conversation
   */
  async addMessage(
    conversationId: string,
    messageData: {
      role: 'user' | 'assistant' | 'system' | 'tool';
      content: string;
      model?: string;
      timestamp?: string;
      toolCalls?: string;
      toolCallId?: string;
      toolName?: string;
    },
    /**
     * Passed straight to the client. The one caller that sets anything is the studio, which turns
     * retries OFF: writing a message CREATES a row, so a retry after a 5xx that arrived late
     * duplicates it. In a chat a duplicate is noise; in a studio thread a second copy of the
     * request envelope reads as a turn whose answer never came, which is the "this may have been
     * charged" warning shown over a generation nobody paid for.
     *
     * <p>Optional so the chat path keeps the client default: there, losing a message to a
     * transient failure is the worse of the two outcomes.
     */
    options?: { retries?: number },
  ) {
    try {
      const response = await apiClient.post(
        `/conversations/${conversationId}/messages`, messageData, options);
      return response;
    } catch (error) {
      console.error('Error adding message:', error);
      throw new Error('Failed to add message');
    }
  }

  /**
   * Update a message's toolCalls field.
   * Used to update workflow run status and duration after completion.
   */
  async updateMessageToolCalls(messageId: string, toolCalls: string) {
    try {
      const response = await apiClient.put(
        `/conversations/messages/${messageId}/toolCalls`,
        toolCalls
      );
      return response;
    } catch (error) {
      console.error('Error updating message toolCalls:', error);
      throw new Error('Failed to update message toolCalls');
    }
  }

  /**
   * Update user feedback on a message (thumbs up/down).
   */
  async updateMessageFeedback(messageId: string, feedback: number | null): Promise<void> {
    try {
      await apiClient.put(`/conversations/messages/${messageId}/feedback`, { feedback });
    } catch (error) {
      console.error('Error updating message feedback:', error);
      throw new Error('Failed to update message feedback');
    }
  }

  /**
   * Get recent conversations for a user
   * User ID is automatically added by the gateway via X-User-ID header
   */
  async getRecentConversations(userId: string, limit = 5) {
    try {
      const response = await apiClient.get(`/conversations/recent`, { params: { limit: limit.toString() } });
      return response;
    } catch (error) {
      console.error('Error fetching recent conversations:', error);
      throw new Error('Failed to fetch recent conversations');
    }
  }

  /**
   * Get conversation count for a user
   * User ID is automatically added by the gateway via X-User-ID header
   */
  async getConversationCount(userId: string) {
    try {
      const response = await apiClient.get(`/conversations/count`);
      return response as number;
    } catch (error) {
      console.error('Error fetching conversation count:', error);
      throw new Error('Failed to fetch conversation count');
    }
  }

  /**
   * Approve external services for a conversation.
   * Called when user approves access to services like Gmail, Slack, etc.
   *
   * @param conversationId - The conversation ID
   * @param services - Array of service types to approve (e.g., ['gmail', 'slack'])
   */
  async approveServices(conversationId: string, services: string[]): Promise<{
    conversationId: string;
    approvedServices: string[];
    newlyApproved: string[];
  }> {
    try {
      console.log(`🔓 [SERVICE_APPROVAL] Approving services for conversation ${conversationId}:`, services);
      const response = await apiClient.post(
        `/conversations/${conversationId}/services/approve`,
        { services }
      );
      console.log(`✅ [SERVICE_APPROVAL] Services approved:`, response);
      return response as {
        conversationId: string;
        approvedServices: string[];
        newlyApproved: string[];
      };
    } catch (error) {
      console.error('Error approving services:', error);
      throw new Error('Failed to approve services');
    }
  }

  /**
   * Get approved services for a conversation.
   *
   * @param conversationId - The conversation ID
   */
  async getApprovedServices(conversationId: string): Promise<string[]> {
    try {
      const response = await apiClient.get(
        `/conversations/${conversationId}/services/approved`
      );
      return (response as { approvedServices: string[] }).approvedServices || [];
    } catch (error) {
      console.error('Error getting approved services:', error);
      throw new Error('Failed to get approved services');
    }
  }

  /**
   * Clear pending action(s) for a conversation.
   * Called when the user cancels/denies a pending approval or sends a fresh message.
   *
   * @param conversationId - The conversation ID
   * @param key - optional canonical card key ("svc:..." / "auth:...") to clear ONE card,
   *              leaving the other parallel cards pending. Omit to clear them all.
   */
  async clearPendingAction(conversationId: string, key?: string): Promise<void> {
    try {
      const query = key ? `?key=${encodeURIComponent(key)}` : '';
      console.log(`🗑️ [PENDING_ACTION] Clearing pending action for conversation ${conversationId}${key ? ` (key=${key})` : ''}`);
      await apiClient.delete(`/conversations/${conversationId}/pending-action${query}`);
      console.log(`✅ [PENDING_ACTION] Pending action cleared`);
    } catch (error) {
      console.error('Error clearing pending action:', error);
      // Don't throw - this is a cleanup operation
    }
  }

  /**
   * Authorize a sensitive tool action the agent requested (chat authorization card).
   *
   * @param conversationId - The conversation ID
   * @param rule - canonical rule key "tool:action" (e.g. "application:acquire")
   * @param remember - true to persist for the whole conversation ("Toujours autoriser"),
   *                   false for a single-shot grant consumed by the resume turn
   * @param toolCallId - id of the call the agent is HOLDING on this card. Passing it lets
   *                     the backend release that call so the assistant finishes its current
   *                     turn; omitting it leaves the older resume-by-new-turn flow.
   * @returns whether a held call was actually released. False means nobody was holding any
   *          more (the hold timed out, or there never was one), so the caller must resume
   *          the agent with a message or the approval visibly does nothing.
   */
  async approveToolAuthorization(conversationId: string, rule: string, remember: boolean,
                                 toolCallId?: string): Promise<boolean> {
    const res = await apiClient.post<{ parkedCallReleased?: boolean }>(
      `/conversations/${conversationId}/tool-authorization/approve`, {
        rule,
        remember,
        toolCallId,
      });
    return res?.parkedCallReleased === true;
  }

  /**
   * Decline a requested tool action. Clears the pending action; the agent stops and the
   * user takes over (no resume).
   *
   * @param conversationId - The conversation ID
   * @param rule - canonical rule key "tool:action" (for logging/correlation)
   * @param toolCallId - id of the held call, so it stops immediately instead of waiting
   *                     out the gate's deadline
   */
  async denyToolAuthorization(conversationId: string, rule: string, toolCallId?: string): Promise<boolean> {
    const res = await apiClient.post<{ parkedCallReleased?: boolean }>(
      `/conversations/${conversationId}/tool-authorization/deny`, { rule, toolCallId });
    return res?.parkedCallReleased === true;
  }

  /**
   * Release a tool call the agent is holding on a connect-a-service card.
   *
   * <p>Used only for the credential card, which has no authorization rule to grant: once
   * the service is connected there is just a held call to let through.
   *
   * @returns whether a held call was actually released - see approveToolAuthorization.
   */
  async resolveApprovalGate(conversationId: string, gateKey: string, approved: boolean): Promise<boolean> {
    const res = await apiClient.post<{ parkedCallReleased?: boolean }>(
      `/conversations/${conversationId}/approval-gate/resolve`, {
        gateKey,
        approved,
      });
    return res?.parkedCallReleased === true;
  }

  /**
   * Submit the answers to a question card raised by the agent (ask_user tool).
   *
   * @param toolCallId - the ask_user call that raised the card (its identity)
   * @param gateKey - set only while the agent is HOLDING that call; releasing it makes the
   *                  answers the tool result of the turn still running
   * @param answers - one entry per question: the header it answers, the labels picked, and
   *                  free text when the user typed their own answer
   * @returns whether a held call was actually released - see approveToolAuthorization. False
   *          means the caller must send the answers as the user's next message.
   */
  async answerAskUser(conversationId: string, toolCallId: string, gateKey: string | undefined,
                      answers: Array<{ header: string; selected: string[]; freeText?: string }>): Promise<boolean> {
    const res = await apiClient.post<{ parkedCallReleased?: boolean }>(
      `/conversations/${conversationId}/ask-user/answer`, { toolCallId, gateKey, answers });
    return res?.parkedCallReleased === true;
  }

  /** The user chose not to answer a question card. Clears it; no resume. */
  async dismissAskUser(conversationId: string, toolCallId: string, gateKey?: string): Promise<boolean> {
    const res = await apiClient.post<{ parkedCallReleased?: boolean }>(
      `/conversations/${conversationId}/ask-user/dismiss`, { toolCallId, gateKey });
    return res?.parkedCallReleased === true;
  }

}

// Export singleton instance
export const conversationApi = new ConversationApiService();
