import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { matchNodeClass, type NodeFamily, type BuilderNodeClass } from '../../nodes/nodeClasses';

interface InspectorNodeMeta {
  nodeClass: BuilderNodeClass | null;
  nodeFamily?: NodeFamily;
  nodeId: string;
  nodeKind: BuilderNodeData['kind'] | undefined;
  isApiNode: boolean;
  isToolNode: boolean;
  isMcpGenericNode: boolean;
  isMcpNode: boolean;
  isWebhookTrigger: boolean;
  isScheduleTrigger: boolean;
  isManualTrigger: boolean;
  isTablesTrigger: boolean;
  isWorkflowsTrigger: boolean;
  isChatTrigger: boolean;
  isFormTrigger: boolean;
  isTriggerGenericNode: boolean;
  isGenericEntryTrigger: boolean;
  isTriggerNode: boolean;
  isAiAgent: boolean;
  isAiSummarize: boolean;
  isGuardrail: boolean;
  isClassify: boolean;
  isAiGenericNode: boolean;
  isAiNode: boolean;
  isCoreGenericNode: boolean;
  isLogicSubcategory: boolean;
  isHttpRequest: boolean;
  isWebhook: boolean;
  isIfElse: boolean;
  isSwitch: boolean;
  isUserApproval: boolean;
  isWhile: boolean;
  isTransform: boolean;
  isMerge: boolean;
  isWait: boolean;
  isCoreNode: boolean;
  isInterfaceNode: boolean;
}

