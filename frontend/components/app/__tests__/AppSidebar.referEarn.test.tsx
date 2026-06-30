// @vitest-environment jsdom
//
// "Refer & earn" user-menu entry: a second entry point (alongside the settings nav) to the
// rewards page where the user shares their referral code/link and both parties earn credits.
// The line is edition-agnostic; this file covers the CLOUD edition (IS_CE false) and asserts
// both that it renders and that clicking it navigates to /app/settings/rewards (then closes
// the menu). CE-edition presence is covered in AppSidebar.ceMenu.test.tsx.
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { UserSection } from '../AppSidebar';

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

let mockWorkspaces: Array<{ id: string; name: string; isDefault?: boolean }> = [];

vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: (opts: { queryKey?: unknown[] }) => {
    if (JSON.stringify(opts.queryKey ?? []).includes('cloud-link')) {
      return { data: undefined };
    }
    return { data: mockWorkspaces };
  },
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (
    selector: (state: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown,
  ) => selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/organization/WorkspaceUpgradeModal', () => ({
  WorkspaceUpgradeModal: ({ open }: { open: boolean }) => (open ? <div data-testid="workspace-upgrade-modal" /> : null),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// Cloud edition (the rewards entry is NOT CE-gated; it shows in both editions).
vi.mock('@/lib/edition', () => ({
  IS_CE: false,
}));

function renderCloudUserSection(onNavigate: () => void) {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Cloud User', email: 'cloud@example.com' }}
      avatarUrl={null}
      numericUserId={1}
      hasActiveSubscription={true}
      planCode="PRO"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
      onSignOut={vi.fn()}
      onNavigate={onNavigate}
      displayName="Cloud User"
      isLoadingProfile={false}
      creditBalance={null}
      creditSubBalance={null}
      creditPaygBalance={null}
      isCreditBalanceLoading={false}
    />,
  );
}

function openUserMenu() {
  fireEvent.click(screen.getByRole('button', { name: /Cloud User/ }));
}

describe('AppSidebar user menu - Refer & earn entry', () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockWorkspaces = [];
  });

  it('renders the Refer & earn entry in the user menu', async () => {
    renderCloudUserSection(vi.fn());

    openUserMenu();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Refer & earn' })).toBeInTheDocument();
    });
  });

  it('navigates to the rewards page when Refer & earn is clicked', async () => {
    const onNavigate = vi.fn();
    renderCloudUserSection(onNavigate);

    openUserMenu();
    fireEvent.click(await screen.findByRole('button', { name: 'Refer & earn' }));

    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(onNavigate).toHaveBeenCalledWith('/app/settings/rewards');
    // The onClick also closes the menu (setShowMenu(false)): the entry unmounts.
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Refer & earn' })).not.toBeInTheDocument();
    });
  });
});
