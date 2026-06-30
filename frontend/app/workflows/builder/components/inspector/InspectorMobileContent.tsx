'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import type { BuilderNodeData } from '../../types';
import { InputColumn } from './InputColumn';
import { OutputColumn } from './OutputColumn';
import { PreviewColumn } from './PreviewColumn';
import { InterfaceMappingsColumn } from './InterfaceMappingsColumn';
import { NodeResultDataTable } from './NodeResultDataTable';
import { ViewModeTabs, ViewMode } from './ViewModeTabs';
import { ParameterColumn } from './ParameterColumn';
import type { ConnectionPropsBundle } from './types/connectionProps';

export interface InspectorMobileContentProps {
  // Core node data
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];

  // State flags
  isRunMode: boolean;
  isRunModeForForms?: boolean;
  isAdvanced: boolean;
  isInterfaceNode: boolean;
  isToolNode: boolean;
  isAiAgent: boolean;

  // Tab state
  activeTab: string;
  setActiveTab: (tab: string) => void;

  // View mode
  viewMode: ViewMode;
  onViewModeChange: (mode: ViewMode) => void;

  // Run context
  runId?: string;
  workflowId?: string;

  // Selection
  onSelectNode?: (nodeId: string) => void;
  selectedLoopChild?: { loopId: string; childId: string } | null;

  // Tool details
  toolDetails: any;

  // Update handler
  onUpdate: (data: BuilderNodeData) => void;

  // Breadcrumb handler for result mode
  onBreadcrumbChange: (items: any[]) => void;

  // Connection props bundle
  connectionProps: ConnectionPropsBundle;

  // Interface mappings props
  getEditorExpression: () => string;
  handleEditorExpressionChange: (value: string) => void;

  // Execution data toggle (for compact ViewModeTabs)
  showExecutionData?: boolean;
  onShowExecutionDataChange?: (show: boolean) => void;
  canShowExecutionDataToggle?: boolean;

  // ParameterColumn props (all props needed for full parameter rendering)
  parameterColumnProps: Record<string, any>;
}

/**
 * InspectorMobileContent - Mobile view with tabs for Input/Parameter/Output
 */
