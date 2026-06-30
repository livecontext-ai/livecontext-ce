'use client';

import { Table, Clock } from 'lucide-react';
import { useTranslations } from 'next-intl';
import type { DataSource } from '@/lib/api';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { getDataSourceColumns, getDataSourceColumnHeaders } from '@/components/DataSourceColumnIcons';
import { PublicationStatusIcon } from '@/components/publications/PublicationStatusIcon';
import { FavoriteStarButton } from '@/components/ui/FavoriteStarButton';

/** Rows shown in a card's mini-table preview (the chat table-visualize card style). */
const PREVIEW_ROWS = 3;
/** Columns shown before the rest collapse behind a trailing "+N" indicator. The preview
 *  never scrolls horizontally (not aesthetic in a 2-up grid), so extras are summarised. */
const PREVIEW_COLS = 4;
/** Keys that may leak into a row's `data` payload but are never user columns. */
const PREVIEW_SYSTEM_KEYS = new Set(['id', 'priority', 'created_at', 'updated_at', 'data_source_id', 'tenant_id']);

/** Compact, single-line rendering of a cell value for the card preview. */
function formatPreviewCell(value: unknown, bool: { yes: string; no: string }): string {
  if (value === null || value === undefined || value === '') return '-';
  if (typeof value === 'boolean') return value ? bool.yes : bool.no;
  if (typeof value === 'object') {
    try { return JSON.stringify(value).slice(0, 40); } catch { return '-'; }
  }
  return String(value);
}

/** Publication-status badge inputs for a table card (omit in read-only contexts). */
export interface DataSourceCardPublicationStatus {
  isShared: boolean;
  isPending: boolean;
  isRejected: boolean;
  rejectionReason?: string | null;
  /** Show a Lock when not shared/pending/rejected (status now ships with the list page). */
  showPrivate?: boolean;
}

interface DataSourceCardProps {
  ds: DataSource;
  /** Number of rows in the table (drives the footer "N rows"). */
  rowCount: number;
  /** First few row `data` payloads: paints the mini-table preview body. */
  sampleRows: Array<Record<string, unknown>>;
  onClick: () => void;
  /**
   * Optional multi-select checkbox (top-right, on hover). Provide BOTH `selected`
   * and `onToggleSelect` to enable it; omit in read-only contexts (e.g. a project's
   * Tables tab) so the card has no checkbox.
   */
  selected?: boolean;
  onToggleSelect?: () => void;
  /** Optional publication-status badge next to the name (omit when not tracked). */
  publication?: DataSourceCardPublicationStatus;
  /** Whether this table is in the user's personal favorites. */
  isFavorite?: boolean;
  /** Toggle the table's favorite state. When omitted, the star is not rendered (e.g. read-only contexts). */
  onToggleFavorite?: () => void;
}

/**
 * A single data-source card: a mini-table preview (column headers + a few sample
 * rows, mirroring the chat table-visualize card / ChatTableView) over a footer with
 * the name, optional publication badge, column count, last-modified, and row count.
 * A column-less table (no schema AND no rows) shows a centered table-icon hero.
 *
 * <p>Shared by {@code /app/tables} (DataSourceTable, with selection + publish badge)
 * and a project's Tables tab (read-only, neither), so both render identically.
 */
