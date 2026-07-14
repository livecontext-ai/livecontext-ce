'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';
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
import type { RenderMode } from '../../utils/interfaceHtmlUtils';
import { translateWithMapping, mergeTriggerDataIntoResolved } from '../../utils/interfaceHtmlUtils';
import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodePlayButton, deriveNodeStatus } from '../NodePlayButton';
import { NodeBottomBar } from './NodeBottomBar';

interface InterfacePreviewNodeProps extends NodeProps<BuilderNodeData> {
  onOpenFullscreen?: () => void;
}

export function InterfacePreviewNode({ data, selected, id }: InterfacePreviewNodeProps) {
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

  // Get node visuals for compact view - always use 'interface' kind for interface nodes
  const visuals = getNodeVisual('interface');

  const borderColor = getStatusBorderColor(effectiveStatus, undefined, undefined, data.statusCounts);
  const isSkipped = !stepByStepStatus.isStepByStepMode && effectiveStatus === 'skipped';

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

  // Determine if we're showing HTML content (not a placeholder state)
  // In run mode: always transparent container (no border/bg), whether loading or showing content
  // In edit mode: transparent when we have template and no error
  const isShowingHtml = isRunMode || (htmlTemplate && !error);

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
            extraTopOffset={viewingEpoch != null && totalPages > 1}
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
            className="absolute left-1/2 -translate-x-1/2 nodrag nopan"
            style={{ top: 'calc(100% + 8px)' }}
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
          position={Position.Left}
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            left: -6,
            top: '50%',
            transform: 'translateY(-50%)',
            backgroundColor: 'var(--border-color)',
          }}
        />

        {/* Source handle (right) */}
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
          }}
        />

      </div>
    );
  }

  return (
    <div
      ref={nodeRef}
      className={clsx(
        'group relative rounded-[28px] border-2',
        // Only show opaque background when NOT showing HTML content
        !isShowingHtml && 'bg-white/95 dark:bg-gray-800/95 backdrop-blur',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
        'transition-colors p-0',
        isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
        isSkipped && selected && 'opacity-100',
      )}
      style={{
        borderColor,
        borderStyle: 'solid',
        boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
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
          'w-full h-full overflow-hidden relative rounded-[26px]',
          !isShowingHtml && 'border border-theme bg-[var(--bg-primary)]'
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
          className="absolute inset-0 pointer-events-none rounded-[26px]"
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
          className="absolute left-1/2 -translate-x-1/2 nodrag nopan z-20"
          style={{ top: 'calc(100% + 8px)' }}
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

      {/* NodeResizer - 4 sides + 4 corners, visible on hover/selection */}
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

      {/* Target handle (left) */}
      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
        style={{
          left: -6,
          top: '50%',
          transform: 'translateY(-50%)',
          backgroundColor: 'var(--border-color)',
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

      {/* Source handle (right) */}
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
          opacity: isRunMode ? 0 : 1,
          pointerEvents: isRunMode ? 'none' : 'auto'
        }}
      />

    </div>
  );
}
