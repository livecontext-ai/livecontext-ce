/**
 * @vitest-environment jsdom
 *
 * ModelPicker and UNLISTED models (V554). An unlisted model is still available, so an agent, a
 * workflow node (agent, classify, guardrail, browser agent) or a chat config can be put on one:
 * the picker lists them in each provider's model list, AFTER the offered models, under their own
 * heading. What it must never do is land on one by itself: every fallback (no value, a provider
 * change, the provider list's first entry) stays on an offered model.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  providers: [] as unknown[],
  unlistedModels: [] as unknown[],
  defaultProvider: 'openai' as string | null,
  defaultModel: 'gpt-5' as string | null,
  prefersFree: false,
  categoryData: null as unknown,
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, blockedForModel: () => false, freeTierForModel: () => false, prefersFreeTierModels: h.prefersFree, isLoading: false }),
}));
// The extra category slice (the classify node's decision models): the raw catalogue, so it
// carries its unlisted rows flagged, unlike useVisibleModels().providers.
vi.mock('@/hooks/useCategoryModels', () => ({
  useCategoryModels: (category: string | null) => ({ data: category ? h.categoryData : null, isLoading: false }),
}));
vi.mock('next/image', () => ({ default: () => null }));
// Each Select exposes its resolved VALUE (what the trigger displays) and renders its items as
// buttons that call its onValueChange, so a choice can be made without Radix's pointer events.
vi.mock('@/components/ui/select', async () => {
  const React = await import('react');
  const Ctx = React.createContext<(v: string) => void>(() => {});
  return {
    Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) =>
      React.createElement(Ctx.Provider, { value: onValueChange },
        React.createElement('div', { 'data-testid': 'select', 'data-value': value }, children)),
    SelectContent: ({ children }: { children: React.ReactNode }) => React.createElement('div', null, children),
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const onValueChange = React.useContext(Ctx);
      return React.createElement('button', { type: 'button', 'data-testid': `option-${value}`, onClick: () => onValueChange(value) }, children);
    },
    SelectTrigger: ({ children }: { children: React.ReactNode }) => React.createElement('div', null, children),
  };
});
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => <span>{model.id}</span>,
  ModelInfoPopover: () => null,
}));
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      unlistedModels: h.unlistedModels,
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

const model = (provider: string, id: string, extra: Partial<AIModel> = {}): AIModel => ({
  id, name: id, provider, ...extra,
});
const hidden = (provider: string, id: string) => model(provider, id, { unlisted: true });

const provider = (name: string, models: AIModel[], displayOrder: number): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  displayOrder,
  models,
});

const noop = () => {};
const selectValues = () => screen.getAllByTestId('select').map((el) => el.getAttribute('data-value'));
/** The ids in the MODEL list, in display order (the provider list's options are provider names). */
const modelOptions = () =>
  Array.from(screen.getAllByTestId('select')[1].querySelectorAll('[data-testid^="option-"]'))
    .map((el) => el.getAttribute('data-testid')!.replace('option-', ''));

afterEach(() => {
  cleanup();
  h.defaultProvider = 'openai';
  h.defaultModel = 'gpt-5';
  h.prefersFree = false;
  h.categoryData = null;
});

const providerOptions = () =>
  Array.from(screen.getAllByTestId('select')[0].querySelectorAll('[data-testid^="option-"]'))
    .map((el) => el.getAttribute('data-testid')!.replace('option-', ''));

