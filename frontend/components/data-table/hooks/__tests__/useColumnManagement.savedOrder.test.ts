// @vitest-environment jsdom
/**
 * Regression tests for the saved column order in {@link useColumnManagement}.
 *
 * Two symptoms, one cause. `getAllColumns` matched a saved `column_order` entry
 * against `col.field` VERBATIM, but the two writers of that array disagree on
 * the spelling: the backend appends the bare column name when a column is
 * created, the grid saves the rendered field with its `data.` prefix. Entries
 * that did not match were dropped, and the grid fell back to the order the API
 * lists the columns in, which is the key order of a JSONB object (length, then
 * bytes) and has nothing to do with how the table was arranged or when a column
 * was added. So a reordered table came back in an arbitrary order, and a new
 * column appeared wherever its NAME sorted instead of at the end.
 *
 * The rule the fix installs, and what these tests hold it to:
 *   - a column the saved order NAMES takes its saved position;
 *   - a column it does not name DOES NOT MOVE (it is not exiled to the end,
 *     which would bury the selection lane on a partial order);
 *   - a saved entry matches its column in either spelling, except where two
 *     rendered columns share a stripped key, where only the exact one counts.
 */
import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useColumnManagement } from '../useColumnManagement';
import { createViewConfig } from '../../viewConfig';
import type { ColumnDefinition, ColumnOrder } from '../../types';

const col = (field: string, extra: Partial<ColumnDefinition> = {}): ColumnDefinition => ({
  col_id: field,
  field,
  header_name: field,
  type: 'text',
  editable: true,
  sortable: true,
  filterable: true,
  ...extra,
});

/** The root table view: checkbox / priority / created_at lanes, user columns after. */
const ROOT_VIEW = createViewConfig(undefined, false, undefined, false, false);
const LANES = ['checkbox', 'priority', 'created_at'];

type WorkflowContext = Parameters<typeof useColumnManagement>[0]['workflowContext'];

const setup = (
  columns: ColumnDefinition[],
  columnOrder: ColumnOrder[],
  options: { workflowContext?: WorkflowContext; snapshot?: boolean; jsonPath?: string } = {},
) => {
  const { workflowContext = null, snapshot = false, jsonPath } = options;
  const view = workflowContext
    ? createViewConfig(workflowContext, false, jsonPath, false, false)
    : createViewConfig(undefined, false, jsonPath, false, snapshot);
  const hook = renderHook(() => useColumnManagement({ viewConfig: view, workflowContext }));
  act(() => {
    hook.result.current.setColumns(columns);
    hook.result.current.setColumnOrder(columnOrder);
  });
  return hook;
};

const fieldsOf = (hook: ReturnType<typeof setup>) =>
  hook.result.current.getAllColumns().map(c => c.field);

