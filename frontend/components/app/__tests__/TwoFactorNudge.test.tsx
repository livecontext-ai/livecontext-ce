// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MfaStatus } from '@/lib/api/services/user-api.service';
import type { Organization } from '@/lib/api/organization-api';

/**
 * The one-time "protect your team with two-factor" suggestion: only to the OWNER of a team
 * workspace (Team plan or above) without two-factor, on the cloud, until dismissed there.
 *
 * <p>The subscription is mocked the way production answers it: `activeOrgPlanCode` is the
 * gateway's X-User-Plan, which follows the user's DEFAULT workspace, not the active one, and
 * `subscription.planCode` is the user's own plan. The header is TEAM by default here on purpose:
 * the pre-fix component took its plan from it, which is what the regression tests catch.
 */
const mocks = vi.hoisted(() => ({
  isCloud: true,
  auth: { isAuthenticated: true, isLoading: false, user: { sub: 'kc-owner' } } as {
    isAuthenticated: boolean; isLoading: boolean; user?: { sub: string };
  },
  orgId: 'org-1' as string | null,
  isOwner: true,
  activeOrgPlanCode: 'TEAM' as string | null,
  ownPlanCode: 'TEAM' as string | null,
  getOrganizations: vi.fn(),
  getMfaStatus: vi.fn(),
  track: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
vi.mock('next/link', () => ({
  default: ({ href, onClick, children }: { href: string; onClick?: () => void; children: React.ReactNode }) => (
    <a href={href} onClick={(e) => { e.preventDefault(); onClick?.(); }}>{children}</a>
  ),
}));
vi.mock('@/lib/edition', () => ({ get IS_CLOUD() { return mocks.isCloud; }, IS_CE: false }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => mocks.auth }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId: mocks.orgId }),
  useIsCurrentOrgOwner: () => mocks.isOwner,
}));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({
  useSubscription: () => ({
    subscription: { activeOrgPlanCode: mocks.activeOrgPlanCode, subscription: mocks.ownPlanCode ? { planCode: mocks.ownPlanCode } : undefined },
  }),
}));
// Unused by the component; kept so the pre-fix version (useWorkspaceEntitlements) still loads
// and the regression tests can be run against it.
vi.mock('@/hooks/useCeCloudLinkStatus', () => ({ useCeCloudLinkStatus: () => ({ status: null }) }));
vi.mock('@/lib/api/organization-api', () => ({ organizationApi: { getOrganizations: mocks.getOrganizations } }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: { getMfaStatus: mocks.getMfaStatus } }));
vi.mock('@/lib/analytics/analytics', () => ({ track: mocks.track }));
vi.mock('@/components/settings/TwoFactorSettingsCard', () => ({
  MFA_STATUS_QUERY_KEY: ['user', 'mfa-status'],
  TWO_FACTOR_RETURN_TO: '/app/settings/overview?tab=security',
}));

import TwoFactorNudge, { TWO_FACTOR_NUDGE_PREFIX } from '../TwoFactorNudge';

const status = (overrides: Partial<MfaStatus> = {}): MfaStatus => ({
  available: true, totpEnabled: false, devices: [], required: false, setupPending: false, recoveryCodes: null,
  ...overrides,
});

const workspace = (overrides: Partial<Organization>): Organization => ({
  id: 'org-1', name: 'Acme', slug: 'acme', isPersonal: false, avatarUrl: null,
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
  currentUserRole: 'OWNER', isDefault: true, memberCount: 3, planCode: 'TEAM',
  ...overrides,
});

function renderNudge() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TwoFactorNudge />
    </QueryClientProvider>,
  );
}

const dismissKey = (orgId: string) => `${TWO_FACTOR_NUDGE_PREFIX}:kc-owner:${orgId}`;
const settle = () => new Promise((r) => setTimeout(r, 20));

