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
import {
  DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
  type WorkflowLayoutDirection,
} from '@/contexts/WorkflowLayoutDirectionContext';

/** The reading direction a dagre rankdir corresponds to. */
function directionOf(rankdir: string): WorkflowLayoutDirection {
  return rankdir === 'TB' || rankdir === 'BT' ? 'vertical' : 'horizontal';
}

/**
 * Layout configuration
 */
const LAYOUT_CONFIG = {
  // Dagre graph direction. Overridden per call from the user's reading direction
  // (see `layoutConfigForDirection`); this stays the horizontal default.
  rankdir: 'LR' as 'TB' | 'BT' | 'LR' | 'RL', // Left to Right (horizontal layout)

  // Node spacing - kept tight for a compact graph (size-aware dims already
  // guarantee no overlap, so the gap is pure breathing room, not safety margin).
  // NOTE both are relative to the FLOW AXIS, so their roles swap with rankdir:
  // `nodesep` is the within-rank gap (vertical in LR, horizontal in TB) and
  // `ranksep` is the along-flow gap. These values are tuned for LR - see
  // `layoutConfigForDirection` for the vertical set.
  nodesep: 40,    // Within-rank gap (also the stack gap between independent components)
  ranksep: 90,    // Along-flow gap between ranks

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
 * Layout config for a reading direction. Every caller that lays the BUILDER out
 * should pass `layoutConfigForDirection(direction)` rather than a bare `rankdir`:
 * the spacing constants are flow-axis relative, so flipping rankdir alone leaves
 * vertical graphs badly spaced.
 *
 * Why the vertical numbers differ, rather than reusing the LR ones:
 * - Nodes are WIDE and SHORT (200-900px x 80-140px). In TB, `nodesep` becomes the
 *   horizontal gap between siblings, so the LR value of 40 would jam wide branches
 *   nearly edge to edge, while `ranksep` becomes the vertical gap, where 90 is far
 *   more room than two 80px-tall nodes need. The fleet canvas, which has been TB
 *   since it shipped, settled on the inverse ratio (36 / 104) - these follow it.
 * - `align: 'UL'` packs ranks toward the upper-left, which reads as intended in LR
 *   but bunches TB branches against one side. Dagre centres when align is undefined,
 *   which is what a top-down graph wants (and what the fleet uses).
 */
export function layoutConfigForDirection(
  direction: WorkflowLayoutDirection,
): Partial<typeof LAYOUT_CONFIG> {
  if (direction !== 'vertical') return {};
  return {
    rankdir: 'TB',
    nodesep: 44,
    ranksep: 104,
    align: undefined,
  };
}

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

  // The cross-axis centering pass is VERTICAL-ONLY. Horizontal keeps the historical
  // algorithm (plain Dagre placement, no post-centering) so existing left-to-right
  // canvases lay out exactly as they always did. Vertical (top-to-bottom) opted into
  // the extra centering because Dagre leaves wide nodes off-centre over their children
  // on the TB axis; horizontal never needed it and re-introducing it visibly shifted
  // long-standing layouts, so it is gated here.
  const vertical = config.rankdir === 'TB' || config.rankdir === 'BT';

  // Independent sub-graphs (e.g. several trigger chains in one workflow) otherwise
  // share Dagre's GLOBAL rank grid: a wide node in one chain pushes that column for
  // EVERY chain, and short chains leave large gaps - the sparse look in multi-trigger
  // workflows. Lay each connected component out on its own, then stack them tightly
  // perpendicular to the flow. A single connected workflow (the common case) takes
  // the fast path and is unchanged.
  const components = connectedComponents(nodes, edges);
  if (components.length <= 1) {
    const laid = layoutConnectedGraph(nodes, edges, config);
    return vertical ? centerOnCrossAxis(laid, edges, config) : laid;
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
    const laidComp = layoutConnectedGraph(compNodes, compEdges, config);
    const laid = vertical ? centerOnCrossAxis(laidComp, compEdges, config) : laidComp;

    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of laid) {
      // Vertical prefers measured dims to match how `centerOnCrossAxis` just placed
      // these nodes (mixing an estimated extent with measured positions would offset
      // the next component's stack by the estimate/measure gap). Horizontal keeps the
      // historical estimate-only extent so multi-component stacking is unchanged.
      const measuredW = typeof n.width === 'number' && n.width > 0 ? n.width : undefined;
      const measuredH = typeof n.height === 'number' && n.height > 0 ? n.height : undefined;
      const est = getNodeDimensions(n, config.ignoreMeasured, directionOf(config.rankdir));
      const width = vertical ? (measuredW ?? est.width) : est.width;
      const height = vertical ? (measuredH ?? est.height) : est.height;
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
/**
 * Center connected nodes on the CROSS axis (perpendicular to the flow) so a parent
 * sits over the middle of its children and a single child lines up under its parent,
 * making the main edge run straight instead of bending.
 *
 * dagre's coordinate assignment (Brandes-Köpf) already tries to centre, but when
 * sibling nodes differ in size it leaves a visible offset (a trigger ~50px off the
 * decision it feeds). This is a post-pass on dagre's output that only shifts nodes
 * along the CROSS axis, never the flow axis, so ranks and edge topology are
 * untouched.
 *
 * Safety: it moves each rank as computed, then re-spaces any rank whose nodes would
 * overlap back to a non-overlapping spread centred on the same midpoint. So it can
 * only straighten alignment, never introduce a collision dagre had avoided.
 *
 * Runs in BOTH directions (the offset exists on either axis); horizontal users get
 * the same straightening, which is why the audit's "do it in horizontal too" applies.
 */
export function centerOnCrossAxis(
  laid: Node<BuilderNodeData>[],
  edges: Edge[],
  config: typeof LAYOUT_CONFIG,
): Node<BuilderNodeData>[] {
  if (laid.length < 2) return laid;
  const vertical = config.rankdir === 'TB' || config.rankdir === 'BT';
  // cross = the axis we may move on; flow = the axis we must never touch.
  const cross: 'x' | 'y' = vertical ? 'x' : 'y';
  const flow: 'x' | 'y' = vertical ? 'y' : 'x';
  // Centre on the size the node is actually PAINTED at. dagre lays out from label
  // estimates, but a rendered node is often wider (a branch node's estimate is ~368
  // while it paints ~451), so aligning estimated centres leaves the VISUAL centres
  // offset - which is the "not centred" the user sees. On the auto-layout button the
  // nodes are already measured (`node.width`/`height` populated by ReactFlow), so use
  // that; at import time nothing is measured yet and the estimate is all there is (and
  // dagre used the same estimate, so they stay consistent).
  const crossSize = (n: Node<BuilderNodeData>) => {
    const measured = vertical ? n.width : n.height;
    if (typeof measured === 'number' && measured > 0) return measured;
    return getNodeDimensions(n, config.ignoreMeasured, directionOf(config.rankdir))[vertical ? 'width' : 'height'];
  };
  const gap = config.nodesep;

  const byId = new Map(laid.map((n) => [n.id, n]));
  const children = new Map<string, string[]>();
  const parents = new Map<string, string[]>();
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target) || e.source === e.target) continue;
    (children.get(e.source) ?? children.set(e.source, []).get(e.source)!).push(e.target);
    (parents.get(e.target) ?? parents.set(e.target, []).get(e.target)!).push(e.source);
  }

  const crossCenter = (n: Node<BuilderNodeData>) => n.position[cross] + crossSize(n) / 2;
  const setCrossCenter = (n: Node<BuilderNodeData>, c: number) => {
    const p = { ...n.position, [cross]: c - crossSize(n) / 2 };
    n.position = p;
    n.positionAbsolute = p;
  };

  // Group nodes into ranks by their flow coordinate (dagre put a rank on one line).
  const rankKey = (n: Node<BuilderNodeData>) => Math.round(n.position[flow]);
  const ranks = new Map<number, Node<BuilderNodeData>[]>();
  for (const n of laid) (ranks.get(rankKey(n)) ?? ranks.set(rankKey(n), []).get(rankKey(n))!).push(n);
  // Ranks in flow order (top-to-bottom / left-to-right).
  const orderedRankKeys = [...ranks.keys()].sort((a, b) => a - b);

  // Pass 1, leaf-ward first (reverse rank order): a parent centres over its children.
  for (const k of [...orderedRankKeys].reverse()) {
    for (const n of ranks.get(k)!) {
      const kids = (children.get(n.id) ?? []).map((id) => byId.get(id)!).filter(Boolean);
      if (kids.length === 0) continue;
      const mid = kids.reduce((s, c) => s + crossCenter(c), 0) / kids.length;
      setCrossCenter(n, mid);
    }
  }
  // Pass 2, root-ward (forward): a node with a SINGLE parent lines up under it, so a
  // straight chain is dead straight. Skipped for merges (multiple parents), where
  // pass 1's child-centring already placed them sensibly.
  for (const k of orderedRankKeys) {
    for (const n of ranks.get(k)!) {
      const ps = parents.get(n.id) ?? [];
      if (ps.length !== 1) continue;
      const parent = byId.get(ps[0]);
      const siblings = children.get(ps[0]) ?? [];
      if (parent && siblings.length === 1) setCrossCenter(n, crossCenter(parent));
    }
  }

  // Pass 3: within every rank, push apart any nodes that now overlap, keeping their
  // order and their shared midpoint. This is the guard that makes the pass safe: it
  // can never leave two nodes closer than dagre's gap.
  for (const k of orderedRankKeys) {
    const row = ranks.get(k)!.slice().sort((a, b) => crossCenter(a) - crossCenter(b));
    if (row.length < 2) continue;
    let overlap = false;
    for (let i = 1; i < row.length; i++) {
      const need = crossSize(row[i - 1]) / 2 + crossSize(row[i]) / 2 + gap;
      if (crossCenter(row[i]) - crossCenter(row[i - 1]) < need - 0.5) { overlap = true; break; }
    }
    if (!overlap) continue;
    // Lay them edge-to-edge with the gap, then recentre the whole row on its old mid.
    const oldMid = row.reduce((s, n) => s + crossCenter(n), 0) / row.length;
    let cursor = 0;
    const centers: number[] = [];
    for (let i = 0; i < row.length; i++) {
      if (i > 0) cursor += crossSize(row[i - 1]) / 2 + gap + crossSize(row[i]) / 2;
      centers.push(cursor);
    }
    const newMid = centers.reduce((s, c) => s + c, 0) / centers.length;
    row.forEach((n, i) => setCrossCenter(n, centers[i] - newMid + oldMid));
  }

  return laid;
}

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
    const { width, height } = getNodeDimensions(node, config.ignoreMeasured, directionOf(config.rankdir));
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
 * Approximate width of ONE branch row when the rows sit side by side (vertical
 * canvas): the row is `rounded-2xl border px-3 py-2` holding an uppercase badge
 * ("ELSE IF" is the widest) plus a truncated label.
 */
