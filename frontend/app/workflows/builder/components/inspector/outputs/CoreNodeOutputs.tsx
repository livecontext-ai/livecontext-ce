/**
 * Core Node Outputs - Output displays for control flow nodes.
 *
 * Each core node has its own output schema (no passthrough).
 * These components display the actual outputs produced by each node type.
 *
 * Categories: decision, switch, fork, wait, loop, split, merge, transform
 */

'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { RunOutputPreview } from './RunOutputPreview';
import { EmptyState } from '../../shared/EmptyState';

// =============================================================================
// SHARED TYPES
// =============================================================================

interface CoreNodeOutputProps {
  currentNode: Node<BuilderNodeData>;
  nextNodes: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError?: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode?: (node: Node<BuilderNodeData>) => string | undefined;
  ArrowIcon?: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  isRunMode?: boolean;
  // Run mode props
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
}

// =============================================================================
// OUTPUT FIELD COMPONENT
// =============================================================================

interface OutputFieldProps {
  label: string;
  type: string;
  description?: string;
}

function OutputField({ label, type, description }: OutputFieldProps) {
  return (
    <div className="flex items-center justify-between py-2 px-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg">
      <div className="flex flex-col">
        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">{label}</span>
        {description && (
          <span className="text-xs text-slate-500 dark:text-slate-400">{description}</span>
        )}
      </div>
      <span className="text-xs font-mono text-slate-500 dark:text-slate-400 bg-slate-200 dark:bg-slate-700 px-2 py-0.5 rounded">
        {type}
      </span>
    </div>
  );
}

// =============================================================================
// DECISION NODE OUTPUT
// =============================================================================

/**
 * Decision node outputs:
 * - selected_branch: string (the branch that was selected: "if", "elseif_0", "else")
 * - evaluations: array (evaluation details for each condition)
 * - skipped_branches: array (branches that were not executed)
 */
export function DecisionOutput({
  currentNode,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
  workflowId,
  runId,
  showExecutionData,
}: CoreNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  return (
    <div className="w-full space-y-3">
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
        <div className="space-y-2">
          <OutputField label="selected_branch" type="string" description="Branch that matched the condition" />
          <OutputField label="evaluations" type="array" description="Evaluation result for each condition" />
          <OutputField label="skipped_branches" type="array" description="Branches that were not executed" />
        </div>
      )}
    </div>
  );
}

// =============================================================================
// SWITCH NODE OUTPUT
// =============================================================================

/**
 * Switch node outputs:
 * - selected_case: string (the case that matched)
 * - switch_value: any (the value that was switched on)
 * - evaluations: array (evaluation details)
 */
export function SwitchOutput({
  currentNode,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
  workflowId,
  runId,
  showExecutionData,
}: CoreNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  return (
    <div className="w-full space-y-3">
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
        <div className="space-y-2">
          <OutputField label="selected_case" type="string" description="Case that matched the switch value" />
          <OutputField label="switch_value" type="any" description="The value that was evaluated" />
          <OutputField label="evaluations" type="array" description="Evaluation result for each case" />
        </div>
      )}
    </div>
  );
}

// =============================================================================
// FORK NODE OUTPUT
// =============================================================================

/**
 * Fork node outputs:
 * - branch_count: number (number of parallel branches)
 * - branches: array (list of branch info)
 */
export function ForkOutput({
  currentNode,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
  workflowId,
  runId,
  showExecutionData,
}: CoreNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  return (
    <div className="w-full space-y-3">
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
        <div className="space-y-2">
          <OutputField label="branch_count" type="number" description="Number of parallel branches started" />
          <OutputField label="branches" type="array" description="List of branch identifiers and targets" />
        </div>
      )}
    </div>
  );
}

// =============================================================================
// WAIT NODE OUTPUT
// =============================================================================

/**
 * Wait node outputs:
 * - status: string ("completed")
 * - waited_ms: number (duration waited in milliseconds)
 */
export function WaitOutput({
  currentNode,
  nextNodes,
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  isRunMode,
  workflowId,
  runId,
  showExecutionData,
}: CoreNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  return (
    <div className="w-full space-y-3">
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
        <div className="space-y-2">
          <OutputField label="status" type="string" description="Completion status" />
          <OutputField label="waited_ms" type="number" description="Duration waited in milliseconds" />
        </div>
      )}
    </div>
  );
}

