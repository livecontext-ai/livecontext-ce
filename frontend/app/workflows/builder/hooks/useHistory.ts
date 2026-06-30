import * as React from 'react';
import { Node, Edge } from 'reactflow';
import { BuilderNodeData } from '../types';

export function useHistory(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  setNodes: (nodes: Node<BuilderNodeData>[]) => void,
  setEdges: (edges: Edge[]) => void
) {
  const [history, setHistory] = React.useState<
    Array<{ nodes: Node<BuilderNodeData>[]; edges: Edge[] }>
  >([{ nodes, edges }]);
  const [historyIndex, setHistoryIndex] = React.useState(0);
  const isUndoRedoRef = React.useRef(false);
  const historyIndexRef = React.useRef(0);
  
  // Keep track of the latest nodes/edges in a ref for the timeout callback
  const nodesEdgesRef = React.useRef({ nodes, edges });

  React.useEffect(() => {
    nodesEdgesRef.current = { nodes, edges };
  }, [nodes, edges]);

  // Sync ref with state
  React.useEffect(() => {
    historyIndexRef.current = historyIndex;
  }, [historyIndex]);

  // Debounced save mechanism
  React.useEffect(() => {
    if (isUndoRedoRef.current) {
      return;
    }

    const timeoutId = setTimeout(() => {
      const currentState = nodesEdgesRef.current;
      
      setHistory((prevHistory) => {
        const currentIndex = historyIndexRef.current;
        const currentHistoryState = prevHistory[currentIndex];
        
        // Safety check
        if (!currentHistoryState) return prevHistory;

        const nodesChanged =
          JSON.stringify(currentState.nodes) !==
          JSON.stringify(currentHistoryState.nodes);
        const edgesChanged =
          JSON.stringify(currentState.edges) !==
          JSON.stringify(currentHistoryState.edges);

        if (nodesChanged || edgesChanged) {
          const newHistory = prevHistory.slice(0, currentIndex + 1);
          const newState = {
            nodes: JSON.parse(JSON.stringify(currentState.nodes)),
            edges: JSON.parse(JSON.stringify(currentState.edges)),
          };
          // Limit history to 50 states
          const updatedHistory = [...newHistory, newState].slice(-50);
          setHistoryIndex(updatedHistory.length - 1);
          return updatedHistory;
        }
        return prevHistory;
      });
    }, 300);

    return () => clearTimeout(timeoutId);
  }, [nodes, edges]); // Run when nodes or edges change

  const undo = React.useCallback(
    (onUndoStart?: () => void) => {
      if (historyIndex > 0) {
        isUndoRedoRef.current = true;
        const prevState = history[historyIndex - 1];
        setNodes(JSON.parse(JSON.stringify(prevState.nodes)));
        setEdges(JSON.parse(JSON.stringify(prevState.edges)));
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
