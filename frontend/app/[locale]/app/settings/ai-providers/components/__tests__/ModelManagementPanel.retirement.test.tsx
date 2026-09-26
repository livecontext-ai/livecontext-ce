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

const OLD = buildModel({ id: 'gpt-3.5', name: 'GPT-3.5', displayOrder: 2, releaseDate: '2023-03-01' });
const NEW = buildModel({ id: 'gpt-5', name: 'GPT-5', displayOrder: 1, releaseDate: '2025-08-07' });
const UNDATED = buildModel({ id: 'mystery', name: 'Mystery', displayOrder: 3 });

const RETIRED = [
  {
    provider: 'openai',
    modelId: 'gpt-4-0314',
    displayName: 'GPT-4 (0314)',
    providerKind: 'cloud',
    retiredAt: '2026-09-20T10:00:00Z',
    retiredBy: '1',
    releaseDate: '2023-03-14',
    deprecationDate: null,
  },
  {
    provider: 'anthropic',
    modelId: 'claude-2',
    displayName: 'Claude 2',
    providerKind: 'cloud',
    retiredAt: '2026-09-19T10:00:00Z',
    retiredBy: null,
    releaseDate: null,
    deprecationDate: null,
  },
];

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  mocks.getEffectiveModels.mockResolvedValue([NEW, OLD, UNDATED]);
  mocks.listRetiredModels.mockResolvedValue([]);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  confirmSpy.mockRestore();
});

describe('ModelManagementPanel - retire', () => {
  it('retires one row after a confirmation that explains what retiring does', async () => {
    mocks.retireModels.mockResolvedValue({ retired: 1 });
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('model-retire-openai-gpt-3.5'));

    await waitFor(() =>
      expect(mocks.retireModels).toHaveBeenCalledWith([{ provider: 'openai', modelId: 'gpt-3.5' }]),
    );
    expect(confirmSpy).toHaveBeenCalledWith('modelConfig.retire.confirmOne:{"name":"GPT-3.5"}');
    // The list is re-read (without the retired row), the caches cleared, the Retired list too.
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(2));
    expect(mocks.clearModelsCache).toHaveBeenCalled();
    await waitFor(() => expect(mocks.listRetiredModels).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('modelConfig.retire.doneTitle:{"count":"1"}')).toBeInTheDocument();
  });

  it('does nothing when the confirmation is declined', async () => {
    confirmSpy.mockReturnValue(false);
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('model-retire-openai-gpt-3.5'));

    expect(confirmSpy).toHaveBeenCalled();
    expect(mocks.retireModels).not.toHaveBeenCalled();
  });

  it('retires every selected row from the bulk bar in one request', async () => {
    mocks.retireModels.mockResolvedValue({ retired: 2 });
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('model-select-openai-gpt-3.5'));
    fireEvent.click(screen.getByTestId('model-select-openai-mystery'));
    const bulk = screen.getByTestId('bulk-retire');
    expect(bulk).toHaveTextContent('modelConfig.retire.bulk:{"count":"2"}');
    fireEvent.click(bulk);

    await waitFor(() => expect(mocks.retireModels).toHaveBeenCalledTimes(1));
    expect(confirmSpy).toHaveBeenCalledWith('modelConfig.retire.confirmMany:{"count":"2"}');
    expect(mocks.retireModels.mock.calls[0][0]).toEqual(
      expect.arrayContaining([
        { provider: 'openai', modelId: 'gpt-3.5' },
        { provider: 'openai', modelId: 'mystery' },
      ]),
    );
    expect(mocks.retireModels.mock.calls[0][0]).toHaveLength(2);
    // The retired rows are no longer ticked, so the bulk bar goes away.
    await waitFor(() => expect(screen.queryByTestId('bulk-bar')).not.toBeInTheDocument());
  });

  it('shows the server reason when the retire request is refused', async () => {
    mocks.retireModels.mockRejectedValue(new Error('models must be a non-empty list of {provider, modelId}'));
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('model-retire-openai-gpt-3.5'));

    expect(await screen.findByText('models must be a non-empty list of {provider, modelId}')).toBeInTheDocument();
    expect(screen.getByText('modelConfig.retire.error')).toBeInTheDocument();
  });
});

