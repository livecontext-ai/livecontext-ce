import type { NodeTypeSetting } from "@/lib/api/orchestrator/node-type-settings.service";

/**
 * Clean, ordered category buckets for the admin Node Types page.
 *
 * The backend's free-text `category` column is inconsistent (e.g. "Actions",
 * "agent", "AI", "Control Flow", "core", "node", "table"), which produced messy,
 * duplicated filters. We classify by a NORMALIZED `category` FIRST - because it
 * carries the meaningful granularity (control_flow vs data_manipulation are both
 * the `core` prefix, so a prefix-only scheme would lump 35 nodes into one bucket)
 * - and fall back to the structural `variablePrefix` only when the category is
 * missing/unknown, so every node lands in exactly one of these 7 buckets.
 */
export const NODE_CATEGORY_ORDER = [
  "trigger",
  "action",
  "ai",
  "control_flow",
  "data",
  "interface",
  "utility",
] as const;

export type NodeCategory = (typeof NODE_CATEGORY_ORDER)[number];

/** i18n key (under the `nodeTypeSettings` namespace) for each bucket. */
export const NODE_CATEGORY_LABEL_KEY: Record<NodeCategory, string> = {
  trigger: "categories.trigger",
  action: "categories.action",
  ai: "categories.ai",
  control_flow: "categories.controlFlow",
  data: "categories.data",
  interface: "categories.interface",
  utility: "categories.utility",
};

// PRIMARY signal: the free-text `category`. Despite the messy naming it carries
// the meaningful granularity (control_flow vs data_manipulation are BOTH the
// `core` prefix, so the prefix alone would lump 35 nodes into one bucket). We
// normalize its casing/synonyms into a clean bucket and keep that granularity.
const RAW_CATEGORY_TO_CATEGORY: Record<string, NodeCategory> = {
  trigger: "trigger",
  triggers: "trigger",
  action: "action",
  actions: "action",
  ai: "ai",
  agent: "ai", // "agent" was a stray duplicate of AI
  agents: "ai",
  control_flow: "control_flow",
  control: "control_flow",
  data_manipulation: "data",
  data: "data",
  table: "data",
  tables: "data",
  interface: "interface",
  interfaces: "interface",
  node: "interface", // the lone "node" category is the interface node
  utility: "utility",
  core: "utility", // leftover/uncategorized core nodes (e.g. data input, option)
  other: "utility",
};

// FALLBACK: the structural node-type prefix, used only when `category` is
// missing/unknown. `core` is too coarse to bucket meaningfully → utility.
const PREFIX_TO_CATEGORY: Record<string, NodeCategory> = {
  trigger: "trigger",
  mcp: "action",
  agent: "ai",
  table: "data",
  interface: "interface",
  core: "utility",
};

function normalizeKey(value: string | null | undefined): string {
  return (value || "")
    .trim()
    .toLowerCase()
    .replace(/:+$/, "")
    .replace(/[\s-]+/g, "_");
}

/** Resolve a node type to its clean category bucket. */
export function nodeTypeCategory(
  nt: Pick<NodeTypeSetting, "variablePrefix" | "category">,
): NodeCategory {
  const raw = normalizeKey(nt.category);
  if (RAW_CATEGORY_TO_CATEGORY[raw]) {
    return RAW_CATEGORY_TO_CATEGORY[raw];
  }
  const prefix = normalizeKey(nt.variablePrefix);
  return PREFIX_TO_CATEGORY[prefix] ?? "utility";
}

/** Count node types per bucket (only buckets with ≥1 node appear). */
export function countByCategory(
  nodeTypes: Array<Pick<NodeTypeSetting, "variablePrefix" | "category">>,
): Record<NodeCategory, number> {
  const counts = {} as Record<NodeCategory, number>;
  for (const nt of nodeTypes) {
    const c = nodeTypeCategory(nt);
    counts[c] = (counts[c] || 0) + 1;
  }
  return counts;
}
