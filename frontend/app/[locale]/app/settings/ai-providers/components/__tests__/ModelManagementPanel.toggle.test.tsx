// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react';

/**
 * Q1 - disabling/enabling a model in the AI-providers "Models" panel must be
 * INSTANT: an optimistic in-place flip with NO full-list refetch (the refetch
 * swapped the whole list for a spinner + re-sorted, which the user saw as a
 * slow visual reload). These tests pin: (1) the toggle flips in place and does
 * not refetch, and (2) a failed save rolls the row back.
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
  getDisabledProviders: vi.fn().mockResolvedValue([]),
  setProviderEnabled: vi.fn().mockResolvedValue(undefined),
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
    getDisabledProviders: mocks.getDisabledProviders,
    setProviderEnabled: mocks.setProviderEnabled,
  },
}));
// Radix Select portals its list and needs real pointer events, which jsdom does not give
// it. The repo mocks it as plain elements elsewhere for the same reason; here each item is
// a button carrying its value, so a test can pick one without fighting the primitive.
vi.mock('@/components/ui/select', async () => {
  const React = await import('react');
  const Ctx = React.createContext<(value: string) => void>(() => {});
  return {
    Select: ({ children, onValueChange }: { children: React.ReactNode; onValueChange: (v: string) => void }) =>
      React.createElement(Ctx.Provider, { value: onValueChange }, children),
    SelectTrigger: ({ children, ...rest }: { children: React.ReactNode }) =>
      React.createElement('div', rest, children),
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) =>
      React.createElement('div', null, children),
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const onValueChange = React.useContext(Ctx);
      return React.createElement(
        'button',
        { type: 'button', 'data-testid': `select-item-${value}`, onClick: () => onValueChange(value) },
        children,
      );
    },
  };
});
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string, values?: Record<string, string>) =>
  values ? `${k}:${JSON.stringify(values)}` : k;

function buildModel(over: Record<string, unknown> = {}) {
  return {
    id: 'gpt-5',
    name: 'GPT-5',
    provider: 'openai',
    displayOrder: 1,
    enabled: true,
    tier: 'top',
    providerKind: 'cloud' as const,
    ...over,
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - optimistic enable toggle (no visual reload)', () => {
  it('flips the row in place and does NOT refetch the model list on toggle', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'gpt-5', provider: 'openai', enabled: true }),
      buildModel({ id: 'claude-opus', name: 'Claude', provider: 'anthropic', displayOrder: 2, enabled: true }),
    ]);
    mocks.saveOverride.mockResolvedValue({ id: 1, provider: 'openai', modelId: 'gpt-5' });

    render(<ModelManagementPanel t={t} />);

    const toggle = await screen.findByTestId('model-toggle-openai-gpt-5');
    // Initial load = exactly one fetch.
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1));
    expect(toggle).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(toggle);

    // Optimistic flip is visible immediately + persisted as enabled=false.
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-checked', 'false'),
    );
    expect(mocks.saveOverride).toHaveBeenCalledWith(
      expect.objectContaining({ provider: 'openai', modelId: 'gpt-5', enabled: false }),
    );
    await waitFor(() => expect(mocks.clearModelsCache).toHaveBeenCalledTimes(1));

    // The KEY assertion: no second fetch - the list was NOT reloaded/re-sorted.
    expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1);
  });

  it('persists via setCategoryEnabled (still no refetch) when toggling on a non-chat tab', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ enabled: true })]);
    mocks.setCategoryEnabled.mockResolvedValue({ success: true });

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1)); // chat (mount)

    // Switch to the Browser Agent tab → one (non-silent) fetch for that category.
    fireEvent.click(screen.getByText('modelConfig.category.browser_agent.label'));
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(2));

    const toggle = await screen.findByTestId('model-toggle-openai-gpt-5');
    fireEvent.click(toggle);

    // Non-chat branch → setCategoryEnabled with the active category; optimistic
    // flip; and crucially NO third fetch (no reload on toggle).
    await waitFor(() =>
      expect(mocks.setCategoryEnabled).toHaveBeenCalledWith('openai', 'gpt-5', 'browser_agent', false),
    );
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-checked', 'false'),
    );
    expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(2);
    expect(mocks.saveOverride).not.toHaveBeenCalled();
  });

  it('rolls the row back to its previous state when the save fails', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ enabled: true })]);
    mocks.saveOverride.mockRejectedValue(new Error('save failed'));

    render(<ModelManagementPanel t={t} />);

    const toggle = await screen.findByTestId('model-toggle-openai-gpt-5');
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1));

    fireEvent.click(toggle);

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    // Rolled back to enabled (aria-checked true) after the rejected save.
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-checked', 'true'),
    );
    // Never refetched, even on the error path.
    expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1);
  });
});

/**
 * What a row says about identity. The name is editable, so on its own it cannot tell a
 * renamed model from one still wearing its catalogue name, and it cannot tell you the id a
 * workflow or an execution link actually refers to.
 */
