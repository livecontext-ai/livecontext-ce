'use client';

/**
 * BrowserAgentNode - workflow node for the agent:browser_agent type.
 *
 * Visual: standard node with a live-view button at the bottom. Clicking
 * the button opens BrowserLiveCdpPanel (side panel) showing the live
 * Chromium tab via CDP screencast. First click in that iframe asks for
 * confirmation, then raises a BROWSER_USER_TAKEOVER signal - the
 * workflow blocks until the user resumes.
 */

import * as React from 'react';
import clsx from 'clsx';
import { Handle, NodeProps, Position } from 'reactflow';
import { Eye, Globe } from 'lucide-react';
import { useTranslations } from 'next-intl';

import { getNodeVisual } from '../../data/nodeVisuals';
import type { BuilderNodeData, DerivedNodeStatus } from '../../types';
import { useValidation } from '../../contexts/ValidationContext';
import {
  NodeHeader,
  useHoverVisibility,
  getStatusBorderColor,
} from './shared';
import { getProviderIconSlug } from '@/lib/ai-providers/providerIcons';
import { getEffectiveDefaultProvider } from '@/hooks/useModels';
import { NodeStatusBadge } from '../NodeStatusBadge';
import { findNodeClassById } from '../../nodes/nodeClasses';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { useNodeExecutionStatus } from '../../contexts/StepByStepContext';
import { NodeBottomBar } from './NodeBottomBar';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { AgentBrowsePanelContent } from '@/components/app/AgentBrowsePanelContent';

/**
 * Get iconSlug for Browser Agent node based on provider, mirroring
 * ClassifyNode/GuardrailNode. The provider lives at
 * `data.params.llm.provider` (BrowserAgentNodeSpec contract) - fall back
 * to data.provider for legacy nodes and to the workspace default when
 * neither is set, so the node header still shows a meaningful icon
 * before the user picks a model.
 */
function getBrowserAgentIconSlug(data: BuilderNodeData): string | undefined {
  const provider = (data as any).provider || getEffectiveDefaultProvider();
  return getProviderIconSlug(provider);
}

