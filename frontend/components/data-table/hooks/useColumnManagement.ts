'use client';

import { useCallback, useMemo, useState } from 'react';
import type { ColumnDefinition, ColumnOrder } from '../types';
import type { ViewConfig } from '../viewConfig';

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
   * Get dynamic columns for forms (excludes fixed columns)
   */
  getDynamicColumns: () => ColumnDefinition[];
  /**
   * Initialize column order from columns (if not already set)
   */
  initializeColumnOrder: (fixedColumns: string[]) => void;
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

  /**
   * Get all columns (fixed + dynamic) sorted according to columnOrder
   */
  const getAllColumns = useCallback((): ColumnDefinition[] => {
    // In workflow context, the backend is the source of truth for all columns
    // Don't add frontend fixed columns - use backend columns directly
    if (workflowContext && columns.length > 0) {
      // Check if columns have renderType (indicates they come from detailed endpoint)
      const hasBackendColumns = columns.some(col => col.renderType);
      if (hasBackendColumns) {
        // Use backend columns directly, respecting columnOrder if set
        if (columnOrder.length === 0) {
          return columns;
        }

        const columnMap = new Map(columns.map(col => [col.field, col]));
        const uniqueOrderFields = Array.from(new Set(columnOrder.map(item => item.field)));
        const orderedColumns = uniqueOrderFields
          .map(field => columnMap.get(field))
          .filter(Boolean) as ColumnDefinition[];

        const remainingColumns = columns.filter(col =>
          !uniqueOrderFields.includes(col.field)
        );

        return [...orderedColumns, ...remainingColumns];
      }
    }

    const fixedColumns = buildFixedColumns();

    // Filter dynamic columns to exclude fixed fields
    const dynamicColumns = columns.filter(col => {
      const fixedFields = workflowContext
        ? ['checkbox', 'id', 'array_index', 'value']
        : ['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'];
      return !fixedFields.includes(col.field);
    });

    // Combine all columns
    const allColumns = [...fixedColumns, ...dynamicColumns];

    // If columnOrder is empty, return default order
    if (columnOrder.length === 0) {
      return allColumns;
    }

    // Create a map for quick column lookup
    const columnMap = new Map(allColumns.map(col => [col.field, col]));

    // Deduplicate columnOrder and sort according to defined order
    const uniqueOrderFields = Array.from(new Set(columnOrder.map(item => item.field)));
    const orderedColumns = uniqueOrderFields
      .map(field => columnMap.get(field))
      .filter(Boolean) as ColumnDefinition[];

    // Add columns not in columnOrder
    const remainingColumns = allColumns.filter(col =>
      !uniqueOrderFields.includes(col.field)
    );

    return [...orderedColumns, ...remainingColumns];
  }, [buildFixedColumns, columns, columnOrder, workflowContext]);

  /**
   * Get deduplicated columns
   */
  const getUniqueColumns = useCallback((): ColumnDefinition[] => {
    return getAllColumns().filter((col, idx, arr) =>
      arr.findIndex(c => c.field === col.field) === idx
    );
  }, [getAllColumns]);

  /**
   * Get dynamic columns for forms (excludes fixed columns)
   */
  const getDynamicColumns = useCallback((): ColumnDefinition[] => {
    return getUniqueColumns().filter(col =>
      !['checkbox', 'id', 'priority', 'created_at', 'array_index', 'value'].includes(col.field)
    );
  }, [getUniqueColumns]);

  /**
   * Initialize column order from columns (if not already set)
   */
  const initializeColumnOrder = useCallback((fixedColumns: string[]) => {
    if (columnOrder.length > 0 || columns.length === 0) return;

    const dynamicColumnFields = columns.map(col => col.field);
    const allFields = [...fixedColumns, ...dynamicColumnFields];

    const initialOrder = allFields.map((field, index) => ({
      field,
      order: index
    }));

    setColumnOrder(initialOrder);
  }, [columnOrder.length, columns]);

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
    getDynamicColumns,
    initializeColumnOrder,
    reorderColumns,
    resetDragState,
  };
}
