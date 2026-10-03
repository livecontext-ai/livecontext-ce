/**
 * The free tier has a ranking of its own.
 *
 * <p>An admin orders the models opened to the free tier on a tab of their own, and the
 * catalogue carries that order per model as `freeTierRank`. A Free account must be
 * ordered by it everywhere at once: the chat composer, the model picker and the model
 * the account opens on all call `freeTierFirst`, which is what this file pins.
 */
import { describe, expect, it } from 'vitest';
import { bestFreeTierModel, freeTierFirst, freeTierProvidersFirst } from '@/lib/models/freeTierModel';
import type { AIModel } from '@/hooks/useModels';

const model = (id: string, over: Partial<AIModel> = {}): AIModel =>
  ({ id, name: id, provider: 'anthropic', ...over }) as AIModel;

const ids = (models: AIModel[]) => models.map((m) => m.id);

describe('freeTierFirst', () => {
  it('orders the covered models by the free tier ranking, not by the global one', () => {
    // Globally sonnet leads (1) and flash trails (56); the free tier says the opposite.
    const sonnet = model('sonnet', { displayOrder: 1, freeTierEnabled: true, freeTierRank: 2 });
    const opus = model('opus', { displayOrder: 2 });
    const flash = model('flash', { displayOrder: 56, freeTierEnabled: true, freeTierRank: 1 });

    expect(ids(freeTierFirst([sonnet, opus, flash]))).toEqual(['flash', 'sonnet', 'opus']);
  });

  it('leaves the uncovered models exactly as they arrived, after the covered ones', () => {
    const a = model('a', { displayOrder: 5 });
    const free = model('free', { displayOrder: 9, freeTierEnabled: true, freeTierRank: 1 });
    const b = model('b', { displayOrder: 3 });

    // a before b although b ranks better: this half is a partition, never a re-sort.
    expect(ids(freeTierFirst([a, free, b]))).toEqual(['free', 'a', 'b']);
  });

  it('falls back to the global order for a covered model the admin never ranked', () => {
    // Nothing ranked on the free tier yet: the behaviour before the ranking existed.
    const haiku = model('haiku', { displayOrder: 4, freeTierEnabled: true });
    const mini = model('mini', { displayOrder: 2, freeTierEnabled: true });

    expect(ids(freeTierFirst([haiku, mini]))).toEqual(['mini', 'haiku']);
  });

  it('places a covered model the admin never ranked AFTER the ranked ones', () => {
    // A free-tier rank and a global rank are two scales. Compared with each other, a model
    // just opened at global #1 would tie with the free tier's #1 and take the opening away
    // from the model the admin put first. The admin tab draws its list by this same rule.
    const first = model('first', { displayOrder: 40, freeTierEnabled: true, freeTierRank: 1 });
    const second = model('second', { displayOrder: 41, freeTierEnabled: true, freeTierRank: 2 });
    const justOpened = model('just-opened', { displayOrder: 1, freeTierEnabled: true });

    const ordered = freeTierFirst([justOpened, first, second]);

    expect(ids(ordered)).toEqual(['first', 'second', 'just-opened']);
    expect(bestFreeTierModel([justOpened, first, second])?.id).toBe('first');
  });

  it('orders the never-ranked covered models between themselves by the global order', () => {
    const ranked = model('ranked', { displayOrder: 90, freeTierEnabled: true, freeTierRank: 1 });
    const late = model('late', { displayOrder: 7, freeTierEnabled: true });
    const early = model('early', { displayOrder: 3, freeTierEnabled: true });

    expect(ids(freeTierFirst([late, ranked, early]))).toEqual(['ranked', 'early', 'late']);
  });

  it('keeps the arrival order between two covered models of equal rank', () => {
    const a = model('a', { freeTierEnabled: true, freeTierRank: 1 });
    const b = model('b', { freeTierEnabled: true, freeTierRank: 1 });

    expect(ids(freeTierFirst([a, b]))).toEqual(['a', 'b']);
    expect(ids(freeTierFirst([b, a]))).toEqual(['b', 'a']);
  });

  it('ignores a rank left on a model that is no longer open to the free tier', () => {
    const closed = model('closed', { displayOrder: 1, freeTierRank: 1 });
    const open = model('open', { displayOrder: 2, freeTierEnabled: true, freeTierRank: 2 });

    expect(ids(freeTierFirst([closed, open]))).toEqual(['open', 'closed']);
  });

  it('treats a never-ranked covered model with no global order as last of its group', () => {
    const noOrder = model('no-order', { freeTierEnabled: true });
    const ordered = model('ordered', { displayOrder: 500, freeTierEnabled: true });

    expect(ids(freeTierFirst([noOrder, ordered]))).toEqual(['ordered', 'no-order']);
  });

  it('does not mutate the list it is given', () => {
    const list = [
      model('b', { freeTierEnabled: true, freeTierRank: 2 }),
      model('a', { freeTierEnabled: true, freeTierRank: 1 }),
    ];

    freeTierFirst(list);

    expect(ids(list)).toEqual(['b', 'a']);
  });
});