export function BrowserAgentNode({ data, selected, id }: NodeProps<BuilderNodeData>) {
  const t = useTranslations('workflowBuilder.nodes.browserAgent');
  // Fallback to 'reasoning' (the generic AI-agent visual) if browser_agent
  // ever drops out of NODE_VISUALS. Note: 'agent' is NOT a valid
  // BuilderNodeKind - the previous fallback was dead code that the type
  // checker now catches.
  const visuals = getNodeVisual('browser_agent') ?? getNodeVisual('reasoning');
  const { targetRef: nodeRef, isVisible: showActions, show } = useHoverVisibility<HTMLDivElement>();
  const { isRunMode, viewingEpoch } = useWorkflowMode();

  const nodeClass = React.useMemo(() => findNodeClassById(data.id || ''), [data.id]);
  const nodeFamily = nodeClass?.family;

  const executionStatus = useNodeExecutionStatus(id, { label: data.label, kind: 'browser_agent' });

  const { hasNodeErrors: checkNodeErrors } = useValidation();
  const hasError = checkNodeErrors(id);

  const effectiveStatus = React.useMemo((): DerivedNodeStatus | undefined => {
    if (viewingEpoch != null) return data.status;
    if (executionStatus.isStepByStepMode) {
      if (executionStatus.isRunning) return 'running';
      if (executionStatus.isFailed) return 'failed';
      if (executionStatus.isSkipped) return 'skipped';
      if (executionStatus.isCompleted) return 'completed';
      if (executionStatus.isReady) return 'ready';
      if (data.status && data.status !== 'pending') return data.status;
      return 'pending';
    }
    if (executionStatus.isRunning) return 'running';
    return data.status;
  }, [viewingEpoch, executionStatus, data.status]);

  const borderColor = getStatusBorderColor(effectiveStatus, hasError, isRunMode || viewingEpoch != null);
  const isSkipped = !executionStatus.isStepByStepMode && effectiveStatus === 'skipped';

  // Session id is published by the backend via the per-step stream and
  // surfaced on data.lastBrowserSessionId by the run-context wiring.
  const sessionId = data.lastBrowserSessionId;
  const sidePanel = useSidePanelSafe();

  // Open the live-view in the system right-side panel. The content JSX
  // is captured at click time, so without the useEffect below the panel
  // would freeze with whatever liveCoords were available at the moment
  // of click - clicking BEFORE cdp_ready arrives leaves it stuck on
  // "no session". The useEffect rewrites the tab content WHEN-AND-ONLY-WHEN
  // the live coords actually change shape (undefined→defined, or any
  // primitive value differs), avoiding redundant updateTab calls that
  // would force BrowserLiveCdpPanel to re-run its prop-driven reconnect
  // bookkeeping (cdpToken/sessionId/cdpWsUrl in its deps) and cause WS
  // churn on every unrelated parent re-render.
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
          nodeId: id,
        }
      : undefined;
  }, [sessionId, id, data.lastBrowserCdpToken,
      data.lastBrowserCdpWsUrl, data.lastBrowserRunId,
      data.lastBrowserCurrentUrl]);

  const handleOpenPanel = React.useCallback(() => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: tabId,
      label: data.label || 'Browser Agent',
      icon: <Globe className="w-4 h-4" />,
      content: <AgentBrowsePanelContent liveCoords={liveCoords} />,
      preferredWidth: 0.4,
      // keepMounted keeps the CDP WS alive across tab switches.
      keepMounted: true,
    });
  }, [sidePanel, tabId, data.label, liveCoords]);

  // Sync the open panel's content with newly-arrived live coords. Only
  // run when the tab is already registered (skip auto-open) AND the
  // memoized liveCoords identity actually changed (the useMemo above
  // handles primitive-equality so unrelated re-renders don't fire this).
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

  return (
    <>
      <div
        ref={nodeRef}
        className={clsx(
          'group relative rounded-[28px] bg-white/95 dark:bg-gray-800/95 px-5 py-4',
          'backdrop-blur focus-visible:outline focus-visible:outline-2 focus-visible:outline-[var(--accent-primary)] focus-visible:outline-offset-2',
          'border-2 transition-colors',
          isSkipped && !selected && 'opacity-50 focus-visible:opacity-100',
          isSkipped && selected && 'opacity-100',
        )}
        style={{
          borderColor,
          borderStyle: 'solid',
          position: 'relative',
          boxShadow: selected ? '0 0 0 2px var(--accent-primary)' : 'none',
        }}
        tabIndex={0}
      >
        {effectiveStatus === 'running' && (
          <div
            className="absolute inset-0 pointer-events-none rounded-[26px]"
            style={{
              background:
                'linear-gradient(90deg, transparent 0%, rgba(59, 130, 246, 0.15) 50%, transparent 100%)',
              backgroundSize: '200% 100%',
              animation: 'shimmer-scan 2.5s ease-in-out infinite',
            }}
          />
        )}

        <NodeHeader
          visuals={visuals}
          label={data.label}
          iconSlug={getBrowserAgentIconSlug(data)}
          nodeId={id}
          nodeKind="browser_agent"
          nodeFamily={nodeFamily}
        />

        {/* Single-line summary of the task - keeps the node tight per the
            user's "minimal panel" preference. The full task lives in the
            inspector. */}
        {data.task && (
          <div className="mt-2 text-xs text-slate-600 dark:text-slate-400 line-clamp-2">
            {data.task}
          </div>
        )}

        {effectiveStatus && effectiveStatus !== 'pending' && (
          <div className="absolute bottom-2 right-2 z-10">
            <NodeStatusBadge status={effectiveStatus} statusCounts={data.statusCounts} />
          </div>
        )}

        <NodeBottomBar
          hover={{ isVisible: showActions, onHover: show }}
          borderColor={borderColor}
          isRunning={effectiveStatus === 'running'}
          buttons={[
            {
              key: 'live-view',
              icon: <Eye size={14} />,
              title: t('viewLiveTrace'),
              onClick: handleOpenPanel,
              shimmer: effectiveStatus === 'running',
              shimmerColor: 'rgba(59, 130, 246, 0.3)',
            },
          ]}
          playButton={
            executionStatus.isStepByStepMode
              ? {
                  nodeId: id,
                  variant: 'play',
                  isAutoMode: false,
                  isTriggerNode: false,
                  stepByStepStatus: executionStatus,
                }
              : undefined
          }
          hoverActions={{
            onDelete: data.onDeleteNode ? () => data.onDeleteNode?.(data.id) : undefined,
            onDuplicate: data.onDuplicateNode ? () => data.onDuplicateNode?.(data.id) : undefined,
          }}
        />

        <Handle
          type="target"
          position={Position.Left}
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            left: -6,
            top: '50%',
            transform: 'translateY(-50%)',
            backgroundColor: 'var(--border-color)',
            opacity: isRunMode ? 0 : 1,
            pointerEvents: isRunMode ? 'none' : 'auto',
          }}
        />
        <Handle
          type="source"
          position={Position.Right}
          className="!h-3 !w-3 !rounded-full !border-2 !border-[var(--bg-primary)] nodrag nopan"
          style={{
            right: -6,
            top: '50%',
            transform: 'translateY(-50%)',
            backgroundColor: 'var(--border-color)',
            opacity: isRunMode ? 0 : 1,
            pointerEvents: isRunMode ? 'none' : 'auto',
          }}
        />
      </div>

    </>
  );
}
