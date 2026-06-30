/**
 * Layout Service - Automatic node positioning using Dagre
 *
 * Applies Dagre layout algorithm to position workflow nodes automatically.
 * Used for:
 * - LLM-created workflows (no positions in plan)
 * - "Auto-layout" button (reorganize existing workflow)
 */

import dagre from '@dagrejs/dagre';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Layout configuration
 */
const LAYOUT_CONFIG = {
  // Dagre graph direction
  rankdir: 'LR' as 'TB' | 'BT' | 'LR' | 'RL', // Left to Right (horizontal layout)

  // Node spacing - kept tight for a compact graph (size-aware dims already
  // guarantee no overlap, so the gap is pure breathing room, not safety margin).
  nodesep: 40,    // Vertical space between nodes in same rank (also the stack gap between independent components)
  ranksep: 90,    // Horizontal space between ranks

  // Edge spacing
  edgesep: 30,    // Space between edges

  // Node dimensions (will be updated based on actual node size)
  nodeWidth: 200,
  nodeHeight: 80,

  // Alignment (undefined = dagre centers nodes naturally)
  align: 'UL' as 'UL' | 'UR' | 'DL' | 'DR' | undefined,

  // Use deterministic LABEL ESTIMATES, never ReactFlow-measured dims. Both the
  // builder and the fleet lay out from estimates on the path that matters: plan
  // import, agent plan-sync, and first paint all run BEFORE nodes are measured.
  // If the auto-layout BUTTON then used measured dims it would produce a different
  // layout → the graph visibly "jumps" even though it's already auto-laid-out.
  // Estimate-only keeps the button idempotent with the automatic layout.
  ignoreMeasured: true,
};

/**
 * Apply Dagre layout to nodes and edges
 *
 * @param nodes - ReactFlow nodes
 * @param edges - ReactFlow edges
 * @param options - Optional layout configuration overrides
 * @returns Nodes with calculated positions
 */
export function applyDagreLayout(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  options?: Partial<typeof LAYOUT_CONFIG>
): Node<BuilderNodeData>[] {
  const config = { ...LAYOUT_CONFIG, ...options };
  if (nodes.length === 0) return nodes;

  // Independent sub-graphs (e.g. several trigger chains in one workflow) otherwise
  // share Dagre's GLOBAL rank grid: a wide node in one chain pushes that column for
  // EVERY chain, and short chains leave large gaps - the sparse look in multi-trigger
  // workflows. Lay each connected component out on its own, then stack them tightly
  // perpendicular to the flow. A single connected workflow (the common case) takes
  // the fast path and is unchanged.
  const components = connectedComponents(nodes, edges);
  if (components.length <= 1) {
    return layoutConnectedGraph(nodes, edges, config);
  }

  // Preserve the author's lane order: order components by the smallest existing
  // coordinate (perpendicular to flow) among their nodes. NaN positions (LLM build,
  // no positions yet) sort after positioned ones, keeping a stable discovery order.
  const discoveryIndex = new Map(nodes.map((n, i) => [n.id, i]));
  const perpAxis: 'x' | 'y' = (config.rankdir === 'TB' || config.rankdir === 'BT') ? 'x' : 'y';
  const componentSortKey = (comp: Set<string>): number => {
    let bestCoord = Infinity;
    let firstSeen = Infinity;
    for (const node of nodes) {
      if (!comp.has(node.id)) continue;
      firstSeen = Math.min(firstSeen, discoveryIndex.get(node.id)!);
      const coord = node.position?.[perpAxis];
      if (typeof coord === 'number' && isFinite(coord)) bestCoord = Math.min(bestCoord, coord);
    }
    return bestCoord !== Infinity ? bestCoord : 1e9 + firstSeen;
  };
  const ordered = [...components].sort((a, b) => componentSortKey(a) - componentSortKey(b));

  const STACK_GAP = config.nodesep; // gap between stacked independent components
  const result: Node<BuilderNodeData>[] = [];
  let offset = 0;
  for (const comp of ordered) {
    const compNodes = nodes.filter((n) => comp.has(n.id));
    const compEdges = edges.filter((e) => comp.has(e.source) && comp.has(e.target));
    const laid = layoutConnectedGraph(compNodes, compEdges, config);

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of laid) {
      const { width, height } = getNodeDimensions(n, config.ignoreMeasured);
      minX = Math.min(minX, n.position.x);
      minY = Math.min(minY, n.position.y);
      maxX = Math.max(maxX, n.position.x + width);
      maxY = Math.max(maxY, n.position.y + height);
    }

    // Reset each component to the origin on the flow axis, and offset it on the
    // perpendicular axis so components stack without overlapping.
    const dx = perpAxis === 'x' ? offset - minX : -minX;
    const dy = perpAxis === 'y' ? offset - minY : -minY;
    for (const n of laid) {
      const p = { x: n.position.x + dx, y: n.position.y + dy };
      n.position = p;
      n.positionAbsolute = p;
    }
    offset += (perpAxis === 'x' ? (maxX - minX) : (maxY - minY)) + STACK_GAP;
    result.push(...laid);
  }

  console.log(`[Layout] Applied Dagre layout to ${result.length} nodes across ${components.length} components`);
  return result;
}

