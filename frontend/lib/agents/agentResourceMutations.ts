/**
 * Agent resource mutations - the single code path for adding/removing a resource,
 * tool, or sub-agent on an agent's `toolsConfig`. Extracted from the fleet inspector's
 * inline delete so the inspector AND the fleet canvas mutate agents identically.
 *
 * Every mutation: GET the fresh agent → clone toolsConfig → mutate → PUT the FULL
 * AgentUpdateInput (the backend `normalizeToolsConfig` then materialises absent keys
 * to []). Sending the whole agent mirrors the original inspector behaviour and avoids
 * partial-update field loss.
 */

import { agentService } from '@/lib/api/orchestrator/agent.service';
import { skillService } from '@/lib/api/orchestrator/skill.service';
import type { Agent, AgentUpdateInput } from '@/lib/api/orchestrator/types';

/**
 * fleet chip `fleetResourceType` → the `toolsConfig` JSONB key it lives under.
 * (Skills are NOT here - they live in agent.agent_skills, managed via skillService.)
 */
export const TOOLSCONFIG_KEY_MAP: Record<string, string> = {
  tool: 'tools',
  workflow: 'workflows',
  interface: 'interfaces',
  table: 'tables',
  application: 'applications',
  agent: 'agents',
  sub_agent: 'agents',
  file: 'files',
  web_search: 'webSearch',
};

type ToolsConfig = Record<string, any>;

/** Build the full update payload for an agent with a replaced toolsConfig. */
function buildAgentUpdatePayload(agent: Agent, toolsConfig: ToolsConfig): AgentUpdateInput {
  return {
    name: agent.name,
    description: agent.description,
    systemPrompt: agent.systemPrompt,
    modelProvider: agent.modelProvider,
    modelName: agent.modelName,
    temperature: agent.temperature,
    maxTokens: agent.maxTokens,
    maxIterations: agent.maxIterations,
    executionTimeout: agent.executionTimeout,
    inactivityTimeout: agent.inactivityTimeout,
    toolsConfig,
    workflowId: agent.workflowId,
    dataSourceId: agent.dataSourceId,
    conversationId: agent.conversationId,
    config: agent.config,
    avatarUrl: agent.avatarUrl,
    isPublic: agent.isPublic,
    isActive: agent.isActive,
    creditBudget: agent.creditBudget ?? null,
    budgetResetMode: agent.budgetResetMode,
    maxPerResourcePerTurn: agent.maxPerResourcePerTurn,
    loopIdenticalStop: agent.loopIdenticalStop,
    loopConsecutiveStop: agent.loopConsecutiveStop,
    compactionModelProvider: agent.compactionModelProvider,
    compactionModelName: agent.compactionModelName,
  } as AgentUpdateInput;
}

const asArray = (v: unknown): any[] => (Array.isArray(v) ? v : []);

/**
 * True iff a React Flow connection links two DIFFERENT agent nodes - the only
 * connection the fleet canvas allows (agent → sub-agent). Pure so it can be unit-tested.
 */
export function isAgentToAgentConnection(source?: string | null, target?: string | null): boolean {
  return !!source && !!target && source.startsWith('agent-') && target.startsWith('agent-') && source !== target;
}

/**
 * Which hover action a fleet edge should offer in edit mode:
 *  - 'edit'   → the model edge (the agent always has a model; you edit, not delete it).
 *  - 'delete' → a sub-agent edge, or an edge to a LEAF resource (a `res-` target).
 *  - undefined → group-container edges (provider/folder/category/aggregator) and the
 *               synthetic "All tools" leaf (not a removable single tool).
 * Gated on `category` (NOT a `-model-` substring of the id) so a real tool whose slug
 * happens to contain "model" is never wrongly excluded.
 */
export function resolveFleetEdgeAction(
  category: string | undefined,
  targetId: string,
  editMode: boolean,
): 'delete' | 'edit' | undefined {
  if (!editMode) return undefined;
  if (category === 'model') return 'edit';
  if (category === 'sub-agents') return 'delete';
  if (targetId.startsWith('res-') && !targetId.endsWith('-tool-all-tools')) return 'delete';
  return undefined;
}

