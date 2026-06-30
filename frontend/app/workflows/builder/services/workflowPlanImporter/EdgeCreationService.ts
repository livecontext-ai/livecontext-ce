/**
 * V2 Edge Creation Service
 *
 * Converts V2 plan edges (pure graph model with ports) to React Flow edges.
 *
 * V2 Edge Format:
 * - from: "nodeType:label:port" (e.g., "core:check:if", "core:process:body")
 * - to: "nodeType:label:port" (e.g., "mcp:fetch", "core:process:iterate")
 *
 * Port mappings for sourceHandle:
 * - core: :if -> IF condition handle, :else -> ELSE handle, :elseif_N -> N-th ELSEIF handle
 * - switch: :case_N -> N-th case handle, :default -> default handle
 * - core: :body -> body output handle, :exit -> exit output handle
 *
 * Port mappings for targetHandle:
 * - core: :iterate -> iterate input handle (for loop-back)
 */

import type { Edge, Node } from 'reactflow';
import { MarkerType } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { PlanParserService } from './PlanParserService';
import type { WorkflowPlan, EdgeV2 } from './PlanParserService';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { nodeRegistry } from '../../registry/nodeRegistry';

export interface EdgeCreationResult {
  edges: Edge[];
  nodeUpdates: Map<string, Partial<BuilderNodeData>>;
}

export class EdgeCreationService {
  /**
   * Create all edges from the V2 plan format.
   */
  static createEdges(
    plan: WorkflowPlan,
    nodes: Node<BuilderNodeData>[],
    aliasToNodeIdMap: Map<string, string>,
    triggerIdToNodeIdMap: Map<string, string>,
    interfaceIdToNodeIdMap: Map<string, string>,
    interfaceLabelToNodeIdMap: Map<string, string>
  ): EdgeCreationResult {
    const edges: Edge[] = [];
    const nodeUpdates = new Map<string, Partial<BuilderNodeData>>();

    // Create node map for quick lookup
    const nodeMap = new Map<string, Node<BuilderNodeData>>();
    nodes.forEach((node) => nodeMap.set(node.id, node));

    // Apply params declared on mcps (steps)
    plan.mcps.forEach((step) => {
      const nodeId = aliasToNodeIdMap.get(step.label);
      if (nodeId && step.params && Object.keys(step.params).length > 0) {
        this.updateNodeParamExpressions(nodeId, step.params, nodeUpdates);
      }
    });

    // Process each V2 edge
    plan.edges.forEach((planEdge, index) => {
      const edge = this.processEdge(
        planEdge,
        index,
        nodes,
        nodeMap,
        aliasToNodeIdMap,
        triggerIdToNodeIdMap,
        interfaceIdToNodeIdMap,
        interfaceLabelToNodeIdMap,
        nodeUpdates,
        plan
      );
      if (edge) {
        edges.push(edge);
      }
    });

    // Distribute edges targeting merge nodes across individual input handles
    this.distributeMergeTargetHandles(edges, nodeMap, nodeUpdates);

    // Create tool edges for agents
    this.createToolEdges(plan, nodes, nodeMap, aliasToNodeIdMap, edges);

    // Merge node edges come from the plan's edges[] array directly
    // (no mergeInputs metadata in the plan)

    console.log('[EdgeCreationV2] Created edges:', {
      totalEdges: edges.length,
      edges: edges.map((e) => ({
        id: e.id,
        source: e.source,
        target: e.target,
        sourceHandle: e.sourceHandle,
        targetHandle: e.targetHandle,
      })),
    });

    return { edges, nodeUpdates };
  }

