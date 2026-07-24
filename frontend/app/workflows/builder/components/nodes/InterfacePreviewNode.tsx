'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position, useReactFlow, useStore, useStoreApi } from 'reactflow';
import { Eye, EyeOff, AppWindow } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { ResizableNodeWrapper } from './ResizableNodeWrapper';
import { ItemNavigator } from '../inspector/outputs/ItemNavigator';

import type { BuilderNodeData, DerivedNodeStatus, NodeStatus } from '../../types';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { NodeActionButtons, NodeHeader, useHoverVisibility, getIconSlug, getStatusBorderColor } from './shared';
import { getNodeVisual } from '../../data/nodeVisuals';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useRun } from '@/contexts/WorkflowRunContext';
import { useInterfaceRender, useInterfaceById } from '../../hooks/useInterfaces';
import { InterfaceThumbnail } from '../interface/InterfaceThumbnail';
import { DEFAULT_FORMAT_VIEWPORT, resolveInterfaceFormat } from '@/lib/interfaces/interfaceFormats';
import {
  MIN_NODE_WIDTH,
  MAX_NODE_WIDTH,
  MIN_NODE_HEIGHT,
  MAX_NODE_HEIGHT,
  snapBoxToFormat,
} from '../../utils/interfaceNodeBox';
import type { RenderMode } from '../../utils/interfaceHtmlUtils';
import { translateWithMapping, mergeTriggerDataIntoResolved } from '../../utils/interfaceHtmlUtils';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';

import { useWorkflowLayoutDirectionSafe } from '@/contexts/WorkflowLayoutDirectionContext';
import { getSourceHandleGeometry, getTargetHandleGeometry, getSideAttachment } from './handleGeometry';
interface InterfacePreviewNodeProps extends NodeProps<BuilderNodeData> {
  onOpenFullscreen?: () => void;
}

/**
 * Run-mode snap verified-retry tuning: after dispatching the box write, wait this
 * long, check the ReactFlow store actually holds the snapped box, and re-arm the
 * effect if the dispatch was silently lost (see the effect for the two loss modes).
 * Bounded: a box that never lands stops retrying after MAX attempts.
 */
const RUN_SNAP_VERIFY_DELAY_MS = 250;
const MAX_RUN_SNAP_ATTEMPTS = 5;

