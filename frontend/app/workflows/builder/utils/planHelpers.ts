import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { WorkflowPlan } from './workflowPlanTypes';
import { normalizeLabel } from './labelNormalizer';
import { nodeRegistry } from '../registry/nodeRegistry';

// =============================================================================
// POSITION UTILITIES
// =============================================================================

/**
 * Checks if a position object is valid (has numeric, finite x and y values).
 */
export function isValidPosition(
  position: { x?: number; y?: number } | undefined | null
): position is { x: number; y: number } {
  return (
    position !== null &&
    position !== undefined &&
    typeof position.x === 'number' &&
    typeof position.y === 'number' &&
    !isNaN(position.x) &&
    !isNaN(position.y) &&
    isFinite(position.x) &&
    isFinite(position.y)
  );
}

/**
 * Gets a valid position from a node, preferring positionAbsolute over position.
 * Returns null if no valid position is found.
 */
export function getNodePosition(
  node: Node<BuilderNodeData>
): { x: number; y: number } | null {
  // Prefer positionAbsolute only when it has valid values, otherwise fall back to position.
  // positionAbsolute can be { x: NaN, y: NaN } (truthy object) after import when dagre
  // updated position but not positionAbsolute - blindly using || would pick the NaN object.
  const position = isValidPosition(node.positionAbsolute)
    ? node.positionAbsolute
    : node.position;
  // Round to integers to avoid excessive precision in saved plans
  return isValidPosition(position) ? { x: Math.round(position.x), y: Math.round(position.y) } : null;
}

/**
 * Rounds position coordinates to integers.
 * Use this when calculating positions inline to avoid excessive precision.
 */
export function roundPosition(x: number, y: number): { x: number; y: number } {
  return { x: Math.round(x), y: Math.round(y) };
}

// =============================================================================
// OBJECT UTILITIES
// =============================================================================

/**
 * Checks if an object has any keys (non-empty).
 */
export function hasKeys(obj: Record<string, unknown> | undefined | null): boolean {
  return obj !== null && obj !== undefined && Object.keys(obj).length > 0;
}

// =============================================================================
// INPUT CONVERSION
// =============================================================================

/**
 * Converts paramExpressions to templated inputs.
 */
export function convertParamExpressionsToInputs(
  paramExpressions?: Record<string, string>
): Record<string, any> {
  if (!paramExpressions) return {};

  const inputs: Record<string, any> = {};
  for (const [key, value] of Object.entries(paramExpressions)) {
    if (value && value.trim()) {
      inputs[key] = value;
    }
  }
  return inputs;
}

/**
 * Gets condition expression safely.
 * Returns the expression if it exists and is not empty, otherwise a default value.
 */
export function getConditionExpression(
  condition: { expression?: string } | null | undefined,
  defaultValue: string = 'false'
): string {
  if (!condition || !condition.expression) return defaultValue;
  const expr = condition.expression.trim();
  return expr.length > 0 ? expr : defaultValue;
}

// =============================================================================
// CONTROL NODE UTILITIES
// =============================================================================

/**
 * Adds a core node to the plan if it doesn't already exist.
 * If it exists and updateExisting is true, updates missing fields.
 * Returns the core node (existing or new).
 */
export function upsertControlNode(
  plan: WorkflowPlan,
  coreNode: any,
  updateExisting = false
): any {
  if (!plan.cores) {
    plan.cores = [];
  }
  const existing = plan.cores.find((cn: any) => cn.id === coreNode.id);
  if (existing) {
    if (updateExisting) {
      for (const key of Object.keys(coreNode)) {
        if (existing[key] === undefined && coreNode[key] !== undefined) {
          existing[key] = coreNode[key];
        }
      }
    }
    return existing;
  }
  plan.cores.push(coreNode);
  return coreNode;
}

// =============================================================================
// NODE TYPE DETECTION
// All functions delegate to nodeRegistry for centralized type detection.
// =============================================================================

/**
 * Detects if a node is a pure agent node (not classify or guardrail).
 */
export function isAgentNode(node: Node<BuilderNodeData>): boolean {
  // Agent node detection based on kind or id pattern
  const nodeDataId = node.data.id || '';
  return (
    node.data.kind === 'reasoning' ||
    nodeDataId === 'ai-agent' ||
    nodeDataId === 'agent' ||
    nodeDataId.startsWith('ai-agent-') ||
    nodeDataId.startsWith('agent-')
  );
}

/**
 * Detects if a node is a classify node.
 */
export function isClassifyNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isClassifyNode(node);
}

/**
 * Detects if a node is a guardrail node.
 */
export function isGuardrailNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isGuardrailNode(node);
}

/**
 * Detects if a node is a Browser Agent node (agent:browser_agent).
 */
export function isBrowserAgentNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isBrowserAgentNode(node);
}

/**
 * Detects if a node is any type of AI reasoning node (agent, classify, guardrail, browser_agent).
 * All these nodes are stored in the agents array with a type field.
 */
export function isAiReasoningNode(node: Node<BuilderNodeData>): boolean {
  return isAgentNode(node) || isClassifyNode(node) || isGuardrailNode(node) || isBrowserAgentNode(node);
}

/**
 * Gets the agent type for a node.
 *
 * Order matters: browser_agent / classify / guardrail are checked before plain
 * 'agent' so the specialised nodes are not misclassified by the generic fallback.
 */
export function getAgentType(node: Node<BuilderNodeData>): 'agent' | 'classify' | 'guardrail' | 'browser_agent' {
  if (isBrowserAgentNode(node)) return 'browser_agent';
  if (isClassifyNode(node)) return 'classify';
  if (isGuardrailNode(node)) return 'guardrail';
  return 'agent';
}

/**
 * Detects if a node is a Transform node.
 */
