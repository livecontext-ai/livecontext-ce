import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Info } from 'lucide-react';

import type { Node, Edge } from 'reactflow';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { InspectorColumn } from './InspectorColumn';
import { useConnectionProps } from './ExpressionField';
import { useDataSourceColumns } from '../../hooks/useDataSourceData';
import { useWorkflowInputsOutputs } from '../../hooks/useWorkflowOutputs';
import { useHoverPopover } from '../../hooks/useHoverPopover';
import { HoverPopover } from '../shared/HoverPopover';
import { AgentConfigurationPanel } from './AgentConfigurationPanel';
import { BrowserAgentParametersForm } from './forms/BrowserAgentParametersForm';
import { ClassifyParametersForm } from './forms/ClassifyParametersForm';
import { GuardrailParametersForm } from './forms/GuardrailParametersForm';
import { DecisionBranchesForm } from './forms/DecisionBranchesForm';
import { SwitchCasesForm } from './forms/SwitchCasesForm';
import { OptionChoicesForm } from './forms/OptionChoicesForm';
import { ApprovalOutputsForm } from './forms/ApprovalOutputsForm';
import { ExpressionSyntaxGuide } from './shared/ExpressionSyntaxGuide';
import { useNodeTypeDetection } from '../../hooks/useNodeTypeDetection';
import { NodeFormRenderer } from './NodeFormRenderer';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { CreateRowForm } from './forms/crud/CreateRowForm';
import { CreateColumnForm } from './forms/crud/CreateColumnForm';
import { ReadRowForm } from './forms/crud/ReadRowForm';
import { UpdateRowForm } from './forms/crud/UpdateRowForm';
import { DeleteRowForm } from './forms/crud/DeleteRowForm';
import { McpToolSelector } from './forms/mcp/McpToolSelector';
import { NoteParametersForm } from './forms/NoteParametersForm';
import { NodeSettingsSection } from './NodeSettingsSection';
import { nodeSupportsPolicy } from '../../utils/nodePolicy';
import { ResolvedParamsView } from './outputs/ResolvedParamsView';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import type { ApprovalDelegation, BuilderNodeData, ConditionRow, SwitchCaseRow, OptionChoice, ApprovalOutput } from '../../types';

interface ParameterColumnProps {
  // Core node props
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  onUpdate: (data: BuilderNodeData) => void;
  connections: any[];

  // UI state
  isAdvanced?: boolean;
  isMobile?: boolean;
  /** When true, skip the InspectorColumn wrapper (used when embedded in mobile tabs) */
  embedded?: boolean;
  isRunMode?: boolean;
  isDark?: boolean;

  // Run mode props
  currentWorkflowId?: string;
  currentRunId?: string;
  showExecutionData?: boolean;

  // MCP node state
  isMcpNode?: boolean;
  isToolNode?: boolean;
  isApiNode?: boolean;
  shouldLoadApis?: boolean;
  mcpNavigationLevel?: 'apis' | 'tools';
  mcpSearchQuery?: string;
  setMcpSearchQuery?: (value: string) => void;
  mcpApis?: any[];
  mcpApiTools?: any[];
  mcpLoadingApis?: boolean;
  mcpLoadingTools?: boolean;
  apiInitialLoading?: boolean;
  apiHasMore?: boolean;
  apiLoadMoreRef?: React.RefObject<HTMLDivElement>;
  toolInitialLoading?: boolean;
  toolHasMore?: boolean;
  toolLoadMoreRef?: React.RefObject<HTMLDivElement>;
  loadingToolDetails?: boolean;
  toolParameters?: any[];
  toolCredentials?: any[];

  // Tool details
  toolDetails?: any;

  // MCP handlers
  handleMcpApiClick?: (api: any) => void;
  handleMcpToolSelect?: (tool: any) => void;
  handleMcpBackClick?: () => void;

  // Decision node - conditions
  currentConditions?: ConditionRow[];
  handleAddCondition?: (type: string, index: number) => void;
  handleDeleteCondition?: (id: string) => void;
  handleRenameCondition?: (id: string, label: string) => void;
  handleLoopConditionExpressionChange?: (value: string) => void;
  getConditionExpression?: (id: string) => string;
  handleConditionExpressionChange?: (id: string, value: string) => void;
  getConditionHandleId?: (condition: ConditionRow, index: number) => string;