/**
 * Connected components over the undirected edge graph (ALL edges, loop-backs
 * included - a while body and its while node belong to the same component).
 * Union-find with path halving.
 */
function connectedComponents(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
): Set<string>[] {
  const parent = new Map<string, string>();
  nodes.forEach((n) => parent.set(n.id, n.id));
  const find = (x: string): string => {
    let root = x;
    while (parent.get(root) !== root) {
      parent.set(root, parent.get(parent.get(root)!)!); // path halving
      root = parent.get(root)!;
    }
    return root;
  };
  const union = (a: string, b: string) => {
    const ra = find(a), rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  };
  edges.forEach((e) => {
    if (parent.has(e.source) && parent.has(e.target)) union(e.source, e.target);
  });
  const groups = new Map<string, Set<string>>();
  nodes.forEach((n) => {
    const root = find(n.id);
    if (!groups.has(root)) groups.set(root, new Set());
    groups.get(root)!.add(n.id);
  });
  return [...groups.values()];
}

/**
 * Lay out a single (connected) graph with Dagre and return ReactFlow-positioned
 * nodes. Loop-back edges are skipped (cycles); While exit edges are rerouted from
 * the last body node so the exit lands after the body chain.
 */
function layoutConnectedGraph(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  config: typeof LAYOUT_CONFIG,
): Node<BuilderNodeData>[] {
  const dagreGraph = new dagre.graphlib.Graph();
  dagreGraph.setDefaultEdgeLabel(() => ({}));

  const graphConfig: Record<string, any> = {
    rankdir: config.rankdir,
    nodesep: config.nodesep,
    ranksep: config.ranksep,
    edgesep: config.edgesep,
  };
  if (config.align !== undefined) {
    graphConfig.align = config.align;
  }
  dagreGraph.setGraph(graphConfig);

  nodes.forEach((node) => {
    const { width, height } = getNodeDimensions(node, config.ignoreMeasured);
    dagreGraph.setNode(node.id, { width, height });
  });

  const loopBackEdges = edges.filter((e) => e.targetHandle?.endsWith('-loop-back'));

  edges.forEach((edge) => {
    if (edge.targetHandle?.endsWith('-loop-back')) return;

    if (edge.sourceHandle?.endsWith('-exit')) {
      const whileNode = nodes.find((n) => n.id === edge.source && nodeRegistry.isWhileGroupNode(n));
      if (whileNode) {
        const lastBodyId = loopBackEdges.find((lb) => lb.target === whileNode.id)?.source;
        if (lastBodyId) {
          dagreGraph.setEdge(lastBodyId, edge.target);
          return;
        }
      }
    }

    dagreGraph.setEdge(edge.source, edge.target);
  });

  dagre.layout(dagreGraph);

  return nodes.map((node) => {
    const nodeWithPosition = dagreGraph.node(node.id);
    if (!nodeWithPosition) {
      console.warn(`[Layout] Node ${node.id} not found in dagre graph`);
      return node;
    }
    // Dagre returns center position, ReactFlow uses top-left
    const x = nodeWithPosition.x - nodeWithPosition.width / 2;
    const y = nodeWithPosition.y - nodeWithPosition.height / 2;
    return { ...node, position: { x, y }, positionAbsolute: { x, y } };
  });
}

