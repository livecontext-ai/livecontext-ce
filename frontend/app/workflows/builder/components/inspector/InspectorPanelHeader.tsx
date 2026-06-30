'use client';

import * as React from 'react';
import clsx from 'clsx';
import { X, GripVertical, Minimize2, Maximize2, Maximize, Copy, Trash2, Eye, Play, RotateCcw, Minus, MoreVertical, Flag } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import type { Node } from 'reactflow';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTranslations } from 'next-intl';
import type { BuilderNodeData } from '../../types';
import type { DataSource } from '../../hooks/useDataSourceData';
import { getIconSlug, NodeIcon } from '../nodes/shared';
import { matchNodeClass } from '../../nodes/nodeClasses';
import { ViewModeTabs, ViewMode } from './ViewModeTabs';
import { AvatarDisplay } from '@/components/agents/AvatarPicker';
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover';

/**
 * Step-by-step execution status
 */
interface StepByStepStatus {
  isStepByStepMode: boolean;
  canExecute: boolean;
  isExecuting: boolean;
  canRerun: boolean;
  isRerunning: boolean;
  executeStep: () => void;
  rerunStep: () => void;
}

export interface InspectorPanelHeaderProps {
  // Node data
  node: Node<BuilderNodeData>;
  data: BuilderNodeData;
  nodeFamily?: string;
  nodeKind?: string;

  // Layout flags
  isRunMode: boolean;
  isFullscreen: boolean;
  isAdvanced: boolean;

  // Node type flags
  isTriggerNode: boolean;
  isInterfaceNode: boolean;
  shouldForceSmallMode: boolean;

  // Navigation state
  triggerNavigationLevel: string;
  selectedDataSourceId: number | null;
  dataSources: DataSource[];

  // View mode
  viewMode: ViewMode;
  onViewModeChange: (mode: ViewMode) => void;

  // Centralized execution data toggle
  showExecutionData: boolean;
  onShowExecutionDataChange: (show: boolean) => void;
  canShowExecutionDataToggle: boolean;

  // Step-by-step execution
  stepByStepStatus: StepByStepStatus;
  hasGlobalValidationErrors: boolean;

  // Handlers
  onUpdate: (data: BuilderNodeData) => void;
  onDeleteNode?: (nodeId: string) => void;
  onDuplicateNode?: (nodeId: string) => void;
  onAdvancedChange?: (advanced: boolean) => void;
  onFullscreenChange?: (fullscreen: boolean) => void;
  onClose?: () => void;
  onDragHandleMouseDown?: (e: React.MouseEvent) => void;
  onMinimize?: () => void;
  onReportNode?: () => void;
}

/**
 * InspectorPanelHeader - Header component for the inspector panel
 *
 * Contains:
 * - Drag handle (desktop only, when not fullscreen)
 * - Node icon
 * - Editable title
 * - ViewModeTabs (run mode only)
 * - Action buttons (duplicate, delete, preview, fullscreen, play, close)
 */
