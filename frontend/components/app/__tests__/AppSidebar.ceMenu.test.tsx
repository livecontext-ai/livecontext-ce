// @vitest-environment jsdom
//
// CE-edition user menu parity (2026-06-10): the workspace switcher, Create workspace and
// the Cost (quota) entry used to be hidden behind a stale `!IS_CE` gate from the
// single-tenant CE era. CE supports organizations/workspaces (cloud-link-governed caps),
// so the menu now renders them in BOTH editions; CE entitlement affordances read the
// governing cloud plan from cloudLinkService.getStatus().cloudPlanCode (conservative:
// no governing cloud plan → upgrade gate, even if a locally-granted plan might pass the
// backend check).
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UserSection } from '../AppSidebar';

// UserSection reads the build version (CE-only About entry). The hook calls useAuth(),
// which requires AppDataProvider; stub it so these menu tests stay provider-free.
vi.mock('@/hooks/useAppVersion', () => ({
  useAppVersion: () => ({ version: null, isLoading: false, isError: false }),
}));

vi.mock('next-intl', () => ({
  useLocale: () => 'en',
  useTranslations: () => {
    const labels: Record<string, string> = {
      settings: 'Settings',
      pricing: 'Pricing',
      credits: 'Credits',
      cost: 'Cost',
      about: 'About',
      workspace: 'Workspace',
      createWorkspace: 'Create workspace',
      inviteTeammates: 'Invite teammates',
      referAndEarn: 'Refer & earn',
      autoMode: 'Auto',
      lightMode: 'Light mode',
      darkMode: 'Dark mode',
      signOut: 'Sign out',
    };
    return (key: string) => labels[key] ?? key;
  },
}));

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

// Per-queryKey dispatch: the component mounts TWO queries (org memberships +
// CE cloud-link status). The mock routes by key so each test can vary the
// linked plan independently of the workspace list.
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

// currentOrgRole OWNER so the CE owner gate (non-managers see the informational
// OwnerOnlyGateModal) does NOT fire here - these tests cover the manager/entitlement path.
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (
    selector: (state: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown,
  ) => selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/organization/WorkspaceUpgradeModal', () => ({
  WorkspaceUpgradeModal: ({ open, variant }: { open: boolean; variant?: string }) =>
    open ? <div data-testid="workspace-upgrade-modal" data-variant={variant ?? 'teammates'} /> : null,
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// The point of this file: the COMMUNITY edition.
vi.mock('@/lib/edition', () => ({
  IS_CE: true,
}));

function renderCeUserSection() {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'CE Owner', email: 'ce@example.com' }}
      avatarUrl={null}
      numericUserId={1}
      hasActiveSubscription={false}
      planCode="COMMUNITY"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={vi.fn()}
      displayName="CE Owner"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

function openUserMenu() {
  fireEvent.click(screen.getByRole('button', { name: /CE Owner/ }));
}

describe('AppSidebar user menu in CE edition', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockWorkspaces = [];
    mockCeLinkStatus = undefined;
  });

  it('shows the workspace row, Create workspace and the Cost entry (parity with cloud)', async () => {
    mockWorkspaces = [{ id: 'ws1', name: 'CE Workspace', isDefault: true }];
    mockCeLinkStatus = { linked: false };
    renderCeUserSection();

    openUserMenu();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /CE Workspace/ })).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: 'Create workspace' })).toBeInTheDocument();
    // CE labels the quota entry "Cost" ($-denominated CeQuotaPage), not "Credits".
    expect(screen.getByRole('button', { name: 'Cost' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Credits' })).not.toBeInTheDocument();
    // Refer & earn is edition-agnostic: it points at the rewards page, available in CE too
    // (CE reads its bound cloud account), so the menu line shows here as well.
    expect(screen.getByRole('button', { name: 'Refer & earn' })).toBeInTheDocument();
  });

  it('unlinked CE (no governing cloud plan) routes Create workspace to the WORKSPACE upgrade gate', async () => {
    mockCeLinkStatus = { linked: false };
    renderCeUserSection();

    openUserMenu();
    fireEvent.click(await screen.findByRole('button', { name: 'Create workspace' }));

    const modal = await screen.findByTestId('workspace-upgrade-modal');
    expect(modal).toHaveAttribute('data-variant', 'workspace');
  });

  it('TEAM-linked CE opens the real create-workspace modal (entitled via cloudPlanCode)', async () => {
    mockCeLinkStatus = { linked: true, registered: true, llmSource: 'CLOUD', cloudPlanCode: 'TEAM' };
    renderCeUserSection();

    openUserMenu();
    fireEvent.click(await screen.findByRole('button', { name: 'Create workspace' }));

    expect(await screen.findByPlaceholderText('workspaceNamePlaceholder')).toBeInTheDocument();
    expect(screen.queryByTestId('workspace-upgrade-modal')).not.toBeInTheDocument();
  });

  it('TEAM-linked CE gets the real Invite teammates flow (no teammates upsell modal)', async () => {
    mockWorkspaces = [{ id: 'ws1', name: 'CE Workspace', isDefault: true }];
    mockCeLinkStatus = { linked: true, registered: true, llmSource: 'CLOUD', cloudPlanCode: 'TEAM' };
    renderCeUserSection();

    openUserMenu();
    fireEvent.click(await screen.findByRole('button', { name: /CE Workspace/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Invite teammates' }));

    expect(screen.queryByTestId('workspace-upgrade-modal')).not.toBeInTheDocument();
  });
});
