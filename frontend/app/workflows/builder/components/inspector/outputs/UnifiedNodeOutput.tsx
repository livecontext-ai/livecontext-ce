/**
 * UnifiedNodeOutput - Unified display component for node input/output.
 *
 * Uses:
 * - RunDataPreview for run mode (execution data for both input and output)
 * - StaticSchemaTree for build mode (schema display)
 * - LazyStructureTree for dynamic schemas (tools/MCP nodes)
 *
 * This provides a consistent visual style across all node types.
 */

'use client';

import * as React from 'react';
import clsx from 'clsx';
import { ChevronRight } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { getFieldTypeColor } from '../../../types';
import { NavigationButtons } from './NavigationButtons';
import { RunDataPreview } from './RunDataPreview';
import type { RunDataType } from '../../../hooks/useRunData';

// =============================================================================
// TYPES
// =============================================================================

export interface OutputSchema {
  key: string;
  type: string;
  description?: string;
  children?: OutputSchema[];
  /**
   * Runtime-only field: injected into the execution context at run time (e.g. a Split's
   * current_item / current_index, resolvable only inside the split body) and NOT part of the
   * persisted node output. Rendered with a "runtime" badge so authors know it is body-scoped.
   */
  runtimeOnly?: boolean;
}

export interface UnifiedNodeOutputProps {
  currentNode: Node<BuilderNodeData>;
  nextNodes?: Node<BuilderNodeData>[];
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  checkNodeError?: (node: Node<BuilderNodeData>) => boolean;
  getLoopIdFromNode?: (node: Node<BuilderNodeData> | undefined) => string | undefined;
  ArrowIcon?: React.ComponentType<{ node: Node<BuilderNodeData> }>;
  /** Static schema to display in build mode */
  schema: OutputSchema[];
  /** Prefix for drag expressions (e.g., "core", "agent", "trigger") - kept for compatibility but not used */
  nodePrefix?: string;
  /** Data type: input or output */
  dataType?: RunDataType;
  /** Run mode props */
  isRunMode?: boolean;
  workflowId?: string;
  runId?: string;
  showExecutionData?: boolean;
  /** Show navigation buttons */
  showNavigation?: boolean;
}

// =============================================================================
// UNIFIED NODE OUTPUT
// =============================================================================

export function UnifiedNodeOutput({
  currentNode,
  nextNodes = [],
  onNavigateToNode,
  checkNodeError,
  getLoopIdFromNode,
  ArrowIcon,
  schema,
  dataType = 'output',
  isRunMode = false,
  workflowId,
  runId,
  showExecutionData = true,
  showNavigation = true,
}: UnifiedNodeOutputProps) {
  const stepAlias = currentNode?.data?.label;

  const canShowExecutionData = isRunMode && showExecutionData && workflowId && runId && stepAlias;

  // Output column is never draggable - drag is only for ParameterColumn
  return (
    <div className="w-full space-y-2">
      {showNavigation && nextNodes.length > 0 && (
        <NavigationButtons
          nextNodes={nextNodes}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
        />
      )}

      {canShowExecutionData ? (
        <RunDataPreview
          workflowId={workflowId}
          runId={runId}
          stepAlias={stepAlias}
          dataType={dataType}
          isDraggable={false}
        />
      ) : (
        <StaticSchemaTree
          schema={schema}
          isDraggable={false}
        />
      )}
    </div>
  );
}

// =============================================================================
// STATIC SCHEMA TREE
// Renders static schema in LazyStructureTree visual style
// =============================================================================

interface StaticSchemaTreeProps {
  schema: OutputSchema[];
  isDraggable?: boolean;
  showBorder?: boolean;
}

export function StaticSchemaTree({
  schema,
  showBorder = false,
}: StaticSchemaTreeProps) {
  if (schema.length === 0) {
    return (
      <div className="text-sm text-slate-500 dark:text-slate-400 py-2 px-1">
        No output fields defined
      </div>
    );
  }

  return (
    <div className={clsx(showBorder && "pl-3 border-l border-slate-200 dark:border-slate-700")}>
      <div className="space-y-1">
        {schema.map((item) => (
          <SchemaNode
            key={item.key}
            item={item}
          />
        ))}
      </div>
    </div>
  );
}

// =============================================================================
// SCHEMA NODE
// Single node in the schema tree (expandable if has children)
// Output schema is read-only (no drag)
// =============================================================================

