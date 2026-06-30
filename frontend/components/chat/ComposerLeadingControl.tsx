'use client';

import React from 'react';
import { AvatarDisplay } from '@/components/agents';

/**
 * The control shown in the message composer, left of the mic:
 *  - agent conversation -> the agent avatar (click opens the agent config panel),
 *  - otherwise -> the model selector.
 *
 * The model-selector node is built by the caller (it owns the model state) and
 * passed in via `modelSelector`, so this component only encodes the
 * avatar-vs-selector decision and the avatar's click affordance.
 */
export function ComposerLeadingControl({
  agentId,
  agentName,
  agentAvatarUrl,
  onOpenAgentPanel,
  modelSelector,
}: {
  agentId: string | null;
  agentName: string | null;
  agentAvatarUrl: string | null;
  onOpenAgentPanel: () => void;
  modelSelector: React.ReactNode;
}) {
  // Gate on the RESOLVED avatar so a brand-new ?agentId= chat keeps the model
  // selector until the linked agent (and its avatar) loads - mirrors the old
  // header gate.
  if (agentAvatarUrl && agentId) {
    return (
      <button
        type="button"
        onClick={onOpenAgentPanel}
        className="flex items-center rounded-full hover:opacity-80 transition-opacity duration-200 cursor-pointer"
        title={agentName || ''}
        data-testid="composer-agent-avatar"
      >
        <AvatarDisplay avatarUrl={agentAvatarUrl} name={agentName || undefined} size="sm" />
      </button>
    );
  }
  return <>{modelSelector}</>;
}
