import * as React from 'react';
import { Node, Edge } from 'reactflow';
import { BuilderNodeData } from '../types';
import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';
import { computeGraphSignature } from './graphSignature';

interface HistoryEntry {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  /** The reading direction the entry's positions were placed in. */
  layoutDirection?: WorkflowLayoutDirection;
  /** Signature of the entry, so "did this change?" never re-derives it. */
  signature: string;
}

function snapshot(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  layoutDirection?: WorkflowLayoutDirection,
): HistoryEntry {
  return {
    nodes: JSON.parse(JSON.stringify(nodes)),
    edges: JSON.parse(JSON.stringify(edges)),
    layoutDirection,
    signature: computeGraphSignature(nodes, edges, layoutDirection),
  };
}

/**
 * The canvas's reading direction, when the caller tracks it. A direction change re-lays
 * every node out, so it is one undo step: undoing it must put the direction back WITH the
 * positions, or the canvas would draw the old positions with the new handles.
 */
export interface HistoryLayoutDirection {
  value: WorkflowLayoutDirection;
  set: (direction: WorkflowLayoutDirection) => void;
}

/**
 * Undo/redo stack for the builder canvas.
 *
 * Two rules keep "undoable" aligned with "the user changed something":
 *
 *  1. **The baseline is the graph as loaded, not as mounted.** `WorkflowBuilder`
 *     mounts with an empty graph (`INITIAL_NODES`) and `useWorkflowLoader` applies
 *     the real one afterwards. Seeding the stack at mount therefore recorded the
 *     load itself as an edit: Undo lit up on a workflow nobody had touched, and
 *     pressing it restored the EMPTY canvas (which a later save would persist).
 *     The baseline is re-seeded when `workflowLoaded` turns true.
 *  2. **An edit is a change of the saved graph, not of the React Flow objects.**
 *     React Flow writes measured dimensions back into the nodes after mount and
 *     `useSelection` toggles `selected`; a raw `JSON.stringify` comparison counted
 *     those as edits. Comparison goes through `computeGraphSignature`, the same
 *     signature `useDirtyState` uses to arm Save, so Undo and Save cannot disagree.
 */
export function useHistory(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  setNodes: (nodes: Node<BuilderNodeData>[]) => void,
  setEdges: (edges: Edge[]) => void,
  workflowLoaded: boolean = true,
  layoutDirection?: HistoryLayoutDirection,
) {
  const directionValue = layoutDirection?.value;
  const setDirectionRef = React.useRef(layoutDirection?.set);
  setDirectionRef.current = layoutDirection?.set;
  const [history, setHistory] = React.useState<HistoryEntry[]>(() => [snapshot(nodes, edges, directionValue)]);
  const [historyIndex, setHistoryIndex] = React.useState(0);
  const isUndoRedoRef = React.useRef(false);
  const historyIndexRef = React.useRef(0);

  // Keep track of the latest nodes/edges in a ref for the timeout callback
  const nodesEdgesRef = React.useRef({ nodes, edges, layoutDirection: directionValue });

  React.useEffect(() => {
    nodesEdgesRef.current = { nodes, edges, layoutDirection: directionValue };
  }, [nodes, edges, directionValue]);

  // Sync ref with state
  React.useEffect(() => {
    historyIndexRef.current = historyIndex;
  }, [historyIndex]);

  // Re-seed the baseline once the workflow is on the canvas. Declared after the
  // nodesEdgesRef effect on purpose: effects run in declaration order within a
  // commit, so the ref already holds the freshly loaded graph here (the loader
  // calls setNodes/setEdges and setWorkflowLoaded(true) in the same batch).
  React.useEffect(() => {
    if (!workflowLoaded) return;
    const { nodes: loadedNodes, edges: loadedEdges, layoutDirection: loadedDirection } = nodesEdgesRef.current;
    setHistory([snapshot(loadedNodes, loadedEdges, loadedDirection)]);
    setHistoryIndex(0);
    historyIndexRef.current = 0;
  }, [workflowLoaded]);

  // Debounced save mechanism
  React.useEffect(() => {
    if (!workflowLoaded) {
      return;
    }
    if (isUndoRedoRef.current) {
      return;
    }

    const timeoutId = setTimeout(() => {
      const currentState = nodesEdgesRef.current;
      const currentSignature = computeGraphSignature(
        currentState.nodes, currentState.edges, currentState.layoutDirection,
      );

      setHistory((prevHistory) => {
        const currentIndex = historyIndexRef.current;
        const currentHistoryState = prevHistory[currentIndex];

        // Safety check
        if (!currentHistoryState) return prevHistory;

        if (currentSignature !== currentHistoryState.signature) {
          const newHistory = prevHistory.slice(0, currentIndex + 1);
          const newState = snapshot(currentState.nodes, currentState.edges, currentState.layoutDirection);
          // Limit history to 50 states
          const updatedHistory = [...newHistory, newState].slice(-50);
          setHistoryIndex(updatedHistory.length - 1);
          return updatedHistory;
        }
        return prevHistory;
      });
    }, 300);

    return () => clearTimeout(timeoutId);
  }, [nodes, edges, directionValue, workflowLoaded]); // Run when nodes, edges or the direction change

  const undo = React.useCallback(
    (onUndoStart?: () => void) => {
      if (historyIndex > 0) {
        isUndoRedoRef.current = true;
        const prevState = history[historyIndex - 1];
        setNodes(JSON.parse(JSON.stringify(prevState.nodes)));
        setEdges(JSON.parse(JSON.stringify(prevState.edges)));
        if (prevState.layoutDirection) setDirectionRef.current?.(prevState.layoutDirection);
        setHistoryIndex(historyIndex - 1);
        // Only call if it's actually a function (not an event object from onClick)
        if (typeof onUndoStart === 'function') onUndoStart();
        setTimeout(() => {
          isUndoRedoRef.current = false;
        }, 100);
      }
    },
    [history, historyIndex, setNodes, setEdges]
  );

  const redo = React.useCallback(
    (onRedoStart?: () => void) => {
      if (historyIndex < history.length - 1) {
        isUndoRedoRef.current = true;
        const nextState = history[historyIndex + 1];
        setNodes(JSON.parse(JSON.stringify(nextState.nodes)));
        setEdges(JSON.parse(JSON.stringify(nextState.edges)));
        if (nextState.layoutDirection) setDirectionRef.current?.(nextState.layoutDirection);
        setHistoryIndex(historyIndex + 1);
        // Only call if it's actually a function (not an event object from onClick)
        if (typeof onRedoStart === 'function') onRedoStart();
        setTimeout(() => {
          isUndoRedoRef.current = false;
        }, 100);
      }
    },
    [history, historyIndex, setNodes, setEdges]
  );

  return {
    undo,
    redo,
    canUndo: historyIndex > 0,
    canRedo: historyIndex < history.length - 1,
  };
}
