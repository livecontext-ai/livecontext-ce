// @vitest-environment jsdom
//
// The storage allowance belongs to the ACCOUNT, not to each workspace: every workspace an
// account owns draws on one pool, and all of them are refused once it is full. Two things follow
// for this page, and both are pinned here.
//
// 1. A workspace's own figure must not carry the allowance as its denominator. "3 MB of 100 GB"
//    reads as this workspace's headroom, which is not a thing that exists, and the gauge has to
//    track the POOL or it would sit near zero right up to a refusal.
// 2. The pool line is shown to the OWNER only. Inside a shared workspace it would tell an
//    invited member how much the owner stores elsewhere, and that workspaces they cannot see
//    exist at all. A member who gets blocked is told so at the refusal instead.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

const GB = 1024 * 1024 * 1024;

let currentOrgId: string | null = 'team-org';
let isOwner = true;
let quotaResponse: Record<string, unknown> = {};
const getOrganizations = vi.fn();

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId }),
  useIsCurrentOrgOwner: () => isOwner,
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
    getBreakdown: vi.fn().mockResolvedValue([]),
    recalculateUsage: vi.fn(),
  },
  STORAGE_CATEGORY_COLORS: {},
}));
vi.mock('../components/StorageBreakdownChart', () => ({ default: () => null }));

import StoragePage from '../page';

const teamOrg = {
  id: 'team-org', name: 'Acme', slug: 'acme', isPersonal: false,
  avatarUrl: null, currentUserRole: 'OWNER', isDefault: false, memberCount: 3, planCode: 'TEAM',
} as unknown as Organization;

/** A workspace holding 3 GB, on an account whose 3 workspaces hold 60 GB of a 100 GB plan. */
function quotaWithPool(accountUsedBytes: number | null) {
  return {
    tenantId: 'team-org',
    usedBytes: 3 * GB,
    maxBytes: 100 * GB,
    softLimitBytes: 80 * GB,
    hardLimitBytes: 100 * GB,
    availableBytes: 97 * GB,
    usagePercentage: 3,
    status: 'OK',
    unlimited: false,
    ...(accountUsedBytes === null ? {} : { accountUsedBytes }),
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        <StoragePage />
      </NextIntlClientProvider>
    </QueryClientProvider>
  );
}

describe('StoragePage - account-wide storage pool', () => {
  beforeEach(() => {
    getOrganizations.mockReset();
    getOrganizations.mockResolvedValue([teamOrg]);
    currentOrgId = 'team-org';
    isOwner = true;
    quotaResponse = quotaWithPool(60 * GB);
  });
  afterEach(cleanup);

  it('shows the owner what the ACCOUNT holds against the plan, alongside this workspace', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB used in this workspace/)).toBeInTheDocument());
    expect(screen.getByText(/Account: 60\.00 GB of 100\.00 GB shared across your workspaces/)).toBeInTheDocument();
  });

  it('never prints the allowance as this workspace\'s own denominator', async () => {
    renderPage();

    await waitFor(() => expect(screen.getByText(/used in this workspace/)).toBeInTheDocument());
    // "3.00 GB of 100.00 GB used" would promise headroom the account may not have.
    expect(screen.queryByText(/3\.00 GB of 100\.00 GB used/)).not.toBeInTheDocument();
  });

  it('tracks the POOL in the gauge, not the workspace, so it warns before a refusal', async () => {
    renderPage();

    // 60 of 100 across the account, not 3 of 100 for this workspace.
    await waitFor(() => expect(screen.getByText('60.0%')).toBeInTheDocument());
    expect(screen.queryByText('3.0%')).not.toBeInTheDocument();
  });

  it('a member never sees the account figure: the server does not send it to them', async () => {
    // The real boundary is server-side (StorageQuotaController returns accountUsedBytes only to
    // the owning account), because hiding it in the UI alone leaves it readable in devtools.
    // This models what a member's response actually looks like.
    isOwner = false;
    getOrganizations.mockResolvedValue([
      { ...teamOrg, currentUserRole: 'MEMBER' } as unknown as Organization,
    ]);
    quotaResponse = quotaWithPool(null);

    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    expect(screen.queryByText(/shared across your workspaces/)).not.toBeInTheDocument();
  });

  it('and the client would still not render it for a member if the figure ever leaked in', async () => {
    // Defence in depth: the line is gated on owning the SCOPED workspace, not merely on a
    // figure being present in the payload.
    isOwner = false;
    getOrganizations.mockResolvedValue([
      { ...teamOrg, currentUserRole: 'MEMBER' } as unknown as Organization,
    ]);
    quotaResponse = quotaWithPool(60 * GB);

    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    expect(screen.queryByText(/shared across your workspaces/)).not.toBeInTheDocument();
    expect(screen.queryByText(/60\.00 GB/)).not.toBeInTheDocument();
  });

  it('a member still sees the REFUSAL: the server status drives the gauge even with no figure', async () => {
    // A member gets no account number, so the page cannot compute how full the pool is. If it
    // fell back to their workspace's bytes it would show a green 3% bar while every upload is
    // being refused. The server sends the pool-decided status precisely for this.
    isOwner = false;
    getOrganizations.mockResolvedValue([
      { ...teamOrg, currentUserRole: 'MEMBER' } as unknown as Organization,
    ]);
    quotaResponse = { ...quotaWithPool(null), status: 'HARD_LIMIT_REACHED' };

    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    // The alert the page raises for a full quota, not a serene green bar.
    expect(screen.queryByText(/shared across your workspaces/)).not.toBeInTheDocument();
    expect(document.querySelector('.bg-red-500, .bg-red-600, [class*="red"]')).toBeTruthy();
  });

  it('an unattributed workspace keeps the pre-change reading, with no pool line', async () => {
    // No accountUsedBytes on the response: enforcement is still per-workspace for this row, so
    // the page must not invent a pool.
    quotaResponse = quotaWithPool(null);

    renderPage();

    await waitFor(() => expect(screen.getByText(/3\.00 GB of 100\.00 GB used/)).toBeInTheDocument());
    expect(screen.queryByText(/shared across your workspaces/)).not.toBeInTheDocument();
  });
});
