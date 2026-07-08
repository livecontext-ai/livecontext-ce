'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { MessageSquareQuote } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { executionService } from '@/lib/api';
import { ApprovalContextDialog } from '@/app/workflows/builder/components/ApprovalContextDialog';
import type { PendingSignal } from '@/lib/websocket/ws-types';

function onlyPendingApprovals(all: PendingSignal[]): PendingSignal[] {
  return all.filter((s) => s.signalType === 'USER_APPROVAL' && s.status === 'PENDING');
}

interface RunApprovalsDialogProps {
  /** Public run id whose pending USER_APPROVAL signals are reviewed. */
  runId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Run-level approval review modal, usable OUTSIDE the workflow builder (board
 * cards, header inbox): fetches the run's pending USER_APPROVAL signals on
 * open and drives the shared ApprovalContextDialog review UI (context text,
 * Prev/Next queue, Approve/Reject with auto-advance). Resolutions are routed
 * per signal node, so a run with several approval nodes reviews as one queue.
 */
export function RunApprovalsDialog({ runId, open, onOpenChange }: RunApprovalsDialogProps) {
  const tRun = useTranslations('runMode');
  // null = loading; [] = loaded empty (or load error, flagged separately).
  const [signals, setSignals] = React.useState<PendingSignal[] | null>(null);
  const [loadFailed, setLoadFailed] = React.useState(false);

  React.useEffect(() => {
    if (!open) {
      setSignals(null);
      setLoadFailed(false);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const all = await executionService.getRunSignals(runId);
        if (!cancelled) setSignals(onlyPendingApprovals(all));
      } catch {
        if (!cancelled) {
          setLoadFailed(true);
          setSignals([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open, runId]);

  // Applies the queue and owns close-on-empty: the review dialog unmounts in
  // the same commit the queue empties, so its own close-on-empty effect never
  // gets to run.
  const applySignals = React.useCallback(
    (next: PendingSignal[]) => {
      setSignals(next);
      if (next.length === 0) onOpenChange(false);
      return next;
    },
    [onOpenChange],
  );

  const resolve = React.useCallback(
    async (resolution: 'APPROVED' | 'REJECTED', itemId?: string, epoch?: number, nodeId?: string) => {
      if (!nodeId) return;
      try {
        await executionService.resolveSignal(runId, nodeId, resolution, undefined, epoch, itemId);
      } catch (e) {
        // The signal may have been resolved elsewhere since the fetch (no live
        // WS feed here, unlike the builder): re-sync the queue so the user is
        // not stuck retrying a gone item, then rethrow so the review dialog
        // does not auto-advance as if this resolution succeeded.
        try {
          applySignals(onlyPendingApprovals(await executionService.getRunSignals(runId)));
        } catch {
          // Keep the stale queue; the original error still surfaces below.
        }
        throw e;
      }
      // Success: drop the resolved signal locally to advance the queue.
      applySignals(
        (signals ?? []).filter(
          (s) =>
            !(
              s.nodeId === nodeId &&
              (s.itemId ?? null) === (itemId ?? null) &&
              (s.epoch ?? null) === (epoch ?? null)
            ),
        ),
      );
    },
    [runId, signals, applySignals],
  );

  // Loaded with work to do -> the shared review dialog owns the whole UI and
  // closes us (onOpenChange(false)) once the last signal resolves.
  if (open && signals != null && signals.length > 0) {
    return (
      <ApprovalContextDialog
        signals={signals}
        resolve={resolve}
        open={open}
        onOpenChange={onOpenChange}
      />
    );
  }

  // Loading / empty / error shell.
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MessageSquareQuote className="h-4 w-4 text-amber-500" />
            {tRun('approvalContextLabel')}
          </DialogTitle>
        </DialogHeader>
        <div
          data-testid="run-approvals-dialog-state"
          className="flex items-center justify-center py-8 text-sm text-theme-muted"
        >
          {signals == null ? (
            <LoadingSpinner size="sm" />
          ) : loadFailed ? (
            tRun('approvalBar.loadError')
          ) : (
            tRun('approvalBar.noPending')
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default RunApprovalsDialog;
