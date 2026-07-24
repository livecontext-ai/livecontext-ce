'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { usePathname } from 'next/navigation';
import { createPortal } from 'react-dom';
import ReactFlow, {
  Background,
  BackgroundVariant,
  Connection,
  ConnectionLineComponentProps,
  ConnectionMode,
  Edge,
  getBezierPath,
  getSmoothStepPath,
  Node,
  NodeDragHandler,
  NodeMouseHandler,
  OnConnectStartParams,
  OnEdgesChange,
  OnNodesChange,
  Panel,
  ReactFlowInstance,
  ReactFlowProvider,
  XYPosition,
} from 'reactflow';
import 'reactflow/dist/style.css';

import { Plus, Info, AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { useValidationOptional } from '../contexts/ValidationContext';
import { useTheme } from '@/components/ThemeProvider';
import { EdgeActionsProvider } from './EdgeActionsContext';
import { nodeTypes, edgeTypes } from '../constants/graphTypes';
import type { BuilderNodeData, PaletteDragItem } from '../types';
import type { ConnectionType } from './ConnectionTypeSelector';
import { generateWorkflowPlan } from '../utils/workflowPlanGenerator';
import { resolveInsertedTargetHandle } from '../utils/hoverConnectHandles';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { isFlowBackward } from './nodes/handleGeometry';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { isEmbeddedWorkflowCanvas } from '@/lib/workflow/canvasEmbedding';
import { useSvgSafeId } from '@/hooks/useSvgSafeId';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useCanvasViewport } from '../hooks/useCanvasViewport';
import { useInspectorDrag } from '../hooks/useInspectorDrag';
import { HoverEdgeManager } from './HoverEdgeManager';
import { DirectionHandleSync } from './DirectionHandleSync';
import { SimpleToast, useSimpleToast } from '@/components/chat/SimpleToast';
import { applyDagreLayout, layoutConfigForDirection } from '../services/LayoutService';

// New extracted modules
import { getDisplayedSuggestions, type WorkflowSuggestion } from '../constants/workflowSuggestions';
import { useTypingSuggestion } from '../hooks/useTypingSuggestion';
import { useBoxSelection } from '../hooks/useBoxSelection';
import { validateConnection, isWiredOutputPort } from '../utils/connectionValidator';
import { EmptyCanvasChat } from './EmptyCanvasChat';
import { CanvasToolbar } from './CanvasToolbar';
import { CanvasSettingsPanel } from './CanvasSettingsPanel';
import { nodeMatchesStep } from '../services/nodeMatcher';
import { nodeRegistry } from '../registry/nodeRegistry';
import { NodeIcon, getIconSlug } from './nodes/shared';
import { findNodeClassById } from '../nodes/nodeClasses';
import type { NodeContextMenuActions, PaneContextMenuActions } from './CanvasContextMenu';
import type { CanvasContextMenuActions } from '../hooks/useCanvasContextMenuActions';
import { useCanvasKeyboardShortcuts } from '../hooks/useCanvasKeyboardShortcuts';
// Lazy so the menu's heavy side-panel-content import chain only loads on the
// first right-click, not eagerly with every canvas mount.
const CanvasContextMenu = React.lazy(() => import('./CanvasContextMenu'));
import { nodeClipboard, useClipboardHasContent } from '../services/nodeClipboard';
import { computeDownstreamNodeIds, nodeHasConnections } from '../utils/graphMutations';

interface BuilderCanvasProps {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  onNodesChange: OnNodesChange;
  onEdgesChange: OnEdgesChange;
  onConnect: (connection: Connection) => void;
  onCreateNode: (item: PaletteDragItem, position: XYPosition, options?: { parentId?: string }) => void;
  onSelectionChange: (selectedIds: string[]) => void;
  onNodeDoubleClick?: (nodeId: string) => void;
  hoveredEdgeId: string | null;
  onHoverEdge: (edgeId: string | null) => void;
  onDeleteEdge: (edgeId: string) => void;
  onOpenNodeCreator?: () => void;
  isNodeCreatorOpen?: boolean;
  hasSelectedNodes?: boolean;
  isFullscreen?: boolean;
  isAdvancedMode?: boolean;
  children?: React.ReactNode;
  inspectorPanel?: React.ReactNode;
  onGetViewportCenter?: () => XYPosition | null;
  onForceNodesUpdate?: (nodes: Node<BuilderNodeData>[]) => void;
  onForceEdgesUpdate?: (edges: Edge[]) => void;
  onUndo?: () => void;
  onRedo?: () => void;
  canUndo?: boolean;
  canRedo?: boolean;
  inspectorConnectionType?: ConnectionType;
  reactFlowConnectionType?: ConnectionType;
  onInspectorConnectionTypeChange?: (type: ConnectionType) => void;
  onReactFlowConnectionTypeChange?: (type: ConnectionType) => void;
  workflowId?: string;
  workflowName?: string;
  runId?: string;
  onSaveWorkflow?: (workflowId: string, plan: any) => Promise<void>;
  onReactFlowInstance?: (instance: ReactFlowInstance) => void;
  isLoadingWorkflow?: boolean;
  /** Plan fetch failed - show the error overlay instead of a silent empty canvas. */
  loadError?: boolean;
  /** Retry the failed plan fetch (wired to useWorkflowLoader.retryLoad). */
  onRetryLoad?: () => void;
  onDeselectAll?: () => void;
  readonly?: boolean;
  selectedNodeIdsFromParent?: string[];
  onSettingsOpenChange?: (isOpen: boolean) => void;
  pendingHoverConnectionRef?: React.MutableRefObject<{
    nodeId: string;
    handleId: string;
    handleType: 'source' | 'target';
    handlePosition: 'left' | 'right' | 'top' | 'bottom';
    position: { x: number; y: number };
  } | null>;
  /**
   * Raw-graph operations backing the right-click context menu (copy/paste,
   * duplicate, select downstream, disconnect, add note, delete, select all)
   * and the canvas keyboard shortcuts. Omitted in embedded/read-only mounts
   * that don't wire the menu.
   */
  contextMenuActions?: CanvasContextMenuActions;
}

/**
 * Validation indicator button - shows aggregated errors/warnings for the workflow.
 * Only visible when there are issues. Clicking a node row focuses the canvas on it.
 */
