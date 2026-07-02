import { describe, it, expect } from 'vitest';
import { initialCompaction, buildChangedCompaction, COMPACTION_DEFAULTS } from '../agentCompaction';

/**
 * The agent modal sends a compaction override only when the user changed it from the
 * hydrated initial - otherwise the backend setter (containsKey semantics) would pin an
 * untouched agent to the UI defaults instead of leaving the column NULL (→ inherit).
 *
 * The summariser-model pair (compactionModelProvider/Name) is both-or-neither: it is
 * emitted whole (both non-blank = override, both "" = explicit clear) and a stored
 * partial pair hydrates as unset, so the payload can never trip the backend's 400
 * invalid_compaction_model guard.
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
        .toEqual({ ...COMPACTION_DEFAULTS, compactionEnabled: true, compactionAfterTurns: 9 });
    });

    it('a stored enabled=false hydrates the toggle as off (not as inherit)', () => {
      expect(initialCompaction({ compactionEnabled: false, compactionAfterTurns: null }))
        .toEqual({ ...COMPACTION_DEFAULTS, compactionEnabled: false });
    });

    it('hydrates the summariser-model pair when both columns are set', () => {
      const initial = initialCompaction({
        compactionModelProvider: 'openai',
        compactionModelName: 'gpt-5-mini',
      });
      expect(initial.compactionModelProvider).toBe('openai');
      expect(initial.compactionModelName).toBe('gpt-5-mini');
    });

    it('treats a stored PARTIAL model pair as unset (both hydrate to "")', () => {
      const providerOnly = initialCompaction({ compactionModelProvider: 'openai', compactionModelName: null });
      expect(providerOnly.compactionModelProvider).toBe('');
      expect(providerOnly.compactionModelName).toBe('');

      const nameOnly = initialCompaction({ compactionModelProvider: '  ', compactionModelName: 'gpt-5-mini' });
      expect(nameOnly.compactionModelProvider).toBe('');
      expect(nameOnly.compactionModelName).toBe('');
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
      const initial = initialCompaction(null); // {enabled:false, afterTurns:5, model pair ''}
      expect(buildChangedCompaction({ ...initial, compactionEnabled: true }, initial))
        .toEqual({ compactionEnabled: true });
    });

    it('changing the cadence sends only afterTurns', () => {
      const initial = initialCompaction({ compactionEnabled: true, compactionAfterTurns: 5 });
      expect(buildChangedCompaction({ ...initial, compactionAfterTurns: 12 }, initial))
        .toEqual({ compactionAfterTurns: 12 });
    });

    it('changing both sends both', () => {
      const initial = initialCompaction(null);
      expect(buildChangedCompaction({ ...initial, compactionEnabled: true, compactionAfterTurns: 8 }, initial))
        .toEqual({ compactionEnabled: true, compactionAfterTurns: 8 });
    });

    it('turning a previously-on agent off sends enabled=false (so it persists)', () => {
      const initial = initialCompaction({ compactionEnabled: true, compactionAfterTurns: 6 });
      expect(buildChangedCompaction({ ...initial, compactionEnabled: false }, initial))
        .toEqual({ compactionEnabled: false });
    });

    it('picking a summariser model sends the WHOLE pair (never a partial pair)', () => {
      const initial = initialCompaction(null);
      expect(
        buildChangedCompaction(
          { ...initial, compactionModelProvider: 'anthropic', compactionModelName: 'claude-haiku-4-5' },
          initial,
        ),
      ).toEqual({ compactionModelProvider: 'anthropic', compactionModelName: 'claude-haiku-4-5' });
    });

    it('untouched model pair → the pair is absent from the payload (columns untouched)', () => {
      const initial = initialCompaction({
        compactionModelProvider: 'openai',
        compactionModelName: 'gpt-5-mini',
      });
      const out = buildChangedCompaction({ ...initial, compactionAfterTurns: 7 }, initial);
      expect(out).toEqual({ compactionAfterTurns: 7 });
      expect('compactionModelProvider' in out).toBe(false);
      expect('compactionModelName' in out).toBe(false);
    });

    it('clearing a stored model override sends both halves as "" (explicit clear)', () => {
      const initial = initialCompaction({
        compactionModelProvider: 'openai',
        compactionModelName: 'gpt-5-mini',
      });
      expect(
        buildChangedCompaction(
          { ...initial, compactionModelProvider: '', compactionModelName: '' },
          initial,
        ),
      ).toEqual({ compactionModelProvider: '', compactionModelName: '' });
    });

    it('a half-set pair is normalised to the explicit clear (never 400s on the backend)', () => {
      const initial = initialCompaction(null);
      expect(
        buildChangedCompaction(
          { ...initial, compactionModelProvider: 'openai', compactionModelName: '' },
          initial,
        ),
      ).toEqual({ compactionModelProvider: '', compactionModelName: '' });
    });
  });
});
