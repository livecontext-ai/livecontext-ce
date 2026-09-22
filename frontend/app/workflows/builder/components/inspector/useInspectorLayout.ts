import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * Window width below which the inspector uses its tabbed layout, whatever the
 * panel measures. Must match the Tailwind `lg:` breakpoint the panel's own
 * container classes use.
 */
export const INSPECTOR_MOBILE_WINDOW_WIDTH = 1024;

/** Minimum the parameters column keeps for itself (`min-w-[200px]`). */
export const INSPECTOR_MIN_PARAMS_WIDTH = 200;

/** A collapsed side column is a 32px rail button, not its stored width. */
export const INSPECTOR_COLLAPSED_COLUMN_WIDTH = 32;

/** Each expanded side column is followed by a `w-4` resize handle. */
export const INSPECTOR_RESIZE_HANDLE_WIDTH = 16;

/**
 * Extra width the panel must gain before going BACK to columns. The two layouts
 * have different intrinsic widths, so a single threshold flip-flops on a panel
 * parked right at the boundary.
 */
export const INSPECTOR_WIDE_PANEL_MARGIN = 48;

interface ColumnWidths {
  inputCollapsed: boolean;
  inputWidth: number;
  outputCollapsed: boolean;
  outputWidth: number;
}

/**
 * How much width the three-column layout needs RIGHT NOW.
 *
 * Not a constant: both side columns are user-draggable (200-500px) and either
 * can be collapsed to a rail, so a fixed threshold is wrong in both directions -
 * it lets a panel with two 500px columns overflow unflagged, and pushes a narrow
 * panel with both columns collapsed into tabs it does not need.
 */
export function requiredColumnsWidth({
  inputCollapsed,
  inputWidth,
  outputCollapsed,
  outputWidth,
}: ColumnWidths): number {
  const input = inputCollapsed
    ? INSPECTOR_COLLAPSED_COLUMN_WIDTH
    : inputWidth + INSPECTOR_RESIZE_HANDLE_WIDTH;
  const output = outputCollapsed
    ? INSPECTOR_COLLAPSED_COLUMN_WIDTH
    : outputWidth + INSPECTOR_RESIZE_HANDLE_WIDTH;
  return input + INSPECTOR_MIN_PARAMS_WIDTH + output;
}

/**
 * Whether the inspector renders its tabbed layout instead of its columns.
 *
 * A narrow PANEL only matters to a layout that has side columns to lose: the
 * single-column inspector (300px, floating) reads perfectly well, and putting IT
 * in tabs would be a regression. The caller owns the effective `isAdvanced`,
 * which is why the two signals are combined here rather than inside the hook.
 */
export function shouldUseTabbedLayout({
  isWindowMobile,
  isNarrowPanel,
  isAdvanced,
  isFullscreen,
}: {
  isWindowMobile: boolean;
  isNarrowPanel: boolean;
  isAdvanced: boolean;
  isFullscreen: boolean;
}): boolean {
  return isWindowMobile || ((isAdvanced || isFullscreen) && isNarrowPanel);
}

/**
 * Whether the minimized inspector renders as the compact pill.
 *
 * Gated on the WINDOW, never on `shouldUseTabbedLayout`. The tabbed flag folds
 * in "the PANEL measured narrow", and an advanced-mode user whose panel measured
 * narrow then got the full panel back when they clicked minimize: minimize was a
 * no-op for them. Whether a panel is narrow has no bearing on whether the user
 * asked for it out of the way.
 */
export function shouldRenderMinimizedPill({
  isMinimized,
  isWindowMobile,
  isDocked,
}: {
  isMinimized: boolean;
  isWindowMobile: boolean;
  isDocked: boolean;
}): boolean {
  return isMinimized && !isWindowMobile && !isDocked;
}

/**
 * Whether the floating panel is capped to its container's measured size.
 *
 * Also gated on the WINDOW rather than the panel-derived flag, and here the
 * reason is circularity: this style IS what constrains the panel, so keying it
 * on "the panel is narrow" would remove the cause of its own condition.
 */
export function shouldConstrainPanelToContainer({
  isFullscreen,
  isDocked,
  isWindowMobile,
}: {
  isFullscreen: boolean;
  isDocked: boolean;
  isWindowMobile: boolean;
}): boolean {
  return !isFullscreen && !isDocked && !isWindowMobile;
}

/**
 * Whether the inspector is pinned to its single-column, 300px panel.
 *
 * It means ONE thing: the node still has to be pointed at something before it
 * has parameters to show, and the picker that does the pointing is a
 * single-column screen. Exactly one such picker is left in the inspector,
 * `McpToolSelector` in the Params column, which an MCP node uses to choose its
 * API and then its tool. A tool node has already chosen, so it is never pinned.
 *
 * It used to mean the same thing for triggers, AI nodes and core nodes, back
 * when `InspectorTriggerNode` / `InspectorAiNode` / `InspectorCoreNode` rendered
 * their own pickers. Those components are gone (the pickers moved to the Add
 * Node panel and the empty-canvas chat), so for those families the gate had no
 * picker left to protect and only took things away: the Input and Output
 * columns, the Expand button, and - the expensive one - the Edit / Run data
 * switcher, which is how a reader reaches what a step actually ran with.
 *
 * It also matched on `data.id` PREFIXES, which do not survive a plan round-trip:
 * a node is re-imported with `data.id` set to its graph node id, so a form
 * trigger saved as `trigger-new-clip` and the transform shipped by the
 * onboarding template as `core-1` both stopped looking like themselves and
 * started looking like "a type has not been chosen yet". On production plans
 * that was 466 of 479 triggers. Nothing here reads an id any more.
 */
