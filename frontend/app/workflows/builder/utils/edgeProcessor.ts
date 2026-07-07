import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { PlanGeneratorContext } from './planGeneratorContext';
import type { EdgeV2 } from './workflowPlanTypes';
import { normalizeLabel, agentKey, tableKey, mcpKey, triggerKey } from './labelNormalizer';
import {
  getNodePosition,
  upsertControlNode,
  isAiReasoningNode,
  mapDecisionConditions,
  mapSwitchCases,
} from './planHelpers';
import { buildEdgeRef } from './edgeRefParser';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * V2 Edge Processor - Pure graph model
 *
 * Generates simple edges with from/to format:
 * - from: source node ref with optional port (e.g., "core:check:if", "core:process:body")
 * - to: target node ref (e.g., "mcp:fetch", "core:check")
 */

/**
 * Process all React Flow edges and generate V2 plan edges.
 */
export function processEdgesV2(ctx: PlanGeneratorContext): void {
  // First pass: register all control nodes (including while nodes)
  registerControlNodes(ctx);

  // Second pass: process all edges
  ctx.edges.forEach((edge) => {
    processEdge(edge, ctx);
  });
}

/**
 * Register all control nodes (decision, switch, loop, split, merge) in the plan.
 */
function registerControlNodes(ctx: PlanGeneratorContext): void {
  ctx.nodes.forEach((node) => {
    const position = getNodePosition(node);
    if (!position) return;

    if (nodeRegistry.isDecisionNode(node)) {
      upsertControlNode(
        ctx.plan,
        {
          id: node.id,
          type: 'decision',
          position,
          label: node.data.label,
          graphNodeId: node.id,
          decisionConditions: mapDecisionConditions(node.data.decisionConditions),
        },
        true
      );
    } else if (nodeRegistry.isSwitchNode(node)) {
      upsertControlNode(
        ctx.plan,
        {
          id: node.id,
          type: 'switch',
          position,
          label: node.data.label,
          graphNodeId: node.id,
          switchExpression: node.data.switchExpression,
          switchCases: mapSwitchCases(node.data.switchCases as any[]),
        },
        true
      );
    } else if (nodeRegistry.isSplitNode(node)) {
      upsertControlNode(
        ctx.plan,
        {
          id: node.id,
          type: 'split',
          position,
          label: node.data.label,
          graphNodeId: node.id,
          list: node.data.list,
          maxItems: node.data.maxItems,
          splitStrategy: node.data.splitStrategy,
        },
        true
      );
    } else if (nodeRegistry.isMergeNode(node)) {
      registerMergeNode(node, position, ctx);
    } else if (nodeRegistry.isForkNode(node)) {
      registerForkNode(node, position, ctx);
    } else if (nodeRegistry.isOptionNode(node)) {
      registerOptionNode(node, position, ctx);
    } else if (nodeRegistry.isUserApprovalNode(node)) {
      registerUserApprovalNode(node, position, ctx);
    } else if (nodeRegistry.isWhileGroupNode(node)) {
      upsertControlNode(
        ctx.plan,
        {
          id: node.id,
          type: 'loop',
          position,
          label: node.data.label,
          graphNodeId: node.id,
          loopCondition: node.data.whileCondition ?? '',
          maxIterations: node.data.maxIterations ?? 10,
        },
        true
      );
    }
  });
}

/**
 * Register a merge node in the plan.
 * Merge connections are stored as edges only - no mergeInputs in the plan.
 */
function registerMergeNode(
  node: Node<BuilderNodeData>,
  position: { x: number; y: number },
  ctx: PlanGeneratorContext
): void {
  // Add merge node to stepLabelMap for edge references
  const normalizedLabel = normalizeLabel(node.data.label || node.id);
  ctx.stepLabelMap.set(node.id, normalizedLabel);

  upsertControlNode(
    ctx.plan,
    {
      id: node.id,
      type: 'merge',
      position,
      label: node.data.label,
      graphNodeId: node.id,
    },
    true
  );
}

/**
 * Register a fork node with computed forkOutputs and targetStep references.
 */
