'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ApprovalOutput, ApprovalDelegation, ApprovalContinuationMode } from '../../types';
import { createDefaultApprovalOutputs } from '../../types';
import type { Connection } from './useInspectorConnections';

interface UseInspectorApprovalOutputsProps {
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  onUpdate: (data: BuilderNodeData) => void;
  setConnections: React.Dispatch<React.SetStateAction<Connection[]>>;
}

export function useInspectorApprovalOutputs({
  node,
  data,
  onUpdate,
  setConnections,
}: UseInspectorApprovalOutputsProps) {
  // Get approval outputs
  const rawOutputs: ApprovalOutput[] =
    (data?.approvalOutputs as ApprovalOutput[] | undefined) ?? (node?.id ? createDefaultApprovalOutputs(node.id) : []);

  const currentOutputs = React.useMemo(() => {
    return rawOutputs;
  }, [rawOutputs]);

  // Update approval outputs
  const handleUpdateOutputs = React.useCallback(
    (updater: (prev: ApprovalOutput[]) => ApprovalOutput[]) => {
      if (!data || !node) return;
      const existing = (data.approvalOutputs as ApprovalOutput[] | undefined) ?? createDefaultApprovalOutputs(data.id);
      const next = updater(existing);
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, approvalOutputs: next });
    },
    [data, onUpdate, node],
  );

  // Add output
  const handleAddOutput = React.useCallback(
    () => {
      handleUpdateOutputs((prev) => {
        const newOutput: ApprovalOutput = {
          id: `approval-${Date.now()}`,
          label: `Path ${prev.length + 1}`,
        };
        return [...prev, newOutput];
      });
    },
    [handleUpdateOutputs],
  );

  // Delete output (must keep at least 2)
  const handleDeleteOutput = React.useCallback(
    (id: string) => {
      if (!node || !data) return;

      const outputs = (data.approvalOutputs as ApprovalOutput[] | undefined) ?? createDefaultApprovalOutputs(data.id);
      const target = outputs.find((row) => row.id === id);

      if (!target || outputs.length <= 2) {
        return;
      }

      setConnections(prev => prev.filter(conn =>
        conn.source !== id && conn.target !== id
      ));

      handleUpdateOutputs((prev) => {
        return prev.filter((row) => row.id !== id);
      });
    },
    [handleUpdateOutputs, node, data, setConnections],
  );

  // Rename output
  const handleRenameOutput = React.useCallback(
    (id: string, label: string) => {
      handleUpdateOutputs((prev) =>
        prev.map((row) => (row.id === id ? { ...row, label: label || row.label } : row)),
      );
    },
    [handleUpdateOutputs],
  );

  // Update approval timeout
  const handleTimeoutChange = React.useCallback(
    (timeoutMs: number | undefined) => {
      if (!data) return;
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, approvalTimeoutMs: timeoutMs });
    },
    [data, onUpdate],
  );

  // Update approval context template (shown to the approver, resolved at pause time)
  const handleContextTemplateChange = React.useCallback(
    (template: string | undefined) => {
      if (!data) return;
      const { validationIssues, ...rest } = data as any;
      onUpdate({ ...rest, approvalContextTemplate: template });
    },
    [data, onUpdate],
  );

  // Update split-context continuation mode (undefined = all_items default, removed from node data)
  const handleContinuationModeChange = React.useCallback(
    (mode: ApprovalContinuationMode | undefined) => {
      if (!data) return;
      const { validationIssues, approvalContinuationMode, ...rest } = data as any;
      onUpdate(mode === undefined ? rest : { ...rest, approvalContinuationMode: mode });
    },
    [data, onUpdate],
  );

  // Update external-channel delegation (undefined = delegation disabled, removed from node data)
  const handleDelegationChange = React.useCallback(
    (delegation: ApprovalDelegation | undefined) => {
      if (!data) return;
      const { validationIssues, approvalDelegation, ...rest } = data as any;
      onUpdate(delegation === undefined ? rest : { ...rest, approvalDelegation: delegation });
    },
    [data, onUpdate],
  );

  return {
    currentOutputs,
    handleUpdateOutputs,
    handleAddOutput,
    handleDeleteOutput,
    handleRenameOutput,
    handleTimeoutChange,
    handleContextTemplateChange,
    handleContinuationModeChange,
    handleDelegationChange,
    approvalTimeoutMs: data?.approvalTimeoutMs,
    approvalContextTemplate: data?.approvalContextTemplate,
    approvalContinuationMode: data?.approvalContinuationMode,
    approvalDelegation: data?.approvalDelegation,
  };
}
