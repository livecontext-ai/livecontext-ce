'use client';

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { useNodesState, useEdgesState } from 'reactflow';
import type { Node, Edge } from 'reactflow';
import { agentService, skillService } from '@/lib/api/orchestrator';
import { orchestratorApi } from '@/lib/api';
import { fetchApis } from '@/app/workflows/builder/hooks/useMcpData';
import type { Agent, AgentSkill } from '@/lib/api/orchestrator/types';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { applyFleetLayout, consolidateFleetResources } from './useAgentFleetState';
import { aggregateContainerStatusCounts, colorEdgesByStatus } from './fleetStatusAggregation';
import { resolveFleetChipStatusCounts } from './fleetChipStatusCounts';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { apiClient } from '@/lib/api/api-client';
import { getAllowedIds, getToolsMode, isWebSearchEnabled, getGrant, getAccessMode, GRANT_FAMILIES } from '@/lib/agents/toolsConfigAccess';
import { resolveFleetResourceLookups, missingResourceKey } from './resolveFleetResourceLookups';
import { groupByAgentId, buildTriggerMap } from './fleetBatchMappers';
import type { FleetStats, FleetTrigger } from '@/lib/api/orchestrator/agent.service';

/**
 * Legacy UUID detector - see useAgentFleetState.ts for the full Delta A rationale.
 * Duplicated here because this single-agent hook builds resources independently.
 */
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// ─── Slugify helper (mirrors backend SlugUtils.generateSlug) ───
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '');
}

// ─── Category group labels ───
const CATEGORY_LABELS: Record<string, string> = {
  workflow: 'Workflows',
  interface: 'Interfaces',
  table: 'Tables',
  file: 'Files',
};

const CATEGORY_KINDS: Record<string, string> = {
  workflow: 'entry',
  interface: 'interface',
  table: 'data_input',
  file: 'download_file',
};

/**
 * Per-family access summary for the agent node - the two independent toolsConfig
 * axes (grant none|all|custom + read/write mode) read via the canonical readers.
 * Exposed on the agent node data so the fleet inspector (and any other consumer)
 * can DISPLAY the grant/RW that is enforced but was previously invisible.
 */
export function buildFleetFamilyAccess(agent: Agent): Record<string, { grant: 'none' | 'all' | 'custom'; mode: 'read' | 'write' }> {
  const out: Record<string, { grant: 'none' | 'all' | 'custom'; mode: 'read' | 'write' }> = {};
  for (const family of GRANT_FAMILIES) {
    out[family] = { grant: getGrant(agent.toolsConfig, family), mode: getAccessMode(agent.toolsConfig, family) };
  }
  return out;
}

// ─── Resource descriptor ───
interface ResourceDescriptor {
  id: string;
  type: 'tool' | 'skill' | 'workflow' | 'interface' | 'table' | 'file' | 'model' | 'web_search';
  label: string;
  flowNodeData: {
    kind: string;
    toolData?: Record<string, any>;
    apiData?: Record<string, any>;
  };
}

// ─── Tool group (provider with 2+ tools) ───
interface ToolGroup {
  apiSlug: string;
  apiName: string;
  iconSlug?: string;
  tools: ResourceDescriptor[];
}

/**
 * toolsConfig resource access - the 5 internal lists (workflows/tables/
 * interfaces/agents/applications) follow the "absent === []" rule:
 * absent / null / [] all mean NO access; only explicit `[ids]` grants. MCP
 * `mode` is the only field allowed to default to "all" when absent.
 * Canonical readers: lib/agents/toolsConfigAccess.ts.
 */