describe('ModelPicker - unlisted models (V554)', () => {
  it('lists a provider\'s unlisted models after its offered ones, under the hidden heading', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5'), model('openai', 'gpt-5-mini')], 1)];
    h.unlistedModels = [hidden('openai', 'gpt-4o'), hidden('openai', 'gpt-4')];

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={noop} hiddenModelsLabel="Hidden models" />);

    expect(modelOptions()).toEqual(['gpt-5', 'gpt-5-mini', 'gpt-4o', 'gpt-4']);
    const heading = screen.getByTestId('model-picker-hidden-group');
    expect(heading).toHaveTextContent('Hidden models');
    // The heading sits right before the first hidden model.
    expect(heading.nextElementSibling).toHaveAttribute('data-testid', 'option-gpt-4o');
  });

  it('shows no heading for a provider with nothing hidden', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [hidden('mistral', 'mistral-large-2')];

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={noop} />);

    expect(screen.queryByTestId('model-picker-hidden-group')).toBeNull();
  });

  it('an unlisted model can be chosen, and a stored one is displayed as itself', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [hidden('openai', 'gpt-4o')];
    const onChange = vi.fn();

    const { unmount } = render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={onChange} />);
    fireEvent.click(screen.getByTestId('option-gpt-4o'));
    expect(onChange).toHaveBeenCalledWith({ provider: 'openai', id: 'gpt-4o' });
    unmount();

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-4o' }} onChange={noop} />);
    expect(selectValues()).toEqual(['openai', 'gpt-4o']);
  });

  it('a provider made only of unlisted models is listed LAST, and a stored model on it is displayed', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 5)];
    h.unlistedModels = [hidden('mistral', 'mistral-large-2')];

    render(<ModelPicker value={{ provider: 'mistral', id: 'mistral-large-2' }} onChange={noop} />);

    expect(selectValues()).toEqual(['mistral', 'mistral-large-2']);
    expect(providerOptions()).toEqual(['openai', 'mistral']);
  });

  it('never falls back onto an unlisted model: with no value, not the provider default nor the first row', () => {
    // The provider declares an unlisted model as its default AND ranks it first.
    h.providers = [{ ...provider('openai', [hidden('openai', 'gpt-4o'), model('openai', 'gpt-5')], 1), defaultModel: 'gpt-4o' }];
    h.unlistedModels = [];
    h.defaultProvider = null;
    h.defaultModel = null;

    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);

    expect(selectValues()).toEqual(['openai', 'gpt-5']);
  });

  it('switching provider picks its first OFFERED model, not a hidden one ranked above it', () => {
    h.providers = [
      provider('openai', [model('openai', 'gpt-5')], 1),
      provider('anthropic', [hidden('anthropic', 'claude-3-opus'), model('anthropic', 'claude-sonnet-5')], 2),
    ];
    h.unlistedModels = [];
    const onChange = vi.fn();

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={onChange} />);
    fireEvent.click(screen.getByTestId('option-anthropic'));

    expect(onChange).toHaveBeenCalledWith({ provider: 'anthropic', id: 'claude-sonnet-5' });
  });

  it('Free plan: a hidden free-tier model neither tops the list nor makes its provider lead', () => {
    h.prefersFree = true;
    h.providers = [
      provider('openai', [model('openai', 'gpt-5')], 1),
      provider('anthropic', [model('anthropic', 'claude-opus'), model('anthropic', 'claude-haiku', { freeTierEnabled: true })], 2),
    ];
    // Hidden AND free: openai offers nothing free, so it must not jump ahead of anthropic.
    h.unlistedModels = [hidden('openai', 'gpt-4o-mini'), hidden('anthropic', 'claude-3-haiku')].map(
      (m) => ({ ...m, freeTierEnabled: true }),
    );
    const onChange = vi.fn();

    render(<ModelPicker value={{ provider: 'anthropic', id: 'claude-opus' }} onChange={onChange} />);

    expect(providerOptions()).toEqual(['anthropic', 'openai']);
    // Free first among the OFFERED ones, the hidden free one still last.
    expect(modelOptions()).toEqual(['claude-haiku', 'claude-opus', 'claude-3-haiku']);
    fireEvent.click(screen.getByTestId('option-openai'));
    expect(onChange).toHaveBeenCalledWith({ provider: 'openai', id: 'gpt-5' });
  });

  it('a provider left with only hidden models is last under the heading even when ranked first, and no fallback lands on it', () => {
    // Through the extra slice, with the best displayOrder: only the partition can move it.
    h.categoryData = { providers: [provider('typesafe', [hidden('typesafe', 'jev-legacy')], 0)] };
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 5)];
    h.unlistedModels = [];
    h.defaultProvider = 'typesafe';
    h.defaultModel = 'jev-legacy';

    render(
      <ModelPicker value={{ provider: '', id: '' }} onChange={noop} unionCategory="classification" hiddenModelsLabel="Hidden models" />,
    );

    expect(providerOptions()).toEqual(['openai', 'typesafe']);
    const heading = screen.getByTestId('model-picker-hidden-providers');
    expect(heading).toHaveTextContent('Hidden models');
    expect(heading.nextElementSibling).toHaveAttribute('data-testid', 'option-typesafe');
    // Neither the declared default provider (hidden-only here) nor index 0.
    expect(selectValues()).toEqual(['openai', 'gpt-5']);
  });

  it('a model arriving from both the extra slice and the unlisted list is rendered once', () => {
    const legacy = hidden('typesafe', 'jev-legacy');
    h.categoryData = { providers: [provider('typesafe', [model('typesafe', 'jev-latest'), legacy], 20)] };
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [legacy];

    render(<ModelPicker value={{ provider: 'typesafe', id: 'jev-latest' }} onChange={noop} unionCategory="classification" />);

    expect(modelOptions()).toEqual(['jev-latest', 'jev-legacy']);
  });

  it('excludeBridgeProviders drops a hidden CLI model too, even for an admin', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [hidden('openai', 'gpt-4o'), model('codex', 'gpt-5.1-codex', { unlisted: true, providerKind: 'bridge' })];

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={noop} excludeBridgeProviders />);

    expect(providerOptions()).toEqual(['openai']);
    expect(modelOptions()).toEqual(['gpt-5', 'gpt-4o']);
  });

  it('picking a hidden-only provider stores its hidden model, which is why that provider sits under the heading', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [hidden('mistral', 'mistral-large-2')];
    const onChange = vi.fn();

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={onChange} />);
    expect(screen.getByTestId('model-picker-hidden-providers').nextElementSibling)
      .toHaveAttribute('data-testid', 'option-mistral');
    fireEvent.click(screen.getByTestId('option-mistral'));

    expect(onChange).toHaveBeenCalledWith({ provider: 'mistral', id: 'mistral-large-2' });
  });
});
