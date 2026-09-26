// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook } from '@testing-library/react';

/**
 * The opening model of a Free account, rendered rather than reasoned about.
 *
 * <p>The rule: a Free account OPENS on the free tier's best-ranked model, whatever the
 * browser restored, exactly once per page load; after that the reader's own picks
 * stand. The restored case is the reported bug: the stored selection is browser-wide,
 * so a Free account inherited DeepSeek (the lowest-ranked free model) from the previous
 * account instead of Sonnet 5 (free #1). The "once" is module-level so the chat page
 * and both side panels share it; each spec resets it to model a fresh page load.
 */

type Sel = { provider: string; id: string };

const mocks = vi.hoisted(() => ({
  prefers: { value: true },
  app: null as null | {
    state: { selectedModel: Sel; selectionRestored: boolean };
    setSelectedModel: (m: Sel) => void;
  },
}));

vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ prefersFreeTierModels: mocks.prefers.value }),
}));
const track = vi.hoisted(() => vi.fn());
vi.mock('@/lib/analytics/analytics', () => ({ track: (...a: unknown[]) => track(...a) }));
vi.mock('@/contexts/UnifiedAppContext', () => ({
  useUnifiedAppSafe: () => mocks.app,
}));

import { resetFreeTierOpeningForTests, usePreferFreeTierModel } from '@/lib/hooks/usePreferFreeTierModel';
import type { AIModel } from '@/hooks/useModels';

const EMPTY: Sel = { provider: '', id: '' };

const model = (id: string, freeTierEnabled = false, provider = 'anthropic'): AIModel =>
  ({ id, name: id, provider, freeTierEnabled }) as AIModel;

// Catalogue order = admin ranking, as useModels delivers it.
const OPUS = model('opus');
const SONNET = model('sonnet', true);
const HAIKU = model('haiku', true);
const DEEPSEEK_FLASH = model('deepseek-flash', true, 'deepseek');
const RANKED = [OPUS, SONNET, HAIKU, DEEPSEEK_FLASH];
const RESTORED_DEEPSEEK: Sel = { provider: 'deepseek', id: 'deepseek-flash' };
const FREE_NUMBER_ONE: Sel = { provider: 'anthropic', id: 'sonnet' };

/** Installs an app context holding {@code selectedModel}, and returns its setter spy. */
function withApp(selectedModel: Sel, selectionRestored = true) {
  const setSelectedModel = vi.fn();
  mocks.app = { state: { selectedModel, selectionRestored }, setSelectedModel };
  return setSelectedModel;
}

function renderWith(models: AIModel[]) {
  return renderHook(({ list }: { list: AIModel[] }) => usePreferFreeTierModel(list), {
    initialProps: { list: models },
  });
}

beforeEach(() => {
  resetFreeTierOpeningForTests();
  mocks.prefers.value = true;
  mocks.app = null;
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('usePreferFreeTierModel', () => {
  it('opens an empty selection on the best-ranked free-tier model', () => {
    const set = withApp(EMPTY);

    renderWith(RANKED);

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
    // An empty selection has no previous model: the prop is absent, not an empty string.
    expect(track).toHaveBeenCalledWith('chat_model_auto_switched', {
      model: 'sonnet', previous_model: undefined, reason: 'free_tier',
    });
  });

  it('replaces a restored lower-ranked FREE model with the free #1 (the DeepSeek report)', () => {
    // It is free-tier too, so a rule that only swapped out paid models would keep it.
    const set = withApp(RESTORED_DEEPSEEK);

    renderWith(RANKED);

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
    expect(track).toHaveBeenCalledTimes(1);
    expect(track).toHaveBeenCalledWith('chat_model_auto_switched', {
      model: 'sonnet', previous_model: 'deepseek-flash', reason: 'free_tier',
    });
  });

  it('replaces a restored model the Free plan cannot pay for', () => {
    const set = withApp({ provider: 'anthropic', id: 'opus' });

    renderWith(RANKED);

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
  });

  it('does not write when the selection already is the free #1', () => {
    const set = withApp(FREE_NUMBER_ONE);

    renderWith(RANKED);

    expect(set).not.toHaveBeenCalled();
    // No switch happened, so none is reported.
    expect(track).not.toHaveBeenCalled();
  });

  it('waits for the stored selection to be restored before writing', () => {
    // The context restores in its own mount effect, which runs AFTER this hook's. A
    // write before that would be overwritten by the restored model, opening spent.
    const set = withApp(EMPTY, false);
    const view = renderWith(RANKED);
    expect(set).not.toHaveBeenCalled();

    const setAfter = withApp(RESTORED_DEEPSEEK, true);
    view.rerender({ list: RANKED });

    expect(setAfter).toHaveBeenCalledWith(FREE_NUMBER_ONE);
  });

  it('waits for the catalogue and opens when it arrives', () => {
    const set = withApp(EMPTY);
    const view = renderWith([]);
    expect(set).not.toHaveBeenCalled();

    view.rerender({ list: RANKED });

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
  });

  it('waits for the Free verdict: the balance answering late still opens on the #1', () => {
    mocks.prefers.value = false;
    const set = withApp(RESTORED_DEEPSEEK);
    const view = renderWith(RANKED);
    expect(set).not.toHaveBeenCalled();

    mocks.prefers.value = true;
    view.rerender({ list: RANKED });

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
  });

  it('opens ONCE, so a model the reader picks afterwards is kept', () => {
    const set = withApp(EMPTY);
    const view = renderWith(RANKED);
    expect(set).toHaveBeenCalledTimes(1);

    mocks.app = { state: { selectedModel: RESTORED_DEEPSEEK, selectionRestored: true }, setSelectedModel: set };
    view.rerender({ list: RANKED });

    expect(set).toHaveBeenCalledTimes(1);
    // The reader's own later pick is not an automatic switch.
    expect(track).toHaveBeenCalledTimes(1);
  });

  it('opens once per PAGE LOAD, not per surface: a second mount does not re-steer', () => {
    // The chat page and both side panels mount the hook. A per-instance flag would
    // undo the reader's pick every time they switch surface.
    withApp(EMPTY);
    renderWith(RANKED);

    const second = withApp(RESTORED_DEEPSEEK);
    renderWith(RANKED);

    expect(second).not.toHaveBeenCalled();
  });

  it('outside the app context it does nothing AND leaves the opening for a surface that can write', () => {
    renderWith(RANKED);

    const set = withApp(RESTORED_DEEPSEEK);
    renderWith(RANKED);

    expect(set).toHaveBeenCalledWith(FREE_NUMBER_ONE);
  });

  it('does nothing on an account that does not prefer free-tier models', () => {
    // A paid plan, or CE: a remembered model and the catalogue default must stand.
    mocks.prefers.value = false;
    const set = withApp(RESTORED_DEEPSEEK);

    renderWith(RANKED);

    expect(set).not.toHaveBeenCalled();
    expect(track).not.toHaveBeenCalled();
  });

  it('does nothing when no model is open to the free tier', () => {
    // Substituting here would swap one unpayable model for another.
    const set = withApp({ provider: 'anthropic', id: 'opus' });

    renderWith([OPUS]);

    expect(set).not.toHaveBeenCalled();
  });
});
