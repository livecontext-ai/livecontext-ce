import * as React from 'react';
import { Node, NodeChange } from 'reactflow';
import { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

interface UseSelectionProps {
  selectedNodeIds: string[];
  setSelectedNodeIds: React.Dispatch<React.SetStateAction<string[]>>;
  setNodes: React.Dispatch<React.SetStateAction<Node<BuilderNodeData>[]>>;
  onNodesChangeBase: (changes: NodeChange[]) => void;
  setIsAdvancedMode: (isAdvanced: boolean) => void;
  setSelectedLoopChild: (selection: { loopId: string; childId: string } | null) => void;
  isNodeCreatorOpen: boolean;
  nodes: Node<BuilderNodeData>[];
}

export function useSelection({
  selectedNodeIds,
  setSelectedNodeIds,
  setNodes,
  onNodesChangeBase,
  setIsAdvancedMode,
  setSelectedLoopChild,
  isNodeCreatorOpen,
  nodes
}: UseSelectionProps) {

  // Use ref for nodes to avoid recreating handleSelectionChange on every node change
  const nodesRef = React.useRef(nodes);
  nodesRef.current = nodes;

  const handleSelectionChange = React.useCallback((ids: string[]) => {
    setSelectedNodeIds(ids);

    if (ids.length === 0) {
      setSelectedLoopChild(null);
      setIsAdvancedMode(false);
    } else {
      const selectedNode = nodesRef.current.find(n => n.id === ids[0]);
      if (selectedNode && !nodeRegistry.isLoopNode(selectedNode)) {
        setSelectedLoopChild(null);
      }
    }
  }, [setSelectedLoopChild, setIsAdvancedMode, setSelectedNodeIds]);

  const onNodesChange = React.useCallback((changes: NodeChange[]) => {
    // Filter out changes that would create new node objects without actually changing
    // anything meaningful.  This breaks the infinite loop:
    //   usePreparedGraph creates new node refs → ReactFlow fires onNodesChange
    //   → applyNodeChanges creates new node state → usePreparedGraph recomputes → loop
    const currentNodes = nodesRef.current;
    const meaningful = changes.filter(change => {
      // Always drop select changes - selection is driven by selectedNodeIds
      if (change.type === 'select') return false;

      // Drop dimension changes that don't actually change dimensions.
      // ReactFlow fires these on every render when it receives new node objects,
      // even if the DOM dimensions haven't changed.
      if (change.type === 'dimensions') {
        const dc = change as NodeChange & { id: string; dimensions?: { width: number; height: number } };
        const node = currentNodes.find(n => n.id === dc.id);
        if (node && dc.dimensions) {
          const nodeAny = node as any;
          const prevW = nodeAny.measured?.width ?? nodeAny.width;
          const prevH = nodeAny.measured?.height ?? nodeAny.height;
          if (prevW === dc.dimensions.width && prevH === dc.dimensions.height) {
            return false; // Same dimensions, skip
          }
        }
      }

      return true;
    });

    if (meaningful.length > 0) {
      onNodesChangeBase(meaningful);
    }
  }, [onNodesChangeBase]);

  // Sync node.selected on raw nodes so ReactFlow's internal store reflects selection.
  // Safe because setSelectedNodeIds is content-stable (only updates on real changes).
  React.useEffect(() => {
    setNodes((nds) => {
      let changed = false;
      const updated = nds.map((node) => {
        const shouldBeSelected = selectedNodeIds.includes(node.id);
        if (node.selected !== shouldBeSelected) {
          changed = true;
          return { ...node, selected: shouldBeSelected };
        }
        return node;
      });
      return changed ? updated : nds;
    });
  }, [selectedNodeIds, setNodes]);

  // Reset selection when node creator opens on mobile
  React.useEffect(() => {
    const isMobileOrTablet = () => {
      if (typeof window === 'undefined') return false;
      return window.innerWidth < 1024;
    };

    if (isMobileOrTablet() && isNodeCreatorOpen) {
      setSelectedNodeIds([]);
      setIsAdvancedMode(false);
    }
  }, [isNodeCreatorOpen, setIsAdvancedMode, setSelectedNodeIds]);

  return {
    handleSelectionChange,
    onNodesChange,
  };
}
