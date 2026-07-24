/**
 * Every workflow template must survive a real import into the builder.
 *
 * This runs the ACTUAL `WorkflowPlanImporter`, not a stand-in, because it is
 * the real consumer: it decides what appears on the canvas. A plan the backend
 * parses happily can still lose nodes here, and a malformed note is dropped
 * SILENTLY (WorkflowPlanParser filters nulls, NoteNodeCreator skips), so the
 * only way to know the teaching notes actually reach the user is to count them
 * after an import.
 */

import { describe, expect, it } from 'vitest';

import { WorkflowPlanImporter } from '@/app/workflows/builder/services/workflowPlanImporter/WorkflowPlanImporter';

import { getTemplates } from '../index';
import type { WorkflowTemplate } from '../types';

/** Identity translator: we assert on structure here, not on copy. */
const t = (key: string) => key;

const workflowEntries = getTemplates('workflow');

describe('workflow templates import into the builder', () => {
  it('registers at least one workflow template', () => {
    expect(workflowEntries.length).toBeGreaterThan(0);
  });

  it.each(workflowEntries.map((e) => [e.meta.slug, e] as const))(
    '%s imports with every node, edge and note preserved',
    async (_slug, entry) => {
      const template = (await entry.load()) as WorkflowTemplate;

      const plan = {
        ...template.plan,
        notes: template.plan.notes.map((n) => ({ ...n, label: t(n.label), text: t(n.text) })),
      };

      const result = await WorkflowPlanImporter.importPlan(JSON.stringify(plan));

      expect(result.error).toBeUndefined();
      expect(result.success).toBe(true);

      const expectedNodeCount =
        template.plan.triggers.length +
        template.plan.mcps.length +
        template.plan.cores.length +
        (template.plan.agents?.length ?? 0) +
        (template.plan.tables?.length ?? 0) +
        (template.plan.interfaces?.length ?? 0) +
        template.plan.notes.length;

      expect(result.nodes).toHaveLength(expectedNodeCount);
      expect(result.edges).toHaveLength(template.plan.edges.length);

      // The teaching notes are the product. Losing one is a silent failure.
      const importedNotes = result.nodes.filter((n) => n.type === 'noteNode');
      expect(importedNotes).toHaveLength(template.plan.notes.length);
      for (const note of importedNotes) {
        expect(note.data.noteText).toBeTruthy();
      }
    },
  );

  /**
   * The node CONFIG must survive the import, not just the node count.
   *
   * This is the format's one genuinely silent failure mode: `Core` is
   * `@JsonIgnoreProperties(ignoreUnknown = true)` and the builder's parser reads
   * key by key, so a mistyped config key (`params` instead of `httpRequest`,
   * `items` instead of `list`) is swallowed with no error. The node still
   * appears on the canvas, correctly positioned and connected, and is simply
   * EMPTY. Counting nodes cannot see it.
   */
  it.each(workflowEntries.map((e) => [e.meta.slug, e] as const))(
    '%s keeps every node configured after import',
    async (slug, entry) => {
      const template = (await entry.load()) as WorkflowTemplate;
      const plan = {
        ...template.plan,
        notes: template.plan.notes.map((n) => ({ ...n, label: t(n.label), text: t(n.text) })),
      };
      const result = await WorkflowPlanImporter.importPlan(JSON.stringify(plan));

      /** Builder data key that must be populated, per core type. */
      const REQUIRED_DATA: Record<string, string> = {
        transform: 'transformMappings',
        decision: 'decisionConditions',
        fork: 'forkOutputs',
        split: 'list',
        aggregate: 'aggregateFields',
        http_request: 'httpRequestData',
        stop_on_error: 'stopOnErrorMessage',
      };

      for (const core of template.plan.cores) {
        const key = REQUIRED_DATA[String(core.type)];
        if (!key) continue;

        const node = result.nodes.find((n) => n.data?.label === core.label);
        expect(node, `${slug}: core "${core.label}" vanished`).toBeDefined();

        const value = (node!.data as unknown as Record<string, unknown>)[key];
        expect(
          value,
          `${slug}: "${core.label}" (${core.type}) imported with an EMPTY ${key}. ` +
            `Its config key in the JSON is probably misspelled: the parser ignores unknown keys.`,
        ).toBeTruthy();
        if (Array.isArray(value)) {
          expect(value.length, `${slug}: "${core.label}" has an empty ${key} array`).toBeGreaterThan(0);
        }
      }
    },
  );

  it('preserves a nodePolicy block through the import', async () => {
    const entry = workflowEntries.find((e) => e.meta.slug === 'resilient-steps')!;
    const template = (await entry.load()) as WorkflowTemplate;
    const plan = {
      ...template.plan,
      notes: template.plan.notes.map((n) => ({ ...n, label: t(n.label), text: t(n.text) })),
    };
    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(plan));

    const call = result.nodes.find((n) => n.data?.label === 'Call api');
    const policy = (call!.data as Record<string, any>).nodePolicy;
    expect(policy, 'the retry policy is the whole point of this template').toBeTruthy();
    expect(policy.retryCount).toBe(2);
    expect(policy.continueOnFailure).toBe(true);
  });

  /**
   * Proves the note assertions above have teeth.
   *
   * A note with no resolved text imports as an EMPTY sticky: no error, no
   * warning, just a blank yellow square where the explanation should be. That
   * is the exact failure mode this suite exists to catch, so pin it here rather
   * than trusting that the green run above means anything.
   */
  it('detects a note whose text failed to resolve', async () => {
    const template = (await workflowEntries[0].load()) as WorkflowTemplate;
    const broken = {
      ...template.plan,
      notes: template.plan.notes.map((n, i) =>
        i === 0 ? { ...n, label: t(n.label), text: '' } : { ...n, label: t(n.label), text: t(n.text) },
      ),
    };

    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(broken));
    const notes = result.nodes.filter((n) => n.type === 'noteNode');

    // The node still exists, which is precisely why counting nodes alone is not
    // enough and the per-note text assertion is required.
    expect(notes).toHaveLength(template.plan.notes.length);
    expect(notes.some((n) => !n.data.noteText)).toBe(true);
  });
});