describe('TwoFactorNudge', () => {
  beforeEach(() => {
    mocks.isCloud = true;
    mocks.auth = { isAuthenticated: true, isLoading: false, user: { sub: 'kc-owner' } };
    mocks.orgId = 'org-1';
    mocks.isOwner = true;
    mocks.activeOrgPlanCode = 'TEAM';
    mocks.ownPlanCode = 'TEAM';
    mocks.getOrganizations.mockReset().mockResolvedValue([
      workspace({ id: 'org-1' }),
      workspace({ id: 'org-2', name: 'Beta', slug: 'beta', isDefault: false }),
    ]);
    mocks.getMfaStatus.mockReset().mockResolvedValue(status());
    mocks.track.mockReset();
    window.localStorage.clear();
  });

  afterEach(() => cleanup());

  it('suggests two-factor to the owner of a team workspace who has none, once', async () => {
    renderNudge();

    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'cta' })).toHaveAttribute('href', '/en/app/settings/overview?tab=security');
    expect(mocks.track).toHaveBeenCalledWith('mfa_nudge_shown', {});
  });

  it('reports "shown" once per workspace, not on every render', async () => {
    const view = renderNudge();
    await screen.findByTestId('two-factor-nudge');
    view.rerender(
      <QueryClientProvider client={new QueryClient()}>
        <TwoFactorNudge />
      </QueryClientProvider>,
    );
    await screen.findByTestId('two-factor-nudge');

    expect(mocks.track.mock.calls.filter(([name]) => name === 'mfa_nudge_shown')).toHaveLength(1);
  });

  it('switching to a workspace where it was dismissed hides it without a remount', async () => {
    window.localStorage.setItem(dismissKey('org-2'), 'dismissed');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={client}>
        <TwoFactorNudge />
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();

    mocks.orgId = 'org-2';
    view.rerender(
      <QueryClientProvider client={client}>
        <TwoFactorNudge />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument());
  });

  it('stays gone for this owner and workspace once dismissed', async () => {
    renderNudge();
    fireEvent.click(await screen.findByRole('button', { name: 'dismiss' }));

    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(window.localStorage.getItem(dismissKey('org-1'))).toBe('dismissed');
    expect(mocks.track).toHaveBeenCalledWith('mfa_nudge_dismissed', {});

    cleanup();
    renderNudge();
    await waitFor(() => expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument());
  });

  it('following the suggestion also retires it, and is tracked as a click', async () => {
    renderNudge();
    fireEvent.click(await screen.findByRole('link', { name: 'cta' }));

    expect(window.localStorage.getItem(dismissKey('org-1'))).toBe('dismissed');
    expect(mocks.track).toHaveBeenCalledWith('mfa_nudge_clicked', {});
  });

  it('a dismissal in one workspace does not hide it in another team the user owns', async () => {
    window.localStorage.setItem(dismissKey('org-1'), 'dismissed');
    mocks.orgId = 'org-2';
    renderNudge();

    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();
  });

  it('suggests it on an Enterprise workspace the user owns', async () => {
    mocks.ownPlanCode = 'ENTERPRISE_PLUS';
    mocks.getOrganizations.mockResolvedValue([workspace({ planCode: 'ENTERPRISE_PLUS' })]);
    renderNudge();

    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();
  });

  it('suggests it on a personal workspace whose owner is on Team (the server lets them invite into it)', async () => {
    mocks.getOrganizations.mockResolvedValue([workspace({ isPersonal: true })]);
    renderNudge();

    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();
  });

  // Regression, cloud e2e ORG-MAIL-003 (2026-10-02): a member of a Team workspace whose browser
  // was still on their own FREE personal workspace (OWNER there) was told "You own this team
  // workspace": ownership came from the active workspace, the plan from the default one.
  it('does not tell a team MEMBER they own a team when their browser is on their own free workspace', async () => {
    mocks.orgId = 'org-personal';
    mocks.isOwner = true;
    mocks.activeOrgPlanCode = 'TEAM';
    mocks.ownPlanCode = 'FREE';
    mocks.getOrganizations.mockResolvedValue([
      workspace({ id: 'org-personal', name: 'Mine', isPersonal: true, isDefault: false, planCode: 'FREE', memberCount: 1 }),
      workspace({ id: 'org-team', name: 'Acme', currentUserRole: 'MEMBER', isDefault: true, planCode: 'TEAM' }),
    ]);
    renderNudge();

    // Settle, then assert the card first, so the pre-fix code fails on the symptom itself.
    await settle();
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
    // Their own plan is FREE, so nothing is even asked: no workspace list either.
    expect(mocks.getOrganizations).not.toHaveBeenCalled();
  });

  it('switching in place to a team workspace the user only belongs to hides it', async () => {
    mocks.getOrganizations.mockResolvedValue([
      workspace({ id: 'org-1' }),
      workspace({ id: 'org-3', name: 'Other', currentUserRole: 'MEMBER', isDefault: false }),
    ]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = render(
      <QueryClientProvider client={client}>
        <TwoFactorNudge />
      </QueryClientProvider>,
    );
    expect(await screen.findByTestId('two-factor-nudge')).toBeInTheDocument();

    // The store role lags (still OWNER) while the cached list already says MEMBER there.
    mocks.orgId = 'org-3';
    view.rerender(
      <QueryClientProvider client={client}>
        <TwoFactorNudge />
      </QueryClientProvider>,
    );

    await waitFor(() => expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument());
  });

  it.each([
    ['a MEMBER whose stored role still says owner', 'MEMBER' as const],
    ['an ADMIN, who manages people but does not hold the billing', 'ADMIN' as const],
    ['a VIEWER', 'VIEWER' as const],
  ])('shows nothing to %s of a team workspace', async (_label, role) => {
    mocks.getOrganizations.mockResolvedValue([workspace({ currentUserRole: role })]);
    renderNudge();

    await waitFor(() => expect(mocks.getOrganizations).toHaveBeenCalled());
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
  });

  it.each([
    ['below Team, even when the default workspace header says Team', 'PRO'],
    ['unknown', undefined],
  ])('shows nothing on an owned workspace whose plan is %s', async (_label, planCode) => {
    mocks.getOrganizations.mockResolvedValue([workspace({ planCode })]);
    renderNudge();

    await waitFor(() => expect(mocks.getOrganizations).toHaveBeenCalled());
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
  });

  it.each([
    ['still loading', () => { mocks.getOrganizations.mockReturnValue(new Promise(() => {})); }],
    ['unreadable', () => { mocks.getOrganizations.mockRejectedValue(new Error('503')); }],
    ['missing the active workspace', () => { mocks.getOrganizations.mockResolvedValue([workspace({ id: 'org-9' })]); }],
  ])('shows nothing while the workspace list is %s', async (_label, arrange) => {
    arrange();
    renderNudge();

    await waitFor(() => expect(mocks.getOrganizations).toHaveBeenCalled());
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
  });

  it.each([
    ['someone the active-workspace store does not see as owner', () => { mocks.isOwner = false; }],
    ['no active workspace resolved yet', () => { mocks.orgId = null; }],
    ['the self-hosted edition', () => { mocks.isCloud = false; }],
    ['a session still resolving', () => { mocks.auth = { isAuthenticated: false, isLoading: true }; }],
    ['a user whose own plan is below Team (no workspace they own can be a Team one)', () => { mocks.ownPlanCode = 'PRO'; }],
    ['a user whose own plan is not known yet', () => { mocks.ownPlanCode = null; }],
  ])('asks nothing and shows nothing for %s', async (_label, arrange) => {
    arrange();
    renderNudge();

    await settle();
    expect(mocks.getOrganizations).not.toHaveBeenCalled();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
  });

  it('asks nothing once dismissed: no status request on any later page', async () => {
    window.localStorage.setItem(dismissKey('org-1'), 'dismissed');
    renderNudge();

    await settle();
    expect(mocks.getOrganizations).not.toHaveBeenCalled();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
  });

  it.each([
    ['two-factor is already on', status({ totpEnabled: true })],
    ['enrollment is already pending at the next sign-in', status({ setupPending: true })],
    ['the account cannot hold a factor', status({ available: false })],
  ])('shows nothing when %s', async (_label, answer) => {
    mocks.getMfaStatus.mockResolvedValue(answer);
    renderNudge();

    await waitFor(() => expect(mocks.getMfaStatus).toHaveBeenCalled());
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.track).not.toHaveBeenCalled();
  });

  it('shows nothing when the status cannot be read, rather than guessing it is off', async () => {
    mocks.getMfaStatus.mockRejectedValue(new Error('503'));
    renderNudge();

    await waitFor(() => expect(mocks.getMfaStatus).toHaveBeenCalled());
    await settle();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
  });
});
