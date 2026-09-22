'use client';

import * as React from 'react';
import { CalendarClock } from 'lucide-react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import { AGENDA_PANEL_TAB_ID } from '@/lib/sidePanel/tabResource';

interface AgendaPanelOpener {
  openTab: (tab: SidePanelTab) => void;
}

/** Open the workspace agenda without navigating away from the current page. */
export function openAgendaPanel(
  sidePanel: AgendaPanelOpener | null | undefined,
  label: string,
): void {
  if (!sidePanel) return;

  // The calendar pulls in its grids and drag-and-drop runtime. Keep that code out of the
  // app shell until the user actually asks to see the agenda.
  void import('@/components/app/AgendaPanelContent').then(({ AgendaPanelContent }) => {
    sidePanel.openTab({
      id: AGENDA_PANEL_TAB_ID,
      label,
      icon: <CalendarClock className="h-4 w-4" />,
      content: <AgendaPanelContent />,
      preferredWidth: 0.65,
    });
  });
}
