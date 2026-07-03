// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import enMessages from '@/messages/en.json';
import type { Organization } from '@/lib/api/organization-api';

// --- data layer spies -------------------------------------------------------
const getOrganizations = vi.fn();
const getOrganization = vi.fn();

// Mutable search-param holder so a test can simulate ?tab= / ?invite= deep-links.
// (We mutate fields rather than reassign so the closure inside the mock factory
// always reads the latest value.)
const sp: { tab: string | null; invite: string | null } = { tab: null, invite: null };

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
    removeMember: vi.fn(),
    setDefaultOrganization: vi.fn(),
    restoreOrganization: vi.fn(),
  },
}));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn() }),
  usePathname: () => '/app/settings/organization',
  useSearchParams: () => ({
    get: (k: string) => (k === 'tab' ? sp.tab : k === 'invite' ? sp.invite : null),
    toString: () => '',
  }),
}));
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }));
// Mutable so a test can toggle the workspace-creation entitlement (drives the Workspaces-tab
// upgrade card). The hook's internals use react-query (mocked thin above), so it must be stubbed.
const entitlements = vi.hoisted(() => ({ canCreateWorkspace: true }));
vi.mock('@/hooks/useWorkspaceEntitlements', () => ({
  useWorkspaceEntitlements: () => ({
    effectivePlanCode: entitlements.canCreateWorkspace ? 'PRO' : 'FREE',
    canCreateWorkspace: entitlements.canCreateWorkspace,
    canInviteTeammates: false,
  }),
}));
// The page calls reconcileCurrentOrgFromMemberships(orgs) inside fetchData, so the
// mock must export it (else the call throws and the page never renders content).
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: { getState: () => ({ setCurrentOrg: vi.fn() }) },
  reconcileCurrentOrgFromMemberships: vi.fn(),
}));
// Sibling panels/modals render identifiable stubs so a test can assert WHICH
// tab is mounted (Radix unmounts inactive <TabsContent>, so a stub is only in
// the DOM when its tab is active).
vi.mock('@/components/organization/InviteMemberModal', () => ({
  default: ({ open }: { open: boolean }) => (open ? <div data-testid="invite-modal" /> : null),
}));
vi.mock('@/components/organization/MemberAccessModal', () => ({ default: () => null }));
vi.mock('@/components/organization/MemberQuotaDialog', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationDangerZone', () => ({
  default: () => <div data-testid="danger-zone" />,
}));
vi.mock('@/components/organization/OrganizationAuditLogPanel', () => ({ default: () => null }));
vi.mock('@/components/organization/OrganizationSsoPanel', () => ({
  default: () => <div data-testid="sso-panel" />,
}));

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
  memberCount: 2,
  planCode: 'TEAM',
  maxMembers: 10,
  paused: false,
} as unknown as Organization;

const otherOrg = {
  id: 'org-2',
  name: 'Beta Workspace',
  slug: 'beta',
  isPersonal: false,
  avatarUrl: null,
  currentUserRole: 'ADMIN',
  isDefault: false,
  memberCount: 3,
  planCode: 'PRO',
  maxMembers: 3,
  paused: false,
} as unknown as Organization;

const orgFull = { ...orgSummary, members: [member], canInvite: true } as unknown as Organization;

function renderPage() {
  return render(
    <NextIntlClientProvider locale="en" messages={enMessages}>
      <OrganizationSettingsPage />
    </NextIntlClientProvider>
  );
}

