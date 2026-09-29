// @vitest-environment jsdom
/**
 * The CSV names the same row the grid does.
 *
 * The export always wrote `String(row.id)` into its ID column and filtered `id` out of the data
 * headers. For a nested step output that `row.id` is the expansion counter (1..N), so a table whose
 * rows the grid now shows as 4711/4712 exported as 1/2 - what you see is not what you export.
 *
 * The synthetic id is still correct for rows that have no id of their own; the row records which
 * one it is (`_injectedDataKeys`), so this never has to guess by comparing values.
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';

import { useTableExport } from '../useTableExport';
import { applyDisplayTimeZone, clearDisplayTimeZone } from '@/lib/utils/timezone';
import { createViewConfig, getFixedColumns, getRowLevelExportFields } from '../../viewConfig';
import type { ColumnDefinition, DataSourceItemRow } from '../../types';

const columns: ColumnDefinition[] = [
  { col_id: 'id', field: 'id', header_name: 'id', type: 'text', editable: false, sortable: true, filterable: true },
  { col_id: 'email', field: 'email', header_name: 'Email', type: 'text', editable: false, sortable: true, filterable: true },
];

/** A NESTED row: one item expanded out of a stored row, so `row.id` is the 1..N counter. */
const row = (
  id: number,
  data: Record<string, unknown>,
  injected: string[],
): DataSourceItemRow => ({
  id,
  data_source_id: 0,
  tenant_id: 't',
  data,
  priority: 0,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  _injectedDataKeys: injected,
  _jsonPath: 'output.rows',
} as DataSourceItemRow);

/** The fixed set a plain datasource ROOT view produces - the default for these fixtures. */
const ROOT_CONFIG = createViewConfig(undefined, false, '');
const ROOT_FIXED_FIELDS = getFixedColumns(ROOT_CONFIG);
const ROOT_ROW_LEVEL = getRowLevelExportFields(ROOT_CONFIG);
const NESTED_CONFIG = createViewConfig(undefined, false, 'payload.items');
const NESTED_FIXED_FIELDS = getFixedColumns(NESTED_CONFIG);
const NESTED_ROW_LEVEL = getRowLevelExportFields(NESTED_CONFIG);

