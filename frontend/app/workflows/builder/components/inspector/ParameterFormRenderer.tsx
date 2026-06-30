'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow, ConditionType, SwitchCaseRow } from '../../types';
import {
  AgentParametersForm,
  isAgentNode,
  SplitParametersForm,
  NoteParametersForm,
  DecisionBranchesForm,
  SwitchCasesForm,
  ToolParametersForm,
  DataSourceColumnsForm,
  TransformParametersForm,
  MergeParametersForm,
  WaitParametersForm,
} from './forms';
import { InterfaceMappingsColumn } from './InterfaceMappingsColumn';
import type { FormConnectionProps, FormOptionalProps } from './useInspectorFormProps';
import { nodeRegistry } from '../../registry/nodeRegistry';

interface ParameterFormRendererProps extends FormConnectionProps, FormOptionalProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode: boolean;
  onUpdate: (data: BuilderNodeData) => void;

  // Node type flags
  isSwitch: boolean;
  isTransform: boolean;
  isMerge: boolean;
  isWait: boolean;
  isInterfaceNode: boolean;
  isToolNode: boolean;
  isTablesTriggerForColumns: boolean;
  isMcpNode: boolean;

  // Tool props
  loadingToolDetails?: boolean;
  toolParameters?: any[];

  // Column props
  isLoadingColumnsForSmallMode?: boolean;
  columnsForSmallMode?: any[];
  dataSourceIdForColumns?: string;
  dataSourceDataForColumns?: any;

  // Expression handlers
  getParamExpression: (param: string) => string;
  handleParamExpressionChange: (param: string, value: string) => void;
  getToolParamExpression: (paramName: string) => string;
  handleToolParamExpressionChange: (paramName: string, value: string) => void;
  getLoopConditionExpression: () => string;
  handleLoopConditionExpressionChange: (value: string) => void;
  getListExpression: () => string;
  handleListExpressionChange: (value: string) => void;
  getColumnExpression: (columnName: string) => string;
  getColumnLabel: (columnName: string) => string;
  handleColumnExpressionChange: (columnName: string, value: string) => void;
  handleColumnLabelChange: (columnName: string, value: string) => void;
  handleDeleteColumn: (columnName: string) => void;
  handleAddColumn: () => void;

  // Interface editor props (for interface nodes)
  getEditorExpression?: () => string;
  handleEditorExpressionChange?: (value: string) => void;

  // Decision/Switch props
  currentConditions?: ConditionRow[];
  getConditionHandleId?: (condition: ConditionRow, index: number) => string;
  getConditionExpression?: (id: string) => string;
  handleConditionExpressionChange?: (id: string, value: string) => void;
  handleAddCondition?: (type: ConditionType, afterIndex?: number) => void;
  handleDeleteCondition?: (id: string) => void;
  handleRenameCondition?: (id: string, label: string) => void;

  // Switch-specific props
  currentCases?: SwitchCaseRow[];
  switchExpression?: string;
  getCaseHandleId?: (caseItem: SwitchCaseRow, index: number) => string;
  getCaseValue?: (id: string) => string;
  handleCaseValueChange?: (id: string, value: string) => void;
  handleSwitchExpressionChange?: (value: string) => void;
  handleAddCase?: () => void;
  handleDeleteCase?: (id: string) => void;
  handleRenameCase?: (id: string, label: string) => void;
}

/**
 * Renders the appropriate parameter form based on node type.
 * Consolidates form rendering logic to avoid duplication.
 */
