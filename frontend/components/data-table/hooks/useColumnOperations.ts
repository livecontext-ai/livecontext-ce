'use client';

import { useCallback, useState } from 'react';
import type { ColumnDefinition, DataSourceItemRow, PaginationState } from '../types';
import type { ColumnStylePreset } from '../visualHelpers';
import { COLUMN_STYLE_PRESETS } from '../visualHelpers';
import { authenticatedFetch } from '../utils/authenticatedFetch';

export interface UseColumnOperationsParams {
  dataSourceId?: number;
  jsonPath?: string;
  columns: ColumnDefinition[];
  rows: DataSourceItemRow[];
  fetchColumns: (force?: boolean) => Promise<void>;
  fetchData: (page?: number, pageSize?: number) => Promise<void>;
  pagination: PaginationState;
  clearColumnSelection: () => void;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
}

export interface EditColumnSubmission {
  newName?: string;
  display?: Record<string, unknown>;
}

export interface UseColumnOperationsReturn {
  // State
  showAddColumnModal: boolean;
  newColumnName: string;
  isAddingColumn: boolean;
  showDeleteColumnsModal: boolean;
  columnsToDelete: string[];
  selectedColumnStyle: ColumnStylePreset;
  showEditColumnModal: boolean;
  columnToEdit: ColumnDefinition | null;
  isEditingColumn: boolean;

  // Setters
  setShowAddColumnModal: React.Dispatch<React.SetStateAction<boolean>>;
  setNewColumnName: React.Dispatch<React.SetStateAction<string>>;
  setSelectedColumnStyle: React.Dispatch<React.SetStateAction<ColumnStylePreset>>;
  setShowDeleteColumnsModal: React.Dispatch<React.SetStateAction<boolean>>;
  setColumnsToDelete: React.Dispatch<React.SetStateAction<string[]>>;
  setShowEditColumnModal: React.Dispatch<React.SetStateAction<boolean>>;
  setColumnToEdit: React.Dispatch<React.SetStateAction<ColumnDefinition | null>>;

  // Actions
  addNewColumn: () => Promise<void>;
  deleteSelectedColumns: (selectedColumns: Set<string>) => void;
  confirmDeleteColumns: () => Promise<void>;
  openEditColumn: (column: ColumnDefinition) => void;
  saveEditColumn: (submission: EditColumnSubmission) => Promise<void>;
}

/**
 * Columns the user cannot delete or edit. Exported so other table-grid
 * components can hide edit/delete affordances on these without redefining
 * the list and risking drift.
 */
export const FIXED_COLUMNS = ['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'];

/**
 * Maximum number of user-defined columns allowed per DataSource
 */
const MAX_COLUMNS_PER_DATASOURCE = 30;

/**
 * Hook for managing column CRUD operations in the data table.
 */
