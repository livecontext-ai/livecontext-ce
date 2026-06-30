// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ThemePreference } from '@/components/ThemeProvider';
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
      about: 'About',
      information: 'Information',
      workspace: 'Workspace',
      createWorkspace: 'Create workspace',
      inviteTeammates: 'Invite teammates',
      autoMode: 'Auto',
      lightMode: 'Light mode',
      darkMode: 'Dark mode',
      signOut: 'Sign out',
      upgrade: 'Upgrade',
      viewQuota: 'View quota',
      cost: 'Cost',
    };
    return (key: string) => labels[key] ?? key;
  },
}));

const mockPush = vi.fn();
const mockRefresh = vi.fn();

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({
    push: mockPush,
    refresh: mockRefresh,
  }),
}));

// Configurable workspace list returned by the org-memberships useQuery. Default empty (the
// pre-resolved/initial-load case); individual tests set a workspace to exercise the switcher.
let mockWorkspaces: Array<{ id: string; name: string; isDefault?: boolean; avatarUrl?: string | null; paused?: boolean; pendingDeletion?: boolean }> = [];

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: () => ({ data: mockWorkspaces }),
  // AppSidebar uses useQueryClient (workspace switch/restore invalidation); the bare UserSection
  // render has no QueryClientProvider, so stub it to avoid "No QueryClient set".
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (state: { currentOrgId: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, setCurrentOrg: vi.fn() }),
}));