export function ParameterFormRenderer({
  node,
  data,
  isRunMode,
  onUpdate,
  connections,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
  findUnknownVariables,
  showOptionalParams,
  onToggleOptionalParams,
  // Node type flags
  isSwitch,
  isTransform,
  isMerge,
  isWait,
  isInterfaceNode,
  isToolNode,
  isTablesTriggerForColumns,
  isMcpNode,
  // Tool props
  loadingToolDetails,
  toolParameters,
  // Column props
  isLoadingColumnsForSmallMode,
  columnsForSmallMode,
  dataSourceIdForColumns,
  dataSourceDataForColumns,
  // Expression handlers
  getParamExpression,
  handleParamExpressionChange,
  getToolParamExpression,
  handleToolParamExpressionChange,
  getLoopConditionExpression,
  handleLoopConditionExpressionChange,
  getListExpression,
  handleListExpressionChange,
  getColumnExpression,
  getColumnLabel,
  handleColumnExpressionChange,
  handleColumnLabelChange,
  handleDeleteColumn,
  handleAddColumn,
  // Interface editor props
  getEditorExpression,
  handleEditorExpressionChange,
  // Decision/Switch props
  currentConditions,
  getConditionHandleId,
  getConditionExpression,
  handleConditionExpressionChange,
  handleAddCondition,
  handleDeleteCondition,
  handleRenameCondition,
  // Switch-specific props
  currentCases,
  switchExpression,
  getCaseHandleId,
  getCaseValue,
  handleCaseValueChange,
  handleSwitchExpressionChange,
  handleAddCase,
  handleDeleteCase,
  handleRenameCase,
}: ParameterFormRendererProps) {
  // Interface node - special case
  if (isInterfaceNode && getEditorExpression && handleEditorExpressionChange) {
    return (
      <InterfaceMappingsColumn
        node={node}
        data={data}
        onUpdate={onUpdate}
        connections={connections}
        isRunMode={isRunMode}
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
    );
  }

  // Note node
  if (nodeRegistry.isNoteNode(node)) {
    return (
      <NoteParametersForm
        data={data}
        onUpdate={onUpdate}
        isRunMode={isRunMode}
      />
    );
  }

  // Transform node
  if (isTransform) {
    return (
      <TransformParametersForm
        node={node}
        data={data}
        connections={connections}
        isRunMode={isRunMode}
        onUpdate={onUpdate}
        findUnknownVariables={findUnknownVariables}
        draggingFromHandle={draggingFromHandle}
        hoveredTargetHandle={hoveredTargetHandle}
        handleHandleClick={handleHandleClick}
        handleHandleMouseDown={handleHandleMouseDown}
        handleHandleMouseUp={handleHandleMouseUp}
        handleSetHandleRef={handleSetHandleRef}
      />
    );
  }

  // Merge node
  if (isMerge) {
    return (
      <MergeParametersForm
        node={node}
        data={data}
        isRunMode={isRunMode}
        onUpdate={onUpdate}
      />
    );
  }

  // Wait node
  if (isWait) {
    return (
      <WaitParametersForm
        node={node}
        data={data}
        isRunMode={isRunMode}
        onUpdate={onUpdate}
      />
    );
  }

  // Render multiple forms in a fragment for nodes that need them
  return (
    <>
      {/* Decision branches */}
      {nodeRegistry.isDecisionLikeNode(node) && !nodeRegistry.isSwitchNode(node) &&
        currentConditions && getConditionHandleId && getConditionExpression &&
        handleConditionExpressionChange && handleAddCondition && handleDeleteCondition &&
        handleRenameCondition && (
          <DecisionBranchesForm
            node={node}
            connections={connections}
            isRunMode={isRunMode}
            currentConditions={currentConditions}
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
        )}

      {/* Switch cases */}
      {(isSwitch || nodeRegistry.isSwitchNode(node)) &&
        currentCases && switchExpression !== undefined && getCaseHandleId &&
        getCaseValue && handleCaseValueChange && handleSwitchExpressionChange &&
        handleAddCase && handleDeleteCase && handleRenameCase && (
          <SwitchCasesForm
            node={node}
            connections={connections}
            isRunMode={isRunMode}
            currentCases={currentCases}
            switchExpression={switchExpression}
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
        )}

      {/* Agent form */}
      {isAgentNode(data) && (
        <AgentParametersForm
          node={node}
          data={data}
          connections={connections}
          isRunMode={isRunMode}
          showOptionalParams={showOptionalParams}
          onToggleOptionalParams={onToggleOptionalParams}
          onUpdate={onUpdate}
          getParamExpression={getParamExpression}
          handleParamExpressionChange={handleParamExpressionChange}
          findUnknownVariables={findUnknownVariables}
          draggingFromHandle={draggingFromHandle}
          hoveredTargetHandle={hoveredTargetHandle}
          handleHandleClick={handleHandleClick}
          handleHandleMouseDown={handleHandleMouseDown}
          handleHandleMouseUp={handleHandleMouseUp}
          handleSetHandleRef={handleSetHandleRef}
        />
      )}

      {/* Split form */}
      {nodeRegistry.isSplitNode(node) && (
        <SplitParametersForm
          node={node}
          data={data}
          connections={connections}
          isRunMode={isRunMode}
          showOptionalParams={showOptionalParams}
          onToggleOptionalParams={onToggleOptionalParams}
          onUpdate={onUpdate}
          getListExpression={getListExpression}
          handleListExpressionChange={handleListExpressionChange}
          findUnknownVariables={findUnknownVariables}
          draggingFromHandle={draggingFromHandle}
          hoveredTargetHandle={hoveredTargetHandle}
          handleHandleClick={handleHandleClick}
          handleHandleMouseDown={handleHandleMouseDown}
          handleHandleMouseUp={handleHandleMouseUp}
          handleSetHandleRef={handleSetHandleRef}
        />
      )}

      {/* Tool parameters */}
      {isToolNode && (
        <ToolParametersForm
          node={node}
          connections={connections}
          isRunMode={isRunMode}
          isLoading={loadingToolDetails}
          toolParameters={toolParameters}
          showOptionalParams={showOptionalParams}
          onToggleOptionalParams={onToggleOptionalParams}
          getToolParamExpression={getToolParamExpression}
          handleToolParamExpressionChange={handleToolParamExpressionChange}
          findUnknownVariables={findUnknownVariables}
          draggingFromHandle={draggingFromHandle}
          hoveredTargetHandle={hoveredTargetHandle}
          handleHandleClick={handleHandleClick}
          handleHandleMouseDown={handleHandleMouseDown}
          handleHandleMouseUp={handleHandleMouseUp}
          handleSetHandleRef={handleSetHandleRef}
        />
      )}

      {/* DataSource columns */}
      {isTablesTriggerForColumns && dataSourceIdForColumns && !isMcpNode && (
        <DataSourceColumnsForm
          node={node}
          connections={connections}
          isRunMode={isRunMode}
          isLoading={isLoadingColumnsForSmallMode}
          columns={columnsForSmallMode}
          dataSourceData={dataSourceDataForColumns}
          getColumnExpression={getColumnExpression}
          getColumnLabel={getColumnLabel}
          handleColumnExpressionChange={handleColumnExpressionChange}
          handleColumnLabelChange={handleColumnLabelChange}
          handleDeleteColumn={handleDeleteColumn}
          handleAddColumn={handleAddColumn}
          findUnknownVariables={findUnknownVariables}
          draggingFromHandle={draggingFromHandle}
          hoveredTargetHandle={hoveredTargetHandle}
          handleHandleClick={handleHandleClick}
          handleHandleMouseDown={handleHandleMouseDown}
          handleHandleMouseUp={handleHandleMouseUp}
          handleSetHandleRef={handleSetHandleRef}
        />
      )}
    </>
  );
}
