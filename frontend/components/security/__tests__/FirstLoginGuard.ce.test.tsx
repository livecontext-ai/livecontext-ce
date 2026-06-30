/** @vitest-environment jsdom */

import React from 'react';
import { cleanup, render, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FirstLoginGuard from '../FirstLoginGuard';

const mocks = vi.hoisted(() => ({
  pathname: '/en/app/chat',
  search: '',
  replace: vi.fn(),
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
  },
  authGuard: {
    isAuthenticated: true,
    isAuthChecking: false,
    isLoading: false,
    user: { sub: '123', roles: ['ADMIN'] },
  },
}));

vi.mock('next/navigation', () => ({
  usePathname: () => mocks.pathname,
  useRouter: () => ({ replace: mocks.replace }),
  useSearchParams: () => new URLSearchParams(mocks.search),
}));

vi.mock('@/hooks/useAuthGuard', () => ({
  useAuthGuard: () => mocks.authGuard,
}));

vi.mock('@/lib/api', () => ({
  apiClient: mocks.apiClient,
}));

vi.mock('@/lib/edition', () => ({
  IS_CE: true,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => React.createElement('div', { 'data-testid': 'spinner' }),
}));

describe('CE FirstLoginGuard', () => {
  beforeEach(() => {
    mocks.pathname = '/en/app/chat';
    mocks.search = '';
    mocks.replace.mockReset();
    mocks.apiClient.get.mockReset();
    mocks.apiClient.post.mockReset();
    mocks.authGuard = {
      isAuthenticated: true,
      isAuthChecking: false,
      isLoading: false,
      user: { sub: '123', roles: ['ADMIN'] },
    };
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('checks CE setup through the apiClient-relative status path', async () => {
    mocks.apiClient.get.mockImplementation(async (path: string) => {
      if (path === '/ce/status') {
        return { bootstrapped: true };
      }
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: false, completed: true, emailVerified: true };
      }
      throw new Error(`Unexpected GET ${path}`);
    });

    renderGuard();

    await waitFor(() => {
      expect(mocks.apiClient.get).toHaveBeenCalledWith('/ce/status');
    });
    expect(mocks.apiClient.get).not.toHaveBeenCalledWith('/api/ce/status');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('does NOT force the cloud-style profile onboarding even when onboarding is incomplete', async () => {
    // Pre-fix the CE guard fetched onboarding status and redirected an
    // un-onboarded admin to /onboarding. CE now skips that funnel entirely:
    // the admin lands straight in the app once the setup wizard is bootstrapped.
    mocks.apiClient.get.mockImplementation(async (path: string) => {
      if (path === '/ce/status') {
        return { bootstrapped: true };
      }
      if (path === '/auth-service/api/onboarding/status') {
        return { needsOnboarding: true, firstLogin: true, profileIncomplete: true, emailVerified: true };
      }
      throw new Error(`Unexpected GET ${path}`);
    });

    const { findByText } = renderGuard();

    // Children render → the guard let the user through without a redirect.
    await findByText('protected app');
    expect(mocks.replace).not.toHaveBeenCalledWith('/en/onboarding');
    // It no longer even reads the onboarding status in CE.
    expect(mocks.apiClient.get).not.toHaveBeenCalledWith('/auth-service/api/onboarding/status');
  });

  it('redirects an admin of a not-yet-bootstrapped install to /ce-setup', async () => {
    mocks.apiClient.get.mockImplementation(async (path: string) => {
      if (path === '/ce/status') {
        return { bootstrapped: false };
      }
      throw new Error(`Unexpected GET ${path}`);
    });

    renderGuard();

    await waitFor(() => {
      expect(mocks.replace).toHaveBeenCalledWith('/en/ce-setup');
    });
  });

  it('ignores a stale legacy lc_ce_setup_done flag and still shows the wizard (no client-side self-heal)', async () => {
    // Regression: a wizard completed during a PRIOR CE test on this same origin
    // (e.g. localhost) leaves lc_ce_setup_done=true in localStorage. The old guard
    // self-migrated (POST /ce/complete) and silently skipped the wizard on a fresh
    // install. The server flag (bootstrapped) is now the ONLY gate.
    localStorage.setItem('lc_ce_setup_done', 'true');
    mocks.apiClient.get.mockImplementation(async (path: string) => {
      if (path === '/ce/status') {
        return { bootstrapped: false };
      }
      throw new Error(`Unexpected GET ${path}`);
    });
    mocks.apiClient.post.mockResolvedValue({});

    renderGuard();

    await waitFor(() => {
      expect(mocks.replace).toHaveBeenCalledWith('/en/ce-setup');
    });
    // The stale localStorage flag must NOT trigger the removed self-heal.
    expect(mocks.apiClient.post).not.toHaveBeenCalledWith('/ce/complete', {});
  });
});

function renderGuard() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <FirstLoginGuard>
        <div>protected app</div>
      </FirstLoginGuard>
    </QueryClientProvider>,
  );
}
