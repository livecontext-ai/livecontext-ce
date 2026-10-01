// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

/**
 * V554: a model has three states, on (listed), unlisted (still available, no longer offered)
 * and off. The row shows them with the switch and the eye button beside it, and every write
 * carries BOTH flags so a state the row cannot display never survives a change. Also the "New"
 * badge and filter, for the models the catalogue just gained.
 */

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  saveOverride: vi.fn(),
  setCategoryEnabled: vi.fn(),
  bulkUpdateRankings: vi.fn(),
  deleteOverride: vi.fn(),
  resetAll: vi.fn(),
  clearModelsCache: vi.fn(),
  listExecutionLinks: vi.fn().mockResolvedValue([]),
  saveExecutionLink: vi.fn(),
  deleteExecutionLink: vi.fn(),
  getDisabledProviders: vi.fn().mockResolvedValue([]),
  setProviderEnabled: vi.fn().mockResolvedValue(undefined),
}));

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
// Radix Select needs real pointer events that jsdom does not give it: each item becomes a
// button carrying its value (same mock as ModelManagementPanel.replacement.test.tsx).
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
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string, values?: Record<string, string>) =>
  values ? `${k}(${Object.values(values).join(',')})` : k;

function model(over: Record<string, unknown> = {}) {
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

const listed = model();
const unlisted = model({ id: 'gpt-4o', name: 'GPT-4o', displayOrder: 2, unlisted: true });
const off = model({ id: 'gpt-4', name: 'GPT-4', displayOrder: 3, enabled: false });

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

const eye = (id: string) => screen.findByTestId(`model-listing-openai-${id}`);

describe('ModelManagementPanel - the three states (V554)', () => {
  it('the row says which state it is in', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted, off]);

    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('model-row-openai-gpt-5')).toHaveAttribute('data-listing', 'listed');
    expect(screen.getByTestId('model-row-openai-gpt-4o')).toHaveAttribute('data-listing', 'unlisted');
    expect(screen.getByTestId('model-row-openai-gpt-4')).toHaveAttribute('data-listing', 'off');
    expect(await eye('gpt-4o')).toHaveAttribute('aria-pressed', 'true');
    expect(await eye('gpt-5')).toHaveAttribute('aria-pressed', 'false');
  });

  it('the eye unlists a listed model WITHOUT writing enabled (no "cannot enable an unpriced model" on a click that enables nothing)', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await eye('gpt-5'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-5', unlisted: true,
    }));
    expect(screen.getByTestId('model-row-openai-gpt-5')).toHaveAttribute('data-listing', 'unlisted');
    await waitFor(() => expect(mocks.clearModelsCache).toHaveBeenCalled());
  });

  it('the eye brings an OFF model back unlisted in one save, never exposing it in the pickers on the way', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, off]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await eye('gpt-4'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-4', enabled: true, unlisted: true,
    });
  });

  it('the eye lists an unlisted model again', async () => {
    mocks.getEffectiveModels.mockResolvedValue([unlisted]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await eye('gpt-4o'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-4o', unlisted: false,
    }));
  });

  it('switching an unlisted model off clears the flag, so switching it on again lists it', async () => {
    mocks.getEffectiveModels.mockResolvedValue([unlisted]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId('model-toggle-openai-gpt-4o'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'openai', modelId: 'gpt-4o', enabled: false, unlisted: false,
    }));
    fireEvent.click(screen.getByTestId('model-toggle-openai-gpt-4o'));
    // The flag is already cleared: on the way back only `enabled` moves.
    await waitFor(() => expect(mocks.saveOverride).toHaveBeenLastCalledWith({
      provider: 'openai', modelId: 'gpt-4o', enabled: true,
    }));
    expect(screen.getByTestId('model-row-openai-gpt-4o')).toHaveAttribute('data-listing', 'listed');
  });

  it('a refused save puts the row back and shows the server reason', async () => {
    mocks.getEffectiveModels.mockResolvedValue([off]);
    mocks.saveOverride.mockRejectedValue(new Error('Model openai/gpt-4 has no price'));

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await eye('gpt-4'));

    expect(await screen.findByText(/has no price/)).toBeInTheDocument();
    expect(screen.getByTestId('model-row-openai-gpt-4')).toHaveAttribute('data-listing', 'off');
  });

  it('the eye is a Chat-tab control: a category tab only shows the state', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted]);

    render(<ModelManagementPanel t={t} />);
    await eye('gpt-4o');
    fireEvent.click(screen.getByText('modelConfig.category.browser_agent.label'));
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent'));
    await screen.findByTestId('model-toggle-openai-gpt-4o');

    expect(screen.queryByTestId('model-listing-openai-gpt-4o')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-unlisted-openai-gpt-4o')).toBeInTheDocument();
  });
});

