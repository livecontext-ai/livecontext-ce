// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * This panel used to carry a Generation tab with five format sub-tabs. They are
 * gone, and these tests exist to keep them gone.
 *
 * <p>The panel answers one question: among models that do the same job, which
 * one runs first, and is it on. A generation model has no answer to give. There
 * is no fallback order (a caller names its model, and a music model does not
 * stand in for a video one), nothing to enable (the model exists because the
 * catalog endpoint exists), and its price is per image or per second, published
 * against a platform credential on another screen entirely.
 *
 * <p>What the tabs actually listed was worse than redundant. The image tab
 * showed model-catalogue rows left behind by the deleted image-generation
 * subsystem, so every model on it was one no code could still execute; the sound,
 * speech and music tabs were permanently empty, because the LLM feed this panel
 * reads has no such models to give.
 *
 * <p>Removing a tab is not only a render change. Four other things had to move
 * with it, and each is pinned below or in a sibling suite: the panel must never
 * REQUEST a retired category, the bridge-hiding rule must still name the tab it
 * applies to rather than catch everything that is not chat, the per-image price
 * cell the image tab fed must be gone (here for the panel, and in
 * `components/ai/__tests__/ModelInfo.noPerImagePrice.test.tsx` for the picker),
 * and the i18n keys must be gone from all six locales at once.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  saveOverride: vi.fn(),
  setCategoryEnabled: vi.fn(),
  bulkUpdateRankings: vi.fn(),
  deleteOverride: vi.fn(),
  resetAll: vi.fn(),
  clearModelsCache: vi.fn(),
  // The panel reads the execution links once so each row can show its routing
  // badge; unrouted catalogs answer with an empty list.
  listExecutionLinks: vi.fn().mockResolvedValue([]),
  saveExecutionLink: vi.fn(),
  deleteExecutionLink: vi.fn(),
}));

// The per-model execution-link badge translates its own labels.
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string) => (ns ? `${ns}.${k}` : k),
}));

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    saveOverride: mocks.saveOverride,
    setCategoryEnabled: mocks.setCategoryEnabled,
    bulkUpdateRankings: mocks.bulkUpdateRankings,
    deleteOverride: mocks.deleteOverride,
    resetAll: mocks.resetAll,
    listExecutionLinks: mocks.listExecutionLinks,
    saveExecutionLink: mocks.saveExecutionLink,
    deleteExecutionLink: mocks.deleteExecutionLink,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

/** Identity translator: the assertions read the raw keys, which is what we care about. */
const t = (k: string) => k;

const RETIRED = [
  'generation',
  'image_generation',
  'video_generation',
  'audio_generation',
  'voice_generation',
  'music_generation',
];

const LOCALES = ['en', 'fr', 'de', 'es', 'pt', 'zh'];

/** One of each provider kind, so the bridge filter has something to bite on. */
const modelFixture = () => ([
  { id: 'gpt-5', provider: 'openai', providerKind: 'cloud', enabled: true, ranking: 1, hasOverride: false, isCustom: false },
  { id: 'sonnet', provider: 'anthropic', providerKind: 'byok', enabled: true, ranking: 2, hasOverride: false, isCustom: false },
  { id: 'claude-code', provider: 'claude-code', providerKind: 'bridge', enabled: true, ranking: 3, hasOverride: false, isCustom: false },
]);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

async function renderPanel(models: unknown[] = []) {
  mocks.getEffectiveModels.mockResolvedValue(models);
  const rendered = render(<ModelManagementPanel t={t} />);
  await screen.findByText('modelConfig.category.chat.label');
  return rendered;
}

/** The tab bar buttons, in order. */
function tabs(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('button[data-category-id]'))
    .map((b) => b.getAttribute('data-category-id') as string);
}

/**
 * Click a tab and wait for ITS re-fetch to land, which is what a reader sees.
 *
 * <p>Waiting on a NEW call rather than on `toHaveBeenCalledWith` matters for
 * `chat`: the initial mount already fetched with `undefined`, so the plain
 * matcher would be satisfied by history and a click that did nothing would pass.
 */
