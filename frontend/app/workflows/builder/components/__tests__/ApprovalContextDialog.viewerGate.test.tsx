// @vitest-environment jsdom
/**
 * VIEWER gate on the shared approval modal (used by the builder, the run approvals
 * dialog, the board cards and the notification bell). Approving or rejecting drives the
 * run, which the backend refuses to a read-only VIEWER: the modal now shows the context
 * and the translated read-only sentence instead of buttons that could only fail.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';

const gate = vi.hoisted(() => ({ canMutate: true }));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCanMutateInCurrentOrg: () => gate.canMutate,
}));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span /> }));
vi.mock('@/components/ui/dialog', () => ({
  Dialog: ({ open, children }: { open: boolean; children: React.ReactNode }) =>
    open ? <div>{children}</div> : null,
  DialogContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { ApprovalContextDialog } from '../ApprovalContextDialog';

const signal: PendingSignal = {
  id: 1, nodeId: 'core:approve', signalType: 'USER_APPROVAL', status: 'PENDING', approvalContext: 'Pay invoice 42',
} as PendingSignal;

function open(resolve = vi.fn()) {
  render(
    <ApprovalContextDialog signals={[signal]} resolve={resolve} initialSignalId={1} data-testid="trigger">
      preview
    </ApprovalContextDialog>,
  );
  fireEvent.click(screen.getByTestId('trigger'));
  return resolve;
}

beforeEach(() => { gate.canMutate = true; });
afterEach(cleanup);

describe('ApprovalContextDialog - VIEWER gate', () => {
  it('VIEWER: reads the context, sees the read-only sentence, gets no Approve / Reject', () => {
    gate.canMutate = false;
    const resolve = open();

    expect(screen.getByText('Pay invoice 42')).toBeInTheDocument();
    expect(screen.getByTestId('approval-modal-read-only')).toHaveTextContent('viewerReadOnly');
    expect(screen.queryByTestId('approval-modal-approve')).toBeNull();
    expect(screen.queryByTestId('approval-modal-reject')).toBeNull();
    expect(resolve).not.toHaveBeenCalled();
  });

  it('MEMBER: Approve is offered and resolves the signal', () => {
    const resolve = open(vi.fn().mockResolvedValue(undefined));

    fireEvent.click(screen.getByTestId('approval-modal-approve'));

    expect(resolve).toHaveBeenCalledWith('APPROVED', undefined, undefined, 'core:approve');
    expect(screen.queryByTestId('approval-modal-read-only')).toBeNull();
  });
});