// ─── Label-based width estimation ───
// FlowNode/NodeHeader (components/nodes/shared.tsx) renders the label in a
// single-line `truncate` <p> inside a node with `minWidth:200` and NO max-width,
// so the node grows horizontally to fit the full label (white-space: nowrap).
// Long labels - e.g. "List Channel Members (youtube.channel-memberships.creator)"
// - therefore render far wider than the legacy hardcoded 200px, which made Dagre
// pack the next column on top of them. These constants approximate that DOM width.
const CHAR_WIDTH_PX = 7.5;        // avg px per char at text-sm (14px); err slightly wide so the pre-measure estimate never under-spaces
const NODE_CHROME_PX = 96;        // px-5 (40) + icon h-11/w-11 (44) + gap-3 (12)
const ESTIMATE_MAX_WIDTH = 900;   // guard against pathological labels; raised so long builder labels (uncapped nodes) don't under-space → no overlap

// ─── Row-based height estimation ───
// Branching nodes (Decision/Switch/Fork/Merge/Classify/Option/UserApproval/Guardrail)
// all render ONE port row per condition/case/branch under the header - same DOM in
// every component: `px-5 py-4` container + h-11 header + `mt-4 space-y-2` list of
// `border px-3 py-2` rows. Their rendered height grows linearly with the row count;
// a fixed per-type height fed Dagre ~80px for a 5-case switch (~320px real), so the
// next lane was packed ON TOP of any node with 3+ rows.
const ROW_NODE_BASE_PX = 96;      // py-4 (32) + h-11 header (44) + mt-4 (16) + border-2 (4)
const ROW_HEIGHT_PX = 38;         // row: py-2 (16) + ~19px content line + border (2); err slightly tall
const ROW_GAP_PX = 8;             // space-y-2 between rows

function estimateRowNodeHeight(rowCount: number): number {
  return ROW_NODE_BASE_PX + rowCount * ROW_HEIGHT_PX + Math.max(0, rowCount - 1) * ROW_GAP_PX;
}

/**
 * Number of port rows a branching node renders, or null for non-row node types.
 * Mirrors each component's data source AND its createDefault* fallback row count
 * (DecisionNode → decisionConditions, SwitchNode → switchCases, etc.).
 */
function getPortRowCount(node: Node<BuilderNodeData>): number | null {
  const data = node.data as any;
  if (nodeRegistry.isDecisionNode(node)) return data.decisionConditions?.length ?? 2;     // if/else
  if (nodeRegistry.isSwitchNode(node)) return data.switchCases?.length ?? 3;              // case×2 + default
  if (nodeRegistry.isForkNode(node)) return data.forkOutputs?.length ?? 2;
  if (nodeRegistry.isMergeNode(node)) return data.mergeInputs?.length ?? 2;
  if (nodeRegistry.isClassifyNode(node)) return data.classifyCategories?.length ?? 2;
  if (nodeRegistry.isOptionNode(node)) return data.optionChoices?.length ?? 2;
  if (nodeRegistry.isUserApprovalNode(node)) return data.approvalOutputs?.length ?? 3;    // approved/rejected/timeout
  if (nodeRegistry.isGuardrailNode(node)) return 2;                                       // fixed pass/fail rows
  return null;
}

/**
 * Estimate the rendered width of a label-driven node when ReactFlow has not yet
 * measured it (import, plan-sync, fleet first build). Floored at `minWidth`
 * (the node type's intrinsic minimum) so short labels keep their base size, and
 * capped to avoid runaway widths on absurd labels.
 *
 * Exported so the fleet layout (useAgentFleetState.applyFleetLayout) shares the
 * exact same heuristic instead of re-deriving it.
 */
export function estimateNodeWidth(label: string | undefined | null, minWidth = 200): number {
  const len = (label ?? '').length;
  return Math.min(ESTIMATE_MAX_WIDTH, Math.max(minWidth, NODE_CHROME_PX + len * CHAR_WIDTH_PX));
}

