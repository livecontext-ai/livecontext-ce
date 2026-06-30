import { useState, useEffect, useCallback, useRef } from 'react';

export type ViewMode = 'configuration' | 'result';

interface UseInspectorViewModeProps {
  isRunMode: boolean;
  runId?: string;
  isInterfaceNode: boolean;
  nodeId?: string;
  /**
   * Whether the selected node has produced any run data (non-empty statusCounts)
   * in the current run. Drives the per-node default of the execution-data toggle:
   * nodes that never ran open in Configuration view instead of empty Run data view.
   */
  nodeHasRunData?: boolean;
}

interface UseInspectorViewModeReturn {
  viewMode: ViewMode;
  setViewMode: (mode: ViewMode) => void;
  handleViewModeChange: (mode: ViewMode) => void;
  showExecutionData: boolean;
  handleShowExecutionDataChange: (show: boolean) => void;
}

/**
 * Hook to manage the inspector view mode (configuration vs result)
 * and the centralized execution data toggle (run data vs config).
 * Handles synchronization with workflow run mode and header events.
 */
export function useInspectorViewMode({
  isRunMode,
  runId,
  isInterfaceNode,
  nodeId,
  nodeHasRunData = false,
}: UseInspectorViewModeProps): UseInspectorViewModeReturn {
  // Initial state - always start with configuration
  const [viewMode, setViewMode] = useState<ViewMode>('configuration');

  // Centralized execution data toggle (true = show run data, false = show config/schema).
  // In run mode, default to run data only when the node actually has run data; a node
  // with no statusCounts opens in Configuration view, not an empty Run data view.
  const [showExecutionData, setShowExecutionData] = useState(
    () => !isRunMode || nodeHasRunData
  );

  // Ref to track previous run mode
  const prevIsRunModeRef = useRef(isRunMode);

  // Sync view mode when run mode changes
  useEffect(() => {
    const prevIsRunMode = prevIsRunModeRef.current;

    // Switching from run to edit -> show configuration
    if (prevIsRunMode && !isRunMode) {
      setViewMode('configuration');
      setShowExecutionData(true);
    }

    prevIsRunModeRef.current = isRunMode;
  }, [isRunMode]);

  // Force configuration mode for interface nodes (they use 3-mode output column instead of result view)
  useEffect(() => {
    if (isInterfaceNode) {
      setViewMode('configuration');
    }
  }, [isInterfaceNode, nodeId]);

  // Per-node default for the execution-data toggle: in run mode, show run data when
  // the selected node has run data (statusCounts), otherwise fall back to the
  // Configuration view. Re-runs when a different node is selected or when a node
  // first produces run data during a live run.
  useEffect(() => {
    if (!isRunMode) return;
    setShowExecutionData(nodeHasRunData);
  }, [isRunMode, nodeId, nodeHasRunData]);

  // Listen for view mode changes from header (in run mode)
  useEffect(() => {
    if (!runId) return;

    const handleEvent = (event: CustomEvent) => {
      const { mode } = event.detail;
      if (mode === 'configuration' || mode === 'result') {
        setViewMode(mode);
      }
    };

    window.addEventListener('workflowViewModeChange', handleEvent as EventListener);

    return () => {
      window.removeEventListener('workflowViewModeChange', handleEvent as EventListener);
    };
  }, [runId]);

  // Listen for execution data toggle changes from header
  useEffect(() => {
    if (!runId) return;

    const handleEvent = (event: CustomEvent) => {
      const { show } = event.detail;
      if (typeof show === 'boolean') {
        setShowExecutionData(show);
      }
    };

    window.addEventListener('workflowExecutionDataChange', handleEvent as EventListener);

    return () => {
      window.removeEventListener('workflowExecutionDataChange', handleEvent as EventListener);
    };
  }, [runId]);

  // Handler to change view mode and sync with header
  const handleViewModeChange = useCallback((mode: ViewMode) => {
    setViewMode(mode);
    // Dispatch event to sync with header
    window.dispatchEvent(new CustomEvent('workflowViewModeChange', {
      detail: { mode }
    }));
  }, []);

  // Handler to change execution data toggle and sync across components
  const handleShowExecutionDataChange = useCallback((show: boolean) => {
    setShowExecutionData(show);
    window.dispatchEvent(new CustomEvent('workflowExecutionDataChange', {
      detail: { show }
    }));
  }, []);

  return {
    viewMode,
    setViewMode,
    handleViewModeChange,
    showExecutionData,
    handleShowExecutionDataChange,
  };
}