  /**
   * Process a single V2 edge and create a React Flow edge.
   */
  private static processEdge(
    planEdge: EdgeV2,
    index: number,
    nodes: Node<BuilderNodeData>[],
    nodeMap: Map<string, Node<BuilderNodeData>>,
    aliasToNodeIdMap: Map<string, string>,
    triggerIdToNodeIdMap: Map<string, string>,
    interfaceIdToNodeIdMap: Map<string, string>,
    interfaceLabelToNodeIdMap: Map<string, string>,
    nodeUpdates: Map<string, Partial<BuilderNodeData>>,
    plan: WorkflowPlan
  ): Edge | null {
    // Parse from and to refs
    const fromRef = PlanParserService.parseEdgeRef(planEdge.from);
    const toRef = PlanParserService.parseEdgeRef(planEdge.to);

    if (!fromRef || !toRef) {
      console.warn(`[EdgeCreationV2] Invalid edge at index ${index}:`, planEdge);
      return null;
    }

    // Find source node
    const sourceNodeId = this.findNodeId(
      fromRef.nodeType,
      fromRef.nodeLabel,
      nodes,
      aliasToNodeIdMap,
      triggerIdToNodeIdMap,
      interfaceIdToNodeIdMap,
      interfaceLabelToNodeIdMap
    );
    if (!sourceNodeId) {
      console.warn(`[EdgeCreationV2] Source node not found for: ${planEdge.from}`);
      return null;
    }

    // Find target node
    const targetNodeId = this.findNodeId(
      toRef.nodeType,
      toRef.nodeLabel,
      nodes,
      aliasToNodeIdMap,
      triggerIdToNodeIdMap,
      interfaceIdToNodeIdMap,
      interfaceLabelToNodeIdMap
    );
    if (!targetNodeId) {
      console.warn(`[EdgeCreationV2] Target node not found for: ${planEdge.to}`);
      return null;
    }

    const sourceNode = nodeMap.get(sourceNodeId);
    const targetNode = nodeMap.get(targetNodeId);

    // Get source handle based on port (interface nodes use standard handles like other nodes)
    const sourceHandle = this.getSourceHandle(
      sourceNode,
      fromRef.nodeType,
      fromRef.port,
      plan
    );

    // Get target handle based on port
    const targetHandle = this.getTargetHandle(
      targetNode,
      toRef.nodeType,
      toRef.port
    );

    // Detect back-edges: edges targeting a while node's loop-back handle
    const isBackEdge = targetHandle?.endsWith('-loop-back') || planEdge.params?.backEdge === true;

    // Create the edge
    const edge: Edge = {
      id: `edge-${sourceNodeId}-${targetNodeId}-${Date.now()}-${index}`,
      source: sourceNodeId,
      target: targetNodeId,
      sourceHandle,
      targetHandle,
      type: 'builderEdge',
      markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
      data: {
        connectionType: 'bezier',
        ...(isBackEdge && { isBackEdge: true }),
      },
    };

    // Update paramExpressions for target node if params exists
    if (planEdge.params && targetNodeId) {
      this.updateNodeParamExpressions(targetNodeId, planEdge.params, nodeUpdates);
    }

    return edge;
  }

