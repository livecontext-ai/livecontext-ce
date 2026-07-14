'use client';

import * as React from 'react';
import { PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, Play, Code, Eye, RefreshCcw } from 'lucide-react';
// Note: Play/Code icons kept for interface node output toggle (schema/preview/output)
import { useTranslations } from 'next-intl';
import clsx from 'clsx';
import type { Node, Edge } from 'reactflow';
import type { ApprovalContinuationMode, ApprovalDelegation, BuilderNodeData } from '../../types';
import type { Connection } from './useInspectorConnections';
import { InputColumn } from './InputColumn';
import { ParameterColumn } from './ParameterColumn';
import { OutputColumn } from './OutputColumn';
import { OutputSettingsMenu } from './OutputSettingsMenu';
import { InterfaceMappingsColumn } from './InterfaceMappingsColumn';
import { PreviewColumn } from './PreviewColumn';
import { NodeResultDataTable } from './NodeResultDataTable';
import { ViewMode } from './ViewModeTabs';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

import { extractFormFields, extractFormFieldsByAction } from '../../utils/interfaceHtmlUtils';
import { getFieldTypeColor } from '../../types';
import { RunDataPreview } from './outputs/RunDataPreview';
import { NavigationButtons } from './outputs/NavigationButtons';
import { useNextNodes, getLoopIdFromNode, useIsIterationNode } from '../../hooks/useNextNodes';
import { useValidation } from '../../contexts/ValidationContext';
import Toast, { useToast } from '@/components/Toast';
import { ensureMockPort, nodeSupportsMock, stripMockMarkers } from '../../utils/nodeMock';

interface InspectorDesktopContentProps {
  // Node props
  node: Node<BuilderNodeData> | null;
  data: BuilderNodeData | undefined;
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onUpdate: (data: BuilderNodeData) => void;
  onSelectNode?: (nodeId: string | any, loopId?: string) => void;
  selectedLoopChild?: { loopId: string; childId: string } | null;

  // Mode props
  isRunMode: boolean;
  effectiveRunModeForForms: boolean;
  runId?: string;
  workflowId?: string;
  viewMode: ViewMode | string;
  /** Centralized toggle: true = show run data, false = show config/schema */
  showExecutionData: boolean;
  isAdvanced: boolean;
  isFullscreen?: boolean;
  onAdvancedChange?: (advanced: boolean) => void;

  // Layout state
  inputCollapsed: boolean;
  setInputCollapsed: (collapsed: boolean) => void;
  outputCollapsed: boolean;
  setOutputCollapsed: (collapsed: boolean) => void;
  inputWidth: number;
  outputWidth: number;
  handleInputResizeStart: (e: React.MouseEvent) => void;
  handleOutputResizeStart: (e: React.MouseEvent) => void;
  isResizingInput?: boolean;
  isResizingOutput?: boolean;

  // Node type flags
  isInterfaceNode: boolean;
  isToolNode: boolean;
  isAiAgent: boolean;
  isMcpNode: boolean;
  isApiNode: boolean;

