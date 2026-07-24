/**
 * Structural contract for every checked-in template.
 *
 * These templates are DATA, so nothing at compile time protects them. The
 * failure modes they guard against are all silent: a dropped note renders an
 * empty sticky, an unresolved i18n key renders the raw key path, a mistyped
 * edge target renders a disconnected node. Each assertion below corresponds to
 * one of those.
 */

import fs from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { PRESET_GRADIENTS } from '@/components/agents/avatarColors';
import { NOTE_COLORS } from '@/app/workflows/builder/components/nodes/NoteNode';
import { normalizeLabel } from '@/app/workflows/builder/utils/labelNormalizer';

import { getTemplates } from '../index';
import { templateNamespace } from '../types';
import type { AgentTemplate, TemplateKind, WorkflowTemplate } from '../types';

const LOCALES = ['en', 'fr', 'es', 'de', 'pt', 'zh'] as const;
const MESSAGES_DIR = path.resolve(__dirname, '../../../messages');

function loadLocale(locale: string): Record<string, unknown> {
  return JSON.parse(fs.readFileSync(path.join(MESSAGES_DIR, `${locale}.json`), 'utf8'));
}

const MESSAGES = Object.fromEntries(LOCALES.map((l) => [l, loadLocale(l)])) as Record<
  string,
  Record<string, unknown>
>;

/** Resolves a dotted key path, returning undefined when any segment is missing. */
function lookup(obj: unknown, dotted: string): unknown {
  return dotted
    .split('.')
    .reduce<unknown>((acc, part) => (acc && typeof acc === 'object' ? (acc as any)[part] : undefined), obj);
}

const ALL_ENTRIES = (['workflow', 'agent'] as TemplateKind[]).flatMap((kind) =>
  getTemplates(kind).map((e) => [`${kind}/${e.meta.slug}`, e] as const),
);

const VALID_NOTE_COLORS = new Set(NOTE_COLORS.map((c) => c.value));
const VALID_BORDER_COLORS = new Set(NOTE_COLORS.map((c) => c.border));
const VALID_TEXT_COLORS = new Set(NOTE_COLORS.map((c) => c.text));

describe('template registry', () => {
  it('ships both workflow and agent templates', () => {
    expect(getTemplates('workflow').length).toBeGreaterThan(0);
    expect(getTemplates('agent').length).toBeGreaterThan(0);
  });

  it.each(['workflow', 'agent'] as TemplateKind[])('%s slugs and orders are unique', (kind) => {
    const metas = getTemplates(kind).map((e) => e.meta);
    expect(new Set(metas.map((m) => m.slug)).size).toBe(metas.length);
    expect(new Set(metas.map((m) => m.order)).size).toBe(metas.length);
  });

  it.each(ALL_ENTRIES)('%s registry meta matches the meta inside its file', async (_key, entry) => {
    const file = await entry.load();
    // The registry duplicates meta so the JSON stays code-split. This is the
    // assertion that keeps the duplication from drifting.
    expect(file.meta).toEqual(entry.meta);
    expect(file.kind).toBe(entry.meta.kind);
    expect(file.schemaVersion).toBe(1);
  });
});

