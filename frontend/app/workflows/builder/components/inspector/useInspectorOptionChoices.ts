'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, OptionChoice } from '../../types';
import { createDefaultOptionChoices } from '../../types';
import type { Connection } from './useInspectorConnections';

interface UseInspectorOptionChoicesProps {
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  onUpdate: (data: BuilderNodeData) => void;
  setConnections: React.Dispatch<React.SetStateAction<Connection[]>>;
}

export function useInspectorOptionChoices({
  node,
  data,
  onUpdate,
  setConnections,
}: UseInspectorOptionChoicesProps) {
  // Get option choices
  const rawChoices: OptionChoice[] =
    (data?.optionChoices as OptionChoice[] | undefined) ?? (node?.id ? createDefaultOptionChoices(node.id) : []);

  const currentChoices = React.useMemo(() => {
    return rawChoices;
  }, [rawChoices]);

  // Update option choices
  const handleUpdateChoices = React.useCallback(
    (updater: (prev: OptionChoice[]) => OptionChoice[]) => {
      if (!data || !node) return;
      const existing = (data.optionChoices as OptionChoice[] | undefined) ?? createDefaultOptionChoices(data.id);
      const next = updater(existing);
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, optionChoices: next });
    },
    [data, onUpdate, node],
  );

  // Add choice
  const handleAddChoice = React.useCallback(
    () => {
      handleUpdateChoices((prev) => {
        const newChoice: OptionChoice = {
          id: `option-${Date.now()}`,
          label: `Option ${prev.length + 1}`,
          expression: '',
        };
        return [...prev, newChoice];
      });
    },
    [handleUpdateChoices],
  );

  // Delete choice
  const handleDeleteChoice = React.useCallback(
    (id: string) => {
      if (!node || !data) return;

      const choices = (data.optionChoices as OptionChoice[] | undefined) ?? createDefaultOptionChoices(data.id);
      const target = choices.find((row) => row.id === id);

      // Cannot delete if it's one of the last 2 choices (must keep at least 2 choices)
      if (!target || choices.length <= 2) {
        return;
      }

      // Update connections - simply remove connections that reference this handle
      setConnections(prev => prev.filter(conn =>
        conn.source !== id && conn.target !== id
      ));

      handleUpdateChoices((prev) => {
        return prev.filter((row) => row.id !== id);
      });
    },
    [handleUpdateChoices, node, data, setConnections],
  );

  // Rename choice
  const handleRenameChoice = React.useCallback(
    (id: string, label: string) => {
      handleUpdateChoices((prev) =>
        prev.map((row) => (row.id === id ? { ...row, label: label || row.label } : row)),
      );
    },
    [handleUpdateChoices],
  );

  // Update choice expression
  const handleExpressionChange = React.useCallback(
    (id: string, expression: string) => {
      handleUpdateChoices((prev) =>
        prev.map((row) => (row.id === id ? { ...row, expression } : row)),
      );
    },
    [handleUpdateChoices],
  );

  return {
    currentChoices,
    handleUpdateChoices,
    handleAddChoice,
    handleDeleteChoice,
    handleRenameChoice,
    handleExpressionChange,
  };
}
