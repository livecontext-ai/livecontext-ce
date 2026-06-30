'use client';

import { useMemo, useEffect } from 'react';
import { useNodesState, useEdgesState } from 'reactflow';
import type { Node, Edge } from 'reactflow';
import type { AgentPublicationSnapshot, AgentSnapshotData } from '@/lib/api/orchestrator/types';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { applyFleetLayout, consolidateFleetResources } from './useAgentFleetState';
import { PROVIDER_ICON_MAP } from '@/lib/ai-providers/providerIcons';
import { getAllowedIds, isWebSearchEnabled } from '@/lib/agents/toolsConfigAccess';

// ─── Category group labels ───
const CATEGORY_LABELS: Record<string, string> = {
  workflow: 'Workflows',
  interface: 'Interfaces',
  table: 'Tables',
};

const CATEGORY_KINDS: Record<string, string> = {
  workflow: 'entry',
  interface: 'interface',
  table: 'data_input',
};

// ─── Module-level stable references (prevent unstable object identity in hooks) ───
const NOOP = () => {};
const TOOL_TYPES = new Set(['tool', 'skill']);
const RESOURCE_TYPES = new Set(['workflow', 'interface', 'table', 'web_search']);

// ─── Slugify helper ───
function slugify(text: string): string {
  return text
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/-{2,}/g, '-')
    .replace(/^-+|-+$/g, '');
}