export function InspectorMobileContent({
  node,
  data,
  allNodes,
  edges,
  isRunMode,
  isRunModeForForms,
  isAdvanced,
  isInterfaceNode,
  isToolNode,
  isAiAgent,
  activeTab,
  setActiveTab,
  viewMode,
  onViewModeChange,
  runId,
  workflowId,
  onSelectNode,
  selectedLoopChild,
  toolDetails,
  onUpdate,
  onBreadcrumbChange,
  connectionProps,
  getEditorExpression,
  handleEditorExpressionChange,
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle,
  parameterColumnProps,
}: InspectorMobileContentProps) {
  const formRunMode = isRunModeForForms ?? isRunMode;

  return (
    <Tabs value={activeTab} onValueChange={setActiveTab} className="h-full flex flex-col">
      {/* Mode Configuration/Result selector - show in run mode on mobile/tablet
          (header's ViewModeTabs is hidden below lg: breakpoint) */}
      {isRunMode && !isInterfaceNode && (
        <div className="mb-4 flex justify-center">
          <ViewModeTabs
            viewMode={viewMode}
            onViewModeChange={onViewModeChange}
            variant="compact"
            showExecutionData={showExecutionData}
            onShowExecutionDataChange={onShowExecutionDataChange}
            canShowExecutionDataToggle={canShowExecutionDataToggle}
          />
        </div>
      )}

      {(!runId || viewMode === 'configuration' || isInterfaceNode) ? (
        <>
          {/* Tab list - 3 tabs in advanced mode, 1 tab otherwise */}
          {isAdvanced ? (
            <TabsList className="grid w-full grid-cols-3 mb-4">
              <TabsTrigger value="input">Input</TabsTrigger>
              <TabsTrigger value={isInterfaceNode ? "mappings" : "parameter"}>
                {isInterfaceNode ? "Mappings" : "Parameter"}
              </TabsTrigger>
              <TabsTrigger value={isInterfaceNode ? "preview" : "output"}>
                {isInterfaceNode ? "Preview" : "Output"}
              </TabsTrigger>
            </TabsList>
          ) : (
            <TabsList className="grid w-full grid-cols-1 mb-4">
              <TabsTrigger value={isInterfaceNode ? "mappings" : "parameter"}>
                {isInterfaceNode ? "Mappings" : "Parameter"}
              </TabsTrigger>
            </TabsList>
          )}

          {/* Input tab content - only in advanced mode */}
          {isAdvanced && (
            <TabsContent value="input" className="flex-1 overflow-y-auto">
              <InputColumn
                node={node}
                allNodes={allNodes}
                edges={edges}
                onSelectNode={onSelectNode}
                selectedLoopChild={selectedLoopChild}
                isRunMode={formRunMode}
                embedded={true}
                showExecutionData={showExecutionData}
                workflowId={workflowId}
                runId={runId}
              />
            </TabsContent>
          )}

          {/* Parameter/Mappings tab content */}
          <TabsContent value={isInterfaceNode ? "mappings" : "parameter"} className="flex-1 overflow-y-auto">
            {isInterfaceNode ? (
              <InterfaceMappingsColumn
                node={node}
                data={data}
                onUpdate={onUpdate}
                connections={connectionProps.connections}
                isRunMode={formRunMode}
                draggingFromHandle={connectionProps.draggingFromHandle}
                hoveredTargetHandle={connectionProps.hoveredTargetHandle}
                handleHandleClick={connectionProps.handleHandleClick}
                handleHandleMouseDown={connectionProps.handleHandleMouseDown}
                handleHandleMouseUp={connectionProps.handleHandleMouseUp}
                handleSetHandleRef={connectionProps.handleSetHandleRef}
                findUnknownVariables={connectionProps.findUnknownVariables}
                getEditorExpression={getEditorExpression}
                handleEditorExpressionChange={handleEditorExpressionChange}
              />
            ) : (
              <ParameterColumn
                embedded
                node={node}
                data={data}
                isRunMode={formRunMode}
                isMobile
                onUpdate={onUpdate}
                connections={connectionProps.connections}
                draggingFromHandle={connectionProps.draggingFromHandle}
                hoveredTargetHandle={connectionProps.hoveredTargetHandle}
                handleHandleClick={connectionProps.handleHandleClick}
                handleHandleMouseDown={connectionProps.handleHandleMouseDown}
                handleHandleMouseUp={connectionProps.handleHandleMouseUp}
                handleSetHandleRef={connectionProps.handleSetHandleRef}
                findUnknownVariables={connectionProps.findUnknownVariables}
                allNodes={allNodes}
                edges={edges}
                toolDetails={toolDetails}
                {...parameterColumnProps}
              />
            )}
          </TabsContent>

          {/* Output/Preview tab content - only in advanced mode */}
          {isAdvanced && (
            <TabsContent value={isInterfaceNode ? "preview" : "output"} className="flex-1 overflow-y-auto flex flex-col">
              {isInterfaceNode ? (
                <PreviewColumn
                  node={node}
                  allNodes={allNodes}
                  edges={edges}
                />
              ) : (
                <OutputColumn
                  isToolNode={isToolNode}
                  toolDetails={toolDetails}
                  onNavigateToNode={onSelectNode}
                  currentNode={node}
                  allNodes={allNodes}
                  edges={edges}
                  selectedLoopChild={selectedLoopChild}
                  isAgentNode={isAiAgent}
                  showExecutionData={showExecutionData}
                  currentWorkflowId={workflowId}
                  currentRunId={runId}
                />
              )}
            </TabsContent>
          )}
        </>
      ) : (
        // Result mode - show merged DataTable directly for the node's step
        <NodeResultDataTable
          node={node}
          runId={runId}
          workflowId={workflowId}
          onBreadcrumbChange={onBreadcrumbChange}
        />
      )}
    </Tabs>
  );
}
