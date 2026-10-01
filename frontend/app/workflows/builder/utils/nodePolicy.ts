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

/** Most retries a node may ask for (the backend refuses more on write and clamps a stored plan). */
export const MAX_RETRY_COUNT = 10;

/** Longest wait before one attempt, in ms (60 seconds), mirrored from the backend cap. */
export const MAX_RETRY_BACKOFF_MS = 60_000;

/** The only non-default `retryOn`: retry only when the provider signals a rate limit (429, 503, or a 4xx with a Retry-After or a rate-limit message). */
export const RETRY_ON_RATE_LIMIT = 'rate_limit' as const;

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
 * `retryCount` and `retryBackoffMs` are clamped to the backend caps, which the engine
 * applies at run time anyway, so what the inspector shows is what runs.
 */
export function sanitizeNodePolicy(raw: unknown): NodePolicy | undefined {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const source = raw as Record<string, unknown>;
  const policy: NodePolicy = {};

  const retryCount = coercePositiveInt(source.retryCount);
  if (retryCount !== undefined) policy.retryCount = Math.min(retryCount, MAX_RETRY_COUNT);

  const retryBackoffMs = coercePositiveInt(source.retryBackoffMs);
  if (retryBackoffMs !== undefined) policy.retryBackoffMs = Math.min(retryBackoffMs, MAX_RETRY_BACKOFF_MS);

  if (source.retryOn === RETRY_ON_RATE_LIMIT) policy.retryOn = RETRY_ON_RATE_LIMIT;

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
 * Mirrors the backend rejection of `continueOnFailure: true` on every node that
 * picks where the run goes next: decision / switch / option cores (refused when
 * the plan is parsed), loop cores and classify / guardrail agents (refused by
 * the builder tools, ignored at run time). A failed one selected no port, so
 * continuing past the failure would fan out ALL its ports at once (every branch,
 * case, choice or category; a loop's body AND its exit).
 */
export function isContinueOnFailureBlocked(node: Node<BuilderNodeData>): boolean {
  return (
    nodeRegistry.isDecisionNode(node) ||
    nodeRegistry.isSwitchNode(node) ||
    nodeRegistry.isOptionNode(node) ||
    nodeRegistry.isLoopNode(node) ||
    nodeRegistry.isClassifyNode(node) ||
    nodeRegistry.isGuardrailNode(node)
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
 * `retryOn` reads the HTTP status of the provider's answer, which only a catalog tool step
 * reports, so the backend refuses it anywhere else (and without retries). Delegates to the one
 * authority on "this node becomes a tool step".
 */
export function supportsRetryOn(node: Node<BuilderNodeData>): boolean {
  return isToolStepNode(node);
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
  if (gated.retryOn && (!supportsRetryOn(node) || !gated.retryCount)) {
    const { retryOn: _dropped, ...rest } = gated;
    gated = rest;
  }
  return Object.keys(gated).length > 0 ? gated : undefined;
}
