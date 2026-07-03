'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position, useReactFlow } from 'reactflow';

import { getNodeVisual } from '../../data/nodeVisuals';
import { deriveStatusFromCounts } from '../../utils/statusCounts';
import type { BuilderNodeData, DerivedNodeStatus, NodeStatus } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import { NodeHeader, useHoverVisibility, getIconSlug, getIconUrl, getStatusBorderColor, ReadyShimmerOverlay } from './shared';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { InterfaceThumbnail } from '../interface/InterfaceThumbnail';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import type { RenderMode } from '../../utils/interfaceHtmlUtils';
import { Loader2, Play, Eye, Table, File, Image, FileText, Film, Music, Zap, Workflow, Monitor, FolderOpen, Globe, ChevronRight, Wrench, Activity, Clock, Cpu, Coins, Trash2, Pencil } from 'lucide-react';
import { type FilePanelTarget } from '@/lib/sidePanel/openFilesPanel';
import { useInterfaceById, useInterfaceRender } from '../../hooks/useInterfaces';
import { fileRefToUrl, normalizeFileRef, findFileRefs, isFileRef } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';
import { FleetTriggerButtons } from './FleetTriggerButtons';
import { TriggerNodePinButton } from './TriggerNodePinButton';
import { TriggerEditLaunchButton } from './TriggerEditLaunchButton';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { deriveNodeContextFlags, useNodeContextualButtons } from '../../hooks/useNodeContextualButtons';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { ResizableNodeWrapper } from './ResizableNodeWrapper';
import { useRunOutputData } from '../../hooks/useRunOutputData';
import { normalizeLabel } from '../../utils/labelNormalizer';
import { useBrowserLiveView } from './shared/useBrowserLiveView';
import { useTranslations } from 'next-intl';
import { useAgentActivity } from '@/components/agent-fleet/hooks/useAgentActivityStream';

import { useRun } from '@/contexts/WorkflowRunContext';
import { useQueryClient } from '@tanstack/react-query';
import { ItemNavigator } from '../inspector/outputs/ItemNavigator';
import { shouldFetchFileOutput } from './fileFetchPredicate';

