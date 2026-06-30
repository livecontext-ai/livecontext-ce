'use client';

import * as React from 'react';
import { Sparkles } from 'lucide-react';
import { ChatPanelContent } from '@/components/app/ChatPanelContent';
import { pathMatchesAnyPattern, type SidePanelTab } from '@/contexts/SidePanelContext';

export const AI_CHAT_TAB_ID = 'ai-chat';

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
 */
export function registerAiChatTab(sidePanel: AiChatTabRegistrar): void {
  if (!sidePanel.addTab) return;
  sidePanel.addTab(buildAiChatTab());
}
