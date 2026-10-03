// @vitest-environment jsdom
/**
 * The models list keeps its filters, its sort and its category tab in the address, so a
 * reload reopens it as it was.
 */
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const mocks = vi.hoisted(() => ({
  getEffectiveModels: vi.fn(),
  listExecutionLinks: vi.fn(),
  getDisabledProviders: vi.fn(),
}));

vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => (k: string) => (ns ? `${ns}.${k}` : k),
}));
vi.mock('@/lib/api/model-config.service', () => ({
  modelConfigService: {
    getEffectiveModels: mocks.getEffectiveModels,
    listExecutionLinks: mocks.listExecutionLinks,
    getDisabledProviders: mocks.getDisabledProviders,
  },
}));
// Radix Select needs real pointer events that jsdom does not give it: each item becomes a
// button carrying its value (same mock as ModelManagementPanel.unlisted.test.tsx).
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
vi.mock('@/hooks/useModels', () => ({ clearModelsCache: vi.fn() }));
vi.mock('@/lib/edition/edition', () => ({
  EDITION: 'cloud', IS_CE: false, IS_CLOUD: true, IS_MANAGED_CLOUD: true,
}));
vi.mock('../AddModelDialog', () => ({ default: () => null }));

import ModelManagementPanel from '../ModelManagementPanel';

const t = (k: string, values?: Record<string, string>) =>
  values ? `${k}(${Object.values(values).join(',')})` : k;

function model(over: Record<string, unknown> = {}) {
  return {
    id: 'gpt-5', name: 'GPT-5', provider: 'openai', displayOrder: 1, enabled: true, tier: 'top',
    providerKind: 'cloud' as const, ...over,
  };
}

const PAGE = '/en/app/settings/ai-providers';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

beforeEach(() => {
  mocks.listExecutionLinks.mockResolvedValue([]);
  mocks.getDisabledProviders.mockResolvedValue([]);
  mocks.getEffectiveModels.mockResolvedValue([
    model(),
    model({ id: 'gpt-4', name: 'GPT-4', displayOrder: 2, enabled: false, tier: 'mid' }),
    model({ id: 'claude-x', name: 'Claude X', provider: 'anthropic', displayOrder: 3, enabled: false }),
  ]);
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('ModelManagementPanel - view kept in the address', () => {
  it('opens with the filters, the search and the category the address carries', async () => {
    openAt('tab=models&category=browser_agent&status=off&provider=openai&q=gpt');
    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('model-row-openai-gpt-4')).toBeInTheDocument();
    expect(screen.queryByTestId('model-row-openai-gpt-5')).toBeNull();
    expect(screen.queryByTestId('model-row-anthropic-claude-x')).toBeNull();
    expect((screen.getByTestId('model-search') as HTMLInputElement).value).toBe('gpt');
    expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent');
  });

  it('writes each filter as it changes, keeping the tab', async () => {
    openAt('tab=models');
    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');

    fireEvent.click(within(screen.getByTestId('tier-filter')).getByTestId('select-item-mid'));
    expect(fakeFolderRouter.search()).toBe('tab=models&tier=mid');
    expect(screen.queryByTestId('model-row-openai-gpt-5')).toBeNull();

    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'gpt' } });
    await waitFor(() => expect(fakeFolderRouter.search()).toBe('tab=models&tier=mid&q=gpt'));

    fireEvent.click(screen.getByRole('button', { name: 'modelConfig.category.browser_agent.label' }));
    expect(fakeFolderRouter.search()).toBe('tab=models&tier=mid&q=gpt&category=browser_agent');
  });

  it('ignores a filter value the list does not offer', async () => {
    openAt('status=sideways&tier=galactic&sort=random&category=image_generation');
    render(<ModelManagementPanel t={t} />);

    expect(await screen.findByTestId('model-row-openai-gpt-5')).toBeInTheDocument();
    expect(screen.getByTestId('model-row-openai-gpt-4')).toBeInTheDocument();
    expect(mocks.getEffectiveModels).not.toHaveBeenCalledWith('image_generation');
  });
});
