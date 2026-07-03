'use client';

/**
 * useBrowserLiveView - shared live-view side-panel wiring for any builder
 * node that can host a browser-agent session:
 *
 *   - the dedicated agent:browser_agent node ({@link BrowserAgentNode}),
 *   - a GENERIC agent node whose remote loop called
 *     web_search(action=agent_browse) - the backend fans the cdp_ready
 *     bootstrap out on the workflow channel addressed to the HOST node,
 *     so `data.lastBrowser*` gets populated on this node too.
 *
 * The hook owns the open-tab + live-coords-sync logic that used to live in
 * BrowserAgentNode: the tab content is captured at click time, so a
 * click BEFORE cdp_ready arrives would freeze the panel on "no session"
 * without the sync effect; the effect rewrites the tab content
 * when-and-only-when the memoized coords identity changes, avoiding WS
 * churn from redundant updateTab calls.
 *
 * The panel's REST calls (takeover-resume / cdp-token-refresh /
 * final-screenshot) are keyed by the runner's CONTROL address. For the
 * dedicated node that equals (runId, builder node id); for a generic agent
 * node the control node id is the tool-call id, published on the event as
 * `control_node_id` and surfaced here via `data.lastBrowserNodeId`.
 */

import * as React from 'react';
import { Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';

import type { BuilderNodeData } from '../../../types';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentBrowsePanelContent } from '@/components/app/AgentBrowsePanelContent';

export function useBrowserLiveView(id: string, data: BuilderNodeData): {
  /** True once a browser session announced itself on this node with the
   *  FULL coordinate set (session + token + ws URL + run id) - i.e. the
   *  panel can actually connect. Gate conditional affordances (the
   *  generic agent node's eye button) on this, not on the session id
   *  alone, so an unconfigured CDP issuer can't produce a dead button. */
  hasLiveSession: boolean;
  /** Open (or focus) the live-view side-panel tab for this node. */
  openLiveView: () => void;
} {
  const t = useTranslations('workflowBuilder.nodes.browserAgent');
  const sidePanel = useSidePanelSafe();
  const sessionId = data.lastBrowserSessionId;

  const tabId = `agent-browse-${id}`;
  const liveCoords = React.useMemo(() => {
    return sessionId && data.lastBrowserCdpToken
        && data.lastBrowserCdpWsUrl && data.lastBrowserRunId
      ? {
          sessionId,
          cdpToken: data.lastBrowserCdpToken,
          cdpWsUrl: data.lastBrowserCdpWsUrl,
          currentUrl: data.lastBrowserCurrentUrl ?? '',
          runId: data.lastBrowserRunId,
          // Control address for the panel's REST calls - the tool-call id
          // for a generic agent node, the builder node id (identical to the
          // control id) for the dedicated browser_agent node.
          nodeId: data.lastBrowserNodeId || id,
        }
      : undefined;
  }, [sessionId, id, data.lastBrowserCdpToken,
      data.lastBrowserCdpWsUrl, data.lastBrowserRunId,
      data.lastBrowserCurrentUrl, data.lastBrowserNodeId]);

  const openLiveView = React.useCallback(() => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: tabId,
      label: data.label || t('tabLabel'),
      icon: <Globe className="w-4 h-4" />,
      content: <AgentBrowsePanelContent liveCoords={liveCoords} />,
      preferredWidth: 0.5,
      // keepMounted keeps the CDP WS alive across tab switches.
      keepMounted: true,
    });
  }, [sidePanel, tabId, data.label, liveCoords, t]);

  // Sync the open panel's content with newly-arrived live coords. Only
  // run when the tab is already registered (skip auto-open) AND the
  // memoized liveCoords identity actually changed.
  const lastPushedCoordsRef = React.useRef<typeof liveCoords | null>(null);
  React.useEffect(() => {
    if (!sidePanel) return;
    if (lastPushedCoordsRef.current === liveCoords) return;
    const tabExists = sidePanel.tabs?.some(t => t.id === tabId);
    if (!tabExists) return;
    lastPushedCoordsRef.current = liveCoords;
    sidePanel.updateTab(tabId, {
      content: <AgentBrowsePanelContent liveCoords={liveCoords} />,
    });
  }, [sidePanel, tabId, liveCoords]);

  return { hasLiveSession: Boolean(liveCoords), openLiveView };
}
