'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';

import { computeGraphSignature } from './graphSignature';

interface UseDirtyStateOptions {
  nodes: Node[];
  edges: Edge[];
  /**
   * Names the workflow on the broadcast `workflowDirtyChange` event. Several
   * canvases can be mounted at once (the right side panel embeds its own), and
   * without it every listener adopted whichever canvas edited last.
   */
  workflowId?: string;
  workflowLoaded: boolean;
  isRunMode: boolean;
  onDirtyChange?: (isDirty: boolean) => void;
  onRefreshBlocked?: () => void;
  /** The canvas's reading direction: changing it is an edit (see computeGraphSignature). */
  layoutDirection?: string;
}

interface UseDirtyStateReturn {
  isDirty: boolean;
  resetDirtyState: (nodes: Node[], edges: Edge[]) => void;
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
  workflowId,
  workflowLoaded,
  isRunMode,
  onDirtyChange,
  onRefreshBlocked,
  layoutDirection,
}: UseDirtyStateOptions): UseDirtyStateReturn {
  const [isDirty, setIsDirty] = React.useState(false);
  const initialStateHashRef = React.useRef<string | null>(null);
  const settleCountRef = React.useRef(0);
  const isHandlingRefreshRef = React.useRef(false);

  // Volatile runtime properties (status, metrics, callbacks, measured dimensions,
  // selection) are stripped so only real user changes trigger dirty state. The
  // signature lives in ./graphSignature because useHistory needs the same answer
  // to "is this a user edit?" - see the note there.
  // Through a ref so `resetDirtyState` keeps its identity (callers hold it in effects),
  // while still hashing the direction of the moment it is called: right after a Save.
  const layoutDirectionRef = React.useRef(layoutDirection);
  layoutDirectionRef.current = layoutDirection;
  const computeStateHash = React.useCallback(
    (nodesList: Node[], edgesList: Edge[]) =>
      computeGraphSignature(nodesList, edgesList, layoutDirectionRef.current),
    []
  );

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
  }, [workflowLoaded, nodes, edges, layoutDirection, computeStateHash, isDirty]);

  // Notify parent when dirty state changes + broadcast via CustomEvent
  React.useEffect(() => {
    onDirtyChange?.(isDirty);
    window.dispatchEvent(new CustomEvent('workflowDirtyChange', {
      detail: { isDirty, workflowId }
    }));
  }, [isDirty, onDirtyChange, workflowId]);

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
