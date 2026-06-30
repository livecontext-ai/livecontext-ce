'use client';

import { useCallback, useState } from 'react';
import type { DataSourceItemRow, PaginationState } from '../types';
import { getValueAtPath, setValueAtPath } from '../visualHelpers';
import { authenticatedFetch } from '../utils/authenticatedFetch';
import { parseEditValue, parseErrorResponse } from '../utils/dataTableUtils';

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

  // Setters
  setShowAddRowModal: React.Dispatch<React.SetStateAction<boolean>>;
  setNewRowData: React.Dispatch<React.SetStateAction<Record<string, any>>>;
  setNewRowPriority: React.Dispatch<React.SetStateAction<number>>;

  // Actions
  handleSaveEdit: (rowId: number, columnId: string, value: string, arrayIndex?: number | null) => Promise<void>;
  addNewRow: () => Promise<void>;
  deleteSelectedRows: (selectedRows: Set<string>, getRowUniqueKey: (row: DataSourceItemRow) => string) => Promise<void>;
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
 * Hook for managing row CRUD operations in the data table.
 */
export function useRowOperations({
  dataSourceId,
  jsonPath,
  workflowContext,
  rows,
  setRows,
  pagination,
  fetchData,
  addToast,
}: UseRowOperationsParams): UseRowOperationsReturn {
  const [showAddRowModal, setShowAddRowModal] = useState(false);
  const [newRowData, setNewRowData] = useState<Record<string, any>>({});
  const [newRowPriority, setNewRowPriority] = useState(1);
  const [isAddingRow, setIsAddingRow] = useState(false);
  const [isAddingRowInline, setIsAddingRowInline] = useState(false);

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
  ) => {
    try {
      // Clean up columnId
      columnId = columnId.trim().replace(/,$/, '').replace(/^,/, '').replace(/\/$/, '');

      const parsedValue = parseEditValue(value);
      let patch: any[];

      if (columnId === 'priority') {
        patch = [{ op: 'replace', path: 'priority', value: parseInt(value) || 0 }];
      } else if (columnId === 'index' || columnId === 'array_index') {
        // Read-only field
        return;
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
          const errorMessage = await parseErrorResponse(response, 'Failed to save changes');
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

        if (columnId === 'priority') {
          return { ...row, priority: parseInt(value) || 0, updated_at: new Date().toISOString() };
        }

        if (columnId === 'index' || columnId === 'array_index') {
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
    } catch (err) {
      console.error('Error saving edit:', err);
      const errorMessage = err instanceof Error ? err.message : 'Failed to save changes';
      addToast({
        type: 'error',
        title: 'Error Saving',
        message: errorMessage,
      });
    }
  }, [dataSourceId, jsonPath, workflowContext, rows, setRows, addToast]);

  // Save edit for workflow context (storage update)
  // Uses row._outputStorageId directly to avoid unnecessary API calls
  const saveWorkflowEdit = useCallback(async (rowId: number, columnId: string, parsedValue: any) => {
    if (!workflowContext) return;

    // Find the row being edited to get its _outputStorageId
    const targetRow = rows.find(r => r.id === rowId);
    if (!targetRow?._outputStorageId) throw new Error('No output storage ID for this row');

    // Fetch current storage data
    const storageResponse = await authenticatedFetch(
      `${ORCHESTRATOR_URL}/storage/${targetRow._outputStorageId}`
    );
    if (!storageResponse.ok) throw new Error('Failed to fetch storage data');
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
      throw new Error(`Item index ${itemIndex} out of bounds (array length: ${dataArray.length})`);
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
      const errorMessage = await parseErrorResponse(updateResponse, 'Failed to save to storage');
      throw new Error(errorMessage);
    }
  }, [workflowContext, rows]);

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
        title: 'Row Added Successfully',
        message: `New row has been added with priority ${newRowPriority}`,
      });
    } catch (err) {
      console.error('Error adding row:', err);
      addToast({
        type: 'error',
        title: 'Error Adding Row',
        message: 'Failed to add row',
      });
    } finally {
      setIsAddingRow(false);
    }
  }, [dataSourceId, newRowData, newRowPriority, pagination, fetchData, addToast]);

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
        title: 'Rows Deleted Successfully',
        message: `${selectedCount} row(s) have been deleted`,
      });
    } catch (err) {
      console.error('Error deleting rows:', err);
      addToast({
        type: 'error',
        title: 'Error Deleting Rows',
        message: 'Failed to delete selected rows',
      });
    }
  }, [dataSourceId, jsonPath, pagination, fetchData, setRows, addToast]);

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

    // Setters
    setShowAddRowModal,
    setNewRowData,
    setNewRowPriority,

    // Actions
    handleSaveEdit,
    addNewRow,
    deleteSelectedRows,
    startAddingRowInline,
    cancelAddingRowInline,
    handleRowDataChange,
  };
}
