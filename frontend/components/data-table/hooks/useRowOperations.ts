'use client';

import { useCallback, useState } from 'react';
import { useTranslations } from 'next-intl';
import type { DataSourceItemRow, PaginationState } from '../types';
import { getValueAtPath, setValueAtPath } from '../visualHelpers';
import { useRevealWindow } from './useRevealWindow';
import { authenticatedFetch } from '../utils/authenticatedFetch';
import { limitConcurrency } from '@/lib/utils/concurrency';
import { getDefaultSortConfig, parseEditValue, parseErrorResponse, toWritableRowData, type SortConfig } from '../utils/dataTableUtils';
import { resolveColumnType } from '@/utils/columnSpec';
import type { ColumnDefinition } from '../types';

export interface WorkflowContext {
  workflowId: string;
  runId: string;
  stepId?: number;
  stepAlias?: string;
  isAggregated?: boolean;
}

export interface UseRowOperationsParams {
  dataSourceId?: number;
  jsonPath?: string;
  workflowContext?: WorkflowContext | null;
  rows: DataSourceItemRow[];
  setRows: React.Dispatch<React.SetStateAction<DataSourceItemRow[]>>;
  pagination: PaginationState;
  /**
   * The user's own sort, when they set one. Duplicating needs it to know WHERE the copies will
   * land: with no sort the server returns newest first, so a copy jumps to page 1.
   */
  sortConfig?: SortConfig | null;
  /**
   * Fields this view renders as ROW-level lanes (see `getRowLevelExportFields(viewConfig)`). The
   * grid decides what a cell SHOWS from the same list, so an edit writes wherever the value was
   * read from. Gating on `jsonPath` alone disagreed with the grid at workflow root: the cell showed
   * the item's own `priority` and the edit wrote the row's, which then reverted with no error.
   */
  rowLevelFields?: string[];
  /** Column definitions, so a copy can leave the read path's vector text behind. */
  columns?: ColumnDefinition[];
  fetchData: (page?: number, pageSize?: number) => Promise<void>;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
}

export interface UseRowOperationsReturn {
  // State
  showAddRowModal: boolean;
  newRowData: Record<string, any>;
  newRowPriority: number;
  isAddingRow: boolean;
  isAddingRowInline: boolean;
  isDuplicatingRows: boolean;
  /**
   * Ids of the rows a duplicate just created, for as long as the grid should point at them
   * (see {@link useRevealWindow}), then empty again.
   */
  revealedRowIds: ReadonlySet<number>;

  // Setters
  setShowAddRowModal: React.Dispatch<React.SetStateAction<boolean>>;
  setNewRowData: React.Dispatch<React.SetStateAction<Record<string, any>>>;
  setNewRowPriority: React.Dispatch<React.SetStateAction<number>>;

  // Actions
  /**
   * Resolves `false` when the edit was refused or failed (a toast says why) or is a read-only
   * position that is never written; `true` otherwise, i.e. nothing refused it and the row on
   * screen now carries the value. `true` is NOT a proof of a server write: a table with no
   * backing source, and the priority of a workflow-run row, only ever change on screen.
   */
  handleSaveEdit: (rowId: number, columnId: string, value: string, arrayIndex?: number | null) => Promise<boolean>;
  addNewRow: () => Promise<void>;
  deleteSelectedRows: (selectedRows: Set<string>, getRowUniqueKey: (row: DataSourceItemRow) => string) => Promise<void>;
  /** Copies the selected rows; resolves with how many copies were actually written. */
  duplicateSelectedRows: (selectedRows: Set<string>, getRowUniqueKey: (row: DataSourceItemRow) => string) => Promise<number>;
  startAddingRowInline: () => void;
  cancelAddingRowInline: () => void;
  handleRowDataChange: (key: string, value: string) => void;
}

const ORCHESTRATOR_URL = '/api/proxy/workflows';