/** A ReactFlow-measured dimension is a finite positive number (null/undefined until rendered). */
function isMeasured(value: number | null | undefined): value is number {
  return typeof value === 'number' && isFinite(value) && value > 0;
}

/**
 * Get node dimensions for layout.
 *
 * Priority:
 *  1. Interface preview nodes → their explicit preview dimensions (set before render).
 *  2. ReactFlow-measured `node.width`/`node.height` - ONLY when `ignoreMeasured` is
 *     false. Both the builder and fleet pass true (deterministic, idempotent layout),
 *     so in practice this branch is for callers that explicitly want pixel-accuracy.
 *  3. Branching nodes → row-aware height (one row per condition/case/branch).
 *  4. Estimate → type-based base height + label-driven width (the default path).
 */
export function getNodeDimensions(
  node: Node<BuilderNodeData>,
  ignoreMeasured = false,
): { width: number; height: number } {
  const data = node.data as any;

  // 1) Interface nodes in preview mode use their actual preview dimensions.
  // Check both isPreviewMode (set by usePreparedGraph at render time) and
  // interfaceData.showPreview (set during import, before usePreparedGraph runs).
  if (nodeRegistry.isInterfaceNode(node)) {
    const interfaceData = data.interfaceData;
    const isPreview = data.isPreviewMode ?? (interfaceData?.showPreview !== false);
    if (isPreview) {
      return {
        width: interfaceData?.previewWidth || 400,
        height: interfaceData?.previewHeight || 300,
      };
    }
  }

  // 2) Measured dimensions are the source of truth once ReactFlow has rendered
  // the node (auto-layout button path). Use them verbatim - UNLESS the caller
  // wants a deterministic layout (fleet), in which case fall through to estimates.
  if (!ignoreMeasured && isMeasured(node.width) && isMeasured(node.height)) {
    return { width: node.width, height: node.height };
  }

  // Resizable nodes carry an explicit stored size even before measure - use it
  // (their width is the box the user dragged, NOT a function of the label).
  if (nodeRegistry.isNoteNode(node)) {
    return { width: data.noteWidth || 250, height: data.noteHeight || 120 };
  }
  if (data.kind === 'data_input' && (data.dataInputWidth || data.dataInputHeight)) {
    return { width: data.dataInputWidth || 220, height: data.dataInputHeight || 120 };
  }
  if (data.kind === 'download_file' && (data.downloadFileWidth || data.downloadFileHeight)) {
    return { width: data.downloadFileWidth || 220, height: data.downloadFileHeight || 120 };
  }

  // Fleet agent nodes (with typed handles) are wider (avatar + metrics) and grow
  // with the agent name; floor at 280.
  if (data.fleetHandles?.length > 0) {
    return { width: estimateNodeWidth(data.label, 280), height: 90 };
  }

  // 3) Branching nodes: height scales with the number of rendered port rows
  // (conditions/cases/branches), exactly like the real DOM. The previous fixed
  // per-type heights under-spaced Dagre's perpendicular axis → the next lane
  // landed on top of any node with 3+ rows.
  const portRows = getPortRowCount(node);
  if (portRows !== null) {
    return { width: estimateNodeWidth(data.label, 220), height: estimateRowNodeHeight(portRows) };
  }

  // 4) Plain nodes: type-based base height + label-driven width estimate. Short
  // labels stay at the type's intrinsic min width (floor); long labels grow like
  // the real DOM.
  const base = nodeRegistry.isWhileGroupNode(node)
    ? { width: 240, height: 140 }
    : { width: 200, height: 80 };
  return { width: estimateNodeWidth(data.label, base.width), height: base.height };
}

/**
 * Check if nodes need layout (no positions or invalid positions)
 *
 * @param nodes - ReactFlow nodes to check
 * @returns true if layout should be applied
 */
