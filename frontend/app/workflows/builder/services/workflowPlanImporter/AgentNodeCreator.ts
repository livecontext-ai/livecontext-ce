/**
 * AgentNodeCreator - Handles creation of agent nodes from plan data
 * Extracted from NodeCreationService for single responsibility
 */

import type { Node, XYPosition } from 'reactflow';
import type { BuilderNodeData, GuardrailType } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { parsePosition, inputToParamExpressions, NODE_SPACING, INITIAL_POSITION } from './nodeCreationHelpers';

/**
 * Maps backend/agent guardrail rule types to frontend GuardrailType values.
 * The agent creates rules with short names (pii, spam, toxicity) while the
 * frontend inspector expects longer identifiers (pii_detection, keyword_filter, etc.).
 */
const GUARDRAIL_TYPE_MAP: Record<string, GuardrailType> = {
  pii: 'pii_detection',
  pii_detection: 'pii_detection',
  toxicity: 'toxic_language',
  toxic: 'toxic_language',
  toxic_language: 'toxic_language',
  spam: 'keyword_filter',
  keyword: 'keyword_filter',
  keyword_filter: 'keyword_filter',
  prompt_injection: 'prompt_injection',
  injection: 'prompt_injection',
  regex: 'regex_pattern',
  regex_pattern: 'regex_pattern',
  length: 'length_check',
  length_check: 'length_check',
  topic: 'topic_restriction',
  topic_restriction: 'topic_restriction',
  competitor: 'competitor_mention',
  competitor_mention: 'competitor_mention',
  custom: 'custom',
};

/**
 * Returns the default config fields for a guardrail rule type.
 * The description from the plan is preserved alongside the type-specific fields
 * so the frontend form doesn't show "required" errors on empty config fields.
 */
function defaultConfigForType(type: GuardrailType, existing?: Record<string, any>): Record<string, any> {
  const desc = existing?.description;
  switch (type) {
    case 'pii_detection':
      return { ...existing, piiTypes: existing?.piiTypes ?? ['email', 'phone', 'credit_card'] };
    case 'keyword_filter':
      return { ...existing, keywordsExpression: existing?.keywordsExpression ?? desc ?? '', mode: existing?.mode ?? 'block' };
    case 'regex_pattern':
      return { ...existing, pattern: existing?.pattern ?? '' };
    case 'length_check':
      return { ...existing, minLength: existing?.minLength ?? 1, maxLength: existing?.maxLength ?? 10000 };
    case 'topic_restriction':
    case 'competitor_mention':
      return { ...existing, topicsExpression: existing?.topicsExpression ?? desc ?? '' };
    case 'custom':
      return { ...existing, expression: existing?.expression ?? '' };
    default:
      return existing ?? {};
  }
}

interface AgentFromPlan {
  label: string;
  type?: 'agent' | 'guardrail' | 'classify' | 'browser_agent';
  position?: { x?: number | string; y?: number | string };
  // Agent entity reference (for type='agent' only)
  agentConfigId?: string;
  agentConfigName?: string;
  agentAvatarUrl?: string;
  withMemory?: boolean;
  // Inline config fields
  provider?: string;
  model?: string;
  systemPrompt?: string;
  prompt?: string;
  temperature?: number;
  maxTokens?: number;
  maxIterations?: number;
  maxTools?: number;
  tools?: string[];
  params?: Record<string, any>;
  // Classify-specific properties (classifyCategories is canonical, categories is legacy)
  classifyCategories?: Array<{ id: string; label: string; description?: string }>;
  categories?: Array<{ id?: string; label: string; description?: string }>; // Legacy name
  classifyParams?: string;
  content?: string; // Legacy name for classifyParams; also guardrail input
  // Guardrail-specific properties
  guardrailRules?: Array<{ id: string; type: string; action?: string; config?: any }>;
  guardrailParams?: string;
  rules?: Record<string, string>; // Backend format: {ruleKey: description}
  action?: string; // Backend guardrail action: flag/block/redact
  graphNodeId?: string;
}

interface AgentCreationResult {
  nodes: Node<BuilderNodeData>[];
  labelToNodeIdMap: Map<string, string>;
  nextY: number;
  nextX: number;
}

/**
 * Create a single agent node
 */