export function useColumnOperations({
  dataSourceId,
  jsonPath,
  columns,
  rows,
  fetchColumns,
  fetchData,
  pagination,
  clearColumnSelection,
  addToast,
}: UseColumnOperationsParams): UseColumnOperationsReturn {
  const [showAddColumnModal, setShowAddColumnModal] = useState(false);
  const [newColumnName, setNewColumnName] = useState('');
  const [isAddingColumn, setIsAddingColumn] = useState(false);
  const [showEditColumnModal, setShowEditColumnModal] = useState(false);
  const [columnToEdit, setColumnToEdit] = useState<ColumnDefinition | null>(null);
  const [isEditingColumn, setIsEditingColumn] = useState(false);
  const [showDeleteColumnsModal, setShowDeleteColumnsModal] = useState(false);
  const [columnsToDelete, setColumnsToDelete] = useState<string[]>([]);
  const [selectedColumnStyle, setSelectedColumnStyle] = useState<ColumnStylePreset>(COLUMN_STYLE_PRESETS[0]);

  /**
   * Add a new column to the data source
   */
  const addNewColumn = useCallback(async () => {
    const trimmedName = newColumnName.trim();
    if (!trimmedName || !dataSourceId) return;

    // Check if column already exists
    const columnNameLower = trimmedName.toLowerCase();
    const existingColumn = columns.find(col =>
      col.header_name.toLowerCase() === columnNameLower
    );

    if (existingColumn) {
      addToast({
        type: 'error',
        title: 'Column Already Exists',
        message: `A column named "${trimmedName}" already exists. Please choose a different name.`,
      });
      return;
    }

    // Check max columns limit (only user-defined columns, not system columns)
    const userColumnCount = columns.filter(col => col.field.startsWith('data.')).length;
    if (userColumnCount >= MAX_COLUMNS_PER_DATASOURCE) {
      addToast({
        type: 'error',
        title: 'Maximum Columns Reached',
        message: `You can only have ${MAX_COLUMNS_PER_DATASOURCE} columns per table. Please delete some columns before adding new ones.`,
      });
      return;
    }

    try {
      setIsAddingColumn(true);

      // Override display.label with user's custom column name
      const displayConfig = selectedColumnStyle?.display
        ? { ...selectedColumnStyle.display, label: trimmedName }
        : { label: trimmedName };

      const response = await authenticatedFetch(`/api/proxy/data-sources/${dataSourceId}/columns`, {
        method: 'POST',
        body: JSON.stringify({
          name: trimmedName,
          type: selectedColumnStyle?.visualType,
          structure: selectedColumnStyle?.structure,
          display: displayConfig,
          defaultValue: selectedColumnStyle?.defaultValue ?? '',
        }),
      });

      if (!response.ok) {
        throw new Error('Failed to add column');
      }

      // Reset form
      setNewColumnName('');
      setSelectedColumnStyle(COLUMN_STYLE_PRESETS[0]);
      setShowAddColumnModal(false);

      // Reload columns and data (force=true to bypass duplicate check)
      await fetchColumns(true);
      await fetchData(pagination.currentPage, pagination.pageSize);

      addToast({
        type: 'success',
        title: 'Column Added Successfully',
        message: `Column "${trimmedName}" (${selectedColumnStyle?.label || selectedColumnStyle?.visualType}) has been added`,
      });
    } catch (err) {
      console.error('Error adding column:', err);
      addToast({
        type: 'error',
        title: 'Error Adding Column',
        message: 'Failed to add column',
      });
    } finally {
      setIsAddingColumn(false);
    }
  }, [dataSourceId, newColumnName, selectedColumnStyle, columns, fetchColumns, fetchData, pagination, addToast]);

  /**
   * Open delete confirmation modal for selected columns
   */
  const deleteSelectedColumns = useCallback((selectedColumns: Set<string>) => {
    if (selectedColumns.size === 0 || !dataSourceId) return;

    // Filter out fixed columns
    const deletableColumns = Array.from(selectedColumns).filter(field =>
      !FIXED_COLUMNS.includes(field)
    );

    if (deletableColumns.length === 0) {
      addToast({
        type: 'error',
        title: 'Cannot Delete Fixed Columns',
        message: 'Fixed columns (ID, Priority, Created At) cannot be deleted',
      });
      return;
    }

    setColumnsToDelete(deletableColumns);
    setShowDeleteColumnsModal(true);
  }, [dataSourceId, addToast]);

  /**
   * Confirm and execute column deletion
   */
  const confirmDeleteColumns = useCallback(async () => {
    if (!dataSourceId || columnsToDelete.length === 0) return;

    try {
      if (jsonPath) {
        // Nested mode: delete JSON keys from all rows
        for (const field of columnsToDelete) {
          const jsonKey = field.startsWith('data.') ? field.replace('data.', '') : field;
          const fullPath = `${jsonPath}.${jsonKey}`;

          const allRowIds = rows.map(row => row.id);

          if (allRowIds.length > 0) {
            const bulkResponse = await authenticatedFetch(
              `/api/proxy/data-sources/${dataSourceId}/bulk`,
              {
                method: 'POST',
                body: JSON.stringify({
                  op: 'patch',
                  ids: allRowIds,
                  patches: [{ op: 'remove', path: fullPath }],
                }),
              }
            );

            if (!bulkResponse.ok) {
              const errorText = await bulkResponse.text().catch(() => '');
              throw new Error(`Failed to delete column ${fullPath}: ${errorText}`);
            }
          }
        }

        await fetchColumns(true);
      } else {
        // Normal mode: delete from mapping_spec
        const requests = columnsToDelete.map(async (field) => {
          const jsonKey = field.startsWith('data.') ? field.replace('data.', '') : field;
          const res = await authenticatedFetch(
            `/api/proxy/data-sources/${dataSourceId}/columns`,
            {
              method: 'PATCH',
              body: JSON.stringify({ op: 'drop', key: jsonKey }),
            }
          );

          if (!res.ok) {
            const text = await res.text().catch(() => '');
            throw new Error(`Failed to delete column ${jsonKey}: ${text}`);
          }
        });

        await Promise.all(requests);
        await fetchColumns(true);
      }

      // Reset state
      clearColumnSelection();
      setShowDeleteColumnsModal(false);
      const deletedCount = columnsToDelete.length;
      setColumnsToDelete([]);

      // Reload data
      await fetchData(pagination.currentPage, pagination.pageSize);

      addToast({
        type: 'success',
        title: 'Columns Deleted Successfully',
        message: `${deletedCount} column(s) have been deleted`,
      });
    } catch (err) {
      console.error('Error deleting columns:', err);
      addToast({
        type: 'error',
        title: 'Error Deleting Columns',
        message: 'Failed to delete selected columns',
      });
    }
  }, [dataSourceId, jsonPath, columnsToDelete, rows, fetchColumns, fetchData, pagination, clearColumnSelection, addToast]);

  /**
   * Open the edit modal for a single column. The modal initialises its state
   * from the column's current displayConfig.
   */
  const openEditColumn = useCallback((column: ColumnDefinition) => {
    if (FIXED_COLUMNS.includes(column.field)) return;
    setColumnToEdit(column);
    setShowEditColumnModal(true);
  }, []);

  /**
   * Persist a column edit: send a single atomic PATCH carrying both the
   * (optional) rename and the new display config. The backend handles both
   * in one transaction so a partial failure rolls back the rename.
   *
   * Submission shape:
   *   { newName?: string, display?: Record<string, unknown> }
   * If both are absent, this is a no-op (the modal would not have called us).
   */
  const saveEditColumn = useCallback(async (submission: EditColumnSubmission) => {
    if (!dataSourceId || !columnToEdit) return;
    if (!submission.newName && !submission.display) return;

    const jsonKey = columnToEdit.field.startsWith('data.')
      ? columnToEdit.field.replace('data.', '')
      : columnToEdit.field;

    try {
      setIsEditingColumn(true);

      const body: Record<string, unknown> = {
        op: 'update_display',
        key: jsonKey,
      };
      if (submission.newName) body.new_key = submission.newName;
      if (submission.display) body.display = submission.display;

      const response = await authenticatedFetch(
        `/api/proxy/data-sources/${dataSourceId}/columns`,
        { method: 'PATCH', body: JSON.stringify(body) }
      );

      if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(`Failed to edit column: ${text}`);
      }

      // Reset state and refresh
      setShowEditColumnModal(false);
      setColumnToEdit(null);
      await fetchColumns(true);
      await fetchData(pagination.currentPage, pagination.pageSize);

      addToast({
        type: 'success',
        title: 'Column updated',
        message: submission.newName
          ? `Column renamed to "${submission.newName}".`
          : `Column display updated.`,
      });
    } catch (err) {
      console.error('Error editing column:', err);
      addToast({
        type: 'error',
        title: 'Error updating column',
        message: err instanceof Error ? err.message : 'Failed to update column',
      });
    } finally {
      setIsEditingColumn(false);
    }
  }, [dataSourceId, columnToEdit, fetchColumns, fetchData, pagination, addToast]);

  return {
    // State
    showAddColumnModal,
    newColumnName,
    isAddingColumn,
    showDeleteColumnsModal,
    columnsToDelete,
    selectedColumnStyle,
    showEditColumnModal,
    columnToEdit,
    isEditingColumn,

    // Setters
    setShowAddColumnModal,
    setNewColumnName,
    setSelectedColumnStyle,
    setShowDeleteColumnsModal,
    setColumnsToDelete,
    setShowEditColumnModal,
    setColumnToEdit,

    // Actions
    addNewColumn,
    deleteSelectedColumns,
    confirmDeleteColumns,
    openEditColumn,
    saveEditColumn,
  };
}