describe('ModelManagementPanel model identity', () => {
  it('shows the real id under the name once the two differ', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'deepseek-v4-flash', name: 'deepseek v4.1 flash', provider: 'deepseek' }),
    ]);

    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('model-id-deepseek-deepseek-v4-flash'))
      .toHaveTextContent('deepseek-v4-flash');
  });

  it('stays one line for a model nobody has renamed', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'gpt-5', name: 'gpt-5', provider: 'openai' }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    // Nothing to disambiguate, so no second line: the id row means "renamed".
    expect(screen.queryByTestId('model-id-openai-gpt-5')).not.toBeInTheDocument();
  });

  it('keeps the custom and unconfigured marks readable without a text pill each', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ isCustom: true, available: false, providerKind: 'cloud' }),
    ]);

    render(<ModelManagementPanel t={t} />);

    // Icons now, with the wording kept where a reader can still get at it.
    const custom = await screen.findByTestId('model-custom-openai-gpt-5');
    expect(custom).toHaveAttribute('title', 'modelConfig.custom');
    const unconfigured = screen.getByTestId('model-unconfigured-openai-gpt-5');
    expect(unconfigured).toHaveAttribute('title', 'modelConfig.notConfiguredTooltip');
  });
});

/**
 * Bulk changes over the current selection. The provider switch answers "remove all 438 of
 * these"; this answers "these eleven", which is what filtering leaves you with.
 */
describe('ModelManagementPanel bulk actions', () => {
  const two = [
    buildModel({ id: 'gpt-5', provider: 'openai', tier: 'top', enabled: true }),
    buildModel({ id: 'gpt-5-mini', provider: 'openai', tier: 'mid', enabled: true }),
  ];

  it('appears only once something is ticked, and acts on exactly the ticked rows', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    expect(screen.queryByTestId('bulk-bar')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('model-select-openai-gpt-5'));

    expect(await screen.findByTestId('bulk-bar')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('bulk-disable'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-5', enabled: false,
    });
  });

  it('select-all takes the rows the filters leave on screen, and only those', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'mini' } });

    fireEvent.click(screen.getByTestId('model-select-all'));
    fireEvent.click(await screen.findByTestId('bulk-enable'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-5-mini', enabled: true,
    });
  });

  it('sets the tier on the selection, globally, whatever tab is open', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    fireEvent.click(screen.getByTestId('model-select-openai-gpt-5'));
    fireEvent.click(within(await screen.findByTestId('bulk-tier')).getByTestId('select-item-high'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    // A tier is a property of the model, not of the surface: unlike enable, there is no
    // per-category version of it, so this stays a global write on every tab.
    expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-5', tier: 'high',
    });
    expect(mocks.setCategoryEnabled).not.toHaveBeenCalled();
  });

  it('writes the sidecar, not the global flag, when a category tab is open', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);
    mocks.setCategoryEnabled.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    fireEvent.click(screen.getByText('modelConfig.category.browser_agent.label'));
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent'));

    fireEvent.click(await screen.findByTestId('model-select-openai-gpt-5'));
    fireEvent.click(await screen.findByTestId('bulk-disable'));

    // The same branch the single-row toggle takes: disabling in browser_agent must leave
    // the model usable in chat.
    await waitFor(() =>
      expect(mocks.setCategoryEnabled).toHaveBeenCalledWith('openai', 'gpt-5', 'browser_agent', false),
    );
    expect(mocks.saveOverride).not.toHaveBeenCalled();
  });

  it('reports how many failed instead of claiming the whole batch worked', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);
    mocks.saveOverride
      .mockResolvedValueOnce({})
      .mockRejectedValueOnce(new Error('model is unpriced'));

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    fireEvent.click(screen.getByTestId('model-select-all'));
    fireEvent.click(await screen.findByTestId('bulk-enable'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(2));
    // The count AND the last reason, so a partial batch is actionable.
    expect(await screen.findByText(/"failed":"1","total":"2"/)).toBeInTheDocument();
  });
});

/**
 * Filters. The panel lists the whole catalogue, which on a cloud install is several hundred
 * rows, so finding the one model you came for was the first problem and narrowing before a
 * bulk change is the second.
 */
