'use client';

import React, { Suspense } from 'react';
import { SettingsNav } from '@/components/settings';
import { SettingsPageSkeleton } from '@/components/skeletons';

/**
 * Layout for /app/settings routes
 * Displays SettingsNav in the central area with content on the right
 *
 * Performance: SettingsNav renders immediately, page content wrapped in Suspense
 *
 * <p><b>Breakpoints follow the room the settings actually get, not the window.</b> The app
 * sidebar and the side panel take their share of the window, so a viewport breakpoint put the
 * menu beside the page on a 768px screen with ~270px left for the page itself, where every row
 * broke one word per line. Both levels are CONTAINERS instead: the menu moves to the side only
 * when this column is 48rem wide (`@3xl`, read by SettingsNav), and the page column is its own
 * container, so a settings page lays its rows out from ITS width (`@lg:` and friends).
 */
export default function AppSettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="h-full overflow-y-auto">
      <div className="@container min-h-full flex justify-center px-3 sm:px-6">
        <div className="flex flex-col @3xl:flex-row max-w-6xl w-full py-4 @3xl:py-8 gap-4 @3xl:gap-8">
          <SettingsNav />
          <div className="@container flex-1 min-w-0" data-testid="settings-page-column">
            <Suspense fallback={<SettingsPageSkeleton />}>
              {children}
            </Suspense>
          </div>
        </div>
      </div>
    </div>
  );
}
