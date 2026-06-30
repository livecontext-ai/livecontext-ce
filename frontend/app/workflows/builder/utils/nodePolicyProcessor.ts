import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import type { PlanGeneratorContext } from './planGeneratorContext';
import { gateNodePolicyForNode, sanitizeNodePolicy } from './nodePolicy';

/**
 * Attaches each node's `nodePolicy` block to its emitted plan entry.
 *
 * Runs AFTER every other processor so it covers ALL executable entries
 * uniformly - mcps / tables / agents / cores (including the control cores
 * registered by edgeProcessor) / interfaces. Every entry carries a
 * `graphNodeId` pointing back to its builder node, which is the join key.
 *
 * Triggers and notes are excluded by design (the backend parser ignores a
 * policy there), and a default/absent policy adds NOTHING to the entry, so
 * plans without policies regenerate byte-identical to before.
 */
export function attachNodePolicies(ctx: PlanGeneratorContext): void {
  const nodesById = new Map<string, Node<BuilderNodeData>>(
    ctx.nodes.map((node) => [node.id, node])
  );

  const attach = (entries?: Array<Record<string, unknown>>) => {
    if (!Array.isArray(entries)) return;
    for (const entry of entries) {
      if (!entry) continue;
      const graphNodeId = entry.graphNodeId as string | undefined;
      const node =
        (graphNodeId && nodesById.get(graphNodeId)) ||
        nodesById.get(entry.id as string);
      if (!node) continue;
      const policy = gateNodePolicyForNode(
        sanitizeNodePolicy(node.data?.nodePolicy),
        node
      );
      if (policy) {
        entry.nodePolicy = policy;
      }
    }
  };

  attach(ctx.plan.mcps as Array<Record<string, unknown>>);
  attach(ctx.plan.tables as Array<Record<string, unknown>> | undefined);
  attach(ctx.plan.agents as Array<Record<string, unknown>> | undefined);
  attach(ctx.plan.cores as Array<Record<string, unknown>> | undefined);
  attach(ctx.plan.interfaces as Array<Record<string, unknown>> | undefined);
}