export function getResourcesForAgent(
  agent: Agent,
  agentSkills: AgentSkill[],
  workflowNames: Map<string, string>,
  interfaceNames: Map<string, string>,
  dataSourceNames: Map<string, string>,
  fileNames: Map<string, string>,
  apiIconMap: Map<string, string>,
  apiNameMap: Map<string, string>,
  toolUuidMap: Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>,
  missingResourceIds: Set<string> = new Set(),
): { resources: ResourceDescriptor[]; toolGroups: ToolGroup[] } {
  const resources: ResourceDescriptor[] = [];
  const toolGroups: ToolGroup[] = [];

  // Skills
  agentSkills.forEach(as => {
    resources.push({
      id: as.skillId,
      type: 'skill',
      label: as.skill?.name || as.skillId.slice(0, 8),
      flowNodeData: { kind: 'action' },
    });
  });

  const tc = agent.toolsConfig;

  // Internal resource lists (workflows / tables / interfaces / agents / applications)
  // are governed by the absent === [] rule (see lib/agents/toolsConfigAccess.ts).
  // We do NOT short-circuit on `!tc` here - null toolsConfig still means "no MCP
  // mode override = all" for the catalogue, but ZERO internal-resource access.
  // Only `mode` and `webSearch` get product-defined defaults.
  if (!tc) {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
    if (isWebSearchEnabled(tc)) {
      resources.push({ id: 'web-search', type: 'web_search', label: 'Web Search', flowNodeData: { kind: 'web_search' } });
    }
    return { resources, toolGroups };
  }

  // Tools (MCP tools) - `mode` IS allowed to default to 'all' (product behavior).
  const toolsMode = getToolsMode(tc);
  if (toolsMode === 'all') {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
  } else if (toolsMode === 'custom' && Array.isArray(tc.tools)) {
    const byProvider = new Map<string, ResourceDescriptor[]>();
    const providerMeta = new Map<string, { iconSlug?: string; apiName: string }>();

    tc.tools.forEach((toolIdRaw: string) => {
      // Delta A: resolve legacy UUID entries via toolUuidMap before the colon split
      // - see useAgentFleetState.ts for rationale.
      let toolId = toolIdRaw;
      if (UUID_REGEX.test(toolIdRaw)) {
        const resolved = toolUuidMap.get(toolIdRaw);
        if (resolved) {
          toolId = `${resolved.apiSlug}:${resolved.toolSlug}`;
        }
      }
      const colonIdx = toolId.indexOf(':');
      const apiSlug = colonIdx > 0 ? toolId.slice(0, colonIdx) : '';
      const toolName = colonIdx > 0 ? toolId.slice(colonIdx + 1) : toolId;
      const readableLabel = toolName.replace(/-/g, ' ').replace(/_/g, ' ');
      const iconSlug = apiIconMap.get(apiSlug) || apiSlug || undefined;
      const displayName = apiNameMap.get(apiSlug) || apiSlug;

      const descriptor: ResourceDescriptor = {
        id: toolId,
        type: 'tool',
        label: readableLabel,
        flowNodeData: {
          kind: 'tool',
          toolData: {
            toolSlug: apiSlug ? `${apiSlug}-${slugify(toolName)}` : slugify(toolName),
            toolName: readableLabel,
            apiSlug,
            apiName: displayName,
            iconSlug,
            method: 'POST',
          },
          apiData: {
            apiSlug,
            apiName: displayName,
            iconSlug,
          },
        },
      };

      const key = apiSlug || toolId;
      if (!byProvider.has(key)) byProvider.set(key, []);
      byProvider.get(key)!.push(descriptor);
      if (!providerMeta.has(key)) providerMeta.set(key, { iconSlug, apiName: displayName });
    });

    byProvider.forEach((tools, slug) => {
      if (tools.length === 1) {
        resources.push(tools[0]);
      } else {
        const meta = providerMeta.get(slug)!;
        toolGroups.push({
          apiSlug: slug,
          apiName: meta.apiName,
          iconSlug: meta.iconSlug,
          tools,
        });
      }
    });
  }

  // Workflows - absent === [] (security rule). No materialization of "all tenant
  // workflows" here. If the agent owner wants unrestricted access, they grant
  // explicitly (or run a backfill via REST). See V163 migration + toolsConfigAccess.
  // Grants whose entity is confirmed GONE (per-id lookup 404/410) are dropped so a
  // deleted resource's node disappears instead of lingering as a dangling ghost.
  getAllowedIds(tc, 'workflows').forEach(wfId => {
    if (missingResourceIds.has(missingResourceKey('workflow', wfId))) return;
    resources.push({ id: wfId, type: 'workflow', label: workflowNames.get(wfId) || wfId.slice(0, 8), flowNodeData: { kind: 'entry' } });
  });

  // Interfaces - same rule
  getAllowedIds(tc, 'interfaces').forEach(ifId => {
    if (missingResourceIds.has(missingResourceKey('interface', ifId))) return;
    resources.push({ id: ifId, type: 'interface', label: interfaceNames.get(ifId) || ifId.slice(0, 8), flowNodeData: { kind: 'interface' } });
  });

  // Tables - same rule. dataSourceNames is keyed by String(ds.id); tables[] in JSON
  // arrives as bigint numbers, but getAllowedIds coerces to string so the lookup hits.
  getAllowedIds(tc, 'tables').forEach(tblId => {
    if (missingResourceIds.has(missingResourceKey('table', tblId))) return;
    resources.push({ id: tblId, type: 'table', label: dataSourceNames.get(tblId) || `Table ${tblId}`, flowNodeData: { kind: 'data_input' } });
  });

  // Files - opt-in allow-list (a non-empty toolsConfig.files scopes the agent to those files).
  getAllowedIds(tc, 'files').forEach(fileId => {
    resources.push({ id: fileId, type: 'file', label: fileNames.get(fileId) || fileId.slice(0, 8), flowNodeData: { kind: 'download_file' } });
  });

  // Web search: boolean toggle (absent or true = enabled, false = disabled)
  if (isWebSearchEnabled(tc)) {
    resources.push({ id: 'web-search', type: 'web_search', label: 'Web Search', flowNodeData: { kind: 'web_search' } });
  }

  return { resources, toolGroups };
}

