/**
 * Node-type tokens: the vocabulary the node-type filter speaks.
 *
 * A token is what the backend stores in `node_types` (see
 * `WorkflowNodeTypeExtractor.java`) and what the list endpoints filter on:
 *
 *   trigger:webhook   mcp:gmail   core:loop   agent:agent   table:create-row   interface
 *
 * This module turns a token back into something a person can read and click:
 * a family (for grouping), a label, and the icon props of the SAME glyph the
 * workflow cards already draw. It resolves both from the existing registries
 * rather than a new table - the palette's `NODE_CLASSES` for labels,
 * `KIND_TO_NODE_ICON_KEY` for trigger naming - so the picker can never drift
 * into calling a node something the rest of the product doesn't.
 *
 * <p>Both are leaf imports on purpose. Reading the trigger map off the dashboard
 * service (where it used to live) pulled the HTTP client in behind it and, via
 * the `lib/api/orchestrator` barrel, closed an import cycle that left the API
 * facade half-initialised - which surfaced as an unrelated page failing to
 * mount, not as an import error.
 */

import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { KIND_TO_NODE_ICON_KEY, type TriggerType } from '@/lib/workflows/triggerNodeIcons';
import type { NodeIconProps } from '@/app/workflows/builder/components/nodes/shared';

/** The families a token can belong to, in the order the picker lists them. */
export const NODE_TYPE_FAMILIES = [
  'trigger',
  'mcp',
  'agent',
  'core',
  'table',
  'interface',
] as const;

export type NodeTypeFamily = (typeof NODE_TYPE_FAMILIES)[number];

/**
 * Agent subtype -> palette node id. Mirror of `AGENT_TYPE_TO_NODE_ID` in
 * `WorkflowIconExtractor.java`; an unknown subtype falls back to the plain
 * agent glyph exactly as the backend does.
 */
const AGENT_TYPE_TO_NODE_ID: Record<string, string> = {
  agent: 'ai-agent',
  guardrail: 'guardrail',
  classify: 'classify',
  browser_agent: 'browser_agent',
  generate: 'generate',
};

/**
 * Table subtype -> palette node id, for the one CRUD operation whose stored type
 * and palette id disagree.
 *
 * Five of the six line up once "crud-" is stripped (`crud-create-row` ->
 * `create-row`, ...). The sixth is stored as `crud-find` (see `Step.java`) and
 * registered as `find-row` (`nodeClasses.ts`), so without this entry the option
 * would read "Find" with a fallback glyph while the canvas says "Find Rows".
 */
const TABLE_TYPE_TO_NODE_ID: Record<string, string> = {
  find: 'find-row',
};

/**
 * Core subtype -> palette node id, for the four core types whose stored type and
 * palette id disagree.
 *
 * Of the rest of `Core.VALID_TYPES`, all match their palette id when read
 * directly except `end` and `option`, which have no palette entry at all
 * (`option` is commented out in nodeClasses.ts). Those two prettify to "End" and
 * "Option", which is what they should read anyway, so they need no alias.
 *
 * Without these four the picker invents names by prettifying the raw type -
 * "Decision", "Loop", "Approval" - for the two most common branch and loop
 * nodes, which the canvas calls If / else and While. Worse than cosmetic: the
 * search field matches on the label, so typing "while" would find nothing.
 *
 * Safe for the glyph too - `NODE_ICON_REGISTRY` carries both spellings.
 */
const CORE_TYPE_TO_NODE_ID: Record<string, string> = {
  decision: 'if-else',
  loop: 'while-group',
  http_request: 'http-request',
  approval: 'user-approval',
};

export interface ParsedNodeType {
  /** The whole token, unchanged - what gets sent back to the server. */
  value: string;
  family: NodeTypeFamily;
  /** The part after the colon, or `''` for the family-only `interface` token. */
  subtype: string;
}

/**
 * Split a token into family + subtype.
 *
 * <p>An unrecognised prefix is filed under `core` rather than dropped: a token
 * this build does not know about still exists on real rows, and hiding it from
 * the picker would make those rows unreachable by the only filter that could
 * find them.
 */
export function parseNodeType(token: string): ParsedNodeType {
  const value = token.trim().toLowerCase();
  if (value === 'interface') {
    return { value, family: 'interface', subtype: '' };
  }
  const colon = value.indexOf(':');
  if (colon > 0) {
    const prefix = value.slice(0, colon);
    const subtype = value.slice(colon + 1);
    if ((NODE_TYPE_FAMILIES as readonly string[]).includes(prefix)) {
      return { value, family: prefix as NodeTypeFamily, subtype };
    }
  }
  return { value, family: 'core', subtype: value };
}

