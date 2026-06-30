import type { Node } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Helpers around the per-node execution policy (`nodePolicy` plan block).
 *
 * The BACKEND (`WorkflowPlanParser` / `NodePolicy.java`) is the single
 * validator. These helpers only:
 *  - normalize a raw policy into its minimal "plan-clean" shape (defaults
 *    omitted, fully-default policy → undefined), and
 *  - mirror the backend's parse-time type gating so the builder never emits a
 *    block the backend would reject.
 */

/** UI bound for the retry stepper (backend accepts any value >= 0). */
export const MAX_RETRY_COUNT = 10;

function coercePositiveInt(value: unknown): number | undefined {
  const n = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  if (typeof n !== 'number' || !Number.isFinite(n)) return undefined;
  const i = Math.floor(n);
  // 0 and negatives resolve to the default → field is omitted entirely.
  return i > 0 ? i : undefined;
}

function coerceTrue(value: unknown): boolean {
  return value === true || value === 'true';
}

/**
 * Normalizes a raw policy object into its minimal non-default shape.
 *
 * Only non-default fields are kept (no `retryCount: 0`, no `executeOnce: false`),
 * and a fully-default/empty/invalid block returns `undefined` so callers drop
 * the key entirely - plans without a policy stay byte-identical.
 *
 * Values are kept faithful (no clamping): the backend is the validator.
 */
export function sanitizeNodePolicy(raw: unknown): NodePolicy | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const source = raw as Record<string, unknown>;
  const policy: NodePolicy = {};

  const retryCount = coercePositiveInt(source.retryCount);
  if (retryCount !== undefined) policy.retryCount = retryCount;

  const retryBackoffMs = coercePositiveInt(source.retryBackoffMs);
  if (retryBackoffMs !== undefined) policy.retryBackoffMs = retryBackoffMs;

  if (coerceTrue(source.continueOnFailure)) policy.continueOnFailure = true;

  const timeoutMs = coercePositiveInt(source.timeoutMs);
  if (timeoutMs !== undefined) policy.timeoutMs = timeoutMs;

  if (coerceTrue(source.executeOnce)) policy.executeOnce = true;

  return Object.keys(policy).length > 0 ? policy : undefined;
}

/**
 * Triggers and notes never carry a policy - they are entry points /
 * annotations, not executed steps (the backend parser ignores a block there).
 */
export function nodeSupportsPolicy(node: Node<BuilderNodeData>): boolean {
  return !nodeRegistry.isTrigger(node) && !nodeRegistry.isNoteNode(node);
}

/**
 * Mirrors the backend rejection of `continueOnFailure: true` on single-port
 * branching cores (decision / switch / option): a failed branching node
 * selected no port, so continuing past the failure would fan out ALL its
 * ports at once (every branch / case / choice).
 */
export function isContinueOnFailureBlocked(node: Node<BuilderNodeData>): boolean {
  return (
    nodeRegistry.isDecisionNode(node) ||
    nodeRegistry.isSwitchNode(node) ||
    nodeRegistry.isOptionNode(node)
  );
}

/**
 * Mirrors the backend rejection of `executeOnce: true` on split / aggregate /
 * merge / loop cores: the flag filters SPLIT ITEMS, and these nodes coordinate
 * all items (or, for loop, the intent would be ambiguous).
 */
export function isExecuteOnceBlocked(node: Node<BuilderNodeData>): boolean {
  return (
    nodeRegistry.isSplitNode(node) ||
    nodeRegistry.isAggregateNode(node) ||
    nodeRegistry.isMergeNode(node) ||
    nodeRegistry.isLoopNode(node)
  );
}

/**
 * Strips the fields the backend would reject for this node type (defense in
 * depth - the inspector already disables those toggles, so this only fires on
 * hand-edited node data). Returns `undefined` when nothing is left.
 */
export function gateNodePolicyForNode(
  policy: NodePolicy | undefined,
  node: Node<BuilderNodeData>
): NodePolicy | undefined {
  if (!policy) return undefined;
  let gated = policy;
  if (gated.continueOnFailure && isContinueOnFailureBlocked(node)) {
    const { continueOnFailure: _dropped, ...rest } = gated;
    gated = rest;
  }
  if (gated.executeOnce && isExecuteOnceBlocked(node)) {
    const { executeOnce: _dropped, ...rest } = gated;
    gated = rest;
  }
  return Object.keys(gated).length > 0 ? gated : undefined;
}
