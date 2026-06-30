// @vitest-environment jsdom
//
// Perf regression guard: the agent side-panel tab icon used to be set by fetching the
// FULL agent (/agents/{id} - system_prompt + tools_config) just to read one avatar
// field. It now reads the lightweight (id, avatarUrl) projection. Pin that the panel
// never issues a full-agent GET for the tab icon.
import '@testing-library/jest-dom/vitest';
import { render, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const getAgent = vi.fn();
const getAgentAvatars = vi.fn();
const updateTab = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getAgent: (...args: unknown[]) => getAgent(...args),
    getAgentAvatars: () => getAgentAvatars(),
  },
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/contexts/SidePanelContext', () => ({
  useSidePanelSafe: () => ({ tabs: [{ id: 'agent-a1' }], updateTab }),
}));
vi.mock('@/components/agents', () => ({ AvatarDisplay: () => <span data-testid="avatar" /> }));
vi.mock('@/components/agent-fleet/AgentFleetPanelContent', () => ({
  AgentFleetPanelContent: () => <div data-testid="fleet-panel" />,
}));
vi.mock('../AgentConversationPanelContent', () => ({ AgentConversationPanelContent: () => <div /> }));
vi.mock('../ConversationPanelContent', () => ({ ConversationPanelContent: () => <div /> }));

import { AgentPanelContent } from '../AgentPanelContent';

describe('AgentPanelContent - tab icon uses the lightweight avatar projection', () => {
  afterEach(() => vi.clearAllMocks());

  it('reads the avatar from getAgentAvatars and never the full getAgent', async () => {
    getAgentAvatars.mockResolvedValue([{ id: 'a1', avatarUrl: 'http://x/a.png' }]);

    render(<AgentPanelContent agentId="a1" />);

    await waitFor(() => expect(getAgentAvatars).toHaveBeenCalledTimes(1));
    // The tab icon must NOT trigger a full-agent GET.
    expect(getAgent).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(updateTab).toHaveBeenCalledWith('agent-a1', expect.objectContaining({ icon: expect.anything() })),
    );
  });

  it('does not update the tab when the agent has no avatar', async () => {
    getAgentAvatars.mockResolvedValue([{ id: 'a1' }]); // no avatarUrl

    render(<AgentPanelContent agentId="a1" />);

    await waitFor(() => expect(getAgentAvatars).toHaveBeenCalledTimes(1));
    expect(getAgent).not.toHaveBeenCalled();
    expect(updateTab).not.toHaveBeenCalled();
  });
});
