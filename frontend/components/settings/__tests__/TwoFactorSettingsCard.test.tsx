// @vitest-environment jsdom
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { MfaStatus } from '@/lib/api/services/user-api.service';

/**
 * Settings > Security > "Two-factor authentication": shows the authenticator apps and
 * sends the user to the Keycloak pages that enroll or remove one, coming back to the
 * Security tab.
 */
const mocks = vi.hoisted(() => ({
  getMfaStatus: vi.fn(),
  loginWithRedirect: vi.fn(),
  auth: { isAuthenticated: true, isLoading: false } as { isAuthenticated: boolean; isLoading: boolean },
}));

vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/lib/utils/dateFormatters', () => ({ formatUtcDate: (d: string) => d }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => mocks.auth,
  useAuth: () => ({ loginWithRedirect: mocks.loginWithRedirect }),
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { getMfaStatus: mocks.getMfaStatus },
}));

import {
  OFFER_RECOVERY_CODES_FLAG,
  RECOVERY_CODES_ACTION,
  TwoFactorSettingsCard,
  TWO_FACTOR_RETURN_TO,
} from '../TwoFactorSettingsCard';

const status = (overrides: Partial<MfaStatus>): MfaStatus => ({
  available: true,
  totpEnabled: false,
  devices: [],
  required: false,
  setupPending: false,
  recoveryCodes: null,
  ...overrides,
});

const phone = { id: 'cred-1', label: 'Phone', createdAt: '2026-09-25T10:00:00Z' };

function renderCard(props: { standalone?: boolean } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <TwoFactorSettingsCard {...props} />
    </QueryClientProvider>,
  );
}

