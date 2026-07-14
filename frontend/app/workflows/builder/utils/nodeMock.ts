import type { Node } from 'reactflow';
import type { BuilderNodeData, NodeMock } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Helpers around the per-node mock block (`mock` plan block, sibling of
 * `nodePolicy`).
 *
 * The BACKEND (`WorkflowPlanParser` / mock support) is the single validator.
 * These helpers only:
 *  - normalize a raw mock into its minimal "plan-clean" shape (defaults
 *    omitted, empty block → undefined), and
 *  - mirror the backend's parse-time type gating so the builder never emits a
 *    block the backend would reject.
 */

const VALID_SOURCES = new Set(['static', 'catalog_example', 'error']);

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/**
 * Normalizes a raw mock object into its minimal shape.
 *
 * Kept fields:
 *  - `enabled` only when explicitly false (true is the default → omitted),
 *  - `source` only when one of static | catalog_example | error,
 *  - `output` only when a plain object,
 *  - `port` only when a non-empty string,
 *  - `error` only when an object with a non-empty string message
 *    (its optional `output` is kept when a plain object).
 *
 * Returns `undefined` when nothing meaningful remains: an empty block = no
 * mock, so plans without one stay byte-identical.
 */
export function sanitizeNodeMock(raw: unknown): NodeMock | undefined {
  if (!isPlainObject(raw)) return undefined;
  const source = raw as Record<string, unknown>;
  const mock: NodeMock = {};

  if (source.enabled === false) mock.enabled = false;

  if (typeof source.source === 'string' && VALID_SOURCES.has(source.source)) {
    mock.source = source.source as NodeMock['source'];
  }

  if (isPlainObject(source.output)) {
    mock.output = source.output as Record<string, unknown>;
  }

  if (typeof source.port === 'string' && source.port.trim() !== '') {
    mock.port = source.port;
  }

  if (isPlainObject(source.error)) {
    const err = source.error as Record<string, unknown>;
    if (typeof err.message === 'string' && err.message.trim() !== '') {
      mock.error = {
        message: err.message,
        ...(isPlainObject(err.output)
          ? { output: err.output as Record<string, unknown> }
          : {}),
      };
    }
  }

  // A lone `enabled: false` (or nothing at all) carries no mock content.
  const contentKeys = Object.keys(mock).filter((k) => k !== 'enabled');
  return contentKeys.length > 0 ? mock : undefined;
}

/**
 * Mocks are FORBIDDEN on split / merge / aggregate / loop / fork cores: they
 * coordinate parallel contexts and the engine cannot substitute their output.
 */
export function isMockBlockedCore(node: Node<BuilderNodeData>): boolean {
  return (
    nodeRegistry.isSplitNode(node) ||
    nodeRegistry.isMergeNode(node) ||
    nodeRegistry.isAggregateNode(node) ||
    nodeRegistry.isLoopNode(node) ||
    nodeRegistry.isForkNode(node)
  );
}

/**
 * Triggers and notes never carry a mock (entry points / annotations, not
 * executed steps), and the blocked coordinator cores are excluded too.
 */
export function nodeSupportsMock(node: Node<BuilderNodeData>): boolean {
  return (
    !nodeRegistry.isTrigger(node) &&
    !nodeRegistry.isNoteNode(node) &&
    !isMockBlockedCore(node)
  );
}

/**
 * Nodes whose mock may carry a `port` (branch to take): decision / switch /
 * option / approval cores and classify agents.
 */
export function isPortSelectingNode(node: Node<BuilderNodeData>): boolean {
  return (
    nodeRegistry.isDecisionNode(node) ||
    nodeRegistry.isSwitchNode(node) ||
    nodeRegistry.isOptionNode(node) ||
    nodeRegistry.isUserApprovalNode(node) ||
    nodeRegistry.isClassifyNode(node)
  );
}

/**
 * An mcp catalog tool node: bound to a catalog tool (slug or UUID tool id).
 * Only these support `source: 'catalog_example'` (the engine serves the
 * tool's default example response).
 */
export function isMcpCatalogToolNode(node: Node<BuilderNodeData>): boolean {
  const toolData = (node.data as BuilderNodeData & { toolData?: { toolId?: string; toolSlug?: string } })
    ?.toolData;
  return !!toolData && (!!toolData.toolId || !!toolData.toolSlug);
}

