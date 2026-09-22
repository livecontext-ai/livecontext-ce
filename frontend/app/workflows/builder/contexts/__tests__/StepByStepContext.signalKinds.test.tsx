// @vitest-environment jsdom
/**
 * A node's pending-signal queues are read PER KIND, and mixing them is silent.
 *
 * <p>An interface node and an approval node are both "parked on a signal", and
 * the run state hands the canvas ONE flat list of every active signal. Before
 * the interface Continue button existed the selector hard-coded
 * `USER_APPROVAL`, so the moment a second consumer appeared there were exactly
 * two ways to get it wrong, and neither raises anything:
 *   - reading every kind makes an approval node count interface waits in its
 *     "N awaiting approval" badge and offer to approve something that is not an
 *     approval;
 *   - reading the wrong kind leaves the new control permanently empty, which
 *     looks like "nothing is pending" rather than like a bug.
 *
 * <p>So both queues are asserted on the SAME node with BOTH kinds present.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { PendingSignal } from '@/lib/websocket/ws-types';

let mockMode: { viewingEpoch: number | null; isPreviewOnly: boolean };
vi.mock('@/contexts/WorkflowModeContext', () => ({
  useWorkflowMode: () => mockMode,
}));
vi.mock('../../components/RerunConfirmModal', () => ({
  RerunConfirmModal: () => null,
}));

import { StepByStepProvider, useNodeExecutionStatus } from '../StepByStepContext';

function signal(id: number, signalType: string, itemId: string): PendingSignal {
  return { id, nodeId: 'interface:form', signalType, status: 'PENDING', epoch: 1, itemId };
}

/** Reads both queues of one node and prints their signal ids. */
function Probe({ nodeId }: { nodeId: string }) {
  const status = useNodeExecutionStatus(nodeId, { label: 'form', kind: 'interface' });
  return (
    <>
      <span data-testid="approvals">{status.pendingSignals.map(s => s.id).join(',')}</span>
      <span data-testid="approval-count">{status.pendingSignalCount}</span>
      <span data-testid="interfaces">{status.interfaceSignals.map(s => s.id).join(',')}</span>
      <span data-testid="interactive">{String(status.isInteractive)}</span>
    </>
  );
}

function renderProbe(pendingSignals: PendingSignal[], currentEpoch = 3) {
  return render(
    <StepByStepProvider
      isEnabled={false}
      isPaused={false}
      readySteps={new Set()}
      completedSteps={new Set()}
      failedSteps={new Set()}
      awaitingSignalSteps={new Set(['interface:form'])}
      nodeIdToStepId={new Map([['interface-form', 'interface:form']])}
      onExecuteStep={async () => {}}
      pendingSignals={pendingSignals}
      currentEpoch={currentEpoch}
    >
      <Probe nodeId="interface-form" />
    </StepByStepProvider>,
  );
}

beforeEach(() => {
  mockMode = { viewingEpoch: null, isPreviewOnly: false };
});

describe('useNodeExecutionStatus signal queues', () => {
  it('keeps the interface waits out of the approval queue and its count', () => {
    renderProbe([
      signal(1, 'USER_APPROVAL', '0'),
      signal(2, 'INTERFACE_SIGNAL', '0'),
      signal(3, 'INTERFACE_SIGNAL', '1'),
    ]);
    expect(screen.getByTestId('approvals').textContent).toBe('1');
    expect(screen.getByTestId('approval-count').textContent).toBe('1');
  });

  it('gives the interface Continue button only the INTERFACE_SIGNAL waits', () => {
    renderProbe([
      signal(1, 'USER_APPROVAL', '0'),
      signal(2, 'INTERFACE_SIGNAL', '0'),
      signal(3, 'INTERFACE_SIGNAL', '1'),
    ]);
    expect(screen.getByTestId('interfaces').textContent).toBe('2,3');
  });

  it('ignores signals parked on a DIFFERENT node', () => {
    renderProbe([
      { id: 9, nodeId: 'interface:other', signalType: 'INTERFACE_SIGNAL', status: 'PENDING', epoch: 1, itemId: '0' },
    ]);
    expect(screen.getByTestId('interfaces').textContent).toBe('');
  });

  /**
   * `isInteractive` is computed HERE, from the focused epoch, and the interface
   * node's Continue button is the first consumer that reads it directly rather
   * than through a control that already folds it in. So the three branches are
   * exercised on the producer: mocking the flag at the consumer only proves the
   * prop travels, which is the pass-through, not the decision.
   */
  describe('isInteractive (what an on-node control may be offered on)', () => {
    it('is true in the all-epochs view, which names no epoch to be wrong about', () => {
      mockMode = { viewingEpoch: null, isPreviewOnly: false };
      renderProbe([signal(2, 'INTERFACE_SIGNAL', '0')], 3);
      expect(screen.getByTestId('interactive').textContent).toBe('true');
    });

    it('is true while reading the epoch the run is living in', () => {
      mockMode = { viewingEpoch: 3, isPreviewOnly: false };
      renderProbe([signal(2, 'INTERFACE_SIGNAL', '0')], 3);
      expect(screen.getByTestId('interactive').textContent).toBe('true');
    });

    it('is false on a HISTORICAL epoch, which is a record and not a place to act', () => {
      // The interface fire carries no epoch, so a Continue pressed here would
      // resolve the run's NEWEST signal and report success about an epoch the
      // user is not looking at.
      mockMode = { viewingEpoch: 1, isPreviewOnly: false };
      renderProbe([signal(2, 'INTERFACE_SIGNAL', '0')], 3);
      expect(screen.getByTestId('interactive').textContent).toBe('false');
    });

    it('is true on epoch 0, a real first fire and not a missing value', () => {
      mockMode = { viewingEpoch: 0, isPreviewOnly: false };
      renderProbe([signal(2, 'INTERFACE_SIGNAL', '0')], 0);
      expect(screen.getByTestId('interactive').textContent).toBe('true');
    });
  });

  it('reports empty queues outside a run, where there is no provider at all', () => {
    render(<Probe nodeId="interface-form" />);
    expect(screen.getByTestId('interfaces').textContent).toBe('');
    expect(screen.getByTestId('approvals').textContent).toBe('');
    // No provider = nothing to act on, so no on-node control may be offered.
    expect(screen.getByTestId('interactive').textContent).toBe('false');
  });
});
