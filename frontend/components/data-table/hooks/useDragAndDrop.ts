'use client';

import { useCallback } from 'react';
import type { ColumnOrder } from '../types';
import { authenticatedFetch } from '../utils/authenticatedFetch';

export interface UseDragAndDropParams {
  dataSourceId?: number;
  columnOrder: ColumnOrder[];
  reorderColumns: (draggedField: string, targetField: string, position: 'before' | 'after') => ColumnOrder[];
  resetDragState: () => void;
  setDraggedColumn: React.Dispatch<React.SetStateAction<string | null>>;
  setDragOverColumn: React.Dispatch<React.SetStateAction<string | null>>;
  setDragPosition: React.Dispatch<React.SetStateAction<'before' | 'after' | null>>;
  draggedColumn: string | null;
  addToast: (toast: { type: 'error' | 'success' | 'warning' | 'info'; title: string; message: string }) => void;
}

export interface UseDragAndDropReturn {
  handleDragStart: (e: React.DragEvent, field: string) => void;
  handleDragOver: (e: React.DragEvent, field: string) => void;
  handleDragLeave: () => void;
  handleDrop: (e: React.DragEvent, targetField: string) => Promise<void>;
}

/**
 * Hook for managing column drag and drop reordering.
 */
export function useDragAndDrop({
  dataSourceId,
  columnOrder,
  reorderColumns,
  resetDragState,
  setDraggedColumn,
  setDragOverColumn,
  setDragPosition,
  draggedColumn,
  addToast,
}: UseDragAndDropParams): UseDragAndDropReturn {
  /**
   * Start dragging a column
   */
  const handleDragStart = useCallback((e: React.DragEvent, field: string) => {
    setDraggedColumn(field);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', field);

    // Create custom drag image
    const dragElement = (e.currentTarget as HTMLElement).closest('th') as HTMLElement;
    if (dragElement) {
      const dragImage = dragElement.cloneNode(true) as HTMLElement;
      dragImage.style.opacity = '0.85';
      dragImage.style.transform = 'rotate(2deg) scale(0.95)';
      dragImage.style.pointerEvents = 'none';
      dragImage.style.position = 'absolute';
      dragImage.style.top = '-1000px';
      dragImage.style.width = `${dragElement.offsetWidth}px`;
      document.body.appendChild(dragImage);

      const rect = dragElement.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;

      e.dataTransfer.setDragImage(dragImage, x, y);

      // Cleanup after drag starts
      setTimeout(() => {
        if (document.body.contains(dragImage)) {
          document.body.removeChild(dragImage);
        }
      }, 0);
    }
  }, [setDraggedColumn]);

  /**
   * Handle drag over a column
   */
  const handleDragOver = useCallback((e: React.DragEvent, field: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';

    if (draggedColumn && draggedColumn !== field) {
      setDragOverColumn(field);

      // Determine position (before or after)
      const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
      const mouseX = e.clientX;
      const centerX = rect.left + rect.width / 2;
      setDragPosition(mouseX < centerX ? 'before' : 'after');
    }
  }, [draggedColumn, setDragOverColumn, setDragPosition]);

  /**
   * Handle drag leave
   */
  const handleDragLeave = useCallback(() => {
    // Delay to prevent flickering
    setTimeout(() => {
      setDragOverColumn(null);
      setDragPosition(null);
    }, 50);
  }, [setDragOverColumn, setDragPosition]);

  /**
   * Handle drop on a column
   */
  const handleDrop = useCallback(async (e: React.DragEvent, targetField: string) => {
    e.preventDefault();
    const draggedField = e.dataTransfer.getData('text/plain') || draggedColumn;

    if (!draggedField || draggedField === targetField) {
      resetDragState();
      return;
    }

    // Get current drag position before resetting
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const mouseX = e.clientX;
    const centerX = rect.left + rect.width / 2;
    const position = mouseX < centerX ? 'before' : 'after';

    // Reorder columns
    const updatedOrder = reorderColumns(draggedField, targetField, position);

    // If no change, reset and return
    if (updatedOrder === columnOrder) {
      resetDragState();
      return;
    }

    // Save column order to backend
    if (dataSourceId) {
      try {
        const response = await authenticatedFetch(
          `/api/proxy/data-sources/${dataSourceId}/column-order`,
          {
            method: 'PUT',
            body: JSON.stringify(updatedOrder),
          }
        );

        if (!response.ok) {
          console.error('Failed to save column order');
          addToast({
            type: 'error',
            title: 'Error Saving Column Order',
            message: 'Failed to save column order',
          });
        } else {
          addToast({
            type: 'success',
            title: 'Column Order Saved',
            message: 'Column order has been saved',
          });
        }
      } catch (err) {
        console.error('Error saving column order:', err);
        addToast({
          type: 'error',
          title: 'Error Saving Column Order',
          message: 'Failed to save column order',
        });
      }
    }

    resetDragState();
  }, [draggedColumn, columnOrder, reorderColumns, resetDragState, dataSourceId, addToast]);

  return {
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
  };
}