describe('template copy exists in every locale', () => {
  it.each(ALL_ENTRIES)('%s has title, description and teaches in all 6 locales', async (_key, entry) => {
    const ns = templateNamespace(entry.meta);
    const keys = [
      `${ns}.title`,
      `${ns}.description`,
      ...Array.from({ length: entry.meta.teachesCount }, (_, i) => `${ns}.teaches.${i}`),
    ];

    for (const locale of LOCALES) {
      for (const key of keys) {
        const value = lookup(MESSAGES[locale], key);
        expect(typeof value, `${locale} is missing ${key}`).toBe('string');
        expect((value as string).length, `${locale} has an empty ${key}`).toBeGreaterThan(0);
      }
    }
  });

  it.each(ALL_ENTRIES)('%s note and prompt keys resolve in all 6 locales', async (_key, entry) => {
    const template = await entry.load();
    const keys =
      template.kind === 'workflow'
        ? (template as WorkflowTemplate).plan.notes.flatMap((n) => [n.label, n.text])
        : [(template as AgentTemplate).agent.systemPromptKey];

    for (const locale of LOCALES) {
      for (const key of keys) {
        const value = lookup(MESSAGES[locale], key);
        expect(typeof value, `${locale} is missing ${key}`).toBe('string');
        expect((value as string).length, `${locale} has an empty ${key}`).toBeGreaterThan(0);
      }
    }
  });

  it('has strict key parity for the templates namespace across all locales', () => {
    const flatten = (obj: unknown, prefix = ''): string[] =>
      obj && typeof obj === 'object'
        ? Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
            v && typeof v === 'object' ? flatten(v, `${prefix}${k}.`) : [`${prefix}${k}`],
          )
        : [];

    const reference = flatten(MESSAGES.en.templates).sort();
    expect(reference.length).toBeGreaterThan(100);

    for (const locale of LOCALES.filter((l) => l !== 'en')) {
      const actual = flatten(MESSAGES[locale].templates).sort();
      expect(actual, `${locale} diverges from en`).toEqual(reference);
    }
  });

  /**
   * The em-dash is a hard project ban, and explanatory prose is exactly where it
   * creeps back in. Six locales of teaching notes is the highest-risk surface in
   * the repo for it, so pin it here rather than trusting review.
   */
  it('contains no em-dash or en-dash anywhere under templates', () => {
    for (const locale of LOCALES) {
      const serialized = JSON.stringify(MESSAGES[locale].templates);
      expect(serialized.includes('-'), `${locale} contains an em-dash`).toBe(false);
      expect(serialized.includes('-'), `${locale} contains an en-dash`).toBe(false);
    }
  });
});

describe('workflow template plans', () => {
  const workflowEntries = getTemplates('workflow').map((e) => [e.meta.slug, e] as const);

  it.each(workflowEntries)('%s carries no id, name or description on the plan', async (_slug, entry) => {
    const plan = ((await entry.load()) as WorkflowTemplate).plan as unknown as Record<string, unknown>;
    // plan.id is IGNORED by the backend parser; carrying one would only invite
    // someone to trust it. name/description are injected at instantiation.
    expect(plan.id).toBeUndefined();
    expect(plan.name).toBeUndefined();
    expect(plan.description).toBeUndefined();
  });

  it.each(workflowEntries)('%s resolves every edge to a real node label', async (_slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;

    const known = new Set<string>();
    const collect = (nodes: Record<string, unknown>[] | undefined, prefix: string) => {
      for (const n of nodes ?? []) {
        const normalized = normalizeLabel(n.label as string);
        if (normalized) known.add(`${prefix}:${normalized}`);
      }
    };
    collect(plan.triggers, 'trigger');
    collect(plan.mcps, 'mcp');
    collect(plan.cores, 'core');
    collect(plan.agents, 'agent');
    collect(plan.tables, 'table');
    collect(plan.interfaces, 'interface');

    for (const edge of plan.edges) {
      // Strip the optional port suffix: `core:check_amount:if` -> `core:check_amount`.
      const stripPort = (ref: string) => ref.split(':').slice(0, 2).join(':');
      expect(known, `unknown edge source ${edge.from}`).toContain(stripPort(edge.from));
      expect(known, `unknown edge target ${edge.to}`).toContain(stripPort(edge.to));
    }
  });

  it.each(workflowEntries)('%s notes are complete and use the builder palette', async (_slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;
    expect(plan.notes.length, 'a template with no notes teaches nothing').toBeGreaterThan(0);

    const ids = new Set<string>();
    for (const note of plan.notes) {
      // `id` blank is the one thing the backend record rejects outright.
      expect(note.id).toBeTruthy();
      expect(ids.has(note.id), `duplicate note id ${note.id}`).toBe(false);
      ids.add(note.id);

      expect(note.type).toBe('note');
      // label/text are i18n KEYS here, never prose.
      expect(note.label.startsWith('templates.')).toBe(true);
      expect(note.text.startsWith('templates.')).toBe(true);

      expect(VALID_NOTE_COLORS.has(note.color as any), `off-palette color ${note.color}`).toBe(true);
      expect(VALID_BORDER_COLORS.has(note.borderColor as any)).toBe(true);
      expect(VALID_TEXT_COLORS.has(note.textColor as any)).toBe(true);

      // Below the builder minimums the note renders clipped.
      expect(note.width).toBeGreaterThanOrEqual(200);
      expect(note.height).toBeGreaterThanOrEqual(100);
      expect(typeof note.position.x).toBe('number');
      expect(typeof note.position.y).toBe('number');
    }
  });

  /**
   * The translation rule: a node label is an identifier that edges and
   * expressions resolve against. If one were ever turned into an i18n key, the
   * graph would silently come apart in every non-English locale.
   */
  it.each(workflowEntries)('%s keeps node labels as literal identifiers', async (_slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;
    const executable = [
      ...plan.triggers,
      ...plan.mcps,
      ...plan.cores,
      ...(plan.agents ?? []),
      ...(plan.tables ?? []),
    ];

    for (const node of executable) {
      const label = node.label as string | undefined;
      expect(label, 'an executable node needs a label to be addressable').toBeTruthy();
      expect(label!.startsWith('templates.'), `${label} looks like an i18n key`).toBe(false);
    }
  });
});

