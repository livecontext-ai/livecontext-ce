import React, { useState } from 'react';

interface PanelResizeHandleProps {
  panelWidth: number;
  isResizing: boolean;
  onResizeStart: () => void;
}

export function PanelResizeHandle({ panelWidth, isResizing, onResizeStart }: PanelResizeHandleProps) {
  const [isHovered, setIsHovered] = useState(false);

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
      <div
        className={`h-full transition-all ${
          isResizing || isHovered ? 'w-1 bg-blue-500' : 'w-0'
        }`}
      />
    </div>
  );
}
