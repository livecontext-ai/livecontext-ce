'use client';

import React from 'react';
import { COLUMN_TYPE_META } from '@/components/data-table/visualHelpers';
import { nodeIconBoxRadiusClass } from '@/app/workflows/builder/components/nodes/shared';
import type { ColumnVisualType } from '@/types/data-sources';
import type { DataSource } from '@/lib/api';
import { buildColumnOrderRank, columnPathOf } from '@/utils/columnSpec';

/**
 * Columns that exist in `mapping_spec` for bookkeeping but are not user-facing
 * data columns - excluded from the card's column count + icon row.
 */
const SYSTEM_COLUMNS = new Set([
  'id', 'priority', 'created_at', 'updated_at', 'data_source_id', 'tenant_id',
]);

/** Coerce any backend column type string to a known ColumnVisualType (lowercase). */
export function normalizeColumnType(raw?: unknown): ColumnVisualType {
  const key = String(raw ?? '').toLowerCase().trim();
  return (key && key in COLUMN_TYPE_META ? key : 'text') as ColumnVisualType;
}

export interface DataSourceColumnsInfo {
  /** User-facing column count (system columns excluded). */
  count: number;
  /** Column visual types in saved column order, system columns excluded. */
  types: ColumnVisualType[];
}

/** A user-facing column: its bare data key, display label, and visual type. */
export interface DataSourceColumnHeader {
  /** Bare column key - matches the keys inside a row's `data` JSON. */
  key: string;
  /** Human label (display.label → label → key fallback). */
  label: string;
  type: ColumnVisualType;
}

/** Per-column spec entry as stored in `mapping_spec` (label may be nested under `display`). */
type ColumnSpec = { type?: string; label?: string; path?: string; display?: { label?: string } } | undefined;

/**
 * The user-facing columns of a DataSource in saved order, system columns
 * excluded. Reads snake_case first (the backend's @JsonProperty names) and
 * falls back to camelCase; respects `column_order` through the shared
 * {@link buildColumnOrderRank}, asked about the same FIELD the data grid asks
 * about (the column's mapping-spec path), so the two can never rank one column
 * differently. Columns the order does not name are appended here, where the
 * grid instead leaves them in place; a card is a preview strip with no lanes to
 * displace. Shared by {@link getDataSourceColumns} and
 * {@link getDataSourceColumnHeaders} so both see identical ordering.
 */
function orderedColumnEntries(
  ds: Pick<DataSource, 'mapping_spec' | 'mappingSpec' | 'column_order' | 'columnOrder'>
): Array<[string, ColumnSpec]> {
  const spec = (ds.mapping_spec ?? ds.mappingSpec ?? {}) as Record<string, ColumnSpec>;
  const order = (ds.column_order ?? ds.columnOrder ?? []) as Array<Record<string, unknown>>;

  let entries = Object.entries(spec).filter(([key]) => !SYSTEM_COLUMNS.has(key));

  const rank = buildColumnOrderRank(order);
  if (rank.size > 0) {
    const rankOf = ([key, value]: [string, ColumnSpec]) =>
      rank.of(columnPathOf(key, value?.path)) ?? Number.MAX_SAFE_INTEGER;
    entries = entries.slice().sort((a, b) => rankOf(a) - rankOf(b));
  }

  return entries;
}

/**
 * Derive the user-facing columns (count + ordered visual types) from a
 * DataSource's `mapping_spec` / `column_order`.
 */
export function getDataSourceColumns(
  ds: Pick<DataSource, 'mapping_spec' | 'mappingSpec' | 'column_order' | 'columnOrder'>
): DataSourceColumnsInfo {
  const entries = orderedColumnEntries(ds);
  return {
    count: entries.length,
    types: entries.map(([, value]) => normalizeColumnType(value?.type)),
  };
}

/**
 * Ordered column headers (key + label + type) for a DataSource - used to paint
 * a card's mini-table preview header row. Same ordering as
 * {@link getDataSourceColumns}; the label resolves `display.label` → `label` →
 * the bare key.
 */
export function getDataSourceColumnHeaders(
  ds: Pick<DataSource, 'mapping_spec' | 'mappingSpec' | 'column_order' | 'columnOrder'>
): DataSourceColumnHeader[] {
  return orderedColumnEntries(ds).map(([key, value]) => ({
    key,
    label: value?.display?.label || value?.label || key,
    type: normalizeColumnType(value?.type),
  }));
}

interface DataSourceColumnIconsProps {
  types: ColumnVisualType[];
  /** Max icons to render before collapsing the rest into "+N". */
  maxDisplay?: number;
  /** Chip scale: `md` (hero, the empty-table fallback) or `sm` (the preview strip). */
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * A row of column-type icon bubbles for a table card - the data-table analogue
 * of {@link WorkflowNodeIcons}. Shows up to `maxDisplay` icons, then a "+N"
 * bubble for the remaining columns.
 */
export function DataSourceColumnIcons({ types, maxDisplay = 5, size = 'md', className = '' }: DataSourceColumnIconsProps) {
  if (!types || types.length === 0) return null;

  const displayed = types.slice(0, maxDisplay);
  const overflow = types.length - displayed.length;
  // Shared chip shape; the per-type colour comes from COLUMN_TYPE_META.badgeClass.
  // `sm` is the compact strip sitting above the mini-table preview; `md` is the
  // hero chip shown centered when a table is empty (today's look).
  const chipPx = size === 'sm' ? 28 : 40;
  const chipSize = size === 'sm' ? 'h-7 w-7' : 'h-10 w-10';
  const iconSize = size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4';
  // Same ladder, read from the same helper, as the WorkflowNodeIcons bubble this
  // component is the sibling of: these two draw the same object on the same
  // cards, and a single hand-picked `rounded-md` for both sizes had them
  // disagreeing by one rung at 28px and two at 40px.
  const base =
    `flex items-center justify-center ${chipSize} ${nodeIconBoxRadiusClass(chipPx)} border border-slate-200 dark:border-slate-700 shadow-sm`;

  return (
    <div className={`flex items-center gap-2 ${className}`}>
      {displayed.map((type, i) => {
        const meta = COLUMN_TYPE_META[type] ?? COLUMN_TYPE_META.text;
        const Icon = meta.icon;
        // Tint each chip by its column type - the data-table analogue of the
        // colour-per-node-type icons on workflow cards. Reuses the shared
        // badgeClass palette; the icon inherits the chip's text colour.
        return (
          <span key={`${type}-${i}`} className={`${base} ${meta.badgeClass}`} title={type}>
            <Icon className={iconSize} />
          </span>
        );
      })}
      {overflow > 0 && (
        <span className={`${base} bg-white dark:bg-slate-800 text-xs font-medium text-slate-500 dark:text-slate-400`} title={`+${overflow} more`}>
          +{overflow}
        </span>
      )}
    </div>
  );
}