interface SchemaNodeProps {
  item: OutputSchema;
}

function SchemaNode({ item }: SchemaNodeProps) {
  const [isExpanded, setIsExpanded] = React.useState(false);

  const hasChildren = item.children && item.children.length > 0;
  const isExpandable = hasChildren || item.type === 'object' || item.type === 'array';

  // Runtime-only fields (e.g. a Split's current_item / current_index) resolve only inside the
  // node's body at run time and are not persisted. Flag them so authors don't expect them in the
  // stored output. Shown in both OutputColumn and the InputColumn ancestor variable picker.
  const runtimeBadge = item.runtimeOnly ? (
    <span
      className="text-[10px] px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0 bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300"
      title="Runtime value: resolvable only inside this node's body (e.g. {{item}} / current_item), not part of the persisted output"
    >
      runtime
    </span>
  ) : null;

  // Leaf node (no children, not object/array)
  if (!isExpandable) {
    return (
      <div
        className="flex items-center justify-between gap-2 text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1 cursor-default"
        title={item.description || item.key}
      >
        <span className="truncate flex-1 min-w-0 text-sm" title={item.key}>
          {item.key}
        </span>
        {runtimeBadge}
        <span
          className={clsx(
            "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
            getFieldTypeColor(item.type)
          )}
        >
          {item.type}
        </span>
      </div>
    );
  }

  // Expandable node (object or array with children)
  return (
    <div className="flex flex-col gap-1">
      <div
        className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full transition-colors rounded-sm px-1 py-1 cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        onClick={() => setIsExpanded(!isExpanded)}
        title={item.description || item.key}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <span className="truncate flex-1 min-w-0 text-sm" title={item.key}>
            {item.key}
          </span>
          <ChevronRight
            className={clsx(
              "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform mr-2",
              isExpanded && "rotate-90"
            )}
          />
        </div>
        {runtimeBadge}
        <span
          className={clsx(
            "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
            getFieldTypeColor(item.type)
          )}
        >
          {item.type}
        </span>
      </div>

      {isExpanded && hasChildren && (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700">
          <div className="space-y-1">
            {item.children!.map((child) => (
              <SchemaNode
                key={child.key}
                item={child}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// =============================================================================
// DATA INPUT (dynamic schema built from configuration)
// =============================================================================

/**
 * Default DATA_INPUT_SCHEMA - used as fallback when no items are configured.
 * Dynamic schemas are built from dataInputItems in OutputColumn and SourceCoreNodeInspector.
 */
export const DATA_INPUT_SCHEMA: OutputSchema[] = [];

/**
 * Execution envelope fields - common metadata available on every node's output.
 * These are injected by the execution engine, not defined by individual NodeSpecs.
 */
export const EXECUTION_ENVELOPE_SCHEMA: OutputSchema[] = [
  { key: '_status', type: 'text', description: 'Execution status (COMPLETED, FAILED, SKIPPED, AWAITING_SIGNAL)' },
  { key: '_error', type: 'text', description: 'Error message (when execution failed)' },
  { key: '_skip_reason', type: 'text', description: 'Why this node was skipped' },
  { key: '_skip_source_node', type: 'text', description: 'Node that caused the skip' },
  { key: '_duration_ms', type: 'number', description: 'Execution time in milliseconds' },
  { key: '_display_name', type: 'text', description: 'Display name of the user who triggered the execution' },
];

/**
 * Wraps a node-specific schema with the common execution envelope fields.
 */
export function withEnvelope(schema: OutputSchema[]): OutputSchema[] {
  return [...schema, ...EXECUTION_ENVELOPE_SCHEMA];
}

/**
 * Build a dynamic output schema from data input items.
 */
export function buildDataInputSchema(items: Array<{ label: string; type: string }> | undefined): OutputSchema[] {
  if (!items || items.length === 0) return [];
  return items.map((item) => ({
    key: item.label,
    type: item.type === 'file' ? 'object' : 'text',
    description: `${item.type === 'file' ? 'File' : 'Text'}: ${item.label}`,
    ...(item.type === 'file' ? {
      children: [
        { key: 'name', type: 'text' as const, description: 'File name' },
        { key: 'path', type: 'text' as const, description: 'File storage path' },
        { key: 'mimeType', type: 'text' as const, description: 'MIME type' },
        { key: 'size', type: 'number' as const, description: 'File size in bytes' },
      ],
    } : {}),
  }));
}

