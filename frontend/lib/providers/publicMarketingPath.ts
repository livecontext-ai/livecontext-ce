import { routing } from '@/i18n/routing';

// Public marketing/docs surfaces whose content MUST be server-rendered for
// SEO/GEO. On these paths the blocking auth UI in smart-providers (full-screen
// loading spinner + SessionGate) is skipped: during SSR `oidc.isLoading` is
// always true, so gating them used to ship spinner-only HTML for the entire
// public site (landing, /compare, /about, docs...), leaving nothing for
// crawlers that do not execute JavaScript. The auth context is still provided;
// public chrome (Sign in buttons) reads it and settles right after hydration.
const PUBLIC_MARKETING_PREFIXES = ['/compare', '/about', '/contact', '/legal', '/changelog', '/docs'];

export function isPublicMarketingPath(pathname: string | null): boolean {
  if (!pathname) return false;
  const firstSegment = pathname.split('/')[1] ?? '';
  const withoutLocale = (routing.locales as readonly string[]).includes(firstSegment)
    ? pathname.slice(firstSegment.length + 1) || '/'
    : pathname;
  if (withoutLocale === '/') return true;
  return PUBLIC_MARKETING_PREFIXES.some(
    (prefix) => withoutLocale === prefix || withoutLocale.startsWith(`${prefix}/`),
  );
}
