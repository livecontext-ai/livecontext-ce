// @vitest-environment jsdom
/**
 * The composer model menu narrows its list by price tier and by provider from a footer
 * strip that also hosts the reasoning-effort control. These tests drive the filters
 * through a native-select stand-in for the Radix Select (which has no layout in jsdom).
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
// Mutable through vi.hoisted so a test can render the footer WITHOUT an effort control,
// which is the shape a provider that takes no effort produces.
const effortSupport = vi.hoisted(() => ({ supported: true }));
vi.mock('@/lib/ai-providers/reasoningEffort', () => ({
  REASONING_EFFORT_LEVELS: ['low', 'high'],
  supportsReasoningEffort: () => effortSupport.supported,
}));
vi.mock('@/hooks/useModels', () => ({
  modelMatches: () => false,
  selectedModelFromAIModel: (m: unknown) => m,
}));
vi.mock('@/components/ui/select', () => ({
  // A native <select> stands in for Radix: value + onValueChange become value + onChange,
  // items become options, the trigger and its value display are not part of the contract.
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
  effortSupport.supported = true;
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
};

type Models = React.ComponentProps<typeof ModelSelectorDropdown>['availableModels'];

const models: Models = [
  { provider: 'anthropic', id: 'claude-opus', name: 'Claude Opus', tier: 'top', iconSlug: 'anthropic' },
  { provider: 'anthropic', id: 'claude-haiku', name: 'Claude Haiku', tier: 'budget', iconSlug: 'anthropic' },
  { provider: 'openai', id: 'gpt-4o', name: 'GPT-4o', tier: 'high', iconSlug: 'openai' },
  { provider: 'deepseek', id: 'deepseek-chat', name: 'DeepSeek Chat', tier: 'budget', iconSlug: 'deepseek' },
];

const baseProps = {
  showModelSelector: true,
  setShowModelSelector: vi.fn(),
  selectedModel: { provider: 'anthropic', id: 'claude-opus' },
  selectedModelData: { name: 'Claude Opus', id: 'claude-opus' },
  availableModels: models,
  setSelectedModel: vi.fn(),
  changeModelTitle: 'Change model',
  filterLabels: labels,
};

const rowNames = () => screen.getAllByTestId('model-row').map((el) => el.textContent);
const selects = () => within(screen.getByTestId('model-selector-footer')).getAllByRole('combobox');

describe('ModelSelectorDropdown - footer filters', () => {
  it('lists every model with no filter, and offers only the tiers and providers the list actually holds', () => {
    render(<ModelSelectorDropdown {...baseProps} />);

    expect(rowNames()).toEqual(['Claude Opus', 'Claude Haiku', 'GPT-4o', 'DeepSeek Chat']);
    const [tierSelect, providerSelect] = selects();
    // No 'mid' model in the catalogue: no 'Mid tier' option to pick.
    expect(within(tierSelect).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['All tiers', 'Top tier', 'High tier', 'Budget']);
    // Providers by display name, alphabetical, so the list reads the same in every menu.
    expect(within(providerSelect).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['All providers', 'Anthropic', 'DeepSeek', 'OpenAI']);
  });

  it('narrows the list by tier, then by provider, and the two combine', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const [tierSelect, providerSelect] = selects();

    fireEvent.change(tierSelect, { target: { value: 'budget' } });
    expect(rowNames()).toEqual(['Claude Haiku', 'DeepSeek Chat']);

    fireEvent.change(providerSelect, { target: { value: 'anthropic' } });
    expect(rowNames()).toEqual(['Claude Haiku']);

    fireEvent.change(tierSelect, { target: { value: '__empty__' } });
    expect(rowNames()).toEqual(['Claude Opus', 'Claude Haiku']);
  });

  it('says so when nothing matches, and one click clears both filters', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const [tierSelect, providerSelect] = selects();

    fireEvent.change(tierSelect, { target: { value: 'top' } });
    fireEvent.change(providerSelect, { target: { value: 'deepseek' } });

    expect(screen.queryAllByTestId('model-row')).toHaveLength(0);
    const noMatch = screen.getByTestId('model-selector-no-match');
    expect(noMatch).toHaveTextContent('No model matches these filters');

    fireEvent.click(within(noMatch).getByRole('button', { name: 'Clear filters' }));
    expect(rowNames()).toHaveLength(4);
    expect(screen.queryByTestId('model-selector-no-match')).toBeNull();
  });

  it('remembers the filters in this browser', () => {
    const { unmount } = render(<ModelSelectorDropdown {...baseProps} />);
    fireEvent.change(selects()[0], { target: { value: 'budget' } });
    expect(JSON.parse(window.localStorage.getItem('lc.composer.modelFilters') ?? '{}')).toEqual({ tier: 'budget', provider: '' });
    unmount();

    render(<ModelSelectorDropdown {...baseProps} />);
    expect(rowNames()).toEqual(['Claude Haiku', 'DeepSeek Chat']);
  });

  it('DROPS a remembered value the catalogue does not hold, from the list, the state and the storage - not merely masks it', () => {
    // A remembered provider that this menu does not hold must not hide every row...
    window.localStorage.setItem('lc.composer.modelFilters', JSON.stringify({ tier: 'mid', provider: 'mistral' }));
    render(<ModelSelectorDropdown {...baseProps} />);
    expect(rowNames()).toHaveLength(4);
    // ...the drop itself rewrites the storage, before the user touches anything...
    expect(JSON.parse(window.localStorage.getItem('lc.composer.modelFilters') ?? '{}')).toEqual({ tier: '', provider: '' });
    // ...and it is gone for good: changing the OTHER filter must not write the stale value back,
    // or it would silently re-apply the day the catalogue gains that provider again.
    fireEvent.change(selects()[0], { target: { value: 'budget' } });
    expect(JSON.parse(window.localStorage.getItem('lc.composer.modelFilters') ?? '{}')).toEqual({ tier: 'budget', provider: '' });
  });

  it('narrows by provider alone, and the sentinel clears it back to every provider', () => {
    render(<ModelSelectorDropdown {...baseProps} />);
    const [, providerSelect] = selects();

    fireEvent.change(providerSelect, { target: { value: 'openai' } });
    expect(rowNames()).toEqual(['GPT-4o']);

    fireEvent.change(providerSelect, { target: { value: '__empty__' } });
    expect(rowNames()).toHaveLength(4);
  });

  it('keeps working when this browser blocks site data (storage throws) or holds garbage', () => {
    window.localStorage.setItem('lc.composer.modelFilters', '{not json');
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError'); });
    try {
      render(<ModelSelectorDropdown {...baseProps} />);
      expect(rowNames()).toHaveLength(4);
      fireEvent.change(selects()[0], { target: { value: 'top' } });
      expect(rowNames()).toEqual(['Claude Opus']);
    } finally {
      setItem.mockRestore();
    }
    cleanup();

    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('SecurityError'); });
    try {
      render(<ModelSelectorDropdown {...baseProps} />);
      expect(rowNames()).toHaveLength(4);
    } finally {
      getItem.mockRestore();
    }
  });

  it('does not offer a filter that has a single choice: one provider keeps only the tier select, one tier keeps only the provider select', () => {
    const oneProvider: Models = [
      { provider: 'anthropic', id: 'claude-opus', name: 'Claude Opus', tier: 'top', iconSlug: 'anthropic' },
      { provider: 'anthropic', id: 'claude-haiku', name: 'Claude Haiku', tier: 'budget', iconSlug: 'anthropic' },
    ];
    render(<ModelSelectorDropdown {...baseProps} availableModels={oneProvider} />);
    let all = selects();
    expect(all).toHaveLength(1);
    expect(within(all[0]).getAllByRole('option').map((o) => o.textContent)).toEqual(['All tiers', 'Top tier', 'Budget']);
    cleanup();

    const oneTier: Models = [
      { provider: 'anthropic', id: 'claude-haiku', name: 'Claude Haiku', tier: 'budget', iconSlug: 'anthropic' },
      { provider: 'deepseek', id: 'deepseek-chat', name: 'DeepSeek Chat', tier: 'budget', iconSlug: 'deepseek' },
    ];
    render(<ModelSelectorDropdown {...baseProps} availableModels={oneTier} />);
    all = selects();
    expect(all).toHaveLength(1);
    expect(within(all[0]).getAllByRole('option').map((o) => o.textContent)).toEqual(['All providers', 'Anthropic', 'DeepSeek']);
  });

  it('regression: a filter whose control is not offered never narrows the list - a remembered tier against a catalogue of untiered models is dropped', () => {
    // One tiered model among untiered ones: the tier select is not offered (one tier), so a
    // remembered "budget" would show a single row with no control to clear it.
    window.localStorage.setItem('lc.composer.modelFilters', JSON.stringify({ tier: 'budget', provider: '' }));
    const mostlyUntiered: Models = [
      { provider: 'anthropic', id: 'a', name: 'A budget', tier: 'budget', iconSlug: 'anthropic' },
      { provider: 'openai', id: 'b', name: 'B untiered', iconSlug: 'openai' },
      { provider: 'deepseek', id: 'c', name: 'C untiered', iconSlug: 'deepseek' },
    ];
    render(<ModelSelectorDropdown {...baseProps} availableModels={mostlyUntiered} />);

    expect(rowNames()).toEqual(['A budget', 'B untiered', 'C untiered']);
    expect(JSON.parse(window.localStorage.getItem('lc.composer.modelFilters') ?? '{}')).toEqual({ tier: '', provider: '' });
  });

  it('keeps the free-tier-first order inside a narrowed list', () => {
    const withFree: Models = [
      { provider: 'anthropic', id: 'claude-opus', name: 'Claude Opus', tier: 'budget', iconSlug: 'anthropic' },
      { provider: 'openai', id: 'gpt-mini', name: 'GPT mini', tier: 'budget', freeTierEnabled: true, iconSlug: 'openai' },
      { provider: 'deepseek', id: 'deepseek-chat', name: 'DeepSeek Chat', tier: 'budget', freeTierEnabled: true, iconSlug: 'deepseek' },
      { provider: 'openai', id: 'gpt-4o', name: 'GPT-4o', tier: 'high', iconSlug: 'openai' },
    ];
    render(<ModelSelectorDropdown {...baseProps} availableModels={withFree} prefersFreeTierModels />);

    fireEvent.change(selects()[0], { target: { value: 'budget' } });
    expect(rowNames()).toEqual(['GPT mini', 'DeepSeek Chat', 'Claude Opus']);
  });

  it('a click inside the footer or on the clear button never closes the menu, while a click outside still does', async () => {
    const setShowModelSelector = vi.fn();
    render(<ModelSelectorDropdown {...baseProps} setShowModelSelector={setShowModelSelector} />);
    // The outside-click listener is attached on the next tick: wait for it, then prove it
    // is live with a click that MUST close, before proving the footer clicks do not.
    await new Promise((resolve) => setTimeout(resolve, 0));
    const [tierSelect, providerSelect] = selects();
    fireEvent.change(tierSelect, { target: { value: 'top' } });
    fireEvent.change(providerSelect, { target: { value: 'deepseek' } });

    fireEvent.click(screen.getByTestId('model-selector-footer'));
    fireEvent.click(within(screen.getByTestId('model-selector-no-match')).getByRole('button', { name: 'Clear filters' }));
    expect(setShowModelSelector).not.toHaveBeenCalled();

    fireEvent.click(document.body);
    expect(setShowModelSelector).toHaveBeenCalledWith(false);
  });

  it('hosts the reasoning-effort control in the same footer, after the filters, and never above the list', () => {
    render(
      <ModelSelectorDropdown
        {...baseProps}
        reasoningEffort=""
        onReasoningEffortChange={vi.fn()}
        reasoningEffortLabel="Reasoning effort"
        effortAutoLabel="Auto"
      />,
    );

    const footer = screen.getByTestId('model-selector-footer');
    const all = within(footer).getAllByRole('combobox');
    expect(all).toHaveLength(3);
    expect(within(all[2]).getAllByRole('option').map((o) => o.textContent)).toEqual(['Auto', 'low', 'high']);
    // The footer comes after the list in the menu.
    const menu = screen.getByTestId('model-selector-menu');
    const list = menu.querySelector('.model-selector-scroll') as HTMLElement;
    expect(list.compareDocumentPosition(footer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('offers no reset until something is actually set, so the strip stays quiet by default', () => {
    render(<ModelSelectorDropdown {...baseProps} />);

    expect(screen.queryByTestId('model-selector-reset')).toBeNull();

    const [tierSelect] = selects();
    fireEvent.change(tierSelect, { target: { value: 'budget' } });
    expect(screen.getByTestId('model-selector-reset')).toHaveTextContent('Reset');
  });

  it('puts every control the footer offers back to its default in one click', () => {
    const onEffortChange = vi.fn();
    render(
      <ModelSelectorDropdown
        {...baseProps}
        reasoningEffort="high"
        onReasoningEffortChange={onEffortChange}
        reasoningEffortLabel="Reasoning effort"
        effortAutoLabel="Auto"
      />,
    );
    const [tierSelect, providerSelect] = selects();
    fireEvent.change(tierSelect, { target: { value: 'budget' } });
    fireEvent.change(providerSelect, { target: { value: 'anthropic' } });
    expect(rowNames()).toEqual(['Claude Haiku']);

    fireEvent.click(screen.getByTestId('model-selector-reset'));

    // The filters are this component's own state, so the list widens back out...
    expect(rowNames()).toEqual(['Claude Opus', 'Claude Haiku', 'GPT-4o', 'DeepSeek Chat']);
    // ...and the per-browser memory goes with them, or the next menu opens narrowed again.
    expect(JSON.parse(window.localStorage.getItem('lc.composer.modelFilters') ?? '{}'))
      .toEqual({ tier: '', provider: '' });
    // The effort override belongs to the CALLER (it is per conversation), so the reset
    // asks for it rather than assuming it.
    expect(onEffortChange).toHaveBeenCalledWith('');
  });

  it('leaves an effort override the footer is not showing alone', () => {
    // The override outlives the menu that set it: a model whose provider takes no effort
    // renders no effort control, and a reset there must not silently drop a setting the
    // user made for another model. It still clears what this footer DOES show.
    effortSupport.supported = false;
    const onEffortChange = vi.fn();
    render(
      <ModelSelectorDropdown
        {...baseProps}
        reasoningEffort="high"
        onReasoningEffortChange={onEffortChange}
        reasoningEffortLabel="Reasoning effort"
        effortAutoLabel="Auto"
      />,
    );
    // The control really is absent, or the assertion below would hold for the wrong reason.
    expect(selects()).toHaveLength(2);
    const [tierSelect] = selects();
    fireEvent.change(tierSelect, { target: { value: 'budget' } });

    fireEvent.click(screen.getByTestId('model-selector-reset'));

    expect(rowNames()).toEqual(['Claude Opus', 'Claude Haiku', 'GPT-4o', 'DeepSeek Chat']);
    expect(onEffortChange).not.toHaveBeenCalled();
  });

  it('keeps the effort control out of the filter group, so a wrap gives it its own line', () => {
    // The layout rule, asserted on the STRUCTURE rather than on a width jsdom does not
    // compute: what narrows the list is one group, the effort control is the other, and
    // the outer strip wraps between them. Flat children wrapped at whatever width ran
    // out, which could leave one filter stranded under the effort control.
    render(
      <ModelSelectorDropdown
        {...baseProps}
        reasoningEffort=""
        onReasoningEffortChange={vi.fn()}
        reasoningEffortLabel="Reasoning effort"
        effortAutoLabel="Auto"
      />,
    );

    const footer = screen.getByTestId('model-selector-footer');
    expect(footer.className).toContain('flex-wrap');
    const groups = Array.from(footer.children) as HTMLElement[];
    expect(groups).toHaveLength(2);
    // Two filters in the first group, the effort control alone in the second.
    expect(within(groups[0]).getAllByRole('combobox')).toHaveLength(2);
    expect(within(groups[1]).getAllByRole('combobox')).toHaveLength(1);
    // Neither group may shrink, or the outer strip squeezes them instead of wrapping.
    for (const group of groups) expect(group.className).toContain('shrink-0');
  });

  it('renders no footer for a caller that offers neither filters nor an effort control', () => {
    render(<ModelSelectorDropdown {...baseProps} filterLabels={undefined} />);

    expect(screen.queryByTestId('model-selector-footer')).toBeNull();
    expect(rowNames()).toHaveLength(4);
  });
});
