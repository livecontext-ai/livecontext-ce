'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, DerivedNodeStatus, StatusCounts } from '../types';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import type { EpochState, EpochSignalInfo } from '@/lib/api/orchestrator/types';
import { edgeMatchesBatchEdge, type BatchEdgeData } from '../services/edgeMatcher';
import {
  normalizeLabel,
  extractCoreLabelWithoutPort,
  extractAgentLabelWithoutPort,
} from '../utils/labelNormalizer';
import {
  normalizeStatusCounts,
  deriveStatusFromCounts,
} from '../utils/statusCounts';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';

/** Minimal shape of a live all-mode batch step consumed by the running overlay. */
type LiveBatchStep = {
  id?: string;
  nodeId?: string;
  stepId?: string;
  normalizedStepId?: string;
  stepAlias?: string;
  statusCounts?: Record<string, number>;
};

interface UseEpochStateViewingOptions {
  viewingEpoch?: number | null;
  runId?: string;
  nodesRef: React.MutableRefObject<Node<BuilderNodeData>[]>;
  edgesRef: React.MutableRefObject<Edge[]>;
  setNodes: (nodes: Node<BuilderNodeData>[] | ((prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[])) => void;
  setEdges: (edges: Edge[] | ((prev: Edge[]) => Edge[])) => void;
  workflowLoaded: boolean;
  /**
   * Flips false→true once the canvas nodes are committed. Needed because the
   * paint effect below bails on an empty {@code nodesRef} and otherwise keys off
   * refs + {@code workflowLoaded} only - so when a pinned/async-loaded graph
   * flips {@code workflowLoaded} true BEFORE its nodes are committed, the effect
   * bails and never retriggers. Including this reactive flag re-fires the paint
   * the moment the nodes exist. (Flips once per load; stable during execution.)
   */
  nodesReady?: boolean;
  /** Backend snapshot sequence - triggers re-fetch when state changes while viewing an epoch. */
  snapshotSeq?: number;
  /**
   * Live all-mode batch steps (carry `statusCounts.running`). The per-epoch
   * count rows never persist a running count, so for the ACTIVE epoch the focus
   * view reads the running ITEM count from here - the same source the
   * all-epochs view renders - to show "N running" badges on nodes.
   */
  batchSteps?: LiveBatchStep[];
  /** Live all-mode batch edges (carry a `running` count). Same purpose, for edges. */
  batchEdges?: BatchEdgeData[];
}

/**
 * Extract the normalized label from a backend node key, stripping prefix and port.
 *
 * Examples:
 * - "mcp:step1" → normalizeLabel("step1")
 * - "core:my_decision:if" → normalizeLabel("my_decision") (port stripped)
 * - "agent:ai_sorter:category_0" → normalizeLabel("ai_sorter") (port stripped)
 * - "trigger:webhook" → normalizeLabel("webhook")
 */
function extractNormalizedLabelFromKey(key: string): string | null {
  // core: and agent: keys may have ports - use specialized extractors
  if (key.startsWith('core:')) {
    return extractCoreLabelWithoutPort(key);
  }
  if (key.startsWith('agent:')) {
    return extractAgentLabelWithoutPort(key);
  }

  // Other prefixes: trigger:, mcp:, table:, interface:, note:
  const prefixes = ['trigger:', 'mcp:', 'table:', 'interface:', 'note:'];
  for (const prefix of prefixes) {
    if (key.startsWith(prefix)) {
      return normalizeLabel(key.substring(prefix.length));
    }
  }
  return null;
}

/** Statuses that indicate a node has finished - signal override should not apply. */
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'skipped']);

/**
 * Build a lookup map: normalized label → Node, for O(1) matching.
 */
function buildNodeLabelMap(
  nodes: Node<BuilderNodeData>[]
): Map<string, Node<BuilderNodeData>> {
  const map = new Map<string, Node<BuilderNodeData>>();
  for (const node of nodes) {
    const label = normalizeLabel(node.data.label);
    if (label) {
      map.set(label, node);
    }
  }
  return map;
}

/**
 * Hook to apply historical epoch state to canvas nodes.
 *
 * When viewingEpoch is non-null:
 * 1. Saves current live node/edge state
 * 2. Fetches pre-aggregated epoch state + active signals from backend
 * 3. Resets all nodes to idle, then applies epoch node counts
 * 4. Applies epoch edge counts using edgeMatcher for accurate matching
 * 5. Marks nodes with active signals as awaiting_signal
 *
 * When viewingEpoch returns to null:
 * - Restores saved live state so SSE naturally resumes
 */