export function InterfacePreviewNode({ data, selected, id }: InterfacePreviewNodeProps) {
  // Handle sides follow the canvas reading direction. Safe variant: nodes also
  // render on provider-less surfaces (marketplace preview, snapshots).
  const { direction: layoutDirection } = useWorkflowLayoutDirectionSafe();
  const targetHandle = getTargetHandleGeometry(layoutDirection);
  const sourceHandle = getSourceHandleGeometry(layoutDirection);

  const t = useTranslations('workflowBuilder.nodes');
  // "Item X / Y" semantic label for the spawn-item pager (run-mode context).
  const tRun = useTranslations('runMode');
  const { isRunMode, isPreviewOnly, isApplicationMode, runId, viewingEpoch } = useWorkflowMode();
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();

  // Step-by-step execution status - pass node data for accurate backend ID mapping
  const stepByStepStatus = useNodeExecutionStatus(id, {
    label: data.label,
    kind: data.kind,
  });

  // Determine effective status: use step-by-step context as source of truth in step-by-step mode
  // Otherwise fall back to data.status (streaming updates for automatic mode)
  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (stepByStepStatus.isStepByStepMode) {
      if (stepByStepStatus.isRunning) return 'running';
      if (stepByStepStatus.isFailed) return 'failed';
      if (stepByStepStatus.isSkipped) return 'skipped';
      if (stepByStepStatus.isCompleted) return 'completed';
      if (stepByStepStatus.isReady) return 'ready';
      return 'pending';
    }
    // Auto mode: check running override from streaming
    if (stepByStepStatus.isRunning) return 'running';
    return data.status;
  }, [stepByStepStatus, data.status]);

  // Subscribe to run state updates - this will re-render when interfaces change
  const [runState] = useRun(isRunMode ? runId : undefined);

  // Authoritative source: interfaceData.interfaceId (set at drop time). The node id
  // itself is a React Flow identifier (e.g. `interface-<uuid>-<timestamp>` on fresh
  // drop, or `interface-<uuid>--N` on duplicate) and must not be parsed as a UUID.
  const interfaceId = React.useMemo(() => {
    const fromData = (data as any)?.interfaceData?.interfaceId;
    if (fromData) return fromData;
    // Legacy fallback for older nodes persisted without interfaceData.interfaceId.
    if (id.startsWith('interface-')) {
      return id.replace('interface-', '').replace(/-\d{10,}$/, '').replace(/--\d+$/, '');
    }
    return null;
  }, [id, data]);

  // Load interface details from DB for edit mode (auto-loading template on page reload)
  const queryClient = useQueryClient();
  const { data: interfaceDetails, isLoading: isLoadingInterface } = useInterfaceById(
    !isRunMode && interfaceId ? interfaceId : null
  );

  // Auto-load template from DB when interface is loaded (only once per mount/reload)
  // Tracks the interfaceId we've loaded for, resets when editorExpression becomes empty
  const loadedTemplateForRef = React.useRef<string | null>(null);

  // Live sync: when LLM updates interface via chat, invalidate cache and reload template
  React.useEffect(() => {
    if (!interfaceId) return;
    const handleInterfaceModified = () => {
      // Invalidate React Query cache so useInterfaceById re-fetches from DB
      queryClient.invalidateQueries({ queryKey: ['interface', interfaceId] });
      // Reset the loaded ref so the useEffect below will reload the template
      loadedTemplateForRef.current = null;
    };
    window.addEventListener('interfaceModified', handleInterfaceModified);
    return () => window.removeEventListener('interfaceModified', handleInterfaceModified);
  }, [interfaceId, queryClient]);

  // Get interface data from local node data (for edit mode)
  const interfaceData = (data as any)?.interfaceData || {};
  const editorExpression = interfaceData.editorExpression || '';
  const hasActionMapping = interfaceData?.actionMapping && Object.keys(interfaceData.actionMapping).length > 0;

  // Reset when editorExpression becomes empty (workflow reload clears it)
  if (!editorExpression && loadedTemplateForRef.current === interfaceId) {
    loadedTemplateForRef.current = null;
  }

  const shouldLoadTemplate = !isRunMode &&
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
          dataSourceId: interfaceDetails!.dataSourceId ?? null,
        },
      });
    } else if (hasLocalTemplate) {
      // Mark as loaded, but still sync dataSourceId if missing
      loadedTemplateForRef.current = interfaceId!;
      if (interfaceDetails!.dataSourceId != null && interfaceData.dataSourceId == null) {
        data.onNodeUpdate!({
          ...data,
          interfaceData: {
            ...interfaceData,
            dataSourceId: interfaceDetails!.dataSourceId,
          },
        });
      }
    }
  }, [shouldLoadTemplate, interfaceId, interfaceDetails, editorExpression, interfaceData, data]);

  // Spawn item pagination - local to this node, resets when viewing epoch changes
  const [currentPage, setCurrentPage] = React.useState(0);
  React.useEffect(() => { setCurrentPage(0); }, [viewingEpoch]);

  // Fetch interface render data in run mode
  const { data: renderData, isLoading, error, refetch } = useInterfaceRender(
    isRunMode ? interfaceId : null,
    isRunMode ? runId : null,
    currentPage,
    1, // One item per page for node preview
    viewingEpoch ?? undefined
  );

  // The INTERFACE's display format -> thumbnail virtual viewport, so the canvas node has the
  // same shape as the capture. The render result wins over the entity: a run reads its frozen
  // snapshot, and in public preview the entity fetch is disabled entirely (useInterfaceById is
  // gated off there), so the render is the only source. undefined keeps the thumbnail's classic
  // 1280x800 default (no declared format, or nothing loaded yet).
  const formatViewport = React.useMemo(
    () => resolveInterfaceFormat(renderData?.format ?? interfaceDetails?.format) ?? undefined,
    [renderData?.format, interfaceDetails?.format]
  );

  // Debounced refetch when execution state changes.
  // Uses executionTotal (monotonically increasing sum of all per-node statusCounts)
  // instead of resolvedStepCount (Set-based, unreliable across epoch resets).
  // executionTotal catches: new epochs, reruns, upstream re-executions.
  const executionTotal = runState?.executionTotal ?? 0;

  const execTotalRef = React.useRef(executionTotal);
  React.useEffect(() => {
    if (!isRunMode) return;

    if (executionTotal === 0 || executionTotal === execTotalRef.current) {
      return;
    }

    execTotalRef.current = executionTotal;

    const timeoutId = setTimeout(() => {
      refetch().catch(() => {});
    }, 1000);

    return () => {
      clearTimeout(timeoutId);
    };
  }, [isRunMode, executionTotal, refetch]);

  // Get CSS/JS templates: render API (run mode) → node data → DB
  const cssTemplate = renderData?.cssTemplate || interfaceData.cssTemplate || interfaceDetails?.cssTemplate || '';
  const jsTemplate = renderData?.jsTemplate || interfaceData.jsTemplate || interfaceDetails?.jsTemplate || '';

  const localTemplate = editorExpression;

  // Determine which template and data to use
  // Priority: 1. API render response (available in run mode), 2. Local node data (edit mode)
  // Note: renderData is only populated when isRunMode=true (query passes null otherwise),
  // so we don't need to gate on isRunMode here - makes it resilient to mode transitions.
  const htmlTemplate = React.useMemo(() => {
    if (renderData?.htmlTemplate) {
      return renderData.htmlTemplate;
    }
    return localTemplate;
  }, [renderData?.htmlTemplate, localTemplate]);

  const currentItem = isRunMode && renderData?.items?.[0] ? renderData.items[0] : null;
  const totalPages = renderData?.pagination?.totalPages ?? 0;

  // Get variable mapping from node data (for translating workflow expressions to generic names)
  const variableMapping = (data as any)?.interfaceData?.variableMapping as Record<string, string> | undefined;

  // Get trigger data for merging into resolved data
  const triggerData = React.useMemo(() => {
    if (!isRunMode || !currentItem) return undefined;
    return currentItem.triggerData as Record<string, Record<string, unknown>> | undefined;
  }, [isRunMode, currentItem]);

  // Get resolved item data for run mode (from render API response)
  const itemData = React.useMemo(() => {
    if (!isRunMode) return undefined;

    const apiData = currentItem?.data;
    if (!apiData) return undefined;

    let rawData = apiData as Record<string, unknown>;

    // Apply mapping translation
    if (variableMapping) {
      rawData = translateWithMapping(rawData, variableMapping);
    }

    // Merge trigger data so {{trigger:name.output.field}} resolves in templates
    return mergeTriggerDataIntoResolved(rawData, triggerData) as Record<string, unknown> | undefined;
  }, [isRunMode, currentItem, variableMapping, triggerData]);

  // Epoch nav: page 0 = newest. ItemNavigator handles left/right naturally.
  // currentPage maps to ItemNavigator index: 0 = most recent = "Epoch 1".

  // Determine render mode. In run mode without actual item data, fall back to
  // "edit" so `{{xxx|default}}` pipe defaults show instead of raw `{{…}}`.
  const hasItemData = !!itemData && Object.keys(itemData).length > 0;
  const renderMode: RenderMode = isRunMode && hasItemData ? 'run' : 'edit';

  // Check if preview mode is enabled
  // Priority: explicit isPreviewMode (set by usePreparedGraph) > showPreview from interfaceData > run mode default
  // Default to preview ON (showPreview !== false) to avoid compact-mode flash during sync transitions
  const isPreviewMode = (data as any).isPreviewMode !== undefined
    ? (data as any).isPreviewMode === true
    : (interfaceData?.showPreview !== false || isRunMode);

  // Snap the node box to the interface's declared format so the node IS the format:
  // the content fills it edge-to-edge (no internal letterbox) and the ring, when shown,
  // hugs the real shape. This effect PERSISTS the box (interfaceData.previewWidth/
  // Height, which useGraphOperations mirrors into the node's style), so it stays
  // edit-only: a write in run mode would dirty a workflow the user is only watching.
  // Run mode gets the same shape from the local-only effect below.
  const effectiveViewport = formatViewport ?? DEFAULT_FORMAT_VIEWPORT;
  React.useEffect(() => {
    if (isRunMode || !isPreviewMode || !data.onNodeUpdate || isLoadingInterface) return;
    // Yield to the template-load effect: both spread the same (stale) `data`, so a snap
    // update fired in the same commit would wholesale-replace the update that just wrote
    // editorExpression. Wait until the template state is settled, then snap.
    const templateLoadPending =
      !editorExpression && !!(interfaceDetails?.htmlTemplate || interfaceDetails?.editorExpression);
    if (templateLoadPending) return;
    const prevW = interfaceData.previewWidth as number | undefined;
    const prevH = interfaceData.previewHeight as number | undefined;
    const { width: w, height: h } = snapBoxToFormat(effectiveViewport, { width: prevW, height: prevH });
    // 1px tolerance so resize rounding never fights the snap in a loop.
    if (prevW != null && prevH != null && Math.abs(w - prevW) <= 1 && Math.abs(h - prevH) <= 1) return;
    data.onNodeUpdate({ ...data, interfaceData: { ...interfaceData, previewWidth: w, previewHeight: h } });
  }, [effectiveViewport, isRunMode, isPreviewMode, isLoadingInterface, editorExpression, interfaceDetails, interfaceData, data]);

  // Same snap for RUN mode, applied to the live node box only - never persisted.
  // The persisted box is written by the edit-mode effect above, so it is only ever
  // right for a workflow that has been OPENED in the builder since its interface
  // got its format. A run opened straight in run mode (agent-triggered via the MCP
  // execute action, the runs list, a shared link) renders whatever the plan stored,
  // and a plan that never went through edit mode carries the importer's historical
  // 400x250 default: the vertical interface then sat letterboxed in the middle of a
  // landscape card. Snapping here makes the node the format in EVERY entry point.
  //
  // The CURRENT box is read from the ReactFlow store, not from a setNodes callback
  // argument, for two reasons: (1) it puts the "already the right shape?" check in
  // the effect, so setNodes is dispatched ONLY on a real change - `setNodes` in a
  // controlled flow emits a `reset` change for every node, which replaces the whole
  // array, so an unconditional dispatch on every render would be a needless chance
  // to clobber a status update queued in the same batch; (2) it makes the box part
  // of the deps, so a wholesale rebuild of the nodes back to the stored 400x250
  // (a workflow refetch while still in run mode) re-fires this and self-heals,
  // instead of leaving the letterbox back for good.
  const { setNodes } = useReactFlow();
  // Serialized so the selector returns a primitive: an object would be a new
  // identity on every store tick and re-run the effect forever.
  const storeBox = useStore((s) => {
    const style = s.nodeInternals.get(id)?.style;
    const w = typeof style?.width === 'number' ? style.width : '';
    const h = typeof style?.height === 'number' ? style.height : '';
    return `${w}x${h}`;
  });
  const storeApi = useStoreApi();
  // Re-arms the snap effect when a dispatched write is detected as LOST (see below).
  const [snapRetryTick, setSnapRetryTick] = React.useState(0);
  const snapAttemptsRef = React.useRef(0);
  React.useEffect(() => {
    // No declared format resolved (yet, or at all): keep the persisted box rather
    // than reshape a legacy free-form node to the 1280x800 default behind the user.
    // Leaving the armed state (exiting run mode, format unresolving) also clears the
    // attempt counter, so re-entering run mode always starts with fresh retries.
    if (!isRunMode || !isPreviewMode || !formatViewport) {
      snapAttemptsRef.current = 0;
      return;
    }
    const [rawW, rawH] = storeBox.split('x');
    const prevW = rawW ? Number(rawW) : undefined;
    const prevH = rawH ? Number(rawH) : undefined;
    const { width, height } = snapBoxToFormat(formatViewport, { width: prevW, height: prevH });
    // 1px tolerance, same as the edit-mode snap: resize rounding must not fight it.
    // This is also what terminates the loop - the snapped box feeds back through
    // storeBox, matches, and stops here.
    if (prevW != null && prevH != null && Math.abs(width - prevW) <= 1 && Math.abs(height - prevH) <= 1) {
      snapAttemptsRef.current = 0;
      return;
    }
    setNodes((nodes) => nodes.map((n) => (n.id === id ? { ...n, style: { ...n.style, width, height } } : n)));
    // VERIFIED RETRY - the dispatch above can be silently LOST while storeBox stays
    // unchanged, in which case this effect never re-fires on its own and the node
    // stays letterboxed in the importer's 400x250 for good (reproduced under CPU
    // throttle, WF-AUTH-026). Two loss modes, both one-shot races on page load:
    // (1) useReactFlow().setNodes on a CONTROLLED flow only EMITS `reset` changes
    //     through the store's onNodesChange, which is not registered yet when this
    //     effect fires during a mount commit (child effects run before ReactFlow's
    //     own prop-to-store effects) - the emission is a silent no-op;
    // (2) the reset lands in the parent state but a concurrent stale parent write
    //     batched into the same render wipes it before ReactFlow syncs the store.
    // So: verify the write actually landed and re-arm for another attempt if not.
    // Bounded so a box that can genuinely never be written cannot retry forever;
    // the attempt counter resets whenever the box is observed matching, keeping the
    // pre-existing self-heal (a mid-run plan rebuild) on fresh retries.
    if (snapAttemptsRef.current >= MAX_RUN_SNAP_ATTEMPTS) return;
    snapAttemptsRef.current += 1;
    const timer = window.setTimeout(() => {
      const style = storeApi.getState().nodeInternals.get(id)?.style as React.CSSProperties | undefined;
      const w = typeof style?.width === 'number' ? style.width : undefined;
      const h = typeof style?.height === 'number' ? style.height : undefined;
      const landed = w != null && h != null && Math.abs(width - w) <= 1 && Math.abs(height - h) <= 1;
      if (!landed) setSnapRetryTick((t) => t + 1);
    }, RUN_SNAP_VERIFY_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [isRunMode, isPreviewMode, formatViewport, id, storeBox, setNodes, storeApi, snapRetryTick]);

  // Get node visuals for compact view - always use 'interface' kind for interface nodes
  const visuals = getNodeVisual('interface');

  const borderColor = getStatusBorderColor(effectiveStatus, undefined, undefined, data.statusCounts);
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Ring policy for the format preview: no ring by default (the idle grey border is
  // gone). A status color still rings the REAL format frame, and selection adds the
  // accent ring inside it - both hug the interface's declared format, never a letterbox.
  // INSET shadows are mandatory here: the frame sits inside overflow-hidden ancestors
  // (the thumbnail's contain container, the content wrapper) that are the SAME size as
  // the frame once the box is snapped, so an outward ring would be clipped invisible.
  const statusRingColor = borderColor !== 'var(--border-color)' ? borderColor : null;
  const frameRing = statusRingColor
    ? selected
      ? `inset 0 0 0 2px ${statusRingColor}, inset 0 0 0 4px var(--accent-primary)`
      : `inset 0 0 0 2px ${statusRingColor}`
    : selected
      ? 'inset 0 0 0 2px var(--accent-primary)'
      : undefined;

  // Application mode handler (dispatch event to parent)
  const handleOpenApplicationMode = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    window.dispatchEvent(new CustomEvent('openApplicationMode', {
      detail: {
        interfaceId,
        actionMapping: interfaceData.actionMapping || {},
      }
    }));
  }, [interfaceId, interfaceData.actionMapping]);

  // Determine if we're showing HTML content (not a placeholder state).
  // Placeholder states (loading, error, no template) keep the bordered card in EVERY
  // mode, so a run-mode spinner or error message never floats chrome-less on the canvas;
  // only real content renders in the transparent, format-hugging frame.
  const isShowingHtml = !!htmlTemplate && !error && !isLoading;

  // Check if node is running (for shimmer animation) - show shimmer in all modes
  const isNodeRunning = effectiveStatus === 'running';

  // Compact view when not in preview mode (edit mode only)
  // Same structure as FlowNode for consistency
  if (!isPreviewMode) {
    return (
      <div
        ref={nodeRef}
        className={clsx(
          'group relative rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4 backdrop-blur border-2',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
          'transition-colors',
          isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
          isSkipped && selected && 'opacity-100',
        )}
        style={{
          borderColor,
          borderStyle: 'solid',
          boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
        }}
        tabIndex={0}
      >
        {/* Shimmer effect when running */}
        {isNodeRunning && (
          <div
            className="absolute inset-0 pointer-events-none rounded-[26px]"
            style={{
              background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
              backgroundSize: '200% 100%',
              animation: 'shimmer-scan 2.5s ease-in-out infinite',
            }}
          />
        )}

        {/* Node header - same as FlowNode */}
        <NodeHeader
          visuals={visuals}
          label={data.label}
          iconSlug={getIconSlug(data)}
          nodeId={data.id || ''}
          nodeKind="interface"
        />

        {/* Status badge */}
        <div className="absolute bottom-2 right-2">
          <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
        </div>

        {/* Action buttons (delete, duplicate, preview toggle) - edit mode only */}
        <NodeActionButtons
          isVisible={showActions}
          onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(id) : undefined}
          onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(id) : undefined}
          onTogglePreview={data.onTogglePreview ? () => data.onTogglePreview?.(id) : undefined}
          isPreviewMode={false}
          showPreviewButton={true}
          onHover={show}
        />

        {/* Centralized bottom bar: interface button + play/rerun.
            Hidden in isPreviewOnly (marketplace preview): no play/rerun is
            relevant on a frozen showcase clone.
            Hidden in isApplicationMode too: inside an application the app is
            ALREADY the main view, so the 'interface' button (which opens the
            same app in the application panel) would just duplicate it left +
            right. It stays visible in workflow surfaces, where opening the app
            in the panel is the useful affordance. */}
        {isRunMode && !isPreviewOnly && !isApplicationMode && (
          <NodeBottomBar
            hover={{ isVisible: showActions, onHover: show }}
            borderColor={borderColor}
            isRunning={isNodeRunning}
            extraOffset={viewingEpoch != null && totalPages > 1}
            buttons={[{
              key: 'interface',
              icon: <AppWindow className="h-3 w-3" strokeWidth={2} />,
              title: t('applicationMode'),
              onClick: () => { if (interfaceId) window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', { detail: { interfaceId } })); },
            }]}
            playButton={stepByStepStatus.isStepByStepMode ? {
              nodeId: id,
              variant: 'play',
              isAutoMode: false,
              isTriggerNode: false,
              stepByStepStatus,
            } : undefined}
          />
        )}

        {/* Pagination controls - below node (spawn items only, not epochs) */}
        {isRunMode && viewingEpoch != null && totalPages > 1 && (
          <div
            className="absolute nodrag nopan"
            style={getSideAttachment(layoutDirection, 8)}
            onMouseDown={(e) => e.stopPropagation()}
            onClick={(e) => e.stopPropagation()}
          >
            <ItemNavigator
              currentIndex={currentPage}
              totalItems={totalPages}
              onIndexChange={setCurrentPage}
              itemLabel={tRun('itemLabel')}
            />
          </div>
        )}

        {/* Target handle (left) */}
        <Handle
          type="target"
          position={targetHandle.position}
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            ...targetHandle.style,
            backgroundColor: 'var(--border-color)',
          }}
        />

        {/* Source handle (right) */}
        <Handle
          type="source"
          position={sourceHandle.position}
          id="source-right"
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            ...sourceHandle.style,
            backgroundColor: 'var(--border-color)',
          }}
        />

      </div>
    );
  }

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative',
        // The showing-HTML node has NO permanent chrome: the interface frame carries the
        // rounding and (when relevant) the status/selection ring. Placeholder states keep
        // the classic bordered card so the node stays visible with nothing to render.
        isShowingHtml
          ? 'rounded-xl'
          : 'rounded-[28px] border-2 bg-white/95 dark:bg-gray-800/95 backdrop-blur',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'transition-colors p-0',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        ...(isShowingHtml ? {} : { borderColor, borderStyle: 'solid' }),
        boxShadow: !isShowingHtml && selected ? '0 0 0 2px var(--accent-primary)' : 'none',
        minWidth: '100px',
        minHeight: '80px',
        width: '100%',
        height: '100%',
        overflow: 'visible',
      }}
      tabIndex={0}
    >
      {/* Content - fixed viewport scaled down via CSS transform */}
      <div
        className={clsx(
          'w-full h-full relative',
          !isShowingHtml && 'overflow-hidden rounded-[26px] border border-theme bg-[var(--bg-primary)]'
        )}
        style={{ pointerEvents: 'none' }}
      >
        {isLoading ? (
          <div className="flex items-center justify-center h-full">
            <LoadingSpinner size="sm" />
          </div>
        ) : error ? (
          <div className="flex items-center justify-center h-full text-red-500 text-sm p-4">
            {t('errorLoadingInterface')}
          </div>
        ) : !htmlTemplate ? (
          <div className="flex items-center justify-center h-full text-slate-400 dark:text-slate-500 text-sm p-4">
            {t('noTemplateConfigured')}
          </div>
        ) : (
          <div className="w-full h-full relative overflow-hidden">
            <InterfaceThumbnail
              htmlTemplate={htmlTemplate}
              mode={renderMode}
              resolvedData={hasItemData ? itemData : undefined}
              customCss={cssTemplate}
              jsTemplate={jsTemplate}
              fit="contain"
              viewport={formatViewport}
              // The frame is the interface's REAL format (vp * scale): round + clip the
              // content there, and draw the status/selection ring on it so the ring hugs
              // the true shape even when the node box does not match (legacy run data).
              frameClassName="rounded-xl overflow-hidden"
              frameStyle={frameRing ? { boxShadow: frameRing } : undefined}
              // Forward bridge inputs in run mode so prefillForms() populates
              // form fields with the trigger's previous values. The overlay
              // div below intercepts pointer events so the bridge's submit/
              // click listeners stay inert (no parent onAction handler is
              // attached anyway). Inputs are display-only inside the canvas.
              actionMapping={renderMode === 'run' ? interfaceData?.actionMapping : undefined}
              triggerData={renderMode === 'run' ? triggerData : undefined}
            />
            {/* Overlay: captures all pointer events for node drag/double-click, blocks iframe interaction */}
            <div className="absolute inset-0 z-10" />
          </div>
        )}
      </div>

      {/* Shimmer effect when running */}
      {isNodeRunning && (
        <div
          className="absolute inset-0 pointer-events-none rounded-xl"
          style={{
            background: 'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
            backgroundSize: '200% 100%',
            animation: 'shimmer-scan 2.5s ease-in-out infinite',
          }}
        />
      )}

      {/* Pagination controls - below node (spawn items only, not epochs) */}
      {isRunMode && viewingEpoch != null && totalPages > 1 && (
        <div
          className="absolute nodrag nopan z-20"
          style={getSideAttachment(layoutDirection, 8)}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={(e) => e.stopPropagation()}
        >
          <ItemNavigator
            currentIndex={currentPage}
            totalItems={totalPages}
            onIndexChange={setCurrentPage}
            itemLabel={tRun('itemLabel')}
          />
        </div>
      )}

      {/* Status badge - render wrapper only when there are real counts to show,
          otherwise we get an empty rounded pill floating at the bottom-right. */}
      {(() => {
        const sc = data.statusCounts;
        const hasCounts = !!sc && (
          (sc.COMPLETED ?? 0) + (sc.SUCCESS ?? 0) +
          (sc.FAILED ?? 0) + (sc.ERROR ?? 0) +
          (sc.RUNNING ?? 0) + (sc.RETRYING ?? 0) +
          (sc.SKIPPED ?? 0) +
          (sc.AWAITING_SIGNAL ?? 0) + ((sc as any).awaitingSignal ?? 0)
        ) > 0;
        if (!hasCounts) return null;
        return (
          <div className={`absolute bottom-2 right-2 z-20 ${isShowingHtml ? 'bg-white/90 dark:bg-gray-800/90 rounded-full px-1.5 py-0.5 backdrop-blur-sm' : ''}`}>
            <NodeStatusBadge status={effectiveStatus} statusCounts={sc} />
          </div>
        );
      })()}

      {/* Action buttons (delete, duplicate, preview toggle) - edit mode only */}
      <NodeActionButtons
        isVisible={showActions}
        onDelete={data.onDeleteNode ? () => data.onDeleteNode?.(id) : undefined}
        onDuplicate={data.onDuplicateNode ? () => data.onDuplicateNode?.(id) : undefined}
        onTogglePreview={undefined}
        isPreviewMode={true}
        showPreviewButton={false}
        onHover={show}
      />

      {/* Centralized bottom bar: interface button + play/rerun (isShowingHtml path).
          Hidden in isPreviewOnly / isApplicationMode - same reasoning as the
          non-showing-html branch above. */}
      {isRunMode && !isPreviewOnly && !isApplicationMode && (
        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={isNodeRunning}
          buttons={[{
            key: 'interface',
            icon: <AppWindow className="h-3 w-3" strokeWidth={2} />,
            title: t('applicationMode'),
            onClick: () => { if (interfaceId) window.dispatchEvent(new CustomEvent('workflowOpenApplicationTab', { detail: { interfaceId } })); },
          }]}
          playButton={stepByStepStatus.isStepByStepMode ? {
            nodeId: id,
            variant: 'play',
            isAutoMode: false,
            isTriggerNode: false,
            stepByStepStatus,
          } : undefined}
        />
      )}

      {/* NodeResizer - 4 sides + 4 corners, visible on hover/selection.
          keepAspectRatio: the box was snapped to the interface's declared format, so a
          resize preserves that exact shape (the ratio is the current box's ratio). */}
      <ResizableNodeWrapper
        enabled={true}
        minWidth={MIN_NODE_WIDTH}
        maxWidth={MAX_NODE_WIDTH}
        minHeight={MIN_NODE_HEIGHT}
        maxHeight={MAX_NODE_HEIGHT}
        keepAspectRatio
        onResizeEnd={(w, h) => {
          data.onNodeUpdate?.({
            ...data,
            interfaceData: { ...interfaceData, previewWidth: w, previewHeight: h },
          });
        }}
      />

      {/* Target handle (left) */}
      <Handle
        type="target"
        position={targetHandle.position}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...targetHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

      {/* Source handle (right) */}
      <Handle
        type="source"
        position={sourceHandle.position}
        id="source-right"
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          ...sourceHandle.style,
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

    </div>
  );
}