export function InspectorPanelHeader({
  node,
  data,
  nodeFamily,
  nodeKind,
  isRunMode,
  isFullscreen,
  isAdvanced,
  isTriggerNode,
  isInterfaceNode,
  shouldForceSmallMode,
  triggerNavigationLevel,
  selectedDataSourceId,
  dataSources,
  viewMode,
  onViewModeChange,
  showExecutionData,
  onShowExecutionDataChange,
  canShowExecutionDataToggle,
  stepByStepStatus,
  hasGlobalValidationErrors,
  onUpdate,
  onDeleteNode,
  onDuplicateNode,
  onAdvancedChange,
  onFullscreenChange,
  onClose,
  onDragHandleMouseDown,
  onMinimize,
  onReportNode,
}: InspectorPanelHeaderProps) {
  const t = useTranslations('workflowBuilder.inspector');

  // Compute display label based on navigation state
  const displayLabel = React.useMemo(() => {
    if (isTriggerNode && triggerNavigationLevel === 'datasources') {
      return t('tables');
    }
    if (isTriggerNode && triggerNavigationLevel === 'tables') {
      return dataSources.find(ds => ds.id === selectedDataSourceId)?.name || t('tables');
    }
    return data.label;
  }, [isTriggerNode, triggerNavigationLevel, dataSources, selectedDataSourceId, data.label]);

  // Compute display kind based on navigation state and node class
  const displayKind = React.useMemo(() => {
    if (isTriggerNode && (triggerNavigationLevel === 'datasources' || triggerNavigationLevel === 'tables')) {
      return 'TABLES';
    }
    const nodeClass = matchNodeClass(data);
    return nodeClass?.label || nodeKind || data.kind;
  }, [isTriggerNode, triggerNavigationLevel, data, nodeKind]);

  // Handle label change
  const handleLabelChange = React.useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (isTriggerNode && (triggerNavigationLevel === 'datasources' || triggerNavigationLevel === 'tables')) {
      return;
    }
    const { validationIssues, ...rest } = data as any;
    onUpdate({ ...rest, label: event.target.value });
  }, [isTriggerNode, triggerNavigationLevel, data, onUpdate]);

  // Is title read-only
  const isTitleReadOnly = isTriggerNode && nodeKind === 'entry' && (triggerNavigationLevel === 'datasources' || triggerNavigationLevel === 'tables');

  // Handle step-by-step start in edit mode
  const handleStartStepByStep = React.useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    if (isTriggerNode && !hasGlobalValidationErrors) {
      window.dispatchEvent(new CustomEvent('workflowStartStepByStep', {
        detail: { startFromNode: node.id }
      }));
    }
  }, [isTriggerNode, hasGlobalValidationErrors, node.id]);

  return (
    <div className={clsx(
      "flex gap-3 px-5 pt-5 pb-3 relative group/header flex-shrink-0",
      viewMode === 'result' ? "items-start" : "items-center"
    )}>
      {/* Drag handle - visible only on desktop and when not fullscreen */}
      {!isFullscreen && onDragHandleMouseDown && (
        <div
          data-drag-handle
          onMouseDown={onDragHandleMouseDown}
          className="absolute left-0 top-0 bottom-0 w-6 cursor-grab active:cursor-grabbing rounded-l-[32px] flex items-center justify-center lg:flex hidden opacity-0 group-hover/inspector:opacity-100 transition-opacity hover:bg-slate-100 dark:hover:bg-slate-800"
          title={t('dragToMove')}
        >
          <GripVertical className="h-4 w-4 text-slate-400 dark:text-slate-500" />
        </div>
      )}

      {/* Icon container - avatar for agent nodes, NodeIcon for everything else */}
      {(data as any)?.agentAvatarUrl ? (
        <AvatarDisplay avatarUrl={(data as any).agentAvatarUrl} name={data.label} size="lg" className="w-11 h-11" />
      ) : (
        <NodeIcon
          iconSlug={getIconSlug(data)}
          nodeId={data?.id || ''}
          nodeKind={(nodeKind || data.kind) as any}
          nodeFamily={nodeFamily}
          alt={data.label}
          size="lg"
        />
      )}

      {/* Title section */}
      <div className="flex-1 min-w-0">
        <div className="mb-1 h-5">
          {(data as any).toolData?.toolName ? (
            <p className="text-sm font-semibold uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500 truncate">
              {(data as any).toolData.toolName.replace(/_/g, ' ')}
            </p>
          ) : (data as any).toolData?.toolSlug ? (
            <div className="h-4 w-32 bg-slate-200 dark:bg-slate-700 rounded animate-pulse" />
          ) : (
            <p className="text-sm font-semibold uppercase tracking-[0.3em] text-slate-400 dark:text-slate-500">
              {displayKind}
            </p>
          )}
        </div>
        {isRunMode ? (
          <div
            className="w-full text-lg font-semibold text-slate-900 dark:text-slate-100 bg-transparent p-0 truncate pointer-events-none select-none"
            onClick={(e) => e.stopPropagation()}
          >
            {displayLabel}
          </div>
        ) : (
          <Input
            className="w-full text-lg font-semibold text-slate-900 dark:text-slate-100 bg-transparent border-none outline-none focus:outline-none focus:ring-0 p-0 shadow-none truncate"
            value={displayLabel}
            maxLength={50}
            onChange={handleLabelChange}
            onClick={(e) => e.stopPropagation()}
            readOnly={isTitleReadOnly}
          />
        )}
      </div>

      {/* ViewModeTabs - show in run mode, desktop only (mobile renders its own in InspectorMobileContent) */}
      {isRunMode && (isAdvanced || isFullscreen) && !isInterfaceNode && (
        <div className="hidden lg:block flex-shrink-0">
          <ViewModeTabs
            viewMode={viewMode}
            onViewModeChange={onViewModeChange}
            variant="header"
            showExecutionData={showExecutionData}
            onShowExecutionDataChange={onShowExecutionDataChange}
            canShowExecutionDataToggle={canShowExecutionDataToggle}
          />
        </div>
      )}

      {/* Action buttons */}
      <div className={clsx(
        "flex items-center gap-1 flex-shrink-0",
        isFullscreen ? "" : "lg:hidden"
      )}>
        {/* ── Mobile: overflow menu for secondary actions ── */}
        <MobileOverflowMenu
          node={node}
          isRunMode={isRunMode}
          isFullscreen={isFullscreen}
          isAdvanced={isAdvanced}
          isInterfaceNode={isInterfaceNode}
          shouldForceSmallMode={shouldForceSmallMode}
          onDuplicateNode={onDuplicateNode}
          onDeleteNode={onDeleteNode}
          onAdvancedChange={onAdvancedChange}
          onFullscreenChange={onFullscreenChange}
          onMinimize={onMinimize}
          onReportNode={onReportNode}
          t={t}
        />

        {/* Play button for step-by-step execution - always visible */}
        {node && !isInterfaceNode && (
          isRunMode ? (
            stepByStepStatus.isStepByStepMode && (
              <>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  disabled={!stepByStepStatus.canExecute || stepByStepStatus.isExecuting}
                  className={clsx(
                    "h-8 w-8",
                    isFullscreen ? "" : "lg:hidden",
                    stepByStepStatus.canExecute && !stepByStepStatus.isExecuting
                      ? "text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                      : "text-slate-400 dark:text-slate-500 cursor-not-allowed"
                  )}
                  onClick={(e) => {
                    e.stopPropagation();
                    if (stepByStepStatus.canExecute && !stepByStepStatus.isExecuting) {
                      stepByStepStatus.executeStep();
                    }
                  }}
                  title={stepByStepStatus.isExecuting ? t('executing') : stepByStepStatus.canExecute ? t('executeStep') : t('waitingDependencies')}
                >
                  {stepByStepStatus.isExecuting ? (
                    <LoadingSpinner size="xs" />
                  ) : (
                    <Play className="h-4 w-4" fill="currentColor" />
                  )}
                </Button>

                {/* Re-run button */}
                {stepByStepStatus.canRerun && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    disabled={stepByStepStatus.isRerunning || stepByStepStatus.isExecuting}
                    className={clsx(
                      "h-8 w-8",
                      isFullscreen ? "" : "lg:hidden",
                      !stepByStepStatus.isRerunning && !stepByStepStatus.isExecuting
                        ? "text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                        : "text-slate-400 dark:text-slate-500 cursor-not-allowed"
                    )}
                    onClick={(e) => {
                      e.stopPropagation();
                      if (!stepByStepStatus.isRerunning && !stepByStepStatus.isExecuting) {
                        stepByStepStatus.rerunStep();
                      }
                    }}
                    title={stepByStepStatus.isRerunning ? t('rerunning') : t('rerunTooltip')}
                  >
                    {stepByStepStatus.isRerunning ? (
                      <LoadingSpinner size="xs" />
                    ) : (
                      <RotateCcw className="h-4 w-4" />
                    )}
                  </Button>
                )}
              </>
            )
          ) : (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              disabled={!isTriggerNode || hasGlobalValidationErrors}
              className={clsx(
                "h-8 w-8",
                isFullscreen ? "" : "lg:hidden",
                isTriggerNode && !hasGlobalValidationErrors
                  ? "text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                  : "text-slate-400 dark:text-slate-500 cursor-not-allowed"
              )}
              onClick={handleStartStepByStep}
              title={hasGlobalValidationErrors ? t('fixValidationErrors') : isTriggerNode ? t('startStepByStep') : t('onlyTriggersCanStart')}
            >
              <Play className="h-4 w-4" fill="currentColor" />
            </Button>
          )
        )}

        {/* Close button - always visible */}
        {onClose && !(isFullscreen && isRunMode) && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className={clsx(
              "h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]",
              isFullscreen ? "" : "lg:hidden"
            )}
            onClick={(e) => {
              e.stopPropagation();
              onClose();
            }}
            title={t('close')}
          >
            <X className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );
}

