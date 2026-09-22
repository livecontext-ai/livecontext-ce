'use client';

import * as React from 'react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { useNodeExecutionStatus } from '../contexts/StepByStepContext';
import type { NodeLiveState } from '../components/inspector/outputs/NodeRunStateNotice';
import type { PendingSignal } from '@/lib/websocket/ws-types';

export interface NodeLiveStateInfo {
  /**
   * The node's live execution state, or null when it is not in one - the run is
   * not live, or the node already produced (or never reached) its result.
   */
  liveState: NodeLiveState | null;
  /**
   * Signals the node is parked on. Empty unless liveState is 'awaiting', and
   * even then only USER_APPROVAL signals appear: this hook asks
   * {@code getPendingSignalsForNode} for that kind, because it feeds the
   * per-item approval UI. Other kinds exist in the run state (the interface
   * node's Continue button reads INTERFACE_SIGNAL through the same selector),
   * they are simply not what this column shows. A node parked on a timer or a
   * webhook is still reported as 'awaiting', it just has no per-signal detail
   * to show here.
   */
  pendingSignals: PendingSignal[];
}

/**
 * Why a run column has nothing to show yet.
 *
 * A step row is only persisted once the node returns a result, so an executing
 * or parked node has literally nothing to fetch, and both run columns rendered
 * the same flat "No data" for it - which reads as "it ran and produced nothing".
 * This reports the two states that are unambiguous facts (executing now, parked
 * on a signal) from the same source the canvas paints from, so the inspector and
 * the node border can never disagree.
 *
 * A node that has simply not been reached yet is deliberately NOT reported: on a
 * finished run that node never ran, and "not started yet" would be a lie the
 * frontend cannot currently disprove (the run's terminality is not exposed here).
 */
export function useNodeLiveState(
  node: Node<BuilderNodeData> | null | undefined,
  options: { isRunMode: boolean },
): NodeLiveStateInfo {
  const status = useNodeExecutionStatus(node?.id || '', {
    label: node?.data?.label,
    kind: node?.data?.kind,
    status: node?.data?.status,
  });

  const { isRunMode } = options;
  const { isAwaitingSignal, isRunning } = status;
  const pendingSignals = status.pendingSignals;

  return React.useMemo(() => {
    if (!node || !isRunMode) {
      return { liveState: null, pendingSignals: [] };
    }
    // Awaiting is checked first on purpose: yielding on a signal never rewrites
    // the RUNNING step row, so a parked node is in BOTH sets and the more
    // specific fact has to win (the ordering the canvas already uses).
    if (isAwaitingSignal) {
      return { liveState: 'awaiting' as const, pendingSignals };
    }
    if (isRunning) {
      return { liveState: 'running' as const, pendingSignals: [] };
    }
    return { liveState: null, pendingSignals: [] };
  }, [node, isRunMode, isAwaitingSignal, isRunning, pendingSignals]);
}
