import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('@/lib/api/orchestrator/agent.service', () => ({
  agentService: { getAgent: vi.fn(), updateAgent: vi.fn() },
}));
vi.mock('@/lib/api/orchestrator/skill.service', () => ({
  skillService: { getAgentSkills: vi.fn(), setAgentSkills: vi.fn() },
}));

import { agentService } from '@/lib/api/orchestrator/agent.service';
import { skillService } from '@/lib/api/orchestrator/skill.service';
import {
  disconnectAgentResource,
  connectSubAgent,
  disconnectSubAgent,
  disconnectAgentSkill,
  setAgentIntegrationTools,
  isAgentToAgentConnection,
  isDisconnectableFleetResource,
  resolveFleetEdgeAction,
} from '../agentResourceMutations';

const getAgent = agentService.getAgent as unknown as ReturnType<typeof vi.fn>;
const updateAgent = agentService.updateAgent as unknown as ReturnType<typeof vi.fn>;
const getAgentSkills = skillService.getAgentSkills as unknown as ReturnType<typeof vi.fn>;
const setAgentSkills = skillService.setAgentSkills as unknown as ReturnType<typeof vi.fn>;

function mockAgent(toolsConfig: Record<string, any>) {
  getAgent.mockResolvedValue({ id: 'a1', name: 'Orchestrator', toolsConfig });
  updateAgent.mockResolvedValue({ id: 'a1', name: 'Orchestrator', toolsConfig });
}

/** The toolsConfig that the last updateAgent call persisted. */
const sentToolsConfig = () => (updateAgent.mock.calls.at(-1)![1] as any).toolsConfig;

beforeEach(() => { vi.clearAllMocks(); });

describe('disconnectAgentResource', () => {
  it('removes exactly the targeted tool, keeping the others', async () => {
    mockAgent({ mode: 'custom', tools: ['gmail:send_email', 'gmail:list_messages'] });
    await disconnectAgentResource('a1', 'tool', 'gmail:send_email');
    expect(sentToolsConfig().tools).toEqual(['gmail:list_messages']);
  });

  it('disables web search rather than touching a list', async () => {
    mockAgent({ webSearch: true, tools: ['x:y'] });
    await disconnectAgentResource('a1', 'web_search', 'web-search');
    expect(sentToolsConfig().webSearch).toBe(false);
    expect(sentToolsConfig().tools).toEqual(['x:y']); // untouched
  });

  it('materializes an absent list to [] then removes (the legacy-"all" fix)', async () => {
    // No `workflows` key at all → must become an explicit [] so the write is not a no-op.
    mockAgent({ mode: 'custom' });
    await disconnectAgentResource('a1', 'workflow', 'wf-123');
    expect(sentToolsConfig().workflows).toEqual([]);
  });

  it('maps a table chip to the tables list', async () => {
    mockAgent({ tables: ['10', '20'] });
    await disconnectAgentResource('a1', 'table', '10');
    expect(sentToolsConfig().tables).toEqual(['20']);
  });

  it('removes a NUMERIC table id (the shape agent-created agents persist) via string comparison', async () => {
    // The MCP agent tool writes tables as JSON numbers; the fleet chip carries the
    // string form. The filter must match across the number/string boundary.
    mockAgent({ tables: [10, 20] });
    await disconnectAgentResource('a1', 'table', '10');
    expect(sentToolsConfig().tables).toEqual([20]);
  });
});

describe('isDisconnectableFleetResource', () => {
  // Regression: confirming a delete on a container/aggregator node (no resourceId)
  // used to run a filter on '' that removed nothing - confirm modal, API call, zero
  // change. The guard must refuse those before the dialog opens.
  it('refuses a delete with no resourceId (container/aggregator nodes)', () => {
    expect(isDisconnectableFleetResource('table', undefined)).toBe(false);
    expect(isDisconnectableFleetResource('table', '')).toBe(false);
    expect(isDisconnectableFleetResource(undefined, 'x')).toBe(false);
  });

  it('allows web_search without an id (boolean toggle, not a list entry)', () => {
    expect(isDisconnectableFleetResource('web_search', undefined)).toBe(true);
  });

  it('allows any typed resource with a concrete id', () => {
    expect(isDisconnectableFleetResource('table', '42')).toBe(true);
    expect(isDisconnectableFleetResource('workflow', 'wf-1')).toBe(true);
    expect(isDisconnectableFleetResource('tool', 'gmail:send_email')).toBe(true);
  });
});

