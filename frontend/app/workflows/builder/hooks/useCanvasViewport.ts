import * as React from 'react';
import type { ReactFlowInstance, Node } from 'reactflow';
import type { BuilderNodeData } from '../types';

interface UseCanvasViewportProps {
  instance: ReactFlowInstance | null;
  nodes: Node<BuilderNodeData>[];
  workflowId?: string;
  isLoadingWorkflow: boolean;
  onReactFlowInstance?: (instance: ReactFlowInstance) => void;
}

interface UseCanvasViewportReturn {
  isViewReady: boolean;
  handleInstanceInit: (instance: ReactFlowInstance) => void;
  handleZoomIn: () => void;
  handleZoomOut: () => void;
  handleFitView: () => void;
}

/**
 * Hook for managing canvas viewport (zoom, fit view, etc.).
 * Handles initial fit view and provides zoom controls.
 */
export function useCanvasViewport({
  instance,
  nodes,
  workflowId,
  isLoadingWorkflow,
  onReactFlowInstance,
}: UseCanvasViewportProps): UseCanvasViewportReturn {
  const [isViewReady, setIsViewReady] = React.useState(false);
  const [localInstance, setLocalInstance] = React.useState<ReactFlowInstance | null>(instance);
  const hasFittedViewRef = React.useRef(false);
  const workflowIdRef = React.useRef(workflowId);
  const prevNodesLengthRef = React.useRef(0);

  // Update workflowId ref when it changes
  React.useEffect(() => {
    workflowIdRef.current = workflowId;
    // Reset hasFittedViewRef and isViewReady when workflowId changes
    if (workflowId) {
      hasFittedViewRef.current = false;
      setIsViewReady(false);
      prevNodesLengthRef.current = 0;
    }
  }, [workflowId]);

  // Reset isViewReady when nodes change from 0 to > 0 (new workflow loaded)
  React.useEffect(() => {
    if (prevNodesLengthRef.current === 0 && nodes.length > 0) {
      setIsViewReady(false);
      hasFittedViewRef.current = false;
    }
    prevNodesLengthRef.current = nodes.length;
  }, [nodes.length]);

  // Expose instance to parent
  React.useEffect(() => {
    if (localInstance && onReactFlowInstance) {
      onReactFlowInstance(localInstance);
    }
  }, [localInstance, onReactFlowInstance]);

  // Handle instance initialization
  const handleInstanceInit = React.useCallback((newInstance: ReactFlowInstance) => {
    setLocalInstance(newInstance);
  }, []);

  // Fit view once after instance and nodes are ready
  React.useEffect(() => {
    const inst = localInstance || instance;

    // No nodes and not loading = empty workflow, mark as ready
    if (nodes.length === 0 && !isLoadingWorkflow) {
      setIsViewReady(true);
      return;
    }

    // Fit view when we have instance + nodes + haven't fitted yet
    if (inst && nodes.length > 0 && !hasFittedViewRef.current) {
      const timeoutId = setTimeout(() => {
        try {
          inst.fitView({ padding: 0.2, duration: 0 });
          hasFittedViewRef.current = true;
          setIsViewReady(true);
        } catch {
          setIsViewReady(true);
        }
      }, 50);

      return () => clearTimeout(timeoutId);
    }
  }, [instance, localInstance, nodes.length, isLoadingWorkflow]);

  // Listen for fit view requests (e.g., when sidepanel opens)
  React.useEffect(() => {
    const inst = localInstance || instance;
    const handleFitViewRequest = (event: CustomEvent<{ animated?: boolean }>) => {
      if (inst) {
        // Use animation only when explicitly requested (sidebar open/close)
        const duration = event.detail?.animated ? 300 : 0;
        setTimeout(() => {
          try {
            inst.fitView({ padding: 0.2, duration });
          } catch {
            // Ignore errors
          }
        }, 50);
      }
    };

    window.addEventListener('workflowViewFitView', handleFitViewRequest as EventListener);
    return () => {
      window.removeEventListener('workflowViewFitView', handleFitViewRequest as EventListener);
    };
  }, [instance, localInstance]);

  // Zoom handlers - no animation for instant response
  const handleZoomIn = React.useCallback(() => {
    const inst = localInstance || instance;
    if (inst) {
      inst.zoomIn({ duration: 0 });
    }
  }, [instance, localInstance]);

  const handleZoomOut = React.useCallback(() => {
    const inst = localInstance || instance;
    if (inst) {
      inst.zoomOut({ duration: 0 });
    }
  }, [instance, localInstance]);

  const handleFitView = React.useCallback(() => {
    const inst = localInstance || instance;
    if (inst) {
      inst.fitView({ padding: 0.2, duration: 0 });
    }
  }, [instance, localInstance]);

  return {
    isViewReady,
    handleInstanceInit,
    handleZoomIn,
    handleZoomOut,
    handleFitView,
  };
}