/**
 * Pick the page to refetch after deleting {@code deletedCount} rows from
 * {@code pagination}. Clamps the current page to the new last page so the
 * user does not land on an empty page when later pages still hold data
 * (e.g. delete every row on page 1 while pages 2+ exist).
 *
 * Exported for unit testing.
 */
export function computeTargetPageAfterDelete(
  pagination: PaginationState,
  deletedCount: number,
): number {
  const remaining = Math.max(0, pagination.totalItems - deletedCount);
  const newTotalPages = Math.max(1, Math.ceil(remaining / pagination.pageSize));
  return Math.min(Math.max(1, pagination.currentPage), newTotalPages);
}

/**
 * How many copies are written at once.
 *
 * There is no bulk "duplicate" on the server (bulk takes DELETE and PATCH only), so a duplicate is
 * one POST per row. Selection is bounded by the page, so this is the difference between a page of
 * rows arriving as a burst of parallel requests and arriving as a handful of small waves.
 */
const DUPLICATE_CONCURRENCY = 4;

/** One copy's result: the id the server assigned, or why it refused. */
type CopyOutcome = { id: number | null } | { error: string };

/** Stable "no row is being pointed at" value, so clearing the cue does not also re-render on a new Set. */
const NO_REVEALED_ROWS: ReadonlySet<number> = new Set();

/** The message out of whatever a rejected copy was rejected with, or null if it carried none. */
function reasonText(reason: unknown): string | null {
  if (reason instanceof Error) return reason.message;
  return typeof reason === 'string' && reason.trim() ? reason : null;
}

/**
 * Hook for managing row CRUD operations in the data table.
 */
