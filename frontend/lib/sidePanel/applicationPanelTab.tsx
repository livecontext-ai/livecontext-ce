'use client';

import * as React from 'react';
import { AppWindow } from 'lucide-react';
import { ApplicationPanelContent } from '@/components/app/ApplicationSidePanel';
import type { SidePanelTab } from '@/contexts/SidePanelContext';

export interface ApplicationPanelTabInput {
  /** Publication id of the application to open. */
  publicationId: string;
  /** Tab label (falls back to "Application"). */
  title?: string;
  /** Live run id from the agent's execute marker, if any. */
  runId?: string;
}

/**
 * Side-panel tab id for an application, scoped by publicationId AND runId.
 *
 * Each `application:execute` creates a NEW run, and the chat history renders one
 * preview card per execution. Keying the tab by publicationId alone collapsed
 * every execution of the same app onto a single panel tab: two cards from
 * different runs/epochs both drove that one tab, so the second card never showed
 * its own run - both "settled on the same epoch" (the user-reported bug).
 * Including runId gives each execution its own tab → independent run, independent
 * epoch selection. The runId-less form keeps the legacy id for the generic
 * "open the app" entries (AddTabPicker, project deep-links) that have no run.
 */
export function applicationPanelTabId(publicationId: string, runId?: string | null): string {
  return runId ? `application-${publicationId}-${runId}` : `application-${publicationId}`;
}

/**
 * Build the side-panel tab descriptor for an auto-opened application.
 *
 * `keepMounted: true` is REQUIRED: when the agent opens several apps at once,
 * the SidePanel renders only the ACTIVE tab's content unless a tab is
 * keepMounted. Without it, an inactive app's data fetch is cancelled on unmount
 * and never resolves - the "only the last app resolved" bug. keepMounted keeps
 * every opened app mounted so each resolves in the background.
 */
export function buildApplicationPanelTab({ publicationId, title, runId }: ApplicationPanelTabInput): SidePanelTab {
  return {
    id: applicationPanelTabId(publicationId, runId),
    label: title || 'Application',
    icon: <AppWindow className="w-4 h-4" />,
    content: <ApplicationPanelContent publicationId={publicationId} runId={runId} />,
    preferredWidth: 0.35,
    keepMounted: true,
  };
}