async function openTab(container: HTMLElement, id: string) {
  const expected = id === 'chat' ? undefined : id;
  const button = container.querySelector(`button[data-category-id="${id}"]`)!;
  const lastAsked = () => {
    const calls = mocks.getEffectiveModels.mock.calls;
    return calls[calls.length - 1]?.[0];
  };
  // Re-clicking the open tab is a no-op in the panel (same category, same memo,
  // no fetch), so demanding a new call there would hang. Assert the state that
  // matters instead: this tab is the one showing, and its data is what loaded.
  if (button.getAttribute('aria-pressed') === 'true') {
    expect(lastAsked()).toBe(expected);
    return;
  }
  const before = mocks.getEffectiveModels.mock.calls.length;
  fireEvent.click(button);
  await waitFor(() => {
    expect(mocks.getEffectiveModels.mock.calls.length).toBeGreaterThan(before);
    expect(lastAsked()).toBe(expected);
  });
}

describe('ModelManagementPanel - the generation tabs are retired', () => {
  it('offers the two categories a ranking actually applies to, and nothing else', async () => {
    const { container } = await renderPanel();

    expect(tabs(container)).toEqual(['chat', 'browser_agent']);
  });

  it('marks exactly the open tab as pressed, and moves that mark on a switch', async () => {
    // A continuity guard, like the bridge test below: it passes before and after.
    // The removal did rewrite this expression (`topTabOf(category) === tab`
    // became `category === tab`, since a format sub-tab no longer has to keep its
    // parent lit), but the two agree on the tabs that survive, so no test can
    // separate them. What this pins is that the rewrite did not lose the
    // behaviour, which nothing else asserts at unit level.
    const { container } = await renderPanel(modelFixture());
    const pressed = () => tabs(container).filter((id) =>
      container.querySelector(`button[data-category-id="${id}"]`)!.getAttribute('aria-pressed') === 'true');

    expect(pressed()).toEqual(['chat']);

    await openTab(container, 'browser_agent');

    expect(pressed()).toEqual(['browser_agent']);
  });

  it('never asks the model catalogue for a generation category, on any tab', async () => {
    // The request is the real damage, not the button: `?category=music_generation`
    // returns nothing and `?category=image_generation` returns models nothing can
    // run. Walking every tab that exists is what makes this an assertion rather
    // than a restatement of the initial render.
    const { container } = await renderPanel(modelFixture());
    const every = tabs(container);
    // Away from the default first, so returning to `chat` is a real click that
    // re-fetches. Walking the tabs in order would leave chat's leg a no-op and
    // the loop would cover one tab less than it claims.
    await openTab(container, 'browser_agent');
    for (const tab of every) {
      await openTab(container, tab);
    }

    const asked = mocks.getEffectiveModels.mock.calls.map(([c]) => c);
    // Every tab produced a read of its own, so "no generation category was asked
    // for" is a statement about all of them, not about a panel that never fetched.
    expect(new Set(asked)).toEqual(new Set(every.map((c) => (c === 'chat' ? undefined : c))));
    for (const retired of RETIRED) {
      expect(asked).not.toContain(retired);
    }
  });

  it('still hides bridges on browser_agent and still shows them on chat', async () => {
    // A continuity guard, not a regression witness: it passes before and after,
    // and that is the point. The removal rewrote the filter, which now reads
    // `=== 'browser_agent'` rather than `!== 'chat'`. The two behave identically
    // while there are two categories, so no test can separate them today; what
    // this pins is that the rule still applies to browser_agent and still spares
    // chat, so a rewrite that inverted or dropped it would be caught.
    const { container } = await renderPanel(modelFixture());
    // The per-row toggle carries provider and id, so it identifies a row without
    // depending on how the row happens to lay its name out.
    const row = (provider: string, id: string) =>
      screen.queryByTestId(`model-toggle-${provider}-${id}`);

    await openTab(container, 'chat');
    expect(row('claude-code', 'claude-code')).toBeInTheDocument();

    await openTab(container, 'browser_agent');
    await waitFor(() => expect(row('claude-code', 'claude-code')).not.toBeInTheDocument());
    // The other two survive, so the filter removed bridges and not the list.
    expect(row('openai', 'gpt-5')).toBeInTheDocument();
    expect(row('anthropic', 'sonnet')).toBeInTheDocument();
  });
});

