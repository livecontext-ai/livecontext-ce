import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

interface TriggerNavigationState {
  triggerNavigationLevel: 'triggers' | 'types' | 'datasources' | 'tables' | 'workflows';
  triggerSelectedType: string | null;
  triggerSearchQuery: string;
  selectedDataSourceId: number | null;
  setTriggerNavigationLevel: React.Dispatch<React.SetStateAction<'triggers' | 'types' | 'datasources' | 'tables' | 'workflows'>>;
  setTriggerSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setTriggerSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  setSelectedDataSourceId: React.Dispatch<React.SetStateAction<number | null>>;
}

interface AiNavigationState {
  aiNavigationLevel: 'ai' | 'types';
  aiSelectedType: string | null;
  aiSearchQuery: string;
  setAiNavigationLevel: React.Dispatch<React.SetStateAction<'ai' | 'types'>>;
  setAiSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setAiSearchQuery: React.Dispatch<React.SetStateAction<string>>;
}

interface CoreNavigationState {
  coreNavigationLevel: 'core' | 'logic' | 'types';
  coreSelectedType: string | null;
  coreSearchQuery: string;
  setCoreNavigationLevel: React.Dispatch<React.SetStateAction<'core' | 'logic' | 'types'>>;
  setCoreSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setCoreSearchQuery: React.Dispatch<React.SetStateAction<string>>;
}

export interface InspectorNavigationState
  extends TriggerNavigationState,
    AiNavigationState,
    CoreNavigationState {}

interface InspectorNavigationParams {
  node: Node<BuilderNodeData> | null;
  meta: {
    isTriggerNode: boolean;
    isTriggerGenericNode: boolean;
    isGenericEntryTrigger: boolean;
    isWebhookTrigger: boolean;
    isScheduleTrigger: boolean;
    isManualTrigger: boolean;
    isTablesTrigger: boolean;
    isWorkflowsTrigger: boolean;
    isAiNode: boolean;
    isAiGenericNode: boolean;
    isAiAgent: boolean;
    isAiSummarize: boolean;
    isGuardrail: boolean;
    isClassify: boolean;
    isCoreNode: boolean;
    isCoreGenericNode: boolean;
    isLogicSubcategory: boolean;
    isIfElse: boolean;
    isUserApproval: boolean;
    isWhile: boolean;
    isTransform: boolean;
    isHttpRequest: boolean;
    isWebhook: boolean;
  };
}

