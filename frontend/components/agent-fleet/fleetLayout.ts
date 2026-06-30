/**
 * Fleet layout - Dagre (for ranks/Y) + a custom top-down/bottom-up X pass that
 * orders each agent's children model (left) → tools (center) → resources (right)
 * and guarantees zero horizontal overlap on every layer.
 *
 * Extracted from useAgentFleetState so it is a pure, testable function with no
 * React / data-fetching dependencies. useAgentFleetState re-exports it for
 * backward compatibility (AgentFleetCanvas imports it from there).
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { applyDagreLayout, estimateNodeWidth } from '@/app/workflows/builder/services/LayoutService';
import { isFleetContainerId } from './fleetStatusAggregation';

/** One consolidated leaf resource, carried on the aggregator node so the inspector
 * can drill into "Resources (N)" and list the actual resources with their counts. */
export interface FleetAggregatedItem {
  type: string;
  label: string;
  iconSlug?: string;
  statusCounts?: Record<string, number>;
  /** The canvas node id of the folded leaf - lets the inspector disconnect it. */
  nodeId: string;
}

// ─── Fleet layout: Dagre + post-process ordering ───
// nodesep = horizontal gap between sibling chips; ranksep = vertical gap between
// layers. Kept tight (combined with the 280px node-width cap in FlowNode) so the
// dashboard stays compact instead of sprawling on long agent/tool names.
const FLEET_LAYOUT_CONFIG = {
  rankdir: 'TB' as const,
  nodesep: 36,
  ranksep: 104,
  align: undefined,
  // Deterministic layout: ignore measured dims so first paint (unmeasured) and the
  // auto-layout button (measured) produce the SAME layout. See nodeWidth below.
  ignoreMeasured: true,
};

// Must match the `maxWidth` cap applied to fleet nodes in FlowNode.tsx so the
// layout's width matches what actually renders.
const FLEET_NODE_WIDTH_CAP = 280;

// Deterministic node width for horizontal packing (estimate-only, NOT measured): the
// fleet always auto-layouts, so first paint (unmeasured) and the auto-layout button
// (measured) must agree - using measured widths on the button made nodes jump. Capped
// at FLEET_NODE_WIDTH_CAP to match the FlowNode maxWidth, so the layout width == the
// rendered width → compact and overlap-free regardless of label length or badges.
function fleetNodeWidth(data: any): number {
  const min = data?.fleetHandles?.length > 0 ? 280 : 200; // agent nodes are wider
  return Math.min(FLEET_NODE_WIDTH_CAP, estimateNodeWidth(data?.label, min));
}

// Deterministic node height - MUST match the heights getNodeDimensions feeds Dagre
// on the fleet path (ignoreMeasured=true): agent nodes (typed handles) are estimated
// taller (avatar + metrics row, 90) than every leaf chip / group node (80). Used only
// to recover each node's Dagre RANK from its top-left Y (see layoutSingleTree).
function fleetNodeHeight(data: any): number {
  return data?.fleetHandles?.length > 0 ? 90 : 80;
}

/**
 * Lay out ONE agent tree: Dagre for Y (ranks) + a custom X pass that orders each
 * parent's children model (left) → tools (center) → resources (right) and guarantees
 * zero horizontal overlap on every layer.
 *
 * applyFleetLayout (below) calls this once per root agent and packs the results into
 * a non-overlapping grid; a single-tree graph (single-agent panel) returns this verbatim.
 */
