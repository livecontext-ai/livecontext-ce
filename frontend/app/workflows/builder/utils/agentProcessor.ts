import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { PlanGeneratorContext } from './planGeneratorContext';
import { normalizeLabel } from './labelNormalizer';
import {
  getNodePosition,
  hasKeys,
  convertParamExpressionsToInputs,
  isAiReasoningNode,
  getAgentType,
} from './planHelpers';

/**
 * Processes all AI reasoning nodes (agent, classify, guardrail) and adds them to the plan.
 */
export function processAgents(ctx: PlanGeneratorContext): void {
  const agentNodes = ctx.nodes.filter((node) => isAiReasoningNode(node));

  // First pass: populate stepLabelMap with agent labels
  agentNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = normalizeLabel(label);
    ctx.stepLabelMap.set(node.id, normalizedLabel);
  });

  // Second pass: create agent plan objects
  agentNodes.forEach((node) => {
    const label = node.data.label || node.id;
    const normalizedLabel = ctx.stepLabelMap.get(node.id)!;

    const agent: any = {
      id: node.id,
      type: getAgentType(node),  // agent, guardrail, classify
      label: label,
      graphNodeId: node.id,
    };

    // Agent entity reference mode (type='agent' with agentConfigId)
    if (node.data.agentConfigId && getAgentType(node) === 'agent') {
      agent.agentConfigId = node.data.agentConfigId;
      if (node.data.agentConfigName) agent.agentConfigName = node.data.agentConfigName;
      if (node.data.agentAvatarUrl) agent.agentAvatarUrl = node.data.agentAvatarUrl;
      agent.withMemory = node.data.withMemory ?? true;
      if (node.data.prompt) agent.prompt = node.data.prompt;

      // Add params (prompt may come from paramExpressions)
      const agentParams = convertParamExpressionsToInputs(node.data.paramExpressions);
      if (hasKeys(agentParams)) {
        agent.params = agentParams;
      }

      // Add position
      const nodePosition = getNodePosition(node);
      agent.position = nodePosition || { x: 0, y: 0 };

      ctx.plan.agents!.push(agent);
      ctx.agentPlanByNodeId.set(node.id, agent);
      return; // Skip inline config for entity-referenced agents
    }

    // Add optional agent properties (common to all AI reasoning nodes)
    if (node.data.provider) agent.provider = node.data.provider;
    if (node.data.model) agent.model = node.data.model;
    if (node.data.systemPrompt) agent.systemPrompt = node.data.systemPrompt;
    if (node.data.prompt) agent.prompt = node.data.prompt;
    if (node.data.temperature !== undefined) agent.temperature = node.data.temperature;
    if (node.data.maxTokens !== undefined) agent.maxTokens = node.data.maxTokens;
    if (node.data.maxIterations !== undefined) agent.maxIterations = node.data.maxIterations;
    if (node.data.maxTools !== undefined) agent.maxTools = node.data.maxTools;

    // Add classify-specific properties
    if (node.data.classifyCategories && node.data.classifyCategories.length > 0) {
      agent.classifyCategories = node.data.classifyCategories;
    }
    // Read classifyParams from paramExpressions (UI storage) or fallback to root (legacy/backend)
    const classifyParams = (node.data.paramExpressions as Record<string, string> | undefined)?.classifyParams
      || node.data.classifyParams;
    if (classifyParams) {
      agent.classifyParams = classifyParams;
    }

    // Add guardrail-specific properties
    if (node.data.guardrailRules && node.data.guardrailRules.length > 0) {
      agent.guardrailRules = node.data.guardrailRules;
    }
    // Read guardrailParams from paramExpressions (UI storage) or fallback to root (legacy/backend)
    const guardrailParams = (node.data.paramExpressions as Record<string, string> | undefined)?.guardrailParams
      || node.data.guardrailParams;
    if (guardrailParams) {
      agent.guardrailParams = guardrailParams;
    }

    // Process tool connections (only for pure agents without entity reference)
    const toolRefs = getToolReferences(node, ctx.nodes, ctx.edges, ctx.stepLabelMap);
    if (toolRefs.length > 0) {
      agent.tools = toolRefs;
    }

    // Add params
    const agentParams = convertParamExpressionsToInputs(node.data.paramExpressions);
    if (hasKeys(agentParams)) {
      agent.params = agentParams;
    }

    // Browser-agent contract: the runner's `_resolve_llm` parses
    // `params.llm` as a Python dict and raises
    // "llm config must be a dict, got str" if it receives a string.
    // The expressions map JSON.stringify's objects (cf.
    // BrowserAgentParametersForm.updateParam → nextExpressions[key] =
    // JSON.stringify(value)), so the plain `agentParams.llm` would arrive
    // as a string and fail at runtime. Re-hydrate the structured fields
    // from `data.params` (the inspector's source of truth for object-
    // valued params) before persisting the plan. Mirror the pattern for
    // `session` and `expected_output_schema` which are also object-shaped
    // per BrowserAgentNodeSpec.
    if (getAgentType(node) === 'browser_agent') {
      const liveParams = ((node.data as unknown) as { params?: Record<string, unknown> }).params ?? {};
      const ensureObject = (key: string) => {
        const live = liveParams[key];
        if (live && typeof live === 'object') {
          if (!agent.params) agent.params = {};
          (agent.params as Record<string, unknown>)[key] = live;
        }
      };
      ensureObject('llm');
      ensureObject('session');
      ensureObject('expected_output_schema');
    }

    // Add position
    const nodePosition = getNodePosition(node);
    agent.position = nodePosition || { x: 0, y: 0 };

    ctx.plan.agents!.push(agent);
    ctx.agentPlanByNodeId.set(node.id, agent);
  });
}

/**
 * Gets tool references for an agent node.
 */
function getToolReferences(
  agentNode: Node<BuilderNodeData>,
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  stepLabelMap: Map<string, string>
): string[] {
  const toolEdges = edges.filter(
    (edge) =>
      edge.source === agentNode.id &&
      (edge.sourceHandle === 'source-bottom-tools' ||
        edge.sourceHandle === 'source-bottom-1' ||
        edge.sourceHandle === 'source-bottom-2')
  );

  if (toolEdges.length === 0) {
    return [];
  }

  const processedNodeIds = new Set<string>();
  const toolRefs: string[] = [];

  for (const toolEdge of toolEdges) {
    const targetNode = nodes.find((n) => n.id === toolEdge.target);
    if (targetNode?.data && !processedNodeIds.has(targetNode.id)) {
      processedNodeIds.add(targetNode.id);
      const targetLabel = stepLabelMap.get(targetNode.id);
      if (targetLabel) {
        const isTargetAgent = isAiReasoningNode(targetNode);
        const prefix = isTargetAgent ? 'agent' : 'mcp';
        toolRefs.push(`${prefix}:${targetLabel}`);
      }
    }
  }

  return toolRefs;
}