describe('ModelManagementPanel - retired models section', () => {
  it('lists the retired models with their dates and restores one row', async () => {
    mocks.listRetiredModels.mockResolvedValue(RETIRED);
    mocks.restoreModels.mockResolvedValue({ restored: 1 });
    render(<ModelManagementPanel t={t} />);

    const toggle = await screen.findByTestId('retired-models-toggle');
    await waitFor(() => expect(toggle).toHaveTextContent('modelConfig.retired.titleWithCount:{"count":"2"}'));
    fireEvent.click(toggle);

    const row = screen.getByTestId('retired-row-openai-gpt-4-0314');
    expect(row).toHaveTextContent('GPT-4 (0314)');
    expect(row).toHaveTextContent('openai / gpt-4-0314');
    expect(row).toHaveTextContent('modelConfig.retired.retiredOn');
    expect(row).toHaveTextContent('modelConfig.releasedOn');
    // No release date known: the line is omitted, not printed as a placeholder.
    expect(screen.getByTestId('retired-row-anthropic-claude-2')).not.toHaveTextContent('modelConfig.releasedOn');

    mocks.listRetiredModels.mockResolvedValue([RETIRED[1]]);
    fireEvent.click(within(row).getByTestId('retired-restore-openai-gpt-4-0314'));

    await waitFor(() =>
      expect(mocks.restoreModels).toHaveBeenCalledWith([{ provider: 'openai', modelId: 'gpt-4-0314' }]),
    );
    // The toast says the model is back DISABLED; both lists are re-read.
    expect(await screen.findByText('modelConfig.retired.restoredMessage')).toBeInTheDocument();
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId('retired-row-openai-gpt-4-0314')).not.toBeInTheDocument());
  });

  it('restores every selected retired model in one request', async () => {
    mocks.listRetiredModels.mockResolvedValue(RETIRED);
    mocks.restoreModels.mockResolvedValue({ restored: 2 });
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('retired-models-toggle'));
    fireEvent.click(await screen.findByTestId('retired-select-all'));
    fireEvent.click(screen.getByTestId('retired-restore-selected'));

    await waitFor(() =>
      expect(mocks.restoreModels).toHaveBeenCalledWith([
        { provider: 'openai', modelId: 'gpt-4-0314' },
        { provider: 'anthropic', modelId: 'claude-2' },
      ]),
    );
  });

  it('shows the server reason when a restore is refused', async () => {
    mocks.listRetiredModels.mockResolvedValue(RETIRED);
    mocks.restoreModels.mockRejectedValue(new Error('Model is not retired: openai/gpt-4-0314'));
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('retired-models-toggle'));
    fireEvent.click(await screen.findByTestId('retired-restore-openai-gpt-4-0314'));

    expect(await screen.findByText('Model is not retired: openai/gpt-4-0314')).toBeInTheDocument();
    expect(screen.getByText('modelConfig.retired.restoreError')).toBeInTheDocument();
  });

  it('shows an empty state when nothing is retired', async () => {
    render(<ModelManagementPanel t={t} />);

    const toggle = await screen.findByTestId('retired-models-toggle');
    await waitFor(() => expect(toggle).toHaveTextContent('modelConfig.retired.titleWithCount:{"count":"0"}'));
    fireEvent.click(toggle);

    expect(screen.getByTestId('retired-models-empty')).toBeInTheDocument();
  });

  it('says the list could not be read instead of claiming it is empty', async () => {
    mocks.listRetiredModels.mockRejectedValue(new Error('boom'));
    render(<ModelManagementPanel t={t} />);

    fireEvent.click(await screen.findByTestId('retired-models-toggle'));

    expect(await screen.findByTestId('retired-models-error')).toBeInTheDocument();
    expect(screen.queryByTestId('retired-models-empty')).not.toBeInTheDocument();
  });
});

describe('ModelManagementPanel - release-date order', () => {
  function rowOrder(): string[] {
    return screen
      .getAllByTestId(/^model-row-/)
      .map((el) => el.getAttribute('data-testid')!.replace('model-row-openai-', ''));
  }

  it('orders oldest release first, newest first, and keeps undated models last', async () => {
    // Rank order chosen so that all three orders differ.
    mocks.getEffectiveModels.mockResolvedValue([OLD, UNDATED, NEW]);
    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-gpt-5');
    expect(rowOrder()).toEqual(['gpt-3.5', 'mystery', 'gpt-5']);
    // The release date only shows while sorting by it.
    expect(screen.queryByTestId('model-release-openai-gpt-5')).not.toBeInTheDocument();

    fireEvent.click(within(screen.getByTestId('sort-order')).getByTestId('select-item-releaseAsc'));
    await waitFor(() => expect(rowOrder()).toEqual(['gpt-3.5', 'gpt-5', 'mystery']));
    expect(screen.getByTestId('model-release-openai-mystery')).toHaveTextContent('modelConfig.releaseUnknown');

    fireEvent.click(within(screen.getByTestId('sort-order')).getByTestId('select-item-releaseDesc'));
    await waitFor(() => expect(rowOrder()).toEqual(['gpt-5', 'gpt-3.5', 'mystery']));

    fireEvent.click(within(screen.getByTestId('sort-order')).getByTestId('select-item-rank'));
    await waitFor(() => expect(rowOrder()).toEqual(['gpt-3.5', 'mystery', 'gpt-5']));
  });
});
