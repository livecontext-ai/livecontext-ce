/**
 * OutputColumn - Displays node outputs.
 *
 * Each node type displays its own outputs (no passthrough).
 * In run mode with execution data, shows the actual execution results using RunDataPreview.
 * In build mode, shows static schema using StaticSchemaTree or LazyStructureTree.
 */

import * as React from 'react';
import { RefreshCcw } from 'lucide-react';
import { InspectorColumn } from './InspectorColumn';
import { TriggerOutput } from './outputs/TriggerOutput';
import { FormTriggerOutput } from './outputs/FormTriggerOutput';
import { NavigationButtons } from './outputs/NavigationButtons';
import { ToolNodeOutput } from './outputs/ToolNodeOutput';
import { AggregateNodeOutput } from './outputs/AggregateNodeOutput';
import {
  UnifiedNodeOutput,
  DATA_INPUT_SCHEMA,
  buildDataInputSchema,
  withEnvelope,
  type OutputSchema,
} from './outputs/UnifiedNodeOutput';
import { useNodeDefinitions } from '../../hooks/useNodeDefinitions';
import { useDataSourceColumns } from '../../hooks/useDataSourceData';
import { useWorkflowInputsOutputs } from '../../hooks/useWorkflowOutputs';
import { useNodeTypeDetection } from '../../hooks/useNodeTypeDetection';
import { useNextNodes, getLoopIdFromNode, useIsIterationNode } from '../../hooks/useNextNodes';
import { normalizeColumnType, removeDataPrefix } from '../../utils/typeNormalizer';
import { useValidation } from '../../contexts/ValidationContext';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { normalizeFieldType } from '../../types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { EmptyState } from '../shared/EmptyState';

/**
 * Columns whose names must NOT be surfaced as top-level output fields - they
 * collide with structured payload keys or legacy batch-scan keys emitted by
 * {@code TriggerPayloadBuilder}/{@code TableTriggerNodeSpec}. Such columns remain
 * reachable under {@code row.<name>} / {@code previous_row.<name>} /
 * {@code _inputs.<name>}, but {{trigger:<label>.output.<name>}} would resolve to
 * the reserved value (WARN emitted server-side).
 *
 * Keep in sync with TableTriggerNodeSpec.RESERVED_FIELDS (structured + engine
 * metadata) and TriggerPayloadBuilder.LEGACY_RESERVED_KEYS (batch-scan payload).
 */
const TABLE_TRIGGER_RESERVED_FIELDS: ReadonlySet<string> = new Set([
  // Structured payload keys (TableTriggerNodeSpec)
  'row', 'previous_row', 'event_type', 'row_id', 'datasource_id', 'triggered_at',
  // Engine metadata
  'node_type', 'item_index', 'itemIndex', 'item_id', 'itemId',
  'iteration', 'currentIteration', 'http_status',
  'tenant_id', 'tenantId', 'trigger_id', 'triggerId',
  'epoch', 'spawn', 'absoluteIndex',
  // Legacy batch-scan payload keys (TriggerPayloadBuilder.LEGACY_RESERVED_KEYS)
  'data', 'count', 'totalCount', 'realTotalCount',
  'offset', 'limit', 'hasMore', 'nextOffset', 'status', 'source',
  'strategy', 'maxItemsCap', 'maxItemsReached', '_inputs',
  'error', 'message',
]);

interface ColumnDef {
  field?: string;
  col_id?: string;
  header_name?: string;
  type?: string;
}

/**
 * Build the datasource-trigger output schema from the datasource's DB columns.
 *
 * Mirrors the runtime payload shape built by DatasourceTriggerDispatchService +
 * TableTriggerNodeSpec: structured metadata first, then row/previous_row objects,
 * then DB columns flattened at the top level (skipping names that collide with
 * reserved keys - those stay reachable only under row.<name>).
 */