function ValidationIndicator({ nodes, onFocusNode }: {
  nodes: Node<BuilderNodeData>[];
  onFocusNode?: (nodeId: string) => void;
}) {
  const validation = useValidationOptional();
  if (!validation) return null;

  const { state } = validation;
  const { errorCount, warningCount, nodeValidation } = state;
  if (errorCount === 0 && warningCount === 0) return null;

  // Build aggregated issue list grouped by node
  const issuesByNode: { nodeId: string; label: string; nodeData?: BuilderNodeData; errors: string[]; warnings: string[] }[] = [];
  const seen = new Set<string>();

  nodes.forEach((node) => {
    const nv = nodeValidation.get(node.id);
    if (!nv || (nv.errorCount === 0 && nv.warningCount === 0)) return;
    if (seen.has(node.id)) return;
    seen.add(node.id);
    issuesByNode.push({
      nodeId: node.id,
      label: node.data?.label || node.id,
      nodeData: node.data,
      errors: nv.errors,
      warnings: nv.warnings,
    });
  });

  // Collect orphan issues (edges, back-edges) not tied to any node.
  // nodeValidation stores entries by nodeId AND by elementKey. Node-linked issues
  // appear under their nodeId (shown above). Edge/back-edge issues only have an
  // elementKey with no matching nodeId, so they never appear - fix that here.
  const orphanErrors: string[] = [];
  const orphanWarnings: string[] = [];
  if (state.fullResult?.issuesByElement) {
    const nodeIdSet = new Set(nodes.map(n => n.id));
    for (const [key, issues] of Object.entries(state.fullResult.issuesByElement)) {
      if (key === 'global') continue;
      // Skip element keys that were matched to a node during validation.
      // When a nodeId is found, ValidationContext stores under both nodeId and elementKey.
      // We only need to check: does any node's elementKey equal this key?
      // If so, the node loop above already rendered it.
      const matchedByNode = issues.some(i => i.context?.nodeId && nodeIdSet.has(i.context.nodeId as string));
      if (matchedByNode) continue;
      for (const issue of issues) {
        if (issue.severity === 'error') orphanErrors.push(issue.message);
        else orphanWarnings.push(issue.message);
      }
    }
  }

  // Add global errors + orphan issues under "Workflow"
  if (state.globalErrors.length > 0 || orphanErrors.length > 0 || orphanWarnings.length > 0) {
    issuesByNode.unshift({
      nodeId: '',
      label: 'Workflow',
      errors: [...state.globalErrors, ...orphanErrors],
      warnings: orphanWarnings,
    });
  }

  const hasErrors = errorCount > 0;

  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          className="relative flex items-center justify-center w-8 h-8"
          title={`${errorCount} error${errorCount !== 1 ? 's' : ''}, ${warningCount} warning${warningCount !== 1 ? 's' : ''}`}
        >
          <Info className={`w-5 h-5 ${hasErrors ? 'text-red-500' : 'text-amber-500'}`} />
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="w-[min(320px,calc(100vw-32px))] max-h-[400px] overflow-y-auto p-0 bg-[var(--bg-primary)] border border-gray-200/50 dark:border-gray-700/50 rounded-xl shadow-none z-[99999]"
        side="bottom"
        align="end"
      >
        <div className="px-3 py-2 border-b border-gray-200 dark:border-gray-700">
          <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">
            {errorCount > 0 && <span className="text-red-500">{errorCount} error{errorCount !== 1 ? 's' : ''}</span>}
            {errorCount > 0 && warningCount > 0 && <span className="text-slate-400 mx-1">&middot;</span>}
            {warningCount > 0 && <span className="text-amber-500">{warningCount} warning{warningCount !== 1 ? 's' : ''}</span>}
          </p>
        </div>
        <div className="divide-y divide-gray-100 dark:divide-gray-800">
          {issuesByNode.map((item, idx) => {
            const dataId = item.nodeData?.id || '';
            const nodeClass = dataId ? findNodeClassById(dataId) : undefined;
            const iconSlug = item.nodeData ? getIconSlug(item.nodeData) : undefined;
            const nodeKind = item.nodeData?.kind || item.nodeData?.agentType;
            const nodeFamily = nodeClass?.family;
            return (
              <div
                key={idx}
                className={`px-3 py-2 ${item.nodeId ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50' : ''}`}
                onClick={() => { if (item.nodeId && onFocusNode) onFocusNode(item.nodeId); }}
              >
                <div className="flex items-center gap-1.5 mb-1">
                  {item.nodeData && (
                    <NodeIcon
                      iconSlug={iconSlug}
                      nodeId={dataId}
                      nodeKind={nodeKind as any}
                      nodeFamily={nodeFamily}
                      size="xs"
                      alt={item.label}
                    />
                  )}
                  <p className="text-xs font-semibold text-slate-600 dark:text-slate-400 truncate">{item.label}</p>
                </div>
                {item.errors.map((msg, i) => (
                  <p key={`e-${i}`} className="text-xs text-red-600 dark:text-red-400 pl-5 py-0.5">{msg}</p>
                ))}
                {item.warnings.map((msg, i) => (
                  <p key={`w-${i}`} className="text-xs text-amber-600 dark:text-amber-400 pl-5 py-0.5">{msg}</p>
                ))}
              </div>
            );
          })}
        </div>
      </PopoverContent>
    </Popover>
  );
}

// Custom connection line component
function CustomConnectionLine({
  fromX,
  fromY,
  toX,
  toY,
  fromPosition,
  toPosition,
}: ConnectionLineComponentProps) {
  // Same rule as the rendered BuilderEdge, from the same helper: the drag preview
  // must not curve one way and then snap to another shape on drop.
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const isBackwardConnection = isFlowBackward(layoutDirection, fromX, fromY, toX, toY);

  let path: string;
  if (isBackwardConnection) {
    [path] = getSmoothStepPath({
      sourceX: fromX, sourceY: fromY, sourcePosition: fromPosition,
      targetX: toX, targetY: toY, targetPosition: toPosition,
      borderRadius: 20,
    });
  } else {
    [path] = getBezierPath({
      sourceX: fromX, sourceY: fromY, sourcePosition: fromPosition,
      targetX: toX, targetY: toY, targetPosition: toPosition,
    });
  }

  return (
    <path d={path} fill="none" stroke="#94a3b8" strokeWidth={1.6} className="animated" />
  );
}

