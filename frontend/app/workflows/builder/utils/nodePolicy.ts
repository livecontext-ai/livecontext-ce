import type { Node } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';
import { isToolStepNode } from './planHelpers';

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

/**
 * True when this node is executed as a catalog tool call, which is the only place the
 * provider-retry budget means anything (`StepNode` is what carries it to the catalog).
 *
 * Delegates to `isToolStepNode`, the predicate that decides which canvas nodes become `plan.mcps`
 * entries. That is deliberate and load-bearing: a second, similar-looking test disagreed with it in
 * both directions, and the direction that mattered was offering the field on a node whose setting
 * could never reach a plan entry at all.
 */
export function nodeCallsProvider(node: Node<BuilderNodeData>): boolean {
  return !!node && isToolStepNode(node);
}

/** Like {@link coercePositiveInt} but keeps 0, for the one field where 0 is a real value. */
function coerceNonNegativeInt(value: unknown): number | undefined {
  const n = typeof value === 'string' && value.trim() !== '' ? Number(value) : value;
  if (typeof n !== 'number' || !Number.isFinite(n)) return undefined;
  const i = Math.floor(n);
  return i >= 0 ? i : undefined;
}

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

  // The one field where 0 is a STATEMENT, not a default: it means "do not retry the provider
  // call, this node paces itself". Every field above resolves 0 to "unset" and drops it, which
  // here would make "off" unexpressible - the setting would silently fall back to the platform
  // budget it was added to override.
  const providerRetryMaxWaitSec = coerceNonNegativeInt(source.providerRetryMaxWaitSec);
  if (providerRetryMaxWaitSec !== undefined) {
    policy.providerRetryMaxWaitSec = providerRetryMaxWaitSec;
  }

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
  // providerRetryMaxWaitSec is deliberately NOT gated. It is inert on a node the engine does not
  // execute as a tool call, and the backend simply never reads it there, whereas dropping it here
  // would run on every save: opening an agent-built workflow and saving it would silently delete a
  // setting nobody asked to remove. An inert field is a much smaller problem than that.
  return Object.keys(gated).length > 0 ? gated : undefined;
}
