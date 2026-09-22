'use client';

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useToast } from '@/components/Toast';
import type { ColumnDefinition, DataSourceItemRow, DataTableProps } from '@/components/data-table/types';
import { createViewConfig, getFixedColumns, getRowLevelExportFields, type ViewConfig } from './viewConfig';
import {
  useTableSelection,
  useTableExport,
  usePagination,
  useColumnManagement,
  useDataFetching,
  useRowOperations,
  useColumnOperations,
  useSortingAndFiltering,
  useDragAndDrop,
  useDataSourceCreation,
  useCellEditing,
} from './hooks';

export type UseDataTableControllerParams = Pick<DataTableProps, 'dataSourceId' | 'jsonPath' | 'workflowContext' | 'showIdColumn' | 'readOnly' | 'snapshotData' | 'serverFilters'>;

export type DataTableController = ReturnType<typeof useDataTableController>;

export function useDataTableController({ dataSourceId, jsonPath, workflowContext, showIdColumn = false, readOnly = false, snapshotData, serverFilters }: UseDataTableControllerParams) {
  const isSnapshot = !!snapshotData;
  const { toasts, addToast, removeToast } = useToast();

  // Wait for auth before firing requests. The token itself comes from apiClient's
  // single provider (installed once in smart-providers.tsx): this hook no longer
  // installs a module-global getter of its own, which AGENTS.md forbids and which
  // outlived every component that set it.
  const { isLoading: isAuthLoading } = useAuthGuard();

  // Create view config - updates when jsonPath changes for nested navigation
  const viewConfig = useMemo(
    () => createViewConfig(workflowContext, showIdColumn, jsonPath, readOnly, isSnapshot),
    [workflowContext, showIdColumn, jsonPath, readOnly, isSnapshot]
  );

  // The fields this view renders as fixed lanes. Memoized: it feeds a callback dep array, and a
  // fresh array each render would quietly cost that callback its referential stability.
  const fixedColumnFields = useMemo(() => getFixedColumns(viewConfig), [viewConfig]);
  const rowLevelFields = useMemo(() => getRowLevelExportFields(viewConfig), [viewConfig]);

  // Use pagination hook
  const { pagination, setPagination } = usePagination({ initialPageSize: 20 });

  // Use column management hook
  const {
    columns,
    columnOrder,
    draggedColumn,
    dragOverColumn,
    dragPosition,
    setColumns,
    setColumnOrder,
    setDraggedColumn,
    setDragOverColumn,
    setDragPosition,
    getAllColumns,
    getUniqueColumns,
    reorderColumns,
    resetDragState,
  } = useColumnManagement({ viewConfig, workflowContext });

  // Use data fetching hook - pass setPagination so fetches update the controller's pagination state
  const {
    rows,
    columns: fetchedColumns,
    tableLoading,
    loadingColumns,
    error,
    setRows,
    fetchColumns,
    fetchData: fetchDataBase,
  } = useDataFetching({
    dataSourceId,
    jsonPath,
    workflowContext,
    showIdColumn,
    addToast,
    setPagination,
    // The order the fetch reads back from the server lands in the SAME state the
    // grid renders from. It used to be a private state of useDataFetching, so
    // every saved order was loaded and then dropped on the floor.
    setColumnOrder,
    snapshotData,
  });

  // Sync columns from data fetching to column management
  // Always sync, even when empty, to handle navigation to paths with no data
  useEffect(() => {
    setColumns(fetchedColumns);
  }, [fetchedColumns, setColumns]);

  // Snapshot mode seeds columnOrder upfront; fetch paths normally call
  // initializeColumnOrder themselves, so we only seed here when snapshot.
  useEffect(() => {
    if (!isSnapshot || !snapshotData) return;
    if (snapshotData.columnOrder && snapshotData.columnOrder.length > 0) {
      setColumnOrder(snapshotData.columnOrder);
    } else {
      setColumnOrder(snapshotData.columns.map((c, i) => ({ field: c.field, order: i })));
    }
  }, [isSnapshot, snapshotData, setColumnOrder]);

  // Use selection hook
  const {
    selectedRows,
    selectedColumns,
    setSelectedRows,
    getRowUniqueKey,
    toggleRowSelection,
    selectAllRows: selectAllRowsBase,
    clearSelection: clearSelectionBase,
    toggleColumnSelection,
    clearColumnSelection,
  } = useTableSelection({ jsonPath });

  // Wrapper functions to provide rows to selection hook
  const selectAllRows = useCallback(() => {
    selectAllRowsBase(rows);
  }, [selectAllRowsBase, rows]);

  const clearSelection = useCallback(() => {
    clearSelectionBase(rows);
  }, [clearSelectionBase, rows]);

  // Use sorting and filtering hook
  const {
    sortConfig,
    searchQuery,
    showColumnFilters,
    columnFilters,
    filteredRows,
    setSortConfig,
    setSearchQuery,
    setShowColumnFilters,
    setColumnFilters,
    handleSort,
    sortData,
  } = useSortingAndFiltering({
    rows,
    fetchData: (page, pageSize, sort) => fetchDataBase(page, pageSize, sort, null, serverFilters),
    pagination,
    dataSourceId,
  });

  // Use cell editing hook
  const {
    hoveredCell,
    editingCellKey,
    progressTempValues,
    setHoveredCell,
    setEditingCellKey,
    setProgressTempValues,
    makeCellKey,
    getFieldPath,
  } = useCellEditing();

  // Use row operations hook
  const {
    showAddRowModal,
    newRowData,
    newRowPriority,
    isAddingRow,
    isAddingRowInline,
    isDuplicatingRows,
    revealedRowIds,
    setShowAddRowModal,
    setNewRowData,
    setNewRowPriority,
    handleSaveEdit,
    addNewRow,
    deleteSelectedRows: deleteSelectedRowsBase,
    duplicateSelectedRows: duplicateSelectedRowsBase,
    startAddingRowInline,
    cancelAddingRowInline,
    handleRowDataChange,
  } = useRowOperations({
    dataSourceId,
    jsonPath,
    workflowContext,
    rows,
    setRows,
    pagination,
    sortConfig,
    columns,
    rowLevelFields,
    fetchData: (page, pageSize) => fetchDataBase(page, pageSize, sortConfig, null, serverFilters),
    addToast,
  });

  // Wrapper for deleteSelectedRows
  const deleteSelectedRows = useCallback(async () => {
    await deleteSelectedRowsBase(selectedRows, getRowUniqueKey);
    setSelectedRows(new Set());
  }, [deleteSelectedRowsBase, selectedRows, getRowUniqueKey, setSelectedRows]);

  // Wrapper for duplicateSelectedRows. The selection is dropped only when EVERY selected row was
  // copied: leaving the originals ticked next to freshly highlighted copies reads as if the copies
  // were the selection. A partial result keeps it, because the rows that did not get copied are
  // exactly the ones the user needs still ticked to try again.
  const duplicateSelectedRows = useCallback(async () => {
    const written = await duplicateSelectedRowsBase(selectedRows, getRowUniqueKey);
    if (written === selectedRows.size) setSelectedRows(new Set());
  }, [duplicateSelectedRowsBase, selectedRows, getRowUniqueKey, setSelectedRows]);

  // Use column operations hook
  const {
    showAddColumnModal,
    newColumnName,
    isAddingColumn,
    showDeleteColumnsModal,
    columnsToDelete,
    selectedColumnStyle,
    showEditColumnModal,
    columnToEdit,
    isEditingColumn,
    revealedColumnField,
    setShowAddColumnModal,
    setNewColumnName,
    setSelectedColumnStyle,
    setShowDeleteColumnsModal,
    setColumnsToDelete,
    setShowEditColumnModal,
    setColumnToEdit,
    addNewColumn,
    deleteSelectedColumns: deleteSelectedColumnsBase,
    confirmDeleteColumns,
    openEditColumn,
    saveEditColumn,
  } = useColumnOperations({
    dataSourceId,
    jsonPath,
    columns,
    rows,
    fetchColumns,
    fetchData: (page, pageSize) => fetchDataBase(page, pageSize, sortConfig, null, serverFilters),
    pagination,
    clearColumnSelection,
    addToast,
  });

  // Wrapper for deleteSelectedColumns
  const deleteSelectedColumns = useCallback(() => {
    deleteSelectedColumnsBase(selectedColumns);
  }, [deleteSelectedColumnsBase, selectedColumns]);

  // Use drag and drop hook
  const {
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  } = useDragAndDrop({
    dataSourceId,
    columnOrder,
    reorderColumns,
    resetDragState,
    setDraggedColumn,
    setDragOverColumn,
    setDragPosition,
    draggedColumn,
    addToast,
  });

  // Compute displayRows from filteredRows (apply sorting, filtering, and array grouping)
  const displayRows = useMemo(() => {
    // Apply sorting first
    let sortedRows = filteredRows;
    if (!(dataSourceId && sortConfig)) {
      // Apply local sorting only if not using server-side sorting
      sortedRows = sortData(filteredRows);
    }

    // If in array mode (jsonPath with array_index), group rows by parent ID
    const isArrayMode = jsonPath && sortedRows.some(row => row.data?.array_index !== undefined);

    if (isArrayMode) {
      // Group rows by parent ID (row.id)
      const rowsByParentId = new Map<number, DataSourceItemRow[]>();

      sortedRows.forEach(row => {
        const parentId = row.id;
        if (!rowsByParentId.has(parentId)) {
          rowsByParentId.set(parentId, []);
        }
        rowsByParentId.get(parentId)!.push(row);
      });

      // Sort rows within each group by array_index
      rowsByParentId.forEach((rows) => {
        rows.sort((a, b) => {
          const indexA = a.data?.array_index ?? 0;
          const indexB = b.data?.array_index ?? 0;
          return indexA - indexB;
        });
      });

      // Create hierarchical structure: parent row header + subRows
      const result: (DataSourceItemRow | { type: 'parent'; parentId: number; subRows: DataSourceItemRow[] })[] = [];

      rowsByParentId.forEach((subRows, parentId) => {
        // Add parent group header
        result.push({ type: 'parent', parentId, subRows });
        // Add all subRows
        result.push(...subRows);
      });

      return result;
    }

    // Otherwise, return rows as-is
    return sortedRows;
  }, [filteredRows, sortData, sortConfig, dataSourceId, jsonPath]);

  // Use data source creation hook
  const {
    showCreateDataSourceModal,
    newDataSourceName,
    newDataSourceDescription,
    isCreatingDataSource,
    setShowCreateDataSourceModal,
    setNewDataSourceName,
    setNewDataSourceDescription,
    createDataSourceFromSelection,
  } = useDataSourceCreation({
    dataSourceId,
    displayRows: displayRows as any,
    columns,
    selectedRows,
    selectedColumns,
    getRowUniqueKey,
    clearColumnSelection,
    setSelectedRows,
    addToast,
  });

  // Use export hook
  const {
    exportLoading,
    selectedExportFormat,
    setSelectedExportFormat,
    getDynamicColumns,
    handleExportCSV,
    handleExportJSON,
    handleExportExcel,
    handleExportFull,
  } = useTableExport({
    dataSourceId,
    rows,
    columns,
    selectedRows,
    selectedColumns,
    getRowUniqueKey,
    searchQuery,
    sortConfig,
    pagination,
    addToast,
    getUniqueColumns,
    fixedColumnFields,
    rowLevelFields,
  });

  // Track previous config to detect changes and reset state
  const prevConfigRef = useRef<string | null>(null);
  // Keep a ref of pageSize to avoid re-triggering the initial load effect on page size changes
  const pageSizeRef = useRef(pagination.pageSize);
  pageSizeRef.current = pagination.pageSize;

  // Reset columns and rows when jsonPath or other key config changes
  useEffect(() => {
    const stepAlias = workflowContext?.stepAlias;
    const stepId = workflowContext?.stepId;
    const currentConfig = JSON.stringify({ dataSourceId, jsonPath, stepAlias, stepId });

    // On mount, just set the config without resetting
    if (prevConfigRef.current === null) {
      prevConfigRef.current = currentConfig;
      return;
    }

    if (prevConfigRef.current !== currentConfig) {
      // Config has changed - reset state immediately
      setColumns([]);
      setRows([]);
      setColumnOrder([]);
      prevConfigRef.current = currentConfig;
    }
  }, [dataSourceId, jsonPath, workflowContext?.stepAlias, workflowContext?.stepId, setColumns, setRows, setColumnOrder]);

  // Initial data load - only re-runs when the data source/path/workflow config changes.
  // Page size changes are handled by handlePageSizeChange directly.
  // Using pageSizeRef to avoid adding pagination.pageSize as a dependency (which would
  // cause this effect to re-fire and reset to page 1, breaking pagination).
  useEffect(() => {
    if (isAuthLoading) {
      return;
    }
    // Snapshot mode - rows/columns seeded at init; no HTTP fetch.
    if (isSnapshot) {
      return;
    }

    fetchColumns();
    fetchDataBase(1, pageSizeRef.current, sortConfig, null, serverFilters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAuthLoading, dataSourceId, jsonPath, workflowContext?.workflowId, workflowContext?.runId, workflowContext?.stepAlias, isSnapshot]);

  // Refetch from page 1 when server-side filters change. Total count and page
  // count shift with the filtered set, so staying on the previous page would
  // often land on an empty page (or skip past valid filtered rows).
  // We track filter values via primitives so the effect doesn't fire on object
  // identity changes when the caller passes a new object with the same values.
  const filterStatus = serverFilters?.status ?? null;
  const filterEpoch = serverFilters?.epoch ?? null;
  const isInitialFilterRef = useRef(true);
  useEffect(() => {
    if (isAuthLoading || isSnapshot) return;
    // Skip the first run - the initial-load effect above already fired the
    // fetch with these filter values. Without this guard, every mount would
    // double-fetch.
    if (isInitialFilterRef.current) {
      isInitialFilterRef.current = false;
      return;
    }
    fetchDataBase(1, pageSizeRef.current, sortConfig, null, serverFilters);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterStatus, filterEpoch]);

  // Pagination handlers
  const handlePageChange = useCallback((page: number) => {
    fetchDataBase(page, pagination.pageSize, sortConfig, null, serverFilters);
  }, [fetchDataBase, pagination.pageSize, sortConfig, serverFilters]);

  const handlePageSizeChange = useCallback((pageSize: number) => {
    setPagination(prev => ({ ...prev, pageSize }));
    fetchDataBase(1, pageSize, sortConfig, null, serverFilters);
  }, [fetchDataBase, sortConfig, setPagination, serverFilters]);

  // Infinite-scroll "load more": fetch the next page and append to the current rows.
  // Guards against double-fire while a fetch is in-flight or when the server reports no
  // further pages. Consumers wire this to a viewport sentinel (see LoadOlderSentinel).
  const loadMore = useCallback(() => {
    if (tableLoading || !pagination.hasMore) return;
    const nextPage = pagination.currentPage + 1;
    fetchDataBase(nextPage, pagination.pageSize, sortConfig, null, serverFilters, true);
  }, [tableLoading, pagination.hasMore, pagination.currentPage, pagination.pageSize, fetchDataBase, sortConfig, serverFilters]);

  return {
    // Row operations
    addNewRow,
    cancelAddingRowInline,
    deleteSelectedRows,
    duplicateSelectedRows,
    isDuplicatingRows,
    revealedRowIds,
    handleRowDataChange,
    handleSaveEdit,
    isAddingRow,
    isAddingRowInline,
    newRowData,
    newRowPriority,
    rows,
    setNewRowData,
    setNewRowPriority,
    setShowAddRowModal,
    showAddRowModal,
    startAddingRowInline,

    // Column operations
    addNewColumn,
    columns,
    columnsToDelete,
    confirmDeleteColumns,
    deleteSelectedColumns,
    isAddingColumn,
    loadingColumns,
    newColumnName,
    selectedColumnStyle,
    setColumnsToDelete,
    setNewColumnName,
    setSelectedColumnStyle,
    setShowAddColumnModal,
    setShowDeleteColumnsModal,
    showAddColumnModal,
    showDeleteColumnsModal,
    // Edit column (per-column editor invoked from <th> pencil icon)
    columnToEdit,
    isEditingColumn,
    openEditColumn,
    saveEditColumn,
    setColumnToEdit,
    setShowEditColumnModal,
    showEditColumnModal,
    revealedColumnField,

    // Selection
    clearColumnSelection,
    clearSelection,
    getRowUniqueKey,
    selectAllRows,
    selectedColumns,
    selectedRows,
    setSelectedRows,
    toggleColumnSelection,
    toggleRowSelection,

    // Sorting and filtering
    columnFilters,
    displayRows,
    handleSort,
    searchQuery,
    setColumnFilters,
    setSearchQuery,
    setShowColumnFilters,
    showColumnFilters,
    sortConfig,

    // Pagination
    handlePageChange,
    handlePageSizeChange,
    loadMore,
    pagination,

    // Drag and drop
    draggedColumn,
    dragOverColumn,
    dragPosition,
    handleDragLeave,
    handleDragOver,
    handleDragStart,
    handleDrop,

    // Export
    exportLoading,
    getDynamicColumns,
    handleExportCSV,
    handleExportExcel,
    handleExportFull,
    handleExportJSON,
    selectedExportFormat,
    setSelectedExportFormat,

    // Data source creation
    createDataSourceFromSelection,
    isCreatingDataSource,
    newDataSourceDescription,
    newDataSourceName,
    setNewDataSourceDescription,
    setNewDataSourceName,
    setShowCreateDataSourceModal,
    showCreateDataSourceModal,

    // Cell editing
    editingCellKey,
    getFieldPath,
    hoveredCell,
    makeCellKey,
    progressTempValues,
    setEditingCellKey,
    setHoveredCell,
    setProgressTempValues,

    // View and columns helpers
    getAllColumns,
    getUniqueColumns,
    viewConfig,

    // Loading and errors
    tableLoading,
    error,

    // Toast
    removeToast,
    toasts,

    // Readonly
    readOnly,
  };
}