describe('connectSubAgent / disconnectSubAgent', () => {
  it('adds a sub-agent without duplicating an existing one', async () => {
    mockAgent({ agents: ['a2'] });
    await connectSubAgent('a1', 'a3');
    expect(sentToolsConfig().agents).toEqual(['a2', 'a3']);
  });

  it('is idempotent when the sub-agent is already connected', async () => {
    mockAgent({ agents: ['a2'] });
    await connectSubAgent('a1', 'a2');
    expect(sentToolsConfig().agents).toEqual(['a2']);
  });

  it('removes a sub-agent', async () => {
    mockAgent({ agents: ['a2', 'a3'] });
    await disconnectSubAgent('a1', 'a2');
    expect(sentToolsConfig().agents).toEqual(['a3']);
  });
});

describe('setAgentIntegrationTools', () => {
  it('replaces ONLY the integration subset, preserving other integrations', async () => {
    mockAgent({ mode: 'custom', tools: ['gmail:send_email', 'slack:post', 'google-ads:list'] });
    await setAgentIntegrationTools('a1', 'gmail', ['gmail:list_messages']);
    const tools = sentToolsConfig().tools as string[];
    expect(tools).toContain('slack:post');
    expect(tools).toContain('google-ads:list');
    expect(tools).toContain('gmail:list_messages');
    expect(tools).not.toContain('gmail:send_email'); // dropped
  });

  it('does not mistake a hyphenated integration for another (prefix is "apiSlug:")', async () => {
    mockAgent({ tools: ['google-ads:list', 'google:search'] });
    await setAgentIntegrationTools('a1', 'google', []);
    // only `google:` tools cleared; `google-ads:` survives
    expect(sentToolsConfig().tools).toEqual(['google-ads:list']);
  });
});

describe('disconnectAgentSkill', () => {
  it('re-PUTs the remaining skill assignments via skillService', async () => {
    getAgentSkills.mockResolvedValue([{ skillId: 's1' }, { skillId: 's2' }]);
    setAgentSkills.mockResolvedValue(undefined);
    await disconnectAgentSkill('a1', 's1');
    expect(setAgentSkills).toHaveBeenCalledWith('a1', [{ skillId: 's2' }]);
    expect(updateAgent).not.toHaveBeenCalled(); // skills never touch toolsConfig
  });
});

describe('isAgentToAgentConnection', () => {
  it('accepts a connection between two different agents', () => {
    expect(isAgentToAgentConnection('agent-1', 'agent-2')).toBe(true);
  });
  it('rejects agent → resource, self, and null endpoints', () => {
    expect(isAgentToAgentConnection('agent-1', 'res-1-tool-x')).toBe(false);
    expect(isAgentToAgentConnection('agent-1', 'agent-1')).toBe(false);
    expect(isAgentToAgentConnection(null, 'agent-2')).toBe(false);
    expect(isAgentToAgentConnection('provider-1-gmail', 'agent-2')).toBe(false);
  });
});

describe('resolveFleetEdgeAction', () => {
  it('offers edit on the model edge, delete on sub-agent and leaf-resource edges', () => {
    expect(resolveFleetEdgeAction('model', 'res-uuid-model-model', true)).toBe('edit');
    expect(resolveFleetEdgeAction('sub-agents', 'agent-2', true)).toBe('delete');
    expect(resolveFleetEdgeAction('resources', 'res-uuid-table-1', true)).toBe('delete');
    expect(resolveFleetEdgeAction('skills', 'res-uuid-skill-1', true)).toBe('delete');
  });

  it('offers no action on group-container edges or the "All tools" leaf', () => {
    expect(resolveFleetEdgeAction('tools', 'provider-uuid-gmail', true)).toBeUndefined();
    expect(resolveFleetEdgeAction('resources', 'category-uuid-table', true)).toBeUndefined();
    expect(resolveFleetEdgeAction('resources', 'agg-agent-1', true)).toBeUndefined();
    expect(resolveFleetEdgeAction('tools', 'res-uuid-tool-all-tools', true)).toBeUndefined();
  });

  it('does NOT exclude a real tool whose id contains "-model-" (regression for the old substring trap)', () => {
    // The old `!target.includes('-model-')` guard would have wrongly denied this edge.
    expect(resolveFleetEdgeAction('tools', 'res-uuid-tool-text-model-embedding', true)).toBe('delete');
  });

  it('offers nothing when not in edit mode', () => {
    expect(resolveFleetEdgeAction('model', 'res-uuid-model-model', false)).toBeUndefined();
    expect(resolveFleetEdgeAction('sub-agents', 'agent-2', false)).toBeUndefined();
  });
});
