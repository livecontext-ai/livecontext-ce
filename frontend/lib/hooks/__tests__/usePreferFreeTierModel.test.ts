/**
 * Which model a free-tier account opens on (V494).
 *
 * <p>This decides the very first turn a visitor sends. The composer's selection
 * starts empty, and an empty selection is sent against the catalogue default - the
 * admin's global #1, which the Free plan's AI allowance may not cover. Getting this
 * wrong means a fresh signup is refused on its first message, at the exact moment
 * the allowance exists to serve.
 *
 * <p>Pure function, tested directly: the three surfaces that need this decision take
 * two different shapes (the chat page primes an empty selection, the panels steer a
 * default they already resolve), and both go through here.
 */
import { describe, expect, it } from 'vitest';
import { resolveFreeTierPreferredModel } from '@/lib/hooks/usePreferFreeTierModel';
import type { AIModel } from '@/hooks/useModels';

const model = (id: string, freeTierEnabled = false): AIModel =>
  ({ id, name: id, provider: 'anthropic', freeTierEnabled }) as AIModel;

const OPUS = model('opus');
const SONNET = model('sonnet');
const HAIKU = model('haiku', true);
const HAIKU_MINI = model('haiku-mini', true);

describe('resolveFreeTierPreferredModel', () => {
  it('steers an uncovered default to the covered model', () => {
    const chosen = resolveFreeTierPreferredModel([OPUS, HAIKU, SONNET], OPUS, true);

    expect(chosen).toBe(HAIKU);
  });

  it('keeps a default that is already covered', () => {
    // Nothing to fix; substituting another covered model would override the
    // admin's ranking for no reason.
    const chosen = resolveFreeTierPreferredModel([HAIKU, HAIKU_MINI, OPUS], HAIKU_MINI, true);

    expect(chosen).toBe(HAIKU_MINI);
  });

  it('takes the FIRST covered model, so the admin ranking still decides between them', () => {
    const chosen = resolveFreeTierPreferredModel([OPUS, HAIKU, HAIKU_MINI], OPUS, true);

    expect(chosen).toBe(HAIKU);
  });

  it('leaves the default alone when NO model is open to the free tier', () => {
    // Substituting here would swap one unpayable model for another while
    // silently ignoring the catalogue's own choice.
    const chosen = resolveFreeTierPreferredModel([OPUS, SONNET], SONNET, true);

    expect(chosen).toBe(SONNET);
  });

  it('changes nothing for an account that is not on the free tier', () => {
    const chosen = resolveFreeTierPreferredModel([OPUS, HAIKU], OPUS, false);

    expect(chosen).toBe(OPUS);
  });

  it('still answers when the catalogue offered no default at all', () => {
    expect(resolveFreeTierPreferredModel([OPUS, HAIKU], undefined, true)).toBe(HAIKU);
    expect(resolveFreeTierPreferredModel([OPUS, SONNET], undefined, true)).toBeUndefined();
    expect(resolveFreeTierPreferredModel([], undefined, true)).toBeUndefined();
  });

  it('treats a model with no flag as uncovered', () => {
    // An older catalogue payload, or CE. Withholding the steer is the safe
    // direction: it cannot promise an allowance that may not apply.
    const legacy = { id: 'legacy', name: 'legacy', provider: 'openai' } as AIModel;

    expect(resolveFreeTierPreferredModel([legacy], legacy, true)).toBe(legacy);
  });
});