  /**
   * Find a node ID by its type and label.
   */
  private static findNodeId(
    nodeType: string,
    nodeLabel: string,
    nodes: Node<BuilderNodeData>[],
    aliasToNodeIdMap: Map<string, string>,
    triggerIdToNodeIdMap: Map<string, string>,
    interfaceIdToNodeIdMap: Map<string, string>,
    interfaceLabelToNodeIdMap: Map<string, string>
  ): string | null {
    const normalizedLabel = normalizeLabel(nodeLabel) || nodeLabel;

    switch (nodeType) {
      case 'trigger':
        // Check trigger map first
        if (triggerIdToNodeIdMap.has(nodeLabel)) {
          return triggerIdToNodeIdMap.get(nodeLabel) || null;
        }
        if (triggerIdToNodeIdMap.has(normalizedLabel)) {
          return triggerIdToNodeIdMap.get(normalizedLabel) || null;
        }
        // Fallback: find by normalized label
        const triggerNode = nodes.find((n) => {
          if (!nodeRegistry.isTrigger(n)) return false;
          const label = normalizeLabel(n.data.label) || n.data.label;
          return label === normalizedLabel || label === nodeLabel;
        });
        return triggerNode?.id || null;

      case 'mcp':
      case 'table':
      case 'agent':
        // Check alias map
        if (aliasToNodeIdMap.has(nodeLabel)) {
          return aliasToNodeIdMap.get(nodeLabel) || null;
        }
        if (aliasToNodeIdMap.has(normalizedLabel)) {
          return aliasToNodeIdMap.get(normalizedLabel) || null;
        }
        return null;

      case 'core':
      case 'decision':  // Legacy support
      case 'switch':    // Legacy support
      case 'loop':      // Legacy support
      case 'split':     // Split
      case 'merge':     // Legacy support
      case 'fork':      // Legacy support
        // Find any control flow node by normalized label
        // core: is the unified prefix for all control flow nodes
        // Includes: decisionNode, switchNode, loopNode, splitNode, mergeNode, forkNode
        // AND flowNode-based cores: transform, wait, download_file
        const coreNode = nodes.find((n) => {
          // Check if it's a core node using nodeRegistry
          if (nodeRegistry.isCoreNode(n)) {
            const label = normalizeLabel(n.data.label) || '';
            return label === normalizedLabel || n.data.label === nodeLabel;
          }
          return false;
        });
        // Also check aliasToNodeIdMap for control nodes
        if (!coreNode) {
          if (aliasToNodeIdMap.has(nodeLabel)) {
            return aliasToNodeIdMap.get(nodeLabel) || null;
          }
          if (aliasToNodeIdMap.has(normalizedLabel)) {
            return aliasToNodeIdMap.get(normalizedLabel) || null;
          }
          console.warn(`[EdgeCreation] Core node not found: nodeLabel=${nodeLabel}, normalizedLabel=${normalizedLabel}, coreNodes=`, nodes.filter(n => nodeRegistry.isCoreNode(n)).map(n => ({ id: n.id, type: n.type, label: n.data.label })));
        }
        return coreNode?.id || null;

      case 'note':
        // Find note node by label
        const noteNode = nodes.find((n) => {
          if (!nodeRegistry.isNoteNode(n)) return false;
          const label = normalizeLabel(n.data.label) || '';
          return label === normalizedLabel || n.data.label === nodeLabel;
        });
        return noteNode?.id || null;

      case 'interface':
        // Check interface maps first (most reliable)
        if (interfaceIdToNodeIdMap.has(nodeLabel)) {
          console.log(`[EdgeCreationV2] Found interface by ID map: ${nodeLabel} -> ${interfaceIdToNodeIdMap.get(nodeLabel)}`);
          return interfaceIdToNodeIdMap.get(nodeLabel) || null;
        }
        if (normalizedLabel && interfaceLabelToNodeIdMap.has(normalizedLabel)) {
          console.log(`[EdgeCreationV2] Found interface by label map (normalized): ${normalizedLabel} -> ${interfaceLabelToNodeIdMap.get(normalizedLabel)}`);
          return interfaceLabelToNodeIdMap.get(normalizedLabel) || null;
        }
        if (interfaceLabelToNodeIdMap.has(nodeLabel)) {
          console.log(`[EdgeCreationV2] Found interface by label map (raw): ${nodeLabel} -> ${interfaceLabelToNodeIdMap.get(nodeLabel)}`);
          return interfaceLabelToNodeIdMap.get(nodeLabel) || null;
        }
        // Log available maps for debugging
        console.log(`[EdgeCreationV2] Interface lookup failed for: nodeLabel="${nodeLabel}", normalized="${normalizedLabel}"`);
        console.log(`[EdgeCreationV2] Available in interfaceIdToNodeIdMap:`, Array.from(interfaceIdToNodeIdMap.keys()));
        console.log(`[EdgeCreationV2] Available in interfaceLabelToNodeIdMap:`, Array.from(interfaceLabelToNodeIdMap.keys()));
        // Fallback: Find interface node by label using nodeRegistry
        const interfaceNode = nodes.find((n) => {
          if (!nodeRegistry.isInterfaceNode(n)) return false;
          const label = normalizeLabel(n.data.label) || '';
          return label === normalizedLabel || n.data.label === nodeLabel;
        });
        if (interfaceNode) {
          console.log(`[EdgeCreationV2] Found interface by node search: ${interfaceNode.id}`);
        }
        return interfaceNode?.id || null;

      default:
        console.warn(`[EdgeCreationV2] Unknown node type: ${nodeType}`);
        return null;
    }
  }

