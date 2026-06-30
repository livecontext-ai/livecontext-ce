'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow, SwitchCaseRow } from '../../types';
import type { ConnectionPropsBundle } from './types/connectionProps';
import {
  AgentParametersForm,
  isAgentNode,
  SplitParametersForm,
  NoteParametersForm,
  DecisionBranchesForm,
  SwitchCasesForm,
  TransformParametersForm,
  MergeParametersForm,
  WaitParametersForm,
} from './forms';
import { nodeRegistry } from '../../registry/nodeRegistry';

/**
 * Props for decision branches form
 */
export interface DecisionFormProps {
  currentConditions: ConditionRow[];
  getConditionHandleId: (condition: ConditionRow, index: number) => string;
  getConditionExpression: (conditionId: string) => string;
  handleConditionExpressionChange: (conditionId: string, value: string) => void;
  handleAddCondition: (type: 'elseif' | 'else', afterIndex: number) => void;
  handleDeleteCondition: (conditionId: string) => void;
  handleRenameCondition: (conditionId: string, newLabel: string) => void;
}

/**
 * Props for switch cases form
 */
export interface SwitchFormProps {
  currentCases: SwitchCaseRow[];
  switchExpression: string;
  getCaseHandleId: (caseRow: SwitchCaseRow, index: number) => string;
  getCaseValue: (caseId: string) => string;
  handleCaseValueChange: (caseId: string, value: string) => void;
  handleSwitchExpressionChange: (value: string) => void;
  handleAddCase: (afterIndex: number) => void;
  handleDeleteCase: (caseId: string) => void;
  handleRenameCase: (caseId: string, newLabel: string) => void;
}

/**
 * Props for agent parameters form
 */
export interface AgentFormProps {
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  onUpdate: (data: BuilderNodeData) => void;
  getParamExpression: (paramName: string) => string;
  handleParamExpressionChange: (paramName: string, value: string) => void;
}

/**
 * Props for split parameters form
 */
export interface SplitFormProps {
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
  onUpdate: (data: BuilderNodeData) => void;
  getListExpression: () => string;
  handleListExpressionChange: (value: string) => void;
}

/**
 * Props for transform parameters form
 */
export interface TransformFormProps {
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Props for simple forms (note, merge, wait)
 */
export interface SimpleFormProps {
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Node type detection flags
 */
export interface NodeTypeFlags {
  isSwitch: boolean;
  isTransform: boolean;
  isMerge: boolean;
  isWait: boolean;
}

/**
 * Main props for FormTypeRouter
 */
export interface FormTypeRouterProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode: boolean;

  // Connection props bundle
  connectionProps: ConnectionPropsBundle;

  // Node type detection
  nodeTypeFlags: NodeTypeFlags;

  // Form-specific props
  decisionProps: DecisionFormProps;
  switchProps: SwitchFormProps;
  agentProps: AgentFormProps;
  splitProps: SplitFormProps;
  transformProps: TransformFormProps;
  simpleFormProps: SimpleFormProps;
}

/**
 * FormTypeRouter - Unified form selection component
 *
 * This component eliminates code duplication between mobile tabs and desktop
 * non-advanced modes by centralizing form type detection and rendering.
 */
export function FormTypeRouter({
  node,
  data,
  isRunMode,
  connectionProps,
  nodeTypeFlags,
  decisionProps,
  switchProps,
  agentProps,
  splitProps,
  transformProps,
  simpleFormProps,
}: FormTypeRouterProps) {
  const { isSwitch, isTransform, isMerge, isWait } = nodeTypeFlags;

  // Spread connection props for forms
  const connProps = {
    connections: connectionProps.connections,
    draggingFromHandle: connectionProps.draggingFromHandle,
    hoveredTargetHandle: connectionProps.hoveredTargetHandle,
    handleHandleClick: connectionProps.handleHandleClick,
    handleHandleMouseDown: connectionProps.handleHandleMouseDown,
    handleHandleMouseUp: connectionProps.handleHandleMouseUp,
    handleSetHandleRef: connectionProps.handleSetHandleRef,
    findUnknownVariables: connectionProps.findUnknownVariables,
  };

  return (
    <div className="space-y-6">
      {/* Decision Branches Form */}
      {nodeRegistry.isDecisionNode(node) && !nodeRegistry.isSwitchNode(node) && (
        <DecisionBranchesForm
          node={node}
          isRunMode={isRunMode}
          currentConditions={decisionProps.currentConditions}
          getConditionHandleId={decisionProps.getConditionHandleId}
          getConditionExpression={decisionProps.getConditionExpression}
          handleConditionExpressionChange={decisionProps.handleConditionExpressionChange}
          handleAddCondition={decisionProps.handleAddCondition}
          handleDeleteCondition={decisionProps.handleDeleteCondition}
          handleRenameCondition={decisionProps.handleRenameCondition}
          {...connProps}
        />
      )}

      {/* Switch Cases Form */}
      {(isSwitch || nodeRegistry.isSwitchNode(node)) && (
        <SwitchCasesForm
          node={node}
          isRunMode={isRunMode}
          currentCases={switchProps.currentCases}
          switchExpression={switchProps.switchExpression}
          getCaseHandleId={switchProps.getCaseHandleId}
          getCaseValue={switchProps.getCaseValue}
          handleCaseValueChange={switchProps.handleCaseValueChange}
          handleSwitchExpressionChange={switchProps.handleSwitchExpressionChange}
          handleAddCase={switchProps.handleAddCase}
          handleDeleteCase={switchProps.handleDeleteCase}
          handleRenameCase={switchProps.handleRenameCase}
          {...connProps}
        />
      )}

      {/* Agent Parameters Form */}
      {isAgentNode(data) && (
        <AgentParametersForm
          node={node}
          data={data}
          isRunMode={isRunMode}
          showOptionalParams={agentProps.showOptionalParams}
          onToggleOptionalParams={agentProps.onToggleOptionalParams}
          onUpdate={agentProps.onUpdate}
          getParamExpression={agentProps.getParamExpression}
          handleParamExpressionChange={agentProps.handleParamExpressionChange}
          {...connProps}
        />
      )}

      {/* Split Parameters Form */}
      {nodeRegistry.isSplitNode(node) && (
        <SplitParametersForm
          node={node}
          data={data}
          isRunMode={isRunMode}
          showOptionalParams={splitProps.showOptionalParams}
          onToggleOptionalParams={splitProps.onToggleOptionalParams}
          onUpdate={splitProps.onUpdate}
          getListExpression={splitProps.getListExpression}
          handleListExpressionChange={splitProps.handleListExpressionChange}
          {...connProps}
        />
      )}

      {/* Note Parameters Form */}
      {nodeRegistry.isNoteNode(node) && (
        <NoteParametersForm
          data={data}
          onUpdate={simpleFormProps.onUpdate}
          isRunMode={isRunMode}
        />
      )}

      {/* Transform Parameters Form */}
      {isTransform && (
        <TransformParametersForm
          node={node}
          data={data}
          isRunMode={isRunMode}
          onUpdate={transformProps.onUpdate}
          {...connProps}
        />
      )}

      {/* Merge Parameters Form */}
      {isMerge && (
        <MergeParametersForm
          node={node}
          data={data}
          isRunMode={isRunMode}
          onUpdate={simpleFormProps.onUpdate}
        />
      )}

      {/* Wait Parameters Form */}
      {isWait && (
        <WaitParametersForm
          node={node}
          data={data}
          isRunMode={isRunMode}
          onUpdate={simpleFormProps.onUpdate}
        />
      )}
    </div>
  );
}
