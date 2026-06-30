import { locales } from '@/i18n/routing';
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
