// @vitest-environment jsdom
/**
 * The sidebar language menu is one of the two places a person PICKS a language: the pick must
 * reach the backend as an explicit choice (reportExplicitLocaleChoice) next to the cookie and the
 * locale navigation, or the lifecycle emails keep the language the app merely displayed. The
 * reporter's own contract is covered by lib/lifecycle/__tests__/localeChoice.test.ts.
 * Harness shared with AppSidebar.themeMenu.test.tsx.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

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

const reportExplicitLocaleChoice = vi.hoisted(() => vi.fn());
vi.mock('@/lib/lifecycle/localeChoice', () => ({ reportExplicitLocaleChoice }));

function renderUserSection() {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Owner E2E', email: 'owner@example.com' }}
      avatarUrl={null}
      numericUserId={42}
      planCode="FREE"
      isSubscriptionLoading={false}
      themePreference="auto"
      onThemeChange={vi.fn()}
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

describe('AppSidebar user language menu', () => {
  beforeEach(() => {
    document.cookie = 'NEXT_LOCALE=; path=/; max-age=0';
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    mockWorkspaces = [];
  });

  it('picking a language reports it as an explicit choice, sets the cookie and navigates to that locale', () => {
    renderUserSection();

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
    fireEvent.click(screen.getByRole('button', { name: /English/ }));
    fireEvent.click(screen.getByRole('button', { name: /Fran/ }));

    expect(reportExplicitLocaleChoice).toHaveBeenCalledTimes(1);
    expect(reportExplicitLocaleChoice).toHaveBeenCalledWith('fr');
    expect(document.cookie).toContain('NEXT_LOCALE=fr');
    expect(mockPush).toHaveBeenCalledWith('/en/app/chat', { locale: 'fr' });
  });

  it('opening the language submenu alone reports nothing', () => {
    renderUserSection();

    fireEvent.click(screen.getByRole('button', { name: /Owner E2E/ }));
    fireEvent.click(screen.getByRole('button', { name: /English/ }));

    expect(screen.getByRole('button', { name: /Fran/ })).toBeInTheDocument();
    expect(reportExplicitLocaleChoice).not.toHaveBeenCalled();
  });
});
