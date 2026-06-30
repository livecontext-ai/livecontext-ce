import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';

/**
 * Common props shared across all parameter forms.
 * Bundles connection and handle interaction props to reduce prop drilling.
 */
export interface FormConnectionProps {
  connections: any[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e?: React.MouseEvent) => void;
  handleHandleMouseDown: (id: string) => void;
  handleHandleMouseUp: (id: string) => void;
  handleSetHandleRef: (id: string, el: HTMLDivElement | null) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Props for forms that support optional parameters toggle.
 */
export interface FormOptionalProps {
  showOptionalParams: boolean;
  onToggleOptionalParams: () => void;
}

/**
 * All common form props bundled together.
 */
export interface CommonFormProps extends FormConnectionProps, FormOptionalProps {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode: boolean;
  onUpdate: (data: BuilderNodeData) => void;
}

interface UseInspectorFormPropsInput {
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  isRunMode: boolean;
  connections: any[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e?: React.MouseEvent) => void;
  handleHandleMouseDown: (id: string) => void;
  handleHandleMouseUp: (id: string) => void;
  handleSetHandleRef: (id: string, el: HTMLDivElement | null) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
  onUpdate: (data: BuilderNodeData) => void;
}

/**
 * Hook that bundles common form props to reduce prop drilling.
 * Returns memoized objects to prevent unnecessary re-renders.
 */
export function useInspectorFormProps(input: UseInspectorFormPropsInput) {
  const {
    node,
    data,
    isRunMode,
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
    findUnknownVariables,
    onUpdate,
  } = input;

  const [showOptionalParams, setShowOptionalParams] = React.useState(false);

  // Reset optional params visibility when node changes
  React.useEffect(() => {
    setShowOptionalParams(false);
  }, [node?.id]);

  const connectionProps: FormConnectionProps = React.useMemo(() => ({
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
    findUnknownVariables,
  }), [
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
    findUnknownVariables,
  ]);

  const optionalProps: FormOptionalProps = React.useMemo(() => ({
    showOptionalParams,
    onToggleOptionalParams: () => setShowOptionalParams(prev => !prev),
  }), [showOptionalParams]);

  const commonProps: CommonFormProps = React.useMemo(() => ({
    node,
    data,
    isRunMode,
    onUpdate,
    ...connectionProps,
    ...optionalProps,
  }), [node, data, isRunMode, onUpdate, connectionProps, optionalProps]);

  return {
    connectionProps,
    optionalProps,
    commonProps,
    showOptionalParams,
    setShowOptionalParams,
  };
}
