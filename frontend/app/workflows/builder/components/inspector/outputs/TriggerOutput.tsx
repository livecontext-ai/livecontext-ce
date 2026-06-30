'use client';

/**
 * TriggerOutput - Workflows and Tables trigger output renderer
 *
 * Shows workflow outputs or datasource columns.
 * In run mode, displays actual execution data using RunDataPreview.
 */

import * as React from 'react';
import clsx from 'clsx';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor, normalizeFieldType } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { EmptyState } from '../../shared/EmptyState';
import { RunDataPreview } from './RunDataPreview';

interface TriggerOutputProps {
  triggerType: 'workflow' | 'datasource';
  isLoading: boolean;
  outputs: any[]; // workflowOutputs or activeColumns
  nextNodes: Node<BuilderNodeData>[];
  currentNode?: Node<BuilderNodeData> | null;
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  // Run mode props
  isRunMode?: boolean;
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
}

export function TriggerOutput({
  triggerType,
  isLoading,
  outputs,
  nextNodes,
  currentNode,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode = false,
  workflowId,
  runId,
  showExecutionData = true,
}: TriggerOutputProps) {
  // Get step alias for run data
  const stepAlias = currentNode?.data?.label;
  const canShowExecutionData = isRunMode && showExecutionData && workflowId && runId && stepAlias;

  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {canShowExecutionData ? (
        <RunDataPreview
          workflowId={workflowId}
          runId={runId}
          stepAlias={stepAlias}
          dataType="output"
          isDraggable={false}
        />
      ) : isLoading ? (
        <div className="space-y-2">
          {[1, 2, 3].map(i => (
            <div key={i} className="h-6 w-full rounded bg-slate-200 dark:bg-slate-700 animate-pulse" />
          ))}
        </div>
      ) : outputs.length > 0 ? (
        <div className="space-y-1">
          {outputs.map((item: any, index: number) => (
            <div
              key={`${item.field}-${index}-${currentNode?.id || ''}`}
              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1"
            >
              <span className="truncate flex-1 min-w-0 text-sm" title={item.label}>
                {item.label}
              </span>
              <span className={clsx(
                "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
                getFieldTypeColor(triggerType === 'workflow' ? item.displayType : normalizeFieldType(item.displayType))
              )}>
                {triggerType === 'workflow' ? item.displayType : normalizeFieldType(item.displayType)}
              </span>
            </div>
          ))}
        </div>
      ) : (
        <EmptyState message={triggerType === 'workflow' ? 'No outputs available' : 'No column mappings defined'} />
      )}
    </div>
  );
}
