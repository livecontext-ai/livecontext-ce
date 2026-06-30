// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import React from 'react';

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) =>
    vars ? `${key}:${Object.values(vars).join(',')}` : key,
}));
vi.mock('@/components/LoadingSpinner', () => ({
  default: () => <span data-testid="spinner" />,
}));

import {
  ApprovalReviewBar,
  sortPendingSignals,
  pickCurrentSignal,
} from '../ApprovalReviewBar';
import {
  requestApprovalReview,
  clearApprovalReview,
  getApprovalReviewTarget,
} from '../../../services/approvalReviewStore';
import { StepByStepProvider } from '../../../contexts/StepByStepContext';
import type { PendingSignal } from '@/lib/websocket/ws-types';

function signal(id: number, epoch: number | undefined, itemId: string | undefined): PendingSignal {
  return { id, nodeId: 'core:my_approval', signalType: 'USER_APPROVAL', status: 'PENDING', epoch, itemId };
}

describe('ApprovalReviewBar', () => {
  beforeEach(() => {
    clearApprovalReview();
  });

  describe('sortPendingSignals / pickCurrentSignal', () => {
    it('sorts on the (epoch, itemIndex) review axis', () => {
      const sorted = sortPendingSignals([
        signal(3, 2, '0'),
        signal(2, 1, '1'),
        signal(1, 1, '0'),
      ]);
      expect(sorted.map((s) => s.id)).toEqual([1, 2, 3]);
    });

    it('picks the signal matching the review target, else the first pending', () => {
      const sorted = sortPendingSignals([signal(1, 1, '0'), signal(2, 1, '1')]);
      expect(pickCurrentSignal(sorted, { epoch: 1, itemIndex: 1 })?.id).toBe(2);
      expect(pickCurrentSignal(sorted, { epoch: 1, itemIndex: 9 })?.id).toBe(1); // miss → first
      expect(pickCurrentSignal(sorted, null)?.id).toBe(1);
      expect(pickCurrentSignal([], null)).toBeNull();
    });

    it('wildcards the epoch axis when the signal carries no epoch', () => {
      const sorted = sortPendingSignals([signal(1, undefined, '0'), signal(2, undefined, '1')]);
      expect(pickCurrentSignal(sorted, { epoch: 5, itemIndex: 1 })?.id).toBe(2);
    });

    it('sorts non-numeric itemIds first instead of producing an unstable NaN order', () => {
      const sorted = sortPendingSignals([signal(1, 1, '2'), signal(2, 1, 'abc'), signal(3, 1, '1')]);
      expect(sorted.map((s) => s.id)).toEqual([2, 3, 1]);
    });
  });

  it('shows the resolved approval context in the banner when present', () => {
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[{ ...signal(1, 1, '0'), approvalContext: 'Approve refund of 120 EUR?' }]}
        resolveApproval={vi.fn().mockResolvedValue(undefined)}
      />,
    );
    expect(screen.getByTestId('approval-review-context').textContent).toBe('Approve refund of 120 EUR?');
  });

  it('renders no approval-context element when the signal carries none', () => {
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0')]}
        resolveApproval={vi.fn().mockResolvedValue(undefined)}
      />,
    );
    expect(screen.queryByTestId('approval-review-context')).toBeNull();
  });

  it("resolves the targeted item with the signal's OWN epoch and itemId", async () => {
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    requestApprovalReview('rf-node-1', 2, 1);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 2, '1')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    expect(resolveApproval).toHaveBeenCalledWith('APPROVED', '1', 2);
  });

  it('auto-advances the review target to the next pending item after resolving', async () => {
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    requestApprovalReview('rf-node-1', 1, 0);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1'), signal(3, 1, '2')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-reject')); });
    expect(resolveApproval).toHaveBeenCalledWith('REJECTED', '0', 1);
    await waitFor(() => {
      const target = getApprovalReviewTarget();
      expect(target).toMatchObject({ rfNodeId: 'rf-node-1', epoch: 1, itemIndex: 1 });
    });
  });

  it('wraps the auto-advance past the end of the pending list', async () => {
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    requestApprovalReview('rf-node-1', 1, 2); // reviewing the LAST item
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1'), signal(3, 1, '2')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    await waitFor(() => {
      expect(getApprovalReviewTarget()).toMatchObject({ epoch: 1, itemIndex: 0 });
    });
  });

  it('clears the review target after resolving the last pending item', async () => {
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    requestApprovalReview('rf-node-1', 1, 0);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    await waitFor(() => expect(getApprovalReviewTarget()).toBeNull());
  });

  it('does not auto-advance when the resolve fails - the signal is still pending', async () => {
    const resolveApproval = vi.fn().mockRejectedValue(new Error('boom'));
    requestApprovalReview('rf-node-1', 1, 0);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    // Target untouched: still on item 0, and the button re-enabled for retry.
    expect(getApprovalReviewTarget()).toMatchObject({ epoch: 1, itemIndex: 0 });
    expect((screen.getByTestId('approval-review-approve') as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not re-target (forced layout) when used without an active review session', async () => {
    // No row click happened: the bar acts as a plain approve button on the
    // first pending signal. Advancing must not publish a target - that would
    // force re-selection and re-collapse columns the user arranged manually.
    const resolveApproval = vi.fn().mockResolvedValue(undefined);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    expect(resolveApproval).toHaveBeenCalledWith('APPROVED', '0', 1);
    expect(getApprovalReviewTarget()).toBeNull();
  });

  it('falls back to the first pending signal when another node owns the target', () => {
    requestApprovalReview('OTHER-node', 1, 1);
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1')]}
        resolveApproval={vi.fn()}
      />,
    );
    // Item 1 (itemId '0' → 1-based label) is shown, not the other node's item 2.
    expect(screen.getByTestId('approval-review-bar').textContent).toContain('itemBadge:1');
  });

  it('renders nothing when no signals are pending', () => {
    render(
      <ApprovalReviewBar rfNodeId="rf-node-1" pendingSignals={[]} resolveApproval={vi.fn()} />,
    );
    expect(screen.queryByTestId('approval-review-bar')).toBeNull();
  });

  it('disables both buttons while a resolution is in flight', async () => {
    let release: () => void = () => {};
    const resolveApproval = vi.fn().mockImplementation(
      () => new Promise<void>((resolve) => { release = resolve; }),
    );
    render(
      <ApprovalReviewBar
        rfNodeId="rf-node-1"
        pendingSignals={[signal(1, 1, '0'), signal(2, 1, '1')]}
        resolveApproval={resolveApproval}
      />,
    );

    await act(async () => { fireEvent.click(screen.getByTestId('approval-review-approve')); });
    expect((screen.getByTestId('approval-review-approve') as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByTestId('approval-review-reject') as HTMLButtonElement).disabled).toBe(true);
    release();
    await waitFor(() =>
      expect((screen.getByTestId('approval-review-approve') as HTMLButtonElement).disabled).toBe(false),
    );
  });

  describe('run-wide queue navigation', () => {
    // sig on a specific backend node id (the queue maps it back to an RF node).
    function nodeSig(id: number, nodeId: string, epoch: number, itemId: string): PendingSignal {
      return { id, nodeId, signalType: 'USER_APPROVAL', status: 'PENDING', epoch, itemId };
    }

    const NODES_AB = [
      { id: 'rf-a', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'rf-a', label: 'A' } },
      { id: 'rf-b', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'rf-b', label: 'B' } },
    ] as any;
    const MAP_AB = new Map([
      ['rf-a', 'core:a'],
      ['rf-b', 'core:b'],
    ]);

    function renderWithProvider(
      ui: React.ReactElement,
      allPending: PendingSignal[],
      nodeIdToStepId: Map<string, string>,
    ) {
      return render(
        <StepByStepProvider
          isEnabled
          isPaused={false}
          readySteps={new Set()}
          completedSteps={new Set()}
          failedSteps={new Set()}
          onExecuteStep={vi.fn()}
          pendingSignals={allPending}
          nodeIdToStepId={nodeIdToStepId}
        >
          {ui}
        </StepByStepProvider>,
      );
    }

    it('shows the run-wide position and steps to the next approval on another node', async () => {
      const allPending = [nodeSig(1, 'core:a', 1, '0'), nodeSig(2, 'core:b', 1, '0')];
      renderWithProvider(
        <ApprovalReviewBar
          rfNodeId="rf-a"
          pendingSignals={[nodeSig(1, 'core:a', 1, '0')]}
          resolveApproval={vi.fn()}
          allNodes={NODES_AB}
        />,
        allPending,
        MAP_AB,
      );

      // Position reflects the whole run (1 of 2), not just node A's single item.
      expect(screen.getByTestId('approval-review-position').textContent).toContain('1');
      expect(screen.getByTestId('approval-review-position').textContent).toContain('2');
      // On the first approval → no previous.
      expect((screen.getByTestId('approval-review-prev') as HTMLButtonElement).disabled).toBe(true);

      await act(async () => {
        fireEvent.click(screen.getByTestId('approval-review-next'));
      });
      // Crossing to node B retargets the store → useApprovalReviewSelection picks it.
      expect(getApprovalReviewTarget()).toMatchObject({ rfNodeId: 'rf-b', epoch: 1, itemIndex: 0 });
    });

    it('auto-advances across nodes after resolving the current approval', async () => {
      const resolveApproval = vi.fn().mockResolvedValue(undefined);
      requestApprovalReview('rf-a', 1, 0); // active review session on node A
      const allPending = [nodeSig(1, 'core:a', 1, '0'), nodeSig(2, 'core:b', 1, '0')];
      renderWithProvider(
        <ApprovalReviewBar
          rfNodeId="rf-a"
          pendingSignals={[nodeSig(1, 'core:a', 1, '0')]}
          resolveApproval={resolveApproval}
          allNodes={NODES_AB}
        />,
        allPending,
        MAP_AB,
      );

      await act(async () => {
        fireEvent.click(screen.getByTestId('approval-review-approve'));
      });
      expect(resolveApproval).toHaveBeenCalledWith('APPROVED', '0', 1);
      await waitFor(() =>
        expect(getApprovalReviewTarget()).toMatchObject({ rfNodeId: 'rf-b', epoch: 1, itemIndex: 0 }),
      );
    });
  });
});