// Fleet resource type → icon + background override (used only in fleet canvas)
// 'tool' and 'model' are excluded: tools use their service icon or MCP logo fallback,
// models use their provider icon (openai, anthropic, etc.)
const FLEET_RESOURCE_ICONS: Record<string, { icon: React.ComponentType<{ className?: string; strokeWidth?: number }>; bg: string }> = {
  skill: { icon: Zap, bg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-500 dark:text-amber-400' },
  folder: { icon: FolderOpen, bg: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400' },
  workflow: { icon: Workflow, bg: 'bg-purple-50 dark:bg-purple-900/30 text-purple-500 dark:text-purple-400' },
  interface: { icon: Monitor, bg: 'bg-teal-50 dark:bg-teal-900/30 text-teal-500 dark:text-teal-400' },
  table: { icon: Table, bg: 'bg-orange-50 dark:bg-orange-900/30 text-orange-500 dark:text-orange-400' },
  file: { icon: FileText, bg: 'bg-sky-50 dark:bg-sky-900/30 text-sky-500 dark:text-sky-400' },
  web_search: { icon: Globe, bg: 'bg-blue-50 dark:bg-blue-900/30 text-blue-500 dark:text-blue-400' },
};

export function FlowNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const visuals = getNodeVisual(data.kind);
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, isPreviewOnly, runId, viewingEpoch, setViewingEpoch, workflowId } = useWorkflowMode();
  const hideHandles = isRunMode || isPreviewOnly;
  const isFleetMode = !!(data.fleetBottomHandles || data.fleetTopHandle);
  // The fleet AGENT node specifically (not chips, and never a workflow-builder node).
  // The compact info line + moving the ✓/✗ status into it are agent-fleet-only.
  const isFleetAgentNode = isFleetMode && id.startsWith('agent-');
  const fleetResourceType = (data as any).fleetResourceType as string | undefined;
  const reactFlowInstance = useReactFlow();

  // Fleet real-time activity: shared hook with optimized equality prevents
  // re-renders when non-visual fields change (e.g. lastEvent timestamp).
  const fleetAgentId = isFleetMode && id.startsWith('agent-') ? id.slice('agent-'.length) : null;
  const fleetActivity = useAgentActivity(fleetAgentId);
  const fleetIsRunning = fleetActivity?.isRunning ?? false;

  // Get node class to determine family
  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  // Step-by-step execution status - pass node data for accurate backend ID mapping
  const stepByStepStatus = useNodeExecutionStatus(id, {
    label: data.label,
    kind: data.kind,
    crudOperation: (data as any)?.dataSourceData?.crudOperation,
  });

  // Browser-agent live view for GENERIC agent nodes: when this node's agent
  // calls web_search(agent_browse) mid-loop, the backend fans the cdp_ready
  // bootstrap out addressed to THIS node (host-node routing), populating
  // data.lastBrowser*. Surfaces the same eye-button live view the dedicated
  // agent:browser_agent node has - chat/workflow parity.
  const { hasLiveSession: hasBrowserLiveSession, openLiveView: openBrowserLiveView } =
    useBrowserLiveView(id, data);
  const tBrowserAgent = useTranslations('workflowBuilder.nodes.browserAgent');

  // Spawn item pagination - local to this node, resets when viewing epoch changes
  const [currentPage, setCurrentPage] = React.useState(0);
  React.useEffect(() => { setCurrentPage(0); }, [viewingEpoch]);

  // For file-producing nodes, FileNodePreview writes the currently-displayed
  // file here so the bottom-bar "Files" button can open the side panel
  // pre-focused on it. Null when no file is resolved (edit mode, no run yet,
  // or fetch gated off - see shouldFetchFileOutput). State (not ref) so MCP
  // nodes re-render the Files button once a FileRef is detected at runtime by
  // findFileRefs.
  const [currentFile, setCurrentFile] = React.useState<FilePanelTarget | null>(null);

  // Resizable node types
  const isDataInputNode = data.kind === 'data_input';
  const isDownloadFileNode = data.kind === 'download_file';

  // Check if this is a trigger node - triggers should never have input handles
  const nodeId = data.id || '';
  const canonicalNodeId = nodeClass?.id || nodeId;
  const nodeKind = data.kind;
  // Node-type flags + the contextual bottom-bar buttons are centralized in
  // deriveNodeContextFlags / useNodeContextualButtons so the run-info step
  // popover (a sibling of StepByStepProvider, outside it) renders the exact
  // same button set with the same behavior from one source of truth.
  const ctxFlags = deriveNodeContextFlags(data, canonicalNodeId);
  const {
    isSubWorkflowNode,
    isWorkflowsTriggerNode,
    referencedWorkflowId,
    isWebhookTrigger,
    isScheduleTrigger,
    isManualTrigger,
    isChatTrigger,
    isFormTrigger,
    isErrorTrigger,
    isTablesTrigger,
    isTriggerNode,
    isStaticFileProducingNode,
    isInterfaceNode,
    triggerVariant,
  } = ctxFlags;
  const sharedContextualButtons = useNodeContextualButtons({
    data,
    nodeUiId: id,
    isRunMode,
    flags: ctxFlags,
    includeFiles: true,
    currentFile,
  });

  // Check for errors (for tool nodes and table nodes, including table triggers)
  const isToolNode = (data.toolData || data.apiData) && !isTriggerNode;
  // Distinguer les nodes de table depuis data/tables (CRUD operations) des triggers
  // Un node de table depuis data a un ID qui commence par 'table-', 'create-', 'read-', 'update-', ou 'delete-' (pas 'tables-trigger-') et kind !== 'entry'
  const isDataTableNode = (nodeId.startsWith('table-') || nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') || nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-')) && !nodeId.startsWith('tables-trigger-') && nodeKind !== 'entry';

  const isFileProducingNode = isStaticFileProducingNode || !!isToolNode;

  // Terminal node that stops the entire workflow - no right source handle (like ExitNode)
  const isStopOnErrorNode = nodeRegistry.isStopOnErrorNode({ data });
  // Read preview mode from props OR directly from interfaceData (for when useMemo hasn't updated yet)
  const isPreviewMode = (data as any)?.isPreviewMode || (data as any)?.interfaceData?.showPreview || false;

  const toolParameters = isToolNode ? ((data as any)?.toolData?.parameters || []) : undefined;


  // Get HTML template for interface nodes in preview mode
  const interfaceData = isInterfaceNode ? ((data as any)?.interfaceData || {}) : {};
  const editorExpression = interfaceData.editorExpression || '';

  // Extract interfaceId for loading template from DB
  const interfaceIdFromData = interfaceData.interfaceId;
  const isInterfaceFromDb = nodeId.startsWith('interface-') && nodeId !== 'interface';
  const extractedInterfaceId = isInterfaceFromDb ? nodeId.replace('interface-', '').replace(/--\d+$/, '') : null;
  const interfaceId = interfaceIdFromData || extractedInterfaceId;

  // Load interface details from DB if interfaceId exists AND this is an interface node (for auto-loading template on page reload)
  // Only fetch if isInterfaceNode is true to avoid 404 errors for non-interface nodes
  const { data: interfaceDetails, isLoading: isLoadingInterface } = useInterfaceById(isInterfaceNode && interfaceId ? interfaceId : null);

  // Fetch rendered interface data in run mode (with resolved variables from backend)
  const shouldFetchRenderData = isInterfaceNode && isRunMode && interfaceId && runId;
  const { data: renderData, isLoading: isLoadingRenderData } = useInterfaceRender(
    shouldFetchRenderData ? interfaceId : null,
    shouldFetchRenderData ? runId : null,
    currentPage,
    1, // Card preview shows 1 item
    viewingEpoch ?? undefined
  );

  // Get render data for run mode
  const runModeTemplate = renderData?.htmlTemplate;
  const runModeItems = renderData?.items || [];
  const runModeTotalItems = renderData?.pagination?.totalItems || 0;
  const runModeTotalPages = renderData?.pagination?.totalPages || 0;


  // Auto-load template from DB when interface is loaded (only once per mount)
  // Tracks the interfaceId we've loaded for, resets when:
  // - Component remounts (new node)
  // - interfaceId changes (different interface)
  // - editorExpression becomes empty (workflow reloaded from backend)
  const loadedTemplateForRef = React.useRef<string | null>(null);

  // Reset when editorExpression becomes empty (workflow reload clears it)
  if (!editorExpression && loadedTemplateForRef.current === interfaceId) {
    loadedTemplateForRef.current = null;
  }

  const shouldLoadTemplate = isInterfaceNode &&
    interfaceId &&
    interfaceDetails &&
    !isLoadingInterface &&
    loadedTemplateForRef.current !== interfaceId &&
    data.onNodeUpdate;

  React.useEffect(() => {
    if (!shouldLoadTemplate) return;

    const templateFromDb = interfaceDetails!.htmlTemplate || interfaceDetails!.editorExpression || '';
    const hasLocalTemplate = editorExpression && editorExpression.trim() !== '';

    // Load template from DB if local is empty
    if (templateFromDb && !hasLocalTemplate) {
      loadedTemplateForRef.current = interfaceId!;
      data.onNodeUpdate!({
        ...data,
        interfaceData: {
          ...interfaceData,
          editorExpression: templateFromDb,
          cssTemplate: interfaceDetails!.cssTemplate ?? interfaceData.cssTemplate ?? null,
          jsTemplate: interfaceDetails!.jsTemplate ?? interfaceData.jsTemplate ?? null,
          dataSourceId: interfaceDetails!.dataSourceId ?? null,
        },
      });
    } else if (hasLocalTemplate) {
      // Mark as loaded, but still sync dataSourceId/cssTemplate/jsTemplate if missing
      loadedTemplateForRef.current = interfaceId!;
      const needsSync =
        (interfaceDetails!.dataSourceId != null && interfaceData.dataSourceId == null) ||
        (interfaceDetails!.cssTemplate && !interfaceData.cssTemplate) ||
        (interfaceDetails!.jsTemplate && !interfaceData.jsTemplate);
      if (needsSync) {
        data.onNodeUpdate!({
          ...data,
          interfaceData: {
            ...interfaceData,
            dataSourceId: interfaceDetails!.dataSourceId ?? interfaceData.dataSourceId,
            cssTemplate: interfaceDetails!.cssTemplate ?? interfaceData.cssTemplate ?? null,
            jsTemplate: interfaceDetails!.jsTemplate ?? interfaceData.jsTemplate ?? null,
          },
        });
      }
    }
  }, [shouldLoadTemplate, interfaceId, interfaceDetails, editorExpression, interfaceData, data]);

  // Local validation errors
  // Use centralized validation context for error state
  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  // Determine effective status: use step-by-step context as source of truth in step-by-step mode
  // Otherwise fall back to data.status (streaming updates for automatic mode)
  // When viewing a historical epoch, always use data.status (set by useEpochStateViewing)
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    // Historical epoch viewing: data.status is set by useEpochStateViewing, use it directly
    if (viewingEpoch != null) return data.status;
    if (stepByStepStatus.isStepByStepMode) {
      if (stepByStepStatus.isRunning) return 'running';
      if (stepByStepStatus.isFailed) return 'failed';
      if (stepByStepStatus.isSkipped) return 'skipped';
      if (stepByStepStatus.isCompleted) return 'completed';
      if (stepByStepStatus.isReady) return 'ready';
      return 'pending';
    }
    // Auto mode: streaming stepStarted/stepCompleted update runningSteps in real-time,
    // so check it as an override for data.status (which only updates from REST API sync)
    if (stepByStepStatus.isRunning) return 'running';
    // Fleet mode: real-time running state from WebSocket activity stream
    if (isFleetMode && fleetIsRunning) return 'running';
    // Fleet mode: derive status from statusCounts when no explicit status is set
    if (isFleetMode && data.status === undefined && data.statusCounts) {
      const derived = deriveStatusFromCounts(data.statusCounts);
      return derived === 'pending' ? undefined : derived;
    }
    return data.status;
  }, [viewingEpoch, stepByStepStatus, data.status, isFleetMode, data.statusCounts, fleetIsRunning]);

  // Get border color based on status
  // Always use status-based color for border
  const statusBorderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  // Toujours utiliser la couleur de statut pour la bordure,
  // même quand le node est sélectionné (le focus sera rendu à l'extérieur).
  // All nodes use status-based border color - blue only appears during running state with shimmer
  const borderColor = statusBorderColor;

  // Don't apply skipped styling in step-by-step mode
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Interface preview mode: always on for interface nodes
  const effectivePreviewMode = isInterfaceNode;

  // Get CSS/JS templates from node data, DB, or render data
  const interfaceCssTemplate = renderData?.cssTemplate || interfaceData.cssTemplate || interfaceDetails?.cssTemplate || '';
  const interfaceJsTemplate = renderData?.jsTemplate || interfaceData.jsTemplate || interfaceDetails?.jsTemplate || '';

  // Get template and resolved data for interface preview
  const interfaceHtmlTemplate = React.useMemo(() => {
    if (!isInterfaceNode || !effectivePreviewMode) return '';
    if (isRunMode && runModeTemplate) return runModeTemplate;
    return editorExpression || '';
  }, [isInterfaceNode, effectivePreviewMode, isRunMode, runModeTemplate, editorExpression]);

  const interfaceResolvedData = React.useMemo(() => {
    if (!isRunMode || runModeItems.length === 0) return undefined;
    // Use the first item's resolved data
    return runModeItems[0]?.data as Record<string, unknown> | undefined;
  }, [isRunMode, runModeItems]);

  // Bridge inputs for prefillForms() in run mode - without these, FlowNode's
  // inline interface preview shows a blank form even when the trigger data is
  // available on the snapshot. Pointer events are blocked by the overlay below
  // so the bridge's submit/click listeners stay inert (no parent onAction).
  const interfaceActionMapping = renderData?.actionMappings as Record<string, string> | undefined;
  const interfaceTriggerData = React.useMemo(() => {
    if (!isRunMode || runModeItems.length === 0) return undefined;
    return runModeItems[0]?.triggerData as Record<string, Record<string, unknown>> | undefined;
  }, [isRunMode, runModeItems]);

  // When the run has no item data, fall back to "edit" so `{{xxx|default}}`
  // pipe defaults show instead of raw `{{…}}` placeholders.
  const hasInterfaceResolvedData = !!interfaceResolvedData && Object.keys(interfaceResolvedData).length > 0;
  const interfaceRenderMode: RenderMode = isRunMode && hasInterfaceResolvedData ? 'run' : 'edit';

  // Epoch nav: page 0 = newest. ItemNavigator handles left/right direction naturally.
  // currentPage maps to ItemNavigator index: index 0 = most recent = "Epoch 1".

  // Fullscreen handler
  const handleOpenFullscreen = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    window.dispatchEvent(new CustomEvent('openInterfaceFullscreen', {
      detail: {
        interfaceId,
        runId,
        currentPage,
        htmlTemplate: runModeTemplate || editorExpression,
        totalItems: runModeTotalItems,
      }
    }));
  }, [interfaceId, runId, currentPage, runModeTemplate, editorExpression, runModeTotalItems]);


  // Check if node is running (for animation) - show shimmer in all modes
  const isNodeRunning = effectiveStatus === 'running';

  // Determine if we're showing HTML content (preview mode with template)
  const isShowingHtml = effectivePreviewMode && !!interfaceHtmlTemplate;

  return (
    <div
      ref={nodeRef}
      data-testid={fleetAgentId ? `fleet-node-${fleetAgentId}` : undefined}
      className={clsx(
        'group relative',
        // Only show container styling when NOT showing HTML content
        !isShowingHtml && 'rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4 backdrop-blur border-2',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor: isShowingHtml ? 'transparent' : borderColor,
        borderStyle: 'solid',
        position: 'relative',
        // Anneau de focus/sélection à l'extérieur du node pour ne pas écraser la bordure de statut
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
        minWidth: effectivePreviewMode ? '100px' : data.fleetBottomHandles ? '180px' : '200px',
        // Fleet is a dashboard/overview - cap node width so long agent/tool names
        // truncate (label is already `truncate`) instead of growing the node and
        // spreading the whole graph. Keeps the size-aware layout compact AND
        // overlap-free (the measured width == this cap). Builder nodes stay
        // uncapped so full step labels remain readable while editing.
        maxWidth: isFleetMode && !effectivePreviewMode ? 280 : undefined,
        // NodeResizer manages node.style.width/height - resizable nodes fill the container
        width: (effectivePreviewMode || isDataInputNode || isDownloadFileNode) ? '100%' : undefined,
        minHeight: effectivePreviewMode ? '80px' : undefined,
        height: effectivePreviewMode ? '100%' : undefined,
        overflow: 'visible', // Keep visible for handles
      }}
      tabIndex={0}
    >
      {/* Shimmer scan effect for running state - left to right like ChatGPT (hide when showing HTML) */}
      {isNodeRunning && !isShowingHtml && (
        <div
          data-testid={fleetAgentId ? `fleet-agent-shimmer-${fleetAgentId}` : undefined}
          className="absolute inset-0 pointer-events-none rounded-[26px]"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}
      {stepByStepStatus.isStepByStepMode && effectiveStatus === 'ready' && !isShowingHtml && (
        <ReadyShimmerOverlay className="absolute inset-0 pointer-events-none rounded-[26px]" />
      )}
      {/* Fleet trigger buttons (webhook / schedule) are rendered to the LEFT of the
          agent node (see the FleetTriggerButtons block further down), so the old
          top-right corner badge is gone. */}

      {effectivePreviewMode && (interfaceHtmlTemplate || isLoadingRenderData) ? (
        <>
          <div className="w-full h-full relative overflow-hidden">
            <InterfaceThumbnail
              htmlTemplate={interfaceHtmlTemplate}
              mode={interfaceRenderMode}
              resolvedData={interfaceRenderMode === 'run' ? interfaceResolvedData : undefined}
              customCss={interfaceCssTemplate}
              jsTemplate={interfaceJsTemplate}
              fit="contain"
              actionMapping={interfaceRenderMode === 'run' ? interfaceActionMapping : undefined}
              triggerData={interfaceRenderMode === 'run' ? interfaceTriggerData : undefined}
            />
            {/* Overlay: captures all pointer events for node drag/double-click, blocks iframe interaction */}
            <div className="absolute inset-0 z-10" />
          </div>
        </>
      ) : (
        <>
          <NodeHeader
            visuals={visuals}
            label={data.label}
            iconSlug={getIconSlug(data)}
            iconUrl={getIconUrl(data)}
            customIcon={fleetResourceType && !getIconSlug(data) ? FLEET_RESOURCE_ICONS[fleetResourceType]?.icon : undefined}
            customIconBg={fleetResourceType && !getIconSlug(data) ? FLEET_RESOURCE_ICONS[fleetResourceType]?.bg : undefined}
            nodeId={canonicalNodeId}
            nodeKind={nodeKind}
            nodeFamily={nodeFamily}
            avatarUrl={(data as any)?.agentAvatarUrl}
          />

          {/* SubWorkflow: show referenced workflow name grayed out (only if distinct from node label) */}
          {isSubWorkflowNode && referencedWorkflowId && (data as any)?.workflowData?.workflowName && (data as any).workflowData.workflowName !== data.label && (
            <p className="text-xs text-slate-400 dark:text-slate-500 truncate mt-1 ml-14">{(data as any).workflowData.workflowName}</p>
          )}

          {/* Fleet agent: ONE compact info line (resources + metrics) in the tight
              statuscount style - small icons + concise values, no pill backgrounds.
              Wraps to a 2nd line if needed. Replaces the old two pill rows so the
              agent node stays short. Resources show only when collapsed (expanded =
              they render as their own chip nodes). */}
          {isFleetAgentNode && ((data.fleetResourceCounts && data.fleetIsCollapsed) || ((data as any).fleetMetrics?.totalExecutions > 0) || data.statusCounts) && (() => {
            const c = data.fleetResourceCounts;
            const m = (data as any).fleetMetrics;
            const hasMetrics = !!m && m.totalExecutions > 0;
            const showResources = !!c && !!data.fleetIsCollapsed;
            const fmtCount = (n: number) => n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${(n / 1_000).toFixed(1)}K` : String(n);
            const fmtDuration = (ms: number) => ms >= 60_000 ? `${(ms / 60_000).toFixed(1)}m` : ms >= 1_000 ? `${(ms / 1_000).toFixed(1)}s` : `${ms}ms`;
            const fmtAgo = (iso: string) => {
              const diff = Date.now() - parseUtcAware(iso).getTime();
              if (diff < 60_000) return 'now';
              if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m`;
              if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h`;
              return `${Math.floor(diff / 86_400_000)}d`;
            };
            // Concise credit display (was 4 decimals like "23.9656" → "23.97"). This compact
            // fleet badge stays credit-denominated in both editions (CE and Cloud) - unlike the
            // $-denominated cost surfaces - so its magnitude reads consistently across branches.
            const fmtCredits = (n: number) => n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${(n / 1_000).toFixed(1)}K` : n >= 10 ? String(Math.round(n)) : n.toFixed(2);
            const item = (Icon: React.ComponentType<{ className?: string }>, value: React.ReactNode, cls = 'text-slate-500 dark:text-slate-400') => (
              <span className={`inline-flex items-center gap-0.5 ${cls}`}>
                <Icon className="h-2.5 w-2.5 flex-shrink-0" />{value}
              </span>
            );
            return (
              <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5 mt-1.5 text-[10px] text-slate-500 dark:text-slate-400">
                {showResources && c!.tools !== 0 && item(Wrench, c!.tools === -1 ? 'All' : c!.tools)}
                {showResources && c!.skills !== 0 && item(Zap, c!.skills)}
                {showResources && c!.workflows !== 0 && item(Workflow, c!.workflows)}
                {showResources && c!.interfaces !== 0 && item(Monitor, c!.interfaces)}
                {showResources && c!.tables !== 0 && item(Table, c!.tables)}
                {showResources && c!.webSearch && <Globe className="h-2.5 w-2.5 flex-shrink-0" />}
                {/* Run status moved here from the bottom-right corner (✓ completed / ✗ failed). */}
                {data.statusCounts && <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />}
                {hasMetrics && m.totalTokens > 0 && item(Cpu, fmtCount(m.totalTokens))}
                {hasMetrics && m.avgDurationMs !== null && item(Clock, fmtDuration(m.avgDurationMs))}
                {hasMetrics && (m.creditsConsumed > 0 || m.creditBudget != null) && item(Coins, fmtCredits(m.creditsConsumed), 'text-amber-600 dark:text-amber-400')}
                {hasMetrics && m.lastExecutionAt && <span>{fmtAgo(m.lastExecutionAt)}</span>}
              </div>
            );
          })()}

          {/* Fleet real-time activity indicator (current tool being called) */}
          {isFleetMode && fleetIsRunning && fleetActivity && (
            <div
              data-testid={fleetAgentId ? `fleet-agent-running-${fleetAgentId}` : undefined}
              className="flex items-center gap-1.5 mt-2 text-[10px] text-blue-600 dark:text-blue-400"
            >
              <Loader2 className="h-2.5 w-2.5 animate-spin flex-shrink-0" />
              <span className="truncate">
                {fleetActivity.currentToolName || 'Running...'}
              </span>
              {fleetActivity.toolCallCount > 0 && (
                <span className="text-blue-400 dark:text-blue-500 flex-shrink-0">
                  ({fleetActivity.toolCallCount} tools)
                </span>
              )}
            </div>
          )}

          {data.metrics ? (
            <div className="mt-4 flex flex-wrap gap-2 text-[11px] text-slate-500 dark:text-slate-400">
              {data.metrics.tokens ? (
                <span className="rounded-full bg-slate-100 dark:bg-slate-700 px-2 py-1">{data.metrics.tokens}</span>
              ) : null}
              {data.metrics.latency ? (
                <span className="rounded-full bg-slate-100 dark:bg-slate-700 px-2 py-1">{data.metrics.latency}</span>
              ) : null}
            </div>
          ) : null}

          {/* Data Input node preview */}
          {nodeKind === 'data_input' && (
            <DataInputNodePreview data={data} />
          )}

          {/* File-producing nodes preview (run mode: shows resulting file info) */}
          {isFileProducingNode && (
            <FileNodePreview
              data={data}
              setCurrentFile={setCurrentFile}
              selected={selected}
              isStaticFileProducingNode={isStaticFileProducingNode}
            />
          )}

        </>
      )}

      {/* Pagination controls - below node (spawn items only, not epochs) */}
      {isRunMode && isInterfaceNode && viewingEpoch != null && runModeTotalItems > 1 && (
        <div
          className="absolute left-1/2 -translate-x-1/2 nodrag nopan z-10"
          style={{ top: 'calc(100% + 8px)' }}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={(e) => e.stopPropagation()}
        >
          <ItemNavigator
            currentIndex={currentPage}
            totalItems={runModeTotalPages}
            onIndexChange={setCurrentPage}
            itemLabel=""
          />
        </div>
      )}

      {/* Status badge bottom-right - hidden when showing HTML. For fleet AGENT nodes
          the ✓/✗ counts move into the compact info line above, so skip it here to
          avoid duplication. */}
      {!isShowingHtml && !isFleetAgentNode && (
        <div className="absolute bottom-2 right-2">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>
      )}

      {/* Fleet edit-mode action cluster - centered ABOVE the node on hover,
          neutral pill style (the workflow builder's delete/duplicate moved into
          the bottom bar; the fleet cluster keeps the top placement).
          Per-type: agent/model → Edit only; web_search/skill → Delete only; tool /
          workflow/interface/table → Delete + Edit. Grouping nodes (provider/folder/
          category/aggregator) and the "All tools" pseudo-chip get no buttons. */}
      {(data as any)?.fleetEditMode && isFleetMode && (() => {
        const rt = fleetResourceType;
        const isContainer = id.startsWith('provider-') || id.startsWith('folder-')
          || id.startsWith('category-') || id.startsWith('agg-');
        // The synthetic "All tools" chip (mode:'all') is NOT a real tool - its id is
        // the literal `all-tools` (no `apiSlug:toolSlug` colon). Editing/deleting it
        // can't persist (it would leave mode:'all' intact), so give it no buttons;
        // tool access for an all-mode agent is changed from the agent's Edit modal.
        const isAllAccessChip = rt === 'tool' && id.endsWith('-tool-all-tools');
        let canEdit = false;
        let canDelete = false;
        if (isFleetAgentNode) { canEdit = true; }
        else if (isContainer || isAllAccessChip) { /* none */ }
        else if (rt === 'model') { canEdit = true; }
        else if (rt === 'web_search') { canDelete = true; }
        else if (rt === 'skill') { canDelete = true; }
        else if (rt === 'tool') { canEdit = true; canDelete = true; }
        else if (rt === 'workflow' || rt === 'interface' || rt === 'table' || rt === 'application') {
          canEdit = true; canDelete = true;
        }
        if (!canEdit && !canDelete) return null;
        const onFleetEdit = (data as any)?.onFleetEdit as ((id: string) => void) | undefined;
        const onFleetDelete = (data as any)?.onFleetDelete as ((id: string) => void) | undefined;
        // Localized tooltips injected by AgentFleetCanvas (keeps this shared node i18n-agnostic).
        const labels = (data as any)?.fleetEditLabels as { edit?: string; remove?: string } | undefined;
        // Neutral pill (no red) - kept for the fleet canvas top cluster.
        const btnClass = 'flex h-6 w-6 items-center justify-center rounded-full bg-[var(--bg-primary)] text-[var(--text-primary)] shadow-sm hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] transition-colors';
        return (
          <div
            className={`absolute top-0 left-1/2 z-20 flex flex-row gap-1 nodrag nopan transition-opacity duration-200 ${showActions ? 'opacity-100' : 'opacity-0 pointer-events-none'}`}
            style={{ transform: 'translateX(-50%) translateY(calc(-100% - 8px))' }}
          >
            {canEdit && onFleetEdit && (
              <button onClick={(e) => { e.stopPropagation(); onFleetEdit(id); }} title={labels?.edit} className={btnClass}>
                <Pencil className="h-3 w-3" />
              </button>
            )}
            {canDelete && onFleetDelete && (
              <button onClick={(e) => { e.stopPropagation(); onFleetDelete(id); }} title={labels?.remove} className={btnClass}>
                <Trash2 className="h-3 w-3" />
              </button>
            )}
          </div>
        );
      })()}

      {/* Centralized bottom bar: contextual buttons + play/rerun.
          In isPreviewOnly mode (marketplace preview - authenticated or
          anonymous) the node bottom bar is suppressed entirely: the buttons
          route to auth-gated side panels (agent config, table data,
          sub-workflow builder) that either 401 or leak the publisher's
          tenant resources. Users who acquire the publication and open it
          from /app/applications/{id} run with isPreviewOnly=false and get
          the full button row back. */}
      {!isPreviewOnly && (() => {
        // Agent config/conversation, table data, files, and sub-workflow
        // buttons are built by useNodeContextualButtons (shared with the
        // run-info step popover). Files is canvas-only (includeFiles: true).
        const bottomButtons = [...sharedContextualButtons];

        // Browser-agent live view (generic agent node hosting an
        // agent_browse tool call) - same affordance as the dedicated
        // browser_agent node's eye button.
        if (hasBrowserLiveSession) {
          bottomButtons.push({
            key: 'browser-live-view',
            icon: <Eye className="h-3 w-3" />,
            title: tBrowserAgent('viewLiveTrace'),
            onClick: openBrowserLiveView,
          });
        }

        // Play button config
        const isTriggerForPlayButton = isManualTrigger || isChatTrigger || isFormTrigger || isWebhookTrigger || isScheduleTrigger || isWorkflowsTriggerNode || isTablesTrigger || isErrorTrigger;
        const showPlay = !isPreviewOnly && !isInterfaceNode && isRunMode && (
          (isTriggerForPlayButton && (stepByStepStatus.isStepByStepMode || stepByStepStatus.isReady)) ||
          (!isTriggerForPlayButton && stepByStepStatus.isStepByStepMode)
        );

        // Edit-mode trigger launcher - replaces the legacy top-hover play
        // button. Renders a single Play button at the bottom that opens an
        // Auto / Step-by-step menu (non-triggers never get a launcher).
        // triggerVariant comes from the centralized ctxFlags destructure above.
        const showEditLaunch = !isPreviewOnly && !isFleetMode && !isShowingHtml && !isRunMode && isTriggerNode;
        const pinWorkflowId = (isTriggerNode && !isPreviewOnly && !isFleetMode && !isShowingHtml) ? workflowId : null;

        // Focus-epoch trigger play: in run mode the normal play is hidden while
        // viewing a specific epoch (useNodeExecutionStatus gates controls to the
        // all-epochs view). Re-expose it on triggers so the user can re-launch
        // from a focused epoch - clicking returns to all-epochs AND fires THIS
        // trigger (fireFromAnyEpoch targets epoch=undefined, so the clicked
        // trigger is the one that runs, even with several triggers).
        const focusTriggerFireable = isManualTrigger || isScheduleTrigger || isWorkflowsTriggerNode || isTablesTrigger || isErrorTrigger;
        if (isRunMode && isTriggerNode && focusTriggerFireable && viewingEpoch != null && !isPreviewOnly && stepByStepStatus.isReadyRaw) {
          bottomButtons.push({
            key: 'focus-trigger-play',
            icon: <Play className="h-3 w-3" fill="currentColor" strokeWidth={2} />,
            title: 'Run',
            onClick: () => {
              setViewingEpoch(null);
              stepByStepStatus.fireFromAnyEpoch();
            },
          });
        }

        // Hover delete/duplicate - joined the bottom bar (same row + style as
        // the persistent buttons; NodeBottomBar hides them in run mode).
        const hasHoverActions = !isRunMode && !!(data.onDeleteNode || data.onDuplicateNode) && !isFleetMode;

        if (bottomButtons.length === 0 && !showPlay && !showEditLaunch && !pinWorkflowId && !hasHoverActions) return null;

        return (
          <NodeBottomBar
            hover={{ isVisible: showActions, onHover: show }}
            borderColor={borderColor}
            isRunning={isNodeRunning}
            buttons={bottomButtons.length > 0 ? bottomButtons : undefined}
            hoverActions={hasHoverActions ? {
              onDelete: data.onDeleteNode ? () => data.onDeleteNode?.(id) : undefined,
              onDuplicate: data.onDuplicateNode ? () => data.onDuplicateNode?.(id) : undefined,
            } : undefined}
            leadingSlot={pinWorkflowId ? (
              <TriggerNodePinButton workflowId={pinWorkflowId} nodeId={id} borderColor={borderColor} />
            ) : undefined}
            trailingSlot={showEditLaunch ? (
              <TriggerEditLaunchButton nodeId={id} variant={triggerVariant} borderColor={borderColor} />
            ) : undefined}
            playButton={showPlay ? {
              nodeId: id,
              variant: triggerVariant,
              isAutoMode: !stepByStepStatus.isStepByStepMode,
              isTriggerNode,
              stepByStepStatus,
            } : undefined}
          />
        );
      })()}

      {/* NodeResizer - 4 sides + 4 corners, visible on hover/selection */}
      {effectivePreviewMode && (
        <ResizableNodeWrapper
          enabled={true}
          minWidth={100}
          maxWidth={800}
          minHeight={80}
          maxHeight={500}
          onResizeEnd={(w, h) => {
            data.onNodeUpdate?.({
              ...data,
              interfaceData: { ...interfaceData, previewWidth: w, previewHeight: h },
            });
          }}
        />
      )}
      {isDataInputNode && !isRunMode && (
        <ResizableNodeWrapper
          enabled={true}
          minWidth={180}
          maxWidth={500}
          minHeight={80}
          maxHeight={600}
          onResizeEnd={(w, h) => {
            data.onNodeUpdate?.({ ...data, dataInputWidth: w, dataInputHeight: h });
          }}
        />
      )}
      {isDownloadFileNode && (
        <ResizableNodeWrapper
          enabled={true}
          minWidth={180}
          maxWidth={500}
          minHeight={80}
          maxHeight={600}
          onResizeEnd={(w, h) => {
            data.onNodeUpdate?.({ ...data, downloadFileWidth: w, downloadFileHeight: h });
          }}
        />
      )}

      {/* Interface bottom bar - run mode only */}
      {isRunMode && isInterfaceNode && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={isNodeRunning}
          extraTopOffset={viewingEpoch != null && runModeTotalItems > 1}
          buttons={[{
            key: 'interface',
            icon: <Monitor className="h-3 w-3" strokeWidth={2} />,
            title: 'View interface',
            onClick: () => {
              const ifaceId = interfaceId || (data as any)?.interfaceData?.interfaceId;
              if (ifaceId) {
                window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', { detail: { interfaceId: ifaceId } }));
              }
            },
          }]}
        />
      )}

      {/* Fleet agent trigger buttons (webhook / schedule) rendered to the LEFT of the
          agent node - vertically centered, with a small gap so they're not glued to
          the node - NOT below it. The bottom edge carries the model/tools/resources
          source handles + their downward edges, so the triggers live on the otherwise
          empty left side; a lone trigger then sits right beside the node (close, not
          floating far below). Same round-button + amber shimmer style as the workflow
          node bottom buttons. Lives outside the !isPreviewOnly bottom-bar block because
          the fleet runs with readOnly (isPreviewOnly = true) yet must still surface the
          trigger affordance. */}
      {isFleetAgentNode && (data as any).fleetTriggers && (() => {
        const triggers = (data as any).fleetTriggers as { hasWebhook: boolean; hasSchedule: boolean; webhookUrl?: string; cronExpression?: string; timezone?: string };
        if (!triggers.hasWebhook && !triggers.hasSchedule) return null;
        return (
          <div
            className="absolute z-10 nodrag nopan"
            // Sit just past the node's left edge (small gap, not glued), vertically centered.
            style={{ right: 'calc(100% + 10px)', top: '50%', transform: 'translateY(-50%)' }}
          >
            <FleetTriggerButtons triggers={triggers} borderColor={borderColor} />
          </div>
        );
      })()}

      {/* Interface nodes: left target + right source (standard DAG flow, same as other nodes) */}
      {/* Fleet mode: hide all left/right handles - only bottom/top are used */}
      {!isFleetMode && isInterfaceNode ? (
        <>
          <Handle
            type="target"
            position={Position.Left}
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              left: -6,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: 'var(--border-color)',
              opacity: hideHandles ? 0 : 1,
              pointerEvents: hideHandles ? 'none' : 'auto'
            }}
          />
          <Handle
            type="source"
            position={Position.Right}
            id="source-right"
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              right: -6,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: 'var(--border-color)',
              opacity: hideHandles ? 0 : 1,
              pointerEvents: hideHandles ? 'none' : 'auto'
            }}
          />
        </>
      ) : !isFleetMode && !isTriggerNode && (
        <Handle
          type="target"
          position={Position.Left}
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            left: -6,
            top: '50%',
            transform: 'translateY(-50%)',
            backgroundColor: 'var(--border-color)',
            opacity: hideHandles ? 0 : 1,
            pointerEvents: hideHandles ? 'none' : 'auto'
          }}
        />
      )}
      {/* Non-interface nodes have additional handles */}
      {!isFleetMode && !isInterfaceNode && !isStopOnErrorNode && (
        <>
          {/* Output handle on the right for all nodes (including triggers, data table nodes, but not interface) */}
          <Handle
            type="source"
            position={Position.Right}
            id="source-right"
            className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              right: -6,
              top: '50%',
              transform: 'translateY(-50%)',
              backgroundColor: 'var(--border-color)',
              opacity: hideHandles ? 0 : 1,
              pointerEvents: hideHandles ? 'none' : 'auto'
            }}
          />
        </>
      )}
      {/* Fleet mode: bottom source handles - model (left) + tools (center) + resources (right) */}
      {data.fleetBottomHandles && (() => {
        const handles = (data as any).fleetHandles as string[] | undefined;
        if (handles && handles.length > 0) {
          const total = handles.length;
          const HANDLE_LABELS: Record<string, string> = { model: 'Model', tools: 'Tools', resources: 'Resources' };
          return handles.map((h: string, i: number) => {
            const pct = total === 1 ? 50 : total === 2 ? 35 + i * 30 : 25 + (i * 50) / (total - 1);
            const color = 'var(--border-color)';
            const label = HANDLE_LABELS[h];
            return (
              <React.Fragment key={`source-${h}`}>
                <Handle
                  type="source"
                  position={Position.Bottom}
                  id={`source-${h}`}
                  className="!h-2.5 !w-2.5 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
                  style={{
                    bottom: -5,
                    left: `${pct}%`,
                    transform: 'translateX(-50%)',
                    backgroundColor: color,
                  }}
                />
                {label && (
                  <span
                    className="absolute text-[9px] font-medium text-slate-400 dark:text-slate-500 pointer-events-none select-none"
                    style={{
                      bottom: -18,
                      left: `${pct}%`,
                      transform: 'translateX(-50%)',
                    }}
                  >
                    {label}
                  </span>
                )}
              </React.Fragment>
            );
          });
        }
        // Fallback: single centered handle
        return (
          <Handle
            type="source"
            position={Position.Bottom}
            id="source-bottom"
            className="!h-2.5 !w-2.5 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
            style={{
              bottom: -5,
              left: '50%',
              transform: 'translateX(-50%)',
              backgroundColor: 'var(--border-color)',
            }}
          />
        );
      })()}
      {/* Fleet mode: top target handle for resource nodes */}
      {data.fleetTopHandle && (
        <Handle
          type="target"
          position={Position.Top}
          id="target-top"
          className="!h-2.5 !w-2.5 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            top: -5,
            left: '50%',
            transform: 'translateX(-50%)',
            backgroundColor: 'var(--border-color)',
          }}
        />
      )}
    </div>
  );
}

