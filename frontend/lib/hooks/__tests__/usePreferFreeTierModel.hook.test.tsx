// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';

/**
 * The hook half of the free-tier default (V494), rendered rather than reasoned about.
 *
 * <p>`resolveFreeTierPreferredModel` is pure and tested next door; everything that can
 * actually go wrong lives here, in the effect: it must prime an EMPTY selection once,
 * never argue with a choice the reader already made, and cope with a catalogue that
 * arrives a tick after first paint (which is the normal case, since the models are
 * fetched). Getting the "once" wrong is the expensive one: the ref is what stops the
 * hook from re-priming after the reader deliberately clears the box, and deleting it
 * leaves the pure function's own tests entirely green.
 */

const mocks = vi.hoisted(() => ({ prefers: { value: true } }));

vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ prefersFreeTierModels: mocks.prefers.value }),
}));

import { usePreferFreeTierModel } from '@/lib/hooks/usePreferFreeTierModel';
import type { AIModel, SelectedModel } from '@/hooks/useModels';

const EMPTY: SelectedModel = { provider: '', id: '' };

const model = (id: string, freeTierEnabled = false): AIModel =>
  ({ id, name: id, provider: 'anthropic', freeTierEnabled }) as AIModel;

const OPUS = model('opus');
const HAIKU = model('haiku', true);

/** Renders the hook with a selection that the hook's own setter updates, like the page. */
function renderWithSelection(initial: SelectedModel, models: AIModel[]) {
  const setSelectedModel = vi.fn();
  const view = renderHook(
    ({ selectedModel, list }: { selectedModel: SelectedModel; list: AIModel[] }) =>
      usePreferFreeTierModel({ models: list, selectedModel, setSelectedModel }),
    { initialProps: { selectedModel: initial, list: models } },
  );
  return { ...view, setSelectedModel };
}

afterEach(() => {
  cleanup();
  mocks.prefers.value = true;
  vi.clearAllMocks();
});

describe('usePreferFreeTierModel', () => {
  it('primes an empty selection with the covered model', () => {
    const { setSelectedModel } = renderWithSelection(EMPTY, [OPUS, HAIKU]);

    expect(setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'haiku' });
  });

  it('leaves a selection the reader already has alone', () => {
    // Including a stale one: correcting it would silently move a reader off the
    // model they picked, which is worse than one refused turn they can act on.
    const { setSelectedModel } = renderWithSelection({ provider: 'anthropic', id: 'opus' }, [OPUS, HAIKU]);

    expect(setSelectedModel).not.toHaveBeenCalled();
  });

  it('waits for the catalogue and primes when it arrives', () => {
    // First paint has no models: the fetch has not landed. The hook must not
    // give up on that pass, or the steer only ever works on a warm cache.
    const { rerender, setSelectedModel } = renderWithSelection(EMPTY, []);
    expect(setSelectedModel).not.toHaveBeenCalled();

    rerender({ selectedModel: EMPTY, list: [OPUS, HAIKU] });

    expect(setSelectedModel).toHaveBeenCalledWith({ provider: 'anthropic', id: 'haiku' });
  });

  it('primes ONCE, so clearing the box afterwards is respected', () => {
    // The reader clears their choice on purpose. Without the ref the effect sees
    // an empty selection again and puts the model straight back, and the box
    // cannot be emptied at all.
    const { rerender, setSelectedModel } = renderWithSelection(EMPTY, [OPUS, HAIKU]);
    expect(setSelectedModel).toHaveBeenCalledTimes(1);

    rerender({ selectedModel: { provider: 'anthropic', id: 'haiku' }, list: [OPUS, HAIKU] });
    rerender({ selectedModel: EMPTY, list: [OPUS, HAIKU] });

    expect(setSelectedModel).toHaveBeenCalledTimes(1);
  });

  it('does nothing on an account that does not prefer free-tier models', () => {
    // A paid plan, or CE. The catalogue default is the admin's ranking and must
    // stand.
    mocks.prefers.value = false;

    const { setSelectedModel } = renderWithSelection(EMPTY, [OPUS, HAIKU]);

    expect(setSelectedModel).not.toHaveBeenCalled();
  });

  it('does nothing when no model is open to the free tier', () => {
    // Substituting here would swap one unpayable model for another.
    const { setSelectedModel } = renderWithSelection(EMPTY, [OPUS]);

    expect(setSelectedModel).not.toHaveBeenCalled();
  });
});
