'use client';

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { dmApi, type DmMessage, type DmThreadEvent } from '@/lib/api/dm-api';
import { unifiedApiService } from '@/lib/api/unified-api-service';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useChannel } from '@/lib/websocket/use-channel';
import { useDmPeerStore } from '@/lib/stores/dm-peer-store';
import { MessageHistory } from '@/components/chat/MessageHistory';
import { MessageSkeleton } from '@/components/chat/MessageSkeleton';
import { MessageComposer } from '@/components/chat/MessageComposer';
import type { AttachmentRef } from '@/lib/api/attachmentApi';
import type { Message } from '@/lib/api/conversationApi';

interface DmThreadViewProps {
  threadId: string;
  /** The OTHER participant's user id. In a 1:1 thread a message is "mine" iff its
   *  sender is NOT this id - so we never need to resolve our own user id. */
  otherUserId: string;
}

function dedupSortByCreatedAt(base: DmMessage[], live: DmMessage[]): DmMessage[] {
  const map = new Map<string, DmMessage>();
  for (const m of base) map.set(m.id, m);
  for (const m of live) map.set(m.id, m);
  return Array.from(map.values()).sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

export default function DmThreadView({ threadId, otherUserId }: DmThreadViewProps) {
  const t = useTranslations('dm');
  const [live, setLive] = useState<DmMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const setPeer = useDmPeerStore((s) => s.setPeer);
  const clearPeer = useDmPeerStore((s) => s.clearPeer);
  const queryClient = useQueryClient();
  // My own avatar/name for the per-message avatar (right side of my bubbles).
  const { user, avatarUrl: myAvatarUrl } = useAuthGuard();

  const { data: history, isLoading: historyLoading } = useQuery({
    queryKey: ['dm', 'messages', threadId],
    queryFn: () => dmApi.getMessages(threadId, 0, 50),
    enabled: !!threadId,
  });

  const { data: other } = useQuery({
    queryKey: ['publicProfile', 'id', otherUserId],
    queryFn: () => unifiedApiService.getPublicProfileById(otherUserId),
    enabled: !!otherUserId,
    retry: false,
  });

  // Publish the other participant to the shared header so the AppHeader renders their avatar +
  // name in place of the model selector (the DM has no local header of its own). Cleared on
  // unmount so the header reverts the moment the user leaves the thread.
  useEffect(() => {
    if (other?.displayName) {
      setPeer({ userId: otherUserId, displayName: other.displayName });
    }
    return () => clearPeer();
  }, [other?.displayName, otherUserId, setPeer, clearPeer]);

  // Reset live buffer when switching threads.
  useEffect(() => {
    setLive([]);
  }, [threadId]);

  // Mark the thread read on open.
  useEffect(() => {
    if (threadId) {
      dmApi.markRead(threadId).catch(() => {});
    }
  }, [threadId]);

  // Real-time: append new messages (deduped); mark read when the other party writes.
  useChannel<DmThreadEvent>(threadId ? `dm:${threadId}` : null, (evt) => {
    if (evt.type === 'message:new') {
      setLive((prev) => (prev.some((m) => m.id === evt.message.id) ? prev : [...prev, evt.message]));
      if (evt.message.senderUserId === otherUserId) {
        dmApi.markRead(threadId).catch(() => {});
      }
    }
  });

  const dmMessages = useMemo(
    () => dedupSortByCreatedAt(history?.items ?? [], live),
    [history, live],
  );

  // Map DM messages onto the chat Message shape so we can render them with the real
  // MessageHistory component (identical bubbles/theme). "Mine" → role 'user' (right
  // bubble); the other participant → role 'assistant' (left), exactly like an agent chat.
  // Attachments ride along - MessageHistory renders them for both sides.
  const messages = useMemo<Message[]>(
    () =>
      dmMessages.map((m) => ({
        id: m.id,
        conversationId: threadId,
        role: m.senderUserId !== otherUserId ? 'user' : 'assistant',
        content: m.content,
        attachments: m.attachments && m.attachments.length > 0 ? m.attachments : undefined,
        model: '',
        timestamp: m.createdAt,
      }) as Message),
    [dmMessages, threadId, otherUserId],
  );

  // DM attachments resolve through the DM-scoped endpoint (participant + referenced-in-thread
  // checks) - the generic chat endpoint is tenant-scoped and would 404 for the recipient.
  const resolveAttachmentUrl = useCallback(
    (storageId: string) => dmApi.attachmentUrl(threadId, storageId),
    [threadId],
  );

  // Per-message avatars: the peer in front of their bubbles, me in front of mine.
  const messageAvatars = useMemo(
    () => ({
      user: { avatarUrl: myAvatarUrl || user?.picture || undefined, name: user?.name },
      assistant: { avatarUrl: other?.avatarUrl ?? undefined, name: other?.displayName ?? undefined },
    }),
    [myAvatarUrl, user?.picture, user?.name, other?.avatarUrl, other?.displayName],
  );

  const handleSend = async (content?: string, attachments?: AttachmentRef[]) => {
    const text = (content ?? input).trim();
    const hasAttachments = !!attachments && attachments.length > 0;
    if ((!text && !hasAttachments) || sending) return;
    setSending(true);
    setInput('');
    try {
      const saved = await dmApi.sendMessage(threadId, text, attachments);
      setLive((prev) => (prev.some((m) => m.id === saved.id) ? prev : [...prev, saved]));
      // Refresh the sidebar inbox: ordering + preview move, and a brand-new thread
      // (opened from a profile / marketplace menu) appears after its first message.
      queryClient.invalidateQueries({ queryKey: ['dm', 'threads'] });
    } catch {
      setInput(text); // restore on failure
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="flex h-full flex-col">
      {/* No local header - the AppHeader shows the other participant's avatar + name. */}
      {/* Messages - reuse the real chat history component for identical styling. */}
      <div ref={scrollRef} className="relative flex-1 min-h-0 space-y-4 overflow-y-auto py-4">
        {historyLoading ? (
          // Skeleton while the thread loads - avoids the empty-state flash.
          <div className="mx-auto w-full max-w-4xl px-4" data-testid="dm-thread-skeleton">
            <MessageSkeleton />
          </div>
        ) : messages.length === 0 ? (
          <p className="pt-10 text-center text-sm text-theme-muted">{t('threadEmpty')}</p>
        ) : (
          <MessageHistory
            messages={messages}
            scrollContainerRef={scrollRef}
            hideWorkflowToggle
            hideDataSourceToggle
            attachmentUrlResolver={resolveAttachmentUrl}
            messageAvatars={messageAvatars}
          />
        )}
      </div>

      {/* Composer - the same chat composer, in minimal (DM) mode: text + attachments + mic. */}
      <div className="mx-auto w-full max-w-4xl flex-shrink-0">
        <MessageComposer
          minimal
          inputValue={input}
          onInputChange={setInput}
          onSendMessage={handleSend}
          disabled={sending}
          showAttachmentMenu={false}
          onShowAttachmentMenu={() => {}}
        />
      </div>
    </div>
  );
}
