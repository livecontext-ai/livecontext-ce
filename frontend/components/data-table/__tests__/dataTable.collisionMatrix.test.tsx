// @vitest-environment jsdom
/**
 * Every view mode, against data whose fields are named after the system columns.
 *
 * The bug this suite guards was fixed four times, and three of those fixes broke a DIFFERENT mode -
 * each time one no spot test covered. The names `id`, `value`, `array_index`, `priority` and
 * `created_at` are all legal user/step fields AND all names the grid reserves for lanes it renders
 * out of the ROW rather than out of `row.data`. Whether a given name is data or a lane depends on
 * the view, so the only honest test is the full matrix.
 *
 * It wires the REAL column filter (`useColumnManagement`, fed by `createViewConfig`) into the REAL
 * grid, so a column that the filter drops or the grid mis-sources shows up here as a missing or
 * wrong cell - not as a green test with a hand-supplied `getUniqueColumns`.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, renderHook, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}));

import { DataTableGrid } from '../DataTableGrid';
import { useColumnManagement } from '../hooks/useColumnManagement';
import { createViewConfig, getFixedColumns, getRowLevelExportFields } from '../viewConfig';
import { useTableExport } from '../hooks/useTableExport';
import type { ColumnDefinition, DataSourceItemRow } from '../types';
import type { DataTableController } from '../useDataTableController';

const WORKFLOW_CONTEXT = { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'table:contacts' };

/** Values that belong to the ROW, i.e. what a system lane must show. */
const ROW_ID = 7;
const ROW_PRIORITY = 42;
const ROW_CREATED_AT = '2099-12-31T00:00:00Z';

/** Values that belong to the navigated ITEM, i.e. what a data column must show. */
const ITEM = {
  id: 'ITEM-4711',
  value: 'ITEM-VALUE',
  array_index: 0,
  priority: 3,
  created_at: '2020-05-05',
  name: 'ITEM-NAME',
};

const col = (field: string): ColumnDefinition => ({
  col_id: field,
  field,
  header_name: field,
  type: 'text',
  editable: false,
  sortable: true,
  filterable: true,
});

/** Columns as the frontend derives them from navigated data: every colliding name, no renderType. */
const DATA_COLUMNS = Object.keys(ITEM).map(col);

const makeRow = (nested: boolean, isWorkflowStep: boolean): DataSourceItemRow => ({
  id: ROW_ID,
  data_source_id: 0,
  tenant_id: 't',
  data: { ...ITEM },
  priority: ROW_PRIORITY,
  created_at: ROW_CREATED_AT,
  updated_at: null,
  ...(nested ? { _jsonPath: 'payload.items' } : {}),
  ...(isWorkflowStep ? { _isWorkflowStep: true } : {}),
} as DataSourceItemRow);