function registerForkNode(
  node: Node<BuilderNodeData>,
  position: { x: number; y: number },
  ctx: PlanGeneratorContext
): void {
  const forkOutputsData = (node.data as any).forkOutputs || [];

  // Compute targetStep for each fork output
  const forkOutputs = forkOutputsData.map((output: any) => {
    const outgoingEdge = ctx.edges.find(
      (e) => e.source === node.id && e.sourceHandle === output.id
    );
    let targetStep: string | undefined;

    if (outgoingEdge) {
      const targetNode = ctx.nodes.find((n) => n.id === outgoingEdge.target);
      if (targetNode) {
        if (ctx.stepLabelMap.has(targetNode.id)) {
          const targetLabel = ctx.stepLabelMap.get(targetNode.id)!;
          if (isAiReasoningNode(targetNode)) {
            targetStep = agentKey(targetLabel) ?? undefined;
          } else {
            const dataSourceData = (targetNode.data as any).dataSourceData;
            targetStep = dataSourceData?.crudOperation
              ? (tableKey(targetLabel) ?? undefined)
              : (mcpKey(targetLabel) ?? undefined);
          }
        } else if (ctx.triggerSlugMap.has(targetNode.id)) {
          targetStep = triggerKey(ctx.triggerSlugMap.get(targetNode.id)) ?? undefined;
        }
      }
    }

    return {
      id: output.id,
      label: output.label || '',
      ...(targetStep && { targetStep }),
    };
  });

  // Add fork node to stepLabelMap for edge references
  const normalizedLabel = normalizeLabel(node.data.label || node.id);
  ctx.stepLabelMap.set(node.id, normalizedLabel);

  upsertControlNode(
    ctx.plan,
    {
      id: node.id,
      type: 'fork',
      position,
      label: node.data.label,
      graphNodeId: node.id,
      ...(forkOutputs.length > 0 && { forkOutputs }),
    },
    true
  );
}

/**
 * Register an option node with computed optionChoices and targetStep references.
 */
function registerOptionNode(
  node: Node<BuilderNodeData>,
  position: { x: number; y: number },
  ctx: PlanGeneratorContext
): void {
  const optionChoicesData = (node.data as any).optionChoices || [];

  // Compute targetStep for each option choice
  const optionChoices = optionChoicesData.map((choice: any) => {
    const outgoingEdge = ctx.edges.find(
      (e) => e.source === node.id && e.sourceHandle === choice.id
    );
    let targetStep: string | undefined;

    if (outgoingEdge) {
      const targetNode = ctx.nodes.find((n) => n.id === outgoingEdge.target);
      if (targetNode) {
        if (ctx.stepLabelMap.has(targetNode.id)) {
          const targetLabel = ctx.stepLabelMap.get(targetNode.id)!;
          if (isAiReasoningNode(targetNode)) {
            targetStep = agentKey(targetLabel) ?? undefined;
          } else {
            const dataSourceData = (targetNode.data as any).dataSourceData;
            targetStep = dataSourceData?.crudOperation
              ? (tableKey(targetLabel) ?? undefined)
              : (mcpKey(targetLabel) ?? undefined);
          }
        } else if (ctx.triggerSlugMap.has(targetNode.id)) {
          targetStep = triggerKey(ctx.triggerSlugMap.get(targetNode.id)) ?? undefined;
        }
      }
    }

    return {
      id: choice.id,
      label: choice.label || '',
      ...(choice.expression && { expression: choice.expression }),
      ...(targetStep && { targetStep }),
    };
  });

  // Add option node to stepLabelMap for edge references
  const normalizedLabel = normalizeLabel(node.data.label || node.id);
  ctx.stepLabelMap.set(node.id, normalizedLabel);

  upsertControlNode(
    ctx.plan,
    {
      id: node.id,
      type: 'option',
      position,
      label: node.data.label,
      graphNodeId: node.id,
      ...(optionChoices.length > 0 && { optionChoices }),
    },
    true
  );
}

/**
 * Register a user approval node with computed approvalOutputs and targetStep references.
 */
