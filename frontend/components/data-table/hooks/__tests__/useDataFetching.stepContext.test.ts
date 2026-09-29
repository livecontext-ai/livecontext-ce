// @vitest-environment jsdom
/**
 * Regression tests for the run-logs table while drilling into a step output
 * ({@link useDataFetching}, stepAlias + jsonPath path).
 *
 * Bug 1: a nested item without an `id` of its own SHOWED its synthetic React key. That key restarts
 * at 1 on every fetch and jumps to `page * 100000 + 1` on an appended page, so the oldest execution
 * (epoch 1, reached by scrolling) read "200001" while the newest read "1".
 * Bug 2: nested rows dropped the parent step's epoch / spawn / iteration, so a drilled-in value
 * could not be traced back to the execution that produced it.
 *
 * Fix: every nested row carries the parent's coordinates as `@epoch` / `@spawn` / `@iteration`
 * columns (first), and shows `<parent id>:<index>` (0-based, like the coordinates) - the parent's
 * id being its coordinates
 * ("21", "20.0.2"), which no page, filter or purge can change.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../../utils/authenticatedFetch', () => ({
  authenticatedFetch: vi.fn(),
}));

import { useDataFetching } from '../useDataFetching';
import { authenticatedFetch } from '../../utils/authenticatedFetch';
import { displayIdOf } from '../../utils/dataTableUtils';

const mockFetch = authenticatedFetch as unknown as ReturnType<typeof vi.fn>;

const okJson = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

const setup = (jsonPath: string) =>
  renderHook(() =>
    useDataFetching({
      dataSourceId: 0,
      jsonPath,
      workflowContext: { workflowId: 'wf-1', runId: 'run-1', stepAlias: 'mcp:search' },
      showIdColumn: true,
      addToast: vi.fn(),
      setPagination: vi.fn(),
      setColumnOrder: vi.fn(),
      snapshotData: undefined,
    })
  );

/** Step rows as the backend sends them: `id` = coordinates, `_rowId` = technical key. */
const stepRow = (execution: number, epoch: number, items: unknown, spawn = 0, iteration = 0) => ({
  id: String(execution),
  _rowId: 9000 + execution,
  epoch,
  spawn,
  iteration,
  startTime: '2026-01-01T00:00:00Z',
  output: { items },
});

const respondWith = (...bodies: unknown[]) => {
  bodies.forEach(body => mockFetch.mockResolvedValueOnce(okJson(body)));
};

beforeEach(() => {
  mockFetch.mockReset();
});

