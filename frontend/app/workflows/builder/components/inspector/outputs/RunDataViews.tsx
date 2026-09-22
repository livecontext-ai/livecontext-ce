/**
 * The three ways a run payload can be read in the inspector, plus the switcher
 * that picks between them.
 *
 *  - Tree  : the existing expandable key/value tree (rendered by the caller).
 *  - JSON  : the raw document, pretty-printed and copyable in one go - the view
 *            you want when the payload is going into a bug report or a curl.
 *  - Table : rows, when the payload (or a field of it) is an array of objects -
 *            the shape a split/aggregate/find node produces on every run.
 *
 * The switcher deliberately reuses the segmented-control chrome of the header's
 * view tabs so the inspector keeps one visual language for "pick a view".
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Braces, ListTree, Table2 } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { CopyButton } from '../shared/CopyButton';
import {
  collectTableColumns,
  formatCellValue,
  formatJson,
  isTabularArray,
  MAX_TABLE_COLUMNS,
  pickTabularValue,
  tabularFields,
} from './runValueUtils';

export type RunDataViewMode = 'tree' | 'json' | 'table';

interface RunDataViewTabsProps {
  mode: RunDataViewMode;
  onModeChange: (mode: RunDataViewMode) => void;
  /** Whether the loaded payload can be laid out as rows. */
  tableAvailable: boolean;
  /** Value the "copy all" button puts on the clipboard (omit to hide it). */
  copyValue?: unknown;
  /**
   * Which run column this switcher belongs to. Stamped on the control so a
   * reader - and a test - can tell the Input column's switcher from the
   * Params column's when several are on screen at once.
   */
  columnId?: 'params' | 'output';
  className?: string;
}

/**
 * Icon-only on purpose: the params/output columns are 280px wide by default and
 * three labelled segments plus a copy button do not fit there without wrapping.
 */
export function RunDataViewTabs({
  mode,
  onModeChange,
  tableAvailable,
  copyValue,
  columnId,
  className,
}: RunDataViewTabsProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');

  const segments: Array<{ id: RunDataViewMode; label: string; icon: React.ComponentType<{ className?: string }> }> = [
    { id: 'tree', label: t('viewTree'), icon: ListTree },
    { id: 'json', label: t('viewJson'), icon: Braces },
    ...(tableAvailable ? [{ id: 'table' as const, label: t('viewTable'), icon: Table2 }] : []),
  ];

  return (
    <div
      data-run-data-column={columnId}
      className={clsx('flex items-center justify-between gap-2', className)}
    >
      <div
        className="inline-flex flex-shrink-0 items-center gap-0.5 rounded-lg bg-theme-tertiary p-1"
        role="tablist"
        aria-label={t('viewSwitcherLabel')}
      >
        {segments.map(({ id, label, icon: Icon }) => {
          const isActive = mode === id;
          return (
            <button
              key={id}
              type="button"
              role="tab"
              data-view-id={id}
              aria-selected={isActive}
              aria-label={label}
              title={label}
              onClick={() => onModeChange(id)}
              className={clsx(
                'flex h-7 w-7 items-center justify-center rounded-md text-sm font-medium transition-colors duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]/60',
                isActive
                  ? 'bg-[var(--bg-primary)] text-theme-primary shadow-sm'
                  : 'text-theme-secondary hover:text-theme-primary',
              )}
            >
              <Icon className="h-3.5 w-3.5" />
            </button>
          );
        })}
      </div>
      {copyValue !== undefined && (
        <CopyButton
          value={copyValue}
          title={t('copyAll')}
          data-testid="run-data-copy-all"
        />
      )}
    </div>
  );
}

/**
 * Raw pretty-printed JSON. Scrolls on its own axis so a long line never widens
 * the inspector column (the panel itself must not scroll sideways).
 */
export function RawJsonView({ data }: { data: unknown }) {
  const text = React.useMemo(() => formatJson(data), [data]);
  return (
    <pre
      data-testid="run-data-json-view"
      className="max-h-[60vh] overflow-auto rounded-md bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-700 p-2 text-sm font-mono text-[var(--text-primary)] whitespace-pre"
    >
      {text}
    </pre>
  );
}

interface JsonTableViewProps {
  /** Any value - a non-tabular one renders the "not tabular" hint. */
  data: unknown;
}