function registerUserApprovalNode(
  node: Node<BuilderNodeData>,
  position: { x: number; y: number },
  ctx: PlanGeneratorContext
): void {
  const approvalOutputsData = (node.data as any).approvalOutputs || [];

  // Compute targetStep for each approval output
  const approvalOutputs = approvalOutputsData.map((output: any) => {
    const outgoingEdge = ctx.edges.find(
      (e) => e.source === node.id && e.sourceHandle === output.id
    );
    let targetStep: string | undefined;

    if (outgoingEdge) {
      const targetNode = ctx.nodes.find((n) => n.id === outgoingEdge.target);
      if (targetNode) {
        if (ctx.stepLabelMap.has(targetNode.id)) {
          const targetLabel = ctx.stepLabelMap.get(targetNode.id)!;
          if (isAiReasoningNode(targetNode)) {
            targetStep = agentKey(targetLabel) ?? undefined;
          } else {
            const dataSourceData = (targetNode.data as any).dataSourceData;
            targetStep = dataSourceData?.crudOperation
              ? (tableKey(targetLabel) ?? undefined)
              : (mcpKey(targetLabel) ?? undefined);
          }
        } else if (ctx.triggerSlugMap.has(targetNode.id)) {
          targetStep = triggerKey(ctx.triggerSlugMap.get(targetNode.id)) ?? undefined;
        }
      }
    }

    return {
      id: output.id,
      label: output.label || '',
      ...(targetStep && { targetStep }),
    };
  });

  // Add approval node to stepLabelMap for edge references
  const normalizedLabel = normalizeLabel(node.data.label || node.id);
  ctx.stepLabelMap.set(node.id, normalizedLabel);

  // Rebuild the full approval config so passthrough fields (approverRoles,
  // requiredApprovals, contextTemplate) written by the agent survive the round-trip.
  // Backend WorkflowPlanParser.parseApprovalConfig reads them all from the same map.
  const data = node.data as any;
  const approverRoles: string[] | undefined = Array.isArray(data.approverRoles)
    ? data.approverRoles.filter((r: unknown): r is string => typeof r === 'string')
    : undefined;
  const requiredApprovals: number | undefined = typeof data.requiredApprovals === 'number'
    ? data.requiredApprovals
    : undefined;
  const timeoutMs: number | undefined = typeof data.approvalTimeoutMs === 'number'
    ? data.approvalTimeoutMs
    : undefined;
  const contextTemplate: string | undefined = typeof data.approvalContextTemplate === 'string'
    && data.approvalContextTemplate.trim() !== ''
    ? data.approvalContextTemplate
    : undefined;

  // Optional external-channel delegation (v1: telegram). Emitted only when the
  // author actually selected a channel; a toggled-off section leaves node data
  // without approvalDelegation and the plan without approval.delegation.
  const rawDelegation = data.approvalDelegation;
  let delegation: Record<string, unknown> | undefined;
  if (rawDelegation && typeof rawDelegation === 'object'
    && typeof rawDelegation.channel === 'string' && rawDelegation.channel.trim() !== '') {
    delegation = { channel: rawDelegation.channel };
    // Tolerate a numeric-string credentialId ("40") in node data: emit a NUMBER
    // (the plan contract), drop only true non-numerics. Mirrors the importer.
    const rawCredentialId = rawDelegation.credentialId;
    const credentialId = typeof rawCredentialId === 'number'
      ? rawCredentialId
      : typeof rawCredentialId === 'string' && rawCredentialId.trim() !== ''
        ? Number(rawCredentialId)
        : Number.NaN;
    if (Number.isFinite(credentialId)) delegation.credentialId = credentialId;
    if (typeof rawDelegation.chatId === 'string' && rawDelegation.chatId.trim() !== '') {
      delegation.chatId = rawDelegation.chatId;
    }
    if (typeof rawDelegation.messageTemplate === 'string' && rawDelegation.messageTemplate.trim() !== '') {
      delegation.messageTemplate = rawDelegation.messageTemplate;
    }
    const allowedUserIds = Array.isArray(rawDelegation.allowedUserIds)
      ? rawDelegation.allowedUserIds.filter((id: unknown): id is string => typeof id === 'string' && id.trim() !== '')
      : [];
    if (allowedUserIds.length > 0) delegation.allowedUserIds = allowedUserIds;
  }

  const approvalBlock: Record<string, unknown> = {};
  if (approverRoles && approverRoles.length > 0) approvalBlock.approverRoles = approverRoles;
  if (requiredApprovals !== undefined) approvalBlock.requiredApprovals = requiredApprovals;
  if (timeoutMs !== undefined) approvalBlock.timeoutMs = timeoutMs;
  if (contextTemplate !== undefined) approvalBlock.contextTemplate = contextTemplate;
  if (delegation !== undefined) approvalBlock.delegation = delegation;

  upsertControlNode(
    ctx.plan,
    {
      id: node.id,
      type: 'approval',
      position,
      label: node.data.label,
      graphNodeId: node.id,
      ...(approvalOutputs.length > 0 && { approvalOutputs }),
      ...(Object.keys(approvalBlock).length > 0 && { approval: approvalBlock }),
    },
    true
  );
}

