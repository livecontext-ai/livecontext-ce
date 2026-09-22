// @vitest-environment jsdom
/**
 * The saved column order has to survive the trip from the server to the grid,
 * and back out again when the user drags a column. This is the only test that
 * watches the whole trip.
 *
 * `useDataFetching` reads `column_order` off the data-source payload, and
 * `useColumnManagement` owns the state the grid renders from. Between the two
 * there is only the controller. When `useDataFetching` kept a PRIVATE copy of
 * that state, every part still looked healthy in isolation: the fetch hook
 * stored the order it read, the column hook sorted correctly by the order it
 * was given, and the PUT that saves a drag persisted the right array. Nothing
 * connected them, so a reordered table came back in its old order after a
 * reload and a freshly added column showed up wherever its name happened to
 * sort. A test on either hook alone exercises a pass-through and can see none
 * of that, so this one composes the real hooks through the real controller.
 *
 * The drag cases matter for a second reason: what the grid SHOWS after a drop
 * and what it PUTS must be the same list. A disagreement there is the same
 * class of bug as the one this branch fixes, wearing a success toast.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';

vi.mock('../utils/authenticatedFetch', () => ({ authenticatedFetch: vi.fn() }));
vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isLoading: false, isAuthenticated: true, isReady: true }),
}));
vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));

import { useDataTableController } from '../useDataTableController';
import { authenticatedFetch } from '../utils/authenticatedFetch';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const DATA_SOURCE_ID = 42;
const LIST_URL = '/api/proxy/data-sources';
const ORDER_URL = `/api/proxy/data-sources/${DATA_SOURCE_ID}/column-order`;
const LANES = ['checkbox', 'priority', 'created_at'];

const textSpec = (key: string) => ({ path: `data.${key}`, type: 'text', structure: 'scalar' });

/**
 * The columns as the API hands them over. `mapping_spec` is a JSONB column, so
 * Postgres returns its keys in ITS order, by name length then bytes, never in
 * the order the columns were created or arranged. `zip` leads here only because
 * it is the shortest name: that is the default the grid falls back to when the
 * saved order is lost, and it is the bug the user sees.
 */
const MAPPING_SPEC = {
  zip: textSpec('zip'),
  city: textSpec('city'),
  email: textSpec('email'),
  company_name: textSpec('company_name'),
};
const PAYLOAD_ORDER = ['data.zip', 'data.city', 'data.email', 'data.company_name'];

/** A grid-written order: every column named, with the `data.` prefix. */
const FULL_SAVED_ORDER = [
  { field: 'checkbox', order: 0 },
  { field: 'priority', order: 1 },
  { field: 'created_at', order: 2 },
  { field: 'data.company_name', order: 3 },
  { field: 'data.email', order: 4 },
  { field: 'data.city', order: 5 },
  { field: 'data.zip', order: 6 },
];
const FULL_SAVED_FIELDS = [...LANES, 'data.company_name', 'data.email', 'data.city', 'data.zip'];

const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const serve = (columnOrder: unknown[], mappingSpec: unknown = MAPPING_SPEC) => {
  mockFetch.mockImplementation((url: string) => {
    if (url === LIST_URL) {
      return Promise.resolve(okJson([
        { id: DATA_SOURCE_ID, mapping_spec: mappingSpec, column_order: columnOrder },
      ]));
    }
    // Rows and the column-order PUT are irrelevant to the ordering assertions;
    // keep every other call successful.
    return Promise.resolve(okJson({ rowData: [], totalItems: 0, totalPages: 0 }));
  });
};

const renderTable = () =>
  renderHook(() => useDataTableController({ dataSourceId: DATA_SOURCE_ID }));

type Controller = { getAllColumns: () => { field: string }[]; handleDrop: (e: React.DragEvent, target: string) => Promise<void> };

/** Wait for the grid to settle on a given layout. */
const expectColumns = async (result: { current: Controller }, fields: string[]) => {
  await waitFor(() => expect(result.current.getAllColumns().map(c => c.field)).toEqual(fields));
};

/**
 * A drop event carrying `dragged`, landing on the RIGHT half of the target
 * header, which `useDragAndDrop` reads as "after".
 */
const dropAfter = (dragged: string) => ({
  preventDefault: () => {},
  dataTransfer: { getData: () => dragged },
  currentTarget: { getBoundingClientRect: () => ({ left: 0, width: 100 }) },
  clientX: 90,
}) as unknown as React.DragEvent;

/** The fields of the last order the grid persisted. */
const persistedFields = () => {
  const call = mockFetch.mock.calls.filter(([url]: [string]) => url === ORDER_URL).pop();
  expect(call, 'the drag must PUT the new order').toBeTruthy();
  expect(call![1].method).toBe('PUT');
  return JSON.parse(call![1].body).map((entry: { field: string }) => entry.field);
};

beforeEach(() => {
  mockFetch.mockReset();
});

