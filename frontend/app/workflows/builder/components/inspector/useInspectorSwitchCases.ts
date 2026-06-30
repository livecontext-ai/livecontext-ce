'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, SwitchCaseRow } from '../../types';
import { createDefaultSwitchCases } from '../../types';
import type { Connection } from './useInspectorConnections';

interface UseInspectorSwitchCasesProps {
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  onUpdate: (data: BuilderNodeData) => void;
  setConnections: React.Dispatch<React.SetStateAction<Connection[]>>;
}

// Helper function to sort cases: cases first (oldest first), then default
function sortCases(cases: SwitchCaseRow[]): SwitchCaseRow[] {
  const sorted = [...cases];
  sorted.sort((a, b) => {
    const order = { 'case': 0, 'default': 1 };
    const typeOrder = (order[a.type] ?? 999) - (order[b.type] ?? 999);

    // If same type (both case), sort by creation order (using id timestamp)
    if (typeOrder === 0 && a.type === 'case' && b.type === 'case') {
      const aTimestamp = parseInt(a.id.split('-').slice(-1)[0]) || 0;
      const bTimestamp = parseInt(b.id.split('-').slice(-1)[0]) || 0;
      return aTimestamp - bTimestamp;
    }

    return typeOrder;
  });
  return sorted;
}

export function useInspectorSwitchCases({
  node,
  data,
  onUpdate,
  setConnections,
}: UseInspectorSwitchCasesProps) {
  // Get sorted switch cases
  const rawCases: SwitchCaseRow[] =
    (data?.switchCases as SwitchCaseRow[] | undefined) ?? (node?.id ? createDefaultSwitchCases(node.id) : []);

  const currentCases = React.useMemo(() => {
    return sortCases(rawCases);
  }, [rawCases, data?.switchCases, data?.id, node?.type]);

  // Get switch expression
  const switchExpression = data?.switchExpression ?? '';

  // Get case handle ID - use caseRow.id directly (same pattern as DecisionNode)
  // This ensures consistency between handle ID and switchCases[].id
  const getCaseHandleId = React.useCallback((caseRow: SwitchCaseRow, _index: number) => {
    return caseRow.id;
  }, []);

  // Get case value
  const getCaseValue = React.useCallback((caseId: string): string => {
    const caseRow = currentCases.find(c => c.id === caseId);
    return caseRow?.value ?? '';
  }, [currentCases]);

  // Update switch cases
  const handleUpdateCases = React.useCallback(
    (updater: (prev: SwitchCaseRow[]) => SwitchCaseRow[]) => {
      if (!data || !node) return;
      const existing = (data.switchCases as SwitchCaseRow[] | undefined) ?? createDefaultSwitchCases(data.id);
      const next = updater(existing);
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, switchCases: next });
    },
    [data, onUpdate, node],
  );

  // Update switch expression
  const handleSwitchExpressionChange = React.useCallback(
    (value: string) => {
      if (!data) return;
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, switchExpression: value });
    },
    [data, onUpdate],
  );

  // Add case
  const handleAddCase = React.useCallback(
    (afterIndex?: number) => {
      handleUpdateCases((prev) => {
        const sorted = sortCases(prev);
        const caseCount = sorted.filter((row) => row.type === 'case').length;

        const newCase: SwitchCaseRow = {
          id: `case-${Date.now()}`,
          type: 'case',
          label: `Case ${caseCount + 1}`,
          value: '',
        };

        if (afterIndex !== undefined && afterIndex >= 0) {
          const newCases = [...sorted];
          // Insert new case after the specified index
          newCases.splice(afterIndex + 1, 0, newCase);
          return sortCases(newCases);
        }

        // Insert before default
        const defaultIndex = sorted.findIndex(c => c.type === 'default');
        if (defaultIndex >= 0) {
          const newCases = [...sorted];
          newCases.splice(defaultIndex, 0, newCase);
          return newCases;
        }

        return sorted.concat(newCase);
      });
    },
    [handleUpdateCases],
  );

  // Delete case
  const handleDeleteCase = React.useCallback(
    (id: string) => {
      if (!node || !data) return;

      const cases = (data.switchCases as SwitchCaseRow[] | undefined) ?? createDefaultSwitchCases(data.id);
      const sorted = sortCases(cases);
      const target = sorted.find((row) => row.id === id);

      // Cannot delete if it's the last case (must keep at least 1 case)
      const caseCount = sorted.filter(c => c.type === 'case').length;
      if (!target || (target.type === 'case' && caseCount <= 1)) {
        return;
      }

      // Use caseRow.id directly as handle ID (same pattern as DecisionNode)
      // No need to renumber since IDs are stable
      const caseHandleId = target.id;

      // Update connections - simply remove connections that reference this handle
      setConnections(prev => prev.filter(conn =>
        conn.source !== caseHandleId && conn.target !== caseHandleId
      ));

      handleUpdateCases((prev) => {
        const sorted = sortCases(prev);
        return sorted.filter((row) => row.id !== id);
      });
    },
    [handleUpdateCases, node, data, setConnections],
  );

  // Rename case
  const handleRenameCase = React.useCallback(
    (id: string, label: string) => {
      handleUpdateCases((prev) =>
        prev.map((row) => (row.id === id ? { ...row, label: label || row.label } : row)),
      );
    },
    [handleUpdateCases],
  );

  // Update case value
  const handleCaseValueChange = React.useCallback(
    (id: string, value: string) => {
      handleUpdateCases((prev) =>
        prev.map((row) => (row.id === id ? { ...row, value } : row)),
      );
    },
    [handleUpdateCases],
  );

  return {
    currentCases,
    switchExpression,
    getCaseHandleId,
    getCaseValue,
    handleUpdateCases,
    handleSwitchExpressionChange,
    handleAddCase,
    handleDeleteCase,
    handleRenameCase,
    handleCaseValueChange,
  };
}
