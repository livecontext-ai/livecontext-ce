'use client';

import React from 'react';
import { cn } from '@/lib/utils';
import { AppSidebar } from '@/components/app/AppSidebar';
import { AppHeader } from '@/components/app/AppHeader';
import { SidePanel } from '@/components/app/SidePanel';
import { ConversationActivityProvider } from '@/contexts/ConversationActivityContext';
import { useSidePanelLayoutSafe } from '@/contexts/SidePanelLayoutContext';

/** Header + routed page content: the primary column, to the right of the sidebar. */
function MainContentColumn({ children }: { children: React.ReactNode }) {
  // The activity provider wraps header + content so the header toggle and the
  // conversation activity card share open/closed state.
  return (
    <ConversationActivityProvider>
      <div className="flex-1 flex flex-col min-w-0 min-h-0">
        <AppHeader />
        <main className="flex-1 flex flex-col min-h-0 relative overflow-hidden">
          {children}
        </main>
      </div>
    </ConversationActivityProvider>
  );
}

/**
 * App shell: sidebar + main content + the unified side panel, arranged per the user's
 * dock-position preference (Settings > Preferences, org-aware):
 *   - 'right'       -> row root: [sidebar][ content | panel ]. Content shrinks in width.
 *   - 'bottom'      -> row root, content region is a column: the panel docks under the
 *                      content spanning only the content area (right of the sidebar).
 *                      Content shrinks in height.
 *   - 'bottom-full' -> column root: [ row(sidebar + content) ][ panel ]. The panel spans
 *                      the FULL viewport width (edge to edge, under the sidebar too) and
 *                      the sidebar shrinks vertically to sit above it.
 *
 * The panel is mounted in exactly ONE place per mode (inside the content region, or at
 * the root for 'bottom-full'); its tab state lives in SidePanelContext, so switching
 * modes does not lose open tabs. On mobile the panel is a fixed overlay, so these
 * arrangements are inert there.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const { position } = useSidePanelLayoutSafe();

  if (position === 'bottom-full') {
    return (
      <div className="flex flex-col h-full relative">
        <div className="flex flex-1 min-h-0 relative">
          <AppSidebar />
          <div className="flex-1 flex flex-row min-w-0 min-h-0">
            <MainContentColumn>{children}</MainContentColumn>
          </div>
        </div>
        {/* Full-width side panel docked under the whole row (sidebar included). */}
        <SidePanel />
      </div>
    );
  }

  // 'right' and 'bottom': the panel lives inside the content region, so it spans only
  // the area to the right of the sidebar. Row for 'right', column for 'bottom'.
  const isBottom = position === 'bottom';
  return (
    <div className="flex h-full relative">
      <AppSidebar />
      <div className={cn('flex-1 flex min-w-0 min-h-0', isBottom ? 'flex-col' : 'flex-row')}>
        <MainContentColumn>{children}</MainContentColumn>
        <SidePanel />
      </div>
    </div>
  );
}