  // Switch node - cases
  currentCases?: SwitchCaseRow[];
  switchExpression?: string;
  getCaseHandleId?: (caseRow: SwitchCaseRow, index: number) => string;
  getCaseValue?: (id: string) => string;
  handleCaseValueChange?: (id: string, value: string) => void;
  handleSwitchExpressionChange?: (value: string) => void;
  handleAddCase?: (index: number) => void;
  handleDeleteCase?: (id: string) => void;
  handleRenameCase?: (id: string, label: string) => void;

  // Option node - choices
  currentChoices?: OptionChoice[];
  handleAddChoice?: () => void;
  handleDeleteChoice?: (id: string) => void;
  handleRenameChoice?: (id: string, label: string) => void;
  handleExpressionChange?: (id: string, expression: string) => void;

  // Approval node - outputs
  currentApprovalOutputs?: ApprovalOutput[];
  approvalTimeoutMs?: number;
  approvalContextTemplate?: string;
  approvalDelegation?: ApprovalDelegation;
  handleAddApprovalOutput?: () => void;
  handleDeleteApprovalOutput?: (id: string) => void;
  handleRenameApprovalOutput?: (id: string, label: string) => void;
  handleApprovalTimeoutChange?: (timeoutMs: number | undefined) => void;
  handleApprovalContextTemplateChange?: (template: string | undefined) => void;
  handleApprovalDelegationChange?: (delegation: ApprovalDelegation | undefined) => void;

  // Parameter expressions
  getParamExpression?: (key: string) => string;
  handleParamExpressionChange?: (key: string, value: string) => void;
  getToolParamExpression?: (key: string) => string;
  handleToolParamExpressionChange?: (key: string, value: string) => void;

  // Column expressions (datasource)
  getColumnExpression?: (field: string) => string;
  handleColumnExpressionChange?: (field: string, value: string) => void;
  getColumnLabel?: (field: string) => string;
  handleColumnLabelChange?: (field: string, label: string) => void;
  handleDeleteColumn?: (field: string) => void;
  handleAddColumn?: () => void;

  // Drag & Drop handles
  draggingFromHandle?: string | null;
  hoveredTargetHandle?: string | null;
  handleHandleClick?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef?: (handleId: string, ref: HTMLElement | null) => void;

  // Utils
  findUnknownVariables?: (expressions: Record<string, string>) => string[];

  // Optional params visibility
  showOptionalParams?: boolean;
  setShowOptionalParams?: (value: boolean) => void;

  // Graph data for AI Agent tool connections
  allNodes?: Node<BuilderNodeData>[];
  edges?: Edge[];

  // Webhook tokens map for multi-DAG support (triggerId -> token)
  webhookTokens?: Record<string, string>;
}