describe('ModelManagementPanel filters', () => {
  const two = [
    buildModel({ id: 'gpt-5', name: 'GPT-5', provider: 'openai', tier: 'top', enabled: true }),
    buildModel({ id: 'grok-4.6', name: 'Grok 4.6', provider: 'xai', tier: 'high', enabled: false }),
  ];

  it('matches the search on the id AND on the name an admin gave it', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    // The two diverge as soon as a model is renamed, and searching for what is on screen
    // has to work either way.
    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'grok' } });
    expect(screen.queryByTestId('model-toggle-openai-gpt-5')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-toggle-xai-grok-4.6')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'GPT-5' } });
    expect(screen.getByTestId('model-toggle-openai-gpt-5')).toBeInTheDocument();
    expect(screen.queryByTestId('model-toggle-xai-grok-4.6')).not.toBeInTheDocument();
  });

  it('filters by tier and by which side of the switch a model is on', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    // Scoped: every row carries a tier select with the same option values.
    fireEvent.click(within(screen.getByTestId('tier-filter')).getByTestId('select-item-high'));
    expect(screen.queryByTestId('model-toggle-openai-gpt-5')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('clear-filters'));
    expect(await screen.findByTestId('model-toggle-openai-gpt-5')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('select-item-off'));
    expect(screen.queryByTestId('model-toggle-openai-gpt-5')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-toggle-xai-grok-4.6')).toBeInTheDocument();
  });

  it('offers a way out: clearing puts every model back', async () => {
    mocks.getEffectiveModels.mockResolvedValue(two);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    // Nothing to clear until something is filtered.
    expect(screen.queryByTestId('clear-filters')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'nothing matches' } });
    fireEvent.click(screen.getByTestId('clear-filters'));

    expect(await screen.findByTestId('model-toggle-openai-gpt-5')).toBeInTheDocument();
    expect(screen.getByTestId('model-toggle-xai-grok-4.6')).toBeInTheDocument();
  });
});

/**
 * The provider switch: one move that takes a whole provider out of every picker. A feed
 * fills OpenRouter with 438 rows and Mistral with 60, so the per-model toggle was never a
 * real answer for them.
 */
describe('ModelManagementPanel provider switch', () => {
  const selectProvider = async (provider: string) =>
    fireEvent.click(await screen.findByTestId(`select-item-${provider}`));

  it('is hidden until a provider is picked: there is no all-providers version of it', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    expect(screen.queryByTestId('provider-toggle-openai')).not.toBeInTheDocument();
  });

  it('switches the whole provider off, and says so once instead of on every row', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    await selectProvider('openai');

    const providerToggle = await screen.findByTestId('provider-toggle-openai');
    expect(providerToggle).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(providerToggle);

    await waitFor(() => expect(mocks.setProviderEnabled).toHaveBeenCalledWith('openai', false));
    // Optimistic, like the per-model toggle: no refetch of the whole catalogue.
    expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1);
    expect(await screen.findByTestId('provider-off-notice')).toBeInTheDocument();
  });

  it('rolls the switch back and shows the server message when the write fails', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.setProviderEnabled.mockRejectedValueOnce(new Error('provider switch unavailable'));

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    await selectProvider('openai');

    fireEvent.click(await screen.findByTestId('provider-toggle-openai'));

    await waitFor(() =>
      expect(screen.getByTestId('provider-toggle-openai')).toHaveAttribute('aria-checked', 'true'),
    );
    expect(screen.getByText('provider switch unavailable')).toBeInTheDocument();
    expect(screen.queryByTestId('provider-off-notice')).not.toBeInTheDocument();
  });

  it('renders the catalogue even when the switch endpoint is unavailable', async () => {
    // A rolling deploy where agent-service predates the endpoint. The switches are a
    // refinement of the list, never a reason to fail rendering it.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.getDisabledProviders.mockRejectedValueOnce(new Error('404'));

    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('model-toggle-openai-gpt-5')).toBeInTheDocument();
    expect(screen.queryByText('modelConfig.fetchError')).not.toBeInTheDocument();
  });

  it('draws NO switch when the disabled set could not be read, and says why', async () => {
    // Unknown is not "none off". Drawing the switch ON over an unknown answer made the first
    // click on an already-off provider write OFF again: a no-op that flips the control and
    // takes two clicks to undo. So the control is withheld rather than guessed.
    mocks.getEffectiveModels.mockResolvedValue([buildModel()]);
    mocks.getDisabledProviders.mockRejectedValueOnce(new Error('404'));

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    await selectProvider('openai');

    expect(await screen.findByText('modelConfig.providerSwitchUnavailable')).toBeInTheDocument();
    // Picking a provider is exactly what normally reveals the switch.
    expect(screen.queryByTestId('provider-toggle-openai')).not.toBeInTheDocument();
    expect(mocks.setProviderEnabled).not.toHaveBeenCalled();
  });

  it('offers a way back in for a provider that is off AND absent from the catalogue', async () => {
    // Switching a provider off removes its models from the list this panel reads, so a
    // provider made only of custom models disappears entirely. The switch that would bring
    // it back is only reachable once it is picked in the filter, so without the union with
    // the disabled set it is stuck off with no route back in the UI.
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ provider: 'openai' })]);
    mocks.getDisabledProviders.mockResolvedValueOnce(['ghostcorp']);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');

    await selectProvider('ghostcorp');

    const providerToggle = await screen.findByTestId('provider-toggle-ghostcorp');
    expect(providerToggle).toHaveAttribute('aria-checked', 'false');

    fireEvent.click(providerToggle);

    await waitFor(() => expect(mocks.setProviderEnabled).toHaveBeenCalledWith('ghostcorp', true));
  });
});
