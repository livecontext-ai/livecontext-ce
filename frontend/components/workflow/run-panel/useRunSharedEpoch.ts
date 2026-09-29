'use client';

import { useCallback, useEffect } from 'react';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { markEpochPickedByUser, selectAllEpochs, useDefaultEpochSelection } from './useDefaultEpochSelection';

export interface RunSharedEpoch {
  /** True when this view shows the run its panel is bound to: the epoch is then the shared one. */
  synced: boolean;
  /** The shared epoch (null = all epochs). Meaningful only when `synced`. */
  epoch: number | null;
  /** Show one epoch everywhere (canvas, pill, Run tab, Analysis, Logs), recorded as a user choice. */
  pick: (epoch: number) => void;
  /** Back to all epochs everywhere, recorded too, so no surface restores the epoch just left. */
  showAll: () => void;
}

/**
 * The epoch a run view (Analysis, Logs) shares with the canvas and the Run tab.
 *
 * - It is shared only for the run the panel is bound to. A view of another run, or one rendered
 *   outside any mode provider (the context's no-op stub, `workflowId` undefined), is not synced
 *   and keeps its own choice.
 * - A panel opened straight on Logs or Analysis has not been bound yet: the Run tab, which binds
 *   it, is not mounted. The view binds it to its run, as the Run tab does, rather than treating
 *   an unbound provider as synced: an epoch broadcast without a run id is adopted by every canvas
 *   and application tab of every run.
 * - The provider starts with no epoch, so the pick remembered for the run (made on the canvas or
 *   another view) is restored, as the Run tab does.
 */
export function useRunSharedEpoch(runId: string | null | undefined): RunSharedEpoch {
  const { workflowId, runId: boundRunId, setRunId, viewingEpoch, setViewingEpoch } = useWorkflowMode();
  const inProvider = workflowId !== undefined;

  useEffect(() => {
    if (inProvider && runId && !boundRunId) setRunId(runId);
  }, [inProvider, runId, boundRunId, setRunId]);

  const synced = inProvider && !!runId && boundRunId === runId;

  useDefaultEpochSelection({
    runId,
    selectedEpoch: viewingEpoch,
    onSelectEpoch: setViewingEpoch,
    enabled: synced,
  });

  const pick = useCallback((epoch: number) => {
    markEpochPickedByUser(runId, epoch);
    setViewingEpoch(epoch);
  }, [runId, setViewingEpoch]);

  const showAll = useCallback(() => selectAllEpochs(runId, setViewingEpoch), [runId, setViewingEpoch]);

  return { synced, epoch: viewingEpoch, pick, showAll };
}
