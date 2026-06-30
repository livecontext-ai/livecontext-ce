'use client';

import * as React from 'react';
import { InspectorTriggerNode } from './InspectorTriggerNode';
import { InspectorAiNode } from './InspectorAiNode';
import { InspectorCoreNode } from './InspectorCoreNode';
import { InspectorMcpNode } from './InspectorMcpNode';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { DataSource, DataSourceTable } from '../../../hooks/useDataSourceData';
import type { WorkflowItem } from '../../../hooks/useWorkflowsData';

interface InspectorNodeRouterProps {
  // Node
  node: Node<BuilderNodeData> | null;
  
  // Node meta
  isTriggerNode: boolean;
  isAiNode: boolean;
  isCoreNode: boolean;
  isMcpNode: boolean;
  isTriggerGenericNode: boolean;
  isGenericEntryTrigger: boolean;
  isWebhookTrigger: boolean;
  isScheduleTrigger: boolean;
  isManualTrigger: boolean;
  isTablesTrigger: boolean;
  isWorkflowsTrigger: boolean;
  isChatTrigger: boolean;
  isFormTrigger: boolean;
  isDataSourceSelected: boolean;
  isAiGenericNode: boolean;
  isAiAgent: boolean;
  isAiSummarize: boolean;
  isGuardrail: boolean;
  isClassify: boolean;
  isCoreGenericNode: boolean;
  isLogicSubcategory: boolean;
  isIfElse: boolean;
  isUserApproval: boolean;
  isWhile: boolean;
  isTransform: boolean;
  isHttpRequest: boolean;
  isWebhook: boolean;
  isApiNode: boolean;
  isToolNode: boolean;
  
  // Navigation state - Trigger
  triggerNavigationLevel: 'triggers' | 'types' | 'datasources' | 'tables' | 'workflows';
  triggerSearchQuery: string;
  setTriggerSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  triggerSelectedType: string | null;
  selectedDataSourceId: number | null;
  
  // Navigation state - AI
  aiNavigationLevel: 'ai' | 'types';
  aiSearchQuery: string;
  setAiSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  aiSelectedType: string | null;
  
  // Navigation state - Core
  coreNavigationLevel: 'core' | 'logic' | 'types';
  coreSearchQuery: string;
  setCoreSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  coreSelectedType: string | null;
  
  // Navigation state - MCP
  mcpNavigationLevel: 'apis' | 'tools';
  mcpSearchQuery: string;
  setMcpSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  mcpSelectedApiSlug: string | null;
  
  // Data - Trigger
  dataSources: DataSource[];
  isLoadingDataSources: boolean;
  dataSourceTables: DataSourceTable[];
  isLoadingTables: boolean;
  workflows: WorkflowItem[];
  isLoadingWorkflows: boolean;
  
  // Data - MCP
  mcpApis: any[];
  mcpApiTools: any[];
  apiInitialLoading: boolean;
  toolInitialLoading: boolean;
  mcpLoadingApis: boolean;
  mcpLoadingTools: boolean;
  shouldLoadApis: boolean;
  apiHasMore: boolean;
  toolHasMore: boolean;
  apiLoadMoreRef: React.RefObject<HTMLDivElement>;
  toolLoadMoreRef: React.RefObject<HTMLDivElement>;
  
  // Handlers - Trigger
  handleTriggerTypeClick: (triggerType: any) => void;
  handleTriggerSelect: (triggerType: any) => void;
  handleDataSourceSelect: (dataSource: any) => void;
  handleTableSelect: (table: any, dataSource: any) => void;
  handleWorkflowSelect: (workflow: WorkflowItem) => void;
  handleTriggerBackFromTables?: () => void;
  handleTriggerBackFromDataSources?: () => void;
  handleTriggerBackFromWorkflows?: () => void;
  
  // Handlers - AI
  handleAiTypeClick: (aiType: any) => void;
  handleAiSelect: (aiType: any) => void;
  
  // Handlers - Core
  handleCoreLogicClick: () => void;
  handleCoreTypeClick: (coreType: any) => void;
  handleCoreSelect: (coreType: any) => void;
  
  // Handlers - MCP
  handleMcpApiClick: (api: any) => void;
  handleMcpToolSelect: (tool: any) => void;
  
  // Common handlers
  onSelectNode?: (nodeId: string | any) => void;
  onUpdate: (data: any) => void;

  // Current workflow ID to filter from selection
  currentWorkflowId?: string;
}

