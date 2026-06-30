'use client';

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import { useNodesState, useEdgesState } from 'reactflow';
import type { Node, Edge } from 'reactflow';
import { agentService, skillService } from '@/lib/api/orchestrator';
import { orchestratorApi } from '@/lib/api';
import { fetchApis } from '@/app/workflows/builder/hooks/useMcpData';
import { apiClient } from '@/lib/api/api-client';
import type { Agent, AgentSkill, SkillFolder } from '@/lib/api/orchestrator/types';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { applyFleetLayout, applyFleetLayoutCached, consolidateFleetResources } from './fleetLayout';
import { aggregateContainerStatusCounts, colorEdgesByStatus } from './fleetStatusAggregation';
import { resolveFleetChipStatusCounts } from './fleetChipStatusCounts';
import type { FleetStats, FleetTrigger } from '@/lib/api/orchestrator/agent.service';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { getAllowedIds, getToolsMode, isWebSearchEnabled } from '@/lib/agents/toolsConfigAccess';
import { resolveFleetResourceLookups, missingResourceKey } from './resolveFleetResourceLookups';
import { groupByAgentId, buildTriggerMap } from './fleetBatchMappers';

// applyFleetLayout lives in ./fleetLayout (pure, testable). Re-exported (the local
// binding imported above is also used internally) so AgentFleetCanvas can keep
// importing it from this module.
export { applyFleetLayout, consolidateFleetResources };

// ─── Slugify helper (mirrors backend SlugUtils.generateSlug) ───
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s_]+/g, '-')       // spaces & underscores → hyphens
    .replace(/[^a-z0-9-]/g, '')    // remove non-alphanumeric (except hyphens)
    .replace(/-{2,}/g, '-')        // collapse multiple hyphens
    .replace(/^-+|-+$/g, '');      // trim leading/trailing hyphens
}


