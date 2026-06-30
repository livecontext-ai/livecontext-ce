'use client';

/**
 * ToolNodeOutput - Output display for Tool/API nodes
 *
 * Uses:
 * - RunDataPreview for run mode (execution data)
 * - LazyStructureTree for build mode (dynamic schema from backend)
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { LazyStructureTree } from '../LazyStructureTree';
import { NavigationButtons } from './NavigationButtons';
import { RunDataPreview } from './RunDataPreview';
import { EmptyState } from '../../shared/EmptyState';

interface ToolNodeOutputProps {
  structureId: string | null;
  treeKey: number;
  nextNodes: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData> | undefined) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  /** Current node for getting stepAlias */
  currentNode?: Node<BuilderNodeData>;
  /** Run mode props */
  isRunMode?: boolean;
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
}

export function ToolNodeOutput({
  structureId,
  treeKey,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  currentNode,
  isRunMode = false,
  workflowId,
  runId,
  showExecutionData = true,
}: ToolNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  const canShowExecutionData = isRunMode && showExecutionData && workflowId && runId && stepAlias;

  // Output column is never draggable - drag is only for ParameterColumn
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
      ) : structureId ? (
        <LazyStructureTree
          key={treeKey}
          structureId={structureId}
          isDraggable={false}
          rootLabel="output"
          includeHttpStatus={true}
        />
      ) : (
        <EmptyState message="No output structure available" />
      )}
    </div>
  );
}