describe('the price column after the per-image cell was removed', () => {
  // The removal deleted a whole rendering branch and collapsed the `isFree`
  // ternary that fed it. Nothing covered this column at all before.
  //
  // `mode: 'image'` is what gives these tests their teeth. The server cannot
  // send such a row any more, which is exactly why the branch went; but feeding
  // one here is the only way to prove the branch is really gone rather than
  // merely unvisited. Pre-change, both tests below fail on this fixture.
  const priced = (input: number, output: number) => ([{
    id: 'gpt-5', provider: 'openai', providerKind: 'cloud', mode: 'image',
    enabled: true, ranking: 1, hasOverride: false, isCustom: false,
    pricing: { input, output },
  }]);

  it('renders token prices and never a per-image price, even for an image row', async () => {
    await renderPanel(priced(3, 15));

    // Both halves of the title, or a cell that rendered nothing at all would
    // satisfy the absences and look like a pass.
    expect(screen.getByTitle('Input price')).toHaveTextContent('$3');
    expect(screen.getByTitle('Output price')).toHaveTextContent('$15');
    expect(screen.queryByText(/\/ image/)).not.toBeInTheDocument();
    expect(screen.queryByText('Free')).not.toBeInTheDocument();
  });

  it('calls a model free only when BOTH token prices are zero', async () => {
    // The half-zero case is what the old ternary decided differently: an image
    // row counted as free on input alone, a token row needs both.
    const { unmount } = await renderPanel(priced(0, 15));
    expect(screen.queryByText('Free')).not.toBeInTheDocument();
    unmount();

    await renderPanel(priced(0, 0));
    expect(screen.queryByText('Free')).toBeInTheDocument();
  });
});

describe('the retired categories left no i18n behind', () => {
  // The panel tests translate with an identity function, so a key that stayed
  // in one locale and vanished from five would be invisible to every assertion
  // above. This reads the message files themselves.
  // Resolved from this file, not from process.cwd(), so the suite does not
  // depend on where the runner was started.
  const MESSAGES = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
    '../../../../../../../messages');
  const categoriesOf = (locale: string) =>
    JSON.parse(readFileSync(path.join(MESSAGES, `${locale}.json`), 'utf8'))
      .aiProviders.modelConfig.category;

  // A Set, not an array: which key comes first in a message file is cosmetic,
  // and this should fail on a missing or a surviving key, not on a reformat.
  const messagesOf = (locale: string) =>
    JSON.parse(readFileSync(path.join(MESSAGES, `${locale}.json`), 'utf8'));

  it.each(LOCALES)('%s declares the two live categories and no retired one', (locale) => {
    expect(new Set(Object.keys(categoriesOf(locale)))).toEqual(new Set(['chat', 'browser_agent']));
  });

  it.each(LOCALES)('%s kept no per-image price string either', (locale) => {
    // The other half of the removal: deleting the per-image renderers in the
    // panel and in ModelInfo orphaned these three. A key with no reader is a
    // translator's time spent on nothing, and an invitation to render it again.
    const modelInfo = messagesOf(locale).modelInfo as Record<string, unknown>;

    for (const retired of ['priceShortPerImage', 'perImage', 'priceImageLabel']) {
      expect(Object.keys(modelInfo)).not.toContain(retired);
    }
  });

  it.each(LOCALES)('%s fills both strings each tab renders, label and hint', (locale) => {
    // Parity alone would be satisfied by an empty string in five locales, and
    // the panel reads BOTH keys per category: the tab caption and the line under
    // the bar. Asserting only the label would let a blanked hint through.
    for (const [key, value] of Object.entries(categoriesOf(locale))) {
      for (const field of ['label', 'hint'] as const) {
        const text = (value as Record<string, unknown>)[field];
        expect(typeof text, `${key}.${field}`).toBe('string');
        expect((text as string).trim().length, `${key}.${field}`).toBeGreaterThan(0);
      }
    }
  });
});