// ─── Resource descriptor ───
interface ResourceDescriptor {
  id: string;
  type: 'tool' | 'skill' | 'workflow' | 'interface' | 'table' | 'file' | 'model' | 'web_search';
  label: string;
  /** FlowNode rendering data (kind, toolData, apiData) */
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

// ─── Skill group (folder with skills) ───
interface SkillGroup {
  folderId: string;
  folderName: string;
  skills: ResourceDescriptor[];
  children: SkillGroup[]; // sub-folders
}

// ─── Category group labels (used for collapsible category nodes) ───
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
 * Filter out nodes and edges that are descendants of collapsed groups.
 * The collapsed group node itself remains visible.
 */
export function filterCollapsedNodes(
  allNodes: Node<BuilderNodeData>[],
  allEdges: Edge[],
  collapsedIds: Set<string>,
): { visibleNodes: Node<BuilderNodeData>[]; visibleEdges: Edge[] } {
  if (collapsedIds.size === 0) {
    return { visibleNodes: allNodes, visibleEdges: allEdges };
  }

  // Build children lookup from edges
  const childrenOf = new Map<string, string[]>();
  for (const e of allEdges) {
    if (!childrenOf.has(e.source)) childrenOf.set(e.source, []);
    childrenOf.get(e.source)!.push(e.target);
  }

  // Collect all descendants of collapsed nodes recursively.
  // Agent nodes are NEVER hidden (always visible even if parent is collapsed).
  // Model nodes are kept visible when their parent agent is collapsed.
  const hiddenIds = new Set<string>();
  const collectDescendants = (nodeId: string, isAgentCollapse: boolean) => {
    for (const childId of (childrenOf.get(nodeId) || [])) {
      // Agent/sub-agent nodes are always visible - skip (don't hide, don't recurse)
      if (childId.startsWith('agent-')) continue;
      // When collapsing an agent, keep its model node visible
      if (isAgentCollapse && childId.includes('-model-')) continue;
      if (!hiddenIds.has(childId)) {
        hiddenIds.add(childId);
        collectDescendants(childId, false);
      }
    }
  };

  for (const collapsedId of collapsedIds) {
    const isAgent = collapsedId.startsWith('agent-');
    collectDescendants(collapsedId, isAgent);
  }

  const visibleNodes = allNodes.filter(n => !hiddenIds.has(n.id));
  const visibleEdges = allEdges.filter(e => !hiddenIds.has(e.source) && !hiddenIds.has(e.target));

  return { visibleNodes, visibleEdges };
}

/**
 * toolsConfig resource access - the 5 internal lists (workflows/tables/
 * interfaces/agents/applications) follow the "absent === []" rule:
 *
 * - toolsConfig is null         → MCP catalogue defaults to "all" (product
 *                                 behavior); the 5 internal lists are EMPTY.
 * - toolsConfig.X is undefined  → identical to []: NO access (security rule).
 * - toolsConfig.X is []         → NO access.
 * - toolsConfig.X is [ids]      → access to those specific resources.
 *
 * Canvas chips: explicit IDs render as resolved-name chips; absent / [] →
 * nothing shown for that category. See lib/agents/toolsConfigAccess.ts for
 * the canonical readers and AgentService.normalizeToolsConfig for the
 * write-side chokepoint that backfills absent keys to [].
 */
/**
 * UUID pattern for legacy {@code api_tools.id} entries that the agent-builder historically
 * wrote into {@code tools_config.tools[]} instead of the canonical {@code apiSlug:toolSlug}
 * format. See {@link resolveUuidToolIds} for the runtime fallback, and the agent-service
 * {@code AgentCrudModule.normalizeToolsList} for the backend-side prevention.
 */
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function getResourcesForAgent(
  agent: Agent,
  agentSkills: AgentSkill[],
  allFolders: SkillFolder[],
  workflowNames: Map<string, string>,
  interfaceNames: Map<string, string>,
  dataSourceNames: Map<string, string>,
  fileNames: Map<string, string>,
  apiIconMap: Map<string, string>,
  apiNameMap: Map<string, string>,
  toolUuidMap: Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>,
  missingResourceIds: Set<string> = new Set(),
): { resources: ResourceDescriptor[]; toolGroups: ToolGroup[]; skillGroups: SkillGroup[] } {
  const resources: ResourceDescriptor[] = [];
  const toolGroups: ToolGroup[] = [];
  const skillGroups: SkillGroup[] = [];

  // Skills - group by folder (like tools are grouped by provider)
  const folderMap = new Map(allFolders.map(f => [f.id, f]));
  const skillsByFolder = new Map<string | null, ResourceDescriptor[]>();

  agentSkills.forEach(as => {
    const descriptor: ResourceDescriptor = {
      id: as.skillId,
      type: 'skill',
      label: as.skill?.name || as.skillId.slice(0, 8),
      flowNodeData: { kind: 'action' },
    };
    const folderId = as.skill?.folderId || null;
    if (!skillsByFolder.has(folderId)) skillsByFolder.set(folderId, []);
    skillsByFolder.get(folderId)!.push(descriptor);
  });

  // Build skill groups recursively from folder hierarchy
  const buildSkillGroup = (folderId: string): SkillGroup | null => {
    const folder = folderMap.get(folderId);
    if (!folder) return null;

    const directSkills = skillsByFolder.get(folderId) || [];
    const childFolders = allFolders.filter(f => f.parentId === folderId);
    const children: SkillGroup[] = [];

    for (const child of childFolders) {
      const childGroup = buildSkillGroup(child.id);
      if (childGroup) children.push(childGroup);
    }

    // Only create group if it has skills or non-empty child groups
    if (directSkills.length === 0 && children.length === 0) return null;

    return {
      folderId: folder.id,
      folderName: folder.name,
      skills: directSkills,
      children,
    };
  };

  // Process root-level folders
  const rootFolders = allFolders.filter(f => !f.parentId);
  for (const folder of rootFolders) {
    const group = buildSkillGroup(folder.id);
    if (group) skillGroups.push(group);
  }

  // Root-level skills (no folder) - single skills go directly in resources
  const rootSkills = skillsByFolder.get(null) || [];
  rootSkills.forEach(s => resources.push(s));

  const tc = agent.toolsConfig;

  // toolsConfig null → MCP catalogue defaults to "all" (product behavior),
  // but the 5 INTERNAL resource lists do NOT - absent === [] (security rule,
  // see lib/agents/toolsConfigAccess.ts). webSearch defaults to enabled.
  if (!tc) {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
    if (isWebSearchEnabled(tc)) {
      resources.push({ id: 'web-search', type: 'web_search', label: 'Web Search', flowNodeData: { kind: 'web_search' } });
    }
    return { resources, toolGroups, skillGroups };
  }

  // Tools (MCP tools) - mode-based
  // Tool IDs in toolsConfig.tools[] use format "apiSlug:toolName" since B/normalizeToolsList.
  // Legacy agents created before that fix may still carry raw api_tools.id UUIDs; they are
  // resolved here via toolUuidMap (populated at mount-time via /workflow-inspector/tools/by-ids)
  // so the fleet canvas renders real tool labels + icons instead of "<uuid-with-spaces>".
  const toolsMode = getToolsMode(tc);
  if (toolsMode === 'all') {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
  } else if (toolsMode === 'custom' && Array.isArray(tc.tools)) {
    // Group tools by apiSlug
    const byProvider = new Map<string, ResourceDescriptor[]>();
    const providerMeta = new Map<string, { iconSlug?: string; apiName: string }>();

    tc.tools.forEach((toolIdRaw: string) => {
      // Defensive resolve: if the entry is a UUID, look it up; otherwise keep as-is.
      // Returns the same apiSlug:toolSlug contract downstream code already handles.
      let toolId = toolIdRaw;
      if (UUID_REGEX.test(toolIdRaw)) {
        const resolved = toolUuidMap.get(toolIdRaw);
        if (resolved) {
          toolId = `${resolved.apiSlug}:${resolved.toolSlug}`;
        }
        // Unresolved UUID → fall through to legacy render (shows dashed UUID).
        // Better than throwing; the admin will notice the ugly label and fix the agent.
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

      const key = apiSlug || toolId; // fallback to toolId if no apiSlug
      if (!byProvider.has(key)) byProvider.set(key, []);
      byProvider.get(key)!.push(descriptor);
      if (!providerMeta.has(key)) providerMeta.set(key, { iconSlug, apiName: displayName });
    });

    // Single tools → directly in resources; 2+ tools → toolGroup
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
  // mode === 'none' → no tool nodes

  // Internal resource lists - absent === [] (security rule). No materialization
  // of "all tenant workflows/interfaces/tables" - explicit grants only.
  // Grants whose entity is confirmed GONE (per-id lookup 404/410 - e.g. a table
  // deleted while still referenced by toolsConfig) are dropped: their node must
  // disappear from the canvas, not linger as a dangling "Table {id}" ghost.
  getAllowedIds(tc, 'workflows').forEach(wfId => {
    if (missingResourceIds.has(missingResourceKey('workflow', wfId))) return;
    resources.push({ id: wfId, type: 'workflow', label: workflowNames.get(wfId) || wfId.slice(0, 8), flowNodeData: { kind: 'entry' } });
  });

  getAllowedIds(tc, 'interfaces').forEach(ifId => {
    if (missingResourceIds.has(missingResourceKey('interface', ifId))) return;
    resources.push({ id: ifId, type: 'interface', label: interfaceNames.get(ifId) || ifId.slice(0, 8), flowNodeData: { kind: 'interface' } });
  });

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

  return { resources, toolGroups, skillGroups };
}

/**
 * Hook that fetches agents and their resources, converts them to ReactFlow
 * nodes and edges for the Agent Fleet canvas.
 *
 * - Each agent → FlowNode with kind='reasoning' + bottom handle + avatar
 * - Each resource → FlowNode with kind matching resource type (tool, entry, interface, data_input, action, reasoning)
 * - Agent→resource edges use sourceHandle='source-bottom' / targetHandle='target-top'
 * - Agent→agent edges use bottom→top handles
 */
export function useAgentFleetState(options?: { skip?: boolean }) {
  const skip = options?.skip ?? false;
  const [agents, setAgents] = useState<Agent[]>([]);
  const [skillsByAgent, setSkillsByAgent] = useState<Map<string, AgentSkill[]>>(new Map());
  const [workflowNames, setWorkflowNames] = useState<Map<string, string>>(new Map());
  const [interfaceNames, setInterfaceNames] = useState<Map<string, string>>(new Map());
  const [dataSourceNames, setDataSourceNames] = useState<Map<string, string>>(new Map());
  const [fileNames, setFileNames] = useState<Map<string, string>>(new Map());
  const [missingResourceIds, setMissingResourceIds] = useState<Set<string>>(new Set());
  const [apiIconMap, setApiIconMap] = useState<Map<string, string>>(new Map());
  const [apiNameMap, setApiNameMap] = useState<Map<string, string>>(new Map());
  // Legacy-UUID resolver: api_tools.id → { apiSlug, toolSlug, iconSlug }. Populated once per
  // fetchAll() via a batch call to /workflow-inspector/tools/by-ids for every UUID found
  // across agents. Empty when no agent has UUID-format tool entries (vast majority post-B).
  const [toolUuidMap, setToolUuidMap] = useState<Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>>(new Map());
  const [resourcesById, setResourcesById] = useState<Map<string, any>>(new Map());
  const [skillFolders, setSkillFolders] = useState<SkillFolder[]>([]);
  const [toolStatsByAgent, setToolStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>>(new Map());
  // Per-RESOURCE stats (agentId → resourceId → counts) - see useSingleAgentFleet.
  const [resourceStatsByAgent, setResourceStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number }>>>(new Map());
  const [subAgentStatsByAgent, setSubAgentStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>>(new Map());
  const [modelStatsByAgent, setModelStatsByAgent] = useState<Map<string, Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>>>(new Map());
  const [triggersByAgent, setTriggersByAgent] = useState<Map<string, { hasWebhook: boolean; hasSchedule: boolean; webhookUrl?: string; cronExpression?: string; timezone?: string }>>(new Map());
  const [isLoading, setIsLoading] = useState(true);
  const [nodes, setNodes, onNodesChange] = useNodesState<BuilderNodeData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const allNodesRawRef = useRef<Node<BuilderNodeData>[]>([]);
  const allEdgesRawRef = useRef<Edge[]>([]);
  const collapsibleGroupIdsRef = useRef<string[]>([]);
  // Layout-skip cache: the node-building effect re-runs whenever the async stat/trigger maps
  // land (they're in its deps so badges paint in), but those updates change node DATA only -
  // not the graph STRUCTURE (which nodes/edges exist). Dagre is expensive, so when the
  // structural signature (sorted node + edge ids) is unchanged we reuse the cached positions
  // instead of re-running applyFleetLayout. Cleared on workspace switch.
  const lastLayoutSigRef = useRef<string>('');
  const lastPositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());

  // Phase 6 (2026-05-18) - clear every fleet accumulator on workspace switch.
  // The Agent fleet page stays mounted under /app/agents/fleet so without
  // this reset the previous workspace's agents/skills/workflows/etc. remain
  // until the next manual refresh.
  useOrgScopedReset(() => {
    setAgents([]);
    setSkillsByAgent(new Map());
    setWorkflowNames(new Map());
    setInterfaceNames(new Map());
    setDataSourceNames(new Map());
    setFileNames(new Map());
    setMissingResourceIds(new Set());
    setApiIconMap(new Map());
    setApiNameMap(new Map());
    setToolUuidMap(new Map());
    setResourcesById(new Map());
    setSkillFolders([]);
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
    lastLayoutSigRef.current = '';
    lastPositionsRef.current = new Map();
  });

  const fetchAll = useCallback(async () => {
    setIsLoading(true);
    try {
      // ── FAST PATH (loading gate): the STRUCTURAL data that decides which nodes/edges
      // exist - the agents, their skills, and the skill-folder hierarchy. Resource CHIP
      // nodes already exist from this wave too because their counts come straight from
      // each agent's toolsConfig; the background lookups below only fill in labels/icons.
      // So the gate clears as soon as these three land. Pre-fix isLoading ALSO waited on
      // the 4 resource-list GETs + the per-id resolveFleetResourceLookups before showing
      // anything, so a large workspace sat on a multi-second loading gate. Mirrors
      // useSingleAgentFleet's fast-path/background split. Audit 2026-06-26.
      const [agentsData, allSkills, foldersData] = await Promise.all([
        agentService.getAgents(),
        skillService.getAllAgentSkills().catch(() => [] as AgentSkill[]),
        orchestratorApi.getAllSkillFolders().catch(() => [] as SkillFolder[]),
      ]);

      // Skills are node CONTENT (skill chips), grouped by agent for O(1) per-agent lookup.
      const skillsByAgentId = groupByAgentId(allSkills);
      const skillsMap = new Map<string, AgentSkill[]>();
      agentsData.forEach(a => {
        const agentSkills = skillsByAgentId.get(a.id) || [];
        if (agentSkills.length > 0) skillsMap.set(a.id, agentSkills);
      });

      // ── STRUCTURAL COMMIT (gate): agents + skills + folders together so the node-
      // building effect runs ONCE on the full graph structure (React batches these
      // synchronous setStates into one render), then the gate clears. Everything below
      // patches node DATA only (names/icons/stats); in the common case the layout cache
      // treats those as "structure unchanged" and reuses positions, so no extra Dagre pass
      // is incurred (see the enrichment commit below for the two minority cases that DO
      // change structure and correctly re-layout).
      setAgents(agentsData);
      setSkillsByAgent(skillsMap);
      setSkillFolders(foldersData);
      setIsLoading(false);

      // Trigger badges are part of the primary Fleet signal but not structural. ONE batch
      // call for the whole fleet instead of getWebhook + getSchedule per agent (2N
      // requests, nearly all 404); the endpoint returns only agents with an active webhook
      // or enabled schedule. Detached so it never gates the first paint.
      void (async () => {
        const triggerRows = await agentService.getFleetTriggers().catch(() => [] as FleetTrigger[]);
        setTriggersByAgent(buildTriggerMap(triggerRows));
      })();

      // ── BACKGROUND ENRICHMENT (off the gate): resource NAME lookups + MCP APIs (for
      // tool icons) in parallel. Data-only - the chip nodes already exist, these resolve
      // labels/icons and drop confirmed-gone grants (missingResourceIds).
      const [workflowsData, interfacesData, dataSourcesData, apisData] = await Promise.all([
        orchestratorApi.getWorkflows({ size: 100 }).catch(() => []),
        orchestratorApi.getInterfaces().catch(() => []),
        orchestratorApi.getDataSources().catch(() => []),
        fetchApis({ pageParam: 0 }).catch(() => ({ content: [] })),
      ]);

      // Build API icon + name lookups (apiSlug → iconSlug, apiSlug → apiName)
      const iconMap = new Map<string, string>();
      const nameMap = new Map<string, string>();
      (apisData.content || []).forEach((api: any) => {
        if (api.slug && api.iconSlug) iconMap.set(api.slug, api.iconSlug);
        if (api.slug && api.apiName) nameMap.set(api.slug, api.apiName);
      });

      // Collect legacy UUID tool entries from all agents and resolve them in one batch.
      // These land in tools_config.tools[] as raw api_tools.id UUIDs for agents created
      // before the backend normaliser (B) - without this map the fleet canvas renders
      // "<uuid-with-spaces>" as the tool label. Skipped entirely when no legacy data exists.
      const legacyUuids = new Set<string>();
      agentsData.forEach((a: any) => {
        const rawTools = a?.toolsConfig?.tools;
        if (!Array.isArray(rawTools)) return;
        rawTools.forEach((t: any) => {
          if (typeof t === 'string' && UUID_REGEX.test(t)) legacyUuids.add(t);
        });
      });
      let toolUuidMapLocal = new Map<string, { apiSlug: string; toolSlug: string; iconSlug?: string }>();
      if (legacyUuids.size > 0) {
        try {
          const ids = Array.from(legacyUuids).join(',');
          const resolved = await apiClient.get<any[]>(
            `/workflow-inspector/tools/by-ids`,
            { params: { ids } }
          );
          (resolved || []).forEach((row: any) => {
            if (row?.toolId && row?.apiSlug && row?.slug) {
              toolUuidMapLocal.set(row.toolId, {
                apiSlug: row.apiSlug,
                toolSlug: row.slug,
                iconSlug: row.iconSlug,
              });
            }
          });
        } catch {
          // Fail-open: legacy UUIDs keep rendering ugly, but no other data is lost.
          toolUuidMapLocal = new Map();
        }
      }

      const resourceLookups = await resolveFleetResourceLookups(agentsData, {
        workflows: workflowsData,
        interfaces: interfacesData,
        dataSources: dataSourcesData,
      });

      // ── ENRICHMENT COMMIT ────────────────────────────────────────────────────────
      // Set ALL enrichment maps together so the node-building effect re-runs ONCE more
      // (React auto-batches these synchronous setStates) to paint resolved names/icons
      // onto the already-rendered chips. In the COMMON case this changes node DATA only
      // (labels/icons), so the structural signature (node + edge ids) is unchanged and
      // applyFleetLayoutCached reuses the cached positions - no second Dagre pass. Two
      // minority cases DO change structure and correctly trigger a relayout on this wave:
      // (1) a confirmed-gone grant lands in missingResourceIds and getResourcesForAgent
      // drops its ghost chip; (2) a legacy-UUID tool id resolves to apiSlug:toolSlug and
      // may regroup under a provider node. Both are self-healing - the gate paints a
      // fallback label / ghost chip first and this wave reconciles to the final graph, the
      // same transient the single-agent hook already accepts. Agents, skills and folders
      // were committed on the gate above; stats and triggers stay detached overlays that
      // only patch badge data onto these nodes.
      setApiIconMap(iconMap);
      setApiNameMap(nameMap);
      setToolUuidMap(toolUuidMapLocal);
      setWorkflowNames(resourceLookups.workflowNames);
      setInterfaceNames(resourceLookups.interfaceNames);
      setDataSourceNames(resourceLookups.dataSourceNames);
      setFileNames(resourceLookups.fileNames);
      setMissingResourceIds(resourceLookups.missingResourceIds);
      setResourcesById(resourceLookups.resourcesById);

      // Usage stats are OVERLAY badges (success/failure counts), not required for the
      // canvas to render. getFleetStats runs 4 unbounded GROUP BY aggregations over the
      // whole execution history, so keeping it on the blocking path made isLoading wait
      // on the slowest call. Fetch it fire-and-forget - same detached-IIFE shape as the
      // trigger batch above (useSingleAgentFleet backgrounds its stats the same way, via
      // an explicit setIsLoading(false) then an inline await). The node-building effect
      // lists these maps in its deps, so it re-runs and paints the badges in when they land.
      void (async () => {
        const fleetStats = await agentService.getFleetStats().catch(() => ({
          toolStats: [], resourceStats: [], subAgentStats: [], modelStats: [],
        } as FleetStats));
        const toolStatsByAgentId = groupByAgentId(fleetStats.toolStats);
        const resourceStatsByAgentId = groupByAgentId(fleetStats.resourceStats);
        const subAgentStatsByAgentId = groupByAgentId(fleetStats.subAgentStats);
        const modelStatsByAgentId = groupByAgentId(fleetStats.modelStats);

        // Build per-agent tool stats lookup (normalized keys)
        const tsMap = new Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>();
        agentsData.forEach(a => {
          const agentStats = new Map<string, { successCount: number; failureCount: number; totalCalls: number }>();
          (toolStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
            if (stat.toolName) {
              const key = stat.toolName.toLowerCase().replace(/[\s-]/g, '_');
              agentStats.set(key, {
                successCount: stat.successCount || 0,
                failureCount: stat.failureCount || 0,
                totalCalls: stat.totalCalls || 0,
              });
            }
          });
          if (agentStats.size > 0) tsMap.set(a.id, agentStats);
        });
        setToolStatsByAgent(tsMap);

        // Build per-agent, per-resource stats lookup (agentId → resourceId → counts)
        const rsMap = new Map<string, Map<string, { successCount: number; failureCount: number }>>();
        agentsData.forEach(a => {
          const agentResourceStats = new Map<string, { successCount: number; failureCount: number }>();
          (resourceStatsByAgentId.get(a.id) || []).forEach((stat: any) => {
            if (stat.resourceId) {
              agentResourceStats.set(String(stat.resourceId), {
                successCount: stat.successCount || 0,
                failureCount: stat.failureCount || 0,
              });
            }
          });
          if (agentResourceStats.size > 0) rsMap.set(a.id, agentResourceStats);
        });
        setResourceStatsByAgent(rsMap);

        // Build per-caller sub-agent stats lookup (callerAgentId → calleeAgentId → stats)
        const saMap = new Map<string, Map<string, { successCount: number; failureCount: number; totalCalls: number }>>();
        agentsData.forEach(a => {
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

        // Build per-agent model stats lookup (agentId → model → stats)
        const msByAgent = new Map<string, Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>>();
        agentsData.forEach(a => {
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
      })();

    } catch (err) {
      console.error('[AgentFleet] Failed to fetch data:', err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  // Build nodes and edges when data changes
  useEffect(() => {
    if (agents.length === 0) {
      setNodes([]);
      setEdges([]);
      return;
    }

    // Sort: parents (with sub-agents) first, then leaves
    const sorted = [...agents].sort((a, b) => {
      const aChildren = (a.toolsConfig?.agents || []).length;
      const bChildren = (b.toolsConfig?.agents || []).length;
      return bChildren - aChildren;
    });

    const allNodes: Node<BuilderNodeData>[] = [];
    const allEdges: Edge[] = [];

    sorted.forEach((agent) => {
      const agentSkills = skillsByAgent.get(agent.id) || [];
      const { resources, toolGroups, skillGroups } = getResourcesForAgent(agent, agentSkills, skillFolders, workflowNames, interfaceNames, dataSourceNames, fileNames, apiIconMap, apiNameMap, toolUuidMap, missingResourceIds);
      // Resolve sub-agents for fleet edges: merge static config + runtime call data
      const agentIds = new Set(agents.map(a => a.id));
      const subAgentSet = new Set<string>();
      // Static: from toolsConfig.agents (absent === [], handled by getAllowedIds)
      getAllowedIds(agent.toolsConfig, 'agents').forEach(id => {
        if (id !== agent.id && agentIds.has(id)) subAgentSet.add(id);
      });
      // Runtime: from actual call stats (agents called but not necessarily in toolsConfig)
      const runtimeCallees = subAgentStatsByAgent.get(agent.id);
      if (runtimeCallees) {
        runtimeCallees.forEach((_, calleeId) => {
          if (calleeId !== agent.id && agentIds.has(calleeId)) subAgentSet.add(calleeId);
        });
      }
      const subAgents = Array.from(subAgentSet);

      // Build all chip resources: model chip first, then resources
      const allChips: ResourceDescriptor[] = [];

      // Model as a FlowNode - provider icon via apiData.iconSlug + model name
      if (agent.modelName) {
        const providerIconSlug = PROVIDER_ICON_MAP[agent.modelProvider || ''] || agent.modelProvider || undefined;
        allChips.push({
          id: 'model',
          type: 'model',
          label: agent.modelName,
          flowNodeData: {
            kind: 'reasoning',
            apiData: providerIconSlug ? { iconSlug: providerIconSlug } : undefined,
          },
        });
      }

      // Then actual resources (single tools + non-tool resources)
      allChips.push(...resources);

      const hasDownwardConnections = allChips.length > 0 || toolGroups.length > 0 || skillGroups.length > 0 || subAgents.length > 0;

      // Determine which typed handles the agent node needs: model (left) + tools (center) + resources (right)
      const TOOL_TYPES = new Set(['tool']);
      const RESOURCE_TYPES = new Set(['skill', 'workflow', 'interface', 'table', 'file', 'web_search']);
      const hasModel = allChips.some(c => c.type === 'model');
      const hasTools = allChips.some(c => TOOL_TYPES.has(c.type)) || toolGroups.length > 0;
      const hasResources = allChips.some(c => RESOURCE_TYPES.has(c.type)) || skillGroups.length > 0 || subAgents.length > 0;
      const fleetHandles: string[] = [];
      if (hasModel) fleetHandles.push('model');
      if (hasTools) fleetHandles.push('tools');
      if (hasResources) fleetHandles.push('resources');

      // Compute resource counts for agent-level collapse badges
      // -1 means "all" (unrestricted access), 0 means none
      // Backend may send numeric IDs - coerce to string for .startsWith()
      const sid = (c: { id: string | number }) => String(c.id);
      const hasAllTools = allChips.some(c => c.type === 'tool' && sid(c).startsWith('all-'));
      const explicitToolCount = allChips.filter(c => c.type === 'tool' && !sid(c).startsWith('all-')).length
        + toolGroups.reduce((sum, g) => sum + g.tools.length, 0);
      const toolCount = hasAllTools ? -1 : explicitToolCount;

      const skillCount = allChips.filter(c => c.type === 'skill').length
        + skillGroups.reduce((sum, g) => {
          const countAll = (sg: typeof g): number =>
            sg.skills.length + sg.children.reduce((s, c) => s + countAll(c), 0);
          return countAll(g);
        }, 0);

      const workflowCount = allChips.filter(c => c.type === 'workflow').length;
      const interfaceCount = allChips.filter(c => c.type === 'interface').length;
      const tableCount = allChips.filter(c => c.type === 'table').length;
      const fileCount = allChips.filter(c => c.type === 'file').length;

      const hasWebSearch = allChips.some(c => c.type === 'web_search');
      const hasAnyResource = toolCount !== 0 || skillCount !== 0 || workflowCount !== 0 || interfaceCount !== 0 || tableCount !== 0 || fileCount !== 0 || hasWebSearch;

      // Agent node (FlowNode with fleet handles + avatar) - placeholder position
      allNodes.push({
        id: `agent-${agent.id}`,
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: `agent-${agent.id}`,
          label: agent.name,
          kind: 'reasoning',
          description: agent.description,
          agentAvatarUrl: agent.avatarUrl || undefined,
          fleetBottomHandles: hasDownwardConnections,
          fleetTopHandle: true,
          fleetHandles,
          fleetCollapsible: hasAnyResource,
          fleetResourceCounts: hasAnyResource ? {
            tools: toolCount,
            workflows: workflowCount,
            interfaces: interfaceCount,
            tables: tableCount,
            files: fileCount,
            skills: skillCount,
            webSearch: hasWebSearch,
          } : undefined,
          statusCounts: (agent.successCount || agent.failureCount) ? {
            COMPLETED: agent.successCount || 0,
            FAILED: agent.failureCount || 0,
          } : undefined,
          fleetMetrics: {
            totalExecutions: agent.totalExecutions || 0,
            successRate: (agent.successCount || 0) + (agent.failureCount || 0) > 0
              ? Math.round(((agent.successCount || 0) / ((agent.successCount || 0) + (agent.failureCount || 0))) * 100)
              : null,
            totalTokens: agent.totalTokensUsed || 0,
            totalToolCalls: agent.totalToolCalls || 0,
            lastExecutionAt: agent.lastExecutionAt || null,
            avgDurationMs: (agent.totalExecutions && agent.totalDurationMs)
              ? Math.round(agent.totalDurationMs / agent.totalExecutions)
              : null,
            creditsConsumed: agent.creditsConsumed || 0,
            creditBudget: agent.creditBudget ?? null,
          },
          fleetTriggers: triggersByAgent.get(agent.id) || undefined,
        } as any,
      });

      // ── Build resource chip nodes (all chips get nodes regardless of edge order) ──
      const agentToolStats = toolStatsByAgent.get(agent.id);
      const agentModelStats = modelStatsByAgent.get(agent.id);
      const agentResourceStats = resourceStatsByAgent.get(agent.id);
      // StatusCounts plumbing is centralized in resolveFleetChipStatusCounts (shared
      // with useSingleAgentFleet): tool/web_search by tool name, model by model name
      // (BUDGET_EXHAUSTED carved out), and resource families by the leaf's OWN id so
      // the family total is the honest sum of its parts (not family-total × N leaves).
      allChips.forEach((res) => {
        const resNodeId = `res-${agent.id}-${res.type}-${res.id}`;
        const chipStatusCounts = resolveFleetChipStatusCounts(
          { type: res.type, id: res.id, label: res.label, toolName: res.flowNodeData.toolData?.toolName },
          { toolStats: agentToolStats, modelStats: agentModelStats, resourceStats: agentResourceStats, modelName: agent.modelName || undefined },
        );
        allNodes.push({
          id: resNodeId,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: resNodeId,
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

      // ── Build provider group nodes + their tool children ──
      toolGroups.forEach(group => {
        const providerNodeId = `provider-${agent.id}-${group.apiSlug}`;
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
          const toolNodeId = `res-${agent.id}-${tool.type}-${tool.id}`;
          const grpToolLabel = tool.flowNodeData.toolData?.toolName || tool.label;
          const grpToolKey = grpToolLabel.toLowerCase().replace(/[\s-]/g, '_');
          const grpToolStat = agentToolStats?.get(grpToolKey);
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
              statusCounts: grpToolStat ? {
                COMPLETED: grpToolStat.successCount,
                FAILED: grpToolStat.failureCount,
              } : undefined,
            } as any,
          });

          allEdges.push({
            id: `edge-provider-${agent.id}-${group.apiSlug}-${tool.id}`,
            source: providerNodeId,
            target: toolNodeId,
            sourceHandle: 'source-bottom',
            targetHandle: 'target-top',
          });
        });
      });

      // ── Build skill folder group nodes + their skill children (recursive) ──
      const skillFolderEdges: Edge[] = [];

      const countSkillGroupItems = (g: SkillGroup): number =>
        g.skills.length + g.children.reduce((s, c) => s + countSkillGroupItems(c), 0);

      const buildSkillGroupNodes = (group: SkillGroup, parentNodeId: string) => {
        const folderNodeId = `folder-${agent.id}-${group.folderId}`;
        const totalChildren = countSkillGroupItems(group);
        allNodes.push({
          id: folderNodeId,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: folderNodeId,
            label: group.folderName,
            kind: 'action',
            fleetTopHandle: true,
            fleetBottomHandles: true,
            fleetResourceType: 'folder',
          } as any,
        });

        // Edge from parent (agent or parent folder) to this folder
        skillFolderEdges.push({
          id: `edge-folder-${agent.id}-${parentNodeId}-${group.folderId}`,
          source: parentNodeId,
          target: folderNodeId,
          sourceHandle: parentNodeId.startsWith('agent-') ? 'source-resources' : 'source-bottom',
          targetHandle: 'target-top',
          data: { category: 'skills' },
        });

        // Skill children of this folder
        group.skills.forEach(skill => {
          const skillNodeId = `res-${agent.id}-skill-${skill.id}`;
          allNodes.push({
            id: skillNodeId,
            type: 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              id: skillNodeId,
              label: skill.label,
              kind: skill.flowNodeData.kind,
              fleetTopHandle: true,
              fleetResourceType: 'skill',
            } as any,
          });

          allEdges.push({
            id: `edge-folder-skill-${agent.id}-${group.folderId}-${skill.id}`,
            source: folderNodeId,
            target: skillNodeId,
            sourceHandle: 'source-bottom',
            targetHandle: 'target-top',
            data: { category: 'skills' },
          });
        });

        // Recurse into sub-folders
        group.children.forEach(child => buildSkillGroupNodes(child, folderNodeId));
      };

      skillGroups.forEach(group => buildSkillGroupNodes(group, `agent-${agent.id}`));

      // ── Build edges from agent → children ──
      const modelEdges: Edge[] = [];
      const toolChipEdges: Edge[] = [];
      const resourceChipEdges: Edge[] = [];

      allChips.forEach((res) => {
        const edge: Edge = {
          id: `res-edge-${agent.id}-${res.type}-${res.id}`,
          source: `agent-${agent.id}`,
          target: `res-${agent.id}-${res.type}-${res.id}`,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: { category: 'resources' },
        };
        if (res.type === 'model') {
          edge.sourceHandle = 'source-model';
          edge.data = { category: 'model' };
          modelEdges.push(edge);
        } else if (TOOL_TYPES.has(res.type)) {
          edge.sourceHandle = 'source-tools';
          edge.data = { category: 'tools' };
          toolChipEdges.push(edge);
        } else {
          resourceChipEdges.push(edge);
        }
      });

      // Provider group edges (agent → provider) - tools handle
      const providerEdges: Edge[] = toolGroups.map(group => ({
        id: `edge-${agent.id}-provider-${group.apiSlug}`,
        source: `agent-${agent.id}`,
        target: `provider-${agent.id}-${group.apiSlug}`,
        sourceHandle: 'source-tools',
        targetHandle: 'target-top',
        data: { category: 'tools' },
      }));

      // Sub-agent edges - with per-caller stats (how many times THIS parent called THIS child)
      const subAgentEdges: Edge[] = subAgents.map(childId => {
        const callerStats = subAgentStatsByAgent.get(agent.id);
        const edgeStats = callerStats?.get(childId);
        const edgeStatusCounts = edgeStats ? {
          COMPLETED: edgeStats.successCount,
          FAILED: edgeStats.failureCount,
        } : undefined;
        return {
          id: `edge-${agent.id}-${childId}`,
          source: `agent-${agent.id}`,
          target: `agent-${childId}`,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: {
            category: 'sub-agents',
            statusCounts: edgeStatusCounts,
            totalCalls: edgeStats?.totalCalls,
          },
        };
      });

      allEdges.push(...modelEdges, ...toolChipEdges, ...providerEdges, ...skillFolderEdges, ...resourceChipEdges, ...subAgentEdges);

      // ── Category grouping: group resource types with 2+ items ──
      const GROUPABLE_TYPES = ['workflow', 'interface', 'table', 'file'] as const;
      for (const resType of GROUPABLE_TYPES) {
        // Find resource chip edges for this type (agent → res node)
        const typeEdges = resourceChipEdges.filter(e => {
          const targetNode = allNodes.find(n => n.id === e.target);
          const rt = (targetNode?.data as any)?.fleetResourceType;
          // Skip any remaining "All X" placeholder nodes (e.g. 'all-tools')
          return rt === resType && !e.target.includes('-all-');
        });
        if (typeEdges.length < 2) continue;

        // Create category group node
        const catNodeId = `category-${agent.id}-${resType}`;
        const catLabel = CATEGORY_LABELS[resType] || resType;
        const catKind = CATEGORY_KINDS[resType] || 'action';
        // statusCounts left undefined here - aggregateContainerStatusCounts fills it
        // with the SUM of this group's leaf resources (e.g. Tables = A + B calls).
        allNodes.push({
          id: catNodeId,
          type: 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            id: catNodeId,
            label: catLabel,
            kind: catKind,
            fleetTopHandle: true,
            fleetBottomHandles: true,
            fleetResourceType: resType,
          } as any,
        });

        // Edge: agent → category group
        allEdges.push({
          id: `edge-category-${agent.id}-${resType}`,
          source: `agent-${agent.id}`,
          target: catNodeId,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: { category: 'resources' },
        });

        // Rewire: remove agent→res edges, add category→res edges
        for (const te of typeEdges) {
          const idx = allEdges.indexOf(te);
          if (idx !== -1) allEdges.splice(idx, 1);
          allEdges.push({
            id: `edge-cat-child-${agent.id}-${resType}-${te.target}`,
            source: catNodeId,
            target: te.target,
            sourceHandle: 'source-bottom',
            targetHandle: 'target-top',
            data: { category: 'resources' },
          });
        }
      }
    });

    // Collect collapsible group IDs
    const collapsibleGroupIds = allNodes
      .filter(n => (n.data as any).fleetCollapsible)
      .map(n => n.id);

    // Roll status counts up onto container nodes (Tables = Σ tables, provider = Σ its
    // tools, …) BEFORE consolidation, so the surviving groups - and the leaves the
    // aggregator later sums - already carry cumulative ✓/✗.
    aggregateContainerStatusCounts(allNodes, allEdges);

    // Consolidate agents with ≥6 resources into one aggregator node (keeps the
    // fleet compact). Rewrites nodes AND edges; must precede layout. Idempotent.
    // The aggregator carries the cumulative count of everything it folds.
    const consolidated = consolidateFleetResources(allNodes, allEdges);

    // Color/label edges from their target node's (now cumulative) counts - run AFTER
    // consolidation so the new agent→"Resources (N)" edge picks up the aggregator's sum.
    colorEdgesByStatus(consolidated.nodes, consolidated.edges);

    // Apply Dagre layout - but SKIP it when the structure (node + edge ids) is unchanged
    // since the last build. The effect re-runs on every stat/trigger map update (badge data),
    // and Dagre is the expensive part; a stats-only update keeps the exact same graph, so
    // applyFleetLayoutCached reuses the cached positions. (The displayed positions are the
    // canvas effect's authority; this still saves the hook's own Dagre pass + stable allNodesRaw.)
    const cachedLayout = applyFleetLayoutCached(
      consolidated.nodes, consolidated.edges, lastLayoutSigRef.current, lastPositionsRef.current);
    lastLayoutSigRef.current = cachedLayout.sig;
    lastPositionsRef.current = cachedLayout.positions;
    const layoutedNodes = cachedLayout.nodes;
    setNodes(layoutedNodes);
    setEdges(consolidated.edges);

    // Store raw data for collapse filtering
    allNodesRawRef.current = layoutedNodes;
    allEdgesRawRef.current = consolidated.edges;
    collapsibleGroupIdsRef.current = collapsibleGroupIds;
  }, [agents, skillsByAgent, skillFolders, workflowNames, interfaceNames, dataSourceNames, fileNames, missingResourceIds, apiIconMap, apiNameMap, toolUuidMap, toolStatsByAgent, resourceStatsByAgent, subAgentStatsByAgent, modelStatsByAgent, triggersByAgent, setNodes, setEdges]);

  // Initial fetch (skip when not active)
  useEffect(() => {
    if (!skip) fetchAll();
  }, [fetchAll, skip]);

  const onConnect = useCallback(() => {}, []);

  return useMemo(() => ({
    nodes,
    edges,
    setNodes,
    setEdges,
    onNodesChange,
    onEdgesChange,
    onConnect,
    isLoading,
    refetch: fetchAll,
    // Raw data for plan generation
    agents,
    skillsByAgent,
    skillFolders,
    workflowNames,
    interfaceNames,
    dataSourceNames,
    resourcesById,
    // Collapse support
    allNodesRaw: allNodesRawRef.current,
    allEdgesRaw: allEdgesRawRef.current,
    collapsibleGroupIds: collapsibleGroupIdsRef.current,
  }), [nodes, edges, setNodes, setEdges, onNodesChange, onEdgesChange, onConnect, isLoading, fetchAll, agents, skillsByAgent, skillFolders, workflowNames, interfaceNames, dataSourceNames, resourcesById]);
}
