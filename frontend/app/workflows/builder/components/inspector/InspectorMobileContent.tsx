'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import type { Node, Edge } from 'reactflow';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import type { BuilderNodeData } from '../../types';
import { InputColumn } from './InputColumn';
import { OutputColumn } from './OutputColumn';
import { PreviewColumn } from './PreviewColumn';
import { InterfaceMappingsColumn } from './InterfaceMappingsColumn';
import { ViewModeTabs } from './ViewModeTabs';
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
 * Segmented-control chrome shared with the header's ViewModeTabs, so the two
 * switchers in the same panel do not look like two different products. The
 * default shadcn tab pill (`bg-muted`, `h-10`) is deliberately overridden.
 */
const TAB_LIST_CLASS =
  'grid w-full h-auto mb-4 gap-0.5 rounded-lg bg-theme-tertiary p-1';

const TAB_TRIGGER_CLASS =
  'rounded-md px-2.5 py-1 text-sm font-medium text-theme-secondary transition-colors duration-150 ' +
  'hover:text-theme-primary data-[state=active]:bg-[var(--bg-primary)] ' +
  'data-[state=active]:text-theme-primary data-[state=active]:shadow-sm';

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
  runId,
  workflowId,
  onSelectNode,
  selectedLoopChild,
  toolDetails,
  onUpdate,
  connectionProps,
  getEditorExpression,
  handleEditorExpressionChange,
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle,
  parameterColumnProps,
}: InspectorMobileContentProps) {
  const ti = useTranslations('workflowBuilder.inspector');
  const formRunMode = isRunModeForForms ?? isRunMode;

  return (
    <Tabs value={activeTab} onValueChange={setActiveTab} className="h-full flex flex-col">
      {/* Configuration/run-data selector - show in run mode on mobile/tablet
          (header's ViewModeTabs is hidden below lg: breakpoint) */}
      {isRunMode && !isInterfaceNode && (
        <div className="mb-4 flex justify-center">
          <ViewModeTabs
            variant="compact"
            showExecutionData={showExecutionData}
            onShowExecutionDataChange={onShowExecutionDataChange}
            canShowExecutionDataToggle={canShowExecutionDataToggle}
          />
        </div>
      )}

      <>
          {/* Tab list - 3 tabs in advanced mode, 1 tab otherwise. Same segmented
              control as the header's view switcher: one visual language for
              "pick a view", instead of the default shadcn pill. */}
          {isAdvanced ? (
            <TabsList className={TAB_LIST_CLASS + ' grid-cols-3'}>
              <TabsTrigger value="input" className={TAB_TRIGGER_CLASS}>
                {ti('inputTitle')}
              </TabsTrigger>
              <TabsTrigger
                value={isInterfaceNode ? 'mappings' : 'parameter'}
                className={TAB_TRIGGER_CLASS}
              >
                {isInterfaceNode ? ti('mappings') : ti('parameters')}
              </TabsTrigger>
              <TabsTrigger
                value={isInterfaceNode ? 'preview' : 'output'}
                className={TAB_TRIGGER_CLASS}
              >
                {isInterfaceNode ? ti('preview') : ti('outputTitle')}
              </TabsTrigger>
            </TabsList>
          ) : (
            <TabsList className={TAB_LIST_CLASS + ' grid-cols-1'}>
              <TabsTrigger
                value={isInterfaceNode ? 'mappings' : 'parameter'}
                className={TAB_TRIGGER_CLASS}
              >
                {isInterfaceNode ? ti('mappings') : ti('parameters')}
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
    </Tabs>
  );
}