// ─── Resource descriptor ───
interface ResourceDescriptor {
  id: string;
  type: 'tool' | 'skill' | 'workflow' | 'interface' | 'table' | 'model' | 'web_search';
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
 * Build resource descriptors from an agent snapshot's toolsConfig.
 * Unlike useSingleAgentFleet, we resolve names from the snapshot's flat resource maps
 * instead of fetching from API.
 */
function getSnapshotResources(
  agent: AgentSnapshotData,
  workflowNames: Map<string, string>,
  interfaceNames: Map<string, string>,
  datasourceNames: Map<string, string>,
): { resources: ResourceDescriptor[]; toolGroups: ToolGroup[] } {
  const resources: ResourceDescriptor[] = [];
  const toolGroups: ToolGroup[] = [];

  // Skills from snapshot
  if (agent.skills && agent.skills.length > 0) {
    agent.skills.forEach(skill => {
      resources.push({
        id: `skill-${skill.name}`,
        type: 'skill',
        label: skill.name,
        flowNodeData: { kind: 'action' },
      });
    });
  }

  const tc = agent.toolsConfig;

  // Null toolsConfig (legacy snapshot, pre-V163) → MCP catalogue defaults to "all"
  // (product behavior), web search defaults to enabled, BUT the 5 internal resource
  // lists are NOT enumerated here - absent === [] (security rule). This snapshot
  // preview is shown to anyone browsing the marketplace; pre-fix it leaked the
  // publisher's full tenant resource set whenever an agent had `tc=null`.
  // Companion readers: useSingleAgentFleet, useAgentFleetState, FleetPlanGenerator.
  if (!tc) {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
    if (isWebSearchEnabled(tc)) {
      resources.push({ id: 'web-search', type: 'web_search', label: 'Web Search', flowNodeData: { kind: 'web_search' } });
    }
    return { resources, toolGroups };
  }

  // Tools (MCP tools)
  const toolsMode = tc.mode || 'all';
  if (toolsMode === 'all') {
    resources.push({ id: 'all-tools', type: 'tool', label: 'All tools', flowNodeData: { kind: 'tool' } });
  } else if (toolsMode === 'custom' && Array.isArray(tc.tools)) {
    const byProvider = new Map<string, ResourceDescriptor[]>();
    const providerMeta = new Map<string, { iconSlug?: string; apiName: string }>();

    tc.tools.forEach((toolId: string) => {
      const colonIdx = toolId.indexOf(':');
      const apiSlug = colonIdx > 0 ? toolId.slice(0, colonIdx) : '';
      const toolName = colonIdx > 0 ? toolId.slice(colonIdx + 1) : toolId;
      const readableLabel = toolName.replace(/-/g, ' ').replace(/_/g, ' ');

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
            apiName: apiSlug,
          },
          apiData: { apiSlug, apiName: apiSlug },
        },
      };

      const key = apiSlug || toolId;
      if (!byProvider.has(key)) byProvider.set(key, []);
      byProvider.get(key)!.push(descriptor);
      if (!providerMeta.has(key)) providerMeta.set(key, { apiName: apiSlug });
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

  // Internal resource lists - absent === [] (security rule). Marketplace snapshots
  // must not enumerate the publisher's full tenant resource set on absent keys.
  // See lib/agents/toolsConfigAccess.ts for the canonical reader.
  getAllowedIds(tc, 'workflows').forEach(wfId => {
    resources.push({ id: wfId, type: 'workflow', label: workflowNames.get(wfId) || wfId.slice(0, 8), flowNodeData: { kind: 'entry' } });
  });

  getAllowedIds(tc, 'interfaces').forEach(ifId => {
    resources.push({ id: ifId, type: 'interface', label: interfaceNames.get(ifId) || ifId.slice(0, 8), flowNodeData: { kind: 'interface' } });
  });

  getAllowedIds(tc, 'tables').forEach(tblId => {
    resources.push({ id: tblId, type: 'table', label: datasourceNames.get(tblId) || `Table ${tblId}`, flowNodeData: { kind: 'data_input' } });
  });

  // Web search
  if (isWebSearchEnabled(tc)) {
    resources.push({ id: 'web-search', type: 'web_search', label: 'Web Search', flowNodeData: { kind: 'web_search' } });
  }

  return { resources, toolGroups };
}

/**
 * Hook that converts an AgentPublicationSnapshot into ReactFlow nodes/edges
 * **without any API calls**. Used for marketplace preview of agent publications.
 *
 * Returns the same interface shape as useSingleAgentFleet so AgentFleetCanvas
 * can use either interchangeably.
 */
export function useSnapshotAgentFleet(snapshot: AgentPublicationSnapshot | null | undefined) {
  const [nodes, setNodes, onNodesChange] = useNodesState<BuilderNodeData>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  // Build name lookup maps from snapshot's flat resource collections
  const { workflowNames, interfaceNames, datasourceNames } = useMemo(() => {
    const wfMap = new Map<string, string>();
    const ifMap = new Map<string, string>();
    const dsMap = new Map<string, string>();
    if (snapshot?.workflows) {
      Object.entries(snapshot.workflows).forEach(([id, wf]) => wfMap.set(id, wf.name));
    }
    if (snapshot?.interfaces) {
      Object.entries(snapshot.interfaces).forEach(([id, iface]) => ifMap.set(id, iface.name));
    }
    if (snapshot?.datasources) {
      Object.entries(snapshot.datasources).forEach(([id, ds]) => dsMap.set(id, ds.name));
    }
    return { workflowNames: wfMap, interfaceNames: ifMap, datasourceNames: dsMap };
  }, [snapshot]);

  // Build all nodes and edges from the snapshot
  const { builtNodes, builtEdges, collapsibleGroupIds } = useMemo(() => {
    if (!snapshot?.agent) {
      return { builtNodes: [] as Node<BuilderNodeData>[], builtEdges: [] as Edge[], collapsibleGroupIds: [] as string[] };
    }

    const allNodes: Node<BuilderNodeData>[] = [];
    const allEdges: Edge[] = [];

    // ── Helper: build nodes + edges for a single agent snapshot ──
    const buildAgentGraph = (agentData: AgentSnapshotData, subAgentIds: string[]) => {
      const aid = agentData.id;
      const { resources, toolGroups } = getSnapshotResources(
        agentData, workflowNames, interfaceNames, datasourceNames,
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

      const hasDownward = chips.length > 0 || toolGroups.length > 0 || subAgentIds.length > 0;

      // Fleet handles
      const hasModel = chips.some(c => c.type === 'model');
      const hasTools = chips.some(c => TOOL_TYPES.has(c.type)) || toolGroups.length > 0;
      const hasRes = chips.some(c => RESOURCE_TYPES.has(c.type)) || subAgentIds.length > 0;
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
      const hasWeb = chips.some(c => c.type === 'web_search');
      const hasAny = toolCount !== 0 || skillCount !== 0 || wfCount !== 0 || ifCount !== 0 || tblCount !== 0 || hasWeb;

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
            conversations: 0,
            skills: skillCount,
            webSearch: hasWeb,
          } : undefined,
        } as any,
      });

      // Resource chip nodes (no stats in snapshot mode)
      chips.forEach((res) => {
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

    // ── Collect all agent IDs + their sub-agent children from the snapshot tree ──
    const subAgentChildrenMap = new Map<string, string[]>();
    const visitedAgents = new Set<string>();

    const collectSubAgents = (
      parentId: string,
      subAgentsMap: Record<string, { agent: AgentSnapshotData; subAgents?: Record<string, unknown> }> | undefined,
    ) => {
      if (visitedAgents.has(parentId)) return; // cycle guard
      visitedAgents.add(parentId);
      const childIds = subAgentsMap ? Object.keys(subAgentsMap) : [];
      subAgentChildrenMap.set(parentId, childIds);
      if (subAgentsMap) {
        Object.entries(subAgentsMap).forEach(([childId, childData]) => {
          collectSubAgents(childId, childData.subAgents as any);
        });
      }
    };

    collectSubAgents(snapshot.agent.id, snapshot.subAgents);

    // Build graph for root agent
    buildAgentGraph(snapshot.agent, subAgentChildrenMap.get(snapshot.agent.id) || []);

    // Build graph for each sub-agent recursively (reuses visitedAgents for cycle guard)
    const builtAgents = new Set<string>();
    const buildSubAgentGraphs = (
      subAgentsMap: Record<string, { agent: AgentSnapshotData; subAgents?: Record<string, unknown> }> | undefined,
    ) => {
      if (!subAgentsMap) return;
      Object.entries(subAgentsMap).forEach(([childId, childData]) => {
        if (builtAgents.has(childId)) return; // cycle guard
        builtAgents.add(childId);
        buildAgentGraph(childData.agent, subAgentChildrenMap.get(childId) || []);
        buildSubAgentGraphs(childData.subAgents as any);
      });
    };

    buildSubAgentGraphs(snapshot.subAgents);

    // ── Sub-agent edges (parent → child) ──
    subAgentChildrenMap.forEach((childIds, parentId) => {
      childIds.forEach(childId => {
        allEdges.push({
          id: `edge-${parentId}-${childId}`,
          source: `agent-${parentId}`,
          target: `agent-${childId}`,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: { category: 'sub-agents' },
        });
      });
    });

    // ── Category grouping: group resource types with 2+ items (per agent) ──
    const GROUPABLE_TYPES = ['workflow', 'interface', 'table'] as const;
    const allAgentIds = new Set([snapshot.agent.id, ...subAgentChildrenMap.keys()]);
    const nodeById = new Map(allNodes.map(n => [n.id, n]));

    for (const aid of allAgentIds) {
      const agentResourceEdges = allEdges.filter(e => {
        if (e.source !== `agent-${aid}`) return false;
        const targetNode = nodeById.get(e.target);
        const rt = (targetNode?.data as any)?.fleetResourceType;
        return rt && rt !== 'model' && rt !== 'tool' && rt !== 'skill';
      });

      for (const resType of GROUPABLE_TYPES) {
        const typeEdges = agentResourceEdges.filter(e => {
          const targetNode = nodeById.get(e.target);
          const rt = (targetNode?.data as any)?.fleetResourceType;
          return rt === resType && !e.target.includes('-all-');
        });
        if (typeEdges.length < 2) continue;

        const catNodeId = `category-${aid}-${resType}`;
        const catNode: Node<BuilderNodeData> = {
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
        };
        allNodes.push(catNode);
        nodeById.set(catNodeId, catNode);

        allEdges.push({
          id: `edge-category-${aid}-${resType}`,
          source: `agent-${aid}`,
          target: catNodeId,
          sourceHandle: 'source-resources',
          targetHandle: 'target-top',
          data: { category: 'resources' },
        });

        // Replace direct agent→resource edges with category→resource edges
        const edgeIdsToRemove = new Set(typeEdges.map(e => e.id));
        const newChildEdges = typeEdges.map(te => ({
          id: `edge-cat-child-${aid}-${resType}-${te.target}`,
          source: catNodeId,
          target: te.target,
          sourceHandle: 'source-bottom',
          targetHandle: 'target-top',
          data: { category: 'resources' },
        }));

        // Filter out old edges and add new ones (avoids in-place splice mutation)
        let i = 0;
        while (i < allEdges.length) {
          if (edgeIdsToRemove.has(allEdges[i].id)) {
            allEdges.splice(i, 1);
          } else {
            i++;
          }
        }
        allEdges.push(...newChildEdges);
      }
    }

    // Consolidate agents with ≥6 resources into one aggregator node (keeps the
    // fleet compact). Rewrites nodes AND edges; must precede layout. Idempotent.
    const consolidated = consolidateFleetResources(allNodes, allEdges);

    // Collect collapsible group IDs (only agent nodes are collapsible, and agents
    // are never consolidated away, so this is unaffected by consolidation)
    const collapsible = allNodes
      .filter(n => (n.data as any).fleetCollapsible)
      .map(n => n.id);

    // Apply fleet layout
    const layoutedNodes = applyFleetLayout(consolidated.nodes, consolidated.edges);

    return { builtNodes: layoutedNodes, builtEdges: consolidated.edges, collapsibleGroupIds: collapsible };
  }, [snapshot, workflowNames, interfaceNames, datasourceNames]);

  // Sync built graph into ReactFlow state
  useEffect(() => {
    setNodes(builtNodes);
    setEdges(builtEdges);
  }, [builtNodes, builtEdges, setNodes, setEdges]);

  // Build flat agent list (root + all sub-agents) so FleetInspectorPanel can resolve any clicked agent node
  const { allAgents, skillsByAgent, resourcesById } = useMemo(() => {
    const agentsList: any[] = [];
    const skillsMap = new Map<string, any[]>();
    const resMap = new Map<string, any>();

    // Walk the full agent tree (root + sub-agents recursively)
    const seen = new Set<string>();
    const visit = (data: AgentSnapshotData | undefined, subTree: Record<string, any> | undefined) => {
      if (!data || seen.has(data.id)) return;
      seen.add(data.id);
      // Pass the FULL agent data so the inspector can render systemPrompt, temperature,
      // maxTokens, maxIterations, executionTimeout, toolsConfig, etc.
      agentsList.push({ ...data });
      if (Array.isArray(data.skills)) {
        // Build the AgentSkill shape FleetInspectorPanel expects: a nested `skill` object
        // (id/name/description/folderId), plus `skillId`/`agentId`/`sortOrder`.
        // Snapshot skills don't carry a real skillId - the snapshot inlines name/description/icon/instructions -
        // so we mint a stable synthetic ID per (agentId, skill name) and reuse it for both `id` and `skillId`.
        skillsMap.set(data.id, data.skills.map((s: any, idx: number) => {
          const synthId = `${data.id}-${s.name || idx}`;
          return {
            id: synthId,
            agentId: data.id,
            skillId: synthId,
            sortOrder: typeof s.sortOrder === 'number' ? s.sortOrder : idx,
            skill: {
              id: synthId,
              name: s.name,
              description: s.description,
              icon: s.icon,
              instructions: s.instructions,
              folderId: null,
            },
          };
        }));
      }
      if (subTree) {
        Object.values(subTree).forEach((child: any) => visit(child?.agent, child?.subAgents));
      }
    };
    visit(snapshot?.agent, snapshot?.subAgents as any);

    // Resource lookup (id → metadata) from snapshot's flat resource maps
    if (snapshot?.workflows) {
      Object.entries(snapshot.workflows).forEach(([id, wf]) => {
        resMap.set(id, { id, name: wf.name, description: wf.description, kind: 'workflow' });
      });
    }
    if (snapshot?.interfaces) {
      Object.entries(snapshot.interfaces).forEach(([id, iface]) => {
        resMap.set(id, { id, name: iface.name, description: iface.description, kind: 'interface', interfaceType: iface.interfaceType });
      });
    }
    if (snapshot?.datasources) {
      Object.entries(snapshot.datasources).forEach(([id, ds]) => {
        resMap.set(id, { id, name: ds.name, description: ds.description, kind: 'table', sourceType: ds.sourceType });
      });
    }

    return { allAgents: agentsList, skillsByAgent: skillsMap, resourcesById: resMap };
  }, [snapshot]);

  // Return same interface shape as useSingleAgentFleet
  return useMemo(() => ({
    nodes,
    edges,
    setNodes,
    setEdges,
    onNodesChange,
    onEdgesChange,
    isLoading: false,
    // Full agent object - never strip fields, FleetInspectorPanel reads systemPrompt,
    // temperature, maxTokens, maxIterations, executionTimeout, toolsConfig, etc.
    agent: snapshot?.agent ? { ...snapshot.agent } : null,
    allAgents,
    skillsByAgent,
    resourcesById,
    refetch: NOOP,
    allNodesRaw: builtNodes,
    allEdgesRaw: builtEdges,
    collapsibleGroupIds,
  }), [nodes, edges, setNodes, setEdges, onNodesChange, onEdgesChange, snapshot, builtNodes, builtEdges, collapsibleGroupIds, allAgents, skillsByAgent, resourcesById]);
}
