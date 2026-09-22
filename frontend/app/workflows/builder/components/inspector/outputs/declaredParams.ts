/**
 * What a node declares in EDIT mode, expressed the way the backend receives it.
 *
 * The builder keeps a node's configuration on `node.data` in per-node-type
 * shapes (`filterConditions`, `limitCount`, `codeContent`, …); what the backend
 * actually gets is the plan entry the generator emits for that node. Reading
 * `node.data` here would mean re-deriving that mapping a second time and letting
 * the two drift, so this runs the REAL generator on the single node and reads
 * the entry it produces.
 *
 * That is what makes "the fields declared in edit mode" a precise statement:
 * the same function the save path uses answers it.
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { generateWorkflowPlan } from '../../../utils/workflowPlanGenerator';
import { CONFIGURED_SECRETS, WITHHELD_CREDENTIAL } from './configuredSecrets';
import { isCredentialKey } from './credentialKeys';
import { flattenPlannedParams } from './runParamAlignment';

/**
 * Every list a plan entry can land in. Order does not matter: a node id appears
 * in exactly one of them.
 */
const PLAN_ENTRY_LISTS = ['cores', 'mcps', 'tables', 'agents', 'triggers', 'interfaces'] as const;

/**
 * The plan entry the generator emits for one node, or null when it emits none.
 *
 * There is no blind spot here, which this comment used to claim for the loop.
 * `processEdgesV2` calls `registerControlNodes` BEFORE it touches any edge, and that
 * function walks `ctx.nodes`: decision, switch, split, option, fork, approval and
 * while-group all declare their configuration from the node alone, and
 * `nodeRegistry.isLoopNode` IS `isWhileGroupNode` - there is one loop node type and it
 * reports `loopCondition` and `maxIterations`. Verified type by type against the
 * generator, because the previous claim sent a reader looking for a hole that is not
 * there. A `merge` reports nothing, and that is honest: it has no parameters of its own.
 */
export function planEntryForNode(
  node: Node<BuilderNodeData>,
): Record<string, unknown> | null {
  let plan: Record<string, unknown>;
  try {
    // No edges: this asks "what does THIS node declare", not "what does the
    // graph look like". A generator throwing on an isolated node must not take
    // the inspector down with it.
    plan = generateWorkflowPlan([node], []) as unknown as Record<string, unknown>;
  } catch {
    return null;
  }

  for (const listName of PLAN_ENTRY_LISTS) {
    const list = plan[listName];
    if (!Array.isArray(list)) continue;
    for (const entry of list) {
      if (!entry || typeof entry !== 'object') continue;
      const candidate = entry as Record<string, unknown>;
      if (candidate.graphNodeId === node.id || candidate.id === node.id) {
        return candidate;
      }
    }
  }
  return null;
}

/**
 * The configuration keys this node hands to its backend node, flattened the way
 * the run reports them back, with the values that authenticate masked. Empty
 * when the node declares nothing.
 *
 * <p>The masking is the load-bearing part and it needs `nodeType`. This function
 * reads the plan entry straight out of the canvas, so it bypasses the backend
 * gate entirely: without it a `crypto_jwt` node rendered `Secret: <the HMAC
 * secret>` in the Params column, and an `http_request` rendered its whole
 * `authConfig` block as JSON. The marker is the backend's own, so a parked node
 * and a resumed one read alike instead of one key answering two ways.
 *
 * <p>Called without a `nodeType` it cannot match the per-type entries and masks
 * nothing, which is why every display call site passes one.
 */
export function collectDeclaredParams(
  node: Node<BuilderNodeData> | null | undefined,
  nodeType?: string,
): Record<string, unknown> {
  if (!node) return {};
  const entry = planEntryForNode(node);
  if (!entry) return {};
  const flattened = flattenPlannedParams(entry);
  if (!nodeType) return flattened;
  const safe: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(flattened)) {
    // TWO rules, because they catch different things. The word predicate is the backend's
    // own and covers any key whose NAME says credential, including the author-typed ones a
    // static list can never enumerate: an MCP tool argument called `token`, an agent's
    // `credentials`. The per-type list covers the ones NO word rule can see, because the
    // secret is inside a block whose own name is innocent: `http_request.authConfig` holds
    // four of them, and one-level flattening makes the block the reachable key.
    safe[key] = isCredentialKey(key) || CONFIGURED_SECRETS.has(`${nodeType}.${key}`)
      ? WITHHELD_CREDENTIAL
      : value;
  }
  return safe;
}
