// @vitest-environment jsdom
import * as React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act, cleanup } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';

/**
 * AvailableCredentialsList infinite scroll.
 *
 * The change: the "Show more (N remaining)" button was replaced by an
 * IntersectionObserver sentinel (useLazyLoadObserver). All templates are
 * already fetched client-side (pageSize 1000); revealing the next batch is a
 * synchronous displayCount bump, driven by the sentinel scrolling into view.
 *
 * These tests pin:
 *   1) only the first batch (INITIAL_DISPLAY_COUNT = 12) renders initially,
 *   2) the old "Show more" button is gone and the sentinel renders when more exist,
 *   3) firing the observer's onLoadMore reveals the next batch (no click),
 *   4) the sentinel/observer report no-more when everything fits in one batch.
 *
 * `useLazyLoadObserver` is mocked because jsdom has no real IntersectionObserver:
 * it captures each call (so we can drive onLoadMore) and returns a real ref so
 * the sentinel <div ref> mounts.
 */

vi.mock('@/components/ui/service-icon', () => ({
  ServiceIcon: () => null,
}));

// Radix Select needs ResizeObserver / pointer APIs jsdom lacks; the filter
// dropdown is irrelevant to the infinite-scroll behaviour under test.
vi.mock('@/components/ui/select', () => ({
  Select: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectItem: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SelectValue: ({ placeholder }: { placeholder?: string }) => <span>{placeholder}</span>,
}));

const { getCredentialTemplatesMock, getCredentialsMock } = vi.hoisted(() => ({
  getCredentialTemplatesMock: vi.fn(),
  getCredentialsMock: vi.fn(),
}));

vi.mock('@/lib/api/orchestrator', () => ({
  orchestratorApi: {
    getCredentialTemplates: getCredentialTemplatesMock,
    getCredentials: getCredentialsMock,
  },
}));

// Capture every useLazyLoadObserver call so the test can drive onLoadMore
// deterministically; return a fresh real ref so the sentinel <div ref> mounts.
const { observerCalls } = vi.hoisted(() => ({
  observerCalls: [] as Array<{
    enabled: boolean;
    hasMore: boolean;
    isLoading: boolean;
    isInitialLoading: boolean;
    dataLength: number;
    onLoadMore: () => void;
  }>,
}));
vi.mock('@/app/workflows/builder/components/palette/useLazyLoadObserver', () => ({
  useLazyLoadObserver: (params: (typeof observerCalls)[number]) => {
    observerCalls.push(params);
    return React.createRef<HTMLDivElement>();
  },
}));

import { AvailableCredentialsList } from '../AvailableCredentialsList';

const messages = {
  credentials: {
    available: {
      searchPlaceholder: 'Search integrations...',
      allTypes: 'All types',
      failedToLoad: 'Failed to load credential templates',
      retry: 'Retry',
      columnIntegration: 'Integration',
      columnType: 'Type',
      noMatchingIntegrations: 'No integrations match your filters.',
      noTemplatesAvailable: 'No credential templates available.',
      configuredTooltip: 'Already configured',
      configuredBadge: 'Configured',
      connectCount: 'Connect ({count})',
      clearSelection: 'Clear selection',
      filterByAuthMethod: 'Filter by {method}',
    },
  },
};

function makeTemplates(n: number) {
  return Array.from({ length: n }, (_, i) => ({
    id: `tpl-${i + 1}`,
    credential_name: `service_${i + 1}`,
    display_name: `Service ${i + 1}`,
    description: `Integration ${i + 1}`,
    auth_type: i % 2 === 0 ? 'api_key' : 'oauth2',
    icon_slug: `service-${i + 1}`,
    variants: [{ variant: i % 2 === 0 ? 'api_key' : 'oauth2', auth_type: i % 2 === 0 ? 'api_key' : 'oauth2' }],
  }));
}

function renderList() {
  return render(
    <NextIntlClientProvider locale="en" messages={messages as any}>
      <AvailableCredentialsList onConfigure={() => {}} onConfigureMultiple={() => {}} />
    </NextIntlClientProvider>,
  );
}

describe('AvailableCredentialsList - infinite scroll', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    observerCalls.length = 0;
    getCredentialsMock.mockResolvedValue({ credentials: [] });
  });
  afterEach(() => cleanup());

  it('renders only the first batch (12) and not the rest after the initial fetch', async () => {
    getCredentialTemplatesMock.mockResolvedValue({ credentials: makeTemplates(15) });

    renderList();

    // First batch is rendered...
    expect(await screen.findByText('Service 1')).toBeTruthy();
    expect(screen.getByText('Service 12')).toBeTruthy();
    // ...the rest is not even in the DOM (slice(0, 12)).
    expect(screen.queryByText('Service 13')).toBeNull();
    expect(screen.queryByText('Service 15')).toBeNull();
  });

  it('replaces the "Show more" button with a sentinel when more templates remain', async () => {
    getCredentialTemplatesMock.mockResolvedValue({ credentials: makeTemplates(15) });

    renderList();
    await screen.findByText('Service 1');

    // The old click-to-paginate button is gone.
    expect(screen.queryByRole('button', { name: /show more/i })).toBeNull();
    // The infinite-scroll sentinel is present, and the observer reports hasMore.
    expect(screen.getByTestId('available-templates-load-more')).toBeTruthy();
    await waitFor(() => expect(observerCalls.some((c) => c.hasMore)).toBe(true));
  });

  it('reveals the next batch when the observer onLoadMore fires (no click)', async () => {
    getCredentialTemplatesMock.mockResolvedValue({ credentials: makeTemplates(15) });

    renderList();
    await screen.findByText('Service 1');
    expect(screen.queryByText('Service 13')).toBeNull();

    // Drive the sentinel "scrolled into view" event deterministically.
    await waitFor(() => expect(observerCalls.some((c) => c.hasMore)).toBe(true));
    const onLoadMore = [...observerCalls].reverse().find((c) => c.hasMore)!.onLoadMore;
    await act(async () => {
      onLoadMore();
    });

    // The full remaining batch (13..15) is now revealed.
    expect(await screen.findByText('Service 13')).toBeTruthy();
    expect(screen.getByText('Service 15')).toBeTruthy();
  });

  it('renders no sentinel and reports no-more when everything fits in one batch', async () => {
    getCredentialTemplatesMock.mockResolvedValue({ credentials: makeTemplates(5) });

    renderList();
    await screen.findByText('Service 1');
    expect(screen.getByText('Service 5')).toBeTruthy();

    expect(screen.queryByTestId('available-templates-load-more')).toBeNull();
    // Every observer call after the load reports there is nothing more to reveal.
    await waitFor(() => expect(getCredentialTemplatesMock).toHaveBeenCalled());
    expect(observerCalls.every((c) => c.hasMore === false)).toBe(true);
  });
});
