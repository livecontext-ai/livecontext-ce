// @vitest-environment jsdom
//
// CE-only owner gate (2026-06-19): a non-manager MEMBER/VIEWER of the active workspace who clicks
// "Invite teammates" or "Create workspace" gets an INFORMATIONAL OwnerOnlyGateModal (the install
// belongs to the admin, everything runs on the admin's plan) - instead of the plan-gated invite /
// create / upgrade flow. OWNER/ADMIN and the cloud build keep the EXACT existing behavior. The role
// comes from the current-org store (currentOrgRole); the gate is CE-only (IS_CE).
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { UserSection } from '../AppSidebar';

// UserSection reads the build version (CE-only About entry). The hook calls useAuth(),
// which requires AppDataProvider; stub it so these menu tests stay provider-free.
vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => (key: string) => key,
}));

const navState = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn() }));
vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => navState,
}));

// Two queries: org memberships + CE cloud-link status. Route by key.
let mockWorkspaces: Array<{ id: string; name: string; isDefault?: boolean }> = [];
let mockCeLinkStatus: Record<string, unknown> | undefined;

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: (opts: { queryKey?: unknown[] }) => {
    if (JSON.stringify(opts.queryKey ?? []).includes('cloud-link')) {
      return { data: mockCeLinkStatus };
    }
    return { data: mockWorkspaces };
  },
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

// The store provides currentOrgId + currentOrgRole. The role drives the owner gate.
const orgState = vi.hoisted(() => ({ currentOrgId: 'ws1' as string | null, currentOrgRole: 'MEMBER' as string | null }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (
    selector: (state: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown,
  ) => selector({ currentOrgId: orgState.currentOrgId, currentOrgRole: orgState.currentOrgRole, setCurrentOrg: vi.fn() }),
}));

// Probe the gate decision: the informational owner-only modal vs the upsell modal vs the real create modal.
vi.mock('@/components/organization/OwnerOnlyGateModal', () => ({
  OwnerOnlyGateModal: ({ open, action }: { open: boolean; action?: string }) =>
    open ? <div data-testid="owner-only-gate-modal" data-action={action ?? ''} /> : null,
}));
vi.mock('@/components/organization/WorkspaceUpgradeModal', () => ({
  WorkspaceUpgradeModal: ({ open, variant }: { open: boolean; variant?: string }) =>
    open ? <div data-testid="workspace-upgrade-modal" data-variant={variant ?? 'teammates'} /> : null,
}));
vi.mock('@/components/organization/CreateWorkspaceModal', () => ({
  default: ({ open }: { open: boolean }) => (open ? <div data-testid="create-workspace-modal" /> : null),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

const editionState = vi.hoisted(() => ({ IS_CE: true }));
vi.mock('@/lib/edition', () => editionState);

function renderUserSection() {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'CE Member', email: 'member@example.com' }}
      avatarUrl={null}
      numericUserId={2}
      hasActiveSubscription={false}
      planCode="COMMUNITY"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={navState.push}
      displayName="CE Member"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

function openUserMenu() {
  fireEvent.click(screen.getByRole('button', { name: /CE Member/ }));
}

beforeEach(() => {
  editionState.IS_CE = true;
  orgState.currentOrgId = 'ws1';
  orgState.currentOrgRole = 'MEMBER';
  mockWorkspaces = [{ id: 'ws1', name: 'CE Workspace', isDefault: true }];
  // Admin-linked install with a TEAM plan so a manager WOULD pass the entitlement gate -
  // proving the member gate fires FIRST (the difference is the role, not the plan).
  mockCeLinkStatus = { linked: false, installLinked: true, installCloudPlanCode: 'TEAM' };
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('AppSidebar CE owner gate - non-manager member', () => {
  it('MEMBER clicking Create workspace opens the owner-only modal and does NOT open the create modal or navigate', async () => {
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: 'createWorkspace' }));

    const modal = await screen.findByTestId('owner-only-gate-modal');
    expect(modal).toHaveAttribute('data-action', 'workspace');
    expect(screen.queryByTestId('create-workspace-modal')).not.toBeInTheDocument();
    expect(screen.queryByTestId('workspace-upgrade-modal')).not.toBeInTheDocument();
    expect(navState.push).not.toHaveBeenCalled();
  });

  it('MEMBER clicking Invite teammates opens the owner-only modal and does NOT navigate to invite', async () => {
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: /CE Workspace/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'inviteTeammates' }));

    const modal = await screen.findByTestId('owner-only-gate-modal');
    expect(modal).toHaveAttribute('data-action', 'invite');
    expect(screen.queryByTestId('workspace-upgrade-modal')).not.toBeInTheDocument();
    expect(navState.push).not.toHaveBeenCalledWith('/app/settings/organization?invite=1');
  });

  it('VIEWER is also gated to the owner-only modal (treated as cannot-manage)', async () => {
    orgState.currentOrgRole = 'VIEWER';
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: 'createWorkspace' }));

    expect(await screen.findByTestId('owner-only-gate-modal')).toBeInTheDocument();
  });
});

