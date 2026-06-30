'use client';

import React, { useCallback, useRef, useState, useEffect, useMemo } from 'react';
import ReactFlow, {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Panel,
  ReactFlowProvider,
  getBezierPath,
  getSmoothStepPath,
  type ReactFlowInstance,
  type ConnectionLineComponentProps,
  type NodeMouseHandler,
} from 'reactflow';
import 'reactflow/dist/style.css';

import { ZoomIn, ZoomOut, Focus, Settings, X, Wand2, UnfoldVertical, FoldVertical, Pencil } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import { useThemeSafely } from '@/hooks/useThemeSafely';
import { useSvgSafeId } from '@/hooks/useSvgSafeId';
import { nodeTypes } from '@/app/workflows/builder/constants/graphTypes';
import { WorkflowModeProvider } from '@/contexts/WorkflowModeContext';
import { StepByStepProvider } from '@/app/workflows/builder/contexts/StepByStepContext';
import { ValidationProvider } from '@/app/workflows/builder/contexts/ValidationContext';
import { EdgeActionsProvider } from '@/app/workflows/builder/components/EdgeActionsContext';
import { useInspectorDrag } from '@/app/workflows/builder/hooks/useInspectorDrag';
import { useAgentFleetState, applyFleetLayout, filterCollapsedNodes } from './useAgentFleetState';
import { applyFleetLayoutCached } from './fleetLayout';
import { useSingleAgentFleet } from './useSingleAgentFleet';
import { useSnapshotAgentFleet } from './useSnapshotAgentFleet';
import type { AgentPublicationSnapshot } from '@/lib/api/orchestrator/types';
import { FleetEdge } from './FleetEdge';
import { FleetPlanGenerator } from './FleetPlanGenerator';
import { FleetInspectorPanel, parseNodeId } from './FleetInspectorPanel';
import { ConfirmDeleteModal } from '@/components/chat/ConfirmDeleteModal';
import { AgentIntegrationToolsModal } from './AgentIntegrationToolsModal';
import { openFleetSidePanelTab } from './fleetSidePanelActions';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import {
  disconnectFleetResource,
  disconnectSubAgent,
  isDisconnectableFleetResource,
  resolveFleetEdgeAction,
} from '@/lib/agents/agentResourceMutations';
import { AgentPickerPanel } from './AgentPickerPanel';
import { CreateAgentModal } from '@/components/chat/CreateAgentModal';
import { useTranslations } from 'next-intl';
import { useAgentActivitySubscriber } from './hooks/useAgentActivityStream';
import { ConnectionTypeSelector, type ConnectionType } from '@/app/workflows/builder/components/ConnectionTypeSelector';

// ─── Fleet edge types ───
const fleetEdgeTypes = { fleet: FleetEdge };

// ─── Edge type selector ───
const EDGE_TYPES = ['default', 'straight', 'step', 'smoothstep', 'wave'] as const;
type FleetEdgeType = (typeof EDGE_TYPES)[number];
// Default connection style for the agent/fleet canvases - step (right-angle) edges.
const DEFAULT_FLEET_EDGE_TYPE: FleetEdgeType = 'step';

function readEdgeType(key: string): FleetEdgeType {
  if (typeof window === 'undefined') return DEFAULT_FLEET_EDGE_TYPE;
  const stored = localStorage.getItem(key);
  if (stored && EDGE_TYPES.includes(stored as FleetEdgeType)) return stored as FleetEdgeType;
  return DEFAULT_FLEET_EDGE_TYPE;
}

// The shared workflow-builder selector uses the explicit 'bezier' value; the
// fleet stores 'default' for the same Bezier style. Map at the UI boundary so
// the fleet shows the exact same control as the workflow builder.
const toConnectionType = (t: FleetEdgeType): ConnectionType => (t === 'default' ? 'bezier' : t);
const fromConnectionType = (t: ConnectionType): FleetEdgeType => (t === 'bezier' ? 'default' : t);

// ─── Connection type visibility filters ───
const EDGE_CATEGORIES_ALL = ['model', 'tools', 'resources', 'sub-agents', 'skills'] as const;
const EDGE_CATEGORIES_SINGLE = ['model', 'tools', 'resources', 'sub-agents'] as const;
type EdgeCategory = (typeof EDGE_CATEGORIES_ALL)[number];
const EDGE_CATEGORY_LABELS: Record<EdgeCategory, string> = {
  model: 'Model',
  tools: 'Tools',
  resources: 'Resources',
  'sub-agents': 'Sub-Agents',
  skills: 'Skills',
};

function readEdgeCategories(key: string, categories: readonly EdgeCategory[]): Set<EdgeCategory> {
  if (typeof window === 'undefined') return new Set(categories);
  try {
    const stored = localStorage.getItem(key);
    if (stored) {
      const parsed = JSON.parse(stored) as string[];
      return new Set(parsed.filter(c => (categories as readonly string[]).includes(c)) as EdgeCategory[]);
    }
  } catch { /* ignore */ }
  return new Set(categories);
}

// ─── Arrow markers (same as BuilderCanvas) ───
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

// ─── CSS (same as BuilderCanvas) ───
const CANVAS_STYLES = `
  .react-flow { height: 100% !important; width: 100% !important; }
  .react-flow__viewport { height: 100% !important; width: 100% !important; transition: none !important; }
  .react-flow__edges { z-index: 5; }
  .react-flow__edge.selected { z-index: 20 !important; }
  .react-flow__edge.selected .react-flow__edge-path {
    stroke: var(--accent-primary) !important;
    stroke-width: 2 !important;
    stroke-dasharray: 8 4;
    animation: dash-flow 1.5s linear infinite;
    transition: stroke 0.15s ease;
  }
  .react-flow__edge:not(.selected) .react-flow__edge-path {
    transition: stroke 0.15s ease, stroke-width 0.15s ease;
  }
  .react-flow__node { transition: none !important; will-change: transform; }
  .react-flow__node.dragging { transition: none !important; }
  .react-flow__node:not(.react-flow__node-dragging) { transition: border-color 0.15s ease !important; }
  @keyframes dash-flow { 0% { stroke-dashoffset: 0; } 100% { stroke-dashoffset: -12; } }
`;