function buildTableTriggerSchema(columns: ColumnDef[] | undefined): OutputSchema[] {
  const rowChildren: OutputSchema[] = (columns || [])
    .map((col): OutputSchema | null => {
      const rawField = col.field || col.col_id || '';
      const name = removeDataPrefix(rawField);
      if (!name) return null;
      return {
        key: name,
        type: normalizeColumnType(col.type),
        description: col.header_name && col.header_name !== name ? col.header_name : undefined,
      };
    })
    .filter((c): c is OutputSchema => c !== null);

  const flattenedTopLevel: OutputSchema[] = rowChildren
    .filter((c) => !TABLE_TRIGGER_RESERVED_FIELDS.has(c.key))
    .map((c) => ({
      ...c,
      description: c.description
        ? `${c.description} (flattened from row)`
        : 'Flattened from row - same value as row.' + c.key,
    }));

  return [
    { key: 'event_type', type: 'text', description: 'row_created | row_updated | row_deleted' },
    { key: 'row_id', type: 'number', description: 'ID of the affected row in the datasource.' },
    { key: 'datasource_id', type: 'number', description: 'ID of the datasource that emitted the event.' },
    { key: 'triggered_at', type: 'text', description: 'ISO-8601 timestamp captured after commit.' },
    {
      key: 'row',
      type: 'object',
      description: 'Row that triggered the event. Current state for row_created/row_updated; last-known state for row_deleted.',
      children: rowChildren,
    },
    {
      key: 'previous_row',
      type: 'object',
      description: 'Pre-change row. Populated only for row_updated; null otherwise.',
      children: rowChildren,
    },
    ...flattenedTopLevel,
  ];
}

interface OutputColumnProps {
  isToolNode: boolean;
  toolDetails: any;
  onNavigateToNode?: (nodeId: string, loopId?: string) => void;
  currentNode?: Node<BuilderNodeData> | null;
  allNodes?: Node<BuilderNodeData>[];
  edges?: Edge[];
  selectedLoopChild?: { loopId: string; childId: string } | null;
  embedded?: boolean;
  isAgentNode?: boolean;
  currentWorkflowId?: string;
  currentRunId?: string;
  showExecutionData?: boolean;
  /** Forwarded to RunDataPreview: publishes the loaded output object (the Output header settings menu consumes it). */
  onLoadedOutputChange?: (data: unknown | null) => void;
}

