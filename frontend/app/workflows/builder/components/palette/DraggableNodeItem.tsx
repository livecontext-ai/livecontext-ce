'use client';

import * as React from 'react';
import { GripVertical, ChevronRight, ArrowRight } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { NodeIcon } from '../nodes/shared';
import { AvatarDisplay } from '@/components/agents';
import type { NodeFamily } from '../../nodes/nodeClasses';
import type { BuilderNodeKind } from '../../types';

export type DraggableNodeItemProps = {
  /** Unique key for the item */
  id: string;
  /** Display label */
  label: string;
  /** Optional description */
  description?: string | null;
  /** Click handler */
  onClick?: () => void;
  /** Drag data to set on dragStart */
  dragData?: object;
  /** Whether dragging is disabled */
  disableDrag?: boolean;
  /** Show navigation arrow (ChevronRight or ArrowRight) */
  showArrow?: boolean;
  /** Arrow type: 'chevron' (default) or 'arrow' */
  arrowType?: 'chevron' | 'arrow';
  /** Secondary info (e.g., "5 tools") */
  secondaryInfo?: string;
  // NodeIcon props
  nodeId?: string;
  nodeKind?: BuilderNodeKind;
  nodeFamily?: NodeFamily;
  bgClassName?: string;
  iconSlug?: string;
  isMcp?: boolean;
  iconSize?: 'sm' | 'md' | 'lg';
  /** Agent avatar URL - replaces NodeIcon when provided */
  avatarUrl?: string;
  /** Custom icon element - replaces NodeIcon entirely when provided */
  iconOverride?: React.ReactNode;
};

/**
 * Reusable draggable node item component for the NodeCreatorPanel.
 * Handles drag & drop, click events, tooltips, and consistent styling.
 */
export function DraggableNodeItem({
  id,
  label,
  description,
  onClick,
  dragData,
  disableDrag = false,
  showArrow = false,
  arrowType = 'chevron',
  secondaryInfo,
  nodeId,
  nodeKind,
  nodeFamily,
  bgClassName,
  iconSlug,
  isMcp = false,
  iconSize = 'md',
  avatarUrl,
  iconOverride,
}: DraggableNodeItemProps) {
  const handleDragStart = React.useCallback(
    (e: React.DragEvent) => {
      if (disableDrag || !dragData) {
        e.preventDefault();
        return;
      }
      e.stopPropagation();
      e.dataTransfer.setData('application/reactflow', JSON.stringify(dragData));
      e.dataTransfer.effectAllowed = 'copy';

      // Create custom drag image
      const dragElement = e.currentTarget as HTMLElement;
      const dragImage = dragElement.cloneNode(true) as HTMLElement;
      dragImage.style.position = 'absolute';
      dragImage.style.top = '-1000px';
      dragImage.style.opacity = '0.8';
      dragImage.style.pointerEvents = 'none';
      document.body.appendChild(dragImage);

      const rect = dragElement.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      e.dataTransfer.setDragImage(dragImage, x, y);

      setTimeout(() => {
        if (document.body.contains(dragImage)) {
          document.body.removeChild(dragImage);
        }
      }, 0);
    },
    [disableDrag, dragData]
  );

  const cursorClass = disableDrag ? 'cursor-pointer' : 'cursor-grab active:cursor-grabbing';

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <div
          draggable={!disableDrag}
          onDragStart={handleDragStart}
          onMouseDown={(e) => e.stopPropagation()}
          className={`group flex items-center gap-2 px-2 py-2 rounded-lg hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors active:bg-gray-100 dark:active:bg-gray-800 relative ${cursorClass}`}
          onClick={onClick}
        >
          {!disableDrag && (
            <GripVertical className="absolute left-0 w-3 h-3 text-gray-400 dark:text-gray-500 opacity-0 group-hover:opacity-100 transition-opacity" />
          )}
          {iconOverride ? iconOverride : avatarUrl ? (
            <AvatarDisplay avatarUrl={avatarUrl} name={label} size="md" className="w-9 h-9" />
          ) : (
            <NodeIcon
              nodeId={nodeId || id}
              nodeKind={nodeKind}
              nodeFamily={nodeFamily}
              bgClassName={bgClassName}
              iconSlug={iconSlug}
              isMcp={isMcp}
              alt={label}
              size={iconSize}
            />
          )}
          <div className="flex-1 min-w-0 flex items-center justify-between gap-4">
            <div className="flex-1 min-w-0">
              <div className="text-sm text-gray-900 dark:text-gray-100 truncate">
                {label}
              </div>
              {description ? (
                <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5 line-clamp-2">
                  {description}
                </div>
              ) : secondaryInfo ? (
                <div className="text-xs text-gray-400 dark:text-gray-500 mt-0.5">
                  {secondaryInfo}
                </div>
              ) : null}
            </div>
            {showArrow && (
              <div className="flex items-center gap-2 text-gray-400 dark:text-gray-500 flex-shrink-0">
                {arrowType === 'arrow' ? (
                  <ArrowRight className="w-4 h-4" />
                ) : (
                  <ChevronRight className="w-4 h-4" />
                )}
              </div>
            )}
          </div>
        </div>
      </TooltipTrigger>
      {description && (
        <TooltipContent side="left" className="max-w-xs">
          <p className="font-medium">{label}</p>
          <p className="text-xs text-gray-500 dark:text-gray-400">{description}</p>
        </TooltipContent>
      )}
    </Tooltip>
  );
}

export default DraggableNodeItem;