const ROW_WIDTH_PX = 132;

/**
 * Width of a branching node whose rows are laid out ACROSS it.
 *
 * `getBranchRowFlow` turns the row container from a column into a row on a vertical
 * canvas, so a 5-case Switch stops being ~320px tall and becomes ~700px wide. Dagre
 * packs the within-rank axis, which under `rankdir: TB` is the WIDTH - so this is the
 * number that decides whether the next branch lands beside the node or on top of it.
 * Feeding it the label-only estimate re-creates, on the vertical canvas, exactly the
 * overlap that the row-aware HEIGHT estimate was added to fix on the horizontal one.
 */
function estimateRowNodeWidth(rowCount: number, label: string): number {
  const rows = rowCount * ROW_WIDTH_PX + Math.max(0, rowCount - 1) * ROW_GAP_PX + NODE_CHROME_PX;
  // NOT clamped to ESTIMATE_MAX_WIDTH: that cap tames a long LABEL, but here the rows
  // ARE the node's real width (they render side by side in vertical), and this is the
  // WITHIN-RANK axis dagre packs against under rankdir:TB. Capping it below the real
  // width (a 7+-case switch exceeds 900) would under-space the rank and let the next
  // node overlap - the exact bug the row-aware estimate exists to prevent.
  return Math.max(rows, estimateNodeWidth(label, 220));
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
  direction: WorkflowLayoutDirection = DEFAULT_WORKFLOW_LAYOUT_DIRECTION,
): { width: number; height: number } {
  const data = node.data as any;

  // 1) Interface nodes in preview mode use their actual preview dimensions.
  // Check both isPreviewMode (set by usePreparedGraph at render time) and
  // interfaceData.showPreview (set during import, before usePreparedGraph runs).
  if (nodeRegistry.isInterfaceNode(node)) {
    const interfaceData = data.interfaceData;
    const isPreview = data.isPreviewMode ?? (interfaceData?.showPreview !== false);
    if (isPreview) {
      // Fallback 400x250 = the drop/import default box (classic 1280x800 contained in
      // 400x400). Once the interface's format loads, the node snaps its box to that
      // format and persists previewWidth/Height, so layout measures the real shape.
      return {
        width: interfaceData?.previewWidth || 400,
        height: interfaceData?.previewHeight || 250,
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
    // The rows run ACROSS the node on a vertical canvas (getBranchRowFlow), so the
    // row count drives the WIDTH there and the height collapses to a single row.
    return direction === 'vertical'
      ? { width: estimateRowNodeWidth(portRows, data.label), height: estimateRowNodeHeight(1) }
      : { width: estimateNodeWidth(data.label, 220), height: estimateRowNodeHeight(portRows) };
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
