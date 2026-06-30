import { describe, it, expect } from 'vitest';
import { initialTurnLimits, buildChangedTurnLimits, TURN_LIMIT_DEFAULTS } from '../agentTurnLimits';

/**
 * The agent modal sends a turn-limit override only when the user changed it from the
 * hydrated initial - otherwise the backend `applyGuardOverrides` (containsKey) would
 * pin an untouched agent to the UI defaults instead of leaving the column NULL.
 */
describe('agentTurnLimits', () => {
  describe('initialTurnLimits (hydrate-on-edit)', () => {
    it('falls back to UI defaults for create (no agent) or null columns', () => {
      expect(initialTurnLimits(null)).toEqual(TURN_LIMIT_DEFAULTS);
      expect(initialTurnLimits(undefined)).toEqual(TURN_LIMIT_DEFAULTS);
      expect(initialTurnLimits({ maxPerResourcePerTurn: null, loopIdenticalStop: null, loopConsecutiveStop: null }))
        .toEqual(TURN_LIMIT_DEFAULTS);
    });

    it('uses the agent\'s stored columns when present', () => {
      expect(initialTurnLimits({ maxPerResourcePerTurn: 7, loopIdenticalStop: 20, loopConsecutiveStop: 60 }))
        .toEqual({ maxPerResourcePerTurn: 7, loopIdenticalStop: 20, loopConsecutiveStop: 60 });
    });
  });

  describe('buildChangedTurnLimits (only the user-changed fields)', () => {
    it('create: untouched defaults → sends nothing (column stays NULL → YAML default)', () => {
      const initial = initialTurnLimits(null);
      expect(buildChangedTurnLimits({ ...initial }, initial)).toEqual({});
    });

    it('edit with NULL columns: untouched → sends nothing (stays NULL)', () => {
      const initial = initialTurnLimits({ maxPerResourcePerTurn: null, loopIdenticalStop: null, loopConsecutiveStop: null });
      expect(buildChangedTurnLimits({ ...initial }, initial)).toEqual({});
    });

    it('edit with a custom value: untouched → sends nothing (prior value preserved)', () => {
      const initial = initialTurnLimits({ maxPerResourcePerTurn: 7, loopIdenticalStop: 20, loopConsecutiveStop: 60 });
      expect(buildChangedTurnLimits({ ...initial }, initial)).toEqual({});
    });

    it('sends only the field(s) the user actually changed', () => {
      const initial = { maxPerResourcePerTurn: 5, loopIdenticalStop: 15, loopConsecutiveStop: 40 };
      expect(buildChangedTurnLimits({ ...initial, loopIdenticalStop: 25 }, initial))
        .toEqual({ loopIdenticalStop: 25 });
      expect(buildChangedTurnLimits({ maxPerResourcePerTurn: 8, loopIdenticalStop: 25, loopConsecutiveStop: 80 }, initial))
        .toEqual({ maxPerResourcePerTurn: 8, loopIdenticalStop: 25, loopConsecutiveStop: 80 });
    });

    it('changing a custom value back to the UI default still sends it (so it persists)', () => {
      const initial = initialTurnLimits({ maxPerResourcePerTurn: 7, loopIdenticalStop: 20, loopConsecutiveStop: 60 });
      // User resets the first field to the UI default 5 - it differs from the stored 7, so it must be sent.
      expect(buildChangedTurnLimits({ maxPerResourcePerTurn: 5, loopIdenticalStop: 20, loopConsecutiveStop: 60 }, initial))
        .toEqual({ maxPerResourcePerTurn: 5 });
    });
  });
});
