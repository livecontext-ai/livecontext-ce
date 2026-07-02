// @vitest-environment jsdom
/**
 * RBAC audit (2026-07-02): the member role-change dropdown mirrors the backend
 * rules of OrganizationMemberService.changeRole EXACTLY:
 *   - only the OWNER can change roles (an ADMIN requester gets a
 *     SecurityException server-side, so the UI never offers the affordance);
 *   - your own row is never changeable;
 *   - OWNER is never offered as a target role (ownership transfer instead).
 * ADMIN keeps the OTHER canEdit member actions (manage access / quota / remove),
 * which their backend endpoints do allow.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

const getOrganizations = vi.fn();
const getOrganization = vi.fn();
const changeMemberRole = vi.fn().mockResolvedValue(undefined);

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), user: { email: 'caller@example.com' } }),
}));
vi.mock('@/lib/api', () => ({
  apiClient: { getTokenProvider: () => null },
}));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getOrganizations: (...a: unknown[]) => getOrganizations(...a),
    getOrganization: (...a: unknown[]) => getOrganization(...a),
    getPendingInvitations: vi.fn().mockResolvedValue([]),
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

function org(currentUserRole: 'OWNER' | 'ADMIN'): Organization {
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

/** The member's row container - actions are queried within it. */
async function memberRowEl(name: string): Promise<HTMLElement> {
  const nameEl = await screen.findByText(name);
  return nameEl.closest('div.grid') as HTMLElement;
}

beforeEach(() => {
  changeMemberRole.mockClear();
});
afterEach(cleanup);

describe('OrganizationSettingsPage - role-change dropdown mirrors backend OWNER-only rule', () => {
  it('OWNER sees the Change Role dropdown on a non-owner row and it never offers OWNER', async () => {
    getOrganizations.mockResolvedValue([org('OWNER')]);
    getOrganization.mockResolvedValue(org('OWNER'));
    renderPage();

    const row = await memberRowEl('Jane Member');
    const trigger = within(row).getByTitle('Change Role');
    fireEvent.click(trigger);

    // Options: every role except the member's current one AND never OWNER.
    expect(await screen.findByRole('button', { name: /Admin/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Viewer/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Owner$/ })).not.toBeInTheDocument();
  });

  it('OWNER changing a role calls changeMemberRole(orgId, userId, newRole)', async () => {
    getOrganizations.mockResolvedValue([org('OWNER')]);
    getOrganization.mockResolvedValue(org('OWNER'));
    renderPage();

    const row = await memberRowEl('Jane Member');
    fireEvent.click(within(row).getByTitle('Change Role'));
    fireEvent.click(await screen.findByRole('button', { name: /Viewer/ }));

    expect(changeMemberRole).toHaveBeenCalledWith('org-1', 42, 'VIEWER');
  });

  it('OWNER never sees the dropdown on the OWNER row', async () => {
    getOrganizations.mockResolvedValue([org('OWNER')]);
    getOrganization.mockResolvedValue(org('OWNER'));
    renderPage();

    const row = await memberRowEl('Boss Owner');
    expect(within(row).queryByTitle('Change Role')).not.toBeInTheDocument();
  });

  it('ADMIN does NOT see the Change Role dropdown (backend rejects non-OWNER requesters)', async () => {
    getOrganizations.mockResolvedValue([org('ADMIN')]);
    getOrganization.mockResolvedValue(org('ADMIN'));
    renderPage();

    const row = await memberRowEl('Jane Member');
    expect(within(row).queryByTitle('Change Role')).not.toBeInTheDocument();
  });

  it('ADMIN keeps the other member actions their endpoints allow (access / quota / remove)', async () => {
    getOrganizations.mockResolvedValue([org('ADMIN')]);
    getOrganization.mockResolvedValue(org('ADMIN'));
    renderPage();

    const row = await memberRowEl('Jane Member');
    expect(within(row).getByTitle('Manage Access')).toBeInTheDocument();
    expect(within(row).getByTitle('Manage quota')).toBeInTheDocument();
    expect(within(row).getByTitle('Remove Member')).toBeInTheDocument();
  });
});
