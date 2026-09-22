// @vitest-environment jsdom
/**
 * Regression tests for data columns that collide with a FIXED column name
 * ({@link useColumnManagement}.getAllColumns).
 *
 * Bug: dynamic columns were filtered against a hard-coded reserved-name list
 * (`id`, `array_index`, `value`, ...) regardless of whether the matching fixed
 * column was actually built. During nested JSON navigation the columns are
 * derived from the DATA, so a workflow step output holding table rows lost its
 * `id` column outright in any view built on DataTable's `showIdColumn = false`
 * default, and an array of primitives lost its `value` column - an empty grid.
 *
 * Fix: dedupe against the fixed columns that were really built, so a data column
 * is dropped only when it would duplicate one. The one deliberate exception is
 * `id` outside workflow mode with the checkbox column on: DataTableGrid renders
 * the row id inside that checkbox column and returns null for both the `id`
 * header and cell, so keeping it would add an empty lane.
 */
import { describe, it, expect } from 'vitest';
import { cleanup, renderHook, act } from '@testing-library/react';

import { useColumnManagement } from '../useColumnManagement';
import { createViewConfig } from '../../viewConfig';
import type { ColumnDefinition } from '../../types';

const WORKFLOW_CONTEXT = { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'table:contacts' };

const col = (field: string): ColumnDefinition => ({
  col_id: field,
  field,
  header_name: field,
  type: 'text',
  editable: false,
  sortable: true,
  filterable: true,
});

/** Columns as derived from nested step-output data (no renderType - frontend-inferred). */
const NESTED_DATA_COLUMNS = [col('id'), col('email'), col('status')];

const setup = (viewConfig: ReturnType<typeof createViewConfig>, workflowContext: typeof WORKFLOW_CONTEXT | null, columns: ColumnDefinition[]) => {
  const hook = renderHook(() => useColumnManagement({ viewConfig, workflowContext }));
  act(() => {
    hook.result.current.setColumns(columns);
  });
  return hook;
};

describe('useColumnManagement - data column named like a fixed column', () => {
  it('keeps the data `id` column in a nested view with no identity lane', () => {
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, 'output.rows');
    const { result } = setup(viewConfig, WORKFLOW_CONTEXT, NESTED_DATA_COLUMNS);

    expect(result.current.getUniqueColumns().map(c => c.field)).toEqual(['id', 'email', 'status']);
  });

  it('renders exactly one `id` column when the fixed one is enabled (showIdColumn=true)', () => {
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, true, 'output.rows');
    const { result } = setup(viewConfig, WORKFLOW_CONTEXT, NESTED_DATA_COLUMNS);

    const fields = result.current.getUniqueColumns().map(c => c.field);
    expect(fields.filter(f => f === 'id')).toHaveLength(1);
    expect(fields).toContain('email');
  });

  it('keeps the `value` column when drilling into an array of primitives', () => {
    // Workflow view sets showValue=false, so before the fix this grid was empty.
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, 'output.tags');
    const { result } = setup(viewConfig, WORKFLOW_CONTEXT, [col('value')]);

    expect(result.current.getUniqueColumns().map(c => c.field)).toEqual(['value']);
  });

  it('still hides `id` in datasource mode, where the checkbox column shows it', () => {
    const viewConfig = createViewConfig(undefined, false, '');
    expect(viewConfig.showCheckbox).toBe(true);

    const { result } = setup(viewConfig, null, NESTED_DATA_COLUMNS);

    const fields = result.current.getUniqueColumns().map(c => c.field);
    expect(fields).not.toContain('id');
    expect(fields).toContain('checkbox');
    expect(fields).toContain('email');
  });

  it('does not duplicate a fixed column that the data also declares', () => {
    const viewConfig = createViewConfig(undefined, false, '');
    const { result } = setup(viewConfig, null, [col('priority'), col('created_at'), col('email')]);

    const fields = result.current.getUniqueColumns().map(c => c.field);
    expect(fields.filter(f => f === 'priority')).toHaveLength(1);
    expect(fields.filter(f => f === 'created_at')).toHaveLength(1);
  });

  it('shows a nested `id` on the tables page, where the checkbox column holds the PARENT id', () => {
    const viewConfig = createViewConfig(undefined, false, 'payload.items');
    expect(viewConfig.isNestedNavigation).toBe(true);

    const { result } = setup(viewConfig, null, NESTED_DATA_COLUMNS);

    expect(result.current.getUniqueColumns().map(c => c.field)).toContain('id');
  });

  it('shows a nested `id` in a marketplace snapshot, which builds no fixed lanes', () => {
    const viewConfig = createViewConfig(undefined, false, 'payload.items', false, true);
    expect(viewConfig.showCheckbox).toBe(false);

    const { result } = setup(viewConfig, null, NESTED_DATA_COLUMNS);

    expect(result.current.getUniqueColumns().map(c => c.field)).toEqual(['id', 'email', 'status']);
  });

  it('builds the fixed Index lane only when the data carries one, and never duplicates it', () => {
    // buildFixedColumns gates array_index/value on `columns.some(...)`, which is the very premise
    // the dedupe rests on: what is dropped is what was actually built.
    const viewConfig = createViewConfig(undefined, false, 'payload.items');

    const withIndex = setup(viewConfig, null, [col('array_index'), col('email')]);
    const fields = withIndex.result.current.getUniqueColumns().map(c => c.field);
    expect(fields.filter(f => f === 'array_index')).toHaveLength(1);

    cleanup();

    const withoutIndex = setup(viewConfig, null, [col('email')]);
    expect(withoutIndex.result.current.getUniqueColumns().map(c => c.field)).not.toContain('array_index');
  });

  it('keeps an identity lane when drilling in, even if the items carry no id', () => {
    // The contract the inspector's showIdColumn override was breaking, stated here
    // on the shared machinery: with the flag the nested view has an identity lane
    // whatever the data holds, without it the rows have no identity at all. This
    // pins the machinery, NOT the inspector's call site - that one is pinned by
    // NodeResultDataTable.identityLane.test.tsx, which is the test that fails on
    // the pre-fix component.
    const nestedItemsWithoutId = [col('name'), col('value')];

    const withLane = createViewConfig(WORKFLOW_CONTEXT, true, 'output.items');
    const kept = setup(withLane, WORKFLOW_CONTEXT, nestedItemsWithoutId);
    expect(kept.result.current.getUniqueColumns().map((c) => c.field)).toContain('id');
    cleanup();

    // Without the lane there is no identity at all - the shape of the defect.
    const bare = createViewConfig(WORKFLOW_CONTEXT, false, 'output.items');
    const { result } = setup(bare, WORKFLOW_CONTEXT, nestedItemsWithoutId);
    expect(result.current.getUniqueColumns().map((c) => c.field)).not.toContain('id');
  });

  it('leaves backend-driven root columns untouched', () => {
    // Root level: columns carry renderType, so the early branch returns them as-is.
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, '');
    const backendColumns: ColumnDefinition[] = [
      { ...col('id'), renderType: 'TEXT_PREVIEW' },
      { ...col('status'), renderType: 'TEXT_PREVIEW' },
    ];
    const { result } = setup(viewConfig, WORKFLOW_CONTEXT, backendColumns);

    expect(result.current.getUniqueColumns().map(c => c.field)).toEqual(['id', 'status']);
  });
});
