'use client';

import * as React from 'react';
import type { HoverPopoverItem } from '../../hooks/useHoverPopover';

interface HoverPopoverProps {
  hoveredItem: HoverPopoverItem | null;
  isDesktop: boolean;
  popoverRef: React.RefObject<HTMLDivElement>;
  isHoveringPopoverRef: React.MutableRefObject<boolean>;
  onMouseLeave: () => void;
}

export function HoverPopover({
  hoveredItem,
  isDesktop,
  popoverRef,
  isHoveringPopoverRef,
  onMouseLeave,
}: HoverPopoverProps) {
  if (!isDesktop || !hoveredItem) return null;

  return (
    <div
      ref={popoverRef}
      className="fixed z-[99999] w-80 rounded-[24px] bg-white/95 dark:bg-gray-900/95 backdrop-blur-sm p-5 pointer-events-auto border border-gray-200/50 dark:border-gray-700/50"
      onMouseEnter={() => {
        isHoveringPopoverRef.current = true;
      }}
      onMouseLeave={() => {
        isHoveringPopoverRef.current = false;
        onMouseLeave();
      }}
      style={{
        transition: 'opacity 0.2s ease-in-out',
      }}
    >
      <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-2">{hoveredItem.label}</h3>
      {hoveredItem.description && (
        <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
          {hoveredItem.description}
        </p>
      )}
    </div>
  );
}

