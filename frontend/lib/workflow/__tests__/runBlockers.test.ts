/**
 * What the application surface is allowed to say a run is waiting for.
 *
 * <p>The defect this closes: the application read `runState.pendingSignals`,
 * which is RUN-wide, and filtered it down to the interface signals of the one
 * node it happened to be showing. So an approval parked anywhere else left the
 * screen frozen with nothing to click and no explanation, while the workflow
 * canvas had the buttons the whole time.
 *
 * <p>Two of the cases below exist because the first version of this module got
 * them wrong, and both are the same shape - offering an action for work the
 * reader is not looking at:
 *  - a FINISHED run kept its pending signal rows (nothing cancels them on a
 *    natural completion), so the app painted an amber "waiting for you" and a
 *    live Approve/Reject bar over a run that had already ended;
 *  - a focused epoch showed the approvals of every OTHER epoch.
 *
 * <p>Ordering is behaviour, not cosmetics: the bar asks about ONE blocker at a
 * time, so whichever comes first is the only one a user sees.
 */
import { describe, it, expect } from 'vitest';
import type { PendingSignal } from '@/lib/websocket/ws-types';
import { TERMINAL_STATUSES } from '@/contexts/workflow-run/RunStateStore';
import { blockerNodeLabel, computeRunBlockers } from '../runBlockers';

function approval(id: number, nodeId: string, epoch?: number, itemId?: string, context?: string): PendingSignal {
  return { id, nodeId, signalType: 'USER_APPROVAL', status: 'PENDING', epoch, itemId, approvalContext: context };
}
function interfaceSignal(id: number, nodeId: string): PendingSignal {
  return { id, nodeId, signalType: 'INTERFACE_SIGNAL', status: 'PENDING', epoch: 1, itemId: '0' };
}

/** A live run parked on one approval, which every case below varies from. */
const PARKED = {
  runStatus: 'running',
  pendingSignals: [approval(1, 'agent:review', 2, '0', 'Send it?')],
};

