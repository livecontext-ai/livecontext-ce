'use client';

import React, { useState, useEffect, useRef } from 'react';
import { MessageSquare, Settings } from 'lucide-react';
import { AgentFleetPanelContent } from '@/components/agent-fleet/AgentFleetPanelContent';
import { AgentConversationPanelContent } from './AgentConversationPanelContent';
import { ConversationPanelContent } from './ConversationPanelContent';
import { cn } from '@/lib/utils';
import { useTranslations } from 'next-intl';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { orchestratorApi } from '@/lib/api';
import { AvatarDisplay } from '@/components/agents';

export const AGENT_CONVERSATION_TAB = '__conversation__';
export const AGENT_CONFIGURATION_TAB = '__configuration__';

interface AgentPanelContentProps {
  agentId: string;
  /** Direct conversationId override (e.g. from chat trigger). Skips agentConfigId resolution. */
  conversationId?: string;
  initialTab?: typeof AGENT_CONVERSATION_TAB | typeof AGENT_CONFIGURATION_TAB;
  readOnly?: boolean;
}

/**
 * Unified Agent Panel with two sub-tabs at the bottom:
 * - Conversation: agent conversation viewer
 * - Configuration: agent ReactFlow canvas (AgentFleetCanvas)
 */
export function AgentPanelContent({ agentId, conversationId: directConversationId, initialTab = AGENT_CONFIGURATION_TAB, readOnly }: AgentPanelContentProps) {
  const t = useTranslations();
  const [activeTab, setActiveTab] = useState(initialTab);
  const sidePanel = useSidePanelSafe();

  // Sync activeTab when initialTab prop changes (e.g. clicking bot vs conversation button on same agent)
  useEffect(() => {
    setActiveTab(initialTab);
  }, [initialTab]);

  // Update side-panel tab icon to the agent's avatar once loaded. Uses the lightweight
  // (id, avatarUrl) projection - NOT a full-agent GET - since only the avatar is needed
  // for the tab icon (the full config is already fetched by the canvas). The guard ensures
  // avatarUrl is present, so the name (absent from this projection) is unused here - it is
  // only the image alt text on AvatarDisplay.
  const avatarUpdatedRef = useRef<string | null>(null);
  useEffect(() => {
    if (avatarUpdatedRef.current === agentId || !sidePanel) return;
    avatarUpdatedRef.current = agentId;

    orchestratorApi.getAgentAvatars().then((avatars) => {
      const avatarUrl = avatars.find((a) => a.id === agentId)?.avatarUrl;
      if (!avatarUrl) return;
      const tabId = sidePanel.tabs?.find(
        (t) => t.id === `agent-${agentId}`,
      )?.id;
      if (tabId) {
        sidePanel.updateTab(tabId, {
          icon: <AvatarDisplay avatarUrl={avatarUrl} name="" size="sm" className="!w-4 !h-4" />,
        });
      }
    }).catch(() => {});
  }, [agentId, sidePanel]);

  return (
    <div className="h-full flex flex-col min-w-0 overflow-hidden">
      {/* Content */}
      <div className="flex-1 min-h-0 flex flex-col overflow-hidden">
        {activeTab === AGENT_CONVERSATION_TAB ? (
          directConversationId
            ? <ConversationPanelContent conversationId={directConversationId} />
            : <AgentConversationPanelContent agentConfigId={agentId} />
        ) : (
          <AgentFleetPanelContent agentId={agentId} readOnly={readOnly} />
        )}
      </div>

      {/* Sub-tabs at bottom */}
      <div className="flex-shrink-0 border-t border-theme bg-theme-secondary">
        <div className="flex overflow-x-auto overflow-y-hidden">
          <button
            type="button"
            onClick={() => setActiveTab(AGENT_CONFIGURATION_TAB)}
            className={cn(
              "flex items-center gap-2 text-sm whitespace-nowrap transition-colors px-4 py-2.5",
              activeTab === AGENT_CONFIGURATION_TAB
                ? "bg-theme-primary text-theme-primary font-medium"
                : "text-theme-muted hover:bg-theme-tertiary"
            )}
          >
            <Settings className="w-3.5 h-3.5 shrink-0" />
            {t('fleetInspector.configuration')}
          </button>
          <button
            type="button"
            onClick={() => setActiveTab(AGENT_CONVERSATION_TAB)}
            className={cn(
              "flex items-center gap-2 text-sm whitespace-nowrap transition-colors px-4 py-2.5",
              activeTab === AGENT_CONVERSATION_TAB
                ? "bg-theme-primary text-theme-primary font-medium"
                : "text-theme-muted hover:bg-theme-tertiary"
            )}
          >
            <MessageSquare className="w-3.5 h-3.5 shrink-0" />
            {t('fleetInspector.conversation')}
          </button>
        </div>
      </div>
    </div>
  );
}
