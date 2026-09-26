// @vitest-environment jsdom
/**
 * CE sidebar: a cloud link the cloud refuses for CLOUD_LINK_PLAN_REQUIRED (status.planRequired)
 * shows a "Paid plan required" badge in the Upgrade CTA's place, opening the CLOUD pricing page
 * in a new tab. Never on a healthy link, never in the cloud edition.
 */
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
    const labels: Record<string, string> = { upgrade: 'Upgrade', viewQuota: 'View quota', cost: 'Cost' };
    return (key: string) => labels[key] ?? key;
  },
}));

const cloudLink = vi.hoisted(() => ({
  status: { installLinked: false, cloudPlanCode: null as string | null } as Record<string, unknown>,
}));

vi.mock('@/i18n/navigation', () => ({
  usePathname: () => '/en/app/chat',
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));

// Two queries live in this component: the org memberships and, in CE, the
// cloud-link status. Route by query key - returning the workspace list for both
// would leave `installLinked` undefined and quietly make every CE case read
// "unlinked", which is the answer one of these tests is trying to disprove.
vi.mock('@tanstack/react-query', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tanstack/react-query')>()),
  useQuery: (opts: { queryKey?: unknown[] }) =>
    JSON.stringify(opts.queryKey ?? []).includes('cloud-link')
      ? { data: cloudLink.status }
      : { data: [] },
  useQueryClient: () => ({ invalidateQueries: vi.fn(() => Promise.resolve()), setQueryData: vi.fn() }),
}));

vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrgStore: (selector: (s: { currentOrgId: string | null; currentOrgRole: string | null; setCurrentOrg: () => void }) => unknown) =>
    selector({ currentOrgId: null, currentOrgRole: 'OWNER', setCurrentOrg: vi.fn() }),
}));

vi.mock('@/components/billing/BalanceBreakdown', () => ({
  BalanceBreakdownTooltip: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// The credit block owns its own tests; here it only has to not require a
// QueryClientProvider, so the whole component is stubbed out.
// The ring MUST pass its children through even when stubbed: it wraps the
// avatar, so a stub returning null would take the avatar out of the sidebar and
// make every assertion here read a tree the app never renders.
vi.mock('@/components/billing/SidebarCreditBalance', () => ({
  SidebarCreditRing: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
  SidebarCreditMenuSection: () => null,
}));

const edition = vi.hoisted(() => ({ isCe: false }));
vi.mock('@/lib/edition', () => ({
  get IS_CE() {
    return edition.isCe;
  },
}));

vi.mock('@/lib/api/cloud-link.service', () => ({
  CLOUD_NO_SUBSCRIPTION: '__NONE__',
  cloudLinkService: { getStatus: () => Promise.resolve(cloudLink.status) },
}));

function renderUserSection(planCode: string | null) {
  render(
    <UserSection
      sidebarCollapsed={false}
      user={{ name: 'Owner E2E', email: 'owner@example.com' }}
      avatarUrl={null}
      numericUserId={42}
      planCode={planCode}
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

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  edition.isCe = false;
  cloudLink.status = { installLinked: false, cloudPlanCode: null };
});

describe('AppSidebar cloud-link paid-plan badge', () => {
  it('shows the badge on a CE install whose cloud link needs a paid plan, and opens cloud pricing in a new tab', async () => {
    edition.isCe = true;
    cloudLink.status = { installLinked: true, linked: true, cloudPlanCode: 'FREE', planRequired: true, planRequiredPlanCode: 'FREE' };
    const open = vi.spyOn(window, 'open').mockImplementation(() => null);
    renderUserSection('FREE');

    const badge = await screen.findByTestId('sidebar-cloud-plan-required');
    expect(badge).toHaveTextContent('sidebarBadge');
    // The linked install keeps no Upgrade CTA: the badge is the only upsell.
    expect(screen.queryByText('Upgrade')).toBeNull();

    fireEvent.click(badge);
    expect(open).toHaveBeenCalledWith('https://livecontext.ai/app/settings/pricing', '_blank', 'noopener,noreferrer');

    open.mockClear();
    fireEvent.keyDown(badge, { key: 'Enter' });
    expect(open).toHaveBeenCalledTimes(1);
    open.mockRestore();
  });

  it('shows no badge on a healthy CE link', async () => {
    edition.isCe = true;
    cloudLink.status = { installLinked: true, linked: true, cloudPlanCode: 'PRO', planRequired: false };
    renderUserSection('FREE');

    await waitFor(() => expect(screen.getByText('CE Pro')).toBeInTheDocument());
    expect(screen.queryByTestId('sidebar-cloud-plan-required')).toBeNull();
  });

  it('never shows the badge in the cloud edition', () => {
    edition.isCe = false;
    cloudLink.status = { planRequired: true };
    renderUserSection('FREE');

    expect(screen.queryByTestId('sidebar-cloud-plan-required')).toBeNull();
  });
});