/**
 * Declared first-level output keys, per node type.
 *
 * Transcribed from the backend NodeSpec `definition().outputs(...)` blocks,
 * which are the source of truth the engine resolves against:
 *   TransformNodeSpec, HttpRequestNodeSpec, ManualTriggerNodeSpec,
 *   ErrorTriggerNodeSpec, SplitNodeSpec, AggregateNodeSpec.
 *
 * Kept as a literal because the frontend has no static copy of these schemas
 * (it fetches node definitions from the backend at runtime), so an offline test
 * cannot derive them. Extend this map when a template starts using a new type.
 */
const DECLARED_OUTPUTS: Record<string, string[]> = {
  manual: ['triggered_at', 'triggered_by'],
  error: [
    'parentWorkflowId',
    'parentRunId',
    'status',
    'errorMessage',
    'triggered_at',
    'triggered_by',
    'failedSteps',
    'completedSteps',
    'totalSteps',
    'skippedSteps',
  ],
  // A transform does NOT expose its mapping labels at the root: they live under
  // `transformed`. Omitting that segment resolves to null, which silently forces
  // a decision to `else` and makes a split FAIL outright.
  transform: ['transformed', 'evaluations'],
  http_request: ['success', 'status', 'statusText', 'data', 'headers', 'error'],
  split: [
    'current_item',
    'current_index',
    'items',
    'item_count',
    'split_id',
    'spawn_reason',
    'terminated',
  ],
  // Deliberately absent, and the scan skips any type it does not know:
  //   - aggregate: its customTransform emits one ROOT key per configured field
  //     label on top of `aggregated_count`, so a fixed list would be WRONG
  //     (it would reject a legitimate `{{core:collect.output.all_labels}}`).
  //   - decision / fork / merge / stop_on_error: no template reads their
  //     outputs. Add them here the day one does, rather than guessing now.
};