function layoutSingleTree(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
): Node<BuilderNodeData>[] {
  if (nodes.length === 0) return nodes;

  // Dagre for Y positions only (ranks) - we recompute all X positions
  const layoutedNodes = applyDagreLayout(nodes, edges, FLEET_LAYOUT_CONFIG);
  const nodeMap = new Map(layoutedNodes.map(n => [n.id, n]));
  const GAP = FLEET_LAYOUT_CONFIG.nodesep; // 50

  // Memoize widths once per tree: fleetNodeWidth → estimateNodeWidth does string-length
  // math on every call, and the X passes below query nodeWidth(id) repeatedly per node
  // across up to ~5 passes. A node's width is constant within one layout, so cache it.
  const widthCache = new Map<string, number>();
  const nodeWidth = (id: string): number => {
    let w = widthCache.get(id);
    if (w === undefined) {
      w = fleetNodeWidth(nodeMap.get(id)?.data);
      widthCache.set(id, w);
    }
    return w;
  };

  // ── Tree structure ──
  const childrenOf = new Map<string, string[]>();
  const parentOf = new Map<string, string>();
  for (const e of edges) {
    if (!childrenOf.has(e.source)) childrenOf.set(e.source, []);
    childrenOf.get(e.source)!.push(e.target);
    if (!parentOf.has(e.target)) parentOf.set(e.target, e.source);
  }

  // Category ordering: model (0) → tools/providers (1) → resources (2)
  const getCategory = (id: string): number => {
    const d = nodeMap.get(id)?.data as any;
    if (!d) return 2;
    if (d.fleetResourceType === 'model') return 0;
    if (d.fleetResourceType === 'tool' || id.startsWith('provider-')) return 1;
    return 2;
  };

  // ── Group nodes by Y-level (layer) ──
  // Bucket by the node's CENTER Y, not its top-left Y. Dagre vertically centers
  // every node of a rank on the SAME center line, so same-rank centers are equal -
  // but top-left Y differs by (rankHeight − nodeHeight)/2 (e.g. a 90px sub-agent vs
  // an 80px tool chip → 5px). Rounding top-left Y to 20px could split that 5px across
  // a bucket boundary (388→380 vs 393→400), dropping a sub-agent and its parent's
  // tool chips into DIFFERENT layers - both then get centered under the same parent
  // and land on top of each other (the "node sous un autre node" overlap). Center Y
  // is height-invariant, so every rank-mate shares one bucket. Round to 20px to
  // absorb any sub-pixel jitter (inter-rank centers are ~190px apart - no collision).
  const yLevels = new Map<number, string[]>();
  for (const node of layoutedNodes) {
    const y = Math.round((node.position.y + fleetNodeHeight(node.data) / 2) / 20) * 20;
    if (!yLevels.has(y)) yLevels.set(y, []);
    yLevels.get(y)!.push(node.id);
  }
  const layers = [...yLevels.keys()].sort((a, b) => a - b);

  // Shift a node + all its descendants
  const shiftSubtree = (nodeId: string, dx: number, visited = new Set<string>()) => {
    if (visited.has(nodeId)) return;
    visited.add(nodeId);
    const n = nodeMap.get(nodeId);
    if (!n) return;
    n.position = { ...n.position, x: n.position.x + dx };
    for (const cid of (childrenOf.get(nodeId) || [])) {
      shiftSubtree(cid, dx, visited);
    }
  };

  // Helper: order nodes on a layer by (parent x, category, dagre x)
  const orderLayer = (ids: string[]): string[] => {
    const byParent = new Map<string, string[]>();
    const orphans: string[] = [];
    for (const id of ids) {
      const pid = parentOf.get(id);
      if (pid) {
        if (!byParent.has(pid)) byParent.set(pid, []);
        byParent.get(pid)!.push(id);
      } else {
        orphans.push(id);
      }
    }
    const sortedPids = [...byParent.keys()].sort(
      (a, b) => (nodeMap.get(a)?.position.x ?? 0) - (nodeMap.get(b)?.position.x ?? 0),
    );
    const ordered: string[] = [];
    for (const pid of sortedPids) {
      const kids = byParent.get(pid)!;
      kids.sort((a, b) => {
        const cd = getCategory(a) - getCategory(b);
        return cd !== 0 ? cd : (nodeMap.get(a)?.position.x ?? 0) - (nodeMap.get(b)?.position.x ?? 0);
      });
      ordered.push(...kids);
    }
    orphans.sort((a, b) => (nodeMap.get(a)?.position.x ?? 0) - (nodeMap.get(b)?.position.x ?? 0));
    ordered.push(...orphans);
    return ordered;
  };

  // ── Helper: resolve overlaps on a single layer (push subtrees right) ──
  const fixLayerOverlaps = (yKey: number) => {
    const ids = yLevels.get(yKey)!;
    ids.sort((a, b) => nodeMap.get(a)!.position.x - nodeMap.get(b)!.position.x);
    for (let i = 1; i < ids.length; i++) {
      const prevRight = nodeMap.get(ids[i - 1])!.position.x + nodeWidth(ids[i - 1]);
      const currLeft = nodeMap.get(ids[i])!.position.x;
      const overlap = prevRight + GAP - currLeft;
      if (overlap > 0.5) {
        shiftSubtree(ids[i], overlap);
      }
    }
  };

  // ── PASS 1: Top-down - center each parent's children under it, then fix overlaps ──
  for (let li = 0; li < layers.length; li++) {
    const ids = yLevels.get(layers[li])!;

    if (li === 0) {
      // Root layer: pack tightly from x=0
      const ordered = orderLayer(ids);
      let x = 0;
      for (const id of ordered) {
        nodeMap.get(id)!.position = { ...nodeMap.get(id)!.position, x };
        x += nodeWidth(id) + GAP;
      }
    } else {
      // Group children by parent, center each group under its parent
      const byParent = new Map<string, string[]>();
      const orphans: string[] = [];
      for (const id of ids) {
        const pid = parentOf.get(id);
        if (pid) {
          if (!byParent.has(pid)) byParent.set(pid, []);
          byParent.get(pid)!.push(id);
        } else {
          orphans.push(id);
        }
      }

      // For each parent group: center children under parent
      for (const [pid, kids] of byParent) {
        kids.sort((a, b) => {
          const cd = getCategory(a) - getCategory(b);
          return cd !== 0 ? cd : (nodeMap.get(a)?.position.x ?? 0) - (nodeMap.get(b)?.position.x ?? 0);
        });
        const parentX = nodeMap.get(pid)!.position.x;
        const parentW = nodeWidth(pid);
        // Total width = sum of each child's width + gaps between them
        const totalW = kids.reduce((sum, k) => sum + nodeWidth(k), 0) + (kids.length - 1) * GAP;
        let startX = parentX + parentW / 2 - totalW / 2; // centered under parent center
        for (const kid of kids) {
          nodeMap.get(kid)!.position = { ...nodeMap.get(kid)!.position, x: startX };
          startX += nodeWidth(kid) + GAP;
        }
      }

      // Place orphans after the last placed node
      if (orphans.length > 0) {
        let maxX = -Infinity;
        for (const id of ids) {
          if (!orphans.includes(id)) {
            maxX = Math.max(maxX, nodeMap.get(id)!.position.x + nodeWidth(id) + GAP);
          }
        }
        if (maxX === -Infinity) maxX = 0;
        for (const id of orphans) {
          nodeMap.get(id)!.position = { ...nodeMap.get(id)!.position, x: maxX };
          maxX += nodeWidth(id) + GAP;
        }
      }

      // Fix overlaps on this layer (children of different parents may collide)
      fixLayerOverlaps(layers[li]);
    }
  }

  // ── PASS 2: Iterate - bottom-up center parents, top-down fix overlaps ──
  // Repeat until stable or max 4 iterations (enough for 4-5 level trees).
  for (let iter = 0; iter < 4; iter++) {
    let shifted = false;

    // Bottom-up: center each parent over its children's span
    for (let li = layers.length - 1; li >= 0; li--) {
      for (const id of yLevels.get(layers[li])!) {
        const kids = childrenOf.get(id);
        if (!kids || kids.length === 0) continue;
        const xs = kids.map(k => nodeMap.get(k)!.position.x);
        const minX = Math.min(...xs);
        const maxX = Math.max(...xs);
        // Center parent over children span: use rightmost child's width for right edge
        const rightmostKid = kids.reduce((r, k) => (nodeMap.get(k)!.position.x > nodeMap.get(r)!.position.x ? k : r), kids[0]);
        const spanRight = maxX + nodeWidth(rightmostKid);
        const parentW = nodeWidth(id);
        const center = (minX + spanRight) / 2 - parentW / 2;
        const node = nodeMap.get(id)!;
        if (Math.abs(node.position.x - center) > 0.5) {
          node.position = { ...node.position, x: center };
          shifted = true;
        }
      }
    }

    // Top-down: fix overlaps - shift entire subtrees right
    for (const yKey of layers) {
      const ids = yLevels.get(yKey)!;
      ids.sort((a, b) => nodeMap.get(a)!.position.x - nodeMap.get(b)!.position.x);
      for (let i = 1; i < ids.length; i++) {
        const prevRight = nodeMap.get(ids[i - 1])!.position.x + nodeWidth(ids[i - 1]);
        const currLeft = nodeMap.get(ids[i])!.position.x;
        const overlap = prevRight + GAP - currLeft;
        if (overlap > 0.5) {
          shiftSubtree(ids[i], overlap);
          shifted = true;
        }
      }
    }

    if (!shifted) break; // converged
  }

  // ── Final safety pass: guarantee zero overlaps on every layer ──
  for (const yKey of layers) {
    const ids = yLevels.get(yKey)!;
    ids.sort((a, b) => nodeMap.get(a)!.position.x - nodeMap.get(b)!.position.x);
    for (let i = 1; i < ids.length; i++) {
      const prevRight = nodeMap.get(ids[i - 1])!.position.x + nodeWidth(ids[i - 1]);
      const currLeft = nodeMap.get(ids[i])!.position.x;
      const overlap = prevRight + GAP - currLeft;
      if (overlap > 0.5) {
        shiftSubtree(ids[i], overlap);
      }
    }
  }

  return layoutedNodes;
}

