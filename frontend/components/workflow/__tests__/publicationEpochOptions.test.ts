import { describe, expect, it } from 'vitest';
import {
  getPublicationEpochOptions,
  resolveDefaultPublicationEpoch,
} from '../publicationEpochOptions';

describe('publicationEpochOptions', () => {
  it('returns unique existing epochs sorted newest first', () => {
    const options = getPublicationEpochOptions({
      items: [
        { epoch: 1 },
        { epoch: 3 },
        { epoch: 1 },
        { epoch: 0 },
        { epoch: 2 },
      ],
    });

    expect(options).toEqual([3, 2, 1, 0]);
  });

  it('ignores missing, negative, and non-integer epoch values', () => {
    const options = getPublicationEpochOptions({
      items: [
        { epoch: null },
        {},
        { epoch: -1 },
        { epoch: 1.5 },
        { epoch: 4 },
      ],
    });

    expect(options).toEqual([4]);
  });

  describe('resolveDefaultPublicationEpoch', () => {
    it('leaves the current selection untouched until real render data arrives', () => {
      expect(resolveDefaultPublicationEpoch(null, [2, 1, 0], false)).toBeNull();
      expect(resolveDefaultPublicationEpoch(5, [2, 1, 0], false)).toBe(5);
    });

    it('defaults to the latest epoch when nothing valid is selected', () => {
      expect(resolveDefaultPublicationEpoch(null, [3, 2, 1], true)).toBe(3);
    });

    it('replaces a stale pin (epoch no longer in the run) with the latest epoch', () => {
      expect(resolveDefaultPublicationEpoch(9, [3, 2, 1], true)).toBe(3);
    });

    it('keeps a still-valid pin instead of forcing the latest', () => {
      expect(resolveDefaultPublicationEpoch(2, [3, 2, 1], true)).toBe(2);
    });

    it('returns null when the run captured no epoch', () => {
      expect(resolveDefaultPublicationEpoch(2, [], true)).toBeNull();
    });
  });
});
