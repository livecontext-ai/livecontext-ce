'use client';

import * as React from 'react';
import { Sparkles } from 'lucide-react';
import { ChatPanelContent } from '@/components/app/ChatPanelContent';
import { pathMatchesAnyPattern, type SidePanelTab } from '@/contexts/SidePanelContext';
import { AI_CHAT_TAB_ID } from '@/lib/sidePanel/tabResource';
import { takeOnScreenBeforeReload } from '@/lib/sidePanel/onScreenAcrossReload';

// Re-exported from its owner so the existing import path keeps working (ChatPanelContent reads
// it too, and importing it from here would be a cycle through this module's ChatPanelContent).
export { AI_CHAT_TAB_ID };

/**
 * Pages where the AI Chat right-panel tab must NOT appear, regardless of how the user got there:
 *   1. Pages embedding AI chat in another panel (workflow / application / marketplace preview).
 *   2. Pages where chat IS the primary view (chat home + chat sub-pages + conversation pages).
 *
 * Single source of truth - fed both into the tab's `excludeScope` (so cross-page navigation
 * drops the tab) and the auto-register guard (so it isn't added in the first place).
 *
 * Pattern syntax (see `pathMatchesPattern`): `*` = single segment wildcard, trailing `$` =
 * exact match. `/app$` is the chat home (literal `/app` would prefix-match `/app/profile`).
 */
export const AI_CHAT_EXCLUDE_SCOPE = [
  '/app/workflow/*',
  '/app/applications/*',
  '/app/marketplace/*/preview',
  '/app$',
  '/app/chat',
  '/app/c/*',
];

type AiChatTabRegistrar = {
  openTab?: (tab: SidePanelTab) => void;
  addTab?: (tab: SidePanelTab) => void;
};

function buildAiChatTab(): SidePanelTab {
  return {
    id: AI_CHAT_TAB_ID,
    label: 'AI Chat',
    icon: <Sparkles className="w-4 h-4" />,
    pinned: true,
    excludeScope: AI_CHAT_EXCLUDE_SCOPE,
    content: <ChatPanelContent />,
  };
}

/**
 * Should the AI Chat tab be hidden on the given (locale-stripped) path?
 * Used by both the SidePanel scope filter (via tab.excludeScope) and call-sites that
 * decide which page-level toggle handler to wire (chat home gets a no-AI-Chat handler).
 */
export function isAiChatExcludedPath(normalizedPath: string | null): boolean {
  return pathMatchesAnyPattern(normalizedPath, AI_CHAT_EXCLUDE_SCOPE);
}

/**
 * Open (or focus) the AI Chat tab in the SidePanel.
 */
export function openAiChatTab(sidePanel: AiChatTabRegistrar): void {
  if (!sidePanel.openTab) return;
  sidePanel.openTab(buildAiChatTab());
}

/**
 * Register the AI Chat tab without opening the panel - used for global auto-registration
 * so the tab is always present (in pages where it's allowed) without forcing the panel open.
 *
 * The one exception is a page left by a reload while its AI Chat was on screen (an OAuth connect
 * started from the chat, an F5): the chat is brought back open, so the conversation that was
 * waiting can carry on. See onScreenAcrossReload.
 */
export function registerAiChatTab(sidePanel: AiChatTabRegistrar, pathname: string | null = null): void {
  if (sidePanel.openTab && takeOnScreenBeforeReload(AI_CHAT_TAB_ID, pathname)) {
    sidePanel.openTab(buildAiChatTab());
    return;
  }
  if (!sidePanel.addTab) return;
  sidePanel.addTab(buildAiChatTab());
}