export function needsLayout(nodes: Node<BuilderNodeData>[]): boolean {
  if (nodes.length === 0) {
    return false;
  }

  // Count nodes without valid positions
  const nodesWithoutPosition = nodes.filter(node =>
    !node.position ||
    isNaN(node.position.x) ||
    isNaN(node.position.y) ||
    !isFinite(node.position.x) ||
    !isFinite(node.position.y)
  );

  // If ALL nodes lack positions → LLM build, apply layout
  if (nodesWithoutPosition.length === nodes.length) {
    console.log('[Layout] All nodes without position - LLM build detected');
    return true;
  }

  // If SOME nodes lack positions → mixed build, apply local positioning
  if (nodesWithoutPosition.length > 0) {
    console.log(`[Layout] ${nodesWithoutPosition.length} nodes without position - mixed build`);
    return true;
  }

  // All nodes have positions → respect them
  return false;
}

/**
 * Calculate local position for a new node based on its neighbors
 * Used when adding a single node to an existing workflow with positions
 *
 * @param newNode - The new node to position
 * @param existingNodes - Existing nodes with positions
 * @param edges - All edges
 * @returns Calculated position
 */
export function calculateLocalPosition(
  newNode: Node<BuilderNodeData>,
  existingNodes: Node<BuilderNodeData>[],
  edges: Edge[]
): { x: number; y: number } {
  // Find predecessors (nodes that connect TO this node)
  const predecessors = existingNodes.filter(node =>
    edges.some(edge => edge.source === node.id && edge.target === newNode.id)
  );

  // Find successors (nodes that this node connects TO)
  const successors = existingNodes.filter(node =>
    edges.some(edge => edge.source === newNode.id && edge.target === node.id)
  );

  // Strategy 1: Position between predecessor and successor
  if (predecessors.length > 0 && successors.length > 0) {
    const predAvgX = average(predecessors.map(n => n.position.x));
    const predAvgY = average(predecessors.map(n => n.position.y));
    const succAvgX = average(successors.map(n => n.position.x));
    const succAvgY = average(successors.map(n => n.position.y));

    return {
      x: (predAvgX + succAvgX) / 2,
      y: (predAvgY + succAvgY) / 2,
    };
  }

  // Strategy 2: Position below predecessors
  if (predecessors.length > 0) {
    const avgX = average(predecessors.map(n => n.position.x));
    const maxY = Math.max(...predecessors.map(n => n.position.y));

    return {
      x: avgX,
      y: maxY + LAYOUT_CONFIG.ranksep,
    };
  }

  // Strategy 3: Position above successors
  if (successors.length > 0) {
    const avgX = average(successors.map(n => n.position.x));
    const minY = Math.min(...successors.map(n => n.position.y));

    return {
      x: avgX,
      y: minY - LAYOUT_CONFIG.ranksep,
    };
  }

  // Strategy 4: No neighbors - position at bottom right
  if (existingNodes.length > 0) {
    const maxX = Math.max(...existingNodes.map(n => n.position.x));
    const maxY = Math.max(...existingNodes.map(n => n.position.y));

    return {
      x: maxX,
      y: maxY + LAYOUT_CONFIG.ranksep,
    };
  }

  // Fallback: initial position
  return { x: 100, y: 100 };
}

/**
 * Calculate average of an array of numbers
 */
function average(numbers: number[]): number {
  if (numbers.length === 0) return 0;
  return numbers.reduce((sum, n) => sum + n, 0) / numbers.length;
}

/**
 * Apply layout to nodes with mixed positions
 * - Nodes with positions: keep them
 * - Nodes without positions: calculate local position
 */
export function applyMixedLayout(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): Node<BuilderNodeData>[] {
  const nodesWithPosition = nodes.filter(n =>
    n.position &&
    !isNaN(n.position.x) &&
    !isNaN(n.position.y)
  );

  const nodesWithoutPosition = nodes.filter(n =>
    !n.position ||
    isNaN(n.position.x) ||
    isNaN(n.position.y)
  );

  // Calculate local positions for nodes without position
  const layoutedNodes = nodesWithoutPosition.map(node => ({
    ...node,
    position: calculateLocalPosition(node, nodesWithPosition, edges),
  }));

  console.log(`[Layout] Applied local positioning to ${layoutedNodes.length} nodes`);

  return [...nodesWithPosition, ...layoutedNodes];
}
