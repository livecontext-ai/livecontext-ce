// @vitest-environment jsdom
/**
 * What happens to the SELECTION when a duplicate finishes.
 *
 * This is the only user-visible decision the controller makes about duplicating, and it cuts both
 * ways: leaving the originals ticked next to freshly highlighted copies reads as if the copies were
 * the selection, while clearing after a partial result throws away the ticks on exactly the rows
 * that still need copying.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

const duplicateSelectedRows = vi.fn();
const setSelectedRows = vi.fn();
let selectedRows = new Set<string>();

vi.mock('@/components/data-table/hooks', () => ({
  useTableSelection: () => ({
    selectedRows,
    selectedColumns: new Set(),
    setSelectedRows,
    getRowUniqueKey: (r: { id?: unknown }) => String(r?.id ?? ''),
    toggleRowSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    clearColumnSelection: vi.fn(),
  }),
  useTableExport: () => ({
    exportLoading: false, selectedExportFormat: 'csv', setSelectedExportFormat: vi.fn(),
    getDynamicColumns: vi.fn(), handleExportCSV: vi.fn(), handleExportJSON: vi.fn(),
    handleExportExcel: vi.fn(), handleExportFull: vi.fn(),
  }),
  usePagination: () => ({
    pagination: { currentPage: 1, pageSize: 20, totalItems: 0, totalPages: 0, nextCursor: null, hasMore: false },
    setPagination: vi.fn(), setCurrentPage: vi.fn(), setPageSize: vi.fn(),
    resetPagination: vi.fn(), updatePaginationFromResponse: vi.fn(),
  }),
  useColumnManagement: () => ({
    columns: [], columnOrder: [], draggedColumn: null, dragOverColumn: null, dragPosition: null,
    setColumns: vi.fn(), setColumnOrder: vi.fn(), setDraggedColumn: vi.fn(),
    setDragOverColumn: vi.fn(), setDragPosition: vi.fn(),
    getAllColumns: () => [], getUniqueColumns: () => [],
    reorderColumns: vi.fn(), resetDragState: vi.fn(),
  }),
  useDataFetching: () => ({
    rows: [], columns: [], tableLoading: false, loadingColumns: false, error: null,
    backendColumns: null, nodeType: null,
    setRows: vi.fn(), setColumns: vi.fn(), setError: vi.fn(),
    fetchColumns: vi.fn(), fetchData: vi.fn().mockResolvedValue(undefined),
  }),
  useRowOperations: () => ({
    showAddRowModal: false, newRowData: {}, newRowPriority: 1,
    isAddingRow: false, isAddingRowInline: false, isDuplicatingRows: false,
    revealedRowIds: new Set<number>(),
    setShowAddRowModal: vi.fn(), setNewRowData: vi.fn(), setNewRowPriority: vi.fn(),
    handleSaveEdit: vi.fn(), addNewRow: vi.fn(),
    deleteSelectedRows: vi.fn(), duplicateSelectedRows,
    startAddingRowInline: vi.fn(), cancelAddingRowInline: vi.fn(), handleRowDataChange: vi.fn(),
  }),
  useColumnOperations: () => ({
    showAddColumnModal: false, newColumnName: '', isAddingColumn: false,
    showDeleteColumnsModal: false, columnsToDelete: [], selectedColumnStyle: null,
    showEditColumnModal: false, columnToEdit: null, isEditingColumn: false,
    revealedColumnField: null,
    setShowAddColumnModal: vi.fn(), setNewColumnName: vi.fn(),
    setSelectedColumnStyle: vi.fn(), setShowDeleteColumnsModal: vi.fn(),
    setColumnsToDelete: vi.fn(), setShowEditColumnModal: vi.fn(), setColumnToEdit: vi.fn(),
    addNewColumn: vi.fn(), deleteSelectedColumns: vi.fn(), confirmDeleteColumns: vi.fn(),
    openEditColumn: vi.fn(), saveEditColumn: vi.fn(),
  }),
  useSortingAndFiltering: () => ({
    sortConfig: null, searchQuery: '', showColumnFilters: false, columnFilters: {},
    filteredRows: [],
    setSortConfig: vi.fn(), setSearchQuery: vi.fn(),
    setShowColumnFilters: vi.fn(), setColumnFilters: vi.fn(),
    handleSort: vi.fn(), sortData: (r: unknown[]) => r,
  }),
  useDragAndDrop: () => ({
    handleDragStart: vi.fn(), handleDragOver: vi.fn(), handleDragLeave: vi.fn(), handleDrop: vi.fn(),
  }),
  useDataSourceCreation: () => ({
    showCreateDataSourceModal: false, newDataSourceName: '', newDataSourceDescription: '',
    isCreatingDataSource: false,
    setShowCreateDataSourceModal: vi.fn(), setNewDataSourceName: vi.fn(),
    setNewDataSourceDescription: vi.fn(), createDataSourceFromSelection: vi.fn(),
  }),
  useCellEditing: () => ({
    hoveredCell: null, editingCellKey: null, progressTempValues: {},
    setHoveredCell: vi.fn(), setEditingCellKey: vi.fn(), setProgressTempValues: vi.fn(),
    makeCellKey: vi.fn(), getFieldPath: (f: string) => f,
  }),
}));

vi.mock('@/components/Toast', () => ({
  useToast: () => ({ toasts: [], addToast: vi.fn(), removeToast: vi.fn() }),
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ getAccessTokenSilently: async () => '', isLoading: false }),
}));

import { useDataTableController } from '../useDataTableController';

const run = async () => {
  const { result } = renderHook(() => useDataTableController({ dataSourceId: 42 }));
  await act(async () => {
    await result.current.duplicateSelectedRows();
  });
};

beforeEach(() => {
  duplicateSelectedRows.mockReset();
  setSelectedRows.mockReset();
  selectedRows = new Set(['1', '2', '3']);
});

describe('useDataTableController - the selection after a duplicate', () => {
  it('clears it when every selected row was copied', async () => {
    duplicateSelectedRows.mockResolvedValue(3);

    await run();

    expect(setSelectedRows).toHaveBeenCalledWith(new Set());
  });

  it('keeps it on a partial result, so the rows that failed are still ticked to retry', async () => {
    duplicateSelectedRows.mockResolvedValue(2);

    await run();

    expect(setSelectedRows).not.toHaveBeenCalled();
  });

  it('keeps it when nothing was written at all', async () => {
    duplicateSelectedRows.mockResolvedValue(0);

    await run();

    expect(setSelectedRows).not.toHaveBeenCalled();
  });

  it('passes the selection and the row-key function through to the hook', async () => {
    duplicateSelectedRows.mockResolvedValue(3);

    await run();

    expect(duplicateSelectedRows).toHaveBeenCalledWith(new Set(['1', '2', '3']), expect.any(Function));
  });
});