  /**
   * Get the source handle for an edge based on node type and port.
   */
  private static getSourceHandle(
    sourceNode: Node<BuilderNodeData> | undefined,
    nodeType: string,
    port: string | undefined,
    plan: WorkflowPlan
  ): string | undefined {
    if (!sourceNode) return undefined;

    // core: prefix handles all control flow nodes
    // Decision/Switch node with port (legacy or core: with decision/switch node type)
    if ((nodeType === 'decision' || nodeType === 'switch' || nodeType === 'core') && port) {
      if (sourceNode && (nodeRegistry.isDecisionNode(sourceNode) || nodeRegistry.isSwitchNode(sourceNode))) {
        return this.getDecisionSourceHandle(sourceNode, port);
      }
      // While group node with port - must check BEFORE isLoopNode (which also matches whileGroupNodes)
      if (sourceNode && nodeRegistry.isWhileGroupNode(sourceNode)) {
        return this.getWhileSourceHandle(sourceNode, port);
      }
      // Loop node with port (old loopNode)
      if (sourceNode && nodeRegistry.isLoopNode(sourceNode)) {
        return this.getLoopSourceHandle(sourceNode, port);
      }
    }

    // Loop/While node with port (legacy support)
    if (nodeType === 'loop' && port && sourceNode) {
      if (nodeRegistry.isWhileGroupNode(sourceNode)) {
        return this.getWhileSourceHandle(sourceNode, port);
      }
      return this.getLoopSourceHandle(sourceNode, port);
    }

    // Fork node with port
    if ((nodeType === 'fork' || nodeType === 'core') && sourceNode && nodeRegistry.isForkNode(sourceNode) && port) {
      return this.getForkSourceHandle(sourceNode, port);
    }

    // Classify node with category port (agent:label:category_N)
    if (nodeType === 'agent' && sourceNode && nodeRegistry.isClassifyNode(sourceNode) && port?.startsWith('category_')) {
      return this.getClassifySourceHandle(sourceNode, port);
    }

    // Guardrail node with pass/fail port (agent:label:pass or agent:label:fail)
    if (nodeType === 'agent' && sourceNode && nodeRegistry.isGuardrailNode(sourceNode) && (port === 'pass' || port === 'fail')) {
      return this.getGuardrailSourceHandle(sourceNode, port);
    }

    // Option node with choice port (core:label:choice_N)
    if ((nodeType === 'option' || nodeType === 'core') && sourceNode && nodeRegistry.isOptionNode(sourceNode) && port?.startsWith('choice_')) {
      return this.getOptionSourceHandle(sourceNode, port);
    }

    // User Approval node with approval port (core:label:approved|rejected|timeout|path_N)
    if ((nodeType === 'approval' || nodeType === 'core') && sourceNode && nodeRegistry.isUserApprovalNode(sourceNode) && port) {
      return this.getApprovalSourceHandle(sourceNode, port);
    }

    // Split node - use exit handle format for ancestor detection
    if ((nodeType === 'split' || nodeType === 'core') && sourceNode && nodeRegistry.isSplitNode(sourceNode)) {
      return `split-${sourceNode.id}-exit`;
    }

    // Default for step/agent/trigger nodes
    return this.getDefaultSourceHandle(sourceNode);
  }

  /**
   * Get the source handle for a decision/switch node based on port.
   */
  private static getDecisionSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    // Get conditions from node
    const conditions =
      nodeRegistry.isSwitchNode(node)
        ? ((node.data.switchCases as any[]) || []).map((c, i) => ({
            id: c.id,
            type: i === 0 ? 'if' : c.type === 'default' ? 'else' : 'elseif',
          }))
        : node.data.decisionConditions || [];

    // Map port to condition
    if (port === 'if') {
      const ifCondition = conditions.find((c: any) => c.type === 'if');
      return ifCondition?.id;
    }
    if (port === 'else') {
      const elseCondition = conditions.find((c: any) => c.type === 'else');
      return elseCondition?.id;
    }
    if (port.startsWith('elseif_')) {
      const index = parseInt(port.replace('elseif_', ''), 10);
      const elseifConditions = conditions.filter((c: any) => c.type === 'elseif');
      return elseifConditions[index]?.id;
    }
    if (port.startsWith('case_')) {
      const index = parseInt(port.replace('case_', ''), 10);
      const caseConditions = conditions.filter(
        (c: any) => c.type !== 'else' && c.type !== 'default'
      );
      return caseConditions[index]?.id;
    }
    if (port === 'default') {
      const defaultCondition = conditions.find(
        (c: any) => c.type === 'default' || c.type === 'else'
      );
      return defaultCondition?.id;
    }

