/**
 * Hook that provides common props for all inspector forms.
 *
 * This hook eliminates prop drilling by bundling all shared functionality:
 * - Expression management (get/set)
 * - Connection props (drag/drop handles)
 * - Validation errors
 * - Unknown variable detection
 *
 * Usage in forms:
 * ```tsx
 * function MyForm(props: InspectorFormProps) {
 *   const { getExpression, setExpression, connectionProps, errors } = props;
 *   // Use directly - no need to destructure 15 individual props
 * }
 * ```
 */

import { useCallback, useMemo } from 'react';
import { Node } from 'reactflow';
import type { Connection } from '../useInspectorConnections';
import { BuilderNodeData } from '../../../types';
import { InspectorFormProps, ConnectionPropsBundle } from './types';

interface UseFormCommonPropsParams {
  node: Node<BuilderNodeData>;
  isRunMode: boolean;
  onUpdate: (data: Partial<BuilderNodeData>) => void;

  // From useInspectorExpressions
  getParamExpression: (field: string) => string;
  handleParamExpressionChange: (field: string, value: string) => void;
  getToolParamExpression: (paramName: string) => string;
  handleToolParamExpressionChange: (paramName: string, value: string) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];

  // From useInspectorConnections
  connections: Connection[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, nodeId: string) => void;
  handleHandleMouseDown: (e: React.MouseEvent, handleId: string, nodeId: string) => void;
  handleHandleMouseUp: (handleId: string, nodeId: string) => void;
  handleSetHandleRef: (handleId: string, element: HTMLElement | null) => void;

  // From useInspectorValidation
  errors?: Record<string, string>;
}

/**
 * Creates the common props bundle for inspector forms.
 */
export function useFormCommonProps(params: UseFormCommonPropsParams): InspectorFormProps {
  const {
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
  } = params;

  // Bundle connection props to avoid drilling 8 individual props
  const connectionProps: ConnectionPropsBundle = useMemo(
    () => ({
      connections,
      draggingFromHandle,
      hoveredTargetHandle,
      handleHandleClick,
      handleHandleMouseDown,
      handleHandleMouseUp,
      handleSetHandleRef,
    }),
    [
      connections,
      draggingFromHandle,
      hoveredTargetHandle,
      handleHandleClick,
      handleHandleMouseDown,
      handleHandleMouseUp,
      handleSetHandleRef,
    ]
  );

  // Wrap expression getters/setters for cleaner API
  const getExpression = useCallback(
    (field: string) => getParamExpression(field),
    [getParamExpression]
  );

  const setExpression = useCallback(
    (field: string, value: string) => handleParamExpressionChange(field, value),
    [handleParamExpressionChange]
  );

  return useMemo(
    () => ({
      node,
      isRunMode,
      onUpdate,
      getExpression,
      setExpression,
      getToolParamExpression,
      setToolParamExpression: handleToolParamExpressionChange,
      connectionProps,
      errors,
      findUnknownVariables,
    }),
    [
      node,
      isRunMode,
      onUpdate,
      getExpression,
      setExpression,
      getToolParamExpression,
      handleToolParamExpressionChange,
      connectionProps,
      errors,
      findUnknownVariables,
    ]
  );
}

/**
 * Spread helper for connection props.
 * Use this when you need to pass connection props to a child component
 * that expects individual props (for backwards compatibility).
 */
export function spreadConnectionProps(bundle: ConnectionPropsBundle) {
  return {
    connections: bundle.connections,
    draggingFromHandle: bundle.draggingFromHandle,
    hoveredTargetHandle: bundle.hoveredTargetHandle,
    handleHandleClick: bundle.handleHandleClick,
    handleHandleMouseDown: bundle.handleHandleMouseDown,
    handleHandleMouseUp: bundle.handleHandleMouseUp,
    handleSetHandleRef: bundle.handleSetHandleRef,
  };
}
