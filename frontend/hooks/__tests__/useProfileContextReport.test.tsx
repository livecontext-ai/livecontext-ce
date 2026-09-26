/**
 * @vitest-environment jsdom
 *
 * useProfileContextReport: PUT /users/profile/context once per tab session per distinct
 * payload, only once auth is ready, never when signed out.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as React from 'react';

type AuthState = { isAuthenticated: boolean; isReady: boolean; isLoading: boolean; numericUserId: number | null };

const authMock = vi.hoisted(() => ({ current: undefined as AuthState | undefined }));
vi.mock('@/lib/providers/smart-providers', () => ({
  useOptionalAuth: () => authMock.current,
}));

const localeMock = vi.hoisted(() => ({ current: 'fr' }));
vi.mock('next-intl', () => ({ useLocale: () => localeMock.current }));

const apiMock = vi.hoisted(() => ({ reportProfileContext: vi.fn() }));
vi.mock('@/lib/api/unified-api-service', () => ({ unifiedApiService: apiMock }));

import {
  useProfileContextReport,
  buildProfileContextPayload,
  PROFILE_CONTEXT_SENT_KEY,
} from '../useProfileContextReport';
import { ACQUISITION_STORAGE_KEY } from '@/lib/lifecycle/acquisition';

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 30));

/** Same key / shape as CookieConsentBanner and lib/analytics/consent.ts. */
const CONSENT_KEY = 'lc.cookieConsent';
const grantAnalytics = () =>
  localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'accepted', version: 1 }));

describe('useProfileContextReport', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMock.reportProfileContext.mockResolvedValue(undefined);
    sessionStorage.clear();
    localStorage.clear();
    authMock.current = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 42 };
    localeMock.current = 'fr';
  });

  it('sends locale, time zone and the stored first touch once auth is ready', async () => {
    const acq = { utmSource: 'google', landingPath: '/', firstSeenAt: '2026-09-24T10:00:00.000Z' };
    localStorage.setItem(ACQUISITION_STORAGE_KEY, JSON.stringify(acq));
    grantAnalytics();

    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });

    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1));
    const payload = apiMock.reportProfileContext.mock.calls[0][0];
    expect(payload.locale).toBe('fr');
    expect(payload.localeExplicit).toBe(false);
    expect(typeof payload.timeZone).toBe('string');
    expect(payload.acquisition).toEqual(acq);
  });

  it('does not send the same payload twice in one session, even after a reload of the tab', async () => {
    const first = renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1));
    first.rerender();
    first.unmount();

    // A new QueryClient stands for a reload: only the sessionStorage guard survives it.
    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await settle();

    expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(PROFILE_CONTEXT_SENT_KEY)).toContain('"locale":"fr"');
  });

  it('sends again when the payload changes (another locale displayed)', async () => {
    const hook = renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1));

    localeMock.current = 'de';
    hook.rerender();

    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(2));
    expect(apiMock.reportProfileContext.mock.calls[1][0].locale).toBe('de');
  });

  it('reports again for a different account signing in on the same tab', async () => {
    const first = renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1));
    first.unmount();

    authMock.current = { isAuthenticated: true, isReady: true, isLoading: false, numericUserId: 7 };
    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });

    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(2));
  });

  it('is skipped when signed out', async () => {
    authMock.current = { isAuthenticated: false, isReady: true, isLoading: false, numericUserId: null };

    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await settle();

    expect(apiMock.reportProfileContext).not.toHaveBeenCalled();
  });

  it('waits while auth is still loading', async () => {
    authMock.current = { isAuthenticated: true, isReady: false, isLoading: true, numericUserId: 42 };

    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await settle();

    expect(apiMock.reportProfileContext).not.toHaveBeenCalled();
  });

  it('is a no-op outside the auth provider', async () => {
    authMock.current = undefined;

    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await settle();

    expect(apiMock.reportProfileContext).not.toHaveBeenCalled();
  });

  it('does not mark the payload sent when the request fails, so the next load retries', async () => {
    apiMock.reportProfileContext.mockRejectedValueOnce(new Error('503'));

    renderHook(() => useProfileContextReport(), { wrapper: makeWrapper() });
    await waitFor(() => expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1));
    await settle();

    expect(apiMock.reportProfileContext).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(PROFILE_CONTEXT_SENT_KEY)).toBeNull();
  });
  describe('buildProfileContextPayload: acquisition only with analytics consent', () => {
    const acq = { utmSource: 'newsletter', landingPath: '/pricing', firstSeenAt: '2026-09-20T08:00:00.000Z' };

    it('includes the stored first touch when analytics cookies were accepted', () => {
      localStorage.setItem(ACQUISITION_STORAGE_KEY, JSON.stringify(acq));
      grantAnalytics();

      expect(buildProfileContextPayload('en').acquisition).toEqual(acq);
    });

    it('omits it when no consent choice was made, but still sends locale and time zone', () => {
      localStorage.setItem(ACQUISITION_STORAGE_KEY, JSON.stringify(acq));

      const payload = buildProfileContextPayload('en');

      expect(payload.acquisition).toBeUndefined();
      expect(payload.locale).toBe('en');
      expect(typeof payload.timeZone).toBe('string');
    });

    it('omits it when analytics cookies were rejected', () => {
      localStorage.setItem(ACQUISITION_STORAGE_KEY, JSON.stringify(acq));
      localStorage.setItem(CONSENT_KEY, JSON.stringify({ status: 'rejected', version: 1 }));

      expect(buildProfileContextPayload('en').acquisition).toBeUndefined();
    });
  });
});
