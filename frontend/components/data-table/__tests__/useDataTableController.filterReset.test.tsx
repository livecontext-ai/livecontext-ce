// @vitest-environment jsdom
/**
 * Regression tests for the {@code serverFilters}-change effect inside
 * {@link useDataTableController}. Before the fix the WorkflowStepTable
 * filtered status/epoch client-side via {@code rowFilter}, leaving totals
 * and pagination stale. The fix forwards filters server-side AND resets
 * pagination back to page 1 on every filter change - otherwise the user
 * could land on an empty page when the filtered set has fewer pages than
 * the previous unfiltered set.
 *
 * The effect must:
 *   - skip the initial mount (initial-load effect already fires the fetch);
 *   - refetch from page 1 on filter change;
 *   - ignore object-identity changes when the underlying primitive values
 *     are equal (callers commonly pass a fresh memo object on every render).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

const fetchDataMock = vi.fn();

vi.mock('@/components/data-table/hooks', () => ({
  // Lightweight stubs for every hook the controller composes - only
  // useDataFetching is load-bearing for this test; the rest can return
  // dummy state.
  useTableSelection: () => ({
    selectedRows: new Set(),
    selectedColumns: new Set(),
    setSelectedRows: vi.fn(),
    getRowUniqueKey: (r: any) => String(r?.id ?? ''),
    toggleRowSelection: vi.fn(),
    selectAllRows: vi.fn(),
    clearSelection: vi.fn(),
    toggleColumnSelection: vi.fn(),
    clearColumnSelection: vi.fn(),
  }),
  useTableExport: () => ({
    exportLoading: false,
    selectedExportFormat: 'csv',
    setSelectedExportFormat: vi.fn(),
    getDynamicColumns: vi.fn(),
    handleExportCSV: vi.fn(),
    handleExportJSON: vi.fn(),
    handleExportExcel: vi.fn(),
    handleExportFull: vi.fn(),
  }),
  usePagination: () => ({
    pagination: { currentPage: 1, pageSize: 20, totalItems: 0, totalPages: 0, nextCursor: null, hasMore: false },
    setPagination: vi.fn(),
    setCurrentPage: vi.fn(),
    setPageSize: vi.fn(),
    resetPagination: vi.fn(),
    updatePaginationFromResponse: vi.fn(),
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
    fetchColumns: vi.fn(),
    fetchData: fetchDataMock,
    setColumnOrder: vi.fn(),
  }),
  useRowOperations: () => ({
    showAddRowModal: false, newRowData: {}, newRowPriority: 1,
    isAddingRow: false, isAddingRowInline: false,
    setShowAddRowModal: vi.fn(), setNewRowData: vi.fn(), setNewRowPriority: vi.fn(),
    handleSaveEdit: vi.fn(), addNewRow: vi.fn(),
    deleteSelectedRows: vi.fn(),
    startAddingRowInline: vi.fn(), cancelAddingRowInline: vi.fn(), handleRowDataChange: vi.fn(),
  }),
  useColumnOperations: () => ({
    showAddColumnModal: false, newColumnName: '', isAddingColumn: false,
    showDeleteColumnsModal: false, columnsToDelete: [], selectedColumnStyle: null,
    showEditColumnModal: false, columnToEdit: null, isEditingColumn: false,
    setShowAddColumnModal: vi.fn(), setNewColumnName: vi.fn(),
    setSelectedColumnStyle: vi.fn(), setShowDeleteColumnsModal: vi.fn(),
    setColumnsToDelete: vi.fn(), setShowEditColumnModal: vi.fn(),
    setColumnToEdit: vi.fn(),
    addNewColumn: vi.fn(), deleteSelectedColumns: vi.fn(), confirmDeleteColumns: vi.fn(),
    openEditColumn: vi.fn(), saveEditColumn: vi.fn(),
  }),
  useSortingAndFiltering: () => ({
    sortConfig: null, searchQuery: '', showColumnFilters: false, columnFilters: {},
    filteredRows: [],
    setSortConfig: vi.fn(), setSearchQuery: vi.fn(),
    setShowColumnFilters: vi.fn(), setColumnFilters: vi.fn(),
    handleSort: vi.fn(), sortData: (r: any[]) => r,
  }),
  useDragAndDrop: () => ({
    handleDragStart: vi.fn(), handleDragOver: vi.fn(),
    handleDragLeave: vi.fn(), handleDrop: vi.fn(),
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

vi.mock('../utils/authenticatedFetch', async () => {
  const actual: any = await vi.importActual('../utils/authenticatedFetch');
  return {
    ...actual,
    setAuthTokenGetter: vi.fn(),
  };
});

import { useDataTableController } from '../useDataTableController';
import type { ServerFilters } from '../types';

describe('useDataTableController - serverFilters change effect', () => {
  beforeEach(() => {
    fetchDataMock.mockReset();
    fetchDataMock.mockResolvedValue(undefined);
  });

  it('refetches from page 1 with the new filters when status changes', async () => {
    const { rerender } = renderHook(
      ({ filters }: { filters: ServerFilters | null }) =>
        useDataTableController({
          dataSourceId: 0,
          workflowContext: { workflowId: 'w1', runId: 'r1', stepAlias: 'a1' },
          serverFilters: filters,
        }),
      { initialProps: { filters: { status: null, epoch: null } as ServerFilters } },
    );

    // The initial-load effect fires once. Subsequent filter-change effect
    // is suppressed on first mount by isInitialFilterRef.
    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    const initialCallCount = fetchDataMock.mock.calls.length;

    rerender({ filters: { status: 'completed', epoch: null } });

    await waitFor(() => {
      expect(fetchDataMock.mock.calls.length).toBeGreaterThan(initialCallCount);
    });

    // Last call must use page=1 (filter change resets pagination) and the
    // new serverFilters (5th positional arg).
    const lastCall = fetchDataMock.mock.calls[fetchDataMock.mock.calls.length - 1];
    expect(lastCall[0]).toBe(1);
    expect(lastCall[4]).toEqual({ status: 'completed', epoch: null });
  });

  it('does NOT refetch when serverFilters object identity changes but values are equal', async () => {
    const { rerender } = renderHook(
      ({ filters }: { filters: ServerFilters | null }) =>
        useDataTableController({
          dataSourceId: 0,
          workflowContext: { workflowId: 'w1', runId: 'r1', stepAlias: 'a1' },
          serverFilters: filters,
        }),
      { initialProps: { filters: { status: 'completed', epoch: 1 } as ServerFilters } },
    );

    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    const callsAfterMount = fetchDataMock.mock.calls.length;

    // New object reference, identical primitive values - must NOT trigger
    // a refetch (otherwise every parent re-render would slam the backend).
    rerender({ filters: { status: 'completed', epoch: 1 } });

    // Give React a chance to flush the (non-)effect.
    await new Promise((r) => setTimeout(r, 30));
    expect(fetchDataMock.mock.calls.length).toBe(callsAfterMount);
  });

  it('refetches when epoch changes alone (status unchanged)', async () => {
    const { rerender } = renderHook(
      ({ filters }: { filters: ServerFilters | null }) =>
        useDataTableController({
          dataSourceId: 0,
          workflowContext: { workflowId: 'w1', runId: 'r1', stepAlias: 'a1' },
          serverFilters: filters,
        }),
      { initialProps: { filters: { status: 'completed', epoch: 1 } as ServerFilters } },
    );

    await waitFor(() => expect(fetchDataMock).toHaveBeenCalled());
    const initialCallCount = fetchDataMock.mock.calls.length;

    rerender({ filters: { status: 'completed', epoch: 2 } });

    await waitFor(() => {
      expect(fetchDataMock.mock.calls.length).toBeGreaterThan(initialCallCount);
    });

    const lastCall = fetchDataMock.mock.calls[fetchDataMock.mock.calls.length - 1];
    expect(lastCall[0]).toBe(1);
    expect(lastCall[4]).toEqual({ status: 'completed', epoch: 2 });
  });
});
