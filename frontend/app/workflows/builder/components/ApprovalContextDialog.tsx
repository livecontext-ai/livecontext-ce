'use client';

import * as React from 'react';
import { useTranslations } from 'next-intl';
import { MessageSquareQuote, ChevronLeft, ChevronRight, CheckCircle, XCircle } from 'lucide-react';
import LoadingSpinner from '@/components/LoadingSpinner';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import type { PendingSignal } from '@/lib/websocket/ws-types';

type Resolution = 'APPROVED' | 'REJECTED';

interface ApprovalContextDialogProps {
  /** Live, ordered pending USER_APPROVAL signals to review (split/iteration -> many). */
  signals: PendingSignal[];
  /** Per-node resolver: resolves one pending signal by its itemId + epoch. */
  resolve: (resolution: Resolution, itemId?: string, epoch?: number) => Promise<void>;
  /** Which signal to open on (defaults to the first). */
  initialSignalId?: number | string;
  /** The clickable preview (usually the truncated text + an expand affordance). */
  children: React.ReactNode;
  /** Classes for the trigger button (caller controls the inline look). */
  className?: string;
  'data-testid'?: string;
}

function itemIndexOf(s: PendingSignal): number {
  const n = Number(s.itemId ?? 0);
  return Number.isFinite(n) ? n : 0;
}

/** Stable review order: by epoch then by numeric item index (matches the bar/list). */
function sortSignals(list: PendingSignal[]): PendingSignal[] {
  return [...list].sort((a, b) => {
    const ea = a.epoch ?? 0;
    const eb = b.epoch ?? 0;
    if (ea !== eb) return ea - eb;
    return itemIndexOf(a) - itemIndexOf(b);
  });
}

/** The text shown for a signal: the configured approvalContext, else the first
 *  non-empty string of its split itemContext, else null. */
function contextText(s: PendingSignal | undefined): string | null {
  if (!s) return null;
  if (s.approvalContext && s.approvalContext.trim() !== '') return s.approvalContext;
  const ic = s.itemContext;
  if (ic && typeof ic === 'object' && !Array.isArray(ic)) {
    const v = Object.values(ic as Record<string, unknown>).find(
      (x): x is string => typeof x === 'string' && x.trim() !== '',
    );
    if (v) return v;
  }
  return null;
}

/**
 * Approval review MODAL (app Dialog style) opened from a truncated preview.
 *
 * Beyond reading the full context at a larger size, the modal lets the approver
 * ACT: Approve / Reject the current item. When several approvals are pending
 * (a Split or a loop iteration), it shows a position (Item N - i/total), lets
 * the user step Prev/Next, and AUTO-ADVANCES to the next pending item after a
 * resolution. It closes itself once nothing remains pending.
 *
 * Self-contained: it tracks the active item by signal id and derives everything
 * from the live `signals` prop, so a resolution elsewhere (WS update) simply
 * shrinks the queue and the modal re-points to a still-pending item.
 */