describe('useDataTableController - saved column order reaches the grid', () => {
  it('renders the columns in the saved order instead of the API payload order', async () => {
    serve(FULL_SAVED_ORDER);

    const { result } = renderTable();

    await expectColumns(result, FULL_SAVED_FIELDS);
  });

  it('honours the bare spelling the backend writes, on a table nobody ever dragged', async () => {
    // Nothing here was written by the grid: the seed lists the data keys bare,
    // and `zip` was appended bare when the column was created. Comparing the
    // entries verbatim matches no column at all, and the grid falls back to the
    // payload order - which puts the NEW column first, because its name is the
    // shortest. That is symptom (2), and this fixture is the shape that causes
    // it in production.
    serve([
      { field: 'checkbox', order: 0 },
      { field: 'index', order: 1 },
      { field: 'id', order: 2 },
      { field: 'priority', order: 3 },
      { field: 'created_at', order: 4 },
      { field: 'email', order: 5 },
      { field: 'city', order: 6 },
      { field: 'company_name', order: 7 },
      { field: 'zip', order: 8 },
    ]);

    const { result } = renderTable();

    await expectColumns(result, [...LANES, 'data.email', 'data.city', 'data.company_name', 'data.zip']);
  });

  it('leaves a column the saved order does not name where the payload put it', async () => {
    // `zip` is absent from the order, and it sits third in the payload. It stays
    // third: only the named columns are rearranged, among the slots they held.
    serve([
      { field: 'checkbox', order: 0 },
      { field: 'priority', order: 1 },
      { field: 'created_at', order: 2 },
      { field: 'data.company_name', order: 3 },
      { field: 'data.email', order: 4 },
      { field: 'data.city', order: 5 },
    ]);

    const { result } = renderTable();

    await expectColumns(result, [
      ...LANES, 'data.zip', 'data.company_name', 'data.email', 'data.city',
    ]);
  });

  it('leaves the lanes alone when the saved order names only data columns', async () => {
    // Five server paths default `column_order` to an empty array, and one
    // appended column then makes it data-only. Ranking the lanes against an
    // order that never mentions them would bury the selection checkbox.
    serve([{ field: 'email', order: 0 }]);

    const { result } = renderTable();

    await expectColumns(result, [...LANES, ...PAYLOAD_ORDER]);
  });

  it('does not wedge an imported id or index column between the lanes', async () => {
    // A CSV import derives columns from the DATA keys and the reserved-name
    // guard only covers CRUD writes, so `id` and `index` reach mapping_spec.
    // Neither renders a lane at root, so nothing on screen says that the bare
    // `id` / `index` entries of the backend seed belong to the lanes.
    serve(
      [
        { field: 'checkbox', order: 0 },
        { field: 'index', order: 1 },
        { field: 'id', order: 2 },
        { field: 'priority', order: 3 },
        { field: 'created_at', order: 4 },
        { field: 'a', order: 5 },
      ],
      { a: textSpec('a'), id: textSpec('id'), index: textSpec('index') },
    );

    const { result } = renderTable();

    await expectColumns(result, [...LANES, 'data.a', 'data.id', 'data.index']);
  });

  it('falls back to the payload order when the table has no saved order', async () => {
    serve([]);

    const { result } = renderTable();

    await expectColumns(result, [...LANES, ...PAYLOAD_ORDER]);
  });
});

describe('useDataTableController - a drag persists and shows the same order', () => {
  it('saves the dragged order and renders it, without waiting for a reload', async () => {
    serve(FULL_SAVED_ORDER);

    const { result } = renderTable();
    await expectColumns(result, FULL_SAVED_FIELDS);

    // Drag `zip` and drop it on the right half of `email`.
    await act(async () => {
      await result.current.handleDrop(dropAfter('data.zip'), 'data.email');
    });

    const expected = [...LANES, 'data.company_name', 'data.email', 'data.zip', 'data.city'];
    expect(result.current.getAllColumns().map(c => c.field)).toEqual(expected);
    expect(persistedFields()).toEqual(expected);
  });

  it('keeps a moved system lane where the user dropped it', async () => {
    // `DataTableGrid` pins only `checkbox` and the id lane. `priority` and
    // `created_at` carry a drag handle, so a user can move them, and the PUT
    // that records it must not be contradicted by the very next render.
    serve(FULL_SAVED_ORDER);

    const { result } = renderTable();
    await expectColumns(result, FULL_SAVED_FIELDS);

    await act(async () => {
      await result.current.handleDrop(dropAfter('created_at'), 'data.city');
    });

    const expected = [
      'checkbox', 'priority', 'data.company_name', 'data.email', 'data.city', 'created_at', 'data.zip',
    ];
    expect(result.current.getAllColumns().map(c => c.field)).toEqual(expected);
    expect(persistedFields()).toEqual(expected);
  });

  it('keeps a user column named like a lane where the user dropped it', async () => {
    // `data.priority` and the `priority` lane collapse onto one key once the
    // `data.` prefix is stripped. If the lane's position won, the grid would
    // snap the column back in the very render that follows the drag, while the
    // PUT that just went out says otherwise.
    serve(
      [
        { field: 'checkbox', order: 0 },
        { field: 'priority', order: 1 },
        { field: 'created_at', order: 2 },
        { field: 'data.priority', order: 3 },
        { field: 'data.zip', order: 4 },
      ],
      { priority: textSpec('priority'), zip: textSpec('zip') },
    );

    const { result } = renderTable();
    await expectColumns(result, [...LANES, 'data.priority', 'data.zip']);

    await act(async () => {
      await result.current.handleDrop(dropAfter('data.priority'), 'data.zip');
    });

    const expected = [...LANES, 'data.zip', 'data.priority'];
    expect(result.current.getAllColumns().map(c => c.field)).toEqual(expected);
    expect(persistedFields()).toEqual(expected);
  });

  it('does not hand a lane-named column the lane position when only the bare name is saved', async () => {
    // The ordinary backend-seeded order. `priority` there is the LANE, and
    // nothing in the array speaks about the user column of the same name, so
    // guessing would hoist it above `created_at`.
    serve(
      [
        { field: 'checkbox', order: 0 },
        { field: 'index', order: 1 },
        { field: 'id', order: 2 },
        { field: 'priority', order: 3 },
        { field: 'created_at', order: 4 },
        { field: 'zip', order: 5 },
      ],
      { priority: textSpec('priority'), zip: textSpec('zip') },
    );

    const { result } = renderTable();

    await expectColumns(result, [...LANES, 'data.priority', 'data.zip']);
  });
});