describe('AppSidebar CE owner gate - manager keeps existing behavior', () => {
  it('OWNER clicking Create workspace opens the real create modal (TEAM-entitled), not the owner-only gate', async () => {
    orgState.currentOrgRole = 'OWNER';
    // A real CE org-owner is the install linker, so they hold a PER-USER cloud plan (cloudPlanCode) -
    // which is what grants team/workspace capability now, not the install-link visibility fallback.
    mockCeLinkStatus = { linked: true, installLinked: true, cloudPlanCode: 'TEAM', installCloudPlanCode: 'TEAM' };
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: 'createWorkspace' }));

    expect(await screen.findByTestId('create-workspace-modal')).toBeInTheDocument();
    expect(screen.queryByTestId('owner-only-gate-modal')).not.toBeInTheDocument();
  });

  it('OWNER clicking Invite teammates navigates to the invite flow (TEAM-entitled), not the owner-only gate', async () => {
    orgState.currentOrgRole = 'OWNER';
    // A real CE org-owner is the install linker, so they hold a PER-USER cloud plan (cloudPlanCode) -
    // which is what grants team/workspace capability now, not the install-link visibility fallback.
    mockCeLinkStatus = { linked: true, installLinked: true, cloudPlanCode: 'TEAM', installCloudPlanCode: 'TEAM' };
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: /CE Workspace/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'inviteTeammates' }));

    await waitFor(() => {
      expect(navState.push).toHaveBeenCalledWith('/app/settings/organization?invite=1');
    });
    expect(screen.queryByTestId('owner-only-gate-modal')).not.toBeInTheDocument();
  });

  it('ADMIN is treated as a manager (keeps existing create-workspace behavior)', async () => {
    orgState.currentOrgRole = 'ADMIN';
    // An ADMIN manager on a TEAM install resolves capability via the per-user cloud plan.
    mockCeLinkStatus = { linked: true, installLinked: true, cloudPlanCode: 'TEAM', installCloudPlanCode: 'TEAM' };
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: 'createWorkspace' }));

    expect(await screen.findByTestId('create-workspace-modal')).toBeInTheDocument();
    expect(screen.queryByTestId('owner-only-gate-modal')).not.toBeInTheDocument();
  });
});

describe('AppSidebar CE plan badge - member inherits the install link', () => {
  it('inheriting member (install-linked, per-user unlinked) shows "CE Free" (their own tier), NOT the install owner\'s "CE Team"', () => {
    orgState.currentOrgRole = 'MEMBER';
    // Member: linked=false but installLinked=true; the install plan (TEAM) is the OWNER's, not theirs.
    mockCeLinkStatus = { linked: false, installLinked: true, installCloudPlanCode: 'TEAM' };
    renderUserSection();

    // The badge reflects the member's OWN tier: they have no per-user cloud plan -> "CE Free".
    // It must NOT show the install owner's "CE Team" (illogical - the member paid nothing and the
    // backend 403's their invite/create), but it stays "CE ..." (cloud-connected), not "Community".
    expect(screen.getByText('CE Free')).toBeInTheDocument();
    expect(screen.queryByText('CE Team')).not.toBeInTheDocument();
    expect(screen.queryByText('Community')).not.toBeInTheDocument();
    // No upsell: a linked install bills on the admin's cloud account.
    expect(screen.queryByText('upgrade')).not.toBeInTheDocument();
  });

  it('install OWNER (per-user TEAM cloud plan) shows "CE Team" - their real tier', () => {
    orgState.currentOrgRole = 'OWNER';
    // The install linker holds a per-user cloud plan (cloudPlanCode), so their badge IS "CE Team".
    mockCeLinkStatus = { linked: true, installLinked: true, cloudPlanCode: 'TEAM', installCloudPlanCode: 'TEAM' };
    renderUserSection();

    expect(screen.getByText('CE Team')).toBeInTheDocument();
    expect(screen.queryByText('Community')).not.toBeInTheDocument();
  });

  it('unlinked install (no link at all) still shows Community + the upgrade upsell', () => {
    orgState.currentOrgRole = 'MEMBER';
    mockCeLinkStatus = { linked: false, installLinked: false };
    renderUserSection();

    expect(screen.getByText('Community')).toBeInTheDocument();
    expect(screen.getByText('upgrade')).toBeInTheDocument();
  });
});

describe('AppSidebar owner gate - cloud build is unaffected', () => {
  it('cloud (!IS_CE) MEMBER keeps the existing plan-gated upsell, never the owner-only modal', async () => {
    editionState.IS_CE = false;
    orgState.currentOrgRole = 'MEMBER';
    // Cloud: no governing cloud-link plan → the create-workspace upsell gate (WORKSPACE variant).
    mockCeLinkStatus = undefined;
    renderUserSection();
    openUserMenu();

    fireEvent.click(await screen.findByRole('button', { name: 'createWorkspace' }));

    expect(await screen.findByTestId('workspace-upgrade-modal')).toHaveAttribute('data-variant', 'workspace');
    expect(screen.queryByTestId('owner-only-gate-modal')).not.toBeInTheDocument();
  });
});
