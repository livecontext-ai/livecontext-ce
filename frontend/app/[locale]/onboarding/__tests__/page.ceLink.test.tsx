// @vitest-environment jsdom
/**
 * Cloud onboarding as the entry of a self-hosted (CE) install's "Connect to Cloud".
 *
 * The CE opens /<locale>/onboarding?ce_link=1&client_id&redirect_uri&state&code_challenge&
 * code_challenge_method. The page stores the validated request on arrival (it has to survive
 * the sign-in redirect, the email step and reloads), and where the page used to leave for the
 * chat it goes to the Keycloak authorization rebuilt from the app's own config (SSO, so the user
 * is not asked to sign in again) and clears the pending link. Any plan, FREE included, may link:
 * there is no plan check and no pricing detour. Without a pending link nothing changes: the chat.
 */
import '@testing-library/jest-dom/vitest';
import React from 'react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mocks = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  leaveForChat: vi.fn(),
  assignLocation: vi.fn(),
  loginWithRedirect: vi.fn(),
  auth: { isAuthenticated: true },
}));

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => 'en',
}));
vi.mock('@/lib/providers/smart-providers', () => ({
  useAuth: () => ({
    user: { sub: 'u1', name: 'Jane', email: 'jane@example.com' },
    isLoading: false,
    isAuthenticated: mocks.auth.isAuthenticated,
    loginWithRedirect: mocks.loginWithRedirect,
    logout: vi.fn(),
  }),
}));
vi.mock('@/lib/api', () => ({ apiClient: { get: mocks.apiGet, post: mocks.apiPost } }));
vi.mock('@/lib/navigation/leaveForChat', () => ({ leaveForChat: (l: string) => mocks.leaveForChat(l) }));
vi.mock('@/lib/navigation/assignLocation', () => ({ assignLocation: (u: string) => mocks.assignLocation(u) }));
vi.mock('@/lib/edition', () => ({ IS_CE: false }));
vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));
vi.mock('@/components/LoadingSpinner', () => ({ default: () => <div data-testid="spinner" /> }));

import OnboardingPage from '../page';
import { PENDING_CE_LINK_KEY } from '@/lib/cloud-link/pendingCeLink';

const CHALLENGE = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM';
const STATE = '0f8fad5b-d9cb-469f-a165-70867728950e';
const REDIRECT = 'http://localhost:8080/api/cloud-link/callback';

function ceLinkQuery(overrides: Record<string, string> = {}): string {
  return `?${new URLSearchParams({
    ce_link: '1',
    client_id: 'livecontext-frontend',
    redirect_uri: REDIRECT,
    state: STATE,
    code_challenge: CHALLENGE,
    code_challenge_method: 'S256',
    ...overrides,
  }).toString()}`;
}

