import { describe, it, expect } from 'vitest';
import { initialCompaction, buildChangedCompaction, COMPACTION_DEFAULTS } from '../agentCompaction';

/**
 * The agent modal sends a compaction override only when the user changed it from the
 * hydrated initial - otherwise the backend setter (containsKey semantics) would pin an
 * untouched agent to the UI defaults instead of leaving the column NULL (→ inherit).
 */
describe('agentCompaction', () => {
  describe('initialCompaction (hydrate-on-edit)', () => {
    it('falls back to UI defaults for create (no agent) or null columns', () => {
      expect(initialCompaction(null)).toEqual(COMPACTION_DEFAULTS);
      expect(initialCompaction(undefined)).toEqual(COMPACTION_DEFAULTS);
      expect(initialCompaction({ compactionEnabled: null, compactionAfterTurns: null }))
        .toEqual(COMPACTION_DEFAULTS);
    });

    it('uses the agent\'s stored columns when present', () => {
      expect(initialCompaction({ compactionEnabled: true, compactionAfterTurns: 9 }))
        .toEqual({ compactionEnabled: true, compactionAfterTurns: 9 });
    });

    it('a stored enabled=false hydrates the toggle as off (not as inherit)', () => {
      expect(initialCompaction({ compactionEnabled: false, compactionAfterTurns: null }))
        .toEqual({ compactionEnabled: false, compactionAfterTurns: COMPACTION_DEFAULTS.compactionAfterTurns });
    });
  });

  describe('buildChangedCompaction (only the user-changed fields)', () => {
    it('create: untouched defaults → sends nothing (columns stay NULL → inherit)', () => {
      const initial = initialCompaction(null);
      expect(buildChangedCompaction({ ...initial }, initial)).toEqual({});
    });

    it('edit with NULL columns: untouched → sends nothing (stays NULL)', () => {
      const initial = initialCompaction({ compactionEnabled: null, compactionAfterTurns: null });
      expect(buildChangedCompaction({ ...initial }, initial)).toEqual({});
    });

    it('enabling compaction sends only the enable flag (cadence inherits until changed)', () => {
      const initial = initialCompaction(null); // {enabled:false, afterTurns:5}
      expect(buildChangedCompaction({ compactionEnabled: true, compactionAfterTurns: 5 }, initial))
        .toEqual({ compactionEnabled: true });
    });

    it('changing the cadence sends only afterTurns', () => {
      const initial = initialCompaction({ compactionEnabled: true, compactionAfterTurns: 5 });
      expect(buildChangedCompaction({ compactionEnabled: true, compactionAfterTurns: 12 }, initial))
        .toEqual({ compactionAfterTurns: 12 });
    });

    it('changing both sends both', () => {
      const initial = initialCompaction(null);
      expect(buildChangedCompaction({ compactionEnabled: true, compactionAfterTurns: 8 }, initial))
        .toEqual({ compactionEnabled: true, compactionAfterTurns: 8 });
    });

    it('turning a previously-on agent off sends enabled=false (so it persists)', () => {
      const initial = initialCompaction({ compactionEnabled: true, compactionAfterTurns: 6 });
      expect(buildChangedCompaction({ compactionEnabled: false, compactionAfterTurns: 6 }, initial))
        .toEqual({ compactionEnabled: false });
    });
  });
});
