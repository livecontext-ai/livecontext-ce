'use client';

import * as React from 'react';
import { Bot } from 'lucide-react';
import { AvatarDisplay } from '@/components/agents';
import { AgentPanelContent, AGENT_CONFIGURATION_TAB } from '@/components/app/AgentPanelContent';
import { type SidePanelTab } from '@/contexts/SidePanelContext';

/**
 * Build the right-side-panel tab that shows an agent's configuration.
 *
 * Single source of truth so the agent avatar in the message composer and the
 * header right-panel toggle open the SAME tab (same id => SidePanel merges
 * instead of stacking duplicates).
 */
export function buildAgentConfigPanelTab(
  { agentId, agentName, agentAvatarUrl }: {
    agentId: string;
    agentName?: string | null;
    agentAvatarUrl?: string | null;
  },
): SidePanelTab {
  return {
    id: `agent-${agentId}`,
    label: agentName || 'Agent',
    icon: agentAvatarUrl
      ? <AvatarDisplay avatarUrl={agentAvatarUrl} name={agentName || 'Agent'} size="sm" className="!w-4 !h-4" />
      : <Bot className="w-4 h-4" />,
    pinned: true,
    scope: ['/app/c/*'],
    content: <AgentPanelContent agentId={agentId} initialTab={AGENT_CONFIGURATION_TAB} />,
  };
}
