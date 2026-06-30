'use client';

import * as React from 'react';
import clsx from 'clsx';
import { Trash2, Copy, Eye, Minimize2, Maximize, Maximize2, Play, RotateCcw, Table, X, Minus, Flag } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import { Button } from '@/components/ui/button';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useTranslations } from 'next-intl';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { DataSourcePanelContent } from '@/components/app/DataSourcePanelContent';

interface StepByStepStatus {
  isStepByStepMode: boolean;
  canExecute: boolean;
  isExecuting: boolean;
  canRerun: boolean;
  isRerunning: boolean;
  executeStep: () => void;
  rerunStep: () => void;
}

interface InspectorActionButtonsProps {
  node: Node<BuilderNodeData>;
  isFullscreen: boolean;
  isAdvanced: boolean;
  isRunMode: boolean;
  isInterfaceNode: boolean;
  isTriggerNode: boolean;
  isTableSelected: boolean;
  dataSourceId?: string | number | null;
  shouldForceSmallMode: boolean;
  hasGlobalValidationErrors: boolean;
  stepByStepStatus: StepByStepStatus;
  onDeleteNode?: (nodeId: string) => void;
  onDuplicateNode?: (nodeId: string) => void;
  onAdvancedChange?: (advanced: boolean) => void;
  onFullscreenChange?: (fullscreen: boolean) => void;
  onClose?: () => void;
  onMinimize?: () => void;
  onReportNode?: () => void;
}

// Shared button style
const actionButtonClass = "h-8 w-8 p-0 rounded-full bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none";
const disabledButtonClass = "h-8 w-8 p-0 rounded-full bg-slate-200 dark:bg-slate-700 text-slate-400 dark:text-slate-500 cursor-not-allowed shadow-none";

