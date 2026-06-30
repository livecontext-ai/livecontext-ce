/**
 * NodeFormRenderer - Registry-based form renderer.
 *
 * Uses the form registry pattern to render the appropriate form
 * for each node type. No legacy, no fallback.
 *
 * Categories: triggers, agents, cores, tools, tables, notes, interface
 */

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow, SwitchCaseRow } from '../../types';
import { detectNodeType, InspectorFormProps, ConnectionPropsBundle } from './core/types';
import { getFormDefinition } from './registry/form-registry';

interface NodeFormRendererProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode?: boolean;
  onUpdate: (data: BuilderNodeData) => void;

  // Connection props
  connections?: any;
  findUnknownVariables?: (expressions: Record<string, string>) => string[];
  draggingFromHandle?: string | null;
  hoveredTargetHandle?: string | null;
  handleHandleClick?: (targetHandle: string, e: React.MouseEvent) => void;
  handleHandleMouseDown?: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp?: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef?: (targetHandle: string, element: HTMLDivElement | null) => void;

  // Webhook tokens map for multi-DAG support (triggerId -> token)
  webhookTokens?: Record<string, string>;

  // Decision/Switch state (for cores)
  currentConditions?: ConditionRow[];
  getConditionExpression?: (conditionId: string) => string;
  handleConditionExpressionChange?: (conditionId: string, value: string) => void;
  getConditionHandleId?: (condition: ConditionRow, index: number) => string;
  handleAddCondition?: (type: 'elseif' | 'else', afterIndex: number) => void;
  handleDeleteCondition?: (conditionId: string) => void;
  handleRenameCondition?: (conditionId: string, newLabel: string) => void;

  // Switch state
  currentCases?: SwitchCaseRow[];
  switchExpression?: string;
  getCaseHandleId?: (caseRow: SwitchCaseRow, index: number) => string;
  getCaseValue?: (caseId: string) => string;
  handleCaseValueChange?: (caseId: string, value: string) => void;
  handleSwitchExpressionChange?: (value: string) => void;
  handleAddCase?: (afterIndex: number) => void;
  handleDeleteCase?: (caseId: string) => void;
  handleRenameCase?: (caseId: string, newLabel: string) => void;

  // Tables trigger state
  columns?: any[];
  isLoadingColumns?: boolean;
  getColumnExpression?: (field: string) => string;
  handleColumnExpressionChange?: (field: string, value: string) => void;
  getColumnLabel?: (field: string) => string;
  handleColumnLabelChange?: (field: string, label: string) => void;
  handleDeleteColumn?: (field: string) => void;
  handleAddColumn?: () => void;

  // Graph data for AI Agent
  allNodes?: Node<BuilderNodeData>[];
  edges?: any[];

  // Expression helpers
  getParamExpression?: (field: string) => string;
  handleParamExpressionChange?: (field: string, value: string) => void;
}

/**
 * Centralized form renderer using registry pattern.
 */
export const NodeFormRenderer: React.FC<NodeFormRendererProps> = (props) => {
  const {
    node,
    data,
    isRunMode = false,
    onUpdate,
    connections = [],
    findUnknownVariables = () => [],
    draggingFromHandle = null,
    hoveredTargetHandle = null,
    handleHandleClick = () => {},
    handleHandleMouseDown = () => {},
    handleHandleMouseUp = () => {},
    handleSetHandleRef = () => {},
    webhookTokens,
    // Decision state
    currentConditions,
    getConditionExpression,
    handleConditionExpressionChange,
    getConditionHandleId,
    handleAddCondition,
    handleDeleteCondition,
    handleRenameCondition,
    // Switch state
    currentCases,
    switchExpression,
    getCaseHandleId,
    getCaseValue,
    handleCaseValueChange,
    handleSwitchExpressionChange,
    handleAddCase,
    handleDeleteCase,
    handleRenameCase,
    // Tables trigger state
    columns,
    isLoadingColumns,
    getColumnExpression,
    handleColumnExpressionChange,
    getColumnLabel,
    handleColumnLabelChange,
    handleDeleteColumn,
    handleAddColumn,
    // Graph data
    allNodes,
    edges,
    // Expression helpers
    getParamExpression,
    handleParamExpressionChange,
  } = props;

  // Detect node type
  const nodeType = detectNodeType(node);

  // Get form definition from registry
  const definition = getFormDefinition(nodeType);

  // No definition found - nothing to render
  if (!definition) {
    return null;
  }

  // Build connection props bundle
  const connectionProps: ConnectionPropsBundle = {
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick: (handleId: string, nodeId: string) =>
      handleHandleClick(handleId, {} as React.MouseEvent),
    handleHandleMouseDown: (e: React.MouseEvent, handleId: string, nodeId: string) =>
      handleHandleMouseDown(handleId, e),
    handleHandleMouseUp: (handleId: string, nodeId: string) =>
      handleHandleMouseUp(handleId, {} as React.MouseEvent),
    handleSetHandleRef: (handleId: string, el: HTMLElement | null) =>
      handleSetHandleRef(handleId, el as HTMLDivElement | null),
  };

  // Build form props
  const formProps: InspectorFormProps = {
    node,
    isRunMode,
    onUpdate: (partialData) => onUpdate({ ...data, ...partialData }),
    getExpression: getParamExpression || ((field: string) => {
      const expressions = (data as any).paramExpressions || {};
      return expressions[field] || '';
    }),
    setExpression: handleParamExpressionChange || ((field: string, value: string) => {
      const expressions = (data as any).paramExpressions || {};
      onUpdate({
        ...data,
        paramExpressions: { ...expressions, [field]: value },
      } as BuilderNodeData);
    }),
    getToolParamExpression: (paramName: string) => {
      const expressions = (data as any).toolParamExpressions || {};
      return expressions[paramName] || '';
    },
    setToolParamExpression: (paramName: string, value: string) => {
      const expressions = (data as any).toolParamExpressions || {};
      onUpdate({
        ...data,
        toolParamExpressions: { ...expressions, [paramName]: value },
      } as BuilderNodeData);
    },
    connectionProps,
    errors: {},
    findUnknownVariables,
    // Webhook tokens for multi-DAG support
    webhookTokens,
    // Decision state
    currentConditions,
    getConditionExpression,
    handleConditionExpressionChange,
    getConditionHandleId,
    handleAddCondition,
    handleDeleteCondition,
    handleRenameCondition,
    // Switch state
    currentCases,
    switchExpression,
    getCaseHandleId,
    getCaseValue,
    handleCaseValueChange,
    handleSwitchExpressionChange,
    handleAddCase,
    handleDeleteCase,
    handleRenameCase,
    // Tables trigger state
    columns,
    isLoadingColumns,
    getColumnExpression,
    handleColumnExpressionChange,
    getColumnLabel,
    handleColumnLabelChange,
    handleDeleteColumn,
    handleAddColumn,
    // Graph data
    allNodes,
    edges,
  };

  // Render the form component
  const FormComponent = definition.component;
  return <FormComponent {...formProps} />;
};
