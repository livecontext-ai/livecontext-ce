'use client';

import { useState, useCallback } from 'react';
import { getValueAtPath, setValueAtPath } from '../visualHelpers';

export interface HoveredCellInfo {
  rowId: number;
  colField: string;
  anchorElement: HTMLElement;
  jsonData: unknown;
  headerName: string;
}

interface RowWithData {
  data: Record<string, unknown>;
}

export interface UseCellEditingReturn {
  hoveredCell: HoveredCellInfo | null;
  editingCellKey: string | null;
  progressTempValues: Map<string, number>;
  setHoveredCell: (cell: HoveredCellInfo | null) => void;
  setEditingCellKey: (key: string | null) => void;
  setProgressTempValues: React.Dispatch<React.SetStateAction<Map<string, number>>>;
  makeCellKey: (rowId: number | string, field: string) => string;
  getFieldPath: (field: string) => string;
  getRowValue: (row: RowWithData, field: string) => unknown;
  applyValueToRow: (data: Record<string, unknown>, field: string, nextValue: unknown) => Record<string, unknown>;
  startEditing: (rowId: number | string, field: string) => void;
  stopEditing: () => void;
  isEditing: (rowId: number | string, field: string) => boolean;
  updateProgressTemp: (rowId: number | string, field: string, value: number) => void;
  getProgressTemp: (rowId: number | string, field: string) => number | undefined;
  clearProgressTemp: (rowId: number | string, field: string) => void;
}

/**
 * Hook for managing cell editing state in the data table.
 * Handles hover state, editing state, and temporary values for progress cells.
 */
export function useCellEditing(): UseCellEditingReturn {
  const [hoveredCell, setHoveredCell] = useState<HoveredCellInfo | null>(null);
  const [editingCellKey, setEditingCellKey] = useState<string | null>(null);
  const [progressTempValues, setProgressTempValues] = useState<Map<string, number>>(new Map());

  const makeCellKey = useCallback((rowId: number | string, field: string): string => {
    return `${rowId}-${field}`;
  }, []);

  const getFieldPath = useCallback((field: string): string => {
    return field.startsWith('data.') ? field.replace('data.', '') : field;
  }, []);

  const getRowValue = useCallback((row: RowWithData, field: string): unknown => {
    return getValueAtPath(row.data, getFieldPath(field));
  }, [getFieldPath]);

  const applyValueToRow = useCallback((
    data: Record<string, unknown>,
    field: string,
    nextValue: unknown
  ): Record<string, unknown> => {
    return setValueAtPath(data, getFieldPath(field), nextValue);
  }, [getFieldPath]);

  const startEditing = useCallback((rowId: number | string, field: string) => {
    setEditingCellKey(makeCellKey(rowId, field));
  }, [makeCellKey]);

  const stopEditing = useCallback(() => {
    setEditingCellKey(null);
  }, []);

  const isEditing = useCallback((rowId: number | string, field: string): boolean => {
    return editingCellKey === makeCellKey(rowId, field);
  }, [editingCellKey, makeCellKey]);

  const updateProgressTemp = useCallback((rowId: number | string, field: string, value: number) => {
    const key = makeCellKey(rowId, field);
    setProgressTempValues(prev => {
      const next = new Map(prev);
      next.set(key, value);
      return next;
    });
  }, [makeCellKey]);

  const getProgressTemp = useCallback((rowId: number | string, field: string): number | undefined => {
    return progressTempValues.get(makeCellKey(rowId, field));
  }, [progressTempValues, makeCellKey]);

  const clearProgressTemp = useCallback((rowId: number | string, field: string) => {
    const key = makeCellKey(rowId, field);
    setProgressTempValues(prev => {
      const next = new Map(prev);
      next.delete(key);
      return next;
    });
  }, [makeCellKey]);

  return {
    hoveredCell,
    editingCellKey,
    progressTempValues,
    setHoveredCell,
    setEditingCellKey,
    setProgressTempValues,
    makeCellKey,
    getFieldPath,
    getRowValue,
    applyValueToRow,
    startEditing,
    stopEditing,
    isEditing,
    updateProgressTemp,
    getProgressTemp,
    clearProgressTemp,
  };
}
