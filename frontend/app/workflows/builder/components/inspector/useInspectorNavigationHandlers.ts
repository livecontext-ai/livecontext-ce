'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { createDefaultDecisionConditions } from '../../types';
import { AI_TYPES, CORE_LOGIC_TYPES, CORE_DIRECT_TYPES, TRIGGER_TYPES } from './nodeTypes';

interface UseInspectorNavigationHandlersProps {
  node: Node<BuilderNodeData> | null;
  onUpdate: (data: BuilderNodeData) => void;
  onSelectNode?: (nodeId: string | any, loopId?: string) => void;
  // Trigger navigation
  setTriggerNavigationLevel: React.Dispatch<React.SetStateAction<'triggers' | 'types' | 'datasources' | 'tables' | 'workflows'>>;
  setTriggerSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setTriggerSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  setSelectedDataSourceId: React.Dispatch<React.SetStateAction<number | null>>;
  // AI navigation
  setAiSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setAiNavigationLevel: React.Dispatch<React.SetStateAction<'ai' | 'types'>>;
  setAiSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  // Core navigation
  setCoreNavigationLevel: React.Dispatch<React.SetStateAction<'core' | 'logic' | 'types'>>;
  setCoreSelectedType: React.Dispatch<React.SetStateAction<string | null>>;
  setCoreSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  // MCP navigation
  setMcpSelectedApiSlug: React.Dispatch<React.SetStateAction<string | null>>;
  setMcpNavigationLevel: React.Dispatch<React.SetStateAction<'apis' | 'tools'>>;
  setMcpSearchQuery: React.Dispatch<React.SetStateAction<string>>;
  setToolPage: React.Dispatch<React.SetStateAction<number>>;
  mcpApis: any[];
  mcpSelectedApiSlug: string | null;
  isMcpNode: boolean;
}

