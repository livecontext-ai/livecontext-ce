'use client';

import { use } from 'react';
import { ChatPageV2 } from '@/components/chat/ChatPageV2';

/**
 * Conversation page - displays a specific conversation
 * Route: /app/c/[conversationId]
 *
 * Uses ChatPageV2 with StreamingContext for unified streaming state.
 */
export default function ConversationPage({
  params
}: {
  params: Promise<{ conversationId: string }>
}) {
  const { conversationId } = use(params);

  return <ChatPageV2 conversationIdFromParams={conversationId} />;
}
