import { useState, useEffect, useRef, useCallback } from 'react';

export function useMouseResize(
  setPanelWidth: (width: number) => void,
  options: {
    minWidth?: number;
    maxFraction?: number;
    onResizeEnd?: () => void;
    /**
     * Resize axis. 'x' (default) resizes WIDTH from the right edge of the viewport
     * (right-docked panel). 'y' resizes HEIGHT from the bottom edge (bottom-docked
     * panel). `minWidth`/`maxFraction` apply to the active axis' size in both cases.
     */
    axis?: 'x' | 'y';
  } = {}
) {
  const [isResizing, setIsResizing] = useState(false);
  const hasManuallyResizedRef = useRef(false);

  // Stash callbacks/options in a ref so the resize effect doesn't tear down
  // mid-drag if the parent passes inline objects/callbacks.
  const optsRef = useRef(options);
  optsRef.current = options;
  const setPanelWidthRef = useRef(setPanelWidth);
  setPanelWidthRef.current = setPanelWidth;

  useEffect(() => {
    if (!isResizing) return;

    const axis = optsRef.current.axis ?? 'x';
    const handleMouseMove = (e: MouseEvent) => {
      const min = optsRef.current.minWidth ?? 280;
      if (axis === 'y') {
        const screenHeight = window.innerHeight;
        const max = screenHeight * (optsRef.current.maxFraction ?? 0.6);
        setPanelWidthRef.current(Math.max(min, Math.min(max, screenHeight - e.clientY)));
        return;
      }
      const screenWidth = window.innerWidth;
      const max = screenWidth * (optsRef.current.maxFraction ?? 0.6);
      setPanelWidthRef.current(Math.max(min, Math.min(max, screenWidth - e.clientX)));
    };

    const stopResize = () => {
      setIsResizing(false);
      optsRef.current.onResizeEnd?.();
    };

    // Listen on window (not document) so we still receive events when the
    // cursor leaves the document area (e.g. crosses an iframe). Pair with
    // pointerup as a belt-and-braces, plus blur to abort if the user alt-tabs.
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', stopResize);
    window.addEventListener('pointerup', stopResize);
    window.addEventListener('blur', stopResize);

    // Disable text selection + force resize cursor on body during the drag
    const prevUserSelect = document.body.style.userSelect;
    const prevCursor = document.body.style.cursor;
    document.body.style.userSelect = 'none';
    document.body.style.cursor = axis === 'y' ? 'ns-resize' : 'ew-resize';

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', stopResize);
      window.removeEventListener('pointerup', stopResize);
      window.removeEventListener('blur', stopResize);
      document.body.style.userSelect = prevUserSelect;
      document.body.style.cursor = prevCursor;
    };
  }, [isResizing]);

  const startResize = useCallback(() => {
    hasManuallyResizedRef.current = true;
    setIsResizing(true);
  }, []);

  return { isResizing, startResize, hasManuallyResizedRef };
}
