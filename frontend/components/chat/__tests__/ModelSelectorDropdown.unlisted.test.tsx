// @vitest-environment jsdom
/**
 * The models an admin UNLISTED (V554) stay available, so the composer still offers them, but
 * discreetly: one muted line under the list, collapsed, that opens on demand, or on its own
 * when the model in hand is one of them.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/hooks/useModelCostBasis', () => ({
  useModelCostBasis: () => ({ basis: null, isLoading: false }),
}));
vi.mock('next/image', () => ({
  // eslint-disable-next-line @next/next/no-img-element
  default: ({ src, alt }: { src: string; alt: string }) => <img src={src} alt={alt} />,
}));
vi.mock('@/components/ai/ModelInfo', () => ({
  ModelOptionDisplay: ({ model }: { model: { name: string } }) => <span data-testid="model-row">{model.name}</span>,
  ModelInfoPopover: () => null,
}));
vi.mock('@/lib/ai-providers/reasoningEffort', () => ({
  REASONING_EFFORT_LEVELS: ['low', 'high'],
  supportsReasoningEffort: () => false,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: (m: { provider: string; id: string }, sel: { provider: string; id: string }) =>
    !!sel?.id && m.id === sel.id && m.provider.toLowerCase() === (sel.provider ?? '').toLowerCase(),
  selectedModelFromAIModel: (m: { provider: string; id: string }) => ({ provider: m.provider, id: m.id }),
}));
vi.mock('@/components/ui/select', () => ({
  Select: ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children: React.ReactNode }) => (
    <select value={value} onChange={(e) => onValueChange(e.target.value)}>{children}</select>
  ),
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => (
    <option value={value === '' ? '__empty__' : value}>{children}</option>
  ),
  SELECT_EMPTY_VALUE_SENTINEL: '__empty__',
}));

import { ModelSelectorDropdown } from '../ModelSelectorDropdown';

const makeRect = (r: Partial<DOMRect>): DOMRect =>
  ({
    x: r.left ?? 0, y: r.top ?? 0, top: r.top ?? 0, left: r.left ?? 0,
    bottom: r.bottom ?? 0, right: r.right ?? 0, width: r.width ?? 0, height: r.height ?? 0,
    toJSON: () => ({}),
  }) as DOMRect;

let rectSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  Object.defineProperty(window, 'innerHeight', { value: 900, writable: true, configurable: true });
  Object.defineProperty(window, 'innerWidth', { value: 1400, writable: true, configurable: true });
  const rect = makeRect({ top: 600, bottom: 632, left: 1000, right: 1120, width: 120, height: 32 });
  rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(() => rect);
  window.localStorage.clear();
});

afterEach(() => {
  rectSpy.mockRestore();
  cleanup();
});

const labels = {
  tier: 'Tier',
  provider: 'Provider',
  allTiers: 'All tiers',
  allProviders: 'All providers',
  tiers: { top: 'Top tier', high: 'High tier', mid: 'Mid tier', budget: 'Budget' },
  noMatch: 'No model matches these filters',
  clear: 'Clear filters',
  reset: 'Reset',
  unlisted: 'Hidden models',
};

type Models = React.ComponentProps<typeof ModelSelectorDropdown>['availableModels'];

const listed: Models = [
  { provider: 'anthropic', id: 'claude-opus', name: 'Claude Opus', tier: 'top', iconSlug: 'anthropic' },
  { provider: 'openai', id: 'gpt-5', name: 'GPT-5', tier: 'high', iconSlug: 'openai' },
];
const hidden: Models = [
  { provider: 'openai', id: 'gpt-4o', name: 'GPT-4o', tier: 'high', iconSlug: 'openai', unlisted: true },
  { provider: 'anthropic', id: 'claude-3-haiku', name: 'Claude 3 Haiku', tier: 'budget', iconSlug: 'anthropic', unlisted: true },
];

const baseProps = {
  showModelSelector: true,
  setShowModelSelector: vi.fn(),
  selectedModel: { provider: 'anthropic', id: 'claude-opus' },
  selectedModelData: { name: 'Claude Opus', id: 'claude-opus' },
  availableModels: listed,
  unlistedModels: hidden,
  setSelectedModel: vi.fn(),
  changeModelTitle: 'Change model',
  filterLabels: labels,
};

const rowNames = () => screen.getAllByTestId('model-row').map((el) => el.textContent);

describe('ModelSelectorDropdown - hidden (unlisted) models', () => {
  it('shows one collapsed line with the count, and none of the hidden rows', () => {
    render(<ModelSelectorDropdown {...baseProps} />);

    const toggle = screen.getByTestId('model-selector-hidden-toggle');
    expect(toggle).toHaveTextContent('Hidden models');
    expect(toggle).toHaveTextContent('(2)');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(rowNames()).toEqual(['Claude Opus', 'GPT-5']);
  });

  it('opens on click, below the offered models, and a hidden model is chosen like any other', () => {
    const setSelectedModel = vi.fn();
    render(<ModelSelectorDropdown {...baseProps} setSelectedModel={setSelectedModel} />);

    fireEvent.click(screen.getByTestId('model-selector-hidden-toggle'));

    expect(rowNames()).toEqual(['Claude Opus', 'GPT-5', 'GPT-4o', 'Claude 3 Haiku']);
    fireEvent.click(within(screen.getByTestId('model-selector-hidden-group')).getByText('GPT-4o'));
    expect(setSelectedModel).toHaveBeenCalledWith({ provider: 'openai', id: 'gpt-4o' });
  });

  it('opens by itself when the model in hand is a hidden one, so the menu never hides the current choice', () => {
    render(
      <ModelSelectorDropdown
        {...baseProps}
        selectedModel={{ provider: 'openai', id: 'gpt-4o' }}
        selectedModelData={{ name: 'GPT-4o', id: 'gpt-4o' }}
      />,
    );

    expect(screen.getByTestId('model-selector-hidden-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(rowNames()).toContain('GPT-4o');
  });

  it('the footer filters narrow the hidden group too', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const [tierSelect] = within(screen.getByTestId('model-selector-footer')).getAllByRole('combobox');

    fireEvent.change(tierSelect, { target: { value: 'high' } });

    expect(screen.getByTestId('model-selector-hidden-toggle')).toHaveTextContent('(1)');
    fireEvent.click(screen.getByTestId('model-selector-hidden-toggle'));
    expect(rowNames()).toEqual(['GPT-5', 'GPT-4o']);
  });

  it('is not rendered without a label (a caller with no translator) nor when nothing is hidden', () => {
    const { unmount } = render(
      <ModelSelectorDropdown {...baseProps} filterLabels={{ ...labels, unlisted: undefined }} />,
    );
    expect(screen.queryByTestId('model-selector-hidden-group')).not.toBeInTheDocument();
    unmount();

    render(<ModelSelectorDropdown {...baseProps} unlistedModels={[]} />);
    expect(screen.queryByTestId('model-selector-hidden-group')).not.toBeInTheDocument();
  });

  it('a catalogue made only of hidden models is not the "no provider" empty state', () => {
    render(
      <ModelSelectorDropdown
        {...baseProps}
        availableModels={[]}
        emptyState={<div data-testid="no-provider-cta" />}
        noModelsLabel="No models"
        selectedModel={{ provider: '', id: '' }}
        selectedModelData={undefined}
      />,
    );

    expect(screen.queryByTestId('no-provider-cta')).not.toBeInTheDocument();
    expect(screen.getByTitle('Change model')).not.toHaveTextContent('No models');
  });
});
