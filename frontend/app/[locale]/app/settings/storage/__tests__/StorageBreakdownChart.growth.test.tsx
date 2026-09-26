// @vitest-environment jsdom
//
// The storage trend in numbers under the chart: the change over the period, the pace per day, and
// when the allowance fills at that pace (only when the page passes a projection).
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

const MB = 1024 * 1024;
const getHistory = vi.fn();

vi.mock('@/lib/api', () => ({
  storageApi: { getHistory: (...a: unknown[]) => getHistory(...a) },
  STORAGE_CATEGORY_HEX: {},
}));
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrgStore: () => 'org-1' }));
vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  const Nothing = () => null;
  return {
    ResponsiveContainer: Passthrough, AreaChart: Passthrough, Area: Nothing, XAxis: Nothing,
    YAxis: Nothing, CartesianGrid: Nothing, Tooltip: Nothing, Legend: Nothing,
  };
});

import StorageBreakdownChart from '../components/StorageBreakdownChart';

const point = (snapshotDate: string, usedBytes: number) => ({ snapshotDate, category: 'FILES', usedBytes, itemCount: 0 });

function renderChart(props: React.ComponentProps<typeof StorageBreakdownChart> = {}) {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <StorageBreakdownChart {...props} />
    </NextIntlClientProvider>,
  );
}

beforeEach(() => {
  // 10 MB -> 30 MB over 10 days: +2 MB a day.
  getHistory.mockReset().mockResolvedValue([point('2026-09-01', 10 * MB), point('2026-09-11', 30 * MB)]);
});
afterEach(cleanup);

describe('StorageBreakdownChart - growth', () => {
  it('states the change over the period and the pace per day', async () => {
    renderChart();

    const growth = await screen.findByTestId('storage-growth');
    expect(growth).toHaveTextContent('+20.0 MB');
    expect(growth).toHaveTextContent('From 10.0 MB to 30.0 MB');
    expect(growth).toHaveTextContent('+2.0 MB');
    expect(screen.queryByTestId('storage-full-in')).toBeNull();
  });

  it('with a projection, says when the allowance fills at that pace', async () => {
    renderChart({ projection: { usedBytes: 30 * MB, limitBytes: 100 * MB } });

    // 70 MB left at 2 MB a day.
    expect(await screen.findByTestId('storage-full-in')).toHaveTextContent('~35 days');
  });

  it('says "not filling up" when storage shrank, never a negative number of days', async () => {
    getHistory.mockResolvedValue([point('2026-09-01', 30 * MB), point('2026-09-11', 20 * MB)]);
    renderChart({ projection: { usedBytes: 20 * MB, limitBytes: 100 * MB } });

    expect(await screen.findByTestId('storage-full-in')).toHaveTextContent('Not filling up');
    expect(screen.getByTestId('storage-growth')).toHaveTextContent('-10.0 MB');
  });

  it('says "already full" rather than "not filling up" at the limit', async () => {
    renderChart({ projection: { usedBytes: 100 * MB, limitBytes: 100 * MB } });

    expect(await screen.findByTestId('storage-full-in')).toHaveTextContent('Already full');
  });

  it('regression - a single snapshot shows no growth figures at all', async () => {
    getHistory.mockResolvedValue([point('2026-09-11', 30 * MB)]);
    renderChart({ projection: { usedBytes: 30 * MB, limitBytes: 100 * MB } });

    await waitFor(() => expect(getHistory).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByText('Trend data will appear after the first daily snapshot.')).toBeNull());
    expect(screen.queryByTestId('storage-growth')).toBeNull();
  });
});