export function useInspectorNavigation({
  node,
  meta: {
    isTriggerNode,
    isTriggerGenericNode,
    isGenericEntryTrigger,
    isWebhookTrigger,
    isScheduleTrigger,
    isManualTrigger,
    isTablesTrigger,
    isWorkflowsTrigger,
    isAiNode,
    isAiGenericNode,
    isAiAgent,
    isAiSummarize,
    isGuardrail,
    isClassify,
    isCoreNode,
    isCoreGenericNode,
    isLogicSubcategory,
    isIfElse,
    isUserApproval,
    isWhile,
    isTransform,
    isHttpRequest,
    isWebhook,
  },
}: InspectorNavigationParams): InspectorNavigationState {
  const [triggerNavigationLevel, setTriggerNavigationLevel] =
    React.useState<TriggerNavigationState['triggerNavigationLevel']>('triggers');
  const [triggerSelectedType, setTriggerSelectedType] = React.useState<string | null>(null);
  const [triggerSearchQuery, setTriggerSearchQuery] = React.useState('');
  const [selectedDataSourceId, setSelectedDataSourceId] = React.useState<number | null>(null);

  const [aiNavigationLevel, setAiNavigationLevel] = React.useState<AiNavigationState['aiNavigationLevel']>('ai');
  const [aiSelectedType, setAiSelectedType] = React.useState<string | null>(null);
  const [aiSearchQuery, setAiSearchQuery] = React.useState('');

  const [coreNavigationLevel, setCoreNavigationLevel] =
    React.useState<CoreNavigationState['coreNavigationLevel']>('core');
  const [coreSelectedType, setCoreSelectedType] = React.useState<string | null>(null);
  const [coreSearchQuery, setCoreSearchQuery] = React.useState('');

  // Track the last trigger node to avoid unnecessary resets
  const triggerLastNodeIdRef = React.useRef<string | null>(null);

  React.useEffect(() => {
    if (!isTriggerNode) {
      triggerLastNodeIdRef.current = null;
      return;
    }

    const currentNodeId = node?.id || null;
    if (currentNodeId === triggerLastNodeIdRef.current) {
      return;
    }
    triggerLastNodeIdRef.current = currentNodeId;

    if (isTriggerGenericNode || isGenericEntryTrigger) {
      setTriggerNavigationLevel('triggers');
      setTriggerSelectedType(null);
      setTriggerSearchQuery('');
    } else if (isWebhookTrigger || isScheduleTrigger || isManualTrigger) {
      const triggerType = isWebhookTrigger
        ? 'webhook-trigger'
        : isScheduleTrigger
          ? 'schedule-trigger'
          : 'manual-trigger';
      setTriggerSelectedType(triggerType);
      setTriggerNavigationLevel('types');
    } else if (isTablesTrigger) {
      const dataSourceData = (node?.data as any)?.dataSourceData;
      if (dataSourceData?.dataSourceId) {
        setSelectedDataSourceId(dataSourceData.dataSourceId);
        setTriggerNavigationLevel('tables');
      } else {
        setTriggerNavigationLevel('datasources');
        setSelectedDataSourceId(null);
      }
    } else if (isWorkflowsTrigger) {
      const workflowData = (node?.data as any)?.workflowData;
      if (workflowData?.workflowId) {
        // Workflow already selected, stay on workflows level
        setTriggerNavigationLevel('workflows');
      } else {
        // No workflow selected yet, show workflows list
        setTriggerNavigationLevel('workflows');
      }
    }
  }, [
    isTriggerNode,
    isTriggerGenericNode,
    isGenericEntryTrigger,
    isWebhookTrigger,
    isScheduleTrigger,
    isManualTrigger,
    isTablesTrigger,
    isWorkflowsTrigger,
    node?.id,
  ]);

  React.useEffect(() => {
    if (!isAiNode) return;

    if (isAiGenericNode) {
      if (aiNavigationLevel !== 'ai') {
        setAiNavigationLevel('ai');
      }
      setAiSelectedType(null);
      setAiSearchQuery('');
    } else if (isAiAgent || isAiSummarize || isGuardrail || isClassify) {
      const aiType = isAiAgent
        ? 'ai-agent'
        : isAiSummarize
          ? 'ai-summarize'
          : isGuardrail
            ? 'guardrail'
            : 'classify';
      if (aiType !== aiSelectedType) {
        setAiSelectedType(aiType);
        setAiNavigationLevel('types');
      }
    }
  }, [isAiNode, isAiGenericNode, isAiAgent, isAiSummarize, isGuardrail, isClassify, aiNavigationLevel, aiSelectedType]);

  const prevCoreNodeIdRef = React.useRef<string | null>(null);
  React.useEffect(() => {
    if (!isCoreNode) {
      prevCoreNodeIdRef.current = null;
      return;
    }

    const currentNodeId = node?.id || null;
    const nodeChanged = prevCoreNodeIdRef.current !== currentNodeId;
    prevCoreNodeIdRef.current = currentNodeId;
    if (!nodeChanged) return;

    if (isCoreGenericNode) {
      setCoreNavigationLevel('core');
      setCoreSelectedType(null);
      setCoreSearchQuery('');
    } else if (isLogicSubcategory) {
      setCoreNavigationLevel('logic');
      setCoreSelectedType(null);
      setCoreSearchQuery('');
    } else if (!isIfElse && !isUserApproval && !isWhile && !isTransform && !isHttpRequest && !isWebhook) {
      setCoreNavigationLevel('types');
      setCoreSelectedType(null);
      setCoreSearchQuery('');
    }
  }, [
    node?.id,
    isCoreNode,
    isCoreGenericNode,
    isLogicSubcategory,
    isIfElse,
    isUserApproval,
    isWhile,
    isTransform,
    isHttpRequest,
    isWebhook,
  ]);

  return {
    triggerNavigationLevel,
    triggerSelectedType,
    triggerSearchQuery,
    selectedDataSourceId,
    setTriggerNavigationLevel,
    setTriggerSelectedType,
    setTriggerSearchQuery,
    setSelectedDataSourceId,
    aiNavigationLevel,
    aiSelectedType,
    aiSearchQuery,
    setAiNavigationLevel,
    setAiSelectedType,
    setAiSearchQuery,
    coreNavigationLevel,
    coreSelectedType,
    coreSearchQuery,
    setCoreNavigationLevel,
    setCoreSelectedType,
    setCoreSearchQuery,
  };
}
