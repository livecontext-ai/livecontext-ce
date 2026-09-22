/**
 * @vitest-environment jsdom
 *
 * V494 - what a Free account meets first, and what it opens on.
 *
 * <p>The Free plan's monthly AI allowance pays for chat and agent turns on the
 * models a cloud admin opened to the free tier, and on no others. Two things
 * follow, and both are pinned here.
 *
 * <p><b>Order.</b> Those models lead, so the reader meets what their allowance
 * covers before what it does not. It is a stable partition, not a re-sort: the
 * admin's global drag-and-drop ranking still decides the order WITHIN each half.
 *
 * <p><b>The opening selection is NOT steered here, on purpose.</b> This component
 * only displays a fallback: it calls {@code onChange} from its two change handlers
 * and nowhere else, so a steered display would show one model while the caller's
 * saved value stayed empty and the run used the catalogue default. A node inspector
 * would then name a model the run does not use, which is worse than naming one the
 * allowance cannot pay for. The surfaces that steer the opening selection do it
 * where it is real, by WRITING the value: {@code usePreferFreeTierModel} for chat,
 * and the side panels' own effective-default effect. That boundary is what the last
 * two cases below pin.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  prefersFreeTier: true,
  providers: [] as unknown[],
  defaultProvider: null as string | null,
  defaultModel: null as string | null,
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next-intl', () => ({ useTranslations: () => (k: string) => k, useLocale: () => 'en' }));
vi.mock('next/link', () => ({
  default: ({ href, children }: { href: string, children: React.ReactNode }) => <a href={href}>{children}</a>,
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div data-testid="select">{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => (
    <span data-testid={`row-${model.id}`}>{model.id}</span>
  ),
  ModelInfoPopover: () => null,
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false,
    blockedForModel: () => false,
    freeTierForModel: () => false,
    get prefersFreeTierModels() { return h.prefersFreeTier; },
    isLoading: false,
  }),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: h.defaultProvider,
      defaultModel: h.defaultModel,
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

const model = (provider: string, id: string, freeTierEnabled = false): AIModel =>
  ({ id, name: id, provider, freeTierEnabled });
/**
 * `defaultModel` is the PROVIDER's own declared default, which is what the
 * picker consults - not the catalogue-wide `defaultModel`, which only answers
 * when a provider offers nothing. Overridable so a test can state which one it
 * is exercising instead of relying on "the first model happens to be it".
 */
const provider = (name: string, models: AIModel[], defaultModel?: string): AIProvider => ({
  name,
  defaultModel: defaultModel ?? models[0]?.id ?? '',
  models,
} as AIProvider);

/**
 * Every model row the picker drew, in order. The model Select renders its
 * CURRENT selection in the trigger before listing the options, so the first
 * entry is what the picker opened on and the rest is the dropdown.
 */
function renderedRows(): string[] {
  return screen.getAllByTestId(/^row-/).map((el) => el.getAttribute('data-testid')!.replace('row-', ''));
}

/** What the picker opened on. */
function selectedId(): string {
  return renderedRows()[0];
}

/** The dropdown, in the order offered. */
function optionOrder(): string[] {
  return renderedRows().slice(1);
}

function renderPicker(value: { provider: string; id: string } = { provider: '', id: '' }) {
  render(<ModelPicker value={value} onChange={vi.fn()} />);
}

beforeEach(() => {
  h.prefersFreeTier = true;
  h.providers = [];
  h.defaultProvider = null;
  h.defaultModel = null;
});
afterEach(cleanup);

describe('ModelPicker - free-tier models lead on a Free plan', () => {
  it('puts the covered models first, keeping the admin order within each half', () => {
    h.providers = [
      provider('anthropic', [
        model('anthropic', 'opus'),
        model('anthropic', 'haiku', true),
        model('anthropic', 'sonnet'),
        model('anthropic', 'haiku-mini', true),
      ]),
    ];

    renderPicker();

    // Free-tier pair first, in their admin order; then the rest, in theirs.
    expect(optionOrder()).toEqual(['haiku', 'haiku-mini', 'opus', 'sonnet']);
  });

  it('leaves the admin order untouched for an account that is not on the free tier', () => {
    h.prefersFreeTier = false;
    h.providers = [
      provider('anthropic', [
        model('anthropic', 'opus'),
        model('anthropic', 'haiku', true),
        model('anthropic', 'sonnet'),
      ]),
    ];

    renderPicker();

    expect(optionOrder()).toEqual(['opus', 'haiku', 'sonnet']);
  });

  it('DISPLAYS the backend default even when the allowance cannot pay for it', () => {
    // The tempting behaviour, deliberately not implemented. The picker cannot make
    // a steer stick: a node inspector's saved model would stay empty while the
    // trigger named something else, and the run would use the catalogue default.
    // Showing the model the run will actually use is the honest answer; the list
    // below still leads with what the allowance covers, and the badge says which.
    h.providers = [
      provider('anthropic', [model('anthropic', 'opus'), model('anthropic', 'haiku', true)]),
    ];
    h.defaultProvider = 'anthropic';
    h.defaultModel = 'opus';

    renderPicker();

    expect(selectedId()).toBe('opus');
  });

  it('does not switch provider either, for the same reason', () => {
    h.providers = [
      provider('openai', [model('openai', 'gpt-5')]),
      provider('anthropic', [model('anthropic', 'haiku', true)]),
    ];
    h.defaultProvider = 'openai';
    h.defaultModel = 'gpt-5';

    renderPicker();

    expect(selectedId()).toBe('gpt-5');
  });

  it('an explicit choice is always shown as-is', () => {
    h.providers = [
      provider('anthropic', [model('anthropic', 'opus'), model('anthropic', 'haiku', true)]),
    ];
    h.defaultProvider = 'anthropic';
    h.defaultModel = 'haiku';

    renderPicker({ provider: 'anthropic', id: 'opus' });

    expect(selectedId()).toBe('opus');
  });
  it('falls back on the CATALOGUE first model, not the one the partition moved up', () => {
    // The branch that exists because this component only DISPLAYS: with no declared
    // default, the trigger must name the model an untouched picker actually resolves
    // to. Reading the fallback off the reordered list would show haiku while the
    // caller stores nothing and the run uses opus.
    h.providers = [provider('anthropic', [model('anthropic', 'opus'), model('anthropic', 'haiku', true)], '')];
    h.defaultProvider = null;
    h.defaultModel = null;

    renderPicker();

    expect(selectedId()).toBe('opus');
  });

  it('falls back on the CATALOGUE first provider for the same reason', () => {
    h.providers = [
      provider('openai', [model('openai', 'gpt-5')], ''),
      provider('anthropic', [model('anthropic', 'haiku', true)], ''),
    ];
    h.defaultProvider = null;
    h.defaultModel = null;

    renderPicker();

    expect(selectedId()).toBe('gpt-5');
  });

  it('still LISTS the covered model first in both of those cases', () => {
    // The two assertions above are about the displayed selection only. The ordering
    // is the half that must keep working, or this whole suite is pinning a no-op.
    h.providers = [provider('anthropic', [model('anthropic', 'opus'), model('anthropic', 'haiku', true)], '')];
    h.defaultProvider = null;
    h.defaultModel = null;

    renderPicker();

    expect(optionOrder()).toEqual(['haiku', 'opus']);
  });
});