  // Connections
  connections: Connection[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, ref: HTMLElement | null) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];

  // Tool details
  toolDetails?: any;
  loadingToolDetails?: boolean;

  // MCP props
  mcpNavigationLevel?: 'apis' | 'tools';
  mcpSearchQuery?: string;
  setMcpSearchQuery?: (value: string) => void;
  mcpApis?: any[];
  mcpApiTools?: any[];
  apiInitialLoading?: boolean;
  toolInitialLoading?: boolean;
  mcpLoadingApis?: boolean;
  mcpLoadingTools?: boolean;
  shouldLoadApis?: boolean;
  apiHasMore?: boolean;
  toolHasMore?: boolean;
  apiLoadMoreRef?: React.RefObject<HTMLDivElement>;
  toolLoadMoreRef?: React.RefObject<HTMLDivElement>;
  toolParameters?: any[];
  toolCredentials?: any[];
  handleMcpApiClick?: (api: any) => void;
  handleMcpToolSelect?: (tool: any) => void;
  handleMcpBackClick?: () => void;

  // Expression handlers
  handleParamExpressionChange?: (key: string, value: string) => void;
  handleToolParamExpressionChange?: (key: string, value: string) => void;
  handleColumnExpressionChange?: (field: string, value: string) => void;
  handleColumnLabelChange?: (field: string, label: string) => void;
  handleDeleteColumn?: (field: string) => void;
  handleAddColumn?: () => void;
  getParamExpression?: (key: string) => string;
  getToolParamExpression?: (key: string) => string;
  getColumnExpression?: (field: string) => string;
  getColumnLabel?: (field: string) => string;
  handleLoopConditionExpressionChange?: (value: string) => void;
  handleRenameCondition?: (id: string, label: string) => void;
  handleAddCondition?: (type: string, index: number) => void;
  handleDeleteCondition?: (id: string) => void;
  handleConditionExpressionChange?: (id: string, value: string) => void;
  currentConditions?: any[];
  getConditionExpression?: (id: string) => string;
  getConditionHandleId?: (condition: any, index: number) => string;

  // Switch props
  currentCases?: any[];
  switchExpression?: string;
  getCaseHandleId?: (caseRow: any, index: number) => string;
  getCaseValue?: (id: string) => string;
  handleCaseValueChange?: (id: string, value: string) => void;
  handleSwitchExpressionChange?: (value: string) => void;
  handleAddCase?: (index: number) => void;
  handleDeleteCase?: (id: string) => void;
  handleRenameCase?: (id: string, label: string) => void;

  // Option props
  optionChoices?: any[];
  handleAddOptionChoice?: () => void;
  handleDeleteOptionChoice?: (id: string) => void;
  handleRenameOptionChoice?: (id: string, label: string) => void;
  handleOptionExpressionChange?: (id: string, expression: string) => void;

  // Approval props
  approvalOutputs?: any[];
  handleAddApprovalOutput?: () => void;
  handleDeleteApprovalOutput?: (id: string) => void;
  handleRenameApprovalOutput?: (id: string, label: string) => void;
  handleApprovalTimeoutChange?: (timeoutMs: number | undefined) => void;
  approvalTimeoutMs?: number;
  handleApprovalContextTemplateChange?: (template: string | undefined) => void;
  approvalContextTemplate?: string;
  handleApprovalContinuationModeChange?: (mode: ApprovalContinuationMode | undefined) => void;
  approvalContinuationMode?: ApprovalContinuationMode;
  handleApprovalDelegationChange?: (delegation: ApprovalDelegation | undefined) => void;
  approvalDelegation?: ApprovalDelegation;

  // Interface props
  getEditorExpression?: () => string;
  handleEditorExpressionChange?: (value: string) => void;

  // Breadcrumb
  onBreadcrumbChange?: (items: Array<{ label: string; onClick?: () => void }>) => void;

  // Webhook tokens map for multi-DAG support (triggerId -> token)
  webhookTokens?: Record<string, string>;
}

/** Resize handle matching PanelResizeHandle style (invisible → blue bar on hover/active) */
function ColumnResizeHandle({
  isResizing,
  onMouseDown,
}: {
  isResizing: boolean;
  onMouseDown: (e: React.MouseEvent) => void;
}) {
  const [hovered, setHovered] = React.useState(false);
  return (
    <div
      className="w-4 flex items-center justify-center flex-shrink-0"
      style={{ cursor: 'ew-resize' }}
      onMouseDown={onMouseDown}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <div
        className={`h-full transition-all ${
          isResizing || hovered ? 'w-1 bg-blue-500' : 'w-0'
        }`}
      />
    </div>
  );
}

/**
 * InspectorDesktopContent - Desktop layout with collapsible side panels
 * Shows Input | Parameters | Output columns with resize handles
 */
