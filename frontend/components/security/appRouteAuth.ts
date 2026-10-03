import { locales } from '@/i18n/routing';
import { OFFER_PATH_RE } from '@/lib/partners/offerToken';
import { localeFromPath } from './onboardingStatus';

const LOCALE_PREFIX_RE = new RegExp(`^/(${locales.join('|')})(?=/|$)`);

const PUBLIC_APP_ROUTE_PREFIXES = [
  '/app/public',
  '/app/settings/pricing',
  '/app/settings/information',
  '/app/settings/cloud-account/recover',
  '/app/settings/cloud-link/recover',
] as const;

export function stripAppLocale(pathname: string | null): string {
  if (!pathname) return '';
  return pathname.replace(LOCALE_PREFIX_RE, '') || '/';
}

export function isAppRoute(pathname: string | null): boolean {
  const path = stripAppLocale(pathname);
  return path === '/app' || path.startsWith('/app/');
}

export function isPublicAppRoute(pathname: string | null): boolean {
  const path = stripAppLocale(pathname);
  return PUBLIC_APP_ROUTE_PREFIXES.some(
    (prefix) => path === prefix || path.startsWith(`${prefix}/`),
  );
}

/**
 * The pricing page: public to an anonymous visitor (they can see the prices and start a sign-up
 * from there), but a signed-in person who has not finished onboarding goes through it first, so
 * their email is verified, and a partner code they followed applies, before they pay.
 */
export function isPricingRoute(pathname: string | null): boolean {
  // The page itself only (it has no sub-route): the same path the post-onboarding return accepts.
  return stripAppLocale(pathname) === '/app/settings/pricing';
}

/**
 * A partner's offer page (/offer/<token>, outside the app and its locale segment): public like
 * pricing, and like pricing a page to pay from, so a signed-in person finishes onboarding first.
 */
export function isPartnerOfferRoute(pathname: string | null): boolean {
  return OFFER_PATH_RE.test(pathname ?? '');
}

/** The public pages a person can pay from: onboarding comes before them, not after. */
export function isCheckoutRoute(pathname: string | null): boolean {
  return isPricingRoute(pathname) || isPartnerOfferRoute(pathname);
}

export function isProtectedAppRoute(pathname: string | null): boolean {
  return isAppRoute(pathname) && !isPublicAppRoute(pathname);
}

export function buildLoginRedirectPath(pathname: string | null, queryString = ''): string {
  const basePath = pathname || '/app/chat';
  const normalizedQuery = queryString ? `?${queryString.replace(/^\?/, '')}` : '';
  const returnTo = `${basePath}${normalizedQuery}`;
  const locale = localeFromPath(pathname);
  return `/${locale}/login?returnTo=${encodeURIComponent(returnTo)}`;
}