describe('freeTierProvidersFirst', () => {
  const provider = (name: string, models: AIModel[]) => ({ name, models });
  const names = (providers: { name: string }[]) => providers.map((p) => p.name);

  it('leads with the provider of the free tier #1, whatever the provider order', () => {
    // anthropic comes first in the catalogue, but the free tier ranks deepseek's model #1:
    // the two-level picker must lead with the provider of the model the account opened on.
    const anthropic = provider('anthropic', [
      model('sonnet', { displayOrder: 1, freeTierEnabled: true, freeTierRank: 2 }),
    ]);
    const openai = provider('openai', [model('gpt', { displayOrder: 2 })]);
    const deepseek = provider('deepseek', [
      model('flash', { displayOrder: 56, freeTierEnabled: true, freeTierRank: 1 }),
    ]);

    expect(names(freeTierProvidersFirst([anthropic, openai, deepseek])))
      .toEqual(['deepseek', 'anthropic', 'openai']);
  });

  it('keeps the arrival order of the providers that offer nothing covered', () => {
    const a = provider('a', [model('a1', { displayOrder: 9 })]);
    const free = provider('free', [model('f1', { freeTierEnabled: true, freeTierRank: 1 })]);
    const b = provider('b', [model('b1', { displayOrder: 2 })]);

    expect(names(freeTierProvidersFirst([a, free, b]))).toEqual(['free', 'a', 'b']);
  });

  it('does not let an UNLISTED covered model make its provider lead', () => {
    const hidden = provider('hidden', [
      model('h1', { freeTierEnabled: true, freeTierRank: 1, unlisted: true }),
    ]);
    const offered = provider('offered', [
      model('o1', { freeTierEnabled: true, freeTierRank: 2 }),
    ]);

    expect(names(freeTierProvidersFirst([hidden, offered]))).toEqual(['offered', 'hidden']);
  });

  it('is the list untouched when no provider offers a covered model', () => {
    const a = provider('a', [model('a1')]);
    const b = provider('b', [model('b1')]);

    expect(names(freeTierProvidersFirst([a, b]))).toEqual(['a', 'b']);
  });
});

describe('bestFreeTierModel', () => {
  it('is the free tier rank #1, even when another covered model leads globally', () => {
    const sonnet = model('sonnet', { displayOrder: 1, freeTierEnabled: true, freeTierRank: 2 });
    const flash = model('flash', { displayOrder: 56, freeTierEnabled: true, freeTierRank: 1 });

    expect(bestFreeTierModel([sonnet, flash])?.id).toBe('flash');
  });

  it('is undefined when no model is open to the free tier', () => {
    expect(bestFreeTierModel([model('opus', { freeTierRank: 1 })])).toBeUndefined();
  });
});