export function BuilderCanvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onCreateNode,
  onSelectionChange,
  onNodeDoubleClick,
  hoveredEdgeId,
  onHoverEdge,
  onDeleteEdge,
  onOpenNodeCreator,
  isNodeCreatorOpen,
  hasSelectedNodes = false,
  isFullscreen = false,
  isAdvancedMode = false,
  children,
  inspectorPanel,
  onGetViewportCenter,
  onForceNodesUpdate,
  onForceEdgesUpdate,
  onUndo,
  onRedo,
  canUndo = false,
  canRedo = false,
  inspectorConnectionType = 'bezier',
  reactFlowConnectionType = 'bezier',
  onInspectorConnectionTypeChange,
  onReactFlowConnectionTypeChange,
  workflowId,
  workflowName = '',
  runId,
  onSaveWorkflow,
  onReactFlowInstance,
  isLoadingWorkflow = false,
  loadError = false,
  onRetryLoad,
  onSettingsOpenChange,
  pendingHoverConnectionRef,
  contextMenuActions,
  selectedNodeIdsFromParent,
}: BuilderCanvasProps) {
  const t = useTranslations('workflowBuilder.canvas');
  const { isRunMode, isPreviewOnly } = useWorkflowMode();
  // Safe variant: the canvas also mounts on surfaces without the provider
  // (marketplace preview, snapshots), which must keep the horizontal default.
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  // Kept in a ref so the Save-event effect can stamp the active direction into the
  // plan WITHOUT re-registering the listener on every direction flip (a live toggle
  // must not tear down and rebind the save handler mid-interaction).
  const layoutDirectionRef = React.useRef(layoutDirection);
  layoutDirectionRef.current = layoutDirection;
  const isLocked = isRunMode || isPreviewOnly;
  const { theme } = useTheme();
  const isDark = theme === 'dark';
  const pathname = usePathname();
  // Embedded = mounted outside this workflow's /app/workflow/<id> page (e.g.
  // a SidePanel tab on a chat page). Same heuristic as WorkflowModeToggle.
  const isEmbedded = isEmbeddedWorkflowCanvas(pathname, workflowId);
  // When the right side panel is open, its AI Chat tab already shows the (now
  // centered) composer, so the empty-canvas hero would be a duplicate - hide it.
  const isSidePanelOpen = useSidePanelSafe()?.isOpen ?? false;
  // ReactFlow's <Background> builds its SVG <pattern> id from the store rfId,
  // which is "1" for every <ReactFlow> mounted without an explicit id. With
  // several canvases mounted at once (keepMounted SidePanel tabs), every
  // url(#pattern-1) resolves to the FIRST <pattern> in the DOM, so the other
  // canvases paint their dots with that instance's viewport transform -
  // sub-pixel radius after its fitView → the grid visually disappears. A
  // per-instance suffix keeps each canvas's pattern self-contained.
  const backgroundPatternId = useSvgSafeId();
  // State
  const [hoveredNodeId, setHoveredNodeId] = React.useState<string | null>(null);
  const [instance, setInstance] = React.useState<ReactFlowInstance | null>(null);
  const [isInteractive, setIsInteractive] = React.useState(true);
  const [isLockFocused, setIsLockFocused] = React.useState(false);
  const [isSaving, setIsSaving] = React.useState(false);
  const [isSettingsOpen, setIsSettingsOpenRaw] = React.useState(false);
  const setIsSettingsOpen = React.useCallback((open: boolean) => {
    setIsSettingsOpenRaw(open);
    onSettingsOpenChange?.(open);
  }, [onSettingsOpenChange]);
  const [isCanvasStreaming, setIsCanvasStreaming] = React.useState(false);
  const [isMobileOrTablet, setIsMobileOrTablet] = React.useState(() => {
    if (typeof window === 'undefined') return false;
    return window.innerWidth < 1024;
  });

  // Right-click context menu (node or empty-canvas). `nodeId` is set for the
  // node variant; `flowPos` carries the paste-at-cursor target for the pane.
  const [contextMenu, setContextMenu] = React.useState<
    { kind: 'node'; x: number; y: number; nodeId: string }
    | { kind: 'pane'; x: number; y: number; flowPos: XYPosition | null }
    | null
  >(null);
  const closeContextMenu = React.useCallback(() => setContextMenu(null), []);
  const clipboardHasContent = useClipboardHasContent();
  // Pointer state for canvas-scoped keyboard shortcuts: only act while the
  // pointer is over the canvas, and paste at the last known cursor position.
  const isPointerOverCanvasRef = React.useRef(false);
  const lastPointerScreenRef = React.useRef<{ x: number; y: number }>({ x: 0, y: 0 });

  const onDeleteEdgeRef = React.useRef(onDeleteEdge);
  React.useEffect(() => { onDeleteEdgeRef.current = onDeleteEdge; }, [onDeleteEdge]);

  // Hooks
  const { toast, showToast, hideToast } = useSimpleToast();

  // Connect-gesture tracking - surface a "one port = one node" hint when the
  // user releases a drag started from an already-wired branch port. isValidConnection
  // can only block such a drag silently, so we detect it on connect-end instead.
  const connectStartRef = React.useRef<OnConnectStartParams | null>(null);
  const connectionMadeRef = React.useRef(false);

  const handleConnectTracked = React.useCallback(
    (connection: Connection) => {
      connectionMadeRef.current = true;
      onConnect(connection);
    },
    [onConnect]
  );

  const handleConnectEnd = React.useCallback(() => {
    const start = connectStartRef.current;
    connectStartRef.current = null;
    if (connectionMadeRef.current || !start || start.handleType !== 'source') return;
    if (isWiredOutputPort(start.nodeId, start.handleId, nodes, edges)) {
      showToast('error', t('portSingleTargetHint'));
    }
  }, [nodes, edges, showToast, t]);
  const {
    typingSuggestionId,
    chatInput,
    handleSuggestionClick,
    handleChatInputChange,
  } = useTypingSuggestion();

  const {
    isBoxSelectionEnabled,
    isSelecting,
    selectionStart,
    selectionEnd,
    handleToggleBoxSelection,
    handleSelectionChange,
    containerRef,
    selectionJustEndedRef,
  } = useBoxSelection({
    instance,
    nodes,
    onSelectionChange,
    onForceNodesUpdate,
    onNodesChange,
  });

  const {
    isViewReady,
    handleInstanceInit: viewportHandleInit,
    handleZoomIn,
    handleZoomOut,
    handleFitView,
  } = useCanvasViewport({
    instance,
    nodes,
    workflowId,
    isLoadingWorkflow,
    onReactFlowInstance,
  });

  // Track container position and size via ResizeObserver
  // This ensures the inspector panel stays within bounds when sidebar/SidePanel resize
  const [containerOffset, setContainerOffset] = React.useState({ x: 0, y: 0 });
  const [containerSize, setContainerSize] = React.useState({ width: 0, height: 0 });
  React.useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const updateBounds = () => {
      const rect = container.getBoundingClientRect();
      // Content-stable updates: a new {x,y} / {width,height} object on every observer
      // tick re-renders consumers (e.g. useInspectorDrag depends on containerSize) even
      // when nothing moved - during a node drag the ResizeObserver can fire repeatedly,
      // amplifying the render storm that trips React error #185.
      setContainerOffset(prev =>
        prev.x === rect.left && prev.y === rect.top
          ? prev
          : { x: rect.left, y: rect.top }
      );
      setContainerSize(prev =>
        prev.width === rect.width && prev.height === rect.height
          ? prev
          : { width: rect.width, height: rect.height }
      );
    };
    updateBounds();
    const resizeObserver = new ResizeObserver(updateBounds);
    resizeObserver.observe(container);
    window.addEventListener('scroll', updateBounds);
    return () => {
      resizeObserver.disconnect();
      window.removeEventListener('scroll', updateBounds);
    };
  }, []);

  const {
    position: inspectorPanelPosition,
    handleDragStart: handleInspectorDragStart,
  } = useInspectorDrag({ x: 16, y: 16 }, { containerSize });

  // Pick the random set once (stable across renders); resolve the user-facing
  // label/prompt from i18n (keyed by id) so both the chip text and the prompt
  // sent on click follow the app locale instead of being hardcoded.
  const suggestionMeta = React.useMemo(() => getDisplayedSuggestions(), []);
  const displayedSuggestions = React.useMemo<WorkflowSuggestion[]>(
    () => suggestionMeta.map((m) => ({
      ...m,
      label: t(`suggestions.${m.id}.label`),
      prompt: t(`suggestions.${m.id}.prompt`),
    })),
    [suggestionMeta, t],
  );

  // Instance init handler
  const handleInstanceInit = React.useCallback((newInstance: ReactFlowInstance) => {
    setInstance(newInstance);
    viewportHandleInit(newInstance);
  }, [viewportHandleInit]);

  // Reset lock focus when switching modes (keep interactivity enabled in both modes)
  React.useEffect(() => {
    setIsLockFocused(false);
  }, [isRunMode]);

  // Mobile/tablet detection
  React.useEffect(() => {
    const checkSize = () => setIsMobileOrTablet(window.innerWidth < 1024);
    checkSize();
    window.addEventListener('resize', checkSize);
    return () => window.removeEventListener('resize', checkSize);
  }, []);

  // Listen for streaming state changes
  React.useEffect(() => {
    const handleStreamingChange = (event: CustomEvent<{ isStreaming: boolean }>) => {
      setIsCanvasStreaming(event.detail.isStreaming);
    };
    window.addEventListener('workflowStreamingStateChange', handleStreamingChange as EventListener);
    return () => window.removeEventListener('workflowStreamingStateChange', handleStreamingChange as EventListener);
  }, []);

  // Listen for toast events from parent views (e.g. cancel failure)
  React.useEffect(() => {
    const handleToast = (event: CustomEvent<{ type: 'success' | 'error'; message: string }>) => {
      showToast(event.detail.type, event.detail.message);
    };
    window.addEventListener('workflowToast', handleToast as EventListener);
    return () => window.removeEventListener('workflowToast', handleToast as EventListener);
  }, [showToast]);

  // Listen for focus-node requests (e.g. from run info panel step clicks)
  React.useEffect(() => {
    const handleFocusNode = (event: CustomEvent<{ stepAlias: string }>) => {
      const { stepAlias } = event.detail;
      const node = nodes.find((n: Node<BuilderNodeData>) =>
        nodeMatchesStep(n, { stepAlias, id: stepAlias })
      );
      if (!node) return;
      // Select the node
      onSelectionChange([node.id]);
      // Center viewport on the node
      const inst = instance;
      if (inst && node.position) {
        const nodeWidth = (node.width ?? 200) / 2;
        const nodeHeight = (node.height ?? 60) / 2;
        inst.setCenter(
          node.position.x + nodeWidth,
          node.position.y + nodeHeight,
          { zoom: 1, duration: 300 }
        );
      }
      // For interface nodes, switch to the application tab within the workflow panel
      // (this stays on the workflow-panel tab, unlike agent/datasource which would steal focus).
      // Agent/datasource tabs are NOT auto-opened here to avoid switching away from the
      // workflow-panel tab. Users can explicitly open those via node bottom-bar buttons.
      if (nodeRegistry.isInterfaceNode(node)) {
        const interfaceId = (node.data as any)?.interfaceData?.interfaceId;
        if (interfaceId) {
          window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', {
            detail: { interfaceId },
          }));
        }
      }
    };
    window.addEventListener('workflowFocusNode', handleFocusNode as EventListener);
    return () => window.removeEventListener('workflowFocusNode', handleFocusNode as EventListener);
  }, [nodes, instance, onSelectionChange]);

  // Dispatch InspectorPanel open/close state change
  const hasInspectorPanel = !!inspectorPanel;
  React.useEffect(() => {
    const isInspectorOpen = hasInspectorPanel && !!hasSelectedNodes;
    window.dispatchEvent(new CustomEvent('inspectorPanelStateChange', {
      detail: { isOpen: isInspectorOpen, isAdvanced: isAdvancedMode, isFullscreen }
    }));
  }, [hasInspectorPanel, hasSelectedNodes, isAdvancedMode, isFullscreen]);

  // Close inspector when clicking anywhere outside it (global listener)
  React.useEffect(() => {
    if (!hasSelectedNodes) return;

    const handleGlobalMouseDown = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      // Keep open if clicking inside the inspector, a node, an edge, the ReactFlow canvas,
      // any ReactFlow panel overlay (toolbar, mode toggle), or the run info panel
      if (
        target.closest('[data-inspector-panel]') ||
        target.closest('[data-run-info-panel]') ||
        target.closest('.react-flow__node') ||
        target.closest('.react-flow__edge') ||
        target.closest('.react-flow__pane') ||
        target.closest('.react-flow__panel')
      ) return;

      onSelectionChange([]);
    };

    document.addEventListener('mousedown', handleGlobalMouseDown);
    return () => document.removeEventListener('mousedown', handleGlobalMouseDown);
  }, [hasSelectedNodes, onSelectionChange]);

  // Keyboard shortcuts for undo/redo
  React.useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (isLocked) return; // Block structural keyboard shortcuts in run/readonly mode
      if ((event.ctrlKey || event.metaKey) && event.key === 'z' && !event.shiftKey) {
        event.preventDefault();
        if (canUndo && onUndo) onUndo();
      }
      if ((event.ctrlKey || event.metaKey) && (event.key === 'y' || (event.key === 'z' && event.shiftKey))) {
        event.preventDefault();
        if (canRedo && onRedo) onRedo();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isLocked, canUndo, canRedo, onUndo, onRedo]);

  // Save event listener
  React.useEffect(() => {
    const handleSaveEvent = async () => {
      if (isPreviewOnly || !workflowId || !onSaveWorkflow) return;
      setIsSaving(true);
      try {
        const nodesWithRealPositions = instance
          ? instance.getNodes().map(instanceNode => {
            const propNode = nodes.find(n => n.id === instanceNode.id);
            return propNode ? {
              ...propNode,
              position: instanceNode.positionAbsolute || instanceNode.position,
              positionAbsolute: instanceNode.positionAbsolute || instanceNode.position,
            } : instanceNode;
          })
          : nodes;
        // Stamp the active reading direction so the header "Save" / pin-button path
        // persists it into the plan (the workflow's rendering identity), matching the
        // save-before-run and rename paths. Read from the ref so a live toggle is
        // reflected without re-binding this listener.
        const plan = generateWorkflowPlan(nodesWithRealPositions, edges, layoutDirectionRef.current);
        await onSaveWorkflow(workflowId, plan);
        window.dispatchEvent(new CustomEvent('workflowViewSaveComplete', {
          detail: { success: true, workflowId }
        }));
      } catch (error) {
        console.error('Error saving workflow:', error);
        window.dispatchEvent(new CustomEvent('workflowViewSaveComplete', {
          detail: { success: false, workflowId, error: String(error) }
        }));
      } finally {
        setIsSaving(false);
      }
    };
    window.addEventListener('workflowViewSave', handleSaveEvent as EventListener);
    return () => window.removeEventListener('workflowViewSave', handleSaveEvent as EventListener);
  }, [workflowId, onSaveWorkflow, instance, nodes, edges, isPreviewOnly]);

  // Drag & Drop handlers
  const handleDragOver = React.useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }, []);

  // Guard node/edge changes in run/readonly mode: filter out 'remove' events
  const guardedOnNodesChange: OnNodesChange = React.useCallback((changes) => {
    if (isLocked) {
      const filtered = changes.filter(c => c.type !== 'remove');
      if (filtered.length > 0) onNodesChange(filtered);
      return;
    }
    // Clear stale hover if the hovered node is being removed
    if (changes.some(c => c.type === 'remove' && c.id === hoveredNodeId)) {
      setHoveredNodeId(null);
    }
    onNodesChange(changes);
  }, [isLocked, onNodesChange, hoveredNodeId]);

  const guardedOnEdgesChange: OnEdgesChange = React.useCallback((changes) => {
    // Filter out edge select changes when nodes are selected - usePreparedGraph
    // programmatically sets edge.selected based on connected nodes.  Letting ReactFlow
    // override that creates an infinite render loop (deselect → reselect → …).
    const filtered = hasSelectedNodes
      ? changes.filter(c => c.type !== 'select' && (isLocked ? c.type !== 'remove' : true))
      : isLocked
        ? changes.filter(c => c.type !== 'remove')
        : changes;
    if (filtered.length > 0) onEdgesChange(filtered);
  }, [isLocked, onEdgesChange, hasSelectedNodes]);

  const handleDrop = React.useCallback((event: React.DragEvent) => {
    event.preventDefault();
    if (!instance || isLocked) return;

    const target = event.target as HTMLElement;
    if (target.closest('[data-node-creator-panel]')) return;

    const payload = event.dataTransfer.getData('application/reactflow');
    if (!payload) return;

    try {
      const item = JSON.parse(payload) as PaletteDragItem & { toolData?: any; apiData?: any };
      const flowPosition = instance.screenToFlowPosition({ x: event.clientX, y: event.clientY });
      onCreateNode(item, flowPosition);
    } catch (error) {
      console.error('Invalid palette payload', error);
    }
  }, [instance, isLocked, onCreateNode]);

  // Click handlers
  const handlePaneClick = React.useCallback((event: React.MouseEvent) => {
    // Skip if box selection just ended - the click event fires right after mouseup
    if (selectionJustEndedRef.current) return;

    const target = event.target as HTMLElement;
    if (target.closest('[data-inspector-panel]') || target.closest('.react-flow__node') || target.closest('.react-flow__edge')) return;

    // Clear pending hover connection on pane click
    if (pendingHoverConnectionRef?.current) {
      pendingHoverConnectionRef.current = null;
      window.dispatchEvent(new CustomEvent('workflowNodeCreated'));
    }

    onSelectionChange([]);
    const selectedEdges = edges.filter((edge) => edge.selected);
    if (selectedEdges.length > 0) {
      onEdgesChange(selectedEdges.map((edge) => ({ type: 'select' as const, id: edge.id, selected: false })));
    }
  }, [onSelectionChange, onEdgesChange, edges, pendingHoverConnectionRef]);

  const handlePaneDoubleClick = React.useCallback((event: React.MouseEvent) => {
    if (!instance) return;
    const target = event.target as HTMLElement;
    if (target.closest('.react-flow__node') || target.closest('[data-inspector-panel]') ||
      target.closest('.react-flow__edge') || target.closest('.react-flow__panel')) return;

    const position = instance.screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const currentZoom = instance.getZoom();
    const newZoom = Math.min(currentZoom * 1.5, 1.5);
    instance.setCenter(position.x, position.y, { zoom: newZoom, duration: 300 });
  }, [instance]);

  const handleNodeClick = React.useCallback<NodeMouseHandler>((_, node) => {
    // Skip if box selection just ended - the click fires right after mouseup
    if (selectionJustEndedRef.current) return;

    // If there's a pending hover connection, try to connect to the clicked node
    const pending = pendingHoverConnectionRef?.current;
    if (pending && pending.nodeId !== node.id) {
      const clickedIsTrigger = nodeRegistry.isTrigger(node);
      const clickedHasTargetHandle = !clickedIsTrigger; // triggers have no incoming handle
      const clickedHasSourceHandle = true; // every node has an outgoing handle

      // Decide by the handle's ROLE, not its geometric side. `handleType` already
      // carries source/target, so this is direction-agnostic: a source is on the
      // right in horizontal and the bottom in vertical, but it is a source either
      // way. Keying off `handlePosition === 'right'` (as this did) made the "+"
      // create a BACKWARD edge on a vertical canvas, where the source is 'bottom'.
      if (pending.handleType === 'source' && clickedHasTargetHandle) {
        // "+" on the source → the hovered node feeds the clicked node.
        onConnect({
          source: pending.nodeId,
          target: node.id,
          sourceHandle: pending.handleId,
          targetHandle: null,
        });
        pendingHoverConnectionRef!.current = null;
        window.dispatchEvent(new CustomEvent('workflowNodeCreated'));
        return;
      } else if (pending.handleType === 'target' && clickedHasSourceHandle) {
        // "+" on the target → the clicked node feeds the hovered node.
        let sourceHandle = 'source-right'; // logical id, geometry-independent
        if (nodeRegistry.isDecisionLikeNode(node)) {
          sourceHandle = `${node.id}-if`;
        }
        onConnect({
          source: node.id,
          target: pending.nodeId,
          sourceHandle,
          targetHandle: resolveInsertedTargetHandle(pending.handleId),
        });
        pendingHoverConnectionRef!.current = null;
        window.dispatchEvent(new CustomEvent('workflowNodeCreated'));
        return;
      }
      // Incompatible handle → do nothing, don't select
      return;
    }

    onSelectionChange([node.id]);
    const selectedEdges = edges.filter((edge) => edge.selected);
    if (selectedEdges.length > 0) {
      onEdgesChange(selectedEdges.map((edge) => ({ type: 'select' as const, id: edge.id, selected: false })));
    }
  }, [onSelectionChange, onEdgesChange, edges, onConnect, pendingHoverConnectionRef]);

  const handleEdgeClick = React.useCallback((_: React.MouseEvent, edge: Edge) => {
    onEdgesChange([{ type: 'select' as const, id: edge.id, selected: true }]);
    onSelectionChange([]);
  }, [onEdgesChange, onSelectionChange]);

  const handleNodeDoubleClick = React.useCallback<NodeMouseHandler>((_, node) => {
    onNodeDoubleClick?.(node.id);
  }, [onNodeDoubleClick]);

  // Node drag handlers (no-op, kept for ReactFlow API)
  const handleNodeDragStop = React.useCallback<NodeDragHandler>(() => {
    // No special handling needed
  }, []);

  // Control handlers
  const handleToggleInteractivity = React.useCallback(() => {
    setIsInteractive((prev) => {
      const newValue = !prev;
      setIsLockFocused(!newValue);
      return newValue;
    });
  }, []);

  // Auto-layout must land in the SAME direction the nodes are wired for, or the
  // button re-flows a top-down graph into a left-right one while every handle still
  // points down.
  const handleAutoLayout = React.useCallback(() => {
    if (!onForceNodesUpdate) return;
    const layoutedNodes = applyDagreLayout(nodes, edges, layoutConfigForDirection(layoutDirection));
    onForceNodesUpdate(layoutedNodes);
    if (instance) {
      setTimeout(() => instance.fitView({ padding: 0.2, duration: 300 }), 100);
    }
  }, [nodes, edges, onForceNodesUpdate, instance, layoutDirection]);

  // Re-tidy the graph after a node is inserted through the hover "+", and ONLY then
  // (the `hoverPlusNodeInserted` event fires from that one path - not drag-drop, plain
  // node clicks, or toolbox/context-menu adds). A ref keeps the listener on the latest
  // handleAutoLayout without re-subscribing; the short delay lets the new node + edge
  // settle into state before the layout reads them.
  const autoLayoutRef = React.useRef(handleAutoLayout);
  React.useEffect(() => {
    autoLayoutRef.current = handleAutoLayout;
  });
  React.useEffect(() => {
    const onInserted = () => window.setTimeout(() => autoLayoutRef.current(), 130);
    window.addEventListener('hoverPlusNodeInserted', onInserted);
    return () => window.removeEventListener('hoverPlusNodeInserted', onInserted);
  }, []);

  // --- Right-click context menu -------------------------------------------
  const handleNodeContextMenu = React.useCallback<NodeMouseHandler>((event, node) => {
    event.preventDefault();
    setContextMenu({ kind: 'node', x: event.clientX, y: event.clientY, nodeId: node.id });
  }, []);

  const handlePaneContextMenu = React.useCallback((event: React.MouseEvent | MouseEvent) => {
    event.preventDefault();
    // Nothing actionable on an empty canvas in run/preview mode - let the
    // native menu through rather than showing an empty box.
    if (isLocked && nodes.length === 0) return;
    const flowPos = instance ? instance.screenToFlowPosition({ x: event.clientX, y: event.clientY }) : null;
    setContextMenu({ kind: 'pane', x: event.clientX, y: event.clientY, flowPos });
  }, [instance, isLocked, nodes.length]);

  const nodeMenuActions = React.useMemo<NodeContextMenuActions>(() => ({
    openSettings: (nodeId) => onNodeDoubleClick?.(nodeId),
    duplicate: (nodeId) => contextMenuActions?.duplicateNode(nodeId),
    copy: (nodeId) => contextMenuActions?.copyNode(nodeId),
    selectDownstream: (nodeId) => contextMenuActions?.selectDownstream(nodeId),
    disconnect: (nodeId) => contextMenuActions?.disconnectNode(nodeId),
    addNote: (nodeId) => contextMenuActions?.addNoteNear(nodeId),
    deleteNode: (nodeId) => contextMenuActions?.deleteNode(nodeId),
  }), [onNodeDoubleClick, contextMenuActions]);

  // Canvas-scoped keyboard shortcuts (copy / paste / duplicate / select all /
  // delete). The canvas owns Delete/Backspace (ReactFlow's built-in deleteKeyCode
  // is disabled below) so a node is deleted exactly once.
  useCanvasKeyboardShortcuts({
    enabled: !!contextMenuActions,
    isLocked,
    isPointerOverCanvasRef,
    getSelectionCount: () => selectedNodeIdsFromParent?.length ?? 0,
    getNodeCount: () => nodes.length,
    hasClipboard: () => nodeClipboard.hasContent(),
    getPastePosition: () => (instance ? instance.screenToFlowPosition(lastPointerScreenRef.current) : null),
    actions: contextMenuActions,
  });

  const handleCanvasSendMessage = React.useCallback(() => {
    if (!chatInput.trim()) return;
    const message = chatInput.trim();
    window.dispatchEvent(new CustomEvent('workflowViewToggleMessagesPanel', { detail: { isOpen: true, view: 'chat' } }));
    setTimeout(() => {
      window.dispatchEvent(new CustomEvent('workflowCanvasSendMessage', { detail: { message } }));
    }, 100);
  }, [chatInput]);

  const handleCanvasStopStream = React.useCallback(() => {
    window.dispatchEvent(new CustomEvent('workflowStopStreamRequest'));
  }, []);

  // Display logic
  const shouldHideCanvas = !isRunMode && nodes.length > 0 && !isViewReady;
  const showLoading = isLoadingWorkflow || shouldHideCanvas;

  return (
    <div
      ref={containerRef}
      className={`relative h-full w-full bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800 ${isBoxSelectionEnabled ? 'cursor-crosshair' : ''}`}
      onMouseEnter={() => { isPointerOverCanvasRef.current = true; }}
      onMouseLeave={() => { isPointerOverCanvasRef.current = false; }}
      onMouseMove={(e) => { lastPointerScreenRef.current = { x: e.clientX, y: e.clientY }; }}
    >
      <style dangerouslySetInnerHTML={{ __html: CANVAS_STYLES }} />

      {/* Vignette effect for run mode */}
      {isRunMode && (
        <div className="absolute inset-0 pointer-events-none bg-[radial-gradient(ellipse_at_center,transparent_40%,rgba(0,0,0,0.06)_100%)] dark:bg-[radial-gradient(ellipse_at_center,transparent_40%,rgba(0,0,0,0.15)_100%)]" />
      )}

      {showLoading && (
        <div className="absolute inset-0 z-[100] bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800 flex items-center justify-center">
          <LoadingSpinner size="lg" />
        </div>
      )}

      {/* Plan fetch failed - explicit error state instead of a silent empty canvas
          (where Save stays disabled because dirty-tracking never arms). */}
      {loadError && !showLoading && (
        <div className="absolute inset-0 z-[100] bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800 flex items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-center px-6">
            <AlertTriangle className="h-8 w-8 text-amber-500" />
            <p className="text-base font-medium text-theme-primary">{t('loadErrorTitle')}</p>
            <p className="text-sm text-theme-secondary max-w-sm">{t('loadErrorMessage')}</p>
            {onRetryLoad && (
              <Button variant="outline" size="sm" onClick={onRetryLoad}>
                {t('loadErrorRetry')}
              </Button>
            )}
          </div>
        </div>
      )}

      {/* Add node button + validation indicator (edit mode only) */}
      {onOpenNodeCreator && !isNodeCreatorOpen && !isSettingsOpen && (!hasSelectedNodes || !isMobileOrTablet) && !isFullscreen && !isLocked && (
        <div className="absolute top-4 right-4 z-[60] flex items-center gap-2">
          <ValidationIndicator nodes={nodes} onFocusNode={(nodeId) => {
            const node = nodes.find(n => n.id === nodeId);
            if (!node) return;
            onSelectionChange([node.id]);
            if (instance && node.position) {
              const nodeWidth = (node.width ?? 200) / 2;
              const nodeHeight = (node.height ?? 60) / 2;
              instance.setCenter(
                node.position.x + nodeWidth,
                node.position.y + nodeHeight,
                { zoom: 1, duration: 300 }
              );
            }
          }} />
          <Button
            onClick={onOpenNodeCreator}
            className="w-11 h-11 rounded-full p-0 shadow-none"
            title={t('addNode')}
          >
            <Plus className="w-[22px] h-[22px]" />
          </Button>
        </div>
      )}

      {/* Validation indicator in preview/read-only mode (no add node button) */}
      {!onOpenNodeCreator && !isFullscreen && (
        <div className="absolute top-4 right-4 z-10">
          <ValidationIndicator nodes={nodes} onFocusNode={(nodeId) => {
            const node = nodes.find(n => n.id === nodeId);
            if (!node || !instance || !node.position) return;
            const nodeWidth = (node.width ?? 200) / 2;
            const nodeHeight = (node.height ?? 60) / 2;
            instance.setCenter(
              node.position.x + nodeWidth,
              node.position.y + nodeHeight,
              { zoom: 1, duration: 300 }
            );
          }} />
        </div>
      )}

      {/* Run info is now displayed inline with WorkflowModeToggle in WorkflowDetailView */}

      <EdgeActionsProvider value={React.useMemo(() => ({ hoveredEdgeId, onDeleteEdge: onDeleteEdgeRef.current, onUpdateEdgeData: () => {} }), [hoveredEdgeId])}>
        <ReactFlowProvider>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            // Virtualise nodes/edges to the viewport: ReactFlow 11 default is false,
            // which mounts every node as a DOM/React component regardless of where
            // the camera is. On a 1000-node plan that meant ~4-5s initial render and
            // 10-15 FPS pan/zoom (per scaling audit 2026-05-07). With virtualization
            // on, only the visible subset re-renders on scroll/zoom - ~70% UX win
            // confirmed by the audit, single-line change.
            onlyRenderVisibleElements
            nodesDraggable={isInteractive && !isSelecting}
            nodesConnectable={isInteractive && !isLocked}
            elementsSelectable={false}
            onNodesChange={guardedOnNodesChange}
            onEdgesChange={guardedOnEdgesChange}
            onConnect={handleConnectTracked}
            onConnectStart={(_, params) => { connectStartRef.current = params; connectionMadeRef.current = false; }}
            onConnectEnd={handleConnectEnd}
            onEdgeClick={handleEdgeClick}
            onDrop={handleDrop}
            onDragOver={handleDragOver}
            minZoom={0.3}
            maxZoom={1.5}
            defaultViewport={{ x: 0, y: 0, zoom: 1 }}
            // The canvas keyboard handler owns Delete/Backspace; disabling
            // ReactFlow's built-in delete avoids deleting selected nodes twice.
            deleteKeyCode={null}
            zoomOnDoubleClick={false}
            connectionLineComponent={CustomConnectionLine}
            connectionMode={ConnectionMode.Loose}
            isValidConnection={(connection) => validateConnection(connection, nodes, edges)}
            onInit={handleInstanceInit}
            proOptions={{ hideAttribution: true }}
            onSelectionChange={handleSelectionChange}
            onNodeClick={handleNodeClick}
            onNodeDoubleClick={handleNodeDoubleClick}
            onNodeContextMenu={handleNodeContextMenu}
            onPaneContextMenu={handlePaneContextMenu}
            onNodeDragStop={handleNodeDragStop}
            onPaneClick={handlePaneClick}
            onDoubleClick={handlePaneDoubleClick}
            onNodeMouseEnter={(_, node) => setHoveredNodeId(node.id)}
            onNodeMouseLeave={() => setHoveredNodeId(null)}
            onEdgeMouseEnter={(_, edge) => onHoverEdge(edge.id)}
            onEdgeMouseLeave={() => onHoverEdge(null)}
            panOnScroll
            panOnDrag={isBoxSelectionEnabled ? [1, 2] : true}
            selectionOnDrag={false}
            className="h-full w-full [&_.react-flow__node:focus-visible]:outline [&_.react-flow__node:focus-visible]:outline-2 [&_.react-flow__node:focus-visible]:outline-[var(--accent-primary)] [&_.react-flow__node:focus-visible]:outline-offset-2"
            defaultEdgeOptions={{
              type: 'builderEdge',
              style: { strokeWidth: 2 },
              data: { connectionType: reactFlowConnectionType },
            }}
            edgesUpdatable={true}
          >
            {!isRunMode && (
              <Background
                id={backgroundPatternId}
                color={isDark ? '#9ca3af' : '#cbd5e1'}
                gap={25}
                size={1.75}
                variant={BackgroundVariant.Dots}
              />
            )}

            {/* SVG arrow markers */}
            <svg style={{ position: 'absolute', width: 0, height: 0 }}>
              <defs>
                {ARROW_MARKERS.map(({ id, color }) => (
                  <marker key={id} id={id} viewBox="0 0 10 10" refX="8" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" style={{ fill: color }} />
                  </marker>
                ))}
              </defs>
            </svg>

            <HoverEdgeManager
              isRunMode={isLocked}
              hoveredNodeId={isLocked ? null : hoveredNodeId}
              onPlusClick={() => onOpenNodeCreator?.()}
            />

            {/* Re-measure handles when the reading direction changes (seed or toggle). */}
            <DirectionHandleSync
              direction={layoutDirection}
              nodeIds={React.useMemo(() => nodes.map((n) => n.id), [nodes])}
            />

            {/* Custom selection box */}
            {isSelecting && selectionStart && selectionEnd && instance && (() => {
              const minX = Math.min(selectionStart.x, selectionEnd.x);
              const maxX = Math.max(selectionStart.x, selectionEnd.x);
              const minY = Math.min(selectionStart.y, selectionEnd.y);
              const maxY = Math.max(selectionStart.y, selectionEnd.y);
              const startScreen = instance.flowToScreenPosition({ x: minX, y: minY });
              const endScreen = instance.flowToScreenPosition({ x: maxX, y: maxY });
              const width = Math.abs(endScreen.x - startScreen.x);
              const height = Math.abs(endScreen.y - startScreen.y);
              if (width < 5 || height < 5) return null;
              return (
                <div
                  className="custom-selection-box"
                  style={{
                    position: 'fixed',
                    left: Math.min(startScreen.x, endScreen.x),
                    top: Math.min(startScreen.y, endScreen.y),
                    width, height,
                    backgroundColor: 'rgba(59, 130, 246, 0.1)',
                    border: '2px solid #000000',
                    borderRadius: '4px',
                    pointerEvents: 'none',
                    zIndex: 10000,
                  }}
                />
              );
            })()}

            {/* Empty canvas chat - full /app/workflow page only. Embedded
                canvases (SidePanel tabs) have their own AI Chat sub-tab; while
                an agent initializes a plan there, the canvas is briefly empty
                and this composer must not flash over it. */}
            {nodes.length === 0 && !isFullscreen && !isLoadingWorkflow && !isLocked && !isEmbedded && !isSidePanelOpen && (
              <>
                <Panel position="top-center" className="relative z-[50] w-full hidden sm:block" style={{ pointerEvents: 'none' }}>
                  <EmptyCanvasChat
                    chatInput={chatInput}
                    onChatInputChange={handleChatInputChange}
                    onSendMessage={handleCanvasSendMessage}
                    onStopStream={handleCanvasStopStream}
                    isStreaming={isCanvasStreaming}
                    displayedSuggestions={displayedSuggestions}
                    typingSuggestionId={typingSuggestionId}
                    onSuggestionClick={handleSuggestionClick}
                    onCreateNode={onCreateNode}
                    onGetViewportCenter={onGetViewportCenter}
                  />
                </Panel>
                <Panel position="bottom-center" className="relative z-[50] w-full sm:hidden pb-4" style={{ pointerEvents: 'none' }}>
                  <EmptyCanvasChat
                    chatInput={chatInput}
                    onChatInputChange={handleChatInputChange}
                    onSendMessage={handleCanvasSendMessage}
                    onStopStream={handleCanvasStopStream}
                    isStreaming={isCanvasStreaming}
                    displayedSuggestions={displayedSuggestions}
                    typingSuggestionId={typingSuggestionId}
                    onSuggestionClick={handleSuggestionClick}
                    onCreateNode={onCreateNode}
                    onGetViewportCenter={onGetViewportCenter}
                    isMobile
                  />
                </Panel>
              </>
            )}

            {/* Inspector panel - rendered via portal to escape canvas z-10 stacking context */}
            {inspectorPanel && hasSelectedNodes && !(isPreviewOnly && isRunMode) && createPortal(
              <div
                className="fixed z-[150] pointer-events-none"
                style={{ left: containerOffset.x + inspectorPanelPosition.x, top: containerOffset.y + inspectorPanelPosition.y }}
                onClick={(e) => e.stopPropagation()}
              >
                <div
                  data-inspector-panel
                  className="pointer-events-auto"
                  onClick={(e) => e.stopPropagation()}
                  onMouseDown={(e) => e.stopPropagation()}
                  onMouseUp={(e) => e.stopPropagation()}
                >
                  {React.cloneElement(inspectorPanel as React.ReactElement<{ onDragHandleMouseDown?: (e: React.MouseEvent) => void; containerSize?: { width: number; height: number } }>, {
                    onDragHandleMouseDown: (e: React.MouseEvent) => {
                      const dragHandle = (e.target as HTMLElement).closest('[data-drag-handle]');
                      if (dragHandle) handleInspectorDragStart(e);
                    },
                    containerSize,
                  })}
                </div>
              </div>,
              document.body
            )}

            {/* Node creator / Run history panels (hidden visually when settings open to preserve state) */}
            {children && (
              <Panel position="top-right" className={`m-2 sm:m-4 relative z-[150] ${isSettingsOpen ? 'invisible pointer-events-none' : ''}`}>
                {children}
              </Panel>
            )}

            {/* Toolbar */}
            {nodes.length > 0 && !(hasSelectedNodes && isMobileOrTablet) && !isFullscreen && (
              <CanvasToolbar
                isRunMode={isLocked}
                canUndo={canUndo}
                canRedo={canRedo}
                isInteractive={isInteractive}
                isLockFocused={isLockFocused}
                isBoxSelectionEnabled={isBoxSelectionEnabled}
                isSettingsOpen={isSettingsOpen}
                onUndo={onUndo}
                onRedo={onRedo}
                onZoomIn={handleZoomIn}
                onZoomOut={handleZoomOut}
                onFitView={handleFitView}
                onAutoLayout={handleAutoLayout}
                onToggleInteractivity={handleToggleInteractivity}
                onToggleBoxSelection={handleToggleBoxSelection}
                onToggleSettings={() => setIsSettingsOpen(!isSettingsOpen)}
              />
            )}

            {/* Settings panel */}
            <CanvasSettingsPanel
              isOpen={isSettingsOpen}
              onClose={() => setIsSettingsOpen(false)}
              isRunMode={isLocked}
              reactFlowConnectionType={reactFlowConnectionType}
              nodes={nodes}
              edges={edges}
              onReactFlowConnectionTypeChange={onReactFlowConnectionTypeChange}
              onForceNodesUpdate={onForceNodesUpdate}
              onForceEdgesUpdate={onForceEdgesUpdate}
            />
          </ReactFlow>
        </ReactFlowProvider>
      </EdgeActionsProvider>

      {/* Right-click context menu (node + empty canvas), lazy-loaded on demand */}
      {contextMenu && contextMenuActions && (
        <React.Suspense fallback={null}>
          {contextMenu.kind === 'node' ? (() => {
            const node = nodes.find((candidate) => candidate.id === contextMenu.nodeId);
            if (!node) return null;
            return (
              <CanvasContextMenu
                variant="node"
                node={node}
                x={contextMenu.x}
                y={contextMenu.y}
                isRunMode={isRunMode}
                isPreviewOnly={isPreviewOnly}
                hasDownstream={computeDownstreamNodeIds(node.id, edges).length > 0}
                hasConnections={nodeHasConnections(node.id, edges)}
                actions={nodeMenuActions}
                onClose={closeContextMenu}
              />
            );
          })() : (
            <CanvasContextMenu
              variant="pane"
              x={contextMenu.x}
              y={contextMenu.y}
              editable={!isLocked}
              canPaste={clipboardHasContent}
              hasNodes={nodes.length > 0}
              actions={{
                addNode: () => onOpenNodeCreator?.(),
                paste: () => contextMenuActions.paste(contextMenu.flowPos),
                selectAll: () => contextMenuActions.selectAll(),
                autoLayout: handleAutoLayout,
                fitView: handleFitView,
              } satisfies PaneContextMenuActions}
              onClose={closeContextMenu}
            />
          )}
        </React.Suspense>
      )}

      {toast && (
        <SimpleToast type={toast.type} message={toast.message} isVisible={true} onClose={hideToast} />
      )}
    </div>
  );
}