export function InspectorActionButtons({
  node,
  isFullscreen,
  isAdvanced,
  isRunMode,
  isInterfaceNode,
  isTriggerNode,
  isTableSelected,
  dataSourceId,
  shouldForceSmallMode,
  hasGlobalValidationErrors,
  stepByStepStatus,
  onDeleteNode,
  onDuplicateNode,
  onAdvancedChange,
  onFullscreenChange,
  onClose,
  onMinimize,
  onReportNode,
}: InspectorActionButtonsProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const { isPreviewOnly } = useWorkflowMode();
  const sidePanel = useSidePanelSafe();

  const handleClick = (e: React.MouseEvent, callback: () => void) => {
    e.stopPropagation();
    callback();
  };

  // Hide in fullscreen mode - header buttons handle actions there
  if (isFullscreen) {
    return null;
  }

  return (
    <div className={clsx(
      "absolute z-[10000] hidden lg:flex flex-col items-center gap-2 rounded-full p-0 pointer-events-auto",
      "top-0 -right-10"
    )}>
      {/* Delete button */}
      {onDeleteNode && !isRunMode && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onDeleteNode(node.id))}
          title={t('deleteNode')}
        >
          <Trash2 className="h-4 w-4" />
        </Button>
      )}

      {/* Duplicate button */}
      {onDuplicateNode && !isRunMode && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onDuplicateNode(node.id))}
          title={t('duplicateNode')}
        >
          <Copy className="h-4 w-4" />
        </Button>
      )}

      {/* Report a problem - available in both edit and run mode */}
      {onReportNode && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, onReportNode)}
          title={t('report.buttonTooltip')}
        >
          <Flag className="h-4 w-4" />
        </Button>
      )}

      {/* Eye button for interface nodes preview */}
      {isInterfaceNode && isRunMode && onAdvancedChange && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onAdvancedChange(true))}
          title={t('previewInterface')}
        >
          <Eye className="h-4 w-4" />
        </Button>
      )}

      {/* Fullscreen button in run mode */}
      {isRunMode && onFullscreenChange && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onFullscreenChange(!isFullscreen))}
          title={isFullscreen ? t('exitFullscreen') : t('fullscreen')}
        >
          {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize className="h-4 w-4" />}
        </Button>
      )}

      {/* Expand/Minimize button in edit mode */}
      {onAdvancedChange && !shouldForceSmallMode && !isFullscreen && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onAdvancedChange(!isAdvanced))}
          title={isAdvanced ? t('minimize') : t('expand')}
        >
          {isAdvanced ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
        </Button>
      )}

      {/* Fullscreen button in edit mode */}
      {!isRunMode && onFullscreenChange && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => onFullscreenChange(!isFullscreen))}
          title={isFullscreen ? t('exitFullscreen') : t('fullscreen')}
        >
          {isFullscreen ? <Minimize2 className="h-4 w-4" /> : <Maximize className="h-4 w-4" />}
        </Button>
      )}

      {/* Step-by-step execution buttons - hidden in preview-only mode (marketplace preview) */}
      {node && !isInterfaceNode && !isPreviewOnly && (
        isRunMode ? (
          // Run mode: show when step-by-step is enabled
          stepByStepStatus.isStepByStepMode && (
            <>
              {/* Play button - only for READY steps */}
              <Button
                type="button"
                variant="default"
                size="sm"
                disabled={!stepByStepStatus.canExecute || stepByStepStatus.isExecuting}
                className={clsx(
                  "h-8 w-8 p-0 rounded-full shadow-none",
                  stepByStepStatus.canExecute && !stepByStepStatus.isExecuting
                    ? "bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                    : disabledButtonClass
                )}
                onClick={(e) => handleClick(e, () => {
                  if (stepByStepStatus.canExecute && !stepByStepStatus.isExecuting) {
                    stepByStepStatus.executeStep();
                  }
                })}
                title={stepByStepStatus.isExecuting ? t('executing') : stepByStepStatus.canExecute ? t('executeStep') : t('waitingDependencies')}
              >
                {stepByStepStatus.isExecuting ? (
                  <LoadingSpinner size="xs" />
                ) : (
                  <Play className="h-4 w-4" fill="currentColor" />
                )}
              </Button>

              {/* Re-run button - for COMPLETED, FAILED, or SKIPPED steps */}
              {stepByStepStatus.canRerun && (
                <Button
                  type="button"
                  variant="default"
                  size="sm"
                  disabled={stepByStepStatus.isRerunning || stepByStepStatus.isExecuting}
                  className={clsx(
                    "h-8 w-8 p-0 rounded-full shadow-none",
                    !stepByStepStatus.isRerunning && !stepByStepStatus.isExecuting
                      ? "bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                      : disabledButtonClass
                  )}
                  onClick={(e) => handleClick(e, () => {
                    if (!stepByStepStatus.isRerunning && !stepByStepStatus.isExecuting) {
                      stepByStepStatus.rerunStep();
                    }
                  })}
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
          // Edit mode: show on all nodes, greyed out for non-triggers or when validation errors exist
          <Button
            type="button"
            variant="default"
            size="sm"
            disabled={!isTriggerNode || hasGlobalValidationErrors}
            className={clsx(
              "h-8 w-8 p-0 rounded-full shadow-none",
              isTriggerNode && !hasGlobalValidationErrors
                ? "bg-[var(--bg-primary)] text-[var(--text-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)]"
                : disabledButtonClass
            )}
            onClick={(e) => handleClick(e, () => {
              if (isTriggerNode && !hasGlobalValidationErrors) {
                window.dispatchEvent(new CustomEvent('workflowStartStepByStep', {
                  detail: { startFromNode: node.id }
                }));
              }
            })}
            title={hasGlobalValidationErrors ? t('fixValidationErrors') : isTriggerNode ? t('startStepByStep') : t('onlyTriggersCanStart')}
          >
            <Play className="h-4 w-4" fill="currentColor" />
          </Button>
        )
      )}

      {/* View table button for table triggers */}
      {isTableSelected && dataSourceId && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, () => {
            sidePanel?.openTab({
              id: `datasource-${dataSourceId}`,
              label: node.data?.label || 'Table',
              icon: <Table className="w-4 h-4" />,
              content: <DataSourcePanelContent dataSourceId={String(dataSourceId)} />,
              preferredWidth: 0.35,
            });
          })}
          title={t('viewTable')}
        >
          <Table className="h-4 w-4" />
        </Button>
      )}

      {/* Minimize button (collapse to pill) */}
      {onMinimize && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, onMinimize)}
          title={t('collapse')}
        >
          <Minus className="h-4 w-4" />
        </Button>
      )}

      {/* Close button */}
      {onClose && (
        <Button
          type="button"
          variant="default"
          size="sm"
          className={actionButtonClass}
          onClick={(e) => handleClick(e, onClose)}
          title={t('close')}
        >
          <X className="h-4 w-4" />
        </Button>
      )}
    </div>
  );
}
