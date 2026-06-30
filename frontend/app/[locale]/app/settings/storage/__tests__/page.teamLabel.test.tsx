// @vitest-environment jsdom
//
// Regression - the storage gauge sub-label must say "Team workspace" ONLY for
// an actual TEAM org, never for the personal workspace.
//
// Bug: the label was driven by `isOrgScope = !!currentOrgId`. But every user
// owns a *personal* organization (is_personal=true) and the active-workspace
// store resolves currentOrgId to it by default - so `!!currentOrgId` was true
// even in the personal workspace, and a solo user (no team at all) saw
// "Team workspace" on /app/settings/storage. The fix labels by the active org's
// `isPersonal` instead. These tests fail on the pre-fix `isOrgScope` code.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

// Active workspace id - reassigned per test before render. The mock factories
// read it at call time, so each render observes the current value.
let currentOrgId: string | null = 'personal-org';
const getOrganizations = vi.fn();

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ isLoading: false, isAuthenticated: true, loginWithRedirect: vi.fn() }),
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({ subscription: { subscription: { planCode: 'FREE' } }, isLoading: false }),
  usePlans: () => ({ plans: [] }),
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: { getOrganizations: (...a: unknown[]) => getOrganizations(...a) },
}));
vi.mock('@/lib/api', () => ({
  storageApi: {
    getQuota: vi.fn().mockResolvedValue({
      tenantId: 't', usedBytes: 10, maxBytes: 100 * 1024 * 1024,
      softLimitBytes: 80 * 1024 * 1024, hardLimitBytes: 100 * 1024 * 1024,
      availableBytes: 100 * 1024 * 1024, usagePercentage: 0, status: 'OK', unlimited: false,
    }),
    getStats: vi.fn().mockResolvedValue({ tenantId: 't', workflowCount: 0, interfaceCount: 0, tableCount: 0, agentCount: 0 }),
    getBreakdown: vi.fn().mockResolvedValue([]),
    recalculateUsage: vi.fn(),
  },
  STORAGE_CATEGORY_COLORS: {},
}));
// Chart is irrelevant to the label - render nothing. Path resolves to the same
// absolute module the page imports via './components/StorageBreakdownChart'.
vi.mock('../components/StorageBreakdownChart', () => ({ default: () => null }));

import StoragePage from '../page';

const personalOrg = {
  id: 'personal-org', name: 'My Workspace', slug: 'me', isPersonal: true,
  avatarUrl: null, currentUserRole: 'OWNER', isDefault: true, memberCount: 1,
} as unknown as Organization;

const teamOrg = {
  id: 'team-org', name: 'Acme', slug: 'acme', isPersonal: false,
  avatarUrl: null, currentUserRole: 'MEMBER', isDefault: false, memberCount: 5, planCode: 'TEAM',
} as unknown as Organization;

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

describe('StoragePage - workspace label (personal vs team)', () => {
  beforeEach(() => {
    getOrganizations.mockReset();
  });
  afterEach(cleanup);

  it('labels the personal workspace with the plan, NOT "Team workspace"', async () => {
    currentOrgId = 'personal-org';
    getOrganizations.mockResolvedValue([personalOrg]);

    renderPage();

    // Plan label renders once storage loads.
    expect(await screen.findByText('FREE plan')).toBeInTheDocument();
    // Let the membership query resolve, then assert the team label never appears.
    await waitFor(() => expect(getOrganizations).toHaveBeenCalled());
    expect(screen.queryByText('Team workspace')).not.toBeInTheDocument();
  });

  it('labels an active TEAM workspace as "Team workspace"', async () => {
    currentOrgId = 'team-org';
    getOrganizations.mockResolvedValue([personalOrg, teamOrg]);

    renderPage();

    expect(await screen.findByText('Team workspace')).toBeInTheDocument();
    expect(screen.queryByText('FREE plan')).not.toBeInTheDocument();
  });

  it('with no active workspace the plan label shows and the shared membership query stays harmless', async () => {
    currentOrgId = null;
    getOrganizations.mockResolvedValue([teamOrg]);

    renderPage();

    expect(await screen.findByText('FREE plan')).toBeInTheDocument();
    // The page-local workspace filter shares the memberships query in both editions.
    // With no selected workspace, that data must not turn the gauge into a team label.
    await waitFor(() => expect(getOrganizations).toHaveBeenCalled());
    expect(screen.queryByText('Team workspace')).not.toBeInTheDocument();
  });
});
