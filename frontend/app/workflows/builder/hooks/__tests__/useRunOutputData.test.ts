import { describe, it, expect } from 'vitest';
import { dedupeMaxSpawnByEpochItem } from '../useRunOutputData';
import type { WorkflowStepData } from '../useStepData';

function row(epoch: number, itemIndex: number, spawn: number, id: number): WorkflowStepData {
  return { id, epoch, itemIndex, spawn } as unknown as WorkflowStepData;
}

describe('dedupeMaxSpawnByEpochItem', () => {
  it('keeps only the row with the highest spawn for each (epoch, itemIndex)', () => {
    const input = [
      row(1, 0, 0, 10),
      row(1, 0, 1, 11),
      row(1, 0, 2, 12),
      row(1, 1, 0, 20),
      row(1, 1, 1, 21),
    ];
    const result = dedupeMaxSpawnByEpochItem(input);
    expect(result).toHaveLength(2);
    expect(result[0].id).toBe(12); // (1, 0, max spawn 2)
    expect(result[1].id).toBe(21); // (1, 1, max spawn 1)
  });

  it('sorts result by (epoch ASC, itemIndex ASC) so newest is at items.length - 1', () => {
    const input = [
      row(2, 1, 0, 30),
      row(1, 0, 0, 10),
      row(2, 0, 0, 20),
      row(1, 1, 0, 11),
    ];
    const result = dedupeMaxSpawnByEpochItem(input);
    expect(result.map(r => `${r.epoch}:${r.itemIndex}`)).toEqual([
      '1:0', '1:1', '2:0', '2:1',
    ]);
    // Newest (highest epoch, highest itemIndex) lands at the end so the
    // navigator's default {@code currentIndex = totalItems - 1} surfaces it.
    expect(result[result.length - 1].epoch).toBe(2);
    expect(result[result.length - 1].itemIndex).toBe(1);
  });

  it('preserves a single row unchanged when no duplicates exist', () => {
    const input = [row(1, 0, 0, 7)];
    expect(dedupeMaxSpawnByEpochItem(input)).toEqual(input);
  });

  it('returns an empty array for empty input', () => {
    expect(dedupeMaxSpawnByEpochItem([])).toEqual([]);
  });

  it('treats nullish epoch/itemIndex/spawn as 0 to avoid duplicate keys', () => {
    const input = [
      { id: 1 } as unknown as WorkflowStepData,
      { id: 2, spawn: 1 } as unknown as WorkflowStepData,
    ];
    const result = dedupeMaxSpawnByEpochItem(input);
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(2); // higher spawn wins for the same (0, 0) bucket
  });

  it('does not collapse rows that differ on epoch or itemIndex', () => {
    const input = [
      row(1, 0, 5, 10),
      row(2, 0, 0, 20),
      row(1, 1, 0, 11),
    ];
    const result = dedupeMaxSpawnByEpochItem(input);
    expect(result).toHaveLength(3);
  });

  it('on equal-spawn ties, keeps the last-seen row (>= comparison)', () => {
    // Both rows share (epoch=1, itemIndex=0, spawn=0). The >= guard means the
    // latter overwrites the former - documents the tie-break contract so a
    // future refactor doesn't silently flip to "first wins".
    const input = [
      row(1, 0, 0, 100),
      row(1, 0, 0, 200),
    ];
    const result = dedupeMaxSpawnByEpochItem(input);
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(200);
  });
});
