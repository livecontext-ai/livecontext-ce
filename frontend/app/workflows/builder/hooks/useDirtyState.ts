'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';

interface UseDirtyStateOptions {
  nodes: Node[];
  edges: Edge[];
  workflowLoaded: boolean;
  isRunMode: boolean;
  onDirtyChange?: (isDirty: boolean) => void;
  onRefreshBlocked?: () => void;
}

interface UseDirtyStateReturn {
  isDirty: boolean;
  resetDirtyState: (nodes: Node[], edges: Edge[]) => void;
}

/**
 * Volatile/runtime keys in node data that should NOT trigger dirty state.
 * These change without user edits (streaming updates, mode transitions, UI state).
 */
const VOLATILE_DATA_KEYS = new Set([
  // Run-mode status (set by streaming / useRunStateProcessing)
  'status', 'statusCounts', 'metrics',
  // Loop iteration counters (set during execution)
  'currentIteration', 'maxIterations', 'totalIterations', 'completedItems',
  // Decision evaluation state
  'selectedBranch',
  // UI runtime state (set by usePreparedGraph)
  'highlightState', 'selectedLoopChildId', 'isPreviewMode', 'validationIssues',
  // Callback functions (already ignored by JSON.stringify, listed for clarity)
  'onDeleteNode', 'onDuplicateNode', 'onTogglePreview', 'onNodeUpdate',
  'onExtractLoopChild', 'onNoteUpdate', 'onLoopClick', 'onLoopChildClick',
  'onCreateNode', 'onConnect',
]);

/** Strip volatile keys from a data object for stable hashing. */
function stripVolatile(data: Record<string, any> | undefined): Record<string, any> {
  if (!data) return {};
  const clean: Record<string, any> = {};
  for (const key of Object.keys(data)) {
    if (VOLATILE_DATA_KEYS.has(key)) continue;
    // For loopChildren, recursively strip volatile keys from each child
    if (key === 'loopChildren' && Array.isArray(data[key])) {
      clean[key] = data[key].map((child: Record<string, any>) => stripVolatile(child));
    } else {
      clean[key] = data[key];
    }
  }
  return clean;
}

/**
 * Hook to manage workflow dirty state (unsaved changes tracking)
 * Handles:
 * - Computing state hash for change detection
 * - F5/Ctrl+R interception to show custom modal
 * - beforeunload fallback for browser refresh button
 */
export function useDirtyState({
  nodes,
  edges,
  workflowLoaded,
  isRunMode,
  onDirtyChange,
  onRefreshBlocked,
}: UseDirtyStateOptions): UseDirtyStateReturn {
  const [isDirty, setIsDirty] = React.useState(false);
  const initialStateHashRef = React.useRef<string | null>(null);
  const settleCountRef = React.useRef(0);
  const isHandlingRefreshRef = React.useRef(false);

  // Compute a simple hash of workflow state for dirty tracking.
  // Volatile runtime properties (status, metrics, callbacks, etc.)
  // are stripped so only real user changes trigger dirty state.
  const computeStateHash = React.useCallback((nodesList: Node[], edgesList: Edge[]) => {
    const nodesData = nodesList.map(n => ({
      id: n.id,
      type: n.type,
      position: n.position,
      data: stripVolatile(n.data as Record<string, any>),
    }));
    const edgesData = edgesList.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle,
      targetHandle: e.targetHandle,
    }));
    return JSON.stringify({ nodes: nodesData, edges: edgesData });
  }, []);

  // Reset settle counter when workflowLoaded changes
  React.useEffect(() => {
    if (!workflowLoaded) {
      settleCountRef.current = 0;
      initialStateHashRef.current = null;
    }
  }, [workflowLoaded]);

  // Track dirty state after workflow is loaded.
  // We allow 2 render cycles for React effects to settle (e.g., useSelection
  // sets selected:false, React Flow measures dimensions) before locking
  // the baseline hash. During settling, the hash is continuously updated.
  React.useEffect(() => {
    if (!workflowLoaded) return;

    const currentHash = computeStateHash(nodes, edges);

    // Still settling - update baseline hash and wait.
    // For empty canvases (no nodes), lock immediately (no stabilization needed).
    if (settleCountRef.current < 2) {
      settleCountRef.current++;
      initialStateHashRef.current = currentHash;
      if (nodes.length === 0 && edges.length === 0) {
        settleCountRef.current = 2;
      }
      return;
    }

    // Baseline not yet stored (shouldn't happen after settling, but just in case)
    if (initialStateHashRef.current === null) {
      initialStateHashRef.current = currentHash;
      return;
    }

    // Compare current state with initial state
    const hasChanges = currentHash !== initialStateHashRef.current;

    if (hasChanges !== isDirty) {
      setIsDirty(hasChanges);
    }
  }, [workflowLoaded, nodes, edges, computeStateHash, isDirty]);

  // Notify parent when dirty state changes + broadcast via CustomEvent
  React.useEffect(() => {
    onDirtyChange?.(isDirty);
    window.dispatchEvent(new CustomEvent('workflowDirtyChange', {
      detail: { isDirty }
    }));
  }, [isDirty, onDirtyChange]);

  // Intercept F5/Ctrl+R to show modal instead of browser alert
  // Skip in run mode - no unsaved changes warning needed
  React.useEffect(() => {
    if (isRunMode) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // F5 or Ctrl+R or Cmd+R
      if (e.key === 'F5' || ((e.ctrlKey || e.metaKey) && e.key === 'r')) {
        if (isDirty) {
          e.preventDefault();
          e.stopPropagation();
          isHandlingRefreshRef.current = true;
          onRefreshBlocked?.();
          // Reset after a short delay
          setTimeout(() => {
            isHandlingRefreshRef.current = false;
          }, 100);
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown, true);
    return () => window.removeEventListener('keydown', handleKeyDown, true);
  }, [isDirty, onRefreshBlocked, isRunMode]);

  // Fallback: beforeunload for browser refresh button (can't show custom modal)
  // Skip in run mode - no unsaved changes warning needed
  React.useEffect(() => {
    if (isRunMode) return;

    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      // Skip if we're handling via our modal
      if (isHandlingRefreshRef.current) return;

      if (isDirty) {
        e.preventDefault();
        e.returnValue = '';
        return '';
      }
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isDirty, isRunMode]);

  // Reset dirty state (called after save or mode transitions)
  const resetDirtyState = React.useCallback((currentNodes: Node[], currentEdges: Edge[]) => {
    initialStateHashRef.current = computeStateHash(currentNodes, currentEdges);
    settleCountRef.current = 2; // Skip settling - we have the definitive baseline
    setIsDirty(false);
  }, [computeStateHash]);

  return {
    isDirty,
    resetDirtyState,
  };
}
