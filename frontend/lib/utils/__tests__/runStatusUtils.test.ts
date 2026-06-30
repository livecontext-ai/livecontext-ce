import { describe, expect, it } from 'vitest';
import { deriveBadgeCycleResult, getRunDisplayStatus, getRunStatusLabel, getStatusClasses } from '../runStatusUtils';

describe('runStatusUtils', () => {
  describe('getStatusClasses - partial success is amber/orange', () => {
    it('maps PARTIAL_SUCCESS (uppercase run status) to amber, not the yellow default', () => {
      const cls = getStatusClasses('PARTIAL_SUCCESS');
      expect(cls).toContain('amber');
      expect(cls).not.toContain('yellow');
      expect(cls).not.toContain('red');
    });

    it('maps the lowercase enum value partial_success to amber too', () => {
      expect(getStatusClasses('partial_success')).toContain('amber');
    });

    it('keeps COMPLETED green, FAILED red, and the idle default yellow', () => {
      expect(getStatusClasses('COMPLETED')).toContain('emerald');
      expect(getStatusClasses('FAILED')).toContain('red');
      // WAITING_TRIGGER has no explicit case -> idle yellow (unchanged behavior).
      expect(getStatusClasses('WAITING_TRIGGER')).toContain('yellow');
    });

    it('distinguishes partial success from plain failed (amber vs red)', () => {
      expect(getStatusClasses('PARTIAL_SUCCESS')).not.toEqual(getStatusClasses('FAILED'));
    });
  });

  describe('getRunDisplayStatus - surfaces the cycle result on WAITING_TRIGGER', () => {
    it('shows partial_success (uppercased) when a reusable-trigger run rests with that lastCycleResult', () => {
      expect(getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: 'partial_success' })).toBe('PARTIAL_SUCCESS');
    });

    it('falls back to the raw status when no lastCycleResult is present', () => {
      expect(getRunDisplayStatus('WAITING_TRIGGER', {})).toBe('WAITING_TRIGGER');
      expect(getRunDisplayStatus('PAUSED', null)).toBe('PAUSED');
    });
  });

  describe('deriveBadgeCycleResult - outcome for a WAITING_TRIGGER badge', () => {
    it('mix of a failed node and a real (non-trigger) completed node -> partial_success', () => {
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], true))
        .toBe('partial_success');
    });

    it('only the trigger completed (everything else failed) -> failed, NOT partial', () => {
      // The trigger always completes; it must not make an all-failed cycle look partial.
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler'], true)).toBe('failed');
    });

    it('non-trigger completions, no failures -> completed', () => {
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], false))
        .toBe('completed');
    });

    it('lowercase run status is handled', () => {
      expect(deriveBadgeCycleResult('waiting_trigger', ['core:wait'], true)).toBe('partial_success');
    });

    it('not WAITING_TRIGGER (e.g. PAUSED mid-step) -> undefined (keep raw status)', () => {
      expect(deriveBadgeCycleResult('PAUSED', ['core:wait'], true)).toBeUndefined();
    });

    it('empty cycle (nothing ran) -> undefined', () => {
      expect(deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler'], false)).toBeUndefined();
    });
  });

  describe('getRunStatusLabel - localizes the badge status (no hardcoded English)', () => {
    const t = (key: string) => {
      const map: Record<string, string> = {
        'status.completed': 'Terminé',
        'status.running': 'En cours',
        'status.paused': 'En pause',
        'status.waiting_trigger': 'En attente de déclencheur',
      };
      return map[key] ?? `MISSING:${key}`;
    };

    it('translates a known status through the status.<key> namespace', () => {
      expect(getRunStatusLabel('COMPLETED', t)).toBe('Terminé');
      expect(getRunStatusLabel('RUNNING', t)).toBe('En cours');
      expect(getRunStatusLabel('PAUSED', t)).toBe('En pause');
      expect(getRunStatusLabel('WAITING_TRIGGER', t)).toBe('En attente de déclencheur');
    });

    it('regression: a known status is NOT the raw lowercased enum the badge used to print', () => {
      expect(getRunStatusLabel('COMPLETED', t)).not.toBe('completed');
    });

    it('falls back to the lowercased raw status for an unknown value (no missing-key placeholder)', () => {
      expect(getRunStatusLabel('SOME_NEW_STATE', t)).toBe('some_new_state');
      expect(getRunStatusLabel('SOME_NEW_STATE', t)).not.toContain('MISSING');
    });
  });

  describe('end-to-end: a mixed cycle renders amber', () => {
    it('WAITING_TRIGGER + partial_success cycle -> amber badge classes', () => {
      const display = getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: 'partial_success' });
      expect(getStatusClasses(display)).toContain('amber');
    });

    it('derive -> display -> color chain yields amber for a mixed WAITING_TRIGGER cycle', () => {
      const cycle = deriveBadgeCycleResult('WAITING_TRIGGER', ['trigger:scheduler', 'core:wait'], true);
      const display = getRunDisplayStatus('WAITING_TRIGGER', { lastCycleResult: cycle });
      expect(display).toBe('PARTIAL_SUCCESS');
      expect(getStatusClasses(display)).toContain('amber');
    });
  });
});
