/**
 * Single source of truth for reading an agent's `tools_config` JSONB blob.
 *
 * Security rule (mirrors backend `AgentService.normalizeToolsConfig` and
 * `AgentConfigProvider.is*None`): for the 5 INTERNAL resource list keys
 * (`workflows`, `tables`, `interfaces`, `agents`, `applications`), an
 * ABSENT key MUST be treated identically to an explicit empty list `[]`
 * - i.e. NO access. Only `mode` (MCP/catalogue) is allowed to default to
 * `'all'` when absent.
 *
 * Pre-fix the frontend had ~11 inline checks like `if (!('workflows' in tc))
 * { /* materialize all tenant workflows *\/ }`. Each one was a visual leak
 * and an attack surface - the user saw chips for resources the agent did
 * NOT have explicit access to, and the modal pre-selected them on edit.
 * Route every reader through this module instead.
 */

export type InternalResourceKey =
  | 'workflows'
  | 'tables'
  | 'interfaces'
  | 'agents'
  | 'applications'
  // Files are opt-in (not in INTERNAL_RESOURCE_KEYS): an absent/empty list means
  // full org-scoped access, a non-empty list scopes the agent to those files.
  | 'files';

export const INTERNAL_RESOURCE_KEYS: readonly InternalResourceKey[] = [
  'workflows',
  'tables',
  'interfaces',
  'agents',
  'applications',
] as const;

// 'off' = NO tools at all (not even internal) - a reasoning-only judge/classifier/transformer
// agent that advertises ZERO tool schemas (AgentModuleResolver resolves it to an empty module set).
export type ToolsMode = 'all' | 'none' | 'custom' | 'off';

/**
 * Per-family ACCESS GRANT - the first of the two independent access axes the
 * backend models on `tools_config` (the second being the read/write `*AccessMode`).
 *
 * `'none'`   → the agent has NO access to the family.
 * `'all'`    → unrestricted access to every resource of the family.
 * `'custom'` → access scoped to the family's explicit id list (the `<family>: [...]`
 *              array, which is only meaningful when the grant is `'custom'`).
 *
 * Mirrors the backend `AgentConfigProvider.ToolsConfig.is<Family>None/Custom/All`:
 * an ABSENT grant resolves to `'none'` (authoritative default - `isWorkflowsNone()`
 * returns true when `workflowsGrant == null`), so the reader below defaults to it.
 */
export type ResourceGrant = 'none' | 'all' | 'custom';

/** The 5 families that carry an independent grant + read/write access mode. */
export type GrantFamily = 'workflows' | 'tables' | 'interfaces' | 'agents' | 'applications';

export const GRANT_FAMILIES: readonly GrantFamily[] = [
  'workflows',
  'tables',
  'interfaces',
  'agents',
  'applications',
] as const;

/** `<family>` → the `*AccessMode` key carrying its read/write axis. */
const ACCESS_MODE_KEY: Record<GrantFamily, string> = {
  workflows: 'workflowAccessMode',
  tables: 'tableAccessMode',
  interfaces: 'interfaceAccessMode',
  agents: 'agentAccessMode',
  applications: 'applicationAccessMode',
};

export interface ToolsConfigShape {
  mode?: ToolsMode | string;
  tools?: string[];
  workflows?: string[];
  tables?: (string | number)[];
  interfaces?: string[];
  agents?: string[];
  applications?: string[];
  webSearch?: boolean;
  imageGeneration?: boolean;
  // Per-family access GRANT (axis 1) - none|all|custom. Absent ⇒ 'none' (see getGrant).
  workflowsGrant?: ResourceGrant;
  tablesGrant?: ResourceGrant;
  interfacesGrant?: ResourceGrant;
  agentsGrant?: ResourceGrant;
  applicationsGrant?: ResourceGrant;
  // Per-resource access modes (axis 2) - orthogonal to the grant and the 5 lists
  tableAccessMode?: 'read' | 'write';
  workflowAccessMode?: 'read' | 'write';
  interfaceAccessMode?: 'read' | 'write';
  agentAccessMode?: 'read' | 'write';
  applicationAccessMode?: 'read' | 'write';
  skillAccessMode?: 'read' | 'write';
  // Files read/write axis. Unlike the 5 grant families, files have NO grant axis
  // (opt-in scoping, absent/empty list = full org access); this mode is orthogonal -
  // 'read' lets the agent list/get/view files but blocks the write actions
  // (create_folder/move_to_folder). Absent ⇒ 'write' (see getFileAccessMode).
  fileAccessMode?: 'read' | 'write';
  [k: string]: unknown;
}

function asObject(tc: unknown): ToolsConfigShape | null {
  return tc && typeof tc === 'object' && !Array.isArray(tc) ? (tc as ToolsConfigShape) : null;
}