function createAgentNode(
  agent: AgentFromPlan,
  currentX: number,
  currentY: number
): {
  node: Node<BuilderNodeData>;
  mappings: { label: string; normalizedLabel: string };
  incrementY: boolean;
} {
  const nodeId = agent.graphNodeId || `agent-${agent.label}-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

  // Parse position
  const { position: agentPosition, useSavedPosition } = parsePosition(
    agent.position,
    currentX,
    currentY,
    `agent ${agent.label}`
  );

  // Determine node type based on agent type
  const agentType = agent.type || 'agent';
  let nodeType = 'flowNode';
  const uniqueSuffix = `${Date.now()}-${Math.random().toString(36).substr(2, 5)}`;
  let dataId = `ai-agent-${uniqueSuffix}`;
  if (agentType === 'classify') {
    nodeType = 'classifyNode';
    dataId = `classify-${uniqueSuffix}`;
  } else if (agentType === 'guardrail') {
    nodeType = 'guardrailNode';
    dataId = `guardrail-${uniqueSuffix}`;
  } else if (agentType === 'browser_agent') {
    // Use the dedicated ReactFlow component (now registered in
    // graphTypes.ts) so the node renders the provider iconSlug + the
    // browser-specific live-view button. dataId mirrors the
    // classify/guardrail prefix convention so
    // nodeRegistry.isBrowserAgentNode() recognises the node.
    nodeType = 'browserAgentNode';
    dataId = `browser_agent-${uniqueSuffix}`;
    // browser_agent stores its LLM choice inside params.llm (often as a
    // stringified JSON blob - that's how the plan generator emits it).
    // Hoist provider/model to the top level so they land in
    // data.provider / data.model exactly like classify/guardrail. Without
    // this, BrowserAgentNode falls back to getEffectiveDefaultProvider()
    // on every refresh and the icon flips to the workspace default.
    if (!agent.provider || !agent.model) {
      const rawLlm = agent.params?.llm;
      let llm: { provider?: string; model?: string } | undefined;
      if (typeof rawLlm === 'string') {
        try { llm = JSON.parse(rawLlm); } catch { llm = undefined; }
      } else if (rawLlm && typeof rawLlm === 'object') {
        llm = rawLlm as { provider?: string; model?: string };
      }
      // Trim + non-blank guard so an empty string in the plan doesn't
      // override the undefined fallback path in BrowserAgentNode (which
      // resolves to the workspace default - better than ""). Same shape
      // as the backend BrowserAgentBudgetGuard: blank == absent.
      const provider = typeof llm?.provider === 'string' ? llm.provider.trim() : '';
      const model = typeof llm?.model === 'string' ? llm.model.trim() : '';
      if (provider && !agent.provider) agent.provider = provider;
      if (model && !agent.model) agent.model = model;
    }
  }

  // Restore data.params for browser_agent so the inspector's read source
  // survives a reload. The importer otherwise rebuilds ONLY paramExpressions
  // and leaves data.params undefined - which reverts every params.llm
  // sub-field (max_steps, session, expected_output_schema, not just
  // provider/model) to its default on reload, and makes handleModelPick spread
  // `...llm` over `{}` (dropping those sub-fields) on the next pick. Parse the
  // object-shaped params back from their JSON-string plan form; scalars pass
  // through unchanged. Only browser_agent reads data.params (classify/guardrail
  // store their model in the top-level fields), so this stays scoped.
  let browserParams: Record<string, unknown> | undefined;
  if (agentType === 'browser_agent' && agent.params && typeof agent.params === 'object') {
    const OBJECT_PARAM_KEYS = ['llm', 'session', 'expected_output_schema'];
    browserParams = {};
    for (const [key, value] of Object.entries(agent.params as Record<string, unknown>)) {
      if (OBJECT_PARAM_KEYS.includes(key) && typeof value === 'string') {
        try {
          browserParams[key] = JSON.parse(value);
        } catch {
          browserParams[key] = value;
        }
      } else {
        browserParams[key] = value;
      }
    }
  }

  // Build paramExpressions including classify/guardrail params
  const paramExpressions = inputToParamExpressions(agent.params);
  if (agent.classifyParams) {
    paramExpressions.classifyParams = agent.classifyParams;
  }
  if (agent.guardrailParams) {
    paramExpressions.guardrailParams = agent.guardrailParams;
  }

  // Create agent node
  const agentNode: Node<BuilderNodeData> = {
    id: nodeId,
    type: nodeType,
    position: agentPosition,
    positionAbsolute: useSavedPosition ? agentPosition : undefined,
    data: {
      id: dataId,
      label: agent.label,
      kind: agentType === 'guardrail' ? 'guardrail'
        : agentType === 'classify' ? 'classify'
        : agentType === 'browser_agent' ? 'browser_agent'
        : 'reasoning',
      agentType: agentType,
      // Agent entity reference fields (for type='agent' only)
      ...(agent.agentConfigId && { agentConfigId: agent.agentConfigId }),
      ...(agent.agentConfigName && { agentConfigName: agent.agentConfigName }),
      ...(agent.agentAvatarUrl && { agentAvatarUrl: agent.agentAvatarUrl }),
      ...(agent.agentConfigId && { withMemory: agent.withMemory ?? true }),
      // Inline config fields
      provider: agent.provider,
      model: agent.model,
      systemPrompt: agent.systemPrompt,
      prompt: agent.prompt,
      temperature: agent.temperature,
      maxTokens: agent.maxTokens,
      maxIterations: agent.maxIterations,
      maxTools: agent.maxTools,
      autoDiscoverTools: !agent.tools || agent.tools.length === 0,
      paramExpressions,
      // browser_agent: restore the structured params so llm sub-fields
      // (max_steps, session, schema) and the ModelPicker display round-trip.
      // `params` is typed `string` on BuilderNodeData for legacy reasons but
      // the runtime shape is the structured Record the inspector reads (same
      // cast the form uses in updateParam) - hence the double cast.
      ...(browserParams && { params: browserParams as unknown as BuilderNodeData['params'] }),
      // Classify-specific properties (support both canonical and legacy names)
      ...((agent.classifyCategories || agent.categories) && {
        classifyCategories: (agent.classifyCategories || agent.categories)?.map((cat, idx) => ({
          id: cat.id || `category-${idx}`,
          label: cat.label,
          description: cat.description,
        })),
      }),
      // Classify-specific: classifyParams from canonical or legacy field
      ...(agentType === 'classify' && (agent.classifyParams || agent.content) && {
        classifyParams: agent.classifyParams || agent.content,
      }),
      // Guardrail-specific properties
      // Backend stores: content (input text), rules ({key: description}), action
      // Frontend expects: guardrailParams (input text), guardrailRules ([{id, type, action, config}])
      ...((agent.guardrailParams || (agentType === 'guardrail' && agent.content)) && {
        guardrailParams: agent.guardrailParams || agent.content,
      }),
      ...((agent.guardrailRules || (agentType === 'guardrail' && agent.rules)) && {
        guardrailRules: agent.guardrailRules
          ? Array.isArray(agent.guardrailRules)
            ? (agent.guardrailRules as any).map((rule: any) => {
                const resolvedType: GuardrailType = GUARDRAIL_TYPE_MAP[rule.type] || rule.type || 'custom';
                return {
                  ...rule,
                  type: resolvedType,
                  config: defaultConfigForType(resolvedType, rule.config),
                };
              })
            : // guardrailRules is an object {key: description} (builder format) - convert to array
              Object.entries(agent.guardrailRules as Record<string, string>).map(([key, desc], idx) => {
                const resolvedType = (GUARDRAIL_TYPE_MAP[key] || 'custom') as GuardrailType;
                return {
                  id: `rule-${idx}`,
                  type: resolvedType,
                  action: agent.action || 'flag',
                  config: defaultConfigForType(resolvedType, { description: desc }),
                };
              })
          : Object.entries(agent.rules || {}).map(([key, desc], idx) => {
              const resolvedType = (GUARDRAIL_TYPE_MAP[key] || 'custom') as GuardrailType;
              return {
                id: `rule-${idx}`,
                type: resolvedType,
                action: agent.action || 'flag',
                config: defaultConfigForType(resolvedType, { description: desc as string }),
              };
            }) as any,
      }),
    },
  };

  return {
    node: agentNode,
    mappings: {
      label: agent.label,
      normalizedLabel: normalizeLabel(agent.label),
    },
    incrementY: !useSavedPosition,
  };
}

/**
 * Create all agent nodes from plan
 */
export function createAgentNodes(
  agents: AgentFromPlan[],
  startX: number,
  startY: number,
  nodeCount: number
): AgentCreationResult {
  const nodes: Node<BuilderNodeData>[] = [];
  const labelToNodeIdMap = new Map<string, string>();
  let currentX = startX;
  let currentY = startY;
  let totalNodes = nodeCount;

  for (const agent of agents) {
    const result = createAgentNode(agent, currentX, currentY);

    nodes.push(result.node);

    // Map labels
    labelToNodeIdMap.set(result.mappings.label, result.node.id);
    if (result.mappings.normalizedLabel && result.mappings.normalizedLabel !== result.mappings.label) {
      labelToNodeIdMap.set(result.mappings.normalizedLabel, result.node.id);
    }

    // Update position
    if (result.incrementY) {
      currentY += NODE_SPACING.y;
      totalNodes++;

      // Move to next column if too many nodes
      if (totalNodes % 5 === 0) {
        currentX += NODE_SPACING.x;
        currentY = INITIAL_POSITION.y;
      }
    }
  }

  return {
    nodes,
    labelToNodeIdMap,
    nextY: currentY,
    nextX: currentX,
  };
}