/**
 * The palette node id a token maps to, or `null` for `mcp:` tokens (an
 * integration is not a node class - it is one of hundreds of catalog APIs).
 *
 * Mirrors the id conventions of `WorkflowIconExtractor.java`, which is what
 * makes one mapping serve both the label and the icon.
 */
export function nodeTypeToNodeId(parsed: ParsedNodeType): string | null {
  switch (parsed.family) {
    case 'trigger': {
      const key = parsed.subtype.toUpperCase() as TriggerType;
      return KIND_TO_NODE_ICON_KEY[key] ?? `${parsed.subtype}-trigger`;
    }
    case 'agent':
      return AGENT_TYPE_TO_NODE_ID[parsed.subtype] ?? 'ai-agent';
    case 'table':
      return TABLE_TYPE_TO_NODE_ID[parsed.subtype] ?? parsed.subtype;
    case 'core':
      return CORE_TYPE_TO_NODE_ID[parsed.subtype] ?? parsed.subtype;
    case 'interface':
      return 'interface';
    case 'mcp':
      return null;
  }
}

/**
 * Turn a slug into a readable name: `google_ads` -> `Google Ads`.
 * Only used where nothing better exists (an integration slug, or a node type
 * this build has no palette entry for).
 */
function prettifySlug(slug: string): string {
  return slug
    .split(/[_\-.]+/)
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

/**
 * What to show on the option. Prefers the palette's own label, so the filter
 * calls a node exactly what the canvas calls it.
 */
export function nodeTypeLabel(parsed: ParsedNodeType): string {
  const nodeId = nodeTypeToNodeId(parsed);
  if (nodeId) {
    const label = findNodeClassById(nodeId)?.label;
    if (label) return label;
  }
  return prettifySlug(parsed.subtype || parsed.value);
}

/**
 * Icon props for the token, matching what `WorkflowIconExtractor` emits into
 * `node_icons` for the same node - so an option in the picker and the glyph on
 * the card below it are the same drawing.
 */
export function nodeTypeIconProps(parsed: ParsedNodeType): Partial<NodeIconProps> {
  switch (parsed.family) {
    case 'mcp':
      return { iconSlug: parsed.subtype, isMcp: true };
    case 'trigger':
      return { nodeId: nodeTypeToNodeId(parsed) ?? undefined, nodeKind: 'entry' };
    case 'agent':
      return { nodeId: nodeTypeToNodeId(parsed) ?? undefined };
    case 'table':
      // nodeId only. WorkflowIconExtractor also emits a `crudOperation` key, but
      // NodeIcon declares no such prop and nothing reads it - copying it here
      // would just be cargo.
      return { nodeId: nodeTypeToNodeId(parsed) ?? parsed.subtype };
    case 'interface':
      return { nodeId: 'interface', nodeKind: 'interface' };
    case 'core':
    default:
      // nodeId alone. The registry resolves every core type (both the raw
      // spelling and the palette one), so passing nodeKind as well would mean
      // casting an arbitrary string into that union for no gain.
      return { nodeId: nodeTypeToNodeId(parsed) ?? parsed.subtype };
  }
}

/**
 * Does this token match what the user typed in the picker's search field?
 *
 * <p>Matches the LABEL and the RAW TOKEN both. Typing "gmail" has to find the
 * Gmail option, but so does typing "mcp:" to see every integration at once, and
 * so does pasting a token straight out of an agent's `node_types` response.
 */
export function nodeTypeMatchesSearch(parsed: ParsedNodeType, needle: string): boolean {
  const search = needle.trim().toLowerCase();
  if (!search) return true;
  return parsed.value.includes(search) || nodeTypeLabel(parsed).toLowerCase().includes(search);
}

/**
 * Group tokens by family, keeping `NODE_TYPE_FAMILIES` order and dropping
 * families with nothing in them. Options keep the order they came in, which is
 * the server's (most-used first).
 */
export function groupNodeTypesByFamily<T extends { value: string }>(
  items: T[]
): { family: NodeTypeFamily; items: (T & { parsed: ParsedNodeType })[] }[] {
  const byFamily = new Map<NodeTypeFamily, (T & { parsed: ParsedNodeType })[]>();
  for (const item of items) {
    const parsed = parseNodeType(item.value);
    const bucket = byFamily.get(parsed.family);
    const entry = { ...item, parsed };
    if (bucket) bucket.push(entry);
    else byFamily.set(parsed.family, [entry]);
  }
  return NODE_TYPE_FAMILIES.filter((family) => byFamily.has(family)).map((family) => ({
    family,
    items: byFamily.get(family)!,
  }));
}
