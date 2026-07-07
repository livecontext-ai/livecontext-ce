'use client';

import React, { useEffect, useState, useCallback, useRef } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { useQueryClient } from '@tanstack/react-query';
import { apiClient } from '@/lib/api';
import LoadingSpinner from '@/components/LoadingSpinner';
import { IS_CE } from '@/lib/edition';
import {
  CE_STATUS_API_PATH,
  localeFromPath,
  type OnboardingStatus,
} from './onboardingStatus';
import {
  buildLoginRedirectPath,
  isProtectedAppRoute,
} from './appRouteAuth';

const IS_EMBEDDED_AUTH = IS_CE;

type Props = { children: React.ReactNode };

/**
 * Current query string WITHOUT the leading '?', read lazily at effect time.
 *
 * Deliberately NOT `useSearchParams()`: calling that hook in a component that
 * wraps the whole tree (this guard sits in the root layout's Providers) makes
 * Next.js bail every statically-rendered page out to client-side rendering,
 * which used to strip the landing/marketing pages out of the server HTML
 * entirely. The query string is only needed inside the login-redirect effect,
 * where `window` is always available.
 */
function currentQueryString(): string {
  return window.location.search.replace(/^\?/, '');
}

/**
 * Guard component that redirects users to onboarding if they haven't completed it.
 *
 * This guard:
 * - Only checks onboarding status for authenticated users on /app/* routes
 * - Skips checking when already on /onboarding or /ce-setup page
 * - Caches the result in React Query to avoid repeated API calls
 * - Resets state when user changes (logout/login)
 * - CE mode: redirects to /ce-setup on first login for API provider configuration
 */
export default function FirstLoginGuard({ children }: Props) {
  if (IS_EMBEDDED_AUTH) {
    return <CeFirstLoginGuard>{children}</CeFirstLoginGuard>;
  }

  return <FirstLoginGuardInner>{children}</FirstLoginGuardInner>;
}

/**
 * CE mode: redirect ADMIN users to /ce-setup if the install has not yet been
 * bootstrapped. Single source of truth: `GET /api/ce/status` (public endpoint,
 * backed by the `auth.ce_install_state` singleton row). No client-side
 * localStorage signal - it leaked across installs sharing an origin (e.g.
 * localhost) and silently skipped the wizard on a fresh install.
 */
function CeFirstLoginGuard({ children }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, user, isLoading, isAuthChecking } = useAuthGuard();
  const [checked, setChecked] = useState(false);
  const protectedAppRoute = isProtectedAppRoute(pathname);

  useEffect(() => {
    if (isAuthChecking || isLoading || isAuthenticated || !protectedAppRoute) return;
    router.replace(buildLoginRedirectPath(pathname, currentQueryString()));
  }, [isAuthenticated, isAuthChecking, isLoading, pathname, protectedAppRoute, router]);

  useEffect(() => {
    // Wait for auth and apiClient token initialization before calling guarded APIs.
    if (isAuthChecking || (isAuthenticated && isLoading)) return;

    // Skip if already on guarded destination pages.
    if (pathname?.includes('/ce-setup') || pathname?.includes('/onboarding')) {
      setChecked(true);
      return;
    }

    // Only guard protected /app/* routes. Pricing, information, and public app
    // paths stay available without auth or onboarding.
    if (!protectedAppRoute) {
      setChecked(true);
      return;
    }

    // Must be authenticated to check setup
    if (!isAuthenticated) {
      setChecked(true);
      return;
    }

    setChecked(false);
    let cancelled = false;
    (async () => {
      const locale = localeFromPath(pathname);

      try {
        // Only the admin sees the setup wizard. Everyone else goes straight to
        // the app - CE does not force the cloud-style profile onboarding.
        const roles: string[] = (user as any)?.roles || [];
        const isAdmin = roles.includes('ADMIN');
        if (isAdmin) {
          try {
            const status = await apiClient.get<{ bootstrapped?: boolean }>(CE_STATUS_API_PATH);
            if (cancelled) return;

            // Single source of truth = the server install flag
            // (auth.ce_install_state). A not-yet-bootstrapped install always sends
            // the admin to the wizard. No client-side localStorage shortcut: it
            // leaked across installs sharing an origin (e.g. localhost) and silently
            // skipped the wizard on a fresh install.
            if (!status?.bootstrapped) {
              router.replace(`/${locale}/ce-setup`);
              return;
            }
          } catch (err) {
            if (cancelled) return;
            console.warn('[CeGuard] CE status request failed, failing open to the app:', err);
          }
        }

        // CE intentionally does NOT force the cloud-style profile onboarding.
        // A display name is already derived at registration (buildDisplayName),
        // and the profession/interests questionnaire is a cloud growth funnel
        // with no value on a self-hosted install - so once past the admin setup
        // wizard the user lands straight in the app.
        if (cancelled) return;
        setChecked(true);
      } catch (err) {
        if (cancelled) return;
        console.warn('[CeGuard] setup check failed, failing open:', err);
        setChecked(true);
      }
    })();
    return () => { cancelled = true; };
  }, [pathname, protectedAppRoute, router, isAuthenticated, isAuthChecking, isLoading, user]);

  if (
    protectedAppRoute
    && (isAuthChecking || isLoading || !isAuthenticated || !checked)
  ) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return <>{children}</>;
}

