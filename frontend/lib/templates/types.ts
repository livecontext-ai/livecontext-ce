/**
 * Starter templates for workflows and agents.
 *
 * A template is a checked-in JSON file that seeds a new workflow or agent. Its
 * purpose is as much pedagogical as practical: workflow templates carry `note:`
 * nodes on the canvas that explain the concept they illustrate (triggers, ports,
 * fork/merge, split, nodePolicy, the error trigger), so a newcomer learns the
 * system by opening one rather than by reading docs that do not exist yet.
 *
 * TRANSLATION RULE, the single most important invariant here:
 *
 *   A node `label` is an IDENTIFIER and is NEVER translated.
 *
 * `edges[].from/to` reference nodes by NORMALIZED LABEL (see `LabelNormalizer`
 * backend-side and `normalizeLabel` in the builder), and so do expressions like
 * `{{core:build_greeting.output.x}}`. Translating "Build greeting" would break
 * every edge and every expression in the template. Labels therefore stay short
 * English identifiers, and ALL of the teaching happens in the notes, which ARE
 * translated. One of the notes even explains this, turning the constraint into
 * a lesson.
 *
 * Consequently the only human prose in a template JSON file is an i18n KEY.
 * Never a sentence. `hydrate.ts` resolves those keys against next-intl.
 */

/** Fields of a template whose value is an i18n key, not display text. */
export type I18nKey = string;

export type TemplateKind = 'workflow' | 'agent' | 'table';

export type TemplateDifficulty = 'beginner' | 'intermediate' | 'advanced';

export interface TemplateMeta {
  /** Stable, unique per kind. Used as the React key and in the i18n namespace. */
  slug: string;
  kind: TemplateKind;
  /** Ascending display order within a kind. Unique per kind. */
  order: number;
  difficulty: TemplateDifficulty;
  /** Key into TEMPLATE_ICONS. Not a lucide import by name (that defeats tree-shaking). */
  icon: string;
  /**
   * False when the template cannot run as-is because it needs user input first
   * (an URL to call, a parent workflow to watch). The card shows a distinct
   * badge: a first run that fails red would discourage exactly the beginner we
   * are trying to help.
   */
  runnable: boolean;
  /** Number of `teaches` bullet points to render. Keys are `<ns>.teaches.0..n-1`. */
  teachesCount: number;
  /**
   * Workflow templates: the node types, in flow order, that the card preview
   * draws as icons (the same treatment a real workflow card gets).
   *
   * Lives in the meta rather than being derived from the plan so the card can
   * render without loading the plan, which is the whole point of the lazy
   * `load()`. The contract test asserts it matches the plan exactly.
   */
  nodeKinds?: string[];
  /** Agent templates: the preset the card preview renders as an avatar. */
  avatarUrl?: string;
  /**
   * Table templates: the column preset ids the card preview draws as chips,
   * mirroring what a real table card shows.
   */
  columnTypes?: string[];
}

/** A note as it lives in a template file: `label` and `text` are i18n keys. */
export interface TemplateNote {
  id: string;
  type: 'note';
  label: I18nKey;
  text: I18nKey;
  color: string;
  borderColor: string;
  textColor: string;
  width: number;
  height: number;
  position: { x: number; y: number };
}

/**
 * The plan payload of a workflow template.
 *
 * Deliberately has NO `id`, `name` or `description`: those are injected at
 * instantiation time. `plan.id` in particular is IGNORED by the backend
 * (`WorkflowPlanParser` mints its own UUID), so carrying one in the file would
 * only invite someone to trust it.
 */
export interface TemplatePlan {
  triggers: Record<string, unknown>[];
  mcps: Record<string, unknown>[];
  tables?: Record<string, unknown>[];
  agents?: Record<string, unknown>[];
  cores: Record<string, unknown>[];
  interfaces?: Record<string, unknown>[];
  notes: TemplateNote[];
  edges: { from: string; to: string }[];
}

export interface WorkflowTemplate {
  schemaVersion: 1;
  kind: 'workflow';
  meta: TemplateMeta;
  plan: TemplatePlan;
}

/**
 * The agent payload of an agent template.
 *
 * `modelProvider` / `modelName` are intentionally absent: pinning a model a user
 * has not configured produces an agent that cannot run. CreateAgentModal resolves
 * the workspace default instead.
 *
 * `toolsConfig` carries only `mode` (the MCP/catalogue axis) and per-family
 * GRANTS (`<family>Grant`) plus their read/write `*AccessMode`. It MUST NOT
 * carry resource id lists (`workflows: [...]`, `tables: [...]`, ...) for two
 * reasons: a template cannot know a user's resource ids, and CreateAgentModal
 * only re-hydrates selected-id lists in EDIT mode, so ids here are silently
 * dropped. Grants, by contrast, ARE hydrated in create mode (their useState
 * initializers call `getGrant` unconditionally), which is why a template can
 * meaningfully ship `tablesGrant: 'all'`.
 *
 * An ABSENT grant means 'none' (deny by default), so a template only lists the
 * families it deliberately opens.
 */
export interface TemplateAgent {
  systemPromptKey: I18nKey;
  temperature: number;
  maxTokens: number;
  maxIterations: number;
  avatarUrl: string;
  toolsConfig: {
    /** MCP / catalogue tools axis. 'none' = no external tools. */
    mode: 'all' | 'none' | 'off';
    /**
     * Separate from `mode`, and defaulting to TRUE in the create modal. A
     * template that advertises "no tools" MUST set it to false explicitly,
     * or the agent it creates can still search the web.
     */
    webSearch?: boolean;
    workflowsGrant?: 'none' | 'all';
    tablesGrant?: 'none' | 'all';
    workflowAccessMode?: 'read' | 'write';
    tableAccessMode?: 'read' | 'write';
  };
}

export interface AgentTemplate {
  schemaVersion: 1;
  kind: 'agent';
  meta: TemplateMeta;
  agent: TemplateAgent;
}

/**
 * A table template describes columns by PRESET ID (`text`, `select`, `date`...),
 * not by raw type: the preset carries the `structure` and `display` contract the
 * backend validates, and a hand-rolled display block is rejected (a `vector`
 * column missing `display.dimension`, for instance). Instantiation resolves the
 * preset from `COLUMN_STYLE_PRESETS`, exactly like the create-table modal does.
 */
export interface TemplateTableColumn {
  /** Column name, an i18n key resolved at instantiation. */
  nameKey: I18nKey;
  /** Id from COLUMN_STYLE_PRESETS. */
  preset: string;
}

export interface TableTemplate {
  schemaVersion: 1;
  kind: 'table';
  meta: TemplateMeta;
  table: {
    columns: TemplateTableColumn[];
  };
}

export type Template = WorkflowTemplate | AgentTemplate | TableTemplate;

/**
 * Registry entry. The heavy JSON stays behind `load()` so it is code-split and
 * never ships in the bundle of the list pages that only render the cards.
 */
export interface TemplateRegistryEntry {
  meta: TemplateMeta;
  load: () => Promise<Template>;
}

/** Root i18n namespace for a template: `templates.<kind>.<slugCamel>`. */
export function templateNamespace(meta: TemplateMeta): string {
  const camel = meta.slug.replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
  return `templates.${meta.kind}.${camel}`;
}
