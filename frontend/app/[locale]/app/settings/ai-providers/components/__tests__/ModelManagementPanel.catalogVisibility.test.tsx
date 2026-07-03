// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * Admin Models panel now lists the FULL catalog (every provider, keyed or not)
 * so models can be ranked/priced before a key exists. These pin the two UI
 * consequences:
 *   1. a model the runtime can't yet serve (available=false, non-bridge) is
 *      still shown, with a "not configured" badge so it isn't confusing;
 *   2. the Browser Agent tab defaults to vision-only (a browser agent must SEE
 *      the page), and the toggle reveals the non-vision models.
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
    available: true,
    ...over,
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - full-catalog visibility + not-configured badge', () => {
  it('shows a keyless model (available=false) with a "not configured" badge', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'gpt-5', provider: 'openai', available: true }),
      buildModel({ id: 'sonar', name: 'Sonar', provider: 'perplexity', displayOrder: 2, available: false }),
    ]);

    render(<ModelManagementPanel t={t} />);

    // BOTH rows render - the keyless provider is no longer hidden.
    await screen.findByTestId('model-toggle-openai-gpt-5');
    expect(screen.getByTestId('model-toggle-perplexity-sonar')).toBeInTheDocument();
    // Exactly one "not configured" badge, for the unavailable row.
    expect(screen.getAllByText('modelConfig.notConfigured')).toHaveLength(1);
  });

  it('does NOT badge a bridge row as not-configured (bridges use their own availability)', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      buildModel({ id: 'claude-code-model', provider: 'claude-code', providerKind: 'bridge', available: false, bridgeAvailable: false }),
    ]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-claude-code-claude-code-model');
    expect(screen.queryByText('modelConfig.notConfigured')).not.toBeInTheDocument();
  });
});

describe('ModelManagementPanel - Browser Agent vision-only filter', () => {
  it('defaults to vision-only on the Browser Agent tab, and the toggle reveals non-vision models', async () => {
    const chat = [buildModel({ id: 'gpt-5', provider: 'openai', supportsVision: true })];
    const browser = [
      buildModel({ id: 'gpt-5', provider: 'openai', supportsVision: true }),
      buildModel({ id: 'o3-mini', name: 'o3-mini', provider: 'openai', displayOrder: 2, supportsVision: false }),
    ];
    mocks.getEffectiveModels.mockImplementation((cat?: string) =>
      Promise.resolve(cat === 'browser_agent' ? browser : chat));

    render(<ModelManagementPanel t={t} />);
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(1)); // chat mount

    // Switch to Browser Agent tab.
    fireEvent.click(screen.getByText('modelConfig.category.browser_agent.label'));
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent'));

    // Default: vision-only ON -> the non-vision model is hidden.
    await screen.findByTestId('model-toggle-openai-gpt-5');
    expect(screen.queryByTestId('model-toggle-openai-o3-mini')).not.toBeInTheDocument();
    expect(screen.getByTestId('browser-agent-vision-only')).toHaveAttribute('aria-pressed', 'true');

    // Toggle OFF -> the non-vision model appears.
    fireEvent.click(screen.getByTestId('browser-agent-vision-only'));
    await waitFor(() =>
      expect(screen.getByTestId('model-toggle-openai-o3-mini')).toBeInTheDocument());
    expect(screen.getByTestId('browser-agent-vision-only')).toHaveAttribute('aria-pressed', 'false');
  });

  it('has no vision toggle on the Chat tab', async () => {
    mocks.getEffectiveModels.mockResolvedValue([buildModel({ supportsVision: false })]);
    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-toggle-openai-gpt-5');
    // Chat tab: no vision filter, and a non-vision model is shown normally.
    expect(screen.queryByTestId('browser-agent-vision-only')).not.toBeInTheDocument();
  });
});