function mockOnboardingStatus(needsOnboarding: boolean) {
  mocks.apiGet.mockImplementation(async (path: string) => {
    if (path === '/auth/email/status') return { verified: true };
    if (path === '/auth-service/api/onboarding/status') {
      return { needsOnboarding, completed: !needsOnboarding, currentStep: 1, displayName: 'Jane' };
    }
    if (path.startsWith('/auth-service/api/onboarding/check-display-name')) return { available: true, message: '' };
    throw new Error(`unexpected GET ${path}`);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OnboardingPage />
    </QueryClientProvider>,
  );
}

describe('Onboarding - CE link continuation (cloud)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.auth.isAuthenticated = true;
    sessionStorage.clear();
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_URL', 'https://auth.livecontext.ai');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_REALM', 'livecontext');
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_CLIENT_ID', 'livecontext-frontend');
    window.history.replaceState({}, '', '/en/onboarding');
  });
  afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
    sessionStorage.clear();
  });

  it('already onboarded (FREE plan included): goes to the rebuilt Keycloak authorization, not the chat, and clears the pending link', async () => {
    window.history.replaceState({}, '', `/en/onboarding${ceLinkQuery()}`);
    mockOnboardingStatus(false);

    renderPage();

    await waitFor(() => expect(mocks.assignLocation).toHaveBeenCalledTimes(1));
    const target = new URL(mocks.assignLocation.mock.calls[0][0]);
    expect(`${target.origin}${target.pathname}`).toBe(
      'https://auth.livecontext.ai/realms/livecontext/protocol/openid-connect/auth',
    );
    expect(target.searchParams.get('redirect_uri')).toBe(REDIRECT);
    expect(target.searchParams.get('state')).toBe(STATE);
    expect(target.searchParams.get('code_challenge')).toBe(CHALLENGE);
    expect(target.searchParams.get('code_challenge_method')).toBe('S256');
    expect(mocks.leaveForChat).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
  });

  it('a link stored earlier in the tab (sign-in, FirstLoginGuard or email step in between) still continues without the query', async () => {
    // Captured on a first visit...
    window.history.replaceState({}, '', `/en/onboarding${ceLinkQuery()}`);
    mockOnboardingStatus(true);
    const first = renderPage();
    await screen.findByText('skipForNow');
    first.unmount();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();

    // ...then back on the bare onboarding path, finishing onboarding by skipping.
    window.history.replaceState({}, '', '/en/onboarding');
    mocks.apiPost.mockResolvedValue({});
    renderPage();
    const skip = await screen.findByText('skipForNow');
    await waitFor(() => expect(skip.closest('button')).not.toBeDisabled());
    fireEvent.click(skip);

    await waitFor(() => expect(mocks.assignLocation).toHaveBeenCalledTimes(1));
    expect(mocks.assignLocation.mock.calls[0][0]).toMatch(
      /^https:\/\/auth\.livecontext\.ai\/realms\/livecontext\/protocol\/openid-connect\/auth\?/,
    );
    expect(mocks.leaveForChat).not.toHaveBeenCalled();
  });

  it('pending link but no usable Keycloak config: falls back to the chat, never to a broken authorize URL, link kept', async () => {
    vi.stubEnv('NEXT_PUBLIC_KEYCLOAK_URL', '');
    window.history.replaceState({}, '', `/en/onboarding${ceLinkQuery()}`);
    mockOnboardingStatus(false);

    renderPage();

    await waitFor(() => expect(mocks.leaveForChat).toHaveBeenCalledWith('en'));
    expect(mocks.assignLocation).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();
  });

  it('without a CE link: leaves for the chat as before', async () => {
    mockOnboardingStatus(false);

    renderPage();

    await waitFor(() => expect(mocks.leaveForChat).toHaveBeenCalledWith('en'));
    expect(mocks.assignLocation).not.toHaveBeenCalled();
  });

  it('an invalid CE link (foreign redirect) is ignored: nothing stored, chat as before', async () => {
    window.history.replaceState(
      {},
      '',
      `/en/onboarding${ceLinkQuery({ redirect_uri: 'https://evil.example/api/cloud-link/callback' })}`,
    );
    mockOnboardingStatus(false);

    renderPage();

    await waitFor(() => expect(mocks.leaveForChat).toHaveBeenCalledWith('en'));
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).toBeNull();
    expect(mocks.assignLocation).not.toHaveBeenCalled();
  });

  it('signed out: stores the link, explains the CE connection, and signs in back to the bare onboarding path', async () => {
    mocks.auth.isAuthenticated = false;
    window.history.replaceState({}, '', `/en/onboarding${ceLinkQuery()}`);

    renderPage();

    expect(await screen.findByText('ceLinkLoginRequiredDescription')).toBeInTheDocument();
    expect(sessionStorage.getItem(PENDING_CE_LINK_KEY)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'signIn' }));
    expect(mocks.loginWithRedirect).toHaveBeenCalledWith({ appState: { returnTo: '/en/onboarding' } });
  });
});
