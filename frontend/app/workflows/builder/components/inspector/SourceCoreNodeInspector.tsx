/**
 * SourceCoreNodeInspector - Shows the OUTPUT of a core node (decision, switch, fork, wait, download_file, http_request, etc.)
 *
 * This replaces the old passthrough behavior where we showed the source nodes' outputs.
 * Now each core node has its own output schema that we display.
 */

import * as React from 'react';
import clsx from 'clsx';
import { GripVertical, ChevronRight, ArrowLeft } from 'lucide-react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { getFieldTypeColor } from '../../types';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { nodeRegistry } from '../../registry/nodeRegistry';
import {
  DATA_INPUT_SCHEMA,
  buildDataInputSchema,
  withEnvelope,
  type OutputSchema,
} from './outputs/UnifiedNodeOutput';
import { useNodeDefinitions } from '../../hooks/useNodeDefinitions';
import { NodeIcon, getIconSlug } from '../nodes/shared';

interface SourceCoreNodeInspectorProps {
  node: Node<BuilderNodeData>;
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  isDraggable?: boolean;
  isRunMode?: boolean;
}

/**
 * Get the output schema for a core node based on its type,
 * using centralized definitions from the backend API.
 */
function getCoreNodeSchema(
  node: Node<BuilderNodeData>,
  getOutputSchema: (nodeType: string) => OutputSchema[],
): OutputSchema[] {
  if (nodeRegistry.isDecisionNode(node)) return getOutputSchema('DECISION');
  if (nodeRegistry.isSwitchNode(node)) return getOutputSchema('SWITCH');
  if (nodeRegistry.isForkNode(node)) return getOutputSchema('FORK');
  if (nodeRegistry.isWaitNode(node)) return getOutputSchema('WAIT');
  if (nodeRegistry.isLoopNode(node) || nodeRegistry.isWhileGroupNode(node)) return getOutputSchema('LOOP');
  if (nodeRegistry.isSplitNode(node)) return getOutputSchema('SPLIT');
  if (nodeRegistry.isMergeNode(node)) return getOutputSchema('MERGE');
  if (nodeRegistry.isTransformNode(node)) return getOutputSchema('TRANSFORM');
  if (nodeRegistry.isDownloadFileNode(node)) return getOutputSchema('DOWNLOAD_FILE');
  if (nodeRegistry.isHttpRequestNode(node)) return getOutputSchema('HTTP_REQUEST');
  if (nodeRegistry.isUserApprovalNode(node)) return getOutputSchema('APPROVAL');
  if (nodeRegistry.isResponseNode(node)) return getOutputSchema('RESPONSE');
  if (nodeRegistry.isSetNode(node)) return getOutputSchema('SET');
  if (nodeRegistry.isHtmlExtractNode(node)) return getOutputSchema('HTML_EXTRACT');
  if (nodeRegistry.isFilterNode(node)) return getOutputSchema('FILTER');
  if (nodeRegistry.isSortNode(node)) return getOutputSchema('SORT');
  if (nodeRegistry.isLimitNode(node)) return getOutputSchema('LIMIT');
  if (nodeRegistry.isRemoveDuplicatesNode(node)) return getOutputSchema('REMOVE_DUPLICATES');
  if (nodeRegistry.isSummarizeNode(node)) return getOutputSchema('SUMMARIZE');
  if (nodeRegistry.isDateTimeNode(node)) return getOutputSchema('DATE_TIME');
  if (nodeRegistry.isCryptoJwtNode(node)) return getOutputSchema('CRYPTO_JWT');
  if (nodeRegistry.isXmlNode(node)) return getOutputSchema('XML');
  if (nodeRegistry.isCompressionNode(node)) return getOutputSchema('COMPRESSION');
  if (nodeRegistry.isRssNode(node)) return getOutputSchema('RSS');
  if (nodeRegistry.isConvertToFileNode(node)) return getOutputSchema('CONVERT_TO_FILE');
  if (nodeRegistry.isExtractFromFileNode(node)) return getOutputSchema('EXTRACT_FROM_FILE');
  if (nodeRegistry.isCompareDatasetsNode(node)) return getOutputSchema('COMPARE_DATASETS');
  if (nodeRegistry.isSubWorkflowNode(node)) return getOutputSchema('SUB_WORKFLOW');
  if (nodeRegistry.isRespondToWebhookNode(node)) return getOutputSchema('RESPOND_TO_WEBHOOK');
  if (nodeRegistry.isSendEmailNode(node)) return getOutputSchema('SEND_EMAIL');
  if (nodeRegistry.isEmailInboxNode(node)) return getOutputSchema('EMAIL_INBOX');
  if (nodeRegistry.isCodeNode(node)) return getOutputSchema('CODE');
  if (nodeRegistry.isTaskNode(node)) return getOutputSchema('TASK');
  if (nodeRegistry.isStopOnErrorNode(node)) return getOutputSchema('STOP_ON_ERROR');
  if (nodeRegistry.isSshNode(node)) return getOutputSchema('SSH');
  if (nodeRegistry.isSftpNode(node)) return getOutputSchema('SFTP');
  if (nodeRegistry.isDatabaseNode(node)) return getOutputSchema('DATABASE');
  if (nodeRegistry.isAggregateNode(node)) return getOutputSchema('AGGREGATE');

  if (nodeRegistry.isDataInputNode(node)) {
    const dataInputItems = (node.data as any)?.dataInputItems;
    const dynamicSchema = buildDataInputSchema(dataInputItems);
    return dynamicSchema.length > 0 ? dynamicSchema : DATA_INPUT_SCHEMA;
  }

  if (nodeRegistry.isFindNode(node)) return getOutputSchema('FIND');

  if (nodeRegistry.isCrudNode(node)) {
    const crudOperation = (node.data as any)?.dataSourceData?.crudOperation;
    switch (crudOperation) {
      case 'create-row': return getOutputSchema('INSERT_ROW');
      case 'read-row': return getOutputSchema('GET_ROWS');
      case 'update-row': return getOutputSchema('UPDATE_ROW');
      case 'delete-row': return getOutputSchema('DELETE_ROW');
      case 'create-column': return getOutputSchema('CREATE_COLUMN');
      default: return [];
    }
  }

  return [];
}