function FirstLoginGuardInner({ children }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, user, isLoading, isAuthChecking } = useAuthGuard();
  const queryClient = useQueryClient();
  const protectedAppRoute = isProtectedAppRoute(pathname);

  // Track the last checked user to reset state on user change
  const lastUserRef = useRef<string | null>(null);
  const checkingRef = useRef(false);
  // hasCheckedForApp: true only after we've verified onboarding status for /app/* routes
  const [hasCheckedForApp, setHasCheckedForApp] = useState(false);

  // Reset state when user changes
  useEffect(() => {
    const currentUser = user?.sub || null;
    if (lastUserRef.current !== currentUser) {
      lastUserRef.current = currentUser;
      setHasCheckedForApp(false);
      checkingRef.current = false;
    }
  }, [user?.sub]);

  const checkOnboardingStatus = useCallback(async () => {
    // Prevent concurrent checks
    if (checkingRef.current) return;

    // Only check protected /app/* routes. Public app routes are intentionally
    // available before signup and before onboarding is complete.
    if (!protectedAppRoute) return;

    // Skip if already on onboarding or ce-setup page
    if (pathname?.includes('/onboarding') || pathname?.includes('/ce-setup')) {
      setHasCheckedForApp(true);
      return;
    }

    checkingRef.current = true;

    try {
      const cacheKey = ['user', 'onboarding-status', user?.sub];

      // First try to read from React Query cache
      let status = queryClient.getQueryData<OnboardingStatus>(cacheKey);

      // If no cached data, fetch from API
      if (!status) {
        try {
          status = await apiClient.get<OnboardingStatus>('/auth-service/api/onboarding/status');

          // Cache the result for future use (5 minutes)
          if (status) {
            queryClient.setQueryData(cacheKey, status);
          }
        } catch (apiError) {
          console.warn('FirstLoginGuard: Failed to fetch onboarding status:', apiError);
          // If API fails, don't block the user - fail open for better UX
          setHasCheckedForApp(true);
          checkingRef.current = false;
          return;
        }
      }

      if (status) {
        const { needsOnboarding, firstLogin, profileIncomplete, emailVerified } = status;

        // Redirect to onboarding if needed (including unverified email)
        if (needsOnboarding || firstLogin || profileIncomplete || emailVerified === false) {
          const localeMatch = pathname?.match(/^\/([a-z]{2})\//);
          const locale = localeMatch ? localeMatch[1] : 'en';
          router.replace(`/${locale}/onboarding`);
          checkingRef.current = false;
          // Don't set hasCheckedForApp to true - we're redirecting
          return;
        }
      }

      setHasCheckedForApp(true);
    } catch (e) {
      console.warn('FirstLoginGuard: Failed to check user status:', e);
      setHasCheckedForApp(true);
    } finally {
      checkingRef.current = false;
    }
  }, [queryClient, user?.sub, pathname, protectedAppRoute, router]);

  useEffect(() => {
    if (isAuthChecking || isLoading || isAuthenticated || !protectedAppRoute) return;
    router.replace(buildLoginRedirectPath(pathname, currentQueryString()));
  }, [isAuthenticated, isAuthChecking, isLoading, pathname, protectedAppRoute, router]);

  useEffect(() => {
    if (!protectedAppRoute) {
      // Reset so the check runs fresh when user navigates to /app/*
      setHasCheckedForApp(false);
      return;
    }

    // Only run check when:
    // - On an /app/* route
    // - User is authenticated
    // - Auth is not loading
    // - We have a user sub
    // - We haven't checked yet for this route
    if (isAuthenticated && !isLoading && user?.sub && !hasCheckedForApp) {
      checkOnboardingStatus();
    }

    // If not authenticated, reset so it will check again on next login
    if (!isAuthenticated && !isLoading) {
      setHasCheckedForApp(false);
    }
  }, [protectedAppRoute, isAuthenticated, isLoading, user?.sub, hasCheckedForApp, checkOnboardingStatus]);

  // Block rendering of /app/* routes until onboarding check completes.
  // This prevents users from seeing or interacting with protected pages
  // before we confirm their email is verified and onboarding is done.
  if (
    protectedAppRoute
    && (isAuthChecking || isLoading || !isAuthenticated || !hasCheckedForApp)
  ) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-theme-primary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return <>{children}</>;
}