// ============================================================================
// Data Input Node Preview (canvas)
// ============================================================================

function getDataInputFileIcon(mimeType: string) {
  if (mimeType.startsWith('image/')) return Image;
  if (mimeType.startsWith('video/')) return Film;
  if (mimeType.startsWith('audio/')) return Music;
  if (mimeType.includes('pdf') || mimeType.includes('document') || mimeType.includes('text')) return FileText;
  return File;
}

function DataInputNodePreview({ data }: { data: BuilderNodeData }) {
  const items: Array<{ id: string; label: string; type: 'text' | 'file'; text?: string; file?: { _type: string; path: string; name: string; mimeType: string; size: number } | null }> = (data as any).dataInputItems ?? [];
  if (items.length === 0) return null;

  const mainItem = items[0];
  const thumbnailItems = items.filter((i) => i.id !== mainItem.id);
  const hasContent = mainItem.type === 'text' ? !!mainItem.text : !!mainItem.file;

  if (!hasContent && thumbnailItems.length === 0) return null;

  return (
    <div className="mt-3 space-y-1.5 w-full">
      {/* Main preview */}
      {mainItem.type === 'text' && mainItem.text && (
        <div>
          <span className="text-[10px] font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wider">{mainItem.label}</span>
          <p className="text-xs text-slate-500 dark:text-slate-400 whitespace-pre-wrap break-words leading-relaxed overflow-hidden">
            {mainItem.text}
          </p>
        </div>
      )}
      {mainItem.type === 'file' && mainItem.file && (
        <div>
          <span className="text-[10px] font-medium text-slate-400 dark:text-slate-500 uppercase tracking-wider">{mainItem.label}</span>
          <DataInputFileEntry file={mainItem.file} />
        </div>
      )}

      {/* Thumbnails row */}
      {thumbnailItems.length > 0 && (
        <div className="flex gap-1.5 overflow-x-auto pt-1 border-t border-theme">
          {thumbnailItems.map((item) => (
            <div key={item.id} className="flex-shrink-0 max-w-[60px]" title={item.label}>
              {item.type === 'text' && (
                <div className="w-[60px] h-[36px] rounded bg-slate-50 dark:bg-slate-800 px-1 py-0.5 overflow-hidden">
                  <span className="text-[9px] font-medium text-slate-400 dark:text-slate-500 block truncate">{item.label}</span>
                  <p className="text-[8px] text-slate-400 dark:text-slate-500 truncate leading-tight">{item.text || ''}</p>
                </div>
              )}
              {item.type === 'file' && item.file && (
                <DataInputThumbnail file={item.file} label={item.label} />
              )}
              {item.type === 'file' && !item.file && (
                <div className="w-[60px] h-[36px] rounded bg-slate-50 dark:bg-slate-800 px-1 py-0.5 overflow-hidden">
                  <span className="text-[9px] font-medium text-slate-400 dark:text-slate-500 truncate">{item.label}</span>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function DataInputThumbnail({ file, label }: { file: { path: string; name: string; mimeType: string; size: number; id?: string }; label: string }) {
  const isImage = file.mimeType.startsWith('image/');
  // Header-authenticated fetch → blob: URL (no session token in the URL). See useAuthedObjectUrl.
  const { url: imageUrl, error: imgError } = useAuthedObjectUrl(
    isImage && file.path ? (fileRefToUrl(file as any, { inline: true }) || null) : null,
  );

  if (isImage && imageUrl && !imgError) {
    return (
      <div className="w-[60px] h-[36px] rounded bg-slate-50 dark:bg-slate-800 overflow-hidden">
        <img
          src={imageUrl}
          alt={label}
          className="w-full h-full object-cover rounded"
        />
      </div>
    );
  }

  const IconComp = getDataInputFileIcon(file.mimeType);
  return (
    <div className="w-[60px] h-[36px] rounded bg-slate-50 dark:bg-slate-800 px-1 py-0.5 overflow-hidden flex flex-col items-center justify-center gap-0.5">
      <IconComp className="h-3 w-3 text-slate-400 dark:text-slate-500" />
      <span className="text-[8px] text-slate-400 dark:text-slate-500 truncate max-w-full">{label}</span>
    </div>
  );
}

function DataInputFileEntry({ file }: { file: { path: string; name: string; mimeType: string; size: number; id?: string } }) {
  const isImage = file.mimeType.startsWith('image/');
  // Header-authenticated fetch → blob: URL (no session token in the URL). See useAuthedObjectUrl.
  const { url: imageUrl, error: imgError } = useAuthedObjectUrl(
    isImage && file.path ? (fileRefToUrl(file as any, { inline: true }) || null) : null,
  );

  if (isImage && imageUrl && !imgError) {
    return (
      <img
        src={imageUrl}
        alt={file.name}
        className="w-full object-contain rounded"
      />
    );
  }

  const IconComp = getDataInputFileIcon(file.mimeType);
  return (
    <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
      <IconComp className="h-3 w-3 shrink-0" />
      <span className="truncate">{file.name}</span>
    </div>
  );
}

// ============================================================================
// File-producing Node Preview (canvas)
// Shared by download_file, convert_to_file, compression, sftp.
// Reuses DataInputFileEntry for consistent file display style.
// Dedupes (epoch, itemIndex) → max(spawn) so the navigator surfaces a clean
// iteration axis instead of every retry.
// ============================================================================

function FileNodePreview({
  data,
  setCurrentFile,
  selected = false,
  isStaticFileProducingNode,
}: {
  data: BuilderNodeData;
  setCurrentFile: React.Dispatch<React.SetStateAction<FilePanelTarget | null>>;
  selected?: boolean;
  isStaticFileProducingNode: boolean;
}) {
  const { isRunMode, workflowId, runId, viewingEpoch } = useWorkflowMode();
  const queryClient = useQueryClient();

  // Use normalized label (without prefix) to match DB step_alias.
  // DB stores normalizeLabel("Download File") = "download_file", NOT "core:download_file".
  const stepAlias = React.useMemo(
    () => normalizeLabel(data.label || ''),
    [data.label]
  );

  // Determine if node has completed execution
  const effectiveStatus = data.status;
  const isCompleted = effectiveStatus === 'completed';

  // Spawn item pagination - local to this node, resets when viewing epoch changes
  const [currentPage, setCurrentPage] = React.useState(0);
  React.useEffect(() => { setCurrentPage(0); }, [viewingEpoch]);

  // useRun MUST come before useRunOutputData because the gate predicate below
  // reads runState?.runStatus to decide whether to fetch. Hooks-order rule:
  // both unconditional, deterministic each render.
  const [runState] = useRun(isRunMode ? runId : undefined);

  // Fetch output data only in run mode when completed AND the gate predicate
  // says so. Predicate kills the prod 80-call mount storm: terminal-run
  // unselected MCP nodes don't fetch until clicked. Live runs + selected
  // node + static file types stay eager (see fileFetchPredicate.ts for the
  // full decision tree + rationale on FINISHED_STATUSES vs TERMINAL_STATUSES).
  // dedupeMaxSpawn collapses retries so the navigator scrolls a clean
  // (epoch, itemIndex) axis - same semantics as InterfaceRenderService.
  const { totalItems, currentIndex, currentItem, goToIndex, getObjectAtPath } = useRunOutputData({
    workflowId,
    runId: runId || undefined,
    stepAlias,
    epoch: viewingEpoch,
    enabled: shouldFetchFileOutput({
      isRunMode,
      isCompleted,
      selected,
      isStaticFileProducingNode,
      runStatus: runState?.runStatus,
    }),
    dedupeMaxSpawn: true,
  });
  const resolvedStepCount = React.useMemo(() => {
    if (!runState) return 0;
    const completed = runState.completedSteps?.size || 0;
    const failed = runState.failedSteps?.size || 0;
    const skipped = runState.skippedSteps?.size || 0;
    return completed + failed + skipped;
  }, [runState?.completedSteps?.size, runState?.failedSteps?.size, runState?.skippedSteps?.size]);

  const resolvedCountRef = React.useRef(resolvedStepCount);
  React.useEffect(() => {
    if (!isRunMode || !isCompleted) return;
    if (resolvedStepCount === 0 || resolvedStepCount === resolvedCountRef.current) return;
    resolvedCountRef.current = resolvedStepCount;

    const timeoutId = setTimeout(() => {
      queryClient.invalidateQueries({ queryKey: ['run-output-data'] });
    }, 1000);
    return () => clearTimeout(timeoutId);
  }, [isRunMode, isCompleted, resolvedStepCount, queryClient]);

  // Sync useRunOutputData index with shared page.
  // useRunOutputData now returns items in display order (oldest first, newest at items.length-1),
  // so the ItemNavigator "N / N" points to the most recent epoch. Default currentPage to the
  // newest position on first load (and whenever totalItems grows beyond the current page).
  const didInitRef = React.useRef(false);
  React.useEffect(() => {
    if (totalItems > 0 && !didInitRef.current) {
      setCurrentPage(totalItems - 1);
      didInitRef.current = true;
    }
  }, [totalItems]);
  React.useEffect(() => { didInitRef.current = false; }, [viewingEpoch, runId, stepAlias]);

  const targetIndex = totalItems > 0 ? Math.min(currentPage, totalItems - 1) : 0;
  React.useEffect(() => {
    if (totalItems > 0 && currentIndex !== targetIndex) {
      goToIndex(targetIndex);
    }
  }, [targetIndex, totalItems, currentIndex, goToIndex]);

  // Lazy-load the full output object for richer file data
  const [outputData, setOutputData] = React.useState<any>(null);

  // Reset outputData when epoch changes or current item changes so we reload
  const prevEpochRef = React.useRef(viewingEpoch);
  const prevItemIdRef = React.useRef(currentItem?.id);
  React.useEffect(() => {
    if (prevEpochRef.current !== viewingEpoch || prevItemIdRef.current !== currentItem?.id) {
      prevEpochRef.current = viewingEpoch;
      prevItemIdRef.current = currentItem?.id;
      setOutputData(null);
    }
  }, [viewingEpoch, currentItem?.id]);

  React.useEffect(() => {
    if (!isRunMode || !isCompleted || outputData) return;
    let cancelled = false;

    getObjectAtPath('').then((obj) => {
      if (!cancelled && obj) setOutputData(obj);
    }).catch(() => {});

    return () => { cancelled = true; };
  }, [isRunMode, isCompleted, getObjectAtPath, outputData]);

  // Extract the first FileRef from the output tree using the centralized
  // walker. Covers every shape: canonical FileRef under `output.file` for the
  // 4 producer nodes (download_file/sftp/convert_to_file/compression),
  // image_generation's data.images[]._type='file', create_image's
  // data[0].b64_json (post-dehydration), metadata.attachments[], and any
  // future catalog-tool fileRef field - without per-shape code here.
  //
  // {@code outputData} comes back from StepPayloadService wrapped with
  // envelope keys (_status, _duration_ms, _display_name, _error). The
  // load-bearing _status guard in isFileRef rejects those envelopes, so
  // for static file nodes (download_file et al.) the FileRef-shaped fields
  // sit AT the envelope's top level alongside _status. We strip envelope
  // keys before isFileRef-checking the root, then walk descendants for
  // tools that nest the FileRef deeper (image_generation, create_image).
  // Falls back to the step row's metadata blob when the lazy-loaded output
  // is not yet hydrated (some nodes persist a FileRef under metadata.file).
  const normalized = React.useMemo(() => {
    if (outputData && typeof outputData === 'object') {
      const stripped = { ...(outputData as Record<string, unknown>) };
      delete stripped._status;
      delete stripped._duration_ms;
      delete stripped._display_name;
      delete stripped._error;
      if (isFileRef(stripped)) return normalizeFileRef(stripped as any);
      const refs = findFileRefs(stripped);
      if (refs.length > 0) return normalizeFileRef(refs[0].fileRef);
    }
    if (currentItem?.metadata) {
      const refs = findFileRefs(currentItem.metadata);
      if (refs.length > 0) return normalizeFileRef(refs[0].fileRef);
    }
    return null;
  }, [outputData, currentItem]);

  // Sync the parent's currentFile state every time the resolved file changes;
  // clear on unmount or when the fetch is gated off (deselected MCP node on a
  // terminal run) so the bottom-bar Files button hides until the user reopens
  // the node - `selected` in deps fires this clear on deselect even though
  // the component itself stays mounted.
  React.useEffect(() => {
    setCurrentFile(normalized
      ? { path: normalized.path, id: (normalized as { id?: string }).id, name: normalized.name, mimeType: normalized.mimeType, size: normalized.size }
      : null);
    return () => { setCurrentFile(null); };
  }, [normalized, selected, setCurrentFile]);

  // Don't show anything if not in run mode or not completed
  if (!isRunMode || !isCompleted) return null;

  // No loading spinner below the node. The ['run-output-data'] query is
  // invalidated on every status event (each completion bumps resolvedStepCount),
  // so a spinner here reappeared - and grew/flickered the node - on every event.
  // Instead we render nothing while the file (re)fetches: an already-resolved
  // file stays visible (outputData persists across refetches), and the preview
  // simply appears once resolved. The full loading state lives in the inspector
  // panel (RunOutputPreview / RunDataPreview), not on the always-on canvas card.
  if (!normalized) return null;

  // Reuse DataInputFileEntry with max-height constraint for compact canvas display.
  // The per-item ItemNavigator that used to render below the node was removed:
  // multi-item navigation now lives in the inspector panel (RunOutputPreview /
  // RunDataPreview / ResolvedParamsView), where it's only rendered when the
  // user actually inspects a node. Saves a layer of always-on canvas chrome.
  return (
    <>
      <div className="mt-3 w-full [&>img]:max-h-12 [&>img]:rounded">
        <DataInputFileEntry file={normalized} />
      </div>
    </>
  );
}
