// @vitest-environment jsdom
/**
 * The Usage Analytics panel keeps its period and its filters in the address, so a reload
 * asks for the same slice of the ledger.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const getAnalytics = vi.hoisted(() => vi.fn());
const t = vi.hoisted(() => Object.assign((key: string) => key, { has: () => false }));

vi.mock('next-intl', () => ({ useTranslations: () => t }));
vi.mock('@/lib/api', () => ({ quotaApi: { getAnalytics } }));
vi.mock('@/lib/format-cost', () => ({
  isCeMode: false,
  creditsToUsd: (c: number) => c / 1000,
  formatCost: (c: number) => String(c),
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrgStore: () => 'org-1' }));
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({
    models: [], providers: [], defaultModel: null, defaultProvider: null, isLoading: false, error: null,
    refresh: async () => {},
  }),
}));
vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  const Nothing = () => null;
  return {
    ResponsiveContainer: Passthrough, AreaChart: Passthrough, Area: Nothing, XAxis: Nothing,
    YAxis: Nothing, CartesianGrid: Nothing, Tooltip: Nothing, Legend: Nothing,
  };
});
// A select jsdom can drive: every option is a button that reports its value.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<(v: string) => void>(() => {});
  return {
    Select: ({ value, onValueChange, children }: {
      value?: string; onValueChange: (v: string) => void; children: React.ReactNode;
    }) => <Ctx.Provider value={onValueChange}><div data-select-value={value}>{children}</div></Ctx.Provider>,
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value, children }: { value: string; children: React.ReactNode }) => {
      const onValueChange = ReactModule.useContext(Ctx);
      return <button type="button" data-option={value} onClick={() => onValueChange(value)}>{children}</button>;
    },
  };
});

import UsageAnalyticsPanel from '../UsageAnalyticsPanel';

const ANALYTICS = {
  dailyUsage: [{ date: '2026-09-01', sourceType: 'AGENT_EXECUTION', count: 6, credits: 600, tokens: 12000 }],
  providers: ['anthropic', 'openai'],
  models: ['claude-sonnet-5'],
  sourceTypes: ['AGENT_EXECUTION', 'CHAT_CONVERSATION'],
  modelUsage: [],
  previousModelUsage: [],
};

const PAGE = '/en/app/settings/quota';

function openAt(query = '') {
  fakeFolderRouter.reset(PAGE);
  if (query) fakeFolderRouter.navigate(`${PAGE}?${query}`, 'replace');
}

beforeEach(() => {
  getAnalytics.mockReset().mockResolvedValue(ANALYTICS);
});
afterEach(cleanup);

describe('UsageAnalyticsPanel - view kept in the address', () => {
  it('asks for the period and the filters the address carries', async () => {
    openAt('period=90&source=AGENT_EXECUTION&provider=anthropic&model=claude-sonnet-5');
    const { container } = render(<UsageAnalyticsPanel />);

    await waitFor(() => expect(getAnalytics).toHaveBeenCalledWith(
      90, 'AGENT_EXECUTION', 'anthropic', 'claude-sonnet-5', 'org-1', false,
    ));
    expect(getAnalytics).not.toHaveBeenCalledWith(30, undefined, undefined, undefined, 'org-1', false);
    expect(container.querySelector('[data-select-value="90"]')).not.toBeNull();
  });

  it('writes the period and a filter as they change, leaving the history params alone', async () => {
    openAt('page=4&type=WEB_SEARCH');
    const { container } = render(<UsageAnalyticsPanel />);
    await waitFor(() => expect(container.querySelector('[data-option="openai"]')).not.toBeNull());

    fireEvent.click(container.querySelector('[data-option="7"]')!);
    expect(fakeFolderRouter.search()).toBe('page=4&type=WEB_SEARCH&period=7');

    fireEvent.click(container.querySelector('[data-option="openai"]')!);
    expect(fakeFolderRouter.search()).toBe('page=4&type=WEB_SEARCH&period=7&provider=openai');
    await waitFor(() => expect(getAnalytics).toHaveBeenLastCalledWith(7, undefined, 'openai', undefined, 'org-1', false));

    // Back to "all": spelled by absence.
    const providerSelect = container.querySelector('[data-select-value="openai"]')!;
    fireEvent.click(providerSelect.querySelector('[data-option="ALL"]')!);
    expect(fakeFolderRouter.search()).toBe('page=4&type=WEB_SEARCH&period=7');
  });

  it('falls back to 30 days on a period the select does not offer', async () => {
    openAt('period=365');
    render(<UsageAnalyticsPanel />);

    await waitFor(() => expect(getAnalytics).toHaveBeenCalledWith(30, undefined, undefined, undefined, 'org-1', false));
  });
});
