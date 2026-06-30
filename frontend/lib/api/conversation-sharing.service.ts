/**
 * Conversation Sharing Service
 *
 * Handles enabling/disabling/updating conversation sharing.
 */

import { apiClient } from './api-client';

export interface ShareSettings {
  shareMode: 'off' | 'read' | 'readwrite';
  memoryEnabled?: boolean;
}

export interface SharedConversation {
  id: string;
  title?: string;
  shareToken?: string;
  shareMode: string;
  memoryEnabled: boolean;
  createdAt: string;
  updatedAt: string;
  messageCount?: number;
}

export class ConversationSharingService {
  async enableSharing(conversationId: string, settings: ShareSettings): Promise<SharedConversation> {
    return apiClient.post<SharedConversation>(`/conversations/${conversationId}/share`, settings);
  }

  async updateShareSettings(conversationId: string, settings: Partial<ShareSettings>): Promise<SharedConversation> {
    return apiClient.patch<SharedConversation>(`/conversations/${conversationId}/share`, settings);
  }

  async disableSharing(conversationId: string): Promise<void> {
    await apiClient.delete(`/conversations/${conversationId}/share`);
  }
}

export const conversationSharingService = new ConversationSharingService();
