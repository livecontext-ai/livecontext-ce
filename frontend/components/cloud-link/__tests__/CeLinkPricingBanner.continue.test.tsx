/**
 * @vitest-environment jsdom
 *
 * CeLinkPricingBanner "continue" button against the REAL pending-link module: only the
 * eligibility endpoint and the page navigation are stubbed, so each case exercises the
 * stored link, the eligibility answer and the rebuilt Keycloak URL end to end.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextIntlClientProvider } from 'next-intl';
import messages from '../../../messages/en.json';

const h = vi.hoisted(() => ({
  eligibility: vi.fn(),
  assignLocation: vi.fn(),
}));

vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams('ce_link=1') }));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/providers/smart-providers', () => ({ useOptionalAuth: () => ({ isAuthenticated: true }) }));
vi.mock('@/lib/api/ce-link.service', () => ({ ceLinkService: { eligibility: () => h.eligibility() } }));
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (u: string) => h.assignLocation(u) }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <span data-testid="spinner" /> }));

import { CeLinkPricingBanner } from '../CeLinkPricingBanner';
import { PENDING_CE_LINK_KEY, savePendingCeLink } from '@/lib/cloud-link/pendingCeLink';

const cloud = messages.ceCloudLink.cloud;
const STATE = '0f8fad5b-d9cb-469f-a165-70867728950e';
const CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
const REDIRECT = 'http://localhost:8080/api/cloud-link/callback';

function storePendingLink() {
  savePendingCeLink({
    clientId: 'livecontext-frontend',
    redirectUri: REDIRECT,
    state: STATE,
    codeChallenge: CHALLENGE,
    codeChallengeMethod: 'S256',
    savedAt: Date.now(),
  });
}

function clickContinue() {
  render(
    <NextIntlClientProvider locale="en" messages={messages as Record<string, unknown>}>
      <CeLinkPricingBanner />
    </NextIntlClientProvider>,
  );
  fireEvent.click(screen.getByRole('button', { name: cloud.pricingBannerContinue }));
}

describe('CeLinkPricingBanner - continue outcomes (real pending-link module)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_URL', 'https://auth.livecontext.ai');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_REALM', 'livecontext');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', 'livecontext-frontend');
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
    sessionStorage.clear();
  });

  it('eligible: navigates to the rebuilt Keycloak authorization and clears the pending link', async () => {
    storePendingLink();
    h.eligibility.mockResolvedValue({ eligible: true, planCode: 'PRO', reason: null });

    clickContinue();

    await waitFor(() => expect(h.assignLocation).toHaveBeenCalledTimes(1));
    const target = new URL(h.assignLocation.mock.calls[0][0]);
    expect(`${target.origin}${target.pathname}`).toBe(
      'https://auth.livecontext.ai/realms/livecontext/protocol/openid-connect/auth',
    );
    expect(target.searchParams.get('client_id')).toBe('livecontext-frontend');
    expect(target.searchParams.get('redirect_uri')).toBe(REDIRECT);
    expect(target.searchParams.get('state')).toBe(STATE);
    expect(target.searchParams.get('code_challenge')).toBe(CHALLENGE);
    expect(target.searchParams.get('code_challenge_method')).toBe('S256');
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
    expect(await screen.findByText(cloud.returning)).toBeInTheDocument();
  });

  it('still not eligible: shows the still-required message, keeps the link, no navigation', async () => {
    storePendingLink();
    h.eligibility.mockResolvedValue({ eligible: false, planCode: 'FREE', reason: 'PLAN_REQUIRED' });

    clickContinue();

    expect(await screen.findByText(cloud.pricingBannerStillRequired)).toBeInTheDocument();
    expect(h.assignLocation).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();
  });

  it('no pending link in this tab: shows the no-pending message and never asks for eligibility', async () => {
    clickContinue();

    expect(await screen.findByText(cloud.pricingBannerNoPending)).toBeInTheDocument();
    expect(h.eligibility).not.toHaveBeenCalled();
    expect(h.assignLocation).not.toHaveBeenCalled();
  });

  it('eligibility request fails: shows the error message, keeps the link, no navigation', async () => {
    storePendingLink();
    h.eligibility.mockRejectedValue(new Error('503'));

    clickContinue();

    expect(await screen.findByText(cloud.pricingBannerError)).toBeInTheDocument();
    expect(h.assignLocation).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();
  });
});
