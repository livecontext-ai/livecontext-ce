/**
 * Collect the catalog tool identifiers a workflow plan references.
 *
 * Twin of publication-service's `CustomApiPublishGuard.collectToolIdentifiers`: the
 * publish surfaces feed this list to `customApiService.findRefs` so they can warn about
 * a custom API BEFORE submitting. Keep both walks in step.
 *
 * Identifiers are returned verbatim in the forms these fields hold: an mcp node's
 * `apiSlug/toolSlug`, an agent tool grant's `apiSlug:toolSlug` or its legacy `api_tools.id`
 * UUID, and a bare tool slug. catalog-service resolves all of them. Names that belong to no
 * catalog API at all (core tools, `web_search`, ...) resolve to nothing.
 *
 * KNOWN LIMIT, deliberate: a SAVED plan carries neither an agent's own catalog tool grant
 * (the node only holds normalised `mcp:<label>` refs, which point at mcp nodes already in
 * this plan) nor the sub-workflow plans. Both only materialise server-side, during publish
 * enrichment, and publication-service re-checks them there. So this warning covers the
 * common case; the rarer ones surface as the publish-time refusal message instead of a
 * pre-check. The `toolsConfig` / `_snapshot_agent_toolsConfig` handling below exists so an
 * already-enriched snapshot (e.g. an acquired application's plan) is covered too.
 */

/** Keys whose value is a list of mcp node objects carrying an `id`. */
const MCP_KEY = 'mcps';
/** Keys whose value is an agent tools config carrying an explicit `tools` grant. */
const TOOLS_CONFIG_KEYS = ['toolsConfig', '_snapshot_agent_toolsConfig'];
/** Same bound as the Java twin's MAX_DEPTH: no legitimate plan nests anywhere near this. */
const MAX_DEPTH = 1000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function addIfText(value: unknown, out: Set<string>): void {
  if (typeof value !== 'string') return;
  const text = value.trim();
  if (text) out.add(text);
}

function addMcpNodeIds(mcps: unknown, out: Set<string>): void {
  if (!Array.isArray(mcps)) return;
  for (const node of mcps) {
    if (isRecord(node)) addIfText(node.id, out);
  }
}

function addGrantedTools(toolsConfig: unknown, out: Set<string>): void {
  if (!isRecord(toolsConfig)) return;
  const tools = toolsConfig.tools;
  if (!Array.isArray(tools)) return;
  for (const tool of tools) {
    if (isRecord(tool)) {
      // Tolerate the object form some payloads use: {id|toolSlug: "..."}.
      addIfText(tool.id, out);
      addIfText(tool.toolSlug, out);
    } else {
      addIfText(tool, out);
    }
  }
}

function walk(node: unknown, out: Set<string>, depth: number): void {
  // Depth bound, matching the Java twin: a pathological plan must not throw a RangeError
  // into the caller, whose catch would then report the workflow as having no interface.
  if (depth > MAX_DEPTH) return;
  if (Array.isArray(node)) {
    for (const item of node) walk(item, out, depth + 1);
    return;
  }
  if (!isRecord(node)) return;
  for (const [key, value] of Object.entries(node)) {
    if (key === MCP_KEY) {
      addMcpNodeIds(value, out);
    } else if (TOOLS_CONFIG_KEYS.includes(key)) {
      addGrantedTools(value, out);
    }
    walk(value, out, depth + 1);
  }
}

/**
 * Every catalog tool identifier the plan references, at any depth (including agent
 * tool grants and, on an already-enriched snapshot, sub-workflow plans).
 */
export function collectPlanToolIdentifiers(plan: unknown): string[] {
  const identifiers = new Set<string>();
  walk(plan, identifiers, 0);
  return [...identifiers];
}
