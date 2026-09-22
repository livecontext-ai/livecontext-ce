// @vitest-environment jsdom
/**
 * A column literally named `id` is rendered as the FIXED ID lane only when the view builds one.
 *
 * Bug: the node Logs table stopped showing the ids of the table rows a step returned. Restoring
 * the column was half the fix - the grid then styled it as the pinned identity lane on name alone
 * (`sticky left-0`, width clamped to `idColumnWidth`), which in nested navigation is the WRONG
 * identity: the column is not leftmost (it overlays its neighbour on horizontal scroll) and
 * `idColumnWidth` is derived from `row.id`, the expansion counter, not from the real id the cell
 * now renders. Datasource-nested and snapshot views got a sticky header over a non-sticky body.
 *
 * These tests render the real grid, so they cover the half the user actually sees.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { DataTableGrid } from '../DataTableGrid';
import { createViewConfig } from '../viewConfig';
import type { DataTableController } from '../useDataTableController';

const WORKFLOW_CONTEXT = { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'table:contacts' };

const col = (field: string, header = field) => ({
  col_id: field,
  field,
  header_name: header,
  type: 'text' as const,
});

/** Two nested step rows: real ids in `data.id`, synthetic expansion counter in `row.id`. */
const nestedRows = [
  {
    id: 1,
    data_source_id: 0,
    tenant_id: 't',
    data: { email: 'ada@example.com', id: 'CUST-4711-LONG' },
    priority: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    _jsonPath: 'output.rows',
    _isWorkflowStep: true,
  },
  {
    id: 2,
    data_source_id: 0,
    tenant_id: 't',
    data: { email: 'grace@example.com', id: 'CUST-4712-LONG' },
    priority: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: null,
    _jsonPath: 'output.rows',
    _isWorkflowStep: true,
  },
];

/** The same rows as a step's ROOT rows: no `_jsonPath`, so nothing was expanded out of them. */
const rootRows = nestedRows.map(({ _jsonPath, ...row }) => row);

function controllerWith(
  viewConfig: ReturnType<typeof createViewConfig>,
  columns: ReturnType<typeof col>[],
  rows: unknown[] = nestedRows,
): DataTableController {
  return {
    rows,
    displayRows: rows,
    columns,
    getUniqueColumns: () => columns,
    getDynamicColumns: () => columns,
    getFieldPath: (field: string) => (field.startsWith('data.') ? field.slice(5) : field),
    makeCellKey: (rowId: unknown, field: string) => `${rowId}-${field}`,
    getRowUniqueKey: (row: { id: number }) => String(row.id),
    selectedColumns: new Set<string>(),
    selectedRows: new Set<string>(),
    progressTempValues: new Map(),
    viewConfig,
    sortConfig: null,
    editingCellKey: null,
    hoveredCell: null,
    draggedColumn: null,
    dragOverColumn: null,
    dragPosition: null,
    tableLoading: false,
    loadingColumns: false,
    isAddingRow: false,
    isAddingRowInline: false,
    newRowPriority: 1,
    newRowData: {},
    readOnly: true,
    revealedColumnField: null,
    revealedRowIds: new Set<number>(),
    pagination: { currentPage: 1, pageSize: 20, totalItems: 2, totalPages: 1, nextCursor: null, hasMore: false },
    handleSort: vi.fn(),
    handleDragStart: vi.fn(),
    handleDragOver: vi.fn(),
    handleDragLeave: vi.fn(),
    handleDrop: vi.fn(),
    handleSaveEdit: vi.fn(),
    handleRowDataChange: vi.fn(),
    toggleRowSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    setSelectedRows: vi.fn(),
    setHoveredCell: vi.fn(),
    setEditingCellKey: vi.fn(),
    setProgressTempValues: vi.fn(),
    setShowAddColumnModal: vi.fn(),
    startAddingRowInline: vi.fn(),
    cancelAddingRowInline: vi.fn(),
    addNewRow: vi.fn(),
    setNewRowPriority: vi.fn(),
    openEditColumn: vi.fn(),
    loadMore: vi.fn(),
  } as unknown as DataTableController;
}

function renderGrid(
  viewConfig: ReturnType<typeof createViewConfig>,
  columns: ReturnType<typeof col>[],
  workflowContext: typeof WORKFLOW_CONTEXT | undefined,
  jsonPath: string,
) {
  const { container } = render(
    <DataTableGrid
      controller={controllerWith(viewConfig, columns, jsonPath ? nestedRows : rootRows)}
      workflowContext={workflowContext}
      jsonPath={jsonPath}
      dataSourceId={0}
      navigateTo={vi.fn()}
      dataSourceBasePath="/app/tables"
    />,
  );
  return container;
}

