/**
 * Chat Endpoint Settings Service
 *
 * Handles CRUD operations for standalone chat endpoints.
 */

import { apiClient } from '../api-client';

export interface StandaloneChatEndpoint {
  id: string;
  name: string;
  description?: string;
  token: string;
  chatUrl: string;
  workflowId: string;
  workflowName?: string;
  welcomeMessage?: string;
  model?: string;
  provider?: string;
  memoryEnabled: boolean;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateChatEndpointRequest {
  name: string;
  description?: string;
  workflowId?: string;
  workflowName?: string;
  welcomeMessage?: string;
  model?: string;
  provider?: string;
  memoryEnabled?: boolean;
  sourceNodeId?: string;
}

export interface UpdateChatEndpointRequest {
  name: string;
  description?: string;
  workflowId?: string;
  workflowName?: string;
  welcomeMessage?: string;
  model?: string;
  provider?: string;
  memoryEnabled?: boolean;
}

export interface ChatEndpointAccessLog {
  id: number;
  sessionId?: string;
  conversationId?: string;
  action: string;
  ipAddress?: string;
  accessedAt: string;
}

export interface ChatEndpointAccessLogsPage {
  content: ChatEndpointAccessLog[];
  totalPages: number;
  totalElements: number;
  number: number;
  size: number;
}

export interface ChatEndpointConfig {
  maxPerUser: number;
  currentCount: number;
}

export class ChatEndpointSettingsService {
  async getAll(): Promise<StandaloneChatEndpoint[]> {
    return apiClient.get<StandaloneChatEndpoint[]>('/chat-endpoints');
  }

  async create(data: CreateChatEndpointRequest): Promise<StandaloneChatEndpoint> {
    return apiClient.post<StandaloneChatEndpoint>('/chat-endpoints', data);
  }

  async getById(id: string): Promise<StandaloneChatEndpoint> {
    return apiClient.get<StandaloneChatEndpoint>(`/chat-endpoints/${id}`);
  }

  async update(id: string, data: UpdateChatEndpointRequest): Promise<StandaloneChatEndpoint> {
    return apiClient.put<StandaloneChatEndpoint>(`/chat-endpoints/${id}`, data);
  }

  async delete(id: string): Promise<void> {
    await apiClient.delete(`/chat-endpoints/${id}`);
  }

  async regenerateToken(id: string): Promise<StandaloneChatEndpoint> {
    return apiClient.post<StandaloneChatEndpoint>(`/chat-endpoints/${id}/regenerate-token`, {});
  }

  async getAccessLogs(id: string, page = 0, size = 20): Promise<ChatEndpointAccessLogsPage> {
    return apiClient.get<ChatEndpointAccessLogsPage>(`/chat-endpoints/${id}/logs`, {
      params: { page: String(page), size: String(size) },
    });
  }

  async getConfig(): Promise<ChatEndpointConfig> {
    return apiClient.get<ChatEndpointConfig>('/chat-endpoints/config');
  }

  async updateWorkflowReference(endpointId: string, workflowId: string | null, workflowName: string | null): Promise<StandaloneChatEndpoint> {
    return apiClient.patch<StandaloneChatEndpoint>(`/chat-endpoints/${endpointId}/workflow`, {
      workflowId: workflowId || null,
      workflowName: workflowName || null,
    });
  }
}

export const chatEndpointSettingsService = new ChatEndpointSettingsService();
