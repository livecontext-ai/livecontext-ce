'use client';

import React from 'react';
import { cn } from '@/lib/utils';
import { AppHeader } from '@/components/app/AppHeader';
import { SidePanel } from '@/components/app/SidePanel';
import { ConversationActivityProvider } from '@/contexts/ConversationActivityContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';

/**
 * Content region to the right of the sidebar: (header + page) + the unified side panel.
 * Its flex direction follows the user's dock-position preference:
 *   - 'right'  -> row: the panel sits to the right and the content shrinks in width.
 *   - 'bottom' -> column: the panel docks under the content and it shrinks in height.
 * On mobile the panel is a fixed overlay, so the direction is inert there.
 */
export function AppContentRegion({ children }: { children: React.ReactNode }) {
  const { position } = useSidePanelLayoutSafe();
  const isBottom = position === 'bottom';
  return (
    <div className={cn('flex-1 flex min-w-0 min-h-0', isBottom ? 'flex-col' : 'flex-row')}>
      {/* Main content area: header + page content. The activity provider wraps both so
          the header toggle and the conversation activity card share open/closed state. */}
      <ConversationActivityProvider>
        <div className="flex-1 flex flex-col min-w-0 min-h-0">
          <AppHeader />
          <main className="flex-1 flex flex-col min-h-0 relative overflow-hidden">
            {children}
          </main>
        </div>
      </ConversationActivityProvider>

      {/* Unified side panel - lazy, tab-based */}
      <SidePanel />
    </div>
  );
}