describe('computeRunBlockers', () => {
  it('reports nothing without a run state', () => {
    expect(computeRunBlockers(null, { interfaceIsAwaiting: true })).toEqual([]);
    expect(computeRunBlockers(undefined, { interfaceIsAwaiting: true })).toEqual([]);
  });

  it('surfaces an approval parked on ANOTHER node, which the app used to hide', () => {
    expect(computeRunBlockers(PARKED, { displayedInterfaceNodeId: 'interface:form' })).toEqual([{
      kind: 'approval',
      nodeId: 'agent:review',
      signalId: 1,
      epoch: 2,
      itemId: '0',
      context: 'Send it?',
    }]);
  });

  it('offers NOTHING once the run is over, whatever its signal rows still say', () => {
    // Signal rows outlive a run: nothing cancels them on a natural completion,
    // and the snapshot emits them regardless of run status. So the terminal
    // check has to live here, or the app shows a live Approve/Reject bar over a
    // dead run - which is every resolution of the LAST blocker, in the window
    // between the resolve landing and the state refresh.
    for (const status of TERMINAL_STATUSES) {
      expect(
        computeRunBlockers({ ...PARKED, runStatus: status }, { interfaceIsAwaiting: true }),
        `a ${status} run is waiting for nobody`,
      ).toEqual([]);
    }
  });

  it('still works on a run whose status has not arrived yet', () => {
    // Absent is not terminal: refusing here would blank the bar on first paint.
    expect(computeRunBlockers({ pendingSignals: PARKED.pendingSignals }, {})).toHaveLength(1);
  });

  it('shows only what the FOCUSED epoch is waiting on', () => {
    const run = {
      runStatus: 'running',
      pendingSignals: [approval(1, 'agent:review', 1, '0'), approval(2, 'agent:review', 5, '0')],
    };
    // Reading epoch 1: offering epoch 5's approval there would resolve a fire
    // the reader cannot see - the rule the canvas gave its own Continue button.
    expect(computeRunBlockers(run, { viewingEpoch: 1 }).map(b => (b as { signalId: number }).signalId))
      .toEqual([1]);
    // The all-epochs view names no epoch, so it hides none.
    expect(computeRunBlockers(run, { viewingEpoch: null })).toHaveLength(2);
  });

  it('never hides a signal that carries no epoch - it cannot be attributed', () => {
    const run = { runStatus: 'running', pendingSignals: [approval(1, 'agent:review', undefined, '0')] };
    expect(computeRunBlockers(run, { viewingEpoch: 3 })).toHaveLength(1);
  });

  it('orders approvals on the run review axis: epoch, then item', () => {
    const run = {
      runStatus: 'running',
      pendingSignals: [
        approval(3, 'agent:review', 2, '1'),
        approval(1, 'agent:review', 1, '4'),
        approval(2, 'agent:review', 1, '2'),
      ],
    };
    expect(computeRunBlockers(run, {}).map(b => (b as { signalId: number }).signalId)).toEqual([2, 1, 3]);
  });

  it('adds the displayed interface only when the caller says it is parked', () => {
    // The "is it parked on a blocking __continue" rule is NOT re-derived here:
    // the caller owns it (computeIsAwaitingSignal), so its Continue button and
    // this bar can never disagree about whether continuing does anything.
    const idle = { runStatus: 'running', pendingSignals: [] };
    expect(computeRunBlockers(idle, { displayedInterfaceNodeId: 'interface:form', interfaceIsAwaiting: true }))
      .toEqual([{ kind: 'interface_continue', nodeId: 'interface:form' }]);
    expect(computeRunBlockers(idle, { displayedInterfaceNodeId: 'interface:form', interfaceIsAwaiting: false }))
      .toEqual([]);
  });

  it('hides the continue when the fire would land on another epoch', () => {
    // Load-bearing and previously untested: with ONE signal the comparison
    // never runs, so inverting newest/oldest changed nothing. A node parked in
    // two epochs at once is the ordinary reusable-trigger case.
    const run = {
      runStatus: 'running',
      pendingSignals: [
        { ...interfaceSignal(1, 'interface:form'), epoch: 1 },
        { ...interfaceSignal(2, 'interface:form'), epoch: 3 },
      ],
    };
    const scope = { displayedInterfaceNodeId: 'interface:form', interfaceIsAwaiting: true, displayedItemIndex: 0 };

    // The fire resolves the NEWEST (3), so reading epoch 1 must offer nothing.
    expect(computeRunBlockers(run, { ...scope, viewingEpoch: 1 })).toEqual([]);
    // ...and on epoch 3 it is offered, carrying the epoch it will move.
    expect(computeRunBlockers(run, { ...scope, viewingEpoch: 3 })).toEqual([
      { kind: 'interface_continue', nodeId: 'interface:form', epoch: 3 },
    ]);
  });

  it('keys that epoch on the ITEM, which is all the endpoint filters by', () => {
    // The fire carries an itemId and no epoch, and the server takes max(epoch)
    // FOR THAT ITEM. Taking the max across the node's items is wrong in both
    // directions on a split whose items park in different epochs.
    const run = {
      runStatus: 'running',
      pendingSignals: [
        { ...interfaceSignal(1, 'interface:form'), epoch: 1, itemId: '0' },
        { ...interfaceSignal(2, 'interface:form'), epoch: 3, itemId: '1' },
      ],
    };
    const scope = { displayedInterfaceNodeId: 'interface:form', interfaceIsAwaiting: true };

    // Item 0 is parked in epoch 1 only: reading epoch 1 must still offer it,
    // even though item 1 is parked further ahead.
    expect(computeRunBlockers(run, { ...scope, displayedItemIndex: 0, viewingEpoch: 1 })).toEqual([
      { kind: 'interface_continue', nodeId: 'interface:form', epoch: 1 },
    ]);
    // ...and reading epoch 3 with item 0 on screen must NOT, because the fire
    // would resolve item 0's epoch-1 signal and report success.
    expect(computeRunBlockers(run, { ...scope, displayedItemIndex: 0, viewingEpoch: 3 })).toEqual([]);
    // Item 1 is the mirror image.
    expect(computeRunBlockers(run, { ...scope, displayedItemIndex: 1, viewingEpoch: 3 })).toEqual([
      { kind: 'interface_continue', nodeId: 'interface:form', epoch: 3 },
    ]);
  });

  it('carries no epoch when the signal list has not hydrated, and hides nothing', () => {
    // toEqual ignores undefined properties, so the absence is asserted directly.
    const blockers = computeRunBlockers({ runStatus: 'running', pendingSignals: [] }, {
      displayedInterfaceNodeId: 'interface:form',
      interfaceIsAwaiting: true,
      viewingEpoch: 7,
    });
    expect(blockers).toHaveLength(1);
    expect((blockers[0] as { epoch?: number }).epoch).toBeUndefined();
  });

  it('trims a blank approval context instead of rendering an empty question', () => {
    const run = { runStatus: 'running', pendingSignals: [approval(1, 'agent:review', 1, '0', '   ')] };
    expect((computeRunBlockers(run, {})[0] as { context?: string }).context).toBeUndefined();
  });

  it('falls back to the split item the signal carries, like the canvas dialog', () => {
    const signal = { ...approval(1, 'agent:review', 1, '0'), itemContext: { subject: 'Invoice #4412' } };
    const run = { runStatus: 'running', pendingSignals: [signal] };
    expect((computeRunBlockers(run, {})[0] as { context?: string }).context).toBe('Invoice #4412');
  });

  it('adds nothing for an interface it is not being shown', () => {
    const idle = { runStatus: 'running', pendingSignals: [] };
    expect(computeRunBlockers(idle, { displayedInterfaceNodeId: null, interfaceIsAwaiting: true })).toEqual([]);
    expect(computeRunBlockers(idle, { interfaceIsAwaiting: true })).toEqual([]);
  });

  it('asks about an approval BEFORE a continue - it carries an authored question', () => {
    const blockers = computeRunBlockers(PARKED, {
      displayedInterfaceNodeId: 'interface:form',
      interfaceIsAwaiting: true,
    });
    expect(blockers.map(b => b.kind)).toEqual(['approval', 'interface_continue']);
  });

  it('never turns an interface SIGNAL into a blocker of its own', () => {
    // INTERFACE_SIGNAL rows are registered even for a NON-blocking interface,
    // which completes anyway - so the pending list alone never means "parked".
    // Only the caller's flag does.
    const run = { runStatus: 'running', pendingSignals: [interfaceSignal(9, 'interface:form')] };
    expect(computeRunBlockers(run, { displayedInterfaceNodeId: 'interface:form' })).toEqual([]);
  });

  it('tolerates a run state with no signal list at all', () => {
    expect(computeRunBlockers({ runStatus: 'running' }, {})).toEqual([]);
  });
});

describe('blockerNodeLabel', () => {
  it('turns a backend step id into something a person can read', () => {
    expect(blockerNodeLabel('agent:review_draft')).toBe('review draft');
    expect(blockerNodeLabel('interface:order_form')).toBe('order form');
  });

  it('leaves an id with no prefix alone', () => {
    expect(blockerNodeLabel('review')).toBe('review');
  });

  it('falls back to the raw id rather than returning nothing', () => {
    // A prefix with an empty label would otherwise render a blank sentence.
    expect(blockerNodeLabel('agent:')).toBe('agent:');
  });
});
