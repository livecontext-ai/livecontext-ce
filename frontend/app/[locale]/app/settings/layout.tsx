'use client';

import React, { Suspense } from 'react';
import { SettingsNav } from '@/components/settings';
import { SettingsPageSkeleton } from '@/components/skeletons';

/**
 * Layout for /app/settings routes
 * Displays SettingsNav in the central area with content on the right
 *
 * Performance: SettingsNav renders immediately, page content wrapped in Suspense
 */
export default function AppSettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="h-full overflow-y-auto">
      <div className="min-h-full flex justify-center px-3 sm:px-6">
        <div className="flex flex-col md:flex-row max-w-6xl w-full py-4 md:py-8 gap-4 md:gap-8">
          <SettingsNav />
          <div className="flex-1 min-w-0">
            <Suspense fallback={<SettingsPageSkeleton />}>
              {children}
            </Suspense>
          </div>
        </div>
      </div>
    </div>
  );
}
