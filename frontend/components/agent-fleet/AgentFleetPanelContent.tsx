'use client';

import { AgentFleetCanvas } from './AgentFleetCanvas';

interface AgentFleetPanelContentProps {
  agentId: string;
  readOnly?: boolean;
}

/**
 * Thin wrapper - renders the unified AgentFleetCanvas in single-agent mode.
 *
 * The side-panel tab icon (the agent avatar) is set by the PARENT
 * {@link AgentPanelContent}, which is the only caller of this component and runs the
 * same effect for both sub-tabs. Duplicating the avatar fetch here issued a second
 * full-agent GET (system_prompt + tools_config) per panel open just to read one
 * avatar field - pure waste - so the effect lives in the parent only.
 */
export function AgentFleetPanelContent({ agentId, readOnly }: AgentFleetPanelContentProps) {
  return <AgentFleetCanvas singleAgentId={agentId} readOnly={readOnly} />;
}
