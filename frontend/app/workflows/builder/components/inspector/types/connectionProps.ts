'use client';

import type { Connection } from '../useInspectorConnections';

/**
 * Bundle of connection-related props that are passed to all forms.
 * This reduces prop drilling and improves maintainability.
 */
export interface ConnectionPropsBundle {
  connections: Connection[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseDown: (handleId: string, e: React.MouseEvent) => void;
  handleHandleMouseUp: (handleId: string, e: React.MouseEvent) => void;
  handleSetHandleRef: (handleId: string, el: HTMLDivElement | null) => void;
  findUnknownVariables: (expressions: Record<string, string>) => string[];
}

/**
 * Helper function to spread connection props to form components.
 * Usage: <FormComponent {...spreadConnectionProps(connectionProps)} />
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
    findUnknownVariables: bundle.findUnknownVariables,
  };
}
