// @vitest-environment jsdom
/**
 * Regression tests for the REAL row id in nested workflow-step navigation
 * ({@link useDataFetching}, stepAlias + jsonPath path).
 *
 * Bug: drilling into a step output from the run logs (e.g. `output.rows` of a
 * table CRUD step, or an agent step whose tool returned table rows) rebuilt
 * every nested item as `data: { ...itemData, id: rowId }`. The trailing
 * `id: rowId` OVERWROTE the row's real database id with a synthetic 1,2,3
 * counter, so the table ids the user came to read were gone.
 *
 * Fix: the synthetic id is a FALLBACK, not an override - it is spread first so
 * an item carrying its own `id` keeps it, mirroring normalizeRow's
 * "fill in when absent" rule. `array_index` stays authoritative because the
 * grid groups and keys sub-rows on it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useDataFetching } from '../useDataFetching';
import { authenticatedFetch } from '../../utils/authenticatedFetch';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const WORKFLOW_CONTEXT = {
  workflowId: 'wf-1',
  runId: 'run-1',
  stepAlias: 'table:contacts',
};

const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const setup = (jsonPath: string, showIdColumn = false) =>
  renderHook(() =>
    useDataFetching({
      dataSourceId: 0,
      jsonPath,
      workflowContext: WORKFLOW_CONTEXT,
      showIdColumn,
      addToast: vi.fn(),
      setPagination: vi.fn(),
      setColumnOrder: vi.fn(),
      snapshotData: undefined,
    })
  );

/** One step row whose `output.rows` holds real table rows (ids 4711/4712). */
const detailedWithTableRows = {
  rows: [
    {
      id: 1,
      startTime: '2026-01-01T00:00:00Z',
      output: {
        rows: [
          { id: 4711, email: 'a@example.com' },
          { id: 4712, email: 'b@example.com' },
        ],
        item_count: 2,
      },
    },
  ],
  columns: [],
};

beforeEach(() => {
  mockFetch.mockReset();
  mockFetch.mockImplementation((url: string) => {
    if (url.includes('/output/detailed')) return Promise.resolve(okJson(detailedWithTableRows));
    throw new Error(`unexpected url ${url}`);
  });
});

