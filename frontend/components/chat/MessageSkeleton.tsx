'use client';

import React from 'react';

export const MessageSkeleton: React.FC = () => {
  return (
    <div className="space-y-4">
      {/* User message skeleton */}
      <div className="flex justify-end">
        <div className="max-w-[85%] p-5 bg-theme-tertiary rounded-[18px]">
          <div className="space-y-3">
            <div className="h-4 bg-theme-secondary rounded animate-pulse" style={{ width: '200px' }} />
            <div className="h-4 bg-theme-secondary rounded animate-pulse" style={{ width: '150px' }} />
          </div>
        </div>
      </div>

      {/* Assistant message skeleton */}
      <div className="flex justify-start">
        <div className="w-full p-4">
          <div className="space-y-2">
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full" />
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full" />
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full" />
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-5/6" />
          </div>
        </div>
      </div>

      {/* User message skeleton */}
      <div className="flex justify-end">
        <div className="max-w-[85%] p-5 bg-theme-tertiary rounded-[18px]">
          <div className="space-y-3">
            <div className="h-4 bg-theme-secondary rounded animate-pulse" style={{ width: '120px' }} />
          </div>
        </div>
      </div>

      {/* Assistant message skeleton */}
      <div className="flex justify-start">
        <div className="w-full p-4">
          <div className="space-y-2">
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full" />
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full" />
            <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4/5" />
          </div>
        </div>
      </div>
    </div>
  );
};