/** `{{prefix:label.output.field...}}`. Bare `{{item}}` / `{{index}}` do not match. */
const OUTPUT_REF = /\{\{(trigger|core|mcp|agent|table|interface):([a-z0-9_]+)\.output\.([a-zA-Z0-9_]+)/g;

describe('workflow template expressions resolve against declared node outputs', () => {
  const workflowEntries = getTemplates('workflow').map((e) => [e.meta.slug, e] as const);

  it.each(workflowEntries)('%s references only fields the target node declares', async (slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;

    // label -> node type, for every addressable node in this plan.
    const typeByLabel = new Map<string, string>();
    const index = (nodes: Record<string, unknown>[] | undefined, prefix: string) => {
      for (const n of nodes ?? []) {
        const normalized = normalizeLabel(n.label as string);
        if (normalized) typeByLabel.set(`${prefix}:${normalized}`, String(n.type ?? ''));
      }
    };
    index(plan.triggers, 'trigger');
    index(plan.cores, 'core');
    index(plan.mcps, 'mcp');

    // Scan the plan WITHOUT the notes: notes are prose and are checked separately.
    const { notes, ...executablePlan } = plan;
    const source = JSON.stringify(executablePlan);

    const seen: string[] = [];
    for (const match of source.matchAll(OUTPUT_REF)) {
      const [, prefix, label, field] = match;
      const ref = `${prefix}:${label}`;
      const nodeType = typeByLabel.get(ref);

      expect(nodeType, `${slug} references unknown node ${ref}`).toBeDefined();

      const declared = DECLARED_OUTPUTS[nodeType!];
      if (!declared) continue; // Type not in the map: nothing to assert here.

      expect(
        declared,
        `${slug}: ${ref} is a "${nodeType}" node and does not expose "${field}". ` +
          `Declared: ${declared.join(', ')}.`,
      ).toContain(field);
      seen.push(`${ref}.${field}`);
    }
  });

  /**
   * The SAME validation, applied to the note prose.
   *
   * The notes ARE the deliverable: they are what teaches a beginner what to
   * type. An expression quoted in a note is copied by hand into a real
   * workflow, so a wrong one there is worse than a wrong one in the plan, which
   * at least fails loudly on the first run.
   *
   * Two real defects shipped through this exact gap (a transform path missing
   * its `transformed` segment, and elseif ports documented as 1-indexed), so
   * the prose is scanned here rather than trusted to review.
   */
  it.each(workflowEntries)('%s notes quote only real output fields', async (slug, entry) => {
    const template = (await entry.load()) as WorkflowTemplate;
    const { plan } = template;

    const typeByLabel = new Map<string, string>();
    const index = (nodes: Record<string, unknown>[] | undefined, prefix: string) => {
      for (const n of nodes ?? []) {
        const normalized = normalizeLabel(n.label as string);
        if (normalized) typeByLabel.set(`${prefix}:${normalized}`, String(n.type ?? ''));
      }
    };
    index(plan.triggers, 'trigger');
    index(plan.cores, 'core');
    index(plan.mcps, 'mcp');

    // ALL SIX locales: both defects that shipped through here existed in every
    // language, so checking English alone would cover one sixth of the surface.
    for (const locale of LOCALES) {
      const prose = plan.notes.map((n) => String(lookup(MESSAGES[locale], n.text) ?? '')).join('\n');

      for (const match of prose.matchAll(OUTPUT_REF)) {
        const [, prefix, label, field] = match;
        const ref = `${prefix}:${label}`;
        const nodeType = typeByLabel.get(ref);
        if (!nodeType) continue; // A note may cite a generic example node.

        const declared = DECLARED_OUTPUTS[nodeType];
        if (!declared) continue;

        expect(
          declared,
          `${slug} [${locale}]: a note tells the user to read "${field}" off ${ref}, but ` +
            `that "${nodeType}" node exposes only: ${declared.join(', ')}.`,
        ).toContain(field);
      }
    }
  });

  /**
   * Port names quoted in the notes must be real.
   *
   * Ports are 0-indexed (`Core.getDecisionPorts` returns if, elseif_0, elseif_1,
   * ..., else; fork branches start at branch_0). A note that says elseif_1 is
   * the FIRST extra condition sends the reader to a port that does not exist,
   * and the resulting edge is silently dead.
   */
  it.each(LOCALES)('%s notes never quote a 1-indexed port', (locale) => {
    const prose = JSON.stringify(MESSAGES[locale].templates);

    // The first extra condition is elseif_0, the first fork branch is branch_0
    // (Core.getDecisionPorts / getForkPorts). Naming the _1 form without ever
    // naming _0 is the signature of 1-indexed documentation.
    for (const [family, first] of [
      ['elseif', 'elseif_0'],
      ['branch', 'branch_0'],
    ] as const) {
      if (prose.includes(`${family}_1`)) {
        expect(prose, `${locale} mentions ${family}_1 without ever mentioning ${first}`).toContain(
          first,
        );
      }
    }
    // The concrete wording that shipped wrong, in every language.
    expect(prose, `${locale} still enumerates elseif from 1`).not.toContain('elseif_1, elseif_2');
  });

  /**
   * Guards the assertions above against passing vacuously.
   *
   * Not asserted per template: `fork-merge` legitimately reads no outputs (its
   * branches produce literals), so a per-template floor would be wrong. What
   * must hold is that the scan finds the references we know exist.
   */
  it('actually scanned the expressions it claims to validate', async () => {
    // Self-contained: recomputed here rather than read off the accumulator the
    // it.each above fills, so running this test alone (vitest -t) still means
    // something instead of passing on an empty array.
    const found: string[] = [];
    for (const [, entry] of workflowEntries) {
      const { plan } = (await entry.load()) as WorkflowTemplate;
      const { notes, ...executablePlan } = plan;
      for (const m of JSON.stringify(executablePlan).matchAll(OUTPUT_REF)) {
        found.push(`${m[1]}:${m[2]}.${m[3]}`);
      }
    }

    expect(found.length).toBeGreaterThan(5);
    // The `transformed` segment whose absence broke two templates.
    expect(found).toContain('core:make_list.transformed');
    expect(found).toContain('core:make_amount.transformed');
    expect(found).toContain('trigger:on_parent_failure.parentWorkflowId');
    expect(found).toContain('core:call_api.success');
  });
});

/**
 * Mirrors the engine's `TemplateEngine.isPureExpression`.
 *
 * The whole value must be one `{{...}}` with nothing outside it and no nested
 * `{{`. Only then is SpEL actually evaluated and a real typed value returned.
 * Anything else goes through `resolveExpressions`, a plain regex substitution
 * that returns a STRING.
 */
function isPureExpression(value: string): boolean {
  const t = value.trim();
  return t.startsWith('{{') && t.endsWith('}}') && t.indexOf('{{', 2) === -1;
}

describe('workflow template expressions are evaluated, not stringified', () => {
  const workflowEntries = getTemplates('workflow').map((e) => [e.meta.slug, e] as const);

  /**
   * The trap this pins, which is invisible at every other layer:
   *
   *   "'Hello from ' + {{trigger:start.output.triggered_by}}"
   *
   * is NOT a pure expression, so SpEL never runs. The engine substitutes the
   * reference into the surrounding text and stores the literal string
   * "'Hello from ' + Alice", quotes and plus sign included. No error, no log.
   * A bare `120` likewise becomes the STRING "120", so a decision comparing it
   * with `> 100` throws internally, is swallowed, and silently takes `else`.
   *
   * Rule: use interpolation ("Hello from {{ref}}") when you want a string, and
   * a pure expression ("{{120}}") when you want a typed value. Never SpEL
   * operators outside the braces.
   */
  it.each(workflowEntries)('%s never leaks SpEL syntax into a text template', async (slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;

    const expressions: { where: string; value: string }[] = [];
    for (const core of plan.cores) {
      const label = String(core.label ?? core.id);
      const transform = core.transform as { mappings?: { label: string; expression: string }[] } | undefined;
      for (const m of transform?.mappings ?? []) {
        expressions.push({ where: `${label}.transform.${m.label}`, value: m.expression });
      }
      const aggregate = core.aggregate as { fields?: { label: string; expression: string }[] } | undefined;
      for (const f of aggregate?.fields ?? []) {
        expressions.push({ where: `${label}.aggregate.${f.label}`, value: f.expression });
      }
      // Decision / switch conditions are deliberately NOT scanned: they run
      // through TemplateEngine.evaluateConditionWithDetails, which explicitly
      // supports the mixed form ("{{score}} > 100") by resolving each block and
      // THEN evaluating. The pure-expression rule below applies only to values
      // that go through evaluateTemplate.
    }

    for (const { where, value } of expressions) {
      if (isPureExpression(value)) continue; // SpEL runs: anything goes.

      const at = `${slug}: ${where} = ${JSON.stringify(value)}`;
      // These only mean anything to SpEL, and SpEL is not running here.
      expect(value.includes("'"), `${at} keeps SpEL quotes in a TEXT template, they land verbatim`).toBe(
        false,
      );
      expect(value.includes(' + '), `${at} uses SpEL concat in a TEXT template, it lands verbatim`).toBe(
        false,
      );
      // A bare literal is stored as a STRING, which breaks numeric comparison.
      expect(
        /^\s*-?\d+(\.\d+)?\s*$/.test(value),
        `${at} is a bare number: it becomes the STRING "${value.trim()}". Write {{${value.trim()}}}.`,
      ).toBe(false);
      expect(
        /^\s*\{/.test(value),
        `${at} is a bare inline list: it becomes a STRING. Wrap it as a pure expression.`,
      ).toBe(false);
    }
  });

  /**
   * No untranslated user-facing prose may sit in a plan.
   *
   * A plan carries text beyond the notes: a decision's port labels and a fork's
   * branch labels are drawn on the canvas, and a `stopOnError` message shows up
   * in the run. Left as English literals they would stay English for the five
   * other locales, silently breaking the project's i18n rule.
   *
   * NODE LABELS are the deliberate exception and are asserted to be the
   * opposite: they are identifiers that edges and expressions resolve against,
   * so they must NEVER be i18n keys.
   */
  it.each(workflowEntries)('%s keeps display text in i18n keys, labels literal', async (slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;

    for (const core of plan.cores) {
      const nodeLabel = String(core.label ?? core.id);

      for (const c of (core.decisionConditions ?? []) as { type: string; label?: string }[]) {
        // An `else` carries no user-visible label in some plans.
        if (c.label) {
          expect(
            c.label.startsWith('templates.'),
            `${slug}: decision port label ${JSON.stringify(c.label)} is untranslated English`,
          ).toBe(true);
        }
      }
      for (const o of (core.forkOutputs ?? []) as { label?: string }[]) {
        expect(
          String(o.label ?? '').startsWith('templates.'),
          `${slug}: fork branch label ${JSON.stringify(o.label)} is untranslated English`,
        ).toBe(true);
      }
      const stop = core.stopOnError as { errorMessage?: string } | undefined;
      if (stop?.errorMessage) {
        expect(
          stop.errorMessage.startsWith('templates.'),
          `${slug}: stop-on-error message is untranslated English`,
        ).toBe(true);
      }

      // The other half of the rule.
      expect(
        nodeLabel.startsWith('templates.'),
        `${slug}: node label ${JSON.stringify(nodeLabel)} must stay a literal identifier`,
      ).toBe(false);
    }

    for (const trigger of plan.triggers) {
      const params = (trigger.params ?? {}) as Record<string, unknown>;
      if (typeof params.workflowName === 'string') {
        expect(
          params.workflowName.startsWith('templates.'),
          `${slug}: the watched-workflow placeholder is untranslated English`,
        ).toBe(true);
      }
    }
  });

  it.each(workflowEntries)('%s split list is a pure expression', async (slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;
    for (const core of plan.cores) {
      if (core.type !== 'split') continue;
      const list = String(core.list ?? '');
      // A split needs a real List. A string here yields a failed split, not an
      // empty one, and the template is advertised as runnable.
      expect(
        isPureExpression(list),
        `${slug}: split list ${JSON.stringify(list)} is not a pure expression, so it resolves to a STRING`,
      ).toBe(true);
    }
  });
});

/**
 * Canvas layout: nothing may sit on top of anything else.
 *
 * Notes are the teaching content, so a note covering a node (or another note)
 * hides exactly what the template exists to show. Positions are hand-written in
 * the JSON, and moving a node without moving its notes is an easy mistake that
 * no other check would catch.
 *
 * Node boxes are approximated generously: the builder renders a node at roughly
 * 220x90, so 260x120 leaves a margin and keeps the assertion from being
 * pixel-precise about a value we do not control.
 */
const NODE_BOX = { w: 260, h: 120 };

interface Box {
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

function overlaps(a: Box, b: Box): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

describe('workflow template canvas layout', () => {
  const workflowEntries = getTemplates('workflow').map((e) => [e.meta.slug, e] as const);

  it.each(workflowEntries)('%s places no note on top of a node or another note', async (slug, entry) => {
    const { plan } = (await entry.load()) as WorkflowTemplate;

    const nodeBoxes: Box[] = [
      ...plan.triggers,
      ...plan.cores,
      ...plan.mcps,
      ...(plan.interfaces ?? []),
      ...(plan.agents ?? []),
      ...(plan.tables ?? []),
    ].map((n) => {
      const pos = (n.position ?? {}) as { x?: number; y?: number };
      return {
        name: String(n.label ?? n.id),
        x: Number(pos.x ?? 0),
        y: Number(pos.y ?? 0),
        ...NODE_BOX,
      };
    });

    const noteBoxes: Box[] = plan.notes.map((n) => ({
      name: `note "${n.id}"`,
      x: n.position.x,
      y: n.position.y,
      w: n.width,
      h: n.height,
    }));

    for (const note of noteBoxes) {
      for (const node of nodeBoxes) {
        expect(
          overlaps(note, node),
          `${slug}: ${note.name} covers the node "${node.name}"`,
        ).toBe(false);
      }
    }

    for (let i = 0; i < noteBoxes.length; i += 1) {
      for (let j = i + 1; j < noteBoxes.length; j += 1) {
        expect(
          overlaps(noteBoxes[i], noteBoxes[j]),
          `${slug}: ${noteBoxes[i].name} covers ${noteBoxes[j].name}`,
        ).toBe(false);
      }
    }
  });
});

describe('agent templates', () => {
  const agentEntries = getTemplates('agent').map((e) => [e.meta.slug, e] as const);

  it.each(agentEntries)('%s pins no model and grants no resource ids', async (_slug, entry) => {
    const { agent } = (await entry.load()) as AgentTemplate;
    const raw = agent as unknown as Record<string, unknown>;

    // Pinning a model the user has not configured produces an agent that cannot run.
    expect(raw.modelProvider).toBeUndefined();
    expect(raw.modelName).toBeUndefined();

    // A template cannot know a user's resource ids, and CreateAgentModal only
    // re-hydrates id lists in EDIT mode, so any listed here vanish silently.
    for (const key of ['workflows', 'tables', 'interfaces', 'agents', 'applications', 'tools']) {
      expect(
        (agent.toolsConfig as Record<string, unknown>)[key],
        `toolsConfig.${key} must not carry ids`,
      ).toBeUndefined();
    }

    expect(agent.systemPromptKey.startsWith('templates.')).toBe(true);
    // An unknown preset name renders no avatar at all, so check membership
    // rather than just the prefix.
    expect(Object.keys(PRESET_GRADIENTS)).toContain(agent.avatarUrl);
  });

  it.each(agentEntries)('%s declares a tools mode', async (_slug, entry) => {
    const { agent } = (await entry.load()) as AgentTemplate;
    // An absent mode falls back to 'all' in the create modal, which would hand
    // a "no tools" template the entire catalogue.
    expect(['all', 'none', 'off']).toContain(agent.toolsConfig.mode);
  });
});
