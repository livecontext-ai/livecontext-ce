'use client';

import { ChatPageV2 } from '@/components/chat/ChatPageV2';

/**
 * Chat page - renders chat interface
 * Route: /app/chat
 *
 * Uses ChatPageV2 with StreamingContext for unified streaming state.
 */
export default function AppChatPage() {
  return <ChatPageV2 />;
}
