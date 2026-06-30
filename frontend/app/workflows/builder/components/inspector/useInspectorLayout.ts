import { useState, useEffect, useRef, useCallback } from 'react';

interface UseInspectorLayoutProps {
  isAdvanced: boolean;
  isFullscreen?: boolean;
}

interface ColumnState {
  inputCollapsed: boolean;
  setInputCollapsed: (collapsed: boolean) => void;
  outputCollapsed: boolean;
  setOutputCollapsed: (collapsed: boolean) => void;
  inputWidth: number;
  setInputWidth: (width: number) => void;
  outputWidth: number;
  setOutputWidth: (width: number) => void;
}

interface ResizeHandlers {
  handleInputResizeStart: (e: React.MouseEvent) => void;
  handleOutputResizeStart: (e: React.MouseEvent) => void;
  isResizingInput: boolean;
  isResizingOutput: boolean;
}

interface UseInspectorLayoutReturn {
  // Column state
  columns: ColumnState;
  // Resize handlers
  resize: ResizeHandlers;
  // Mobile detection
  isMobile: boolean;
  // Active tab for mobile/advanced view
  activeTab: string;
  setActiveTab: (tab: string) => void;
}

/**
 * Hook to manage inspector panel layout:
 * - Column collapse/expand state
 * - Column width resizing
 * - Mobile detection
 * - Active tab state
 */
export function useInspectorLayout({ isAdvanced, isFullscreen = false }: UseInspectorLayoutProps): UseInspectorLayoutReturn {
  // Column collapse state
  const [inputCollapsed, setInputCollapsed] = useState(true);
  const [outputCollapsed, setOutputCollapsed] = useState(true);

  // Column widths
  const [inputWidth, setInputWidth] = useState(280);
  const [outputWidth, setOutputWidth] = useState(280);

  // Resize state - using React state so the effect mounts/unmounts listeners properly
  const [isResizingInput, setIsResizingInput] = useState(false);
  const [isResizingOutput, setIsResizingOutput] = useState(false);
  const startX = useRef(0);
  const startWidth = useRef(0);

  // Active tab for mobile/advanced view
  // Default to 'parameter' - in non-advanced mode only the parameter tab exists
  const [activeTab, setActiveTab] = useState('parameter');

  // Mobile detection
  const [isMobile, setIsMobile] = useState(() => {
    if (typeof window === 'undefined') return false;
    // Must match Tailwind lg: breakpoint (1024px) used by InspectorPanel container
    return window.innerWidth < 1024;
  });

  // Calculate max width based on fullscreen mode
  const getMaxWidth = useCallback(() => {
    if (isFullscreen && typeof window !== 'undefined') {
      return Math.floor(window.innerWidth * 0.9);
    }
    return 500;
  }, [isFullscreen]);

  // Expand columns when switching to advanced mode
  useEffect(() => {
    if (isAdvanced) {
      setInputCollapsed(false);
      setOutputCollapsed(false);
    } else {
      // Reset to parameter tab when leaving advanced (input/output tabs don't exist)
      setActiveTab('parameter');
    }
  }, [isAdvanced]);

  // Set equal column widths and expand columns when entering fullscreen mode, reset when exiting
  useEffect(() => {
    if (isFullscreen && typeof window !== 'undefined') {
      setInputCollapsed(false);
      setOutputCollapsed(false);
      const availableWidth = window.innerWidth - 20;
      const equalWidth = Math.floor(availableWidth / 3);
      setInputWidth(equalWidth);
      setOutputWidth(equalWidth);
    } else if (!isFullscreen) {
      setInputWidth(280);
      setOutputWidth(280);
    }
  }, [isFullscreen]);

  // Mobile detection - threshold must match Tailwind lg: breakpoint (1024px)
  useEffect(() => {
    const checkSize = () => {
      setIsMobile(window.innerWidth < 1024);
    };
    checkSize();
    window.addEventListener('resize', checkSize);
    return () => window.removeEventListener('resize', checkSize);
  }, []);

  // Resize start handlers
  const handleInputResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    startX.current = e.clientX;
    startWidth.current = inputWidth;
    setIsResizingInput(true);
  }, [inputWidth]);

  const handleOutputResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    startX.current = e.clientX;
    startWidth.current = outputWidth;
    setIsResizingOutput(true);
  }, [outputWidth]);

  // Input column resize effect - listens on window so iframes / ReactFlow
  // canvases inside the panel cannot swallow mousemove/mouseup.
  useEffect(() => {
    if (!isResizingInput) return;

    const prevCursor = document.body.style.cursor;
    const prevUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'ew-resize';
    document.body.style.userSelect = 'none';
    const maxWidth = getMaxWidth();

    const onMove = (e: MouseEvent) => {
      const delta = e.clientX - startX.current;
      setInputWidth(Math.max(200, Math.min(maxWidth, startWidth.current + delta)));
    };

    const onUp = () => setIsResizingInput(false);

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('blur', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('blur', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevUserSelect;
    };
  }, [isResizingInput, getMaxWidth]);

  // Output column resize effect - same hardening as input.
  useEffect(() => {
    if (!isResizingOutput) return;

    const prevCursor = document.body.style.cursor;
    const prevUserSelect = document.body.style.userSelect;
    document.body.style.cursor = 'ew-resize';
    document.body.style.userSelect = 'none';
    const maxWidth = getMaxWidth();

    const onMove = (e: MouseEvent) => {
      const delta = startX.current - e.clientX;
      setOutputWidth(Math.max(200, Math.min(maxWidth, startWidth.current + delta)));
    };

    const onUp = () => setIsResizingOutput(false);

    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('blur', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('blur', onUp);
      document.body.style.cursor = prevCursor;
      document.body.style.userSelect = prevUserSelect;
    };
  }, [isResizingOutput, getMaxWidth]);

  return {
    columns: {
      inputCollapsed,
      setInputCollapsed,
      outputCollapsed,
      setOutputCollapsed,
      inputWidth,
      setInputWidth,
      outputWidth,
      setOutputWidth,
    },
    resize: {
      handleInputResizeStart,
      handleOutputResizeStart,
      isResizingInput,
      isResizingOutput,
    },
    isMobile,
    activeTab,
    setActiveTab,
  };
}