export function useRowOperations({
  dataSourceId,
  jsonPath,
  workflowContext,
  rows,
  setRows,
  pagination,
  sortConfig,
  columns,
  rowLevelFields = ['checkbox', 'id', 'priority', 'created_at'],
  fetchData,
  addToast,
}: UseRowOperationsParams): UseRowOperationsReturn {
  const [showAddRowModal, setShowAddRowModal] = useState(false);
  const [newRowData, setNewRowData] = useState<Record<string, any>>({});
  const [newRowPriority, setNewRowPriority] = useState(1);
  const [isAddingRow, setIsAddingRow] = useState(false);
  const [isAddingRowInline, setIsAddingRowInline] = useState(false);
  const [isDuplicatingRows, setIsDuplicatingRows] = useState(false);
  const [revealedRowIds, revealRows] = useRevealWindow<ReadonlySet<number>>(NO_REVEALED_ROWS, `${dataSourceId}:${jsonPath ?? ''}`);
  const t = useTranslations('dataTable');

  // Helper to get field path without 'data.' prefix
  const getFieldPath = (field: string) =>
    field.startsWith('data.') ? field.replace('data.', '') : field;

  // Apply value to row data at path
  const applyValueToRow = (data: Record<string, any>, field: string, nextValue: any) =>
    setValueAtPath(data, getFieldPath(field), nextValue);

  /**
   * Save a cell edit
   */
  const handleSaveEdit = useCallback(async (
    rowId: number,
    columnId: string,
    value: string,
    arrayIndex?: number | null
  ): Promise<boolean> => {
    try {
      // Clean up columnId
      columnId = columnId.trim().replace(/,$/, '').replace(/^,/, '').replace(/\/$/, '');

      const parsedValue = parseEditValue(value);
      let patch: any[];

      // Row-level lane, like the array index below: only outside nested navigation, where a column
      // of that name is the navigated item's own field.
      if (columnId === 'priority' && rowLevelFields.includes('priority')) {
        patch = [{ op: 'replace', path: 'priority', value: parseInt(value) || 0 }];
      } else if ((columnId === 'index' || columnId === 'array_index') && jsonPath) {
        // The array index the nested view injects is a position, not stored data - read-only.
        // Outside nested navigation a column of that name is the table's own and saves normally.
        return false;
      } else if (jsonPath) {
        // Nested/array mode
        const currentArrayIndex = arrayIndex ?? rows.find(r => r.id === rowId)?.data?.array_index;
        let fullPath: string;

        if (currentArrayIndex !== undefined && currentArrayIndex !== null) {
          if (columnId === 'value') {
            fullPath = `${jsonPath}/${currentArrayIndex}`;
          } else {
            fullPath = `${jsonPath}/${currentArrayIndex}/${columnId}`;
          }
        } else {
          fullPath = `${jsonPath}/${columnId}`;
        }

        patch = [{ op: 'replace', path: fullPath, value: parsedValue }];
      } else if (columnId.startsWith('data.')) {
        const cleanPath = columnId.replace('data.', '').trim().replace(/,$/, '').replace(/^,/, '');
        patch = [{ op: 'replace', path: cleanPath, value: parsedValue }];
      } else {
        const systemColumns = ['id', 'priority', 'created_at', 'updated_at', 'checkbox', 'array_index', 'index'];
        if (systemColumns.includes(columnId)) {
          patch = [{ op: 'replace', path: columnId, value: parsedValue }];
        } else {
          const cleanColumnId = columnId.trim().replace(/,$/, '').replace(/^,/, '');
          patch = [{ op: 'replace', path: cleanColumnId, value: parsedValue }];
        }
      }

      // Save to backend
      if (workflowContext) {
        await saveWorkflowEdit(rowId, columnId, parsedValue);
      } else if (dataSourceId) {
        const response = await authenticatedFetch(
          `/api/proxy/data-sources/${dataSourceId}/items/${rowId}`,
          {
            method: 'PUT',
            body: JSON.stringify({ patch }),
          }
        );

        if (!response.ok) {
          const errorMessage = await parseErrorResponse(response, t('saveErrorMessage'));
          throw new Error(errorMessage);
        }
      }

      // Optimistic local update
      const currentArrayIdx = arrayIndex ?? rows.find(r => r.id === rowId)?.data?.array_index;

      setRows(prev => prev.map(row => {
        // For arrays, check both row.id and array_index
        if (jsonPath && currentArrayIdx !== undefined && currentArrayIdx !== null) {
          if (row.id !== rowId || row.data?.array_index !== currentArrayIdx) return row;
        } else {
          if (row.id !== rowId) return row;
        }

        if (columnId === 'priority' && rowLevelFields.includes('priority')) {
          return { ...row, priority: parseInt(value) || 0, updated_at: new Date().toISOString() };
        }

        // Same gate as the patch above: read-only only for the index the nested view injects.
        // Ungated, a saved root-level `array_index` edit would be dropped here and the cell would
        // visually revert while the PUT succeeded.
        if ((columnId === 'index' || columnId === 'array_index') && jsonPath) {
          return row;
        }

        // Update field in data
        if (jsonPath) {
          return {
            ...row,
            data: { ...row.data, [columnId]: parsedValue },
            updated_at: new Date().toISOString(),
          };
        } else if (columnId.startsWith('data.')) {
          return {
            ...row,
            data: applyValueToRow(row.data, columnId, parsedValue),
            updated_at: new Date().toISOString(),
          };
        } else {
          return {
            ...row,
            data: { ...row.data, [columnId]: parsedValue },
            updated_at: new Date().toISOString(),
          };
        }
      }));
      return true;
    } catch (err) {
      console.error('Error saving edit:', err);
      const errorMessage = err instanceof Error ? err.message : t('saveErrorMessage');
      addToast({
        type: 'error',
        title: t('saveErrorTitle'),
        message: errorMessage,
      });
      return false;
    }
  }, [dataSourceId, jsonPath, workflowContext, rows, setRows, addToast, rowLevelFields, t]);

  // Save edit for workflow context (storage update)
  // Uses row._outputStorageId directly to avoid unnecessary API calls
  const saveWorkflowEdit = useCallback(async (rowId: number, columnId: string, parsedValue: any) => {
    if (!workflowContext) return;

    // Find the row being edited to get its _outputStorageId
    const targetRow = rows.find(r => r.id === rowId);
    // What these throw is shown to the user as the body of the save-error toast, so it is the
    // translated message; the diagnostic detail goes to the console.
    if (!targetRow?._outputStorageId) {
      console.error('Workflow edit: no output storage id for row', rowId);
      throw new Error(t('saveErrorMessage'));
    }

    // Fetch current storage data
    const storageResponse = await authenticatedFetch(
      `${ORCHESTRATOR_URL}/storage/${targetRow._outputStorageId}`
    );
    if (!storageResponse.ok) {
      console.error('Workflow edit: storage read failed', storageResponse.status);
      throw new Error(t('saveErrorMessage'));
    }
    const storageData = await storageResponse.json();

    const storageContent = storageData.data_mapped || storageData.data || {};
    const dataArray = Array.isArray(storageContent) ? [...storageContent] : [storageContent];

    // Determine the item index within the storage array
    let itemIndex: number;
    if (targetRow.data?.array_index != null) {
      itemIndex = targetRow.data.array_index;
    } else if (workflowContext.stepAlias && !workflowContext.stepId) {
      // Merged view: each storage contains just the data for this row
      itemIndex = 0;
    } else {
      // Single step view: rowId is 1-based
      itemIndex = rowId - 1;
    }

    if (itemIndex < 0 || itemIndex >= dataArray.length) {
      console.error(`Workflow edit: item index ${itemIndex} out of bounds (array length: ${dataArray.length})`);
      throw new Error(t('saveErrorMessage'));
    }

    const cleanCol = columnId.startsWith('data.') ? columnId.replace('data.', '') : columnId;
    if (cleanCol === 'priority') return;
    dataArray[itemIndex] = { ...dataArray[itemIndex], [cleanCol]: parsedValue };

    const updatedData = Array.isArray(storageContent) ? dataArray : dataArray[0];

    // PUT updated data back
    const updateResponse = await authenticatedFetch(
      `${ORCHESTRATOR_URL}/storage/${targetRow._outputStorageId}`,
      {
        method: 'PUT',
        body: JSON.stringify({ data: updatedData, data_mapped: updatedData }),
      }
    );

    if (!updateResponse.ok) {
      const errorMessage = await parseErrorResponse(updateResponse, t('saveErrorMessage'));
      throw new Error(errorMessage);
    }
  }, [workflowContext, rows, t]);

  /**
   * Add a new row
   */
  const addNewRow = useCallback(async () => {
    if (!dataSourceId) return;

    try {
      setIsAddingRow(true);

      const response = await authenticatedFetch(`/api/proxy/data-sources/${dataSourceId}/items`, {
        method: 'POST',
        body: JSON.stringify({ data: newRowData, priority: newRowPriority }),
      });

      if (!response.ok) {
        throw new Error('Failed to add row');
      }

      // Reset form
      setNewRowData({});
      setNewRowPriority(1);
      setShowAddRowModal(false);
      setIsAddingRowInline(false);

      // Reload data
      await fetchData(pagination.currentPage, pagination.pageSize);

      addToast({
        type: 'success',
        title: t('rowAddedTitle'),
        message: t('rowAddedMessage', { priority: newRowPriority }),
      });
    } catch (err) {
      console.error('Error adding row:', err);
      addToast({
        type: 'error',
        title: t('addRowErrorTitle'),
        message: t('addRowErrorMessage'),
      });
    } finally {
      setIsAddingRow(false);
    }
  }, [dataSourceId, newRowData, newRowPriority, pagination, fetchData, addToast, t]);

  /**
   * Duplicate every selected row. Returns how many copies were actually written.
   *
   * One POST per row: the bulk endpoint only speaks DELETE and PATCH, so there is nothing to batch
   * into. Each copy carries the source row's user data and priority, and gets a fresh id and
   * timestamp from the server.
   *
   * Two things it deliberately does not do. A VECTOR column is left out of the copy entirely (see
   * toWritableRowData): the embedding itself lives in another table and is not reproduced, so a
   * copy stays absent from similarity search until something re-embeds it. And it does not touch a
   * nested view - there a "row" is one element inside a JSON array, duplicating it means patching
   * its parent, and the caller hides the action rather than write a junk row at the table's root.
   * Read-only is enforced by the caller hiding the action, and by the server refusing a VIEWER.
   */
  const duplicateSelectedRows = useCallback(async (
    selectedRows: Set<string>,
    getRowUniqueKey: (row: DataSourceItemRow) => string
  ): Promise<number> => {
    if (selectedRows.size === 0 || !dataSourceId || jsonPath || workflowContext) return 0;

    const requested = selectedRows.size;
    // Off the rendered rows, not off the keys: the copy needs the row's data, and iterating the
    // rows keeps the copies in the table's own order rather than the order they were clicked.
    const targets = rows.filter(row => selectedRows.has(getRowUniqueKey(row)));

    // A selection survives paging, so rows ticked on another page are not loaded and there is
    // nothing to copy them from. Said out loud on purpose: doing nothing at all, silently, is how
    // a user concludes the button is broken.
    if (targets.length === 0) {
      addToast({
        type: 'warning',
        title: t('duplicateRowsPartialTitle'),
        message: t('duplicateRowsPartialMessage', {
          done: 0,
          total: requested,
          reason: t('duplicateRowsReasonNotLoaded'),
        }),
      });
      revealRows(NO_REVEALED_ROWS);
      return 0;
    }

    setIsDuplicatingRows(true);
    try {
      // The shared worker pool, with each copy settled by hand: it resolves in order and keeps a
      // free slot busy instead of waiting on the slowest request of a batch, but it rejects as a
      // whole, and one refused copy must not abandon the rest half-written.
      const results = await limitConcurrency<CopyOutcome>(targets.map(row => async () => {
        try {
          const response = await authenticatedFetch(`/api/proxy/data-sources/${dataSourceId}/items`, {
            method: 'POST',
            // toWritableRowData, not row.data: the read path adds the display identity
            // (`_callId`/`id`) and the whole embedding as text to every row it hands the grid.
            body: JSON.stringify({ data: toWritableRowData(row, columns), priority: row.priority ?? 1 }),
          });
          if (!response.ok) {
            // The fallback is translated because it is interpolated into a translated sentence.
            // parseErrorResponse prefers the server's own text and only reaches this when a JSON
            // body carried no message at all - the item endpoint usually answers with an empty
            // body, in which case the user sees the status line instead.
            return { error: await parseErrorResponse(response, t('duplicateRowsReasonUnknown')) };
          }
          const created = await response.json();
          return { id: typeof created?.id === 'number' ? created.id : null };
        } catch (err) {
          return { error: reasonText(err) ?? t('duplicateRowsReasonUnknown') };
        }
      }), DUPLICATE_CONCURRENCY);

      const createdIds = results
        .map(r => ('id' in r ? r.id : null))
        .filter((id): id is number => id !== null);
      const written = results.filter(r => 'id' in r).length;
      const refused = results.find(r => 'error' in r) as { error: string } | undefined;

      if (written === 0) {
        // Nothing was created, so there is nothing to refetch or point at - and the reason the
        // server gave is the only useful thing here (a viewer-role 403 and a dropped connection
        // look identical without it).
        addToast({
          type: 'error',
          title: t('duplicateRowsErrorTitle'),
          message: t('duplicateRowsErrorMessage', {
            reason: refused?.error ?? t('duplicateRowsReasonUnknown'),
          }),
        });
        // Down with the previous duplicate's cue: leaving it up points at rows this run did not
        // produce, right next to a message saying nothing was copied.
        revealRows(NO_REVEALED_ROWS);
        return 0;
      }

      // Where the copies are is decided by the table's ORDER, not by where the user was standing.
      // Newest-first - the server's default, and what a sort cycled back to "default" resolves to -
      // puts them on page 1, so refetching page 3 would show a green toast and no copies.
      //
      // Under any OTHER order this code does not know where they landed: the copies carry their
      // originals' values and the server adds no tiebreaker within a sort key, so they can be
      // anywhere in their tie group. It stays put rather than guess, because moving the user
      // somewhere arbitrary is worse than not moving them.
      //
      // It also stays put after a PARTIAL result, whatever the order. The rows that were not copied
      // keep their tick precisely so the user can press Duplicate again, and leaving the page they
      // are loaded on would make that retry hit "nothing selected here" instead.
      const serverDefault = getDefaultSortConfig();
      const newestFirst = !sortConfig
        || (sortConfig.key === serverDefault.key && sortConfig.direction === serverDefault.direction);
      const goToFirstPage = newestFirst && written === requested;
      await fetchData(goToFirstPage ? 1 : pagination.currentPage, pagination.pageSize);
      revealRows(new Set(createdIds));

      if (written < requested) {
        // Two different shortfalls, named rather than merged: the server refused some copies, or
        // some selected rows were never loaded to copy from.
        addToast({
          type: 'warning',
          title: t('duplicateRowsPartialTitle'),
          message: t('duplicateRowsPartialMessage', {
            done: written,
            total: requested,
            reason: refused?.error ?? t('duplicateRowsReasonNotLoaded'),
          }),
        });
      } else {
        // A copy is not re-embedded, so on a table with a vector column it is absent from
        // similarity search until something embeds it. Said in the toast rather than left for the
        // user to discover through a search that quietly does not return their copies.
        const hasVector = (columns ?? []).some(c => c.type && resolveColumnType(c.type) === 'vector');
        addToast({
          type: 'success',
          title: t('duplicateRowsSuccessTitle'),
          message: hasVector
            ? t('duplicateRowsSuccessMessageVector', { count: written })
            : t('duplicateRowsSuccessMessage', { count: written }),
        });
      }
      return written;
    } finally {
      // try/finally, with no catch: every request failure is already a settled result above, so a
      // throw reaching here would be a real defect. Swallowing it into "duplication failed" would
      // hide it AND lie, since by then the copies have already been written.
      setIsDuplicatingRows(false);
    }
  }, [dataSourceId, jsonPath, workflowContext, rows, pagination, sortConfig, columns, fetchData, revealRows, addToast, t]);

  /**
   * Delete selected rows
   */
  const deleteSelectedRows = useCallback(async (
    selectedRows: Set<string>,
    getRowUniqueKey: (row: DataSourceItemRow) => string
  ) => {
    if (selectedRows.size === 0 || !dataSourceId) return;

    try {
      const selectedCount = selectedRows.size;

      if (jsonPath) {
        // Nested mode: group by rowId and delete from JSON path
        const selectionsByRowId = new Map<number, number[]>();

        Array.from(selectedRows).forEach(uniqueKey => {
          if (uniqueKey.includes('-')) {
            const [rowIdStr, arrayIndexStr] = uniqueKey.split('-');
            const rowId = parseInt(rowIdStr, 10);
            const arrayIdx = parseInt(arrayIndexStr, 10);

            if (!isNaN(rowId) && !isNaN(arrayIdx)) {
              if (!selectionsByRowId.has(rowId)) {
                selectionsByRowId.set(rowId, []);
              }
              selectionsByRowId.get(rowId)!.push(arrayIdx);
            }
          } else {
            const rowId = parseInt(uniqueKey, 10);
            if (!isNaN(rowId)) {
              selectionsByRowId.set(rowId, []);
            }
          }
        });

        const requests = Array.from(selectionsByRowId.entries()).map(async ([rowId, arrayIndexes]) => {
          if (arrayIndexes.length === 0) {
            // Delete entire JSON at path
            const response = await authenticatedFetch(
              `/api/proxy/data-sources/${dataSourceId}/items/${rowId}`,
              {
                method: 'PUT',
                body: JSON.stringify({ patch: [{ op: 'remove', path: jsonPath }] }),
              }
            );

            if (!response.ok) {
              const errorText = await response.text().catch(() => '');
              throw new Error(`Failed to delete at path "${jsonPath}" for row ${rowId}: ${errorText}`);
            }
          } else {
            // Delete specific array elements (descending order to avoid index shift)
            const sortedIndexes = [...arrayIndexes].sort((a, b) => b - a);

            for (const arrayIdx of sortedIndexes) {
              const arrayPath = `${jsonPath}/${arrayIdx}`;
              const response = await authenticatedFetch(
                `/api/proxy/data-sources/${dataSourceId}/items/${rowId}`,
                {
                  method: 'PUT',
                  body: JSON.stringify({ patch: [{ op: 'remove', path: arrayPath }] }),
                }
              );

              if (!response.ok) {
                const errorText = await response.text().catch(() => '');
                throw new Error(`Failed to delete at path "${arrayPath}": ${errorText}`);
              }
            }
          }
        });

        await Promise.all(requests);
        // Refetch on the page that still has rows after deletion. If the
        // current page would be empty (deleted everything on it but earlier
        // pages still have rows), step back to the last page that has data.
        await fetchData(
          computeTargetPageAfterDelete(pagination, selectedCount),
          pagination.pageSize,
        );
      } else {
        // Normal mode: bulk delete
        const rowIds = Array.from(selectedRows).map(key => parseInt(key, 10)).filter(id => !isNaN(id));

        const response = await authenticatedFetch(
          `/api/proxy/data-sources/${dataSourceId}/bulk`,
          {
            method: 'POST',
            body: JSON.stringify({ op: 'delete', ids: rowIds }),
          }
        );

        if (!response.ok) {
          throw new Error('Failed to delete selected rows');
        }

        // Refetch with corrected page so the user never lands on an empty
        // page when later pages still hold data (e.g. delete all of page 1
        // while pages 2+ exist → server now returns the old page 2 as page 1).
        await fetchData(
          computeTargetPageAfterDelete(pagination, rowIds.length),
          pagination.pageSize,
        );
      }

      addToast({
        type: 'success',
        title: t('rowsDeletedTitle'),
        message: t('rowsDeletedMessage', { count: selectedCount }),
      });
    } catch (err) {
      console.error('Error deleting rows:', err);
      addToast({
        type: 'error',
        title: t('deleteRowsErrorTitle'),
        message: t('deleteRowsErrorMessage'),
      });
    }
  }, [dataSourceId, jsonPath, pagination, fetchData, setRows, addToast, t]);

  const startAddingRowInline = useCallback(() => {
    setIsAddingRowInline(true);
    setNewRowData({});
    setNewRowPriority(1);
  }, []);

  const cancelAddingRowInline = useCallback(() => {
    setIsAddingRowInline(false);
    setNewRowData({});
    setNewRowPriority(1);
  }, []);

  const handleRowDataChange = useCallback((key: string, value: string) => {
    setNewRowData(prev => ({ ...prev, [key]: value }));
  }, []);

  return {
    // State
    showAddRowModal,
    newRowData,
    newRowPriority,
    isAddingRow,
    isAddingRowInline,
    isDuplicatingRows,
    revealedRowIds,

    // Setters
    setShowAddRowModal,
    setNewRowData,
    setNewRowPriority,

    // Actions
    handleSaveEdit,
    addNewRow,
    deleteSelectedRows,
    duplicateSelectedRows,
    startAddingRowInline,
    cancelAddingRowInline,
    handleRowDataChange,
  };
}