// CSS styles extracted
const CANVAS_STYLES = `
  .react-flow { height: 100% !important; width: 100% !important; }
  .react-flow__viewport { height: 100% !important; width: 100% !important; transition: none !important; }
  .react-flow__edges { z-index: 5; }
  .react-flow__edge.selected { z-index: 20 !important; }
  .react-flow__selection { display: none !important; }
  .custom-selection-box { border: 2px solid #000000 !important; background: rgba(59, 130, 246, 0.08) !important; }
  .react-flow__node { transition: none !important; will-change: transform; }
  .react-flow__node.dragging { transition: none !important; }
  .react-flow__node:not(.react-flow__node-dragging) { transition: border-color 0.15s ease !important; }
  @keyframes dash-flow { 0% { stroke-dashoffset: 0; } 100% { stroke-dashoffset: -12; } }
  `;

// Arrow marker definitions (uses CSS vars via style={{ fill }} - no isDark needed)
const ARROW_MARKERS = [
  { id: 'arrow-default', color: 'var(--border-color)' },
  { id: 'arrow-running', color: '#3b82f6' },
  { id: 'arrow-completed', color: '#10b981' },
  { id: 'arrow-failed', color: '#ef4444' },
  { id: 'arrow-skipped', color: '#94a3b8' },
  { id: 'arrow-selected', color: 'var(--accent-primary)' },
  { id: 'arrow-partial_success', color: '#f59e0b' },
  { id: 'arrow-while-body', color: '#f97316' },
];
