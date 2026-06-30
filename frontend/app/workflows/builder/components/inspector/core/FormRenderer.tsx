/**
 * FormRenderer - Central component for rendering inspector forms.
 *
 * This component:
 * 1. Detects the node type
 * 2. Looks up the form definition in the registry
 * 3. Renders the appropriate form component
 * 4. Injects common props automatically
 *
 * No more giant switch/case or if/else chains!
 * Adding a new node type = 1 entry in the registry.
 */

'use client';

import React from 'react';
import { Node } from 'reactflow';
import type { Connection } from '../useInspectorConnections';
import { BuilderNodeData } from '../../../types';
import { detectNodeType, InspectorFormProps } from './types';
import { formRegistry } from '../registry/form-registry';
import { UnknownNodeForm } from '../forms/UnknownNodeForm';

interface FormRendererProps {
  node: Node<BuilderNodeData>;
  isRunMode: boolean;
  onUpdate: (data: Partial<BuilderNodeData>) => void;

  // Expression helpers
  getParamExpression: (field: string) => string;
  handleParamExpressionChange: (field: string, value: string) => void;
  getToolParamExpression: (paramName: string) => string;
  handleToolParamExpressionChange: (paramName: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];

  // Connection props
  connections: Connection[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, nodeId: string) => void;
  handleHandleMouseDown: (e: React.MouseEvent, handleId: string, nodeId: string) => void;
  handleHandleMouseUp: (handleId: string, nodeId: string) => void;
  handleSetHandleRef: (handleId: string, element: HTMLElement | null) => void;

  // Validation
  errors?: Record<string, string>;

  // Optional: tool details for MCP nodes
  toolDetails?: any;
}

export function FormRenderer({
  node,
  isRunMode,
  onUpdate,
  getParamExpression,
  handleParamExpressionChange,
  getToolParamExpression,
  handleToolParamExpressionChange,
  findUnknownVariables,
  connections,
  draggingFromHandle,
  hoveredTargetHandle,
  handleHandleClick,
  handleHandleMouseDown,
  handleHandleMouseUp,
  handleSetHandleRef,
  errors = {},
  toolDetails,
}: FormRendererProps) {
  // 1. Detect node type
  const nodeType = detectNodeType(node);

  // 2. Get form definition from registry
  const definition = formRegistry[nodeType];

  // 3. Build common props
  const formProps: InspectorFormProps = {
    node,
    isRunMode,
    onUpdate,
    getExpression: getParamExpression,
    setExpression: handleParamExpressionChange,
    getToolParamExpression,
    setToolParamExpression: handleToolParamExpressionChange,
    connectionProps: {
      connections,
      draggingFromHandle,
      hoveredTargetHandle,
      handleHandleClick,
      handleHandleMouseDown,
      handleHandleMouseUp,
      handleSetHandleRef,
    },
    errors,
    findUnknownVariables,
  };

  // 4. Render the form
  if (!definition) {
    return <UnknownNodeForm {...formProps} />;
  }

  const FormComponent = definition.component;
  return <FormComponent {...formProps} />;
}

export default FormRenderer;
