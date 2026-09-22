'use client';

import { useCallback, useMemo, useState } from 'react';
import type { ColumnDefinition, ColumnOrder } from '../types';
import { idIsHiddenBehindCheckbox, type ViewConfig } from '../viewConfig';
import { buildColumnOrderRank } from '@/utils/columnSpec';

export interface UseColumnManagementParams {
  viewConfig: ViewConfig;
  workflowContext?: {
    workflowId: string;
    runId: string;
    stepId?: number;
    stepAlias?: string;
    isAggregated?: boolean;
  } | null;
}

export interface UseColumnManagementReturn {
  // State
  columns: ColumnDefinition[];
  columnOrder: ColumnOrder[];
  draggedColumn: string | null;
  dragOverColumn: string | null;
  dragPosition: 'before' | 'after' | null;
  // Setters
  setColumns: React.Dispatch<React.SetStateAction<ColumnDefinition[]>>;
  setColumnOrder: React.Dispatch<React.SetStateAction<ColumnOrder[]>>;
  setDraggedColumn: React.Dispatch<React.SetStateAction<string | null>>;
  setDragOverColumn: React.Dispatch<React.SetStateAction<string | null>>;
  setDragPosition: React.Dispatch<React.SetStateAction<'before' | 'after' | null>>;
  // Functions
  /**
   * Get all columns (fixed + dynamic) sorted according to columnOrder
   */
  getAllColumns: () => ColumnDefinition[];
  /**
   * Get deduplicated columns
   */
  getUniqueColumns: () => ColumnDefinition[];
  /**
   * Reorder columns after a successful drag and drop operation.
   * Returns the new order for persistence.
   */
  reorderColumns: (draggedField: string, targetField: string, position: 'before' | 'after') => ColumnOrder[];
  /**
   * Reset drag state
   */
  resetDragState: () => void;
}

/**
 * Hook for managing column definitions, ordering, and drag-and-drop reordering.
 * Follows Single Responsibility Principle - only handles column management logic.
 */
