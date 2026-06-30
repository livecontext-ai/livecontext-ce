/**
 * Container wrapper for output content.
 * Provides consistent styling and optional title.
 */

'use client';

import React, { ReactNode } from 'react';

interface OutputContainerProps {
  title?: string;
  children: ReactNode;
}

export function OutputContainer({ title, children }: OutputContainerProps) {
  return (
    <div className="flex flex-col h-full">
      {title && (
        <div className="px-4 py-2 border-b border-slate-200">
          <h3 className="text-sm font-medium text-slate-700">{title}</h3>
        </div>
      )}
      <div className="flex-1 overflow-auto">
        {children}
      </div>
    </div>
  );
}

export default OutputContainer;