/**
 * Overflow menu for secondary mobile header actions (duplicate, delete, expand, fullscreen, minimize).
 * Keeps the header compact on small screens.
 */
function MobileOverflowMenu({
  node,
  isRunMode,
  isFullscreen,
  isAdvanced,
  isInterfaceNode,
  shouldForceSmallMode,
  onDuplicateNode,
  onDeleteNode,
  onAdvancedChange,
  onFullscreenChange,
  onMinimize,
  onReportNode,
  t,
}: {
  node: Node<BuilderNodeData>;
  isRunMode: boolean;
  isFullscreen: boolean;
  isAdvanced: boolean;
  isInterfaceNode: boolean;
  shouldForceSmallMode: boolean;
  onDuplicateNode?: (nodeId: string) => void;
  onDeleteNode?: (nodeId: string) => void;
  onAdvancedChange?: (advanced: boolean) => void;
  onFullscreenChange?: (fullscreen: boolean) => void;
  onMinimize?: () => void;
  onReportNode?: () => void;
  t: (key: string) => string;
}) {
  const [open, setOpen] = React.useState(false);

  // Collect menu items based on current state
  const items: { icon: React.ReactNode; label: string; onClick: () => void; danger?: boolean }[] = [];

  if (onDuplicateNode && !isRunMode) {
    items.push({
      icon: <Copy className="h-4 w-4" />,
      label: t('duplicateNode'),
      onClick: () => onDuplicateNode(node.id),
    });
  }

  if (onDeleteNode && !isRunMode) {
    items.push({
      icon: <Trash2 className="h-4 w-4" />,
      label: t('deleteNode'),
      onClick: () => onDeleteNode(node.id),
      danger: true,
    });
  }

  if (isInterfaceNode && isRunMode && onAdvancedChange) {
    items.push({
      icon: <Eye className="h-4 w-4" />,
      label: t('previewInterface'),
      onClick: () => onAdvancedChange(true),
    });
  }

  if (onAdvancedChange && !shouldForceSmallMode && !isFullscreen) {
    items.push({
      icon: isAdvanced ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />,
      label: isAdvanced ? t('minimize') : t('expand'),
      onClick: () => onAdvancedChange(!isAdvanced),
    });
  }

  if (onFullscreenChange) {
    items.push({
      icon: isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize className="h-4 w-4" />,
      label: isFullscreen ? t('exitFullscreen') : t('fullscreen'),
      onClick: () => onFullscreenChange(!isFullscreen),
    });
  }

  if (onMinimize && !isFullscreen) {
    items.push({
      icon: <Minus className="h-4 w-4" />,
      label: t('collapse'),
      onClick: () => onMinimize(),
    });
  }

  // Report a problem - available in both edit and run mode
  if (onReportNode) {
    items.push({
      icon: <Flag className="h-4 w-4" />,
      label: t('report.buttonTooltip'),
      onClick: () => onReportNode(),
    });
  }

  if (items.length === 0) return null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className={clsx(
            "h-8 w-8 text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]",
            isFullscreen ? "" : "lg:hidden"
          )}
          title={t('moreActions')}
        >
          <MoreVertical className="h-4 w-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        side="bottom"
        className="w-48 p-1 rounded-xl z-[10000] bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-lg"
      >
        {items.map((item, i) => (
          <button
            key={i}
            type="button"
            className={clsx(
              "w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm transition-colors",
              item.danger
                ? "text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20"
                : "text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700"
            )}
            onClick={(e) => {
              e.stopPropagation();
              setOpen(false);
              item.onClick();
            }}
          >
            {item.icon}
            <span>{item.label}</span>
          </button>
        ))}
      </PopoverContent>
    </Popover>
  );
}
