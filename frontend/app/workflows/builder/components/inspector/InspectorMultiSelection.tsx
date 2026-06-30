'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { Trash2, Copy, X, CheckSquare, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface InspectorMultiSelectionProps {
  selectedNodeIds: string[];
  isAdvanced: boolean;
  onDeleteNode?: (nodeId: string) => void;
  onDuplicateNode?: (nodeId: string) => void;
  onUndo?: () => void;
  canUndo?: boolean;
  onClose?: () => void;
}

/**
 * Floating toolbar for multi-node selection.
 * Shows bulk actions (delete all, duplicate all) in a compact horizontal bar.
 */
export function InspectorMultiSelection({
  selectedNodeIds,
  onDeleteNode,
  onDuplicateNode,
  onUndo,
  canUndo = false,
  onClose,
}: InspectorMultiSelectionProps) {
  const t = useTranslations('workflowBuilder.inspector');
  const count = selectedNodeIds.length;

  const handleDeleteAll = () => {
    if (onDeleteNode && count > 0) {
      selectedNodeIds.forEach(id => onDeleteNode(id));
    }
  };

  const handleDuplicateAll = () => {
    if (onDuplicateNode && count > 0) {
      selectedNodeIds.forEach(id => onDuplicateNode(id));
    }
  };

  const handleClick = (e: React.MouseEvent) => e.stopPropagation();
  const handleMouseDown = (e: React.MouseEvent) => e.stopPropagation();
  const handleMouseUp = (e: React.MouseEvent) => e.stopPropagation();

  if (count < 2) return null;

  return (
    <div
      onClick={handleClick}
      onMouseDown={handleMouseDown}
      onMouseUp={handleMouseUp}
      data-inspector-panel
      className="flex items-center gap-1 px-3 py-2 bg-white dark:bg-gray-800 rounded-[24px] pointer-events-auto"
    >
      {/* Selection count */}
      <div className="flex items-center gap-1.5 h-7 px-2.5">
        <CheckSquare className="h-3.5 w-3.5" />
        <span className="text-xs font-medium">{count} {t('selected')}</span>
      </div>

      {/* Separator */}
      <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-1" />

      {/* Action buttons */}
      {onDuplicateNode && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={handleDuplicateAll}
          className="h-7 px-2.5 text-xs font-medium rounded-full"
          title={t('duplicateAllTooltip')}
        >
          <Copy className="h-3.5 w-3.5 mr-1.5" />
          {t('duplicate')}
        </Button>
      )}

      {onUndo && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={onUndo}
          disabled={!canUndo}
          className="h-7 px-2.5 text-xs font-medium rounded-full disabled:opacity-50"
          title={t('undoTooltip')}
        >
          <Undo2 className="h-3.5 w-3.5 mr-1.5" />
          {t('undo')}
        </Button>
      )}

      {onDeleteNode && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={handleDeleteAll}
          className="h-7 px-2.5 text-xs font-medium text-red-500 hover:text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:text-red-300 dark:hover:bg-red-950/50 rounded-full"
          title={t('deleteAllTooltip')}
        >
          <Trash2 className="h-3.5 w-3.5 mr-1.5" />
          {t('delete')}
        </Button>
      )}

      {/* Close button */}
      {onClose && (
        <>
          <div className="w-px h-5 bg-slate-200 dark:bg-slate-700 mx-1" />
          <Button
            type="button"
            variant="ghost"
            size="icon"
            onClick={(e) => {
              e.stopPropagation();
              onClose();
            }}
            className="h-7 w-7 rounded-full"
            title={t('clearSelection')}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </>
      )}
    </div>
  );
}
