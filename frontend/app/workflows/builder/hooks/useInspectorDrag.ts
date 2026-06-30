import * as React from 'react';
import type { XYPosition } from 'reactflow';

interface UseInspectorDragOptions {
  containerSize?: { width: number; height: number };
}

interface UseInspectorDragReturn {
  position: XYPosition;
  isDragging: boolean;
  handleDragStart: (e: React.MouseEvent) => void;
}

/**
 * Hook for managing inspector panel dragging.
 * Handles mouse events for dragging the inspector panel around the canvas.
 * Clamps position within the container bounds (responds to sidebar/panel resizes).
 */
export function useInspectorDrag(
  initialPosition: XYPosition = { x: 16, y: 16 },
  options: UseInspectorDragOptions = {}
): UseInspectorDragReturn {
  const { containerSize } = options;
  const [position, setPosition] = React.useState<XYPosition>(initialPosition);
  const [isDragging, setIsDragging] = React.useState(false);
  const dragStartRef = React.useRef<{ x: number; y: number; startX: number; startY: number } | null>(null);

  // Clamp position when container shrinks (e.g. right panel expanded, sidebar opened)
  React.useEffect(() => {
    if (!containerSize || containerSize.width === 0 || isDragging) return;
    setPosition(prev => {
      const maxX = Math.max(0, containerSize.width - 320);
      const maxY = Math.max(0, containerSize.height - 100);
      const clampedX = Math.max(0, Math.min(prev.x, maxX));
      const clampedY = Math.max(0, Math.min(prev.y, maxY));
      if (clampedX === prev.x && clampedY === prev.y) return prev;
      return { x: clampedX, y: clampedY };
    });
  }, [containerSize?.width, containerSize?.height, isDragging]);

  // Use primitive width/height instead of the containerSize object so the effect
  // doesn't re-attach listeners on every parent render that hands a new object.
  const containerWidth = containerSize?.width;
  const containerHeight = containerSize?.height;

  // Handle drag
  React.useEffect(() => {
    if (!isDragging) return;

    const handleMouseMove = (e: MouseEvent) => {
      if (dragStartRef.current) {
        e.preventDefault();
        const deltaX = e.clientX - dragStartRef.current.x;
        const deltaY = e.clientY - dragStartRef.current.y;

        // Use container bounds if available, fallback to window
        const maxX = containerWidth != null ? Math.max(0, containerWidth - 320) : window.innerWidth - 350;
        const maxY = containerHeight != null ? Math.max(0, containerHeight - 100) : window.innerHeight - 400;
        setPosition({
          x: Math.max(0, Math.min(maxX, dragStartRef.current.startX + deltaX)),
          y: Math.max(0, Math.min(maxY, dragStartRef.current.startY + deltaY)),
        });
      }
    };

    const handleMouseUp = (e: MouseEvent) => {
      e.preventDefault();
      setIsDragging(false);
      dragStartRef.current = null;
      document.body.style.userSelect = '';
      document.body.style.cursor = '';
    };

    document.addEventListener('mousemove', handleMouseMove, true);
    document.addEventListener('mouseup', handleMouseUp, true);

    return () => {
      document.removeEventListener('mousemove', handleMouseMove, true);
      document.removeEventListener('mouseup', handleMouseUp, true);
    };
  }, [isDragging, containerWidth, containerHeight]);

  const handleDragStart = React.useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();

    dragStartRef.current = {
      x: e.clientX,
      y: e.clientY,
      startX: position.x,
      startY: position.y,
    };

    setIsDragging(true);
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'grabbing';
  }, [position]);

  return {
    position,
    isDragging,
    handleDragStart,
  };
}