/**
 * Process a single React Flow edge and add to plan.
 */
function processEdge(edge: Edge, ctx: PlanGeneratorContext): void {
  const sourceNode = ctx.nodes.find((n) => n.id === edge.source);
  const targetNode = ctx.nodes.find((n) => n.id === edge.target);

  // Skip tool edges (agent tools)
  if (isToolEdge(edge)) {
    return;
  }

  const from = buildFromRef(sourceNode, edge.sourceHandle, ctx);
  const to = buildToRef(targetNode, edge.targetHandle, ctx);

  if (!from || !to || from === to) {
    return;
  }

  // V2: Edges are pure connections (from/to only)
  // Input is stored in the step, not in the edge
  const planEdge: EdgeV2 = {
    from,
    to,
  };

  // Serialize back-edge metadata if present
  if (edge.data?.isBackEdge) {
    planEdge.params = {
      backEdge: true,
      condition: edge.data.backEdgeCondition || '',
      maxIterations: edge.data.backEdgeMaxIterations || 10,
    };
  }

  // Deduplicate: skip if an edge with the same from/to already exists
  const isDuplicate = ctx.plan.edges.some(e => e.from === planEdge.from && e.to === planEdge.to);
  if (!isDuplicate) {
    ctx.plan.edges.push(planEdge);
  }
}

/**
 * Check if edge is a tool connection (agent → tool).
 */
function isToolEdge(edge: Edge): boolean {
  return (
    edge.sourceHandle === 'source-bottom-tools' ||
    edge.sourceHandle === 'source-bottom-1' ||
    edge.sourceHandle === 'source-bottom-2'
  );
}

/**
 * Build the 'from' reference for an edge.
 * Includes port for decision/switch/loop nodes based on sourceHandle.
 */