/** Split one CSV line into fields, honouring quoting. */
const parseCsvLine = (line: string): string[] =>
  (line.match(/("([^"]|"")*"|[^,]*)(,|$)/g) ?? [])
    .filter((chunk, i, all) => i < all.length - 1 || chunk !== '')
    .map(chunk => chunk.replace(/,$/, ''))
    .map(chunk => (chunk.startsWith('"') ? chunk.slice(1, -1).replace(/""/g, '"') : chunk));

const setupWith = (rows: DataSourceItemRow[], cols: ColumnDefinition[], fixedColumnFields: string[], rowLevelFields?: string[]) =>
  renderHook(() =>
    useTableExport({
      dataSourceId: 1,
      rows,
      columns: cols,
      selectedRows: new Set<string>(),
      selectedColumns: new Set<string>(),
      getRowUniqueKey: (r: DataSourceItemRow) => String(r.id),
      searchQuery: '',
      sortConfig: null,
      pagination: { currentPage: 1, pageSize: 20, totalItems: rows.length, totalPages: 1, nextCursor: null, hasMore: false },
      addToast: vi.fn(),
      fixedColumnFields,
      rowLevelFields: rowLevelFields ?? ROOT_ROW_LEVEL,
    })
  );

const setup = (rows: DataSourceItemRow[], fixedColumnFields: string[] = ROOT_FIXED_FIELDS, rowLevelFields?: string[]) =>
  renderHook(() =>
    useTableExport({
      dataSourceId: 1,
      rows,
      columns,
      selectedRows: new Set<string>(),
      selectedColumns: new Set<string>(),
      getRowUniqueKey: (r: DataSourceItemRow) => String(r.id),
      searchQuery: '',
      sortConfig: null,
      pagination: { currentPage: 1, pageSize: 20, totalItems: rows.length, totalPages: 1, nextCursor: null, hasMore: false },
      addToast: vi.fn(),
      fixedColumnFields,
      rowLevelFields: rowLevelFields ?? ROOT_ROW_LEVEL,
    })
  );

describe('useTableExport - exported ID matches the displayed one', () => {
  it('exports the row\'s own id, not the expansion counter', () => {
    const rows = [row(1, { id: 4711, email: 'ada@example.com' }, [])];
    const { result } = setup(rows);

    const csv = result.current.convertToCSV(rows, columns);
    const [, dataLine] = csv.split('\n');
    expect(dataLine.startsWith('4711,')).toBe(true);
  });

  it('falls back to the synthetic id when that is all the row has', () => {
    const rows = [row(7, { id: 7, email: 'ada@example.com' }, ['id'])];
    const { result } = setup(rows);

    const csv = result.current.convertToCSV(rows, columns);
    expect(csv.split('\n')[1].startsWith('7,')).toBe(true);
  });

  it.each([
    ['a string id', { id: 'CUST-4711', email: 'a@b.c' }, undefined, 'CUST-4711'],
    ['no _injectedDataKeys at all', { id: 4711, email: 'a@b.c' }, undefined, '4711'],
    // Present-but-falsy ids are real values, so the export reports them, not the row counter.
    ['a falsy id', { id: 0, email: 'a@b.c' }, [], '0'],
    ['an empty-string id', { id: '', email: 'a@b.c' }, [], ''],
    ['no data at all', undefined, undefined, '3'],
  ])('reports the right identity with %s', (_label, data, injected, expected) => {
    const rows = [{
      id: 3,
      data_source_id: 0,
      tenant_id: 't',
      data,
      priority: 0,
      created_at: '2026-01-01T00:00:00Z',
      updated_at: null,
      _jsonPath: 'output.rows',
      ...(injected ? { _injectedDataKeys: injected } : {}),
    } as unknown as DataSourceItemRow];
    const { result } = setup(rows);

    expect(result.current.convertToCSV(rows, columns).split('\n')[1].split(',')[0]).toBe(expected);
  });

  it('reports the id exactly once, in the base ID column', () => {
    const rows = [row(1, { id: 4711, email: 'ada@example.com' }, [])];
    const { result } = setup(rows);

    const csv = result.current.convertToCSV(rows, columns);
    const [headerLine, dataLine] = csv.split('\n');
    expect(headerLine).toMatch(new RegExp(`^ID,Priority,Created At \\([A-Za-z0-9/_+-]+\\),Email$`));
    // A formatted date carries commas, so the row is only well-formed if the base columns are
    // quoted like the data ones: 4 fields, not one row smeared across six.
    expect(parseCsvLine(dataLine)).toEqual(['4711', '0', expect.any(String), 'ada@example.com']);
  });

  it('keeps reporting the row id at ROOT, where the grid shows the row id too', () => {
    // A root table may own a column literally called `id`. The grid still shows the row's own id
    // there (inside the checkbox lane), so preferring the field would make export and screen
    // disagree - the exact thing displayIdOf exists to prevent.
    const rows = [{
      id: 3,
      data_source_id: 0,
      tenant_id: 't',
      data: { id: 4711, email: 'ada@example.com' },
      priority: 0,
      created_at: '2026-01-01T00:00:00Z',
      updated_at: null,
    } as unknown as DataSourceItemRow];
    const { result } = setup(rows);

    expect(parseCsvLine(result.current.convertToCSV(rows, columns).split('\n')[1])[0]).toBe('3');
  });

  it('exports a nested column named like a base one instead of dropping it', () => {
    // The base ID / Priority / Created At columns carry ROW-level values. A nested item's own
    // `created_at` is data: filtered out, it was visible in the grid and missing from the export.
    const rows = [row(1, { created_at: '2020-05-05', email: 'ada@example.com' }, [])];
    const cols = [
      { col_id: 'created_at', field: 'created_at', header_name: 'created_at', type: 'text', editable: false, sortable: true, filterable: true },
      ...columns.slice(1),
    ] as ColumnDefinition[];
    const { result } = setup(rows, NESTED_FIXED_FIELDS, NESTED_ROW_LEVEL);

    const csv = result.current.convertToCSV(rows, cols);
    // Nested: no base columns apply, so the item's own `created_at` is simply one of the data ones.
    expect(csv.split('\n')[0]).toBe('created_at,Email');
    expect(parseCsvLine(csv.split('\n')[1])).toContain('2020-05-05');
  });

  it('exports the content of an array of primitives, driven by the REAL fixed set', () => {
    // The view's fixed set for a nested datasource path is ['checkbox','array_index','value'].
    // Excluding all of it from the export drops `value` - which IS the content here - leaving a
    // file of nothing but ids and timestamps. Only the fields the BASE columns carry may be
    // skipped, and `value` / `array_index` have no base column.
    const nestedTags = createViewConfig(undefined, false, 'payload.tags');
    const fixedColumnFields = getFixedColumns(nestedTags);
    expect(fixedColumnFields).toContain('value');

    const rows = [row(1, { value: 'alpha', array_index: 0 }, ['id'])];
    const cols = [
      { col_id: 'value', field: 'value', header_name: 'Value', type: 'text', editable: true, sortable: true, filterable: true },
    ] as ColumnDefinition[];
    const { result } = setup(rows, fixedColumnFields, getRowLevelExportFields(nestedTags));

    const csv = result.current.convertToCSV(rows, cols);
    // No Priority / Created At base columns here: the nested view renders neither lane, and those
    // row-level values belong to the PARENT row.
    expect(csv.split('\n')[0]).toBe('Value');
    expect(parseCsvLine(csv.split('\n')[1])).toContain('alpha');
  });

  it('still folds Priority and Created At into the base columns at datasource root', () => {
    const fixedColumnFields = getFixedColumns(createViewConfig(undefined, false, ''));
    expect(fixedColumnFields).toEqual(expect.arrayContaining(['priority', 'created_at']));

    const rows = [row(1, { email: 'ada@example.com' }, ['id'])];
    const cols = [
      { col_id: 'priority', field: 'priority', header_name: 'Priority', type: 'number', editable: false, sortable: true, filterable: false },
      ...columns.slice(1),
    ] as ColumnDefinition[];
    const { result } = setup(rows, fixedColumnFields);

    // One Priority column, the base one - not a second, empty data column beside it.
    expect(result.current.convertToCSV(rows, cols).split('\n')[0])
      .toMatch(new RegExp(`^ID,Priority,Created At \\([A-Za-z0-9/_+-]+\\),Email$`));
  });

  it('describes a row identically in CSV and JSON, field for field', async () => {
    // The two exporters drifted: JSON seeded `{id, priority, created_at}` from the ROW and then let
    // a data column of the same name overwrite it, so the row's own values vanished from JSON while
    // the CSV kept both under duplicate headers. Compare the two outputs, do not spot-check.
    const blobs: Blob[] = [];
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = vi.fn((b: Blob) => { blobs.push(b); return 'blob:x'; });
    (URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = vi.fn();

    const rows = [row(1, { id: 4711, priority: 99, created_at: '2020-05-05', email: 'a@b.c' }, [])];
    const cols = [
      { col_id: 'priority', field: 'priority', header_name: 'priority', type: 'number', editable: false, sortable: true, filterable: true },
      { col_id: 'created_at', field: 'created_at', header_name: 'created_at', type: 'text', editable: false, sortable: true, filterable: true },
      ...columns,
    ] as ColumnDefinition[];
    const { result } = setupWith(rows, cols, NESTED_FIXED_FIELDS, NESTED_ROW_LEVEL);

    const csv = result.current.convertToCSV(rows, cols);
    await result.current.handleExportJSON();
    const json = JSON.parse(await blobs[0].text()).data[0];

    const csvHeaders = parseCsvLine(csv.split('\n')[0]);
    const csvValues = parseCsvLine(csv.split('\n')[1]);
    // No name appears twice, and the two formats carry the same fields with the same values.
    expect(new Set(csvHeaders).size).toBe(csvHeaders.length);
    // Nested: no Priority / Created At base columns, and `id` is item data with its own column.
    // The base ID column then reports the row's own identity, not a repeat of that field.
    // Nested: none of the three base columns applies (they are the PARENT row's), so every field
    // here is the item's own - and `id` appears exactly once, as data, in both formats.
    expect(csvHeaders).toEqual(['priority', 'created_at', 'id', 'Email']);
    expect(csvValues).toEqual(['99', '2020-05-05', '4711', 'a@b.c']);
    expect(json).toEqual({ priority: 99, created_at: '2020-05-05', id: 4711, email: 'a@b.c' });
  });

  it('exports JSON with the same identity and the same columns as the CSV', async () => {
    // The two exporters of one table drifted: JSON still wrote `row.id` and kept its own hard-coded
    // column list, so the same rows described themselves differently depending on the format.
    const blobs: Blob[] = [];
    const createObjectURL = vi.fn((blob: Blob) => { blobs.push(blob); return 'blob:x'; });
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = createObjectURL;
    (URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = vi.fn();

    const rows = [row(1, { id: 4711, created_at: '2020-05-05', email: 'ada@example.com' }, [])];
    const cols = [
      { col_id: 'created_at', field: 'created_at', header_name: 'created_at', type: 'text', editable: false, sortable: true, filterable: true },
      ...columns,
    ] as ColumnDefinition[];
    const { result } = renderHook(() =>
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
        fixedColumnFields: NESTED_FIXED_FIELDS,
        rowLevelFields: NESTED_ROW_LEVEL,
      })
    );

    await result.current.handleExportJSON();

    const payload = JSON.parse(await blobs[0].text());
    expect(payload.data[0].id).toBe(4711);
    // The nested item's own created_at survives instead of being filtered out by name.
    expect(payload.data[0].created_at).toBe('2020-05-05');
  });

  it('quotes a header that carries a comma, like it quotes the values', () => {
    // Column names are user input (`addNewColumn` validates no characters), so a header can carry a
    // separator. Escaping only the values left the header row splitting into extra fields.
    const rows = [row(1, { id: 4711, revenue: 12 }, [])];
    const cols = [
      { col_id: 'revenue', field: 'revenue', header_name: 'Revenue, USD', type: 'number', editable: false, sortable: true, filterable: true },
    ] as ColumnDefinition[];
    const { result } = setup(rows, ROOT_FIXED_FIELDS);

    const [headerLine] = result.current.convertToCSV(rows, cols).split('\n');
    expect(headerLine).toMatch(new RegExp(`^ID,Priority,Created At \\([A-Za-z0-9/_+-]+\\),"Revenue, USD"$`));
    expect(parseCsvLine(headerLine)).toHaveLength(4);
  });

  it('announces in the JSON metadata exactly the fields its data objects carry', async () => {
    // The metadata was built from the unfiltered column set, so it advertised row-level columns
    // that no data object contained.
    const blobs: Blob[] = [];
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = vi.fn((b: Blob) => { blobs.push(b); return 'blob:x'; });
    (URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = vi.fn();

    const rows = [row(1, { id: 4711, email: 'a@b.c' }, [])];
    const { result } = setup(rows, ROOT_FIXED_FIELDS);
    await result.current.handleExportJSON();

    const payload = JSON.parse(await blobs[0].text());
    const announced = payload.columns.map((c: { field: string }) => c.field);
    const carried = Object.keys(payload.data[0]).filter(k => !ROOT_ROW_LEVEL.includes(k));
    expect(announced).toEqual(carried);
    expect(announced).not.toContain('id');
  });

  it('honours a column subset without smuggling the row-level lanes back in', async () => {
    // The subset filter used to force-keep every row-level name, which the next filter removed
    // again - dead for CSV, and for JSON it desynchronised metadata from data. Driven through
    // handleExportCSV, so the filter that actually changed is the one that runs.
    const blobs: Blob[] = [];
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = vi.fn((b: Blob) => { blobs.push(b); return 'blob:x'; });
    (URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = vi.fn();

    const rows = [row(1, { id: 4711, email: 'a@b.c' }, [])];
    const { result } = renderHook(() =>
      useTableExport({
        dataSourceId: 1,
        rows,
        columns,
        selectedRows: new Set<string>(),
        selectedColumns: new Set(['email']),
        getRowUniqueKey: (r: DataSourceItemRow) => String(r.id),
        searchQuery: '',
        sortConfig: null,
        pagination: { currentPage: 1, pageSize: 20, totalItems: 1, totalPages: 1, nextCursor: null, hasMore: false },
        addToast: vi.fn(),
        fixedColumnFields: ROOT_FIXED_FIELDS,
        rowLevelFields: ROOT_ROW_LEVEL,
      })
    );

    await result.current.handleExportCSV();

    const [headerLine] = (await blobs[0].text()).split('\n');
    expect(headerLine).toMatch(new RegExp(`^ID,Priority,Created At \\([A-Za-z0-9/_+-]+\\),Email$`));
  });

  it('offers a data column named like a fixed one as fillable when the view builds no such lane', () => {
    const rows = [row(1, { id: 4711, email: 'ada@example.com' }, [])];
    const { result } = setup(rows, ['checkbox', 'priority', 'created_at']);

    expect(result.current.getDynamicColumns().map(c => c.field)).toEqual(['id', 'email']);
  });

  it('excludes the fields the view renders as fixed lanes', () => {
    const rows = [row(1, { id: 4711, email: 'ada@example.com' }, [])];
    const { result } = setup(rows, ['checkbox', 'id', 'priority', 'created_at']);

    expect(result.current.getDynamicColumns().map(c => c.field)).toEqual(['email']);
  });

  /**
   * The timestamp column says which zone it is in, because the file outlives the session.
   *
   * <p>Absolute timestamps are drawn in the reader's display zone and no longer carry the zone
   * beside each value: inside the product that label only restated a preference the reader had
   * set. A CSV is the case where that reasoning stops holding - it is opened by somebody else, on
   * another machine, with none of our preferences - so the ATTRIBUTION moves to the column name,
   * once per file instead of once per row.
   *
   * <p>Asserted against a pinned zone rather than the default, because the default resolves to UTC
   * here and an implementation that hardcoded "UTC" would pass.
   */
  it("names the reader's display zone in the Created At column", async () => {
    try {
      applyDisplayTimeZone('Asia/Tokyo');
      const rows = [row(1, { id: 4711, email: 'ada@example.com' }, [])];
      const { result } = setup(rows, ['checkbox', 'id', 'priority', 'created_at']);

      const [headerLine] = result.current.convertToCSV(rows, result.current.getDynamicColumns()).split('\n');

      expect(headerLine).toContain('Created At (Asia/Tokyo)');
    } finally {
      clearDisplayTimeZone();
    }
  });
});
