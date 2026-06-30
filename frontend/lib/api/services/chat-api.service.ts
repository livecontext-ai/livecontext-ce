/**
 * Chat API Service
 * Single Responsibility: All chat, streaming, and message operations
 */

import { apiClient } from '../api-client';
import { getActiveOrgIdForRequest } from '@/lib/stores/current-org-store';
import type { StreamEventData } from '@/lib/streaming/streamHelpers';

export interface ChatMessagePayload {
  message: string;
  model?: string;
  provider?: string;
  conversationId?: string;
  history?: Array<{ role: string; content: string }>;
  attachments?: Array<{ storageId: string; type: string; fileName: string; mimeType: string }>;
  agentId?: string;
  defaultSkillIds?: string[];
  chatConfig?: Record<string, unknown>;
  source?: string;
  taskId?: string;
  /** Per-conversation reasoning-effort override for CLI/bridge models. */
  reasoningEffort?: string;
  /** Resume after resolving ONE parallel card - keep the other pending cards (no start-of-turn wipe). */
  keepPendingActions?: boolean;
}

export type { StreamEventData } from '@/lib/streaming/streamHelpers';

export class ChatApiService {
  constructor() {}

  async stopChatStream(conversationId: string): Promise<any> {
    try {
      const tokenProvider = apiClient.getTokenProvider();
      if (!tokenProvider) {
        throw new Error('No token provider available');
      }
      const token = await tokenProvider();
      if (!token) {
        throw new Error('No access token available');
      }

      // Audit 2026-05-17 round-3 - raw fetch must carry X-Active-Organization-ID
      // so the gateway routes /chat/stop into the right workspace.
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
        'Cache-Control': 'no-cache',
        'X-Requested-With': 'XMLHttpRequest'
      };
      const activeOrgId = getActiveOrgIdForRequest();
      if (activeOrgId) headers['X-Active-Organization-ID'] = activeOrgId;

      const response = await fetch('/api/proxy/chat/stop', {
        method: 'POST',
        headers,
        body: JSON.stringify({ conversationId })
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      return await response.json();
    } catch (error) {
      console.error('Error stopping chat stream:', error);
      throw error;
    }
  }

  /**
   * Send a chat message via WebSocket-compatible flow.
   * POSTs with Accept: application/json to get {conversationId, streamId, model} back.
   * Events arrive via WebSocket channel: conversation:{conversationId}
   */
  async sendChatMessageWs(
    message: string,
    model: string,
    provider: string,
    conversationId?: string,
    history?: Array<{ role: string; content: string }>,
    attachments?: Array<{ storageId: string; type: string; fileName: string; mimeType: string }>,
    agentId?: string,
    defaultSkillIds?: string[],
    chatConfig?: Record<string, unknown>,
    source?: string,
    taskId?: string,
    reasoningEffort?: string,
    keepPendingActions?: boolean
  ): Promise<{ conversationId: string; streamId: string; model: string }> {
    const payload: ChatMessagePayload = {
      message,
      model,
      provider,
      history: history || [],
    };

    if (conversationId) {
      payload.conversationId = conversationId;
    }

    if (attachments && attachments.length > 0) {
      payload.attachments = attachments;
    }

    if (agentId) {
      payload.agentId = agentId;
    }

    if (defaultSkillIds !== undefined) {
      payload.defaultSkillIds = defaultSkillIds;
    }

    if (chatConfig && Object.keys(chatConfig).length > 0) {
      payload.chatConfig = chatConfig;
    }

    if (source) {
      payload.source = source;
    }

    if (taskId) {
      payload.taskId = taskId;
    }

    if (reasoningEffort) {
      payload.reasoningEffort = reasoningEffort;
    }

    if (keepPendingActions) {
      payload.keepPendingActions = true;
    }

    return apiClient.post<{ conversationId: string; streamId: string; model: string }>(
      '/v3/chat',
      payload,
      { headers: { 'Accept': 'application/json' }, retries: 0, timeout: 120000 }
    );
  }

  /**
   * Get stream reconnection state for a conversation.
   * Returns buffered content + tool events for WebSocket reconnection recovery.
   */
  async getStreamReconnectionState(conversationId: string): Promise<{
    streamId: string;
    conversationId: string;
    model: string;
    state: string;
    content: string;
    toolEvents: string[];
    hasActiveStream: boolean;
  }> {
    return apiClient.get(`/v3/streams/by-conversation/${conversationId}/state`);
  }

  async getStreamStatus(conversationId: string): Promise<any> {
    try {
      const result = await apiClient.get<any>(`/v3/streams/by-conversation/${conversationId}/status`);
      return result;
    } catch (error) {
      console.error('Error getting stream status:', error);
      throw error;
    }
  }

  async getActiveStreamingConversations(): Promise<string[]> {
    try {
      const result = await apiClient.get<string[]>('/v3/streams/active');
      return result || [];
    } catch (error) {
      console.error('Error getting active streaming conversations:', error);
      return [];
    }
  }

  async stopStream(streamId: string): Promise<void> {
    try {
      await apiClient.post<void>(`/v3/streams/${streamId}/stop`);
    } catch (error) {
      console.error('Error stopping stream:', error);
      throw error;
    }
  }

}
