'use client';

import clsx from 'clsx';
import * as React from 'react';
import { X, GripVertical, Minimize2, Maximize2, Maximize, Copy, Trash2, Eye, Play, RotateCcw } from 'lucide-react';
import { ApiListSkeleton, ToolListSkeleton, ToolDetailsSkeleton } from './SkeletonLoaders';
import type { Node, Edge } from 'reactflow';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useQueryClient } from '@tanstack/react-query';
// MCP data hooks are now managed by useInspectorMcpData and useInspectorToolDetails
import { useDataSources, useDataSourceTables, useDataSourceColumns, DataSource, DataSourceTable } from '../hooks/useDataSourceData';
import { useWorkflows } from '../hooks/useWorkflowsData';

import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Textarea } from '@/components/ui/textarea';
import { Input } from '@/components/ui/input';
import { Breadcrumb } from '@/components/ui/breadcrumb';
import { ExpressionEditor } from '@/components/ui/expression-editor';
import { ParameterColumn } from './inspector/ParameterColumn';
import { OutputColumn } from './inspector/OutputColumn';
import { OptionalSection } from './inspector/OptionalSection';
import { useInspectorConnections } from './inspector/useInspectorConnections';
import { InspectorConnections } from './inspector/InspectorConnections';
import { useInspectorExpressions } from './inspector/useInspectorExpressions';
import { useInspectorConditions } from './inspector/useInspectorConditions';
import { useInspectorSwitchCases } from './inspector/useInspectorSwitchCases';
import { useInspectorOptionChoices } from './inspector/useInspectorOptionChoices';
import { useInspectorApprovalOutputs } from './inspector/useInspectorApprovalOutputs';
import { useInspectorNodeMeta } from './inspector/useInspectorNodeMeta';
import { useInspectorMcpData } from './inspector/useInspectorMcpData';
import { useInspectorNavigation } from './inspector/useInspectorNavigation';
import { useInspectorNavigationHandlers } from './inspector/useInspectorNavigationHandlers';
import { useInspectorToolDetails } from './inspector/useInspectorToolDetails';
import { AI_TYPES, CORE_LOGIC_TYPES, CORE_DIRECT_TYPES, TRIGGER_TYPES } from './inspector/nodeTypes';
import { InspectorMultiSelection } from './inspector/InspectorMultiSelection';
import { useInspectorViewMode } from './inspector/useInspectorViewMode';
import {
  shouldConstrainPanelToContainer,
  shouldForceCompactPanel,
  shouldRenderMinimizedPill,
  shouldUseTabbedLayout,
  useInspectorLayout,
} from './inspector/useInspectorLayout';
import { useInspectorValidation } from './inspector/useInspectorValidation';
import { useDataSourceColumnsInit } from './inspector/useDataSourceColumnsInit';
import type { ConnectionPropsBundle } from './inspector/types/connectionProps';
import { InspectorPanelHeader } from './inspector/InspectorPanelHeader';
import { ApprovalReviewBar } from './inspector/ApprovalReviewBar';
import { nodeRegistry } from '../registry/nodeRegistry';
import { useApprovalReviewLayout } from './inspector/useApprovalReviewLayout';
import { InspectorMobileContent } from './inspector/InspectorMobileContent';
import { InspectorDesktopContent } from './inspector/InspectorDesktopContent';
import { inspectorGeometryClass } from './inspector/inspectorGeometry';
import { extractStepAliasFromNode } from '../services/idMatcherUtils';
import { isReviewTargetForNode, useApprovalReviewTarget } from '../services/approvalReviewStore';
import { normalizeLabel } from '../utils/labelNormalizer';
import { InterfaceMappingsColumn } from './inspector/InterfaceMappingsColumn';
import { PreviewColumn } from './inspector/PreviewColumn';

import { getNodeVisual } from '../data/nodeVisuals';
import { getIconSlug, NodeIcon } from './nodes/shared';
import type { BuilderNodeData, ConditionRow, ConditionType } from '../types';
import { createDefaultDecisionConditions } from '../types';
import { findNodeClassById } from '../nodes/nodeClasses';
import { NOTE_COLORS } from './nodes/NoteNode';
import type { ConnectionType } from './ConnectionTypeSelector';
import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';
import { buildNodeReportHref } from '../utils/nodeReportLink';
import { useNodeExecutionStatus, useStepByStep } from '../contexts/StepByStepContext';
import { InspectorFooter } from './inspector/InspectorFooter';
import { useWorkflowLogsSidePanel } from '@/components/workflow/useWorkflowLogsSidePanel';

interface InspectorPanelProps {
  node: Node<BuilderNodeData> | null;
  selectedNodeIds?: string[];
  onUpdate: (data: BuilderNodeData) => void;
  onClose?: () => void;
  isAdvanced?: boolean;
  onAdvancedChange?: (advanced: boolean) => void;
  isFullscreen?: boolean;
  onFullscreenChange?: (fullscreen: boolean) => void;
  onDeleteNode?: (nodeId: string) => void;
  onDuplicateNode?: (nodeId: string) => void;
  onUndo?: () => void;
  canUndo?: boolean;
  connectionType?: ConnectionType;
  onConnectionTypeChange?: (type: ConnectionType) => void;
  allNodes?: Node<BuilderNodeData>[];
  edges?: Edge[];
  onSelectNode?: (nodeId: string | any, loopId?: string) => void; // Accepts string or palette data object, and optional loopId
  runId?: string; // Run ID for fetching step data
  workflowId?: string; // Workflow ID for building output links
  onDragHandleMouseDown?: (e: React.MouseEvent) => void; // Handler pour le drag du panel
  webhookTokens?: Record<string, string>; // Map of triggerId -> token for multi-DAG webhook support
  isMinimized?: boolean; // Controlled minimized state (lifted to parent to survive unmount)
  onMinimizedChange?: (minimized: boolean) => void;
  containerSize?: { width: number; height: number }; // Canvas container size for adaptive sizing
  /**
   * Where the panel is being rendered.
   *
   * `floating` (default) is the historical draggable window over the canvas.
   * `panel` means it was portalled into the side panel's Inspector sub-tab: the
   * host owns the geometry, so the panel fills it and drops the affordances that
   * only make sense while floating (drag, minimize-to-pill).
   */
  dockMode?: 'floating' | 'panel';
}