/**
 * True iff a fleet delete request can actually disconnect something. web_search is a
 * boolean toggle (no id needed); every other type needs a concrete resourceId -
 * container/aggregator nodes have none, and confirming a delete for them used to run
 * a filter on '' that removed nothing (silent no-op: confirm modal, API call, zero
 * change). Callers must refuse to open the confirm dialog when this is false.
 */
export function isDisconnectableFleetResource(
  type: string | undefined,
  resourceId: string | undefined,
): boolean {
  if (!type) return false;
  if (type === 'web_search') return true;
  return !!resourceId;
}

/**
 * Fetch the agent, apply `mutate` to a clone of its toolsConfig, PUT the full agent.
 * Returns the updated agent.
 */
export async function updateAgentToolsConfig(
  agentId: string,
  mutate: (tc: ToolsConfig) => void,
): Promise<Agent> {
  const agent = await agentService.getAgent(agentId);
  const tc: ToolsConfig = agent.toolsConfig ? { ...agent.toolsConfig } : {};
  mutate(tc);
  return agentService.updateAgent(agentId, buildAgentUpdatePayload(agent, tc));
}

/**
 * Remove ONE resource (tool / workflow / interface / table / application / sub-agent)
 * from the agent, or disable web search. `type` is the fleet `fleetResourceType`.
 * Absent list keys materialise to [] before filtering (the legacy-"all" fix), so the
 * write is never a silent no-op.
 */
export async function disconnectAgentResource(
  agentId: string,
  type: string,
  resourceId: string,
): Promise<Agent> {
  return updateAgentToolsConfig(agentId, (tc) => {
    if (type === 'web_search') {
      tc.webSearch = false;
      return;
    }
    const key = TOOLSCONFIG_KEY_MAP[type];
    if (!key) {
      // Unknown type - surface it rather than silently no-op (see inspector history).
      console.warn('[agentResourceMutations] Unknown resource type for disconnect:', type);
      return;
    }
    tc[key] = asArray(tc[key]).filter((id) => String(id) !== String(resourceId));
  });
}

/**
 * Remove one skill from an agent. Skills live in agent.agent_skills (NOT toolsConfig),
 * managed via skillService's replace-all endpoint, so we re-PUT the remaining set.
 */
export async function disconnectAgentSkill(agentId: string, skillId: string): Promise<void> {
  const current = await skillService.getAgentSkills(agentId);
  const remaining = current
    .filter((s) => String(s.skillId) !== String(skillId))
    .map((s) => ({ skillId: s.skillId }));
  await skillService.setAgentSkills(agentId, remaining);
}

/**
 * Disconnect any fleet leaf from an agent, routing skills to skillService and
 * everything else to the toolsConfig path. `type` is the fleet `fleetResourceType`.
 */
export async function disconnectFleetResource(agentId: string, type: string, id: string): Promise<void> {
  if (type === 'skill') {
    await disconnectAgentSkill(agentId, id);
    return;
  }
  await disconnectAgentResource(agentId, type, id);
}

/** Add `calleeId` to the agent's sub-agents (idempotent). */
export async function connectSubAgent(agentId: string, calleeId: string): Promise<Agent> {
  return updateAgentToolsConfig(agentId, (tc) => {
    const current = asArray(tc.agents).map((id) => String(id));
    if (!current.includes(String(calleeId))) tc.agents = [...current, String(calleeId)];
  });
}

/** Remove `calleeId` from the agent's sub-agents. */
export async function disconnectSubAgent(agentId: string, calleeId: string): Promise<Agent> {
  return disconnectAgentResource(agentId, 'sub_agent', calleeId);
}

/**
 * Replace the agent's selected tools FOR ONE INTEGRATION. Tools are `apiSlug:toolSlug`
 * strings; everything not belonging to `apiSlug` is preserved (other integrations +
 * legacy UUID entries). `toolIds` are the full `apiSlug:toolSlug` ids to keep enabled.
 */
export async function setAgentIntegrationTools(
  agentId: string,
  apiSlug: string,
  toolIds: string[],
): Promise<Agent> {
  const prefix = `${apiSlug}:`;
  return updateAgentToolsConfig(agentId, (tc) => {
    const others = asArray(tc.tools).filter((t) => !String(t).startsWith(prefix));
    tc.tools = [...others, ...toolIds];
  });
}
