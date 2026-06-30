import { describe, it, expect } from 'vitest';
import { snapshotToDataTable } from '../snapshot-adapter';
import type { DataSourceSnapshot } from '@/contexts/PublicationSnapshotContext';

describe('snapshotToDataTable', () => {
  it('returns empty rows/columns when snapshot is null or undefined', () => {
    expect(snapshotToDataTable(null)).toEqual({ rows: [], columns: [] });
    expect(snapshotToDataTable(undefined)).toEqual({ rows: [], columns: [] });
  });

  it('returns empty rows/columns when snapshot.items is missing', () => {
    const snap: DataSourceSnapshot = { name: 'Empty' };
    const result = snapshotToDataTable(snap);
    expect(result.rows).toEqual([]);
    expect(result.columns).toEqual([]);
  });

  it('maps items to rows with synthesized id, tenant, data_source_id', () => {
    const snap: DataSourceSnapshot = {
      items: [
        { data: { foo: 'a' }, priority: 1 },
        { data: { foo: 'b' }, priority: 2 },
      ],
    };
    const result = snapshotToDataTable(snap);
    expect(result.rows).toHaveLength(2);
    expect(result.rows[0]).toMatchObject({
      id: 1,
      data_source_id: 0,
      tenant_id: 'snapshot',
      data: { foo: 'a' },
      priority: 1,
      updated_at: null,
    });
    expect(result.rows[1].id).toBe(2);
    expect(typeof result.rows[0].created_at).toBe('string');
  });

  it('sorts items by priority ascending', () => {
    const snap: DataSourceSnapshot = {
      items: [
        { data: { k: 'c' }, priority: 3 },
        { data: { k: 'a' }, priority: 1 },
        { data: { k: 'b' }, priority: 2 },
      ],
    };
    const result = snapshotToDataTable(snap);
    expect(result.rows.map(r => r.data)).toEqual([
      { k: 'a' },
      { k: 'b' },
      { k: 'c' },
    ]);
  });

  it('defends against non-object data payloads', () => {
    const snap: DataSourceSnapshot = {
      items: [
        { data: null as unknown as Record<string, unknown>, priority: 0 },
        { data: 'string' as unknown as Record<string, unknown>, priority: 0 },
      ],
    };
    const result = snapshotToDataTable(snap);
    expect(result.rows).toHaveLength(2);
    expect(result.rows[0].data).toEqual({});
    expect(result.rows[1].data).toEqual({});
  });

  it('infers columns from data keys when mappingSpec is absent', () => {
    const snap: DataSourceSnapshot = {
      items: [
        { data: { name: 'Alice', age: 30, active: true }, priority: 0 },
        { data: { name: 'Bob', age: 25, active: false, extra: 'x' }, priority: 0 },
      ],
    };
    const result = snapshotToDataTable(snap);
    const fields = result.columns.map(c => c.field).sort();
    expect(fields).toEqual(['data.active', 'data.age', 'data.extra', 'data.name']);
    const byField = Object.fromEntries(result.columns.map(c => [c.field, c.type]));
    expect(byField['data.name']).toBe('text');
    expect(byField['data.age']).toBe('number');
    expect(byField['data.active']).toBe('boolean');
  });

  it('ignores keys starting with _ in fallback inference', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { visible: 'x', _internal: 'y' }, priority: 0 }],
    };
    const result = snapshotToDataTable(snap);
    expect(result.columns.map(c => c.field)).toEqual(['data.visible']);
  });

  it('uses mappingSpec pipeline when provided', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { title: 'Hello' }, priority: 0 }],
      mappingSpec: {
        // `path` must be present for normalizeMappingSpec to keep `display`
        title: { path: 'data.title', type: 'text', display: { label: 'Title' } },
      },
    };
    const result = snapshotToDataTable(snap);
    const titleCol = result.columns.find(c => c.field === 'data.title');
    expect(titleCol).toBeDefined();
    expect(titleCol?.header_name).toBe('Title');
    expect(titleCol?.editable).toBe(false);
  });

  it('normalizes columnOrder from raw string[] shape', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { a: 1, b: 2 }, priority: 0 }],
      columnOrder: ['a', 'b'] as unknown as DataSourceSnapshot['columnOrder'],
    };
    const result = snapshotToDataTable(snap);
    expect(result.columnOrder).toEqual([
      { field: 'a', order: 0 },
      { field: 'b', order: 1 },
    ]);
  });

  it('normalizes columnOrder from {field, order}[] shape', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { a: 1, b: 2 }, priority: 0 }],
      columnOrder: [
        { field: 'b', order: 0 },
        { field: 'a', order: 1 },
      ] as unknown as DataSourceSnapshot['columnOrder'],
    };
    const result = snapshotToDataTable(snap);
    expect(result.columnOrder).toEqual([
      { field: 'b', order: 0 },
      { field: 'a', order: 1 },
    ]);
  });

  it('drops invalid columnOrder entries and falls back to column-index order when empty', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { a: 1 }, priority: 0 }],
      columnOrder: [null, 42, { notField: 'x' }] as unknown as DataSourceSnapshot['columnOrder'],
    };
    const result = snapshotToDataTable(snap);
    // All entries are invalid → fall back to column-index order.
    expect(result.columnOrder).toEqual([{ field: 'data.a', order: 0 }]);
  });

  it('falls back to column-index order when columnOrder is missing', () => {
    const snap: DataSourceSnapshot = {
      items: [{ data: { x: 1, y: 2 }, priority: 0 }],
    };
    const result = snapshotToDataTable(snap);
    expect(result.columnOrder).toHaveLength(result.columns.length);
    expect(result.columnOrder?.map(o => o.field).sort()).toEqual(
      result.columns.map(c => c.field).sort()
    );
  });

  it('produces unique IDs for React keys', () => {
    const snap: DataSourceSnapshot = {
      items: [
        { data: { k: 'a' }, priority: 0 },
        { data: { k: 'b' }, priority: 0 },
        { data: { k: 'c' }, priority: 0 },
      ],
    };
    const result = snapshotToDataTable(snap);
    const ids = result.rows.map(r => r.id);
    expect(new Set(ids).size).toBe(ids.length);
  });
});
