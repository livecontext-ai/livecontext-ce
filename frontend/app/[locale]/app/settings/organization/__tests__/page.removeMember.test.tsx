// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

// --- shared spies on the data layer ----------------------------------------
const removeMember = vi.fn().mockResolvedValue(undefined);
const getOrganizations = vi.fn();
const getOrganization = vi.fn();

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  // The remove button is hidden for the current user's own row, so the signed-in
  // owner must be a different account than the member we remove.
  useAuth: () => ({ loginWithRedirect: vi.fn(), user: { email: 'owner@example.com' } }),
}));
vi.mock('@/lib/api', () => ({
  apiClient: { getTokenProvider: () => null },
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getOrganizations: (...a: unknown[]) => getOrganizations(...a),
    getOrganization: (...a: unknown[]) => getOrganization(...a),
    getPendingInvitations: vi.fn().mockResolvedValue([]),
    removeMember: (...a: unknown[]) => removeMember(...a),
    setDefaultOrganization: vi.fn(),
    updateOrganization: vi.fn(),
    changeMemberRole: vi.fn(),
    cancelInvitation: vi.fn(),
  },
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/app/settings/organization',
  useSearchParams: () => ({ get: () => null, toString: () => '' }),
}));
vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}));
// The page reads workspace entitlements via this hook (its internals use react-query, mocked
// thin above); stub it so the page renders without a real QueryClient/auth provider.
vi.mock('@/hooks/useWorkspaceEntitlements', () => ({
  useWorkspaceEntitlements: () => ({ effectivePlanCode: 'TEAM', canCreateWorkspace: true, canInviteTeammates: true }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: { getState: () => ({ setCurrentOrg: vi.fn() }) },
  // fetchData calls this on load; without it the page throws before rendering.
  reconcileCurrentOrgFromMemberships: vi.fn(),
}));
// Heavy sibling panels/modals are out of scope for this test → render nothing.
vi.mock('@/components/organization/InviteMemberModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberAccessModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberQuotaDialog', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationDangerZone', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationAuditLogPanel', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationSsoPanel', () => ({ default: () => null }));

import OrganizationSettingsPage from '../page';

const member = {
  userId: 42,
  email: 'jane@example.com',
  displayName: 'Jane Member',
  avatarUrl: null,
  role: 'MEMBER',
  joinedAt: '2026-01-01T00:00:00Z',
  isOwner: false,
};

const orgSummary = {
  id: 'org-1',
  name: 'Acme',
  slug: 'acme',
  isPersonal: false,
  avatarUrl: null,
  currentUserRole: 'OWNER',
  isDefault: true,
  memberCount: 1,
  planCode: 'TEAM',
  maxMembers: 10,
  paused: false,
} as unknown as Organization;

const orgFull = { ...orgSummary, members: [member] } as unknown as Organization;

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OrganizationSettingsPage />
    </NextIntlClientProvider>
  );
}

describe('OrganizationSettingsPage - remove member', () => {
  beforeEach(() => {
    removeMember.mockClear();
    getOrganizations.mockResolvedValue([orgSummary]);
    getOrganization.mockResolvedValue(orgFull);
  });
  afterEach(cleanup);

  it('opens the confirmation modal instead of a native window.confirm', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    renderPage();

    await screen.findByText('Jane Member');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Remove Member' }));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(confirmSpy).not.toHaveBeenCalled();
  });

  it('calls organizationApi.removeMember(orgId, userId) when the modal is confirmed', async () => {
    renderPage();

    await screen.findByText('Jane Member');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Member' }));
    const dialog = await screen.findByRole('dialog');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(removeMember).toHaveBeenCalledWith('org-1', 42));
  });

  it('does not remove the member when the modal is dismissed', async () => {
    renderPage();

    await screen.findByText('Jane Member');
    fireEvent.click(screen.getByRole('button', { name: 'Remove Member' }));
    const dialog = await screen.findByRole('dialog');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(removeMember).not.toHaveBeenCalled();
  });
});
