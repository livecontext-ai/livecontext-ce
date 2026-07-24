/**
 * Resolves the i18n keys carried by a template into the user's language.
 *
 * Only note text is translated. Node labels are identifiers and are passed
 * through untouched, see the TRANSLATION RULE in `types.ts`.
 */

import { templateNamespace } from './types';
import type {
  AgentTemplate,
  TemplateAgent,
  TemplateMeta,
  TemplatePlan,
  WorkflowTemplate,
} from './types';

/**
 * Matches next-intl's `useTranslations()` called with no namespace.
 *
 * `raw` matters more than it looks. Template copy is FULL of workflow
 * expressions: `{{item}}`, `{{index}}`, `{{core:build_greeting.output.greeting}}`.
 * ICU MessageFormat treats `{` as the start of an argument, so putting any of
 * those through `t()` raises INVALID_MESSAGE / MALFORMED_ARGUMENT and next-intl
 * falls back to rendering the RAW KEY PATH. The symptom is a sticky note reading
 * "templates.workflow.helloWorkflow.notes.output.text" in every language.
 *
 * None of this copy takes ICU arguments, so `t.raw()` is both correct and the
 * only thing that keeps the braces intact.
 */
export interface Translate {
  (key: string, values?: Record<string, unknown>): string;
  raw: (key: string) => unknown;
}

/**
 * Reads a message verbatim, with no ICU parsing. See the note on `Translate`.
 *
 * Tolerates a translator without `.raw`. Real next-intl always provides it; the
 * only callers that do not are hand-rolled `useTranslations` stubs in component
 * tests of the surrounding pages, and a missing helper there should not crash a
 * whole table render. The ICU behaviour itself is pinned against the REAL
 * translator in `templates.hydrate.test.ts`, so this fallback cannot hide it.
 */
function raw(t: Translate, key: string): string {
  const value = typeof t.raw === 'function' ? t.raw(key) : t(key);
  return typeof value === 'string' ? value : String(value ?? '');
}

export interface TemplateCopy {
  title: string;
  description: string;
  teaches: string[];
}

/** Card copy: title, description and the "what you learn" bullets. */
export function templateCopy(meta: TemplateMeta, t: Translate): TemplateCopy {
  const ns = templateNamespace(meta);
  return {
    title: raw(t, `${ns}.title`),
    description: raw(t, `${ns}.description`),
    teaches: Array.from({ length: meta.teachesCount }, (_, i) => raw(t, `${ns}.teaches.${i}`)),
  };
}

/**
 * Builds the plan to POST: notes translated, name and description injected.
 *
 * `id` is NOT set on the plan. The backend ignores `plan.id` and takes the id
 * from the request's top-level `workflowId` field, so putting one here would be
 * dead weight that invites someone to trust it.
 */
export function hydrateWorkflowPlan(
  template: WorkflowTemplate,
  t: Translate,
  name: string,
): TemplatePlan & { name: string; description: string } {
  const ns = templateNamespace(template.meta);
  return {
    ...(resolveKeysDeep(template.plan, t) as TemplatePlan),
    name,
    description: raw(t, `${ns}.description`),
  };
}

/**
 * Replaces every string that IS an i18n key with its translation, anywhere in
 * the plan.
 *
 * A workflow plan carries user-facing text beyond the notes: the labels of a
 * decision's ports and a fork's branches show on the canvas, and a
 * `stopOnError` message surfaces in the run. Those must be translated too, so
 * the rule is uniform rather than a list of paths to remember.
 *
 * The marker is the `templates.` prefix, which is exactly what a NODE LABEL
 * must never look like: labels are identifiers that edges and expressions
 * resolve against, so they stay literal and are left untouched here. The
 * contract test enforces both halves of that rule.
 */
function resolveKeysDeep(value: unknown, t: Translate): unknown {
  if (typeof value === 'string') {
    return value.startsWith('templates.') ? raw(t, value) : value;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => resolveKeysDeep(entry, t));
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, resolveKeysDeep(v, t)]),
    );
  }
  return value;
}

/**
 * Builds the partial agent handed to CreateAgentModal.
 *
 * Deliberately carries NO `id`: CreateAgentModal derives `isEditMode` from
 * `!!agent?.id`, so an id would open the modal in edit mode against an agent
 * that does not exist.
 */
export function hydrateAgent(
  template: AgentTemplate,
  t: Translate,
  name: string,
): Omit<TemplateAgent, 'systemPromptKey'> & {
  name: string;
  description: string;
  systemPrompt: string;
} {
  const ns = templateNamespace(template.meta);
  const { systemPromptKey, ...rest } = template.agent;
  return {
    ...rest,
    name,
    description: raw(t, `${ns}.description`),
    systemPrompt: raw(t, systemPromptKey),
  };
}
