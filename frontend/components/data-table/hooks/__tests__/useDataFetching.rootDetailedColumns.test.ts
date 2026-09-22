// @vitest-environment jsdom
/**
 * Regression tests for where the workflow ROOT view gets its columns in
 * {@link useDataFetching}, and how many times it asks for them.
 *
 * Bug: the alias path called that endpoint TWICE on every mount - once from
 * fetchColumns with `limit=1` for the column definitions, once from fetchData
 * with the page for the rows - although a single response carries both
 * (the endpoint is documented in the code as "columns + rows from same source").
 * The controller fires the two in parallel on mount, so opening a step's Logs
 * paid two round trips for one payload; on a remote backend that doubled the
 * latency of the whole surface.
 *
 * The fix: at root, fetchColumns does not call at all and the row response
 * publishes the columns. Nested navigation is deliberately untouched - there the
 * columns are INFERRED from the data, reading up to 100 rows to collect keys that
 * a 20-row page would miss.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useDataFetching } from '../useDataFetching';
import { authenticatedFetch } from '../../utils/authenticatedFetch';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const WORKFLOW_CONTEXT = { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'table:read_rows' };

const DETAILED_BODY = {
  columns: [
    { field: 'id', header: 'ID', type: 'NUMBER', sortable: true, filterable: false, renderType: 'TEXT_PREVIEW' },
    { field: 'output', header: 'Output', type: 'JSON', sortable: false, filterable: false, renderType: 'JSON_NAVIGABLE' },
  ],
  nodeType: 'TABLE',
  rows: [{ id: 1, output: { items: [{ id: 64, title: 'Ada' }] }, startTime: '2026-01-01T00:00:00Z' }],
  pagination: { totalRows: 1, hasMore: false },
};

const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const detailedCalls = () =>
  mockFetch.mock.calls.map((c) => String(c[0])).filter((u) => u.includes('/output/detailed'));

const setup = (jsonPath: string | undefined) =>
  renderHook(() =>
    useDataFetching({
      dataSourceId: 0,
      jsonPath,
      workflowContext: WORKFLOW_CONTEXT,
      showIdColumn: true,
      addToast: vi.fn(),
      setPagination: vi.fn(),
      setColumnOrder: vi.fn(),
      snapshotData: undefined,
    })
  );

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockImplementation(async () => okJson(DETAILED_BODY));
});

describe('useDataFetching - workflow alias root view', () => {
  it('asks the detailed endpoint ONCE when both columns and rows are wanted', async () => {
    const { result } = setup('');

    await act(async () => {
      // The two calls the controller makes on mount, in parallel.
      await Promise.all([result.current.fetchColumns(), result.current.fetchData(1, 20)]);
    });

    expect(detailedCalls()).toHaveLength(1);
    expect(detailedCalls()[0]).toContain('limit=20');
  });

  it('publishes the columns carried by that single response', async () => {
    const { result } = setup('');

    await act(async () => {
      await Promise.all([result.current.fetchColumns(), result.current.fetchData(1, 20)]);
    });

    // Columns come from the ROW response now, so they must still be there - a grid
    // with rows and no columns is the way this optimisation could break.
    expect(result.current.columns.map((c) => c.field)).toEqual(['id', 'output']);
    expect(result.current.backendColumns).toHaveLength(2);
    expect(result.current.nodeType).toBe('TABLE');
    expect(result.current.rows).toHaveLength(1);
  });

  it('KEEPS the columns of the rows already on screen when scrolling loads more', async () => {
    // The backend derives these columns from the rows of THAT page (a field earns a
    // column when some row has a non-null value), so page 2 can legitimately declare
    // fewer than page 1 - while page 1's rows are still displayed. Replacing took the
    // errorMessage column away from a failed row the user was looking at.
    const page1 = {
      ...DETAILED_BODY,
      columns: [...DETAILED_BODY.columns, { field: 'errorMessage', header: 'Error', type: 'TEXT', sortable: false, filterable: false, renderType: 'TEXT_PREVIEW' }],
      rows: [{ id: 1, output: {}, errorMessage: 'boom', startTime: '2026-01-01T00:00:00Z' }],
      pagination: { totalRows: 2, hasMore: true },
    };
    const page2 = { ...DETAILED_BODY, rows: [{ id: 2, output: {}, startTime: '2026-01-01T00:00:01Z' }] };

    mockFetch.mockImplementation(async (url: string) => okJson(String(url).includes('page=2') ? page2 : page1));
    const { result } = setup('');

    await act(async () => { await result.current.fetchData(1, 20); });
    expect(result.current.columns.map((c) => c.field)).toContain('errorMessage');

    // The infinite-scroll append: same call with append=true.
    await act(async () => { await result.current.fetchData(2, 20, null, null, null, true); });

    expect(result.current.rows).toHaveLength(2);
    expect(result.current.columns.map((c) => c.field)).toEqual(['id', 'output', 'errorMessage']);
  });

  it('puts a column an appended page introduces where the backend orders it', async () => {
    // Both pages are subsequences of ONE canonical order (the backend's FIELD_ORDER),
    // so a column missing from page 1 belongs where that order puts it, not at the far
    // right. `errorMessage` sits next to `output`; a user reading a failed row should
    // not have to scroll past every other column to find why it failed.
    const page1 = { ...DETAILED_BODY, pagination: { totalRows: 2, hasMore: true } };
    const page2 = {
      ...DETAILED_BODY,
      columns: [
        DETAILED_BODY.columns[0],
        { field: 'errorMessage', header: 'Error', type: 'TEXT', sortable: false, filterable: false, renderType: 'TEXT_PREVIEW' },
        DETAILED_BODY.columns[1],
      ],
      rows: [{ id: 2, output: {}, errorMessage: 'boom', startTime: '2026-01-01T00:00:01Z' }],
    };

    mockFetch.mockImplementation(async (url: string) => okJson(String(url).includes('page=2') ? page2 : page1));
    const { result } = setup(undefined);

    await act(async () => { await result.current.fetchData(1, 20); });
    await act(async () => { await result.current.fetchData(2, 20, null, null, null, true); });

    expect(result.current.columns.map((c) => c.field)).toEqual(['id', 'errorMessage', 'output']);
  });

  it('resolves the node type from an appended page when the first had none', async () => {
    const emptyFirst = { columns: [], nodeType: null, rows: [], pagination: { totalRows: 1, hasMore: true } };
    mockFetch.mockImplementation(async (url: string) => okJson(String(url).includes('page=2') ? DETAILED_BODY : emptyFirst));
    const { result } = setup(undefined);

    await act(async () => { await result.current.fetchData(1, 20); });
    expect(result.current.columns).toHaveLength(0);

    await act(async () => { await result.current.fetchData(2, 20, null, null, null, true); });

    expect(result.current.columns.map((c) => c.field)).toEqual(['id', 'output']);
    expect(result.current.nodeType).toBe('TABLE');
  });

  it('lets a server filter reshape the columns, because they describe the rows shown', async () => {
    // Deliberate and worth pinning: the columns now come from the page the filter
    // returned, so filtering out every failed step also drops the errorMessage column
    // - and clearing the filter brings it back. A filter refetches page 1 (not an
    // append), so this is a replace, never a merge.
    const unfiltered = {
      ...DETAILED_BODY,
      columns: [...DETAILED_BODY.columns, { field: 'errorMessage', header: 'Error', type: 'TEXT', sortable: false, filterable: false, renderType: 'TEXT_PREVIEW' }],
      rows: [{ id: 1, output: {}, errorMessage: 'boom', startTime: '2026-01-01T00:00:00Z' }],
    };
    mockFetch.mockImplementation(async (url: string) => okJson(String(url).includes('status=') ? DETAILED_BODY : unfiltered));
    const { result } = setup(undefined);

    await act(async () => { await result.current.fetchData(1, 20); });
    expect(result.current.columns.map((c) => c.field)).toContain('errorMessage');

    await act(async () => { await result.current.fetchData(1, 20, null, null, { status: 'COMPLETED', epoch: null }); });
    expect(result.current.columns.map((c) => c.field)).toEqual(['id', 'output']);

    await act(async () => { await result.current.fetchData(1, 20, null, null, null); });
    expect(result.current.columns.map((c) => c.field)).toContain('errorMessage');
  });

  it('derives nested columns from the displayed filtered page without an independent sample', async () => {
    const { result } = setup('output.items');

    await act(async () => {
      await result.current.fetchColumns();
      await result.current.fetchData(1, 20, null, null, { epoch: 2 });
    });

    const calls = detailedCalls();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toContain('limit=20');
    expect(calls[0]).toContain('epoch=2');
  });
});