function buildFromRef(
  sourceNode: Node<BuilderNodeData> | undefined,
  sourceHandle: string | null | undefined,
  ctx: PlanGeneratorContext
): string | null {
  if (!sourceNode) return null;

  const nodeType = getNodeTypeWithContext(sourceNode, ctx);
  const nodeLabel = getNodeLabel(sourceNode, ctx);

  if (!nodeLabel) return null;

  // For decision nodes, extract condition port from sourceHandle
  if (nodeRegistry.isDecisionNode(sourceNode) && sourceHandle) {
    const port = getDecisionPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For switch nodes, extract case port from sourceHandle
  if (nodeRegistry.isSwitchNode(sourceNode) && sourceHandle) {
    const port = getSwitchPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For fork nodes, extract branch port from sourceHandle
  if (nodeRegistry.isForkNode(sourceNode) && sourceHandle) {
    const port = getForkPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For option nodes, extract choice port from sourceHandle
  if (nodeRegistry.isOptionNode(sourceNode) && sourceHandle) {
    const port = getOptionPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For user approval nodes, extract approval port from sourceHandle
  if (nodeRegistry.isUserApprovalNode(sourceNode) && sourceHandle) {
    const port = getApprovalPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For while nodes, extract body/exit port from sourceHandle
  if (nodeRegistry.isWhileGroupNode(sourceNode) && sourceHandle) {
    const port = getWhilePort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('core', nodeLabel, port);
    }
  }

  // For guardrail nodes, extract pass/fail port from sourceHandle
  if (nodeRegistry.isGuardrailNode(sourceNode) && sourceHandle) {
    const port = getGuardrailPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('agent', nodeLabel, port);
    }
  }

  // For classify nodes, extract category port from sourceHandle
  if (nodeRegistry.isClassifyNode(sourceNode) && sourceHandle) {
    const port = getClassifyPort(sourceNode, sourceHandle);
    if (port) {
      return buildEdgeRef('agent', nodeLabel, port);
    }
  }

  // Standard node reference (no port)
  return buildEdgeRef(nodeType, nodeLabel);
}

/**
 * Build the 'to' reference for an edge.
 * Includes port for core:iterate when targeting loop entry.
 */
function buildToRef(
  targetNode: Node<BuilderNodeData> | undefined,
  targetHandle: string | null | undefined,
  ctx: PlanGeneratorContext
): string | null {
  if (!targetNode) return null;

  // For interface nodes, get the label from interfaceNodeIdMap
  if (nodeRegistry.isInterfaceNode(targetNode)) {
    const interfaceInfo = ctx.interfaceNodeIdMap.get(targetNode.id);
    if (interfaceInfo) {
      return buildEdgeRef('interface', normalizeLabel(interfaceInfo.label));
    }
    // Fallback: use node label
    const label = targetNode.data.label || targetNode.id;
    return buildEdgeRef('interface', normalizeLabel(label));
  }

  const nodeType = getNodeTypeWithContext(targetNode, ctx);
  const nodeLabel = getNodeLabel(targetNode, ctx);

  if (!nodeLabel) return null;

  // While node loop-back handle → port "iterate"
  if (nodeRegistry.isWhileGroupNode(targetNode) && targetHandle?.endsWith('-loop-back')) {
    return buildEdgeRef(nodeType, nodeLabel, 'iterate');
  }

  // Standard node reference (no port)
  return buildEdgeRef(nodeType, nodeLabel);
}

/**
 * Get the node type for edge reference using context maps.
 * This is the authoritative way to determine node types since
 * triggerSlugMap and stepLabelMap are populated during plan generation.
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix     | Category  | Applies To                                              |
 * |------------|-----------|--------------------------------------------------------|
 * | trigger:   | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:       | MCP       | Tools (MCP tool calls)                                  |
 * | table:     | Table     | CRUD operations (database tables)                       |
 * | agent:     | AI        | Agent, Guardrail, Classify                              |
 * | core:      | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval |
 * | note:      | Note      | Notes                                                   |
 * | interface: | Interface | Interfaces                                              |
 */
function getNodeTypeWithContext(
  node: Node<BuilderNodeData>,
  ctx: PlanGeneratorContext
): 'trigger' | 'mcp' | 'table' | 'agent' | 'core' | 'note' | 'interface' {
  // Priority 1: Check context maps (authoritative source)
  if (ctx.triggerSlugMap.has(node.id)) {
    return 'trigger';
  }

  // Priority 2: Check using nodeRegistry for triggers
  if (nodeRegistry.isTrigger(node)) {
    return 'trigger';
  }

  // Priority 3: Check using nodeRegistry for control/core nodes
  // nodeRegistry.isControlNode() handles node.type, node.data.id, AND node.data.kind
  if (nodeRegistry.isControlNode(node)) {
    return 'core';
  }

  // Priority 4: Check stepLabelMap - if node is registered as step, check type
  if (ctx.stepLabelMap.has(node.id)) {
    if (isAiReasoningNode(node)) {
      return 'agent';
    }
    // Check if it's a CRUD node (table operation)
    const dataSourceData = (node.data as any).dataSourceData;
    if (dataSourceData?.crudOperation) {
      return 'table';
    }
    return 'mcp';
  }

  // Priority 5: Check for agents (flowNode with reasoning/guardrail/classify kind or agent- prefix)
  if (nodeRegistry.isFlowNode(node)) {
    if (node.data.kind === 'reasoning' || node.data.kind === 'guardrail' || node.data.kind === 'classify' || node.id?.startsWith('agent-')) {
      return 'agent';
    }
  }

  // Priority 6: Check node.data.kind for additional trigger detection
  if (node.data.kind === 'entry') {
    return 'trigger';
  }

  // Priority 7: Check for datasource triggers (NOT CRUD steps)
  // CRUD steps have dataSourceData.crudOperation, datasource triggers don't
  const dataSourceData = (node.data as any).dataSourceData;
  if (dataSourceData && !dataSourceData.crudOperation) {
    return 'trigger';
  }

  // Priority 8: Check for interface nodes
  if (ctx.interfaceNodeIdMap.has(node.id) || nodeRegistry.isInterfaceNode(node)) {
    return 'interface';
  }

  // Default: mcp (tool call)
  return 'mcp';
}

/**
 * Get the normalized label for a node.
 */
function getNodeLabel(
  node: Node<BuilderNodeData>,
  ctx: PlanGeneratorContext
): string | null {
  // Check if we have a pre-computed label in the maps
  if (ctx.stepLabelMap.has(node.id)) {
    return ctx.stepLabelMap.get(node.id)!;
  }

  if (ctx.triggerSlugMap.has(node.id)) {
    return ctx.triggerSlugMap.get(node.id)!;
  }

  // Compute from node data
  if (node.data.label) {
    return normalizeLabel(node.data.label);
  }

  return normalizeLabel(node.id);
}

/**
 * Get the port for a decision node based on sourceHandle.
 * Maps condition IDs to port names (if, else, elseif_0, etc.)
 */
function getDecisionPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const conditions = node.data.decisionConditions || [];

  for (let i = 0; i < conditions.length; i++) {
    const condition = conditions[i];
    if (condition.id === sourceHandle || sourceHandle.includes(condition.id)) {
      // Map condition type to port name
      if (condition.type === 'if') return 'if';
      if (condition.type === 'else') return 'else';
      if (condition.type === 'elseif') {
        // Find the index among elseif conditions
        const elseifIndex = conditions
          .filter((c) => c.type === 'elseif')
          .findIndex((c) => c.id === condition.id);
        return `elseif_${elseifIndex >= 0 ? elseifIndex : i}`;
      }
    }
  }

  return null;
}

/**
 * Get the port for a switch node based on sourceHandle.
 * Maps case IDs to port names (case_0, case_1, default, etc.)
 */
function getSwitchPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const cases = (node.data.switchCases as any[]) || [];

  for (let i = 0; i < cases.length; i++) {
    const switchCase = cases[i];
    if (switchCase.id === sourceHandle || sourceHandle.includes(switchCase.id)) {
      if (switchCase.type === 'default') return 'default';
      return `case_${i}`;
    }
  }

  return null;
}

/**
 * Get the port for a fork node based on sourceHandle.
 * Maps fork output IDs to port names (branch_0, branch_1, etc.)
 */
function getForkPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const outputs = (node.data.forkOutputs as any[]) || [];

  for (let i = 0; i < outputs.length; i++) {
    const output = outputs[i];
    if (output.id === sourceHandle || sourceHandle.includes(output.id)) {
      return `branch_${i}`;
    }
  }

  return null;
}

