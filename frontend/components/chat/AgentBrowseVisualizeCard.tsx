'use client';

/**
 * AgentBrowseVisualizeCard - chat-side preview card for an `agent_browse`
 * tool result. Mirrors {@link WebSearchVisualizeCard} visually (browser
 * chrome with traffic-light dots + address bar) so the user sees a
 * consistent "right-side browser" primitive across the product, but
 * the side panel it opens embeds the LIVE CDP canvas (via
 * {@link AgentBrowsePanelContent}) instead of static fetch screenshots.
 *
 * The card is rendered when the assistant text contains a marker
 * `[visualize:agent_browse:{interfaceId}]` (emitted by
 * `WebSearchToolsProvider#persistAndEnrichResult` when action is
 * `agent_browse`). The Interface entity stores the session_id, cdp_token,
 * cdp_ws_url, last_url that the panel needs to open the WS bridge.
 */

import React, { useState, useEffect } from 'react';
import { Globe, X, Lock, Eye } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import type { Interface } from '@/lib/api/orchestrator/types';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentBrowsePanelContent } from '@/components/app/AgentBrowsePanelContent';

import LoadingSpinner from '@/components/LoadingSpinner';
import { useTranslations } from 'next-intl';

interface AgentBrowseVisualizeCardProps {
  interfaceId: string;
  title?: string;
}

export function AgentBrowseVisualizeCard({ interfaceId, title }: AgentBrowseVisualizeCardProps) {
  const [interfaceData, setInterfaceData] = useState<Interface | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sidePanel = useSidePanelSafe();
  const t = useTranslations('agentBrowse');

  // Refresh trigger so progressive Interface updates (the runner appends
  // step results during the session) bump this card's snapshot.
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('webSearchModified', handleModified);
    return () => window.removeEventListener('webSearchModified', handleModified);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const data = await orchestratorApi.getInterface(interfaceId);
        if (!cancelled) setInterfaceData(data);
      } catch (err: unknown) {
        if (!cancelled) {
          const msg = err instanceof Error ? err.message : 'Failed to load';
          setError(msg);
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [interfaceId, refreshKey]);

  // Pull the latest agent_browse result entry from the Interface data
  // so the card preview shows the current URL + session id.
  const data = interfaceData?.data as Record<string, unknown> | undefined;
  const results = (data?.results as Array<Record<string, unknown>>) || [];
  const latestBrowse = [...results].reverse().find(r => r.action === 'agent_browse') as
    | { url?: string; session_id?: string; final_url?: string; task?: string }
    | undefined;
  const currentUrl = latestBrowse?.final_url || latestBrowse?.url || '';
  const sessionId = latestBrowse?.session_id;
  const taskLabel = latestBrowse?.task || title || t('cardDefaultTitle');

  // Unified with AgentBrowseLivePreview: key the tab on the SESSION id (not the
  // interface id) so the in-run "View live browser" card and this result card
  // open the SAME side-panel tab instead of two different ones (live screencast
  // vs final page). Fall back to interfaceId until the session id has loaded.
  const tabId = `agent-browse-${sessionId || interfaceId}`;
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === tabId;

  const handleClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(tabId);
      sidePanel.close();
      return;
    }
    sidePanel.openTab({
      id: tabId,
      label: taskLabel,
      icon: <Globe className="w-4 h-4" />,
      content: <AgentBrowsePanelContent interfaceId={interfaceId} />,
      preferredWidth: 0.4,
    });
  };

  if (isLoading) {
    return (
      <div className="my-4 rounded-[18px] border border-theme overflow-hidden bg-theme-secondary">
        <div className="h-[80px] flex items-center justify-center">
          <LoadingSpinner size="md" />
        </div>
      </div>
    );
  }

  if (error || !interfaceData) {
    return (
      <div className="my-4 rounded-[18px] border border-theme overflow-hidden bg-theme-primary">
        <div className="flex flex-col items-center justify-center min-h-[80px] text-theme-muted">
          <Globe className="w-8 h-8 mb-2 opacity-50" />
          <span className="text-sm">{t('cardErrorFallback')}</span>
        </div>
      </div>
    );
  }

  return (
    <div
      onClick={handleClick}
      className="my-4 w-full rounded-[18px] border border-theme overflow-hidden bg-theme-primary cursor-pointer isolate relative"
    >
      {/* Active tab overlay */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-[18px] cursor-pointer">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}

      {/* Browser chrome - title bar with dots + address bar.
          Matches WebSearchVisualizeCard layout exactly so any "look at a
          page" primitive in chat reads as the same UI. */}
      <div className="flex items-center gap-2 px-3 py-2 bg-theme-secondary border-b border-theme">
        <div className="flex items-center gap-1.5">
          <span className="w-2.5 h-2.5 rounded-full bg-red-400/70" />
          <span className="w-2.5 h-2.5 rounded-full bg-yellow-400/70" />
          <span className="w-2.5 h-2.5 rounded-full bg-green-400/70" />
        </div>
        <div className="flex-1 flex items-center gap-1.5 px-2.5 py-1 bg-theme-primary rounded-md border border-theme text-sm">
          <Lock className="w-3.5 h-3.5 text-theme-muted shrink-0" />
          <span className="truncate text-theme-secondary">
            {currentUrl || taskLabel}
          </span>
        </div>
      </div>

      {/* Footer */}
      <div className="bg-theme-primary border-t border-theme px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <Globe className="w-4 h-4 text-theme-secondary shrink-0" />
              <span className="text-sm font-medium text-theme-primary truncate">{taskLabel}</span>
            </div>
            <div className="flex items-center gap-2 mt-0.5">
              <span className="text-xs text-theme-muted flex items-center gap-1">
                <Eye className="h-3 w-3" />
                {t('viewLiveBrowser')}
              </span>
              {sessionId && (
                <span className="text-xs text-theme-muted truncate">
                  {sessionId.slice(0, 16)}…
                </span>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