describe('ModelManagementPanel - replacement while unlisted', () => {
  it('shows a stored replacement as paused on an unlisted row, still editable', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      listed,
      model({ id: 'gpt-4o', name: 'GPT-4o', displayOrder: 2, unlisted: true,
        replacementProvider: 'openai', replacementModel: 'gpt-5' }),
    ]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    const control = await screen.findByTestId('model-replacement-openai-gpt-4o');

    expect(control).toHaveAttribute('data-paused', 'true');
    expect(control).toHaveAttribute('title', 'modelConfig.replacementPausedTooltip');
    expect(within(control).getByText('modelConfig.replacementPaused')).toBeInTheDocument();
    fireEvent.click(within(control).getByTestId('select-item-__platform_default__'));
    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith(
      expect.objectContaining({ modelId: 'gpt-4o', replacementProvider: null, replacementModel: null }),
    ));
  });

  it('shows nothing on an unlisted row with no replacement ("platform default, paused" says nothing)', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted]);

    render(<ModelManagementPanel t={t} />);
    await eye('gpt-4o');

    expect(screen.queryByTestId('model-replacement-openai-gpt-4o')).not.toBeInTheDocument();
  });

  it('an OFF row keeps the live (not paused) control', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, off]);

    render(<ModelManagementPanel t={t} />);
    const control = await screen.findByTestId('model-replacement-openai-gpt-4');

    expect(control).not.toHaveAttribute('data-paused');
    expect(control).toHaveAttribute('title', 'modelConfig.replacementTooltip');
  });
});

describe('ModelManagementPanel - state filter and New', () => {
  const fresh = model({ id: 'gpt-5.5', name: 'GPT-5.5', displayOrder: 4, isNew: true, addedAt: '2026-09-28T10:00:00Z' });

  it('filters on each of the three states', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted, off]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');

    fireEvent.click(screen.getByTestId('select-item-unlisted'));
    await waitFor(() => expect(screen.queryByTestId('model-row-openai-gpt-5')).not.toBeInTheDocument());
    expect(screen.getByTestId('model-row-openai-gpt-4o')).toBeInTheDocument();
    expect(screen.queryByTestId('model-row-openai-gpt-4')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('select-item-on'));
    await waitFor(() => expect(screen.getByTestId('model-row-openai-gpt-5')).toBeInTheDocument());
    expect(screen.queryByTestId('model-row-openai-gpt-4o')).not.toBeInTheDocument();
  });

  it('badges a new model with the date it arrived, and filters on it', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, fresh]);

    render(<ModelManagementPanel t={t} />);
    const badge = await screen.findByTestId('model-new-openai-gpt-5.5');

    expect(badge).toHaveTextContent('modelConfig.newBadge');
    expect(badge.getAttribute('title')).toMatch(/^modelConfig\.newBadgeTooltip\(.+\)$/);
    expect(screen.queryByTestId('model-new-openai-gpt-5')).not.toBeInTheDocument();

    const chip = screen.getByTestId('new-filter');
    expect(chip).toHaveTextContent('modelConfig.filterNew(1)');
    fireEvent.click(chip);
    await waitFor(() => expect(screen.queryByTestId('model-row-openai-gpt-5')).not.toBeInTheDocument());
    expect(screen.getByTestId('model-row-openai-gpt-5.5')).toBeInTheDocument();
    expect(chip).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('clear-filters'));
    await waitFor(() => expect(screen.getByTestId('model-row-openai-gpt-5')).toBeInTheDocument());
  });

  it('offers no New chip when nothing is new', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');

    expect(screen.queryByTestId('new-filter')).not.toBeInTheDocument();
  });
});

describe('ModelManagementPanel - bulk unlist', () => {
  it('unlists the ticked rows, each from its own state', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, off]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');
    fireEvent.click(screen.getByTestId('model-select-openai-gpt-5'));
    fireEvent.click(screen.getByTestId('model-select-openai-gpt-4'));
    fireEvent.click(await screen.findByTestId('bulk-unlist'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(2));
    // Per row, from its own state: a listed row only changes the flag, an off one is switched on.
    expect(mocks.saveOverride).toHaveBeenCalledWith({ provider: 'openai', modelId: 'gpt-5', unlisted: true });
    expect(mocks.saveOverride).toHaveBeenCalledWith({ provider: 'openai', modelId: 'gpt-4', enabled: true, unlisted: true });
  });
});

describe('ModelManagementPanel - bulk Enable and unlisted rows', () => {
  it('switches the OFF rows on and leaves the rows already on as they are, an unlisted one included', async () => {
    mocks.getEffectiveModels.mockResolvedValue([listed, unlisted, off]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');
    fireEvent.click(screen.getByTestId('model-select-all'));
    fireEvent.click(await screen.findByTestId('bulk-enable'));

    await waitFor(() => expect(mocks.clearModelsCache).toHaveBeenCalled());
    expect(mocks.saveOverride).toHaveBeenCalledTimes(1);
    expect(mocks.saveOverride).toHaveBeenCalledWith({ provider: 'openai', modelId: 'gpt-4', enabled: true });
  });
});
