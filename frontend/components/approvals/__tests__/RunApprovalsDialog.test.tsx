// @vitest-environment jsdom
/**
 * Run-level approval review modal (board cards + header inbox).
 *
 * Fetches the run's pending USER_APPROVAL signals on open, drives the shared
 * ApprovalContextDialog (real component - only Radix/next-intl are mocked),
 * routes each resolution to executionService.resolveSignal with the signal's
 * nodeId, and closes itself once the last signal resolves.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';

const getRunSignals = vi.fn();
const resolveSignal = vi.fn();

vi.mock('@/lib/api', () => ({
  executionService: {
    getRunSignals: (...args: unknown[]) => getRunSignals(...args),
    resolveSignal: (...args: unknown[]) => resolveSignal(...args),
  },
}));
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    const templates: Record<string, string> = {
      itemBadge: 'Item {number}',
      'approvalBar.queuePosition': '{current}/{total}',
    };
    const tpl = templates[key] ?? key;
    return vars ? tpl.replace(/\{(\w+)\}/g, (_m, k: string) => String(vars[k] ?? '')) : tpl;
  },
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));
// Open-gated Dialog so content renders deterministically (no Radix portal in jsdom).
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div data-testid="dialog">{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { RunApprovalsDialog } from '../RunApprovalsDialog';

const sig = (over: Partial<PendingSignal> & { id: number }): PendingSignal => ({
  nodeId: 'core:approve',
  signalType: 'USER_APPROVAL',
  status: 'PENDING',
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  resolveSignal.mockResolvedValue({ status: 'RESOLVED' });
});

describe('RunApprovalsDialog', () => {
  it('fetches the run signals on open and shows only PENDING USER_APPROVAL ones', async () => {
    getRunSignals.mockResolvedValue([
      sig({ id: 1, itemId: '0', epoch: 1, approvalContext: 'Approve Alice' }),
      sig({ id: 2, itemId: '1', epoch: 1, approvalContext: 'Approve Bob' }),
      sig({ id: 3, signalType: 'INTERFACE_SIGNAL', approvalContext: 'not an approval' }),
      sig({ id: 4, status: 'RESOLVED', approvalContext: 'already done' }),
    ]);
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={vi.fn()} />);
    expect(getRunSignals).toHaveBeenCalledWith('run-1');
    await waitFor(() =>
      expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Alice'),
    );
    // 2 reviewable items, not 4.
    expect(screen.getByTestId('approval-modal-position').textContent).toBe('1/2');
  });

  it('routes Approve to executionService.resolveSignal with the signal nodeId and advances', async () => {
    getRunSignals.mockResolvedValue([
      sig({ id: 1, nodeId: 'core:approve_a', itemId: '0', epoch: 2, approvalContext: 'A' }),
      sig({ id: 2, nodeId: 'core:approve_b', itemId: '1', epoch: 2, approvalContext: 'B' }),
    ]);
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={vi.fn()} />);
    await waitFor(() => screen.getByTestId('approval-modal-approve'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-approve')); });
    expect(resolveSignal).toHaveBeenCalledWith('run-1', 'core:approve_a', 'APPROVED', undefined, 2, '0');
    // The resolved signal left the local queue - the modal moved to B.
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('B');
  });

  it('closes (onOpenChange(false)) after the LAST signal resolves', async () => {
    getRunSignals.mockResolvedValue([
      sig({ id: 1, itemId: '0', epoch: 1, approvalContext: 'Only one' }),
    ]);
    const onOpenChange = vi.fn();
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={onOpenChange} />);
    await waitFor(() => screen.getByTestId('approval-modal-reject'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-reject')); });
    expect(resolveSignal).toHaveBeenCalledWith('run-1', 'core:approve', 'REJECTED', undefined, 1, '0');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('shows the empty state when the run has no pending approvals (stale entry point)', async () => {
    getRunSignals.mockResolvedValue([]);
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={vi.fn()} />);
    await waitFor(() =>
      expect(screen.getByTestId('run-approvals-dialog-state').textContent).toBe('approvalBar.noPending'),
    );
  });

  it('shows the load-error state when the fetch fails', async () => {
    getRunSignals.mockRejectedValue(new Error('boom'));
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={vi.fn()} />);
    await waitFor(() =>
      expect(screen.getByTestId('run-approvals-dialog-state').textContent).toBe('approvalBar.loadError'),
    );
  });

  it('on resolve failure: keeps the dialog open, re-syncs the queue, and does not advance blindly', async () => {
    getRunSignals
      .mockResolvedValueOnce([
        sig({ id: 1, itemId: '0', epoch: 1, approvalContext: 'A' }),
        sig({ id: 2, itemId: '1', epoch: 1, approvalContext: 'B' }),
      ])
      // Re-sync after the failed resolve: item 0 was resolved elsewhere.
      .mockResolvedValueOnce([
        sig({ id: 2, itemId: '1', epoch: 1, approvalContext: 'B' }),
      ]);
    resolveSignal.mockRejectedValue(new Error('409 already resolved'));
    const onOpenChange = vi.fn();
    render(<RunApprovalsDialog runId="run-1" open onOpenChange={onOpenChange} />);
    await waitFor(() => screen.getByTestId('approval-modal-approve'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-approve')); });
    // Still open, queue re-synced to the single remaining item.
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
    await waitFor(() =>
      expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('B'),
    );
    expect(screen.queryByTestId('approval-modal-position')).toBeNull(); // 1 left, no queue nav
  });

  it('does not fetch while closed', () => {
    getRunSignals.mockResolvedValue([]);
    render(<RunApprovalsDialog runId="run-1" open={false} onOpenChange={vi.fn()} />);
    expect(getRunSignals).not.toHaveBeenCalled();
  });
});
