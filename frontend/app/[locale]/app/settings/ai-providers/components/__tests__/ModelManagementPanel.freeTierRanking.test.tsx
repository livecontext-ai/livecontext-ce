// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';

/**
 * The Free tier tab: the free tier's own ranking of the models opened to it.
 *
 * <p>It is a ranking and nothing else. Its order is read and written under the
 * `free_tier` category, so the Chat / Agent ranking a paid account is ordered by never
 * moves. Everything else on a row there is the GLOBAL value: the backend refuses a
 * per-category switch for this category (it would answer saved and change nothing), so a
 * switch on this tab that called the per-category endpoint would fail on every click.
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
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;

const entry = (id: string, displayOrder: number, over: Record<string, unknown> = {}) => ({
  id,
  name: id,
  provider: 'anthropic',
  displayOrder,
  enabled: true,
  tier: 'fast',
  providerKind: 'cloud' as const,
  freeTierEnabled: true,
  ...over,
});

/** What the backend answers for `?category=free_tier`: opened models, free-tier order. */
const FREE_TIER_LIST = [entry('haiku', 1), entry('flash', 2), entry('mini', 3)];
/** The global list the Chat / Agent tab opens on. */
const CHAT_LIST = [entry('opus', 1, { freeTierEnabled: false }), ...FREE_TIER_LIST];

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

async function openFreeTierTab() {
  mocks.getEffectiveModels.mockImplementation((category?: string) =>
    Promise.resolve(category === 'free_tier' ? FREE_TIER_LIST : CHAT_LIST));
  const rendered = render(<ModelManagementPanel t={t} />);
  await screen.findByTestId('model-row-anthropic-opus');
  fireEvent.click(rendered.container.querySelector('button[data-category-id="free_tier"]')!);
  await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenLastCalledWith('free_tier'));
  await waitFor(() => expect(screen.queryByTestId('model-row-anthropic-opus')).toBeNull());
  await screen.findByTestId('model-row-anthropic-haiku');
  return rendered;
}

describe('ModelManagementPanel - the Free tier tab', () => {
  it('reads its own list, which holds the opened models only', async () => {
    await openFreeTierTab();

    expect(screen.getByTestId('model-row-anthropic-flash')).toBeInTheDocument();
    expect(screen.getByTestId('model-row-anthropic-mini')).toBeInTheDocument();
    expect(screen.queryByTestId('model-row-anthropic-opus')).toBeNull();
  });

  it('writes a typed rank under the free_tier category, leaving the global ranking alone', async () => {
    mocks.bulkUpdateRankings.mockResolvedValue({});
    await openFreeTierTab();

    // mini is third: type it to first.
    fireEvent.click(screen.getByTestId('model-rank-anthropic-mini'));
    const input = await screen.findByTestId('model-rank-input-anthropic-mini');
    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    const [rankings, category] = mocks.bulkUpdateRankings.mock.calls[0];
    // The category is the whole point: without it this write would re-rank chat.
    expect(category).toBe('free_tier');
    // The FULL list of the tab, as the backend contract requires, renumbered from 1.
    expect(rankings).toEqual([
      { provider: 'anthropic', modelId: 'mini', ranking: 1 },
      { provider: 'anthropic', modelId: 'haiku', ranking: 2 },
      { provider: 'anthropic', modelId: 'flash', ranking: 3 },
    ]);
  });

  it('switches a model off through the GLOBAL flag, never the per-category one', async () => {
    mocks.saveOverride.mockResolvedValue({});
    await openFreeTierTab();

    fireEvent.click(screen.getByTestId('model-toggle-anthropic-haiku'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    expect(mocks.saveOverride.mock.calls[0][0]).toMatchObject({
      provider: 'anthropic',
      modelId: 'haiku',
      enabled: false,
    });
    // The backend refuses this category on the per-category endpoint.
    expect(mocks.setCategoryEnabled).not.toHaveBeenCalled();
  });

  it('re-reads the list when a model is closed to the free tier, so it leaves the tab', async () => {
    mocks.saveOverride.mockResolvedValue({});
    await openFreeTierTab();
    const readsBefore = mocks.getEffectiveModels.mock.calls.length;

    fireEvent.click(screen.getByTestId('model-free-tier-anthropic-flash'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'anthropic',
      modelId: 'flash',
      freeTierEnabled: false,
    }));
    await waitFor(() =>
      expect(mocks.getEffectiveModels.mock.calls.length).toBeGreaterThan(readsBefore));
    expect(mocks.getEffectiveModels).toHaveBeenLastCalledWith('free_tier');
  });

  it('does not offer "Add model": a model is added to the catalogue, on the global ranking', async () => {
    // Computed from this tab, the new model would take a global rank of 4 (three free-tier
    // ranks + 1) and land near the top of the order every paid account is listed by.
    mocks.getEffectiveModels.mockImplementation((category?: string) =>
      Promise.resolve(category === 'free_tier' ? FREE_TIER_LIST : CHAT_LIST));
    const { container } = render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-anthropic-opus');
    expect(screen.getByTestId('add-model')).toBeInTheDocument();

    fireEvent.click(container.querySelector('button[data-category-id="free_tier"]')!);
    await waitFor(() => expect(screen.queryByTestId('model-row-anthropic-opus')).toBeNull());
    await screen.findByTestId('model-row-anthropic-haiku');

    expect(screen.queryByTestId('add-model')).toBeNull();
  });

  it('bulk-disables a selection through the GLOBAL flag too', async () => {
    mocks.saveOverride.mockResolvedValue({});
    await openFreeTierTab();

    fireEvent.click(screen.getByTestId('model-select-anthropic-haiku'));
    fireEvent.click(await screen.findByTestId('bulk-disable'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalled());
    expect(mocks.saveOverride.mock.calls[0][0]).toMatchObject({
      provider: 'anthropic',
      modelId: 'haiku',
      enabled: false,
    });
    expect(mocks.setCategoryEnabled).not.toHaveBeenCalled();
  });

  it('shows the empty state when no model is open to the free tier', async () => {
    mocks.getEffectiveModels.mockImplementation((category?: string) =>
      Promise.resolve(category === 'free_tier' ? [] : CHAT_LIST));
    const { container } = render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-anthropic-opus');

    fireEvent.click(container.querySelector('button[data-category-id="free_tier"]')!);

    expect(await screen.findByText('modelConfig.noModels')).toBeInTheDocument();
    expect(screen.queryByTestId('model-row-anthropic-opus')).toBeNull();
  });

  it('does not re-read the list when the chip is toggled on the Chat / Agent tab', async () => {
    // There the row stays where it is, and a refetch would re-sort the list under the admin.
    mocks.getEffectiveModels.mockResolvedValue(CHAT_LIST);
    mocks.saveOverride.mockResolvedValue({});
    render(<ModelManagementPanel t={t} />);
    const chip = await screen.findByTestId('model-free-tier-anthropic-opus');
    const readsBefore = mocks.getEffectiveModels.mock.calls.length;

    fireEvent.click(chip);

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(mocks.clearModelsCache).toHaveBeenCalled());
    expect(mocks.getEffectiveModels.mock.calls.length).toBe(readsBefore);
  });
});
