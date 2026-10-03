// @vitest-environment jsdom
//
// The storage trend in numbers under the chart: the change over the period, the pace per day, and
// when the allowance fills at that pace (only when the page passes a projection).
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';

vi.mock('next/navigation', async () => {
  const mod = await import('@/lib/folders/testing/fakeFolderRouter');
  return mod.fakeFolderRouter.nextNavigationModule();
});
import { fakeFolderRouter } from '@/lib/folders/testing/fakeFolderRouter';

const MB = 1024 * 1024;
const getHistory = vi.fn();
const STORAGE_PAGE = '/en/app/settings/storage';

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

// A select jsdom can drive: every option is a button that reports its value.
vi.mock('@/components/ui/select', async () => {
  const ReactModule = await import('react');
  const Ctx = ReactModule.createContext<(v: string) => void>(() => {});
  return {
    Select: ({ onValueChange, children }: { onValueChange: (v: string) => void; children: React.ReactNode }) =>
      <Ctx.Provider value={onValueChange}>{children}</Ctx.Provider>,
    SelectTrigger: () => null,
    SelectValue: () => null,
    SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    SelectItem: ({ value }: { value: string }) => {
      const onValueChange = ReactModule.useContext(Ctx);
      return <button type="button" data-option={value} onClick={() => onValueChange(value)} />;
    },
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
  fakeFolderRouter.reset(STORAGE_PAGE);
  // 10 MB -> 30 MB over 10 days: +2 MB a day.
  getHistory.mockReset().mockResolvedValue([point('2026-09-01', 10 * MB), point('2026-09-11', 30 * MB)]);
});
afterEach(cleanup);

describe('StorageBreakdownChart - period kept in the address', () => {
  it('asks for the period the address carries, never the default first', async () => {
    fakeFolderRouter.navigate(`${STORAGE_PAGE}?period=90`, 'replace');
    renderChart();

    await waitFor(() => expect(getHistory).toHaveBeenCalledWith(90, 'org-1'));
    expect(getHistory).not.toHaveBeenCalledWith(30, 'org-1');
  });

  it('falls back to 30 days on a period the select does not offer', async () => {
    fakeFolderRouter.navigate(`${STORAGE_PAGE}?period=1`, 'replace');
    renderChart();

    await waitFor(() => expect(getHistory).toHaveBeenCalledWith(30, 'org-1'));
  });

  it('writes the period when it changes', async () => {
    renderChart();
    await screen.findByTestId('storage-growth');

    fireEvent.click(document.querySelector('[data-option="7"]')!);

    await waitFor(() => expect(fakeFolderRouter.search()).toBe('period=7'));
    expect(getHistory).toHaveBeenLastCalledWith(7, 'org-1');
  });
});

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