export function useInspectorNodeMeta(node: Node<BuilderNodeData> | null): InspectorNodeMeta {
  return React.useMemo(() => {
    const nodeClass = matchNodeClass(node?.data ?? null);
    const nodeFamily = nodeClass?.family;
    const nodeId = node?.data?.id || '';
    const nodeKind = node?.data?.kind;

    // MCP
    const isApiNode = (nodeFamily === 'mcp' && (node?.data as any)?.apiData !== undefined) || nodeId.startsWith('api-');
    const isToolNode = nodeFamily === 'mcp-tool' || nodeId.startsWith('tool-') || (node?.data as any)?.toolData !== undefined;
    const isMcpGenericNode =
      (!isApiNode && !isToolNode && (nodeFamily === 'mcp' || nodeFamily === 'mcp-resource')) ||
      nodeId.startsWith('mcp-');
    const isMcpNode = isApiNode || isToolNode || isMcpGenericNode;

    // Trigger
    const isWebhookTrigger = nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-');
    const isScheduleTrigger = nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-');
    const isManualTrigger = nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-');
    const isChatTrigger = nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-');
    const isFormTrigger = nodeId === 'form-trigger' || nodeId.startsWith('form-trigger-');
    // Check for tables trigger by ID or by dataSourceData (for nodes that were converted from generic triggers)
    // IMPORTANT: exclude CRUD nodes which also have dataSourceData but are NOT triggers
    const hasDataSourceData = (node?.data as any)?.dataSourceData !== undefined;
    const crudOperation = (node?.data as any)?.dataSourceData?.crudOperation;
    const validCrudOperations = ['create-row', 'read-row', 'update-row', 'delete-row', 'create-column', 'find-row',
      'insert_row', 'insert_rows', 'read_row', 'read_rows', 'update_row', 'update_rows', 'delete_row', 'delete_rows', 'find_row', 'find_rows', 'find'];
    const isCrudOperation = crudOperation && validCrudOperations.includes(crudOperation);
    const isTablesTrigger = nodeId === 'tables-trigger' || nodeId.startsWith('tables-trigger-') || (hasDataSourceData && !isCrudOperation);
    // Check for workflows trigger by ID or by workflowData (for nodes that were converted from generic triggers)
    const hasWorkflowData = (node?.data as any)?.workflowData !== undefined;
    const isWorkflowsTrigger = nodeId === 'workflows-trigger' || nodeId.startsWith('workflows-trigger-') || hasWorkflowData;
    const isTriggerGenericNode =
      (nodeId === 'triggers' || nodeId.startsWith('triggers-')) &&
      !isWebhookTrigger &&
      !isScheduleTrigger &&
      !isManualTrigger &&
      !isTablesTrigger &&
      !isWorkflowsTrigger &&
      !isChatTrigger &&
      !isFormTrigger;
    const isGenericEntryTrigger =
      nodeKind === 'entry' &&
      !isWebhookTrigger &&
      !isScheduleTrigger &&
      !isManualTrigger &&
      !isTablesTrigger &&
      !isWorkflowsTrigger &&
      !isChatTrigger &&
      !isFormTrigger &&
      !nodeId.includes('-trigger-');
    const isTriggerNodeFromFamily = nodeFamily === 'trigger' || nodeKind === 'entry';
    const isTriggerNode =
      isTriggerNodeFromFamily ||
      isTriggerGenericNode ||
      isGenericEntryTrigger ||
      isWebhookTrigger ||
      isScheduleTrigger ||
      isManualTrigger ||
      isTablesTrigger ||
      isWorkflowsTrigger ||
      isChatTrigger ||
      isFormTrigger;

    // AI
    const nodeIdForAi = nodeId;
    const isAiAgent = nodeIdForAi === 'ai-agent' || nodeIdForAi.startsWith('ai-agent-') || nodeClass?.id === 'ai-agent';
    const isAiSummarize = nodeIdForAi === 'ai-summarize' || nodeIdForAi.startsWith('ai-summarize-');
    const isGuardrail = nodeIdForAi === 'guardrail' || nodeIdForAi.startsWith('guardrail-');
    const isClassify = nodeIdForAi === 'classify' || nodeIdForAi.startsWith('classify-');
    // Browser agent - must be excluded from isAiGenericNode (otherwise the
    // inspector router falls back to the "choose an AI type" panel instead
    // of routing to the dedicated BrowserAgentParametersForm + BROWSER_AGENT
    // output schema). Detection mirrors the classify/guardrail pattern with
    // both id-prefix and kind checks for backward compat.
    const isBrowserAgent = nodeIdForAi === 'browser_agent'
      || nodeIdForAi.startsWith('browser_agent-')
      || nodeIdForAi === 'browser-agent'
      || nodeIdForAi.startsWith('browser-agent-')
      || node?.data?.kind === 'browser_agent';
    const isAiGenericNode =
      (nodeFamily === 'ai' && !isAiAgent && !isAiSummarize && !isGuardrail && !isClassify && !isBrowserAgent) ||
      (nodeIdForAi === 'ai' || (nodeIdForAi.startsWith('ai-') && !isAiAgent && !isAiSummarize && !isGuardrail && !isClassify && !isBrowserAgent));
    const isAiNode =
      isAiGenericNode || isAiAgent || isAiSummarize || isGuardrail || isClassify || isBrowserAgent || (node?.data?.kind === 'reasoning' && !nodeIdForAi.includes('-'));

    // Core
    const nodeData = node?.data as any;
    const nodeIdForCore = nodeData?.id || '';
    const isCoreGenericNode = nodeIdForCore === 'core' || nodeIdForCore.startsWith('core-');
    const isLogicSubcategory = nodeIdForCore === 'logic' || nodeIdForCore.startsWith('logic-');
    const isHttpRequest = nodeIdForCore === 'http-request' || nodeIdForCore.startsWith('http-request-');
    const isWebhook = nodeIdForCore === 'webhook' || nodeIdForCore.startsWith('webhook-');
    const isIfElse = nodeIdForCore === 'if-else' || nodeIdForCore.startsWith('if-else-');
    const isSwitch = nodeIdForCore === 'switch' || nodeIdForCore.startsWith('switch-');
    const isUserApproval = nodeIdForCore === 'user-approval' || nodeIdForCore.startsWith('user-approval-');
    const isWhile = nodeIdForCore === 'while' || nodeIdForCore.startsWith('while-');
    const isTransform = nodeIdForCore === 'transform' || nodeIdForCore.startsWith('transform-');
    const isMerge = nodeIdForCore === 'merge' || nodeIdForCore.startsWith('merge-') || node?.type === 'mergeNode';
    const isWait = nodeIdForCore === 'wait' || nodeIdForCore.startsWith('wait-') || (node?.type === 'flowNode' && nodeKind === 'wait');
    const isCoreNode =
      (isCoreGenericNode || isLogicSubcategory) && !isIfElse && !isSwitch && !isUserApproval && !isWhile && !isTransform && !isMerge && !isWait && !isHttpRequest && !isWebhook;
    
    // Interface node
    const isInterfaceNode = nodeIdForCore === 'interface' || nodeIdForCore.startsWith('interface-');

    return {
      nodeClass,
      nodeFamily,
      nodeId,
      nodeKind,
      isApiNode,
      isToolNode,
      isMcpGenericNode,
      isMcpNode,
      isWebhookTrigger,
      isScheduleTrigger,
      isManualTrigger,
      isTablesTrigger,
      isWorkflowsTrigger,
      isChatTrigger,
      isFormTrigger,
      isTriggerGenericNode,
      isGenericEntryTrigger,
      isTriggerNode,
      isAiAgent,
      isAiSummarize,
      isGuardrail,
      isClassify,
      isAiGenericNode,
      isAiNode,
      isCoreGenericNode,
      isLogicSubcategory,
      isHttpRequest,
      isWebhook,
      isIfElse,
      isSwitch,
      isUserApproval,
      isWhile,
      isTransform,
      isMerge,
      isWait,
      isCoreNode,
      isInterfaceNode,
    };
  }, [node]);
}