    return undefined;
  }

  /**
   * Get the source handle for a loop node based on port.
   */
  private static getLoopSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const loopNodeId = node.id;

    if (port === 'body') {
      return `loop-${loopNodeId}-body`;
    }
    if (port === 'exit') {
      return `loop-${loopNodeId}-exit`;
    }

    return undefined;
  }

  /**
   * Get the source handle for a While group node based on port.
   */
  private static getWhileSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const nodeId = node.id;

    if (port === 'body') {
      return `while-${nodeId}-body`;
    }
    if (port === 'exit') {
      return `while-${nodeId}-exit`;
    }

    return undefined;
  }

  /**
   * Get the source handle for a fork node based on port.
   * Maps branch ports (branch_0, branch_1, etc.) to fork output IDs.
   */
  private static getForkSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const outputs = (node.data as any).forkOutputs || [];

    // Handle branch_N port format
    if (port.startsWith('branch_')) {
      const index = parseInt(port.replace('branch_', ''), 10);
      if (index >= 0 && index < outputs.length) {
        return outputs[index]?.id;
      }
    }

    return undefined;
  }

  /**
   * Get the source handle for a classify node based on port.
   * Maps category ports (category_0, category_1, etc.) to classify category IDs.
   */
  private static getClassifySourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const categories = (node.data as any).classifyCategories || [];

    // Handle category_N port format
    if (port.startsWith('category_')) {
      const index = parseInt(port.replace('category_', ''), 10);
      if (index >= 0 && index < categories.length) {
        return categories[index]?.id;
      }
    }

    return undefined;
  }

  /**
   * Get the source handle for a guardrail node based on port.
   * Maps pass/fail ports to guardrail output handle IDs.
   */
  private static getGuardrailSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    // GuardrailNode.tsx uses hardcoded handle IDs: "pass" and "fail"
    if (port === 'pass' || port === 'fail') {
      return port;
    }
    return undefined;
  }

  /**
   * Get the source handle for an option node based on port.
   * Maps choice ports (choice_0, choice_1, etc.) to option choice IDs.
   */
  private static getOptionSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const choices = (node.data as any).optionChoices || [];

    // Handle choice_N port format
    if (port.startsWith('choice_')) {
      const index = parseInt(port.replace('choice_', ''), 10);
      if (index >= 0 && index < choices.length) {
        return choices[index]?.id;
      }
    }

    return undefined;
  }

  /**
   * Get the source handle for a user approval node based on port.
   * Maps approval ports (approved, rejected, timeout, path_N) to output IDs.
   */
  private static getApprovalSourceHandle(
    node: Node<BuilderNodeData>,
    port: string
  ): string | undefined {
    const outputs = (node.data as any).approvalOutputs || [];

    // Handle well-known port names (approved, rejected, timeout)
    for (let i = 0; i < outputs.length; i++) {
      const label = (outputs[i]?.label || '').toLowerCase().trim();
      if (label === port) {
        return outputs[i]?.id;
      }
    }

    // Handle path_N port format for custom paths
    if (port.startsWith('path_')) {
      const index = parseInt(port.replace('path_', ''), 10);
      if (index >= 0 && index < outputs.length) {
        return outputs[index]?.id;
      }
    }

    return undefined;
  }

  /**
   * Get the target handle for an edge based on node type and port.
   */
  private static getTargetHandle(
    targetNode: Node<BuilderNodeData> | undefined,
    nodeType: string,
    port: string | undefined
  ): string | undefined {
    if (!targetNode) return undefined;

    // Interface nodes use standard left target handle (no explicit handle needed)
    if (nodeType === 'interface') {
      return undefined;
    }

    // While group node - handle entry and loop-back for any nodeType (core or loop)
    if (nodeRegistry.isWhileGroupNode(targetNode)) {
      if (port === 'iterate') {
        return `while-${targetNode.id}-loop-back`;
      }
      return `while-${targetNode.id}-entry`;
    }

    // Old loop node with iterate port
    if (nodeType === 'loop') {
      if (port === 'iterate') {
        return `loop-${targetNode.id}-iterate`;
      }
      return `loop-${targetNode.id}-entry`;
    }

    // Split node
    if (nodeType === 'split') {
      return 'target-left';
    }

    // Default: no explicit handle
    return undefined;
  }

  /**
   * Get default source handle for a node based on its type.
   */
  private static getDefaultSourceHandle(
    sourceNode: Node<BuilderNodeData> | undefined
  ): string | undefined {
    if (!sourceNode || !nodeRegistry.isFlowNode(sourceNode)) return undefined;

    const sourceData = sourceNode.data;
    const sourceId = sourceData?.id || sourceNode.id || '';
    const sourceKind = sourceData?.kind;

    // Interface nodes use standard source-right handle
    const isInterfaceNode =
      sourceId === 'interface' || sourceId.startsWith('interface-');
    if (isInterfaceNode) return 'source-right';

    const dataSourceData = (sourceData as any)?.dataSourceData;
    const isTriggerNode =
      sourceKind === 'entry' ||
      sourceId.startsWith('trigger-') ||
      sourceId.startsWith('trigger:') ||
      sourceId.startsWith('tables-trigger-');
    const isTableNode = !!dataSourceData;
    const isToolLikeNode = !!(sourceData as any).toolData || !!(sourceData as any).apiData;
    const isAiAgentNode =
      sourceId === 'ai-agent' ||
      sourceId === 'agent' ||
      sourceId.startsWith('ai-agent-') ||
      sourceId.startsWith('agent-');
    const hasTopHandle = isTriggerNode || isTableNode || isToolLikeNode || isAiAgentNode;

    // For nodes with top handles, use right handle for flow connections
    if (hasTopHandle) {
      return 'source-right';
    }

    return 'source-right';
  }

  /**
   * Create tool edges for agents (agent -> tool connections).
   */
  private static createToolEdges(
    plan: WorkflowPlan,
    nodes: Node<BuilderNodeData>[],
    nodeMap: Map<string, Node<BuilderNodeData>>,
    aliasToNodeIdMap: Map<string, string>,
    edges: Edge[]
  ): void {
    if (!plan.agents) return;

    for (const agent of plan.agents) {
      if (!agent.tools || agent.tools.length === 0) continue;

      const agentNodeId = aliasToNodeIdMap.get(agent.label);
      if (!agentNodeId) {
        console.warn(`[EdgeCreationV2] Agent node not found for label: ${agent.label}`);
        continue;
      }

      for (const toolRef of agent.tools) {
        const stepLabel = PlanParserService.extractMcpLabel(toolRef);
        const agentLabel = PlanParserService.extractAgentLabel(toolRef);
        const toolLabel = stepLabel || agentLabel;

        if (!toolLabel) {
          console.warn(`[EdgeCreationV2] Invalid tool reference: ${toolRef}`);
          continue;
        }

        const toolNodeId = aliasToNodeIdMap.get(toolLabel);
        if (!toolNodeId) {
          console.warn(`[EdgeCreationV2] Tool node not found for label: ${toolLabel}`);
          continue;
        }

        edges.push({
          id: `edge-${agentNodeId}-${toolNodeId}-tool-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
          source: agentNodeId,
          target: toolNodeId,
          sourceHandle: 'source-bottom-tools',
          targetHandle: 'target-top',
          type: 'builderEdge',
          markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
          data: { connectionType: 'bezier' },
        });
      }
    }
  }

  /**
   * Distribute edges targeting merge nodes across individual input handles.
   * When plan edges target a merge node without specifying a handle,
   * this assigns each edge to a distinct merge input handle.
   * If more edges than existing inputs, expands the merge node's inputs.
   */
  private static distributeMergeTargetHandles(
    edges: Edge[],
    nodeMap: Map<string, Node<BuilderNodeData>>,
    nodeUpdates: Map<string, Partial<BuilderNodeData>>
  ): void {
    // Group edges by merge target node (only those without a targetHandle)
    const mergeEdgesByTarget = new Map<string, Edge[]>();

    for (const edge of edges) {
      const targetNode = nodeMap.get(edge.target);
      if (targetNode && nodeRegistry.isMergeNode(targetNode) && !edge.targetHandle) {
        const existing = mergeEdgesByTarget.get(edge.target) || [];
        existing.push(edge);
        mergeEdgesByTarget.set(edge.target, existing);
      }
    }

    // For each merge node, assign input handles to edges
    for (const [mergeNodeId, mergeEdges] of mergeEdgesByTarget) {
      const mergeNode = nodeMap.get(mergeNodeId);
      if (!mergeNode) continue;

      const currentInputs: Array<{ id: string; label: string }> = [
        ...((mergeNode.data as any)?.mergeInputs || []),
      ];

      // Ensure we have enough inputs for all edges
      while (currentInputs.length < mergeEdges.length) {
        const newIndex = currentInputs.length + 1;
        currentInputs.push({ id: `${mergeNodeId}-input-${newIndex}`, label: '' });
      }

      // Assign target handles sequentially
      for (let i = 0; i < mergeEdges.length; i++) {
        mergeEdges[i].targetHandle = currentInputs[i].id;
      }

      // Update merge node data if we expanded inputs
      const originalLength = ((mergeNode.data as any)?.mergeInputs || []).length;
      if (currentInputs.length > originalLength) {
        const existing = nodeUpdates.get(mergeNodeId) || {};
        nodeUpdates.set(mergeNodeId, {
          ...existing,
          mergeInputs: currentInputs,
        } as any);
      }
    }
  }

  /**
   * Update paramExpressions for a node.
   */
  private static updateNodeParamExpressions(
    nodeId: string,
    params: Record<string, any>,
    nodeUpdates: Map<string, Partial<BuilderNodeData>>
  ): void {
    const existing = nodeUpdates.get(nodeId) || {};
    const existingExpressions = existing.paramExpressions || {};

    const paramExpressions: Record<string, string> = { ...existingExpressions };
    for (const [key, value] of Object.entries(params)) {
      if (typeof value === 'string') {
        paramExpressions[key] = value;
      } else if (value && typeof value === 'object' && 'template' in value) {
        paramExpressions[key] = (value as any).template;
      } else if (value !== undefined && value !== null) {
        paramExpressions[key] = typeof value === 'object'
          ? JSON.stringify(value)
          : String(value);
      }
    }

    nodeUpdates.set(nodeId, {
      ...existing,
      paramExpressions,
    });
  }

  /**
   * Update internal edges for a loop node when its children change.
   */
  public static updateLoopInternalEdges(
    loopNodeId: string,
    childNodes: Node<BuilderNodeData>[],
    existingEdges: Edge[]
  ): Edge[] {
    // Remove existing loop internal edges for this loop
    const filteredEdges = existingEdges.filter((edge) => {
      if (
        edge.data?.isLoopInternal === true &&
        edge.source === loopNodeId &&
        edge.target === loopNodeId
      ) {
        return false;
      }
      if (edge.id.startsWith(`loop-edge-${loopNodeId}-`)) {
        return false;
      }
      return true;
    });

    // Create new internal edges
    const newEdges: Edge[] = [];
    const sortedChildren = [...childNodes].sort((a, b) => {
      const aMatch = a.id.match(/#(\d+)$/);
      const bMatch = b.id.match(/#(\d+)$/);
      const aIndex = aMatch ? parseInt(aMatch[1], 10) : 0;
      const bIndex = bMatch ? parseInt(bMatch[1], 10) : 0;
      return aIndex - bIndex;
    });

    for (let i = 0; i < sortedChildren.length - 1; i++) {
      const currentChild = sortedChildren[i];
      const nextChild = sortedChildren[i + 1];
      const edgeId = `loop-edge-${loopNodeId}-${currentChild.id}-${nextChild.id}`;

      newEdges.push({
        id: edgeId,
        source: loopNodeId,
        target: loopNodeId,
        sourceHandle: `loop-${loopNodeId}-${currentChild.id}-source`,
        targetHandle: `loop-${loopNodeId}-${nextChild.id}-target`,
        type: 'builderEdge',
        markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
        data: {
          connectionType: 'bezier',
          isLoopInternal: true,
        },
      });
    }

    return [...filteredEdges, ...newEdges];
  }
}