/**
 * Renders the output schema fields for a core node
 */
function SchemaFieldRow({
  field,
  dragPrefix,
  isDraggable,
  path = [],
}: {
  field: OutputSchema;
  dragPrefix: string;
  isDraggable: boolean;
  path?: string[];
}) {
  const [isExpanded, setIsExpanded] = React.useState(false);
  const hasChildren = field.children && field.children.length > 0;
  const isExpandable = hasChildren || field.type === 'object' || field.type === 'array';

  const currentPath = [...path, field.key];
  const fullPath = `{{${dragPrefix}.${currentPath.join('.')}}}`;

  const handleDragStart = (e: React.DragEvent) => {
    if (!isDraggable) return;
    e.stopPropagation();
    e.dataTransfer.setData('text/plain', fullPath);
    e.dataTransfer.effectAllowed = 'copy';
  };

  if (!isExpandable) {
    return (
      <div
        className={clsx(
          "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 transition-colors rounded-sm",
          isDraggable
            ? "cursor-grab active:cursor-grabbing hover:bg-slate-50 dark:hover:bg-slate-800"
            : "cursor-default"
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        title={isDraggable ? fullPath : field.key}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm">{field.key}</span>
        </div>
        <span
          className={clsx(
            "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
            getFieldTypeColor(field.type)
          )}
        >
          {field.type}
        </span>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      <div
        className={clsx(
          "flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 transition-colors rounded-sm",
          "cursor-pointer hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800"
        )}
        draggable={isDraggable}
        onDragStart={handleDragStart}
        onClick={() => setIsExpanded(!isExpanded)}
        title={isDraggable ? fullPath : field.key}
      >
        <div className="flex items-center gap-2 flex-1 min-w-0">
          {isDraggable && (
            <GripVertical className="h-3.5 w-3.5 text-slate-500 dark:text-slate-400 cursor-grab active:cursor-grabbing flex-shrink-0" />
          )}
          <span className="truncate flex-1 min-w-0 text-sm">{field.key}</span>
          <ChevronRight
            className={clsx(
              "h-3 w-3 text-slate-400 dark:text-slate-500 transition-transform mr-2",
              isExpanded && "rotate-90"
            )}
          />
        </div>
        <span
          className={clsx(
            "text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono flex-shrink-0",
            getFieldTypeColor(field.type)
          )}
        >
          {field.type}
        </span>
      </div>

      {isExpanded && hasChildren && (
        <div className="pl-3 border-l border-slate-200 dark:border-slate-700 space-y-1">
          {field.children!.map((child) => (
            <SchemaFieldRow
              key={child.key}
              field={child}
              dragPrefix={dragPrefix}
              isDraggable={isDraggable}
              path={currentPath}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export const SourceCoreNodeInspector = React.memo(function SourceCoreNodeInspector({
  node,
  onNavigateToNode,
  isDraggable = true,
  isRunMode = false,
}: SourceCoreNodeInspectorProps) {
  const loopId = (node.data as any)?._loopId;
  const { getOutputSchema } = useNodeDefinitions();
  const schema = withEnvelope(getCoreNodeSchema(node, getOutputSchema));
  const nodeLabel = node.data?.label || node.id;
  const normalizedLabel = normalizeLabel(nodeLabel) || nodeLabel;

  // Build drag prefix: core:label.output (or table: for CRUD/Find nodes)
  const prefix = (nodeRegistry.isCrudNode(node) || nodeRegistry.isFindNode(node)) ? 'table' : 'core';
  const dragPrefix = `${prefix}:${normalizedLabel}.output`;

  const handleNavigate = React.useCallback(() => {
    onNavigateToNode?.(node.id, loopId);
  }, [node.id, loopId, onNavigateToNode]);

  if (schema.length === 0) {
    return null;
  }

  return (
    <div className="mb-3">
      {/* Navigation header */}
      <button
        onClick={handleNavigate}
        className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 mb-1"
        title={`Go to ${nodeLabel}`}
      >
        <ArrowLeft className="h-3 w-3 flex-shrink-0" />
        <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
        <span className="truncate max-w-[200px] font-medium">{nodeLabel}</span>
      </button>

      {/* Output schema fields */}
      <div className="space-y-1 pl-4">
        {schema.map((field) => (
          <SchemaFieldRow
            key={field.key}
            field={field}
            dragPrefix={dragPrefix}
            isDraggable={isDraggable && !isRunMode}
          />
        ))}
      </div>
    </div>
  );
});
