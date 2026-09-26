// @vitest-environment jsdom
//
// What the Storage page tells a reader beyond the gauge: the room left, where the warning starts,
// what each category holds, the biggest items, and when the allowance fills at the current pace.
// The last two claims are only honest for some views, and those rules are what is pinned here.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

const GB = 1024 * 1024 * 1024;
const MB = 1024 * 1024;

let currentOrgId: string | null = 'team-org';
let quotaResponse: Record<string, unknown> = {};
let breakdownResponse: unknown[] = [];
const getOrganizations = vi.fn();
const getExplorerEntries = vi.fn();
const chartProps = vi.hoisted(() => ({ last: null as null | Record<string, unknown> }));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId }),
  useIsCurrentOrgOwner: () => true,
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ subscription: { subscription: { planCode: 'TEAM' } }, isLoading: false }),
  usePlans: () => ({ plans: [] }),
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganizations: (...a: unknown[]) => getOrganizations(...a) },
}));
vi.mock('@/lib/api', () => ({
  storageApi: {
    getQuota: vi.fn().mockImplementation(() => Promise.resolve(quotaResponse)),
    getStats: vi.fn().mockResolvedValue({ tenantId: 't', workflowCount: 0, interfaceCount: 0, tableCount: 0, agentCount: 0 }),
    getBreakdown: vi.fn().mockImplementation(() => Promise.resolve(breakdownResponse)),
    recalculateUsage: vi.fn(),
    getExplorerEntries: (...a: unknown[]) => getExplorerEntries(...a),
  },
  STORAGE_CATEGORY_COLORS: {},
}));
vi.mock('../components/StorageBreakdownChart', () => ({
  default: (props: Record<string, unknown>) => {
    chartProps.last = props;
    return null;
  },
}));

import StoragePage from '../page';

const org = (id: string, role: 'OWNER' | 'MEMBER') => ({
  id, name: id, slug: id, isPersonal: false, avatarUrl: null, currentUserRole: role,
  isDefault: false, memberCount: 3, planCode: 'TEAM',
}) as unknown as Organization;

function quota(accountUsedBytes: number | null, extra: Record<string, unknown> = {}) {
  return {
    tenantId: 'team-org', usedBytes: 3 * GB, maxBytes: 100 * GB, softLimitBytes: 80 * GB,
    hardLimitBytes: 100 * GB, availableBytes: 97 * GB, usagePercentage: 3, status: 'OK', unlimited: false,
    ...(accountUsedBytes === null ? {} : { accountUsedBytes }),
    ...extra,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <StoragePage />
      </NextIntlClientProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  currentOrgId = 'team-org';
  chartProps.last = null;
  getOrganizations.mockReset().mockResolvedValue([org('team-org', 'OWNER')]);
  quotaResponse = quota(60 * GB);
  breakdownResponse = [];
  getExplorerEntries.mockReset().mockResolvedValue({ content: [] });
});
afterEach(cleanup);

describe('StoragePage - room left and where the warning starts', () => {
  it('states the room left in the ACCOUNT pool for its owner', async () => {
    renderPage();
    // 100 GB allowance, 60 GB held across the account.
    await waitFor(() => expect(screen.getByTestId('storage-available')).toHaveTextContent('40.00 GB available'));
    expect(screen.getByTestId('storage-soft-limit-marker')).toHaveStyle({ left: '80%' });
  });

  it('regression - a member is never told "available" from their workspace alone', async () => {
    // Their workspace holds 3 GB of a pool they cannot see: "97 GB available" would be a promise
    // the pool may not keep.
    getOrganizations.mockResolvedValue([org('team-org', 'MEMBER')]);
    quotaResponse = quota(null);
    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    expect(screen.getByTestId('storage-available')).not.toHaveTextContent('available');
  });

  it('regression - an owner\'s workspace with no reported pool is not "limit minus this workspace"', async () => {
    // Without accountUsedBytes the page only knows this workspace's bytes, while the owner's other
    // workspaces draw on the same allowance.
    getOrganizations.mockResolvedValue([org('team-org', 'OWNER'), org('other-org', 'OWNER')]);
    quotaResponse = quota(null);
    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    expect(screen.getByTestId('storage-available')).not.toHaveTextContent('available');
  });

  it('unlimited storage has no warning marker and no "available"', async () => {
    quotaResponse = quota(null, { unlimited: true });
    renderPage();

    await waitFor(() => expect(screen.getByText('∞')).toBeInTheDocument());
    expect(screen.queryByTestId('storage-soft-limit-marker')).toBeNull();
    expect(screen.getByTestId('storage-available')).not.toHaveTextContent('available');
  });
});

