/** @vitest-environment jsdom */

import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FirstLoginGuard from '../FirstLoginGuard';

const mocks = vi.hoisted(() => ({
  pathname: '/fr/app/chat',
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
  // Regression pin: FirstLoginGuard wraps the whole tree from the root layout,
  // and useSearchParams() there opts every static page out of server rendering
  // (empty-body HTML, the SEO killer fixed in the landing-SEO pass). The query
  // string must come from window.location instead.
  useSearchParams: () => {
    throw new Error('useSearchParams must not be called in FirstLoginGuard (forces CSR bailout of every page)');
  },
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
    window.history.replaceState(null, '', '/');
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
    window.history.replaceState(null, '', '/fr/app/chat?draft=1');

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

  it('a signed-in person who has not finished onboarding goes through it before buying, and is brought back to the same pricing page', async () => {
    // A fresh account back from sign-up on a partner's recommended link: email unverified, so the
    // partner code is still waiting. Paying now would never attribute them to the partner.
    const query = '?pricingMode=subscription&planCode=PRO&creditTierIndex=5&billingCycle=monthly&lc_ref=NORTHWIND&lc_rec=pro.5.monthly';
    window.history.replaceState(null, '', `/fr/app/settings/pricing${query}`);
    window.localStorage.clear();
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

    // Held while the status is read: no plan to click on the way out.
    expect(screen.queryByText('app page')).toBeNull();
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/fr/onboarding'));
    const saved = JSON.parse(window.localStorage.getItem('lc_post_onboarding_return_v1') ?? '{}');
    expect(saved.path).toBe(`/fr/app/settings/pricing${query}`);
    // For this account only.
    expect(saved.owner).toBe('user-1');
  });

  it('regression: pricing is mounted once, not shown during the session read and then remounted (the Stripe return reads its query on mount)', async () => {
    mocks.pathname = '/fr/app/settings/pricing';
    mocks.authGuard = { isAuthenticated: false, isAuthChecking: true, isLoading: true, user: null };
    mocks.apiClient.get.mockResolvedValue({ needsOnboarding: false, firstLogin: false, profileIncomplete: false, emailVerified: true });
    let mounts = 0;
    function Page() {
      React.useEffect(() => { mounts += 1; }, []);
      return <div>pricing page</div>;
    }
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const tree = () => (
      <QueryClientProvider client={queryClient}>
        <FirstLoginGuard><Page /></FirstLoginGuard>
      </QueryClientProvider>
    );

    const { rerender } = render(tree());
    expect(screen.queryByText('pricing page')).toBeNull();

    mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-4' } };
    rerender(tree());

    await waitFor(() => expect(screen.getByText('pricing page')).toBeTruthy());
    await act(async () => {});
    expect(mounts).toBe(1);
  });

  it('when the onboarding status cannot be read, pricing opens (fail open) rather than spinning', async () => {
    mocks.pathname = '/fr/app/settings/pricing';
    mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-5' } };
    mocks.apiClient.get.mockRejectedValue(new Error('503'));

    renderGuard();

    await waitFor(() => expect(screen.getByText('app page')).toBeTruthy());
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('moving to pricing from a public page in the app checks onboarding there', async () => {
    mocks.pathname = '/fr/app/settings/information';
    mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-6' } };
    mocks.apiClient.get.mockResolvedValue({ needsOnboarding: true, emailVerified: false });
    const { rerender } = renderGuard();
    await act(async () => {});
    expect(mocks.apiClient.get).not.toHaveBeenCalled();

    mocks.pathname = '/fr/app/settings/pricing';
    rerender(guardTree());

    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/fr/onboarding'));
  });

  it('signing in drops a return to pricing another account left on this browser', async () => {
    window.localStorage.setItem('lc_post_onboarding_return_v1', JSON.stringify({
      path: '/fr/app/settings/pricing?lc_ref=NORTHWIND&lc_rec=pro.5.monthly', owner: 'someone-else', savedAt: Date.now(),
    }));
    mocks.pathname = '/fr/onboarding';
    mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-7' } };

    renderGuard();

    await waitFor(() => expect(window.localStorage.getItem('lc_post_onboarding_return_v1')).toBeNull());
  });

  it('a signed-in person whose onboarding is done buys on the pricing page as before', async () => {
    mocks.pathname = '/fr/app/settings/pricing';
    mocks.authGuard = {
      isAuthenticated: true,
      isAuthChecking: false,
      isLoading: false,
      user: { sub: 'user-2' },
    };
    mocks.apiClient.get.mockResolvedValue({
      needsOnboarding: false,
      firstLogin: false,
      profileIncomplete: false,
      emailVerified: true,
    });

    renderGuard();

    await waitFor(() => expect(screen.getByText('app page')).toBeTruthy());
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('the information page stays open before onboarding: only pricing waits for it', async () => {
    mocks.pathname = '/fr/app/settings/information';
    mocks.authGuard = {
      isAuthenticated: true,
      isAuthChecking: false,
      isLoading: false,
      user: { sub: 'user-3' },
    };
    mocks.apiClient.get.mockResolvedValue({ needsOnboarding: true, emailVerified: false });

    renderGuard();
    await act(async () => {});

    expect(mocks.apiClient.get).not.toHaveBeenCalled();
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.getByText('app page')).toBeTruthy();
  });

  describe("a partner's offer page (/offer/<token>), a page to pay from outside the locale segment", () => {
    afterEach(() => {
      document.cookie = 'NEXT_LOCALE=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
      window.localStorage.clear();
    });

    it('an anonymous visitor gets the offer with no sign-in redirect', async () => {
      mocks.pathname = '/offer/Abc23XyZ9k';

      renderGuard();
      await act(async () => {});

      expect(mocks.replace).not.toHaveBeenCalled();
      expect(screen.getByText('app page')).toBeTruthy();
    });

    it('a fresh account goes through onboarding, in the app locale, and comes back to the offer with its choice', async () => {
      document.cookie = 'NEXT_LOCALE=fr; path=/';
      const query = '?plan=team&tier=3&cycle=yearly&continue=1';
      window.history.replaceState(null, '', `/offer/Abc23XyZ9k${query}`);
      mocks.pathname = '/offer/Abc23XyZ9k';
      mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-8' } };
      mocks.apiClient.get.mockResolvedValue({ needsOnboarding: true, firstLogin: true, profileIncomplete: true, emailVerified: false });

      renderGuard();

      expect(screen.queryByText('app page')).toBeNull();
      // Not /en/onboarding: the offer path carries no locale, the cookie does.
      await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/fr/onboarding'));
      const saved = JSON.parse(window.localStorage.getItem('lc_post_onboarding_return_v1') ?? '{}');
      expect(saved.path).toBe(`/offer/Abc23XyZ9k${query}`);
      expect(saved.owner).toBe('user-8');
    });

    it('without a locale cookie, onboarding opens in the default language', async () => {
      window.history.replaceState(null, '', '/offer/Abc23XyZ9k');
      mocks.pathname = '/offer/Abc23XyZ9k';
      mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-10' } };
      mocks.apiClient.get.mockResolvedValue({ needsOnboarding: true, emailVerified: false });

      renderGuard();

      await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/en/onboarding'));
    });

    it('an account whose onboarding is done gets the offer', async () => {
      mocks.pathname = '/offer/Abc23XyZ9k';
      mocks.authGuard = { isAuthenticated: true, isAuthChecking: false, isLoading: false, user: { sub: 'user-9' } };
      mocks.apiClient.get.mockResolvedValue({ needsOnboarding: false, firstLogin: false, profileIncomplete: false, emailVerified: true });

      renderGuard();

      await waitFor(() => expect(screen.getByText('app page')).toBeTruthy());
      expect(mocks.replace).not.toHaveBeenCalled();
    });

    it('the offer is held while the session is read, so it mounts once (its checkout resumes on mount)', () => {
      mocks.pathname = '/offer/Abc23XyZ9k';
      mocks.authGuard = { isAuthenticated: false, isAuthChecking: true, isLoading: true, user: null };

      renderGuard();

      expect(screen.queryByText('app page')).toBeNull();
      expect(screen.getByTestId('spinner')).toBeTruthy();
    });
  });
});

const guardClient = () => new QueryClient({
  defaultOptions: {
    queries: { retry: false },
    mutations: { retry: false },
  },
});
let currentClient = guardClient();

function guardTree() {
  return (
    <QueryClientProvider client={currentClient}>
      <FirstLoginGuard>
        <div>app page</div>
      </FirstLoginGuard>
    </QueryClientProvider>
  );
}

function renderGuard() {
  currentClient = guardClient();
  return render(guardTree());
}
