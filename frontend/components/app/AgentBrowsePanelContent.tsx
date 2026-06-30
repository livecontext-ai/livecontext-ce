'use client';

/**
 * AgentBrowsePanelContent - content slot for the chat right-side panel
 * when the user clicks "View live browser" on an agent_browse card.
 *
 * The panel embeds {@link BrowserLiveCdpPanel} inline (no fixed-position
 * aside chrome - the SidePanel context already provides the outer
 * frame). Connection coordinates are read from the Interface entity
 * persisted by `WebSearchToolsProvider` when the agent_browse tool ran:
 *
 *   - session_id   - for the wss:// URL path + status polling
 *   - cdp_token    - short-lived JWT for the WS upgrade
 *   - cdp_ws_url   - wss://websearch-host/cdp/{sid}
 *   - run_id       - orchestrator run id (so /takeover-resume + token
 *                    refresh hit the right (runId, nodeId) pair)
 *
 * In the chat-tool path, runId == streamId == conversationId of the
 * tool call's turn; nodeId == toolCallId. We surface them as props to
 * BrowserLiveCdpPanel which already knows how to call the takeover +
 * refresh endpoints.
 */

import React, { useState, useEffect } from 'react';
import { Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { orchestratorApi } from '@/lib/api';
import type { Interface } from '@/lib/api/orchestrator/types';
import LoadingSpinner from '@/components/LoadingSpinner';
import { BrowserLiveCdpPanel } from '@/app/workflows/builder/components/nodes/shared/BrowserLiveCdpPanel';

interface AgentBrowsePanelContentProps {
  /** Pass either `interfaceId` (post-completion path - Interface entity
   *  carries the persisted result) OR `liveCoords` (mid-execution path
   *  - coords pushed by BrowserSessionLifecycleService before the
   *  blocking tool call returns). When both are provided, `liveCoords`
   *  takes precedence as long as the live panel is still relevant. */
  interfaceId?: string;
  liveCoords?: {
    sessionId: string;
    cdpToken: string;
    cdpWsUrl: string;
    currentUrl?: string;
    runId: string;
    nodeId: string;
  };
}

interface AgentBrowseResultRecord {
  action: string;
  session_id?: string;
  cdp_token?: string;
  cdp_ws_url?: string;
  run_id?: string;
  node_id?: string;
  task?: string;
  url?: string;
  final_url?: string;
  status?: string;
  step_index?: number;
  cost_usd?: number;
}

export function AgentBrowsePanelContent({ interfaceId, liveCoords }: AgentBrowsePanelContentProps) {
  const [interfaceData, setInterfaceData] = useState<Interface | null>(null);
  const [isLoading, setIsLoading] = useState(!liveCoords && !!interfaceId);
  const [error, setError] = useState<string | null>(null);
  const t = useTranslations('agentBrowse');

  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('webSearchModified', handleModified);
    return () => window.removeEventListener('webSearchModified', handleModified);
  }, []);

  useEffect(() => {
    // Live-coords path: skip Interface fetch - we already have everything
    // we need (cdp_token, cdp_ws_url, sessionId, runId, nodeId) directly
    // from the streaming AGENT_BROWSE_STEP event.
    if (liveCoords) {
      setIsLoading(false);
      return;
    }
    if (!interfaceId) return;
    let cancelled = false;
    async function load() {
      try {
        const data = await orchestratorApi.getInterface(interfaceId!);
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

  // BrowserLiveCdpPanel uses its `onClose` prop to dismiss its own
  // fixed-position aside. In the chat side-panel slot the surrounding
  // SidePanel chrome already has a close button - make ours a no-op.
  // The panel's takeover + reconnect logic doesn't depend on onClose.
  const noop = () => { /* close is handled by the SidePanel host */ };

  type DerivedNodeStatus = 'running' | 'completed' | 'failed' | 'pending' | 'awaiting_signal' | 'skipped' | 'partial_success' | 'ready';

  // Live-coords fast path: bypass the Interface fetch entirely. Used
  // when the panel was opened from a streaming AGENT_BROWSE_STEP event
  // (live-view bootstrap from BrowserSessionLifecycleService) - the
  // CDP coords are direct, the Chromium session is alive RIGHT NOW.
  if (liveCoords) {
    return (
      <div className="h-full overflow-hidden">
        <BrowserLiveCdpPanel
          nodeId={liveCoords.nodeId}
          runId={liveCoords.runId}
          sessionId={liveCoords.sessionId}
          cdpWsUrl={liveCoords.cdpWsUrl}
          cdpToken={liveCoords.cdpToken}
          status={'running' as DerivedNodeStatus}
          embedded
          onClose={noop}
        />
      </div>
    );
  }

  // Workflow path before a session has started - neither live coords
  // nor a persisted Interface entity exists yet. Show a friendly
  // "no session yet" message instead of the generic load-error.
  if (!interfaceId) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-theme-muted gap-3">
        <Globe className="w-10 h-10 opacity-50" />
        <span className="text-sm">{t('panelNoSession')}</span>
      </div>
    );
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error || !interfaceData) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-theme-muted">
        <Globe className="w-10 h-10 mb-3 opacity-50" />
        <span className="text-sm">{error || t('panelErrorFallback')}</span>
      </div>
    );
  }

  // Pull the latest agent_browse entry from the Interface data - multiple
  // calls in the same turn would append to .results, so the most-recent
  // is the last entry with action='agent_browse'.
  const data = interfaceData.data as Record<string, unknown> | undefined;
  const results = (data?.results as AgentBrowseResultRecord[]) || [];
  const latest = [...results].reverse().find(r => r.action === 'agent_browse');

  if (!latest) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-theme-muted">
        <Globe className="w-10 h-10 mb-3 opacity-50" />
        <span className="text-sm">{t('panelNoSession')}</span>
      </div>
    );
  }

  // Map status string to the `DerivedNodeStatus` BrowserLiveCdpPanel
  // expects. The Interface stores generic strings ('running'/'completed'
  // /'failed') - but `WebSearchToolsProvider.persistAndEnrichResult`
  // doesn't currently write a `status` field on the agent_browse
  // record, so `latest.status` is typically undefined here.
  //
  // CRITICAL: this branch IS the post-completion path (we have an
  // Interface entity → tool already finished). Defaulting to
  // 'completed' makes the panel render "Session ended" instead of
  // the misleading "Waiting for the browser session to start…"
  // (which is the 'running' fallback shown when no live frames are
  // flowing). Only treat it as 'failed' if the runner explicitly
  // marked it so via `stop_reason` (forwarded by the Java tool result
  // enrichment when present).
  const stopReason = (latest as { stop_reason?: string }).stop_reason;
  const failedStopReasons = ['LLM_FAILED', 'DOMAIN_BLOCKED', 'TIMEOUT',
    'SCHEMA_MISMATCH', 'BUDGET_EXHAUSTED', 'CANCELLED', 'ERROR'];
  const status: DerivedNodeStatus = latest.status === 'failed'
      || (stopReason && failedStopReasons.includes(stopReason))
    ? 'failed'
    : 'completed';

  return (
    <div className="h-full overflow-hidden">
      <BrowserLiveCdpPanel
        nodeId={latest.node_id || `agent-browse-${interfaceId}`}
        runId={latest.run_id}
        sessionId={latest.session_id}
        cdpWsUrl={latest.cdp_ws_url}
        cdpToken={latest.cdp_token}
        status={status}
        embedded
        stepIndex={latest.step_index}
        costUsd={latest.cost_usd}
        lastAction={latest.task}
        onClose={noop}
      />
    </div>
  );
}