describe('useColumnManagement saved column order', () => {
  it('follows the saved order whichever spelling each entry uses', () => {
    // As the API returns them: JSONB key order, which is by name length.
    const columns = [col('data.city'), col('data.email'), col('data.company_name')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.company_name', order: 3 }, // written by the grid
      { field: 'data.email', order: 4 },
      { field: 'city', order: 5 },              // appended by the backend
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.company_name', 'data.email', 'data.city',
    ]);
  });

  it('honours a saved order that moved a system lane, because the grid lets one be dragged', () => {
    // `DataTableGrid` pins only `checkbox` and the id lane; `priority` and
    // `created_at` carry a drag handle like any other column. Refusing to move
    // them would discard an arrangement the user really made and really saved.
    const columns = [col('data.city'), col('data.zip')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'data.zip', order: 2 },
      { field: 'created_at', order: 3 },
      { field: 'data.city', order: 4 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      'checkbox', 'priority', 'data.zip', 'created_at', 'data.city',
    ]);
  });

  it('leaves every column the saved order does not name exactly where it was', () => {
    // The shape a cloned table has once one column is added to it: several
    // server paths copy `column_order` as an empty array, and the append then
    // names a single field. Sending everything unnamed to the end would put the
    // selection checkbox behind every data column.
    const columns = [col('data.a'), col('data.b'), col('data.x')];
    const saved: ColumnOrder[] = [{ field: 'x', order: 0 }];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.a', 'data.b', 'data.x',
    ]);
  });

  it('renders a newly added column last, because the backend appended it last', () => {
    // `data.zip` leads the API payload only because its name is the shortest.
    const columns = [col('data.zip'), col('data.customer_name'), col('data.status')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'customer_name', order: 3 },
      { field: 'status', order: 4 },
      { field: 'zip', order: 5 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.customer_name', 'data.status', 'data.zip',
    ]);
  });

  it('gives a user column named like a lane its OWN saved position', () => {
    // `data.priority` and the `priority` lane strip to one key. Matching on the
    // stripped form alone would hand the column the lane position and throw
    // away the drag that put it last.
    const columns = [col('data.priority'), col('data.x')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.x', order: 3 },
      { field: 'data.priority', order: 4 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.x', 'data.priority',
    ]);
  });

  it('does not guess for a lane-named column when the order names only the bare spelling', () => {
    // The ordinary backend-seeded order: system names, then bare data names,
    // and a data field called `priority` gets NO entry of its own. The bare
    // `priority` entry is the lane's, so the column keeps its incoming slot
    // instead of being hoisted to the front of the data block.
    const columns = [col('data.a'), col('data.b'), col('data.priority')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'a', order: 5 },
      { field: 'b', order: 6 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.a', 'data.b', 'data.priority',
    ]);
  });

  it('does not wedge an imported id or index column between the lanes', () => {
    // A CSV import derives its columns from the DATA keys, so `id` and `index`
    // reach mapping_spec however the file spelled them: the reserved-name guard
    // only covers CRUD writes. Neither renders a lane at root (`index` has none
    // and `id` lives inside the checkbox), so nothing on screen reveals that the
    // bare `id` / `index` entries of the seed are the LANES'. Reading them as
    // this table's columns puts two data columns between checkbox and priority.
    const columns = [col('data.a'), col('data.b'), col('data.index'), col('data.id')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'a', order: 5 },
      { field: 'b', order: 6 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.a', 'data.b', 'data.index', 'data.id',
    ]);
  });

  it('keeps a column that is in the data but absent from a fully-named order where it was', () => {
    // The append runs inside a catch-all that logs and carries on, so a column
    // can exist with no entry at all while every other column has one. It must
    // not be dragged to a position by the columns around it.
    const columns = [col('data.zip'), col('data.alpha'), col('data.beta')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.beta', order: 3 },
      { field: 'data.alpha', order: 4 },
    ];

    // `zip` holds slot 0 of the data block and stays there; alpha and beta swap
    // into the two slots they held.
    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.zip', 'data.beta', 'data.alpha',
    ]);
  });

  it('keeps a renamed column in place, because the backend renames its entry too', () => {
    // `renameColumn` rewrites the entry under the new name, in whichever
    // spelling it was stored, so the column is still named and still ranked.
    const columns = [col('data.ab'), col('data.alpha'), col('data.beta')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.alpha', order: 3 },
      { field: 'data.beta', order: 4 },
      { field: 'ab', order: 5 }, // was `gamma`, appended bare, renamed in place
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.alpha', 'data.beta', 'data.ab',
    ]);
  });

  it('ranks a column called value, which the frontend seed names beside its lane', () => {
    // `initializeColumnOrder` (root) writes the nested-view lanes `array_index`
    // and `value` AND every data column's exact `data.<name>` spelling. Treating
    // those two as ambiguous would cost this column a position nothing was ever
    // unsure about, and drop it wherever its short name sorts.
    const columns = [col('data.value'), col('data.city')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'array_index', order: 3 },
      { field: 'value', order: 4 },
      { field: 'data.city', order: 5 },
      { field: 'data.value', order: 6 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.city', 'data.value',
    ]);
  });

  it('ranks a column called value that the BACKEND seed names bare', () => {
    // The backend system list does not contain `value`, so the seed does not
    // skip a data field of that name: the bare entry is unambiguously this
    // column's. Same for a column added through the UI, appended bare.
    const columns = [col('data.value'), col('data.city')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'city', order: 5 },
      { field: 'value', order: 6 },
    ];

    expect(fieldsOf(setup(columns, saved))).toEqual([
      ...LANES, 'data.city', 'data.value',
    ]);
  });

  it('leaves the incoming order alone when nothing is saved', () => {
    const columns = [col('data.zulu'), col('data.alpha')];

    expect(fieldsOf(setup(columns, []))).toEqual([...LANES, 'data.zulu', 'data.alpha']);
  });

  it('leaves the incoming order alone when every saved entry is unusable', () => {
    const columns = [col('data.zulu'), col('data.alpha')];

    const hook = setup(columns, [{ field: '' }, {}] as unknown as ColumnOrder[]);

    expect(fieldsOf(hook)).toEqual([...LANES, 'data.zulu', 'data.alpha']);
  });

  it('arranges every column in the snapshot view, which renders no lanes at all', () => {
    // A marketplace preview builds no system lanes, so the saved order it
    // carries is the only thing deciding the layout.
    // And it is the view where nothing on screen could ever reveal that a bare
    // `priority` entry is a lane's, since there are no lanes: the rule has to
    // come from the ORDER, not from what happens to be rendered.
    const columns = [col('data.c'), col('data.priority'), col('data.a')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.a', order: 3 },
      { field: 'data.c', order: 4 },
    ];

    // `a` and `c` swap into the slots they held; `data.priority` is not named by
    // that bare `priority` entry, so it does not move.
    expect(fieldsOf(setup(columns, saved, { snapshot: true })))
      .toEqual(['data.a', 'data.priority', 'data.c']);
  });

  it('applies the saved order to backend-driven workflow columns', () => {
    // A workflow order can only ever be the one `initializeColumnOrder` seeds
    // from these same columns, so it names them bare and names them all.
    const columns = [
      col('status', { renderType: 'text' } as Partial<ColumnDefinition>),
      col('label', { renderType: 'text' } as Partial<ColumnDefinition>),
      col('owner', { renderType: 'text' } as Partial<ColumnDefinition>),
    ];
    const saved: ColumnOrder[] = [
      { field: 'owner', order: 0 },
      { field: 'status', order: 1 },
      { field: 'label', order: 2 },
    ];

    expect(fieldsOf(setup(columns, saved, { workflowContext: { workflowId: 'wf-1', runId: 'run-1' } })))
      .toEqual(['owner', 'status', 'label']);
  });

  it('arranges the nested view, whose columns come from the data and carry no prefix', () => {
    // Drilling into a JSON path derives the columns from the DATA, so they are
    // bare, and the only lane is the checkbox. The seed there writes the ROOT
    // lane names ahead of them, which name nothing in this view and must
    // therefore move nothing.
    const columns = [col('title'), col('note')];
    const saved: ColumnOrder[] = [
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'array_index', order: 3 },
      { field: 'value', order: 4 },
      { field: 'note', order: 5 },
      { field: 'title', order: 6 },
    ];

    expect(fieldsOf(setup(columns, saved, { jsonPath: 'payload.items' })))
      .toEqual(['checkbox', 'note', 'title']);
  });

  // KNOWN LIMIT, deliberately not pinned as passing behaviour. The nested seed
  // writes the ROOT lane names beside bare nested keys, so a nested key called
  // exactly `priority` shares a spelling with an entry that is not about it and
  // takes its index. `SYSTEM_ORDER_FIELDS` cannot see this: it gates the bridge
  // between two spellings, and here the collision is EXACT. Closing it means
  // seeding `getFixedColumns(viewConfig)` instead of a hardcoded superset, which
  // is a change to what the nested branch writes rather than to how it is read.
  it.todo('gives a nested key named like a root lane its own position');

  it('keeps one array instance across re-renders, so downstream memos hold', () => {
    const hook = setup([col('data.a')], [{ field: 'a', order: 0 }]);
    const first = hook.result.current.getAllColumns();
    const firstUnique = hook.result.current.getUniqueColumns();

    hook.rerender();

    expect(hook.result.current.getAllColumns()).toBe(first);
    expect(hook.result.current.getUniqueColumns()).toBe(firstUnique);
  });
});
