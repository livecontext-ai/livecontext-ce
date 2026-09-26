// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

/**
 * The replacement a DISABLED model's existing runs execute on (V515). Agents, workflow nodes
 * and chat endpoints keep the model they stored; the backend swaps it at run time for the
 * pair chosen here, or for the platform default when none is. The control only matters while
 * the model is disabled, so it only appears then.
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
// button carrying its value (same mock as ModelManagementPanel.toggle.test.tsx).
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
// Cloud: the edition where CLI bridges are admin-only and must not be offered as replacements.
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string) => k;
const OPUS_48 = 'model-replacement-anthropic-claude-opus-4-8';

function model(over: Record<string, unknown> = {}) {
  return {
    id: 'claude-opus-4-8',
    name: 'Opus 4.8',
    provider: 'anthropic',
    displayOrder: 1,
    enabled: false,
    tier: 'top',
    providerKind: 'cloud' as const,
    ...over,
  };
}

const opus49 = model({ id: 'claude-opus-4-9', name: 'Opus 4.9', displayOrder: 2, enabled: true });

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - replacement of a disabled model', () => {
  it('is offered on a disabled row only, listing enabled models and never the model itself', async () => {
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49]);

    render(<ModelManagementPanel t={t} />);
    const control = await screen.findByTestId(OPUS_48);

    expect(screen.queryByTestId('model-replacement-anthropic-claude-opus-4-9')).not.toBeInTheDocument();
    expect(within(control).getByTestId('select-item-anthropic:claude-opus-4-9')).toBeInTheDocument();
    expect(within(control).queryByTestId('select-item-anthropic:claude-opus-4-8')).not.toBeInTheDocument();
  });

  it('does not offer an enabled but unconfigured model: at run time it would be skipped for the default', async () => {
    const unkeyed = model({ id: 'gpt-5', name: 'GPT-5', provider: 'openai', displayOrder: 3, enabled: true, available: false });
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49, unkeyed]);

    render(<ModelManagementPanel t={t} />);
    const control = await screen.findByTestId(OPUS_48);

    expect(within(control).queryByTestId('select-item-openai:gpt-5')).not.toBeInTheDocument();
    expect(within(control).getByTestId('select-item-anthropic:claude-opus-4-9')).toBeInTheDocument();
  });

  it('does not offer a CLI bridge on cloud: non-admin runs swapped onto it would be refused', async () => {
    const cli = model({ id: 'claude-opus-4-9', name: 'Opus CLI', provider: 'claude-code', displayOrder: 4, enabled: true, providerKind: 'bridge' });
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49, cli]);

    render(<ModelManagementPanel t={t} />);
    const control = await screen.findByTestId(OPUS_48);

    expect(within(control).queryByTestId('select-item-claude-code:claude-opus-4-9')).not.toBeInTheDocument();
  });

  it('is not shown on a category tab, whose rows carry a per-category flag the runtime swap never reads', async () => {
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49]);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId(OPUS_48);
    const tab = document.querySelector('[data-category-id="browser_agent"]') as HTMLElement | null;
    expect(tab).not.toBeNull();
    fireEvent.click(tab!);

    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent'));
    await waitFor(() => expect(screen.queryByTestId(OPUS_48)).not.toBeInTheDocument());
  });

  it('saves the chosen pair, and only that', async () => {
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(within(await screen.findByTestId(OPUS_48)).getByTestId('select-item-anthropic:claude-opus-4-9'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'anthropic',
      modelId: 'claude-opus-4-8',
      replacementProvider: 'anthropic',
      replacementModel: 'claude-opus-4-9',
    }));
  });

  it('keeps a model id that contains the separator intact (only the provider is cut off)', async () => {
    const ollama = model({ id: 'llama3:8b', name: 'Llama 3', provider: 'ollama', displayOrder: 3, enabled: true });
    mocks.getEffectiveModels.mockResolvedValue([model(), ollama]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(within(await screen.findByTestId(OPUS_48)).getByTestId('select-item-ollama:llama3:8b'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith(
      expect.objectContaining({ replacementProvider: 'ollama', replacementModel: 'llama3:8b' }),
    ));
  });

  it('platform default clears the pair with explicit nulls', async () => {
    mocks.getEffectiveModels.mockResolvedValue([
      model({ replacementProvider: 'anthropic', replacementModel: 'claude-opus-4-9' }),
      opus49,
    ]);
    mocks.saveOverride.mockResolvedValue({});

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(within(await screen.findByTestId(OPUS_48)).getByTestId('select-item-__platform_default__'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith({
      provider: 'anthropic',
      modelId: 'claude-opus-4-8',
      replacementProvider: null,
      replacementModel: null,
    }));
  });

  it('still shows a stored replacement that is no longer among the enabled models', async () => {
    // Otherwise the control would read "platform default" while the backend keeps
    // following the stored pair, which is a lie about what the runs execute on.
    mocks.getEffectiveModels.mockResolvedValue([
      model({ replacementProvider: 'openai', replacementModel: 'gpt-5' }),
      opus49,
    ]);

    render(<ModelManagementPanel t={t} />);

    expect(within(await screen.findByTestId(OPUS_48)).getByTestId('select-item-openai:gpt-5'))
      .toHaveTextContent('openai / gpt-5');
  });

  it('shows the refusal when the backend rejects the pair', async () => {
    mocks.getEffectiveModels.mockResolvedValue([model(), opus49]);
    mocks.saveOverride.mockRejectedValue(new Error('Unknown replacement model: anthropic:claude-opus-4-9'));

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(within(await screen.findByTestId(OPUS_48)).getByTestId('select-item-anthropic:claude-opus-4-9'));

    expect(await screen.findByText(/Unknown replacement model/)).toBeInTheDocument();
  });
});
