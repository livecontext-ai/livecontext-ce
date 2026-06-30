// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

const uploadAvatar = vi.fn().mockResolvedValue({ storageId: 's2', avatarUrl: '/api/organizations/org-1/avatar?v=s2' });
const deleteAvatar = vi.fn().mockResolvedValue(undefined);
const getOrganizations = vi.fn();
const getOrganization = vi.fn();

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => ({ isAuthenticated: true, isAuthChecking: false }),
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({ loginWithRedirect: vi.fn(), user: { email: 'owner@example.com' } }),
}));
vi.mock('@/lib/api', () => ({ apiClient: { getTokenProvider: () => null } }));
vi.mock('@/lib/api/organization-api', () => ({
  organizationApi: {
    getOrganizations: (...a: unknown[]) => getOrganizations(...a),
    getOrganization: (...a: unknown[]) => getOrganization(...a),
    getPendingInvitations: vi.fn().mockResolvedValue([]),
    uploadAvatar: (...a: unknown[]) => uploadAvatar(...a),
    deleteAvatar: (...a: unknown[]) => deleteAvatar(...a),
    removeMember: vi.fn(),
    setDefaultOrganization: vi.fn(),
  },
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/app/settings/organization',
  useSearchParams: () => ({ get: () => null, toString: () => '' }),
}));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
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
vi.mock('@/components/organization/InviteMemberModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberAccessModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberQuotaDialog', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationDangerZone', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationAuditLogPanel', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationSsoPanel', () => ({ default: () => null }));

import OrganizationSettingsPage from '../page';

function makeOrg(role: string, avatarUrl: string | null): Organization {
  return {
    id: 'org-1',
    name: "Acme's Workspace",
    slug: 'acme',
    isPersonal: false,
    avatarUrl,
    currentUserRole: role,
    isDefault: true,
    memberCount: 1,
    planCode: 'TEAM',
    maxMembers: 10,
    paused: false,
    members: [
      { userId: 1, email: 'owner@example.com', displayName: 'Owner', avatarUrl: null, role: 'OWNER', joinedAt: '2026-01-01T00:00:00Z', isOwner: true },
    ],
  } as unknown as Organization;
}

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OrganizationSettingsPage />
    </NextIntlClientProvider>
  );
}

describe('OrganizationSettingsPage - workspace avatar editing', () => {
  beforeEach(() => {
    uploadAvatar.mockClear();
    deleteAvatar.mockClear();
  });
  afterEach(cleanup);

  it('OWNER sees the editable avatar (change + remove) when an avatar is set', async () => {
    const org = makeOrg('OWNER', '/api/organizations/org-1/avatar?v=s1');
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    renderPage();
    expect(await screen.findByRole('button', { name: 'Change workspace avatar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove avatar' })).toBeInTheDocument();
  });

  it('a VIEWER does not get the avatar edit affordance', async () => {
    const org = makeOrg('VIEWER', '/api/organizations/org-1/avatar?v=s1');
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    renderPage();
    // The workspace name renders once data is loaded…
    await screen.findAllByText("Acme's Workspace");
    // …but there is no avatar-change button for a non-manager.
    expect(screen.queryByRole('button', { name: 'Change workspace avatar' })).not.toBeInTheDocument();
  });

  it('uploads the selected file via organizationApi.uploadAvatar', async () => {
    const org = makeOrg('OWNER', null);
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    const { container } = renderPage();
    await screen.findByRole('button', { name: 'Change workspace avatar' });

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['bytes'], 'logo.png', { type: 'image/png' });
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(uploadAvatar).toHaveBeenCalledWith('org-1', file));
  });

  it('removes the avatar via organizationApi.deleteAvatar', async () => {
    const org = makeOrg('OWNER', '/api/organizations/org-1/avatar?v=s1');
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    renderPage();
    const removeBtn = await screen.findByRole('button', { name: 'Remove avatar' });
    fireEvent.click(removeBtn);

    await waitFor(() => expect(deleteAvatar).toHaveBeenCalledWith('org-1'));
  });
});

describe('OrganizationSettingsPage - header slug visibility', () => {
  afterEach(cleanup);

  it('hides the slug for a personal workspace (slug duplicates the user name)', async () => {
    const org = {
      ...makeOrg('OWNER', null),
      isPersonal: true,
      slug: 'ada-lovelace',
      name: "ada lovelace's Workspace",
    } as unknown as Organization;
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    renderPage();
    await screen.findAllByText("ada lovelace's Workspace");
    // The raw slug (= the user's name) must not appear in the header subtitle.
    expect(screen.queryByText(/ada-lovelace/)).not.toBeInTheDocument();
  });

  it('shows the slug for a team workspace (real org handle)', async () => {
    const org = { ...makeOrg('OWNER', null), isPersonal: false, slug: 'acme' } as unknown as Organization;
    getOrganizations.mockResolvedValue([org]);
    getOrganization.mockResolvedValue(org);

    renderPage();
    await screen.findAllByText("Acme's Workspace");
    expect(screen.getByText(/acme/)).toBeInTheDocument();
  });
});
