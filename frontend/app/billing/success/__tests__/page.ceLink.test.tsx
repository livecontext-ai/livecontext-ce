// @vitest-environment jsdom
/**
 * Billing success page, cloud edition, with a self-hosted install waiting for a paid plan
 * (a pending CE link in this tab, stored by the onboarding page). Once the purchase is
 * confirmed the page re-checks GET /api/ce-link/eligibility and continues:
 *  - eligible     -> the Keycloak authorization rebuilt from the app's own config;
 *  - not eligible -> back to the pricing page with ?ce_link=1 (its banner re-checks).
 * Without a pending link the page behaves exactly as before (no eligibility call).
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  searchParams: new URLSearchParams(),
  push: vi.fn(),
  finalizeCheckout: vi.fn(),
  eligibility: vi.fn(),
  assignLocation: vi.fn(),
}));

// Stable identities, like the real hooks: the page's checkout effect depends on the router,
// so a fresh object per render would re-run it on every render.
const stable = vi.hoisted(() => ({
  router: { push: (...a: unknown[]) => mocks.push(...a) },
  translators: new Map<string, (k: string) => string>(),
  auth: { isAuthenticated: true, isLoading: false, getAccessTokenSilently: async () => 't' },
}));
vi.mock('next/navigation', () => ({
  useRouter: () => stable.router,
  useSearchParams: () => mocks.searchParams,
}));
vi.mock('next-intl', () => ({
  useTranslations: (ns?: string) => {
    const key = ns ?? '';
    if (!stable.translators.has(key)) stable.translators.set(key, (k: string) => `${key}.${k}`);
    return stable.translators.get(key)!;
  },
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => stable.auth,
}));
vi.mock('@/lib/api/unified-api-service', () => ({
  unifiedApiService: { finalizeCheckout: (...a: unknown[]) => mocks.finalizeCheckout(...a) },
}));
const plans = vi.hoisted(() => ({ value: { plans: [{ id: 3, code: 'PRO' }] } }));
vi.mock('@/lib/hooks/smart-hooks-complete', () => ({ usePlans: () => plans.value }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="spinner" /> }));
vi.mock('@/components/CheckoutModal', () => ({
  default: ({ type, message }: { type: string; message: string }) => (
    <div data-testid="checkout-modal" data-type={type}>{message}</div>
  ),
}));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/utils/locale', () => ({ getClientLocale: () => 'fr' }));
vi.mock('@/lib/api/ce-link.service', () => ({ ceLinkService: { eligibility: () => mocks.eligibility() } }));
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (u: string) => mocks.assignLocation(u) }));

import BillingSuccessPage from '../page';
import { PENDING_CE_LINK_KEY, savePendingCeLink } from '@/lib/cloud-link/pendingCeLink';

function storePendingLink() {
  savePendingCeLink({
    clientId: 'livecontext-frontend',
    redirectUri: 'http://localhost:8080/api/cloud-link/callback',
    state: '0f8fad5b-d9cb-469f-a165-70867728950e',
    codeChallenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
    codeChallengeMethod: 'S256',
    savedAt: Date.now(),
  });
}

describe('BillingSuccessPage - pending CE link continuation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_URL', 'https://auth.livecontext.ai');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_REALM', 'livecontext');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', 'livecontext-frontend');
    mocks.searchParams = new URLSearchParams('session_id=cs_test_1');
    mocks.finalizeCheckout.mockResolvedValue({ state: 'provisioned', planId: 3, status: 'active' });
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
    sessionStorage.clear();
  });

  it('subscription provisioned + eligible: continues to the Keycloak authorization and clears the pending link', async () => {
    storePendingLink();
    mocks.eligibility.mockResolvedValue({ eligible: true, planCode: 'PRO', reason: null });

    render(<BillingSuccessPage />);

    await waitFor(() => expect(mocks.assignLocation).toHaveBeenCalledTimes(1));
    expect(mocks.assignLocation.mock.calls[0][0]).toMatch(
      /^https:\/\/auth\.livecontext\.ai\/realms\/livecontext\/protocol\/openid-connect\/auth\?/,
    );
    expect(screen.getByTestId('checkout-modal')).toHaveTextContent('ceCloudLink.cloud.returning');
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('subscription provisioned but still not eligible: back to the locale pricing page with ce_link=1, link kept', async () => {
    storePendingLink();
    mocks.eligibility.mockResolvedValue({ eligible: false, planCode: 'FREE', reason: 'PLAN_REQUIRED' });

    render(<BillingSuccessPage />);

    await waitFor(() => expect(mocks.assignLocation).toHaveBeenCalledWith('/fr/app/settings/pricing?ce_link=1'));
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();
  });

  it('no pending link: unchanged behaviour, no eligibility call, no navigation', async () => {
    render(<BillingSuccessPage />);

    await waitFor(() => expect(screen.getByTestId('checkout-modal')).toHaveAttribute('data-type', 'success'));
    expect(mocks.eligibility).not.toHaveBeenCalled();
    expect(mocks.assignLocation).not.toHaveBeenCalled();
  });

  it('PAYG top-up with a pending link: waits for the credit to settle, then continues instead of opening the overview', async () => {
    vi.useFakeTimers();
    storePendingLink();
    mocks.searchParams = new URLSearchParams('session_id=cs_test_2&payg=100');
    mocks.eligibility.mockResolvedValue({ eligible: true, planCode: 'PAYG', reason: null });

    render(<BillingSuccessPage />);
    expect(mocks.eligibility).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });

    expect(mocks.eligibility).toHaveBeenCalledTimes(1);
    expect(mocks.assignLocation).toHaveBeenCalledTimes(1);
    expect(mocks.push).not.toHaveBeenCalledWith('/app/settings/overview');
  });

  it('PAYG top-up without a pending link still opens the overview', async () => {
    vi.useFakeTimers();
    mocks.searchParams = new URLSearchParams('session_id=cs_test_3&payg=100');

    render(<BillingSuccessPage />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });

    expect(mocks.push).toHaveBeenCalledWith('/app/settings/overview');
    expect(mocks.eligibility).not.toHaveBeenCalled();
  });
});