export function shouldForceCompactPanel({
  isApiNode,
  isMcpGenericNode,
  isToolNode,
}: {
  isApiNode: boolean;
  isMcpGenericNode: boolean;
  isToolNode: boolean;
}): boolean {
  return (isApiNode || isMcpGenericNode) && !isToolNode;
}

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
  /** The WINDOW is too narrow for the columns layout (Tailwind lg: breakpoint). */
  isMobile: boolean;
  /**
   * The PANEL is too narrow to host the three-column layout. Left separate from
   * {@link isMobile} because only a layout that actually renders the side
   * columns should act on it: the 300px single-column inspector reads perfectly
   * well, and switching IT to tabs would be a regression. The caller, which is
   * the only place that knows the EFFECTIVE advanced flag, combines the two.
   */
  isNarrowPanel: boolean;
  /**
   * Callback ref for the panel element. Attaching it is what lets the hook
   * measure the space the inspector actually has, rather than assuming the
   * window's width is it.
   */
  measurePanel: (element: HTMLElement | null) => void;
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
export function useInspectorLayout({
  isAdvanced,
  isFullscreen = false,
}: UseInspectorLayoutProps): UseInspectorLayoutReturn {
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

  // Mobile detection - the window half
  const [isWindowMobile, setIsWindowMobile] = useState(() => {
    if (typeof window === 'undefined') return false;
    // Must match Tailwind lg: breakpoint (1024px) used by InspectorPanel container
    return window.innerWidth < INSPECTOR_MOBILE_WINDOW_WIDTH;
  });

  // Mobile detection - the panel half. A wide window says nothing about the
  // space the inspector was actually given: docked into the side panel it is
  // only as wide as the user dragged that panel.
  const [isNarrowPanel, setIsNarrowPanel] = useState(false);

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
      setIsWindowMobile(window.innerWidth < INSPECTOR_MOBILE_WINDOW_WIDTH);
    };
    checkSize();
    window.addEventListener('resize', checkSize);
    return () => window.removeEventListener('resize', checkSize);
  }, []);

  // Read through a ref, not a closure capture: the observer callback is created
  // once (a stable callback ref, so it does not churn), while the column widths
  // and collapse state change under it. The ref is seeded on the first render
  // and updated from an effect - writing it DURING render is what makes a
  // component miss its own update.
  const requiredWidth = requiredColumnsWidth({
    inputCollapsed,
    inputWidth,
    outputCollapsed,
    outputWidth,
  });
  const requiredWidthRef = useRef(requiredWidth);
  const lastMeasuredWidthRef = useRef(0);

  const applyMeasuredWidth = useCallback((width: number) => {
    if (width <= 0) return; // hidden / not laid out yet - no opinion
    lastMeasuredWidthRef.current = width;
    const required = requiredWidthRef.current;
    setIsNarrowPanel((wasNarrow) =>
      wasNarrow ? width < required + INSPECTOR_WIDE_PANEL_MARGIN : width < required,
    );
  }, []);

  // Measure the panel itself. A callback ref rather than an effect on a
  // RefObject: the panel unmounts and remounts as the selection changes, and a
  // mount-once effect would keep observing a detached element (or nothing at
  // all, when the first render had no node to show).
  const observerRef = useRef<ResizeObserver | null>(null);
  const measurePanel = useCallback((element: HTMLElement | null) => {
    observerRef.current?.disconnect();
    observerRef.current = null;
    if (!element || typeof ResizeObserver === 'undefined') return;

    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry) applyMeasuredWidth(entry.contentRect.width);
    });
    observer.observe(element);
    observerRef.current = observer;
    applyMeasuredWidth(element.getBoundingClientRect().width);
  }, [applyMeasuredWidth]);

  // Dragging a column wider can overflow a panel that never changed size, so the
  // verdict is re-evaluated on the requirement too, not only on a resize event.
  useEffect(() => {
    requiredWidthRef.current = requiredWidth;
    if (lastMeasuredWidthRef.current > 0) applyMeasuredWidth(lastMeasuredWidthRef.current);
  }, [applyMeasuredWidth, requiredWidth]);

  useEffect(() => {
    return () => {
      observerRef.current?.disconnect();
      observerRef.current = null;
    };
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
    isMobile: isWindowMobile,
    isNarrowPanel,
    measurePanel,
    activeTab,
    setActiveTab,
  };
}