export function isTransformNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isTransformNode(node);
}

/**
 * Detects if a node is a Wait node.
 */
export function isWaitNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isWaitNode(node);
}

/**
 * Detects if a node is a Download File node.
 */
export function isDownloadFileNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isDownloadFileNode(node);
}

/**
 * Detects if a node is a Public Link node.
 */
export function isPublicLinkNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isPublicLinkNode(node);
}

/**
 * Detects if a node is a Media node.
 */
export function isMediaNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isMediaNode(node);
}

/**
 * Detects if a node is an HTTP Request node.
 */
export function isHttpRequestNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isHttpRequestNode(node);
}

/**
 * Detects if a node is a Data Input node.
 */
export function isDataInputNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isDataInputNode(node);
}

/**
 * Detects if a node is a Merge node.
 */
export function isMergeNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isMergeNode(node);
}

/**
 * Detects if a node is an Aggregate node.
 */
export function isAggregateNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isAggregateNode(node);
}

/**
 * Detects if a node is an Exit node.
 */
export function isExitNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isExitNode(node);
}

/**
 * Detects if a node is a Response node.
 */
export function isResponseNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isResponseNode(node);
}

/**
 * Detects if a node is an Option node.
 */
export function isOptionNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isOptionNode(node);
}

/**
 * Detects if a node is a Fork node.
 */
export function isForkNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isForkNode(node);
}

/**
 * Detects if a node is a CRUD node.
 */
export function isCrudNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCrudNode(node);
}

/**
 * Detects if a node is a Find node (CRUD table operation that behaves like Split).
 */
export function isFindNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isFindNode(node);
}

/**
 * Detects if a node behaves like a split (parallel per item).
 * Includes SplitNode (core) and FindNode (table CRUD).
 */
export function isSplitLikeNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSplitLikeNode(node);
}

/**
 * Detects if a node is a Filter node.
 */
export function isFilterNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isFilterNode(node);
}

/**
 * Detects if a node is a Set / Edit Fields node.
 */
export function isSetNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSetNode(node);
}

/**
 * Detects if a node is an HTML Extract node.
 */
export function isHtmlExtractNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isHtmlExtractNode(node);
}

/**
 * Detects if a node is a Task node.
 */
export function isTaskNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isTaskNode(node);
}

/**
 * Detects if a node is a Sort node.
 */
export function isSortNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSortNode(node);
}

/**
 * Detects if a node is a Limit node.
 */
export function isLimitNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isLimitNode(node);
}

/**
 * Detects if a node is a RemoveDuplicates node.
 */
export function isRemoveDuplicatesNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isRemoveDuplicatesNode(node);
}

/**
 * Detects if a node is a Summarize node.
 */
export function isSummarizeNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSummarizeNode(node);
}

/**
 * Detects if a node is a DateTime node.
 */
export function isDateTimeNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isDateTimeNode(node);
}

/**
 * Detects if a node is a CryptoJWT node.
 */
export function isCryptoJwtNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCryptoJwtNode(node);
}

/**
 * Detects if a node is an XML node.
 */
export function isXmlNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isXmlNode(node);
}

/**
 * Detects if a node is a Compression node.
 */
export function isCompressionNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCompressionNode(node);
}

/**
 * Detects if a node is an RSS node.
 */
export function isRssNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isRssNode(node);
}

/**
 * Detects if a node is a convert-to-file node.
 */
export function isConvertToFileNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isConvertToFileNode(node);
}

/**
 * Detects if a node is an extract-from-file node.
 */
export function isExtractFromFileNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isExtractFromFileNode(node);
}

/**
 * Detects if a node is a compare-datasets node.
 */
export function isCompareDatasetsNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCompareDatasetsNode(node);
}

/**
 * Detects if a node is a sub-workflow node.
 */
export function isSubWorkflowNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSubWorkflowNode(node);
}

/**
 * Detects if a node is a respond-to-webhook node.
 */
export function isRespondToWebhookNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isRespondToWebhookNode(node);
}

/**
 * Detects if a node is a send-email node.
 */
export function isSendEmailNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSendEmailNode(node);
}

/**
 * Detects if a node is an email-inbox (IMAP) node.
 */
export function isEmailInboxNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isEmailInboxNode(node);
}

/**
 * Detects if a node is a code node.
 */
export function isCodeNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCodeNode(node);
}

/**
 * Detects if a node is a stop_on_error node.
 */
export function isStopOnErrorNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isStopOnErrorNode(node);
}

/**
 * Detects if a node is an SSH node.
 */
export function isSshNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSshNode(node);
}

/**
 * Detects if a node is an SFTP node.
 */
export function isSftpNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isSftpNode(node);
}

/**
 * Detects if a node is a database node.
 */
export function isDatabaseNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isDatabaseNode(node);
}

// =============================================================================
// CONDITION MAPPING
// =============================================================================

/**
 * Maps decisionConditions from node data to plan format.
 * Used when creating/updating cores for decision nodes.
 */
export function mapDecisionConditions(conditions: any[] | undefined): any[] | undefined {
  if (!conditions || conditions.length === 0) return undefined;
  return conditions.map((cond) => ({
    id: cond.id,
    type: cond.type,
    label: cond.label,
    ...(cond.expression && { expression: cond.expression }),
  }));
}

/**
 * Maps switchCases from node data to plan format.
 * Used when creating/updating cores for switch nodes.
 */
export function mapSwitchCases(cases: any[] | undefined): any[] | undefined {
  if (!cases || cases.length === 0) return undefined;
  return cases.map((caseItem: any) => ({
    id: caseItem.id,
    type: caseItem.type,
    label: caseItem.label,
    ...(caseItem.value && { value: caseItem.value }),
  }));
}