/**
 * Rows for an array of objects. Nested values are summarised (`{3}` / `[7]`)
 * rather than expanded: the tree view is where nesting is explored, the table
 * is for scanning many items on the same key.
 */
export function JsonTableView({ data }: JsonTableViewProps) {
  const t = useTranslations('workflowBuilder.inspector.runData');
  // Which field is being laid out. A payload is rarely an array itself: the rows
  // live under `items`, `results`, `organic_results`... The table therefore shows
  // a SUBSET of what the tree shows, and used to do it silently - which is why it
  // looked like the two views disagreed. The field is named on screen, and when
  // several qualify the reader picks instead of the code guessing.
  const fields = React.useMemo(() => tabularFields(data), [data]);
  const [selected, setSelected] = React.useState<string | null>(null);
  const activeField = selected && fields.includes(selected) ? selected : (fields[0] ?? null);
  const picked = React.useMemo(
    () => (isTabularArray(data) ? data : pickTabularValue(data, activeField ?? undefined)),
    [data, activeField],
  );
  const rows = isTabularArray(picked) ? picked : null;
  const columns = React.useMemo(() => (rows ? collectTableColumns(rows) : []), [rows]);
  // Memoized because countDistinctKeys walks every row, and placed above the
  // early return because it is now a hook: below it, a value that stops being
  // tabular between renders would change the hook count and React would throw.
  // (It was a plain expression before, so there was no such hazard to fix - the
  // ordering is a constraint this memo introduces, not a bug it repairs.)
  const hiddenColumns = React.useMemo(
    () => (rows ? countDistinctKeys(rows) - columns.length : 0),
    [rows, columns.length],
  );

  if (!rows) {
    return (
      <p className="py-4 text-center text-sm text-slate-500">{t('tableUnavailable')}</p>
    );
  }

  return (
    <div className="space-y-1">
      {!isTabularArray(data) && activeField && (
        <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
          <span>{t('tableShowingField')}</span>
          {fields.length > 1 ? (
            <select
              value={activeField}
              onChange={(e) => setSelected(e.target.value)}
              aria-label={t('tableShowingField')}
              data-testid="run-data-table-field"
              className="rounded border border-slate-200 dark:border-slate-700 bg-transparent px-1 py-0.5 text-sm font-mono text-[var(--text-primary)]"
            >
              {fields.map((field) => (
                <option key={field} value={field}>{field}</option>
              ))}
            </select>
          ) : (
            <code data-testid="run-data-table-field" className="font-mono text-[var(--text-primary)]">{activeField}</code>
          )}
        </div>
      )}
      <div className="overflow-x-auto rounded-md border border-slate-200 dark:border-slate-700">
        <table className="w-full border-collapse text-sm" data-testid="run-data-table-view">
          <thead>
            <tr className="bg-slate-50 dark:bg-slate-800/60">
              <th className="px-2 py-1 text-left text-sm font-semibold text-slate-500 dark:text-slate-400">
                #
              </th>
              {columns.map((column) => (
                <th
                  key={column}
                  className="px-2 py-1 text-left text-sm font-semibold text-slate-500 dark:text-slate-400 whitespace-nowrap"
                  title={column}
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr
                key={index}
                className="border-t border-slate-100 dark:border-slate-700/60 hover:bg-slate-50 dark:hover:bg-slate-800/40"
              >
                <td className="px-2 py-1 font-mono text-sm text-slate-400">{index}</td>
                {columns.map((column) => {
                  const cell = row[column];
                  return (
                    <td
                      key={column}
                      className="px-2 py-1 align-top font-mono text-sm text-[var(--text-primary)] max-w-[220px] truncate"
                      title={formatCellValue(cell)}
                    >
                      {formatCellValue(cell)}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {hiddenColumns > 0 && (
        <p className="text-sm text-slate-500">
          {t('tableColumnsTruncated', { shown: MAX_TABLE_COLUMNS, hidden: hiddenColumns })}
        </p>
      )}
    </div>
  );
}

function countDistinctKeys(rows: Array<Record<string, unknown>>): number {
  const keys = new Set<string>();
  for (const row of rows) {
    for (const key of Object.keys(row)) keys.add(key);
  }
  return keys.size;
}