/**
 * Strips the fields the backend would reject for this node type (defense in
 * depth - the inspector already gates them, so this only fires on hand-edited
 * node data). Returns `undefined` when the node cannot carry a mock or when
 * nothing meaningful remains.
 */
export function gateNodeMockForNode(
  mock: NodeMock | undefined,
  node: Node<BuilderNodeData>
): NodeMock | undefined {
  if (!mock) return undefined;
  if (!nodeSupportsMock(node)) return undefined;
  let gated = mock;
  if (gated.source === 'catalog_example' && !isMcpCatalogToolNode(node)) {
    const { source: _dropped, ...rest } = gated;
    gated = rest;
  }
  if (gated.port !== undefined && !isPortSelectingNode(node)) {
    const { port: _dropped, ...rest } = gated;
    gated = rest;
  }
  const contentKeys = Object.keys(gated).filter((k) => k !== 'enabled');
  return contentKeys.length > 0 ? gated : undefined;
}

/**
 * The backend rejects a STATIC mock without a `port` on a port-selecting node
 * at parse time ("a decision mock must select a branch"), which would fail
 * every subsequent editor run. This guard keeps UI-authored mocks valid: on a
 * port-selecting node, a static-output mock without a port gets the node's
 * first port. Non-static mocks (catalog_example / error) and non-branching
 * nodes pass through untouched.
 */
export function ensureMockPort(
  mock: NodeMock | undefined,
  node: Node<BuilderNodeData>
): NodeMock | undefined {
  if (!mock) return undefined;
  if (mock.port !== undefined) return mock;
  if (mock.source === 'catalog_example' || mock.error !== undefined) return mock;
  if (mock.output === undefined) return mock;
  if (!isPortSelectingNode(node)) return mock;
  const ports = nodePortOptions(node);
  if (ports.length === 0) return mock;
  return { ...mock, port: ports[0] };
}

/**
 * Derives the list of valid mock ports for a port-selecting node from its
 * builder data. Port naming mirrors edgeProcessor's handle → port mapping:
 *  - decision: if / elseif_N (index among elseifs) / else
 *  - switch:   case_N (array index) / default
 *  - option:   choice_N
 *  - approval: approved / rejected / timeout (fixed)
 *  - classify: category_N
 * Returns [] for non-port-selecting nodes.
 */
export function nodePortOptions(node: Node<BuilderNodeData>): string[] {
  const data = node.data || ({} as BuilderNodeData);

  if (nodeRegistry.isDecisionNode(node)) {
    const conditions = data.decisionConditions || [];
    let elseifIndex = 0;
    const ports: string[] = [];
    for (const condition of conditions) {
      if (condition.type === 'if') ports.push('if');
      else if (condition.type === 'elseif') ports.push(`elseif_${elseifIndex++}`);
      else if (condition.type === 'else') ports.push('else');
    }
    return ports.length > 0 ? ports : ['if', 'else'];
  }

  if (nodeRegistry.isSwitchNode(node)) {
    const cases = data.switchCases || [];
    const ports = cases.map((c, i) => (c.type === 'default' ? 'default' : `case_${i}`));
    return ports.length > 0 ? ports : ['default'];
  }

  if (nodeRegistry.isOptionNode(node)) {
    const choices = data.optionChoices || [];
    return choices.map((_, i) => `choice_${i}`);
  }

  if (nodeRegistry.isUserApprovalNode(node)) {
    return ['approved', 'rejected', 'timeout'];
  }

  if (nodeRegistry.isClassifyNode(node)) {
    const categories = data.classifyCategories || [];
    return categories.map((_, i) => `category_${i}`);
  }

  return [];
}

/**
 * Engine-added marker keys inside a persisted MOCKED step output. Stripped
 * when copying a run output back into a node's mock (`Use as mock output`),
 * so the copied mock is clean user data.
 */
export const MOCK_OUTPUT_MARKER_KEYS = ['__mocked__', '__mock_source__'] as const;

/** Returns true when a loaded run output object was produced by a mock. */
export function isMockedOutput(output: unknown): boolean {
  return isPlainObject(output) && (output as Record<string, unknown>).__mocked__ === true;
}

/** Shallow-copies an output object without the engine's mock marker keys. */
export function stripMockMarkers(output: unknown): Record<string, unknown> | undefined {
  if (!isPlainObject(output)) return undefined;
  const copy: Record<string, unknown> = { ...(output as Record<string, unknown>) };
  for (const key of MOCK_OUTPUT_MARKER_KEYS) {
    delete copy[key];
  }
  return copy;
}
