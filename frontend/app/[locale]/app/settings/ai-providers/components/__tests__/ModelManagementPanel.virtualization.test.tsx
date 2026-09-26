// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import { afterEach, describe, expect, it, vi } from 'vitest';
import React from 'react';
import { act, cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';

/**
 * The Models tab lists the whole catalogue (several hundred rows, each with selects, inputs
 * and a drag handle). Mounting every row at once is what made the tab slow to open, so the
 * list is virtualised. That takes away the long drag (the rows in between are not mounted),
 * which is why the rank can now be typed. These tests pin both halves.
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

const catalogue = (n: number) =>
  Array.from({ length: n }, (_, i) =>
    buildModel({ id: `model-${i + 1}`, name: `Model ${i + 1}`, displayOrder: i + 1 }),
  );

/** The ranking write the panel sent, as `provider:id` in rank order. */
function sentOrder(): string[] {
  const [rankings] = mocks.bulkUpdateRankings.mock.calls[0];
  return [...rankings]
    .sort((a: { ranking: number }, b: { ranking: number }) => a.ranking - b.ranking)
    .map((r: { provider: string; modelId: string }) => `${r.provider}:${r.modelId}`);
}

async function typeRank(modelId: string, value: string, key = 'Enter') {
  fireEvent.click(await screen.findByTestId(`model-rank-openai-${modelId}`));
  const input = screen.getByTestId(`model-rank-input-openai-${modelId}`);
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key });
}

describe('ModelManagementPanel - virtualised model list', () => {
  it('mounts only the rows near the viewport, not the whole catalogue', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(300));

    render(<ModelManagementPanel t={t} />);

    await screen.findByTestId('model-row-openai-model-1');
    const mounted = screen.getAllByTestId(/^model-row-/);
    // Before the virtualisation all 300 were in the DOM at once.
    expect(mounted.length).toBeGreaterThan(0);
    // jsdom's 768 px viewport: ~7 rows in view at the estimated height, plus the overscan
    // (8) on each side at most.
    expect(mounted.length).toBeLessThanOrEqual(7 + 2 * 8);
    // The list still knows its full size (what a screen reader announces).
    expect(screen.getAllByRole('listitem')[0]).toHaveAttribute('aria-setsize', '300');
    expect(screen.queryByTestId('model-row-openai-model-300')).toBeNull();
  });

  it('still reaches a row far down the list through the search', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(300));

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-row-openai-model-1');

    fireEvent.change(screen.getByTestId('model-search'), { target: { value: 'model-287' } });

    expect(await screen.findByTestId('model-row-openai-model-287')).toBeInTheDocument();
    // Its rank is its place in the whole list, not "1" in the filtered view.
    expect(screen.getByTestId('model-rank-openai-model-287')).toHaveTextContent('287');
  });
});

describe('ModelManagementPanel - typed rank', () => {
  it('moves the model to the typed position and saves the whole order', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(5));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await typeRank('model-4', '1');

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    expect(sentOrder()).toEqual([
      'openai:model-4', 'openai:model-1', 'openai:model-2', 'openai:model-3', 'openai:model-5',
    ]);
    // Chat tab = the legacy global ranking write, no category.
    expect(mocks.bulkUpdateRankings.mock.calls[0][1]).toBeUndefined();
  });

  it('commits on blur, the way a click elsewhere ends the edit', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(4));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId('model-rank-openai-model-3'));
    const input = screen.getByTestId('model-rank-input-openai-model-3');
    fireEvent.change(input, { target: { value: '2' } });
    fireEvent.blur(input);

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    expect(sentOrder()).toEqual(['openai:model-1', 'openai:model-3', 'openai:model-2', 'openai:model-4']);
  });

  it('clamps a rank below 1 to the first position', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(4));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await typeRank('model-3', '-5');

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    expect(sentOrder()[0]).toBe('openai:model-3');
  });

  /*
   * Chromium blurs an input that is removed while focused, and React runs that blur with
   * the handlers of the last render. Dispatching both events inside one act() reproduces
   * it: the input is still mounted when the blur arrives, as in the browser.
   */
  it('does not move the model when Escape is followed by the unmount blur', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(4));

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId('model-rank-openai-model-3'));
    const input = screen.getByTestId('model-rank-input-openai-model-3');
    fireEvent.change(input, { target: { value: '1' } });
    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
    });

    expect(await screen.findByTestId('model-rank-openai-model-3')).toHaveTextContent('3');
    expect(mocks.bulkUpdateRankings).not.toHaveBeenCalled();
  });

  it('saves once when Enter is followed by the unmount blur', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(4));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    fireEvent.click(await screen.findByTestId('model-rank-openai-model-3'));
    const input = screen.getByTestId('model-rank-input-openai-model-3');
    fireEvent.change(input, { target: { value: '1' } });
    act(() => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      input.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
    });

    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledTimes(2));
    expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1);
  });

  it('scrolls the list to the row it just moved, so the move is visible', async () => {
    const scrollTo = vi.fn();
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: scrollTo });
    try {
      mocks.getEffectiveModels.mockResolvedValue(catalogue(300));
      mocks.bulkUpdateRankings.mockResolvedValue(undefined);

      render(<ModelManagementPanel t={t} />);
      await typeRank('model-2', '250');

      await waitFor(() => expect(scrollTo).toHaveBeenCalled());
      // Row 250 at the estimated height sits far below the first screen.
      const { top } = scrollTo.mock.calls.at(-1)![0];
      expect(top).toBeGreaterThan(1000);
    } finally {
      delete (HTMLElement.prototype as { scrollTo?: unknown }).scrollTo;
    }
  });

  it('says the save failed when the ranking write is refused', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(3));
    mocks.bulkUpdateRankings.mockRejectedValue(new Error('boom'));

    render(<ModelManagementPanel t={t} />);
    await typeRank('model-3', '1');

    expect(await screen.findByText('modelConfig.saveError')).toBeInTheDocument();
  });

  it('clamps a rank past the end to the last position', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(5));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await typeRank('model-2', '999');

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    expect(sentOrder().at(-1)).toBe('openai:model-2');
  });

  it('writes to the category sidecar on a non-chat tab', async () => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(3));
    mocks.bulkUpdateRankings.mockResolvedValue(undefined);

    render(<ModelManagementPanel t={t} />);
    await screen.findByTestId('model-rank-openai-model-3');
    fireEvent.click(screen.getByText('modelConfig.category.browser_agent.label'));
    await waitFor(() => expect(mocks.getEffectiveModels).toHaveBeenCalledWith('browser_agent'));
    await typeRank('model-3', '1');

    await waitFor(() => expect(mocks.bulkUpdateRankings).toHaveBeenCalledTimes(1));
    expect(mocks.bulkUpdateRankings.mock.calls[0][1]).toBe('browser_agent');
  });

  it.each([
    ['the same rank', '2', 'Enter'],
    ['something that is not a number', 'abc', 'Enter'],
    ['Escape', '1', 'Escape'],
  ])('writes nothing for %s', async (_label, value, key) => {
    mocks.getEffectiveModels.mockResolvedValue(catalogue(5));

    render(<ModelManagementPanel t={t} />);
    await typeRank('model-2', value, key);

    // Back to the plain rank, with no write.
    expect(await screen.findByTestId('model-rank-openai-model-2')).toHaveTextContent('2');
    expect(mocks.bulkUpdateRankings).not.toHaveBeenCalled();
  });
});
