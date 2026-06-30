// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';

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
}));

vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    saveOverride: mocks.saveOverride,
    setCategoryEnabled: mocks.setCategoryEnabled,
    bulkUpdateRankings: mocks.bulkUpdateRankings,
    deleteOverride: mocks.deleteOverride,
    resetAll: mocks.resetAll,
  },
}));
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: mocks.clearModelsCache }));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;

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
    expect(toggle).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(toggle);

    // Optimistic flip is visible immediately + persisted as enabled=false.
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-pressed', 'false'),
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
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-pressed', 'false'),
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
    // Rolled back to enabled (aria-pressed true) after the rejected save.
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-gpt-5')).toHaveAttribute('aria-pressed', 'true'),
    );
    // Never refetched, even on the error path.
    expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1);
  });
});
