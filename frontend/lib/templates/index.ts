/**
 * Template registry.
 *
 * The metas below are duplicated from each JSON file ON PURPOSE: they are the
 * only part the list pages need in order to render cards, and keeping them here
 * as plain literals means the heavy plan payloads stay behind `load()` and are
 * code-split away from the Workflows and Agents pages.
 *
 * The duplication cannot drift silently: `templates.contract.test.ts` asserts
 * that every registry meta is deep-equal to the `meta` block of the file its
 * `load()` resolves to.
 */

import type { Template, TemplateKind, TemplateMeta, TemplateRegistryEntry } from './types';

const WORKFLOW_METAS: TemplateMeta[] = [
  {
    slug: 'hello-workflow',
    kind: 'workflow',
    order: 10,
    difficulty: 'beginner',
    icon: 'Play',
    runnable: true,
    teachesCount: 3,
    nodeKinds: ['manual', 'transform'],
  },
  {
    slug: 'branch-decision',
    kind: 'workflow',
    order: 20,
    difficulty: 'beginner',
    icon: 'GitBranch',
    runnable: true,
    teachesCount: 3,
    nodeKinds: ['manual', 'transform', 'decision', 'transform', 'transform'],
  },
  {
    slug: 'fork-merge',
    kind: 'workflow',
    order: 30,
    difficulty: 'intermediate',
    icon: 'Split',
    runnable: true,
    teachesCount: 3,
    nodeKinds: ['manual', 'fork', 'transform', 'transform', 'merge'],
  },
  {
    slug: 'split-list',
    kind: 'workflow',
    order: 40,
    difficulty: 'intermediate',
    icon: 'List',
    runnable: true,
    teachesCount: 3,
    nodeKinds: ['manual', 'transform', 'split', 'transform', 'aggregate'],
  },
  {
    slug: 'interface-page',
    kind: 'workflow',
    order: 45,
    difficulty: 'intermediate',
    icon: 'Monitor',
    runnable: true,
    teachesCount: 3,
    nodeKinds: ['manual', 'transform', 'interface'],
  },
  {
    slug: 'resilient-steps',
    kind: 'workflow',
    order: 50,
    difficulty: 'advanced',
    icon: 'ShieldCheck',
    runnable: false,
    teachesCount: 3,
    nodeKinds: ['manual', 'http_request', 'decision', 'transform', 'stop_on_error'],
  },
  {
    slug: 'error-handler',
    kind: 'workflow',
    order: 60,
    difficulty: 'advanced',
    icon: 'AlertTriangle',
    runnable: false,
    teachesCount: 3,
    nodeKinds: ['manual', 'error', 'transform', 'stop_on_error', 'transform'],
  },
];

const AGENT_METAS: TemplateMeta[] = [
  {
    slug: 'basic-assistant',
    kind: 'agent',
    order: 10,
    difficulty: 'beginner',
    icon: 'Bot',
    runnable: true,
    teachesCount: 3,
    avatarUrl: 'preset:purple',
  },
  {
    slug: 'data-analyst',
    kind: 'agent',
    order: 20,
    difficulty: 'intermediate',
    icon: 'Table',
    runnable: true,
    teachesCount: 3,
    avatarUrl: 'preset:teal',
  },
  {
    slug: 'workflow-conductor',
    kind: 'agent',
    order: 30,
    difficulty: 'intermediate',
    icon: 'Workflow',
    runnable: true,
    teachesCount: 3,
    avatarUrl: 'preset:blue',
  },
  {
    slug: 'frugal-agent',
    kind: 'agent',
    order: 40,
    difficulty: 'advanced',
    icon: 'Gauge',
    runnable: true,
    teachesCount: 3,
    avatarUrl: 'preset:slate',
  },
];

const TABLE_METAS: TemplateMeta[] = [
  {
    slug: 'contacts',
    kind: 'table',
    order: 10,
    difficulty: 'beginner',
    icon: 'Users',
    runnable: true,
    teachesCount: 3,
    columnTypes: ['text', 'email', 'phone', 'text', 'text'],
  },
  {
    slug: 'task-tracker',
    kind: 'table',
    order: 20,
    difficulty: 'beginner',
    icon: 'ListChecks',
    runnable: true,
    teachesCount: 3,
    columnTypes: ['text', 'select', 'date', 'checkbox', 'rating'],
  },
  {
    slug: 'content-calendar',
    kind: 'table',
    order: 30,
    difficulty: 'intermediate',
    icon: 'CalendarDays',
    runnable: true,
    teachesCount: 3,
    columnTypes: ['text', 'select', 'date', 'select', 'url'],
  },
];

/** Lazy loaders, keyed by `${kind}/${slug}`. */
const LOADERS: Record<string, () => Promise<Template>> = {
  'workflow/hello-workflow': () =>
    import('./workflow/hello-workflow.json').then((m) => m.default as unknown as Template),
  'workflow/branch-decision': () =>
    import('./workflow/branch-decision.json').then((m) => m.default as unknown as Template),
  'workflow/fork-merge': () =>
    import('./workflow/fork-merge.json').then((m) => m.default as unknown as Template),
  'workflow/split-list': () =>
    import('./workflow/split-list.json').then((m) => m.default as unknown as Template),
  'workflow/interface-page': () =>
    import('./workflow/interface-page.json').then((m) => m.default as unknown as Template),
  'workflow/resilient-steps': () =>
    import('./workflow/resilient-steps.json').then((m) => m.default as unknown as Template),
  'workflow/error-handler': () =>
    import('./workflow/error-handler.json').then((m) => m.default as unknown as Template),
  'agent/basic-assistant': () =>
    import('./agent/basic-assistant.json').then((m) => m.default as unknown as Template),
  'agent/data-analyst': () =>
    import('./agent/data-analyst.json').then((m) => m.default as unknown as Template),
  'agent/workflow-conductor': () =>
    import('./agent/workflow-conductor.json').then((m) => m.default as unknown as Template),
  'agent/frugal-agent': () =>
    import('./agent/frugal-agent.json').then((m) => m.default as unknown as Template),
  'table/contacts': () =>
    import('./table/contacts.json').then((m) => m.default as unknown as Template),
  'table/task-tracker': () =>
    import('./table/task-tracker.json').then((m) => m.default as unknown as Template),
  'table/content-calendar': () =>
    import('./table/content-calendar.json').then((m) => m.default as unknown as Template),
};

function toEntry(meta: TemplateMeta): TemplateRegistryEntry {
  const key = `${meta.kind}/${meta.slug}`;
  const load = LOADERS[key];
  if (!load) {
    // Unreachable in practice: the contract test fails first. Throwing beats
    // returning undefined, which would surface as a blank card at click time.
    throw new Error(`No loader registered for template "${key}"`);
  }
  return { meta, load };
}

export const TEMPLATE_REGISTRY: Record<TemplateKind, TemplateRegistryEntry[]> = {
  workflow: WORKFLOW_METAS.slice()
    .sort((a, b) => a.order - b.order)
    .map(toEntry),
  agent: AGENT_METAS.slice()
    .sort((a, b) => a.order - b.order)
    .map(toEntry),
  table: TABLE_METAS.slice()
    .sort((a, b) => a.order - b.order)
    .map(toEntry),
};

export function getTemplates(kind: TemplateKind): TemplateRegistryEntry[] {
  return TEMPLATE_REGISTRY[kind];
}

export * from './types';
