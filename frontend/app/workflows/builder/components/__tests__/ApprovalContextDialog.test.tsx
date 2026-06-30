// @vitest-environment jsdom
/**
 * Interactive approval review MODAL.
 *
 * The modal is opened from a truncated preview and lets the approver read the
 * full context AND act: Approve / Reject. With several pending approvals (Split
 * or loop iteration) it exposes a position, Prev/Next navigation, and
 * auto-advances to the next item after a resolution, closing once none remain.
 *
 * The app Dialog (Radix) is mocked to an open-gated <div> so the test exercises
 * THIS component's queue/resolve/advance logic deterministically in jsdom.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';

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
// Open-gated Dialog so content renders only while open (no Radix portal in jsdom).
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div data-testid="dialog">{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { ApprovalContextDialog } from '../ApprovalContextDialog';

const sig = (over: Partial<PendingSignal> & { id: number }): PendingSignal => ({
  nodeId: 'core:approve',
  signalType: 'USER_APPROVAL',
  status: 'PENDING',
  ...over,
});

function renderDialog(
  signals: PendingSignal[],
  resolve = vi.fn().mockResolvedValue(undefined),
  initialSignalId?: number | string,
) {
  const utils = render(
    <ApprovalContextDialog
      signals={signals}
      resolve={resolve}
      initialSignalId={initialSignalId ?? signals[0]?.id}
      data-testid="trigger"
    >
      preview
    </ApprovalContextDialog>,
  );
  return { ...utils, resolve };
}

beforeEach(() => vi.clearAllMocks());

describe('ApprovalContextDialog (interactive review modal)', () => {
  it('is closed initially and opens on trigger click, showing the active context', () => {
    renderDialog([sig({ id: 1, itemId: '0', approvalContext: 'Approve Alice' })]);
    expect(screen.queryByTestId('approval-context-dialog-text')).toBeNull();
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Alice');
  });

  it('single pending: shows NO queue navigation and resolves on Approve', async () => {
    const { resolve } = renderDialog([sig({ id: 1, itemId: '0', epoch: 2, approvalContext: 'Approve Alice' })]);
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.queryByTestId('approval-modal-position')).toBeNull();
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-approve')); });
    expect(resolve).toHaveBeenCalledWith('APPROVED', '0', 2);
  });

  it('Reject calls resolve with REJECTED + the item id/epoch', async () => {
    const { resolve } = renderDialog([sig({ id: 1, itemId: '0', epoch: 3, approvalContext: 'Approve Alice' })]);
    fireEvent.click(screen.getByTestId('trigger'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-reject')); });
    expect(resolve).toHaveBeenCalledWith('REJECTED', '0', 3);
  });

  it('multiple pending: shows position, Prev disabled at start, and Next steps forward', () => {
    renderDialog([
      sig({ id: 1, itemId: '0', approvalContext: 'Approve Alice' }),
      sig({ id: 2, itemId: '1', approvalContext: 'Approve Bob' }),
      sig({ id: 3, itemId: '2', approvalContext: 'Approve Carol' }),
    ]);
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-modal-item').textContent).toBe('Item 1');
    expect(screen.getByTestId('approval-modal-position').textContent).toBe('1/3');
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Alice');
    expect((screen.getByTestId('approval-modal-prev') as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByTestId('approval-modal-next'));
    expect(screen.getByTestId('approval-modal-item').textContent).toBe('Item 2');
    expect(screen.getByTestId('approval-modal-position').textContent).toBe('2/3');
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Bob');
  });

  it('orders items by (epoch, itemIndex) and defaults to the first after sort', () => {
    // No initialSignalId -> the modal opens on the first item AFTER sorting.
    render(
      <ApprovalContextDialog
        signals={[
          sig({ id: 3, itemId: '2', approvalContext: 'Carol' }),
          sig({ id: 1, itemId: '0', approvalContext: 'Alice' }),
          sig({ id: 2, itemId: '1', approvalContext: 'Bob' }),
        ]}
        resolve={vi.fn()}
        data-testid="trigger"
      >
        preview
      </ApprovalContextDialog>,
    );
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Alice');
  });

  it('Approve AUTO-ADVANCES to the next pending item', async () => {
    const { resolve } = renderDialog([
      sig({ id: 1, itemId: '0', epoch: 1, approvalContext: 'Approve Alice' }),
      sig({ id: 2, itemId: '1', epoch: 1, approvalContext: 'Approve Bob' }),
    ]);
    fireEvent.click(screen.getByTestId('trigger'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-approve')); });
    expect(resolve).toHaveBeenCalledWith('APPROVED', '0', 1);
    // The modal moved on to item 2 (Bob) without closing.
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Bob');
    expect(screen.getByTestId('approval-modal-item').textContent).toBe('Item 2');
    expect(screen.getByTestId('approval-modal-position').textContent).toBe('2/2');
  });

  it('does NOT advance when the resolve fails (stays on the current item)', async () => {
    const resolve = vi.fn().mockRejectedValue(new Error('boom'));
    renderDialog([
      sig({ id: 1, itemId: '0', approvalContext: 'Approve Alice' }),
      sig({ id: 2, itemId: '1', approvalContext: 'Approve Bob' }),
    ], resolve);
    fireEvent.click(screen.getByTestId('trigger'));
    await act(async () => { fireEvent.click(screen.getByTestId('approval-modal-approve')); });
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Approve Alice');
  });

  it('falls back to the split itemContext when no approvalContext is set', () => {
    renderDialog([sig({ id: 1, itemId: '0', itemContext: { current_item: 'Order #42 for Bob' } })]);
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('Order #42 for Bob');
  });

  it('shows the empty fallback when a signal carries no context at all', () => {
    renderDialog([sig({ id: 1, itemId: '0' })]);
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-context-dialog-text').textContent).toBe('approvalContextEmpty');
  });

  it('auto-closes once nothing remains pending', () => {
    const resolve = vi.fn().mockResolvedValue(undefined);
    const { rerender } = render(
      <ApprovalContextDialog signals={[sig({ id: 1, itemId: '0', approvalContext: 'Approve Alice' })]} resolve={resolve} data-testid="trigger">
        preview
      </ApprovalContextDialog>,
    );
    fireEvent.click(screen.getByTestId('trigger'));
    expect(screen.getByTestId('approval-context-dialog-text')).toBeTruthy();
    // The last pending signal was resolved elsewhere -> queue empties -> modal closes.
    rerender(
      <ApprovalContextDialog signals={[]} resolve={resolve} data-testid="trigger">
        preview
      </ApprovalContextDialog>,
    );
    expect(screen.queryByTestId('approval-context-dialog-text')).toBeNull();
  });
});