/**
 * Returns the explicit ID list for an internal resource category.
 * Absent key, non-array value, or `null`/`undefined` toolsConfig → `[]`.
 * Numeric IDs (e.g. tables) are coerced to string for stable comparison.
 */
export function getAllowedIds(tc: unknown, key: InternalResourceKey): string[] {
  const obj = asObject(tc);
  if (!obj) return [];
  const raw = obj[key];
  if (!Array.isArray(raw)) return [];
  return raw.map(v => String(v));
}

/**
 * True iff the agent has explicit access to `id` under `key`.
 * Returns false on absent key / null toolsConfig - never grants by default.
 */
export function isAllowed(tc: unknown, key: InternalResourceKey, id: string | number): boolean {
  const list = getAllowedIds(tc, key);
  return list.includes(String(id));
}

/**
 * Per-family access GRANT (axis 1: none|all|custom).
 *
 * Absent / null / unrecognized value ⇒ `'none'` - the backend-authoritative
 * default (`AgentConfigProvider.ToolsConfig.is<Family>None()` treats a null grant
 * as `none`). Only an explicit `'all'` or `'custom'` widens access, so a row that
 * somehow escaped the backfill can only LOSE access, never silently gain it.
 */
export function getGrant(tc: unknown, family: GrantFamily): ResourceGrant {
  const obj = asObject(tc);
  const g = obj?.[`${family}Grant`];
  if (g === 'all' || g === 'custom' || g === 'none') return g;
  return 'none';
}

/**
 * Per-family READ/WRITE access mode (axis 2: read|write), orthogonal to the grant.
 *
 * Absent / null / unrecognized value ⇒ `'write'` - the backend default
 * (`AgentModuleResolver` treats a missing `*AccessMode` as full read/write). A
 * family stored+enforced as read-only carries an explicit `'read'`.
 */
export function getAccessMode(tc: unknown, family: GrantFamily): 'read' | 'write' {
  const obj = asObject(tc);
  const m = obj?.[ACCESS_MODE_KEY[family]];
  return m === 'read' ? 'read' : 'write';
}

/**
 * Files read/write access mode. Files are not a grant family (no none/all/custom),
 * but they carry the same orthogonal read/write axis as the 5 families. Absent /
 * null / unrecognized ⇒ `'write'` - the backend default (`ToolAccessControl` treats
 * a missing `fileAccessMode` as full read/write). `'read'` lets the agent
 * list/get/view files but blocks the write actions (create_folder / move_to_folder).
 */
export function getFileAccessMode(tc: unknown): 'read' | 'write' {
  const obj = asObject(tc);
  return obj?.fileAccessMode === 'read' ? 'read' : 'write';
}

/**
 * MCP/catalogue tool mode. Defaults to `'all'` when absent - this IS the
 * documented product behavior (per AgentHelpModule), and MCP is the only
 * surface where absent → all is allowed.
 */
export function getToolsMode(tc: unknown): ToolsMode {
  const obj = asObject(tc);
  const m = obj?.mode;
  if (m === 'none' || m === 'custom') return m;
  return 'all';
}

/**
 * Web search defaults to enabled when absent (product behavior, mirrors
 * backend `AgentDefaultsConfig`).
 */
export function isWebSearchEnabled(tc: unknown): boolean {
  const obj = asObject(tc);
  return obj?.webSearch !== false;
}

/**
 * Image generation is opt-IN - defaults to disabled when absent (mirrors backend
 * `AgentModuleResolver.isImageGenerationEnabled`). Tolerates both the boolean
 * (`imageGeneration: true`) and object (`{enabled: true, ...}`) shapes; an object
 * without an explicit `enabled` is treated as enabled.
 */
export function isImageGenerationEnabled(tc: unknown): boolean {
  const obj = asObject(tc);
  if (!obj) return false;
  const raw = obj.imageGeneration as unknown;
  if (raw === undefined || raw === null) return false;
  if (typeof raw === 'boolean') return raw;
  if (typeof raw === 'object') {
    const enabled = (raw as Record<string, unknown>).enabled;
    return enabled === undefined ? true : enabled === true;
  }
  return false;
}

/**
 * Build a complete toolsConfig payload for the agent create/update REST call.
 * Always emits the 5 internal keys explicitly, matching the backend's
 * `normalizeToolsConfig` chokepoint - frontend-side defense-in-depth so a
 * REST PUT can't accidentally wipe a list.
 */
