/**
 * Regression: workflow expressions in template copy must survive translation.
 *
 * Template notes and bullets teach workflow syntax, so they are full of
 * `{{item}}`, `{{index}}` and `{{core:x.output.y}}`. ICU MessageFormat reads `{`
 * as the start of an argument, so routing that copy through `t()` raises
 * INVALID_MESSAGE / MALFORMED_ARGUMENT and next-intl falls back to rendering the
 * RAW KEY PATH: the user sees "templates.workflow.splitList.notes.itemVar.text"
 * on a sticky note, in every language, with no error anywhere.
 *
 * `hydrate.ts` therefore reads this copy with `t.raw()`. These tests fail on the
 * pre-fix `t()` implementation.
 */

import { createTranslator } from 'next-intl';
import { describe, expect, it } from 'vitest';

import enMessages from '@/messages/en.json';

import { hydrateAgent, templateCopy, type Translate } from '../hydrate';
import { getTemplates } from '../index';
import type { AgentTemplate, WorkflowTemplate } from '../types';
import { hydrateWorkflowPlan } from '../hydrate';

const t = createTranslator({ locale: 'en', messages: enMessages as any }) as unknown as Translate;

/** Copy that reached the user must never still look like a key path. */
function assertResolved(value: string, label: string) {
  expect(value, `${label} was not resolved`).toBeTruthy();
  expect(value.startsWith('templates.'), `${label} fell back to its key path`).toBe(false);
}

describe('template copy survives ICU', () => {
  it.each(getTemplates('workflow').map((e) => [e.meta.slug, e] as const))(
    '%s keeps braces in its notes instead of falling back to the key',
    async (slug, entry) => {
      const template = (await entry.load()) as WorkflowTemplate;
      const plan = hydrateWorkflowPlan(template, t, 'Test name');

      for (const note of plan.notes) {
        assertResolved(note.text, `${slug} note text`);
        assertResolved(note.label, `${slug} note label`);
      }
    },
  );

  it.each(getTemplates('workflow').map((e) => [e.meta.slug, e] as const))(
    '%s keeps braces in its card copy',
    (slug, entry) => {
      const copy = templateCopy(entry.meta, t);
      assertResolved(copy.title, `${slug} title`);
      assertResolved(copy.description, `${slug} description`);
      copy.teaches.forEach((line, i) => assertResolved(line, `${slug} teaches.${i}`));
    },
  );

  it('preserves a double-brace expression verbatim', async () => {
    const entry = getTemplates('workflow').find((e) => e.meta.slug === 'split-list')!;
    const template = (await entry.load()) as WorkflowTemplate;
    const plan = hydrateWorkflowPlan(template, t, 'Test name');

    const itemNote = plan.notes.find((n) => n.id === 'note-item-var');
    expect(itemNote).toBeDefined();
    // The exact strings a beginner is meant to copy off the canvas.
    expect(itemNote!.text).toContain('{{item}}');
    expect(itemNote!.text).toContain('{{index}}');
  });

  it('resolves an agent system prompt without mangling it', async () => {
    const entry = getTemplates('agent').find((e) => e.meta.slug === 'data-analyst')!;
    const template = (await entry.load()) as AgentTemplate;
    const prefilled = hydrateAgent(template, t, 'Test name');

    assertResolved(prefilled.systemPrompt, 'data-analyst systemPrompt');
    // The multi-line structure is what makes it a usable prompt.
    expect(prefilled.systemPrompt).toContain('\n');
    expect((prefilled as unknown as Record<string, unknown>).systemPromptKey).toBeUndefined();
  });
});
