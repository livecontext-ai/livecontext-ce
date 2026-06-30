/**
 * Fleet status-count aggregation - pure helpers shared by useAgentFleetState and
 * useSingleAgentFleet so the two views roll counts up identically.
 *
 * `aggregateContainerStatusCounts` makes every CONTAINER node (provider / folder /
 * category / aggregator) carry the SUM of its descendant leaves' status counts, so
 * e.g. a "Tables" group shows 3 when table A was called once and table B twice. Leaf
 * nodes keep their own counts; agent nodes keep their own run stats (never folded in).
 *
 * `colorEdgesByStatus` is the (previously duplicated) edge post-process: it strokes
 * and labels each edge from its own or its target node's counts. Running it AFTER
 * consolidation lets the new `agent → Resources (N)` edge pick up the aggregator's sum.
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, StatusCounts } from '@/app/workflows/builder/types';

// Grouping nodes whose status count is the SUM of their children (not their own).
const CONTAINER_PREFIXES = ['provider-', 'folder-', 'category-', 'agg-'] as const;

/** True for provider / folder / category / aggregator grouping nodes. */
export function isFleetContainerId(id: string): boolean {
  return CONTAINER_PREFIXES.some((p) => id.startsWith(p));
}

/** Add every numeric key of `from` into `into` (mutates `into`). Subset keys such as
 * BUDGET_EXHAUSTED sum independently - the badge subtracts them from FAILED at render. */
function addCounts(into: StatusCounts, from: StatusCounts | undefined | null): void {
  if (!from) return;
  for (const [k, v] of Object.entries(from)) {
    const n = Number(v);
    if (Number.isFinite(n) && n !== 0) into[k] = (into[k] ?? 0) + n;
  }
}

/**
 * Bottom-up cumulative status counts onto container nodes. Mutates
 * `node.data.statusCounts` for provider / folder / category nodes (and aggregators,
 * when present) to the sum of their descendant LEAF counts. Pure w.r.t. leaves and
 * agent nodes (those are left untouched).
 *
 * MUST run BEFORE consolidateFleetResources so the surviving category/provider/folder
 * nodes - and the leaves the aggregator later sums - already carry correct values.
 */
export function aggregateContainerStatusCounts(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
): void {
  const nodeById = new Map(nodes.map((n) => [n.id, n]));
  const childrenOf = new Map<string, string[]>();
  for (const e of edges) {
    if (!childrenOf.has(e.source)) childrenOf.set(e.source, []);
    childrenOf.get(e.source)!.push(e.target);
  }

  // cumulative(id) = sum of leaf counts in id's subtree. A node WITH (non-agent)
  // children is summed from its children (its own counts are ignored - containers
  // don't run); a node WITHOUT children is a leaf and contributes its own counts.
  const memo = new Map<string, StatusCounts | undefined>();
  const cumulative = (id: string, stack: Set<string>): StatusCounts | undefined => {
    if (memo.has(id)) return memo.get(id);
    if (stack.has(id)) return undefined; // cycle guard (shared sub-trees)
    stack.add(id);

    const acc: StatusCounts = {};
    const kids = (childrenOf.get(id) ?? []).filter((cid) => !cid.startsWith('agent-') && nodeById.has(cid));
    if (kids.length === 0) {
      addCounts(acc, (nodeById.get(id)?.data as any)?.statusCounts);
    } else {
      for (const cid of kids) addCounts(acc, cumulative(cid, stack));
    }

    stack.delete(id);
    const result = Object.keys(acc).length > 0 ? acc : undefined;
    memo.set(id, result);
    return result;
  };

  for (const n of nodes) {
    if (!isFleetContainerId(n.id)) continue;
    const c = cumulative(n.id, new Set());
    if (c) (n.data as any).statusCounts = c;
  }
}

/**
 * Stroke + label edges from status counts. An edge uses its own `data.statusCounts`
 * when present (sub-agent edges carry per-caller stats), otherwise the target node's
 * counts. Mutates edges in place - same behaviour the two hooks shared inline before.
 */
export function colorEdgesByStatus(nodes: Node<BuilderNodeData>[], edges: Edge[]): void {
  const nodeStatusCountsMap = new Map<string, StatusCounts>();
  for (const n of nodes) {
    const sc = (n.data as any)?.statusCounts as StatusCounts | undefined;
    if (sc) nodeStatusCountsMap.set(n.id, sc);
  }

  for (const e of edges) {
    const isSubAgentEdge = (e.data as any)?.category === 'sub-agents';
    const sc = (e.data as any)?.statusCounts || (isSubAgentEdge ? undefined : nodeStatusCountsMap.get(e.target));
    if (!sc) continue;
    const completed = sc.COMPLETED ?? 0;
    const failed = sc.FAILED ?? 0;
    if (completed === 0 && failed === 0) continue;
    const stroke = completed > 0 && failed > 0 ? '#f59e0b' : completed > 0 ? '#10b981' : '#ef4444';
    const markerType = completed > 0 && failed > 0 ? 'partial_success' : completed > 0 ? 'completed' : 'failed';
    e.style = { strokeWidth: 1.6, stroke };
    e.markerEnd = `url(#arrow-${markerType})`;
    if (!(e.data as any)?.statusCounts) {
      e.data = { ...e.data, statusCounts: sc };
    }
  }
}
