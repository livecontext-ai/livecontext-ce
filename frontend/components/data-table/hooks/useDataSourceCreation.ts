'use client';

import { useCallback, useState } from 'react';
import type { DataSourceItemRow } from '../types';
import { authenticatedFetch } from '../utils/authenticatedFetch';

export interface UseDataSourceCreationParams {
  dataSourceId?: number;
  displayRows: DataSourceItemRow[];
  selectedRows: Set<string>;
  selectedColumns: Set<string>;
  getRowUniqueKey: (row: DataSourceItemRow) => string;
  clearColumnSelection: () => void;
  setSelectedRows: React.Dispatch<React.SetStateAction<Set<string>>>;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
}

export interface UseDataSourceCreationReturn {
  // State
  showCreateDataSourceModal: boolean;
  newDataSourceName: string;
  newDataSourceDescription: string;
  isCreatingDataSource: boolean;

  // Setters
  setShowCreateDataSourceModal: React.Dispatch<React.SetStateAction<boolean>>;
  setNewDataSourceName: React.Dispatch<React.SetStateAction<string>>;
  setNewDataSourceDescription: React.Dispatch<React.SetStateAction<string>>;

  // Actions
  createDataSourceFromSelection: () => Promise<void>;
}

/**
 * Hook for creating a new DataSource from selected rows/columns.
 */
export function useDataSourceCreation({
  displayRows,
  selectedRows,
  selectedColumns,
  getRowUniqueKey,
  clearColumnSelection,
  setSelectedRows,
  addToast,
}: UseDataSourceCreationParams): UseDataSourceCreationReturn {
  const [showCreateDataSourceModal, setShowCreateDataSourceModal] = useState(false);
  const [newDataSourceName, setNewDataSourceName] = useState('');
  const [newDataSourceDescription, setNewDataSourceDescription] = useState('');
  const [isCreatingDataSource, setIsCreatingDataSource] = useState(false);

  /**
   * Create a new DataSource from the current selection
   */
  const createDataSourceFromSelection = useCallback(async () => {
    if (selectedRows.size === 0 && selectedColumns.size === 0) return;

    try {
      setIsCreatingDataSource(true);

      // Filter out parent/group rows - keep only normal data rows
      const normalRows = displayRows.filter((item): item is DataSourceItemRow =>
        !('type' in item && ((item as any).type === 'parent' || (item as any).type === 'group'))
      );

      // Get selected rows or all rows if only columns are selected
      const selectedData = selectedRows.size > 0
        ? normalRows.filter(row => selectedRows.has(getRowUniqueKey(row)))
        : normalRows;

      // Extract data from rows
      let filteredData = selectedData.map(row => row.data);

      // If columns are selected, filter to only include those columns
      if (selectedColumns.size > 0) {
        filteredData = selectedData.map(row => {
          const filteredRowData: Record<string, any> = {};
          selectedColumns.forEach(columnField => {
            const cleanField = columnField.startsWith('data.')
              ? columnField.replace('data.', '')
              : columnField;
            if (row.data[cleanField] !== undefined) {
              filteredRowData[cleanField] = row.data[cleanField];
            }
          });
          return filteredRowData;
        });
      }

      // Build mapping spec from selected columns
      let mappingSpec: Record<string, string> = {};
      if (selectedColumns.size > 0) {
        selectedColumns.forEach(columnField => {
          const cleanField = columnField.startsWith('data.')
            ? columnField.replace('data.', '')
            : columnField;
          mappingSpec[cleanField] = `data.${cleanField}`;
        });
      }

      const dataSourceConfig = {
        name: newDataSourceName,
        description: newDataSourceDescription,
        sourceConfig: {},
        data: filteredData,
        createdBy: 'user',
        mappingSpec,
      };

      const response = await authenticatedFetch('/api/proxy/data-sources', {
        method: 'POST',
        body: JSON.stringify(dataSourceConfig),
      });

      if (!response.ok) {
        throw new Error('Failed to create data source from selection');
      }

      const createdName = newDataSourceName;

      // Reset form and selections
      setNewDataSourceName('');
      setNewDataSourceDescription('');
      setShowCreateDataSourceModal(false);
      setSelectedRows(new Set());
      clearColumnSelection();

      addToast({
        type: 'success',
        title: 'DataSource Created Successfully',
        message: `DataSource "${createdName}" has been created`,
      });
    } catch (err) {
      console.error('Error creating data source:', err);
      addToast({
        type: 'error',
        title: 'Error Creating DataSource',
        message: 'Failed to create data source from selection',
      });
    } finally {
      setIsCreatingDataSource(false);
    }
  }, [
    displayRows,
    selectedRows,
    selectedColumns,
    getRowUniqueKey,
    newDataSourceName,
    newDataSourceDescription,
    clearColumnSelection,
    setSelectedRows,
    addToast,
  ]);

  return {
    // State
    showCreateDataSourceModal,
    newDataSourceName,
    newDataSourceDescription,
    isCreatingDataSource,

    // Setters
    setShowCreateDataSourceModal,
    setNewDataSourceName,
    setNewDataSourceDescription,

    // Actions
    createDataSourceFromSelection,
  };
}