export function useInspectorNavigationHandlers({
  node,
  onUpdate,
  onSelectNode,
  setTriggerNavigationLevel,
  setTriggerSelectedType,
  setTriggerSearchQuery,
  setSelectedDataSourceId,
  setAiSelectedType,
  setAiNavigationLevel,
  setAiSearchQuery,
  setCoreNavigationLevel,
  setCoreSelectedType,
  setCoreSearchQuery,
  setMcpSelectedApiSlug,
  setMcpNavigationLevel,
  setMcpSearchQuery,
  setToolPage,
  mcpApis,
  mcpSelectedApiSlug,
  isMcpNode,
}: UseInspectorNavigationHandlersProps) {
  // Trigger navigation handlers
  // Define handleTriggerSelect first so it can be used by handleTriggerTypeClick
  const handleTriggerSelect = React.useCallback((triggerType: typeof TRIGGER_TYPES[0]) => {
    if (!node?.data) return;
    
    const updatedData = {
      ...node.data,
      id: triggerType.id,
      label: triggerType.name,
      description: triggerType.description,
      kind: 'entry' as const,
      nodeType: 'flowNode' as const,
    };
    onUpdate(updatedData);
  }, [node?.data, onUpdate]);
  
  const handleTriggerTypeClick = React.useCallback((triggerType: typeof TRIGGER_TYPES[0]) => {
    if (triggerType.id === 'tables-trigger') {
      // Like handleCoreTypeClick: if node exists, update it immediately, otherwise just navigate
      if (node?.data && node?.id) {
        // Update the node to tables-trigger format (like handleCoreSelect does for IF/ELSE)
        const triggerId = `tables-trigger-${Date.now()}`;
        const updatedData = {
          ...node.data,
          id: triggerId,
          _matchNodeId: node.id,
          label: triggerType.name,
          description: triggerType.description,
          kind: 'entry' as const,
          nodeType: 'flowNode' as const,
        };
        onUpdate(updatedData);
      }
      setTriggerNavigationLevel('datasources');
      setSelectedDataSourceId(null);
      setTriggerSearchQuery('');
    } else if (triggerType.id === 'workflows-trigger') {
      // Similar to tables-trigger: update node immediately if it exists
      if (node?.data && node?.id) {
        const triggerId = `workflows-trigger-${Date.now()}`;
        const updatedData = {
          ...node.data,
          id: triggerId,
          _matchNodeId: node.id,
          label: triggerType.name,
          description: triggerType.description,
          kind: 'entry' as const,
          nodeType: 'flowNode' as const,
        };
        onUpdate(updatedData);
      }
      setTriggerNavigationLevel('workflows');
      setTriggerSearchQuery('');
    } else {
      // For other trigger types, use handleTriggerSelect to update the node
      if (node?.data && node?.id) {
        handleTriggerSelect(triggerType);
      } else {
        setTriggerSelectedType(triggerType.id);
        setTriggerNavigationLevel('types');
        setTriggerSearchQuery('');
      }
    }
  }, [node?.data, node?.id, onUpdate, handleTriggerSelect, setTriggerNavigationLevel, setTriggerSelectedType, setTriggerSearchQuery, setSelectedDataSourceId]);

  // DataSource selection handler (like handleCoreSelect for IF/ELSE)
  const handleDataSourceSelect = React.useCallback((dataSource: any) => {
    if (!node?.data || !node?.id) return;
    
    // Change node.data.id to tables-trigger format for isTablesTrigger detection
    const dataSourceId = `tables-trigger-${dataSource.id}-${Date.now()}`;
    const updatedData = {
      ...node.data,
      id: dataSourceId,
      _matchNodeId: node.id,
      label: dataSource.name,
      description: dataSource.description || `Trigger from ${dataSource.name}`,
      kind: 'entry' as const,
      nodeType: 'flowNode' as const,
      dataSourceData: {
        dataSourceId: dataSource.id,
        dataSourceName: dataSource.name,
      },
    };
    onUpdate(updatedData);
    
    // Navigate to tables list
    setSelectedDataSourceId(dataSource.id);
    setTriggerNavigationLevel('tables');
    setTriggerSearchQuery('');
  }, [node?.data, node?.id, onUpdate, setSelectedDataSourceId, setTriggerNavigationLevel, setTriggerSearchQuery]);

  // Table selection handler (like handleCoreSelect for IF/ELSE)
  const handleTableSelect = React.useCallback((table: any, dataSource: any) => {
    if (!node?.data || !node?.id) return;
    
    // Change node.data.id to tables-trigger format for isTablesTrigger detection
    const tableId = `tables-trigger-${dataSource.id}-${table.name}-${Date.now()}`;
    const updatedData = {
      ...node.data,
      id: tableId,
      _matchNodeId: node.id,
      label: table.name,
      description: `Table from ${dataSource?.name || 'data source'}`,
      kind: 'entry' as const,
      nodeType: 'flowNode' as const,
      dataSourceData: {
        dataSourceId: dataSource.id,
        dataSourceName: dataSource?.name || '',
        tableName: table.name,
        schema: table.schema,
      },
    };
    onUpdate(updatedData);
  }, [node?.data, node?.id, onUpdate]);

  // Workflow selection handler (similar to handleDataSourceSelect)
  const handleWorkflowSelect = React.useCallback((workflow: any) => {
    if (!node?.data || !node?.id) return;

    // Change node.data.id to workflows-trigger format for isWorkflowsTrigger detection
    const workflowTriggerId = `workflows-trigger-${workflow.id}-${Date.now()}`;
    const updatedData = {
      ...node.data,
      id: workflowTriggerId,
      _matchNodeId: node.id,
      label: workflow.name,
      description: workflow.description || `Triggered when workflow ${workflow.name} completes`,
      kind: 'entry' as const,
      nodeType: 'flowNode' as const,
      workflowData: {
        workflowId: workflow.id,
        workflowName: workflow.name,
      },
    };
    onUpdate(updatedData);
  }, [node?.data, node?.id, onUpdate]);

  // Trigger back navigation handlers
  const handleTriggerBackFromTables = React.useCallback(() => {
    setTriggerNavigationLevel('datasources');
    setTriggerSearchQuery('');
  }, [setTriggerNavigationLevel, setTriggerSearchQuery]);

  const handleTriggerBackFromDataSources = React.useCallback(() => {
    setTriggerNavigationLevel('triggers');
    setSelectedDataSourceId(null);
    setTriggerSearchQuery('');
  }, [setTriggerNavigationLevel, setSelectedDataSourceId, setTriggerSearchQuery]);

  const handleTriggerBackFromWorkflows = React.useCallback(() => {
    setTriggerNavigationLevel('triggers');
    setTriggerSearchQuery('');
  }, [setTriggerNavigationLevel, setTriggerSearchQuery]);
  
  // AI navigation handlers
  const handleAiSelect = React.useCallback((aiType: typeof AI_TYPES[0]) => {
    if (!node?.data || !node?.id) return;
    
    const aiTypeId = `${aiType.id}-${Date.now()}`;

    const updatedData = {
      ...node.data,
      id: aiTypeId,
      _matchNodeId: node.id,
      label: aiType.name,
      description: aiType.description,
      kind: 'reasoning' as const,
      nodeType: 'flowNode' as const,
    };
    onUpdate(updatedData);
  }, [node?.data, node?.id, onUpdate]);
  
  const handleAiTypeClick = React.useCallback((aiType: typeof AI_TYPES[0]) => {
    if (node?.data && node?.id) {
      handleAiSelect(aiType);
    } else {
      setAiSelectedType(aiType.id);
      setAiNavigationLevel('types');
      setAiSearchQuery('');
    }
  }, [node?.data, node?.id, handleAiSelect, setAiSelectedType, setAiNavigationLevel, setAiSearchQuery]);
  
  // Core navigation handlers
  const handleCoreLogicClick = React.useCallback(() => {
    setCoreNavigationLevel('logic');
    setCoreSelectedType(null);
    setCoreSearchQuery('');
  }, [setCoreNavigationLevel, setCoreSelectedType, setCoreSearchQuery]);
  
  const handleCoreSelect = React.useCallback((coreType: typeof CORE_LOGIC_TYPES[0] | typeof CORE_DIRECT_TYPES[0]) => {
    if (!node?.data || !node?.id) return;
    
    const coreTypeId = `${coreType.id}-${Date.now()}`;
    
    const decisionConditions = coreType.id === 'if-else' && !node.data.decisionConditions
      ? createDefaultDecisionConditions(coreTypeId)
      : node.data.decisionConditions;
    
    const loopCondition = coreType.id === 'while' && !node.data.loopCondition
      ? ''
      : node.data.loopCondition;
    
    const kind = coreType.id === 'if-else' ? 'condition' as const :
                 coreType.id === 'while' ? 'loop' as const :
                 'action' as const;
    
    const badge = coreType.id === 'if-else' ? 'IF/ELSE' :
                  coreType.id === 'while' ? 'WHILE' :
                  node.data.badge;
    
    const updatedData = {
      ...node.data,
      id: coreTypeId,
      _matchNodeId: node.id,
      label: coreType.name,
      description: coreType.description,
      kind,
      badge,
      nodeType: coreType.id === 'if-else' ? 'decisionNode' as const : coreType.id === 'while' ? 'whileGroupNode' as const : 'flowNode' as const,
      decisionConditions,
      loopCondition,
      loopChildren: coreType.id === 'while' && !node.data.loopChildren ? [] : node.data.loopChildren,
    };
    onUpdate(updatedData);
  }, [node?.data, node?.id, onUpdate]);
  
  const handleCoreTypeClick = React.useCallback((coreType: typeof CORE_LOGIC_TYPES[0] | typeof CORE_DIRECT_TYPES[0]) => {
    if (node?.data && node?.id) {
      handleCoreSelect(coreType);
    } else {
      setCoreSelectedType(coreType.id);
      setCoreNavigationLevel('types');
      setCoreSearchQuery('');
    }
  }, [node?.data, node?.id, handleCoreSelect, setCoreSelectedType, setCoreNavigationLevel, setCoreSearchQuery]);
  
  // MCP navigation handlers
  const handleMcpApiClick = React.useCallback((api: any) => {
    const toolsCount = api.toolsCount || 0;
    if (toolsCount === 0) return;

    setMcpSelectedApiSlug(api.slug);
    setMcpNavigationLevel('tools');
    setMcpSearchQuery('');
    setToolPage(0);

    if (isMcpNode && node?.data) {
      const updatedData = {
        ...node.data,
        label: api.apiName,
        description: api.description || '',
        apiData: {
          apiSlug: api.slug,
          apiName: api.apiName,
          iconSlug: api.iconSlug,
        },
      };
      onUpdate(updatedData);
    } else if (onSelectNode) {
      // Create a new API node when clicking from inspector
      const apiNodeData = {
        id: `api-${api.slug}`,
        label: api.apiName,
        description: api.description || '',
        kind: 'tool' as const,
        nodeType: 'flowNode' as const,
        apiData: {
          apiSlug: api.slug,
          apiName: api.apiName,
          iconSlug: api.iconSlug,
        },
      };
      onSelectNode(apiNodeData);
    }
  }, [isMcpNode, node?.data, onUpdate, onSelectNode, setMcpSelectedApiSlug, setMcpNavigationLevel, setMcpSearchQuery, setToolPage]);
  
  const handleMcpBackClick = React.useCallback(() => {
    setMcpNavigationLevel('apis');
    setMcpSelectedApiSlug(null);
    setMcpSearchQuery('');
  }, [setMcpNavigationLevel, setMcpSelectedApiSlug, setMcpSearchQuery]);
  
  const handleMcpToolSelect = React.useCallback((tool: any) => {
    if (!node?.data) return;

    const api = mcpApis.find(a => a.slug === mcpSelectedApiSlug);

    const { apiData, ...restData } = node.data as any;
    const updatedData = {
      ...restData,
      label: tool.name,
      description: tool.description,
      toolData: {
        toolSlug: tool.slug,
        // Stable api_tool UUID persisted so the platform-credential pricing
        // toggle can ask auth-service for a per-endpoint rate rather than a
        // blanket API-wide answer. Optional: older nodes without it simply
        // fall back to integration-level "any non-zero rate" semantics.
        apiToolId: tool.toolId ?? null,
        apiSlug: mcpSelectedApiSlug,
        apiName: api?.apiName || '',
        iconSlug: api?.iconSlug || (apiData?.iconSlug),
        method: tool.method,
        parameters: [],
        responses: [],
        credentials: [],
      },
    };
    onUpdate(updatedData);
  }, [node?.data, mcpSelectedApiSlug, mcpApis, onUpdate]);

  return {
    handleTriggerTypeClick,
    handleTriggerSelect,
    handleDataSourceSelect,
    handleTableSelect,
    handleWorkflowSelect,
    handleTriggerBackFromTables,
    handleTriggerBackFromDataSources,
    handleTriggerBackFromWorkflows,
    handleAiSelect,
    handleAiTypeClick,
    handleCoreLogicClick,
    handleCoreSelect,
    handleCoreTypeClick,
    handleMcpApiClick,
    handleMcpBackClick,
    handleMcpToolSelect,
  };
}

