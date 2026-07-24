/**
 * Turns a template into a real workflow.
 *
 * This reuses the SAME write path as CreateWorkflowModal and the inline
 * creator (`orchestratorApi.saveWorkflowPlan`). There is deliberately no new
 * endpoint and no new backend code: a template is just a pre-filled plan.
 */

import { orchestratorApi } from '@/lib/api';
import { rememberWorkflowName } from '@/lib/workflows/recentWorkflowNames';

import { COLUMN_STYLE_PRESETS } from '@/components/data-table/visualHelpers';

import { hydrateWorkflowPlan, type Translate } from './hydrate';
import { templateNamespace } from './types';
import type { TableTemplate, TemplatePlan, WorkflowTemplate } from './types';

/** Reads a message that IS an i18n key, without ICU parsing. */
function rawKey(t: Translate, key: string): string {
  const value = typeof t.raw === 'function' ? t.raw(key) : t(key);
  return typeof value === 'string' ? value : String(value ?? '');
}

/**
 * Appends " (2)", " (3)"... when the name is already taken.
 *
 * Uses only the names already loaded by the caller: instantiating a template
 * must not cost an extra round trip. Collisions beyond the current page are
 * harmless, workflow names are not unique.
 */
export function uniqueName(base: string, existingNames: string[]): string {
  const normalize = (n: string) => n.trim().toLowerCase();
  const taken = new Set(existingNames.map(normalize));
  if (!taken.has(normalize(base))) return base;

  // Unbounded rather than capped: a cap would silently hand back a duplicate
  // at the boundary, which is exactly the confusion the suffix exists to avoid.
  for (let i = 2; ; i += 1) {
    const candidate = `${base} (${i})`;
    if (!taken.has(normalize(candidate))) return candidate;
  }
}

/**
 * Prefix marking interface content a template carries inline.
 *
 * Deliberately NOT `_snapshot_`: that prefix is the marketplace's contract, and
 * `SnapshotCloneService` re-creates an interface on the mere presence of
 * `_snapshot_htmlTemplate`. Reusing it would make a workflow created from a
 * template behave strangely if it were ever published.
 */
const TEMPLATE_IFACE_PREFIX = '_template_';

/**
 * Creates the interface entities a template needs, and rewrites the plan to
 * point at them.
 *
 * A plan references an interface by the UUID of a real `interface` entity, and
 * a checked-in template cannot know one. Worse, NOTHING validates that id when
 * the plan is saved: a bogus one saves with HTTP 200 and the interface silently
 * renders empty on the first run. So the entity is created first and the real
 * id is injected, and any failure here aborts the whole instantiation rather
 * than producing a workflow that looks fine and is not.
 */
async function materializeInterfaces(
  plan: TemplatePlan & { name: string },
  t: Translate,
): Promise<void> {
  for (const iface of plan.interfaces ?? []) {
    const html = iface[`${TEMPLATE_IFACE_PREFIX}htmlTemplate`];
    if (typeof html !== 'string') continue;

    const created = await orchestratorApi.createInterface({
      name: `${plan.name} ${String(iface.label ?? 'Interface')}`,
      description: raw(t, `${TEMPLATE_IFACE_PREFIX}description`, iface),
      htmlTemplate: html,
      cssTemplate: String(iface[`${TEMPLATE_IFACE_PREFIX}cssTemplate`] ?? ''),
      jsTemplate: String(iface[`${TEMPLATE_IFACE_PREFIX}jsTemplate`] ?? ''),
      interfaceType: 'html',
      isPublic: false,
    } as Record<string, unknown>);

    if (!created?.id) {
      throw new Error('Interface creation returned no id');
    }
    iface.id = created.id;

    // Strip the inline content: it has served its purpose and must not be
    // persisted into the plan as dead weight.
    for (const key of Object.keys(iface)) {
      if (key.startsWith(TEMPLATE_IFACE_PREFIX)) delete iface[key];
    }
  }
}

/** Reads an optional `_template_*` string field, resolving it if it is an i18n key. */
function raw(t: Translate, key: string, iface: Record<string, unknown>): string {
  const value = iface[key];
  if (typeof value !== 'string') return '';
  return value.startsWith('templates.')
    ? String((typeof t.raw === 'function' ? t.raw(value) : t(value)) ?? '')
    : value;
}

/**
 * Creates the workflow and returns the id to navigate to.
 *
 * `workflowId` MUST be sent as a TOP-LEVEL request field. The backend's
 * WorkflowPlanParser ignores `plan.id` and mints its own UUID, so without it
 * the row is created under a server-generated id and the redirect lands on a
 * 404 builder. The save response echoes the authoritative id, so prefer it.
 */
export async function instantiateWorkflowTemplate(
  template: WorkflowTemplate,
  t: Translate,
  name: string,
): Promise<string> {
  const workflowId = crypto.randomUUID();
  const plan = hydrateWorkflowPlan(template, t, name);
  await materializeInterfaces(plan, t);

  const result = await orchestratorApi.saveWorkflowPlan({
    planJson: JSON.stringify(plan),
    dataInputs: {},
    workflowId,
  });

  const createdId =
    typeof result?.workflowId === 'string' && result.workflowId ? result.workflowId : workflowId;

  // Prime the breadcrumb so the builder title is right immediately after the
  // redirect, instead of transiently showing "Workflow {uuid}".
  rememberWorkflowName(createdId, name);

  return createdId;
}

/**
 * Creates a table (data source) and its columns from a template.
 *
 * Columns are described by PRESET ID, and the preset is resolved here from
 * `COLUMN_STYLE_PRESETS`, the same source the create-table modal uses. That
 * matters: the backend validates the `display` contract per type and rejects an
 * incomplete one, so a hand-written display block would fail for some types.
 *
 * Column failures are collected rather than thrown: a table missing one column
 * is still useful and the user can add it, whereas losing the whole table over
 * one column is not. The caller is told what was skipped.
 */
export async function instantiateTableTemplate(
  template: TableTemplate,
  t: Translate,
  name: string,
): Promise<{ dataSourceId: string; skippedColumns: string[] }> {
  const ns = templateNamespace(template.meta);

  const created = await orchestratorApi.createDataSource({
    name,
    description: rawKey(t, `${ns}.description`),
    sourceConfig: {},
    data: [],
    createdBy: 'user',
    mappingSpec: {},
  } as Record<string, unknown>);

  const dataSourceId = String((created as { id?: unknown })?.id ?? '');
  if (!dataSourceId) throw new Error('Table creation returned no id');

  const skippedColumns: string[] = [];
  for (const column of template.table.columns) {
    const preset = COLUMN_STYLE_PRESETS.find((p) => p.id === column.preset);
    const columnName = rawKey(t, column.nameKey);
    if (!preset) {
      skippedColumns.push(columnName);
      continue;
    }
    try {
      await orchestratorApi.createColumn(dataSourceId, {
        name: columnName,
        type: preset.visualType,
        structure: preset.structure,
        display: { ...(preset.display ?? {}), label: columnName },
        ...(preset.defaultValue !== undefined ? { defaultValue: preset.defaultValue } : {}),
      });
    } catch {
      skippedColumns.push(columnName);
    }
  }

  return { dataSourceId, skippedColumns };
}
