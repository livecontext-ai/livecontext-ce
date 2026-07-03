'use client';

/**
 * AgentBrowseLivePreview - inline preview shown in the chat tool card
 * while an `agent_browse` session is live. The session coords arrive via
 * the streaming `agent_browse_step` event from
 * {@code BrowserSessionLifecycleService} - typically 100-300ms after the
 * LLM's tool call submit, well before the (blocking) agent_browse result
 * returns. The user sees the address bar with the live URL + a button
 * that opens the right-side panel embedding the CDP canvas (full live
 * view + takeover/interaction).
 *
 * <p>Visual style mirrors the WebSearch browser-chrome (traffic-light
 * dots + Lock + URL) - same primitive UI for any "look at a page" panel
 * across the product.</p>
 */

import React from 'react';
import { Globe, Lock, Eye } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentBrowsePanelContent } from '@/components/app/AgentBrowsePanelContent';

interface AgentBrowseLivePreviewProps {
  toolId: string;
  session: {
    sessionId: string;
    cdpToken: string;
    cdpWsUrl: string;
    currentUrl: string;
    runId: string;
    nodeId: string;
  };
  /** True while the underlying tool call is still in-flight (Chromium
   *  alive). False once the agent_browse blocking call has returned -
   *  the panel still opens but warns "session ended" via its fallback. */
  isLive?: boolean;
}

export function AgentBrowseLivePreview({ toolId, session, isLive = true }: AgentBrowseLivePreviewProps) {
  const t = useTranslations('agentBrowse');
  const sidePanel = useSidePanelSafe();
  // Key the side-panel tab on the SESSION id so this live card and the
  // post-run AgentBrowseVisualizeCard open the SAME tab (one surface: live
  // during the run, final page after) instead of two different tabs.
  const tabId = `agent-browse-${session.sessionId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const handleOpen = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    sidePanel.openTab({
      id: tabId,
      label: session.currentUrl || t('cardDefaultTitle'),
      icon: <Globe className="w-4 h-4" />,
      content: <AgentBrowsePanelContent liveCoords={session} />,
      preferredWidth: 0.5,
    });
  };

  return (
    <div
      onClick={handleOpen}
      className="w-full rounded-[14px] border border-theme overflow-hidden bg-theme-primary cursor-pointer max-w-md"
    >
      {/* Browser chrome - matches WebSearchVisualizeCard style */}
      <div className="flex items-center gap-2 px-3 py-2 bg-theme-secondary border-b border-theme">
        <div className="flex items-center gap-1.5">
          <span className="w-2.5 h-2.5 rounded-full bg-red-400/70" />
          <span className="w-2.5 h-2.5 rounded-full bg-yellow-400/70" />
          <span className="w-2.5 h-2.5 rounded-full bg-green-400/70" />
        </div>
        {/* Live indicator - pulses while session is alive */}
        {isLive && (
          <span
            className="inline-flex h-2 w-2 rounded-full bg-blue-500 animate-pulse shrink-0"
            title={t('viewLiveBrowser')}
          />
        )}
        <div className="flex-1 min-w-0 flex items-center gap-1.5 px-2.5 py-1 bg-theme-primary rounded-md border border-theme text-sm">
          <Lock className="w-3.5 h-3.5 text-theme-muted shrink-0" />
          <span className="truncate text-theme-secondary">
            {session.currentUrl || t('cardDefaultTitle')}
          </span>
        </div>
      </div>
      <div className="px-3 py-2 flex items-center justify-between">
        <span className="text-xs text-theme-muted flex items-center gap-1.5">
          <Eye className="h-3 w-3" />
          {t('viewLiveBrowser')}
        </span>
        <span className="text-xs text-theme-muted truncate ml-2">
          {session.sessionId.slice(0, 12)}…
        </span>
      </div>
    </div>
  );
}