// ─── Grid-of-trees packing (fleet view) ───
// A safe per-tree height allowance for the bottom row: larger than any rendered
// fleet node so rows never overlap vertically (over-estimating only adds whitespace).
const FLEET_TREE_NODE_H = 160;
const FLEET_GRID_GAP_X = 80; // horizontal gap between agent trees
const FLEET_GRID_GAP_Y = 120; // vertical gap between rows of trees

interface PlacedTree {
  nodes: Node<BuilderNodeData>[];
  w: number;
  h: number;
}

/**
 * Lay out the fleet as a GRID OF TREES: each root agent's sub-tree is laid out
 * independently (overlap-free via layoutSingleTree), then the trees are packed into
 * a roughly-square grid of disjoint bounding boxes. This is what keeps a large fleet
 * from collapsing into one over-wide row where sibling sub-trees hide each other.
 *
 * A graph with ≤1 root agent (the single-agent panel, a snapshot, or any single
 * connected tree) returns layoutSingleTree verbatim - behaviour is unchanged there.
 *
 * Exported so the auto-layout toolbar button can reuse it.
 */
export function applyFleetLayout(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
): Node<BuilderNodeData>[] {
  if (nodes.length === 0) return nodes;

  // Root agents = agent nodes that are NOT the target of an agent→agent (sub-agent)
  // edge. Everything else hangs under a root.
  const nodeIds = new Set(nodes.map(n => n.id));
  const agentIds = nodes.filter(n => n.id.startsWith('agent-')).map(n => n.id);
  const subAgentTargets = new Set<string>();
  for (const e of edges) {
    if (e.source.startsWith('agent-') && e.target.startsWith('agent-')
        && nodeIds.has(e.source) && nodeIds.has(e.target)) {
      subAgentTargets.add(e.target);
    }
  }
  const roots = agentIds.filter(id => !subAgentTargets.has(id));

  // Single tree (single-agent panel / snapshot / lone connected fleet) → original
  // algorithm with no grid offset. Keeps every existing layout pixel-identical.
  if (roots.length <= 1) {
    return layoutSingleTree(nodes, edges);
  }

  // Assign every node to exactly one root's tree. BFS from each root; the first root
  // to reach a shared sub-agent claims it (and its sub-tree). Never cross into another
  // root agent's node.
  const rootSet = new Set(roots);
  const childrenOf = new Map<string, string[]>();
  for (const e of edges) {
    if (!childrenOf.has(e.source)) childrenOf.set(e.source, []);
    childrenOf.get(e.source)!.push(e.target);
  }
  const owner = new Map<string, string>();
  for (const root of roots) {
    const queue: string[] = [root];
    while (queue.length) {
      const id = queue.shift()!;
      if (owner.has(id) || !nodeIds.has(id)) continue;
      owner.set(id, root);
      for (const c of (childrenOf.get(id) ?? [])) {
        if (rootSet.has(c)) continue; // a different root's territory
        if (!owner.has(c)) queue.push(c);
      }
    }
  }

  // Group nodes per owning root (preserving root order), plus any disconnected
  // orphans as their own single-node trees so nothing is dropped.
  const groups = new Map<string, Node<BuilderNodeData>[]>();
  for (const root of roots) groups.set(root, []);
  for (const n of nodes) {
    const key = owner.get(n.id) ?? `__orphan__:${n.id}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(n);
  }

  // Lay out each tree independently and normalize it to a (0,0) origin.
  const trees: PlacedTree[] = [];
  for (const group of groups.values()) {
    if (group.length === 0) continue;
    const ids = new Set(group.map(n => n.id));
    const subEdges = edges.filter(e => ids.has(e.source) && ids.has(e.target));
    const laid = layoutSingleTree(group, subEdges);
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of laid) {
      minX = Math.min(minX, n.position.x);
      minY = Math.min(minY, n.position.y);
      maxX = Math.max(maxX, n.position.x + fleetNodeWidth(n.data));
      maxY = Math.max(maxY, n.position.y);
    }
    const normalized = laid.map(n => ({
      ...n,
      position: { x: n.position.x - minX, y: n.position.y - minY },
      positionAbsolute: { x: n.position.x - minX, y: n.position.y - minY },
    }));
    trees.push({ nodes: normalized, w: maxX - minX, h: (maxY - minY) + FLEET_TREE_NODE_H });
  }

  // Greedy row-packing aiming for a roughly square grid. Wrap to a new row when the
  // running width would exceed a target (the wider of the widest tree and an even
  // split across ~√n rows), so variable-width trees never overflow or overlap.
  const widest = Math.max(...trees.map(t => t.w));
  const totalWidth = trees.reduce((s, t) => s + t.w, 0) + FLEET_GRID_GAP_X * Math.max(0, trees.length - 1);
  const targetRowWidth = Math.max(widest, totalWidth / Math.ceil(Math.sqrt(trees.length)));

  const out: Node<BuilderNodeData>[] = [];
  let rowX = 0;
  let rowTop = 0;
  let rowHeight = 0;
  for (const tree of trees) {
    if (rowX > 0 && rowX + tree.w > targetRowWidth) {
      rowTop += rowHeight + FLEET_GRID_GAP_Y;
      rowX = 0;
      rowHeight = 0;
    }
    for (const n of tree.nodes) {
      out.push({
        ...n,
        position: { x: n.position.x + rowX, y: n.position.y + rowTop },
        positionAbsolute: { x: n.position.x + rowX, y: n.position.y + rowTop },
      });
    }
    rowX += tree.w + FLEET_GRID_GAP_X;
    rowHeight = Math.max(rowHeight, tree.h);
  }
  return out;
}

export interface CachedFleetLayout {
  /** Laid-out nodes to display. Membership is ALWAYS the input {@code nodes} set, never a superset. */
  nodes: Node<BuilderNodeData>[];
  /** Structural signature of (nodes, edges) - feed back as {@code prevSig} next call. */
  sig: string;
  /** id → position map - feed back as {@code cachedPositions} next call. */
  positions: Map<string, { x: number; y: number }>;
  /** True iff Dagre actually ran (structure changed). Callers should only re-fit the viewport when true. */
  relaidOut: boolean;
}

/**
 * {@link applyFleetLayout} with a structural-signature skip. When the (nodes, edges) id-set is
 * unchanged from {@code prevSig} - a stats/activity update mutates node DATA only, not the graph -
 * the expensive Dagre pass is skipped and each input node is returned at its {@code cachedPositions}
 * position.
 *
 * <p>CRITICAL invariant: the result's node membership is ALWAYS the input {@code nodes} (e.g. a
 * collapse/category-filtered subset), never a wider set. This is why the skip rebuilds from the
 * input rather than patching a previous nodes array - patching a stale/full array would resurrect
 * filtered-out nodes on the first data-only update. Callers persist {@code sig} + {@code positions}
 * and pass them back, and re-fit the viewport only when {@code relaidOut} is true.
 */
export function applyFleetLayoutCached(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  prevSig: string,
  cachedPositions: Map<string, { x: number; y: number }>,
): CachedFleetLayout {
  const sig = nodes.map(n => n.id).join('|') + '::' + edges.map(e => e.id).join('|');
  if (sig === prevSig && cachedPositions.size > 0) {
    const positioned = nodes.map(n => {
      const pos = cachedPositions.get(n.id);
      return pos ? { ...n, position: pos } : n;
    });
    return { nodes: positioned, sig, positions: cachedPositions, relaidOut: false };
  }
  const laid = applyFleetLayout(nodes, edges);
  return { nodes: laid, sig, positions: new Map(laid.map(n => [n.id, n.position])), relaidOut: true };
}

// At or above this many DIRECT resource children, an agent's resources are
// consolidated into one aggregator node (keeps the fleet compact; the model chip
// and sub-agents stay visible separately). User-confirmed: "à partir de 6
// ressources on consolide" → 6+ children aggregate, 5 stay expanded.
export const RESOURCE_AGGREGATION_THRESHOLD = 6;

/** Sum the leaf-resource counts for the aggregator label ("Resources (N)"). */
function countAggregatedResources(counts: any, fallback: number): number {
  if (!counts) return fallback;
  let total = 0;
  for (const k of ['tools', 'skills', 'workflows', 'interfaces', 'tables', 'files']) {
    if (typeof counts[k] === 'number' && counts[k] > 0) total += counts[k];
  }
  if (counts.webSearch) total += 1;
  return total > 0 ? total : fallback;
}

/**
 * When an agent has at least `threshold` DIRECT resource children (tool chips /
 * provider groups / skill folders / single resource chips / category groups / web
 * search - NOT the model chip, NOT sub-agents), replace them all with ONE
 * "Resources (N)" aggregator node so the fleet stays compact. The model chip and
 * sub-agent nodes are kept. The aggregator carries the agent's resource-count
 * breakdown for the inspector (`fleetAggregator` + `fleetResourceCounts`).
 *
 * Pure + idempotent: re-running leaves the agent with a single resource child (the
 * aggregator, ≤ threshold), so nothing changes on a second pass.
 *
 * MUST run BEFORE applyFleetLayout (it returns the rewritten nodes AND edges).
 */
export function consolidateFleetResources(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  threshold = RESOURCE_AGGREGATION_THRESHOLD,
): { nodes: Node<BuilderNodeData>[]; edges: Edge[] } {
  // Resource edges per agent: from an agent node, category tools/resources/skills
  // (excludes 'model' and 'sub-agents' edges, which must stay visible).
  const resourceEdgesByAgent = new Map<string, Edge[]>();
  for (const e of edges) {
    if (!e.source.startsWith('agent-')) continue;
    const cat = (e.data as any)?.category;
    if (cat === 'tools' || cat === 'resources' || cat === 'skills') {
      if (!resourceEdgesByAgent.has(e.source)) resourceEdgesByAgent.set(e.source, []);
      resourceEdgesByAgent.get(e.source)!.push(e);
    }
  }

  // children lookup over all edges, to collect each resource child's subtree
  // (provider → tools, folder → skills, category → children).
  const childrenOf = new Map<string, string[]>();
  for (const e of edges) {
    if (!childrenOf.has(e.source)) childrenOf.set(e.source, []);
    childrenOf.get(e.source)!.push(e.target);
  }
  const nodeById = new Map(nodes.map((n) => [n.id, n]));

  const removeNodeIds = new Set<string>();
  const addNodes: Node<BuilderNodeData>[] = [];
  const addEdges: Edge[] = [];

  for (const [agentId, resourceEdges] of resourceEdgesByAgent) {
    // Idempotency: an already-aggregated agent has exactly its one aggregator child.
    if (resourceEdges.length === 1 && resourceEdges[0].target === `agg-${agentId}`) continue;

    // Threshold is on the agent's TOTAL leaf resources, NOT its direct resource
    // edges. tables/workflows/interfaces with 2+ items are pre-grouped under one
    // category node upstream, so an agent with e.g. 12 tables has a SINGLE direct
    // "Tables" edge - counting direct edges alone (resourceEdges.length) would miss
    // it and leave the whole tree expanded. The spec is "à partir de 6 ressources".
    // Use max(total leaves, direct edges): the total catches pre-grouped categories,
    // while direct-edges is a floor so an "all tools" agent (counted as 0 leaves but
    // a real edge) that aggregated before never stops aggregating.
    const counts = (nodeById.get(agentId)?.data as any)?.fleetResourceCounts;
    const total = countAggregatedResources(counts, resourceEdges.length);
    if (Math.max(total, resourceEdges.length) < threshold) continue;

    // While removing the subtree, fold each LEAF resource's status counts up into
    // the aggregator (so "Resources (N)" shows cumulative ✓/✗) and record the leaf
    // for the inspector drill-down (fleetAggregatedItems). Grouping containers
    // (provider/folder/category) are skipped - their counts are the sum of these
    // leaves, so summing them too would double-count.
    const aggCounts: Record<string, number> = {};
    const aggItems: FleetAggregatedItem[] = [];
    const collectSubtree = (id: string) => {
      if (removeNodeIds.has(id)) return;
      removeNodeIds.add(id);
      const nd = nodeById.get(id)?.data as any;
      if (nd && !isFleetContainerId(id) && nd.fleetResourceType) {
        aggItems.push({
          type: nd.fleetResourceType,
          label: nd.label ?? id,
          iconSlug: nd.toolData?.iconSlug || nd.apiData?.iconSlug,
          statusCounts: nd.statusCounts,
          nodeId: id,
        });
        for (const [k, v] of Object.entries((nd.statusCounts ?? {}) as Record<string, number>)) {
          const n = Number(v);
          if (Number.isFinite(n) && n !== 0) aggCounts[k] = (aggCounts[k] ?? 0) + n;
        }
      }
      for (const child of (childrenOf.get(id) ?? [])) {
        if (!child.startsWith('agent-')) collectSubtree(child);
      }
    };
    for (const e of resourceEdges) collectSubtree(e.target);

    const aggId = `agg-${agentId}`;
    addNodes.push({
      id: aggId,
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: {
        id: aggId,
        label: `Resources (${total})`,
        kind: 'action',
        fleetTopHandle: true,
        fleetResourceType: 'folder', // reuse the folder icon (no FlowNode change)
        fleetAggregator: true,
        fleetResourceCounts: counts,
        fleetAggregatedItems: aggItems,
        statusCounts: Object.keys(aggCounts).length > 0 ? aggCounts : undefined,
      } as any,
    });
    addEdges.push({
      id: `edge-${aggId}`,
      source: agentId,
      target: aggId,
      sourceHandle: 'source-resources',
      targetHandle: 'target-top',
      data: { category: 'resources' },
    });
  }

  if (removeNodeIds.size === 0) return { nodes, edges };
  return {
    nodes: nodes.filter((n) => !removeNodeIds.has(n.id)).concat(addNodes),
    edges: edges.filter((e) => !removeNodeIds.has(e.source) && !removeNodeIds.has(e.target)).concat(addEdges),
  };
}