export function DataSourceCard({ ds, rowCount, sampleRows, onClick, selected, onToggleSelect, publication, isFavorite, onToggleFavorite }: DataSourceCardProps) {
  const t = useTranslations();
  const boolLabels = { yes: t('common.yes'), no: t('common.no') };

  const columns = getDataSourceColumns(ds);
  const modifiedAt = ds.updatedAt || ds.updated_at || ds.createdAt || ds.created_at;
  // Mini-table preview (inspired by the chat table-visualize card): column
  // headers + the first few sample rows from the batched paged response.
  const headers = getDataSourceColumnHeaders(ds);
  const rawSample = sampleRows ?? [];
  // Tables with rows but an empty mapping_spec still preview, so derive the
  // header keys straight from the row payload.
  const derivedHeaders = headers.length === 0 && rawSample.length > 0
    ? Object.keys(rawSample[0])
        .filter((k) => !PREVIEW_SYSTEM_KEYS.has(k))
        .map((k) => ({ key: k, label: k, type: 'text' as const }))
    : headers;
  // Cap the visible columns and summarise the rest behind a "+N" indicator,
  // the preview never scrolls horizontally (not aesthetic in a 2-up grid).
  const visibleCols = derivedHeaders.slice(0, PREVIEW_COLS);
  const extraCols = derivedHeaders.length - visibleCols.length;
  const previewRows = rawSample.slice(0, PREVIEW_ROWS);
  // Mirror ChatTableView (the chat table-visualize card this preview is
  // modeled on): as soon as the table HAS columns, show the mini-table,
  // the header row renders even with zero rows, and an empty body shows a
  // "no rows yet" placeholder. Only a column-less table (no schema AND no
  // rows) falls back to the centered icon hero.
  const hasPreview = visibleCols.length > 0;

  return (
    <div
      className="group rounded-[18px] border border-theme overflow-hidden bg-gradient-to-br from-slate-50 to-slate-100 dark:from-slate-800 dark:to-slate-900 hover:shadow-md transition-shadow cursor-pointer"
      onClick={onClick}
    >
      {/* Sober, solid backdrop, no grid pattern. The center mirrors the chat
          table-visualize card (ChatTableView): a clean mini-table preview
          (column headers + a few sample rows, no column-type icons),
          falling back to a centered table icon only when the table has no
          columns at all (a table WITH columns shows its header row even when
          it has no rows yet). */}
      <div className="relative min-h-[200px] overflow-hidden bg-slate-50 dark:bg-slate-900">
        {hasPreview ? (
          // Full-bleed mini-table styled like the chat table-visualize card
          // (ChatTableView): generous px-4 cells, taller h-14 rows, row hover.
          // It never scrolls horizontally: `table-fixed w-full` makes the
          // capped columns share the width, and any columns past PREVIEW_COLS
          // collapse into a trailing "+N" indicator column.
          <div className="min-h-[200px] bg-theme-primary overflow-hidden">
            <table className="w-full text-sm table-fixed">
              <thead className="bg-theme-secondary border-b border-theme">
                <tr>
                  {visibleCols.map((col) => (
                    <th key={col.key} className="px-4 py-3 text-left font-medium text-theme-primary whitespace-nowrap">
                      <span className="block truncate" title={col.label}>{col.label}</span>
                    </th>
                  ))}
                  {extraCols > 0 && (
                    <th className="w-12 px-2 py-3 text-center font-medium text-theme-muted whitespace-nowrap">
                      +{extraCols}
                    </th>
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {previewRows.length > 0 ? previewRows.map((row, ri) => (
                  <tr key={ri} className="hover:bg-theme-secondary/50 transition-colors h-14">
                    {visibleCols.map((col) => {
                      const cell = formatPreviewCell(row[col.key], boolLabels);
                      return (
                        <td key={col.key} className="px-4 py-4 text-theme-primary">
                          <span className="block truncate" title={cell}>{cell}</span>
                        </td>
                      );
                    })}
                    {extraCols > 0 && (
                      <td className="w-12 px-2 py-4 text-center text-theme-muted">…</td>
                    )}
                  </tr>
                )) : (
                  // Columns defined but no rows yet, mirror ChatTableView's
                  // "No rows yet" placeholder spanning all the columns.
                  <tr>
                    <td colSpan={visibleCols.length + (extraCols > 0 ? 1 : 0)} className="px-4 py-8 text-center text-theme-muted">
                      <span className="text-sm">{t('data.noRowsYet')}</span>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        ) : (
          // Column-less table (no schema AND no rows): a clean centered table
          // icon, mirroring ChatTableView's "no data yet" state.
          <div className="h-[200px] flex items-center justify-center">
            <div className="w-12 h-12 bg-theme-secondary rounded-full flex items-center justify-center">
              <Table className="w-6 h-6 text-theme-primary" />
            </div>
          </div>
        )}

        {/* Selection checkbox - visible on hover OR when checked (only when selectable). */}
        {onToggleSelect && (
          <div className={`absolute top-2 right-2 transition-opacity z-10 ${selected ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'}`}>
            <input
              type="checkbox"
              checked={!!selected}
              onChange={onToggleSelect}
              onClick={(e) => e.stopPropagation()}
              className="rounded border-theme cursor-pointer h-4 w-4 bg-white dark:bg-slate-800"
            />
          </div>
        )}

        {/* Favorite star - bottom-left, floats this table to the top of the list. */}
        {onToggleFavorite && (
          <FavoriteStarButton
            isFavorite={!!isFavorite}
            onToggle={onToggleFavorite}
          />
        )}
      </div>

      {/* Footer */}
      <div className="bg-white/80 dark:bg-slate-800/80 backdrop-blur-sm border-t border-theme px-4 py-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-medium text-theme-primary truncate">{ds.name}</span>
            {publication && (
              <PublicationStatusIcon
                isShared={publication.isShared}
                isPending={publication.isPending}
                isRejected={publication.isRejected}
                rejectionReason={publication.rejectionReason}
                showPrivate={publication.showPrivate}
              />
            )}
            <span className="text-xs text-theme-muted shrink-0 ml-auto">
              {t('data.columnCount', { count: columns.count })}
            </span>
          </div>
          {ds.description && (
            <p className="text-xs text-theme-muted truncate mt-0.5">{ds.description}</p>
          )}
          <div className="flex items-center gap-1 mt-1 text-xs text-theme-muted">
            <Clock className="h-3 w-3" />
            <span>{modifiedAt ? formatRelativeDate(modifiedAt) : '-'}</span>
            <span className="text-slate-300 dark:text-slate-600">·</span>
            <span>{t('data.rowCount', { count: rowCount })}</span>
          </div>
        </div>
      </div>
    </div>
  );
}