export function ApprovalContextDialog({
  signals,
  resolve,
  initialSignalId,
  children,
  className,
  'data-testid': testId,
}: ApprovalContextDialogProps) {
  const tRun = useTranslations('runMode');
  const [open, setOpen] = React.useState(false);
  const [activeId, setActiveId] = React.useState<number | string | undefined>(initialSignalId);
  const [resolving, setResolving] = React.useState<Resolution | null>(null);

  const sorted = React.useMemo(() => sortSignals(signals), [signals]);

  // On open, point at the requested item (or the first). Intentionally NOT keyed
  // on `signals` so a WS tick mid-review does not yank the user off their item.
  React.useEffect(() => {
    if (open) setActiveId(initialSignalId ?? sorted[0]?.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // Nothing left to review -> close.
  React.useEffect(() => {
    if (open && sorted.length === 0) setOpen(false);
  }, [open, sorted.length]);

  let activeIndex = sorted.findIndex((s) => s.id === activeId);
  if (activeIndex < 0) activeIndex = 0; // the active item was resolved/removed -> snap to first remaining
  const current = sorted[activeIndex];
  const total = sorted.length;
  const hasPrev = activeIndex > 0;
  const hasNext = activeIndex < total - 1;
  const itemNumber =
    current?.itemId != null && Number.isFinite(Number(current.itemId))
      ? Number(current.itemId) + 1
      : null;
  const text = contextText(current);

  const go = (delta: number) => {
    if (resolving) return;
    const next = sorted[activeIndex + delta];
    if (next) setActiveId(next.id);
  };

  const handle = async (resolution: Resolution) => {
    if (!current || resolving) return;
    // The item to land on once this one leaves the queue: the next, else the previous.
    const nextSignal = sorted[activeIndex + 1] ?? sorted[activeIndex - 1] ?? null;
    setResolving(resolution);
    try {
      await resolve(resolution, current.itemId, current.epoch);
      // Advance only on success; the WS update then drops the resolved signal.
      if (nextSignal && nextSignal.id !== current.id) setActiveId(nextSignal.id);
    } catch {
      // Stay on the current item so the user can retry.
    } finally {
      setResolving(null);
    }
  };

  return (
    <>
      <button
        type="button"
        data-testid={testId}
        // Stop propagation so opening the modal never selects/drags the node or
        // the review row beneath the trigger. nodrag/nopan keep ReactFlow calm.
        onClick={(e) => {
          e.stopPropagation();
          setOpen(true);
        }}
        onMouseDown={(e) => e.stopPropagation()}
        title={tRun('approvalBar.viewContext')}
        aria-label={tRun('approvalBar.viewContext')}
        className={className}
      >
        {children}
      </button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <MessageSquareQuote className="h-4 w-4 text-amber-500" />
              {tRun('approvalContextLabel')}
            </DialogTitle>
          </DialogHeader>

          {/* Item / Epoch badges + queue navigation, mirroring the inspector
              review bar style (amber badges; Prev/Next + position only when >1). */}
          {current && (itemNumber != null || current.epoch != null || total > 1) && (
            <div className="flex items-center gap-2 rounded-xl border border-amber-200/70 dark:border-amber-500/30 bg-amber-50/60 dark:bg-amber-500/10 px-2.5 py-1.5">
              {total > 1 && (
                <button
                  type="button"
                  data-testid="approval-modal-prev"
                  onClick={() => go(-1)}
                  disabled={!hasPrev || resolving != null}
                  aria-label={tRun('approvalBar.prev')}
                  title={tRun('approvalBar.prev')}
                  className="w-6 h-6 p-0 rounded-full inline-flex items-center justify-center text-amber-700 dark:text-amber-300 hover:bg-amber-100 dark:hover:bg-amber-500/20 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                </button>
              )}
              <span className="flex items-center gap-1.5 min-w-0">
                {itemNumber != null && (
                  <span
                    data-testid="approval-modal-item"
                    className="text-sm font-medium text-amber-800 dark:text-amber-200 font-mono whitespace-nowrap"
                  >
                    {tRun('itemBadge', { number: itemNumber })}
                  </span>
                )}
                {current.epoch != null && (
                  <span
                    data-testid="approval-modal-epoch"
                    className="text-xs px-1.5 rounded-full bg-amber-100 dark:bg-amber-500/20 text-amber-700 dark:text-amber-300 whitespace-nowrap"
                  >
                    {tRun('epochBadge', { number: current.epoch })}
                  </span>
                )}
              </span>
              {total > 1 && (
                <>
                  <span
                    data-testid="approval-modal-position"
                    className="ml-auto text-sm font-medium text-amber-800 dark:text-amber-200 font-mono whitespace-nowrap"
                  >
                    {tRun('approvalBar.queuePosition', { current: activeIndex + 1, total })}
                  </span>
                  <button
                    type="button"
                    data-testid="approval-modal-next"
                    onClick={() => go(1)}
                    disabled={!hasNext || resolving != null}
                    aria-label={tRun('approvalBar.next')}
                    title={tRun('approvalBar.next')}
                    className="w-6 h-6 p-0 rounded-full inline-flex items-center justify-center text-amber-700 dark:text-amber-300 hover:bg-amber-100 dark:hover:bg-amber-500/20 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                  >
                    <ChevronRight className="h-3.5 w-3.5" />
                  </button>
                </>
              )}
            </div>
          )}

          <div
            data-testid="approval-context-dialog-text"
            className="max-h-[50vh] overflow-y-auto whitespace-pre-wrap break-words text-base leading-relaxed text-slate-700 dark:text-slate-200"
          >
            {text ?? (
              <span className="italic text-slate-400 dark:text-slate-500">
                {tRun('approvalContextEmpty')}
              </span>
            )}
          </div>

          <div className="flex items-center justify-end gap-2 pt-1">
            <button
              type="button"
              data-testid="approval-modal-approve"
              onClick={() => handle('APPROVED')}
              disabled={resolving != null}
              className="flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium bg-emerald-100 text-emerald-700 hover:bg-emerald-200 dark:bg-emerald-500/20 dark:text-emerald-300 dark:hover:bg-emerald-500/30 disabled:opacity-50 disabled:cursor-not-allowed transition-colors shadow-sm"
            >
              {resolving === 'APPROVED' ? <LoadingSpinner size="xs" /> : <CheckCircle className="h-4 w-4" />}
              {tRun('approvalBar.approve')}
            </button>
            <button
              type="button"
              data-testid="approval-modal-reject"
              onClick={() => handle('REJECTED')}
              disabled={resolving != null}
              className="flex items-center gap-1.5 px-4 py-2 rounded-full text-sm font-medium bg-red-100 text-red-700 hover:bg-red-200 dark:bg-red-500/20 dark:text-red-300 dark:hover:bg-red-500/30 disabled:opacity-50 disabled:cursor-not-allowed transition-colors shadow-sm"
            >
              {resolving === 'REJECTED' ? <LoadingSpinner size="xs" /> : <XCircle className="h-4 w-4" />}
              {tRun('approvalBar.reject')}
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

export default ApprovalContextDialog;