export const ParameterColumn = (props: ParameterColumnProps) => {
  const {
    node, data, onUpdate, connections,
    isAdvanced, isMobile, embedded,
    isRunMode: isRunModeProp = false, // En mode run, rendre tous les champs readonly
    // Run mode props
    currentWorkflowId,
    currentRunId,
    showExecutionData = true,
    // MCP States
    isMcpNode, isToolNode, isApiNode,
    shouldLoadApis,
    mcpNavigationLevel, mcpSearchQuery, setMcpSearchQuery,
    mcpApis, mcpApiTools, mcpLoadingApis, mcpLoadingTools,
    apiInitialLoading, apiHasMore, apiLoadMoreRef,
    toolInitialLoading, toolHasMore, toolLoadMoreRef,
    loadingToolDetails, toolParameters,
    // Tool details (used by McpToolSelector to backfill apiToolId on legacy nodes)
    toolDetails,
    // Tool Credentials
    toolCredentials = [],
    // Handlers
    handleMcpApiClick, handleMcpToolSelect,
    // Conditions
    currentConditions, handleAddCondition, handleDeleteCondition, handleRenameCondition,
    getConditionExpression, handleConditionExpressionChange,
    getConditionHandleId,
    // Switch cases
    currentCases, switchExpression,
    getCaseHandleId, getCaseValue,
    handleCaseValueChange, handleSwitchExpressionChange,
    handleAddCase, handleDeleteCase, handleRenameCase,
    // Option choices
    currentChoices,
    handleAddChoice, handleDeleteChoice,
    handleRenameChoice, handleExpressionChange,
    // Approval outputs
    currentApprovalOutputs,
    approvalTimeoutMs,
    approvalContextTemplate,
    approvalDelegation,
    handleAddApprovalOutput, handleDeleteApprovalOutput,
    handleRenameApprovalOutput,
    handleApprovalTimeoutChange,
    handleApprovalContextTemplateChange,
    handleApprovalDelegationChange,
    // Params
    getParamExpression, handleParamExpressionChange,
    getToolParamExpression, handleToolParamExpressionChange,
    getColumnExpression, handleColumnExpressionChange,
    getColumnLabel, handleColumnLabelChange,
    handleDeleteColumn, handleAddColumn,
    // Drag & Drop
    draggingFromHandle, hoveredTargetHandle,
    handleHandleClick, handleHandleMouseDown, handleHandleMouseUp, handleSetHandleRef,
    // Utils
    findUnknownVariables,
    isDark,
    // Optional params visibility
    showOptionalParams, setShowOptionalParams,
    // Graph data for AI Agent tool connections
    allNodes = [],
    edges = [],
    // Webhook tokens map for multi-DAG support
    webhookTokens,
  } = props;

  const t = useTranslations('workflowBuilder.inspector');

  // Get run mode from context or props
  // When isRunModeProp is explicitly passed (even as false), use it to override context
  // This allows step-by-step mode to enable editing while still in run mode URL
  const { isRunMode: isRunModeFromContext, runId: contextRunId, workflowId: contextWorkflowId } = useWorkflowMode();
  const isRunMode = isRunModeProp !== undefined ? isRunModeProp : isRunModeFromContext;
  const effectiveRunId = currentRunId || contextRunId;
  const effectiveWorkflowId = currentWorkflowId || contextWorkflowId;
  const stepAlias = node?.data?.label;

  // Execution data toggle is centralized in the header (showExecutionData prop)
  // Triggers don't have input data - they ARE the input - so skip execution data for them
  const canShowExecutionInput = isRunModeFromContext && effectiveWorkflowId && effectiveRunId && stepAlias && !nodeRegistry.isTrigger(node) && showExecutionData;

  // Local state if not provided
  const [localShowOptionalParams, setLocalShowOptionalParams] = React.useState(false);
  const effectiveShowOptionalParams = showOptionalParams !== undefined ? showOptionalParams : localShowOptionalParams;
  const effectiveSetShowOptionalParams = setShowOptionalParams || setLocalShowOptionalParams;

  // Hook pour gérer le popover au survol
  const {
    hoveredItem,
    isDesktop,
    containerRef: inspectorRef,
    popoverRef,
    handleMouseEnter,
    handleMouseLeave,
    isHoveringPopoverRef,
  } = useHoverPopover({
    position: 'right',
    gap: 32,
  });

  // Track if all required credentials are configured
  const hasRequiredCredentials = toolCredentials?.some((cred: any) => cred.isRequired) ?? false;
  const [allRequiredCredentialsConfigured, setAllRequiredCredentialsConfigured] = React.useState(!hasRequiredCredentials);

  // Bundle connection props for ExpressionField components
  const connectionProps = useConnectionProps(
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef
  );

  // Centralized node type detection using custom hook
  const nodeTypes = useNodeTypeDetection(data);

  // Extract commonly used values from nodeTypes for backwards compatibility
  const {
    dataSourceData: dataSourceDataForColumns,
    workflowData: workflowDataForColumns,
    isTablesTrigger: isTablesTriggerForColumns,
    isWorkflowsTrigger: isWorkflowsTriggerForColumns,
    isCreateRowNode,
    isCreateColumnNode,
    isReadRowNode,
    isUpdateRowNode,
    isDeleteRowNode,
    isFindRowNode,
    isTransformNode,
    isMergeNode,
    isWaitNode,
    isChatTrigger,
    isManualTrigger,
    isWebhookTrigger,
    isScheduleTrigger,
  } = nodeTypes;

  const dataSourceIdForColumns = dataSourceDataForColumns?.dataSourceId;
  const workflowIdForColumns = workflowDataForColumns?.workflowId;
  const workflowNameForColumns = workflowDataForColumns?.workflowName;

  const { data: workflowIO, isLoading: isLoadingWorkflowIO } = useWorkflowInputsOutputs(
    isWorkflowsTriggerForColumns && workflowIdForColumns ? workflowIdForColumns : null
  );

  // DataSource IDs for CRUD operations
  const createRowDataSourceId = isCreateRowNode ? dataSourceDataForColumns?.dataSourceId : null;
  const createColumnDataSourceId = isCreateColumnNode ? dataSourceDataForColumns?.dataSourceId : null;
  const readRowDataSourceId = isReadRowNode ? dataSourceDataForColumns?.dataSourceId : null;
  const updateRowDataSourceId = isUpdateRowNode ? dataSourceDataForColumns?.dataSourceId : null;
  const deleteRowDataSourceId = isDeleteRowNode ? dataSourceDataForColumns?.dataSourceId : null;
  const findRowDataSourceId = isFindRowNode ? dataSourceDataForColumns?.dataSourceId : null;

  // Get the active datasource ID for column fetching (prioritize in order: trigger, then CRUD operations)
  const activeDataSourceId =
    (isTablesTriggerForColumns && dataSourceIdForColumns) ||
    createRowDataSourceId ||
    createColumnDataSourceId ||
    readRowDataSourceId ||
    updateRowDataSourceId ||
    deleteRowDataSourceId ||
    findRowDataSourceId ||
    null;

  const { data: columns, isLoading: isLoadingColumns } = useDataSourceColumns(activeDataSourceId);

  const columnTitle = t('parameters');

  const contentBlock = (
        <div ref={inspectorRef} className="space-y-6" onMouseLeave={handleMouseLeave}>
          {/* Run Mode: Show resolved params data (Data toggle active) */}
          {canShowExecutionInput ? (
            <ResolvedParamsView
              workflowId={effectiveWorkflowId}
              runId={effectiveRunId}
              stepAlias={stepAlias}
              node={node}
              toolParameters={toolParameters}
            />
          ) : null}

          {/* Inspector Content - Show when Data toggle is off, or not in run mode, or in step-by-step mode */}
          {(!canShowExecutionInput) && isMcpNode ? (
            <McpToolSelector
              node={node}
              data={data}
              isToolNode={isToolNode}
              isApiNode={isApiNode}
              isRunMode={isRunMode}
              isDark={isDark}
              mcpNavigationLevel={mcpNavigationLevel}
              mcpSearchQuery={mcpSearchQuery}
              setMcpSearchQuery={setMcpSearchQuery}
              shouldLoadApis={shouldLoadApis}
              apiInitialLoading={apiInitialLoading}
              mcpLoadingApis={mcpLoadingApis}
              mcpApis={mcpApis}
              apiHasMore={apiHasMore}
              apiLoadMoreRef={apiLoadMoreRef}
              mcpLoadingTools={mcpLoadingTools}
              toolInitialLoading={toolInitialLoading}
              mcpApiTools={mcpApiTools}
              toolHasMore={toolHasMore}
              toolLoadMoreRef={toolLoadMoreRef}
              loadingToolDetails={loadingToolDetails}
              toolParameters={toolParameters}
              toolDetails={toolDetails}
              toolCredentials={toolCredentials}
              allRequiredCredentialsConfigured={allRequiredCredentialsConfigured}
              setAllRequiredCredentialsConfigured={setAllRequiredCredentialsConfigured}
              handleMouseEnter={handleMouseEnter}
              handleMouseLeave={handleMouseLeave}
              handleMcpApiClick={handleMcpApiClick}
              handleMcpToolSelect={handleMcpToolSelect}
              onUpdate={onUpdate}
              effectiveShowOptionalParams={effectiveShowOptionalParams}
              effectiveSetShowOptionalParams={effectiveSetShowOptionalParams}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          ) : null}

          {/* Build Mode, Config toggle, or Step-by-Step Mode: Show forms for editing parameters */}
          {!canShowExecutionInput && (
            <>
              {/* Workflow Trigger Configuration - handled by NodeFormRenderer */}

          {/* Create Row - Each row has one field per column */}
          {isCreateRowNode && createRowDataSourceId && !isMcpNode && (
            <CreateRowForm
              node={node}
              data={data}
              dataSourceId={createRowDataSourceId}
              columns={columns}
              isLoadingColumns={isLoadingColumns}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {/* Create Column - Add new columns to a table */}
          {isCreateColumnNode && createColumnDataSourceId && !isMcpNode && (
            <CreateColumnForm
              node={node}
              data={data}
              dataSourceId={createColumnDataSourceId}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {/* Update Row - WHERE condition + SET columns */}
          {isUpdateRowNode && updateRowDataSourceId && !isMcpNode && (
            <UpdateRowForm
              node={node}
              data={data}
              dataSourceId={updateRowDataSourceId}
              columns={columns}
              isLoadingColumns={isLoadingColumns}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {/* Get Row - WHERE condition + LIMIT */}
          {isReadRowNode && readRowDataSourceId && !isMcpNode && (
            <ReadRowForm
              node={node}
              data={data}
              dataSourceId={readRowDataSourceId}
              columns={columns}
              isLoadingColumns={isLoadingColumns}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              showOptionalParams={effectiveShowOptionalParams}
              setShowOptionalParams={effectiveSetShowOptionalParams}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {/* Delete Row - WHERE condition only with warning */}
          {isDeleteRowNode && deleteRowDataSourceId && !isMcpNode && (
            <DeleteRowForm
              node={node}
              data={data}
              dataSourceId={deleteRowDataSourceId}
              columns={columns}
              isLoadingColumns={isLoadingColumns}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {/* Find Rows - WHERE condition + LIMIT (reuses ReadRowForm) */}
          {isFindRowNode && findRowDataSourceId && !isMcpNode && (
            <ReadRowForm
              node={node}
              data={data}
              dataSourceId={findRowDataSourceId}
              columns={columns}
              isLoadingColumns={isLoadingColumns}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              showOptionalParams={effectiveShowOptionalParams}
              setShowOptionalParams={effectiveSetShowOptionalParams}
              findUnknownVariables={findUnknownVariables}
              connectionProps={connectionProps}
            />
          )}

          {nodeRegistry.isDecisionNode(node) && !nodeRegistry.isSwitchNode(node) && !nodeRegistry.isOptionNode(node) ? (
            <DecisionBranchesForm
              node={node}
              connections={connections}
              isRunMode={isRunMode}
              currentConditions={currentConditions || []}
              getConditionHandleId={getConditionHandleId}
              getConditionExpression={getConditionExpression}
              handleConditionExpressionChange={handleConditionExpressionChange}
              handleAddCondition={handleAddCondition}
              handleDeleteCondition={handleDeleteCondition}
              handleRenameCondition={handleRenameCondition}
              findUnknownVariables={findUnknownVariables}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
            />
          ) : null}

          {/* Switch node cases */}
          {nodeRegistry.isSwitchNode(node) ? (
            <SwitchCasesForm
              node={node}
              connections={connections}
              isRunMode={isRunMode}
              currentCases={currentCases || []}
              switchExpression={switchExpression || ''}
              getCaseHandleId={getCaseHandleId}
              getCaseValue={getCaseValue}
              handleCaseValueChange={handleCaseValueChange}
              handleSwitchExpressionChange={handleSwitchExpressionChange}
              handleAddCase={handleAddCase}
              handleDeleteCase={handleDeleteCase}
              handleRenameCase={handleRenameCase}
              findUnknownVariables={findUnknownVariables}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
            />
          ) : null}

          {/* Option node choices */}
          {nodeRegistry.isOptionNode(node) ? (
            <OptionChoicesForm
              node={node}
              isRunMode={isRunMode}
              currentChoices={currentChoices || []}
              connections={connections}
              handleAddChoice={handleAddChoice!}
              handleDeleteChoice={handleDeleteChoice!}
              handleRenameChoice={handleRenameChoice!}
              handleExpressionChange={handleExpressionChange!}
              findUnknownVariables={findUnknownVariables}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
            />
          ) : null}

          {/* User Approval node outputs */}
          {nodeRegistry.isUserApprovalNode(node) ? (
            <ApprovalOutputsForm
              isRunMode={isRunMode}
              approvalTimeoutMs={approvalTimeoutMs}
              handleTimeoutChange={handleApprovalTimeoutChange!}
              approvalContextTemplate={approvalContextTemplate}
              handleContextTemplateChange={handleApprovalContextTemplateChange!}
              approvalDelegation={approvalDelegation}
              handleDelegationChange={handleApprovalDelegationChange!}
            />
          ) : null}

          {nodeRegistry.isAiAgentNode(node) ? (
            <AgentConfigurationPanel
              node={node}
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              connectionProps={connectionProps}
              findUnknownVariables={findUnknownVariables}
              getParamExpression={getParamExpression}
              handleParamExpressionChange={handleParamExpressionChange}
              showOptionalParams={effectiveShowOptionalParams}
              setShowOptionalParams={effectiveSetShowOptionalParams}
              allNodes={allNodes}
              edges={edges}
            />
          ) : null}

          {/* Browser-agent node - distinct execution shape (LLM-driven
              browser navigation), needs its own focused form rather than
              the generic AgentConfigurationPanel JSON drawer. */}
          {nodeRegistry.isBrowserAgentNode?.(node) ? (
            <BrowserAgentParametersForm
              node={node}
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              connectionProps={connectionProps}
              findUnknownVariables={findUnknownVariables}
              getParamExpression={getParamExpression}
              handleParamExpressionChange={handleParamExpressionChange}
            />
          ) : null}

          {/* Classify node */}
          {nodeRegistry.isClassifyNode(node) ? (
            <ClassifyParametersForm
              node={node}
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              connectionProps={connectionProps}
              findUnknownVariables={findUnknownVariables}
              getParamExpression={getParamExpression}
              handleParamExpressionChange={handleParamExpressionChange}
              connections={connections}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
            />
          ) : null}

          {/* Guardrail node */}
          {nodeRegistry.isGuardrailNode(node) ? (
            <GuardrailParametersForm
              node={node}
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
              connectionProps={connectionProps}
              findUnknownVariables={findUnknownVariables}
              getParamExpression={getParamExpression}
              handleParamExpressionChange={handleParamExpressionChange}
            />
          ) : null}

          {/* Note node - rendered directly for reliable interaction */}
          {nodeRegistry.isNoteNode(node) ? (
            <NoteParametersForm
              data={data}
              onUpdate={onUpdate}
              isRunMode={isRunMode}
            />
          ) : null}

              {/* Centralized form rendering for other node types (triggers, transform, etc.) */}
              <NodeFormRenderer
                node={node}
                data={data}
                isRunMode={isRunMode}
                onUpdate={onUpdate}
                connections={connections}
                findUnknownVariables={findUnknownVariables}
                draggingFromHandle={draggingFromHandle}
                hoveredTargetHandle={hoveredTargetHandle}
                handleHandleClick={handleHandleClick}
                handleHandleMouseDown={handleHandleMouseDown}
                handleHandleMouseUp={handleHandleMouseUp}
                handleSetHandleRef={handleSetHandleRef}
                webhookTokens={webhookTokens}
                // Graph data for AI Agent
                allNodes={allNodes}
                edges={edges}
                // Expression helpers
                getParamExpression={getParamExpression}
                handleParamExpressionChange={handleParamExpressionChange}
                // Tables trigger: pass fetched DB columns so TablesTriggerFormAdapter can render
                columns={columns}
                isLoadingColumns={isLoadingColumns}
              />

              {/* Generic per-node execution policy (nodePolicy) - every executable
                  node type; triggers and notes are excluded (parser ignores them) */}
              {nodeSupportsPolicy(node) ? (
                <NodeSettingsSection
                  node={node}
                  data={data}
                  onUpdate={onUpdate}
                  isRunMode={isRunMode}
                />
              ) : null}
            </>
          )}
        </div>
  );

  // Embedded mode: skip InspectorColumn wrapper (used in mobile tabs)
  if (embedded) {
    return contentBlock;
  }

  return (
    <>
      {/* Popover au survol - Desktop uniquement */}
      <HoverPopover
        hoveredItem={hoveredItem}
        isDesktop={isDesktop}
        popoverRef={popoverRef}
        isHoveringPopoverRef={isHoveringPopoverRef}
        onMouseLeave={handleMouseLeave}
      />
      <InspectorColumn
        title={columnTitle}
        showRightBorder={true}
        headerRight={
          <div className="flex items-center gap-1">
            <Popover>
              <PopoverTrigger asChild>
                <button
                  type="button"
                  className="inline-flex items-center justify-center rounded-full hover:bg-slate-200 dark:hover:bg-slate-700 p-0.5"
                >
                  <Info className="h-3 w-3 text-slate-500 dark:text-slate-400" />
                </button>
              </PopoverTrigger>
              <PopoverContent className="w-[min(420px,calc(100vw-32px))] max-h-[600px] overflow-y-auto p-4 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-[24px] z-[99999]" side="right" align="start">
                <ExpressionSyntaxGuide />
              </PopoverContent>
            </Popover>
          </div>
        }
      >
        {contentBlock}
      </InspectorColumn>
    </>
  );
};

