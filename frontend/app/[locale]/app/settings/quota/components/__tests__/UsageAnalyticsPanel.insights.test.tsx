// @vitest-environment jsdom
/**
 * What the Usage Analytics panel tells a reader beyond the chart: the key figures of the period
 * compared with the one before, how long the balance lasts, and where the spend went by type and
 * by model. Rendered against the real English dictionary so a missing key shows as a failure.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const getAnalytics = vi.hoisted(() => vi.fn());
const format = vi.hoisted(() => ({ isCeMode: false }));

vi.mock('@/lib/api', () => ({ quotaApi: { getAnalytics } }));
// formatCost's real edition switch is a build-time constant, so this stand-in reproduces its two
// shapes: credits with 2 decimals on cloud, dollars with sub-cent precision in CE.
vi.mock('@/lib/format-cost', () => ({
  get isCeMode() { return format.isCeMode; },
  creditsToUsd: (c: number) => c / 1000,
  formatCost: (c: number) => {
    if (!format.isCeMode) return c.toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const usd = c / 1000;
    return `$${usd.toLocaleString('en', { minimumFractionDigits: 2, maximumFractionDigits: usd > 0 && usd < 1 ? 4 : 2 })}`;
  },
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrgStore: () => null }));
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({
    models: [{ id: 'claude-sonnet-5', name: 'House Sonnet', provider: 'anthropic' }],
    providers: [], defaultModel: null, defaultProvider: null, isLoading: false, error: null, refresh: async () => {},
  }),
}));
// Recharts needs a measured container to draw; the stand-in exposes the series it was handed.
vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  const Chart = ({ data, children }: { data: unknown; children?: React.ReactNode }) => (
    <div data-testid="chart-data" data-points={JSON.stringify(data)}>{children}</div>
  );
  const Nothing = () => null;
  return {
    ResponsiveContainer: Passthrough, AreaChart: Chart, Area: Nothing, XAxis: Nothing,
    YAxis: Nothing, CartesianGrid: Nothing, Tooltip: Nothing, Legend: Nothing,
  };
});

import UsageAnalyticsPanel from '../UsageAnalyticsPanel';

const ANALYTICS = {
  dailyUsage: [
    { date: '2026-09-01', sourceType: 'AGENT_EXECUTION', count: 6, credits: 600, tokens: 12000 },
    { date: '2026-09-02', sourceType: 'CHAT_CONVERSATION', count: 4, credits: 300, tokens: 3000 },
    { date: '2026-09-02', sourceType: 'PLATFORM_MARKUP', count: 2, credits: 100, tokens: 0 },
  ],
  providers: ['anthropic'],
  models: ['claude-sonnet-5'],
  sourceTypes: ['AGENT_EXECUTION', 'CHAT_CONVERSATION', 'PLATFORM_MARKUP'],
  modelUsage: [
    { provider: 'anthropic', model: 'claude-sonnet-5', sourceType: 'AGENT_EXECUTION', count: 6, credits: 600, tokens: 12000 },
    { provider: 'anthropic', model: 'claude-sonnet-5', sourceType: 'CHAT_CONVERSATION', count: 4, credits: 300, tokens: 3000 },
    { provider: null, model: null, sourceType: 'PLATFORM_MARKUP', count: 2, credits: 100, tokens: 0 },
  ],
  previousModelUsage: [
    { provider: 'anthropic', model: 'claude-sonnet-5', sourceType: 'AGENT_EXECUTION', count: 8, credits: 800, tokens: 1 },
  ],
};

beforeEach(() => {
  format.isCeMode = false;
  getAnalytics.mockResolvedValue(ANALYTICS);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPanel(props: React.ComponentProps<typeof UsageAnalyticsPanel> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <UsageAnalyticsPanel {...props} />
    </NextIntlClientProvider>,
  );
}

const kpi = (label: string) =>
  within(screen.getByTestId('analytics-kpis')).getByText(label).closest('div.rounded-xl') as HTMLElement;

describe('Usage Analytics - key figures', () => {
  it('states the total spend and calls with their change against the previous period', async () => {
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    const total = kpi('Total Credits');
    expect(total).toHaveTextContent('1,000');
    // 1,000 now against 800 before.
    expect(within(total).getByTestId('kpi-change')).toHaveTextContent('+25%');
    const calls = kpi('Calls');
    expect(calls).toHaveTextContent('12');
    // 12 now against 8 before.
    expect(within(calls).getByTestId('kpi-change')).toHaveTextContent('+50%');
  });

  it('shows tokens, the average per call and the average per day', async () => {
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    expect(kpi('Tokens')).toHaveTextContent('15K');
    // 1,000 credits over 12 calls.
    expect(kpi('Avg. per call')).toHaveTextContent('83.33');
    // 1,000 credits over the default 30 days.
    expect(kpi('Avg. Daily')).toHaveTextContent('33.33');
    // Without a runway the peak has its own card, so it is not repeated under the average.
    expect(kpi('Avg. Daily')).not.toHaveTextContent(/Peak/);
    expect(kpi('Peak Day')).toHaveTextContent('600.00');
  });

  it('with a balance, says how long it lasts at this pace', async () => {
    renderPanel({ balance: 1000 });
    await screen.findByTestId('analytics-kpis');

    // 1,000 / 33.33 per day = 29.99, rounded DOWN: a runway never promises a day it does not have.
    expect(kpi('Balance lasts')).toHaveTextContent('~29 days');
    // The runway takes the peak card's place, so the peak moves under the average.
    expect(kpi('Avg. Daily')).toHaveTextContent(/Peak 600\.00 on/);
    expect(screen.queryByText('Peak Day')).toBeNull();
  });

  it('regression - under a filter, no runway: the whole wallet over a slice of the spend would be inflated', async () => {
    renderPanel({ balance: 1000 });
    await screen.findByTestId('analytics-kpis');
    expect(kpi('Balance lasts')).toBeInTheDocument();

    // Filter on the model: the pace shown is now only part of what drains the wallet.
    const table = screen.getByTestId('analytics-by-model');
    fireEvent.click(within(within(table).getAllByTestId('analytics-by-model-row')[0]).getByRole('button'));

    await waitFor(() => expect(within(screen.getByTestId('analytics-kpis')).queryByText('Balance lasts')).toBeNull());
    expect(within(screen.getByTestId('analytics-kpis')).getByText('Peak Day')).toBeInTheDocument();
  });

  it('without a balance, shows the peak day instead of inventing a runway', async () => {
    renderPanel({ balance: null });
    await screen.findByTestId('analytics-kpis');

    expect(screen.queryByText('Balance lasts')).toBeNull();
    expect(screen.getByText('Peak Day')).toBeInTheDocument();
  });

  it('shows no change where the server sent no comparison', async () => {
    getAnalytics.mockResolvedValue({ ...ANALYTICS, previousModelUsage: undefined });
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    expect(screen.queryAllByTestId('kpi-change')).toHaveLength(0);
  });
});

describe('Usage Analytics - where the spend went', () => {
  it('ranks the types, each with its share', async () => {
    renderPanel();
    const table = await screen.findByTestId('analytics-by-type');
    const rows = within(table).getAllByTestId('analytics-by-type-row');

    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining('Agent'),
      expect.stringContaining('Chat'),
      expect.stringContaining('Platform API Call'),
    ]);
    expect(rows[0]).toHaveTextContent('60%');
  });

  it('names the models as the model picker does, and keeps the spend with no model on its own row', async () => {
    renderPanel();
    const table = await screen.findByTestId('analytics-by-model');
    const rows = within(table).getAllByTestId('analytics-by-model-row');

    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('House Sonnet');
    expect(rows[0]).toHaveTextContent('90%');
    expect(rows[1]).toHaveTextContent('No model (API calls and tools)');
    // Nothing to filter on for a row with no model.
    expect(within(rows[1]).queryByRole('button')).toBeNull();
  });

  it('a model row filters the chart on that model, and a second click clears it', async () => {
    renderPanel();
    const table = await screen.findByTestId('analytics-by-model');
    const button = within(within(table).getAllByTestId('analytics-by-model-row')[0]).getByRole('button');

    fireEvent.click(button);
    await waitFor(() => expect(getAnalytics).toHaveBeenLastCalledWith(30, undefined, undefined, 'claude-sonnet-5', null, false));

    const again = within(within(await screen.findByTestId('analytics-by-model')).getAllByTestId('analytics-by-model-row')[0]).getByRole('button');
    expect(again).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(again);
    await waitFor(() => expect(getAnalytics).toHaveBeenLastCalledWith(30, undefined, undefined, undefined, null, false));
  });

  it('a type row filters the chart on that type', async () => {
    renderPanel();
    const table = await screen.findByTestId('analytics-by-type');
    fireEvent.click(within(within(table).getAllByTestId('analytics-by-type-row')[1]).getByRole('button'));

    await waitFor(() => expect(getAnalytics).toHaveBeenLastCalledWith(30, 'CHAT_CONVERSATION', undefined, undefined, null, false));
  });

  it('folds the models past the top eight into one line', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({
      provider: 'openai', model: `m-${i}`, sourceType: 'AGENT_EXECUTION', count: 1, credits: 100 - i, tokens: 0,
    }));
    getAnalytics.mockResolvedValue({ ...ANALYTICS, modelUsage: many });
    renderPanel();
    const rows = within(await screen.findByTestId('analytics-by-model')).getAllByTestId('analytics-by-model-row');

    expect(rows).toHaveLength(9);
    expect(rows[8]).toHaveTextContent('2 other models');
  });

  it('an older backend with no model breakdown still shows the type table alone', async () => {
    getAnalytics.mockResolvedValue({ ...ANALYTICS, modelUsage: undefined });
    renderPanel();
    await screen.findByTestId('analytics-by-type');

    expect(screen.queryByTestId('analytics-by-model')).toBeNull();
  });
});

describe('Usage Analytics - chart metric', () => {
  it('switches what the chart stacks, spend by default, without a new query', async () => {
    renderPanel();
    await screen.findByTestId('analytics-kpis');
    const points = () => JSON.parse(screen.getByTestId('chart-data').getAttribute('data-points')!);

    expect(screen.getByTestId('analytics-metric-credits')).toHaveAttribute('aria-pressed', 'true');
    expect(points()).toEqual([
      { date: '2026-09-01', AGENT_EXECUTION: 600, CHAT_CONVERSATION: 0, PLATFORM_MARKUP: 0 },
      { date: '2026-09-02', AGENT_EXECUTION: 0, CHAT_CONVERSATION: 300, PLATFORM_MARKUP: 100 },
    ]);

    fireEvent.click(screen.getByTestId('analytics-metric-calls'));
    expect(screen.getByTestId('analytics-metric-calls')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('analytics-metric-credits')).toHaveAttribute('aria-pressed', 'false');
    expect(points()[1]).toEqual({ date: '2026-09-02', AGENT_EXECUTION: 0, CHAT_CONVERSATION: 4, PLATFORM_MARKUP: 2 });

    fireEvent.click(screen.getByTestId('analytics-metric-tokens'));
    expect(points()[0]).toMatchObject({ AGENT_EXECUTION: 12000 });

    expect(getAnalytics).toHaveBeenCalledTimes(1);
  });

  it('in CE the spend is a cost in dollars', async () => {
    format.isCeMode = true;
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    expect(screen.getByTestId('analytics-metric-credits')).toHaveTextContent('Cost');
    // 1,000 credits = $1.
    expect(kpi('Total Cost')).toHaveTextContent('$1.00');
  });

  it('regression - in CE a sub-cent average per call keeps its digits instead of reading "$0"', async () => {
    format.isCeMode = true;
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    // 1,000 credits over 12 calls = 83.3 credits = $0.0833.
    expect(kpi('Avg. per call')).toHaveTextContent('$0.0833');
  });

  it('in CE the workflow node fee (free when self-hosted) is left out of every figure', async () => {
    format.isCeMode = true;
    getAnalytics.mockResolvedValue({
      ...ANALYTICS,
      dailyUsage: [...ANALYTICS.dailyUsage, { date: '2026-09-02', sourceType: 'WORKFLOW_NODE', count: 50, credits: 5000, tokens: 0 }],
      modelUsage: [...ANALYTICS.modelUsage, { provider: null, model: null, sourceType: 'WORKFLOW_NODE', count: 50, credits: 5000, tokens: 0 }],
    });
    renderPanel();
    await screen.findByTestId('analytics-kpis');

    expect(kpi('Total Cost')).toHaveTextContent('$1.00');
    expect(kpi('Calls')).toHaveTextContent('12');
    expect(within(screen.getByTestId('analytics-by-type')).getAllByTestId('analytics-by-type-row')).toHaveLength(3);
  });
});
