/**
 * @vitest-environment jsdom
 *
 * The classify node's picker must offer BOTH engines, and no other picker may offer the
 * decision one.
 *
 * This is the seam two earlier suites each proved half of and neither crossed: the server
 * strips decision models from the chat answer (asserted in
 * `ModelCatalogServiceDecisionModeTest`), so a capability filter alone has nothing to let
 * through and the classify picker stayed empty of them while both suites passed. What is
 * asserted here is the union actually arriving in the rendered list.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import * as React from 'react';
import type { AIModel, AIProvider } from '@/hooks/useModels';

const h = vi.hoisted(() => ({
  providers: [] as unknown[],
  categoryData: null as unknown,
  requestedCategories: [] as (string | null)[],
}));

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('@/lib/hooks/useMonthlyCreditsCannotPay', () => ({
  useMonthlyCreditsCannotPay: () => ({
    blocked: false,
    blockedForModel: () => false,
    freeTierForModel: () => false,
    prefersFreeTierModels: false,
    isLoading: false,
  }),
}));
vi.mock('next/image', () => ({ default: () => null }));
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { id: string } }) => <span>{model.id}</span>,
  ModelInfoPopover: () => null,
}));

// Records what the picker ASKS for, which is the half that was missing: a picker that
// never requests the category can never show its models, however good its filter is.
vi.mock('@/hooks/useCategoryModels', () => ({
  useCategoryModels: (category: string | null) => {
    h.requestedCategories.push(category);
    return { data: category ? h.categoryData : null, isLoading: false };
  },
}));

// The real capability predicate is kept: it is half of what this suite is about.
vi.mock('@/hooks/useModels', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/useModels')>();
  return {
    ...actual,
    useVisibleModels: () => ({
      providers: h.providers,
      defaultProvider: 'openai',
      defaultModel: 'gpt-5-mini',
      isLoading: false,
      error: null,
      models: [],
      refresh: async () => {},
    }),
  };
});

import { ModelPicker } from '../ModelPicker';

const model = (provider: string, id: string, mode?: string): AIModel => ({
  id,
  name: id,
  provider,
  ...(mode ? { mode } : {}),
});

const provider = (name: string, models: AIModel[], displayOrder: number): AIProvider => ({
  name,
  defaultModel: models[0]?.id ?? '',
  supportsStreaming: true,
  supportsToolCalling: true,
  displayOrder,
  models,
});

/** What the server answers for the chat slice: no decision model, ever. */
const CHAT_CATALOG = [
  provider('openai', [model('openai', 'gpt-5-mini', 'chat')], 1),
  provider('anthropic', [model('anthropic', 'claude-haiku-4-5', 'chat')], 2),
];

/** What the server answers for the classification slice. */
const DECISION_CATALOG = {
  providers: [provider('typesafe', [model('typesafe', 'jev-latest', 'decision')], 20)],
  defaultProvider: 'typesafe',
  defaultModel: 'jev-latest',
};

const noop = () => {};
const CLASSIFY_ENGINES = ['chat', 'decision'] as const;

describe('ModelPicker - the classify node offers both engines', () => {
  beforeEach(() => {
    h.providers = CHAT_CATALOG;
    h.categoryData = DECISION_CATALOG;
    h.requestedCategories = [];
  });
  afterEach(() => cleanup());

  it('offers the decision model when the surface asks for its category', () => {
    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    // The PROVIDER list first: the picker renders models for the selected provider only,
    // so a provider that never appears can never have its model chosen.
    expect(screen.getAllByText('TypeSafe').length).toBeGreaterThan(0);
  });

  it('lists the decision MODEL itself once its provider is the selected one', () => {
    // The provider appearing is necessary and not sufficient: what a user actually picks
    // is the model, and it renders only under its own provider.
    render(
      <ModelPicker
        value={{ provider: 'typesafe', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(screen.getAllByText('jev-latest').length).toBeGreaterThan(0);
  });

  it('keeps offering the chat engines beside it, which are still the default', () => {
    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Anthropic').length).toBeGreaterThan(0);
  });

  it('requests the classification slice, because the chat answer never contains it', () => {
    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(h.requestedCategories).toContain('classification');
  });

  it('a plain picker asks for no extra category and is offered no decision model', () => {
    // The containment half. A default picker is every chat and agent surface in the app.
    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);

    expect(h.requestedCategories.every(c => c === null)).toBe(true);
    expect(screen.queryByText('TypeSafe')).toBeNull();
  });

  it('a decision model that somehow reached a chat picker is still filtered out', () => {
    // Defence in depth: if the server ever stopped stripping it, the capability filter
    // is the second line and must hold on its own.
    h.providers = [
      ...CHAT_CATALOG,
      provider('typesafe', [model('typesafe', 'jev-latest', 'decision')], 20),
    ];

    render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);

    expect(screen.queryByText('TypeSafe')).toBeNull();
    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
  });

  it('a category that answers nothing leaves the chat list intact rather than emptying it', () => {
    // The endpoint can fail, and a classify inspector that renders an empty picker would
    // be a worse outcome than one that offers only the chat engine.
    h.categoryData = null;

    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
    expect(screen.queryByText('TypeSafe')).toBeNull();
  });

  it('a provider present in both slices is listed once, not twice', () => {
    // Measured against the same render without the extra slice, because the provider
    // name legitimately appears more than once (the trigger shows the selection and the
    // list shows the option). What must not change is the COUNT.
    const { unmount } = render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    const baseline = screen.getAllByText('OpenAI').length;
    unmount();

    h.categoryData = {
      providers: [provider('openai', [model('openai', 'gpt-5-mini', 'chat')], 1)],
      defaultProvider: 'openai',
      defaultModel: 'gpt-5-mini',
    };
    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(screen.getAllByText('OpenAI')).toHaveLength(baseline);
  });

  it('a model present in both slices is listed once under its provider', () => {
    h.categoryData = {
      providers: [provider('openai', [model('openai', 'gpt-5-mini', 'chat')], 1)],
      defaultProvider: 'openai',
      defaultModel: 'gpt-5-mini',
    };

    const { unmount } = render(<ModelPicker value={{ provider: '', id: '' }} onChange={noop} />);
    const baseline = screen.getAllByText('gpt-5-mini').length;
    unmount();

    render(
      <ModelPicker
        value={{ provider: '', id: '' }}
        onChange={noop}
        filterCapability={CLASSIFY_ENGINES}
        unionCategory="classification"
      />,
    );

    expect(screen.getAllByText('gpt-5-mini')).toHaveLength(baseline);
  });
});