// Render a marker carrying the variant so tests can assert WHICH upsell the gate opens:
// 'workspace' (→ PRO, extra workspaces) vs 'teammates' (→ TEAM, collaboration).
vi.mock('@/components/organization/WorkspaceUpgradeModal', () => ({
  WorkspaceUpgradeModal: ({ open, variant }: { open: boolean; variant?: string }) =>
    open ? <div data-testid="workspace-upgrade-modal" data-variant={variant ?? 'teammates'} /> : null,
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('@/lib/edition', () => ({
  IS_CE: false,
}));

const themeLabels: Record<ThemePreference, string> = {
  auto: 'Auto',
  light: 'Light mode',
  dark: 'Dark mode',
};

function renderUserSection(themePreference: ThemePreference, onThemeChange = vi.fn(), planCode = 'FREE') {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Owner E2E', email: 'owner@example.com' }}
      avatarUrl={null}
      numericUserId={42}
      hasActiveSubscription={false}
      planCode={planCode}
      isSubscriptionLoading={false}
      themePreference={themePreference}
      onThemeChange={onThemeChange}
      onSignOut={vi.fn()}
      onNavigate={vi.fn()}
      displayName="Owner E2E"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

function openThemeSubmenu(currentThemePreference: ThemePreference) {
  fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
  fireEvent.click(screen.getByRole('button', { name: themeLabels[currentThemePreference] }));
}

describe('AppSidebar user theme menu', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockWorkspaces = [];
  });

  // Regression: a user with no uploaded photo used to get a generic lucide person icon
  // here (and an agent preset SVG on the public profile). Both surfaces now render the
  // canonical user-avatar endpoint (uploaded photo OR server-generated initials SVG),
  // so the avatar is the same everywhere - never a person icon, never an agent preset.
  // Fixture: avatarUrl=null, numericUserId=42, displayName "Owner E2E".
  it('regression: with no photo, the user avatar renders the canonical user endpoint (initials SVG), not a person icon', () => {
    renderUserSection('auto');

    const avatar = screen.getByAltText('Owner E2E');
    expect(avatar.tagName).toBe('IMG');
    expect(avatar).toHaveAttribute('src', '/api/proxy/users/42/avatar');
    expect(avatar.getAttribute('src') ?? '').not.toContain('/avatars/avatar-');
  });

  // Regression: a FREE/STARTER user (single personal workspace, no create entitlement) used to see
  // a plain "Invite teammates" row INSTEAD of their workspace - the workspace focus only appeared
  // for PRO+ (canCreateWorkspace) or multi-workspace users. Now every tier sees the workspace row,
  // same as PRO.
  it('shows the active workspace row (avatar + name) at the TOP level for a FREE user - same as PRO', async () => {
    mockWorkspaces = [{ id: 'ws1', name: 'My Workspace', isDefault: true }];
    renderUserSection('auto', vi.fn(), 'FREE');

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /My Workspace/ })).toBeInTheDocument();
    });
    // The teammates upsell is no longer a top-level row; it lives inside the (collapsed) switch submenu.
    expect(screen.queryByRole('button', { name: 'Invite teammates' })).not.toBeInTheDocument();
  });

  it('renders Create workspace as a TOP-LEVEL menu item (sibling of the workspace focus, not in the switch submenu)', async () => {
    renderUserSection('auto', vi.fn(), 'PRO');

    // Open the user menu.
    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));

    // The workspace focus row is the top-level switcher; Create workspace must be visible at the
    // SAME level immediately - WITHOUT expanding the switch submenu (which is collapsed by default).
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Create workspace' })).toBeInTheDocument();
    });
    // The switch submenu is still collapsed, so the per-workspace "Invite teammates" submenu row is absent.
    expect(screen.queryByRole('button', { name: 'Invite teammates' })).not.toBeInTheDocument();
  });

  it('also shows Create workspace for FREE/STARTER (upsell entry point → upgrade gate)', async () => {
    renderUserSection('auto', vi.fn(), 'FREE');

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));

    // Every tier sees Create workspace as an entry point; for FREE/STARTER its handler routes to
    // the upgrade gate (WorkspaceUpgradeModal → /settings/pricing) instead of the create modal.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Create workspace' })).toBeInTheDocument();
    });
  });

  // Regression: a STARTER/FREE user clicking "Create workspace" used to get the teammates/TEAM
  // upsell ("Team collaboration requires a TEAM plan"). Additional workspaces unlock on PRO, so the
  // gate must open the WORKSPACE variant (→ PRO), NOT the teammates one.
  it('opens the WORKSPACE upgrade variant when FREE clicks Create workspace (not the teammates/TEAM one)', async () => {
    renderUserSection('auto', vi.fn(), 'FREE');

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Create workspace' }));

    const modal = await screen.findByTestId('workspace-upgrade-modal');
    expect(modal).toHaveAttribute('data-variant', 'workspace');
  });

  it('opens the TEAMMATES upgrade variant when FREE clicks Invite teammates (inside the workspace submenu)', async () => {
    mockWorkspaces = [{ id: 'ws1', name: 'My Workspace', isDefault: true }];
    renderUserSection('auto', vi.fn(), 'FREE');

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
    // Invite teammates now lives inside the workspace switch submenu → expand the workspace row first.
    fireEvent.click(await screen.findByRole('button', { name: /My Workspace/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Invite teammates' }));

    const modal = await screen.findByTestId('workspace-upgrade-modal');
    expect(modal).toHaveAttribute('data-variant', 'teammates');
  });

  it('opens the CREATE modal (not the upgrade gate) when a PRO user clicks Create workspace', async () => {
    renderUserSection('auto', vi.fn(), 'PRO');

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Create workspace' }));

    // PRO can create workspaces directly → the create form opens; the upgrade gate must NOT.
    expect(await screen.findByPlaceholderText('workspaceNamePlaceholder')).toBeInTheDocument();
    expect(screen.queryByTestId('workspace-upgrade-modal')).not.toBeInTheDocument();
  });

  it('shows Auto, Light, and Dark choices from the user menu theme row', async () => {
    renderUserSection('auto');

    openThemeSubmenu('auto');

    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'Auto' })).toHaveLength(2);
    });
    expect(screen.getByRole('button', { name: 'Light mode' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dark mode' })).toBeInTheDocument();
  });

  it.each([
    ['auto', 'light'],
    ['light', 'auto'],
    ['dark', 'auto'],
  ] as Array<[ThemePreference, ThemePreference]>)(
    'selecting %s forwards that theme preference',
    async (targetThemePreference, currentThemePreference) => {
      const onThemeChange = vi.fn();
      renderUserSection(currentThemePreference, onThemeChange);

      openThemeSubmenu(currentThemePreference);
      fireEvent.click(screen.getByRole('button', { name: themeLabels[targetThemePreference] }));

      expect(onThemeChange).toHaveBeenCalledWith(targetThemePreference);
      await waitFor(() => {
        expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
      });
    },
  );
});
