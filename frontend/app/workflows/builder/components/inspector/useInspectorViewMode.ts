import { useState, useEffect, useCallback, useRef } from 'react';

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
  showExecutionData: boolean;
  handleShowExecutionDataChange: (show: boolean) => void;
}

/**
 * Manages the inspector's centralized execution-data toggle.
 * Logs are intentionally outside this hook because they live in the side panel.
 */
export function useInspectorViewMode({
  isRunMode,
  runId,
  nodeId,
  nodeHasRunData = false,
}: UseInspectorViewModeProps): UseInspectorViewModeReturn {
  // Centralized execution data toggle (true = show run data, false = show config/schema).
  // In run mode, default to run data only when the node actually has run data; a node
  // with no statusCounts opens in Configuration view, not an empty Run data view.
  const [showExecutionData, setShowExecutionData] = useState(
    () => !isRunMode || nodeHasRunData
  );

  // Ref to track previous run mode
  const prevIsRunModeRef = useRef(isRunMode);

  // Sync the execution-data default when run mode changes.
  useEffect(() => {
    const prevIsRunMode = prevIsRunModeRef.current;

    // Switching from run to edit restores the output/schema default.
    if (prevIsRunMode && !isRunMode) {
      setShowExecutionData(true);
    }

    prevIsRunModeRef.current = isRunMode;
  }, [isRunMode]);

  // Per-node default for the execution-data toggle: in run mode, show run data when
  // the selected node has run data (statusCounts), otherwise fall back to the
  // Configuration view. Re-runs when a different node is selected or when a node
  // first produces run data during a live run.
  //
  // Latched per node, and deliberately one-way. `nodeHasRunData` also folds in
  // "the node is executing / parked on a signal", which can go back to false
  // without the node leaving any statusCounts behind (a branch pruned mid-flight,
  // a skip cascade). Following that down would yank the panel back to the
  // configuration form under a reader who is looking at the run.
  //
  // State adjusted during render rather than a ref written during render: this is
  // derived state, and the ref form is not safe under a re-entrant render.
  //
  // Keyed on the RUN as well as the node: the same node in another run may never
  // have executed there, and a latch that ignored the run would hold an empty
  // run view open on it.
  const latchKey = `${runId ?? ''}:${nodeId ?? ''}`;
  const [latchedKey, setLatchedKey] = useState(latchKey);
  const [nodeEverHadRunData, setNodeEverHadRunData] = useState(nodeHasRunData);
  if (latchedKey !== latchKey) {
    setLatchedKey(latchKey);
    setNodeEverHadRunData(nodeHasRunData);
  } else if (nodeHasRunData && !nodeEverHadRunData) {
    setNodeEverHadRunData(true);
  }

  useEffect(() => {
    if (!isRunMode) return;
    setShowExecutionData(nodeEverHadRunData);
  }, [isRunMode, nodeId, nodeEverHadRunData]);

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

  // Handler to change execution data toggle and sync across components
  const handleShowExecutionDataChange = useCallback((show: boolean) => {
    setShowExecutionData(show);
    window.dispatchEvent(new CustomEvent('workflowExecutionDataChange', {
      detail: { show }
    }));
  }, []);

  return {
    showExecutionData,
    handleShowExecutionDataChange,
  };
}