export function useEpochStateViewing({
  viewingEpoch,
  runId,
  nodesRef,
  edgesRef,
  setNodes,
  setEdges,
  workflowLoaded,
  nodesReady,
  snapshotSeq,
  batchSteps,
  batchEdges,
}: UseEpochStateViewingOptions): void {
  // Store live state snapshot when entering epoch viewing
  const liveNodesRef = React.useRef<Node<BuilderNodeData>[] | null>(null);
  const liveEdgesRef = React.useRef<Edge[] | null>(null);

  // Live all-mode batch data kept in refs so applyEpochState (which runs async
  // after the fetch) always reads the freshest running counts without adding
  // them as effect deps. They're refreshed by the same batch-updates that bump
  // snapshotSeq, so the effect re-runs and re-applies with current values.
  const batchStepsRef = React.useRef<LiveBatchStep[] | undefined>(batchSteps);
  const batchEdgesRef = React.useRef<BatchEdgeData[] | undefined>(batchEdges);
  React.useEffect(() => { batchStepsRef.current = batchSteps; }, [batchSteps]);
  React.useEffect(() => { batchEdgesRef.current = batchEdges; }, [batchEdges]);

  // In-flight guard: prevent redundant concurrent fetches without debounce.
  // When a fetch is in-flight, new epoch/seq changes are tracked and re-fetched after completion.
  const fetchingRef = React.useRef(false);
  const latestSeqRef = React.useRef(snapshotSeq ?? -1);
  const appliedSeqRef = React.useRef(-1);
  // Track previous viewingEpoch so we only pre-reset on epoch change,
  // not on every snapshotSeq tick (MF-1 audit 2026-05-09).
  // Sentinel `undefined` (NOT null - null is a valid epoch state for "live mode")
  // so the first comparison fires the reset for the initial null → N transition.
  const prevViewingEpochRef = React.useRef<number | null | undefined>(undefined);
  // Track pending epoch: when a fetch is in-flight and the user navigates to a new epoch,
  // we store it here so the in-flight fetch triggers a re-fetch on completion.
  const pendingEpochRef = React.useRef<number | null>(null);

  // Track the latest seq without triggering effects
  React.useEffect(() => {
    if (snapshotSeq != null) {
      latestSeqRef.current = snapshotSeq;
    }
  }, [snapshotSeq]);

  React.useEffect(() => {
    if (!workflowLoaded || nodesRef.current.length === 0) {
      return;
    }

    // Returning to live mode - restore saved structural state.
    // useRunStateProcessing (called after this hook) will then apply current
    // batchSteps on top, overwriting any stale status values.
    if (viewingEpoch == null) {
      // MF-4 (audit 2026-05-09): track the live-restore transition in
      // prevViewingEpochRef so a subsequent re-entry (null → N where N
      // equals the previously-viewed epoch) correctly fires the pre-reset.
      // Without this, re-clicking the SAME epoch after returning to live
      // skips the gate (prev still equals N) → user sees stale live counts
      // during the in-flight fetch.
      prevViewingEpochRef.current = null;
      // If we left run mode (no runId), don't restore the saved live snapshot -
      // edit mode must show a clean plan. Just discard the refs so the workflow
      // loader's fresh nodes/edges are kept.
      if (!runId) {
        if (liveNodesRef.current || liveEdgesRef.current) {
          streamDebug.log('EpochStateViewing', 'Discarding live snapshot (left run mode)');
          liveNodesRef.current = null;
          liveEdgesRef.current = null;
        }
        return;
      }
      if (liveNodesRef.current) {
        streamDebug.log('EpochStateViewing', 'Restoring live nodes (structural):', {
          count: liveNodesRef.current.length,
          statuses: liveNodesRef.current.slice(0, 5).map(n => `${n.data.label}:${n.data.status}`),
        });
        setNodes(liveNodesRef.current);
        nodesRef.current = liveNodesRef.current;
        liveNodesRef.current = null;
      }
      if (liveEdgesRef.current) {
        streamDebug.log('EpochStateViewing', 'Restoring live edges:', {
          count: liveEdgesRef.current.length,
        });
        setEdges(liveEdgesRef.current);
        edgesRef.current = liveEdgesRef.current;
        liveEdgesRef.current = null;
      }
      return;
    }

    // Entering epoch viewing mode
    if (!runId) {
      return;
    }

    // Save live state on first epoch selection
    if (!liveNodesRef.current) {
      liveNodesRef.current = nodesRef.current;
      liveEdgesRef.current = edgesRef.current;
      streamDebug.log('EpochStateViewing', 'Saved live state for epoch viewing:', {
        nodeCount: nodesRef.current.length,
        edgeCount: edgesRef.current.length,
        epoch: viewingEpoch,
      });
    }

    // Pre-reset canvas counts SYNCHRONOUSLY on epoch CHANGE - don't wait for
    // the async fetch to complete. Without this, the user sees stale aggregate
    // counts on the canvas (and "all" totals in the side panel) until the
    // fetch resolves. Refresh "fixed" the perceived bug because nodesRef.current
    // started empty post-refresh, so nothing stale showed during the fetch.
    //
    // MF-1 (audit 2026-05-09): gate the pre-reset to ONLY fire when
    // `viewingEpoch` actually changed. Without this gate, the pre-reset
    // would fire on every snapshotSeq tick (SSE update while parked on
    // an epoch), causing visible flicker every cycle. The fetch+apply that
    // follows will still overlay epoch-specific counts on top of the reset
    // OR on top of stale data if no reset happened - both correct.
    if (prevViewingEpochRef.current !== viewingEpoch) {
      setNodes(prevNodes => prevNodes.map(node => ({
        ...node,
        data: {
          ...node.data,
          status: undefined as DerivedNodeStatus | undefined,
          statusCounts: undefined as StatusCounts | undefined,
          selectedBranch: undefined,
        },
      })));
      setEdges(prevEdges => prevEdges.map(edge => ({
        ...edge,
        data: { ...edge.data, status: undefined, statusCounts: undefined },
      })));
    }
    prevViewingEpochRef.current = viewingEpoch;

    // Fetch epoch data and apply to canvas (with in-flight guard)
    let cancelled = false;

    async function fetchAndApply(epochToFetch?: number) {
      const epoch = epochToFetch ?? viewingEpoch!;

      // If another fetch is in-flight, mark this epoch as pending - it will be
      // fetched when the current one completes (avoids skipping epoch navigations).
      if (fetchingRef.current) {
        pendingEpochRef.current = epoch;
        streamDebug.log('EpochStateViewing', 'Fetch in-flight, queued pending epoch:', {
          pendingEpoch: epoch,
        });
        return;
      }
      fetchingRef.current = true;
      pendingEpochRef.current = null;
      const seqAtStart = latestSeqRef.current;

      streamDebug.log('EpochStateViewing', 'Fetching epoch data:', {
        epoch,
        seq: seqAtStart,
      });

      try {
        // Marketplace public preview: the module-level store published
        // { publicationId, showcaseRunId } - route both fetches through the
        // allowlisted public endpoints so anonymous visitors see epoch
        // statusCount resets identically to authenticated owners.
        const publicCtx = getActivePublicPreview();
        const usePublic = !!(publicCtx && publicCtx.showcaseRunId === runId);
        const [epochState, signals] = usePublic && publicCtx
          ? await Promise.all([
              publicationService.getShowcaseEpochState(publicCtx.publicationId, epoch, publicCtx.remote) as Promise<EpochState>,
              publicationService.getShowcaseEpochSignals(publicCtx.publicationId, epoch, publicCtx.remote) as Promise<EpochSignalInfo[]>,
            ])
          : await Promise.all([
              orchestratorApi.getEpochState(runId!, epoch),
              orchestratorApi.getEpochSignals(runId!, epoch),
            ]);

        if (cancelled) return;

        streamDebug.log('EpochStateViewing', 'Epoch data received:', {
          epoch,
          nodeKeys: Object.keys(epochState.nodes || {}),
          edgeKeys: Object.keys(epochState.edges || {}),
          triggerId: (epochState as Record<string, unknown>).triggerId,
        });

        appliedSeqRef.current = seqAtStart;
        applyEpochState(epochState, signals);
      } catch (err) {
        console.error('[useEpochStateViewing] Failed to fetch epoch data:', err);
      } finally {
        fetchingRef.current = false;

        // Check for pending epoch change (user navigated during fetch)
        const pending = pendingEpochRef.current;
        if (!cancelled && pending != null && pending !== epoch) {
          streamDebug.log('EpochStateViewing', 'Processing pending epoch after fetch:', {
            completedEpoch: epoch,
            pendingEpoch: pending,
          });
          pendingEpochRef.current = null;
          fetchAndApply(pending);
        } else if (!cancelled && latestSeqRef.current > seqAtStart) {
          // Re-fetch if seq changed during flight (new data available)
          fetchAndApply(epoch);
        }
      }
    }

    function applyEpochState(epochState: EpochState, signals: EpochSignalInfo[]) {
      // Start from the saved live nodes (clean structure) but reset all statuses
      const sourceNodes = liveNodesRef.current || nodesRef.current;

      // Log which nodes had statusCounts BEFORE reset
      const nodesWithCountsBefore = sourceNodes
        .filter(n => n.data.statusCounts)
        .map(n => `${n.data.label}:${JSON.stringify(n.data.statusCounts)}`);
      streamDebug.log('EpochStateViewing', 'BEFORE reset - nodes with statusCounts:', {
        count: nodesWithCountsBefore.length,
        nodes: nodesWithCountsBefore,
        sourceIsLive: !!liveNodesRef.current,
      });

      const baseNodes = sourceNodes.map(node => ({
        ...node,
        data: {
          ...node.data,
          status: undefined as DerivedNodeStatus | undefined,
          statusCounts: undefined as StatusCounts | undefined,
          selectedBranch: undefined,
        },
      }));

      // Build O(1) lookup: normalized label → node index
      const labelToIndex = new Map<string, number>();
      for (let i = 0; i < baseNodes.length; i++) {
        const label = normalizeLabel(baseNodes[i].data.label);
        if (label) {
          labelToIndex.set(label, i);
        }
      }

      // Apply node counts from epoch state using pre-built map (O(N+M))
      const updatedNodes = [...baseNodes];
      const appliedNodes: string[] = [];
      for (const [key, counts] of Object.entries(epochState.nodes || {})) {
        const normalizedLabel = extractNormalizedLabelFromKey(key);
        if (!normalizedLabel) continue;

        const idx = labelToIndex.get(normalizedLabel);
        if (idx === undefined) continue;

        const derivedStatus = deriveStatusFromCounts(counts);
        const normalizedCounts = normalizeStatusCounts(counts);
        appliedNodes.push(`${key}→${updatedNodes[idx].data.label}:${derivedStatus}:${JSON.stringify(normalizedCounts)}`);

        updatedNodes[idx] = {
          ...updatedNodes[idx],
          data: {
            ...updatedNodes[idx].data,
            status: derivedStatus,
            statusCounts: normalizedCounts,
          },
        };
      }

      streamDebug.log('EpochStateViewing', 'AFTER apply - nodes with epoch data:', {
        appliedCount: appliedNodes.length,
        applied: appliedNodes,
      });

      // Overlay running nodes from the epoch state: `runningNodeIds` is the
      // authoritative "still in-flight" SET (backend merges JSONB + Redis), but
      // it carries no per-node count. The running ITEM count (e.g. a split node
      // processing 5 items) lives only in the live all-mode stream, so for the
      // ACTIVE epoch we read it from `batchStepsRef` (same source the all-epochs
      // view renders) and attach it as a RUNNING statusCount - otherwise the
      // focus view showed the shimmer but no number. Override AFTER terminal
      // counts so a split node with items still in flight shimmers even when
      // some items have already completed. The signals overlay below runs last
      // and may flip 'running' → 'awaiting_signal'.
      const runningCountByLabel = new Map<string, number>();
      if (epochState.isActive) {
        for (const step of batchStepsRef.current || []) {
          const rc = Number(step?.statusCounts?.running ?? step?.statusCounts?.RUNNING ?? 0);
          if (!Number.isFinite(rc) || rc <= 0) continue;
          const stepKey = step?.id || step?.nodeId || step?.stepId || step?.normalizedStepId || '';
          const lbl = extractNormalizedLabelFromKey(stepKey)
            || (step?.stepAlias ? normalizeLabel(step.stepAlias) : null);
          if (lbl) runningCountByLabel.set(lbl, rc);
        }
      }
      const appliedRunning: string[] = [];
      for (const key of epochState.runningNodeIds || []) {
        const normalizedLabel = extractNormalizedLabelFromKey(key);
        if (!normalizedLabel) continue;
        const idx = labelToIndex.get(normalizedLabel);
        if (idx === undefined) continue;
        appliedRunning.push(`${key}→${updatedNodes[idx].data.label}`);
        // Attach the live running item count (active epoch) so the badge shows
        // "N running" like the all-epochs view - merged onto any terminal
        // counts already applied above (split node: some done, some in flight).
        const runningCount = runningCountByLabel.get(normalizedLabel);
        const mergedCounts: StatusCounts | undefined = runningCount
          ? { ...(updatedNodes[idx].data.statusCounts || {}), RUNNING: runningCount }
          : updatedNodes[idx].data.statusCounts;
        updatedNodes[idx] = {
          ...updatedNodes[idx],
          data: {
            ...updatedNodes[idx].data,
            status: 'running' as DerivedNodeStatus,
            statusCounts: mergedCounts,
          },
        };
      }
      if (appliedRunning.length > 0) {
        streamDebug.log('EpochStateViewing', 'AFTER running overlay - nodes set to running:', {
          count: appliedRunning.length,
          nodes: appliedRunning,
        });
      }

      // Log final state of ALL nodes
      const finalNodesWithCounts = updatedNodes
        .filter(n => n.data.statusCounts || n.data.status)
        .map(n => `${n.data.label}:status=${n.data.status},counts=${JSON.stringify(n.data.statusCounts)}`);
      streamDebug.log('EpochStateViewing', 'FINAL state - nodes with status/counts:', {
        count: finalNodesWithCounts.length,
        nodes: finalNodesWithCounts,
      });

      // Override status for nodes with active signals (pending approval, interface, etc.)
      // Signal nodeId is a backend key (e.g., "core:my_approval"), so we match by label.
      // Skip override if the node already has a terminal status (completed/failed/skipped) -
      // non-blocking interface signals stay PENDING in DB even after the node completes.
      if (signals.length > 0) {
        const nodeLabelMap = buildNodeLabelMap(updatedNodes);

        for (const signal of signals) {
          const signalLabel = extractNormalizedLabelFromKey(signal.nodeId);
          if (!signalLabel) continue;

          const matchedNode = nodeLabelMap.get(signalLabel);
          if (!matchedNode) continue;

          // Find the index of this node to update it
          const idx = updatedNodes.findIndex(n => n.id === matchedNode.id);
          if (idx === -1) continue;

          // Don't override terminal statuses - the node completed despite having a pending signal
          if (updatedNodes[idx].data.status && TERMINAL_STATUSES.has(updatedNodes[idx].data.status!)) {
            continue;
          }

          updatedNodes[idx] = {
            ...updatedNodes[idx],
            data: {
              ...updatedNodes[idx].data,
              status: 'awaiting_signal' as DerivedNodeStatus,
            },
          };
        }
      }

      // Apply edge counts from epoch state using edgeMatcher for accurate matching
      const baseEdges = liveEdgesRef.current || edgesRef.current;
      const nodeMap = new Map(updatedNodes.map(n => [n.id, n]));

      // Log edges BEFORE reset
      const edgesWithStatusBefore = baseEdges
        .filter(e => e.data?.status)
        .map(e => `${e.source}→${e.target}:${e.data?.status}`);
      streamDebug.log('EpochStateViewing', 'EDGES BEFORE reset:', {
        total: baseEdges.length,
        withStatus: edgesWithStatusBefore.length,
        edges: edgesWithStatusBefore,
        sourceIsLive: !!liveEdgesRef.current,
      });

      const matchedEdges: string[] = [];
      const resetEdges: string[] = [];
      const unchangedEdges: string[] = [];

      // Log all backend edge keys for debugging
      const backendEdgeKeys = Object.keys(epochState.edges || {});
      streamDebug.log('EpochStateViewing', 'Backend edge keys:', backendEdgeKeys);

      const updatedEdges = baseEdges.map(edge => {
        const sourceNode = nodeMap.get(edge.source);
        const targetNode = nodeMap.get(edge.target);
        if (!sourceNode || !targetNode) {
          unchangedEdges.push(`${edge.source}→${edge.target}:no-nodes`);
          return edge;
        }

        const edgeDesc = `${sourceNode.data.label}(${edge.source})→${targetNode.data.label}(${edge.target}) handle=${edge.sourceHandle}`;

        // 1. Terminal counts from the per-epoch edge rows.
        let counts: Record<string, number> | undefined;
        for (const [edgeKey, raw] of Object.entries(epochState.edges || {})) {
          const arrowIdx = edgeKey.indexOf('->');
          if (arrowIdx === -1) continue;
          const batchEdge: BatchEdgeData = { from: edgeKey.substring(0, arrowIdx), to: edgeKey.substring(arrowIdx + 2), id: edgeKey };
          if (edgeMatchesBatchEdge(edge, batchEdge, sourceNode, targetNode, updatedNodes)) {
            counts = normalizeStatusCounts(raw);
            matchedEdges.push(`${edgeDesc} ← ${edgeKey}:${deriveStatusFromCounts(raw)}`);
            break;
          }
        }

        // 2. Live running count (ACTIVE epoch only). The per-epoch edge rows never
        //    persist a running status (WorkflowEpochService.recordEdgeCounts writes
        //    completed/skipped only), so read the in-flight count from the live
        //    batch edges - the same source the all-epochs view renders - and merge
        //    it onto the SAME edge. Folding it into this one match pass keeps the
        //    running count consistent with the terminal match (no second, looser
        //    scan that could attach a sibling branch's count). A purely-skipped
        //    edge (no items flowed) is never re-flagged running.
        const onlySkipped = !!counts
          && (counts.SKIPPED ?? 0) > 0
          && (counts.COMPLETED ?? 0) === 0
          && (counts.FAILED ?? 0) === 0;
        if (epochState.isActive && batchEdgesRef.current?.length && !onlySkipped) {
          for (const be of batchEdgesRef.current) {
            const rc = Number(be?.running ?? 0);
            if (!Number.isFinite(rc) || rc <= 0) continue;
            const beId = typeof be?.id === 'string' ? be.id : undefined;
            const arrowIdx = beId ? beId.indexOf('->') : -1;
            const beFrom = be?.from ?? (beId && arrowIdx >= 0 ? beId.substring(0, arrowIdx) : undefined);
            const beTo = be?.to ?? (beId && arrowIdx >= 0 ? beId.substring(arrowIdx + 2) : undefined);
            if (!beFrom || !beTo) continue;
            if (edgeMatchesBatchEdge(edge, { from: beFrom, to: beTo, id: beId ?? `${beFrom}->${beTo}` }, sourceNode, targetNode, updatedNodes)) {
              counts = { ...(counts || {}), RUNNING: rc };
              break;
            }
          }
        }

        if (!counts) {
          resetEdges.push(`${edgeDesc} ← NO MATCH (tried: ${backendEdgeKeys.join(', ')})`);
          return { ...edge, data: { ...edge.data, status: undefined, statusCounts: undefined } };
        }
        return {
          ...edge,
          data: { ...edge.data, status: deriveStatusFromCounts(counts), statusCounts: counts },
        };
      });

      streamDebug.log('EpochStateViewing', 'EDGES AFTER apply:', {
        matched: matchedEdges,
        reset: resetEdges,
        unchanged: unchangedEdges,
      });

      // Log final edge state
      const edgesWithStatusAfter = updatedEdges
        .filter(e => e.data?.status)
        .map(e => {
          const src = nodeMap.get(e.source)?.data.label || e.source;
          const tgt = nodeMap.get(e.target)?.data.label || e.target;
          return `${src}→${tgt}:${e.data?.status}`;
        });
      streamDebug.log('EpochStateViewing', 'EDGES FINAL state:', {
        withStatus: edgesWithStatusAfter.length,
        edges: edgesWithStatusAfter,
      });

      // Apply to canvas
      setNodes(updatedNodes as Node<BuilderNodeData>[]);
      nodesRef.current = updatedNodes as Node<BuilderNodeData>[];
      setEdges(updatedEdges);
      edgesRef.current = updatedEdges;
    }

    fetchAndApply();

    return () => {
      cancelled = true;
    };
  // snapshotSeq: re-fetch epoch data when backend state changes (e.g., signal resolved).
  // nodesReady: re-fire once the canvas nodes are committed (pinned/async-loaded
  //   graph may flip workflowLoaded true before its nodes exist → first paint
  //   bailed on the empty nodesRef and never retriggered).
  // In-flight guard prevents redundant concurrent fetches without debounce.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewingEpoch, runId, workflowLoaded, nodesReady, snapshotSeq]);
}