export const OutputColumn = ({
  isToolNode,
  toolDetails,
  onNavigateToNode,
  currentNode,
  allNodes = [],
  edges = [],
  selectedLoopChild,
  embedded = false,
  isAgentNode = false,
  currentWorkflowId,
  currentRunId,
  showExecutionData = true,
  onLoadedOutputChange,
}: OutputColumnProps) => {
  const { isRunMode, runId: contextRunId } = useWorkflowMode();
  const effectiveRunId = currentRunId || contextRunId;
  const { hasNodeErrors: checkNodeErrorFromContext } = useValidation();
  const [treeKey] = React.useState(0);

  // Centralized node type detection
  const nodeTypes = useNodeTypeDetection(currentNode?.data || {} as BuilderNodeData, currentNode);
  const {
    isDecisionNode,
    isSwitchNode,
    isClassifyNode,
    isGuardrailNode,
    isBrowserAgentNode,
    isLoopNode,
    isWhileGroupNode,
    isSplitNode,
    isAggregateNode,
    isExitNode,
    isResponseNode,
    isMergeNode,
    isForkNode,
    isTransformNode,
    isWaitNode,
    isDownloadFileNode,
    isHttpRequestNode,
    isDataInputNode,
    isOptionNode,
    isUserApprovalNode,
    isManualTrigger,
    isChatTrigger,
    isWebhookTrigger,
    isScheduleTrigger,
    isFormTrigger,
    isTablesTrigger,
    isWorkflowsTrigger,
    isCreateRowNode,
    isCreateColumnNode,
    isReadRowNode,
    isUpdateRowNode,
    isDeleteRowNode,
    isFindRowNode,
    isErrorTrigger,
    isFilterNode,
    isSortNode,
    isLimitNode,
    isRemoveDuplicatesNode,
    isSummarizeNode,
    isDateTimeNode,
    isCryptoJwtNode,
    isXmlNode,
    isCompressionNode,
    isRssNode,
    isConvertToFileNode,
    isExtractFromFileNode,
    isCompareDatasetsNode,
    isSubWorkflowNode,
    isRespondToWebhookNode,
    isSendEmailNode,
    isEmailInboxNode,
    isCodeNode,
    isSetNode,
    isHtmlExtractNode,
    isTaskNode,
    isStopOnErrorNode,
    isSshNode,
    isSftpNode,
    isDatabaseNode,
    isInterfaceNode,
    dataSourceData,
    workflowData,
  } = nodeTypes;

  // Extract datasource and workflow IDs
  const dataSourceId = dataSourceData?.dataSourceId;
  const { data: columns } = useDataSourceColumns(
    isTablesTrigger && dataSourceId ? dataSourceId : null
  );

  const workflowId = workflowData?.workflowId;
  const { data: workflowIO, isLoading: isLoadingWorkflowIO } = useWorkflowInputsOutputs(
    isWorkflowsTrigger && workflowId ? workflowId : null
  );

  const structureId = React.useMemo(() => {
    if (!isToolNode || !toolDetails?.responses) return null;
    const defaultResponse = toolDetails.responses.find((r: any) => r.isDefault) || toolDetails.responses[0];
    return defaultResponse?.id || null;
  }, [isToolNode, toolDetails]);

  // Use hook for iteration node detection
  const isIterationNode = useIsIterationNode(allNodes, edges);

  // Helper component to render the appropriate arrow icon
  const ArrowIcon = React.useCallback(({ node }: { node: Node<BuilderNodeData> }) => {
    if (isIterationNode(node)) {
      return <RefreshCcw className="h-3 w-3" />;
    }
    return (
      <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="lucide lucide-arrow-right h-3 w-3" aria-hidden="true">
        <path d="M5 12h14"></path>
        <path d="m12 5 7 7-7 7"></path>
      </svg>
    );
  }, [isIterationNode]);

  // Use hook for calculating next nodes
  const nextNodes = useNextNodes({
    currentNode,
    allNodes,
    edges,
    selectedLoopChild,
  });

  // Helper function to check if a node has errors
  const checkNodeError = React.useCallback((node: Node<BuilderNodeData>): boolean => {
    const nodeStatus = node.data?.status as string | undefined;
    if (nodeStatus === 'failed') {
      return true;
    }

    const statusCounts = node.data?.statusCounts;
    if (statusCounts) {
      const errorCount = (statusCounts.FAILED || statusCounts.failed || 0);
      if (errorCount > 0) {
        return true;
      }
    }

    return checkNodeErrorFromContext(node.id);
  }, [checkNodeErrorFromContext]);

  // Build the table trigger schema dynamically from DB columns. Mirrors the
  // runtime payload: fixed meta fields (event_type, row_id, datasource_id,
  // triggered_at) + row/previous_row objects with the DB columns as children +
  // flattened columns at the top level (skipping reserved names).
  const tableTriggerSchema = React.useMemo(() => {
    if (!isTablesTrigger) return [];
    return buildTableTriggerSchema(columns as ColumnDef[] | undefined);
  }, [isTablesTrigger, columns]);

  // Get workflow outputs
  const workflowOutputs = React.useMemo(() => {
    if (!isWorkflowsTrigger || !workflowId || !workflowIO) return [];

    return workflowIO.outputs.map((output: any) => ({
      field: output.field,
      label: output.label,
      type: output.type,
      displayType: normalizeFieldType(output.type),
    }));
  }, [isWorkflowsTrigger, workflowId, workflowIO]);

  // Get aggregate fields for Aggregate node
  const aggregateFields = React.useMemo(() => {
    if (!isAggregateNode || !currentNode?.data) return [];
    return (currentNode.data as any).aggregateFields || [{ id: 'default-field-1', label: 'field_1', expression: '' }];
  }, [isAggregateNode, currentNode?.data]);

  // Centralized schema lookup from backend NodeDefinitions
  const { getOutputSchema } = useNodeDefinitions();

  // Determine schema and prefix based on node type
  const { schema, nodePrefix } = React.useMemo(() => {
    // Core control flow nodes
    if (isDecisionNode) return { schema: withEnvelope(getOutputSchema('DECISION')), nodePrefix: 'core' };
    if (isSwitchNode) return { schema: withEnvelope(getOutputSchema('SWITCH')), nodePrefix: 'core' };
    if (isForkNode) return { schema: withEnvelope(getOutputSchema('FORK')), nodePrefix: 'core' };
    if (isWaitNode) return { schema: withEnvelope(getOutputSchema('WAIT')), nodePrefix: 'core' };
    if (isLoopNode || isWhileGroupNode) return { schema: withEnvelope(getOutputSchema('LOOP')), nodePrefix: 'core' };
    if (isSplitNode) return { schema: withEnvelope(getOutputSchema('SPLIT')), nodePrefix: 'core' };
    if (isAggregateNode) return { schema: withEnvelope(getOutputSchema('AGGREGATE')), nodePrefix: 'core' };
    if (isExitNode) return { schema: withEnvelope(getOutputSchema('EXIT')), nodePrefix: 'core' };
    if (isResponseNode) return { schema: withEnvelope(getOutputSchema('RESPONSE')), nodePrefix: 'core' };
    if (isMergeNode) return { schema: withEnvelope(getOutputSchema('MERGE')), nodePrefix: 'core' };
    if (isTransformNode) return { schema: withEnvelope(getOutputSchema('TRANSFORM')), nodePrefix: 'core' };
    if (isDownloadFileNode) return { schema: withEnvelope(getOutputSchema('DOWNLOAD_FILE')), nodePrefix: 'core' };
    if (isHttpRequestNode) return { schema: withEnvelope(getOutputSchema('HTTP_REQUEST')), nodePrefix: 'core' };
    if (isDataInputNode) {
      const dataInputItems = (currentNode?.data as any)?.dataInputItems;
      const dynamicSchema = buildDataInputSchema(dataInputItems);
      return { schema: withEnvelope(dynamicSchema.length > 0 ? dynamicSchema : DATA_INPUT_SCHEMA), nodePrefix: 'core' };
    }
    if (isOptionNode) return { schema: withEnvelope(getOutputSchema('OPTION')), nodePrefix: 'core' };
    if (isUserApprovalNode) return { schema: withEnvelope(getOutputSchema('APPROVAL')), nodePrefix: 'core' };
    // Data manipulation nodes
    if (isFilterNode) return { schema: withEnvelope(getOutputSchema('FILTER')), nodePrefix: 'core' };
    if (isSortNode) return { schema: withEnvelope(getOutputSchema('SORT')), nodePrefix: 'core' };
    if (isLimitNode) return { schema: withEnvelope(getOutputSchema('LIMIT')), nodePrefix: 'core' };
    if (isRemoveDuplicatesNode) return { schema: withEnvelope(getOutputSchema('REMOVE_DUPLICATES')), nodePrefix: 'core' };
    if (isSummarizeNode) return { schema: withEnvelope(getOutputSchema('SUMMARIZE')), nodePrefix: 'core' };
    if (isDateTimeNode) return { schema: withEnvelope(getOutputSchema('DATE_TIME')), nodePrefix: 'core' };
    if (isCryptoJwtNode) return { schema: withEnvelope(getOutputSchema('CRYPTO_JWT')), nodePrefix: 'core' };
    if (isXmlNode) return { schema: withEnvelope(getOutputSchema('XML')), nodePrefix: 'core' };
    if (isCompressionNode) return { schema: withEnvelope(getOutputSchema('COMPRESSION')), nodePrefix: 'core' };
    if (isRssNode) return { schema: withEnvelope(getOutputSchema('RSS')), nodePrefix: 'core' };
    if (isConvertToFileNode) return { schema: withEnvelope(getOutputSchema('CONVERT_TO_FILE')), nodePrefix: 'core' };
    if (isExtractFromFileNode) return { schema: withEnvelope(getOutputSchema('EXTRACT_FROM_FILE')), nodePrefix: 'core' };
    if (isCompareDatasetsNode) return { schema: withEnvelope(getOutputSchema('COMPARE_DATASETS')), nodePrefix: 'core' };
    if (isSubWorkflowNode) return { schema: withEnvelope(getOutputSchema('SUB_WORKFLOW')), nodePrefix: 'core' };
    if (isRespondToWebhookNode) return { schema: withEnvelope(getOutputSchema('RESPOND_TO_WEBHOOK')), nodePrefix: 'core' };
    if (isSendEmailNode) return { schema: withEnvelope(getOutputSchema('SEND_EMAIL')), nodePrefix: 'core' };
    if (isEmailInboxNode) return { schema: withEnvelope(getOutputSchema('EMAIL_INBOX')), nodePrefix: 'core' };
    if (isCodeNode) return { schema: withEnvelope(getOutputSchema('CODE')), nodePrefix: 'core' };
    if (isSetNode) return { schema: withEnvelope(getOutputSchema('SET')), nodePrefix: 'core' };
    if (isHtmlExtractNode) return { schema: withEnvelope(getOutputSchema('HTML_EXTRACT')), nodePrefix: 'core' };
    if (isTaskNode) return { schema: withEnvelope(getOutputSchema('TASK')), nodePrefix: 'core' };
    if (isStopOnErrorNode) return { schema: withEnvelope(getOutputSchema('STOP_ON_ERROR')), nodePrefix: 'core' };
    if (isSshNode) return { schema: withEnvelope(getOutputSchema('SSH')), nodePrefix: 'core' };
    if (isSftpNode) return { schema: withEnvelope(getOutputSchema('SFTP')), nodePrefix: 'core' };
    if (isDatabaseNode) return { schema: withEnvelope(getOutputSchema('DATABASE')), nodePrefix: 'core' };
    // Interface nodes - outputs from InterfaceNodeSpec (interface_id, action_mapping, is_entry_interface).
    // The dynamic output.{action_name} per-action data is merged at signal resolution and is not part
    // of the static spec - it's documented in node_type_documentation but cannot be enumerated here.
    if (isInterfaceNode) return { schema: withEnvelope(getOutputSchema('INTERFACE')), nodePrefix: 'interface' };
    // Agent nodes
    if (isAgentNode) return { schema: withEnvelope(getOutputSchema('AGENT')), nodePrefix: 'agent' };
    if (isClassifyNode) return { schema: withEnvelope(getOutputSchema('CLASSIFY')), nodePrefix: 'agent' };
    if (isGuardrailNode) return { schema: withEnvelope(getOutputSchema('GUARDRAIL')), nodePrefix: 'agent' };
    if (isBrowserAgentNode) return { schema: withEnvelope(getOutputSchema('BROWSER_AGENT')), nodePrefix: 'agent' };
    // Trigger nodes (Form trigger is handled separately with dynamic fields)
    if (isManualTrigger) return { schema: withEnvelope(getOutputSchema('MANUAL_TRIGGER')), nodePrefix: 'trigger' };
    if (isChatTrigger) return { schema: withEnvelope(getOutputSchema('CHAT_TRIGGER')), nodePrefix: 'trigger' };
    if (isWebhookTrigger) return { schema: withEnvelope(getOutputSchema('WEBHOOK_TRIGGER')), nodePrefix: 'trigger' };
    if (isScheduleTrigger) return { schema: withEnvelope(getOutputSchema('SCHEDULE_TRIGGER')), nodePrefix: 'trigger' };
    if (isErrorTrigger) return { schema: withEnvelope(getOutputSchema('ERROR_TRIGGER')), nodePrefix: 'trigger' };
    if (isTablesTrigger) return { schema: withEnvelope(tableTriggerSchema), nodePrefix: 'trigger' };
    // CRUD nodes (Tables)
    if (isFindRowNode) return { schema: withEnvelope(getOutputSchema('FIND')), nodePrefix: 'table' };
    if (isCreateRowNode) return { schema: withEnvelope(getOutputSchema('INSERT_ROW')), nodePrefix: 'table' };
    if (isReadRowNode) return { schema: withEnvelope(getOutputSchema('GET_ROWS')), nodePrefix: 'table' };
    if (isUpdateRowNode) return { schema: withEnvelope(getOutputSchema('UPDATE_ROW')), nodePrefix: 'table' };
    if (isDeleteRowNode) return { schema: withEnvelope(getOutputSchema('DELETE_ROW')), nodePrefix: 'table' };
    if (isCreateColumnNode) return { schema: withEnvelope(getOutputSchema('CREATE_COLUMN')), nodePrefix: 'table' };
    return { schema: [], nodePrefix: 'mcp' };
  }, [
    isDecisionNode, isSwitchNode, isForkNode, isWaitNode,
    isLoopNode, isWhileGroupNode, isSplitNode, isAggregateNode, isExitNode, isResponseNode, isMergeNode, isTransformNode, isDownloadFileNode, isHttpRequestNode, isDataInputNode, isOptionNode, isUserApprovalNode,
    isFilterNode, isSortNode, isLimitNode, isRemoveDuplicatesNode, isSummarizeNode,
    isDateTimeNode, isCryptoJwtNode, isXmlNode, isCompressionNode, isRssNode,
    isConvertToFileNode, isExtractFromFileNode, isCompareDatasetsNode,
    isSubWorkflowNode, isRespondToWebhookNode, isSendEmailNode, isEmailInboxNode, isCodeNode, isSetNode, isHtmlExtractNode, isTaskNode, isStopOnErrorNode, isSshNode, isSftpNode, isDatabaseNode,
    isInterfaceNode,
    isAgentNode, isClassifyNode, isGuardrailNode, isBrowserAgentNode,
    isManualTrigger, isChatTrigger, isWebhookTrigger, isScheduleTrigger, isErrorTrigger,
    isTablesTrigger, tableTriggerSchema,
    isFindRowNode, isCreateRowNode, isCreateColumnNode, isReadRowNode, isUpdateRowNode, isDeleteRowNode,
    currentNode?.data, getOutputSchema,
  ]);

  // Content to render using UnifiedNodeOutput
  const outputContent = (
    <>
      {/* Dynamic outputs (workflow trigger - its outputs are defined per-workflow, not by a static spec) */}
      {isWorkflowsTrigger && workflowId ? (
        <TriggerOutput
          triggerType="workflow"
          isLoading={isLoadingWorkflowIO}
          outputs={workflowOutputs}
          nextNodes={nextNodes}
          currentNode={currentNode}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
          isRunMode={isRunMode}
          workflowId={currentWorkflowId}
          runId={effectiveRunId}
          showExecutionData={showExecutionData}
        />
      ) : isToolNode && currentNode ? (
        /* Tool nodes use LazyStructureTree for dynamic schema */
        <ToolNodeOutput
          structureId={structureId}
          treeKey={treeKey}
          nextNodes={nextNodes}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
          currentNode={currentNode}
          isRunMode={isRunMode}
          workflowId={currentWorkflowId}
          runId={effectiveRunId}
          showExecutionData={showExecutionData}
          onLoadedOutputChange={onLoadedOutputChange}
        />
      ) : isAggregateNode && currentNode ? (
        /* Aggregate nodes show dynamic fields from aggregateFields config */
        <AggregateNodeOutput
          aggregateFields={aggregateFields}
          nextNodes={nextNodes}
          currentNode={currentNode}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
          isRunMode={isRunMode}
          workflowId={currentWorkflowId}
          runId={effectiveRunId}
          showExecutionData={showExecutionData}
        />
      ) : isFormTrigger && currentNode ? (
        /* Form trigger shows dynamic fields based on form configuration */
        <FormTriggerOutput
          currentNode={currentNode}
          nextNodes={nextNodes}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
          isRunMode={isRunMode}
          workflowId={currentWorkflowId}
          runId={effectiveRunId}
          showExecutionData={showExecutionData}
        />
      ) : currentNode && schema.length > 0 ? (
        /* All other nodes use UnifiedNodeOutput */
        <UnifiedNodeOutput
          currentNode={currentNode}
          nextNodes={nextNodes}
          onNavigateToNode={onNavigateToNode}
          checkNodeError={checkNodeError}
          getLoopIdFromNode={getLoopIdFromNode}
          ArrowIcon={ArrowIcon}
          schema={schema}
          nodePrefix={nodePrefix}
          dataType="output"
          isRunMode={isRunMode}
          workflowId={currentWorkflowId}
          runId={effectiveRunId}
          showExecutionData={showExecutionData}
          onLoadedOutputChange={onLoadedOutputChange}
        />
      ) : (
        <div className="w-full space-y-2">
          <NavigationButtons
            nextNodes={nextNodes}
            onNavigateToNode={onNavigateToNode}
            checkNodeError={checkNodeError}
            getLoopIdFromNode={getLoopIdFromNode}
            ArrowIcon={ArrowIcon}
          />
          {nextNodes.length === 0 && (
            <EmptyState message="No output data available" />
          )}
        </div>
      )}
    </>
  );

  if (embedded) {
    return <div className="p-3">{outputContent}</div>;
  }

  return (
    <InspectorColumn title="Output">
      {outputContent}
    </InspectorColumn>
  );
};
