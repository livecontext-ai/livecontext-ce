// @vitest-environment jsdom
//
// Perf regression guard for the single-agent config panel loading gate.
//
// Opening an agent's config panel used to await the FULL fleet (agentService.getAgents -
// a payload that grows with workspace size) AND four full resource-list GETs before the
// panel showed anything. The fix moves all of that off the gate: isLoading clears once the
// SELECTED agent + its skills are ready, and the sub-agent graph / resource names / stats
// enrich the canvas reactively afterwards.
//
// These tests pin that gate: isLoading MUST become false while getAgents is still pending
// (pre-fix this hung until getAgents resolved), and the selected agent's node builds from
// getAgent alone.
import '@testing-library/jest-dom/vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const getAgent = vi.fn();
const getAgents = vi.fn();
const getAgentSkills = vi.fn();
const getFleetTriggers = vi.fn();
const getFleetStats = vi.fn();

vi.mock('@/lib/api/orchestrator', () => ({
  agentService: {
    getAgent: (id: string) => getAgent(id),
    getAgents: () => getAgents(),
    getFleetTriggers: () => getFleetTriggers(),
    getFleetStats: () => getFleetStats(),
  },
  skillService: {
    getAgentSkills: (id: string) => getAgentSkills(id),
  },
}));
vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getWorkflows: vi.fn().mockResolvedValue([]),
    getInterfaces: vi.fn().mockResolvedValue([]),
    getDataSources: vi.fn().mockResolvedValue([]),
  },
}));
vi.mock('@/app/workflows/builder/hooks/useMcpData', () => ({
  fetchApis: vi.fn().mockResolvedValue({ content: [] }),
}));
vi.mock('@/lib/api/api-client', () => ({ apiClient: { get: vi.fn().mockResolvedValue([]) } }));
vi.mock('@/lib/hooks/useOrgScopedReset', () => ({ useOrgScopedReset: vi.fn() }));

import { useSingleAgentFleet } from '../useSingleAgentFleet';

const AGENT = { id: 'a1', name: 'Agent One', toolsConfig: { mode: 'all' } };
const EMPTY_STATS = { toolStats: [], resourceStats: [], subAgentStats: [], modelStats: [] };

describe('useSingleAgentFleet - the full fleet is off the loading gate', () => {
  afterEach(() => vi.clearAllMocks());

  it('clears isLoading from the selected agent + skills alone, without waiting for getAgents', async () => {
    getAgent.mockResolvedValue(AGENT);
    getAgentSkills.mockResolvedValue([]);
    getFleetTriggers.mockResolvedValue([]);
    getFleetStats.mockResolvedValue(EMPTY_STATS);
    // getAgents (the full-fleet GET) stays pending for the whole assertion window.
    let resolveAgents!: (v: unknown) => void;
    getAgents.mockReturnValue(new Promise((res) => { resolveAgents = res; }));

    const { result } = renderHook(() => useSingleAgentFleet('a1'));
    expect(result.current.isLoading).toBe(true);

    // The gate clears even though the full fleet has NOT resolved (pre-fix this hung).
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(getAgent).toHaveBeenCalledTimes(1);
    expect(getAgentSkills).toHaveBeenCalledTimes(1);
    // getAgents is kicked off only AFTER the selected agent loads (off the blocking path).
    expect(getAgent.mock.invocationCallOrder[0])
      .toBeLessThan(getAgents.mock.invocationCallOrder[0]);

    // The selected agent's node is built from getAgent alone - no full fleet needed.
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === 'agent-a1')).toBe(true));

    resolveAgents([AGENT]); // drain the background promise so no dangling work
    await waitFor(() => expect(result.current.isLoading).toBe(false));
  });

  it('still clears isLoading and builds the agent node when the background getAgents rejects', async () => {
    getAgent.mockResolvedValue(AGENT);
    getAgentSkills.mockResolvedValue([]);
    getFleetTriggers.mockResolvedValue([]);
    getFleetStats.mockResolvedValue(EMPTY_STATS);
    getAgents.mockRejectedValue(new Error('fleet backend down'));

    const { result } = renderHook(() => useSingleAgentFleet('a1'));

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    // A failing background fleet fetch must not block the selected agent's node.
    await waitFor(() => expect(result.current.nodes.some((n) => n.id === 'agent-a1')).toBe(true));
  });

  it('does not fetch anything when skip is set', async () => {
    renderHook(() => useSingleAgentFleet('a1', { skip: true }));
    await Promise.resolve();
    expect(getAgent).not.toHaveBeenCalled();
    expect(getAgents).not.toHaveBeenCalled();
  });
});
