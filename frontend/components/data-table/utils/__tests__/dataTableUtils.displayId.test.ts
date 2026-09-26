// @vitest-environment jsdom
/**
 * The run-logs table shows a row by its coordinates ("20.0.2") and a nested item as
 * `<parent id>:<position>` (`_displayId`, e.g. "21:3") while its
 * `row.id` stays a hidden React key. Every place that reports or orders a row by id must use what
 * is SHOWN: the CSV export (through displayIdOf) and the ID column sort. Before the fix the sort
 * compared the hidden key (so the order contradicted the ids on screen) and did it as text (so
 * 10 sorted before 9).
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { compareDisplayIds, displayIdOf } from '../dataTableUtils';
import { useSortingAndFiltering } from '../../hooks/useSortingAndFiltering';
import type { DataSourceItemRow } from '../../types';

const nestedRow = (key: number, displayId: string | number, data: Record<string, any> = { id: key }): DataSourceItemRow => ({
  id: key,
  data_source_id: 0,
  tenant_id: 'anonymous',
  data,
  priority: 0,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: null,
  _injectedDataKeys: ['id'],
  _displayId: displayId,
  _jsonPath: 'output.items',
  _isWorkflowStep: true,
});

describe('displayIdOf with _displayId', () => {
  it('shows _displayId over the injected React key', () => {
    expect(displayIdOf(nestedRow(200001, '1:1'))).toBe('1:1');
  });

  it("still prefers the item's own id", () => {
    const row = { ...nestedRow(3, '21:1', { id: 4711 }), _injectedDataKeys: [] };
    expect(displayIdOf(row)).toBe(4711);
  });

  it('falls back to row.id when nothing else is set', () => {
    const { _displayId, ...row } = nestedRow(7, 'unused');
    expect(displayIdOf(row as DataSourceItemRow)).toBe(7);
  });
});

describe('compareDisplayIds', () => {
  it('orders numbers numerically', () => {
    expect([10, 9, 100].sort(compareDisplayIds)).toEqual([9, 10, 100]);
  });

  it('orders coordinate ids segment by segment, numerically', () => {
    expect(['21.10', '21.9', '9', '21', '20.0.2', '20.1'].sort(compareDisplayIds))
      .toEqual(['9', '20.0.2', '20.1', '21', '21.9', '21.10']);
  });

  it('orders nested items after their parent coordinates, numerically by position', () => {
    expect(['21:10', '21:9', '20.0.2:1', '21'].sort(compareDisplayIds)).toEqual(['20.0.2:1', '21', '21:9', '21:10']);
  });

  it('treats a non-finite segment as text, never returning NaN', () => {
    expect(compareDisplayIds('Infinity', '5')).not.toBeNaN();
  });

  it('puts a bare parent id before its items', () => {
    expect(['21:1', '21'].sort(compareDisplayIds)).toEqual(['21', '21:1']);
  });

  it('falls back to text order for non-numeric ids', () => {
    expect(['CUST-2', 'CUST-10', 'ABC'].sort(compareDisplayIds)).toEqual(['ABC', 'CUST-10', 'CUST-2']);
  });
});

describe('ID column sort', () => {
  it('sorts by the SHOWN id, not the hidden React key', () => {
    // Keys ascend in the opposite order of the shown ids (newest execution fetched first).
    const rows = [nestedRow(1, '21:1'), nestedRow(2, '9:1'), nestedRow(100001, '21:10'), nestedRow(100002, '21:9')];
    const { result } = renderHook(() =>
      useSortingAndFiltering({
        rows,
        fetchData: vi.fn(async () => {}),
        pagination: { currentPage: 1, pageSize: 20, totalItems: 4, totalPages: 1, nextCursor: null, hasMore: false },
      })
    );

    act(() => result.current.setSortConfig({ key: 'id', direction: 'asc' }));
    expect(result.current.sortData(rows).map(displayIdOf)).toEqual(['9:1', '21:1', '21:9', '21:10']);

    act(() => result.current.setSortConfig({ key: 'id', direction: 'desc' }));
    expect(result.current.sortData(rows).map(displayIdOf)).toEqual(['21:10', '21:9', '21:1', '9:1']);
  });
});
