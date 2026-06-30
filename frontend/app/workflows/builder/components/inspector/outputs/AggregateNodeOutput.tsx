'use client';

/**
 * AggregateNodeOutput - Aggregate node output renderer
 *
 * Shows aggregate fields as array outputs (each configured field becomes an array)
 * Plus a count field showing number of items aggregated
 *
 * In run mode, displays actual execution data using RunDataPreview.
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { RunDataPreview } from './RunDataPreview';

interface AggregateField {
  id: string;
  label: string;
  expression: string;
}

interface AggregateNodeOutputProps {
  aggregateFields: AggregateField[];
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

export function AggregateNodeOutput({
  aggregateFields,
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
}: AggregateNodeOutputProps) {
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
      ) : (
        /* Aggregate output fields - each field becomes an array */
        <div className="space-y-1">
          {aggregateFields.map((field) => (
            <div
              key={field.id}
              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1"
            >
              <span className="truncate flex-1 min-w-0 text-sm" title={field.label}>
                {field.label}
              </span>
              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('array')}`}>
                array
              </span>
            </div>
          ))}
          {/* Always show count field */}
          <div className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1">
            <span className="truncate flex-1 min-w-0 text-sm" title="count">
              count
            </span>
            <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('number')}`}>
              number
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
