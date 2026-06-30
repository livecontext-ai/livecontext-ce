import { normalizeMappingSpec, type RawMappingSpecValue } from '@/utils/columnSpec';
import type {
  ColumnDefinition,
  ColumnOrder,
  DataSourceItemRow,
  SnapshotTableData,
} from '@/components/data-table/types';
import type { DataSourceSnapshot } from '@/contexts/PublicationSnapshotContext';

type ColumnOrderSource = string | { field?: string; order?: number } | null | undefined;

function inferType(value: unknown): 'text' | 'number' | 'date' | 'boolean' | 'json' {
  if (value === null || value === undefined) return 'text';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}/.test(value)) return 'date';
    return 'text';
  }
  if (typeof value === 'object') return 'json';
  return 'text';
}

/**
 * Converts a publication's frozen DataSource snapshot into the shape DataTable expects.
 * When mappingSpec is present we reuse the same column pipeline as live tables; otherwise
 * we fall back to inferring columns from the first row's data keys.
 */
export function snapshotToDataTable(snapshot: DataSourceSnapshot | null | undefined): SnapshotTableData {
  if (!snapshot) return { rows: [], columns: [] };

  const items = snapshot.items ?? [];
  const nowIso = new Date().toISOString();

  const rows: DataSourceItemRow[] = [...items]
    .sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0))
    .map((item, idx) => ({
      id: idx + 1,
      data_source_id: 0,
      tenant_id: 'snapshot',
      data: (item && typeof item === 'object' && item.data && typeof item.data === 'object') ? item.data : {},
      priority: item?.priority ?? 0,
      created_at: nowIso,
      updated_at: null,
    }));

  const mappingSpec = snapshot.mappingSpec as Record<string, RawMappingSpecValue> | undefined | null;
  let columns: ColumnDefinition[] = [];

  if (mappingSpec && typeof mappingSpec === 'object') {
    const normalized = normalizeMappingSpec(mappingSpec);
    columns = Object.values(normalized).map(spec => ({
      col_id: spec.path,
      field: spec.path,
      header_name: spec.display?.label || spec.key,
      type: spec.type as ColumnDefinition['type'],
      editable: false,
      sortable: spec.type !== 'json',
      filterable: true,
      isNavigable: spec.structure !== 'scalar' || spec.type === 'json',
      displayConfig: spec.display,
      structure: spec.structure,
    }));
  }

  if (columns.length === 0) {
    // Fallback - infer from the union of data keys.
    const keys = new Set<string>();
    const types = new Map<string, 'text' | 'number' | 'date' | 'boolean' | 'json'>();
    for (const row of rows) {
      const data = row.data;
      if (data && typeof data === 'object') {
        for (const k of Object.keys(data)) {
          if (k.startsWith('_')) continue;
          keys.add(k);
          if (!types.has(k)) types.set(k, inferType((data as Record<string, unknown>)[k]));
        }
      }
    }
    columns = Array.from(keys).map(k => ({
      col_id: `data.${k}`,
      field: `data.${k}`,
      header_name: k,
      type: types.get(k) ?? 'text',
      editable: false,
      sortable: true,
      filterable: true,
      isNavigable: false,
    }));
  }

  // ColumnOrder tolerates both `string[]` (raw keys) and `{field, order}[]` (live table shape).
  const rawOrder = snapshot.columnOrder as ColumnOrderSource[] | undefined;
  let columnOrder: ColumnOrder[] | undefined;
  if (Array.isArray(rawOrder) && rawOrder.length > 0) {
    const normalized = rawOrder
      .map((entry, idx) => {
        if (typeof entry === 'string') return { field: entry, order: idx };
        if (entry && typeof entry === 'object' && typeof entry.field === 'string') {
          return { field: entry.field, order: typeof entry.order === 'number' ? entry.order : idx };
        }
        return null;
      })
      .filter((v): v is { field: string; order: number } => v !== null);
    if (normalized.length > 0) columnOrder = normalized;
  }
  if (!columnOrder) {
    columnOrder = columns.map((c, i) => ({ field: c.field, order: i }));
  }

  return { rows, columns, columnOrder };
}
