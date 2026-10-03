/**
 * @vitest-environment jsdom
 *
 * The cloud token refresh (smart-providers refreshCloudSession) calls the UserManager it reads from
 * OidcUserManagerContext, and relies on that manager being the one react-oidc-context's
 * AuthProvider runs on: a refresh made there reaches the auth context only through THAT manager's
 * userLoaded event. Were AuthProvider given its own instance (the `userManager` prop dropped, or a
 * second manager created), the refresh would store new tokens the app never sees, and the person
 * would stay "expired" with a valid session. This pins the wiring in app/providers.tsx.
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useContext } from 'react';
import { useAuth } from 'react-oidc-context';
import { UserManager } from 'oidc-client-ts';

vi.mock('next/navigation', () => ({
  usePathname: () => '/',
  useRouter: () => ({ replace: vi.fn(), push: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/lib/api/api-client', () => {
  const apiClient = {
    get: vi.fn(() => Promise.resolve(null)),
    setTokenProvider: vi.fn(),
    getTokenProvider: vi.fn(() => null),
    setOnAuthFailure: vi.fn(),
    setActiveOrgProvider: vi.fn(),
  };
  return { apiClient, ApiClient: class {}, ApiError: class extends Error {} };
});
vi.mock('@/lib/api', async () => ({ apiClient: (await import('@/lib/api/api-client')).apiClient }));
vi.mock('@/lib/websocket/ws-provider', () => ({
  WebSocketProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/PlanLimitToastListener', () => ({ default: () => null }));
vi.mock('@/components/ThemeProvider', () => ({
  ThemeProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
vi.mock('@/components/analytics/AnalyticsProvider', () => ({ default: () => null }));
vi.mock('@/components/lifecycle/AcquisitionCapture', () => ({ default: () => null }));
vi.mock('@/components/reward/PendingRewardCodeCapture', () => ({ default: () => null }));

import Providers, { createOidcUserManager, oidcConfig } from '../providers';
import { OidcUserManagerContext } from '../../lib/auth/oidcUserManager';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.doUnmock('../../lib/edition');
  vi.resetModules();
});

describe('app/providers.tsx hands ONE UserManager to AuthProvider and to the token refresh', () => {
  it('the manager in OidcUserManagerContext is the one useAuth() runs on', async () => {
    let fromContext: UserManager | null | undefined;
    let fromAuth: ReturnType<typeof useAuth> | undefined;
    function Probe() {
      fromContext = useContext(OidcUserManagerContext);
      fromAuth = useAuth();
      return <div data-testid="probe" />;
    }

    render(<Providers><Probe /></Providers>);
    await screen.findByTestId('probe');

    expect(fromContext).toBeInstanceOf(UserManager);
    // react-oidc-context exposes its manager's own settings and events objects: identity proves
    // AuthProvider did not build a second manager of its own.
    expect(fromAuth?.events).toBe(fromContext!.events);
    expect(fromAuth?.settings).toBe(fromContext!.settings);
    expect(fromContext!.settings.client_id).toBe(oidcConfig.client_id);
  });

  it('keeps the same manager across re-renders (a new one would lose the auth context link)', async () => {
    const seen: Array<UserManager | null> = [];
    function Probe() {
      seen.push(useContext(OidcUserManagerContext));
      return <div data-testid="probe" />;
    }

    const { rerender } = render(<Providers><Probe /></Providers>);
    await screen.findByTestId('probe');
    rerender(<Providers><Probe /></Providers>);
    await screen.findByTestId('probe');

    expect(seen.length).toBeGreaterThan(1);
    expect(new Set(seen).size).toBe(1);
  });
});

describe('createOidcUserManager', () => {
  it('builds a UserManager from the app settings in the cloud browser', () => {
    const manager = createOidcUserManager();
    expect(manager).toBeInstanceOf(UserManager);
    expect(manager!.settings.authority).toBe(oidcConfig.authority);
    expect(manager!.settings.silentRequestTimeoutInSeconds).toBe(oidcConfig.silentRequestTimeoutInSeconds);
  });

  it('builds none during server rendering', () => {
    vi.stubGlobal('window', undefined);
    expect(createOidcUserManager()).toBeNull();
  });

  it('builds none in CE (embedded auth has no UserManager)', async () => {
    vi.resetModules();
    vi.doMock('../../lib/edition', async (importOriginal) => ({
      ...(await importOriginal<typeof import('../../lib/edition')>()),
      IS_CE: true,
      IS_CLOUD: false,
    }));
    const ce = await import('../providers');
    expect(ce.createOidcUserManager()).toBeNull();
  });
});
