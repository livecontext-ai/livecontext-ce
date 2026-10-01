/**
 * @vitest-environment jsdom
 *
 * ModelPicker and UNLISTED models (V554). An agent or a workflow node already on a model the
 * admin unlisted still runs on it, so the inspector must SHOW that model: the picker's own
 * fallback would display the first listed model, which the run does not use. The rest of the
 * unlisted set is not offered here (only the chat composer lists it, discreetly).
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  providers: [] as unknown[],
  unlistedModels: [] as unknown[],
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({ blocked: false, blockedForModel: () => false, freeTierForModel: () => false, prefersFreeTierModels: false, isLoading: false }),
}));
vi.mock('next/image', () => ({ default: () => null }));
// The Select stand-in exposes each control's resolved VALUE, which is what the picker would
// display in its trigger, and inlines the options so the offered list is assertable.
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <div data-testid="select" data-value={value}>{children}</div>
  ),
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <div data-testid={`option-${value}`}>{children}</div>
  ),
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
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
      defaultProvider: 'openai',
      defaultModel: 'gpt-5',
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

describe('ModelPicker - unlisted models (V554)', () => {
  afterEach(() => cleanup());

  it('displays a stored unlisted model instead of falling back to the first listed one', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [model('openai', 'gpt-4o', { unlisted: true }), model('openai', 'gpt-4', { unlisted: true })];

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-4o' }} onChange={noop} />);

    expect(selectValues()).toEqual(['openai', 'gpt-4o']);
    // Only the value's own model comes back: the rest of the unlisted set is not offered.
    expect(screen.getByTestId('option-gpt-4o')).toBeInTheDocument();
    expect(screen.queryByTestId('option-gpt-4')).not.toBeInTheDocument();
  });

  it('also when its provider has no listed model left', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [model('mistral', 'mistral-large-2', { unlisted: true })];

    render(<ModelPicker value={{ provider: 'mistral', id: 'mistral-large-2' }} onChange={noop} />);

    expect(selectValues()).toEqual(['mistral', 'mistral-large-2']);
  });

  it('offers no unlisted model to a picker whose value is a listed one', () => {
    h.providers = [provider('openai', [model('openai', 'gpt-5')], 1)];
    h.unlistedModels = [model('openai', 'gpt-4o', { unlisted: true })];

    render(<ModelPicker value={{ provider: 'openai', id: 'gpt-5' }} onChange={noop} />);

    expect(selectValues()).toEqual(['openai', 'gpt-5']);
    expect(screen.queryByTestId('option-gpt-4o')).not.toBeInTheDocument();
  });
});
