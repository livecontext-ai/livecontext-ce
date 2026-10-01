'use client';

import { useEffect, useState } from 'react';
import type { SidePanelTab } from '@/contexts/SidePanelContext';
import type { AutoOpenVisualization } from '@/contexts/sidePanelAutoOpen';

/**
 * Conversations whose chat lives INSIDE the side panel: the AI Chat tab and the AI chat of a
 * workflow panel.
 *
 * What such an agent builds opens as a tab BESIDE the chat and never takes the reader away from
 * it: the person is typing in the panel, and a tab that jumped in front of the conversation on
 * every build step would hide the very answer they are waiting for. A full-page chat keeps
 * bringing its agent's work forward, because there the panel is the only place it can be seen.
 *
 * Module state rather than context, for the reason workflowPanelChat.ts gives: the chat body is
 * unmounted whenever the panel closes or shows another tab while its stream carries on in
 * StreamingContext, so the host records its conversation here and the auto-open listener reads
 * it when a marker arrives.
 */
const hostedConversations = new Map<string, string | null>();

/**
 * Record a side-panel conversation, with the workflow its host already shows when it has one
 * (the chat of a workflow panel): a build marker about THAT workflow must not open a second
 * view of it beside the first.
 */
export function markSidePanelConversation(
  conversationId: string | null | undefined,
  hostWorkflowId: string | null = null,
): void {
  if (!conversationId) return;
  hostedConversations.set(conversationId, hostWorkflowId ?? hostedConversations.get(conversationId) ?? null);
}

export function isSidePanelConversation(conversationId: string | null | undefined): boolean {
  return !!conversationId && hostedConversations.has(conversationId);
}

/** The workflow the host of a side-panel conversation already shows, if it shows one. */
export function hostWorkflowOf(conversationId: string | null | undefined): string | null {
  return conversationId ? hostedConversations.get(conversationId) ?? null : null;
}

/**
 * Whether a marker opens its tab in the background: it comes from a side-panel chat's stream
 * (a click never does: the reader asked to see it), and that chat is not the conversation the
 * page itself shows (a side-panel conversation opened full page afterwards is a full-page chat
 * again).
 */
export function opensInBackground(
  detail: Pick<AutoOpenVisualization, 'conversationId' | 'userInitiated'>,
  pageConversationId: string | null,
): boolean {
  return detail.userInitiated !== true
    && isSidePanelConversation(detail.conversationId)
    && detail.conversationId !== pageConversationId;
}

export interface AutoOpenPage {
  /** A page where the chat IS the view: the side panel is where its agent's work shows. */
  isChatPage: boolean;
  /** The conversation the page shows, on a conversation page. */
  pageConversationId: string | null;
  /** The workflow the page shows, on a workflow page. */
  workflowPageId: string | null;
  /** The application the page shows, on an application page. */
  applicationPageId: string | null;
}

/**
 * Where AppHeader's build-marker handler puts a tab: in front, in the background beside a
 * side-panel chat, or nowhere.
 *
 * - A click on a tool row opens in front, on every page.
 * - A side-panel chat's agent opens in the background, on every page.
 * - Anything else opens in front on chat pages only.
 * - A presentation is openPresentedView's (the other handler), on every page, except the run
 *   views a full-page chat presents, which this handler opens on chat pages.
 * - Nothing opens a second view of what is already on screen: the workflow page's own workflow
 *   and runs (WorkflowDetailView follows them in place), the workflow a side-panel chat's host
 *   already shows (a workflow panel on an application page), the application page's own
 *   application.
 * - An interface opens only beside a side-panel chat or on a click: a full-page chat already
 *   shows it as a card in the conversation.
 */
export function autoOpenPlacement(
  detail: AutoOpenVisualization,
  page: AutoOpenPage,
): 'front' | 'background' | null {
  if (!detail.id) return null;
  const clicked = detail.userInitiated === true;
  const background = opensInBackground(detail, page.pageConversationId);
  if (detail.type.startsWith('present_') && (background || !page.isChatPage)) return null;
  if (!page.isChatPage && !background && !clicked) return null;
  const aboutWorkflow = detail.type === 'workflow' || detail.type === 'workflow_run';
  if (aboutWorkflow && detail.id === page.workflowPageId) return null;
  if (aboutWorkflow && background && detail.id === hostWorkflowOf(detail.conversationId)) return null;
  if (detail.type === 'application' && detail.id === page.applicationPageId) return null;
  if (detail.type === 'interface' && !background && !clicked) return null;
  return background ? 'background' : 'front';
}

/**
 * The tabs a side-panel agent is working in, each with the conversation working in it. The tab
 * shimmers (SidePanel draws it on an inactive tab) while that conversation streams, which is
 * what tells the reader where the agent is without taking them there.
 */
const workingTabs = new Map<string, string>();
const workingListeners = new Set<() => void>();

function notifyWorking(): void {
  workingListeners.forEach(listener => listener());
}

interface BackgroundPanel {
  addTab: (tab: SidePanelTab) => void;
}

/** Add (or refresh) a tab without bringing it forward, marked as worked on by `conversationId`. */
export function openTabInBackground(panel: BackgroundPanel, tab: SidePanelTab, conversationId: string): void {
  panel.addTab({ ...tab, shimmer: true });
  workingTabs.set(tab.id, conversationId);
  notifyWorking();
}

interface OpeningPanel extends BackgroundPanel {
  openTab: (tab: SidePanelTab) => void;
  openTabDeferred: (tab: SidePanelTab) => void;
}

/**
 * How a marker's tab is opened: beside a side-panel chat, added without being brought forward
 * and marked as worked on; otherwise in front, or as a peek on mobile, where an open panel would
 * cover the page.
 */
export function tabOpener(
  panel: OpeningPanel,
  placement: { background: boolean; conversationId?: string | null; isMobile: boolean },
): (tab: SidePanelTab) => void {
  if (placement.background && placement.conversationId) {
    const conversationId = placement.conversationId;
    return (tab) => openTabInBackground(panel, tab, conversationId);
  }
  return placement.isMobile ? panel.openTabDeferred : panel.openTab;
}

/**
 * Stop the shimmer of every tab whose conversation no longer streams. Checked whenever a tab is
 * marked and whenever streaming changes: a marker can flush AFTER the last chunk (the auto-open
 * is debounced), and a check made only on streaming changes would then leave that tab
 * shimmering for good.
 */
export function useSettleAgentWorkingTabs(
  isStreaming: (conversationId: string) => boolean,
  updateTab: ((tabId: string, updates: Partial<SidePanelTab>) => void) | undefined,
): void {
  const [marked, setMarked] = useState(0);
  useEffect(() => {
    const listener = () => setMarked(count => count + 1);
    workingListeners.add(listener);
    return () => { workingListeners.delete(listener); };
  }, []);
  useEffect(() => {
    if (!updateTab) return;
    for (const [tabId, conversationId] of Array.from(workingTabs.entries())) {
      if (isStreaming(conversationId)) continue;
      workingTabs.delete(tabId);
      updateTab(tabId, { shimmer: false });
    }
  }, [marked, isStreaming, updateTab]);
}

/** Test hook: forget every registration. */
export function resetSidePanelConversationsForTest(): void {
  hostedConversations.clear();
  workingTabs.clear();
}