describe('StoragePage - categories', () => {
  it('lists the categories largest first, with share, item count and average size', async () => {
    breakdownResponse = [
      { category: 'FILES', usedBytes: 30 * MB, itemCount: 3, calculatedAt: '' },
      { category: 'STEP_OUTPUTS', usedBytes: 70 * MB, itemCount: 700, calculatedAt: '' },
      { category: 'AGENTS', usedBytes: 0, itemCount: 2, calculatedAt: '' },
    ];
    renderPage();

    const rows = await screen.findAllByTestId('storage-category-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(enMessages.storage.categories.STEP_OUTPUTS);
    expect(rows[0]).toHaveTextContent('70.0 MB');
    expect(rows[0]).toHaveTextContent('70%');
    expect(rows[0]).toHaveTextContent('700 items, 102.4 KB on average');
    expect(rows[1]).toHaveTextContent('3 items, 10.0 MB on average');
  });
});

describe('StoragePage - largest items', () => {
  it('lists the biggest items of the scoped workspace, sorted server-side by size', async () => {
    getExplorerEntries.mockResolvedValue({
      content: [
        { id: 'a', fileName: 'video.mp4', sizeBytes: 50 * MB, workflowName: 'Shorts', createdAt: '2026-09-20T10:00:00Z', isFolder: false },
        { id: 'b', fileName: null, sizeBytes: 5 * MB, workflowName: 'Scraper', stepKey: 'fetch', createdAt: '2026-09-21T10:00:00Z' },
        { id: 'f', fileName: 'Folder', sizeBytes: 0, isFolder: true, createdAt: '2026-09-21T10:00:00Z' },
      ],
    });
    renderPage();

    const items = await screen.findAllByTestId('storage-largest-item');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('video.mp4');
    expect(items[0]).toHaveTextContent('50.0 MB');
    // A step output is named by the workflow and step that produced it.
    expect(items[1]).toHaveTextContent('Scraper / fetch');
    expect(getExplorerEntries).toHaveBeenCalledWith({ page: 0, size: 5, sort: 'size', direction: 'desc' }, 'team-org');
    expect(within(screen.getByTestId('storage-largest-items')).getByRole('link')).toHaveAttribute('href', '/app/files');
  });

  it('steps aside when the listing fails, instead of adding an error to the page', async () => {
    getExplorerEntries.mockRejectedValue(new Error('HTTP 500'));
    renderPage();

    await waitFor(() => expect(getExplorerEntries).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByText(/used in this workspace/)).toBeInTheDocument());
    expect(screen.queryByTestId('storage-largest-items')).toBeNull();
  });
});

describe('StoragePage - when the allowance fills', () => {
  it('projects for an owner whose only workspace this is: its trend is the account trend', async () => {
    renderPage();

    await waitFor(() => expect(chartProps.last?.projection).toEqual({ usedBytes: 60 * GB, limitBytes: 100 * GB }));
  });

  it('regression - no projection for one workspace of several: its trend is not the account trend', async () => {
    getOrganizations.mockResolvedValue([org('team-org', 'OWNER'), org('other-org', 'OWNER')]);
    renderPage();

    await waitFor(() => expect(screen.getByText(/used in this workspace/)).toBeInTheDocument());
    await waitFor(() => expect(chartProps.last).not.toBeNull());
    expect(chartProps.last?.projection).toBeUndefined();
  });

  it('no projection for a member', async () => {
    getOrganizations.mockResolvedValue([org('team-org', 'MEMBER')]);
    quotaResponse = quota(null);
    renderPage();

    await waitFor(() => expect(chartProps.last).not.toBeNull());
    expect(chartProps.last?.projection).toBeUndefined();
  });
});
