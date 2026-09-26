// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

/**
 * V533 model retirement in the admin Models panel: retire from a row or from the bulk bar
 * (always after a confirmation that says what retiring means), the Retired models section
 * with per-row and bulk restore, the server's own refusal reason in the toast, and the
 * release-date order that helps find old models.
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
  listRetiredModels: vi.fn(),
  retireModels: vi.fn(),
  restoreModels: vi.fn(),
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
    listRetiredModels: mocks.listRetiredModels,
    retireModels: mocks.retireModels,
    restoreModels: mocks.restoreModels,
  },
}));
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

const DISABLED = buildModel({ id: 'gpt-3.5', name: 'GPT-3.5', enabled: false, rateLimitRpm: 60 });

beforeEach(() => {
  mocks.getEffectiveModels.mockResolvedValue([DISABLED]);
  mocks.listRetiredModels.mockResolvedValue([]);
  mocks.saveOverride.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

async function openEditor() {
  render(<ModelManagementPanel t={t} />);
  const row = await screen.findByTestId('model-row-openai-gpt-3.5');
  const trigger = within(row).getByText('RPM').closest('button') as HTMLButtonElement;
  fireEvent.click(trigger);
  const editor = await screen.findByTestId('rate-limit-editor-openai-gpt-3.5');
  return { row, editor };
}

describe('ModelManagementPanel - rate-limit editor', () => {
  it('opens OUTSIDE the faded row of a disabled model, so nothing can cover or fade it', async () => {
    const { row, editor } = await openEditor();

    expect(row.className).toContain('opacity-40');
    expect(row.contains(editor)).toBe(false);
    expect(within(editor).getAllByRole('spinbutton')).toHaveLength(2);
  });

  it('saves the new RPM on OK', async () => {
    const { editor } = await openEditor();
    const [, rpmInput] = within(editor).getAllByRole('spinbutton');

    fireEvent.change(rpmInput, { target: { value: '120' } });
    fireEvent.click(within(editor).getByText('OK'));

    await waitFor(() => expect(mocks.saveOverride).toHaveBeenCalledWith(
      expect.objectContaining({ provider: 'openai', modelId: 'gpt-3.5', rateLimitRpm: 120 }),
    ));
    await waitFor(() => expect(screen.queryByTestId('rate-limit-editor-openai-gpt-3.5')).toBeNull());
  });

  it('Escape closes it without saving', async () => {
    const { editor } = await openEditor();
    const [, rpmInput] = within(editor).getAllByRole('spinbutton');
    fireEvent.change(rpmInput, { target: { value: '999' } });

    fireEvent.keyDown(rpmInput, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByTestId('rate-limit-editor-openai-gpt-3.5')).toBeNull());
    expect(mocks.saveOverride).not.toHaveBeenCalled();
  });
});
