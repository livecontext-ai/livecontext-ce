// @vitest-environment jsdom
/**
 * Invitation security audit (2026-09-27): GET /organizations/{id}/invitations is
 * OWNER/ADMIN only (it carries invitee emails and, in CE, raw accept tokens). The
 * settings page must not even request it for a MEMBER or VIEWER.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

const getOrganizations = vi.fn();
const getOrganization = vi.fn();
const changeMemberRole = vi.fn().mockResolvedValue(undefined);
const getPendingInvitations = vi.fn().mockResolvedValue([]);

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), user: { email: 'caller@example.com' } }),
}));
vi.mock('@/lib/api', () => ({
  apiClient: { getTokenProvider: () => null, getAuthToken: async () => null },
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getOrganizations: (...a: unknown[]) => getOrganizations(...a),
    getOrganization: (...a: unknown[]) => getOrganization(...a),
    getPendingInvitations: (...a: unknown[]) => getPendingInvitations(...a),
    removeMember: vi.fn(),
    setDefaultOrganization: vi.fn(),
    updateOrganization: vi.fn(),
    changeMemberRole: (...a: unknown[]) => changeMemberRole(...a),
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
vi.mock('@/hooks/useWorkspaceEntitlements', () => ({
  useWorkspaceEntitlements: () => ({ effectivePlanCode: 'TEAM', canCreateWorkspace: true, canInviteTeammates: true }),
}));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: { getState: () => ({ setCurrentOrg: vi.fn() }) },
  reconcileCurrentOrgFromMemberships: vi.fn(),
}));
vi.mock('@/components/organization/InviteMemberModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberAccessModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberQuotaDialog', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationDangerZone', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationAuditLogPanel', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationSsoPanel', () => ({ default: () => null }));

import OrganizationSettingsPage from '../page';

const ownerRow = {
  userId: 1,
  email: 'boss@example.com',
  displayName: 'Boss Owner',
  avatarUrl: null,
  role: 'OWNER',
  joinedAt: '2026-01-01T00:00:00Z',
};
const memberRow = {
  userId: 42,
  email: 'jane@example.com',
  displayName: 'Jane Member',
  avatarUrl: null,
  role: 'MEMBER',
  joinedAt: '2026-01-01T00:00:00Z',
};
const selfRow = {
  userId: 7,
  email: 'caller@example.com',
  displayName: 'Caller Self',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2026-01-01T00:00:00Z',
};

function org(currentUserRole: 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'): Organization {
  return {
    id: 'org-1',
    name: 'Acme',
    slug: 'acme',
    isPersonal: false,
    avatarUrl: null,
    currentUserRole,
    isDefault: true,
    memberCount: 3,
    planCode: 'TEAM',
    maxMembers: 10,
    paused: false,
    // The backend sends canInvite to every role; the old gate keyed on its presence.
    canInvite: currentUserRole === 'OWNER' || currentUserRole === 'ADMIN',
    members: [ownerRow, memberRow, selfRow],
  } as unknown as Organization;
}

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OrganizationSettingsPage />
    </NextIntlClientProvider>
  );
}

beforeEach(() => {
  getPendingInvitations.mockClear();
});
afterEach(cleanup);

describe('OrganizationSettingsPage - pending invitations are fetched for OWNER/ADMIN only', () => {
  it.each(['OWNER', 'ADMIN'] as const)('%s: the pending list is requested', async (role) => {
    getOrganizations.mockResolvedValue([org(role)]);
    getOrganization.mockResolvedValue(org(role));
    renderPage();

    await waitFor(() => expect(getPendingInvitations).toHaveBeenCalledWith('org-1'));
  });

  it.each(['MEMBER', 'VIEWER'] as const)('%s: the pending list is never requested (server answers 403)', async (role) => {
    getOrganizations.mockResolvedValue([org(role)]);
    getOrganization.mockResolvedValue(org(role));
    renderPage();

    await screen.findAllByText('Jane Member');
    await new Promise((r) => setTimeout(r, 50));
    expect(getPendingInvitations).not.toHaveBeenCalled();
  });
});
