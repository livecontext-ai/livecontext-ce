// @vitest-environment jsdom
/**
 * The usage chart calls each kind of spend what the table under it calls it.
 *
 * <p><b>The bug this closes.</b> The two halves of the quota page each carried their own map of
 * source-type names, and the chart's was four entries shorter. A catalogue call on the platform key
 * - which is what a generation is - appeared in the table as "Platform API Call" and in the chart's
 * summary, legend and filter as the raw {@code PLATFORM_MARKUP}. Same rows, same screen, two names,
 * one of them machine text.
 *
 * <p>A source-reading contract test already forbids a second map from coming back
 * ({@code lib/billing/__tests__/creditSourceTypes.test.ts}). This one answers the question that
 * regex cannot: does a reader actually SEE the word. It renders the panel against the real
 * dictionary, with the server answering as it does for a workspace whose spend is generations.
 *
 * <p>Recharts is stubbed: it measures its container to draw, and jsdom gives it no size, so the SVG
 * never renders anywhere. Nothing asserted here comes from inside the chart - the summary row is
 * plain DOM, and it is fed by the same {@code formatSourceType} the legend and the filter use.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';

import enMessages from '@/messages/en.json';

const getAnalytics = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api', () => ({ quotaApi: { getAnalytics } }));
vi.mock('@/lib/stores/current-org-store', () => ({ useCurrentOrgStore: () => null }));
// The catalogue behind the provider/model filter labels. `claude-sonnet-5` is
// renamed here exactly as a cloud admin renames one in Settings > AI Providers;
// `dall-e-3` is not in the catalogue at all, which is the common case for a model
// a workspace spent on months ago.
vi.mock('@/hooks/useModels', () => ({
  useModels: () => ({
    models: [{ id: 'claude-sonnet-5', name: 'House Sonnet', provider: 'anthropic' }],
    providers: [],
    defaultModel: null,
    defaultProvider: null,
    isLoading: false,
    error: null,
    refresh: async () => {},
  }),
}));
vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
  const Nothing = () => null;
  return {
    ResponsiveContainer: Passthrough,
    AreaChart: Passthrough,
    Area: Nothing,
    XAxis: Nothing,
    YAxis: Nothing,
    CartesianGrid: Nothing,
    Tooltip: Nothing,
    Legend: Nothing,
  };
});

import UsageAnalyticsPanel from '../UsageAnalyticsPanel';

/**
 * A workspace whose spend is generations plus chat: catalogue calls on the
 * platform key, a web search, and agent turns.
 *
 * <p>The provider list carries BOTH shapes the ledger really writes, because
 * they are written by different services and only one of them needs naming. A
 * catalogue call stores the API's own name (`ToolExecutionManager` writes
 * `api.getApiName()`, already human, e.g. "Google Gemini"); an LLM row stores
 * the wire provider key (`anthropic`). A fixture carrying only the first would
 * make the label lookup look unnecessary, and one carrying only the second
 * would hide that a human string must pass through untouched.
 */
const ANALYTICS = {
  dailyUsage: [
    { date: '2026-09-09', sourceType: 'PLATFORM_MARKUP', count: 3, credits: 234, tokens: 0 },
    { date: '2026-09-09', sourceType: 'WEB_SEARCH', count: 1, credits: 1, tokens: 0 },
    // Kept SMALLER than the platform row on purpose: the summary card names the
    // biggest kind of spend, and the two tests above read it by name.
    { date: '2026-09-09', sourceType: 'AGENT_EXECUTION', count: 2, credits: 40, tokens: 9100 },
  ],
  providers: ['Google Gemini', 'anthropic'],
  models: ['claude-sonnet-5', 'dall-e-3'],
  sourceTypes: ['PLATFORM_MARKUP', 'WEB_SEARCH', 'AGENT_EXECUTION'],
};

beforeEach(() => {
  getAnalytics.mockResolvedValue(ANALYTICS);
  // Radix Select opens on a pointer event and scrolls its list into view; jsdom
  // implements none of that. Same stubs as the marketplace filter test.
  (window as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  (Element.prototype as unknown as { scrollIntoView: () => void }).scrollIntoView = () => {};
  (Element.prototype as unknown as { hasPointerCapture: () => boolean }).hasPointerCapture = () => false;
  (Element.prototype as unknown as { setPointerCapture: () => void }).setPointerCapture = () => {};
  (Element.prototype as unknown as { releasePointerCapture: () => void }).releasePointerCapture = () => {};
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPanel() {
  render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <UsageAnalyticsPanel />
    </NextIntlClientProvider>,
  );
}

describe('the usage chart names a kind of spend', () => {
  it('calls a platform API call what the history table calls it', async () => {
    renderPanel();

    // The biggest kind of spend, named in the summary row from the shared map.
    expect(await screen.findByText('Platform API Call')).toBeInTheDocument();
  });

  it('never puts the raw wire name in front of a reader', async () => {
    renderPanel();

    await waitFor(() => expect(getAnalytics).toHaveBeenCalled());
    await screen.findByText('Platform API Call');

    expect(screen.queryByText('PLATFORM_MARKUP')).not.toBeInTheDocument();
    // Only the biggest series is asserted here, and deliberately so. A second type is named on this
    // panel only inside its filter dropdown, which is a Radix portal that mounts nothing until it
    // is opened - so an "and WEB_SEARCH is not raw either" line passes whether or not that label
    // exists, which is a line that reads as coverage and is not. The other types are pinned where
    // they can be seen: lib/billing/__tests__/creditSourceTypes.test.ts for the map, and the CE
    // e2e for the options a reader actually opens.
  });

});

/**
 * The filters list the ids the LEDGER stored, and an admin renames models in
 * Settings > AI Providers. These open the dropdowns, because that is the only
 * place the labels exist: the list is portalled and mounts nothing at rest.
 */
describe('the usage filters name a provider and a model', () => {
  /**
   * Open the filter whose control currently reads `label`, and return its
   * options. Found by what it SAYS rather than by position: the panel draws
   * four of these, and an index would quietly follow the wrong one the day a
   * filter is added.
   */
  async function openFilter(label: string) {
    await screen.findByText('Platform API Call');
    const control = screen.getAllByRole('combobox')
      .find((node) => node.textContent === label);
    expect(control, `no filter control reading "${label}"`).toBeTruthy();
    fireEvent.click(control!);
    return screen.findAllByRole('option');
  }

  it('calls a model what the model picker calls it, not what the ledger stored', async () => {
    renderPanel();

    const options = (await openFilter('All models')).map((option) => option.textContent);

    expect(options).toContain('House Sonnet');
    expect(options).not.toContain('claude-sonnet-5');
  });

  it('still lists a model the catalogue cannot name, under its stored id', async () => {
    // Dropping it would hide spend. The id is the true thing to show here.
    renderPanel();

    const options = (await openFilter('All models')).map((option) => option.textContent);

    expect(options).toContain('dall-e-3');
  });

  it('names the provider too, since the ledger stores its wire key', async () => {
    renderPanel();

    const options = (await openFilter('All providers')).map((option) => option.textContent);

    expect(options).toContain('Anthropic');
    expect(options).not.toContain('anthropic');
  });

  it('leaves a provider the ledger already stored as a human name alone', () => {
    // A catalogue call stores the API's own name, not a wire key. Passing it
    // through the display map must be a no-op rather than a mangling.
    renderPanel();

    return openFilter('All providers').then((options) => {
      expect(options.map((option) => option.textContent)).toContain('Google Gemini');
    });
  });
});