export function useColumnManagement({
  viewConfig,
  workflowContext
}: UseColumnManagementParams): UseColumnManagementReturn {
  const [columns, setColumns] = useState<ColumnDefinition[]>([]);
  const [columnOrder, setColumnOrder] = useState<ColumnOrder[]>([]);
  const [draggedColumn, setDraggedColumn] = useState<string | null>(null);
  const [dragOverColumn, setDragOverColumn] = useState<string | null>(null);
  const [dragPosition, setDragPosition] = useState<'before' | 'after' | null>(null);

  /**
   * Build fixed columns based on view configuration
   */
  const buildFixedColumns = useCallback((): ColumnDefinition[] => {
    const fixedColumns: ColumnDefinition[] = [];

    if (viewConfig.showCheckbox) {
      fixedColumns.push({
        col_id: 'checkbox',
        field: 'checkbox',
        header_name: '',
        type: 'boolean' as const,
        editable: false,
        sortable: false,
        filterable: false
      });
    }

    if (viewConfig.showIdColumn) {
      fixedColumns.push({
        col_id: 'id',
        field: 'id',
        header_name: 'ID',
        type: 'number' as const,
        editable: false,
        sortable: true,
        filterable: false
      });
    }

    if (viewConfig.showPriority) {
      fixedColumns.push({
        col_id: 'priority',
        field: 'priority',
        header_name: 'Priority',
        type: 'number' as const,
        editable: true,
        sortable: true,
        filterable: false
      });
    }

    if (viewConfig.showCreatedAt) {
      fixedColumns.push({
        col_id: 'created_at',
        field: 'created_at',
        header_name: 'Created At',
        type: 'date' as const,
        editable: false,
        sortable: true,
        filterable: false
      });
    }

    // Add array_index and value for nested array navigation
    // Only show these when the fetched data actually contains them
    // (arrays have array_index, primitive arrays have value)
    if (viewConfig.showArrayIndex && columns.some(col => col.field === 'array_index')) {
      fixedColumns.push({
        col_id: 'array_index',
        field: 'array_index',
        header_name: 'Index',
        type: 'number' as const,
        editable: false,
        sortable: true,
        filterable: false
      });
    }

    if (viewConfig.showValue && columns.some(col => col.field === 'value')) {
      // Try to get type from columns if available, otherwise default to 'text'
      const valueCol = columns.find(col => col.field === 'value');
      fixedColumns.push({
        col_id: 'value',
        field: 'value',
        header_name: 'Value',
        type: (valueCol?.type || 'text'),
        editable: true,
        sortable: true,
        filterable: true
      });
    }

    return fixedColumns;
  }, [viewConfig, columns]);

  /** Saved position per column, rebuilt only when the saved order itself changes. */
  const savedOrderRank = useMemo(() => buildColumnOrderRank(columnOrder), [columnOrder]);

  /**
   * Arrange columns according to the saved order.
   *
   * The rule is: a column the saved order NAMES takes its saved position; a
   * column it does not name DOES NOT MOVE. Concretely, the named columns are
   * sorted among themselves and put back into the slots they already occupied,
   * so an order that only knows about some of the columns rearranges those and
   * leaves the rest exactly where they were.
   *
   * That is what makes this safe on a partial order, which is a shape the
   * product really produces: several server paths copy a table with an empty
   * `column_order`, and one added column then makes it name a single field.
   * Sending everything unnamed to the END instead would push the selection
   * checkbox behind every data column. And no column needs to be excluded from
   * the sort to protect it: the grid pins only `checkbox` and the id lane, but
   * `priority`, `created_at`, `array_index` and `value` are draggable like any
   * other column (`DataTableGrid` gates the handle on `isFixed`, which is those
   * two alone), so a saved order that moves one of them is a real arrangement a
   * user made and must be honoured.
   *
   * A freshly added column lands at the END because the BACKEND appends it to
   * `column_order` when it creates it, not because of anything decided here.
   * Which entry names which column is decided in one place, `buildColumnOrderRank`,
   * including the case where a data column carries a lane's name.
   */
  const applySavedOrder = useCallback((cols: ColumnDefinition[]): ColumnDefinition[] => {
    if (savedOrderRank.size === 0) return cols;

    const named: Array<{ col: ColumnDefinition; rank: number }> = [];
    const slots: number[] = [];
    cols.forEach((col, index) => {
      const rank = savedOrderRank.of(col.field);
      if (rank === undefined) return;
      named.push({ col, rank });
      slots.push(index);
    });
    if (named.length === 0) return cols;

    // Stable (ES2019), so two columns sharing a saved position keep their
    // incoming relative order rather than swapping at random.
    named.sort((a, b) => a.rank - b.rank);

    const arranged = [...cols];
    slots.forEach((slot, i) => { arranged[slot] = named[i].col; });
    return arranged;
  }, [savedOrderRank]);

  /**
   * Every column of this view, arranged by the saved order.
   *
   * Memoized rather than rebuilt per call: the grid asks for these a dozen
   * times per render, and every caller therefore shares one array instance.
   * Treat the result as READ-ONLY; copy before sorting or splicing it.
   */
  const allColumns = useMemo((): ColumnDefinition[] => {
    // In workflow context, the backend is the source of truth for all columns
    // Don't add frontend fixed columns - use backend columns directly
    if (workflowContext && columns.length > 0) {
      // Check if columns have renderType (indicates they come from detailed endpoint)
      const hasBackendColumns = columns.some(col => col.renderType);
      if (hasBackendColumns) {
        // Use backend columns directly, respecting columnOrder if set
        return applySavedOrder(columns);
      }
    }

    const fixedColumns = buildFixedColumns();

    // Drop only the data columns that the fixed set ALREADY renders, so the two
    // never duplicate. Never filter on a hard-coded reserved-name list: during
    // nested JSON navigation the columns are derived from the data itself, so a
    // row genuinely carrying `id` (table rows), `value` or `array_index` would
    // have its column silently deleted when the matching fixed column is off
    // (any view on DataTable's own `showIdColumn = false` default).
    const fixedFields = new Set(fixedColumns.map(col => col.field));
    // At ROOT level outside workflow mode the grid renders the row id INSIDE the
    // checkbox column (DataTableGrid returns null for both the `id` header and
    // cell there), so an `id` column would only add an empty lane. Nested is the
    // opposite case: `id` there belongs to the navigated data, not to the row.
    if (idIsHiddenBehindCheckbox(viewConfig)) {
      fixedFields.add('id');
    }
    const dynamicColumns = columns.filter(col => !fixedFields.has(col.field));

    return applySavedOrder([...fixedColumns, ...dynamicColumns]);
  }, [buildFixedColumns, columns, applySavedOrder, workflowContext, viewConfig]);

  const uniqueColumns = useMemo((): ColumnDefinition[] => (
    allColumns.filter((col, idx, arr) => arr.findIndex(c => c.field === col.field) === idx)
  ), [allColumns]);

  /**
   * Get all columns (fixed + dynamic) sorted according to columnOrder
   */
  const getAllColumns = useCallback((): ColumnDefinition[] => allColumns, [allColumns]);

  /**
   * Get deduplicated columns
   */
  const getUniqueColumns = useCallback((): ColumnDefinition[] => uniqueColumns, [uniqueColumns]);

  // A second `getDynamicColumns` used to live here with its own reserved-name list. Nothing called
  // it - the controller takes useTableExport's, which is driven by the view's real fixed set - and
  // two implementations of one rule is how they drift apart.

  // An `initializeColumnOrder` used to live here too, seeding the order from the
  // current columns. Nothing called it: useDataFetching owns that seeding, on the
  // same state, and two implementations of one rule is how they drift apart.

  /**
   * Reorder columns after a successful drag and drop operation.
   * Returns the new order for persistence.
   */
  const reorderColumns = useCallback((
    draggedField: string,
    targetField: string,
    position: 'before' | 'after'
  ): ColumnOrder[] => {
    if (!draggedField || draggedField === targetField) {
      return columnOrder;
    }

    // Create a new list based on current order
    const currentOrder = getAllColumns().map(col => col.field);
    const draggedIndex = currentOrder.indexOf(draggedField);
    const targetIndex = currentOrder.indexOf(targetField);

    if (draggedIndex === -1 || targetIndex === -1) {
      return columnOrder;
    }

    // Create new order considering position (before/after)
    const newOrder = [...currentOrder];
    const [draggedItem] = newOrder.splice(draggedIndex, 1);

    // Calculate insertion position based on dragPosition
    let insertIndex = targetIndex;
    if (draggedIndex < targetIndex) {
      // Moving to the right, adjust index
      insertIndex = position === 'after' ? targetIndex : targetIndex - 1;
    } else {
      // Moving to the left, adjust index
      insertIndex = position === 'after' ? targetIndex + 1 : targetIndex;
    }

    // Ensure index is valid
    insertIndex = Math.max(0, Math.min(insertIndex, newOrder.length));

    newOrder.splice(insertIndex, 0, draggedItem);

    // Convert to ColumnOrder format
    const updatedOrder = newOrder.map((field, index) => ({
      field,
      order: index
    }));

    setColumnOrder(updatedOrder);
    return updatedOrder;
  }, [columnOrder, getAllColumns]);

  /**
   * Reset drag state
   */
  const resetDragState = useCallback(() => {
    setDraggedColumn(null);
    setDragOverColumn(null);
    setDragPosition(null);
  }, []);

  return {
    // State
    columns,
    columnOrder,
    draggedColumn,
    dragOverColumn,
    dragPosition,
    // Setters
    setColumns,
    setColumnOrder,
    setDraggedColumn,
    setDragOverColumn,
    setDragPosition,
    // Functions
    getAllColumns,
    getUniqueColumns,
    reorderColumns,
    resetDragState,
  };
}