// ─── Connection line (same as BuilderCanvas) ───
function CustomConnectionLine({
  fromX,
  fromY,
  toX,
  toY,
  fromPosition,
  toPosition,
}: ConnectionLineComponentProps) {
  const isHorizontalBackward =
    (fromPosition === 'right' || fromPosition === 'left') && fromX > toX;
  const isVerticalBackward =
    (fromPosition === 'top' || fromPosition === 'bottom') && fromY < toY;
  const isBackwardConnection = isHorizontalBackward || isVerticalBackward;

  let path: string;
  if (isBackwardConnection) {
    [path] = getSmoothStepPath({
      sourceX: fromX,
      sourceY: fromY,
      sourcePosition: fromPosition,
      targetX: toX,
      targetY: toY,
      targetPosition: toPosition,
      borderRadius: 20,
    });
  } else {
    [path] = getBezierPath({
      sourceX: fromX,
      sourceY: fromY,
      sourcePosition: fromPosition,
      targetX: toX,
      targetY: toY,
      targetPosition: toPosition,
    });
  }

  return (
    <path d={path} fill="none" stroke="var(--border-color)" strokeWidth={1.6} className="animated" />
  );
}

// ─── Toolbar with zoom + fit + settings + collapse toggle ───
function FleetToolbar({
  onZoomIn,
  onZoomOut,
  onFitView,
  onAutoLayout,
  onToggleSettings,
  isSettingsOpen,
  onToggleCollapseAll,
  isAllCollapsed,
  hasCollapsible,
  showEditToggle,
  isEditMode,
  onToggleEditMode,
  editLabel,
}: {
  onZoomIn: () => void;
  onZoomOut: () => void;
  onFitView: () => void;
  onAutoLayout: () => void;
  onToggleSettings: () => void;
  isSettingsOpen: boolean;
  onToggleCollapseAll?: () => void;
  isAllCollapsed?: boolean;
  hasCollapsible?: boolean;
  showEditToggle?: boolean;
  isEditMode?: boolean;
  onToggleEditMode?: () => void;
  editLabel?: string;
}) {
  return (
    <Panel position="bottom-center" className="mb-6">
      <div className="flex items-center gap-1 rounded-full bg-white/95 dark:bg-gray-800/95 px-3 py-2 backdrop-blur border-0">
        <Button
          onClick={onZoomIn}
          variant="ghost"
          size="sm"
          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title="Zoom in"
        >
          <ZoomIn className="h-4 w-4" />
        </Button>
        <Button
          onClick={onZoomOut}
          variant="ghost"
          size="sm"
          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title="Zoom out"
        >
          <ZoomOut className="h-4 w-4" />
        </Button>
        <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-0.5" />
        <Button
          onClick={onFitView}
          variant="ghost"
          size="sm"
          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title="Fit view"
        >
          <Focus className="h-4 w-4" />
        </Button>
        <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-0.5" />
        <Button
          onClick={onAutoLayout}
          variant="ghost"
          size="sm"
          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title="Auto-layout"
        >
          <Wand2 className="h-4 w-4" />
        </Button>
        {hasCollapsible && onToggleCollapseAll && (
          <>
            <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-0.5" />
            <Button
              onClick={onToggleCollapseAll}
              variant="ghost"
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={isAllCollapsed ? 'Expand all' : 'Collapse all'}
            >
              {isAllCollapsed ? (
                <UnfoldVertical className="h-4 w-4" />
              ) : (
                <FoldVertical className="h-4 w-4" />
              )}
            </Button>
          </>
        )}
        {showEditToggle && onToggleEditMode && (
          <>
            <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-0.5" />
            <Button
              onClick={onToggleEditMode}
              variant={isEditMode ? 'default' : 'ghost'}
              size="sm"
              className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
              title={editLabel}
            >
              <Pencil className="h-4 w-4" />
            </Button>
          </>
        )}
        <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-0.5" />
        <Button
          onClick={onToggleSettings}
          variant={isSettingsOpen ? 'default' : 'ghost'}
          size="sm"
          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
          title="Settings"
        >
          <Settings className="h-4 w-4" />
        </Button>
      </div>
    </Panel>
  );
}

// ─── No-op edge actions (read-only) ───
const NOOP_EDGE_ACTIONS = {
  hoveredEdgeId: null,
  onDeleteEdge: () => {},
  onUpdateEdgeData: () => {},
};

// ─── Empty sets for StepByStepProvider ───
const EMPTY_SET = new Set<string>();
const noopAsync = async () => {};

// ─── Fleet real-time activity subscriptions ───
// Each agent needs its own useChannel hook invocation (Rules of Hooks),
// so we render a lightweight component per agent that subscribes to its channel.
function AgentActivitySubscriberComponent({ agentId }: { agentId: string }) {
  useAgentActivitySubscriber(agentId);
  return null;
}

const MAX_ACTIVITY_SUBSCRIPTIONS = 20;

const FleetActivitySubscriptions = React.memo(
  function FleetActivitySubscriptions({ agentIds }: { agentIds: string[] }) {
    // Limit subscriptions to avoid excessive WebSocket channels
    const activeIds = useMemo(
      () => agentIds.slice(0, MAX_ACTIVITY_SUBSCRIPTIONS),
      [agentIds],
    );
    return (
      <>
        {activeIds.map(id => (
          <AgentActivitySubscriberComponent key={id} agentId={id} />
        ))}
      </>
    );
  },
  (prev, next) =>
    prev.agentIds.length === next.agentIds.length
    && prev.agentIds.every((id, i) => id === next.agentIds[i]),
);

interface AgentFleetCanvasProps {
  singleAgentId?: string;
  /** When provided, renders from a publication snapshot (read-only, no API calls). */
  snapshot?: AgentPublicationSnapshot | null;
  /** Forces publication snapshot rendering even when the optional snapshot is absent. */
  snapshotMode?: boolean;
  /** Hides all edit/create actions (used on shared conversation pages). */
  readOnly?: boolean;
}