/**
 * Get the port for an option node based on sourceHandle.
 * Maps option choice IDs to port names (choice_0, choice_1, etc.)
 */
function getOptionPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const choices = ((node.data as any).optionChoices as any[]) || [];

  for (let i = 0; i < choices.length; i++) {
    const choice = choices[i];
    if (choice.id === sourceHandle || sourceHandle.includes(choice.id)) {
      return `choice_${i}`;
    }
  }

  return null;
}

/**
 * Get the port for a user approval node based on sourceHandle.
 * Maps approval output IDs to port names (approved, rejected, timeout, or path_N)
 */
function getApprovalPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const outputs = ((node.data as any).approvalOutputs as any[]) || [];

  for (let i = 0; i < outputs.length; i++) {
    const output = outputs[i];
    if (output.id === sourceHandle || sourceHandle.includes(output.id)) {
      // Use the normalized label as port name for well-known ports
      const label = (output.label || '').toLowerCase().trim();
      if (label === 'approved') return 'approved';
      if (label === 'rejected') return 'rejected';
      if (label === 'timeout') return 'timeout';
      // For custom paths, use indexed port
      return `path_${i}`;
    }
  }

  return null;
}

/**
 * Get the port for a classify node based on sourceHandle.
 * Maps category IDs to port names (category_0, category_1, etc.)
 */
function getClassifyPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  const categories = ((node.data as any).classifyCategories as any[]) || [];

  for (let i = 0; i < categories.length; i++) {
    const category = categories[i];
    if (category.id === sourceHandle || sourceHandle.includes(category.id)) {
      return `category_${i}`;
    }
  }

  return null;
}

/**
 * Get the port for a guardrail node based on sourceHandle.
 * GuardrailNode.tsx uses hardcoded handle IDs: "pass" and "fail".
 */
function getGuardrailPort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  if (sourceHandle === 'pass' || sourceHandle.endsWith('-pass')) return 'pass';
  if (sourceHandle === 'fail' || sourceHandle.endsWith('-fail')) return 'fail';
  return null;
}

/**
 * Get the port for a while node based on sourceHandle.
 * Maps handle IDs to port names (body, exit).
 *
 * Handle format: while-{nodeId}-body or while-{nodeId}-exit
 */
function getWhilePort(
  node: Node<BuilderNodeData>,
  sourceHandle: string
): string | null {
  if (sourceHandle.endsWith('-body')) return 'body';
  if (sourceHandle.endsWith('-exit')) return 'exit';
  return null;
}

// NOTE: getEdgeInput was removed - inputs are now stored in steps only, not in edges
