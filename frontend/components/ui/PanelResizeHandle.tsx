import React, { useState } from 'react';

interface PanelResizeHandleProps {
  /** Current panel size along the resize axis, in px (width for 'right', height for 'bottom'). */
  panelWidth: number;
  isResizing: boolean;
  onResizeStart: () => void;
  /**
   * Which edge of the viewport the panel is docked to.
   *  - 'right' (default): vertical handle on the panel's left edge, resizes width (ew-resize).
   *  - 'bottom': horizontal handle on the panel's top edge, resizes height (ns-resize).
   */
  orientation?: 'right' | 'bottom';
}

export function PanelResizeHandle({
  panelWidth,
  isResizing,
  onResizeStart,
  orientation = 'right',
}: PanelResizeHandleProps) {
  const [isHovered, setIsHovered] = useState(false);
  const active = isResizing || isHovered;

  if (orientation === 'bottom') {
    return (
      <div
        onMouseDown={(e) => {
          e.preventDefault();
          onResizeStart();
        }}
        onMouseEnter={() => setIsHovered(true)}
        onMouseLeave={() => setIsHovered(false)}
        className="fixed left-0 right-0 h-4 flex items-center justify-center z-[100]"
        style={{ bottom: `${panelWidth - 2}px`, cursor: 'ns-resize' }}
      >
        <div className={`w-full transition-all ${active ? 'h-1 bg-blue-500' : 'h-0'}`} />
      </div>
    );
  }

  return (
    <div
      onMouseDown={(e) => {
        e.preventDefault();
        onResizeStart();
      }}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      className="fixed top-0 bottom-0 w-4 flex items-center justify-center z-[100]"
      style={{ right: `${panelWidth - 2}px`, cursor: 'ew-resize' }}
    >
      <div className={`h-full transition-all ${active ? 'w-1 bg-blue-500' : 'w-0'}`} />
    </div>
  );
}
