import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Hook to detect various node types based on node ID patterns and node type.
 * Uses nodeRegistry as the single source of truth for node type detection.
 *
 * @param data - The node data object containing the node ID
 * @param node - Optional React Flow node object for type-based detection
 * @returns Object with boolean flags for each node type
 *
 * @example
 * ```tsx
 * const nodeTypes = useNodeTypeDetection(data, node);
 * if (nodeTypes.isTransformNode) {
 *   return <TransformParametersForm />;
 * }
 * ```
 */
export function useNodeTypeDetection(data: BuilderNodeData, node?: Node<BuilderNodeData> | null) {
  const nodeId = data?.id || '';
  const nodeType = node?.type || '';

  // DataSource-related nodes
  const dataSourceData = (data as any)?.dataSourceData;
  const hasDataSource = !!dataSourceData;

  // Workflow-related nodes
  const workflowData = (data as any)?.workflowData;
  const isSubWorkflow = node ? nodeRegistry.isSubWorkflowNode(node) : (data as any)?.kind === 'sub_workflow';
  const hasWorkflow = !!workflowData && !isSubWorkflow;

  // Create a mock node for registry methods when full node is not available
  const mockNode = node || { type: nodeType, data, id: nodeId } as Node<BuilderNodeData>;

  return {
    // Trigger types (ID-based detection - these are specific trigger subtypes)
    isTablesTrigger: nodeId.startsWith('tables-trigger-') ||
                     (hasDataSource && !nodeId.startsWith('create-') &&
                      !nodeId.startsWith('read-') && !nodeId.startsWith('update-') &&
                      !nodeId.startsWith('delete-') && !nodeId.startsWith('find-') &&
                      !nodeId.startsWith('list-')),
    isWorkflowsTrigger: nodeId.startsWith('workflows-trigger-') || hasWorkflow,
    isChatTrigger: nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-'),
    isManualTrigger: nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-'),
    isWebhookTrigger: nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-'),
    isScheduleTrigger: nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-'),
    isFormTrigger: nodeId === 'form-trigger' || nodeId.startsWith('form-trigger-'),
    isErrorTrigger: nodeId === 'error-trigger' || nodeId.startsWith('error-trigger-'),
    // CRUD operations (ID-based detection)
    isCreateRowNode: nodeId.startsWith('create-row'),
    isCreateColumnNode: nodeId.startsWith('create-column'),
    isReadRowNode: nodeId.startsWith('read-row'),
    isUpdateRowNode: nodeId.startsWith('update-row'),
    isDeleteRowNode: nodeId.startsWith('delete-row'),
    isFindRowNode: nodeRegistry.isFindNode(mockNode),
    isSplitLikeNode: nodeRegistry.isSplitLikeNode(mockNode),

    // Control nodes - using nodeRegistry
    isTransformNode: nodeRegistry.isTransformNode(mockNode),
    isMergeNode: nodeRegistry.isMergeNode(mockNode),
    isForkNode: nodeRegistry.isForkNode(mockNode),
    isWaitNode: nodeRegistry.isWaitNode(mockNode),
    isDownloadFileNode: nodeRegistry.isDownloadFileNode(mockNode),
    isPublicLinkNode: nodeRegistry.isPublicLinkNode(mockNode),
    isMediaNode: nodeRegistry.isMediaNode(mockNode),
    isHttpRequestNode: nodeRegistry.isHttpRequestNode(mockNode),
    isDataInputNode: nodeRegistry.isDataInputNode(mockNode),
    isDecisionNode: nodeRegistry.isDecisionNode(mockNode),
    isSwitchNode: nodeRegistry.isSwitchNode(mockNode),
    isLoopNode: nodeRegistry.isLoopNode(mockNode),
    isSplitNode: nodeRegistry.isSplitNode(mockNode),
    isAggregateNode: nodeRegistry.isAggregateNode(mockNode),
    isExitNode: nodeRegistry.isExitNode(mockNode),
    isResponseNode: nodeRegistry.isResponseNode(mockNode),
    isOptionNode: nodeRegistry.isOptionNode(mockNode),
    isUserApprovalNode: nodeRegistry.isUserApprovalNode(mockNode),
    isWhileGroupNode: nodeRegistry.isWhileGroupNode(mockNode),
    isSortNode: nodeRegistry.isSortNode(mockNode),
    isRemoveDuplicatesNode: nodeRegistry.isRemoveDuplicatesNode(mockNode),
    isCodeNode: nodeRegistry.isCodeNode(mockNode),
    isRespondToWebhookNode: nodeRegistry.isRespondToWebhookNode(mockNode),
    isDateTimeNode: nodeRegistry.isDateTimeNode(mockNode),
    isXmlNode: nodeRegistry.isXmlNode(mockNode),
    isCryptoJwtNode: nodeRegistry.isCryptoJwtNode(mockNode),
    isCompressionNode: nodeRegistry.isCompressionNode(mockNode),
    isConvertToFileNode: nodeRegistry.isConvertToFileNode(mockNode),
    isFilterNode: nodeRegistry.isFilterNode(mockNode),
    isRssNode: nodeRegistry.isRssNode(mockNode),
    isSubWorkflowNode: nodeRegistry.isSubWorkflowNode(mockNode),
    isLimitNode: nodeRegistry.isLimitNode(mockNode),
    isSendEmailNode: nodeRegistry.isSendEmailNode(mockNode),
    isEmailInboxNode: nodeRegistry.isEmailInboxNode(mockNode),
    isExtractFromFileNode: nodeRegistry.isExtractFromFileNode(mockNode),
    isCompareDatasetsNode: nodeRegistry.isCompareDatasetsNode(mockNode),
    isSummarizeNode: nodeRegistry.isSummarizeNode(mockNode),
    isSetNode: nodeRegistry.isSetNode(mockNode),
    isHtmlExtractNode: nodeRegistry.isHtmlExtractNode(mockNode),
    isTaskNode: nodeRegistry.isTaskNode(mockNode),
    isStopOnErrorNode: nodeRegistry.isStopOnErrorNode(mockNode),
    isSshNode: nodeRegistry.isSshNode(mockNode),
    isSftpNode: nodeRegistry.isSftpNode(mockNode),
    isDatabaseNode: nodeRegistry.isDatabaseNode(mockNode),

    // Interface nodes
    isInterfaceNode: nodeRegistry.isInterfaceNode(mockNode),

    // Agent-related nodes - use nodeRegistry
    isClassifyNode: nodeRegistry.isClassifyNode(mockNode),
    isGuardrailNode: nodeRegistry.isGuardrailNode(mockNode),
    isBrowserAgentNode: nodeRegistry.isBrowserAgentNode?.(mockNode) ?? false,

    // Conditional/Data access
    dataSourceData,
    workflowData,
    hasDataSource,
    hasWorkflow,

    // Raw node ID and type for custom checks
    nodeId,
    nodeType,
  };
}
