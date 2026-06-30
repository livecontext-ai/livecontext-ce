import { apiClient } from '@/lib/api/api-client';
import type { AttachmentRef } from '@/lib/api/attachmentApi';

/** A 1:1 DM thread as seen by the current user (mirrors backend DmThreadDto). */
export interface DmThread {
  id: string;
  otherUserId: string;
  lastMessageAt: string | null;
  lastMessagePreview: string | null;
  unreadCount: number;
  createdAt: string;
}

/** One DM message (mirrors backend DmMessageDto). Empty attachments for text-only. */
export interface DmMessage {
  id: string;
  threadId: string;
  senderUserId: string;
  content: string;
  attachments?: AttachmentRef[];
  readAt: string | null;
  createdAt: string;
}

export interface DmMessagesPage {
  items: DmMessage[];
  totalCount: number;
  page: number;
  size: number;
}

/** WebSocket event shapes pushed on the `dm:{threadId}` channel. */
export type DmThreadEvent =
  | { type: 'message:new'; threadId: string; message: DmMessage }
  | { type: 'message:read'; threadId: string; readerUserId: string };

/** WebSocket event shape pushed on the `dm-inbox:{userId}` channel. */
export interface DmInboxEvent {
  type: 'dm:incoming';
  threadId: string;
  message: DmMessage;
}

/**
 * Direct-message API. Routes through apiClient → gateway → conversation-service
 * (/api/dm/**). All endpoints require authentication; threads are identity-level
 * (global), so no workspace header is needed.
 */
export const dmApi = {
  listThreads: () => apiClient.get<DmThread[]>('/dm/threads'),

  getThread: (threadId: string) => apiClient.get<DmThread>(`/dm/threads/${threadId}`),

  openThread: (otherUserId: string) =>
    apiClient.post<DmThread>('/dm/threads', { otherUserId }),

  getMessages: (threadId: string, page = 0, size = 30) =>
    apiClient.get<DmMessagesPage>(`/dm/threads/${threadId}/messages`, {
      params: { page, size },
    }),

  sendMessage: (threadId: string, content: string, attachments?: AttachmentRef[]) =>
    apiClient.post<DmMessage>(`/dm/threads/${threadId}/messages`, {
      content,
      ...(attachments && attachments.length > 0 ? { attachments } : {}),
    }),

  markRead: (threadId: string) =>
    apiClient.post<{ markedRead: number }>(`/dm/threads/${threadId}/read`, {}),

  /**
   * "Delete" the conversation from MY inbox only - a soft, one-sided hide. Messages
   * are kept, the other participant is unaffected, and any new activity (re-opening
   * it or a new message either way) resurfaces the thread.
   */
  deleteThread: (threadId: string) =>
    apiClient.delete<{ deleted: boolean }>(`/dm/threads/${threadId}`),

  /**
   * DM-scoped attachment URL. The generic chat attachment endpoint is tenant-scoped
   * (the recipient could never read the sender's file there); this one checks thread
   * participation + that the storageId is referenced by a message of the thread.
   */
  attachmentUrl: (threadId: string, storageId: string) =>
    `/api/proxy/dm/threads/${encodeURIComponent(threadId)}/attachments/${encodeURIComponent(storageId)}`,
};
