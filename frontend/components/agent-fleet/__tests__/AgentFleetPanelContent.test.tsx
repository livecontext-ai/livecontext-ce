// @vitest-environment jsdom
//
// Perf regression guard: opening an agent's config panel used to issue THREE
// full-agent GETs (/agents/{id}) - the canvas (config), AgentPanelContent (tab icon),
// AND this wrapper (tab icon again). This component is a pure pass-through now; the
// tab-icon avatar is owned by the parent AgentPanelContent. Pin that it never fetches
// the agent itself.
import '@testing-library/jest-dom/vitest';
import { render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const getAgent = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: { getAgent: (...args: unknown[]) => getAgent(...args) },
}));

// Stub the heavy canvas so only THIS wrapper is exercised.
vi.mock('../AgentFleetCanvas', () => ({
  AgentFleetCanvas: (props: { singleAgentId: string; readOnly?: boolean }) => (
    <div
      data-testid="canvas"
      data-agent={props.singleAgentId}
      data-readonly={String(!!props.readOnly)}
    />
  ),
}));

import { AgentFleetPanelContent } from '../AgentFleetPanelContent';

describe('AgentFleetPanelContent - thin pass-through (no redundant agent fetch)', () => {
  afterEach(() => vi.clearAllMocks());

  it('renders the canvas in single-agent mode and never fetches the full agent', () => {
    const { getByTestId } = render(<AgentFleetPanelContent agentId="a1" readOnly />);

    const canvas = getByTestId('canvas');
    expect(canvas).toHaveAttribute('data-agent', 'a1');
    expect(canvas).toHaveAttribute('data-readonly', 'true');

    // The duplicate avatar-tab-icon fetch (a full /agents/{id} GET) is gone.
    expect(getAgent).not.toHaveBeenCalled();
  });
});