describe('useDataFetching nested workflow rows - real id preservation', () => {
  it('keeps the real table id of each nested row instead of a synthetic counter', async () => {
    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows.map(r => r.data.id)).toEqual([4711, 4712]);
  });

  it('still exposes the array position so grid grouping is unaffected', async () => {
    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows.map(r => r.data.array_index)).toEqual([0, 1]);
    // The wrapper id stays synthetic and unique - it is the React/selection key.
    expect(new Set(result.current.rows.map(r => r.id)).size).toBe(2);
  });

  it('array_index wins over an item field of the same name (grouping stays correct)', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(
          okJson({
            rows: [
              {
                id: 1,
                output: { rows: [{ id: 9, array_index: 999 }] },
              },
            ],
            columns: [],
          })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows[0].data.array_index).toBe(0);
    expect(result.current.rows[0].data.id).toBe(9);
  });

  it('falls back to the synthetic id when the nested item carries none', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(
          okJson({
            rows: [{ id: 1, output: { rows: [{ email: 'a@example.com' }, { email: 'b@example.com' }] } }],
            columns: [],
          })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    const ids = result.current.rows.map(r => r.data.id);
    expect(ids).toEqual([1, 2]);
    expect(new Set(ids).size).toBe(2);
  });

  it('keeps the real id when the nested path resolves to a single object', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(
          okJson({ rows: [{ id: 1, output: { row: { id: 4711, email: 'a@example.com' } } }], columns: [] })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.row');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows[0].data.id).toBe(4711);
  });

  it('keeps a falsy but PRESENT id - `0` and empty string are real values', async () => {
    // A CRUD or agent step genuinely returns `id: 0`. Treating falsy as absent overwrote it with
    // the 1..N counter AND declared it injected, so a writer would strip the key afterwards: the
    // value was destroyed, not merely hidden. Only a MISSING id gets the fallback.
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(
          okJson({ rows: [{ id: 1, output: { rows: [{ id: '', a: 1 }, { id: 0, a: 2 }] } }], columns: [] })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows.map(r => r.data.id)).toEqual(['', 0]);
    // Nothing was injected, so nothing may be stripped back out.
    expect(result.current.rows.every(r => (r._injectedDataKeys ?? []).includes('id'))).toBe(false);
  });

  it('records the injected id so a writer can take it back out', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(
          okJson({ rows: [{ id: 1, output: { rows: [{ id: 4711, a: 1 }, { a: 2 }] } }], columns: [] })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    // Real id kept -> only the array position was injected; id-less item -> the fallback too.
    expect(result.current.rows[0]._injectedDataKeys).toEqual(['array_index']);
    expect(result.current.rows[1]._injectedDataKeys).toEqual(['id', 'array_index']);
  });

  it('declares the injected id for a primitive nested value too', async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/output/detailed')) {
        return Promise.resolve(okJson({ rows: [{ id: 1, output: { note: 'hello' } }], columns: [] }));
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = setup('output.note');
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows[0].data).toMatchObject({ value: 'hello', id: 1 });
    expect(result.current.rows[0]._injectedDataKeys).toEqual(['id']);
  });

  it('declares the injected array position on the DATASOURCE nested path too', async () => {
    // The tables page expands nested items with a second normalizer. It never overwrote `id`, so it
    // had no data-loss bug, but it injected `array_index` without declaring it - and an undeclared
    // injected key is one a writer cannot take back out, so it rides into every copy of the row.
    // The backend `/items/nested` endpoint is tried first; this covers the CLIENT-SIDE fallback
    // that expands the items here, which is the normalizer that injects the position.
    mockFetch.mockImplementation((url: string) => {
      if (url.includes('/items/nested')) return Promise.resolve({ ok: false, status: 404, json: async () => ({}) });
      if (url.includes('/items')) {
        return Promise.resolve(
          okJson({ rowData: [{ id: 7, data: { payload: { items: [{ id: 4711, a: 1 }] } } }] })
        );
      }
      throw new Error(`unexpected url ${url}`);
    });

    const { result } = renderHook(() =>
      useDataFetching({
        dataSourceId: 42,
        jsonPath: 'payload.items',
        workflowContext: null,
        showIdColumn: false,
        addToast: vi.fn(),
        setPagination: vi.fn(),
        setColumnOrder: vi.fn(),
        snapshotData: undefined,
      })
    );
    await act(async () => {
      await result.current.fetchData(1, 100);
    });

    expect(result.current.rows[0].data.id).toBe(4711);
    expect(result.current.rows[0]._injectedDataKeys).toEqual(['array_index']);
  });

  it('derives an `id` column from the nested data so the grid can render it', async () => {
    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchColumns();
      await result.current.fetchData(1, 100);
    });

    expect(result.current.columns.map(c => c.field)).toContain('id');
  });

  it('does not lose an epoch-specific id column to an unfiltered schema sample', async () => {
    mockFetch.mockImplementation((url: string) => Promise.resolve(okJson(url.includes('epoch=2')
      ? detailedWithTableRows
      : { rows: [{ output: { rows: [{ oldField: true }] } }], columns: [] })));
    const { result } = setup('output.rows');
    await act(async () => {
      await result.current.fetchData(1, 20, null, null, { epoch: 2 });
      await result.current.fetchColumns();
    });
    expect(result.current.columns.map(c => c.field)).toEqual(['id', 'email']);
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(result.current.rows[0].data.id).toBe(4711);
  });

  it('keeps nested columns from older pages and adds IDs first encountered on a later page', async () => {
    mockFetch.mockResolvedValueOnce(okJson({ rows: [{ output: { rows: [{ title: 'No ID' }] } }], columns: [] }));
    const { result } = setup('output.rows');
    await act(async () => { await result.current.fetchData(1, 20); });
    await act(async () => { await result.current.fetchData(2, 20, null, null, null, true); });
    expect(result.current.columns.map(c => c.field).sort()).toEqual(['email', 'id', 'title']);
    expect(result.current.rows.map(row => row.data.id)).toEqual([1, 4711, 4712]);
  });
});