/**
 * Hook that fetches a single agent and its resources, converts them to ReactFlow
 * nodes and edges for a mini fleet canvas in the side panel.
 */
export function useSingleAgentFleet(agentId: string, options?: { skip?: boolean }) {
  const skip = options?.skip ?? false;
  const [agent, setAgent] = useState<Agent | null>(null);
  const [allAgents, setAllAgents] = useState<Agent[]>([]);
  // Agents actually rendered in the panel (main agent + its BFS-discovered sub-tree).
  // Subset of allAgents - used by the edit picker so the user can pick any visible agent.
  const [visibleAgents, setVisibleAgents] = useState<Agent[]>([]);
  const [skillsByAgent, setSkillsByAgent] = useState<Map<string, AgentSkill[]>>(new Map());
  const [resourcesById, setResourcesById] = useState<Map<string, any>>(new Map());
  const [isLoading, setIsLoading] = useState(true);
  const [nodes, setNodes, onNodesChange] = useNodesState<BuilderNodeData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const allNodesRawRef = useRef<Node<BuilderNodeData>[]>([]);
  const allEdgesRawRef = useRef<Edge[]>([]);
  const collapsibleGroupIdsRef = useRef<string[]>([]);

  // Internal refs for node building (no need to expose)
  const [workflowNames, setWorkflowNames] = useState<Map<string, string>>(new Map());
  const [interfaceNames, setInterfaceNames] = useState<Map<string, string>>(new Map());
  const [dataSourceNames, setDataSourceNames] = useState<Map<string, string>>(new Map());
  const [fileNames, setFileNames] = useState<Map<string, string>>(new Map());
  const [missingResourceIds, setMissingResourceIds] = useState<Set<string>>(new Set());
  const [apiIconMap, setApiIconMap] = useState<Map<string, string>>(new Map());
  const [apiNameMap, setApiNameMap] = useState<Map<string, string>>(new Map());
  const [toolUuidMap, setToolUuidMap] = useState<Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>>(new Map());
  const [toolStatsByAgent, setToolStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>>(new Map());
  // Per-RESOURCE stats (agentId → resourceId → counts) - resource-family tools
  // (table/interface/workflow/skill/application) broken down by the targeted id so
  // each leaf shows its own usage instead of the replicated family total.
  const [resourceStatsByAgent, setResourceStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number }>>>(new Map());
  const [subAgentStatsByAgent, setSubAgentStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>>(new Map());
  const [modelStatsByAgent, setModelStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>>>(new Map());
  const [triggersByAgent, setTriggersByAgent] = useState<Map<string, { hasWebhook: boolean; hasSchedule: boolean; webhookUrl?: string; cronExpression?: string; timezone?: string }>>(new Map());

  // Phase 6 (2026-05-18) - clear every fleet accumulator on workspace switch.
  // SidePanel Fleet tab uses keepMounted so the previous workspace's agent
  // tree survives across switches without this reset.
  useOrgScopedReset(() => {
    setAgent(null);
    setAllAgents([]);
    setVisibleAgents([]);
    setSkillsByAgent(new Map());
    setResourcesById(new Map());
    setWorkflowNames(new Map());
    setInterfaceNames(new Map());
    setDataSourceNames(new Map());
    setFileNames(new Map());
    setMissingResourceIds(new Set());
    setApiIconMap(new Map());
    setApiNameMap(new Map());
    setToolUuidMap(new Map());
    setToolStatsByAgent(new Map());
    setResourceStatsByAgent(new Map());
    setSubAgentStatsByAgent(new Map());
    setModelStatsByAgent(new Map());
    setTriggersByAgent(new Map());
    setNodes([]);
    setEdges([]);
    allNodesRawRef.current = [];
    allEdgesRawRef.current = [];
    collapsibleGroupIdsRef.current = [];
  });

  const fetchAll = useCallback(async () => {
    setIsLoading(true);
    try {
      // BLOCKING (fast path): the selected agent + its skills are all that's needed to render the
      // agent's OWN node. The rest of the fleet (for the sub-agent graph), the resource-name
      // lookups, and the usage stats are fetched BELOW and enrich the graph reactively - none of
      // them gates the initial render. Pre-fix this awaited a full-fleet getAgents (its payload
      // grows with workspace size) AND four full resource-list GETs before showing anything, so a
      // large workspace sat on a multi-second loading gate. Audit 2026-06-25.
      const [agentData, skillsData] = await Promise.all([
        agentService.getAgent(agentId),
        skillService.getAgentSkills(agentId).catch(() => [] as AgentSkill[]),
      ]);
      setAgent(agentData);
      // Start the fleet with just the selected agent so its node renders now; the full list is set
      // below and the node-building effect re-runs to expand the sub-agent graph.
      setAllAgents([agentData]);
      const skillsMap = new Map<string, AgentSkill[]>();
      if (skillsData.length > 0) {
        skillsMap.set(agentId, skillsData);
      }
      setSkillsByAgent(skillsMap);
      // Gate cleared: the agent's own node renders now; everything below enriches in the background.
      setIsLoading(false);

      // ONE batch call for the whole fleet instead of getWebhook + getSchedule per agent
      // (2N requests, nearly all 404 → slow right-panel load + agent-service 404 storm).
      // The endpoint returns only agents with an active webhook or enabled schedule.
      // Mirrors useAgentFleetState. Audit 2026-06-14.
      void (async () => {
        const triggerRows = await agentService.getFleetTriggers().catch(() => [] as FleetTrigger[]);
        setTriggersByAgent(buildTriggerMap(triggerRows));
      })();

      // Background enrichment (off the loading gate): the rest of the fleet for the sub-agent graph
      // + the resource lists, in ONE batch. The graph expands and labels resolve as this arrives.
      const [fetchedAgentsData, workflowsData, interfacesData, dataSourcesData, apisData] = await Promise.all([
        agentService.getAgents().catch(() => [] as Agent[]),
        orchestratorApi.getWorkflows({ size: 100 }).catch(() => []),
        orchestratorApi.getInterfaces().catch(() => []),
        orchestratorApi.getDataSources().catch(() => []),
        fetchApis({ pageParam: 0 }).catch(() => ({ content: [] })),
      ]);
      const allAgentsData = fetchedAgentsData.some(a => a.id === agentData.id)
        ? fetchedAgentsData
        : [agentData, ...fetchedAgentsData];
      setAllAgents(allAgentsData);

      // Build API icon + name lookups
      const iconMap = new Map<string, string>();
      const nameMap = new Map<string, string>();
      (apisData.content || []).forEach((api: any) => {
        if (api.slug && api.iconSlug) iconMap.set(api.slug, api.iconSlug);
        if (api.slug && api.apiName) nameMap.set(api.slug, api.apiName);
      });
      setApiIconMap(iconMap);
      setApiNameMap(nameMap);

      // Delta A: resolve any legacy UUID entries in this agent's tools_config.tools[]
      // - see useAgentFleetState for full rationale. We scan both the current agent
      // and the wider allAgents list to populate a single shared map for the view.
      const legacyUuids = new Set<string>();
      const collectUuids = (ag: any) => {
        const t = ag?.toolsConfig?.tools;
        if (!Array.isArray(t)) return;
        t.forEach((s: any) => { if (typeof s === 'string' && UUID_REGEX.test(s)) legacyUuids.add(s); });
      };
      collectUuids(agentData);
      allAgentsData.forEach(collectUuids);
      if (legacyUuids.size > 0) {
        try {
          const ids = Array.from(legacyUuids).join(',');
          const resolved = await apiClient.get<any[]>('/workflow-inspector/tools/by-ids', { params: { ids } });
          const m = new Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>();
          (resolved || []).forEach((row: any) => {
            if (row?.toolId && row?.apiSlug && row?.slug) {
              m.set(row.toolId, { apiSlug: row.apiSlug, toolSlug: row.slug, iconSlug: row.iconSlug });
            }
          });
          setToolUuidMap(m);
        } catch {
          setToolUuidMap(new Map());
        }
      } else {
        setToolUuidMap(new Map());
      }

      const resourceLookups = await resolveFleetResourceLookups(allAgentsData, {
        workflows: workflowsData,
        interfaces: interfacesData,
        dataSources: dataSourcesData,
      });
      setWorkflowNames(resourceLookups.workflowNames);
      setInterfaceNames(resourceLookups.interfaceNames);
      setDataSourceNames(resourceLookups.dataSourceNames);
      setFileNames(resourceLookups.fileNames);
      setMissingResourceIds(resourceLookups.missingResourceIds);
      setResourcesById(resourceLookups.resourcesById);

      // ONE batch call for the whole fleet instead of 4 stat requests per agent (4N
      // requests). The flat lists are grouped by agentId so the per-agent builders below
      // stay O(1). Mirrors useAgentFleetState. Audit 2026-06-14.
      const fleetStats = await agentService.getFleetStats().catch(() => ({
        toolStats: [], resourceStats: [], subAgentStats: [], modelStats: [],
      } as FleetStats));
      const toolStatsByAgentId = groupByAgentId(fleetStats.toolStats);
      const resourceStatsByAgentId = groupByAgentId(fleetStats.resourceStats);
      const modelStatsByAgentId = groupByAgentId(fleetStats.modelStats);
      const subAgentStatsByAgentId = groupByAgentId(fleetStats.subAgentStats);

      // Build per-agent tool stats lookup (agentId → toolKey → stats)
      const tsByAgent = new Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>();
      allAgentsData.forEach((a) => {
        const agentToolStats = new Map<string, { successCount: number; failureCount: number; totalCalls: number }>();
        (toolStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
          if (stat.toolName) {
            const key = stat.toolName.toLowerCase().replace(/[\s-]/g, '_');
            agentToolStats.set(key, {
              successCount: stat.successCount || 0,
              failureCount: stat.failureCount || 0,
              totalCalls: stat.totalCalls || 0,
            });
          }
        });
        if (agentToolStats.size > 0) tsByAgent.set(a.id, agentToolStats);
      });
      setToolStatsByAgent(tsByAgent);

      // Build per-agent, per-resource stats lookup (agentId → resourceId → counts)
      const rsByAgent = new Map<string, Map<string, { successCount: number; failureCount: number }>>();
      allAgentsData.forEach((a) => {
        const agentResourceStats = new Map<string, { successCount: number; failureCount: number }>();
        (resourceStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
          if (stat.resourceId) {
            agentResourceStats.set(String(stat.resourceId), {
              successCount: stat.successCount || 0,
              failureCount: stat.failureCount || 0,
            });
          }
        });
        if (agentResourceStats.size > 0) rsByAgent.set(a.id, agentResourceStats);
      });
      setResourceStatsByAgent(rsByAgent);

      // Build per-agent model stats lookup (agentId → model → stats)
      const msByAgent = new Map<string, Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>>();
      allAgentsData.forEach((a) => {
        const agentModelStats = new Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>();
        (modelStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
          if (stat.model) {
            agentModelStats.set(stat.model, {
              successCount: stat.successCount || 0,
              failureCount: stat.failureCount || 0,
              budgetExhaustedCount: stat.budgetExhaustedCount || 0,
            });
          }
        });
        if (agentModelStats.size > 0) msByAgent.set(a.id, agentModelStats);
      });
      setModelStatsByAgent(msByAgent);

      // Build per-agent sub-agent call stats
      const saMap = new Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>();
      allAgentsData.forEach((a) => {
        const callerStats = new Map<string, { successCount: number; failureCount: number; totalCalls: number }>();
        (subAgentStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
          if (stat.calleeAgentId) {
            callerStats.set(stat.calleeAgentId, {
              successCount: stat.successCount || 0,
              failureCount: stat.failureCount || 0,
              totalCalls: stat.totalCalls || 0,
            });
          }
        });
        if (callerStats.size > 0) saMap.set(a.id, callerStats);
      });
      setSubAgentStatsByAgent(saMap);
    } catch (err) {
      console.error('[SingleAgentFleet] Failed to fetch data:', err);
    } finally {
      setIsLoading(false);
    }
  }, [agentId]);

  // Build nodes and edges when data changes
  useEffect(() => {
    if (!agent) {
      setNodes([]);
      setEdges([]);
      return;
    }

    const allNodes: Node<BuilderNodeData>[] = [];
    const allEdges: Edge[] = [];
    const TOOL_TYPES = new Set(['tool', 'skill']);
    const RESOURCE_TYPES = new Set(['workflow', 'interface', 'table', 'file', 'web_search']);

    // ── Helper: resolve sub-agent IDs for any agent (static config + runtime calls) ──
    const resolveSubAgents = (a: Agent): string[] => {
      const validIds = new Set(allAgents.map(x => x.id));
      const subAgentSet = new Set<string>();
      // Static: from toolsConfig.agents (absent === [], handled by getAllowedIds)
      getAllowedIds(a.toolsConfig, 'agents').forEach(id => {
        if (id !== a.id && validIds.has(id)) subAgentSet.add(id);
      });
      // Runtime: from actual call stats (agents called but not necessarily in toolsConfig)
      const runtimeCallees = subAgentStatsByAgent.get(a.id);
      if (runtimeCallees) {
        runtimeCallees.forEach((_, calleeId) => {
          if (calleeId !== a.id && validIds.has(calleeId)) subAgentSet.add(calleeId);
        });
      }
      return Array.from(subAgentSet);
    };

    // ── BFS: discover all agents in the sub-agent tree ──
    const subAgentChildrenMap = new Map<string, string[]>();
    const mainSubAgentIds = resolveSubAgents(agent);
    subAgentChildrenMap.set(agent.id, mainSubAgentIds);
    const bfsQueue = [...mainSubAgentIds];
    const bfsVisited = new Set<string>([agent.id, ...mainSubAgentIds]);

    while (bfsQueue.length > 0) {
      const currentId = bfsQueue.shift()!;
      const currentAgent = allAgents.find(a => a.id === currentId);
      if (!currentAgent) continue;
      const childIds = resolveSubAgents(currentAgent);
      const relevantChildren = childIds.filter(id => bfsVisited.has(id) || allAgents.some(a => a.id === id));
      subAgentChildrenMap.set(currentId, relevantChildren);
      for (const childId of relevantChildren) {
        if (!bfsVisited.has(childId)) {
          bfsVisited.add(childId);
          bfsQueue.push(childId);
        }
      }
    }

    // ── Helper: build nodes + edges for a single agent (resources, tools, model) ──
    const buildAgentGraph = (agentData: Agent, agentSubAgentIds: string[], isMain: boolean) => {
      const aid = agentData.id;
      const agentSkills = skillsByAgent.get(aid) || [];
      const { resources, toolGroups } = getResourcesForAgent(
        agentData, agentSkills, workflowNames, interfaceNames, dataSourceNames, fileNames, apiIconMap, apiNameMap, toolUuidMap, missingResourceIds,
      );

      // Chips: model first, then resources
      const chips: ResourceDescriptor[] = [];
      if (agentData.modelName) {
        const providerIconSlug = PROVIDER_ICON_MAP[agentData.modelProvider || ''] || agentData.modelProvider || undefined;
        chips.push({
          id: 'model',
          type: 'model',
          label: agentData.modelName,
          flowNodeData: {
            kind: 'reasoning',
            apiData: providerIconSlug ? { iconSlug: providerIconSlug } : undefined,
          },
        });
      }
      chips.push(...resources);



      const hasDownward = chips.length > 0 || toolGroups.length > 0 || agentSubAgentIds.length > 0;

      // Fleet handles
      const hasModel = chips.some(c => c.type === 'model');
      const hasTools = chips.some(c => TOOL_TYPES.has(c.type)) || toolGroups.length > 0;
      const hasRes = chips.some(c => RESOURCE_TYPES.has(c.type)) || agentSubAgentIds.length > 0;
      const fleetHandles: string[] = [];
      if (hasModel) fleetHandles.push('model');
      if (hasTools) fleetHandles.push('tools');
      if (hasRes) fleetHandles.push('resources');

      // Resource counts for collapse badges
      const sid = (c: { id: string | number }) => String(c.id);
      const hasAllTools = chips.some(c => c.type === 'tool' && sid(c).startsWith('all-'));
      const explicitToolCount = chips.filter(c => c.type === 'tool' && !sid(c).startsWith('all-')).length
        + toolGroups.reduce((sum, g) => sum + g.tools.length, 0);
      const toolCount = hasAllTools ? -1 : explicitToolCount;
      const skillCount = chips.filter(c => c.type === 'skill').length;
      const wfCount = chips.filter(c => c.type === 'workflow').length;
      const ifCount = chips.filter(c => c.type === 'interface').length;
      const tblCount = chips.filter(c => c.type === 'table').length;
      const fileCount = chips.filter(c => c.type === 'file').length;
      const hasWeb = chips.some(c => c.type === 'web_search');
      const hasAny = toolCount !== 0 || skillCount !== 0 || wfCount !== 0 || ifCount !== 0 || tblCount !== 0 || fileCount !== 0 || hasWeb;

      // Agent node
      allNodes.push({
        id: `agent-${aid}`,
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: `agent-${aid}`,
          label: agentData.name,
          kind: 'reasoning',
          description: agentData.description,
          agentAvatarUrl: agentData.avatarUrl || undefined,
          fleetBottomHandles: hasDownward,
          fleetTopHandle: true,
          fleetHandles,
          fleetCollapsible: hasAny,
          fleetResourceCounts: hasAny ? {
            tools: toolCount,
            workflows: wfCount,
            interfaces: ifCount,
            tables: tblCount,
            files: fileCount,
            skills: skillCount,
            webSearch: hasWeb,
          } : undefined,
          // Per-family grant (none|all|custom) + read/write mode - enforced but
          // previously invisible; surfaced for the fleet inspector to display.
          fleetFamilyAccess: buildFleetFamilyAccess(agentData),
          statusCounts: (agentData.successCount || agentData.failureCount) ? {
            COMPLETED: agentData.successCount || 0,
            FAILED: agentData.failureCount || 0,
          } : undefined,
          fleetMetrics: {
            totalExecutions: agentData.totalExecutions || 0,
            successRate: (agentData.successCount || 0) + (agentData.failureCount || 0) > 0
              ? Math.round(((agentData.successCount || 0) / ((agentData.successCount || 0) + (agentData.failureCount || 0))) * 100)
              : null,
            totalTokens: agentData.totalTokensUsed || 0,
            totalToolCalls: agentData.totalToolCalls || 0,
            lastExecutionAt: agentData.lastExecutionAt || null,
            avgDurationMs: (agentData.totalExecutions && agentData.totalDurationMs)
              ? Math.round(agentData.totalDurationMs / agentData.totalExecutions)
              : null,
          },
          fleetTriggers: triggersByAgent.get(aid) || undefined,
        } as any,
      });

      // Resource chip nodes
      const agentToolStats = toolStatsByAgent.get(aid);
      const agentModelStats = modelStatsByAgent.get(aid);
      const agentResourceStats = resourceStatsByAgent.get(aid);
      // StatusCounts plumbing is centralized in resolveFleetChipStatusCounts:
      //  - tool / web_search → per-tool aggregate keyed by tool name
      //  - model             → per-model aggregate, with BUDGET_EXHAUSTED carved out
      //  - resource family   → PER-RESOURCE count keyed by the leaf's own id (so the
      //    family total is the honest sum of its parts, not family-total × N leaves)
      //  - sub-agent / other → no chip statusCount
      chips.forEach((res) => {
        const chipStatusCounts = resolveFleetChipStatusCounts(
          { type: res.type, id: res.id, label: res.label, toolName: res.flowNodeData.toolData?.toolName },
          { toolStats: agentToolStats, modelStats: agentModelStats, resourceStats: agentResourceStats, modelName: agentData.modelName || undefined },
        );

        allNodes.push({
          id: `res-${aid}-${res.type}-${res.id}`,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: `res-${aid}-${res.type}-${res.id}`,
            label: res.label,
            kind: res.flowNodeData.kind,
            toolData: res.flowNodeData.toolData,
            apiData: res.flowNodeData.apiData,
            fleetTopHandle: true,
            fleetResourceType: res.type,
            statusCounts: chipStatusCounts,
          } as any,
        });
      });

      // Provider group nodes + tool children
      toolGroups.forEach(group => {
        const providerNodeId = `provider-${aid}-${group.apiSlug}`;
        allNodes.push({
          id: providerNodeId,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: providerNodeId,
            label: group.apiName,
            kind: 'tool',
            apiData: { iconSlug: group.iconSlug, apiSlug: group.apiSlug, apiName: group.apiName },
            fleetTopHandle: true,
            fleetBottomHandles: true,
          } as any,
        });

        group.tools.forEach(tool => {
          const toolNodeId = `res-${aid}-${tool.type}-${tool.id}`;
          const groupToolLabel = tool.flowNodeData.toolData?.toolName || tool.label;
          const groupToolKey = groupToolLabel.toLowerCase().replace(/[\s-]/g, '_');
          const groupToolStat = agentToolStats?.get(groupToolKey);
          allNodes.push({
            id: toolNodeId,
            type: 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              id: toolNodeId,
              label: tool.label,
              kind: tool.flowNodeData.kind,
              toolData: tool.flowNodeData.toolData,
              apiData: tool.flowNodeData.apiData,
              fleetTopHandle: true,
              fleetResourceType: 'tool',
              statusCounts: groupToolStat ? {
                COMPLETED: groupToolStat.successCount,
                FAILED: groupToolStat.failureCount,
              } : undefined,
            } as any,
          });

          allEdges.push({
            id: `edge-provider-${aid}-${group.apiSlug}-${tool.id}`,
            source: providerNodeId,
            target: toolNodeId,
            sourceHandle: 'source-bottom',
            targetHandle: 'target-top',
            data: { category: 'tools' },
          });
        });
      });

      // Edges: agent → chips
      chips.forEach((res) => {
        let sourceHandle = 'source-resources';
        let category = 'resources';
        if (res.type === 'model') { sourceHandle = 'source-model'; category = 'model'; }
        else if (TOOL_TYPES.has(res.type)) { sourceHandle = 'source-tools'; category = 'tools'; }
        allEdges.push({
          id: `res-edge-${aid}-${res.type}-${res.id}`,
          source: `agent-${aid}`,
          target: `res-${aid}-${res.type}-${res.id}`,
          sourceHandle,
          targetHandle: 'target-top',
          data: { category },
        });
      });

      // Edges: agent → provider groups
      toolGroups.forEach(group => {
        allEdges.push({
          id: `edge-${aid}-provider-${group.apiSlug}`,
          source: `agent-${aid}`,
          target: `provider-${aid}-${group.apiSlug}`,
          sourceHandle: 'source-tools',
          targetHandle: 'target-top',
          data: { category: 'tools' },
        });
      });
    };

    // Publish the list of agents actually rendered in the panel (main + sub-tree).
    // Used by the edit picker to let the user pick any visible agent.
    const visible = Array.from(bfsVisited)
      .map(id => (id === agent.id ? agent : allAgents.find(a => a.id === id)))
      .filter((a): a is Agent => !!a);
    setVisibleAgents(visible);

    // ── Build graph for main agent ──
    buildAgentGraph(agent, mainSubAgentIds, true);

    // ── Build graph for each sub-agent ──
    bfsVisited.forEach(visitedId => {
      if (visitedId === agent.id) return;
      const visitedAgent = allAgents.find(a => a.id === visitedId);
      if (!visitedAgent) return;
      const childIds = subAgentChildrenMap.get(visitedId) || [];
      buildAgentGraph(visitedAgent, childIds, false);
    });

    // ── Sub-agent edges (parent → child) with per-caller stats ──
    const createdEdgeIds = new Set<string>();
    subAgentChildrenMap.forEach((childIds, parentId) => {
      childIds.forEach(childId => {
        const edgeId = `edge-${parentId}-${childId}`;
        if (createdEdgeIds.has(edgeId)) return;
        createdEdgeIds.add(edgeId);
        // Use per-caller stats: how many times THIS parent called THIS child
        const callerStats = subAgentStatsByAgent.get(parentId);
        const edgeStats = callerStats?.get(childId);
        const edgeStatusCounts = edgeStats ? {
          COMPLETED: edgeStats.successCount,
          FAILED: edgeStats.failureCount,
        } : undefined;
        allEdges.push({
          id: edgeId,
          source: `agent-${parentId}`,
          target: `agent-${childId}`,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: {
            category: 'sub-agents',
            statusCounts: edgeStatusCounts,
            totalCalls: edgeStats?.totalCalls,
          },
        });
      });
    });

    // ── Category grouping: group resource types with 2+ items (per agent) ──
    const GROUPABLE_TYPES = ['workflow', 'interface', 'table', 'file'] as const;
    const allAgentIds = [agent.id, ...Array.from(bfsVisited).filter(id => id !== agent.id)];

    for (const aid of allAgentIds) {
      // Find resource chip edges from this agent (not tool/model/skill)
      const agentResourceEdges = allEdges.filter(e => {
        if (e.source !== `agent-${aid}`) return false;
        const targetNode = allNodes.find(n => n.id === e.target);
        const rt = (targetNode?.data as any)?.fleetResourceType;
        return rt && rt !== 'model' && rt !== 'tool' && rt !== 'skill';
      });

      for (const resType of GROUPABLE_TYPES) {
        const typeEdges = agentResourceEdges.filter(e => {
          const targetNode = allNodes.find(n => n.id === e.target);
          const rt = (targetNode?.data as any)?.fleetResourceType;
          return rt === resType && !e.target.includes('-all-');
        });
        if (typeEdges.length < 2) continue;

        const catNodeId = `category-${aid}-${resType}`;
        // statusCounts left undefined here - aggregateContainerStatusCounts fills it
        // with the SUM of this group's leaf resources (e.g. Tables = A + B calls).
        allNodes.push({
          id: catNodeId,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: catNodeId,
            label: CATEGORY_LABELS[resType] || resType,
            kind: CATEGORY_KINDS[resType] || 'action',
            fleetTopHandle: true,
            fleetBottomHandles: true,
            fleetResourceType: resType,
          } as any,
        });

        allEdges.push({
          id: `edge-category-${aid}-${resType}`,
          source: `agent-${aid}`,
          target: catNodeId,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: { category: 'resources' },
        });

        for (const te of typeEdges) {
          const idx = allEdges.indexOf(te);
          if (idx !== -1) allEdges.splice(idx, 1);
          allEdges.push({
            id: `edge-cat-child-${aid}-${resType}-${te.target}`,
            source: catNodeId,
            target: te.target,
            sourceHandle: 'source-bottom',
            targetHandle: 'target-top',
            data: { category: 'resources' },
          });
        }
      }
    }

    // Collect collapsible group IDs
    const collapsibleGroupIds = allNodes
      .filter(n => (n.data as any).fleetCollapsible)
      .map(n => n.id);

    // Roll status counts up onto container nodes (Tables = Σ tables, provider = Σ its
    // tools, …) BEFORE consolidation - same pipeline as the full fleet view.
    aggregateContainerStatusCounts(allNodes, allEdges);

    // Consolidate agents with ≥6 resources into one aggregator node (keeps the
    // fleet compact). Rewrites nodes AND edges; must precede layout. Idempotent.
    // The aggregator carries the cumulative count of everything it folds.
    const consolidated = consolidateFleetResources(allNodes, allEdges);

    // Color/label edges from their target node's (now cumulative) counts - AFTER
    // consolidation so the agent→"Resources (N)" edge picks up the aggregator's sum.
    colorEdgesByStatus(consolidated.nodes, consolidated.edges);

    // Apply fleet layout (contour-based compaction)
    const layoutedNodes = applyFleetLayout(consolidated.nodes, consolidated.edges);
    setNodes(layoutedNodes);
    setEdges(consolidated.edges);

    allNodesRawRef.current = layoutedNodes;
    allEdgesRawRef.current = consolidated.edges;
    collapsibleGroupIdsRef.current = collapsibleGroupIds;
  }, [agent, allAgents, skillsByAgent, workflowNames, interfaceNames, dataSourceNames, fileNames, missingResourceIds, apiIconMap, apiNameMap, toolUuidMap, toolStatsByAgent, resourceStatsByAgent, subAgentStatsByAgent, modelStatsByAgent, triggersByAgent, setNodes, setEdges]);

  // Initial fetch (skip when not active)
  useEffect(() => {
    if (!skip) fetchAll();
  }, [fetchAll, skip]);

  return useMemo(() => ({
    nodes,
    edges,
    setNodes,
    setEdges,
    onNodesChange,
    onEdgesChange,
    isLoading,
    agent,
    allAgents,
    visibleAgents,
    skillsByAgent,
    resourcesById,
    refetch: fetchAll,
    allNodesRaw: allNodesRawRef.current,
    allEdgesRaw: allEdgesRawRef.current,
    collapsibleGroupIds: collapsibleGroupIdsRef.current,
  }), [nodes, edges, setNodes, setEdges, onNodesChange, onEdgesChange, isLoading, agent, allAgents, visibleAgents, skillsByAgent, resourcesById, fetchAll]);
}