const headerFor = (container: HTMLElement, name: string) =>
  [...container.querySelectorAll('th')].find(th => th.textContent?.trim() === name);

const bodyCells = (container: HTMLElement) => [...container.querySelectorAll('tbody td')];

beforeEach(() => {
  (window.HTMLElement.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = vi.fn();
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null,
  })) as unknown as typeof window.matchMedia;
});

afterEach(cleanup);

describe('DataTableGrid - nested `id` is a data column, not the fixed ID lane', () => {
  // Note: this one asserts the CELL renders whatever id the row carries. It passes on the buggy
  // code too, because the bug was upstream (the column filter dropped the column, and the fetch
  // overwrote the value). The regression coverage for the reported symptom lives in
  // dataTable.collisionMatrix.test.tsx and the two hook suites; this is the rendering half.
  it('renders the id a row carries, in a nested view with no identity lane', () => {
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, 'output.rows');
    const container = renderGrid(viewConfig, [col('email'), col('id')], WORKFLOW_CONTEXT, 'output.rows');

    const text = container.textContent ?? '';
    expect(text).toContain('CUST-4711-LONG');
    expect(text).toContain('CUST-4712-LONG');
  });

  it('does not pin or clamp the nested `id` column', () => {
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, 'output.rows');
    const container = renderGrid(viewConfig, [col('email'), col('id')], WORKFLOW_CONTEXT, 'output.rows');

    const header = headerFor(container, 'id');
    expect(header).toBeDefined();
    // `sticky left-0` on a column that is NOT leftmost overlays its neighbour on horizontal scroll.
    expect(header!.className).not.toContain('left-0');
    // It is sized like its siblings, not clamped to `idColumnWidth` (derived from the 1..N counter,
    // which truncates the real id the cell now renders).
    const sibling = headerFor(container, 'email');
    expect(header!.style.width).toBe(sibling!.style.width);

    const idCell = bodyCells(container).find(td => td.textContent === 'CUST-4711-LONG');
    expect(idCell).toBeDefined();
    expect(idCell!.className).not.toContain('sticky');
  });

  it('keeps the workflow ROOT id pinned - the backend emits it as the row index, first column', () => {
    // At root the columns come from the backend, which always emits `id` (the step's row index) as
    // FIELD_ORDER[0]. It is the identity lane there whether or not the view asked for one, so
    // treating "no showIdColumn" as "ordinary data column" would unpin a step root's own ID.
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, '');
    const container = renderGrid(viewConfig, [col('id'), col('status')], WORKFLOW_CONTEXT, '');

    const header = headerFor(container, 'id');
    expect(header).toBeDefined();
    expect(header!.className).toContain('left-0');
    expect(header!.style.width).not.toBe('');

    const idCell = bodyCells(container).find(td => td.className.includes('sticky'));
    expect(idCell).toBeDefined();
  });

  it('sizes the workflow ROOT lane on the id it shows too', () => {
    // Workflow rows read their identity out of `row.data` at every level, so a step row carrying a
    // long id must not be clamped to the width of the row counter either.
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, false, '');
    const container = renderGrid(viewConfig, [col('id'), col('status')], WORKFLOW_CONTEXT, '');

    const header = headerFor(container, 'id');
    expect(parseInt(header!.style.width, 10)).toBeGreaterThan(48);
  });

  it('sizes the fixed lane on the id it SHOWS, not on the expansion counter', () => {
    // showIdColumn=true + nested (the run-result modal and the inspector's node Logs): the lane
    // holds the item's real id while row.id is still 1..N, so measuring row.id clamps it to "1".
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, true, 'output.rows');
    const container = renderGrid(viewConfig, [col('id'), col('email')], WORKFLOW_CONTEXT, 'output.rows');

    const header = headerFor(container, 'ID') ?? headerFor(container, 'id');
    const width = parseInt(header!.style.width, 10);
    // 14 characters cannot fit in the 48px minimum the counter would have produced.
    expect(width).toBeGreaterThan(48);
  });

  it('still pins the fixed ID lane when the view builds one (showIdColumn=true)', () => {
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, true, 'output.rows');
    const container = renderGrid(viewConfig, [col('id'), col('email')], WORKFLOW_CONTEXT, 'output.rows');

    const header = headerFor(container, 'ID') ?? headerFor(container, 'id');
    expect(header).toBeDefined();
    expect(header!.className).toContain('left-0');
    expect(header!.style.width).not.toBe('');
  });

  it('renders a nested `id` on the tables page, where the checkbox column holds the PARENT id', () => {
    // Datasource nested: header and body must agree - a sticky header over a scrolling body was
    // the visible symptom of styling this column by name alone.
    const viewConfig = createViewConfig(undefined, false, 'payload.items');
    const container = renderGrid(viewConfig, [col('id'), col('email')], undefined, 'payload.items');

    const header = headerFor(container, 'id');
    expect(header).toBeDefined();
    expect(header!.className).not.toContain('left-0');
    expect(container.textContent).toContain('CUST-4711-LONG');
  });

  it('shows a nested `created_at` from the ITEM, never the parent row', () => {
    // Generalizing the column filter let data fields named like the system lanes through. Those
    // lanes read row.created_at / row.priority, so unguarded they would print the parent row's
    // values under the item's own column heading - visible and wrong.
    const viewConfig = createViewConfig(undefined, false, 'payload.items');
    const rows = [
      {
        ...nestedRows[0],
        created_at: '2099-12-31T00:00:00Z',
        priority: 7,
        data: { created_at: '2020-05-05', priority: 42, name: 'x' },
      },
    ];
    const { container } = render(
      <DataTableGrid
        controller={{ ...controllerWith(viewConfig, [col('created_at'), col('priority'), col('name')]), rows, displayRows: rows } as never}
        jsonPath="payload.items"
        dataSourceId={1}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );

    const text = container.textContent ?? '';
    expect(text).toContain('2020-05-05');
    expect(text).toContain('42');
    expect(text).not.toContain('2099');
  });

  it('still renders the fixed Created At / Priority lanes from the row at datasource root', () => {
    const viewConfig = createViewConfig(undefined, false, '');
    expect(viewConfig.showCreatedAt).toBe(true);
    const rows = [{ ...nestedRows[0], created_at: '2099-12-31T00:00:00Z', priority: 7, data: { name: 'x' } }];
    const { container } = render(
      <DataTableGrid
        controller={{ ...controllerWith(viewConfig, [col('priority'), col('created_at'), col('name')]), rows, displayRows: rows } as never}
        jsonPath=""
        dataSourceId={1}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );

    expect(container.textContent).toContain('2099');
    expect(container.textContent).toContain('7');
  });

  it('gives a group header the same id as the rows under it', () => {
    // Nested workflow rows all carry array_index, so the grid groups them and emits a parent
    // header per group. Printing the synthetic parentId there contradicts its own children.
    // showIdColumn: the identity lane is what the header renders; a plain data `id` column shows
    // the group's item count there instead, like every other data column.
    const viewConfig = createViewConfig(WORKFLOW_CONTEXT, true, 'output.rows');
    const child = { ...nestedRows[0], data: { ...nestedRows[0].data, array_index: 0 } };
    const displayRows = [
      { type: 'parent', parentId: 1, subRows: [child] },
      child,
    ];
    const { container } = render(
      <DataTableGrid
        controller={{ ...controllerWith(viewConfig, [col('email'), col('id')]), rows: [child], displayRows } as never}
        workflowContext={WORKFLOW_CONTEXT}
        jsonPath="output.rows"
        dataSourceId={0}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );

    const idCells = [...container.querySelectorAll('tbody td')].filter(td => td.textContent === 'CUST-4711-LONG');
    // One in the group header, one in the child row - they agree.
    expect(idCells).toHaveLength(2);
  });

  it('renders the system Index lane read-only, and a same-named root column as data', () => {
    const nested = createViewConfig(undefined, false, 'payload.items');
    const withIndex = [{ ...nestedRows[0], data: { array_index: 3, name: 'x' } }];
    const { container } = render(
      <DataTableGrid
        controller={{ ...controllerWith(nested, [col('array_index'), col('name')]), rows: withIndex, displayRows: withIndex } as never}
        jsonPath="payload.items"
        dataSourceId={1}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );
    expect(container.querySelector('span[title="Array index (read-only)"]')).not.toBeNull();

    cleanup();

    const root = createViewConfig(undefined, false, '');
    const { container: rootContainer } = render(
      <DataTableGrid
        controller={{ ...controllerWith(root, [col('array_index'), col('name')]), rows: withIndex, displayRows: withIndex } as never}
        jsonPath=""
        dataSourceId={1}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );
    expect(rootContainer.querySelector('span[title="Array index (read-only)"]')).toBeNull();
  });

  it('keeps hiding the root-level `id` on the tables page (shown inside the checkbox column)', () => {
    const viewConfig = createViewConfig(undefined, false, '');
    const container = renderGrid(viewConfig, [col('id'), col('email')], undefined, '');

    expect(headerFor(container, 'id')).toBeUndefined();
  });
});