describe('TwoFactorSettingsCard', () => {
  beforeEach(() => {
    mocks.getMfaStatus.mockReset();
    mocks.loginWithRedirect.mockReset();
    mocks.auth = { isAuthenticated: true, isLoading: false };
    window.sessionStorage.clear();
  });

  afterEach(() => cleanup());

  it('renders nothing where the account cannot hold a factor (CE)', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({ available: false }));
    renderCard();

    await vi.waitFor(() => expect(mocks.getMfaStatus).toHaveBeenCalled());
    await vi.waitFor(() => expect(screen.queryByTestId('two-factor-card')).not.toBeInTheDocument());
  });

  it('says it is unavailable instead of leaving the tab empty when it is the only content', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({ available: false }));
    renderCard({ standalone: true });

    expect(await screen.findByTestId('two-factor-unavailable')).toHaveTextContent('unavailable');
    expect(screen.queryByTestId('two-factor-card')).not.toBeInTheDocument();
  });

  it('shows the loading line while the session is still resolving, and no buttons', () => {
    mocks.auth = { isAuthenticated: false, isLoading: true };
    renderCard();

    expect(screen.getByText('loading')).toBeInTheDocument();
    expect(mocks.getMfaStatus).not.toHaveBeenCalled();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('turning it on launches the Keycloak enrollment and comes back to the Security tab', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({}));
    renderCard();

    fireEvent.click(await screen.findByRole('button', { name: /turnOn/ }));

    expect(mocks.loginWithRedirect).toHaveBeenCalledWith({
      authorizationParams: { kc_action: 'CONFIGURE_TOTP' },
      appState: { returnTo: TWO_FACTOR_RETURN_TO },
      resetLoopGuards: true,
    });
    expect(TWO_FACTOR_RETURN_TO).toContain('tab=security');
    expect(window.sessionStorage.getItem(OFFER_RECOVERY_CODES_FLAG), 'codes are offered on the way back').toBe('1');
  });

  describe('recovery codes', () => {
    it('are not mentioned while two-factor is off', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({}));
      renderCard();

      await screen.findByRole('button', { name: /turnOn/ });
      expect(screen.queryByTestId('recovery-codes')).not.toBeInTheDocument();
    });

    it('warn that a lost phone locks the account while there are none, and create them', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone] }));
      renderCard();

      expect(await screen.findByTestId('recovery-codes-state')).toHaveTextContent('recoveryNone');
      fireEvent.click(screen.getByRole('button', { name: 'recoveryCreate' }));
      expect(mocks.loginWithRedirect).toHaveBeenCalledWith(
        expect.objectContaining({ authorizationParams: { kc_action: RECOVERY_CODES_ACTION } }),
      );
    });

    it('count what is left, and a new set can be generated', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone], recoveryCodes: { remaining: 9, total: 12 } }));
      renderCard();

      expect(await screen.findByTestId('recovery-codes-state')).toHaveTextContent('recoveryLeft');
      fireEvent.click(screen.getByRole('button', { name: 'recoveryRegenerate' }));
      expect(mocks.loginWithRedirect).toHaveBeenCalledWith(
        expect.objectContaining({ authorizationParams: { kc_action: RECOVERY_CODES_ACTION } }),
      );
    });

    it('ask for a new set when three or fewer are left', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone], recoveryCodes: { remaining: 3, total: 12 } }));
      renderCard();

      expect(await screen.findByTestId('recovery-codes-state')).toHaveTextContent('recoveryLow');
    });

    it('say they are set up, without inventing a count, when the counts are unknown', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone], recoveryCodes: { remaining: null, total: null } }));
      renderCard();

      expect(await screen.findByTestId('recovery-codes-state')).toHaveTextContent('recoverySet');
    });

    it('are offered straight away, once, when coming back from turning two-factor on', async () => {
      window.sessionStorage.setItem(OFFER_RECOVERY_CODES_FLAG, '1');
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone] }));
      renderCard();

      await vi.waitFor(() => expect(mocks.loginWithRedirect).toHaveBeenCalledWith(
        expect.objectContaining({ authorizationParams: { kc_action: RECOVERY_CODES_ACTION } }),
      ));
      expect(window.sessionStorage.getItem(OFFER_RECOVERY_CODES_FLAG)).toBeNull();
    });

    it.each([
      ['the user cancelled the app enrollment', status({})],
      ['the account already has codes', status({ totpEnabled: true, devices: [phone], recoveryCodes: { remaining: 12, total: 12 } })],
    ])('are not pushed when %s, and the offer is dropped', async (_label, answer) => {
      window.sessionStorage.setItem(OFFER_RECOVERY_CODES_FLAG, '1');
      mocks.getMfaStatus.mockResolvedValue(answer);
      renderCard();

      await vi.waitFor(() => expect(window.sessionStorage.getItem(OFFER_RECOVERY_CODES_FLAG)).toBeNull());
      expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
    });

    it('are never pushed without the offer flag', async () => {
      mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone] }));
      renderCard();

      await screen.findByTestId('recovery-codes');
      expect(mocks.loginWithRedirect).not.toHaveBeenCalled();
    });
  });

  it('removing a device launches the Keycloak deletion of that credential', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone] }));
    renderCard();

    expect(await screen.findByText('Phone')).toBeInTheDocument();
    expect(screen.getByTestId('two-factor-state')).toHaveTextContent('stateOn');
    fireEvent.click(screen.getByRole('button', { name: 'remove' }));

    expect(mocks.loginWithRedirect).toHaveBeenCalledWith(
      expect.objectContaining({ authorizationParams: { kc_action: 'delete_credential:cred-1' } }),
    );
  });

  it('an admin cannot remove their last authenticator app', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone], required: true }));
    renderCard();

    expect(await screen.findByText('Phone')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'remove' })).not.toBeInTheDocument();
    expect(screen.getByTestId('two-factor-required')).toHaveTextContent(/^requiredForAdmins$/);
  });

  it('once two-factor is on, no button adds another app, yet several apps stay listed and removable', async () => {
    const laptop = { id: 'cred-2', label: 'Laptop', createdAt: null };
    mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone, laptop], recoveryCodes: { remaining: 12, total: 12 } }));
    renderCard();

    expect(await screen.findByText('Laptop')).toBeInTheDocument();
    expect(screen.getByText('Phone')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /turnOn/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'remove' })).toHaveLength(2);
    // The only Keycloak action reachable from the card is the recovery codes one.
    for (const button of screen.getAllByRole('button')) fireEvent.click(button);
    const launched = mocks.loginWithRedirect.mock.calls.map(([arg]) => arg.authorizationParams.kc_action);
    expect(launched).not.toContain('CONFIGURE_TOTP');
    expect(launched).toContain(RECOVERY_CODES_ACTION);
  });

  it('an admin with two apps can remove either one', async () => {
    const laptop = { id: 'cred-2', label: null, createdAt: null };
    mocks.getMfaStatus.mockResolvedValue(status({ totpEnabled: true, devices: [phone, laptop], required: true }));
    renderCard();

    expect(await screen.findByText('unnamedDevice')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'remove' })).toHaveLength(2);
  });

  it('tells an admin without a factor that the next sign-in will require it', async () => {
    mocks.getMfaStatus.mockResolvedValue(status({ required: true, setupPending: true }));
    renderCard();

    expect(await screen.findByTestId('two-factor-required')).toHaveTextContent('requiredForAdminsPending');
  });

  it('says so when the status cannot be loaded instead of showing it as off', async () => {
    mocks.getMfaStatus.mockRejectedValue(new Error('503'));
    renderCard();

    expect(await screen.findByText('error')).toBeInTheDocument();
    expect(screen.queryByTestId('two-factor-state')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /turnOn/ })).not.toBeInTheDocument();
  });
});