export function buildToolsConfigPayload(input: {
  mode: ToolsMode;
  tools?: string[];
  workflows: string[];
  tables: (string | number)[];
  interfaces: string[];
  agents: string[];
  applications: string[];
  /**
   * Per-family access GRANT (axis 1). Absent ⇒ `'none'` (the family is denied
   * unless the caller explicitly widens it). When a grant is `'custom'` the
   * matching id list above is emitted as the scope; for `'all'`/`'none'` the list
   * is irrelevant and emitted as `[]` (a placeholder the backend preserves
   * alongside the grant - the grant drives, the list is just payload).
   */
  workflowsGrant?: ResourceGrant;
  tablesGrant?: ResourceGrant;
  interfacesGrant?: ResourceGrant;
  agentsGrant?: ResourceGrant;
  applicationsGrant?: ResourceGrant;
  /**
   * File allow-list (opt-in). Emitted explicitly so deselecting all on edit
   * clears a prior scope; an empty array means full org file access (not deny),
   * the inverse of the 5 internal keys above. See [[FilesToolsProvider]].
   */
  files?: string[];
  webSearch?: boolean;
  imageGeneration?: boolean;
  tableAccessMode?: 'read' | 'write';
  workflowAccessMode?: 'read' | 'write';
  interfaceAccessMode?: 'read' | 'write';
  agentAccessMode?: 'read' | 'write';
  applicationAccessMode?: 'read' | 'write';
  skillAccessMode?: 'read' | 'write';
  fileAccessMode?: 'read' | 'write';
}): ToolsConfigShape {
  // For a `custom` grant the id list IS the scope; for `all`/`none`/absent the
  // list is a placeholder the backend keeps but never reads - emit `[]` so the
  // intent (the grant) is the single source of truth and a stale selection never
  // leaks through when the grant is widened to `all` or narrowed to `none`.
  const grantedList = <T>(grant: ResourceGrant | undefined, list: T[]): T[] =>
    grant === 'custom' ? list : ([] as T[]);

  const payload: ToolsConfigShape = {
    mode: input.mode,
    workflows: grantedList(input.workflowsGrant, input.workflows),
    tables: grantedList(input.tablesGrant, input.tables),
    interfaces: grantedList(input.interfacesGrant, input.interfaces),
    agents: grantedList(input.agentsGrant, input.agents),
    applications: grantedList(input.applicationsGrant, input.applications),
    // Per-family grant sentinel (axis 1) - always emitted so the row is
    // self-describing and a widen-to-all / narrow-to-none persists through the
    // backend merge. Absent caller value ⇒ 'none' (deny by default).
    workflowsGrant: input.workflowsGrant ?? 'none',
    tablesGrant: input.tablesGrant ?? 'none',
    interfacesGrant: input.interfacesGrant ?? 'none',
    agentsGrant: input.agentsGrant ?? 'none',
    applicationsGrant: input.applicationsGrant ?? 'none',
    // Files: opt-in scope. Always emitted so an edit that clears the selection
    // resets to full access; [] is treated as unrestricted downstream.
    files: input.files ?? [],
  };
  // `tools` only meaningful when mode='custom'; mode='none' carries an explicit
  // empty array to make the intent visible in DB inspection. mode='all' omits it.
  if (input.mode === 'custom') payload.tools = input.tools ?? [];
  else if (input.mode === 'none') payload.tools = [];
  // Optional flags - emitted whenever the caller passes an explicit value (the
  // modal always knows the current toggle state). Emitting both true and false
  // matters because the backend MERGES toolsConfig on update: omitting a flag
  // keeps the prior value, so turning a flag OFF must send `false` (otherwise an
  // agent that had it ON could never be switched off).
  if (input.webSearch !== undefined) payload.webSearch = input.webSearch;
  if (input.imageGeneration !== undefined) payload.imageGeneration = input.imageGeneration;
  // Per-family read/write access mode (axis 2) - emit for BOTH 'read' AND 'write'.
  // The pre-fix code emitted only on 'read' (omit-on-write); combined with the
  // backend's merge-on-update that made a read→write toggle never persist (the
  // omitted field kept the stored 'read'). Always emitting the explicit value
  // fixes that: write is now sent as 'write', not silently dropped.
  if (input.tableAccessMode) payload.tableAccessMode = input.tableAccessMode;
  if (input.workflowAccessMode) payload.workflowAccessMode = input.workflowAccessMode;
  if (input.interfaceAccessMode) payload.interfaceAccessMode = input.interfaceAccessMode;
  if (input.agentAccessMode) payload.agentAccessMode = input.agentAccessMode;
  if (input.applicationAccessMode) payload.applicationAccessMode = input.applicationAccessMode;
  if (input.skillAccessMode) payload.skillAccessMode = input.skillAccessMode;
  if (input.fileAccessMode) payload.fileAccessMode = input.fileAccessMode;
  return payload;
}
