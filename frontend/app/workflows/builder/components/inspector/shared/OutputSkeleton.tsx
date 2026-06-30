/**
 * Loading skeleton for output display.
 */

'use client';

import React from 'react';

export function OutputSkeleton() {
  return (
    <div className="space-y-3 p-4">
      <div className="h-4 bg-slate-200 rounded animate-pulse w-3/4" />
      <div className="h-4 bg-slate-200 rounded animate-pulse w-1/2" />
      <div className="h-4 bg-slate-200 rounded animate-pulse w-5/6" />
      <div className="h-4 bg-slate-200 rounded animate-pulse w-2/3" />
    </div>
  );
}

export default OutputSkeleton;
