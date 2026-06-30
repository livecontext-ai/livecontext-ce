/**
 * GuardrailNodeOutput - Output for Guardrail nodes.
 *
 * Displays the node's own outputs (no passthrough).
 * Fields: passed, violations, score, input, model, provider
 */

'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { OutputFieldRow } from './OutputFieldRow';
import { RunOutputPreview } from './RunOutputPreview';

interface GuardrailNodeOutputProps {
  currentNode: Node<BuilderNodeData>;
  nextNodes: Node<BuilderNodeData>[];
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode: (node: Node<BuilderNodeData> | undefined) => string | undefined;
  ArrowIcon: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  isRunMode?: boolean;
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
}

export function GuardrailNodeOutput({
  currentNode,
  nextNodes,
  allNodes,
  edges,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode = false,
  workflowId,
  runId,
  showExecutionData,
}: GuardrailNodeOutputProps) {
  const nodeLabel = currentNode?.data?.label || '';
  const stepAlias = currentNode?.data?.label;

  return (
    <div className="w-full space-y-2">
      <NavigationButtons
        nextNodes={nextNodes}
        onNavigateToNode={onNavigateToNode}
        checkNodeError={checkNodeError}
        getLoopIdFromNode={getLoopIdFromNode}
        ArrowIcon={ArrowIcon}
      />

      {isRunMode && showExecutionData && workflowId && runId && stepAlias ? (
        <RunOutputPreview
          workflowId={workflowId}
          runId={runId}
          stepAlias={stepAlias}
        />
      ) : (
        <div className="space-y-1">
          <OutputFieldRow
            fieldName="passed"
            fieldType="boolean"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
          <OutputFieldRow
            fieldName="violations"
            fieldType="array"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
          <OutputFieldRow
            fieldName="score"
            fieldType="number"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
          <OutputFieldRow
            fieldName="input"
            fieldType="text"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
          <OutputFieldRow
            fieldName="model"
            fieldType="text"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
          <OutputFieldRow
            fieldName="provider"
            fieldType="text"
            nodeLabel={nodeLabel}
            nodePrefix="agent"
            isRunMode={isRunMode}
          />
        </div>
      )}
    </div>
  );
}
