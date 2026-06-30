import * as React from 'react';
import type { Node, OnNodesChange, ReactFlowInstance, XYPosition } from 'reactflow';
import type { BuilderNodeData } from '../types';

interface UseBoxSelectionProps {
  instance: ReactFlowInstance | null;
  nodes: Node<BuilderNodeData>[];
  onSelectionChange: (selectedIds: string[]) => void;
  onForceNodesUpdate?: (nodes: Node<BuilderNodeData>[]) => void;
  onNodesChange: OnNodesChange;
}

interface UseBoxSelectionResult {
  isBoxSelectionEnabled: boolean;
  isSelecting: boolean;
  selectionStart: XYPosition | null;
  selectionEnd: XYPosition | null;
  handleToggleBoxSelection: () => void;
  handleSelectionChange: (selection: { nodes?: Array<{ id: string }> }) => void;
  containerRef: React.RefObject<HTMLDivElement | null>;
  selectionJustEndedRef: React.RefObject<boolean>;
}

export function useBoxSelection({
  instance,
  nodes,
  onSelectionChange,
  onForceNodesUpdate,
  onNodesChange,
}: UseBoxSelectionProps): UseBoxSelectionResult {
  const [isBoxSelectionEnabled, setIsBoxSelectionEnabled] = React.useState(false);
  const [selectionStart, setSelectionStart] = React.useState<XYPosition | null>(null);
  const [selectionEnd, setSelectionEnd] = React.useState<XYPosition | null>(null);
  const [isSelecting, setIsSelecting] = React.useState(false);

  const selectionEndRef = React.useRef<XYPosition | null>(null);
  // Guard: prevents handlePaneClick from clearing selection right after box-select ends
  const selectionJustEndedRef = React.useRef(false);
  const nodesRef = React.useRef(nodes);
  const onForceNodesUpdateRef = React.useRef(onForceNodesUpdate);
  const onNodesChangeRef = React.useRef(onNodesChange);
  const onSelectionChangeRef = React.useRef(onSelectionChange);
  const containerRef = React.useRef<HTMLDivElement | null>(null);

  // Update refs when props change
  React.useEffect(() => {
    nodesRef.current = nodes;
  }, [nodes]);

  React.useEffect(() => {
    onForceNodesUpdateRef.current = onForceNodesUpdate;
  }, [onForceNodesUpdate]);

  React.useEffect(() => {
    onNodesChangeRef.current = onNodesChange;
  }, [onNodesChange]);

  React.useEffect(() => {
    onSelectionChangeRef.current = onSelectionChange;
  }, [onSelectionChange]);

  React.useEffect(() => {
    selectionEndRef.current = selectionEnd;
  }, [selectionEnd]);

  const handleToggleBoxSelection = React.useCallback(() => {
    setIsBoxSelectionEnabled((prev) => !prev);
    if (isBoxSelectionEnabled) {
      setSelectionStart(null);
      setSelectionEnd(null);
      setIsSelecting(false);
    }
  }, [isBoxSelectionEnabled]);

  const handleSelectionMove = React.useCallback(
    (event: MouseEvent) => {
      if (!isSelecting || !instance || !selectionStart) return;

      event.preventDefault();
      event.stopPropagation();
      const position = instance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });
      setSelectionEnd(position);
    },
    [isSelecting, instance, selectionStart],
  );

  const handleSelectionEnd = React.useCallback(() => {
    const currentSelectionEnd = selectionEndRef.current;
    if (!isSelecting || !instance || !selectionStart || !currentSelectionEnd) {
      setIsSelecting(false);
      setSelectionStart(null);
      setSelectionEnd(null);
      selectionEndRef.current = null;
      return;
    }

    const minX = Math.min(selectionStart.x, currentSelectionEnd.x);
    const maxX = Math.max(selectionStart.x, currentSelectionEnd.x);
    const minY = Math.min(selectionStart.y, currentSelectionEnd.y);
    const maxY = Math.max(selectionStart.y, currentSelectionEnd.y);

    const selectedNodeIds: string[] = [];
    const allNodes = nodesRef.current;
    const instanceNodes = instance.getNodes();
    const instanceNodesMap = new Map(instanceNodes.map(n => [n.id, n]));

    allNodes.forEach((node) => {
      const instanceNode = instanceNodesMap.get(node.id);
      const nodeX = instanceNode?.positionAbsolute?.x ?? node.positionAbsolute?.x ?? node.position.x;
      const nodeY = instanceNode?.positionAbsolute?.y ?? node.positionAbsolute?.y ?? node.position.y;

      const nodeAny = node as unknown as { measured?: { width?: number; height?: number }; width?: number; height?: number };
      const nodeWidth = nodeAny.measured?.width ?? nodeAny.width ?? (typeof node.style?.width === 'number' ? node.style.width : 150);
      const nodeHeight = nodeAny.measured?.height ?? nodeAny.height ?? (typeof node.style?.minHeight === 'number' ? node.style.minHeight : 40);

      const nodeLeft = nodeX;
      const nodeRight = nodeX + nodeWidth;
      const nodeTop = nodeY;
      const nodeBottom = nodeY + nodeHeight;

      const isIntersecting = !(
        nodeRight < minX ||
        nodeLeft > maxX ||
        nodeBottom < minY ||
        nodeTop > maxY
      );

      if (isIntersecting) {
        selectedNodeIds.push(node.id);
      }
    });

    // Only update selectedNodeIds - usePreparedGraph handles the visual
    // `selected` prop on nodes.  Do NOT call setNodes/onNodesChange with
    // select changes here; that creates new node objects and triggers an
    // infinite render loop (nodes change → usePreparedGraph → ReactFlow → …).
    onSelectionChangeRef.current(selectedNodeIds);

    // Prevent the subsequent click event (mouseup → click) from clearing selection
    // via handlePaneClick. The click fires ~0ms after mouseup on the same position.
    selectionJustEndedRef.current = true;
    setTimeout(() => { selectionJustEndedRef.current = false; }, 200);

    setIsSelecting(false);
    setSelectionStart(null);
    setSelectionEnd(null);
    selectionEndRef.current = null;
  }, [isSelecting, instance, selectionStart]);

  // Event listeners for selection
  React.useEffect(() => {
    if (isSelecting) {
      window.addEventListener('mousemove', handleSelectionMove);
      window.addEventListener('mouseup', handleSelectionEnd);
      return () => {
        window.removeEventListener('mousemove', handleSelectionMove);
        window.removeEventListener('mouseup', handleSelectionEnd);
      };
    }
  }, [isSelecting, handleSelectionMove, handleSelectionEnd]);

  // MouseDown handler on container for selection
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container || !isBoxSelectionEnabled) return;

    const handleMouseDown = (event: MouseEvent) => {
      if (!instance) return;
      const target = event.target as HTMLElement;
      if (target.closest('.react-flow__node')) return;
      if (target.closest('button') || target.closest('[role="button"]')) return;
      if (target.closest('[data-node-creator-panel]')) return;
      if (target.closest('[data-inspector-panel]')) return;

      event.preventDefault();
      event.stopPropagation();

      const position = instance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });
      setSelectionStart(position);
      setSelectionEnd(position);
      setIsSelecting(true);
    };

    container.addEventListener('mousedown', handleMouseDown);
    return () => {
      container.removeEventListener('mousedown', handleMouseDown);
    };
  }, [isBoxSelectionEnabled, instance]);

  const handleSelectionChange = React.useCallback(
    (selection: { nodes?: Array<{ id: string }> }) => {
      if (isSelecting) {
        const ids = selection.nodes?.map((node) => node.id) ?? [];
        onSelectionChange(ids);
      }
    },
    [onSelectionChange, isSelecting],
  );

  return {
    isBoxSelectionEnabled,
    isSelecting,
    selectionStart,
    selectionEnd,
    handleToggleBoxSelection,
    handleSelectionChange,
    containerRef,
    selectionJustEndedRef,
  };
}
