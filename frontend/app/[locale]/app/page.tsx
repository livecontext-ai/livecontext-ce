'use client';

import { ChatPageV2 } from '@/components/chat/ChatPageV2';

/**
 * App root page - new chat
 * Route: /app
 *
 * Uses ChatPageV2 with StreamingContext for unified streaming state.
 */
export default function AppPage() {
  return <ChatPageV2 />;
}