describe('OrganizationSettingsPage - categorized tabs', () => {
  beforeEach(() => {
    sp.tab = null;
    sp.invite = null;
    entitlements.canCreateWorkspace = true;
    getOrganizations.mockResolvedValue([orgSummary, otherOrg]);
    getOrganization.mockResolvedValue(orgFull);
  });
  afterEach(cleanup);

  it('renders the four category tabs', async () => {
    renderPage();
    expect(await screen.findByRole('button', { name: 'Members' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Workspaces' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Security' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Advanced' })).toBeInTheDocument();
  });

  it('centers the category toggle row (matches the account overview tabs)', async () => {
    renderPage();
    const membersBtn = await screen.findByRole('button', { name: 'Members' });
    // Responsive centering (matches settings/overview): the pill group is centered
    // via `mx-auto` on its own container, which sits inside a horizontal-scroll
    // wrapper so narrow screens scroll instead of clipping tabs. `justify-center`
    // on the outer wrapper was removed by the responsive pill-bar fix (it clipped
    // tabs on narrow screens), so assert the mechanism that actually centers now.
    const scrollWrapper = membersBtn.closest('div.overflow-x-auto');
    expect(scrollWrapper).not.toBeNull();
    const pillGroup = membersBtn.parentElement;
    expect(pillGroup?.className).toContain('mx-auto');
  });

  it('defaults to the Members tab (members table visible, other tabs unmounted)', async () => {
    renderPage();
    expect(await screen.findByText('Jane Member')).toBeInTheDocument();
    // Inactive tabs are not in the DOM.
    expect(screen.queryByTestId('sso-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('danger-zone')).not.toBeInTheDocument();
    expect(screen.queryByText('Beta Workspace')).not.toBeInTheDocument();
  });

  it('keeps the current-workspace header visible on every tab', async () => {
    renderPage();
    // Header is the level-1 heading with the workspace name; it lives ABOVE the
    // toggle and must survive a tab switch.
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Advanced' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument();
  });

  it('Workspaces tab swaps in the workspace list and unmounts the members table', async () => {
    renderPage();
    await screen.findByText('Jane Member');

    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));

    expect(await screen.findByText('Beta Workspace')).toBeInTheDocument();
    expect(screen.queryByText('Jane Member')).not.toBeInTheDocument();
  });

  it('Security tab reveals the SSO panel only', async () => {
    renderPage();
    await screen.findByText('Jane Member');

    fireEvent.click(screen.getByRole('button', { name: 'Security' }));

    expect(await screen.findByTestId('sso-panel')).toBeInTheDocument();
    expect(screen.queryByText('Jane Member')).not.toBeInTheDocument();
    expect(screen.queryByTestId('danger-zone')).not.toBeInTheDocument();
  });

  it('Advanced tab reveals the danger zone', async () => {
    renderPage();
    await screen.findByText('Jane Member');

    fireEvent.click(screen.getByRole('button', { name: 'Advanced' }));

    expect(await screen.findByTestId('danger-zone')).toBeInTheDocument();
    expect(screen.queryByText('Jane Member')).not.toBeInTheDocument();
  });

  it('?tab=workspaces deep-link opens the Workspaces tab on load', async () => {
    sp.tab = 'workspaces';
    renderPage();

    expect(await screen.findByText('Beta Workspace')).toBeInTheDocument();
    expect(screen.queryByText('Jane Member')).not.toBeInTheDocument();
  });

  it('Workspaces tab shows the PRO upgrade card when the plan cannot create more workspaces', async () => {
    entitlements.canCreateWorkspace = false;
    renderPage();
    await screen.findByText('Jane Member');

    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));

    // Workspace-specific upsell (NOT the Members-tab Team card) → "Upgrade to PRO".
    expect(await screen.findByText('Additional workspaces need a PRO plan')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Upgrade to PRO/i })).toBeInTheDocument();
  });

  it('Workspaces tab hides the upgrade card when the plan can already create workspaces', async () => {
    entitlements.canCreateWorkspace = true;
    renderPage();
    await screen.findByText('Jane Member');

    fireEvent.click(screen.getByRole('button', { name: 'Workspaces' }));

    expect(await screen.findByText('Beta Workspace')).toBeInTheDocument(); // tab really switched
    expect(screen.queryByText('Additional workspaces need a PRO plan')).not.toBeInTheDocument();
  });

  it('?tab=security&invite=1 still lands on the Members tab and opens the invite modal', async () => {
    // invite=1 must win over the tab deep-link: the page forces "members" so the
    // invite modal is shown next to the team it acts on.
    sp.tab = 'security';
    sp.invite = '1';
    renderPage();

    expect(await screen.findByTestId('invite-modal')).toBeInTheDocument();
    expect(screen.getByText('Jane Member')).toBeInTheDocument();
    expect(screen.queryByTestId('sso-panel')).not.toBeInTheDocument();
  });
});