// Connection and HandlePosition types moved to useInspectorConnections


export function InspectorPanel({ node, selectedNodeIds = [], onUpdate, onClose, isAdvanced: isAdvancedRequested = false, onAdvancedChange, isFullscreen = false, onFullscreenChange, onDeleteNode, onDuplicateNode, onUndo, canUndo = false, connectionType = 'bezier', allNodes = [], edges = [], onSelectNode, runId: propRunId, workflowId, onDragHandleMouseDown, webhookTokens, isMinimized = false, onMinimizedChange, containerSize, dockMode = 'floating' }: InspectorPanelProps) {
  const { isRunMode, isPreviewOnly, runId: contextRunId, pinnedVersion } = useWorkflowMode();
  // Use context runId if available (set after workflow execution), fallback to prop
  const runId = contextRunId || propRunId;
  const isMultiSelection = selectedNodeIds.length > 1;
  const { openWorkflowLogs } = useWorkflowLogsSidePanel();
  // Docked into the side panel: the host sizes us, and drag / minimize-to-pill
  // are meaningless there (the panel has its own resize handle and close button).
  const isDocked = dockMode === 'panel';

  // Step-by-step context for runtime parameter editing
  const stepByStepContext = useStepByStep();
  const isStepByStepMode = stepByStepContext?.isStepByStepMode ?? false;
  const isPaused = stepByStepContext?.isPaused ?? false;
  const isExecutingStep = stepByStepContext?.isExecutingStep != null;

  // Allow runtime editing in step-by-step mode OR when paused, but not while executing
  // This enables users to modify parameters before rerunning a step
  const allowRuntimeEdit = (isStepByStepMode || isPaused) && !isExecutingStep;

  // Current run's plan version - needed to distinguish "run on the pinned
  // version" (immutable) from "run on an older/newer version of a pinned
  // workflow" (still editable, e.g. v3 while pin is v1).
  const [runStateForPin] = useRun(runId ?? undefined);
  const runPlanVersion: number | null = runStateForPin?.rawRunState?.planVersion ?? null;

  // Runs that target the pinned version are immutable: any edit made in the
  // inspector would be ignored by the backend (plan is frozen to pinned).
  // Runs on a different version of a pinned workflow stay editable.
  const isOnPinnedRun =
    isRunMode && pinnedVersion != null && runPlanVersion != null && runPlanVersion === pinnedVersion;

  // Effective run mode for forms (false when runtime editing is allowed)
  // This enables editing parameters in step-by-step mode while preserving
  // other isRunMode-dependent behaviors.
  // Forced readonly when: marketplace preview, or run targets a pinned workflow.
  const effectiveRunModeForForms = isPreviewOnly || isOnPinnedRun;

  // Step-by-step execution status for the current node
  const stepByStepStatus = useNodeExecutionStatus(node?.id || '', {
    label: node?.data?.label,
    kind: node?.data?.kind,
    // While one epoch is focused this is THAT epoch's outcome - what the run controls
    // must speak about there (the context's own sets accumulate across every epoch).
    status: node?.data?.status,
  });

  // Layout management (columns, resize, mobile detection)
  // The REQUESTED value, because `shouldForceSmallMode` needs node-kind predicates that are
  // not computed until further down. The column widths this hook manages are only read by
  // the advanced layout, which a forced-small node does not render, so that half is a
  // no-op - but the hook ALSO owns `activeTab`, and its only reset to 'parameter' hangs off
  // this same flag. See `activeTabForNode` below, which is what now covers that.
  const { columns, resize, isMobile: isWindowMobile, isNarrowPanel, measurePanel, activeTab, setActiveTab } = useInspectorLayout({ isAdvanced: isAdvancedRequested, isFullscreen });
  const { inputCollapsed, setInputCollapsed, outputCollapsed, setOutputCollapsed, inputWidth, outputWidth } = columns;
  const { handleInputResizeStart, handleOutputResizeStart, isResizingInput, isResizingOutput } = resize;

  // Approval review layout: when an item review is requested for THIS node,
  // collapse the output column and expand + widen the input column. Runs
  // after useInspectorLayout's own isAdvanced-expansion effect (registered
  // above) so the collapse wins within the same commit.
  useApprovalReviewLayout({
    nodeId: node?.id,
    setOutputCollapsed,
    setInputCollapsed,
    inputWidth: columns.inputWidth,
    setInputWidth: columns.setInputWidth,
  });

  // Execution-data view management
  const isInterfaceNodeEarly = node?.data?.kind === 'interface';
  // A node "has run data" when it carries non-empty statusCounts for the current run.
  // Nodes that never executed open the inspector in Configuration view, not Run data.
  // An approval under review is the one case where "this node has no rows yet" must
  // NOT fall back to Configuration: the node is parked on its signal, so it has no
  // statusCounts of its own, and what the reviewer needs to read is the INPUT of the
  // item being decided (the parent's rows, jumped to that epoch/itemIndex). Opening in
  // Configuration hid the input navigator entirely, so the review flow showed the
  // variable picker instead of the item's data.
  const inspectorReviewTarget = useApprovalReviewTarget();
  const isNodeUnderApprovalReview = isReviewTargetForNode(inspectorReviewTarget, node?.id);
  // A node EXECUTING right now, or parked on a signal, also opens on the run
  // view: it has no rows yet, but that view is the one that says so (and shows
  // the parameters it was launched with). Opening it on the configuration form
  // instead reported nothing at all about the live state.
  const nodeHasRunData =
    (!!node?.data?.statusCounts && Object.keys(node.data.statusCounts).length > 0)
    || isNodeUnderApprovalReview
    || stepByStepStatus.isRunning
    || stepByStepStatus.isAwaitingSignal;
  const { showExecutionData, handleShowExecutionDataChange } = useInspectorViewMode({
    isRunMode,
    runId,
    isInterfaceNode: isInterfaceNodeEarly,
    nodeId: node?.id,
    nodeHasRunData,
  });

  // Panel ref for connections hook
  const panelRef = React.useRef<HTMLDivElement>(null);

  // One ref for two consumers: the connections hook reads the element through
  // panelRef, the layout hook measures it. Stable so React does not detach and
  // re-attach (and so re-create the ResizeObserver) on every render.
  const setPanelRef = React.useCallback(
    (element: HTMLDivElement | null) => {
      panelRef.current = element;
      measurePanel(element);
    },
    [measurePanel],
  );

  // Use connections hook to manage all connection logic
  const {
    connections,
    setConnections,
    draggingFromHandle,
    mousePosition,
    hoveredTargetHandle,
    dragStartPosition,
    handlePositions,
    handleRefs,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleHandleClick,
    handleSetHandleRef,
    getParamConnection,
    handleInputParamConnection,
    handleDeleteConnection,
    isValidConnection,
    handleCreateConnection,
  } = useInspectorConnections({
    node,
    connectionType,
    allNodes,
    onUpdate,
    panelRef,
  });

  // Préparer les données du nœud
  const data = node?.data;

  // Use expressions hook to manage all expression logic
  const {
    extractVariables,
    getParamExpression,
    getToolParamExpression,
    getConditionExpression,
    getLoopConditionExpression,
    getListExpression,
    getColumnExpression,
    getColumnLabel,
    findUnknownVariables,
    createConnectionsForVariables,
    handleParamExpressionChange,
    handleToolParamExpressionChange,
    handleConditionExpressionChange,
    handleLoopConditionExpressionChange,
    handleListExpressionChange,
    handleColumnExpressionChange,
    handleColumnLabelChange,
    handleDeleteColumn,
    handleAddColumn,
    // Interface editor expression
    getEditorExpression,
    handleEditorExpressionChange,
  } = useInspectorExpressions({
    node,
    data,
    allNodes,
    connectionType,
    onUpdate,
    setConnections,
  });

  // Use conditions hook to manage all condition logic
  const {
    currentConditions,
    getConditionHandleId,
    handleUpdateConditions,
    handleAddCondition,
    handleDeleteCondition,
    handleRenameCondition,
  } = useInspectorConditions({
    node,
    data,
    onUpdate,
    setConnections,
  });

  // Use switch cases hook to manage all switch case logic
  const {
    currentCases,
    switchExpression,
    getCaseHandleId,
    getCaseValue,
    handleSwitchExpressionChange,
    handleAddCase,
    handleDeleteCase,
    handleRenameCase,
    handleCaseValueChange,
  } = useInspectorSwitchCases({
    node,
    data,
    onUpdate,
    setConnections,
  });

  // Use option choices hook to manage option node choices
  const {
    currentChoices: optionChoices,
    handleAddChoice: handleAddOptionChoice,
    handleDeleteChoice: handleDeleteOptionChoice,
    handleRenameChoice: handleRenameOptionChoice,
    handleExpressionChange: handleOptionExpressionChange,
  } = useInspectorOptionChoices({
    node,
    data,
    onUpdate,
    setConnections,
  });

  // Use approval outputs hook to manage user approval node outputs
  const {
    currentOutputs: approvalOutputs,
    handleAddOutput: handleAddApprovalOutput,
    handleDeleteOutput: handleDeleteApprovalOutput,
    handleRenameOutput: handleRenameApprovalOutput,
    handleTimeoutChange: handleApprovalTimeoutChange,
    handleContextTemplateChange: handleApprovalContextTemplateChange,
    handleContinuationModeChange: handleApprovalContinuationModeChange,
    handleDelegationChange: handleApprovalDelegationChange,
    approvalTimeoutMs,
    approvalContextTemplate,
    approvalContinuationMode,
    approvalDelegation,
  } = useInspectorApprovalOutputs({
    node,
    data,
    onUpdate,
    setConnections,
  });

  // Use node meta hook to get all node type detection
  const nodeMeta = useInspectorNodeMeta(node);

  // Destructure for easier access
  const {
    isApiNode,
    isToolNode,
    isMcpGenericNode,
    isMcpNode,
    isTablesTrigger,
    isWorkflowsTrigger,
    isTriggerGenericNode,
    isGenericEntryTrigger,
    isTriggerNode,
    isAiAgent,
    isAiSummarize,
    isGuardrail,
    isClassify,
    isAiNode,
    isCoreGenericNode,
    isLogicSubcategory,
    isHttpRequest,
    isWebhook,
    isIfElse,
    isSwitch,
    isUserApproval,
    isWhile,
    isTransform,
    isMerge,
    isWait,
    isInterfaceNode,
    nodeId,
    nodeKind,
  } = nodeMeta;

  // Use navigation hook for triggers, AI, and Core
  const {
    triggerNavigationLevel,
    setTriggerNavigationLevel,
    triggerSelectedType,
    setTriggerSelectedType,
    triggerSearchQuery,
    setTriggerSearchQuery,
    selectedDataSourceId,
    setSelectedDataSourceId,
    aiNavigationLevel,
    setAiNavigationLevel,
    aiSelectedType,
    setAiSelectedType,
    aiSearchQuery,
    setAiSearchQuery,
    coreNavigationLevel,
    setCoreNavigationLevel,
    coreSelectedType,
    setCoreSelectedType,
    coreSearchQuery,
    setCoreSearchQuery,
  } = useInspectorNavigation({
    node,
    meta: nodeMeta,
  });

  // Use MCP data hook
  const mcpData = useInspectorMcpData({
    node,
    isMcpNode,
    isApiNode,
    isToolNode,
    isMcpGenericNode,
  });

  // Destructure MCP data for easier access
  const {
    mcpNavigationLevel,
    setMcpNavigationLevel,
    mcpSelectedApiSlug,
    setMcpSelectedApiSlug,
    mcpSearchQuery,
    setMcpSearchQuery,
    mcpApis,
    mcpApiTools,
    mcpLoadingApis,
    mcpLoadingTools,
    apiInitialLoading,
    toolInitialLoading,
    apiHasMore,
    toolHasMore,
    apiLoadMoreRef,
    toolLoadMoreRef,
    setToolPage,
    shouldLoadApis,
  } = mcpData;

  // Use navigation handlers hook
  const {
    handleTriggerTypeClick,
    handleTriggerSelect,
    handleDataSourceSelect,
    handleTableSelect,
    handleWorkflowSelect,
    handleTriggerBackFromTables,
    handleTriggerBackFromDataSources,
    handleTriggerBackFromWorkflows,
    handleAiSelect,
    handleAiTypeClick,
    handleCoreLogicClick,
    handleCoreSelect,
    handleCoreTypeClick,
    handleMcpApiClick,
    handleMcpBackClick,
    handleMcpToolSelect,
  } = useInspectorNavigationHandlers({
    node,
    onUpdate,
    onSelectNode,
    setTriggerNavigationLevel,
    setTriggerSelectedType,
    setTriggerSearchQuery,
    setSelectedDataSourceId,
    setAiSelectedType,
    setAiNavigationLevel,
    setAiSearchQuery,
    setCoreNavigationLevel,
    setCoreSelectedType,
    setCoreSearchQuery,
    setMcpSelectedApiSlug,
    setMcpNavigationLevel,
    setMcpSearchQuery,
    setToolPage,
    mcpApis,
    mcpSelectedApiSlug,
    isMcpNode,
  });
  const dataSourceData = (node?.data as any)?.dataSourceData;
  const isTableSelected = isTablesTrigger && dataSourceData?.dataSourceId && dataSourceData?.tableName;
  // The one screen that still needs the panel to itself: the MCP api/tool picker.
  // See shouldForceCompactPanel for what this gate used to cover and why none of it is left.
  const shouldForceSmallMode = shouldForceCompactPanel({ isApiNode, isMcpGenericNode, isToolNode });

  // What this inspector actually renders. An MCP node still choosing its api and tool
  // has no three-column view to show, so it stays compact.
  //
  // Resolved HERE rather than by calling `onAdvancedChange(false)`, which is what it used
  // to do, and that was a per-NODE constraint writing a per-SESSION value: the flag it
  // reset is the builder's, shared by every node. Opening one unconfigured node therefore
  // turned advanced mode off for the whole session, and since nothing restores it until
  // the selection empties, every node clicked after it opened compact too. Invisible while
  // the session default was a hardcoded `false`; with a user preference behind that
  // default it is the preference quietly ceasing to apply, which is the shape of bug that
  // gets reported as "it works sometimes".
  const isAdvanced = isAdvancedRequested && !shouldForceSmallMode;

  // The tabbed layout is used when the WINDOW is narrow (as before) and also
  // when the PANEL is: docked into a side panel the user dragged in, the
  // three-column layout no longer fits and used to scroll sideways, hiding the
  // Output column off-screen.
  //
  // CONTENT ONLY. The panel-derived half of this flag must never reach the
  // panel's own geometry: `maxWidth` and the minimized pill stay gated on
  // `isWindowMobile`, because a style that constrains the panel cannot also be
  // switched off by "the panel is constrained" without oscillating.
  const isMobile = shouldUseTabbedLayout({ isWindowMobile, isNarrowPanel, isAdvanced, isFullscreen });

  // A forced-small node offers ONE tab, so the active one has to be it.
  //
  // DERIVED, not synced, for the same reason `isAdvanced` above is: an effect would leave
  // the first render of the forced-small node pointing at a tab that is not mounted, which
  // is one frame of a blank inspector - and this is exactly the frame the whole problem is
  // made of. It used to be handled by accident: forcing the requested flag to false fired
  // useInspectorLayout's `else` branch, whose single job is that reset. Leaving the flag
  // alone removed the accident, so the rule is stated here instead.
  //
  // The stored `activeTab` is deliberately NOT overwritten: a user who was reading Output
  // on a normal node, glances at a forced-small one and comes back, finds Output again.
  const activeTabForNode = shouldForceSmallMode ? 'parameter' : activeTab;

  // Prevent fullscreen for a node still on the MCP api/tool picker: a one-column
  // picker has nothing to fill a full screen with.
  React.useEffect(() => {
    if (shouldForceSmallMode && isFullscreen && onFullscreenChange) {
      onFullscreenChange(false);
    }
  }, [shouldForceSmallMode, isFullscreen, onFullscreenChange]);

  const [showOptionalParams, setShowOptionalParams] = React.useState(false);

  // Use tool details hook
  const {
    toolSlug,
    toolDetails,
    loadingToolDetails,
    toolParameters,
    toolResponses,
    toolCredentials,
  } = useInspectorToolDetails({
    node,
    isToolNode,
    onUpdate,
  });


  // Validation management
  const { nodeValidation, hasGlobalValidationErrors, backendErrors } = useInspectorValidation({
    node,
    toolParameters,
  });

  // State for footer collapse
  const [isFooterCollapsed, setIsFooterCollapsed] = React.useState(true);
  // Reset footer collapse state when node changes
  React.useEffect(() => {
    setIsFooterCollapsed(true);
  }, [node?.id]);


  const queryClient = useQueryClient();

  // Hooks for DataSources - only load when needed (tables trigger navigation)
  const shouldLoadDataSources = isTriggerNode && (triggerNavigationLevel === 'datasources' || triggerNavigationLevel === 'tables' || isTablesTrigger);
  const { data: dataSources = [], isLoading: isLoadingDataSources } = useDataSources(shouldLoadDataSources);
  const { data: dataSourceTables = [], isLoading: isLoadingTables } = useDataSourceTables(selectedDataSourceId);

  // Hooks for Workflows - only load when needed (workflows trigger navigation)
  const shouldLoadWorkflows = isTriggerNode && (triggerNavigationLevel === 'workflows' || isWorkflowsTrigger);
  const { data: workflows = [], isLoading: isLoadingWorkflows } = useWorkflows(shouldLoadWorkflows);

  // Hook for datasource columns - always call to avoid hook order issues, but only enable when needed
  const dataSourceDataForColumns = (data as any)?.dataSourceData;
  const isTablesTriggerForColumns = data?.id?.startsWith('tables-trigger-') || !!dataSourceDataForColumns;
  const dataSourceIdForColumns = dataSourceDataForColumns?.dataSourceId;
  const { data: columnsForSmallMode, isLoading: isLoadingColumnsForSmallMode } = useDataSourceColumns(
    isTablesTriggerForColumns && dataSourceIdForColumns && !isMcpNode ? dataSourceIdForColumns : null
  );

  // Initialize default column expressions when columns are loaded
  useDataSourceColumnsInit({
    node,
    columns: columnsForSmallMode,
    isTablesTrigger: isTablesTriggerForColumns,
    dataSourceId: dataSourceIdForColumns,
    isMcpNode,
    onUpdate,
  });

  // Reset optional params visibility when node changes
  React.useEffect(() => {
    setShowOptionalParams(false);
  }, [node?.id]);

  // Navigation initialization is handled by useInspectorNavigation hook

  // Préparer les données du nœud (avec valeurs par défaut si node est null)
  // data is already declared above for useInspectorExpressions and useInspectorConditions hooks
  const visuals = data ? getNodeVisual(data.kind) : { iconBg: '' };

  // Get node class to determine family (for centralized icon determination)
  // For plan-loaded nodes, data.id is prefixed (e.g. "core:wait_for_all") which findNodeClassById can't resolve.
  // Fall back to node.type detection for merge/wait nodes.
  const nodeClass = React.useMemo(() => data ? findNodeClassById(data.id || '') : null, [data?.id]);
  const nodeFamily = nodeClass?.family ?? (isMerge || isWait ? 'core' : undefined);
  const effectiveNodeKind = isMerge ? 'merge' : isWait ? 'wait' : nodeKind;

  // Liste des paramètres disponibles pour les sélecteurs
  const availableParams = React.useMemo(() => {
    if (!node || !data) return [];
    const isAgent = data.id === 'agent' ||
      data.id?.startsWith('ai-agent') ||
      data.id?.startsWith('agent-') ||
      data.label?.toLowerCase().includes('agent');
    if (isAgent) {
      return ['prompt', 'model', 'temperature', 'maxTokens'];
    }
    return [];
  }, [node, data]);

  // Connection props bundle for FormTypeRouter
  const connectionPropsBundle = React.useMemo<ConnectionPropsBundle>(() => ({
    connections,
    draggingFromHandle,
    hoveredTargetHandle,
    handleHandleClick,
    handleHandleMouseDown,
    handleHandleMouseUp,
    handleSetHandleRef,
    findUnknownVariables,
  }), [connections, draggingFromHandle, hoveredTargetHandle, handleHandleClick, handleHandleMouseDown, handleHandleMouseUp, handleSetHandleRef, findUnknownVariables]);

  // Toggle optional params handler (memoized)
  const handleToggleOptionalParams = React.useCallback(() => {
    setShowOptionalParams(prev => !prev);
  }, []);

  // Report-a-problem: opens the pre-filled contact ticket (node + plan context)
  // in a new tab. Built lazily on click so the plan summary isn't computed on
  // every render. Available in both edit and run mode.
  const reportT = useTranslations('workflowBuilder.inspector.report');
  const handleReportNode = React.useCallback(() => {
    if (!node) return;
    const href = buildNodeReportHref(
      { node, workflowId, runId, isRunMode, allNodes, edges },
      reportT,
    );
    if (typeof window !== 'undefined') {
      window.open(href, '_blank', 'noopener,noreferrer');
    }
  }, [node, workflowId, runId, isRunMode, allNodes, edges, reportT]);

  const handleOpenNodeLogs = React.useCallback(() => {
    if (!node || !workflowId || !runId) return;
    openWorkflowLogs({
      workflowId,
      runId,
      initialStepAlias: extractStepAliasFromNode(node) || undefined,
      reuseActiveWorkflowTab: true,
    });
  }, [node, openWorkflowLogs, runId, workflowId]);

  // Expression and condition management moved to useInspectorExpressions and useInspectorConditions hooks

  // Si pas de nœud et pas de multi-sélection, ne rien afficher
  if (!node && !isMultiSelection) {
    return null;
  }

  if (!node || isMultiSelection) {
    return (
      <InspectorMultiSelection
        selectedNodeIds={selectedNodeIds}
        isAdvanced={isAdvanced}
        onDeleteNode={isRunMode ? undefined : onDeleteNode}
        onDuplicateNode={isRunMode ? undefined : onDuplicateNode}
        onUndo={onUndo}
        canUndo={canUndo}
        onClose={onClose}
      />
    );
  }

  // À ce stade, node ne peut pas être null
  if (!node || !data) {
    return null;
  }

  // Connection rendering and path creation moved to InspectorConnections component

  // Minimized mode: compact pill with icon + label (desktop only).
  // The gate is extracted so it can be tested; see shouldRenderMinimizedPill.
  if (shouldRenderMinimizedPill({ isMinimized, isWindowMobile, isDocked })) {
    return (
      <div
        onClick={(e) => e.stopPropagation()}
        onMouseDown={(e) => e.stopPropagation()}
        className="relative"
      >
        <div
          data-inspector-panel
          className="flex items-center gap-2.5 px-3 py-2 bg-white dark:bg-gray-800 rounded-md pointer-events-auto cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/50 transition-colors relative inset-auto z-[150]"
          onClick={() => onMinimizedChange?.(false)}
        >
          <NodeIcon
            iconSlug={getIconSlug(data)}
            nodeId={data?.id || ''}
            nodeKind={effectiveNodeKind}
            nodeFamily={nodeFamily}
            avatarUrl={(data as any)?.agentAvatarUrl}
            size="sm"
          />
          <span className="text-sm font-medium text-gray-900 dark:text-gray-100 truncate max-w-[140px]">
            {data.label}
          </span>
          <Maximize2 className="h-3.5 w-3.5 text-gray-400 dark:text-gray-500 flex-shrink-0" />
        </div>
      </div>
    );
  }

  const geometryClass = inspectorGeometryClass({ isFullscreen, isDocked, isAdvanced });

  return (
    <div
      onClick={(e) => {
        e.stopPropagation();
        // Cancel the current connection if we click elsewhere
        // This is now handled by the connections hook
      }}
      onMouseDown={(e) => {
        e.stopPropagation();
      }}
      onMouseUp={(e) => {
        e.stopPropagation();
      }}
      className={clsx('relative', isDocked && !isFullscreen && 'h-full w-full min-h-0 min-w-0')}
    >
      <div
        ref={setPanelRef}
        data-inspector-panel
        className={clsx(
          geometryClass,
          "bg-white dark:bg-gray-800 flex flex-col pointer-events-auto overflow-hidden",
          // Docked, we are INSIDE the side panel's own stacking: a z-[9999] here
          // would lift the inspector over the panel's tab bar and header.
          isDocked && !isFullscreen ? "z-auto" : "z-[9999] lg:z-[150]",
          !isFullscreen && "group/inspector",
          draggingFromHandle && "select-none"
        )}
        // Extracted so it can be tested; see shouldConstrainPanelToContainer.
        style={shouldConstrainPanelToContainer({ isFullscreen, isDocked, isWindowMobile }) ? {
          ...(containerSize && containerSize.width > 0 ? {
            maxWidth: `${containerSize.width - 32}px`,
            maxHeight: `${containerSize.height - 32}px`,
          } : {
            maxWidth: isAdvanced ? '80vw' : '60vw',
            maxHeight: '90vh',
          }),
        } : undefined}
        onDoubleClick={(e) => e.stopPropagation()}
      >
        <InspectorConnections
          connections={connections}
          handlePositions={handlePositions}
          draggingFromHandle={draggingFromHandle}
          mousePosition={mousePosition}
          hoveredTargetHandle={hoveredTargetHandle}
          dragStartPosition={dragStartPosition}
          connectionType={connectionType}
          onDeleteConnection={handleDeleteConnection}
        />
        {/* Header with icon, editable title, and action buttons */}
        <InspectorPanelHeader
          node={node}
          data={data}
          nodeFamily={nodeFamily}
          nodeKind={effectiveNodeKind}
          isRunMode={isRunMode}
          isFullscreen={isFullscreen}
          isAdvanced={isAdvanced}
          isTabbedLayout={isMobile}
          isTriggerNode={isTriggerNode}
          isInterfaceNode={isInterfaceNode}
          shouldForceSmallMode={shouldForceSmallMode}
          isTableSelected={!!isTableSelected}
          dataSourceId={dataSourceData?.dataSourceId}
          triggerNavigationLevel={triggerNavigationLevel}
          selectedDataSourceId={selectedDataSourceId}
          dataSources={dataSources}
          showExecutionData={showExecutionData}
          onShowExecutionDataChange={handleShowExecutionDataChange}
          canShowExecutionDataToggle={isRunMode && !isInterfaceNode && !!runId && !!workflowId}
          stepByStepStatus={stepByStepStatus}
          hasGlobalValidationErrors={hasGlobalValidationErrors}
          onUpdate={onUpdate}
          onDeleteNode={onDeleteNode}
          onDuplicateNode={onDuplicateNode}
          onAdvancedChange={onAdvancedChange}
          onFullscreenChange={onFullscreenChange}
          onClose={onClose}
          onDragHandleMouseDown={onDragHandleMouseDown}
          onMinimize={isDocked ? undefined : () => onMinimizedChange?.(true)}
          onReportNode={handleReportNode}
          onOpenLogs={isRunMode && !!workflowId && !!runId ? handleOpenNodeLogs : undefined}
        />
        {/* Approval review: approve/reject the targeted pending item without
            leaving the inspector; auto-advances to the next pending item.
            nodeRegistry-based detection - nodeMeta.isUserApproval only matches
            palette ids (user-approval-*), not plan-loaded nodes (type
            userApprovalNode with the plan's own core id). */}
        {isRunMode && nodeRegistry.isUserApprovalNode(node) && (stepByStepStatus.pendingSignals?.length ?? 0) > 0 && (
          <ApprovalReviewBar
            rfNodeId={node.id}
            pendingSignals={stepByStepStatus.pendingSignals}
            resolveApproval={stepByStepStatus.resolveApproval}
            allNodes={allNodes}
          />
        )}
        <div className={clsx(
          "flex-1 min-h-0 relative",
          // Docked, the panel is only as wide as the user's side panel, while the
          // advanced layout needs its two 280px side columns plus a 200px minimum
          // for the parameters. Clipping there would cut the Output column off with
          // nothing on screen saying so, hence a scroll rather than overflow-hidden.
          !isMobile
            ? clsx('p-0 flex flex-row', isDocked && !isFullscreen ? 'overflow-x-auto overflow-y-hidden' : 'overflow-hidden')
            : "overflow-y-auto p-5 block"
        )}>
          {isMobile && (
            <InspectorMobileContent
              node={node}
              data={data}
              allNodes={allNodes}
              edges={edges}
              isRunMode={isRunMode}
              isRunModeForForms={effectiveRunModeForForms}
              // NOT the effective `isAdvanced`: the mobile inspector shows its three tabs
              // for any node that HAS them, independently of the header toggle and of the
              // open-mode preference. This used to read `isAdvanced || !shouldForceSmallMode`,
              // whose first operand cannot change the result (`isAdvanced` already implies
              // `!shouldForceSmallMode`) and invited the reader to believe mobile follows
              // the toggle.
              isAdvanced={!shouldForceSmallMode}
              isInterfaceNode={isInterfaceNode}
              isToolNode={isToolNode}
              isAiAgent={isAiAgent}
              activeTab={activeTabForNode}
              setActiveTab={setActiveTab}
              runId={runId}
              workflowId={workflowId}
              onSelectNode={onSelectNode}
              toolDetails={toolDetails}
              onUpdate={onUpdate}
              connectionProps={connectionPropsBundle}
              getEditorExpression={getEditorExpression}
              handleEditorExpressionChange={handleEditorExpressionChange}
              showExecutionData={showExecutionData}
              onShowExecutionDataChange={handleShowExecutionDataChange}
              canShowExecutionDataToggle={isRunMode && !isInterfaceNode && !!runId && !!workflowId}
              parameterColumnProps={{
                isMcpNode,
                isToolNode,
                isApiNode,
                shouldLoadApis,
                mcpNavigationLevel,
                mcpSearchQuery,
                setMcpSearchQuery,
                mcpApis,
                mcpApiTools,
                apiInitialLoading,
                toolInitialLoading,
                mcpLoadingApis,
                mcpLoadingTools,
                apiHasMore,
                toolHasMore,
                apiLoadMoreRef,
                toolLoadMoreRef,
                toolParameters,
                toolCredentials,
                loadingToolDetails,
                handleMcpApiClick,
                handleMcpToolSelect,
                handleMcpBackClick,
                handleParamExpressionChange,
                handleToolParamExpressionChange,
                handleColumnExpressionChange,
                handleColumnLabelChange,
                handleDeleteColumn,
                handleAddColumn,
                getParamExpression,
                getToolParamExpression,
                getColumnExpression,
                getColumnLabel,
                handleLoopConditionExpressionChange,
                handleRenameCondition,
                handleAddCondition,
                handleDeleteCondition,
                handleConditionExpressionChange,
                currentConditions,
                getConditionExpression,
                getConditionHandleId,
                currentCases,
                switchExpression,
                getCaseHandleId,
                getCaseValue,
                handleCaseValueChange,
                handleSwitchExpressionChange,
                handleAddCase,
                handleDeleteCase,
                handleRenameCase,
                currentChoices: optionChoices,
                handleAddChoice: handleAddOptionChoice,
                handleDeleteChoice: handleDeleteOptionChoice,
                handleRenameChoice: handleRenameOptionChoice,
                handleExpressionChange: handleOptionExpressionChange,
                currentApprovalOutputs: approvalOutputs,
                approvalTimeoutMs,
                approvalContextTemplate,
                approvalContinuationMode,
                approvalDelegation,
                handleAddApprovalOutput,
                handleDeleteApprovalOutput,
                handleRenameApprovalOutput,
                handleApprovalTimeoutChange,
                handleApprovalContextTemplateChange,
                handleApprovalContinuationModeChange,
                handleApprovalDelegationChange,
                webhookTokens,
                showExecutionData,
                currentWorkflowId: workflowId,
                currentRunId: runId,
              }}
            />
          )}
          {!isMobile && (
            <InspectorDesktopContent
              node={node}
              data={data}
              allNodes={allNodes}
              edges={edges}
              onUpdate={onUpdate}
              onSelectNode={onSelectNode}
              isRunMode={isRunMode}
              effectiveRunModeForForms={effectiveRunModeForForms}
              runId={runId}
              workflowId={workflowId}
              showExecutionData={showExecutionData}
              isAdvanced={isAdvanced}
              isFullscreen={isFullscreen}
              onAdvancedChange={onAdvancedChange}
              inputCollapsed={inputCollapsed}
              setInputCollapsed={setInputCollapsed}
              outputCollapsed={outputCollapsed}
              setOutputCollapsed={setOutputCollapsed}
              inputWidth={inputWidth}
              outputWidth={outputWidth}
              handleInputResizeStart={handleInputResizeStart}
              handleOutputResizeStart={handleOutputResizeStart}
              isResizingInput={isResizingInput}
              isResizingOutput={isResizingOutput}
              isInterfaceNode={isInterfaceNode}
              isToolNode={isToolNode}
              isAiAgent={isAiAgent}
              isMcpNode={isMcpNode}
              isApiNode={isApiNode}
              connections={connections}
              draggingFromHandle={draggingFromHandle}
              hoveredTargetHandle={hoveredTargetHandle}
              handleHandleClick={handleHandleClick}
              handleHandleMouseDown={handleHandleMouseDown}
              handleHandleMouseUp={handleHandleMouseUp}
              handleSetHandleRef={handleSetHandleRef}
              findUnknownVariables={findUnknownVariables}
              toolDetails={toolDetails}
              loadingToolDetails={loadingToolDetails}
              mcpNavigationLevel={mcpNavigationLevel}
              mcpSearchQuery={mcpSearchQuery}
              setMcpSearchQuery={setMcpSearchQuery}
              mcpApis={mcpApis}
              mcpApiTools={mcpApiTools}
              apiInitialLoading={apiInitialLoading}
              toolInitialLoading={toolInitialLoading}
              mcpLoadingApis={mcpLoadingApis}
              mcpLoadingTools={mcpLoadingTools}
              shouldLoadApis={shouldLoadApis}
              apiHasMore={apiHasMore}
              toolHasMore={toolHasMore}
              apiLoadMoreRef={apiLoadMoreRef}
              toolLoadMoreRef={toolLoadMoreRef}
              toolParameters={toolParameters}
              toolCredentials={toolCredentials}
              handleMcpApiClick={handleMcpApiClick}
              handleMcpToolSelect={handleMcpToolSelect}
              handleMcpBackClick={handleMcpBackClick}
              handleParamExpressionChange={handleParamExpressionChange}
              handleToolParamExpressionChange={handleToolParamExpressionChange}
              handleColumnExpressionChange={handleColumnExpressionChange}
              handleColumnLabelChange={handleColumnLabelChange}
              handleDeleteColumn={handleDeleteColumn}
              handleAddColumn={handleAddColumn}
              getParamExpression={getParamExpression}
              getToolParamExpression={getToolParamExpression}
              getColumnExpression={getColumnExpression}
              getColumnLabel={getColumnLabel}
              handleLoopConditionExpressionChange={handleLoopConditionExpressionChange}
              handleRenameCondition={handleRenameCondition}
              handleAddCondition={handleAddCondition}
              handleDeleteCondition={handleDeleteCondition}
              handleConditionExpressionChange={handleConditionExpressionChange}
              currentConditions={currentConditions}
              getConditionExpression={getConditionExpression}
              getConditionHandleId={getConditionHandleId}
              currentCases={currentCases}
              switchExpression={switchExpression}
              getCaseHandleId={getCaseHandleId}
              getCaseValue={getCaseValue}
              handleCaseValueChange={handleCaseValueChange}
              handleSwitchExpressionChange={handleSwitchExpressionChange}
              handleAddCase={handleAddCase}
              handleDeleteCase={handleDeleteCase}
              handleRenameCase={handleRenameCase}
              // Option props
              optionChoices={optionChoices}
              handleAddOptionChoice={handleAddOptionChoice}
              handleDeleteOptionChoice={handleDeleteOptionChoice}
              handleRenameOptionChoice={handleRenameOptionChoice}
              handleOptionExpressionChange={handleOptionExpressionChange}
              // Approval props
              approvalOutputs={approvalOutputs}
              handleAddApprovalOutput={handleAddApprovalOutput}
              handleDeleteApprovalOutput={handleDeleteApprovalOutput}
              handleRenameApprovalOutput={handleRenameApprovalOutput}
              handleApprovalTimeoutChange={handleApprovalTimeoutChange}
              approvalTimeoutMs={approvalTimeoutMs}
              handleApprovalContextTemplateChange={handleApprovalContextTemplateChange}
              approvalContextTemplate={approvalContextTemplate}
              handleApprovalContinuationModeChange={handleApprovalContinuationModeChange}
              approvalContinuationMode={approvalContinuationMode}
              handleApprovalDelegationChange={handleApprovalDelegationChange}
              approvalDelegation={approvalDelegation}
              getEditorExpression={getEditorExpression}
              handleEditorExpressionChange={handleEditorExpressionChange}
              webhookTokens={webhookTokens}
            />
          )}
          {/* Basic mode mobile content is now fully handled by InspectorMobileContent via ParameterColumn (embedded) */}
        </div>

        {/* Footer with validation errors */}
        {node && (
          <InspectorFooter
            errors={nodeValidation.errors}
            errorCount={nodeValidation.errorCount}
            warningCount={nodeValidation.warningCount}
            isCollapsed={isFooterCollapsed}
            onToggleCollapsed={() => setIsFooterCollapsed(!isFooterCollapsed)}
          />
        )}
      </div>

      {/* Full-viewport overlay during column resize - neutralizes iframes /
       *  ReactFlow / any child element that would otherwise swallow
       *  mousemove/mouseup. z-[140] sits below the desktop InspectorPanel
       *  (z-[150]) so the column resize handles keep their visual feedback,
       *  but above ReactFlow / interface iframes underneath. */}
      {(isResizingInput || isResizingOutput) && (
        <div
          className="fixed inset-0 z-[140]"
          style={{ cursor: 'ew-resize' }}
          aria-hidden="true"
        />
      )}

    </div >
  );
}