export function InspectorNodeRouter(props: InspectorNodeRouterProps) {
  const {
    node,
    isTriggerNode,
    isAiNode,
    isCoreNode,
    isMcpNode,
    isDataSourceSelected,
  } = props;

  // Route to the appropriate node component
  // Don't show trigger navigation if datasource is selected OR if table is selected OR if workflow is selected
  const dataSourceData = (node?.data as any)?.dataSourceData;
  const workflowData = (node?.data as any)?.workflowData;
  const isTableSelected = props.isTablesTrigger && dataSourceData?.dataSourceId && dataSourceData?.tableName;
  const isWorkflowSelected = props.isWorkflowsTrigger && workflowData?.workflowId;
  const shouldShowTriggerNavigation = isTriggerNode && !isDataSourceSelected && !isTableSelected && !isWorkflowSelected;
  
  if (shouldShowTriggerNavigation) {
    return (
      <InspectorTriggerNode
        triggerNavigationLevel={props.triggerNavigationLevel}
        triggerSearchQuery={props.triggerSearchQuery}
        setTriggerSearchQuery={props.setTriggerSearchQuery}
        triggerSelectedType={props.triggerSelectedType}
        selectedDataSourceId={props.selectedDataSourceId}
        isTriggerGenericNode={props.isTriggerGenericNode}
        isGenericEntryTrigger={props.isGenericEntryTrigger}
        isWebhookTrigger={props.isWebhookTrigger}
        isScheduleTrigger={props.isScheduleTrigger}
        isManualTrigger={props.isManualTrigger}
        isTablesTrigger={props.isTablesTrigger}
        isWorkflowsTrigger={props.isWorkflowsTrigger}
        isChatTrigger={props.isChatTrigger}
        isFormTrigger={props.isFormTrigger}
        isDataSourceSelected={props.isDataSourceSelected}
        dataSources={props.dataSources}
        isLoadingDataSources={props.isLoadingDataSources}
        dataSourceTables={props.dataSourceTables}
        isLoadingTables={props.isLoadingTables}
        workflows={props.workflows}
        isLoadingWorkflows={props.isLoadingWorkflows}
        handleTriggerTypeClick={props.handleTriggerTypeClick}
        handleTriggerSelect={props.handleTriggerSelect}
        handleDataSourceSelect={props.handleDataSourceSelect}
        handleTableSelect={props.handleTableSelect}
        handleWorkflowSelect={props.handleWorkflowSelect}
        handleTriggerBackFromTables={props.handleTriggerBackFromTables}
        handleTriggerBackFromDataSources={props.handleTriggerBackFromDataSources}
        handleTriggerBackFromWorkflows={props.handleTriggerBackFromWorkflows}
        onSelectNode={props.onSelectNode}
        onUpdate={props.onUpdate}
        node={node}
        currentWorkflowId={props.currentWorkflowId}
      />
    );
  }

  if (isAiNode && props.isAiGenericNode) {
    return (
      <InspectorAiNode
        aiNavigationLevel={props.aiNavigationLevel}
        aiSearchQuery={props.aiSearchQuery}
        setAiSearchQuery={props.setAiSearchQuery}
        aiSelectedType={props.aiSelectedType}
        isAiGenericNode={props.isAiGenericNode}
        isAiAgent={props.isAiAgent}
        isAiSummarize={props.isAiSummarize}
        isGuardrail={props.isGuardrail}
        isClassify={props.isClassify}
        handleAiTypeClick={props.handleAiTypeClick}
        handleAiSelect={props.handleAiSelect}
        node={node}
      />
    );
  }

  if (isCoreNode) {
    return (
      <InspectorCoreNode
        coreNavigationLevel={props.coreNavigationLevel}
        coreSearchQuery={props.coreSearchQuery}
        setCoreSearchQuery={props.setCoreSearchQuery}
        coreSelectedType={props.coreSelectedType}
        isCoreNode={props.isCoreNode}
        isCoreGenericNode={props.isCoreGenericNode}
        isLogicSubcategory={props.isLogicSubcategory}
        isIfElse={props.isIfElse}
        isUserApproval={props.isUserApproval}
        isWhile={props.isWhile}
        isTransform={props.isTransform}
        isHttpRequest={props.isHttpRequest}
        isWebhook={props.isWebhook}
        handleCoreLogicClick={props.handleCoreLogicClick}
        handleCoreTypeClick={props.handleCoreTypeClick}
        handleCoreSelect={props.handleCoreSelect}
        node={node}
      />
    );
  }

  if (isMcpNode) {
    return (
      <InspectorMcpNode
        mcpNavigationLevel={props.mcpNavigationLevel}
        mcpSearchQuery={props.mcpSearchQuery}
        setMcpSearchQuery={props.setMcpSearchQuery}
        mcpSelectedApiSlug={props.mcpSelectedApiSlug}
        isMcpNode={props.isMcpNode}
        isApiNode={props.isApiNode}
        isToolNode={props.isToolNode}
        mcpApis={props.mcpApis}
        mcpApiTools={props.mcpApiTools}
        apiInitialLoading={props.apiInitialLoading}
        toolInitialLoading={props.toolInitialLoading}
        mcpLoadingApis={props.mcpLoadingApis}
        mcpLoadingTools={props.mcpLoadingTools}
        shouldLoadApis={props.shouldLoadApis}
        apiHasMore={props.apiHasMore}
        toolHasMore={props.toolHasMore}
        apiLoadMoreRef={props.apiLoadMoreRef}
        toolLoadMoreRef={props.toolLoadMoreRef}
        handleMcpApiClick={props.handleMcpApiClick}
        handleMcpToolSelect={props.handleMcpToolSelect}
        node={node}
      />
    );
  }

  // No specific node type, return null
  return null;
}

