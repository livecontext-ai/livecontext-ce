// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MfaStatus } from '@/lib/api/services/user-api.service';

/**
 * The one-time "protect your team with two-factor" suggestion: only to the OWNER of a team
 * workspace (Team plan or above) without two-factor, on the cloud, until dismissed there.
 */
const mocks = vi.hoisted(() => ({
  isCloud: true,
  auth: { isAuthenticated: true, isLoading: false, user: { sub: 'kc-owner' } } as {
    isAuthenticated: boolean; isLoading: boolean; user?: { sub: string };
  },
  orgId: 'org-1' as string | null,
  isOwner: true,
  canInviteTeammates: true,
  getMfaStatus: vi.fn(),
  track: vi.fn(),
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useLocale: () => 'en' }));
vi.mock('next/link', () => ({
  default: ({ href, onClick, children }: { href: string; onClick?: () => void; children: React.ReactNode }) => (
    <a href={href} onClick={(e) => { e.preventDefault(); onClick?.(); }}>{children}</a>
  ),
}));
vi.mock('@/lib/edition', () => ({ get IS_CLOUD() { return mocks.isCloud; } }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => mocks.auth }));
vi.mock('@/lib/stores/current-org-store', () => ({
  useCurrentOrg: () => ({ currentOrgId: mocks.orgId }),
  useIsCurrentOrgOwner: () => mocks.isOwner,
}));
vi.mock('@/hooks/useWorkspaceEntitlements', () => ({
  useWorkspaceEntitlements: () => ({ canInviteTeammates: mocks.canInviteTeammates }),
}));
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

function renderNudge() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TwoFactorNudge />
    </QueryClientProvider>,
  );
}

const dismissKey = (orgId: string) => `${TWO_FACTOR_NUDGE_PREFIX}:kc-owner:${orgId}`;

describe('TwoFactorNudge', () => {
  beforeEach(() => {
    mocks.isCloud = true;
    mocks.auth = { isAuthenticated: true, isLoading: false, user: { sub: 'kc-owner' } };
    mocks.orgId = 'org-1';
    mocks.isOwner = true;
    mocks.canInviteTeammates = true;
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

  it.each([
    ['a member who does not own the workspace', () => { mocks.isOwner = false; }],
    ['the personal workspace', () => { mocks.orgId = null; }],
    ['a plan below Team (or an unknown one)', () => { mocks.canInviteTeammates = false; }],
    ['the self-hosted edition', () => { mocks.isCloud = false; }],
    ['a session still resolving', () => { mocks.auth = { isAuthenticated: false, isLoading: true }; }],
  ])('asks nothing and shows nothing for %s', async (_label, arrange) => {
    arrange();
    renderNudge();

    await new Promise((r) => setTimeout(r, 20));
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
  });

  it('asks nothing once dismissed: no status request on any later page', async () => {
    window.localStorage.setItem(dismissKey('org-1'), 'dismissed');
    renderNudge();

    await new Promise((r) => setTimeout(r, 20));
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
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
    expect(mocks.track).not.toHaveBeenCalled();
  });

  it('shows nothing when the status cannot be read, rather than guessing it is off', async () => {
    mocks.getMfaStatus.mockRejectedValue(new Error('503'));
    renderNudge();

    await waitFor(() => expect(mocks.getMfaStatus).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId('two-factor-nudge')).not.toBeInTheDocument();
  });
});