export function InspectorDesktopContent({
  node,
  data,
  allNodes,
  edges,
  onUpdate,
  onSelectNode,
  selectedLoopChild,
  isRunMode,
  effectiveRunModeForForms,
  runId,
  workflowId,
  viewMode,
  showExecutionData,
  isAdvanced,
  isFullscreen = false,
  onAdvancedChange,
  inputCollapsed,
  setInputCollapsed,
  outputCollapsed,
  setOutputCollapsed,
  inputWidth,
  outputWidth,
  handleInputResizeStart,
  handleOutputResizeStart,
  isResizingInput = false,
  isResizingOutput = false,
  isInterfaceNode,
  isToolNode,
  isAiAgent,
  isMcpNode,
  isApiNode,
  connections,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
  findUnknownVariables,
  toolDetails,
  loadingToolDetails,
  mcpNavigationLevel,
  mcpSearchQuery,
  setMcpSearchQuery,
  mcpApis,
  mcpApiTools,
  apiInitialLoading,
  toolInitialLoading,
  mcpLoadingApis,
  mcpLoadingTools,
  shouldLoadApis,
  apiHasMore,
  toolHasMore,
  apiLoadMoreRef,
  toolLoadMoreRef,
  toolParameters,
  toolCredentials,
  handleMcpApiClick,
  handleMcpToolSelect,
  handleMcpBackClick,
  handleParamExpressionChange,
  handleToolParamExpressionChange,
  handleColumnExpressionChange,
  handleColumnLabelChange,
  handleDeleteColumn,
  handleAddColumn,
  getParamExpression,
  getToolParamExpression,
  getColumnExpression,
  getColumnLabel,
  handleLoopConditionExpressionChange,
  handleRenameCondition,
  handleAddCondition,
  handleDeleteCondition,
  handleConditionExpressionChange,
  currentConditions,
  getConditionExpression,
  getConditionHandleId,
  currentCases,
  switchExpression,
  getCaseHandleId,
  getCaseValue,
  handleCaseValueChange,
  handleSwitchExpressionChange,
  handleAddCase,
  handleDeleteCase,
  handleRenameCase,
  // Option props
  optionChoices,
  handleAddOptionChoice,
  handleDeleteOptionChoice,
  handleRenameOptionChoice,
  handleOptionExpressionChange,
  // Approval props
  approvalOutputs,
  handleAddApprovalOutput,
  handleDeleteApprovalOutput,
  handleRenameApprovalOutput,
  handleApprovalTimeoutChange,
  approvalTimeoutMs,
  handleApprovalContextTemplateChange,
  approvalContextTemplate,
  handleApprovalContinuationModeChange,
  approvalContinuationMode,
  handleApprovalDelegationChange,
  approvalDelegation,
  getEditorExpression,
  handleEditorExpressionChange,
  onBreadcrumbChange,
  webhookTokens,
}: InspectorDesktopContentProps) {
  const { isRunMode: contextIsRunMode, runId: contextRunId, workflowId: contextWorkflowId } = useWorkflowMode();
  const t = useTranslations('workflowBuilder.interfacePreview');
  const ti = useTranslations('workflowBuilder.inspector');

  // Execution data toggle is centralized in the header (showExecutionData prop)
  const canShowExecutionData = contextIsRunMode && (runId || contextRunId) && workflowId && node?.data?.label;

  // Interface output column: 3-mode view (schema / preview / output)
  // Default to 'output' in run mode so execution data is visible first
  type InterfaceOutputMode = 'schema' | 'preview' | 'output';
  const [interfaceOutputMode, setInterfaceOutputMode] = React.useState<InterfaceOutputMode>(
    contextIsRunMode ? 'output' : 'schema'
  );

  // Build output field data from the interface HTML template (matching SourceNodeInspector style)
  // Uses per-action field extraction so each action only shows its own form fields.
  // Node-level outputs (screenshot, rendered_html/css/js) are gated by InterfaceNode toggles -
  // surfaced here so the user sees them in the right-pane schema view, mirroring the
  // downstream node's drag-source list in SourceNodeInspector.
  const interfaceOutputData = React.useMemo(() => {
    if (!isInterfaceNode) return {
      formFields: [] as string[],
      actionNames: [] as string[],
      fieldsByAction: new Map<string, string[]>(),
      nodeLevelOutputs: [] as Array<{ name: string; type: string }>,
    };
    const interfaceData = (node?.data as any)?.interfaceData || {};
    const html = interfaceData?.editorExpression || '';
    const formFields = extractFormFields(html);
    const actionMapping: Record<string, string> = interfaceData?.actionMapping || {};
    const actionNames = Object.keys(actionMapping).filter(k => k.length > 0);
    const fieldsByAction = extractFormFieldsByAction(html, actionNames);
    const nodeLevelOutputs: Array<{ name: string; type: string }> = [];
    if (interfaceData?.generateScreenshot === true) {
      nodeLevelOutputs.push({ name: 'screenshot', type: 'object' });
    }
    if (interfaceData?.generatePdf === true) {
      nodeLevelOutputs.push({ name: 'pdf', type: 'object' });
    }
    if (interfaceData?.exposeRenderedSource === true) {
      nodeLevelOutputs.push({ name: 'rendered_html', type: 'text' });
      nodeLevelOutputs.push({ name: 'rendered_css', type: 'text' });
      nodeLevelOutputs.push({ name: 'rendered_js', type: 'text' });
    }
    return { formFields, actionNames, fieldsByAction, nodeLevelOutputs };
  }, [
    isInterfaceNode,
    (node?.data as any)?.interfaceData?.editorExpression,
    (node?.data as any)?.interfaceData?.actionMapping,
    (node?.data as any)?.interfaceData?.generateScreenshot,
    (node?.data as any)?.interfaceData?.generatePdf,
    (node?.data as any)?.interfaceData?.exposeRenderedSource,
  ]);

  // Execution data toggle is centralized in the header (showExecutionData prop)

  // Navigation buttons for interface node output (matching OutputColumn pattern)
  const nextNodes = useNextNodes({
    currentNode: node,
    allNodes,
    edges,
    selectedLoopChild,
  });

  const isIterationNode = useIsIterationNode(allNodes, edges);
  const { hasNodeErrors: checkNodeErrorFromContext } = useValidation();

  const InterfaceArrowIcon = React.useCallback(({ node: n }: { node: Node<BuilderNodeData> }) => {
    if (isIterationNode(n)) {
      return <RefreshCcw className="h-3 w-3" />;
    }
    return (
      <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="lucide lucide-arrow-right h-3 w-3" aria-hidden="true">
        <path d="M5 12h14"></path>
        <path d="m12 5 7 7-7 7"></path>
      </svg>
    );
  }, [isIterationNode]);

  const checkNodeError = React.useCallback((n: Node<BuilderNodeData>): boolean => {
    const nodeStatus = n.data?.status as string | undefined;
    if (nodeStatus === 'failed') return true;
    const statusCounts = n.data?.statusCounts;
    if (statusCounts) {
      const errorCount = (statusCounts.FAILED || statusCounts.failed || 0);
      if (errorCount > 0) return true;
    }
    return checkNodeErrorFromContext(n.id);
  }, [checkNodeErrorFromContext]);

  // "Use as mock output": copies a viewed run output onto the node as its
  // static mock (engine marker keys stripped). Offered only on mock-capable
  // nodes; confirmed with a success toast.
  const tMock = useTranslations('workflowBuilder.mock');
  const { toasts, addToast, removeToast } = useToast();
  const handleUseAsMock = React.useCallback(
    (output: unknown) => {
      if (!node || !nodeSupportsMock(node)) return;
      const cleaned = stripMockMarkers(output);
      if (!cleaned) return;
      // ensureMockPort: on a branching node a static mock must carry a port
      // (backend parse rule) - default to the first branch.
      onUpdate({ ...node.data, mock: ensureMockPort({ output: cleaned }, node) });
      addToast({
        type: 'success',
        title: tMock('title'),
        message: tMock('usedAsMockToast', { label: node.data?.label || node.id }),
      });
    },
    [node, onUpdate, addToast, tMock]
  );
  const onUseAsMock = node && nodeSupportsMock(node) ? handleUseAsMock : undefined;

  // Output object currently loaded by the column's RunDataPreview, lifted up so
  // the Output header's settings menu can offer "Use as mock output" on it.
  // RunDataPreview publishes null whenever it is not displaying loaded data;
  // the node-switch reset here is defense in depth (the preview instance is
  // reused, never remounted, across node selections).
  const [loadedRunOutput, setLoadedRunOutput] = React.useState<unknown | null>(null);
  React.useEffect(() => {
    setLoadedRunOutput(null);
  }, [node?.id]);

  // Show configuration mode or result mode
  if (!runId || viewMode === 'configuration' || isInterfaceNode) {
    return (
      <div className="flex flex-1 min-h-0 overflow-x-auto overflow-y-hidden">
        {/* Toast notifications (Use-as-mock confirmation) - same pattern as CredentialSection */}
        {toasts.length > 0 && (
          <div className="fixed top-4 right-4 z-[99999] space-y-2">
            {toasts.map((toast) => (
              <Toast
                key={toast.id}
                id={toast.id}
                type={toast.type}
                title={toast.title}
                message={toast.message}
                onClose={removeToast}
              />
            ))}
          </div>
        )}
        {/* Left collapse zone - only in advanced/fullscreen mode */}
        {(isAdvanced || isFullscreen) && inputCollapsed && (
          <button
            onClick={() => {
              setInputCollapsed(false);
              if (onAdvancedChange) {
                onAdvancedChange(true);
              }
            }}
            className="flex-shrink-0 w-8 flex flex-col items-center justify-center bg-white dark:bg-gray-800 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors cursor-pointer border-r border-slate-100 dark:border-slate-700"
            title={ti('showInput')}
          >
            <PanelLeftOpen className="h-4 w-4 text-slate-400 dark:text-slate-500" />
          </button>
        )}

        {/* Input Column - only when advanced/fullscreen and expanded */}
        {(isAdvanced || isFullscreen) && !inputCollapsed && (
          <>
            <div
              className="flex-shrink-0 flex flex-col bg-white dark:bg-gray-800 transition-all duration-200 border-r border-slate-100 dark:border-slate-700"
              style={{ width: inputWidth }}
            >
              <div className="flex items-center justify-center px-3 py-2 flex-shrink-0">
                <button
                  onClick={() => setInputCollapsed(true)}
                  className="p-1 hover:bg-slate-100 dark:hover:bg-slate-700 rounded text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                  title={ti('hideInput')}
                >
                  <PanelLeftClose className="h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
                </button>
                <div className="flex-1" /> {/* Left spacer for centering */}
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">{ti('inputTitle')}</span>
                <div className="flex-1" /> {/* Right spacer for centering */}
              </div>
              <div className="flex-1 overflow-auto">
                <InputColumn
                  node={node}
                  allNodes={allNodes}
                  edges={edges}
                  onSelectNode={onSelectNode}
                  selectedLoopChild={selectedLoopChild}
                  isRunMode={effectiveRunModeForForms}
                  embedded={true}
                  showExecutionData={showExecutionData}
                  workflowId={workflowId}
                  runId={runId || contextRunId}
                />
              </div>
            </div>
            <ColumnResizeHandle isResizing={isResizingInput} onMouseDown={handleInputResizeStart} />
          </>
        )}

        {/* Parameter Column - Center (always visible) */}
        <div className="flex-1 min-w-[200px] flex flex-col overflow-hidden">
          {isInterfaceNode ? (
            <InterfaceMappingsColumn
              node={node}
              data={data}
              onUpdate={onUpdate}
              connections={connections}
              isRunMode={effectiveRunModeForForms}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
              findUnknownVariables={findUnknownVariables}
              getEditorExpression={getEditorExpression}
              handleEditorExpressionChange={handleEditorExpressionChange}
            />
          ) : (
            <ParameterColumn
              node={node!}
              data={data!}
              isRunMode={effectiveRunModeForForms}
              showExecutionData={showExecutionData}
              isMcpNode={isMcpNode}
              isToolNode={isToolNode}
              isApiNode={isApiNode}
              connections={connections}
              toolDetails={toolDetails}
              loadingToolDetails={loadingToolDetails}
              mcpNavigationLevel={mcpNavigationLevel}
              mcpSearchQuery={mcpSearchQuery}
              setMcpSearchQuery={setMcpSearchQuery}
              mcpApis={mcpApis}
              mcpApiTools={mcpApiTools}
              apiInitialLoading={apiInitialLoading}
              toolInitialLoading={toolInitialLoading}
              mcpLoadingApis={mcpLoadingApis}
              mcpLoadingTools={mcpLoadingTools}
              shouldLoadApis={shouldLoadApis}
              apiHasMore={apiHasMore}
              toolHasMore={toolHasMore}
              apiLoadMoreRef={apiLoadMoreRef}
              toolLoadMoreRef={toolLoadMoreRef}
              toolParameters={toolParameters}
              toolCredentials={toolCredentials}
              handleMcpApiClick={handleMcpApiClick}
              handleMcpToolSelect={handleMcpToolSelect}
              handleMcpBackClick={handleMcpBackClick}
              handleParamExpressionChange={handleParamExpressionChange}
              handleToolParamExpressionChange={handleToolParamExpressionChange}
              handleColumnExpressionChange={handleColumnExpressionChange}
              handleColumnLabelChange={handleColumnLabelChange}
              handleDeleteColumn={handleDeleteColumn}
              handleAddColumn={handleAddColumn}
              getParamExpression={getParamExpression}
              findUnknownVariables={findUnknownVariables}
              getToolParamExpression={getToolParamExpression}
              getColumnExpression={getColumnExpression}
              getColumnLabel={getColumnLabel}
              handleLoopConditionExpressionChange={handleLoopConditionExpressionChange}
              handleRenameCondition={handleRenameCondition}
              handleAddCondition={handleAddCondition}
              handleDeleteCondition={handleDeleteCondition}
              handleConditionExpressionChange={handleConditionExpressionChange}
              currentConditions={currentConditions}
              getConditionExpression={getConditionExpression}
              getConditionHandleId={getConditionHandleId}
              onUpdate={onUpdate}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
              allNodes={allNodes}
              edges={edges}
              webhookTokens={webhookTokens}
              // Switch props
              currentCases={currentCases}
              switchExpression={switchExpression}
              getCaseHandleId={getCaseHandleId}
              getCaseValue={getCaseValue}
              handleCaseValueChange={handleCaseValueChange}
              handleSwitchExpressionChange={handleSwitchExpressionChange}
              handleAddCase={handleAddCase}
              handleDeleteCase={handleDeleteCase}
              handleRenameCase={handleRenameCase}
              // Option props
              currentChoices={optionChoices}
              handleAddChoice={handleAddOptionChoice}
              handleDeleteChoice={handleDeleteOptionChoice}
              handleRenameChoice={handleRenameOptionChoice}
              handleExpressionChange={handleOptionExpressionChange}
              // Approval props
              currentApprovalOutputs={approvalOutputs}
              approvalTimeoutMs={approvalTimeoutMs}
              handleAddApprovalOutput={handleAddApprovalOutput}
              handleDeleteApprovalOutput={handleDeleteApprovalOutput}
              handleRenameApprovalOutput={handleRenameApprovalOutput}
              handleApprovalTimeoutChange={handleApprovalTimeoutChange}
              approvalContextTemplate={approvalContextTemplate}
              handleApprovalContextTemplateChange={handleApprovalContextTemplateChange}
              approvalContinuationMode={approvalContinuationMode}
              handleApprovalContinuationModeChange={handleApprovalContinuationModeChange}
              approvalDelegation={approvalDelegation}
              handleApprovalDelegationChange={handleApprovalDelegationChange}
            />
          )}
        </div>

        {/* Output Column - only when advanced/fullscreen and expanded */}
        {(isAdvanced || isFullscreen) && !outputCollapsed && (
          <>
            <ColumnResizeHandle isResizing={isResizingOutput} onMouseDown={handleOutputResizeStart} />
            <div
              className="flex-shrink-0 flex flex-col bg-white dark:bg-gray-800 transition-all duration-200 border-l border-slate-100 dark:border-slate-700"
              style={{ width: outputWidth }}
            >
              <div className="flex items-center justify-center px-3 py-2 flex-shrink-0">
                <div className="flex-1" /> {/* Left spacer for centering */}
                <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
                  {ti('outputTitle')}
                </span>
                {/* Interface nodes: Output / Preview / Schema toggle (output first like other nodes) */}
                {isInterfaceNode && (
                  <div className="flex items-center gap-0.5 ml-2">
                    {contextIsRunMode && (
                      <button
                        onClick={() => setInterfaceOutputMode('output')}
                        className={clsx(
                          'p-1 rounded transition-colors',
                          interfaceOutputMode === 'output'
                            ? 'text-slate-700 dark:text-slate-200'
                            : 'text-slate-400 hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300'
                        )}
                        title={t('output')}
                      >
                        <Play className="h-3.5 w-3.5" />
                      </button>
                    )}
                    <button
                      onClick={() => setInterfaceOutputMode('preview')}
                      className={clsx(
                        'p-1 rounded transition-colors',
                        interfaceOutputMode === 'preview'
                          ? 'text-slate-700 dark:text-slate-200'
                          : 'text-slate-400 hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300'
                      )}
                      title={t('preview')}
                    >
                      <Eye className="h-3.5 w-3.5" />
                    </button>
                    <button
                      onClick={() => setInterfaceOutputMode('schema')}
                      className={clsx(
                        'p-1 rounded transition-colors',
                        interfaceOutputMode === 'schema'
                          ? 'text-slate-700 dark:text-slate-200'
                          : 'text-slate-400 hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300'
                      )}
                      title={t('schema')}
                    >
                      <Code className="h-3.5 w-3.5" />
                    </button>
                  </div>
                )}
                <div className="flex-1" /> {/* Right spacer for centering */}
                {/* Settings menu - actions on the loaded run output (hides
                    itself on non-mock-capable nodes) */}
                <OutputSettingsMenu onUseAsMock={onUseAsMock} loadedOutput={loadedRunOutput} />
                <button
                  onClick={() => setOutputCollapsed(true)}
                  className="p-1 hover:bg-slate-100 dark:hover:bg-slate-700 rounded text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                  title={ti('hideOutput')}
                >
                  <PanelRightClose className="h-3.5 w-3.5 text-slate-400 dark:text-slate-500" />
                </button>
              </div>
              <div className={clsx("flex-1 min-h-0", isInterfaceNode && interfaceOutputMode === 'preview' ? "flex flex-col overflow-hidden" : "overflow-auto")}>
                {isInterfaceNode ? (
                  interfaceOutputMode === 'schema' ? (
                    <div className="p-3 space-y-2">
                      <NavigationButtons
                        nextNodes={nextNodes}
                        onNavigateToNode={onSelectNode}
                        checkNodeError={checkNodeError}
                        getLoopIdFromNode={getLoopIdFromNode}
                        ArrowIcon={InterfaceArrowIcon}
                      />
                      {interfaceOutputData.actionNames.length > 0 ? (
                        <div className="space-y-2">
                          {interfaceOutputData.actionNames.map((actionName) => {
                            const actionFields = interfaceOutputData.fieldsByAction.get(actionName) || [];
                            return (
                              <div key={actionName}>
                                <div className="text-xs font-semibold text-indigo-500 dark:text-indigo-400 mb-1 px-1">
                                  {actionName}
                                </div>
                                <div className="space-y-0.5 pl-3 border-l-2 border-indigo-200 dark:border-indigo-800">
                                  {actionFields.map((field) => (
                                    <div
                                      key={`${actionName}-${field}`}
                                      className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded"
                                    >
                                      <span className="text-sm">{field}</span>
                                      <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('text')}`}>text</span>
                                    </div>
                                  ))}
                                  <div className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded">
                                    <span className="text-sm">fired_at</span>
                                    <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('datetime')}`}>datetime</span>
                                  </div>
                                </div>
                              </div>
                            );
                          })}
                          {interfaceOutputData.nodeLevelOutputs.length > 0 && (
                            <div>
                              <div className="text-xs font-semibold text-indigo-500 dark:text-indigo-400 mb-1 px-1">
                                {ti('nodeOutputs')}
                              </div>
                              <div className="space-y-0.5 pl-3 border-l-2 border-indigo-200 dark:border-indigo-800">
                                {interfaceOutputData.nodeLevelOutputs.map((field) => (
                                  <div
                                    key={field.name}
                                    className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded"
                                  >
                                    <span className="text-sm">{field.name}</span>
                                    <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      ) : (
                        <div className="space-y-1">
                          {interfaceOutputData.formFields.map((field) => (
                            <div
                              key={field}
                              className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-1 rounded"
                            >
                              <span className="text-sm">{field}</span>
                              <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor('text')}`}>text</span>
                            </div>
                          ))}
                          {interfaceOutputData.nodeLevelOutputs.length > 0 && (
                            <div>
                              <div className="text-xs font-semibold text-indigo-500 dark:text-indigo-400 mb-1 px-1 mt-2">
                                {ti('nodeOutputs')}
                              </div>
                              <div className="space-y-0.5 pl-3 border-l-2 border-indigo-200 dark:border-indigo-800">
                                {interfaceOutputData.nodeLevelOutputs.map((field) => (
                                  <div
                                    key={field.name}
                                    className="flex items-center justify-between text-sm font-normal text-[var(--text-primary)] w-full px-1 py-0.5 rounded"
                                  >
                                    <span className="text-sm">{field.name}</span>
                                    <span className={`text-xs px-1.5 py-0.5 rounded uppercase tracking-wider font-mono ${getFieldTypeColor(field.type)}`}>{field.type}</span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  ) : interfaceOutputMode === 'output' ? (
                    <div className="p-3 space-y-2">
                      <NavigationButtons
                        nextNodes={nextNodes}
                        onNavigateToNode={onSelectNode}
                        checkNodeError={checkNodeError}
                        getLoopIdFromNode={getLoopIdFromNode}
                        ArrowIcon={InterfaceArrowIcon}
                      />
                      <RunDataPreview
                        workflowId={workflowId || contextWorkflowId}
                        runId={runId || contextRunId || undefined}
                        stepAlias={node?.data?.label}
                        dataType="output"
                        isDraggable={false}
                        onLoadedOutputChange={setLoadedRunOutput}
                      />
                    </div>
                  ) : (
                    <PreviewColumn
                      node={node}
                      allNodes={allNodes}
                      edges={edges}
                      embedded={true}
                    />
                  )
                ) : (
                  <OutputColumn
                    isToolNode={isToolNode}
                    toolDetails={toolDetails}
                    onNavigateToNode={onSelectNode}
                    currentNode={node}
                    allNodes={allNodes}
                    edges={edges}
                    embedded={true}
                    isAgentNode={isAiAgent}
                    currentWorkflowId={workflowId}
                    currentRunId={runId}
                    showExecutionData={showExecutionData}
                    onLoadedOutputChange={setLoadedRunOutput}
                  />
                )}
              </div>
            </div>
          </>
        )}

        {/* Right collapse zone - only in advanced/fullscreen mode when collapsed */}
        {(isAdvanced || isFullscreen) && outputCollapsed && (
          <button
            onClick={() => {
              setOutputCollapsed(false);
              if (onAdvancedChange) {
                onAdvancedChange(true);
              }
            }}
            className="flex-shrink-0 w-8 flex flex-col items-center justify-center bg-white dark:bg-gray-800 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors cursor-pointer border-l border-slate-100 dark:border-slate-700"
            title={ti('showOutput')}
          >
            <PanelRightOpen className="h-4 w-4 text-slate-400 dark:text-slate-500" />
          </button>
        )}
      </div>
    );
  }

  // Result mode - show merged DataTable directly for the node's step (full height)
  return (
    <div className="flex-1 min-h-0 overflow-hidden flex flex-col">
      <NodeResultDataTable
        node={node}
        runId={runId}
        workflowId={workflowId}
        onBreadcrumbChange={onBreadcrumbChange}
      />
    </div>
  );
}
