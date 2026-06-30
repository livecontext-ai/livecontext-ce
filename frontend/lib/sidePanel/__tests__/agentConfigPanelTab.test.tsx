// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// The composer agent-avatar click and the header right-panel toggle both call
// buildAgentConfigPanelTab(...). Pin its output so both surfaces open the SAME
// tab (id `agent-<id>` => SidePanel merges, no duplicate) on the configuration tab.
vi.mock('@/components/app/AgentPanelContent', () => ({
  AgentPanelContent: (props: { agentId: string; initialTab: string }) => (
    <div data-testid="agent-panel" data-agent-id={props.agentId} data-tab={props.initialTab} />
  ),
  AGENT_CONFIGURATION_TAB: 'configuration',
}));
vi.mock('@/components/agents', () => ({
  AvatarDisplay: ({ name }: { name?: string }) => <div data-testid="avatar">{name}</div>,
}));

import { buildAgentConfigPanelTab } from '../agentConfigPanelTab';

describe('buildAgentConfigPanelTab', () => {
  it('builds the agent-config tab keyed on the agent id and scoped to conversations', () => {
    const tab = buildAgentConfigPanelTab({ agentId: 'a1', agentName: 'Research', agentAvatarUrl: '/a.png' });
    expect(tab.id).toBe('agent-a1');
    expect(tab.label).toBe('Research');
    expect(tab.pinned).toBe(true);
    expect(tab.scope).toContain('/app/c/*');
  });

  it('renders AgentPanelContent on the configuration tab; falls back to "Agent" with no name', () => {
    const tab = buildAgentConfigPanelTab({ agentId: 'a2', agentName: null, agentAvatarUrl: null });
    expect(tab.label).toBe('Agent');
    const { getByTestId } = render(<>{tab.content}</>);
    const panel = getByTestId('agent-panel');
    expect(panel).toHaveAttribute('data-agent-id', 'a2');
    expect(panel).toHaveAttribute('data-tab', 'configuration');
  });
});