describe('useDataFetching nested workflow rows - execution context', () => {
  it('carries the parent epoch / spawn / iteration onto every nested item', async () => {
    respondWith({ rows: [stepRow(21, 21, [{ title: 'a' }, { title: 'b' }], 2, 3)], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    expect(result.current.rows.map(r => [r.data['@epoch'], r.data['@spawn'], r.data['@iteration']]))
      .toEqual([[21, 2, 3], [21, 2, 3]]);
  });

  it('puts the context columns after the item fields, with the root-level headers', async () => {
    respondWith({ rows: [stepRow(1, 1, [{ title: 'a' }])], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    // The item's data first (the row id already reads as its coordinates), the coordinates last.
    expect(result.current.columns[0].field).toBe('title');
    expect(result.current.columns.slice(-3).map(c => [c.field, c.header_name]))
      .toEqual([['@epoch', 'Epoch'], ['@spawn', 'Spawn'], ['@iteration', 'Iteration']]);
  });

  it('an item key named like a context column yields ONE column, not a duplicate', async () => {
    respondWith({ rows: [stepRow(4, 4, [{ '@epoch': 'mine', title: 'a' }])], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    const fields = result.current.columns.map(c => c.field);
    expect(fields.filter(f => f === '@epoch')).toHaveLength(1);
    expect(result.current.rows[0].data['@epoch']).toBe(4);
  });

  it('the context wins over an item key of the same name on a single-object field too', async () => {
    respondWith({ rows: [{ ...stepRow(6, 6, null), output: { row: { '@epoch': 'mine', title: 'a' } } }], columns: [] });
    const { result } = setup('output.row');
    await act(async () => { await result.current.fetchData(1, 20); });

    expect(result.current.rows[0].data['@epoch']).toBe(6);
    expect(result.current.columns.map(c => c.field).filter(f => f === '@epoch')).toHaveLength(1);
  });

  it("never overwrites an item's own `epoch` field", async () => {
    respondWith({ rows: [stepRow(4, 4, [{ epoch: 1700000000 }])], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    expect(result.current.rows[0].data.epoch).toBe(1700000000);
    expect(result.current.rows[0].data['@epoch']).toBe(4);
  });

  it('shows `<parent id>:<index>` (0-based) for an id-less array item, not the synthetic key', async () => {
    respondWith({ rows: [stepRow(21, 21, [{ title: 'a' }, { title: 'b' }])], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    expect(result.current.rows.map(displayIdOf)).toEqual(['21:0', '21:1']);
    // Same base as the element's array position: "21:0" is array_index 0.
    expect(result.current.rows.map(r => r.data.array_index)).toEqual([0, 1]);
  });

  it('an appended page keeps coordinate-based ids (was page * 100000 + 1)', async () => {
    respondWith(
      { rows: [stepRow(2, 2, [{ title: 'newest' }])], columns: [] },
      { rows: [stepRow(1, 1, [{ title: 'oldest' }])], columns: [] },
    );
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 1); });
    await act(async () => { await result.current.fetchData(2, 1, null, null, null, true); });

    expect(result.current.rows.map(displayIdOf)).toEqual(['2:0', '1:0']);
    // The React / selection keys stay unique across the appended page.
    expect(new Set(result.current.rows.map(r => r.id)).size).toBe(2);
  });

  it("still shows an item's own id when it has one", async () => {
    respondWith({ rows: [stepRow(5, 5, [{ id: 4711, title: 'a' }])], columns: [] });
    const { result } = setup('output.items');
    await act(async () => { await result.current.fetchData(1, 20); });

    expect(displayIdOf(result.current.rows[0])).toBe(4711);
  });

  it('shows the bare parent id for an object or a primitive nested value', async () => {
    respondWith(
      { rows: [{ ...stepRow(7, 7, null), output: { row: { title: 'a' } } }], columns: [] },
      { rows: [{ ...stepRow(8, 8, null), output: { note: 'hello' } }], columns: [] },
    );
    const objectView = setup('output.row');
    await act(async () => { await objectView.result.current.fetchData(1, 20); });
    const primitiveView = setup('output.note');
    await act(async () => { await primitiveView.result.current.fetchData(1, 20); });

    expect(displayIdOf(objectView.result.current.rows[0])).toBe('7');
    expect(objectView.result.current.rows[0].data['@epoch']).toBe(7);
    expect(displayIdOf(primitiveView.result.current.rows[0])).toBe('8');
    expect(primitiveView.result.current.rows[0].data['@epoch']).toBe(8);
  });

  it('root rows SHOW their coordinates and key on the technical row id', async () => {
    respondWith({
      rows: [
        { id: '20.0.2', _rowId: 555, epoch: 20, spawn: 0, iteration: 2 },
        { id: '20.0.2', _rowId: 554, epoch: 20, spawn: 0, iteration: 2, status: 'waiting' },
      ],
      columns: [],
    });
    const { result } = setup('');
    await act(async () => { await result.current.fetchData(1, 20); });

    // Two rows at the same coordinates (a wait, then its completion) show the same id and
    // still have distinct keys.
    expect(result.current.rows.map(displayIdOf)).toEqual(['20.0.2', '20.0.2']);
    expect(result.current.rows.map(r => r.id)).toEqual([555, 554]);
  });

  it('a root row without its technical key still gets a key no appended page can repeat', async () => {
    respondWith(
      { rows: [{ id: '2', epoch: 2 }, { id: '1', epoch: 1 }], columns: [] },
      { rows: [{ id: '0', epoch: 0 }], columns: [] },
    );
    const { result } = setup('');
    await act(async () => { await result.current.fetchData(1, 2); });
    await act(async () => { await result.current.fetchData(2, 2, null, null, null, true); });

    // Position over ALL pages (1, 2 | 3), never the page-local index (1, 2 | 1).
    expect(result.current.rows.map(r => r.id)).toEqual([1, 2, 3]);
  });
  it('a row an appended page repeats (new rows pushed it down) is shown once, not twice', async () => {
    // Page 1 = executions 3, 2. Execution 4 arrives, so offset page 2 = executions 2, 1.
    respondWith(
      { rows: [stepRow(3, 3, [{ title: 'c' }]), stepRow(2, 2, [{ title: 'b' }])], columns: [] },
      { rows: [stepRow(2, 2, [{ title: 'b' }]), stepRow(1, 1, [{ title: 'a' }])], columns: [] },
      { rows: [stepRow(3, 3, null), stepRow(2, 2, null)].map(r => ({ ...r, output: undefined })), columns: [] },
      { rows: [stepRow(2, 2, null), stepRow(1, 1, null)].map(r => ({ ...r, output: undefined })), columns: [] },
    );
    const nested = setup('output.items');
    await act(async () => { await nested.result.current.fetchData(1, 2); });
    await act(async () => { await nested.result.current.fetchData(2, 2, null, null, null, true); });
    expect(nested.result.current.rows.map(displayIdOf)).toEqual(['3:0', '2:0', '1:0']);

    const root = setup('');
    await act(async () => { await root.result.current.fetchData(1, 2); });
    await act(async () => { await root.result.current.fetchData(2, 2, null, null, null, true); });
    expect(root.result.current.rows.map(r => r.id)).toEqual([9003, 9002, 9001]);
  });
});