function controllerWith(
  viewConfig: ReturnType<typeof createViewConfig>,
  getUniqueColumns: () => ColumnDefinition[],
  rows: DataSourceItemRow[],
): DataTableController {
  return {
    rows,
    displayRows: rows,
    columns: getUniqueColumns(),
    getUniqueColumns,
    getDynamicColumns: getUniqueColumns,
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
    pagination: { currentPage: 1, pageSize: 20, totalItems: rows.length, totalPages: 1, nextCursor: null, hasMore: false },
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

interface Mode {
  name: string;
  workflowContext?: typeof WORKFLOW_CONTEXT;
  showIdColumn: boolean;
  jsonPath: string;
  isSnapshot: boolean;
  /** Column names the grid must NOT show (the row-level lane covers them). */
  hidden: string[];
  /** Fields whose cell must show the ROW's value, because a system lane renders them. */
  fromRow: string[];
}

const MODES: Mode[] = [
  {
    name: 'datasource root',
    showIdColumn: false, jsonPath: '', isSnapshot: false,
    // The row id lives inside the checkbox lane here, so an `id` column would be an empty extra one.
    hidden: ['id'],
    fromRow: ['priority', 'created_at'],
  },
  {
    name: 'datasource nested',
    showIdColumn: false, jsonPath: 'payload.items', isSnapshot: false,
    hidden: [],
    fromRow: [],
  },
  {
    name: 'workflow root',
    workflowContext: WORKFLOW_CONTEXT, showIdColumn: false, jsonPath: '', isSnapshot: false,
    hidden: [],
    fromRow: [],
  },
  {
    name: 'workflow nested, no identity lane',
    workflowContext: WORKFLOW_CONTEXT, showIdColumn: false, jsonPath: 'payload.items', isSnapshot: false,
    hidden: [],
    fromRow: [],
  },
  {
    name: 'workflow nested with identity lane (node Logs, run-result Logs)',
    workflowContext: WORKFLOW_CONTEXT, showIdColumn: true, jsonPath: 'payload.items', isSnapshot: false,
    hidden: [],
    fromRow: [],
  },
  {
    name: 'marketplace snapshot root',
    showIdColumn: false, jsonPath: '', isSnapshot: true,
    hidden: [],
    fromRow: [],
  },
  {
    name: 'marketplace snapshot nested',
    showIdColumn: false, jsonPath: 'payload.items', isSnapshot: true,
    hidden: [],
    fromRow: [],
  },
];

/** Render one mode through the real column filter and return the rendered cells by column. */
function renderMode(mode: Mode) {
  const viewConfig = createViewConfig(
    mode.workflowContext, mode.showIdColumn, mode.jsonPath, false, mode.isSnapshot,
  );
  const hook = renderHook(() =>
    useColumnManagement({ viewConfig, workflowContext: mode.workflowContext ?? null }),
  );
  act(() => {
    hook.result.current.setColumns(DATA_COLUMNS);
  });
  const getUniqueColumns = () => hook.result.current.getUniqueColumns();
  const rows = [makeRow(!!mode.jsonPath, !!mode.workflowContext)];

  const { container } = render(
    <DataTableGrid
      controller={controllerWith(viewConfig, getUniqueColumns, rows)}
      workflowContext={mode.workflowContext}
      jsonPath={mode.jsonPath}
      dataSourceId={mode.workflowContext ? 0 : 1}
      navigateTo={vi.fn()}
      dataSourceBasePath="/app/tables"
    />,
  );

  const fields = getUniqueColumns().map(c => c.field);
  const cells = [...([...container.querySelectorAll('tbody tr')][0]?.querySelectorAll('td') ?? [])];
  // Cells are matched to columns by position, so a branch that returns null for one column would
  // shift every later assertion onto the wrong field and quietly pass. Fail loudly instead.
  expect(cells.length, 'one cell per column').toBe(fields.length);
  const byField = new Map<string, string>();
  fields.forEach((field, i) => byField.set(field, (cells[i]?.textContent ?? '').trim()));
  return { fields, byField, container };
}

beforeEach(() => {
  (window.HTMLElement.prototype as unknown as { scrollIntoView: unknown }).scrollIntoView = vi.fn();
  window.matchMedia = ((query: string) => ({
    matches: false, media: query,
    addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(), onchange: null,
  })) as unknown as typeof window.matchMedia;
});

afterEach(cleanup);

describe.each(MODES)('collision matrix - $name', (mode) => {
  it('shows every data column except the ones a row-level lane already covers', () => {
    const { fields } = renderMode(mode);

    for (const name of Object.keys(ITEM)) {
      if (mode.hidden.includes(name)) {
        expect(fields, `${name} must stay hidden`).not.toContain(name);
      } else {
        expect(fields, `${name} must be shown`).toContain(name);
      }
    }
  });

  it('sources each cell from the ITEM, except where a system lane owns the name', () => {
    const { byField } = renderMode(mode);

    for (const [name, itemValue] of Object.entries(ITEM)) {
      if (mode.hidden.includes(name)) continue;
      const rendered = byField.get(name) ?? '';

      if (mode.fromRow.includes(name)) {
        // A system lane: it must NOT be showing the item's value.
        expect(rendered, `${name} is a system lane here`).not.toBe(String(itemValue));
        continue;
      }
      // A data column: it must show the item's own value, never the row's.
      expect(rendered, `${name} must come from the item`).toContain(String(itemValue));
    }
  });

  it('never renders the same column twice', () => {
    const { fields } = renderMode(mode);
    expect(new Set(fields).size).toBe(fields.length);
  });

  it('exports the id the grid shows, and every column the grid shows', () => {
    // The grid half of this matrix ran for five rounds while the export was only ever tested with
    // hand-made fixed sets - and that is exactly where the last defect hid: a column visible on
    // screen and absent from the file, with the base ID column reporting a different row.
    const viewConfig = createViewConfig(
      mode.workflowContext, mode.showIdColumn, mode.jsonPath, false, mode.isSnapshot,
    );
    const fixedColumnFields = getFixedColumns(viewConfig);
    const { fields, byField } = renderMode(mode);

    const rows = [makeRow(!!mode.jsonPath, !!mode.workflowContext)];
    const cols = fields.map(f => col(f));
    const exportHook = renderHook(() =>
      useTableExport({
        dataSourceId: 1,
        rows,
        columns: cols,
        selectedRows: new Set<string>(),
        selectedColumns: new Set<string>(),
        getRowUniqueKey: (r: DataSourceItemRow) => String(r.id),
        searchQuery: '',
        sortConfig: null,
        pagination: { currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false },
        addToast: vi.fn(),
        fixedColumnFields,
        rowLevelFields: getRowLevelExportFields(viewConfig),
      }),
    );

    const [headerLine, dataLine] = exportHook.result.current.convertToCSV(rows, cols).split('\n');
    const headers = headerLine.split(',');
    const values = dataLine.split(',');

    // The file and the screen agree on the id, wherever the view puts it: in the base ID column
    // when `id` names the identity, in the `id` data column when it is the item's own field.
    const rowLevel = getRowLevelExportFields(viewConfig);
    if (!mode.hidden.includes('id')) {
      const idIndex = rowLevel.includes('id') ? headers.indexOf('ID') : headers.indexOf('id');
      expect(idIndex, 'the id must have exactly one home in the file').toBeGreaterThanOrEqual(0);
      expect(values[idIndex]).toBe(byField.get('id'));
    }

    // Every column the grid shows is in the file, either as a base column or as a data column.
    for (const field of fields) {
      if (field === 'checkbox') continue;
      const asBase = field === 'id' ? 'ID' : field === 'priority' ? 'Priority' : field === 'created_at' ? 'Created At' : null;
      const present = headers.includes(field) || (asBase !== null && headers.includes(asBase));
      expect(present, `${field} must reach the export`).toBe(true);
    }

    // And no name is emitted twice.
    expect(new Set(headers).size).toBe(headers.length);
  });

  it('survives a non-scalar `id` instead of unmounting the table', () => {
    // Preserving the item's real id let a `{$oid: ...}` (Mongo-ish step output) reach the cell,
    // where React refuses it as a child and takes the whole Logs table down. `displayIdOf` rejects
    // a non-scalar and falls back to the row id; an open-coded `?? row.id` did not.
    const viewConfig = createViewConfig(
      mode.workflowContext, mode.showIdColumn, mode.jsonPath, false, mode.isSnapshot,
    );
    const hook = renderHook(() =>
      useColumnManagement({ viewConfig, workflowContext: mode.workflowContext ?? null }),
    );
    act(() => { hook.result.current.setColumns(DATA_COLUMNS); });

    const row = makeRow(!!mode.jsonPath, !!mode.workflowContext);
    (row.data as Record<string, unknown>).id = { $oid: 'deadbeef' };

    expect(() =>
      render(
        <DataTableGrid
          controller={controllerWith(viewConfig, () => hook.result.current.getUniqueColumns(), [row])}
          workflowContext={mode.workflowContext}
          jsonPath={mode.jsonPath}
          dataSourceId={mode.workflowContext ? 0 : 1}
          navigateTo={vi.fn()}
          dataSourceBasePath="/app/tables"
        />,
      ),
    ).not.toThrow();
  });

  it('gives a data `id` column the same affordances as its siblings', () => {
    // `isFixed` (hover highlight, drag target, cursor) used to key on the NAME alone, so a data
    // column called `id` was the one column in the table you could not drag or hover.
    const { container, fields } = renderMode(mode);
    const headers = [...container.querySelectorAll('th')];
    const idHeader = headers.find(th => th.textContent?.trim() === 'id' || th.textContent?.trim() === 'ID');

    if (mode.hidden.includes('id')) {
      // The checkbox lane prints the row id inside itself here, so there is no `id` column at all -
      // an empty extra lane is the thing that must NOT come back.
      expect(fields).not.toContain('id');
      expect(idHeader).toBeUndefined();
      return;
    }

    const sibling = headers.find(th => th.textContent?.trim() === 'name');
    expect(idHeader).toBeDefined();
    expect(sibling).toBeDefined();

    // `id` IS the identity lane when the view builds one, and at workflow root, where the backend
    // emits it as the step's row index. Pinned and inert is correct there.
    const isIdentityLane = mode.showIdColumn || (!!mode.workflowContext && !mode.jsonPath);
    if (isIdentityLane) {
      expect(idHeader!.style.left).not.toBe('');
    } else {
      expect(idHeader!.style.cursor).toBe(sibling!.style.cursor);
      expect(idHeader!.className.includes('hover:bg-theme-tertiary'))
        .toBe(sibling!.className.includes('hover:bg-theme-tertiary'));
    }
  });

  it('offers the column menu on a data column named like a lane, and never on a lane', () => {
    // FIXED_COLUMNS gated the kebab by name, so a table owning a column called `value` or
    // `array_index` had the one column with no rename and no delete. The menu itself stays off
    // wherever column management is off (nested navigation, workflow, snapshot).
    const viewConfig = createViewConfig(
      mode.workflowContext, mode.showIdColumn, mode.jsonPath, false, mode.isSnapshot,
    );
    const hook = renderHook(() =>
      useColumnManagement({ viewConfig, workflowContext: mode.workflowContext ?? null }),
    );
    act(() => { hook.result.current.setColumns(DATA_COLUMNS); });

    const { container } = render(
      <DataTableGrid
        controller={{
          ...controllerWith(viewConfig, () => hook.result.current.getUniqueColumns(), [makeRow(!!mode.jsonPath, !!mode.workflowContext)]),
          readOnly: false,
        } as never}
        workflowContext={mode.workflowContext}
        jsonPath={mode.jsonPath}
        dataSourceId={mode.workflowContext ? 0 : 1}
        navigateTo={vi.fn()}
        dataSourceBasePath="/app/tables"
      />,
    );

    const menuIn = (name: string) =>
      [...container.querySelectorAll('th')]
        .find(th => th.textContent?.trim().startsWith(name))
        ?.querySelector('button[title]') ?? null;

    if (viewConfig.allowColumnManagement) {
      // Root tables page: `value` is the table's own column and gets the same menu as `name`.
      expect(menuIn('value')).not.toBeNull();
      expect(menuIn('name')).not.toBeNull();
      expect(menuIn('priority')).toBeNull();
    } else {
      expect(menuIn('name')).toBeNull();
    }
  });
});