/**
 * AgentFleetCanvas - ReactFlow canvas showing agents as FlowNodes with their
 * resources. When `singleAgentId` is set, shows a single agent (panel mode).
 * Otherwise shows the full fleet dashboard.
 */
export function AgentFleetCanvas({ singleAgentId, snapshot, snapshotMode = false, readOnly }: AgentFleetCanvasProps) {
  const t = useTranslations('common');
  const { theme } = useThemeSafely();
  const isDark = theme === 'dark';
  // Per-instance <Background> pattern id - without it every ReactFlow canvas
  // shares pattern-1 and the first mounted canvas's viewport transform paints
  // everyone's dots (see useSvgSafeId / BuilderCanvas for the full rationale).
  const backgroundPatternId = useSvgSafeId();
  const isSnapshotMode = snapshotMode || snapshot !== undefined;
  const isSingleAgent = !!singleAgentId || isSnapshotMode;
  const instanceRef = useRef<ReactFlowInstance | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  // Structural signature + cached positions of the last laid-out (filtered) graph. When the
  // visible node/edge set is unchanged (a stats/activity update only mutates node DATA),
  // applyFleetLayoutCached rebuilds the FILTERED set at these cached positions instead of
  // re-running Dagre + the animated fitView (the rAF-driven viewport transform behind the
  // layout jank). Rebuilding from the filtered input - not patching a prior array - is what
  // keeps a data-only update from resurrecting collapsed/filtered-out nodes.
  const lastCanvasSigRef = useRef<string>('');
  const lastCanvasPositionsRef = useRef<Map<string, { x: number; y: number }>>(new Map());
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [isInspectorMinimized, setIsInspectorMinimized] = useState(false);

  // Refresh state
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Edit state - shared across fleet and single-agent modes.
  // Fleet mode: picker always opens. Single-agent mode: picker opens iff the
  // panel contains multiple agents (main + children); otherwise edit jumps
  // straight to the modal for the main agent.
  const [isAgentPickerOpen, setIsAgentPickerOpen] = useState(false);
  const [editingAgent, setEditingAgent] = useState<any>(null);
  // Which tab the edit modal opens on: 1=Basic, 2=Configuration (model), 3=Integration (tools).
  const [editingAgentStep, setEditingAgentStep] = useState(1);
  const openAgentEditor = useCallback((agent: any, step = 1) => {
    setEditingAgentStep(step);
    setEditingAgent(agent);
  }, []);

  // ─── Mode-aware localStorage keys ───
  const edgeTypeKey = isSingleAgent ? 'fleet-panel-edge-type' : 'fleet-edge-type';
  const edgeCategoryKey = isSingleAgent ? 'fleet-panel-edge-categories' : 'fleet-edge-categories';
  const activeEdgeCategories = isSingleAgent ? EDGE_CATEGORIES_SINGLE : EDGE_CATEGORIES_ALL;

  const [edgeType, setEdgeType] = useState<FleetEdgeType>(() => readEdgeType(edgeTypeKey));
  const [visibleCategories, setVisibleCategories] = useState<Set<EdgeCategory>>(() =>
    readEdgeCategories(edgeCategoryKey, activeEdgeCategories),
  );

  // Track container size for inspector drag clamping
  const [containerSize, setContainerSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const updateSize = () => {
      const rect = container.getBoundingClientRect();
      setContainerSize({ width: rect.width, height: rect.height });
    };
    updateSize();
    const resizeObserver = new ResizeObserver(updateSize);
    resizeObserver.observe(container);
    return () => { resizeObserver.disconnect(); };
  }, []);

  // Inspector panel drag - pass containerSize for clamping
  const { position: inspectorPosition, handleDragStart: handleInspectorDragStart } = useInspectorDrag(
    { x: 16, y: 16 },
    { containerSize },
  );

  // edgeType is seeded synchronously from localStorage in useState above (correct
  // on first paint, SSR-safe via readEdgeType's window guard). This effect only
  // re-reads when the storage key changes (fleet ↔ single-agent panel mode).
  useEffect(() => {
    setEdgeType(readEdgeType(edgeTypeKey));
  }, [edgeTypeKey]);

  // 'wave' has no dedicated fleet path renderer (FleetEdge falls back to bezier),
  // so treat it as the bezier ('default') style. Declared here so BOTH the layout
  // effect (fresh edges on refresh/data-load) and the live-change effect below
  // stamp the current style onto edges.
  const resolvedEdgeType: FleetEdgeType = edgeType === 'wave' ? 'default' : edgeType;

  // ─── Conditional data hooks ───
  const fleetState = useAgentFleetState({ skip: isSingleAgent });
  const singleState = useSingleAgentFleet(singleAgentId ?? '', { skip: !isSingleAgent || isSnapshotMode });
  const snapshotState = useSnapshotAgentFleet(isSnapshotMode ? snapshot : null);

  // Unify into common variables
  const activeState = isSnapshotMode ? snapshotState : isSingleAgent ? singleState : fleetState;
  const {
    nodes: rawNodes, edges: rawEdges, setNodes, setEdges,
    onNodesChange, onEdgesChange, isLoading,
    allNodesRaw, allEdgesRaw, collapsibleGroupIds, refetch,
  } = activeState;

  const onConnect = isSingleAgent ? undefined : fleetState.onConnect;

  const agents = isSnapshotMode
    ? (snapshotState.allAgents?.length > 0 ? snapshotState.allAgents : snapshotState.agent ? [snapshotState.agent] : [])
    : isSingleAgent
      ? (singleState.allAgents?.length > 0 ? singleState.allAgents : singleState.agent ? [singleState.agent] : [])
      : fleetState.agents;
  // Agents the edit picker can target. In single-agent mode this is the sub-tree
  // actually rendered (main + BFS-discovered children); in fleet mode it's all
  // fleet agents; in snapshot mode the inspector must be able to resolve any
  // sub-agent node clicked on the canvas, so we expose the full snapshot tree.
  const editableAgents = isSnapshotMode
    ? (snapshotState.allAgents?.length > 0 ? snapshotState.allAgents : snapshotState.agent ? [snapshotState.agent] : [])
    : isSingleAgent
      ? (singleState.visibleAgents?.length > 0
        ? singleState.visibleAgents
        : singleState.agent ? [singleState.agent] : [])
      : fleetState.agents;
  const skillsByAgent = isSnapshotMode ? snapshotState.skillsByAgent : isSingleAgent ? singleState.skillsByAgent : fleetState.skillsByAgent;
  const skillFolders = isSingleAgent ? undefined : fleetState.skillFolders;
  const resourcesById = isSnapshotMode ? snapshotState.resourcesById : isSingleAgent ? singleState.resourcesById : fleetState.resourcesById;

  // Fleet-only data for plan generator
  const workflowNames = isSingleAgent ? undefined : fleetState.workflowNames;
  const interfaceNames = isSingleAgent ? undefined : fleetState.interfaceNames;
  const dataSourceNames = isSingleAgent ? undefined : fleetState.dataSourceNames;

  // ─── Real-time activity: agent IDs for WebSocket subscriptions ───
  const agentIds = useMemo(() => agents.map(a => a.id), [agents]);

  // Real-time activity updates (running indicator, tool calls, completion) are handled
  // entirely via WebSocket → Zustand store - no full refetch needed on execution_completed.
  // Execution history panels have their own lightweight per-agent refresh.
  // Removed: full fetchAll() refetch on completionSeq change - it caused a heavy
  // graph re-layout (6+6N API calls, Dagre recompute, fitView animation) with no
  // structural change to justify it.

  // ── Collapse state ──
  const COLLAPSE_KEY = 'fleet-collapsed-groups';
  const [collapsedGroupIds, setCollapsedGroupIds] = useState<Set<string>>(() => {
    if (isSingleAgent || typeof window === 'undefined') return new Set();
    try {
      const stored = localStorage.getItem(COLLAPSE_KEY);
      return stored ? new Set(JSON.parse(stored)) : new Set();
    } catch { return new Set(); }
  });

  // Default = EXPAND ALL for both fleet and single-agent. The initial collapsedGroupIds
  // is empty for single-agent and seeded from localStorage for fleet, so there is no
  // auto-collapse: agents render with their resources expanded on first load. A user who
  // explicitly "collapse-all"s in the fleet view still has that choice persisted (the
  // localStorage seed restores it on reload); the toolbar toggle flips it back.

  // Toggle single group collapse
  const handleToggleCollapse = useCallback((nodeId: string) => {
    setCollapsedGroupIds(prev => {
      const next = new Set(prev);
      if (next.has(nodeId)) next.delete(nodeId);
      else next.add(nodeId);
      if (!isSingleAgent) {
        localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...next]));
      }
      return next;
    });
  }, [isSingleAgent]);

  // Toggle all collapsed/expanded
  const isAllCollapsed = collapsibleGroupIds.length > 0 && collapsibleGroupIds.every(id => collapsedGroupIds.has(id));
  const handleToggleCollapseAll = useCallback(() => {
    if (isAllCollapsed) {
      setCollapsedGroupIds(new Set());
      if (!isSingleAgent) localStorage.setItem(COLLAPSE_KEY, '[]');
    } else {
      const all = new Set(collapsibleGroupIds);
      setCollapsedGroupIds(all);
      if (!isSingleAgent) localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...all]));
    }
  }, [isAllCollapsed, collapsibleGroupIds, isSingleAgent]);

  // ── Edit mode (ON by default; forced off for read-only / snapshot) ──
  const canEdit = !readOnly && !isSnapshotMode;
  const [isEditMode, setIsEditMode] = useState(canEdit);
  useEffect(() => { if (!canEdit) setIsEditMode(false); }, [canEdit]);

  const sidePanel = useSidePanelSafe();
  const tEdit = useTranslations('fleetInspector');

  // Stable ref carrying the latest fleet data so the per-node/edge callbacks
  // injected into `data` keep stable identities (no layout-effect churn).
  const editRef = useRef({ agents, allNodesRaw, allEdgesRaw, refetch, sidePanel });
  editRef.current = { agents, allNodesRaw, allEdgesRaw, refetch, sidePanel };

  const [confirmState, setConfirmState] = useState<null | {
    title: string; description: string; confirmLabel: string; onConfirm: () => Promise<void>;
  }>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [toolsModal, setToolsModal] = useState<null | {
    agent: any; apiSlug: string; integrationName: string; iconSlug?: string;
  }>(null);
  const [hoveredEdgeId, setHoveredEdgeId] = useState<string | null>(null);

  const runConfirm = useCallback(async () => {
    if (!confirmState) return;
    setConfirmBusy(true);
    try {
      await confirmState.onConfirm();
      await editRef.current.refetch();
      setConfirmState(null);
    } catch (err) {
      console.error('[Fleet] edit action failed:', err);
    } finally {
      setConfirmBusy(false);
    }
  }, [confirmState]);

  // Build a per-type delete confirmation (description explains the update).
  const openDeleteConfirm = useCallback((agent: any, type: string | undefined, resourceId: string | undefined, name: string) => {
    if (!agent || !type) return;
    // "All tools" isn't a removable single tool - changing tool access is a mode change,
    // done from the agent modal (defensive; the canvas already suppresses its buttons).
    if (type === 'tool' && resourceId === 'all-tools') { openAgentEditor(agent, 3); return; }
    // No id → nothing to disconnect (container/aggregator nodes). Refusing here beats
    // showing a confirm dialog whose confirmation is a silent no-op.
    if (!isDisconnectableFleetResource(type, resourceId)) return;
    let description: string;
    let onConfirm: () => Promise<void>;
    if (type === 'web_search') {
      description = tEdit('confirmRemoveWebSearchDesc', { agent: agent.name });
      onConfirm = async () => { await disconnectFleetResource(agent.id, 'web_search', resourceId || ''); };
    } else if (type === 'sub_agent' || type === 'agent') {
      description = tEdit('confirmRemoveSubAgentDesc', { name, agent: agent.name });
      onConfirm = async () => { await disconnectSubAgent(agent.id, resourceId || ''); };
    } else if (type === 'tool') {
      description = tEdit('confirmRemoveToolDesc', { name, agent: agent.name });
      onConfirm = async () => { await disconnectFleetResource(agent.id, type, resourceId || ''); };
    } else {
      description = tEdit('confirmRemoveResourceDesc', { name, agent: agent.name });
      onConfirm = async () => { await disconnectFleetResource(agent.id, type, resourceId || ''); };
    }
    setConfirmState({ title: tEdit('confirmRemoveTitle'), description, confirmLabel: tEdit('removeAction'), onConfirm });
  }, [tEdit, openAgentEditor]);

  // Edit a node → node-type-specific modal/panel.
  const handleFleetEdit = useCallback((nodeId: string) => {
    const { agents, allNodesRaw, sidePanel: sp } = editRef.current;
    const parsed = parseNodeId(nodeId);
    const data: any = allNodesRaw.find((n) => n.id === nodeId)?.data;
    const agent = agents.find((a) => a.id === parsed.agentId);
    if (!agent) return;
    const rt = data?.fleetResourceType as string | undefined;
    if (parsed.category === 'agent' || rt === 'model') {
      // model → open on Configuration (where the model lives); agent → Basic Info.
      openAgentEditor(agent, rt === 'model' ? 2 : 1);
      return;
    }
    if (rt === 'tool') {
      // The "All tools" pseudo-chip can't be managed per-integration - open the
      // agent modal on Integration (tool access / mode).
      if (parsed.resourceId === 'all-tools') { openAgentEditor(agent, 3); return; }
      const apiSlug = data?.apiData?.apiSlug || String(parsed.resourceId || '').split(':')[0];
      if (!apiSlug) return;
      setToolsModal({ agent, apiSlug, integrationName: data?.apiData?.apiName || apiSlug, iconSlug: data?.apiData?.iconSlug });
      return;
    }
    if ((rt === 'workflow' || rt === 'table' || rt === 'interface') && sp && parsed.resourceId) {
      openFleetSidePanelTab(sp, { type: rt, resourceId: parsed.resourceId, label: data?.label || rt });
    }
  }, [openAgentEditor]);

  const handleFleetDelete = useCallback((nodeId: string) => {
    const { agents, allNodesRaw } = editRef.current;
    const parsed = parseNodeId(nodeId);
    const data: any = allNodesRaw.find((n) => n.id === nodeId)?.data;
    const agent = agents.find((a) => a.id === parsed.agentId);
    openDeleteConfirm(agent, data?.fleetResourceType, parsed.resourceId, data?.label || '');
  }, [openDeleteConfirm]);

  const handleFleetEdgeDelete = useCallback((edgeId: string) => {
    const { agents, allEdgesRaw, allNodesRaw } = editRef.current;
    const edge = allEdgesRaw.find((e) => e.id === edgeId);
    if (!edge) return;
    if ((edge.data as any)?.category === 'sub-agents') {
      const callerId = edge.source.replace('agent-', '');
      const calleeId = edge.target.replace('agent-', '');
      const caller = agents.find((a) => a.id === callerId);
      const callee = agents.find((a) => a.id === calleeId);
      if (!caller) return;
      setConfirmState({
        title: tEdit('confirmRemoveTitle'),
        description: tEdit('confirmRemoveSubAgentDesc', { name: callee?.name || calleeId, agent: caller.name }),
        confirmLabel: tEdit('removeAction'),
        onConfirm: async () => { await disconnectSubAgent(callerId, calleeId); },
      });
      return;
    }
    const parsed = parseNodeId(edge.target);
    const data: any = allNodesRaw.find((n) => n.id === edge.target)?.data;
    const agent = agents.find((a) => a.id === parsed.agentId);
    openDeleteConfirm(agent, data?.fleetResourceType, parsed.resourceId, data?.label || '');
  }, [tEdit, openDeleteConfirm]);

  const handleFleetEdgeEdit = useCallback((edgeId: string) => {
    const { agents, allEdgesRaw } = editRef.current;
    const edge = allEdgesRaw.find((e) => e.id === edgeId);
    if (!edge) return;
    const agent = agents.find((a) => a.id === edge.source.replace('agent-', ''));
    if (agent) openAgentEditor(agent, 2); // model edge → Configuration
  }, [openAgentEditor]);

  // Real edge-actions context (replaces the read-only no-op): FleetEdge reads
  // `hoveredEdgeId` to reveal its trash/edit button on the hovered edge.
  const edgeActionsValue = useMemo(() => ({
    hoveredEdgeId,
    onDeleteEdge: (id: string) => handleFleetEdgeDelete(id),
    onUpdateEdgeData: () => {},
  }), [hoveredEdgeId, handleFleetEdgeDelete]);

  // Apply collapse filtering + category filtering, layout, and inject callbacks
  useEffect(() => {
    if (allNodesRaw.length === 0) return;
    const { visibleNodes, visibleEdges } = filterCollapsedNodes(allNodesRaw, allEdgesRaw, collapsedGroupIds);

    // Filter edges by visible categories
    const filteredEdges = visibleEdges.filter(e => {
      const cat = (e.data as any)?.category as EdgeCategory | undefined;
      if (!cat) return true;
      return visibleCategories.has(cat);
    });

    // Also hide orphaned nodes (nodes that lost all edges due to category filter)
    const connectedNodeIds = new Set<string>();
    filteredEdges.forEach(e => { connectedNodeIds.add(e.source); connectedNodeIds.add(e.target); });
    const filteredNodes = visibleNodes.filter(n => {
      if (n.id.startsWith('agent-')) return true;
      return connectedNodeIds.has(n.id);
    });

    // Inject collapse state + (in edit mode) per-node edit callbacks into visible nodes.
    const editLabels = { edit: t('edit'), remove: tEdit('removeAction') };
    const injected = filteredNodes.map(n => {
      const d = n.data as any;
      const isCollapsible = d.fleetCollapsible;
      if (!isEditMode && !isCollapsible) return n;
      return {
        ...n,
        data: {
          ...d,
          ...(isCollapsible ? { fleetIsCollapsed: collapsedGroupIds.has(n.id), onToggleCollapse: handleToggleCollapse } : {}),
          ...(isEditMode
            ? {
                fleetEditMode: true,
                onFleetEdit: handleFleetEdit,
                onFleetDelete: handleFleetDelete,
                fleetEditLabels: editLabels,
              }
            : { fleetEditMode: false }),
        },
      };
    });

    // Stamp the current connection style onto freshly-built edges. Without this,
    // an async data load (which rebuilds edges with no pathType) would strip the
    // restored style on refresh and the live-change effect below would not re-run
    // (its only meaningful dep, resolvedEdgeType, is unchanged). resolvedEdgeType
    // is read via closure (intentionally NOT a dep - re-running a full layout on a
    // pure style change would re-fit the viewport).
    // In edit mode, also compute the per-edge action (model→edit, sub-agent / leaf
    // resource→delete) and inject the edge handlers.
    const styledEdges = filteredEdges.map(e => {
      const fleetEdgeAction = resolveFleetEdgeAction((e.data as any)?.category, e.target, isEditMode);
      return {
        ...e,
        type: 'fleet' as const,
        data: {
          ...e.data,
          pathType: resolvedEdgeType,
          fleetEditMode: isEditMode,
          fleetEdgeAction,
          onFleetEdgeDelete: handleFleetEdgeDelete,
          onFleetEdgeEdit: handleFleetEdgeEdit,
        },
      };
    });
    // Skip the Dagre re-layout + animated fitView when the visible graph is structurally
    // unchanged (a stats/activity update changed node DATA only). applyFleetLayoutCached
    // rebuilds from `injected` (the FILTERED set) at the canvas's own cached positions - so a
    // data-only update keeps the displayed subset (it can NEVER resurrect collapsed/filtered-out
    // nodes, which patching a prior nodes array would). fitView fires only on a real re-layout.
    const cached = applyFleetLayoutCached(injected as any, styledEdges, lastCanvasSigRef.current, lastCanvasPositionsRef.current);
    lastCanvasSigRef.current = cached.sig;
    lastCanvasPositionsRef.current = cached.positions;
    setNodes(cached.nodes);
    setEdges(styledEdges);
    if (cached.relaidOut) {
      setTimeout(() => instanceRef.current?.fitView({ padding: 0.2, duration: 300 }), 100);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [collapsedGroupIds, visibleCategories, allNodesRaw, allEdgesRaw, handleToggleCollapse, isEditMode]);

  // Apply edge path type to already-present edges on a live style change (the
  // layout effect above handles freshly-built edges on refresh/data-load).
  useEffect(() => {
    setEdges(eds => eds.map(e => {
      if (e.type === 'fleet' && (e.data as any)?.pathType === resolvedEdgeType) return e;
      return { ...e, type: 'fleet', data: { ...e.data, pathType: resolvedEdgeType } };
    }));
  }, [resolvedEdgeType, setEdges]);

  const handleEdgeTypeChange = useCallback((type: FleetEdgeType) => {
    setEdgeType(type);
    localStorage.setItem(edgeTypeKey, type);
  }, [edgeTypeKey]);

  const handleToggleCategory = useCallback((cat: EdgeCategory) => {
    setVisibleCategories(prev => {
      const next = new Set(prev);
      if (next.has(cat)) next.delete(cat);
      else next.add(cat);
      localStorage.setItem(edgeCategoryKey, JSON.stringify([...next]));
      return next;
    });
  }, [edgeCategoryKey]);

  const handleInit = useCallback((instance: ReactFlowInstance) => {
    instanceRef.current = instance;
    setTimeout(() => instance.fitView({ padding: 0.2 }), 100);
  }, []);

  const handleZoomIn = useCallback(() => {
    instanceRef.current?.zoomIn();
  }, []);

  const handleZoomOut = useCallback(() => {
    instanceRef.current?.zoomOut();
  }, []);

  const handleFitView = useCallback(() => {
    instanceRef.current?.fitView({ padding: 0.2 });
  }, []);

  const handleAutoLayout = useCallback(() => {
    const layoutedNodes = applyFleetLayout(rawNodes, rawEdges);
    setNodes(layoutedNodes);
    setTimeout(() => instanceRef.current?.fitView({ padding: 0.2, duration: 300 }), 100);
  }, [rawNodes, rawEdges, setNodes]);

  const handleToggleSettings = useCallback(() => {
    setIsSettingsOpen(prev => !prev);
  }, []);

  const handleRefresh = useCallback(async () => {
    setIsRefreshing(true);
    try {
      await refetch();
    } finally {
      setIsRefreshing(false);
    }
  }, [refetch]);

  // ─── Node selection: highlight connected edges ───
  useEffect(() => {
    setEdges(eds => eds.map(e => {
      const connected = selectedNodeId
        ? (e.source === selectedNodeId || e.target === selectedNodeId)
        : false;
      return e.selected === connected ? e : { ...e, selected: connected };
    }));
  }, [selectedNodeId, setEdges]);

  const handleNodeClick: NodeMouseHandler = useCallback((_event, node) => {
    setSelectedNodeId(node.id);
    setIsInspectorMinimized(false);
  }, []);

  // Double-click on a collapsible group node → toggle collapse (stop zoom)
  const handleNodeDoubleClick: NodeMouseHandler = useCallback((event, node) => {
    if ((node.data as any)?.fleetCollapsible) {
      event.stopPropagation();
      handleToggleCollapse(node.id);
    }
  }, [handleToggleCollapse]);

  const handlePaneClick = useCallback(() => {
    setSelectedNodeId(null);
  }, []);

  const handleCloseInspector = useCallback(() => {
    setSelectedNodeId(null);
  }, []);

  const selectedNode = useMemo(
    () => selectedNodeId ? rawNodes.find(n => n.id === selectedNodeId) ?? null : null,
    [selectedNodeId, rawNodes],
  );

  // Edit button click handler.
  // - Fleet mode → always open the agent picker.
  // - Single-agent mode with children visible in the panel → open the picker so
  //   the user can edit any agent in the sub-tree (main or a child).
  // - Single-agent mode with no children → jump straight to the modal for the
  //   main agent (preserves the old one-click flow for the common case).
  const handleEditClick = useCallback(() => {
    if (isSingleAgent && editableAgents.length <= 1) {
      const target = singleState.agent;
      if (target) openAgentEditor(target);
      return;
    }
    setIsAgentPickerOpen(true);
  }, [isSingleAgent, editableAgents.length, singleState.agent, openAgentEditor]);

  if (isLoading) {
    return (
      <div className="relative h-full w-full bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800 flex items-center justify-center">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  // Agent not found guard (single-agent mode only, skip for snapshot)
  if (isSingleAgent && !isSnapshotMode && !singleState.agent) {
    return (
      <div className="relative h-full w-full bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800 flex items-center justify-center">
        <span className="text-sm text-theme-secondary">Agent not found</span>
      </div>
    );
  }

  return (
    <WorkflowModeProvider readOnly>
      <StepByStepProvider
        isEnabled={false}
        isPaused={false}
        readySteps={EMPTY_SET}
        completedSteps={EMPTY_SET}
        failedSteps={EMPTY_SET}
        onExecuteStep={noopAsync}
      >
        <ValidationProvider nodes={[]} edges={[]}>
          <EdgeActionsProvider value={canEdit ? edgeActionsValue : NOOP_EDGE_ACTIONS}>
            <div ref={containerRef} className="relative h-full w-full bg-gradient-to-br from-slate-50 to-white dark:from-gray-900 dark:to-gray-800">
              {/* Inject CSS */}
              <style dangerouslySetInnerHTML={{ __html: CANVAS_STYLES }} />

              {/* Real-time agent activity WebSocket subscriptions (renders nothing visible) */}
              {!isSnapshotMode && <FleetActivitySubscriptions agentIds={agentIds} />}

              <ReactFlowProvider>
              <ReactFlow
                nodes={rawNodes}
                edges={rawEdges}
                nodeTypes={nodeTypes}
                edgeTypes={fleetEdgeTypes}
                nodesDraggable
                nodesConnectable={false}
                elementsSelectable
                // Virtualize: only mount nodes/edges inside the viewport. Keeps a large
                // fleet (many agents / sub-agents / resource chips) from putting every
                // node in the DOM at once, which made pan/zoom janky on big graphs.
                onlyRenderVisibleElements
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                onNodeClick={handleNodeClick}
                onNodeDoubleClick={handleNodeDoubleClick}
                onPaneClick={handlePaneClick}
                onEdgeMouseEnter={(_e, edge) => setHoveredEdgeId(edge.id)}
                onEdgeMouseLeave={() => setHoveredEdgeId(null)}
                minZoom={0.1}
                maxZoom={2}
                defaultViewport={{ x: 0, y: 0, zoom: 1 }}
                zoomOnDoubleClick
                zoomOnScroll
                selectNodesOnDrag={false}
                connectionLineComponent={CustomConnectionLine}
                connectionMode={ConnectionMode.Loose}
                onInit={handleInit}
                proOptions={{ hideAttribution: true }}
                panOnScroll
                panOnDrag
                className="h-full w-full [&_.react-flow__node:focus-visible]:outline [&_.react-flow__node:focus-visible]:outline-2 [&_.react-flow__node:focus-visible]:outline-black [&_.react-flow__node:focus-visible]:outline-offset-2"
                defaultEdgeOptions={{
                  type: 'fleet',
                  style: { strokeWidth: 1.6, stroke: 'var(--border-color)' },
                  animated: false,
                }}
              >
                <Background
                  id={backgroundPatternId}
                  color={isDark ? '#9ca3af' : '#cbd5e1'}
                  gap={25}
                  size={1.75}
                  variant={BackgroundVariant.Dots}
                />

                {/* SVG arrow markers */}
                <svg style={{ position: 'absolute', width: 0, height: 0 }}>
                  <defs>
                    {ARROW_MARKERS.map(({ id, color }) => (
                      <marker
                        key={id}
                        id={id}
                        viewBox="0 0 10 10"
                        refX="8"
                        refY="5"
                        markerWidth="5"
                        markerHeight="5"
                        orient="auto-start-reverse"
                      >
                        <path d="M 0 0 L 10 5 L 0 10 z" fill={color} />
                      </marker>
                    ))}
                  </defs>
                </svg>

                {/* Edit button (top-right) - hidden in snapshot/preview/read-only mode */}
                {!isSnapshotMode && !readOnly && !isSettingsOpen && !isAgentPickerOpen && (
                  <Button
                    onClick={handleEditClick}
                    className="absolute top-4 right-4 z-10 w-11 h-11 rounded-full p-0 shadow-none"
                    title={t('edit')}
                  >
                    <Pencil className="w-[18px] h-[18px]" />
                  </Button>
                )}

                {/* Agent picker panel - used in fleet mode and in single-agent
                    mode when the panel contains multiple agents (main + children). */}
                {isAgentPickerOpen && (
                  <Panel position="top-right" className={`m-4 relative z-[150] ${isSettingsOpen ? 'invisible pointer-events-none' : ''}`}>
                    <AgentPickerPanel
                      isOpen={isAgentPickerOpen}
                      onClose={() => setIsAgentPickerOpen(false)}
                      agents={editableAgents.map(a => ({
                        id: a.id,
                        name: a.name,
                        description: a.description,
                        avatarUrl: a.avatarUrl,
                        modelProvider: a.modelProvider,
                        modelName: a.modelName,
                      }))}
                      onSelectAgent={(agent) => {
                        const full = editableAgents.find(a => a.id === agent.id);
                        if (full) {
                          openAgentEditor(full);
                        }
                      }}
                    />
                  </Panel>
                )}

                {/* Settings Panel */}
                {isSettingsOpen && (
                  <Panel position="top-right" className="m-4 relative z-[200]">
                    <div className={`${isSingleAgent ? 'w-64' : 'w-72'} rounded-[32px] bg-white/80 dark:bg-gray-800/80 backdrop-blur overflow-hidden`}>
                      <div className="flex items-center justify-between px-5 pt-5 pb-3">
                        <span className="text-sm font-semibold text-slate-900 dark:text-slate-100">Settings</span>
                        <Button
                          onClick={() => setIsSettingsOpen(false)}
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8 p-0 rounded-full shadow-none border-0 focus-visible:ring-2 focus-visible:ring-theme-tertiary"
                          title="Close settings"
                        >
                          <X className="h-4 w-4" />
                        </Button>
                      </div>
                      <div className="px-5 pb-5 space-y-4">
                        {/* Connection style selector */}
                        <div className="space-y-2">
                          <span className="text-sm font-medium text-slate-700 dark:text-slate-300 block">
                            Connection Style
                          </span>
                          <ConnectionTypeSelector
                            value={toConnectionType(edgeType)}
                            onChange={(t) => handleEdgeTypeChange(fromConnectionType(t))}
                          />
                        </div>

                        <div className="h-px bg-slate-200 dark:bg-slate-700" />

                        {/* Connection type visibility */}
                        <div className="space-y-2">
                          <span className="text-sm font-medium text-slate-700 dark:text-slate-300 block">
                            Connection Types
                          </span>
                          <div className="flex flex-wrap gap-1.5">
                            {activeEdgeCategories.map((cat) => (
                              <button
                                key={cat}
                                onClick={() => handleToggleCategory(cat)}
                                className={`px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                                  visibleCategories.has(cat)
                                    ? 'bg-slate-900 text-white dark:bg-white dark:text-slate-900'
                                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-700 dark:text-slate-300 dark:hover:bg-slate-600'
                                }`}
                              >
                                {EDGE_CATEGORY_LABELS[cat]}
                              </button>
                            ))}
                          </div>
                        </div>

                        {/* Fleet Plan Generator (fleet mode only) */}
                        {!isSingleAgent && workflowNames && interfaceNames && dataSourceNames && (
                          <>
                            <div className="h-px bg-slate-200 dark:bg-slate-700" />
                            <span className="text-sm font-medium text-slate-700 dark:text-slate-300 block">
                              Developer Tools
                            </span>
                            <FleetPlanGenerator
                              agents={agents}
                              skillsByAgent={skillsByAgent}
                              workflowNames={workflowNames}
                              interfaceNames={interfaceNames}
                              dataSourceNames={dataSourceNames}
                            />
                          </>
                        )}
                      </div>
                    </div>
                  </Panel>
                )}

                <FleetToolbar
                  onZoomIn={handleZoomIn}
                  onZoomOut={handleZoomOut}
                  onFitView={handleFitView}
                  onAutoLayout={handleAutoLayout}
                  onToggleSettings={handleToggleSettings}
                  isSettingsOpen={isSettingsOpen}
                  onToggleCollapseAll={handleToggleCollapseAll}
                  isAllCollapsed={isAllCollapsed}
                  hasCollapsible={collapsibleGroupIds.length > 0}
                  showEditToggle={canEdit}
                  isEditMode={isEditMode}
                  onToggleEditMode={() => setIsEditMode(v => !v)}
                  editLabel={tEdit('editMode')}
                />

              </ReactFlow>

              {/* Fleet Inspector Panel - absolute inside container to stay within canvas bounds */}
              {selectedNode && (
                <div
                  className="absolute z-[60] pointer-events-none"
                  style={{ left: inspectorPosition.x, top: inspectorPosition.y }}
                  onClick={(e) => e.stopPropagation()}
                >
                  <div
                    className="pointer-events-auto"
                    onClick={(e) => e.stopPropagation()}
                    onMouseDown={(e) => e.stopPropagation()}
                    onMouseUp={(e) => e.stopPropagation()}
                  >
                    <FleetInspectorPanel
                      node={selectedNode}
                      allNodes={allNodesRaw.length > 0 ? allNodesRaw : rawNodes}
                      agents={agents}
                      skillsByAgent={skillsByAgent}
                      skillFolders={skillFolders}
                      resourcesById={resourcesById}
                      onClose={handleCloseInspector}
                      onDragHandleMouseDown={handleInspectorDragStart}
                      isMinimized={isInspectorMinimized}
                      onMinimizedChange={setIsInspectorMinimized}
                      availableWidth={containerSize.width - inspectorPosition.x}
                      onRefresh={handleRefresh}
                    />
                  </div>
                </div>
              )}
              </ReactFlowProvider>
            </div>
          </EdgeActionsProvider>
        </ValidationProvider>
      </StepByStepProvider>

      {/* Edit agent modal - unified across fleet, single, and single-with-children.
          Fleet mode: editingAgent is set by the picker. Single mode with children:
          same picker flow. Single mode without children: handleEditClick sets
          editingAgent directly to the main agent, skipping the picker. */}
      {!readOnly && editingAgent && (
        <CreateAgentModal
          agent={editingAgent as any}
          initialStep={editingAgentStep}
          onClose={() => setEditingAgent(null)}
          onAgentCreated={() => {
            setEditingAgent(null);
            refetch();
          }}
        />
      )}

      {/* Delete confirmation - explains exactly what will be updated. */}
      {confirmState && (
        <ConfirmDeleteModal
          isOpen
          title={confirmState.title}
          message={confirmState.description}
          confirmLabel={confirmState.confirmLabel}
          isLoading={confirmBusy}
          onConfirm={runConfirm}
          onCancel={() => { if (!confirmBusy) setConfirmState(null); }}
        />
      )}

      {/* Manage which tools of an integration the agent can use. */}
      {toolsModal && (
        <AgentIntegrationToolsModal
          agent={toolsModal.agent}
          apiSlug={toolsModal.apiSlug}
          integrationName={toolsModal.integrationName}
          iconSlug={toolsModal.iconSlug}
          onClose={() => setToolsModal(null)}
          onSaved={() => refetch()}
        />
      )}
    </WorkflowModeProvider>
  );
}
