/**
 * Conversation API service
 * Uses apiClient for all HTTP requests (unified auth system)
 */

import { apiClient } from './api-client';
import type { Message } from './conversation.types';

// Re-export types from conversation.types for convenience
export type { Conversation, Message, MessageAttachment, ConversationResponse, CompactionMarker } from './conversation.types';

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
    size = 10
  ) {
    try {
      const tokenProvider = apiClient.getTokenProvider();
      const token = tokenProvider ? await tokenProvider() : null;
      if (!token) {
        return { content: [] as unknown[], totalElements: 0, totalPages: 0, number: page, size };
      }
      console.log(`🌐 [API CALL] GET /conversations?page=${page}&size=${size} - timestamp: ${new Date().toISOString()}`);
      const response = await apiClient.get(
        `/conversations`, { params: { page: page.toString(), size: size.toString() } }
      );
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
    searchType: 'title' | 'content' = 'content'
  ) {
    try {
      const endpoint = searchType === 'title' ? '/conversations/search/title' : '/conversations/search/content';
      const response = await apiClient.get(
        endpoint, { params: { searchTerm } }
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
   * Find an existing conversation for a specific workflow (does NOT create)
   * Returns null if no conversation exists
   */
  async findWorkflowConversation(
    workflowId: string
  ): Promise<{ id: string; title?: string; workflowId?: string } | null> {
    try {
      const response = await apiClient.get<{ id: string; title?: string; workflowId?: string }>(
        `/conversations/workflow/${workflowId}`
      );
      return response;
    } catch (error: any) {
      // 404 means no conversation exists - this is expected and normal
      const errorMessage = error?.message?.toLowerCase() || '';
      if (errorMessage.includes('not found') || errorMessage.includes('404') || error?.status === 404) {
        return null;
      }
      console.error('Error finding workflow conversation:', error);
      throw new Error('Failed to find workflow conversation');
    }
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
      return result;
    } catch (error) {
      console.error('Error deleting conversation:', error);

      // Si l'erreur contient "Invalid response status code 204",
      // c'est probablement que la suppression a reussi côte serveur
      if (error instanceof Error && error.message.includes('Invalid response status code 204')) {
        console.log('Server returned 204 (success) but frontend had parsing issue, considering deletion successful');
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
    }
  ) {
    try {
      const response = await apiClient.post(`/conversations/${conversationId}/messages`, messageData);
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
   */
  async approveToolAuthorization(conversationId: string, rule: string, remember: boolean): Promise<void> {
    await apiClient.post(`/conversations/${conversationId}/tool-authorization/approve`, {
      rule,
      remember,
    });
  }

  /**
   * Decline a requested tool action. Clears the pending action; the agent stops and the
   * user takes over (no resume).
   *
   * @param conversationId - The conversation ID
   * @param rule - canonical rule key "tool:action" (for logging/correlation)
   */
  async denyToolAuthorization(conversationId: string, rule: string): Promise<void> {
    await apiClient.post(`/conversations/${conversationId}/tool-authorization/deny`, { rule });
  }

}

// Export singleton instance
export const conversationApi = new ConversationApiService();
