'use client';

import React, { useState, useEffect, useRef } from 'react';
import { conversationApi } from '@/lib/api/conversationApi';
import { ConversationPanelContent } from './ConversationPanelContent';
import LoadingSpinner from '@/components/LoadingSpinner';
import { MessageSquare } from 'lucide-react';

interface AgentConversationPanelContentProps {
  agentConfigId: string;
}

/**
 * Wrapper that resolves agentConfigId → conversationId,
 * then delegates to ConversationPanelContent.
 * Retries a few times if not found (race with agent creating the conversation).
 */
export function AgentConversationPanelContent({ agentConfigId }: AgentConversationPanelContentProps) {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const retriesRef = useRef(0);

  useEffect(() => {
    if (!agentConfigId) {
      setIsLoading(false);
      setNotFound(true);
      return;
    }

    let cancelled = false;
    retriesRef.current = 0;
    setIsLoading(true);
    setNotFound(false);
    setConversationId(null);

    const attempt = async () => {
      try {
        const result = await conversationApi.findAgentConversation(agentConfigId);
        if (cancelled) return;
        if (result?.id) {
          setConversationId(result.id);
          setIsLoading(false);
          return;
        }
      } catch {
        // treat as not found
      }

      // Retry up to 3 times with 1.5s delay (agent may still be creating the conversation)
      if (!cancelled && retriesRef.current < 3) {
        retriesRef.current++;
        setTimeout(() => { if (!cancelled) attempt(); }, 1500);
      } else if (!cancelled) {
        setNotFound(true);
        setIsLoading(false);
      }
    };

    attempt();

    return () => { cancelled = true; };
  }, [agentConfigId]);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-full">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (notFound || !conversationId) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-theme-secondary gap-2">
        <MessageSquare className="w-8 h-8 opacity-50" />
        <span className="text-sm">No conversation yet</span>
      </div>
    );
  }

  return <ConversationPanelContent conversationId={conversationId} />;
}
