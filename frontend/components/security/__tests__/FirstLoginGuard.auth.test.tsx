/** @vitest-environment jsdom */

import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FirstLoginGuard from '../FirstLoginGuard';

const mocks = vi.hoisted(() => ({
  pathname: '/fr/app/chat',
  search: '',
  replace: vi.fn(),
  authGuard: {
    isAuthenticated: false,
    isAuthChecking: false,
    isLoading: false,
    user: null,
  },
  apiClient: {
    get: vi.fn(),
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
  IS_CE: false,
}));

vi.mock('@/components/LoadingSpinner', () => ({
  default: () => React.createElement('div', { 'data-testid': 'spinner' }),
}));

describe('FirstLoginGuard app authentication redirect', () => {
  beforeEach(() => {
    mocks.pathname = '/fr/app/chat';
    mocks.search = '';
    mocks.replace.mockReset();
    mocks.apiClient.get.mockReset();
    mocks.authGuard = {
      isAuthenticated: false,
      isAuthChecking: false,
      isLoading: false,
      user: null,
    };
  });

  afterEach(() => {
    cleanup();
  });

  it('redirects anonymous protected app routes to localized login with returnTo', async () => {
    mocks.search = 'draft=1';

    renderGuard();

    await waitFor(() => {
      expect(mocks.replace).toHaveBeenCalledWith(
        '/fr/login?returnTo=%2Ffr%2Fapp%2Fchat%3Fdraft%3D1',
      );
    });
    expect(screen.getByTestId('spinner')).toBeTruthy();
  });

  it('does not render protected app content while auth is still resolving', () => {
    mocks.authGuard = {
      isAuthenticated: false,
      isAuthChecking: true,
      isLoading: true,
      user: null,
    };

    renderGuard();

    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.getByTestId('spinner')).toBeTruthy();
    expect(screen.queryByText('app page')).toBeNull();
  });

  it('keeps anonymous pricing and information routes public', async () => {
    mocks.pathname = '/fr/app/settings/pricing';

    renderGuard();

    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.getByText('app page')).toBeTruthy();

    cleanup();
    mocks.pathname = '/fr/app/settings/information';

    renderGuard();

    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.getByText('app page')).toBeTruthy();
  });

  it('does not force onboarding on public pricing routes', async () => {
    mocks.pathname = '/fr/app/settings/pricing';
    mocks.authGuard = {
      isAuthenticated: true,
      isAuthChecking: false,
      isLoading: false,
      user: { sub: 'user-1' },
    };
    mocks.apiClient.get.mockResolvedValue({
      needsOnboarding: true,
      firstLogin: true,
      profileIncomplete: true,
      emailVerified: false,
    });

    renderGuard();
    await act(async () => {});

    expect(mocks.apiClient.get).not.toHaveBeenCalled();
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.getByText('app page')).toBeTruthy();
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
        <div>app page</div>
      </FirstLoginGuard>
    </QueryClientProvider>,
  );
}
