import { describe, it, expect } from 'vitest';
import { buildApprovalQueue } from '../approvalQueue';
import type { PendingSignal } from '@/lib/websocket/ws-types';

function sig(
  id: number,
  nodeId: string,
  epoch: number | undefined,
  itemId: string | undefined,
): PendingSignal {
  return { id, nodeId, signalType: 'USER_APPROVAL', status: 'PENDING', epoch, itemId };
}

// Maps an RF node id to its backend step id, mirroring StepByStepContext.resolveNodeId.
const resolve = (table: Record<string, string>) => (rfId: string) => table[rfId] ?? rfId;

const NODES = [{ id: 'rf-a', data: { label: 'A' } }, { id: 'rf-b', data: { label: 'B' } }];
const TABLE = { 'rf-a': 'core:a', 'rf-b': 'core:b' };

describe('buildApprovalQueue', () => {
  it('maps each signal back to its RF node and orders by node, epoch, item', () => {
    const queue = buildApprovalQueue(
      [
        sig(5, 'core:b', 0, '0'), // node B
        sig(3, 'core:a', 0, '1'), // node A, item 1
        sig(2, 'core:a', 0, '0'), // node A, item 0
      ],
      NODES,
      resolve(TABLE),
    );
    // Node A (index 0) drained before node B (index 1); within A by item index.
    expect(queue.map((e) => e.signalId)).toEqual([2, 3, 5]);
    expect(queue[0]).toMatchObject({ rfNodeId: 'rf-a', epoch: 0, itemIndex: 0 });
    expect(queue[2]).toMatchObject({ rfNodeId: 'rf-b', epoch: 0, itemIndex: 0 });
  });

  it('orders by epoch before item within the same node', () => {
    const queue = buildApprovalQueue(
      [sig(1, 'core:a', 2, '0'), sig(2, 'core:a', 1, '5')],
      NODES,
      resolve(TABLE),
    );
    expect(queue.map((e) => e.signalId)).toEqual([2, 1]);
  });

  it('drops signals whose owning step has no matching graph node', () => {
    const queue = buildApprovalQueue(
      [sig(1, 'core:a', 0, '0'), sig(9, 'core:ghost', 0, '0')],
      NODES,
      resolve(TABLE),
    );
    expect(queue.map((e) => e.signalId)).toEqual([1]);
  });

  it('records a null itemIndex for missing or non-numeric itemId', () => {
    const queue = buildApprovalQueue(
      [sig(1, 'core:a', 1, undefined), sig(2, 'core:b', 1, 'abc')],
      NODES,
      resolve(TABLE),
    );
    expect(queue.find((e) => e.signalId === 1)?.itemIndex).toBeNull();
    expect(queue.find((e) => e.signalId === 2)?.itemIndex).toBeNull();
  });

  it('returns an empty queue when there are no signals', () => {
    expect(buildApprovalQueue([], NODES, resolve(TABLE))).toEqual([]);
  });
});
